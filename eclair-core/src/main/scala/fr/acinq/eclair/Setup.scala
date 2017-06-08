package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import fr.acinq.bitcoin.{Base58Check, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Script}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.zmq.ZMQActor
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.{Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JString

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.util.Try

/**
  * Created by PM on 25/01/2016.
  */
class Setup(datadir: File, actorSystemName: String = "default") extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")
  val config = NodeParams.loadConfiguration(datadir)

  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  implicit lazy val system = ActorSystem(actorSystemName)
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
    chainHash <- bitcoinClient.client.invoke("getblockhash", 0).map(_.extract[String])
  } yield (chain, blockCount, progress, chainHash)
  val (chain, blockCount, progress, chainHash) = Try(Await.result(future, 10 seconds)).recover { case _ => throw BitcoinRPCConnectionException }.get
  logger.info(s"using chain=$chain chainHash=$chainHash")
  chain match {
    case "test" | "regtest" => ()
    case _ => throw new RuntimeException("only regtest and testnet are supported for now")
  }
  val nodeParams = NodeParams.makeNodeParams(datadir, config, chainHash)
  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")
  assert(progress > 0.99, "bitcoind should be synchronized")

  Globals.blockCount.set(blockCount)
  val defaultFeeratePerKw = config.getLong("default-feerate-perkw")
  val feeratePerKw = if (chain == "regtest") defaultFeeratePerKw else {
    val feeratePerKB = Await.result(bitcoinClient.estimateSmartFee(nodeParams.smartfeeNBlocks), 10 seconds)
    if (feeratePerKB < 0) defaultFeeratePerKw else feerateKB2Kw(feeratePerKB)
  }

  logger.info(s"initial feeratePerKw=$feeratePerKw")
  Globals.feeratePerKw.set(feeratePerKw)
  val bitcoinVersion = Await.result(bitcoinClient.client.invoke("getinfo").map(json => (json \ "version").extract[String]), 10 seconds)
  // we use it as final payment address, so that funds are moved to the bitcoind wallet upon channel termination
  val JString(finalAddress) = Await.result(bitcoinClient.client.invoke("getnewaddress"), 10 seconds)
  logger.info(s"finaladdress=$finalAddress")
  // TODO: we should use p2wpkh instead of p2pkh as soon as bitcoind supports it
  //val finalScriptPubKey = OP_0 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: Nil
  val finalScriptPubKey = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)

  val zmqConnected = Promise[Boolean]()
  val zmq = system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmq"), Some(zmqConnected))), "zmq", SupervisorStrategy.Restart))
  val watcher = system.actorOf(SimpleSupervisor.props(PeerWatcher.props(nodeParams, bitcoinClient), "watcher", SupervisorStrategy.Resume))
  val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
    case "local" => LocalPaymentHandler.props(nodeParams)
    case "noop" => Props[NoopPaymentHandler]
  }, "payment-handler", SupervisorStrategy.Resume))
  val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
  val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams.privateKey, paymentHandler), "relayer", SupervisorStrategy.Resume))
  val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
  val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer, finalScriptPubKey), "switchboard", SupervisorStrategy.Resume))
  val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))
  val tcpBound = Promise[Unit]()
  val server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, switchboard, new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port")), Some(tcpBound)), "server", SupervisorStrategy.Restart))

  val _setup = this
  val api = new Service {
    override val switchboard: ActorRef = _setup.switchboard
    override val router: ActorRef = _setup.router
    override val register: ActorRef = _setup.register
    override val paymentHandler: ActorRef = _setup.paymentHandler
    override val paymentInitiator: ActorRef = _setup.paymentInitiator
    override val system: ActorSystem = _setup.system
  }
  val httpBound = Http().bindAndHandle(api.route, config.getString("api.binding-ip"), config.getInt("api.port"))

  Try(Await.result(zmqConnected.future, 5 seconds)).recover { case _ => throw BitcoinZMQConnectionTimeoutException }.get
  Try(Await.result(tcpBound.future, 5 seconds)).recover { case _ => throw new TCPBindException(config.getInt("server.port")) }.get
  Try(Await.result(httpBound, 5 seconds)).recover { case _ => throw new TCPBindException(config.getInt("api.port")) }.get

  val tasks = new Thread(new Runnable() {
    override def run(): Unit = {
      nodeParams.peersDb.values.foreach(rec => switchboard ! rec)
      nodeParams.channelsDb.values.foreach(rec => switchboard ! rec)
      nodeParams.announcementsDb.values.collect { case ann: ChannelAnnouncement => router ! ann }
      nodeParams.announcementsDb.values.collect { case ann: NodeAnnouncement => router ! ann }
      nodeParams.announcementsDb.values.collect { case ann: ChannelUpdate => router ! ann }
      if (nodeParams.channelsDb.values.size > 0) {
        val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.address :: Nil, Platform.currentTime / 1000)
        router ! nodeAnn
      }
    }
  })

  def boostrap: Unit = tasks.start()
}

case class TCPBindException(port: Int) extends RuntimeException

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case object BitcoinRPCConnectionException extends RuntimeException("could not connect to bitcoind using json-rpc")
