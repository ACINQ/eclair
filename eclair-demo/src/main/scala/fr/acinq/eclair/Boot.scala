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
import fr.acinq.eclair.channel.Register
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
  val register = system.actorOf(Register.props(blockchain), name = "register")

  val server = system.actorOf(Server.props(config.getString("eclair.server.host"), config.getInt("eclair.server.port")), "server")
  val api = new Service {
    override val register: ActorRef = Boot.register

    override def connect(addr: InetSocketAddress, amount: Long): Unit = system.actorOf(Props(classOf[Client], addr, amount))
  }
  Http().bindAndHandle(api.route, config.getString("eclair.api.host"), config.getInt("eclair.api.port"))

}
