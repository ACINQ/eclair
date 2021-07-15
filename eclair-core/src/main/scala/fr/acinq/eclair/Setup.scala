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
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy, typed}
import akka.pattern.after
import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi}
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.balance.{BalanceActor, ChannelsListener}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, ZmqWatcher}
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.{Channel, Register}
import fr.acinq.eclair.crypto.WeakEntropyPool
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.db.Databases.FileBackup
import fr.acinq.eclair.db.FileBackupHandler.FileBackupParams
import fr.acinq.eclair.db.{Databases, DbEventHandler, FileBackupHandler}
import fr.acinq.eclair.io.{ClientSpawner, Peer, Server, Switchboard}
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.send.{Autoprobe, PaymentInitiator}
import fr.acinq.eclair.router._
import fr.acinq.eclair.tor.TorProtocolHandler.OnionServiceVersion
import fr.acinq.eclair.tor.{Controller, TorProtocolHandler}
import fr.acinq.eclair.wire.protocol.NodeAddress
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JArray
import scodec.bits.ByteVector

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

  datadir.mkdirs()
  val config = system.settings.config.getConfig("eclair")
  val Seeds(nodeSeed, channelSeed) = seeds_opt.getOrElse(NodeParams.getSeeds(datadir))
  val chain = config.getString("chain")
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
  val blockCount = new AtomicLong(0)

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
    // @formatter:on
  }

  val nodeParams = NodeParams.makeNodeParams(config, instanceId, nodeKeyManager, channelKeyManager, initTor(), databases, blockCount, feeEstimator, pluginParams)
  pluginParams.foreach(param => logger.info(s"using plugin=${param.name}"))

  val serverBindingAddress = new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port"))

  // early checks
  DBCompatChecker.checkDBCompatibility(nodeParams)
  DBCompatChecker.checkNetworkDBCompatibility(nodeParams)
  PortChecker.checkAvailable(serverBindingAddress)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  val bitcoin = {
    val wallet = {
      val name = config.getString("bitcoind.wallet")
      if (!name.isBlank) Some(name) else None
    }
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"),
      wallet = wallet)
    val future = for {
      json <- bitcoinClient.invoke("getblockchaininfo").recover { case e => throw BitcoinRPCConnectionException(e) }
      // Make sure wallet support is enabled in bitcoind.
      _ <- bitcoinClient.invoke("getbalance").recover { case e => throw BitcoinWalletDisabledException(e) }
      progress = (json \ "verificationprogress").extract[Double]
      ibd = (json \ "initialblockdownload").extract[Boolean]
      blocks = (json \ "blocks").extract[Long]
      headers = (json \ "headers").extract[Long]
      chainHash <- bitcoinClient.invoke("getblockhash", 0).map(_.extract[String]).map(s => ByteVector32.fromValidHex(s)).map(_.reverse)
      bitcoinVersion <- bitcoinClient.invoke("getnetworkinfo").map(json => json \ "version").map(_.extract[Int])
      unspentAddresses <- bitcoinClient.invoke("listunspent").collect { case JArray(values) =>
        values
          .filter(value => (value \ "spendable").extract[Boolean])
          .map(value => (value \ "address").extract[String])
      }
      _ <- chain match {
        case "mainnet" => bitcoinClient.invoke("getrawtransaction", "2157b554dcfda405233906e461ee593875ae4b1b97615872db6a25130ecc1dd6") // coinbase of #500000
        case "testnet" => bitcoinClient.invoke("getrawtransaction", "8f38a0dd41dc0ae7509081e262d791f8d53ed6f884323796d5ec7b0966dd3825") // coinbase of #1500000
        case "regtest" => Future.successful(())
      }
    } yield (progress, ibd, chainHash, bitcoinVersion, unspentAddresses, blocks, headers)
    // blocking sanity checks
    val (progress, initialBlockDownload, chainHash, bitcoinVersion, unspentAddresses, blocks, headers) = await(future, 30 seconds, "bicoind did not respond after 30 seconds")
    assert(bitcoinVersion >= 180000, "Eclair requires Bitcoin Core 0.18.0 or higher")
    assert(chainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$chainHash)")
    if (chainHash != Block.RegtestGenesisBlock.hash) {
      assert(unspentAddresses.forall(address => !isPay2PubkeyHash(address)), "Your wallet contains non-segwit UTXOs. You must send those UTXOs to a bech32 address to use Eclair (check out our README for more details).")
    }
    assert(!initialBlockDownload, s"bitcoind should be synchronized (initialblockdownload=$initialBlockDownload)")
    assert(progress > 0.999, s"bitcoind should be synchronized (progress=$progress)")
    assert(headers - blocks <= 1, s"bitcoind should be synchronized (headers=$headers blocks=$blocks)")
    logger.info(s"current blockchain height=$blocks")
    blockCount.set(blocks)
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
          new FallbackFeeProvider(new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte)
        case _ =>
          new FallbackFeeProvider(new SmoothFeeProvider(new BitcoinCoreFeeProvider(bitcoin, defaultFeerates), smoothFeerateWindow) :: Nil, minFeeratePerByte)
      }
      _ = system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.onComplete {
        case Success(feerates) =>
          feeratesPerKB.set(feerates)
          feeratesPerKw.set(FeeratesPerKw(feerates))
          channel.Monitoring.Metrics.LocalFeeratePerKw.withoutTags().update(feeratesPerKw.get.feePerBlock(nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget).toLong)
          blockchain.Monitoring.Metrics.MempoolMinFeeratePerKw.withoutTags().update(feeratesPerKw.get.mempoolMinFee.toLong)
          system.eventStream.publish(CurrentFeerates(feeratesPerKw.get))
          logger.info(s"current feeratesPerKB=${feeratesPerKB.get} feeratesPerKw=${feeratesPerKw.get}")
          feeratesRetrieved.trySuccess(Done)
        case Failure(exception) =>
          logger.warn(s"cannot retrieve feerates: ${exception.getMessage}")
          blockchain.Monitoring.Metrics.CannotRetrieveFeeratesCount.withoutTags().increment()
          feeratesRetrieved.tryFailure(CannotRetrieveFeerates)
      })
      _ <- feeratesRetrieved.future

      extendedBitcoinClient = new ExtendedBitcoinClient(new BatchingBitcoinJsonRPCClient(bitcoin))
      watcher = {
        system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqblock"), ZMQActor.Topics.RawBlock, Some(zmqBlockConnected))), "zmqblock", SupervisorStrategy.Restart))
        system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqtx"), ZMQActor.Topics.RawTx, Some(zmqTxConnected))), "zmqtx", SupervisorStrategy.Restart))
        system.spawn(Behaviors.supervise(ZmqWatcher(nodeParams.chainHash, blockCount, extendedBitcoinClient)).onFailure(typed.SupervisorStrategy.resume), "watcher")
      }

      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher, Some(routerInitialized)), "router", SupervisorStrategy.Resume))
      routerTimeout = after(FiniteDuration(config.getDuration("router.init-timeout").getSeconds, TimeUnit.SECONDS), using = system.scheduler)(Future.failed(new RuntimeException("Router initialization timed out")))
      _ <- Future.firstCompletedOf(routerInitialized.future :: routerTimeout :: Nil)

      wallet = new BitcoinCoreWallet(bitcoin)
      _ = wallet.getReceiveAddress().map(address => logger.info(s"initial wallet address=$address"))

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
      register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
      paymentHandler = system.actorOf(SimpleSupervisor.props(PaymentHandler.props(nodeParams, register), "payment-handler", SupervisorStrategy.Resume))
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, router, register, paymentHandler, Some(postRestartCleanUpInitialized)), "relayer", SupervisorStrategy.Resume))
      // Before initializing the switchboard (which re-connects us to the network) and the user-facing parts of the system,
      // we want to make sure the handler for post-restart broken HTLCs has finished initializing.
      _ <- postRestartCleanUpInitialized.future

      txPublisherFactory = Channel.SimpleTxPublisherFactory(nodeParams, watcher, extendedBitcoinClient)
      channelFactory = Peer.SimpleChannelFactory(nodeParams, watcher, relayer, wallet, txPublisherFactory)
      peerFactory = Switchboard.SimplePeerFactory(nodeParams, wallet, channelFactory)

      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, peerFactory), "switchboard", SupervisorStrategy.Resume))
      clientSpawner = system.actorOf(SimpleSupervisor.props(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, switchboard, router), "client-spawner", SupervisorStrategy.Restart))
      server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams.keyPair, nodeParams.peerConnectionConf, switchboard, router, serverBindingAddress, Some(tcpBound)), "server", SupervisorStrategy.Restart))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams, PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)), "payment-initiator", SupervisorStrategy.Restart))
      _ = for (i <- 0 until config.getInt("autoprobe-count")) yield system.actorOf(SimpleSupervisor.props(Autoprobe.props(nodeParams, router, paymentInitiator), s"payment-autoprobe-$i", SupervisorStrategy.Restart))

      balanceActor = system.spawn(BalanceActor(nodeParams.db, extendedBitcoinClient, channelsListener, nodeParams.balanceCheckInterval), name = "balance-actor")

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
        wallet = wallet)

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
        version = OnionServiceVersion(config.getString("tor.protocol")),
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
               wallet: EclairWallet)

object Kit {

  def getVersionLong: String = s"$getVersion-$getCommit"

  def getVersion: String = getClass.getPackage.getImplementationVersion

  def getCommit: String = Option(getClass.getPackage.getSpecificationVersion).map(_.take(7)).getOrElse("null")

}

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case class BitcoinRPCConnectionException(e: Throwable) extends RuntimeException("could not connect to bitcoind using json-rpc", e)

case class BitcoinWalletDisabledException(e: Throwable) extends RuntimeException("bitcoind wallet not available", e)

case object EmptyAPIPasswordException extends RuntimeException("must set a password for the json-rpc api")

case object IncompatibleDBException extends RuntimeException("database is not compatible with this version of eclair")

case object IncompatibleNetworkDBException extends RuntimeException("network database is not compatible with this version of eclair")