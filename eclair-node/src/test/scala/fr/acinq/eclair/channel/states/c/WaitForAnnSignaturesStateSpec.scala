package fr.acinq.eclair.channel.states.c

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForAnnSignaturesStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val (aliceParams, bobParams) = (Alice.channelParams.copy(localFeatures = "01"), Bob.channelParams.copy(localFeatures = "01"))
    val aliceInit = Init(aliceParams.globalFeatures, aliceParams.localFeatures)
    val bobInit = Init(bobParams.globalFeatures, bobParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(0, TestConstants.fundingSatoshis, TestConstants.pushMsat, aliceParams, alice2bob.ref, bobInit)
      bob ! INPUT_INIT_FUNDEE(0, bobParams, bob2alice.ref, aliceInit)
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
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[AnnouncementSignatures]
      awaitCond(alice.stateName == WAIT_FOR_ANN_SIGNATURES)
      awaitCond(bob.stateName == WAIT_FOR_ANN_SIGNATURES)
    }
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, blockchainA, router))
  }

  test("recv AnnouncementSignatures") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      bob2alice.expectMsgType[AnnouncementSignatures]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NORMAL)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      // bob publishes his commitment tx
      val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_ANN_SIGNATURES].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ANN_SIGNATURES].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, null)
      alice2bob.expectMsgType[Error]
      alice2blockchain.expectMsg(PublishAsap(tx))
      awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
    }
  }

  test("recv Error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ANN_SIGNATURES].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }

  test("recv CMD_CLOSE") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _, router) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ANN_SIGNATURES].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }
}
