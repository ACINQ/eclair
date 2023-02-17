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
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Satoshi}
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.balance.{BalanceActor, ChannelsListener}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingBitcoinJsonRPCClient, BitcoinCoreClient, BitcoinJsonRPCAuthMethod}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.bitcoind.{OnchainPubkeyRefresher, ZmqWatcher}
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.WeakEntropyPool
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.db.Databases.FileBackup
import fr.acinq.eclair.db.FileBackupHandler.FileBackupParams
import fr.acinq.eclair.db.{Databases, DbEventHandler, FileBackupHandler}
import fr.acinq.eclair.io.{ClientSpawner, Peer, PendingChannelsRateLimiter, Server, Switchboard}
import fr.acinq.eclair.message.Postman
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.{AsyncPaymentTriggerer, PostRestartHtlcCleaner, Relayer}
import fr.acinq.eclair.payment.send.{Autoprobe, PaymentInitiator}
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

  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  implicit val sttpBackend = OkHttpFutureBackend()

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

  if (chain != "regtest") {
    // TODO: database format is WIP, we want to be able to squash changes and not support intermediate unreleased versions
    throw new RuntimeException("this unreleased version of Eclair only works on regtest")
  }

  val chaindir = new File(datadir, chain)
  chaindir.mkdirs()
  val nodeKeyManager = new LocalNodeKeyManager(nodeSeed, NodeParams.hashFromChain(chain))
  val channelKeyManager = new LocalChannelKeyManager(channelSeed, NodeParams.hashFromChain(chain))
  val instanceId = UUID.randomUUID()

  logger.info(s"instanceid=$instanceId")

  val databases = Databases.init(config.getConfig("db"), instanceId, chaindir, db)

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
  val feeratesPerKB = new AtomicReference[FeeratesPerKB](null)

  /**
   * This holds the current feerates, in satoshi-per-kw.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)

  val feeEstimator = new FeeEstimator {
    // @formatter:off
    override def getFeeratePerKb(target: Int): FeeratePerKB = feeratesPerKB.get().feePerBlock(target)
    override def getFeeratePerKw(target: Int): FeeratePerKw = feeratesPerKw.get().feePerBlock(target)
    override def getMempoolMinFeeratePerKw(): FeeratePerKw = feeratesPerKw.get().mempoolMinFee
    // @formatter:on
  }

  val nodeParams = NodeParams.makeNodeParams(config, instanceId, nodeKeyManager, channelKeyManager, initTor(), databases, blockHeight, feeEstimator, pluginParams)
  pluginParams.foreach(param => logger.info(s"using plugin=${param.name}"))

  val serverBindingAddress = new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port"))

  // early checks
  PortChecker.checkAvailable(serverBindingAddress)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  val bitcoin = {
    val wallet = {
      val name = config.getString("bitcoind.wallet")
      if (!name.isBlank) Some(name) else None
    }
    val rpcAuthMethod = config.getString("bitcoind.auth") match {
      case "safecookie" => BitcoinJsonRPCAuthMethod.readCookie(config.getString("bitcoind.cookie")) match {
        case Success(safeCookie) => safeCookie
        case Failure(exception) => throw new RuntimeException("could not read bitcoind cookie file", exception)
      }
      case "password" => BitcoinJsonRPCAuthMethod.UserPassword(config.getString("bitcoind.rpcuser"), config.getString("bitcoind.rpcpassword"))
    }

    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      rpcAuthMethod = rpcAuthMethod,
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"),
      wallet = wallet)
    val future = for {
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
      chainHash <- bitcoinClient.invoke("getblockhash", 0).map(_.extract[String]).map(s => ByteVector32.fromValidHex(s)).map(_.reverse)
      bitcoinVersion <- bitcoinClient.invoke("getnetworkinfo").map(json => json \ "version").map(_.extract[Int])
      unspentAddresses <- bitcoinClient.invoke("listunspent").recover { _ => if (wallet.isEmpty && wallets.length > 1) throw BitcoinDefaultWalletException(wallets) else throw BitcoinWalletNotLoadedException(wallet.getOrElse(""), wallets) }
        .collect { case JArray(values) =>
          values
            .filter(value => (value \ "spendable").extract[Boolean])
            .map(value => (value \ "address").extract[String])
        }
      _ <- chain match {
        case "mainnet" => bitcoinClient.invoke("getrawtransaction", "2157b554dcfda405233906e461ee593875ae4b1b97615872db6a25130ecc1dd6") // coinbase of #500000
        case "testnet" => bitcoinClient.invoke("getrawtransaction", "8f38a0dd41dc0ae7509081e262d791f8d53ed6f884323796d5ec7b0966dd3825") // coinbase of #1500000
        case "signet" => bitcoinClient.invoke("getrawtransaction", "ff1027486b628b2d160859205a3401fb2ee379b43527153b0b50a92c17ee7955") // coinbase of #5000
        case "regtest" => Future.successful(())
      }
    } yield (progress, ibd, chainHash, bitcoinVersion, unspentAddresses, blocks, headers)
    // blocking sanity checks
    val (progress, initialBlockDownload, chainHash, bitcoinVersion, unspentAddresses, blocks, headers) = await(future, 30 seconds, "bicoind did not respond after 30 seconds")
    assert(bitcoinVersion >= 230000, "Eclair requires Bitcoin Core 23.0 or higher")
    assert(chainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$chainHash)")
    assert(unspentAddresses.forall(address => !isPay2PubkeyHash(address)), "Your wallet contains non-segwit UTXOs. You must send those UTXOs to a bech32 address to use Eclair (check out our README for more details).")
    if (chainHash != Block.RegtestGenesisBlock.hash) {
      assert(!initialBlockDownload, s"bitcoind should be synchronized (initialblockdownload=$initialBlockDownload)")
      assert(progress > 0.999, s"bitcoind should be synchronized (progress=$progress)")
      assert(headers - blocks <= 1, s"bitcoind should be synchronized (headers=$headers blocks=$blocks)")
    }
    logger.info(s"current blockchain height=$blocks")
    blockHeight.set(blocks)
    bitcoinClient
  }

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
          mempoolMinFee = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.1008"))),
          block_1 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.1"))),
          blocks_2 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.2"))),
          blocks_6 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.6"))),
          blocks_12 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.12"))),
          blocks_36 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.36"))),
          blocks_72 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.72"))),
          blocks_144 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.144"))),
          blocks_1008 = FeeratePerKB(Satoshi(config.getLong("on-chain-fees.default-feerates.1008"))),
        )
        feeratesPerKB.set(confDefaultFeerates)
        feeratesPerKw.set(FeeratesPerKw(confDefaultFeerates))
        confDefaultFeerates
      }
      minFeeratePerByte = FeeratePerByte(Satoshi(config.getLong("on-chain-fees.min-feerate")))
      smoothFeerateWindow = config.getInt("on-chain-fees.smoothing-window")
      feeProvider = nodeParams.chainHash match {
        case Block.RegtestGenesisBlock.hash =>
          FallbackFeeProvider(ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte)
        case _ =>
          FallbackFeeProvider(SmoothFeeProvider(BitcoinCoreFeeProvider(bitcoin, defaultFeerates), smoothFeerateWindow) :: Nil, minFeeratePerByte)
      }
      _ = system.scheduler.scheduleWithFixedDelay(0 seconds, 10 minutes)(() => feeProvider.getFeerates.onComplete {
        case Success(feerates) =>
          feeratesPerKB.set(feerates)
          feeratesPerKw.set(FeeratesPerKw(feerates))
          channel.Monitoring.Metrics.LocalFeeratePerKw.withoutTags().update(feeratesPerKw.get.feePerBlock(nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget).toLong.toDouble)
          blockchain.Monitoring.Metrics.MempoolMinFeeratePerKw.withoutTags().update(feeratesPerKw.get.mempoolMinFee.toLong.toDouble)
          system.eventStream.publish(CurrentFeerates(feeratesPerKw.get))
          logger.info(s"current feeratesPerKB=${feeratesPerKB.get} feeratesPerKw=${feeratesPerKw.get}")
          feeratesRetrieved.trySuccess(Done)
        case Failure(exception) =>
          logger.warn(s"cannot retrieve feerates: ${exception.getMessage}")
          blockchain.Monitoring.Metrics.CannotRetrieveFeeratesCount.withoutTags().increment()
          feeratesRetrieved.tryFailure(CannotRetrieveFeerates)
      })
      _ <- feeratesRetrieved.future

      finalPubkey = new AtomicReference[PublicKey](null)
      pubkeyRefreshDelay = FiniteDuration(config.getDuration("bitcoind.final-pubkey-refresh-delay").getSeconds, TimeUnit.SECONDS)
      bitcoinClient = new BitcoinCoreClient(bitcoin) with OnchainPubkeyCache {
        val refresher: typed.ActorRef[OnchainPubkeyRefresher.Command] = system.spawn(Behaviors.supervise(OnchainPubkeyRefresher(this, finalPubkey, pubkeyRefreshDelay)).onFailure(typed.SupervisorStrategy.restart), name = "onchain-address-manager")

        override def getP2wpkhPubkey(renew: Boolean): PublicKey = {
          val key = finalPubkey.get()
          if (renew) refresher ! OnchainPubkeyRefresher.Renew
          key
        }
      }
      initialPubkey <- bitcoinClient.getP2wpkhPubkey()
      _ = finalPubkey.set(initialPubkey)

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
          new BitcoinCoreClient(new BatchingBitcoinJsonRPCClient(bitcoin))
        } else {
          new BitcoinCoreClient(bitcoin)
        }
        system.spawn(Behaviors.supervise(ZmqWatcher(nodeParams, blockHeight, watcherBitcoinClient)).onFailure(typed.SupervisorStrategy.resume), "watcher")
      }

      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher, Some(routerInitialized)), "router", SupervisorStrategy.Resume))
      routerTimeout = after(FiniteDuration(config.getDuration("router.init-timeout").getSeconds, TimeUnit.SECONDS), using = system.scheduler)(Future.failed(new RuntimeException("Router initialization timed out")))
      _ <- Future.firstCompletedOf(routerInitialized.future :: routerTimeout :: Nil)

      _ = bitcoinClient.getReceiveAddress().map(address => logger.info(s"initial wallet address=$address"))

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
      dbEventHandler = system.actorOf(SimpleSupervisor.props(DbEventHandler.props(nodeParams), "db-event-handler", SupervisorStrategy.Resume))
      register = system.actorOf(SimpleSupervisor.props(Register.props(), "register", SupervisorStrategy.Resume))
      paymentHandler = system.actorOf(SimpleSupervisor.props(PaymentHandler.props(nodeParams, register), "payment-handler", SupervisorStrategy.Resume))
      triggerer = system.spawn(Behaviors.supervise(AsyncPaymentTriggerer()).onFailure(typed.SupervisorStrategy.resume), name = "async-payment-triggerer")
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, router, register, paymentHandler, triggerer, Some(postRestartCleanUpInitialized)), "relayer", SupervisorStrategy.Resume))
      _ = relayer ! PostRestartHtlcCleaner.Init(channels)
      // Before initializing the switchboard (which re-connects us to the network) and the user-facing parts of the system,
      // we want to make sure the handler for post-restart broken HTLCs has finished initializing.
      _ <- postRestartCleanUpInitialized.future

      txPublisherFactory = Channel.SimpleTxPublisherFactory(nodeParams, watcher, bitcoinClient)
      channelFactory = Peer.SimpleChannelFactory(nodeParams, watcher, relayer, bitcoinClient, txPublisherFactory)
      pendingChannelsRateLimiter = system.spawn(Behaviors.supervise(PendingChannelsRateLimiter(nodeParams, router.toTyped, channels)).onFailure(typed.SupervisorStrategy.resume), name = "pending-channels-rate-limiter")
      peerFactory = Switchboard.SimplePeerFactory(nodeParams, bitcoinClient, channelFactory, pendingChannelsRateLimiter)

      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, peerFactory), "switchboard", SupervisorStrategy.Resume))
      _ = switchboard ! Switchboard.Init(channels)
      clientSpawner = system.actorOf(SimpleSupervisor.props(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, switchboard, router), "client-spawner", SupervisorStrategy.Restart))
      server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams.keyPair, nodeParams.peerConnectionConf, switchboard, router, serverBindingAddress, Some(tcpBound)), "server", SupervisorStrategy.Restart))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams, PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)), "payment-initiator", SupervisorStrategy.Restart))
      _ = for (i <- 0 until config.getInt("autoprobe-count")) yield system.actorOf(SimpleSupervisor.props(Autoprobe.props(nodeParams, router, paymentInitiator), s"payment-autoprobe-$i", SupervisorStrategy.Restart))

      _ = triggerer ! AsyncPaymentTriggerer.Start(switchboard.toTyped)
      balanceActor = system.spawn(BalanceActor(nodeParams.db, bitcoinClient, channelsListener, nodeParams.balanceCheckInterval), name = "balance-actor")

      postman = system.spawn(Behaviors.supervise(Postman(nodeParams, switchboard.toTyped)).onFailure(typed.SupervisorStrategy.restart), name = "postman")

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
               wallet: OnChainWallet with OnchainPubkeyCache)

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