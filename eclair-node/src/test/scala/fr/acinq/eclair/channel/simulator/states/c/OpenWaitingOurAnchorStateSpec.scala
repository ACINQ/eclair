package fr.acinq.eclair.channel.simulator.states.c

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{ERR_INFORMATION_LEAK, OPEN_WAITING_OURANCHOR, OPEN_WAIT_FOR_COMPLETE_OURANCHOR, _}
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OpenWaitingOurAnchorStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll {

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
      alice2bob.expectMsgType[open_channel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_channel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[open_anchor]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_commit_sig]
      bob2alice.forward(alice)
      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.send(blockchainA, watch.copy(channel = system.deadLetters)) // so that we can control when BITCOIN_ANCHOR_DEPTHOK arrives
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob ! BITCOIN_ANCHOR_DEPTHOK
      awaitCond(alice.stateName == OPEN_WAITING_OURANCHOR)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv open_complete") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val msg = bob2alice.expectMsgType[open_complete]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_OPEN_WAITING].deferred == Some(msg))
      awaitCond(alice.stateName == OPEN_WAITING_OURANCHOR)
    }
  }

  test("recv BITCOIN_ANCHOR_DEPTHOK") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      alice ! BITCOIN_ANCHOR_DEPTHOK
      awaitCond(alice.stateName == OPEN_WAIT_FOR_COMPLETE_OURANCHOR)
      alice2blockchain.expectMsgType[WatchLost]
      bob2alice.expectMsgType[open_complete]
    }
  }

  test("recv BITCOIN_ANCHOR_TIMEOUT") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      alice ! BITCOIN_ANCHOR_TIMEOUT
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_OPEN_WAITING].commitments.ourCommit.publishableTx
      alice ! (BITCOIN_ANCHOR_SPENT, null)
      alice2bob.expectMsgType[error]
      alice2blockchain.expectMsg(Publish(tx))
      awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
    }
  }

  test("recv error") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_OPEN_WAITING].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_OPEN_WAITING].commitments.ourCommit.publishableTx
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

}
