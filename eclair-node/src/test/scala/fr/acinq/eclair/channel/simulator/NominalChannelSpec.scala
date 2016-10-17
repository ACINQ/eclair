package fr.acinq.eclair.channel.simulator

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.peer.NewBlock
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_SPENT, CLOSED, CLOSING, NEGOTIATING, _}
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, update_add_htlc}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
@RunWith(classOf[JUnitRunner])
class NominalChannelSpec extends BaseChannelTestClass {

  test("open channel and reach normal state") { case (alice, bob, pipe) =>
    val monitorA = TestProbe()
    alice ! SubscribeTransitionCallBack(monitorA.ref)
    val CurrentState(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR) = monitorA.expectMsgClass(classOf[CurrentState[_]])

    val monitorB = TestProbe()
    bob ! SubscribeTransitionCallBack(monitorB.ref)
    val CurrentState(_, OPEN_WAIT_FOR_OPEN_NOANCHOR) = monitorB.expectMsgClass(classOf[CurrentState[_]])

    pipe ! (alice, bob) // this starts the communication between alice and bob

    within(30 seconds) {

      val Transition(_, OPEN_WAIT_FOR_OPEN_WITHANCHOR, OPEN_WAIT_FOR_COMMIT_SIG) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAIT_FOR_OPEN_NOANCHOR, OPEN_WAIT_FOR_ANCHOR) = monitorB.expectMsgClass(classOf[Transition[_]])

      val Transition(_, OPEN_WAIT_FOR_COMMIT_SIG, OPEN_WAITING_OURANCHOR) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAIT_FOR_ANCHOR, OPEN_WAITING_THEIRANCHOR) = monitorB.expectMsgClass(classOf[Transition[_]])

      val Transition(_, OPEN_WAITING_OURANCHOR, OPEN_WAIT_FOR_COMPLETE_OURANCHOR) = monitorA.expectMsgClass(5 seconds, classOf[Transition[_]])
      val Transition(_, OPEN_WAITING_THEIRANCHOR, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) = monitorB.expectMsgClass(classOf[Transition[_]])

      val Transition(_, OPEN_WAIT_FOR_COMPLETE_OURANCHOR, NORMAL) = monitorA.expectMsgClass(classOf[Transition[_]])
      val Transition(_, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR, NORMAL) = monitorB.expectMsgClass(classOf[Transition[_]])
    }
  }

  test("create and fulfill HTLCs") { case (alice, bob, pipe) =>
    pipe ! (alice, bob) // this starts the communication between alice and bob

    within(30 seconds) {

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)

      alice ! CMD_ADD_HTLC(60000000, H, locktime(Blocks(400)))
      Thread.sleep(100)

      (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val List(update_add_htlc(_, _, h, _, _)) = d.commitments.ourChanges.proposed
          assert(h == bin2sha256(H))
      }
      (bob.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val List(update_add_htlc(_, _, h, _, _)) = d.commitments.theirChanges.proposed
          assert(h == bin2sha256(H))
      }

      alice ! CMD_SIGN
      Thread.sleep(500)

      (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val htlc = d.commitments.theirCommit.spec.htlcs.head
          assert(htlc.add.rHash == bin2sha256(H))
      }
      (bob.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val htlc = d.commitments.ourCommit.spec.htlcs.head
          assert(htlc.add.rHash == bin2sha256(H))
      }

      bob ! CMD_FULFILL_HTLC(1, R)
      bob ! CMD_SIGN
      Thread.sleep(500)

      alice ! CMD_SIGN
      Thread.sleep(500)

      (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          assert(d.commitments.ourCommit.spec.htlcs.isEmpty)
          assert(d.commitments.ourCommit.spec.amount_us_msat == TestConstants.anchorAmount * 1000 - 60000000)
          assert(d.commitments.ourCommit.spec.amount_them_msat == 60000000)
      }
      (bob.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          assert(d.commitments.ourCommit.spec.htlcs.isEmpty)
          assert(d.commitments.ourCommit.spec.amount_us_msat == 60000000)
          assert(d.commitments.ourCommit.spec.amount_them_msat == TestConstants.anchorAmount  * 1000 - 60000000)
      }

      // send another HTLC
      val R1 = Crypto.sha256(H)
      val H1 = Crypto.sha256(R1)

      alice ! CMD_ADD_HTLC(60000000, H1, locktime(Blocks(400)))
      Thread.sleep(500)

      (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val List(update_add_htlc(_, _, h, _, _)) = d.commitments.ourChanges.proposed
          assert(h == bin2sha256(H1))
      }
      (bob.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val List(update_add_htlc(_, _, h, _, _)) = d.commitments.theirChanges.proposed
          assert(h == bin2sha256(H1))
      }

      alice ! CMD_SIGN
      Thread.sleep(500)

      (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val htlc = d.commitments.theirCommit.spec.htlcs.head
          assert(htlc.add.rHash == bin2sha256(H1))
      }
      (bob.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          val htlc = d.commitments.ourCommit.spec.htlcs.head
          assert(htlc.add.rHash == bin2sha256(H1))
      }

      bob ! CMD_FULFILL_HTLC(2, R1)
      bob ! CMD_SIGN
      Thread.sleep(500)

      alice ! CMD_SIGN

      Thread.sleep(500)

      (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          assert(d.commitments.ourCommit.spec.htlcs.isEmpty)
          assert(d.commitments.ourCommit.spec.amount_us_msat == TestConstants.anchorAmount * 1000 - 2 * 60000000)
          assert(d.commitments.ourCommit.spec.amount_them_msat == 2 * 60000000)
      }
      (bob.stateData: @unchecked) match {
        case d: DATA_NORMAL =>
          assert(d.commitments.ourCommit.spec.htlcs.isEmpty)
          assert(d.commitments.ourCommit.spec.amount_us_msat == 2 * 60000000)
          assert(d.commitments.ourCommit.spec.amount_them_msat == TestConstants.anchorAmount * 1000 - 2 * 60000000)
      }
    }
  }

  test("close channel starting with no HTLC") { case (alice, bob, pipe) =>
    pipe ! (alice, bob) // this starts the communication between alice and bob

    within(30 seconds) {

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      alice ! CMD_CLOSE(None)

      awaitCond(alice.stateName == CLOSED)
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("close channel with pending htlcs") { case (alice, bob, pipe) =>
    within(30 seconds) {

      pipe ! (alice, bob) // this starts the communication between alice and bob
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      val monitorA = TestProbe()
      alice ! SubscribeTransitionCallBack(monitorA.ref)
      val CurrentState(_, NORMAL) = monitorA.expectMsgClass(classOf[CurrentState[_]])

      val monitorB = TestProbe()
      bob ! SubscribeTransitionCallBack(monitorB.ref)
      val CurrentState(_, NORMAL) = monitorB.expectMsgClass(classOf[CurrentState[_]])

      def expectTransition(monitor: TestProbe, from: State, to: State): Unit = {
        val Transition(_, from, to) = monitor.expectMsgClass(classOf[Transition[_]])
      }

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)

      alice ! CMD_ADD_HTLC(60000000, H, locktime(Blocks(400)))
      alice ! CMD_SIGN
      bob ! CMD_SIGN
      alice ! CMD_CLOSE(None)

      expectTransition(monitorA, NORMAL, SHUTDOWN)
      expectTransition(monitorB, NORMAL, SHUTDOWN)

      bob ! CMD_FULFILL_HTLC(1, R)
      bob ! CMD_SIGN
      Thread.sleep(100)
      alice ! CMD_SIGN

      expectTransition(monitorA, SHUTDOWN, NEGOTIATING)
      expectTransition(monitorB, SHUTDOWN, NEGOTIATING)
      expectTransition(monitorA, NEGOTIATING, CLOSING)
      expectTransition(monitorB, NEGOTIATING, CLOSING)
      expectTransition(monitorA, CLOSING, CLOSED)
      expectTransition(monitorB, CLOSING, CLOSED)
    }
  }

  test("steal revoked commit tx") { case (alice, bob, pipe) =>
    pipe ! (alice, bob) // this starts the communication between alice and bob

    within(30 seconds) {

      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)

      val R: BinaryData = "0102030405060708010203040506070801020304050607080102030405060708"
      val H = Crypto.sha256(R)

      alice ! CMD_ADD_HTLC(60000000, H, locktime(Blocks(400)))
      alice ! CMD_SIGN
      Thread.sleep(500)
      bob ! CMD_SIGN
      Thread.sleep(500)

      val commitTx = (alice.stateData: @unchecked) match {
        case d: DATA_NORMAL => d.commitments.ourCommit.publishableTx
      }

      bob ! CMD_FULFILL_HTLC(1, R)
      bob ! CMD_SIGN
      Thread.sleep(500)

      alice ! CMD_SIGN
      Thread.sleep(500)

      // alice publishes a revoked tx
      bob ! (BITCOIN_ANCHOR_SPENT, commitTx)
      awaitCond(bob.stateName == CLOSED)
    }
  }
}
