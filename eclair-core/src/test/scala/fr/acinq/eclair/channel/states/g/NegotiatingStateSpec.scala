package fr.acinq.eclair.channel.states.g

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.{Globals, TestkitBaseClass}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.wire.{ClosingSigned, Error, Shutdown}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NegotiatingStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val sender = TestProbe()
      // alice initiates a closing
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[Shutdown]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[Shutdown]
      // NB: at this point, bob has already computed and sent the first ClosingSigned message
      // In order to force a fee negotiation, we will change the current fee before forwarding
      // the Shutdown message to alice, so that alice computes a different initial closing fee.
      Globals.feeratePerKw.set(Globals.feeratePerKw.get() * 2)
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NEGOTIATING)
      awaitCond(bob.stateName == NEGOTIATING)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
    }
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
      val bobCloseSig = bob2alice.expectMsgType[ClosingSigned]
      assert(aliceCloseSig1.feeSatoshis == 2 * bobCloseSig.feeSatoshis)
      // actual test starts here
      val initialState = alice.stateData.asInstanceOf[DATA_NEGOTIATING]
      bob2alice.forward(alice)
      val aliceCloseSig2 = alice2bob.expectMsgType[ClosingSigned]
      // BOLT 2: If the receiver [doesn't agree with the fee] it SHOULD propose a value strictly between the received fee-satoshis and its previously-sent fee-satoshis
      assert(aliceCloseSig2.feeSatoshis < aliceCloseSig1.feeSatoshis && aliceCloseSig2.feeSatoshis > bobCloseSig.feeSatoshis)
      awaitCond(alice.stateData.asInstanceOf[DATA_NEGOTIATING] == initialState.copy(localClosingSigned = aliceCloseSig2))
    }
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      var aliceCloseFee, bobCloseFee = 0L
      do {
        aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
        alice2bob.forward(bob)
        bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
        bob2alice.forward(alice)
      } while (aliceCloseFee != bobCloseFee)
      alice2blockchain.expectMsgType[PublishAsap]
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_CLOSE_DONE)
      bob2blockchain.expectMsgType[PublishAsap]
      assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_CLOSE_DONE)
      awaitCond(alice.stateName == CLOSING)
      awaitCond(bob.stateName == CLOSING)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (counterparty's mutual close)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      var aliceCloseFee, bobCloseFee = 0L
      do {
        aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
        alice2bob.forward(bob)
        bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
        if (aliceCloseFee != bobCloseFee) {
          bob2alice.forward(alice)
        }
      } while (aliceCloseFee != bobCloseFee)
      // at this point alice and bob have converged on closing fees, but alice has not yet received the final signature whereas bob has
      // bob publishes the mutual close and alice is notified that the funding tx has been spent
      // actual test starts here
      assert(alice.stateName == NEGOTIATING)
      val mutualCloseTx = bob2blockchain.expectMsgType[PublishAsap].tx
      assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_CLOSE_DONE)
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, mutualCloseTx)
      alice2blockchain.expectNoMsg(1 second)
      assert(alice.stateName == NEGOTIATING)
    }
  }

  test("recv Error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes())
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }

}
