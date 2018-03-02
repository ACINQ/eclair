package fr.acinq.eclair.channel.states.a

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{WAIT_FOR_FUNDING_INTERNAL, _}
import fr.acinq.eclair.wire.{AcceptChannel, Error, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.Tag
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForAcceptChannelStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple4[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = if (test.tags.contains("mainnet")) {
      init(TestConstants.Alice.nodeParams.copy(chainHash = Block.LivenetGenesisBlock.hash), TestConstants.Bob.nodeParams.copy(chainHash = Block.LivenetGenesisBlock.hash))
    } else {
      init()
    }
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_CHANNEL)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain))
  }

  test("recv AcceptChannel") { case (alice, _, bob2alice, _) =>
    within(30 seconds) {
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    }
  }

  test("recv AcceptChannel (invalid max accepted htlcs)") { case (alice, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val accept = bob2alice.expectMsgType[AcceptChannel]
      // spec says max = 483
      val invalidMaxAcceptedHtlcs = 484
      alice ! accept.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
      val error = alice2bob.expectMsgType[Error]
      assert(error === Error(accept.temporaryChannelId, InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage.getBytes("UTF-8")))
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv AcceptChannel (invalid dust limit)", Tag("mainnet")) { case (alice, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val accept = bob2alice.expectMsgType[AcceptChannel]
      // we don't want their dust limit to be below 546
      val lowDustLimitSatoshis = 545
      alice ! accept.copy(dustLimitSatoshis = lowDustLimitSatoshis)
      val error = alice2bob.expectMsgType[Error]
      assert(error === Error(accept.temporaryChannelId, InvalidDustLimit(accept.temporaryChannelId, lowDustLimitSatoshis, Channel.MIN_DUSTLIMIT).getMessage.getBytes("UTF-8")))
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv AcceptChannel (to_self_delay too high)") { case (alice, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val accept = bob2alice.expectMsgType[AcceptChannel]
      val delayTooHigh = 10000
      alice ! accept.copy(toSelfDelay = delayTooHigh)
      val error = alice2bob.expectMsgType[Error]
      assert(error === Error(accept.temporaryChannelId, ToSelfDelayTooHigh(accept.temporaryChannelId, delayTooHigh, Alice.nodeParams.maxToLocalDelayBlocks).getMessage.getBytes("UTF-8")))
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv AcceptChannel (reserve too high)") { case (alice, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val accept = bob2alice.expectMsgType[AcceptChannel]
      // 30% is huge, recommended ratio is 1%
      val reserveTooHigh = (0.3 * TestConstants.fundingSatoshis).toLong
      alice ! accept.copy(channelReserveSatoshis = reserveTooHigh)
      val error = alice2bob.expectMsgType[Error]
      assert(error === Error(accept.temporaryChannelId, ChannelReserveTooHigh(accept.temporaryChannelId, reserveTooHigh, 0.3,  0.05).getMessage.getBytes("UTF-8")))
      awaitCond(alice.stateName == CLOSED)
    }
  }

  test("recv Error") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      bob ! Error("00" * 32, "oops".getBytes)
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
