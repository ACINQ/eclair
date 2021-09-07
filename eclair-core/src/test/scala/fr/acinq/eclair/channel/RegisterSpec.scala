package fr.acinq.eclair.channel

import fr.acinq.eclair._

import scala.concurrent.duration._
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import org.scalatest.funsuite.AnyFunSuite
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await

class RegisterSpec extends AnyFunSuite {

  case class CustomChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey) extends AbstractChannelRestored

  test("relay an htlc-add") {
    implicit val timeout: Timeout = Timeout(3.seconds)
    implicit val system: ActorSystem = ActorSystem("test")

    val registerRef = system.actorOf(Props(new Register))
    val customRestoredEvent = CustomChannelRestored(TestProbe().ref, randomBytes32(), TestProbe().ref, randomKey().publicKey)
    registerRef ! customRestoredEvent

    assert(Await.result(registerRef ? Symbol("channels"), 3.seconds).asInstanceOf[Map[ByteVector32, ActorRef]].keys.head == customRestoredEvent.channelId)
  }
}
