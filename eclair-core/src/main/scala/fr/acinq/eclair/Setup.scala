package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.pattern.after
import akka.stream.{ActorMaterializer, BindFailedException}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{Base58Check, BinaryData, Block, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Script}
import fr.acinq.eclair.api.{GetInfoResponse, Service}
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.zmq.ZMQActor
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.{Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JString

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

/**
  * Created by PM on 25/01/2016.
  */
class Setup(datadir: File, overrideDefaults: Config = ConfigFactory.empty(), actorSystem: ActorSystem = ActorSystem()) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)

  // early check
  PortChecker.checkAvailable(config.getString("server.binding-ip"), config.getInt("server.port"))

  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  implicit val system = actorSystem
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)

  val bitcoinClient = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("bitcoind.rpcuser"),
    password = config.getString("bitcoind.rpcpassword"),
    host = config.getString("bitcoind.host"),
    port = config.getInt("bitcoind.rpcport")))

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val future = for {
    json <- bitcoinClient.client.invoke("getblockchaininfo")
    chain = (json \ "chain").extract[String]
    blockCount = (json \ "blocks").extract[Long]
    progress = (json \ "verificationprogress").extract[Double]
    bitcoinVersion <- bitcoinClient.client.invoke("getnetworkinfo").map(json => (json \ "version")).map(_.extract[String])
  } yield (chain, blockCount, progress, bitcoinVersion)
  val (chain, blockCount, progress, bitcoinVersion) = Try(Await.result(future, 10 seconds)).recover { case _ => throw BitcoinRPCConnectionException }.get
  val chainHash = chain match {
    case "test" => Block.TestnetGenesisBlock.hash
    case "regtest" => Block.RegtestGenesisBlock.hash
    case _ => throw new RuntimeException("only regtest and testnet are supported for now")
  }

  logger.info(s"using chain=$chain chainHash=$chainHash")
  assert(progress > 0.99, "bitcoind should be synchronized")
   // we use it as final payment address, so that funds are moved to the bitcoind wallet upon channel termination
  val JString(finalAddress) = Await.result(bitcoinClient.client.invoke("getnewaddress"), 10 seconds)
  logger.info(s"finaladdress=$finalAddress")
  // TODO: we should use p2wpkh instead of p2pkh as soon as bitcoind supports it
  //val finalScriptPubKey = OP_0 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: Nil
  val finalScriptPubKey = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)

  val nodeParams = NodeParams.makeNodeParams(datadir, config, chainHash, finalScriptPubKey)
  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")

  DBCompatChecker.checkDBCompatibility(nodeParams)

  Globals.blockCount.set(blockCount)

  val defaultFeeratePerKw = config.getLong("default-feerate-perkw")
  val feeratePerKw = if (chain == "regtest") defaultFeeratePerKw else {
    val feeratePerKB = Await.result(bitcoinClient.estimateSmartFee(nodeParams.smartfeeNBlocks), 10 seconds)
    if (feeratePerKB < 0) defaultFeeratePerKw else feerateKB2Kw(feeratePerKB)
  }
  logger.info(s"initial feeratePerKw=$feeratePerKw")
  Globals.feeratePerKw.set(feeratePerKw)

  def bootstrap: Future[Kit] = {
    val zmqConnected = Promise[Boolean]()
    val tcpBound = Promise[Unit]()

    val zmq = system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmq"), Some(zmqConnected))), "zmq", SupervisorStrategy.Restart))
    val watcher = system.actorOf(SimpleSupervisor.props(PeerWatcher.props(nodeParams, bitcoinClient), "watcher", SupervisorStrategy.Resume))
    val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
      case "local" => LocalPaymentHandler.props(nodeParams)
      case "noop" => Props[NoopPaymentHandler]
    }, "payment-handler", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams.privateKey, paymentHandler), "relayer", SupervisorStrategy.Resume))
    val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
    val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer), "switchboard", SupervisorStrategy.Resume))
    val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))
    val server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, switchboard, new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port")), Some(tcpBound)), "server", SupervisorStrategy.Restart))

    val kit = Kit(
      nodeParams = nodeParams,
      system = system,
      zmq = zmq,
      watcher = watcher,
      paymentHandler = paymentHandler,
      register = register,
      relayer = relayer,
      router = router,
      switchboard = switchboard,
      paymentInitiator = paymentInitiator,
      server = server)

    val api = new Service {

      override def getInfoResponse: Future[GetInfoResponse] = Future.successful(GetInfoResponse(nodeId = nodeParams.privateKey.publicKey, alias = nodeParams.alias, port = config.getInt("server.port"), chainHash = chainHash, blockHeight = Globals.blockCount.intValue()))

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
               zmq: ActorRef,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               server: ActorRef)

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case object BitcoinRPCConnectionException extends RuntimeException("could not connect to bitcoind using json-rpc")
