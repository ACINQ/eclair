package fr.acinq.eclair

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
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
import Globals._

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  logger.info(s"hello!")
  logger.info(s"nodeid=${Globals.Node.publicKey}")

  val config = ConfigFactory.load()
  val chain = Await.result(bitcoin_client.invoke("getblockchaininfo").map(json => (json \ "chain").extract[String]), 10 seconds)
  assert(chain == "testnet" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")

  val blockchain = system.actorOf(Props(new PollingWatcher(new ExtendedBitcoinClient(bitcoin_client))), name = "blockchain")
  val register = system.actorOf(Props[Register], name = "register")
  val router = system.actorOf(Props[Router], name = "router")
  val paymentHandler = config.getString("eclair.payment-handler") match {
    case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
    case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  }

  val server = system.actorOf(Server.props(config.getString("eclair.server.address"), config.getInt("eclair.server.port")), "server")
  val api = new Service {
    override val register: ActorRef = Boot.register
    override val router: ActorRef = Boot.router
    override def paymentHandler: ActorRef = Boot.paymentHandler

    override def connect(addr: InetSocketAddress, amount: Long): Unit = system.actorOf(Props(classOf[Client], addr, amount))
  }
  Http().bindAndHandle(api.route, config.getString("eclair.api.address"), config.getInt("eclair.api.port"))

}
