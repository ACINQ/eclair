package fr.acinq.eclair.channel.simulator.states.c

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.simulator.states.StateSpecBaseClass
import fr.acinq.eclair.channel.{ERR_INFORMATION_LEAK, OPEN_WAITING_OURANCHOR, OPEN_WAIT_FOR_COMPLETE_OURANCHOR, _}
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingLockedStateSpec extends StateSpecBaseClass {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    within(30 seconds) {
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.send(blockchainA, watch.copy(channel = system.deadLetters)) // so that we can control when BITCOIN_ANCHOR_DEPTHOK arrives
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob ! BITCOIN_FUNDING_DEPTHOK
      awaitCond(alice.stateName == OPEN_WAITING_OURANCHOR)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  test("recv FundingLocked") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val msg = bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].deferred == Some(msg))
      awaitCond(alice.stateName == OPEN_WAITING_OURANCHOR)
    }
  }

  test("recv BITCOIN_ANCHOR_DEPTHOK") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      alice ! BITCOIN_FUNDING_DEPTHOK
      awaitCond(alice.stateName == OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      alice2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[FundingLocked]
    }
  }

  test("recv BITCOIN_ANCHOR_TIMEOUT") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      alice ! BITCOIN_FUNDING_TIMEOUT
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.ourCommit.publishableTx
      alice ! (BITCOIN_FUNDING_SPENT, null)
      alice2bob.expectMsgType[Error]
      alice2blockchain.expectMsg(Publish(tx))
      awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
    }
  }

  test("recv error") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.ourCommit.publishableTx
      alice ! Error(0, "oops".getBytes)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_LOCKED].commitments.ourCommit.publishableTx
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

}
