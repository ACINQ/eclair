package fr.acinq.eclair.channel.simulator.states.c

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{PeerWatcher, WatchConfirmed, WatchLost, WatchSpent}
import fr.acinq.eclair.channel.simulator.states.StateSpecBaseClass
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_DEPTHOK, OPEN_WAITING_THEIRANCHOR, OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR, _}
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OpenWaitingTheirAnchorStateSpec extends StateSpecBaseClass {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, blockchainA, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    alice2bob.expectMsgType[open_channel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[open_channel]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[open_anchor]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[open_commit_sig]
    bob2alice.forward(alice)
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    awaitCond(bob.stateName == OPEN_WAITING_THEIRANCHOR)
    test((alice, bob, alice2bob, bob2alice, bob2blockchain))
  }

  test("recv open_complete") { case (_, bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      val msg = alice2bob.expectMsgType[open_complete]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_OPEN_WAITING].deferred == Some(msg))
      awaitCond(bob.stateName == OPEN_WAITING_THEIRANCHOR)
    }
  }

  test("recv BITCOIN_ANCHOR_DEPTHOK") { case (_, bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! BITCOIN_ANCHOR_DEPTHOK
      awaitCond(bob.stateName == OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR)
      bob2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[open_complete]
    }
  }

  test("recv BITCOIN_ANCHOR_TIMEOUT") { case (_, bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! BITCOIN_ANCHOR_TIMEOUT
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT") { case (alice, bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      // this is the fully signed tx that alice could decide to publish
      alice2bob.expectMsgType[open_complete]
      awaitCond(alice.stateName == OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      // we have nothing at stake so we don't do anything with the tx
      bob ! (BITCOIN_ANCHOR_SPENT, tx)
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv error") { case (_, bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! error(Some("oops"))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (_, bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! CMD_CLOSE(None)
      awaitCond(bob.stateName == CLOSED)
    }
  }

}
