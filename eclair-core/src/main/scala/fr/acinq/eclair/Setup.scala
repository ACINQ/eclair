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

import java.io.File
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.pattern.after
import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.NodeParams.{BITCOIND, ELECTRUM}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, ZmqWatcher}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.blockchain.electrum._
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.{BackupHandler, Databases}
import fr.acinq.eclair.io.{Authenticator, Server, Switchboard}
import fr.acinq.eclair.payment.Auditor
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.{CommandBuffer, Relayer}
import fr.acinq.eclair.payment.send.{Autoprobe, PaymentInitiator}
import fr.acinq.eclair.router._
import fr.acinq.eclair.tor.TorProtocolHandler.OnionServiceVersion
import fr.acinq.eclair.tor.{Controller, TorProtocolHandler}
import fr.acinq.eclair.wire.NodeAddress
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JArray
import scodec.bits.ByteVector

import scala.concurrent._
import scala.concurrent.duration._

/**
 * Setup eclair from a data directory.
 *
 * Created by PM on 25/01/2016.
 *
 * @param datadir          directory where eclair-core will write/read its data.
 * @param overrideDefaults use this parameter to programmatically override the node configuration .
 * @param seed_opt         optional seed, if set eclair will use it instead of generating one and won't create a seed.dat file.
 */
class Setup(datadir: File,
            overrideDefaults: Config = ConfigFactory.empty(),
            seed_opt: Option[ByteVector] = None,
            db: Option[Databases] = None)(implicit system: ActorSystem) extends Logging {

  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  implicit val sttpBackend = OkHttpFutureBackend()

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")
  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  datadir.mkdirs()
  val appConfig = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val config = appConfig.getConfig("eclair")
  val seed = seed_opt.getOrElse(NodeParams.getSeed(datadir))
  val chain = config.getString("chain")
  val chaindir = new File(datadir, chain)
  val keyManager = new LocalKeyManager(seed, NodeParams.makeChainHash(chain))

  val database = db match {
    case Some(d) => d
    case None => Databases.sqliteJDBC(chaindir)
  }

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
    override def getFeeratePerKb(target: Int): Long = feeratesPerKB.get().feePerBlock(target)
    override def getFeeratePerKw(target: Int): Long = feeratesPerKw.get().feePerBlock(target)
  }

  val nodeParams = NodeParams.makeNodeParams(config, keyManager, initTor(), database, blockCount, feeEstimator)

  val serverBindingAddress = new InetSocketAddress(
    config.getString("server.binding-ip"),
    config.getInt("server.port"))

  // early checks
  DBCompatChecker.checkDBCompatibility(nodeParams)
  DBCompatChecker.checkNetworkDBCompatibility(nodeParams)
  PortChecker.checkAvailable(serverBindingAddress)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  val bitcoin = nodeParams.watcherType match {
    case BITCOIND =>
      val bitcoinClient = new BasicBitcoinJsonRPCClient(
        user = config.getString("bitcoind.rpcuser"),
        password = config.getString("bitcoind.rpcpassword"),
        host = config.getString("bitcoind.host"),
        port = config.getInt("bitcoind.rpcport"))
      implicit val timeout = Timeout(30 seconds)
      implicit val formats = org.json4s.DefaultFormats
      val future = for {
        json <- bitcoinClient.invoke("getblockchaininfo").recover { case _ => throw BitcoinRPCConnectionException }
        // Make sure wallet support is enabled in bitcoind.
        _ <- bitcoinClient.invoke("getbalance").recover { case _ => throw BitcoinWalletDisabledException }
        progress = (json \ "verificationprogress").extract[Double]
        ibd = (json \ "initialblockdownload").extract[Boolean]
        blocks = (json \ "blocks").extract[Long]
        headers = (json \ "headers").extract[Long]
        chainHash <- bitcoinClient.invoke("getblockhash", 0).map(_.extract[String]).map(s => ByteVector32.fromValidHex(s)).map(_.reverse)
        bitcoinVersion <- bitcoinClient.invoke("getnetworkinfo").map(json => (json \ "version")).map(_.extract[Int])
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
      assert(bitcoinVersion >= 170000, "Eclair requires Bitcoin Core 0.17.0 or higher")
      assert(chainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$chainHash)")
      if (chainHash != Block.RegtestGenesisBlock.hash) {
        assert(unspentAddresses.forall(address => !isPay2PubkeyHash(address)), "Your wallet contains non-segwit UTXOs. You must send those UTXOs to a p2sh-segwit or bech32 address to use Eclair (check out our README for more details).")
      }
      assert(!initialBlockDownload, s"bitcoind should be synchronized (initialblockdownload=$initialBlockDownload)")
      assert(progress > 0.999, s"bitcoind should be synchronized (progress=$progress)")
      assert(headers - blocks <= 1, s"bitcoind should be synchronized (headers=$headers blocks=$blocks)")
      Bitcoind(bitcoinClient)
    case ELECTRUM =>
      val addresses = config.hasPath("electrum") match {
        case true =>
          val host = config.getString("electrum.host")
          val port = config.getInt("electrum.port")
          val address = InetSocketAddress.createUnresolved(host, port)
          val ssl = config.getString("electrum.ssl") match {
              case _ if address.getHostName.endsWith(".onion") => SSL.OFF // Tor already adds end-to-end encryption, adding TLS on top doesn't add anything
              case "off" => SSL.OFF
              case "loose" => SSL.LOOSE
              case _ => SSL.STRICT // strict mode is the default when we specify a custom electrum server, we don't want to be MITMed
          }

          logger.info(s"override electrum default with server=$address ssl=$ssl")
          Set(ElectrumServerAddress(address, ssl))
        case false =>
          val (addressesFile, sslEnabled) = nodeParams.chainHash match {
            case Block.RegtestGenesisBlock.hash => ("/electrum/servers_regtest.json", false) // in regtest we connect in plaintext
            case Block.TestnetGenesisBlock.hash => ("/electrum/servers_testnet.json", true)
            case Block.LivenetGenesisBlock.hash => ("/electrum/servers_mainnet.json", true)
          }
          val stream = classOf[Setup].getResourceAsStream(addressesFile)
          ElectrumClientPool.readServerAddresses(stream, sslEnabled)
      }
      val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClientPool(blockCount, addresses, nodeParams.socksProxy_opt)), "electrum-client", SupervisorStrategy.Resume))
      Electrum(electrumClient)
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

      defaultFeerates = {
        val confDefaultFeerates = FeeratesPerKB(
          block_1 = config.getLong("on-chain-fees.default-feerates.1"),
          blocks_2 = config.getLong("on-chain-fees.default-feerates.2"),
          blocks_6 = config.getLong("on-chain-fees.default-feerates.6"),
          blocks_12 = config.getLong("on-chain-fees.default-feerates.12"),
          blocks_36 = config.getLong("on-chain-fees.default-feerates.36"),
          blocks_72 = config.getLong("on-chain-fees.default-feerates.72"),
          blocks_144 = config.getLong("on-chain-fees.default-feerates.144")
        )
        feeratesPerKB.set(confDefaultFeerates)
        feeratesPerKw.set(FeeratesPerKw(confDefaultFeerates))
        confDefaultFeerates
      }
      minFeeratePerByte = config.getLong("min-feerate")
      smoothFeerateWindow = config.getInt("smooth-feerate-window")
      readTimeout = FiniteDuration(config.getDuration("feerate-provider-timeout", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
      feeProvider = (nodeParams.chainHash, bitcoin) match {
        case (Block.RegtestGenesisBlock.hash, _) => new FallbackFeeProvider(new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte)
        case (_, Bitcoind(bitcoinClient)) =>
          new FallbackFeeProvider(new SmoothFeeProvider(new BitcoinCoreFeeProvider(bitcoinClient, defaultFeerates), smoothFeerateWindow) :: new SmoothFeeProvider(new BitgoFeeProvider(nodeParams.chainHash, readTimeout), smoothFeerateWindow) :: new SmoothFeeProvider(new EarnDotComFeeProvider(readTimeout), smoothFeerateWindow) :: new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte) // order matters!
        case _ =>
          new FallbackFeeProvider(new SmoothFeeProvider(new BitgoFeeProvider(nodeParams.chainHash, readTimeout), smoothFeerateWindow) :: new SmoothFeeProvider(new EarnDotComFeeProvider(readTimeout), smoothFeerateWindow) :: new ConstantFeeProvider(defaultFeerates) :: Nil, minFeeratePerByte) // order matters!
      }
      _ = system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.map {
        case feerates: FeeratesPerKB =>
          feeratesPerKB.set(feerates)
          feeratesPerKw.set(FeeratesPerKw(feerates))
          system.eventStream.publish(CurrentFeerates(feeratesPerKw.get))
          logger.info(s"current feeratesPerKB=${feeratesPerKB.get()} feeratesPerKw=${feeratesPerKw.get()}")
          feeratesRetrieved.trySuccess(Done)
      })
      _ <- feeratesRetrieved.future

      watcher = bitcoin match {
        case Bitcoind(bitcoinClient) =>
          system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqblock"), Some(zmqBlockConnected))), "zmqblock", SupervisorStrategy.Restart))
          system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmqtx"), Some(zmqTxConnected))), "zmqtx", SupervisorStrategy.Restart))
          system.actorOf(SimpleSupervisor.props(ZmqWatcher.props(blockCount, new ExtendedBitcoinClient(new BatchingBitcoinJsonRPCClient(bitcoinClient))), "watcher", SupervisorStrategy.Resume))
        case Electrum(electrumClient) =>
          zmqBlockConnected.success(Done)
          zmqTxConnected.success(Done)
          system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(blockCount, electrumClient)), "watcher", SupervisorStrategy.Resume))
      }

      router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher, Some(routerInitialized)), "router", SupervisorStrategy.Resume))
      routerTimeout = after(FiniteDuration(config.getDuration("router.init-timeout").getSeconds, TimeUnit.SECONDS), using = system.scheduler)(Future.failed(new RuntimeException("Router initialization timed out")))
      _ <- Future.firstCompletedOf(routerInitialized.future :: routerTimeout :: Nil)

      wallet = bitcoin match {
        case Bitcoind(bitcoinClient) => new BitcoinCoreWallet(bitcoinClient)
        case Electrum(electrumClient) =>
          val sqlite = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "wallet.sqlite")}")
          val walletDb = new SqliteWalletDb(sqlite)
          val electrumWallet = system.actorOf(ElectrumWallet.props(seed, electrumClient, ElectrumWallet.WalletParameters(nodeParams.chainHash, walletDb)), "electrum-wallet")
          implicit val timeout = Timeout(30 seconds)
          new ElectrumEclairWallet(electrumWallet, nodeParams.chainHash)
      }
      _ = wallet.getFinalAddress.map {
        case address => logger.info(s"initial wallet address=$address")
      }
      // do not change the name of this actor. it is used in the configuration to specify a custom bounded mailbox

      backupHandler = system.actorOf(SimpleSupervisor.props(
        BackupHandler.props(
          nodeParams.db,
          new File(chaindir, "eclair.sqlite.bak"),
          if (config.hasPath("backup-notify-script")) Some(config.getString("backup-notify-script")) else None
        ), "backuphandler", SupervisorStrategy.Resume))
      audit = system.actorOf(SimpleSupervisor.props(Auditor.props(nodeParams), "auditor", SupervisorStrategy.Resume))
      register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
      commandBuffer = system.actorOf(SimpleSupervisor.props(Props(new CommandBuffer(nodeParams, register)), "command-buffer", SupervisorStrategy.Resume))
      paymentHandler = system.actorOf(SimpleSupervisor.props(PaymentHandler.props(nodeParams, commandBuffer), "payment-handler", SupervisorStrategy.Resume))
      relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, router, register, commandBuffer, paymentHandler, Some(postRestartCleanUpInitialized)), "relayer", SupervisorStrategy.Resume))
      authenticator = system.actorOf(SimpleSupervisor.props(Authenticator.props(nodeParams), "authenticator", SupervisorStrategy.Resume))
      // Before initializing the switchboard (which re-connects us to the network) and the user-facing parts of the system,
      // we want to make sure the handler for post-restart broken HTLCs has finished initializing.
      _ <- postRestartCleanUpInitialized.future
      switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, authenticator, watcher, router, relayer, paymentHandler, wallet), "switchboard", SupervisorStrategy.Resume))
      server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, authenticator, serverBindingAddress, Some(tcpBound)), "server", SupervisorStrategy.Restart))
      paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams, router, relayer, register), "payment-initiator", SupervisorStrategy.Restart))
      _ = for (i <- 0 until config.getInt("autoprobe-count")) yield system.actorOf(SimpleSupervisor.props(Autoprobe.props(nodeParams, router, paymentInitiator), s"payment-autoprobe-$i", SupervisorStrategy.Restart))

      kit = Kit(
        nodeParams = nodeParams,
        system = system,
        watcher = watcher,
        paymentHandler = paymentHandler,
        register = register,
        commandBuffer = commandBuffer,
        relayer = relayer,
        router = router,
        switchboard = switchboard,
        paymentInitiator = paymentInitiator,
        server = server,
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

// @formatter:off
sealed trait Bitcoin
case class Bitcoind(bitcoinClient: BasicBitcoinJsonRPCClient) extends Bitcoin
case class Electrum(electrumClient: ActorRef) extends Bitcoin
// @formatter:on

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               commandBuffer: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               server: ActorRef,
               wallet: EclairWallet)

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case object BitcoinRPCConnectionException extends RuntimeException("could not connect to bitcoind using json-rpc")

case object BitcoinWalletDisabledException extends RuntimeException("bitcoind must have wallet support enabled")

case object EmptyAPIPasswordException extends RuntimeException("must set a password for the json-rpc api")

case object IncompatibleDBException extends RuntimeException("database is not compatible with this version of eclair")

case object IncompatibleNetworkDBException extends RuntimeException("network database is not compatible with this version of eclair")
