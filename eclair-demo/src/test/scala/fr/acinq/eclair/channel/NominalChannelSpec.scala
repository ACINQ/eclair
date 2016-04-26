package fr.acinq.eclair.channel

import akka.actor.ActorSystem
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.PollingWatcher
import fr.acinq.eclair.channel.TestConstants.{Alice, Bob}
import lightning.{locktime, update_add_htlc}
import lightning.locktime.Locktime.Blocks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSuiteLike, Matchers}

import scala.collection.generic.SeqFactory
import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
class NominalChannelSpec extends TestKit(ActorSystem("test")) with Matchers with FunSuiteLike with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("open channel and reach normal state") {
    val pipe = TestActorRef[Pipe]
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val blockchainB = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val alice = TestFSMRef(new Channel(pipe, blockchainA, Alice.channelParams, "B"))
    val bob = TestFSMRef(new Channel(pipe, blockchainB, Bob.channelParams, "A"))

    val monitorA = TestProbe()
    alice ! SubscribeTransitionCallBack(monitorA.ref)
    val CurrentState(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR) = monitorA.expectMsgClass(classOf[CurrentState[_]])

    val monitorB = TestProbe()
    bob ! SubscribeTransitionCallBack(monitorB.ref)
    val CurrentState(_, OPEN_WAIT_FOR_OPEN_NOANCHOR) = monitorB.expectMsgClass(classOf[CurrentState[_]])

    pipe !(alice, bob) // this starts the communication between alice and bob

    within(1 minute) {

      val Transition(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR, OPEN_WAIT_FOR_COMMIT_SIG) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAIT_FOR_OPEN_NOANCHOR, OPEN_WAIT_FOR_ANCHOR) = monitorB.expectMsgClass(classOf[Transition[_]])

      val Transition(_, OPEN_WAIT_FOR_COMMIT_SIG, OPEN_WAITING_OURANCHOR) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAIT_FOR_ANCHOR, OPEN_WAITING_THEIRANCHOR) = monitorB.expectMsgClass(classOf[Transition[_]])

      val Transition(_, OPEN_WAITING_OURANCHOR, OPEN_WAIT_FOR_COMPLETE_OURANCHOR) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAITING_THEIRANCHOR, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) = monitorB.expectMsgClass(classOf[Transition[_]])

      val Transition(_, OPEN_WAIT_FOR_COMPLETE_OURANCHOR, NORMAL) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR, NORMAL) = monitorB.expectMsgClass(classOf[Transition[_]])
    }
  }

  test("create and fulfill HTLCs") {
    val pipe = TestActorRef[Pipe]
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val blockchainB = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val alice = TestFSMRef(new Channel(pipe, blockchainA, Alice.channelParams, "B"))
    val bob = TestFSMRef(new Channel(pipe, blockchainB, Bob.channelParams, "A"))

    pipe !(alice, bob) // this starts the communication between alice and bob

    within(1 minute) {

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)

      alice ! CMD_ADD_HTLC(60000000, H, locktime(Blocks(4)))

      awaitAssert({
        val DATA_NORMAL(_, _, _, _, _, _, List(Change(OUT, _, update_add_htlc(_, _, r1, _, _))), _, _) = alice.stateData
        assert(r1 == bin2sha256(H))
        val DATA_NORMAL(_, _, _, _, _, _, List(Change(IN, _, update_add_htlc(_, _, r2, _, _))), _, _) = bob.stateData
        assert(r2 == bin2sha256(H))
      })

      bob ! CMD_SIGN

    }
  }

}
