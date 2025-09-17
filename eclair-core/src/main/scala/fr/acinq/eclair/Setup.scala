/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy, typed}
import akka.pattern.after
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, BlockId, ByteVector32, Satoshi, Script, ScriptElt, addressToPublicKeyScript}
import fr.acinq.eclair.NodeParams.hashFromChain
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.balance.{BalanceActor, ChannelsListener}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingBitcoinJsonRPCClient, BitcoinCoreClient, BitcoinJsonRPCAuthMethod}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.bitcoind.{OnChainAddressRefresher, ZmqWatcher}
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.WeakEntropyPool
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager, LocalOnChainKeyManager}
import fr.acinq.eclair.db.Databases.FileBackup
import fr.acinq.eclair.db.FileBackupHandler.FileBackupParams
import fr.acinq.eclair.db.{Databases, DbEventHandler, FileBackupHandler, PeerStorageCleaner}
import fr.acinq.eclair.io._
import fr.acinq.eclair.message.Postman
import fr.acinq.eclair.payment.offer.{DefaultOfferHandler, OfferManager}
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.{AsyncPaymentTriggerer, PostRestartHtlcCleaner, Relayer}
import fr.acinq.eclair.payment.send.{Autoprobe, PaymentInitiator}
import fr.acinq.eclair.reputation.ReputationRecorder
import fr.acinq.eclair.router._
import fr.acinq.eclair.tor.{Controller, TorProtocolHandler}
import fr.acinq.eclair.wire.protocol.NodeAddress
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JArray
import scodec.bits.ByteVector
import sttp.client3.okhttp.OkHttpFutureBackend

import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent._
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/**
 * Setup eclair from a data directory.
 *
 * Created by PM on 25/01/2016.
 *
 * @param datadir      directory where eclair-core will write/read its data.
 * @param pluginParams parameters for all configured plugins.
 * @param seeds_opt    optional seeds, if set eclair will use them instead of generating them and won't create a node_seed.dat and channel_seed.dat files.
 * @param db           optional databases to use, if not set eclair will create the necessary databases
 */
class Setup(val datadir: File,
            pluginParams: Seq[PluginParams],
            seeds_opt: Option[Seeds] = None,
            db: Option[Databases] = None)(implicit system: ActorSystem) extends Logging {

  implicit val timeout: Timeout = Timeout(30 seconds)
  implicit val formats: org.json4s.Formats = org.json4s.DefaultFormats
  implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val sttpBackend: sttp.client3.SttpBackend[Future, sttp.capabilities.WebSockets] = OkHttpFutureBackend()

  logger.info(s"hello!")
  logger.info(s"version=${Kit.getVersion} commit=${Kit.getCommit}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")
  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later
  randomGen.init()
  system.spawn(Behaviors.supervise(WeakEntropyPool(randomGen)).onFailure(typed.SupervisorStrategy.restart), "entropy-pool")

  // start a system-wide actor to collect and log important notifications for the node operator
  system.spawn(Behaviors.supervise(NotificationsLogger()).onFailure(typed.SupervisorStrategy.restart), "notifications-logger")

  datadir.mkdirs()
  val config = system.settings.config.getConfig("eclair")
  val Seeds(nodeSeed, channelSeed) = seeds_opt.getOrElse(NodeParams.getSeeds(datadir))
  val chain = config.getString("chain")
  val chainCheckTx = chain match {
    case "mainnet" => Some("2157b554dcfda405233906e461ee593875ae4b1b97615872db6a25130ecc1dd6") // coinbase of #500000
    case "testnet" => Some("8f38a0dd41dc0ae7509081e262d791f8d53ed6f884323796d5ec7b0966dd3825") // coinbase of #1500000
    case "testnet4" => Some("5c50d460b3b98ea0c70baa0f50d1f0cc6ffa553788b4a7e23918bcdd558828fa") // coinbase of #40000
    case "signet" => if (config.hasPath("bitcoind.signet-check-tx") && config.getString("bitcoind.signet-check-tx").nonEmpty) Some(config.getString("bitcoind.signet-check-tx")) else None
    case "regtest" => None
  }

  val chaindir = new File(datadir, chain)
  chaindir.mkdirs()
  val nodeKeyManager = LocalNodeKeyManager(nodeSeed, NodeParams.hashFromChain(chain))
  val channelKeyManager = LocalChannelKeyManager(channelSeed, NodeParams.hashFromChain(chain))

  /**
   * This counter holds the current blockchain height.
   * It is mainly used to calculate htlc expiries.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val blockHeight = new AtomicLong(0)

  /**
   * This holds the current feerates, in satoshi-per-kilobytes.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)

  val serverBindingAddress = new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port"))

  // early checks
  PortChecker.checkAvailable(serverBindingAddress)

  // load on onchain key manager if an `eclair-signer.conf` is found in Eclair's data directory
  val onChainKeyManager_opt = LocalOnChainKeyManager.load(datadir, NodeParams.hashFromChain(chain))

  val (bitcoin, bitcoinChainHash) = {
    val wallet = if (config.hasPath("bitcoind.wallet")) Some(config.getString("bitcoind.wallet")) else None
    val rpcAuthMethod = config.getString("bitcoind.auth") match {
      case "safecookie" => BitcoinJsonRPCAuthMethod.readCookie(config.getString("bitcoind.cookie")) match {
        case Success(safeCookie) => safeCookie
        case Failure(exception) => throw new RuntimeException("could not read bitcoind cookie file", exception)
      }
      case "password" => BitcoinJsonRPCAuthMethod.UserPassword(config.getString("bitcoind.rpcuser"), config.getString("bitcoind.rpcpassword"))
    }

    case class BitcoinStatus(version: Int, chainHash: BlockHash, initialBlockDownload: Boolean, verificationProgress: Double, blockCount: Long, headerCount: Long, unspentAddresses: List[String])

    def getBitcoinStatus(bitcoinClient: BasicBitcoinJsonRPCClient): Future[BitcoinStatus] = for {
      json <- bitcoinClient.invoke("getblockchaininfo").recover { case e => throw BitcoinRPCConnectionException(e) }
      // Make sure wallet support is enabled in bitcoind.
      wallets <- bitcoinClient.invoke("listwallets").recover { case e => throw BitcoinWalletDisabledException(e) }
        .collect {
          case JArray(values) => values.map(value => value.extract[String])
        }
      progress = (json \ "verificationprogress").extract[Double]
      ibd = (json \ "initialblockdownload").extract[Boolean]
      blocks = (json \ "blocks").extract[Long]
      headers = (json \ "headers").extract[Long]
      // NB: bitcoind confusingly returns the blockId instead of the blockHash.
      chainHash <- bitcoinClient.invoke("getblockhash", 0).map(_.extract[String]).map(s => BlockId(ByteVector32.fromValidHex(s))).map(BlockHash(_))
      bitcoinVersion <- bitcoinClient.invoke("getnetworkinfo").map(json => json \ "version").map(_.extract[Int])
      unspentAddresses <- bitcoinClient.invoke("listunspent").recover { _ => if (wallet.isEmpty && wallets.length > 1) throw BitcoinDefaultWalletException(wallets) else throw BitcoinWalletNotLoadedException(wallet.getOrElse(""), wallets) }
        .collect { case JArray(values) =>
          values
            .filter(value => (value \ "spendable").extract[Boolean])
            .map(value => (value \ "address").extract[String])
        }
      _ <- chainCheckTx match {
        case Some(txid) => bitcoinClient.invoke("getrawtransaction", txid)
        case None => Future.successful(())
      }
    } yield BitcoinStatus(bitcoinVersion, chainHash, ibd, progress, blocks, headers, unspentAddresses)

    def pollBitcoinStatus(bitcoinClient: BasicBitcoinJsonRPCClient): Future[BitcoinStatus] = {
      getBitcoinStatus(bitcoinClient).transformWith {
        case Success(status) => Future.successful(status)
        case Failure(e) =>
          logger.warn(s"failed to connect to bitcoind (${e.getMessage}), retrying...")
          after(5 seconds) {
            pollBitcoinStatus(bitcoinClient)
          }
      }
    }

    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      chainHash = hashFromChain(chain),
      rpcAuthMethod = rpcAuthMethod,
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"),
      wallet = wallet
    )
    val bitcoinStatus = if (config.getBoolean("bitcoind.wait-for-bitcoind-up")) {
      await(pollBitcoinStatus(bitcoinClient), 30 seconds, "bitcoind wasn't ready after 30 seconds")
    } else {
      await(getBitcoinStatus(bitcoinClient), 30 seconds, "bitcoind did not respond after 30 seconds")
    }
    logger.info(s"bitcoind version=${bitcoinStatus.version}")
    assert(bitcoinStatus.version >= 280100, "Eclair requires Bitcoin Core 28.1 or higher")
    bitcoinStatus.unspentAddresses.foreach { address =>
      val isSegwit = addressToPublicKeyScript(bitcoinStatus.chainHash, address).map(script => Script.isNativeWitnessScript(script)).getOrElse(false)
      assert(isSegwit, s"Your wallet contains non-segwit UTXOs (e.g. address=$address). You must send those UTXOs to a segwit address to use Eclair (check out our README for more details).")
    }
    if (bitcoinStatus.chainHash != Block.RegtestGenesisBlock.hash) {
      assert(!bitcoinStatus.initialBlockDownload, s"bitcoind should be synchronized (initialblockdownload=${bitcoinStatus.initialBlockDownload})")
      assert(bitcoinStatus.verificationProgress > 0.999, s"bitcoind should be synchronized (progress=${bitcoinStatus.verificationProgress})")
      assert(bitcoinStatus.headerCount - bitcoinStatus.blockCount <= 1, s"bitcoind should be synchronized (headers=${bitcoinStatus.headerCount} blocks=${bitcoinStatus.blockCount})")
    }
    logger.info(s"current blockchain height=${bitcoinStatus.blockCount}")
    blockHeight.set(bitcoinStatus.blockCount)
    (bitcoinClient, bitcoinStatus.chainHash)
  }

  val instanceId = UUID.randomUUID()
  logger.info(s"connecting to database with instanceId=$instanceId")
  val databases = Databases.init(config.getConfig("db"), instanceId, chaindir, db)

  val nodeParams = NodeParams.makeNodeParams(config, instanceId, nodeKeyManager, channelKeyManager, onChainKeyManager_opt, initTor(), databases, blockHeight, feeratesPerKw, pluginParams)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  assert(bitcoinChainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$bitcoinChainHash)")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")
  pluginParams.foreach(param => logger.info(s"using plugin=${param.name}"))

  def bootstrap: Future[Kit] = {
    for {
      _ <- Future.successful(true)
      feeratesRetrieved = Promise[Done]()
      zmqBlockConnected = Promise[Done]()
      zmqTxConnected = Promise[Done]()
      tcpBound = Promise[Done]()
      routerInitialized = Promise[Done]()
      postRestartCleanUpInitialized = Promise[Done]()
      channelsListenerReady = Promise[Done]()

      channels = DBChecker.checkChannelsDB(nodeParams)
      _ = DBChecker.checkNetworkDB(nodeParams)

      defaultFeerates = {
        val confDefaultFeerates = FeeratesPerKB(
          minimum = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.default-feerates.minimum"))).perKB,
          slow = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.default-feerates.slow"))).perKB,
          medium = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.default-feerates.medium"))).perKB,
          fast = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.default-feerates.fast"))).perKB,
          fastest = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.default-feerates.fastest"))).perKB,
        )
        feeratesPerKw.set(FeeratesPerKw(confDefaultFeerates))
        confDefaultFeerates
      }
      minFeeratePerByte = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.min-feerate")))
      feeProvider = nodeParams.chainHash match {
        case Block.RegtestGenesisBlock.hash | Block.SignetGenesisBlock.hash =>
          FallbackFeeProvider(ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte)
        case _ =>
          val smoothFeerateWindow = config.getInt("on-chain-fees.smoothing-window")
          FallbackFeeProvider(SmoothFeeProvider(BitcoinCoreFeeProvider(bitcoin, defaultFeerates), smoothFeerateWindow) :: Nil, minFeeratePerByte)
      }
      _ = system.scheduler.scheduleWithFixedDelay(0 seconds, 10 minutes)(() => feeProvider.getFeerates.onComplete {
        case Success(feeratesPerKB) =>
          feeratesPerKw.set(FeeratesPerKw(feeratesPerKB))
          blockchain.Monitoring.recordFeerates(feeratesPerKw.get(), provider = blockchain.Monitoring.Tags.Providers.BitcoinCore)
          system.eventStream.publish(CurrentFeerates.BitcoinCore(feeratesPerKw.get))
          logger.info(s"current bitcoin core feerates: min=${feeratesPerKB.minimum.perByte} slow=${feeratesPerKB.slow.perByte} medium=${feeratesPerKB.medium.perByte} fast=${feeratesPerKB.fast.perByte} fastest=${feeratesPerKB.fastest.perByte}")
          feeratesRetrieved.trySuccess(Done)
        case Failure(exception) =>
          logger.warn(s"cannot retrieve feerates: ${exception.getMessage}")
          blockchain.Monitoring.Metrics.CannotRetrieveFeeratesCount.withoutTags().increment()
          feeratesRetrieved.tryFailure(CannotRetrieveFeerates)
      })
      _ <- feeratesRetrieved.future

      finalPubkey = new AtomicReference[PublicKey](null)
      finalPubkeyScript = new AtomicReference[Seq[ScriptElt]](null)
      pubkeyRefreshDelay = FiniteDuration(config.getDuration("bitcoind.final-pubkey-refresh-delay").getSeconds, TimeUnit.SECONDS)
      // there are 3 possibilities regarding onchain key management:
      // 1) there is no `eclair-signer.conf` file in Eclair's data directory, Eclair will not manage Bitcoin core keys, and Eclair's API will not return bitcoin core descriptors. This is the default mode.
      // 2) there is an `eclair-signer.conf` file in Eclair's data directory, but the name of the wallet set in `eclair-signer.conf` does not match the `eclair.bitcoind.wallet` setting in `eclair.conf`.
      // Eclair will use the wallet set in `eclair.conf` and will not manage Bitcoin core keys (here we don't set an optional onchain key manager in our bitcoin client) BUT its API will return bitcoin core descriptors.
      // This is how you would create a new bitcoin wallet whose private keys are managed by Eclair.
      // 3) there is an `eclair-signer.conf` file in Eclair's data directory, and the name of the wallet set in `eclair-signer.conf` matches the `eclair.bitcoind.wallet` setting in `eclair.conf`.
      // Eclair will assume that this is a watch-only bitcoin wallet that has been created from descriptors generated by Eclair, and will manage its private keys, and here we pass the onchain key manager to our bitcoin client.
      bitcoinClient = new BitcoinCoreClient(bitcoin, nodeParams.liquidityAdsConfig.lockUtxos, if (bitcoin.wallet == onChainKeyManager_opt.map(_.walletName)) onChainKeyManager_opt else None) with OnChainPubkeyCache {
        val refresher: typed.ActorRef[OnChainAddressRefresher.Command] = system.spawn(Behaviors.supervise(OnChainAddressRefresher(this, finalPubkey, finalPubkeyScript, pubkeyRefreshDelay)).onFailure(typed.SupervisorStrategy.restart), name = "onchain-address-manager")

        override def getP2wpkhPubkey(renew: Boolean): PublicKey = {
          val key = finalPubkey.get()
          if (renew) refresher ! OnChainAddressRefresher.RenewPubkey
          key
        }

        override def getReceivePublicKeyScript(renew: Boolean): Seq[ScriptElt] = {
          val script = finalPubkeyScript.get()
          if (renew) refresher ! OnChainAddressRefresher.RenewPubkeyScript
          script
        }
      }
      _ = if (bitcoinClient.useEclairSigner) logger.info("using eclair to sign bitcoin core transactions")
      initialPubkey <- bitcoinClient.getP2wpkhPubkey()
      _ = finalPubkey.set(initialPubkey)
      // We use the default address type configured on the Bitcoin Core node.
      initialPubkeyScript <- bitcoinClient.getReceivePublicKeyScript(addressType_opt = None)
      _ = finalPubkeyScript.set(initialPubkeyScript)

      // If we started funding a transaction and restarted before signing it, we may have utxos that stay locked forever.
      // We want to do something about it: we can unlock them automatically, or let the node operator decide what to do.
      //
      // The only drawback that this may have is if we have funded and signed a funding transaction but didn't publish
      // it and we accidentally double-spend it after a restart. This shouldn't be an issue because:
      //  - locks are automatically removed when the transaction is published anyway
      //  - funding transactions are republished at startup if they aren't in the blockchain or in the mempool
      //  - funding transactions detect when they are double-spent and abort the channel creation
      //  - the only case where double-spending a funding transaction causes a loss of funds is when we accept a 0-conf
      //    channel and our peer double-spends it, but we should never accept 0-conf channels from peers we don't trust
      lockedUtxosBehavior = config.getString("bitcoind.startup-locked-utxos-behavior").toLowerCase
      _ <- bitcoinClient.listLockedOutpoints().flatMap { lockedOutpoints =>
        if (lockedOutpoints.isEmpty) {
          Future.successful(true)
        } else if (lockedUtxosBehavior == "unlock") {
          logger.info(s"unlocking utxos: ${lockedOutpoints.map(o => s"${o.txid}:${o.index}").mkString(", ")}")
          bitcoinClient.unlockOutpoints(lockedOutpoints.toSeq)
        } else if (lockedUtxosBehavior == "stop") {
          logger.warn(s"cannot start eclair with locked utxos: ${lockedOutpoints.map(o => s"${o.txid}:${o.index}").mkString(", ")}")
          NotificationsLogger.logFatalError(
            """aborting startup as configured strategy for locked utxos
              |
              |If you want eclair to automatically unlock utxos at startup, set 'eclair.bitcoind.startup-locked-utxos-behavior = "unlock"' in your eclair.conf and restart.
              |If you want to start eclair without unlocking those utxos, set 'eclair.bitcoind.startup-locked-utxos-behavior = "ignore"' in your eclair.conf and restart.
              |
              |Otherwise, run the following command to unlock them and restart eclair: 'bitcoin-cli lockunspent true'
              |""".stripMargin, new RuntimeException("cannot start with locked utxos"))
          throw new RuntimeException("cannot start with locked utxos: see notifications.log for detailed instructions to fix it")
        } else {
          logger.warn(s"ignoring locked utxos: ${lockedOutpoints.map(o => s"${o.txid}:${o.index}").mkString(", ")}")
          Future.successful(true)
        }
      }

      watcher = {
        system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqblock"), ZMQActor.Topics.HashBlock, Some(zmqBlockConnected))), "zmqblock", SupervisorStrategy.Restart))
        system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqtx"), ZMQActor.Topics.RawTx, Some(zmqTxConnected))), "zmqtx", SupervisorStrategy.Restart))
        val watcherBitcoinClient = if (config.getBoolean("bitcoind.batch-watcher-requests")) {
          new BitcoinCoreClient(new BatchingBitcoinJsonRPCClient(bitcoin), nodeParams.liquidityAdsConfig.lockUtxos)
        } else {
          new BitcoinCoreClient(bitcoin, nodeParams.liquidityAdsConfig.lockUtxos)
        }
        system.spawn(Behaviors.supervise(ZmqWatcher(nodeParams, blockHeight, watcherBitcoinClient)).onFailure(typed.SupervisorStrategy.resume), "watcher")
      }

      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher, Some(routerInitialized)), "router", SupervisorStrategy.Resume))
      routerTimeout = after(FiniteDuration(config.getDuration("router.init-timeout").getSeconds, TimeUnit.SECONDS), using = system.scheduler)(Future.failed(new RuntimeException("Router initialization timed out")))
      _ <- Future.firstCompletedOf(routerInitialized.future :: routerTimeout :: Nil)

      _ = bitcoinClient.getReceiveAddress().map(address => logger.info(s"initial address=$address for bitcoin wallet=${bitcoinClient.rpcClient.wallet.getOrElse("(default)")}"))
      channelsListener = system.spawn(ChannelsListener(channelsListenerReady), name = "channels-listener")
      _ <- channelsListenerReady.future

      _ = if (config.getBoolean("file-backup.enabled")) {
        nodeParams.db match {
          case fileBackup: FileBackup if config.getBoolean("file-backup.enabled") =>
            val fileBackupParams = FileBackupParams(
              interval = FiniteDuration(config.getDuration("file-backup.interval").getSeconds, TimeUnit.SECONDS),
              targetFile = new File(chaindir, config.getString("file-backup.target-file")),
              script_opt = if (config.hasPath("file-backup.notify-script")) Some(config.getString("file-backup.notify-script")) else None
            )
            system.spawn(Behaviors.supervise(FileBackupHandler(fileBackup, fileBackupParams)).onFailure(typed.SupervisorStrategy.restart), name = "backuphandler")
          case _ =>
            system.deadLetters
        }
      } else {
        logger.warn("database backup is disabled")
        system.deadLetters
      }
      _ = if (nodeParams.features.hasFeature(Features.ProvideStorage)) {
        system.spawn(Behaviors.supervise(PeerStorageCleaner(nodeParams.db.peers, nodeParams.peerStorageConfig)).onFailure(typed.SupervisorStrategy.restart), name = "peer-storage-cleaner")
      }
      dbEventHandler = system.actorOf(SimpleSupervisor.props(DbEventHandler.props(nodeParams), "db-event-handler", SupervisorStrategy.Resume))
      register = system.actorOf(SimpleSupervisor.props(Register.props(), "register", SupervisorStrategy.Resume))
      offerManager = system.spawn(Behaviors.supervise(OfferManager(nodeParams, paymentTimeout = 1 minute)).onFailure(typed.SupervisorStrategy.resume), name = "offer-manager")
      defaultOfferHandler = system.spawn(Behaviors.supervise(DefaultOfferHandler(nodeParams, router)).onFailure(typed.SupervisorStrategy.resume), name = "default-offer-handler")
      _ = for (offer <- nodeParams.db.offers.listOffers(onlyActive = true)) offerManager ! OfferManager.RegisterOffer(offer.offer, if (offer.pathId_opt.isEmpty) Some(nodeParams.privateKey) else None, offer.pathId_opt, defaultOfferHandler)
      paymentHandler = system.actorOf(SimpleSupervisor.props(PaymentHandler.props(nodeParams, register, offerManager), "payment-handler", SupervisorStrategy.Resume))
      triggerer = system.spawn(Behaviors.supervise(AsyncPaymentTriggerer()).onFailure(typed.SupervisorStrategy.resume), name = "async-payment-triggerer")
      peerReadyManager = system.spawn(Behaviors.supervise(PeerReadyManager()).onFailure(typed.SupervisorStrategy.restart), name = "peer-ready-manager")
      reputationRecorder_opt = if (nodeParams.relayParams.peerReputationConfig.enabled) {
        Some(system.spawn(Behaviors.supervise(ReputationRecorder(nodeParams.relayParams.peerReputationConfig)).onFailure(typed.SupervisorStrategy.resume), name = "reputation-recorder"))
      } else {
        None
      }
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, router, register, paymentHandler, reputationRecorder_opt, Some(postRestartCleanUpInitialized)), "relayer", SupervisorStrategy.Resume))
      _ = relayer ! PostRestartHtlcCleaner.Init(channels)
      // Before initializing the switchboard (which re-connects us to the network) and the user-facing parts of the system,
      // we want to make sure the handler for post-restart broken HTLCs has finished initializing.
      _ <- postRestartCleanUpInitialized.future

      txPublisherFactory = Channel.SimpleTxPublisherFactory(nodeParams, bitcoinClient)
      channelFactory = Peer.SimpleChannelFactory(nodeParams, watcher, relayer, bitcoinClient, txPublisherFactory)
      pendingChannelsRateLimiter = system.spawn(Behaviors.supervise(PendingChannelsRateLimiter(nodeParams, router.toTyped, channels)).onFailure(typed.SupervisorStrategy.resume), name = "pending-channels-rate-limiter")
      peerFactory = Switchboard.SimplePeerFactory(nodeParams, bitcoinClient, channelFactory, pendingChannelsRateLimiter, register, router.toTyped)

      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, peerFactory), "switchboard", SupervisorStrategy.Resume))
      _ = switchboard ! Switchboard.Init(channels)
      clientSpawner = system.actorOf(SimpleSupervisor.props(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, switchboard, router), "client-spawner", SupervisorStrategy.Restart))
      server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams.keyPair, nodeParams.peerConnectionConf, switchboard, router, serverBindingAddress, Some(tcpBound)), "server", SupervisorStrategy.Restart))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams, PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)), "payment-initiator", SupervisorStrategy.Restart))

      _ = for (i <- 0 until config.getInt("autoprobe-count")) yield system.actorOf(SimpleSupervisor.props(Autoprobe.props(nodeParams, router, paymentInitiator), s"payment-autoprobe-$i", SupervisorStrategy.Restart))

      balanceActor = system.spawn(BalanceActor(bitcoinClient, nodeParams.channelConf.minDepth, channelsListener, nodeParams.balanceCheckInterval), name = "balance-actor")

      postman = system.spawn(Behaviors.supervise(Postman(nodeParams, switchboard, router.toTyped, register, offerManager)).onFailure(typed.SupervisorStrategy.restart), name = "postman")

      kit = Kit(
        nodeParams = nodeParams,
        system = system,
        watcher = watcher,
        paymentHandler = paymentHandler,
        register = register,
        relayer = relayer,
        router = router,
        switchboard = switchboard,
        paymentInitiator = paymentInitiator,
        server = server,
        channelsListener = channelsListener,
        balanceActor = balanceActor,
        postman = postman,
        offerManager = offerManager,
        defaultOfferHandler = defaultOfferHandler,
        wallet = bitcoinClient)

      zmqBlockTimeout = after(5 seconds, using = system.scheduler)(Future.failed(BitcoinZMQConnectionTimeoutException))
      zmqTxTimeout = after(5 seconds, using = system.scheduler)(Future.failed(BitcoinZMQConnectionTimeoutException))
      tcpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("server.port"))))

      _ <- Future.firstCompletedOf(zmqBlockConnected.future :: zmqBlockTimeout :: Nil)
      _ <- Future.firstCompletedOf(zmqTxConnected.future :: zmqTxTimeout :: Nil)
      _ <- Future.firstCompletedOf(tcpBound.future :: tcpTimeout :: Nil)
    } yield kit

  }

  private def await[T](awaitable: Awaitable[T], atMost: Duration, messageOnTimeout: => String): T = try {
    Await.result(awaitable, atMost)
  } catch {
    case e: TimeoutException =>
      logger.error(messageOnTimeout)
      throw e
  }

  private def initTor(): Option[NodeAddress] = {
    if (config.getBoolean("tor.enabled")) {
      val promiseTorAddress = Promise[NodeAddress]()
      val auth = config.getString("tor.auth") match {
        case "password" => TorProtocolHandler.Password(config.getString("tor.password"))
        case "safecookie" => TorProtocolHandler.SafeCookie()
      }
      val protocolHandlerProps = TorProtocolHandler.props(
        authentication = auth,
        privateKeyPath = new File(datadir, config.getString("tor.private-key-file")).toPath,
        virtualPort = config.getInt("server.port"),
        targets = config.getStringList("tor.targets").asScala.toSeq,
        onionAdded = Some(promiseTorAddress))

      val controller = system.actorOf(SimpleSupervisor.props(Controller.props(
        address = new InetSocketAddress(config.getString("tor.host"), config.getInt("tor.port")),
        protocolHandlerProps = protocolHandlerProps), "tor", SupervisorStrategy.Stop))

      val torAddress = await(promiseTorAddress.future, 30 seconds, "tor did not respond after 30 seconds")
      logger.info(s"Tor address $torAddress")
      Some(torAddress)
    } else {
      None
    }
  }

}

object Setup {

  final case class Seeds(nodeSeed: ByteVector, channelSeed: ByteVector)

}

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: typed.ActorRef[ZmqWatcher.Command],
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               server: ActorRef,
               channelsListener: typed.ActorRef[ChannelsListener.Command],
               balanceActor: typed.ActorRef[BalanceActor.Command],
               postman: typed.ActorRef[Postman.Command],
               offerManager: typed.ActorRef[OfferManager.Command],
               defaultOfferHandler: typed.ActorRef[OfferManager.HandlerCommand],
               wallet: OnChainWallet with OnChainPubkeyCache)

object Kit {

  def getVersionLong: String = s"$getVersion-$getCommit"

  def getVersion: String = getClass.getPackage.getImplementationVersion

  def getCommit: String = Option(getClass.getPackage.getSpecificationVersion).map(_.take(7)).getOrElse("null")

}

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case class BitcoinRPCConnectionException(e: Throwable) extends RuntimeException("could not connect to bitcoind using json-rpc", e)

case class BitcoinWalletDisabledException(e: Throwable) extends RuntimeException("bitcoind wallet support disabled", e)

case class BitcoinDefaultWalletException(loaded: List[String]) extends RuntimeException(s"no bitcoind wallet configured, but multiple wallets loaded: ${loaded.map("\"" + _ + "\"").mkString("[", ",", "]")}")

case class BitcoinWalletNotLoadedException(wallet: String, loaded: List[String]) extends RuntimeException(s"configured wallet \"$wallet\" not in the set of loaded bitcoind wallets: ${loaded.map("\"" + _ + "\"").mkString("[", ",", "]")}")

case object EmptyAPIPasswordException extends RuntimeException("must set a password for the json-rpc api")

case object IncompatibleDBException extends RuntimeException("database is not compatible with this version of eclair")

case object IncompatibleNetworkDBException extends RuntimeException("network database is not compatible with this version of eclair")

case class InvalidChannelSeedException(channelId: ByteVector32) extends RuntimeException(s"channel seed has been modified for channel $channelId")