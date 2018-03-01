package fr.acinq.eclair.channel.states.b

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.Tag
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingCreatedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple4[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val (fundingSatoshis, pushMsat) = if (test.tags.contains("funder_below_reserve")) {
      (1000100L, 1000000000L) // toRemote = 100 satoshis
    } else {
      (TestConstants.fundingSatoshis, TestConstants.pushMsat)
    }
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER("00" * 32, fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    }
    test((bob, alice2bob, bob2alice, bob2blockchain))
  }

  test("recv FundingCreated") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      bob2alice.expectMsgType[FundingSigned]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv FundingCreated (funder can't pay fees)", Tag("funder_below_reserve")) { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val fees = Transactions.commitWeight * TestConstants.feeratePerKw / 1000
      val reserve = Bob.channelParams.channelReserveSatoshis
      val missing = 100 - fees - reserve
      val fundingCreated = alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(fundingCreated.temporaryChannelId, s"can't pay the fee: missingSatoshis=${-1 * missing} reserveSatoshis=$reserve feesSatoshis=$fees".getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv Error") { case (bob, _, _, _) =>
    within(30 seconds) {
      bob ! Error("00" * 32, "oops".getBytes)
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (bob, _, _, _) =>
    within(30 seconds) {
      bob ! CMD_CLOSE(None)
      awaitCond(bob.stateName == CLOSED)
    }
  }

}
