package fr.acinq.eclair.channel.states.c

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{AcceptChannel, Error, FundingCreated, FundingLocked, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingConfirmedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(0, TestConstants.fundingSatoshis, TestConstants.pushMsat, Alice.channelParams, alice2bob.ref, bobInit)
      bob ! INPUT_INIT_FUNDEE(0, Bob.channelParams, bob2alice.ref, aliceInit)
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
      alice2blockchain.expectMsgType[PublishAsap]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    }
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  test("recv FundingLocked") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // make bob send a FundingLocked msg
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
      val msg = bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred == Some(msg))
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    }
  }

  test("recv BITCOIN_FUNDING_DEPTHOK") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
      alice2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
    }
  }

  test("recv BITCOIN_FUNDING_TIMEOUT") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      alice ! BITCOIN_FUNDING_TIMEOUT
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // bob publishes his commitment tx
      val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, null)
      alice2bob.expectMsgType[Error]
      alice2blockchain.expectMsg(PublishAsap(tx))
      awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
    }
  }

  test("recv Error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }

  test("recv CMD_CLOSE") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }

}
