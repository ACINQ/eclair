package fr.acinq.eclair.channel.states.g

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.{StateSpecBaseClass, StateTestsHelperMethods}
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.wire.{ClosingSigned, Error, Shutdown}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NegotiatingStateSpec extends StateSpecBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    // note that alice.initialFeeRate != bob.initialFeeRate
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "0B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "0A"))
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, blockchainA, alice2blockchain, bob2blockchain)
      val sender = TestProbe()
      // alice initiates a closing
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[Shutdown]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NEGOTIATING)
      awaitCond(bob.stateName == NEGOTIATING)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
    }
  }

  // TODO: this must be re-enabled when dynamic fees are implemented
  ignore("recv ClosingSigned (theirCloseFee != ourCloseFee") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
      alice2bob.forward(bob)
      val bob2aliceCloseSig = bob2alice.expectMsgType[ClosingSigned]
      assert(2 * aliceCloseSig.feeSatoshis == bob2aliceCloseSig.feeSatoshis)
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
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchConfirmed]
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
      bob2blockchain.expectMsgType[WatchConfirmed]
      alice ! (BITCOIN_FUNDING_SPENT, mutualCloseTx)
      alice2blockchain.expectNoMsg(1 second)
      assert(alice.stateName == NEGOTIATING)
    }
  }

  test("recv Error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes())
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

}
