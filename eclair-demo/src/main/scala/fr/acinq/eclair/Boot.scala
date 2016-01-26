package fr.acinq.eclair

import akka.actor.{Props, ActorSystem}
import akka.util.Timeout
import fr.acinq.eclair.blockchain.PollingWatcher
import grizzled.slf4j.Logging
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

  val testnet = Await.result(bitcoin_client.invoke("getinfo").map(json => (json \ "testnet").extract[Boolean]), 10 seconds)
  assert(testnet, "you should be on testnet")

  val blockchain = system.actorOf(Props(new PollingWatcher(bitcoin_client)), name = "blockchain")

  val register = system.actorOf(Props[RegisterActor], name = "register")

}
