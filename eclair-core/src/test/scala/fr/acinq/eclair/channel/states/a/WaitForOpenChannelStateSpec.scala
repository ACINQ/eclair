package fr.acinq.eclair.channel.states.a

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{AcceptChannel, Error, Init, OpenChannel}
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
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, alice2bob.ref, bobInit, ChannelFlags.Empty)
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

  test("recv OpenChannel (invalid chain)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      // using livenet genesis block
      val livenetChainHash = Block.LivenetGenesisBlock.hash
      bob ! open.copy(chainHash = livenetChainHash)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, new InvalidChainHash(open.temporaryChannelId, Block.RegtestGenesisBlock.hash, livenetChainHash).getMessage.getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv OpenChannel (funding too low)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      val lowFundingMsat = 100
      bob ! open.copy(fundingSatoshis = lowFundingMsat)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, new InvalidFundingAmount(open.temporaryChannelId, lowFundingMsat, Channel.MIN_FUNDING_SATOSHIS, Channel.MAX_FUNDING_SATOSHIS).getMessage.getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv OpenChannel (funding too high)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      val highFundingMsat = 100000000
      bob ! open.copy(fundingSatoshis = highFundingMsat)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, new InvalidFundingAmount(open.temporaryChannelId, highFundingMsat, Channel.MIN_FUNDING_SATOSHIS, Channel.MAX_FUNDING_SATOSHIS).getMessage.getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv OpenChannel (invalid push_msat)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      val invalidPushMsat = 100000000000L
      bob ! open.copy(pushMsat = invalidPushMsat)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, new InvalidPushAmount(open.temporaryChannelId, invalidPushMsat, 1000 * open.fundingSatoshis).getMessage.getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv OpenChannel (to_self_delay too high)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      val delayTooHigh = 10000
      bob ! open.copy(toSelfDelay = delayTooHigh)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, ToSelfDelayTooHigh(open.temporaryChannelId, delayTooHigh, Alice.nodeParams.maxToLocalDelayBlocks).getMessage.getBytes("UTF-8")))
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv OpenChannel (reserve too high)") { case (bob, alice2bob, bob2alice, _) =>
    within(30 seconds) {
      val open = alice2bob.expectMsgType[OpenChannel]
      // 30% is huge, recommended ratio is 1%
      val reserveTooHigh = (0.3 * TestConstants.fundingSatoshis).toLong
      bob ! open.copy(channelReserveSatoshis = reserveTooHigh)
      val error = bob2alice.expectMsgType[Error]
      assert(error === Error(open.temporaryChannelId, new ChannelReserveTooHigh(open.temporaryChannelId, reserveTooHigh, 0.3,  0.05).getMessage.getBytes("UTF-8")))
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
