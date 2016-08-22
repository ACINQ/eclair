package fr.acinq.eclair

import javafx.application.Application

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.util.Timeout
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PollingWatcher}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.{Client, Server}
import grizzled.slf4j.Logging

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import fr.acinq.bitcoin.BitcoinJsonRPCClient
import fr.acinq.eclair.gui.MainWindow
import fr.acinq.eclair.router.IRCRouter

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {
  args.toList match {
    case "headless" :: rest => new Setup()
    case _ => Application.launch(classOf[MainWindow])
  }
}

class Setup extends Logging {

  logger.info(s"hello!")
  logger.info(s"nodeid=${Globals.Node.publicKey}")
  val config = ConfigFactory.load()

  implicit lazy val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val bitcoin_client = new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = config.getInt("eclair.bitcoind.port"))

  val chain = Await.result(bitcoin_client.invoke("getblockchaininfo").map(json => (json \ "chain").extract[String]), 10 seconds)
  assert(chain == "testnet" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")
  val bitcoinVersion = Await.result(bitcoin_client.invoke("getinfo").map(json => (json \ "version").extract[String]), 10 seconds)

  val blockchain = system.actorOf(Props(new PollingWatcher(new ExtendedBitcoinClient(bitcoin_client))), name = "blockchain")
  val paymentHandler = config.getString("eclair.payment-handler") match {
    case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
    case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  }
  val register = system.actorOf(Register.props(blockchain, paymentHandler), name = "register")
  val router = system.actorOf(IRCRouter.props(new ExtendedBitcoinClient(bitcoin_client)), name = "router")

  val server = system.actorOf(Server.props(config.getString("eclair.server.host"), config.getInt("eclair.server.port"), register), "server")
  val _setup = this
  val api = new Service {
    override val register: ActorRef = _setup.register
    override val router: ActorRef = _setup.router

    override def paymentHandler: ActorRef = _setup.paymentHandler

    override def connect(host: String, port: Int, amount: Long): Unit = system.actorOf(Client.props(host, port, amount, register))
  }
  Http().bindAndHandle(api.route, config.getString("eclair.api.host"), config.getInt("eclair.api.port"))
}
