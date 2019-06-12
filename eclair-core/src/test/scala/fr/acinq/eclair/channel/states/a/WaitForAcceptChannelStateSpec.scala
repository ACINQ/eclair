/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.states.a

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{MakeFundingTxResponse, TestWallet}
import fr.acinq.eclair.channel.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{WAIT_FOR_FUNDING_INTERNAL_SIGNED, _}
import fr.acinq.eclair.wire.{AcceptChannel, Error, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class WaitForAcceptChannelStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = if (test.tags.contains("mainnet")) {
      val mainnetWallet = new TestWallet {
        override def getFinalAddress: Future[String] = Future.successful("3LcWzTnuZGPkGkPyX7tfKsktdvMoz4VabR")
      }
      init(TestConstants.Alice.nodeParams.copy(chainHash = Block.LivenetGenesisBlock.hash), TestConstants.Bob.nodeParams.copy(chainHash = Block.LivenetGenesisBlock.hash), wallet = mainnetWallet)
    } else {
      init()
    }
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, alice2bob.ref, bobInit, ChannelFlags.Empty)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv AcceptChannel") { f =>
    import f._
    bob2alice.expectMsgType[AcceptChannel]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL_SIGNED)
  }

  test("recv AcceptChannel (invalid max accepted htlcs)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // spec says max = 483
    val invalidMaxAcceptedHtlcs = 484
    alice ! accept.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv AcceptChannel (invalid dust limit)", Tag("mainnet")) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // we don't want their dust limit to be below 546
    val lowDustLimitSatoshis = 545
    alice ! accept.copy(dustLimitSatoshis = lowDustLimitSatoshis)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, DustLimitTooSmall(accept.temporaryChannelId, lowDustLimitSatoshis, Channel.MIN_DUSTLIMIT).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv AcceptChannel (to_self_delay too high)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val delayTooHigh = 10000
    alice ! accept.copy(toSelfDelay = delayTooHigh)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, ToSelfDelayTooHigh(accept.temporaryChannelId, delayTooHigh, Alice.nodeParams.maxToLocalDelayBlocks).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv AcceptChannel (reserve too high)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // 30% is huge, recommended ratio is 1%
    val reserveTooHigh = (0.3 * TestConstants.fundingSatoshis).toLong
    alice ! accept.copy(channelReserveSatoshis = reserveTooHigh)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, ChannelReserveTooHigh(accept.temporaryChannelId, reserveTooHigh, 0.3, 0.05).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv AcceptChannel (reserve below dust limit)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val reserveTooSmall = accept.dustLimitSatoshis - 1
    alice ! accept.copy(channelReserveSatoshis = reserveTooSmall)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, reserveTooSmall).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv AcceptChannel (reserve below our dust limit)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val open = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].lastSent
    val reserveTooSmall = open.dustLimitSatoshis - 1
    alice ! accept.copy(channelReserveSatoshis = reserveTooSmall)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, reserveTooSmall, open.dustLimitSatoshis).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv AcceptChannel (dust limit above our reserve)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val open = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].lastSent
    val dustTooBig = open.channelReserveSatoshis + 1
    alice ! accept.copy(dustLimitSatoshis = dustTooBig)
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(accept.temporaryChannelId, DustLimitAboveOurChannelReserve(accept.temporaryChannelId, dustTooBig, open.channelReserveSatoshis).getMessage))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv Error") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].unsignedFundingTx.fundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.isEmpty)

    alice ! Error(ByteVector32.Zeroes, "oops")

    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.contains(fundingTx))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].unsignedFundingTx.fundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.isEmpty)

    alice ! CMD_CLOSE(None)

    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.contains(fundingTx))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv TickChannelOpenTimeout") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].unsignedFundingTx.fundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.isEmpty)

    alice ! TickChannelOpenTimeout

    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.contains(fundingTx))
    awaitCond(alice.stateName == CLOSED)
  }

}
