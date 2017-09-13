package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.pattern.after
import akka.stream.{ActorMaterializer, BindFailedException}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.api.{GetInfoResponse, Service}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.{BitpayInsightFeeProvider, ConstantFeeProvider}
import fr.acinq.eclair.blockchain.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.spv.BitcoinjKit
import fr.acinq.eclair.blockchain.wallet.{BitcoinCoreWallet, BitcoinjWallet, EclairWallet}
import fr.acinq.eclair.blockchain.zmq.ZMQActor
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.{Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/**
  * Created by PM on 25/01/2016.
  */
class Setup(datadir: File, overrideDefaults: Config = ConfigFactory.empty(), actorSystem: ActorSystem = ActorSystem()) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")

  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val nodeParams = NodeParams.makeNodeParams(datadir, config)
  val spv = config.getBoolean("spv")
  val chain = config.getString("chain")

  // early checks
  DBCompatChecker.checkDBCompatibility(nodeParams)
  PortChecker.checkAvailable(config.getString("server.binding-ip"), config.getInt("server.port"))

  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  implicit val system = actorSystem
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val bitcoin = if (spv) {
    logger.warn("EXPERIMENTAL SPV MODE ENABLED!!!")
    val staticPeers = config.getConfigList("bitcoinj.static-peers").map(c => new InetSocketAddress(c.getString("host"), c.getInt("port"))).toList
    logger.info(s"using staticPeers=$staticPeers")
    val bitcoinjKit = new BitcoinjKit(chain, datadir, staticPeers)
    bitcoinjKit.startAsync()
    Await.ready(bitcoinjKit.initialized, 10 seconds)
    Left(bitcoinjKit)
  } else {
    val bitcoinClient = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport")))
    val future = for {
      json <- bitcoinClient.rpcClient.invoke("getblockchaininfo").recover { case _ => throw BitcoinRPCConnectionException }
      progress = (json \ "verificationprogress").extract[Double]
      chainHash <- bitcoinClient.rpcClient.invoke("getblockhash", 0).map(_.extract[String]).map(BinaryData(_)).map(x => BinaryData(x.reverse))
      bitcoinVersion <- bitcoinClient.rpcClient.invoke("getnetworkinfo").map(json => (json \ "version")).map(_.extract[String])
    } yield (progress, chainHash, bitcoinVersion)
    // blocking sanity checks
    val (progress, chainHash, bitcoinVersion) = Await.result(future, 10 seconds)
    assert(chainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$chainHash)")
    assert(progress > 0.99, "bitcoind should be synchronized")
    // TODO: add a check on bitcoin version?
    Right(bitcoinClient)
  }

  def bootstrap: Future[Kit] = {
    val zmqConnected = Promise[Boolean]()
    val tcpBound = Promise[Unit]()

    val defaultFeeratePerKb = config.getLong("default-feerate-per-kb")
    Globals.feeratePerKw.set(feerateKb2Kw(defaultFeeratePerKb))
    logger.info(s"initial feeratePerKw=${Globals.feeratePerKw.get()}")
    val feeProvider = chain match {
      case "regtest" => new ConstantFeeProvider(defaultFeeratePerKb)
      case _ => new BitpayInsightFeeProvider()
    }
    system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeeratePerKB.map {
      case feeratePerKB =>
        Globals.feeratePerKw.set(feerateKb2Kw(feeratePerKB))
        system.eventStream.publish(CurrentFeerate(Globals.feeratePerKw.get()))
        logger.info(s"current feeratePerKw=${Globals.feeratePerKw.get()}")
    })

    val watcher = bitcoin match {
      case Left(bitcoinj) =>
        zmqConnected.success(true)
        system.actorOf(SimpleSupervisor.props(SpvWatcher.props(bitcoinj), "watcher", SupervisorStrategy.Resume))
      case Right(bitcoinClient) =>
        system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmq"), Some(zmqConnected))), "zmq", SupervisorStrategy.Restart))
        system.actorOf(SimpleSupervisor.props(ZmqWatcher.props(bitcoinClient), "watcher", SupervisorStrategy.Resume))
    }

    val wallet = bitcoin match {
      case Left(bitcoinj) => new BitcoinjWallet(bitcoinj.initialized.map(_ => bitcoinj.wallet()))
      case Right(bitcoinClient) => new BitcoinCoreWallet(bitcoinClient.rpcClient, watcher)
    }
    wallet.getFinalAddress.map {
      case address => logger.info(s"initial wallet address=$address")
    }

    val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
      case "local" => LocalPaymentHandler.props(nodeParams)
      case "noop" => Props[NoopPaymentHandler]
    }, "payment-handler", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams.privateKey, paymentHandler), "relayer", SupervisorStrategy.Resume))
    val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
    val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer, wallet), "switchboard", SupervisorStrategy.Resume))
    val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))
    val server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, switchboard, new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port")), Some(tcpBound)), "server", SupervisorStrategy.Restart))

    val kit = Kit(
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
      wallet = wallet)

    val api = new Service {

      override def getInfoResponse: Future[GetInfoResponse] = Future.successful(GetInfoResponse(nodeId = nodeParams.privateKey.publicKey, alias = nodeParams.alias, port = config.getInt("server.port"), chainHash = nodeParams.chainHash, blockHeight = Globals.blockCount.intValue()))

      override def appKit = kit
    }
    val httpBound = Http().bindAndHandle(api.route, config.getString("api.binding-ip"), config.getInt("api.port")).recover {
      case _: BindFailedException => throw TCPBindException(config.getInt("api.port"))
    }

    val zmqTimeout = after(5 seconds, using = system.scheduler)(Future.failed(BitcoinZMQConnectionTimeoutException))
    val tcpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("server.port"))))
    val httpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("api.port"))))

    for {
      _ <- Future.firstCompletedOf(zmqConnected.future :: zmqTimeout :: Nil)
      _ <- Future.firstCompletedOf(tcpBound.future :: tcpTimeout :: Nil)
      _ <- Future.firstCompletedOf(httpBound :: httpTimeout :: Nil)
    } yield kit

  }

}

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               server: ActorRef,
               wallet: EclairWallet)

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case object BitcoinRPCConnectionException extends RuntimeException("could not connect to bitcoind using json-rpc")
