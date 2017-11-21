package fr.acinq.eclair.channel.states.a

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{Error, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForOpenChannelStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple4[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, bob2alice.ref, aliceInit)
      awaitCond(bob.stateName == WAIT_FOR_OPEN_CHANNEL)
    }
    test((bob, alice2bob, bob2alice, bob2blockchain))
  }

  test("recv OpenChannel") { case (bob, alice2bob, _, _) =>
    within(30 seconds) {
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    }
  }

  test("recv OpenChannel (reserve too high)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      // 30% is huge, recommended ratio is 1%
      val reserveTooHigh = (0.3 * TestConstants.fundingSatoshis).toLong
      bob ! open.copy(channelReserveSatoshis = reserveTooHigh)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, "channelReserveSatoshis too high: reserve=300000 fundingRatio=0.3 maxFundingRatio=0.05".getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv OpenChannel (fee too low)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      // set a very small fee
      val tinyFee = 100
      bob ! open.copy(feeratePerKw = tinyFee)
      alice2bob.forward(bob)
      val error = bob2alice.expectMsgType[Error]
      // we check that the error uses the temporary channel id
      assert(error === Error(open.temporaryChannelId, "local/remote feerates are too different: remoteFeeratePerKw=100 localFeeratePerKw=10000".getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv Error") { case (bob, _, _, _) =>
    within(30 seconds) {
      bob ! Error("00" * 32, "oops".getBytes())
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
