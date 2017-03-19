package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress
import javafx.application.Platform

import akka.actor.{Actor, ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.FileAppender
import com.sun.javafx.application.LauncherImpl
import fr.acinq.bitcoin.{Base58Check, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Script}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.peer.PeerClient
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.gui.{FxApp, FxPreloader}
import fr.acinq.eclair.io.{Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JString
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Promise}

case class CmdLineConfig(datadir: File = new File(System.getProperty("user.home"), ".eclair"), headless: Boolean = false)

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  val parser = new scopt.OptionParser[CmdLineConfig]("scopt") {
    head("scopt", "3.x")
    opt[File]("datadir").optional().valueName("<file>").action((x, c) => c.copy(datadir = x)).text("optional data directory, default is ~/.eclair")
    opt[Unit]("headless").optional().action((_, c) => c.copy(headless = true)).text("runs eclair without a gui")
  }
  parser.parse(args, CmdLineConfig()) match {
    case Some(config) if config.headless =>
      val s = new Setup(config.datadir.getAbsolutePath)
      import ExecutionContext.Implicits.global
      s.fatalEventFuture.map(e => {
        logger.error(s"received fatal event $e")
        Platform.exit()
      })
      s.boostrap
    case Some(config) => LauncherImpl.launchApplication(classOf[FxApp], classOf[FxPreloader], Array(config.datadir.getAbsolutePath))
    case None => Platform.exit()
  }
}

class Setup(datadir: String) extends Logging {

  LogSetup.logTo(datadir)

  logger.info(s"hello!")
  val config = NodeParams.loadConfiguration(new File(datadir))
  val nodeParams = NodeParams.makeNodeParams(new File(datadir), config)
  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")

  implicit lazy val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)

  val bitcoin_client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("bitcoind.rpcuser"),
    password = config.getString("bitcoind.rpcpassword"),
    host = config.getString("bitcoind.host"),
    port = config.getInt("bitcoind.rpcport")))

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  val (chain, blockCount, progress) = Await.result(bitcoin_client.client.invoke("getblockchaininfo").map(json => ((json \ "chain").extract[String], (json \ "blocks").extract[Long], (json \ "verificationprogress").extract[Double])), 10 seconds)
  assert(chain == "test" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")
  assert(progress > 0.99, "bitcoind should be synchronized")
  Globals.blockCount.set(blockCount)
  val defaultFeeratePerKw = config.getLong("default-feerate-perkw")
  val feeratePerKw = if (chain == "regtest") defaultFeeratePerKw else {
    val estimate = Await.result(bitcoin_client.estimateSmartFee(nodeParams.smartfeeNBlocks), 10 seconds)
    if (estimate < 0) defaultFeeratePerKw else estimate
  }

  logger.info(s"initial feeratePerKw=$feeratePerKw")
  Globals.feeratePerKw.set(feeratePerKw)
  val bitcoinVersion = Await.result(bitcoin_client.client.invoke("getinfo").map(json => (json \ "version").extract[String]), 10 seconds)
  // we use it as final payment address, so that funds are moved to the bitcoind wallet upon channel termination
  val JString(finalAddress) = Await.result(bitcoin_client.client.invoke("getnewaddress"), 10 seconds)
  logger.info(s"finaladdress=$finalAddress")
  // TODO: we should use p2wpkh instead of p2pkh as soon as bitcoind supports it
  //val finalScriptPubKey = OP_0 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: Nil
  val finalScriptPubKey = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Base58Check.decode(finalAddress)._2) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)

  val fatalEventPromise = Promise[FatalEvent]()
  system.actorOf(Props(new Actor {
    system.eventStream.subscribe(self, classOf[FatalEvent])

    override def receive: Receive = {
      case e: FatalEvent => fatalEventPromise.success(e)

    }
  }))
  val fatalEventFuture = fatalEventPromise.future

  val peer = system.actorOf(SimpleSupervisor.props(PeerClient.props(config.getConfig("bitcoind")), "bitcoin-peer", SupervisorStrategy.Restart))
  val watcher = system.actorOf(SimpleSupervisor.props(PeerWatcher.props(nodeParams, bitcoin_client), "watcher", SupervisorStrategy.Resume))
  val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
    case "local" => Props[LocalPaymentHandler]
    case "noop" => Props[NoopPaymentHandler]
  }, "payment-handler", SupervisorStrategy.Resume))
  val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
  val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams.privateKey, paymentHandler), "relayer", SupervisorStrategy.Resume))
  val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
  val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer, finalScriptPubKey), "switchboard", SupervisorStrategy.Resume))
  val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))
  val server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, switchboard, new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port"))), "server", SupervisorStrategy.Restart))

  val _setup = this
  val api = new Service {
    override val switchboard: ActorRef = _setup.switchboard
    override val router: ActorRef = _setup.router
    override val register: ActorRef = _setup.register
    override val paymentHandler: ActorRef = _setup.paymentHandler
    override val paymentInitiator: ActorRef = _setup.paymentInitiator
    override val system: ActorSystem = _setup.system
  }
  Http().bindAndHandle(api.route, config.getString("api.binding-ip"), config.getInt("api.port")) onFailure {
    case t: Throwable => system.eventStream.publish(HTTPBindError)
  }

  val tasks = new Thread(new Runnable() {
    override def run(): Unit = {
      nodeParams.peersDb.values.foreach(rec => switchboard ! rec)
      nodeParams.channelsDb.values.foreach(rec => switchboard ! rec)
      nodeParams.announcementsDb.values.collect { case ann: ChannelAnnouncement => router ! ann }
      nodeParams.announcementsDb.values.collect { case ann: NodeAnnouncement => router ! ann }
      nodeParams.announcementsDb.values.collect { case ann: ChannelUpdate => router ! ann }
    }
  })

  def boostrap: Unit = tasks.start()
}

object LogSetup {
  def logTo(datadir: String) = {
    val lc = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
    val ple = new PatternLayoutEncoder()
    ple.setPattern("%d %-5level %logger{36} %X{akkaSource} - %msg%ex{24}%n")
    ple.setContext(lc)
    ple.start()
    val fileAppender = new FileAppender[ILoggingEvent]()
    fileAppender.setFile(new File(datadir, "eclair.log").getPath)
    fileAppender.setEncoder(ple)
    fileAppender.setContext(lc)
    fileAppender.start()
    val logger = LoggerFactory.getLogger("ROOT").asInstanceOf[Logger]
    logger.addAppender(fileAppender)
  }
}