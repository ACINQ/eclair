package fr.acinq.eclair.channel.states.c

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Transaction
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

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    }
    test((alice, bob, alice2bob, bob2alice, alice2blockchain))
  }

  test("recv FundingLocked") { case (alice, bob, _, bob2alice, _) =>
    within(30 seconds) {
      // make bob send a FundingLocked msg
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
      val msg = bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].deferred == Some(msg))
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    }
  }

  test("recv BITCOIN_FUNDING_DEPTHOK") { case (alice, _, alice2bob, _, alice2blockchain) =>
    within(30 seconds) {
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_LOCKED)
      alice2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
    }
  }

  test("recv BITCOIN_FUNDING_PUBLISH_FAILED") { case (alice, _, alice2bob, _, _) =>
    within(30 seconds) {
      alice ! BITCOIN_FUNDING_PUBLISH_FAILED
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv BITCOIN_FUNDING_TIMEOUT") { case (alice, _, alice2bob, _, _) =>
    within(30 seconds) {
      alice ! BITCOIN_FUNDING_TIMEOUT
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == ERR_FUNDING_TIMEOUT)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (remote commit)") { case (alice, bob, _, _, alice2blockchain) =>
    within(30 seconds) {
      // bob publishes his commitment tx
      val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, tx)
      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (other commit)") { case (alice, _, alice2bob, _, alice2blockchain) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, Transaction(0, Nil, Nil, 0))
      alice2bob.expectMsgType[Error]
      alice2blockchain.expectMsg(PublishAsap(tx))
      awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
    }
  }

  test("recv Error") { case (alice, _, _, _, alice2blockchain) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes)
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
    }
  }

  test("recv CMD_CLOSE") { case (alice, _, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg(Failure(CannotCloseInThisState(channelId(alice), WAIT_FOR_FUNDING_CONFIRMED)))
    }
  }

  test("recv CMD_FORCECLOSE") { case (alice, _, _, _, alice2blockchain) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! CMD_FORCECLOSE
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[PublishAsap] // claim-main-delayed
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
    }
  }

}
