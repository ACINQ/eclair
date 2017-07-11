package fr.acinq.eclair.channel.states.b

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestBitcoinClient, TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingParentStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, Transaction]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      val makeFundingTx = alice2blockchain.expectMsgType[MakeFundingTx]
      val dummyFundingTx = TestBitcoinClient.makeDummyFundingTx(makeFundingTx)
      alice ! dummyFundingTx
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[PublishAsap]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_PARENT)
      test((alice, alice2bob, bob2alice, alice2blockchain, dummyFundingTx.parentTx))
    }
  }

  test("recv BITCOIN_INPUT_SPENT and then BITCOIN_TX_CONFIRMED") { case (alice, alice2bob, _, alice2blockchain, parentTx) =>
    within(30 seconds) {
      alice ! WatchEventSpent(BITCOIN_INPUT_SPENT(parentTx), parentTx)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(parentTx), 400000, 42)
      alice2bob.expectMsgType[FundingCreated]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
    }
  }

  test("recv Error") { case (bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      bob ! Error("00" * 32, "oops".getBytes)
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
