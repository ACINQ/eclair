package fr.acinq.eclair.channel

import fr.acinq.eclair._

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.ParallelTestExecution

class RegisterSpec extends TestKitBaseClass with AnyFunSuiteLike with ParallelTestExecution {

  case class CustomChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey) extends AbstractChannelRestored

  test("register processes custom restored events") {
    val sender = TestProbe()
    val registerRef = system.actorOf(Register.props())
    val customRestoredEvent = CustomChannelRestored(TestProbe().ref, randomBytes32(), TestProbe().ref, randomKey().publicKey)
    registerRef ! customRestoredEvent
    sender.send(registerRef, Symbol("channels"))
    sender.expectMsgType[Map[ByteVector32, ActorRef]] == Map(customRestoredEvent.channelId -> customRestoredEvent.channel)
  }
}
