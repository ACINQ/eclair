package fr.acinq.eclair

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.io.IO
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.api.ServiceActor
import fr.acinq.eclair.blockchain.PollingWatcher
import fr.acinq.eclair.io.{Client, Server}
import grizzled.slf4j.Logging
import spray.can.Http
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._
import Globals._

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  implicit val system = ActorSystem()
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val config = ConfigFactory.load()
  val testnet = Await.result(bitcoin_client.invoke("getinfo").map(json => (json \ "testnet").extract[Boolean]), 10 seconds)
  assert(testnet, "you should be on testnet")

  val blockchain = system.actorOf(Props(new PollingWatcher(bitcoin_client)), name = "blockchain")
  val register = system.actorOf(Props[RegisterActor], name = "register")
  val server = system.actorOf(Server.props(config.getString("eclair.server.address"), config.getInt("eclair.server.port")), "server")
  val api = system.actorOf(Props(new ServiceActor {
    override val register: ActorRef = Boot.register

    override def connect(addr: InetSocketAddress, amount: Long): Unit = system.actorOf(Props(classOf[Client], addr, amount))
  }), "api")

  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ! Http.Bind(api, config.getString("eclair.api.address"), config.getInt("eclair.api.port"))
}
