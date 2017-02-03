package fr.acinq.eclair.channel.states.c

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestBitcoinClient, TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingLockedStateSpec extends TestkitBaseClass {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, router.ref, relayer.ref, Alice.channelParams, Bob.id))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, router.ref, relayer.ref, Bob.channelParams, Alice.id))
    alice ! INPUT_INIT_FUNDER(TestConstants.fundingSatoshis, TestConstants.pushMsat)
    bob ! INPUT_INIT_FUNDEE()
    within(30 seconds) {
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_LOCKED)
    }
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, blockchainA, router))
  }

  test("recv FundingLocked") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NORMAL)
      val channelAnnouncement = router.expectMsgType[ChannelAnnouncement]
      val nodeAnnouncement = router.expectMsgType[NodeAnnouncement]
      val channelUpdate = router.expectMsgType[ChannelUpdate]
      //assert(Router.checkSigs(channelAnnouncement))
      //assert(Router.checkSig(nodeAnnouncement))
      // TODO: test should not use global key
      //assert(Router.checkSig(channelUpdate, TestConstants.Alice.id))
    }
  }

  test("recv FundingLocked (channel id mismatch") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val fundingLocked = bob2alice.expectMsgType[FundingLocked]
      alice ! fundingLocked.copy(channelId = 42)
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      // bob publishes his commitment tx
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, null)
      alice2bob.expectMsgType[Error]
      alice2blockchain.expectMsg(PublishAsap(tx))
      awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
    }
  }

  test("recv Error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }

  test("recv CMD_CLOSE") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_CLOSE_DONE)
    }
  }

}
