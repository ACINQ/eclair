package fr.acinq.eclair.channel

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorRef, PoisonPill}
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.io.PeerCreated
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution}

class RegisterSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ParallelTestExecution {

  case class CustomChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey) extends AbstractChannelRestored

  case class FixtureParam(register: TestActorRef[Register], probe: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[SubscriptionsComplete])
    val register = TestActorRef(new Register())
    probe.expectMsg(SubscriptionsComplete(classOf[Register]))
    try {
      withFixture(test.toNoArgTest(FixtureParam(register, probe)))
    } finally {
      system.stop(register)
    }
  }

  test("process custom restored events") { f =>
    import f._

    val customRestoredEvent = CustomChannelRestored(TestProbe().ref, randomBytes32(), TestProbe().ref, randomKey().publicKey)
    system.eventStream.publish(customRestoredEvent)
    awaitAssert({
      probe.send(register, Register.GetChannels)
      probe.expectMsgType[Map[ByteVector32, ActorRef]] == Map(customRestoredEvent.channelId -> customRestoredEvent.channel)
    })
  }

  test("forward messages to peers") { f =>
    import f._

    val nodeId = randomKey().publicKey
    val peer1 = TestProbe()
    system.eventStream.publish(PeerCreated(peer1.ref, nodeId))

    awaitAssert({
      register ! Register.ForwardNodeId(probe.ref.toTyped, nodeId, "hello")
      peer1.expectMsg("hello")
    })

    // We simulate a race condition, where the peer is recreated but we receive events out of order.
    val peer2 = TestProbe()
    system.eventStream.publish(PeerCreated(peer2.ref, nodeId))
    awaitAssert({
      register ! Register.ForwardNodeId(probe.ref.toTyped, nodeId, "world")
      peer2.expectMsg("world")
    })
    register ! Register.PeerTerminated(peer1.ref, nodeId)

    register ! Register.ForwardNodeId(probe.ref.toTyped, nodeId, "hello again")
    peer2.expectMsg("hello again")

    peer2.ref ! PoisonPill
    awaitAssert({
      val fwd = Register.ForwardNodeId(probe.ref.toTyped, nodeId, "d34d")
      register ! fwd
      probe.expectMsg(Register.ForwardNodeIdFailure(fwd))
    })
  }

}
