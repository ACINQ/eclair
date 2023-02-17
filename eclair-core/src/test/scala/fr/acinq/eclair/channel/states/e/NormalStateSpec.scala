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

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, OutPoint, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.Features.StaticRemoteKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, CurrentFeerates}
import fr.acinq.eclair.channel.RealScidStatus.Final
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel._
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.DirectedHtlc.{incoming, outgoing}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ClosingSigned, CommitSig, Error, FailureMessageCodecs, PermanentChannelFailure, RevokeAndAck, Shutdown, TemporaryNodeFailure, TlvStream, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc, Warning}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits._

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class NormalStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  private def testRecvCmdAddHtlcEmptyOrigin(f: FixtureParam): Unit = {
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[AvailableBalanceChanged])
    val h = randomBytes32()
    val add = CMD_ADD_HTLC(sender.ref, 50000000 msat, h, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val e = listener.expectMsgType[AvailableBalanceChanged]
    assert(e.commitments.availableBalanceForSend < initialState.commitments.availableBalanceForSend)
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    assert(htlc.id == 0 && htlc.paymentHash == h)
    awaitCond(alice.stateData == initialState
      .modify(_.commitments.changes.localNextHtlcId).setTo(1)
      .modify(_.commitments.changes.localChanges.proposed).setTo(htlc :: Nil)
      .modify(_.commitments.originChannels).setTo(Map(0L -> add.origin)))
  }

  test("recv CMD_ADD_HTLC (empty origin)") { f =>
    testRecvCmdAddHtlcEmptyOrigin(f)
  }

  test("recv CMD_ADD_HTLC (empty origin, dual funding)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    testRecvCmdAddHtlcEmptyOrigin(f)
  }

  test("recv CMD_ADD_HTLC (incrementing ids)") { f =>
    import f._
    val sender = TestProbe()
    val h = randomBytes32()
    for (i <- 0 until 10) {
      alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, h, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
      assert(htlc.id == i && htlc.paymentHash == h)
    }
  }

  test("recv CMD_ADD_HTLC (relayed htlc)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val h = randomBytes32()
    val originHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = h, onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    val origin = Origin.ChannelRelayedHot(sender.ref, originHtlc, originHtlc.amountMsat)
    val cmd = CMD_ADD_HTLC(sender.ref, originHtlc.amountMsat - 10000.msat, h, originHtlc.cltvExpiry - CltvExpiryDelta(7), TestConstants.emptyOnionPacket, None, origin)
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    assert(htlc.id == 0 && htlc.paymentHash == h)
    awaitCond(alice.stateData == initialState
      .modify(_.commitments.changes.localNextHtlcId).setTo(1)
      .modify(_.commitments.changes.localChanges.proposed).setTo(htlc :: Nil)
      .modify(_.commitments.originChannels).setTo(Map(0L -> cmd.origin)))
  }

  test("recv CMD_ADD_HTLC (trampoline relayed htlc)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val h = randomBytes32()
    val originHtlc1 = UpdateAddHtlc(randomBytes32(), 47, 30000000 msat, h, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    val originHtlc2 = UpdateAddHtlc(randomBytes32(), 32, 20000000 msat, h, CltvExpiryDelta(160).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    val origin = Origin.TrampolineRelayedHot(sender.ref, originHtlc1 :: originHtlc2 :: Nil)
    val cmd = CMD_ADD_HTLC(sender.ref, originHtlc1.amountMsat + originHtlc2.amountMsat - 10000.msat, h, originHtlc2.cltvExpiry - CltvExpiryDelta(7), TestConstants.emptyOnionPacket, None, origin)
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    assert(htlc.id == 0 && htlc.paymentHash == h)
    awaitCond(alice.stateData == initialState
      .modify(_.commitments.changes.localNextHtlcId).setTo(1)
      .modify(_.commitments.changes.localChanges.proposed).setTo(htlc :: Nil)
      .modify(_.commitments.originChannels).setTo(Map(0L -> cmd.origin)))
  }

  test("recv CMD_ADD_HTLC (expiry too small)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val expiryTooSmall = CltvExpiry(currentBlockHeight)
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, randomBytes32(), expiryTooSmall, TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = ExpiryTooSmall(channelId(alice), CltvExpiry(currentBlockHeight + 3), expiryTooSmall, currentBlockHeight)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (expiry too big)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val expiryTooBig = (Channel.MAX_CLTV_EXPIRY_DELTA + 1).toCltvExpiry(currentBlockHeight)
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, randomBytes32(), expiryTooBig, TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = ExpiryTooBig(channelId(alice), maximum = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(currentBlockHeight), actual = expiryTooBig, blockHeight = currentBlockHeight)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (value too small)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(sender.ref, 50 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = HtlcValueTooSmall(channelId(alice), 1000 msat, 50 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (0 msat)") { f =>
    import f._
    val sender = TestProbe()
    // Alice has a minimum set to 0 msat (which should be invalid, but may mislead Bob into relaying 0-value HTLCs which is forbidden by the spec).
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.htlcMinimum == 0.msat)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(sender.ref, 0 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add
    val error = HtlcValueTooSmall(channelId(bob), 1 msat, 0 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (increasing balance but still below reserve)", Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._
    val sender = TestProbe()
    // channel starts with all funds on alice's side, alice sends some funds to bob, but not enough to make it go above reserve
    val h = randomBytes32()
    val add = CMD_ADD_HTLC(sender.ref, 50000000 msat, h, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
  }

  test("recv CMD_ADD_HTLC (insufficient funds)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(sender.ref, MilliSatoshi(Int.MaxValue), randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = InsufficientFunds(channelId(alice), amount = MilliSatoshi(Int.MaxValue), missing = 1388843 sat, reserve = 20000 sat, fees = 8960 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (insufficient funds) (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // The anchor outputs commitment format costs more fees for the funder (bigger commit tx + cost of anchor outputs)
    assert(initialState.commitments.availableBalanceForSend < initialState.commitments.modify(_.params.channelFeatures).setTo(ChannelFeatures()).availableBalanceForSend)
    val add = CMD_ADD_HTLC(sender.ref, initialState.commitments.availableBalanceForSend + 1.msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add

    val error = InsufficientFunds(channelId(alice), amount = add.amount, missing = 0 sat, reserve = 20000 sat, fees = 3900 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (insufficient funds, missing 1 msat)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(sender.ref, initialState.commitments.availableBalanceForSend + 1.msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add

    val error = InsufficientFunds(channelId(alice), amount = add.amount, missing = 0 sat, reserve = 10000 sat, fees = 0 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (HTLC dips into remote funder fee reserve)", Tag(ChannelStateTestsTags.NoMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    addHtlc(758640000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.availableBalanceForSend == 0.msat)

    // actual test begins
    // at this point alice has the minimal amount to sustain a channel
    // alice maintains an extra reserve to accommodate for a few more HTLCs, so the first few HTLCs should be allowed
    for (_ <- 1 to 7) {
      bob ! CMD_ADD_HTLC(sender.ref, 12000000 msat, randomBytes32(), CltvExpiry(400144), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    }

    // but this one will dip alice below her reserve: we must wait for the previous HTLCs to settle before sending any more
    val failedAdd = CMD_ADD_HTLC(sender.ref, 11000000 msat, randomBytes32(), CltvExpiry(400144), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! failedAdd
    val error = RemoteCannotAffordFeesForNewHtlc(channelId(bob), failedAdd.amount, missing = 1360 sat, 20000 sat, 22720 sat)
    sender.expectMsg(RES_ADD_FAILED(failedAdd, error, Some(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate)))
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs and 0 balance)", Tag(ChannelStateTestsTags.NoMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_ADD_HTLC(sender.ref, 500000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice ! CMD_ADD_HTLC(sender.ref, 200000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice ! CMD_ADD_HTLC(sender.ref, 51760000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    val add = CMD_ADD_HTLC(sender.ref, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = InsufficientFunds(channelId(alice), amount = 1000000 msat, missing = 1000 sat, reserve = 20000 sat, fees = 12400 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 2/2)", Tag(ChannelStateTestsTags.NoMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_ADD_HTLC(sender.ref, 300000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice ! CMD_ADD_HTLC(sender.ref, 300000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = InsufficientFunds(channelId(alice), amount = 500000000 msat, missing = 348240 sat, reserve = 20000 sat, fees = 12400 sat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (over remote max inflight htlc value)", Tag(ChannelStateTestsTags.AliceLowMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.params.localParams.maxHtlcValueInFlightMsat == initialState.commitments.latest.capacity.toMilliSatoshi)
    assert(initialState.commitments.params.remoteParams.maxHtlcValueInFlightMsat == UInt64(150000000))
    val add = CMD_ADD_HTLC(sender.ref, 151000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add
    val error = HtlcValueTooHighInFlight(channelId(bob), maximum = 150000000 msat, actual = 151000000 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (over remote max inflight htlc value with duplicate amounts)", Tag(ChannelStateTestsTags.AliceLowMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.params.localParams.maxHtlcValueInFlightMsat == initialState.commitments.latest.capacity.toMilliSatoshi)
    assert(initialState.commitments.params.remoteParams.maxHtlcValueInFlightMsat == UInt64(150000000))
    val add = CMD_ADD_HTLC(sender.ref, 75500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    val add1 = CMD_ADD_HTLC(sender.ref, 75500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add1
    val error = HtlcValueTooHighInFlight(channelId(bob), maximum = 150000000 msat, actual = 151000000 msat)
    sender.expectMsg(RES_ADD_FAILED(add1, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (over local max inflight htlc value)", Tag(ChannelStateTestsTags.AliceLowMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.params.localParams.maxHtlcValueInFlightMsat == 150000000.msat)
    assert(initialState.commitments.params.remoteParams.maxHtlcValueInFlightMsat == UInt64(initialState.commitments.latest.capacity.toMilliSatoshi.toLong))
    val add = CMD_ADD_HTLC(sender.ref, 151000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = HtlcValueTooHighInFlight(channelId(alice), maximum = 150000000 msat, actual = 151000000 msat)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (over remote max accepted htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.params.localParams.maxAcceptedHtlcs == 100)
    assert(initialState.commitments.params.remoteParams.maxAcceptedHtlcs == 30) // Bob accepts a maximum of 30 htlcs
    for (_ <- 0 until 30) {
      alice ! CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      alice2bob.expectMsgType[UpdateAddHtlc]
    }
    val add = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = TooManyAcceptedHtlcs(channelId(alice), maximum = 30)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (over local max accepted htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.params.localParams.maxAcceptedHtlcs == 30) // Bob accepts a maximum of 30 htlcs
    assert(initialState.commitments.params.remoteParams.maxAcceptedHtlcs == 100) // Alice accepts more, but Bob will stop at 30 HTLCs
    for (_ <- 0 until 30) {
      bob ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      bob2alice.expectMsgType[UpdateAddHtlc]
    }
    val add = CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add
    val error = TooManyAcceptedHtlcs(channelId(bob), maximum = 30)
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (over max dust htlc exposure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitments = initialState.commitments
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)
    assert(Transactions.offeredHtlcTrimThreshold(aliceCommitments.params.localParams.dustLimit, aliceCommitments.latest.localCommit.spec, aliceCommitments.params.commitmentFormat) == 7730.sat)
    assert(Transactions.receivedHtlcTrimThreshold(aliceCommitments.params.localParams.dustLimit, aliceCommitments.latest.localCommit.spec, aliceCommitments.params.commitmentFormat) == 8130.sat)
    assert(Transactions.offeredHtlcTrimThreshold(aliceCommitments.params.remoteParams.dustLimit, aliceCommitments.latest.localCommit.spec, aliceCommitments.params.commitmentFormat) == 7630.sat)
    assert(Transactions.receivedHtlcTrimThreshold(aliceCommitments.params.remoteParams.dustLimit, aliceCommitments.latest.localCommit.spec, aliceCommitments.params.commitmentFormat) == 8030.sat)

    // Alice sends HTLCs to Bob that add 10 000 sat to the dust exposure:
    addHtlc(500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // dust htlc
    addHtlc(1250.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // trimmed htlc
    addHtlc(8250.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // slightly above the trimmed threshold -> included in the dust exposure
    addHtlc(15000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // way above the trimmed threshold -> not included in the dust exposure
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob sends HTLCs to Alice that add 14 500 sat to the dust exposure:
    addHtlc(300.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // dust htlc
    addHtlc(6000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // trimmed htlc
    addHtlc(8200.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // slightly above the trimmed threshold -> included in the dust exposure
    addHtlc(18000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // way above the trimmed threshold -> not included in the dust exposure
    crossSign(bob, alice, bob2alice, alice2bob)

    // HTLCs that take Alice's dust exposure above her threshold are rejected.
    val dustAdd = CMD_ADD_HTLC(sender.ref, 501.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! dustAdd
    sender.expectMsg(RES_ADD_FAILED(dustAdd, LocalDustHtlcExposureTooHigh(channelId(alice), 25000.sat, 25001.sat.toMilliSatoshi), Some(initialState.channelUpdate)))
    val trimmedAdd = CMD_ADD_HTLC(sender.ref, 5000.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! trimmedAdd
    sender.expectMsg(RES_ADD_FAILED(trimmedAdd, LocalDustHtlcExposureTooHigh(channelId(alice), 25000.sat, 29500.sat.toMilliSatoshi), Some(initialState.channelUpdate)))
    val justAboveTrimmedAdd = CMD_ADD_HTLC(sender.ref, 8500.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! justAboveTrimmedAdd
    sender.expectMsg(RES_ADD_FAILED(justAboveTrimmedAdd, LocalDustHtlcExposureTooHigh(channelId(alice), 25000.sat, 33000.sat.toMilliSatoshi), Some(initialState.channelUpdate)))

    // HTLCs that don't contribute to dust exposure are accepted.
    alice ! CMD_ADD_HTLC(sender.ref, 25000.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
  }

  test("recv CMD_ADD_HTLC (over max dust htlc exposure with pending local changes)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)

    // Alice sends HTLCs to Bob that add 20 000 sat to the dust exposure.
    // She signs them but Bob doesn't answer yet.
    addHtlc(4000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(3000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(7000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(6000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    // Alice sends HTLCs to Bob that add 4 000 sat to the dust exposure.
    addHtlc(2500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(1500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)

    // HTLCs that take Alice's dust exposure above her threshold are rejected.
    val add = CMD_ADD_HTLC(sender.ref, 1001.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    sender.expectMsg(RES_ADD_FAILED(add, LocalDustHtlcExposureTooHigh(channelId(alice), 25000.sat, 25001.sat.toMilliSatoshi), Some(initialState.channelUpdate)))
  }

  test("recv CMD_ADD_HTLC (over max dust htlc exposure in local commit only with pending local changes)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)
    assert(alice.underlyingActor.nodeParams.channelConf.dustLimit == 1100.sat)
    assert(bob.underlyingActor.nodeParams.channelConf.dustLimit == 1000.sat)

    // Alice sends HTLCs to Bob that add 21 000 sat to the dust exposure.
    // She signs them but Bob doesn't answer yet.
    (1 to 20).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice))
    alice ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    // Alice sends HTLCs to Bob that add 3 150 sat to the dust exposure.
    (1 to 3).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice))

    // HTLCs that take Alice's dust exposure above her threshold are rejected.
    val add = CMD_ADD_HTLC(sender.ref, 1050.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    sender.expectMsg(RES_ADD_FAILED(add, LocalDustHtlcExposureTooHigh(channelId(alice), 25000.sat, 25200.sat.toMilliSatoshi), Some(initialState.channelUpdate)))
  }

  test("recv CMD_ADD_HTLC (over max dust htlc exposure in remote commit only with pending local changes)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(bob.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(alice.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 30_000.sat)
    assert(alice.underlyingActor.nodeParams.channelConf.dustLimit == 1100.sat)
    assert(bob.underlyingActor.nodeParams.channelConf.dustLimit == 1000.sat)

    // Bob sends HTLCs to Alice that add 21 000 sat to the dust exposure.
    // He signs them but Alice doesn't answer yet.
    (1 to 20).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob))
    bob ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]

    // Bob sends HTLCs to Alice that add 8400 sat to the dust exposure.
    (1 to 8).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob))

    // HTLCs that take Bob's dust exposure above his threshold are rejected.
    val add = CMD_ADD_HTLC(sender.ref, 1050.sat.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    bob ! add
    sender.expectMsg(RES_ADD_FAILED(add, RemoteDustHtlcExposureTooHigh(channelId(bob), 30000.sat, 30450.sat.toMilliSatoshi), Some(initialState.channelUpdate)))
  }

  test("recv CMD_ADD_HTLC (over capacity)", Tag(ChannelStateTestsTags.NoMaxHtlcValueInFlight)) { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val add1 = CMD_ADD_HTLC(sender.ref, TestConstants.fundingSatoshis.toMilliSatoshi * 2 / 3, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add1
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    // this is over channel-capacity
    val add2 = CMD_ADD_HTLC(sender.ref, TestConstants.fundingSatoshis.toMilliSatoshi * 2 / 3, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add2
    val error = InsufficientFunds(channelId(alice), add2.amount, 578133 sat, 20000 sat, 10680 sat)
    sender.expectMsg(RES_ADD_FAILED(add2, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (channel feerate mismatch)") { f =>
    import f._

    val sender = TestProbe()
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(20000 sat)))
    bob ! CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(20000 sat)))
    bob2alice.expectNoMessage(100 millis) // we don't close because the commitment doesn't contain any HTLC

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val upstream = localOrigin(sender.ref)
    val add = CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, upstream)
    bob ! add
    val error = FeerateTooDifferent(channelId(bob), FeeratePerKw(20000 sat), FeeratePerKw(10000 sat))
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    bob2alice.expectNoMessage(100 millis) // we don't close the channel, we can simply avoid using it while we disagree on feerate

    // we now agree on feerate so we can send HTLCs
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob ! CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob2alice.expectNoMessage(100 millis)
    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
  }

  test("recv CMD_ADD_HTLC (after having sent Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined && alice.stateData.asInstanceOf[DATA_NORMAL].remoteShutdown.isEmpty)

    // actual test starts here
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    val error = NoMoreHtlcsClosingInProgress(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, Some(initialState.channelUpdate)))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_ADD_HTLC (after having received Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // let's make alice send an htlc
    val add1 = CMD_ADD_HTLC(sender.ref, 50000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add1
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    // at the same time bob initiates a closing
    bob ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    // this command will be received by alice right after having received the shutdown
    val add2 = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32(), CltvExpiry(300000), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    // messages cross
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    alice ! add2
    val error = NoMoreHtlcsClosingInProgress(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add2, error, Some(initialState.channelUpdate)))
  }

  test("recv UpdateAddHtlc") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, 150000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    bob ! htlc
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ htlc)
      .modify(_.commitments.changes.remoteNextHtlcId).setTo(1))
    // bob won't forward the add before it is cross-signed
    bob2relayer.expectNoMessage()
  }

  test("recv UpdateAddHtlc (unexpected id)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 42, 150000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    bob ! htlc.copy(id = 0)
    bob ! htlc.copy(id = 1)
    bob ! htlc.copy(id = 2)
    bob ! htlc.copy(id = 3)
    bob ! htlc.copy(id = 42)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == UnexpectedHtlcId(channelId(bob), expected = 4, actual = 42).getMessage)
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateAddHtlc (value too small)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, 150 msat, randomBytes32(), cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    alice2bob.forward(bob, htlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == HtlcValueTooSmall(channelId(bob), minimum = 1000 msat, actual = 150 msat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateAddHtlc (insufficient funds)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliSatoshi(Long.MaxValue), randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    alice2bob.forward(bob, htlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == InsufficientFunds(channelId(bob), amount = MilliSatoshi(Long.MaxValue), missing = 9223372036083735L sat, reserve = 20000 sat, fees = 8960 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs) (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 400000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 1, 300000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 2, 100000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == InsufficientFunds(channelId(bob), amount = 100000000 msat, missing = 24760 sat, reserve = 20000 sat, fees = 4760 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 1/2)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 400000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 1, 200000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 2, 167600000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 3, 10000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == InsufficientFunds(channelId(bob), amount = 10000000 msat, missing = 11720 sat, reserve = 20000 sat, fees = 14120 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 2/2)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 300000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 1, 300000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 2, 500000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == InsufficientFunds(channelId(bob), amount = 500000000 msat, missing = 332400 sat, reserve = 20000 sat, fees = 12400 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateAddHtlc (over max inflight htlc value)", Tag(ChannelStateTestsTags.AliceLowMaxHtlcValueInFlight)) { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice2bob.forward(alice, UpdateAddHtlc(ByteVector32.Zeroes, 0, 151000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) == HtlcValueTooHighInFlight(channelId(alice), maximum = 150000000 msat, actual = 151000000 msat).getMessage)
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateAddHtlc (over max accepted htlcs)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    // Bob accepts a maximum of 30 htlcs
    for (i <- 0 until 30) {
      alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, i, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    }
    alice2bob.forward(bob, UpdateAddHtlc(ByteVector32.Zeroes, 30, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == TooManyAcceptedHtlcs(channelId(bob), maximum = 30).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CMD_SIGN") { f =>
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
  }

  test("recv CMD_SIGN (two identical htlcs in each direction)") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)

    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    bob ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)

    // actual test starts here
    bob ! CMD_SIGN()
    val commitSig = bob2alice.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.toSet.size == 4)
  }

  test("recv CMD_SIGN (check htlc info are persisted)") { f =>
    import f._
    val sender = TestProbe()
    // for the test to be really useful we have constraint on parameters
    assert(Alice.nodeParams.channelConf.dustLimit > Bob.nodeParams.channelConf.dustLimit)
    // and a low feerate to avoid messing with dust exposure limits
    val currentFeerate = FeeratePerKw(2500 sat)
    alice.feeEstimator.setFeerate(FeeratesPerKw.single(currentFeerate))
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(currentFeerate))
    updateFee(currentFeerate, alice, bob, alice2bob, bob2alice)
    // we're gonna exchange two htlcs in each direction, the goal is to have bob's commitment have 4 htlcs, and alice's
    // commitment only have 3. We will then check that alice indeed persisted 4 htlcs, and bob only 3.
    val aliceMinReceive = Alice.nodeParams.channelConf.dustLimit + weight2fee(currentFeerate, DefaultCommitmentFormat.htlcSuccessWeight)
    val aliceMinOffer = Alice.nodeParams.channelConf.dustLimit + weight2fee(currentFeerate, DefaultCommitmentFormat.htlcTimeoutWeight)
    val bobMinReceive = Bob.nodeParams.channelConf.dustLimit + weight2fee(currentFeerate, DefaultCommitmentFormat.htlcSuccessWeight)
    val bobMinOffer = Bob.nodeParams.channelConf.dustLimit + weight2fee(currentFeerate, DefaultCommitmentFormat.htlcTimeoutWeight)
    val a2b_1 = bobMinReceive + 10.sat // will be in alice and bob tx
    val a2b_2 = bobMinReceive + 20.sat // will be in alice and bob tx
    val b2a_1 = aliceMinReceive + 10.sat // will be in alice and bob tx
    val b2a_2 = bobMinOffer + 10.sat // will be only be in bob tx
    assert(a2b_1 > aliceMinOffer && a2b_1 > bobMinReceive)
    assert(a2b_2 > aliceMinOffer && a2b_2 > bobMinReceive)
    assert(b2a_1 > aliceMinReceive && b2a_1 > bobMinOffer)
    assert(b2a_2 < aliceMinReceive && b2a_2 > bobMinOffer)
    alice ! CMD_ADD_HTLC(sender.ref, a2b_1.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! CMD_ADD_HTLC(sender.ref, a2b_2.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    bob ! CMD_ADD_HTLC(sender.ref, b2a_1.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    bob ! CMD_ADD_HTLC(sender.ref, b2a_2.toMilliSatoshi, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)

    // actual test starts here
    crossSign(alice, bob, alice2bob, bob2alice)
    // depending on who starts signing first, there will be one or two commitments because both sides have changes
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.index == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.index == 3)
    assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 0)
    assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 2).size == 2)
    assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 3).size == 4)
    assert(bob.underlyingActor.nodeParams.db.channels.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 0)
    assert(bob.underlyingActor.nodeParams.db.channels.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 2).size == 3)
  }

  test("recv CMD_SIGN (htlcs with same pubkeyScript but different amounts)") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    val epsilons = List(3, 1, 5, 7, 6) // unordered on purpose
    val htlcCount = epsilons.size
    for (i <- epsilons) {
      alice ! add.copy(amount = add.amount + (i * 1000).msat)
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
    }
    // actual test starts here
    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.toSet.size == htlcCount)
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == htlcCount)
    val htlcTxs = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs
    val amounts = htlcTxs.map(_.htlcTx.tx.txOut.head.amount.toLong)
    assert(amounts == amounts.sorted)
  }

  test("recv CMD_SIGN (no changes)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_SIGN()
    sender.expectNoMessage(1 second) // just ignored
    //sender.expectMsg("cannot sign when there are no changes")
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck (no pending changes)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get

    // actual test starts here
    alice ! CMD_SIGN()
    sender.expectNoMessage(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo == Left(waitForRevocation))
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck (with pending changes)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get

    // actual test starts here
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    sender.expectNoMessage(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo == Left(waitForRevocation))
  }

  test("recv CMD_SIGN (going above reserve)", Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._
    // channel starts with all funds on alice's side, so channel will be initially disabled on bob's side
    assert(!bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags.isEnabled)
    // alice will send enough funds to bob to make it go above reserve
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FULFILL_HTLC(htlc.id, r)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    // we listen to channel_update events
    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    // actual test starts here
    // when signing the fulfill, bob will have its main output go above reserve in alice's commitment tx
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    // it should update its channel_update
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags.isEnabled)
    // and broadcast it
    assert(listener.expectMsgType[LocalChannelUpdate].channelUpdate == bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate)
  }

  test("recv CMD_SIGN (after CMD_UPDATE_FEE)") { f =>
    import f._
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[AvailableBalanceChanged])
    alice ! CMD_UPDATE_FEE(FeeratePerKw(654564 sat))
    alice2bob.expectMsgType[UpdateFee]
    alice ! CMD_SIGN()
    listener.expectMsgType[AvailableBalanceChanged]
  }

  test("recv CommitSig (one htlc received)") { f =>
    import f._

    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    alice ! CMD_SIGN()

    // actual test begins
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    bob2alice.expectMsgType[RevokeAndAck]
    // bob replies immediately with a signature
    bob2alice.expectMsgType[CommitSig]

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.htlcs.collect(incoming).exists(_.id == htlc.id))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == initialState.commitments.latest.localCommit.spec.toLocal)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteChanges.acked.size == 0)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteChanges.signed.size == 1)
  }

  test("recv CommitSig (one htlc sent)") { f =>
    import f._

    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins (note that channel sends a CMD_SIGN to itself when it receives RevokeAndAck and there are changes)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.htlcs.collect(outgoing).exists(_.id == htlc.id))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == initialState.commitments.latest.localCommit.spec.toLocal)
  }

  test("recv CommitSig (multiple htlcs in both directions)") { f =>
    import f._

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(80000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(1200000 msat, bob, alice, bob2alice, alice2bob) // b->a (trimmed to dust)
    addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)
    addHtlc(1200000 msat, alice, bob, alice2bob, bob2alice) // a->b (trimmed to dust)
    addHtlc(40000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)

    alice ! CMD_SIGN()
    val aliceCommitSig = alice2bob.expectMsgType[CommitSig]
    assert(aliceCommitSig.htlcSignatures.length == 3)
    alice2bob.forward(bob, aliceCommitSig)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins
    val bobCommitSig = bob2alice.expectMsgType[CommitSig]
    assert(bobCommitSig.htlcSignatures.length == 5)
    bob2alice.forward(alice, bobCommitSig)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.index == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 5)
  }

  test("recv CommitSig (multiple htlcs in both directions) (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(1100000 msat, alice, bob, alice2bob, bob2alice) // a->b (trimmed to dust)
    addHtlc(999999 msat, bob, alice, bob2alice, alice2bob) // b->a (dust)
    addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)
    addHtlc(999999 msat, alice, bob, alice2bob, bob2alice) // a->b (dust)
    addHtlc(1100000 msat, bob, alice, bob2alice, alice2bob) // b->a (trimmed to dust)

    alice ! CMD_SIGN()
    val aliceCommitSig = alice2bob.expectMsgType[CommitSig]
    assert(aliceCommitSig.htlcSignatures.length == 2)
    alice2bob.forward(bob, aliceCommitSig)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins
    val bobCommitSig = bob2alice.expectMsgType[CommitSig]
    assert(bobCommitSig.htlcSignatures.length == 3)
    bob2alice.forward(alice, bobCommitSig)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.index == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 3)
  }

  test("recv CommitSig (multiple htlcs in both directions) (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(1100000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(999999 msat, bob, alice, bob2alice, alice2bob) // b->a (dust)
    addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)
    addHtlc(999999 msat, alice, bob, alice2bob, bob2alice) // a->b (dust)
    addHtlc(1100000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)

    alice ! CMD_SIGN()
    val aliceCommitSig = alice2bob.expectMsgType[CommitSig]
    assert(aliceCommitSig.htlcSignatures.length == 3)
    alice2bob.forward(bob, aliceCommitSig)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins
    val bobCommitSig = bob2alice.expectMsgType[CommitSig]
    assert(bobCommitSig.htlcSignatures.length == 5)
    bob2alice.forward(alice, bobCommitSig)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.index == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 5)
  }

  test("recv CommitSig (multiple htlcs in both directions) (without fundingTxId tlv)") { f =>
    import f._

    addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(1_100_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(1_100_000 msat, bob, alice, bob2alice, alice2bob)

    alice ! CMD_SIGN()
    val aliceCommitSig = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, aliceCommitSig.copy(tlvStream = TlvStream.empty))
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    val bobCommitSig = bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice, bobCommitSig.copy(tlvStream = TlvStream.empty))

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommitIndex == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommitIndex == 1)
  }

  test("recv CommitSig (only fee update)") { f =>
    import f._

    alice ! CMD_UPDATE_FEE(TestConstants.feeratePerKw + FeeratePerKw(1000 sat), commit = false)
    alice ! CMD_SIGN()

    // actual test begins (note that channel sends a CMD_SIGN to itself when it receives RevokeAndAck and there are changes)
    val updateFee = alice2bob.expectMsgType[UpdateFee]
    assert(updateFee.feeratePerKw == TestConstants.feeratePerKw + FeeratePerKw(1000 sat))
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
  }

  test("recv CommitSig (two htlcs received with same r)") { f =>
    import f._
    val sender = TestProbe()
    val r = randomBytes32()
    val h = Crypto.sha256(r)

    alice ! CMD_ADD_HTLC(sender.ref, 50000000 msat, h, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    alice ! CMD_ADD_HTLC(sender.ref, 50000000 msat, h, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    crossSign(alice, bob, alice2bob, bob2alice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.htlcs.collect(incoming).exists(_.id == htlc1.id))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.htlcTxsAndRemoteSigs.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == initialState.commitments.latest.localCommit.spec.toLocal)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.count(_.amount == 50000.sat) == 2)
  }

  ignore("recv CommitSig (no changes)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    // signature is invalid but it doesn't matter
    bob ! CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("cannot sign when there are no changes"))
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CommitSig (invalid signature)") { f =>
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx

    // actual test begins
    bob ! CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid commitment signature"))
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CommitSig (bad htlc sig count)") { f =>
    import f._

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx

    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val badCommitSig = commitSig.copy(htlcSignatures = commitSig.htlcSignatures ::: commitSig.htlcSignatures)
    bob ! badCommitSig
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == HtlcSigCountMismatch(channelId(bob), expected = 1, actual = 2).getMessage)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CommitSig (invalid htlc sig)") { f =>
    import f._

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx

    alice ! CMD_SIGN()
    val commitSig = alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val badCommitSig = commitSig.copy(htlcSignatures = commitSig.signature :: Nil)
    bob ! badCommitSig
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).startsWith("invalid htlc signature"))
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv RevokeAndAck (one htlc sent)") { f =>
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.localChanges.acked.size == 1)
  }

  test("recv RevokeAndAck (one htlc received)") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // at this point bob still hasn't forwarded the htlc downstream
    bob2relayer.expectNoMessage()

    // actual test begins
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    // now bob will forward the htlc downstream
    val forward = bob2relayer.expectMsgType[RelayForward]
    assert(forward.add == htlc)
  }

  test("recv RevokeAndAck (multiple htlcs in both directions)") { f =>
    import f._

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice) // a->b (regular)
    addHtlc(8000000 msat, alice, bob, alice2bob, bob2alice) //  a->b (regular)
    addHtlc(300000 msat, bob, alice, bob2alice, alice2bob) //   b->a (dust)
    addHtlc(1000000 msat, alice, bob, alice2bob, bob2alice) //  a->b (regular)
    addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob) // b->a (regular)
    addHtlc(500000 msat, alice, bob, alice2bob, bob2alice) //   a->b (dust)
    addHtlc(4000000 msat, bob, alice, bob2alice, alice2bob) //  b->a (regular)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // actual test begins
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommitIndex == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.remoteCommit.spec.htlcs.size == 7)
  }

  test("recv RevokeAndAck (with pending changes)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    sender.expectNoMessage(300 millis)

    // actual test starts here
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
  }

  test("recv RevokeAndAck (invalid preimage)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    alice ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32()), PrivateKey(randomBytes32()).publicKey)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv RevokeAndAck (over max dust htlc exposure)") { f =>
    import f._
    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)
    assert(Transactions.offeredHtlcTrimThreshold(aliceCommitments.params.localParams.dustLimit, aliceCommitments.latest.localCommit.spec, aliceCommitments.params.commitmentFormat) == 7730.sat)
    assert(Transactions.receivedHtlcTrimThreshold(aliceCommitments.params.remoteParams.dustLimit, aliceCommitments.latest.localCommit.spec, aliceCommitments.params.commitmentFormat) == 8030.sat)

    // Alice sends HTLCs to Bob that add 10 000 sat to the dust exposure:
    addHtlc(500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // dust htlc
    addHtlc(1250.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // trimmed htlc
    addHtlc(8250.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice) // slightly above the trimmed threshold -> included in the dust exposure
    crossSign(alice, bob, alice2bob, bob2alice)

    // Bob sends HTLCs to Alice that overflow the dust exposure:
    val (_, dust1) = addHtlc(500.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // dust htlc
    val (_, dust2) = addHtlc(500.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // dust htlc
    val (_, trimmed1) = addHtlc(4000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // trimmed htlc
    val (_, trimmed2) = addHtlc(6400.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // trimmed htlc
    val (_, almostTrimmed) = addHtlc(8500.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // slightly above the trimmed threshold -> included in the dust exposure
    val (_, nonDust) = addHtlc(20000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob) // way above the trimmed threshold -> not included in the dust exposure
    crossSign(bob, alice, bob2alice, alice2bob)

    // Alice forwards HTLCs that fit in the dust exposure.
    alice2relayer.expectMsgAllOf(
      RelayForward(nonDust),
      RelayForward(almostTrimmed),
      RelayForward(trimmed2),
    )
    alice2relayer.expectNoMessage(100 millis)
    // And instantly fails the others.
    val failedHtlcs = Seq(
      alice2bob.expectMsgType[UpdateFailHtlc],
      alice2bob.expectMsgType[UpdateFailHtlc],
      alice2bob.expectMsgType[UpdateFailHtlc]
    )
    assert(failedHtlcs.map(_.id).toSet == Set(dust1.id, dust2.id, trimmed1.id))
    alice2bob.expectMsgType[CommitSig]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv RevokeAndAck (over max dust htlc exposure with pending local changes)") { f =>
    import f._
    val sender = TestProbe()
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)

    // Bob sends HTLCs to Alice that add 10 000 sat to the dust exposure.
    addHtlc(4000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    addHtlc(6000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsgType[RelayForward]
    alice2relayer.expectMsgType[RelayForward]

    // Alice sends HTLCs to Bob that add 10 000 sat to the dust exposure but doesn't sign them yet.
    addHtlc(6500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(3500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)

    // Bob sends HTLCs to Alice that add 10 000 sat to the dust exposure.
    val (_, rejectedHtlc) = addHtlc(7000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    val (_, acceptedHtlc) = addHtlc(3000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // Alice forwards HTLCs that fit in the dust exposure and instantly fails the others.
    alice2relayer.expectMsg(RelayForward(acceptedHtlc))
    alice2relayer.expectNoMessage(100 millis)
    assert(alice2bob.expectMsgType[UpdateFailHtlc].id == rejectedHtlc.id)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.expectNoMessage(100 millis)
  }

  def testRevokeAndAckDustOverflowSingleCommit(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)

    // Bob sends HTLCs to Alice that add 10 500 sat to the dust exposure.
    (1 to 10).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob))
    crossSign(bob, alice, bob2alice, alice2bob)
    (1 to 10).foreach(_ => alice2relayer.expectMsgType[RelayForward])

    // Alice sends HTLCs to Bob that add 10 500 sat to the dust exposure but doesn't sign them yet.
    (1 to 10).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice))

    // Bob sends HTLCs to Alice that add 8 400 sat to the dust exposure.
    (1 to 8).foreach(_ => addHtlc(1050.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob))
    bob ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // Alice forwards HTLCs that fit in the dust exposure and instantly fails the others.
    (1 to 3).foreach(_ => alice2relayer.expectMsgType[RelayForward])
    alice2relayer.expectNoMessage(100 millis)
    (1 to 5).foreach(_ => alice2bob.expectMsgType[UpdateFailHtlc])
    alice2bob.expectMsgType[CommitSig]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv RevokeAndAck (over max dust htlc exposure in local commit only with pending local changes)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob)) { f =>
    import f._
    assert(alice.underlyingActor.nodeParams.channelConf.dustLimit == 5000.sat)
    assert(bob.underlyingActor.nodeParams.channelConf.dustLimit == 1000.sat)
    testRevokeAndAckDustOverflowSingleCommit(f)
  }

  test("recv RevokeAndAck (over max dust htlc exposure in remote commit only with pending local changes)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice)) { f =>
    import f._
    assert(alice.underlyingActor.nodeParams.channelConf.dustLimit == 1000.sat)
    assert(bob.underlyingActor.nodeParams.channelConf.dustLimit == 5000.sat)
    testRevokeAndAckDustOverflowSingleCommit(f)
  }

  test("recv RevokeAndAck (unexpectedly)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    alice ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32()), PrivateKey(randomBytes32()).publicKey)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv RevokeAndAck (forward UpdateFailHtlc)") { f =>
    import f._
    val (_, htlc) = addHtlc(150000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure()))
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice still hasn't forwarded the fail because it is not yet cross-signed
    alice2relayer.expectNoMessage()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFail]]
    assert(forward.result.fail == fail)
    assert(forward.htlc == htlc)
  }

  test("recv RevokeAndAck (forward UpdateFailMalformedHtlc)") { f =>
    import f._
    val (_, htlc) = addHtlc(150000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.hash(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION)
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice still hasn't forwarded the fail because it is not yet cross-signed
    alice2relayer.expectNoMessage()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFailMalformed]]
    assert(forward.result.fail == fail)
    assert(forward.htlc == htlc)
  }

  def testRevokeAndAckHtlcStaticRemoteKey(f: FixtureParam): Unit = {
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.initFeatures.hasFeature(StaticRemoteKey))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.initFeatures.hasFeature(StaticRemoteKey))

    def aliceToRemoteScript(): ByteVector = {
      val toRemoteAmount = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote
      val Some(toRemoteOut) = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.find(_.amount == toRemoteAmount.truncateToSatoshi)
      toRemoteOut.publicKeyScript
    }

    val initialToRemoteScript = aliceToRemoteScript()

    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    awaitCond(alice.stateName == NORMAL)
    // using option_static_remotekey alice's view of bob toRemote script stays the same across commitments
    assert(initialToRemoteScript == aliceToRemoteScript())
  }

  test("recv RevokeAndAck (one htlc sent, static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) {
    testRevokeAndAckHtlcStaticRemoteKey _
  }

  test("recv RevokeAndAck (one htlc sent, anchor_outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    testRevokeAndAckHtlcStaticRemoteKey _
  }

  test("recv RevokeAndAck (one htlc sent, anchors_zero_fee_htlc_tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testRevokeAndAckHtlcStaticRemoteKey _
  }

  test("recv RevocationTimeout") { f =>
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val peer = TestProbe()
    alice ! RevocationTimeout(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommitIndex, peer.ref)
    peer.expectMsg(Peer.Disconnect(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId))
  }

  private def testReceiveCmdFulfillHtlc(f: FixtureParam): Unit = {
    import f._

    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FULFILL_HTLC(htlc.id, r)
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    awaitCond(bob.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fulfill))
  }

  test("recv CMD_FULFILL_HTLC") {
    testReceiveCmdFulfillHtlc _
  }

  test("recv CMD_FULFILL_HTLC (static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) {
    testReceiveCmdFulfillHtlc _
  }

  test("recv CMD_FULFILL_HTLC (anchor_outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    testReceiveCmdFulfillHtlc _
  }

  test("recv CMD_FULFILL_HTLC (anchors_zero_fee_htlc_tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testReceiveCmdFulfillHtlc _
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val r = randomBytes32()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FULFILL_HTLC(42, r, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_FULFILL_HTLC(htlc.id, ByteVector32.Zeroes, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidHtlcPreimage(channelId(bob), 0)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (acknowledge in case of success)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_FULFILL_HTLC(htlc.id, r, replyTo_opt = Some(sender.ref))
    // this would be done automatically when the relayer calls safeSend
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, c)
    bob ! c
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    bob2alice.expectMsgType[CommitSig]
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  test("recv CMD_FULFILL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FULFILL_HTLC(42, randomBytes32(), replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  private def testUpdateFulfillHtlc(f: FixtureParam): Unit = {
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FULFILL_HTLC(htlc.id, r)
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.forward(alice)
    awaitCond(alice.stateData == initialState.modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fulfill))
    // alice immediately propagates the fulfill upstream
    val forward = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFulfill]]
    assert(forward.result.fulfill == fulfill)
    assert(forward.htlc == htlc)
  }

  test("recv UpdateFulfillHtlc") {
    testUpdateFulfillHtlc _
  }

  test("recv UpdateFulfillHtlc (static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) {
    testUpdateFulfillHtlc _
  }

  test("recv UpdateFulfillHtlc (anchor_outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    testUpdateFulfillHtlc _
  }

  test("recv UpdateFulfillHtlc (anchors_zero_fee_htlc_tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testUpdateFulfillHtlc _
  }

  test("recv UpdateFulfillHtlc (sender has not signed htlc)") { f =>
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, htlc.id, r)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, 42, ByteVector32.Zeroes)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob2relayer.expectMsgType[RelayForward]
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx

    // actual test begins
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, htlc.id, ByteVector32.Zeroes)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  private def testCmdFailHtlc(f: FixtureParam): Unit = {
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val cmd = CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure()))
    val Right(fail) = OutgoingPaymentPacket.buildHtlcFailure(Bob.nodeParams.privateKey, cmd, htlc)
    assert(fail.id == htlc.id)
    bob ! cmd
    bob2alice.expectMsg(fail)
    awaitCond(bob.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fail))
  }

  test("recv CMD_FAIL_HTLC") {
    testCmdFailHtlc _
  }

  test("recv CMD_FAIL_HTLC (static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) {
    testCmdFailHtlc _
  }

  test("recv CMD_FAIL_HTLC (anchor_outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    testCmdFailHtlc _
  }

  test("recv CMD_FAIL_HTLC (anchors_zero_fee_htlc_tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testCmdFailHtlc _
  }

  test("recv CMD_FAIL_HTLC (with delay)") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val cmd = CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure()), delay_opt = Some(50 millis))
    val Right(fail) = OutgoingPaymentPacket.buildHtlcFailure(Bob.nodeParams.privateKey, cmd, htlc)
    assert(fail.id == htlc.id)
    bob ! cmd
    bob2alice.expectMsg(fail)
    awaitCond(bob.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fail))
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_HTLC(42, Right(PermanentChannelFailure()), replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_HTLC (htlc pending fulfill)") { f =>
    import f._

    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // HTLC is fulfilled but alice doesn't send its revocation.
    bob ! CMD_FULFILL_HTLC(htlc.id, r)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.expectMsgType[CommitSig]

    // We cannot fail the HTLC, we must wait for the fulfill to be acked.
    val c = CMD_FAIL_HTLC(htlc.id, Right(TemporaryNodeFailure()), replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), htlc.id)))
  }

  test("recv CMD_FAIL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_HTLC(42, Right(PermanentChannelFailure()), replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.hash(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION)
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    awaitCond(bob.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fail))
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, FailureMessageCodecs.BADONION, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, 42, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidFailureCode(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    val c = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, FailureMessageCodecs.BADONION, replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  private def testUpdateFailHtlc(f: FixtureParam): Unit = {
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure()))
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.forward(alice)
    awaitCond(alice.stateData == initialState.modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fail))
    // alice won't forward the fail before it is cross-signed
    alice2relayer.expectNoMessage()
  }

  test("recv UpdateFailHtlc") {
    testUpdateFailHtlc _
  }

  test("recv UpdateFailHtlc (static_remotekey)", Tag(ChannelStateTestsTags.StaticRemoteKey)) {
    testUpdateFailHtlc _
  }

  test("recv UpdateFailHtlc (anchor_outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    testUpdateFailHtlc _
  }

  test("recv UpdateFailHtlc (anchors_zero_fee_htlc_tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testUpdateFailHtlc _
  }

  test("recv UpdateFailMalformedHtlc") { f =>
    import f._

    // Alice sends an HTLC to Bob, which they both sign
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Bob fails the HTLC because he cannot parse it
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob ! CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.hash(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION)
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)

    awaitCond(alice.stateData == initialState.modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fail))
    // alice won't forward the fail before it is cross-signed
    alice2relayer.expectNoMessage()

    bob ! CMD_SIGN()
    val sig = bob2alice.expectMsgType[CommitSig]
    // Bob should not have the htlc in its remote commit anymore
    assert(sig.htlcSignatures.isEmpty)

    // and Alice should accept this signature
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
  }

  test("recv UpdateFailMalformedHtlc (invalid failure_code)") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val fail = UpdateFailMalformedHtlc(ByteVector32.Zeroes, htlc.id, Sphinx.hash(htlc.onionRoutingPacket), 42)
    alice ! fail
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) == InvalidFailureCode(ByteVector32.Zeroes).getMessage)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFailHtlc (sender has not signed htlc)") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! UpdateFailHtlc(ByteVector32.Zeroes, htlc.id, ByteVector.fill(152)(0))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! UpdateFailHtlc(ByteVector32.Zeroes, 42, ByteVector.fill(152)(0))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFailHtlc (onion error bigger than recommended value)") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Bob receives a failure with a completely invalid onion error (missing mac)
    bob ! CMD_FAIL_HTLC(htlc.id, Left(ByteVector.fill(561)(42)))
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    assert(fail.id == htlc.id)
    // We propagate failure upstream (hopefully the sender knows how to unwrap them).
    assert(fail.reason.length == 561)
  }

  private def testCmdUpdateFee(f: FixtureParam): Unit = {
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat))
    val fee = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fee))
  }

  test("recv CMD_UPDATE_FEE") {
    testCmdUpdateFee _
  }

  test("recv CMD_UPDATE_FEE (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testCmdUpdateFee _
  }

  test("recv CMD_UPDATE_FEE (over max dust htlc exposure)") { f =>
    import f._

    // Alice sends HTLCs to Bob that are not included in the dust exposure at the current feerate:
    addHtlc(13000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(14000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(DustExposure.computeExposure(aliceCommitments.latest.localCommit.spec, aliceCommitments.params.localParams.dustLimit, aliceCommitments.params.commitmentFormat) == 0.msat)
    assert(DustExposure.computeExposure(aliceCommitments.latest.remoteCommit.spec, aliceCommitments.params.remoteParams.dustLimit, aliceCommitments.params.commitmentFormat) == 0.msat)

    // A large feerate increase would make these HTLCs overflow alice's dust exposure, so she rejects it:
    val sender = TestProbe()
    val cmd = CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    alice ! cmd
    sender.expectMsg(RES_FAILURE(cmd, LocalDustHtlcExposureTooHigh(channelId(alice), 25000 sat, 27000000 msat)))
  }

  test("recv CMD_UPDATE_FEE (over max dust htlc exposure with pending local changes)") { f =>
    import f._
    val sender = TestProbe()
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)

    // Alice sends an HTLC to Bob that is not included in the dust exposure at the current feerate.
    // She signs them but Bob doesn't answer yet.
    addHtlc(13000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    // Alice sends another HTLC to Bob that is not included in the dust exposure at the current feerate.
    addHtlc(14000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(DustExposure.computeExposure(aliceCommitments.latest.localCommit.spec, aliceCommitments.params.localParams.dustLimit, aliceCommitments.params.commitmentFormat) == 0.msat)
    assert(DustExposure.computeExposure(aliceCommitments.latest.remoteCommit.spec, aliceCommitments.params.remoteParams.dustLimit, aliceCommitments.params.commitmentFormat) == 0.msat)

    // A large feerate increase would make these HTLCs overflow alice's dust exposure, so she rejects it:
    val cmd = CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    alice ! cmd
    sender.expectMsg(RES_FAILURE(cmd, LocalDustHtlcExposureTooHigh(channelId(alice), 25000 sat, 27000000 msat)))
  }

  def testCmdUpdateFeeDustOverflowSingleCommit(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    // We start with a low feerate.
    val initialFeerate = FeeratePerKw(500 sat)
    alice.feeEstimator.setFeerate(FeeratesPerKw.single(initialFeerate))
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(initialFeerate))
    updateFee(initialFeerate, alice, bob, alice2bob, bob2alice)
    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)
    val higherDustLimit = Seq(aliceCommitments.params.localParams.dustLimit, aliceCommitments.params.remoteParams.dustLimit).max
    val lowerDustLimit = Seq(aliceCommitments.params.localParams.dustLimit, aliceCommitments.params.remoteParams.dustLimit).min
    // We have the following dust thresholds at the current feerate
    assert(Transactions.offeredHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 6989.sat)
    assert(Transactions.receivedHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 7109.sat)
    assert(Transactions.offeredHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 2989.sat)
    assert(Transactions.receivedHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 3109.sat)
    // And the following thresholds after the feerate update
    // NB: we apply the real feerate when sending update_fee, not the one adjusted for dust
    val updatedFeerate = FeeratePerKw(4000 sat)
    assert(Transactions.offeredHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 7652.sat)
    assert(Transactions.receivedHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 7812.sat)
    assert(Transactions.offeredHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 3652.sat)
    assert(Transactions.receivedHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 3812.sat)

    // Alice send HTLCs to Bob that are not included in the dust exposure at the current feerate.
    // She signs them but Bob doesn't answer yet.
    (1 to 2).foreach(_ => addHtlc(7400.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice))
    alice ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    // Alice sends other HTLCs to Bob that are not included in the dust exposure at the current feerate, without signing them.
    (1 to 2).foreach(_ => addHtlc(7400.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice))

    // A feerate increase makes these HTLCs become dust in one of the commitments but not the other.
    val cmd = CMD_UPDATE_FEE(updatedFeerate, replyTo_opt = Some(sender.ref))
    alice.feeEstimator.setFeerate(FeeratesPerKw.single(updatedFeerate))
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(updatedFeerate))
    alice ! cmd
    if (higherDustLimit == aliceCommitments.params.localParams.dustLimit) {
      sender.expectMsg(RES_FAILURE(cmd, LocalDustHtlcExposureTooHigh(channelId(alice), 25000 sat, 29600000 msat)))
    } else {
      sender.expectMsg(RES_FAILURE(cmd, RemoteDustHtlcExposureTooHigh(channelId(alice), 25000 sat, 29600000 msat)))
    }
  }

  test("recv CMD_UPDATE_FEE (over max dust htlc exposure in local commit only with pending local changes)", Tag(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob)) { f =>
    testCmdUpdateFeeDustOverflowSingleCommit(f)
  }

  test("recv CMD_UPDATE_FEE (over max dust htlc exposure in remote commit only with pending local changes)", Tag(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice)) { f =>
    testCmdUpdateFeeDustOverflowSingleCommit(f)
  }

  test("recv CMD_UPDATE_FEE (two in a row)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat))
    alice2bob.expectMsgType[UpdateFee]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(30000 sat))
    val fee2 = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fee2))
  }

  test("recv CMD_UPDATE_FEE (when fundee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val c = CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, NonInitiatorCannotSendUpdateFee(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  test("recv UpdateFee") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    bob ! fee
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fee)
      .modify(_.commitments.changes.remoteNextHtlcId).setTo(0))
  }

  test("recv UpdateFee (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    val fee = UpdateFee(ByteVector32.Zeroes, TestConstants.anchorOutputsFeeratePerKw * 0.8)
    bob ! fee
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fee)
      .modify(_.commitments.changes.remoteNextHtlcId).setTo(0))
  }

  test("recv UpdateFee (two in a row)") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee1 = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    bob ! fee1
    val fee2 = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(14000 sat))
    bob ! fee2
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fee2)
      .modify(_.commitments.changes.remoteNextHtlcId).setTo(0))
  }

  test("recv UpdateFee (when sender is not funder)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[PublishTx]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFee (sender can't afford it)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(100000000 sat))
    // we first update the feerates so that we don't trigger a 'fee too different' error
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(fee.feeratePerKw))
    bob ! fee
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == CannotAffordFees(channelId(bob), missing = 71620000L sat, reserve = 20000L sat, fees = 72400000L sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    //bob2blockchain.expectMsgType[PublishTx] // main delayed (removed because of the high fees)
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFee (sender can't afford it, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    // This feerate is just above the threshold: (800000 (alice balance) - 20000 (reserve) - 660 (anchors)) / 1124 (commit tx weight) = 693363
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(693364 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == CannotAffordFees(channelId(bob), missing = 1 sat, reserve = 20000 sat, fees = 780001 sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
  }

  test("recv UpdateFee (local/remote feerates are too different)") { f =>
    import f._

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val commitTx = initialState.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.feeratePerKw)
    alice2bob.send(bob, UpdateFee(ByteVector32.Zeroes, TestConstants.feeratePerKw * 3))
    bob2alice.expectNoMessage(250 millis) // we don't close because the commitment doesn't contain any HTLC

    // when we try to add an HTLC, we still disagree on the feerate so we close
    alice2bob.send(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 2500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray).contains("local/remote feerates are too different"))
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == commitTx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFee (remote feerate is too high, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    val add = UpdateAddHtlc(ByteVector32.Zeroes, 0, 2500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    alice2bob.send(bob, add)
    val fee = UpdateFee(initialState.channelId, TestConstants.anchorOutputsFeeratePerKw * 3)
    alice2bob.send(bob, fee)
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ add :+ fee)
      .modify(_.commitments.changes.remoteNextHtlcId).setTo(1))
    bob2alice.expectNoMessage(250 millis) // we don't close because we're using anchor outputs
  }

  test("recv UpdateFee (remote feerate is too small, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    val add = UpdateAddHtlc(ByteVector32.Zeroes, 0, 2500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None)
    alice2bob.send(bob, add)
    val fee = UpdateFee(initialState.channelId, FeeratePerKw(FeeratePerByte(2 sat)))
    alice2bob.send(bob, fee)
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ add :+ fee)
      .modify(_.commitments.changes.remoteNextHtlcId).setTo(1))
    bob2alice.expectNoMessage(250 millis) // we don't close because we're using anchor outputs
  }

  test("recv UpdateFee (remote feerate is too small)") { f =>
    import f._
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    val tx = bobCommitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val expectedFeeratePerKw = bob.feeEstimator.getFeeratePerKw(bob.feeTargets.commitmentBlockTarget)
    assert(bobCommitments.latest.localCommit.spec.commitTxFeerate == expectedFeeratePerKw)
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(252 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == "remote fee rate is too small: remoteFeeratePerKw=252")
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFee (over max dust htlc exposure)") { f =>
    import f._

    // Alice sends HTLCs to Bob that are not included in the dust exposure at the current feerate:
    addHtlc(13000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(13500.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    addHtlc(14000.sat.toMilliSatoshi, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(DustExposure.computeExposure(bobCommitments.latest.localCommit.spec, bobCommitments.params.localParams.dustLimit, bobCommitments.params.commitmentFormat) == 0.msat)
    assert(DustExposure.computeExposure(bobCommitments.latest.remoteCommit.spec, bobCommitments.params.remoteParams.dustLimit, bobCommitments.params.commitmentFormat) == 0.msat)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx

    // A large feerate increase would make these HTLCs overflow Bob's dust exposure, so he force-closes:
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(20000 sat)))
    bob ! UpdateFee(channelId(bob), FeeratePerKw(20000 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == LocalDustHtlcExposureTooHigh(channelId(bob), 30000 sat, 40500000 msat).getMessage)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv UpdateFee (over max dust htlc exposure with pending local changes)") { f =>
    import f._
    val sender = TestProbe()
    assert(bob.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(alice.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 30_000.sat)

    // Bob sends HTLCs to Alice that are not included in the dust exposure at the current feerate.
    // He signs them but Alice doesn't answer yet.
    addHtlc(13000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    addHtlc(13500.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]

    // Bob sends another HTLC to Alice that is not included in the dust exposure at the current feerate.
    addHtlc(14000.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob)
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(DustExposure.computeExposure(bobCommitments.latest.localCommit.spec, bobCommitments.params.localParams.dustLimit, bobCommitments.params.commitmentFormat) == 0.msat)
    assert(DustExposure.computeExposure(bobCommitments.latest.remoteCommit.spec, bobCommitments.params.remoteParams.dustLimit, bobCommitments.params.commitmentFormat) == 0.msat)

    // A large feerate increase would make these HTLCs overflow Bob's dust exposure, so he force-close:
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(20000 sat)))
    bob ! UpdateFee(channelId(bob), FeeratePerKw(20000 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == LocalDustHtlcExposureTooHigh(channelId(bob), 30000 sat, 40500000 msat).getMessage)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    awaitCond(bob.stateName == CLOSING)
  }

  def testUpdateFeeDustOverflowSingleCommit(f: FixtureParam): Unit = {
    import f._
    val sender = TestProbe()
    // We start with a low feerate.
    val initialFeerate = FeeratePerKw(500 sat)
    alice.feeEstimator.setFeerate(FeeratesPerKw.single(initialFeerate))
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(initialFeerate))
    updateFee(initialFeerate, alice, bob, alice2bob, bob2alice)
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitments = initialState.commitments
    assert(alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(bob.underlyingActor.nodeParams.nodeId).dustTolerance.maxExposure == 25_000.sat)
    val higherDustLimit = Seq(aliceCommitments.params.localParams.dustLimit, aliceCommitments.params.remoteParams.dustLimit).max
    val lowerDustLimit = Seq(aliceCommitments.params.localParams.dustLimit, aliceCommitments.params.remoteParams.dustLimit).min
    // We have the following dust thresholds at the current feerate
    assert(Transactions.offeredHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 6989.sat)
    assert(Transactions.receivedHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 7109.sat)
    assert(Transactions.offeredHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 2989.sat)
    assert(Transactions.receivedHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = DustExposure.feerateForDustExposure(initialFeerate)), aliceCommitments.params.commitmentFormat) == 3109.sat)
    // And the following thresholds after the feerate update
    // NB: we apply the real feerate when sending update_fee, not the one adjusted for dust
    val updatedFeerate = FeeratePerKw(4000 sat)
    assert(Transactions.offeredHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 7652.sat)
    assert(Transactions.receivedHtlcTrimThreshold(higherDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 7812.sat)
    assert(Transactions.offeredHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 3652.sat)
    assert(Transactions.receivedHtlcTrimThreshold(lowerDustLimit, aliceCommitments.latest.localCommit.spec.copy(commitTxFeerate = updatedFeerate), aliceCommitments.params.commitmentFormat) == 3812.sat)

    // Bob send HTLCs to Alice that are not included in the dust exposure at the current feerate.
    // He signs them but Alice doesn't answer yet.
    (1 to 3).foreach(_ => addHtlc(7400.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob))
    bob ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]

    // Bob sends other HTLCs to Alice that are not included in the dust exposure at the current feerate, without signing them.
    (1 to 2).foreach(_ => addHtlc(7400.sat.toMilliSatoshi, bob, alice, bob2alice, alice2bob))

    // A feerate increase makes these HTLCs become dust in one of the commitments but not the other.
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(updatedFeerate))
    bob ! UpdateFee(channelId(bob), updatedFeerate)
    val error = bob2alice.expectMsgType[Error]
    // NB: we don't need to distinguish local and remote, the error message is exactly the same.
    assert(new String(error.data.toArray) == LocalDustHtlcExposureTooHigh(channelId(bob), 30000 sat, 37000000 msat).getMessage)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid)
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv UpdateFee (over max dust htlc exposure in local commit only with pending local changes)", Tag(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice)) { f =>
    testUpdateFeeDustOverflowSingleCommit(f)
  }

  test("recv UpdateFee (over max dust htlc exposure in remote commit only with pending local changes)", Tag(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob)) { f =>
    testUpdateFeeDustOverflowSingleCommit(f)
  }

  test("recv CMD_UPDATE_RELAY_FEE ") { f =>
    import f._
    val sender = TestProbe()
    val newFeeBaseMsat = TestConstants.Alice.nodeParams.relayParams.publicChannelFees.feeBase * 2
    val newFeeProportionalMillionth = TestConstants.Alice.nodeParams.relayParams.publicChannelFees.feeProportionalMillionths * 2
    val newCltvExpiryDelta = CltvExpiryDelta(145)
    sender.send(alice, CMD_UPDATE_RELAY_FEE(ActorRef.noSender, newFeeBaseMsat, newFeeProportionalMillionth, cltvExpiryDelta_opt = Some(newCltvExpiryDelta)))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_RELAY_FEE]]

    val localUpdate = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(localUpdate.channelUpdate.feeBaseMsat == newFeeBaseMsat)
    assert(localUpdate.channelUpdate.feeProportionalMillionths == newFeeProportionalMillionth)
    assert(localUpdate.channelUpdate.cltvExpiryDelta == newCltvExpiryDelta)
    alice2relayer.expectNoMessage(1 seconds)
  }

  def testCmdClose(f: FixtureParam, script_opt: Option[ByteVector]): Unit = {
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    alice ! CMD_CLOSE(sender.ref, script_opt, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val shutdown = alice2bob.expectMsgType[Shutdown]
    script_opt.foreach(script => assert(script == shutdown.scriptPubKey))
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_CLOSE (no pending htlcs)") { f =>
    testCmdClose(f, None)
  }

  test("recv CMD_CLOSE (no pending htlcs) (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testCmdClose(f, None)
  }

  test("recv CMD_CLOSE (no pending htlcs) (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testCmdClose(f, None)
  }

  test("recv CMD_CLOSE (with noSender)") { f =>
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    // this makes sure that our backward-compatibility hack for the ask pattern (which uses context.sender as reply-to)
    // works before we fully transition to akka typed
    val c = CMD_CLOSE(ActorRef.noSender, None, None)
    sender.send(alice, c)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_CLOSE (with unacked sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, CannotCloseWithUnsignedOutgoingHtlcs]]
  }

  test("recv CMD_CLOSE (with unacked received htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    bob ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    bob2alice.expectMsgType[Shutdown]
  }

  test("recv CMD_CLOSE (with invalid final script)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, Some(hex"00112233445566778899"), None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, InvalidFinalScript]]
  }

  test("recv CMD_CLOSE (with unsupported native segwit script)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, Some(hex"51050102030405"), None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, InvalidFinalScript]]
  }

  test("recv CMD_CLOSE (with native segwit script)", Tag(ChannelStateTestsTags.ShutdownAnySegwit)) { f =>
    testCmdClose(f, Some(hex"51050102030405"))
  }

  test("recv CMD_CLOSE (with signed sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_CLOSE (two in a row)") { f =>
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  test("recv CMD_CLOSE (while waiting for a RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    // actual test begins
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv CMD_CLOSE (with unsigned fee update)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat), commit = false)
    alice2bob.expectMsgType[UpdateFee]
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, CannotCloseWithUnsignedOutgoingUpdateFee]]
    alice2bob.expectNoMessage(100 millis)
    // once alice signs, the channel can be closed
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv CMD_CLOSE (with a script that does not match our upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val sender = TestProbe()
    val shutdownScript = Script.write(Script.pay2wpkh(randomKey().publicKey))
    alice ! CMD_CLOSE(sender.ref, Some(shutdownScript), None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, InvalidFinalScript]]
  }

  test("recv CMD_CLOSE (with a script that does match our upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val sender = TestProbe()
    val shutdownScript = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.upfrontShutdownScript_opt.get
    alice ! CMD_CLOSE(sender.ref, Some(shutdownScript), None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val shutdown = alice2bob.expectMsgType[Shutdown]
    assert(shutdown.scriptPubKey == alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.upfrontShutdownScript_opt.get)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_CLOSE (upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    val shutdown = alice2bob.expectMsgType[Shutdown]
    assert(shutdown.scriptPubKey == alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.localParams.upfrontShutdownScript_opt.get)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_FORCECLOSE (with pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 1)

    // actual test starts here
    alice ! CMD_FORCECLOSE(sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE]]
    val addSettled = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.ChannelFailureBeforeSigned.type]]
    assert(addSettled.htlc == htlc1)
  }

  def testShutdown(f: FixtureParam, script_opt: Option[ByteVector]): Unit = {
    import f._
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    alice ! Shutdown(ByteVector32.Zeroes, script_opt.getOrElse(bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData)))
    alice2bob.expectMsgType[Shutdown]
    alice2bob.expectMsgType[ClosingSigned]
    awaitCond(alice.stateName == NEGOTIATING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_NEGOTIATING].channelId)
  }

  test("recv Shutdown (no pending htlcs)") { f =>
    testShutdown(f, None)
  }

  test("recv Shutdown (no pending htlcs) (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    testShutdown(f, None)
  }

  test("recv Shutdown (no pending htlcs) (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testShutdown(f, None)
  }

  test("recv Shutdown (with unacked sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    bob ! CMD_CLOSE(sender.ref, None, None)
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob2alice.forward(alice)
    // alice sends a new sig
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // bob replies with a revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // as soon as alice as received the revocation, she will send her shutdown message
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId == alice.stateData.asInstanceOf[DATA_SHUTDOWN].channelId)
  }

  test("recv Shutdown (with unacked received htlcs)") { f =>
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // actual test begins
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob ! Shutdown(ByteVector32.Zeroes, alice.underlyingActor.getOrGenerateFinalScriptPubKey(aliceData))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[PublishTx]
    bob2blockchain.expectMsgType[WatchTxConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv Shutdown (with unsigned fee update)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat), commit = true)
    alice2bob.expectMsgType[UpdateFee]
    alice2bob.forward(bob)
    val sig = alice2bob.expectMsgType[CommitSig]
    // Bob initiates a close before receiving the signature.
    bob ! CMD_CLOSE(sender.ref, None, None)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    alice2bob.forward(bob, sig)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    // Once the fee update has been signed, shutdown resumes.
    alice2bob.expectMsgType[ClosingSigned]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ClosingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv Shutdown (with invalid final script)") { f =>
    import f._
    bob ! Shutdown(ByteVector32.Zeroes, hex"00112233445566778899")
    bob2alice.expectMsgType[Warning]
    // we should fail the connection as per the BOLTs
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId) if nodeId == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  test("recv Shutdown (with unsupported native segwit script)") { f =>
    import f._
    bob ! Shutdown(ByteVector32.Zeroes, hex"51050102030405")
    bob2alice.expectMsgType[Warning]
    // we should fail the connection as per the BOLTs
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId) if nodeId == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  test("recv Shutdown (with native segwit script)", Tag(ChannelStateTestsTags.ShutdownAnySegwit)) { f =>
    testShutdown(f, Some(hex"51050102030405"))
  }

  test("recv Shutdown (with invalid final script and signed htlcs, in response to a Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob ! CMD_CLOSE(sender.ref, None, None)
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob ! Shutdown(ByteVector32.Zeroes, hex"00112233445566778899")
    // we should fail the connection as per the BOLTs
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId) if nodeId == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  test("recv Shutdown (with a script that does not match the upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    bob ! Shutdown(ByteVector32.Zeroes, Script.write(Script.pay2wpkh(randomKey().publicKey)))

    // we should fail the connection as per the BOLTs
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId) if nodeId == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  def testShutdownWithHtlcs(f: FixtureParam): Unit = {
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! Shutdown(ByteVector32.Zeroes, bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    bob2alice.expectMsgType[Shutdown]
    awaitCond(bob.stateName == SHUTDOWN)
  }

  test("recv Shutdown (with signed htlcs)") {
    testShutdownWithHtlcs _
  }

  test("recv Shutdown (with signed htlcs) (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    testShutdownWithHtlcs _
  }

  test("recv Shutdown (with signed htlcs) (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    testShutdownWithHtlcs _
  }

  test("recv Shutdown (while waiting for a RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    bob ! CMD_CLOSE(sender.ref, None, None)
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob2alice.forward(alice)
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
  }

  test("recv Shutdown (while waiting for a RevokeAndAck with pending outgoing htlc)") { f =>
    import f._
    val sender = TestProbe()
    // let's make bob send a Shutdown message
    bob ! CMD_CLOSE(sender.ref, None, None)
    bob2alice.expectMsgType[Shutdown]
    // this is just so we have something to sign
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // now we can sign
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // adding an outgoing pending htlc
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    // actual test begins
    // alice eventually gets bob's shutdown
    bob2alice.forward(alice)
    // alice can't do anything for now other than waiting for bob to send the revocation
    alice2bob.expectNoMessage()
    // bob sends the revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // bob will also sign back
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    // then alice can sign the 2nd htlc
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // and reply to bob's first signature
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    // bob replies with the 2nd revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // then alice can send her shutdown
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
    // note: bob will sign back a second time, but that is out of our scope
  }

  test("recv CurrentBlockCount (no htlc timed out)") { f =>
    import f._
    TestProbe()
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    alice ! CurrentBlockHeight(BlockHeight(400143))
    awaitCond(alice.stateData == initialState)
  }

  test("recv CurrentBlockCount (an htlc timed out)") { f =>
    import f._
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = initialState.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CurrentBlockHeight(BlockHeight(400145))
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid)
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx.txid)
    channelUpdateListener.expectMsgType[LocalChannelDown]
  }

  test("recv CurrentBlockCount (fulfilled signed htlc ignored by upstream peer)") { f =>
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    // actual test begins:
    //  * Bob receives the HTLC pre-image and wants to fulfill
    //  * Alice does not react to the fulfill (drops the message for some reason)
    //  * When the HTLC timeout on Alice side is near, Bob needs to close the channel to avoid an on-chain race
    //    condition between his HTLC-success and Alice's HTLC-timeout
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _, _, _) = initialState.commitments.latest.localCommit.htlcTxsAndRemoteSigs.head.htlcTx

    bob ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.expectMsgType[CommitSig]
    bob ! CurrentBlockHeight(htlc.cltvExpiry.blockHeight - Bob.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)

    val ChannelErrorOccurred(_, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == initialCommitTx.txid)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txOut == htlcSuccessTx.txOut)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == initialCommitTx.txid)
    channelUpdateListener.expectMsgType[LocalChannelDown]
    alice2blockchain.expectNoMessage(500 millis)
  }

  test("recv CurrentBlockCount (fulfilled proposed htlc ignored by upstream peer)") { f =>
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    // actual test begins:
    //  * Bob receives the HTLC pre-image and wants to fulfill but doesn't sign
    //  * Alice does not react to the fulfill (drops the message for some reason)
    //  * When the HTLC timeout on Alice side is near, Bob needs to close the channel to avoid an on-chain race
    //    condition between his HTLC-success and Alice's HTLC-timeout
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _, _, _) = initialState.commitments.latest.localCommit.htlcTxsAndRemoteSigs.head.htlcTx

    bob ! CMD_FULFILL_HTLC(htlc.id, r, commit = false)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.expectNoMessage(500 millis)
    bob ! CurrentBlockHeight(htlc.cltvExpiry.blockHeight - Bob.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)

    val ChannelErrorOccurred(_, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == initialCommitTx.txid)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txOut == htlcSuccessTx.txOut)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == initialCommitTx.txid)
    channelUpdateListener.expectMsgType[LocalChannelDown]
    alice2blockchain.expectNoMessage(500 millis)
  }

  test("recv CurrentBlockCount (fulfilled proposed htlc acked but not committed by upstream peer)") { f =>
    import f._
    val (r, htlc) = addHtlc(150000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    // actual test begins:
    //  * Bob receives the HTLC pre-image and wants to fulfill
    //  * Alice acks but doesn't commit
    //  * When the HTLC timeout on Alice side is near, Bob needs to close the channel to avoid an on-chain race
    //    condition between his HTLC-success and Alice's HTLC-timeout
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _, _, _) = initialState.commitments.latest.localCommit.htlcTxsAndRemoteSigs.head.htlcTx

    bob ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    bob ! CurrentBlockHeight(htlc.cltvExpiry.blockHeight - Bob.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)

    val ChannelErrorOccurred(_, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == initialCommitTx.txid)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txOut == htlcSuccessTx.txOut)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == initialCommitTx.txid)
    channelUpdateListener.expectMsgType[LocalChannelDown]
    alice2blockchain.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val event = CurrentFeerates(FeeratesPerKw(FeeratePerKw(50 sat), FeeratePerKw(100 sat), FeeratePerKw(200 sat), FeeratePerKw(600 sat), FeeratePerKw(1200 sat), FeeratePerKw(3600 sat), FeeratePerKw(7200 sat), FeeratePerKw(14400 sat), FeeratePerKw(100800 sat)))
    alice ! event
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, event.feeratesPerKw.feePerBlock(Alice.nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget)))
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    alice ! CurrentFeerates(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw / 2).copy(mempoolMinFee = FeeratePerKw(250 sat)))
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, TestConstants.anchorOutputsFeeratePerKw / 2))
    alice2bob.expectMsgType[CommitSig]
    // The configured maximum feerate is bypassed if it's below the propagation threshold.
    alice ! CurrentFeerates(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(mempoolMinFee = TestConstants.anchorOutputsFeeratePerKw))
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, TestConstants.anchorOutputsFeeratePerKw * 1.25))
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee)") { f =>
    import f._
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(10010 sat)))
    alice ! event
    alice2bob.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    alice ! CurrentFeerates(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(mempoolMinFee = FeeratePerKw(250 sat)))
    alice2bob.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are close)") { f =>
    import f._
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob ! event
    bob2alice.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different, with HTLCs)") { f =>
    import f._

    addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(14000 sat)))
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(14000 sat)))
    bob ! event
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx] // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different, with HTLCs, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // We start with a feerate lower than the 10 sat/byte threshold.
    alice.feeEstimator.setFeerate(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw / 2))
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw / 2))
    alice ! CMD_UPDATE_FEE(TestConstants.anchorOutputsFeeratePerKw / 2)
    alice2bob.expectMsgType[UpdateFee]
    alice2bob.forward(bob)
    addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw / 2)

    // The network fees spike, but Bob doesn't close the channel because we're using anchor outputs.
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2))
    val event = CurrentFeerates(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2))
    bob ! event
    bob2alice.expectNoMessage(250 millis)
    assert(bob.stateName == NORMAL)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different, without HTLCs)") { f =>
    import f._

    bob.feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(1000 sat)))
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(1000 sat)))
    bob ! event
    bob2alice.expectNoMessage(250 millis) // we don't close because the commitment doesn't contain any HTLC

    // when we try to add an HTLC, we still disagree on the feerate so we close
    alice2bob.send(bob, UpdateAddHtlc(ByteVector32.Zeroes, 0, 2500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx] // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (their commit w/ htlc)") { f =>
    import f._

    val (ra1, htlca1) = addHtlc(250000000 msat, CltvExpiryDelta(50), alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, CltvExpiryDelta(60), alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, CltvExpiryDelta(55), bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, CltvExpiryDelta(65), bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // in response to that, alice publishes her claim txs
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    // in addition to her main output, alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val claimHtlcTxs = (1 to 3).map(_ => alice2blockchain.expectMsgType[PublishReplaceableTx])
    val htlcAmountClaimed = (for (claimHtlcTx <- claimHtlcTxs) yield {
      assert(claimHtlcTx.txInfo.tx.txIn.size == 1)
      assert(claimHtlcTx.txInfo.tx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx.txInfo.tx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txInfo.tx.txOut.head.amount
    }).sum
    // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
    val amountClaimed = claimMain.txOut.head.amount + htlcAmountClaimed
    assert(amountClaimed == 814880.sat)

    // alice sets the confirmation targets to the HTLC expiry
    assert(claimHtlcTxs.collect { case PublishReplaceableTx(tx: ClaimHtlcSuccessTx, _) => (tx.htlcId, tx.confirmBefore.toLong) }.toMap == Map(htlcb1.id -> htlcb1.cltvExpiry.toLong))
    assert(claimHtlcTxs.collect { case PublishReplaceableTx(tx: ClaimHtlcTimeoutTx, _) => (tx.htlcId, tx.confirmBefore.toLong) }.toMap == Map(htlca1.id -> htlca1.cltvExpiry.toLong, htlca2.id -> htlca2.cltvExpiry.toLong))

    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 1
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 2
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 3
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 4
    alice2blockchain.expectNoMessage(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
    val rcp = alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get
    assert(rcp.claimHtlcTxs.size == 4)
    assert(getClaimHtlcSuccessTxs(rcp).length == 1)
    assert(getClaimHtlcTimeoutTxs(rcp).length == 2)

    // assert the feerate of the claim main is what we expect
    val expectedFeeRate = alice.feeEstimator.getFeeratePerKw(alice.feeTargets.claimMainBlockTarget)
    val expectedFee = Transactions.weight2fee(expectedFeeRate, Transactions.claimP2WPKHOutputWeight)
    val claimFee = claimMain.txIn.map(in => bobCommitTx.txOut(in.outPoint.index.toInt).amount).sum - claimMain.txOut.map(_.amount).sum
    assert(claimFee == expectedFee)
  }

  test("recv WatchFundingSpentTriggered (their commit w/ pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 1)

    // actual test starts here
    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    val addSettled = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.ChannelFailureBeforeSigned.type]]
    assert(addSettled.htlc == htlc1)
  }

  test("recv WatchFundingSpentTriggered (their *next* commit w/ htlc)") { f =>
    import f._

    val (ra1, htlca1) = addHtlc(250000000 msat, CltvExpiryDelta(24), alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, CltvExpiryDelta(30), alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)
    // alice sign but we intercept bob's revocation
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]

    // as far as alice knows, bob currently has two valid unrevoked commitment transactions

    // at this point here is the situation from bob's pov with the latest sig received from alice,
    // and what alice should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 499 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.size == 5) // two main outputs and 3 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // in response to that, alice publishes her claim txs
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    // in addition to her main output, alice can only claim 2 out of 3 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val claimHtlcTxs = (1 to 2).map(_ => alice2blockchain.expectMsgType[PublishReplaceableTx])
    val htlcAmountClaimed = (for (claimHtlcTx <- claimHtlcTxs) yield {
      assert(claimHtlcTx.txInfo.tx.txIn.size == 1)
      assert(claimHtlcTx.txInfo.tx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx.txInfo.tx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txInfo.tx.txOut.head.amount
    }).sum
    // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
    val amountClaimed = claimMain.txOut.head.amount + htlcAmountClaimed
    assert(amountClaimed == 822310.sat)

    // alice sets the confirmation targets to the HTLC expiry
    assert(claimHtlcTxs.collect { case PublishReplaceableTx(tx: ClaimHtlcTimeoutTx, _) => (tx.htlcId, tx.confirmBefore.toLong) }.toMap == Map(htlca1.id -> htlca1.cltvExpiry.toLong, htlca2.id -> htlca2.cltvExpiry.toLong))

    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.txid) // claim-main
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 1
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 2
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 3
    alice2blockchain.expectNoMessage(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.isDefined)
    val rcp = alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get
    assert(getClaimHtlcSuccessTxs(rcp).length == 0)
    assert(getClaimHtlcTimeoutTxs(rcp).length == 2)
  }

  test("recv WatchFundingSpentTriggered (their *next* commit w/ pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    // alice sign but we intercept bob's revocation
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    val (_, htlc2) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 1)

    // actual test starts here
    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    val addSettled = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.ChannelFailureBeforeSigned.type]]
    assert(addSettled.htlc == htlc2)
  }

  test("recv WatchFundingSpentTriggered (revoked commit)") { f =>
    import f._
    // initially we have :
    // alice = 800 000
    //   bob = 200 000
    def send(): Transaction = {
      // alice sends 8 000 sat
      addHtlc(10000000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)

      bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    }

    val txs = for (_ <- 0 until 10) yield send()
    // bob now has 10 spendable tx, 9 of them being revoked

    // let's say that bob published this tx
    val revokedTx = txs(3)
    // channel state for this revoked tx is as follows:
    // alice = 760 000
    //   bob = 200 000
    //  a->b =  10 000
    //  a->b =  10 000
    //  a->b =  10 000
    //  a->b =  10 000
    // two main outputs + 4 htlc
    assert(revokedTx.txOut.size == 6)
    alice ! WatchFundingSpentTriggered(revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcPenaltyTxs = for (_ <- 0 until 4) yield alice2blockchain.expectMsgType[PublishFinalTx].tx
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == revokedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == mainTx.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent] // main-penalty
    // let's make sure that htlc-penalty txs each spend a different output
    assert(htlcPenaltyTxs.map(_.txIn.head.outPoint.index).toSet.size == htlcPenaltyTxs.size)
    htlcPenaltyTxs.foreach(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    alice2blockchain.expectNoMessage(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => Transaction.correctlySpends(htlcPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    // two main outputs are 760 000 and 200 000
    assert(mainTx.txOut.head.amount == 741500.sat)
    assert(mainPenaltyTx.txOut.head.amount == 195160.sat)
    assert(htlcPenaltyTxs(0).txOut.head.amount == 4540.sat)
    assert(htlcPenaltyTxs(1).txOut.head.amount == 4540.sat)
    assert(htlcPenaltyTxs(2).txOut.head.amount == 4540.sat)
    assert(htlcPenaltyTxs(3).txOut.head.amount == 4540.sat)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
  }

  test("recv WatchFundingSpentTriggered (revoked commit with identical htlcs)") { f =>
    import f._
    val sender = TestProbe()

    // initially we have :
    // alice = 800 000
    //   bob = 200 000

    val add = CMD_ADD_HTLC(sender.ref, 10000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)
    // bob will publish this tx after it is revoked
    val revokedTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx

    alice ! add
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    crossSign(alice, bob, alice2bob, bob2alice)

    // channel state for this revoked tx is as follows:
    // alice = 780 000
    //   bob = 200 000
    //  a->b =  10 000
    //  a->b =  10 000
    assert(revokedTx.txOut.size == 4)
    alice ! WatchFundingSpentTriggered(revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcPenaltyTxs = for (_ <- 0 until 2) yield alice2blockchain.expectMsgType[PublishFinalTx].tx
    // let's make sure that htlc-penalty txs each spend a different output
    assert(htlcPenaltyTxs.map(_.txIn.head.outPoint.index).toSet.size == htlcPenaltyTxs.size)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == revokedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == mainTx.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent] // main-penalty
    htlcPenaltyTxs.foreach(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    alice2blockchain.expectNoMessage(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => Transaction.correctlySpends(htlcPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }

  test("recv WatchFundingSpentTriggered (revoked commit w/ pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    crossSign(alice, bob, alice2bob, bob2alice)
    val bobRevokedCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    crossSign(alice, bob, alice2bob, bob2alice)
    val (_, htlc3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 1)

    // actual test starts here
    // bob publishes his current commit tx

    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)
    val addSettled = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.ChannelFailureBeforeSigned.type]]
    assert(addSettled.htlc == htlc3)
  }

  test("recv WatchFundingSpentTriggered (other commit)") { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when she publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend using 2nd stage htlc-timeout
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend using 2nd stage htlc-timeout
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage using htlc-success
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid)
    assert(aliceCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished.commitTx.txid == aliceCommitTx.txid)
    assert(localCommitPublished.htlcTxs.size == 4)
    assert(getHtlcSuccessTxs(localCommitPublished).length == 1)
    assert(getHtlcTimeoutTxs(localCommitPublished).length == 2)
    assert(localCommitPublished.claimHtlcDelayedTxs.isEmpty)

    // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    // so we expect 4 transactions:
    // - 1 tx to claim the main delayed output
    // - 3 txs for each htlc
    // NB: 3rd-stage txs will only be published once the htlc txs confirm
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcTx1 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcTx2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcTx3 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    // the main delayed output and htlc txs spend the commitment transaction
    Seq(claimMain, htlcTx1, htlcTx2, htlcTx3).foreach(tx => Transaction.correctlySpends(tx, aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.txid) // main-delayed
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 1
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 2
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 3
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 4
    alice2blockchain.expectNoMessage(1 second)

    // 3rd-stage txs are published when htlc txs confirm
    Seq(htlcTx1, htlcTx2, htlcTx3).foreach { htlcTimeoutTx =>
      alice ! WatchOutputSpentTriggered(htlcTimeoutTx)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == htlcTimeoutTx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(2701), 3, htlcTimeoutTx)
      val claimHtlcDelayedTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
      Transaction.correctlySpends(claimHtlcDelayedTx, htlcTimeoutTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimHtlcDelayedTx.txid)
    }
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs.length == 3)
    alice2blockchain.expectNoMessage(1 second)
  }

  def testErrorAnchorOutputsWithHtlcs(f: FixtureParam): Unit = {
    import f._

    val (ra1, htlca1) = addHtlc(250000000 msat, CltvExpiryDelta(20), alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, CltvExpiryDelta(25), alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, CltvExpiryDelta(30), bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, CltvExpiryDelta(35), bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)

    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid)
    assert(aliceCommitTx.txOut.size == 8) // two main outputs, two anchors and 4 pending htlcs
    awaitCond(alice.stateName == CLOSING)

    val localAnchor = alice2blockchain.expectMsgType[PublishReplaceableTx]
    assert(localAnchor.txInfo.confirmBefore.toLong == htlca1.cltvExpiry.toLong) // the target is set to match the first htlc that expires
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx]
    // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val htlcConfirmationTargets = Seq(
      alice2blockchain.expectMsgType[PublishReplaceableTx], // htlc 1
      alice2blockchain.expectMsgType[PublishReplaceableTx], // htlc 2
      alice2blockchain.expectMsgType[PublishReplaceableTx], // htlc 3
    ).map(p => p.txInfo.asInstanceOf[HtlcTx].htlcId -> p.txInfo.confirmBefore.toLong).toMap
    assert(htlcConfirmationTargets == Map(htlcb1.id -> htlcb1.cltvExpiry.toLong, htlca1.id -> htlca1.cltvExpiry.toLong, htlca2.id -> htlca2.cltvExpiry.toLong))

    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid)
    val watchedOutputs = Seq(
      alice2blockchain.expectMsgType[WatchOutputSpent], // htlc 1
      alice2blockchain.expectMsgType[WatchOutputSpent], // htlc 2
      alice2blockchain.expectMsgType[WatchOutputSpent], // htlc 3
      alice2blockchain.expectMsgType[WatchOutputSpent], // htlc 4
      alice2blockchain.expectMsgType[WatchOutputSpent], // local anchor
    ).map(w => OutPoint(w.txId.reverse, w.outputIndex)).toSet
    val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(watchedOutputs == localCommitPublished.htlcTxs.keySet + localAnchor.txInfo.input.outPoint)
    alice2blockchain.expectNoMessage(1 second)
  }

  test("recv Error (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testErrorAnchorOutputsWithHtlcs(f)
  }

  test("recv Error (anchor outputs zero fee htlc txs, fee-bumping for commit txs without htlcs disabled)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.DontSpendAnchorWithoutHtlcs)) { f =>
    // We should ignore the disable flag since there are htlcs in the commitment (funds at risk).
    testErrorAnchorOutputsWithHtlcs(f)
  }

  def testErrorAnchorOutputsWithoutHtlcs(f: FixtureParam, commitFeeBumpDisabled: Boolean): Unit = {
    import f._

    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid)
    assert(aliceCommitTx.txOut.size == 4) // two main outputs and two anchors
    awaitCond(alice.stateName == CLOSING)

    val currentBlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight
    val blockTargets = alice.underlyingActor.nodeParams.onChainFeeConf.feeTargets
    if (commitFeeBumpDisabled) {
      val claimMain = alice2blockchain.expectMsgType[PublishFinalTx]
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === aliceCommitTx.txid)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.tx.txid)
      alice2blockchain.expectNoMessage(1 second)
    } else {
      val localAnchor = alice2blockchain.expectMsgType[PublishReplaceableTx]
      // When there are no pending HTLCs, there is no rush to get the commit tx confirmed
      assert(localAnchor.txInfo.confirmBefore === currentBlockHeight + blockTargets.commitmentWithoutHtlcsBlockTarget)
      val claimMain = alice2blockchain.expectMsgType[PublishFinalTx]
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === aliceCommitTx.txid)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.tx.txid)
      assert(alice2blockchain.expectMsgType[WatchOutputSpent].outputIndex === localAnchor.input.index)
      alice2blockchain.expectNoMessage(1 second)
    }
  }

  test("recv Error (anchor outputs zero fee htlc txs without htlcs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testErrorAnchorOutputsWithoutHtlcs(f, commitFeeBumpDisabled = false)
  }

  test("recv Error (anchor outputs zero fee htlc txs without htlcs, fee-bumping for commit txs without htlcs disabled)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.DontSpendAnchorWithoutHtlcs)) { f =>
    testErrorAnchorOutputsWithoutHtlcs(f, commitFeeBumpDisabled = true)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._

    // when receiving an error bob should publish its commitment even if it has nothing at stake, because alice could
    // have lost its data and need assistance

    // an error occurs and alice publishes her commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob ! Error(ByteVector32.Zeroes, "oops")
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobCommitTx.txid)
    assert(bobCommitTx.txOut.size == 1) // only one main output
    alice2blockchain.expectNoMessage(1 second)

    awaitCond(bob.stateName == CLOSING)
    assert(bob.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val localCommitPublished = bob.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished.commitTx.txid == bobCommitTx.txid)
  }

  test("recv Error (with pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 1)

    // actual test starts here
    alice ! Error(ByteVector32.Zeroes, "oops")
    val addSettled = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.ChannelFailureBeforeSigned.type]]
    assert(addSettled.htlc == htlc1)
  }

  test("recv WatchFundingDeeplyBuriedTriggered (public channel)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    val aliceIds = alice.stateData.asInstanceOf[DATA_NORMAL].shortIds
    val realShortChannelId = aliceIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    // existing funding tx coordinates
    val TxCoordinates(blockHeight, txIndex, _) = ShortChannelId.coordinates(realShortChannelId)
    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    assert(annSigs.shortChannelId == realShortChannelId)
    // alice updates her internal state
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(realShortChannelId))
    alice2bob.expectNoMessage(100 millis)
    channelUpdateListener.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceIds.localAlias)
  }

  test("recv WatchFundingDeeplyBuriedTriggered (public channel, dual funding)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    // public channel: we don't send the channel_update directly to the peer
    alice2bob.expectNoMessage(1 second)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(annSigs.shortChannelId))
    // we don't re-publish the same channel_update if there was no change
    channelUpdateListener.expectNoMessage(1 second)
  }

  test("recv WatchFundingDeeplyBuriedTriggered (public channel, zero-conf)", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // in zero-conf channel we don't have a real short channel id when going to NORMAL state
    val aliceState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceState.shortIds.real == RealScidStatus.Unknown)
    // funding tx coordinates (unknown before)
    val (blockHeight, txIndex) = (BlockHeight(400000), 42)
    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    val realShortChannelId = RealShortChannelId(blockHeight, txIndex, alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.commitInput.outPoint.index.toInt)
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    assert(annSigs.shortChannelId == realShortChannelId)
    // alice updates her internal state
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(realShortChannelId))
    alice2bob.expectNoMessage(100 millis)
    // we emit a new local channel update containing the same channel_update, but also the new real scid
    val lcu = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(lcu.shortIds.real == Final(realShortChannelId))
    assert(lcu.channelUpdate == aliceState.channelUpdate)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceState.shortIds.localAlias)
  }

  test("recv WatchFundingDeeplyBuriedTriggered (public channel, short channel id changed)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    val aliceState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val realShortChannelId = aliceState.shortIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    // existing funding tx coordinates
    val TxCoordinates(blockHeight, txIndex, _) = ShortChannelId.coordinates(realShortChannelId)
    // new funding tx coordinates (there was a reorg)
    val (blockHeight1, txIndex1) = (blockHeight + 10, txIndex + 10)
    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight1, txIndex1, null)
    val newRealShortChannelId = RealShortChannelId(blockHeight1, txIndex1, alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.commitInput.outPoint.index.toInt)
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    assert(annSigs.shortChannelId == newRealShortChannelId)
    // update data with real short channel id
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(newRealShortChannelId))
    // we emit a new local channel update containing the same channel_update, but also the new real scid
    val lcu = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(lcu.shortIds.real == Final(newRealShortChannelId))
    assert(lcu.channelUpdate == aliceState.channelUpdate)
    channelUpdateListener.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceState.shortIds.localAlias)
  }

  test("recv WatchFundingDeeplyBuriedTriggered (private channel)") { f =>
    import f._
    val aliceIds = alice.stateData.asInstanceOf[DATA_NORMAL].shortIds
    val realShortChannelId = alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    // existing funding tx coordinates
    val TxCoordinates(blockHeight, txIndex, _) = ShortChannelId.coordinates(realShortChannelId)
    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    // update data with real short channel id
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(realShortChannelId))
    // private channel: we'll use the remote alias in the channel_update we sent to our peer, so there is no change and we don't send a new one
    alice2bob.expectNoMessage(100 millis)
    channelUpdateListener.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceIds.localAlias)
  }

  test("recv WatchFundingDeeplyBuriedTriggered (private channel, zero-conf)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // we create a new listener that registers after alice has published the funding tx
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionConfirmed])
    // zero-conf channel: the funding tx isn't confirmed
    val aliceState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceState.shortIds.real == RealScidStatus.Unknown)
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(42000), 42, null)
    val realShortChannelId = RealShortChannelId(BlockHeight(42000), 42, 0)
    // update data with real short channel id
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(realShortChannelId))
    // private channel: we'll use the remote alias in the channel_update we sent to our peer, so there is no change and we don't send a new one
    alice2bob.expectNoMessage(100 millis)
    // we emit a new local channel update containing the same channel_update, but also the new real scid
    val lcu = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(lcu.shortIds.real == Final(realShortChannelId))
    assert(lcu.channelUpdate == aliceState.channelUpdate)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceState.shortIds.localAlias)
    // this is the first time we know the funding tx has been confirmed
    listener.expectMsgType[TransactionConfirmed]
  }

  test("recv WatchFundingDeeplyBuriedTriggered (private channel, short channel id changed)") { f =>
    import f._
    val aliceState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val realShortChannelId = aliceState.shortIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    // existing funding tx coordinates
    val TxCoordinates(blockHeight, txIndex, _) = ShortChannelId.coordinates(realShortChannelId)
    // new funding tx coordinates (there was a reorg)
    val (blockHeight1, txIndex1) = (blockHeight + 10, txIndex + 10)
    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight1, txIndex1, null)
    val newRealShortChannelId = RealShortChannelId(blockHeight1, txIndex1, alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.commitInput.outPoint.index.toInt)
    // update data with real short channel id
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real == RealScidStatus.Final(newRealShortChannelId))
    // private channel: we'll use the remote alias in the channel_update we sent to our peer, so there is no change and we don't send a new one
    alice2bob.expectNoMessage(100 millis)
    // we emit a new local channel update containing the same channel_update, but also the new real scid
    val lcu = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(lcu.shortIds.real == Final(newRealShortChannelId))
    assert(lcu.channelUpdate == aliceState.channelUpdate)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceState.shortIds.localAlias)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceState.shortIds.localAlias)
  }

  test("recv AnnouncementSignatures", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val realShortChannelId = initialState.shortIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    // existing funding tx coordinates
    val TxCoordinates(blockHeight, txIndex, _) = ShortChannelId.coordinates(realShortChannelId)

    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    val annSigsA = alice2bob.expectMsgType[AnnouncementSignatures]
    bob ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    import initialState.commitments.latest.{localParams, remoteParams}
    val channelAnn = Announcements.makeChannelAnnouncement(Alice.nodeParams.chainHash, annSigsA.shortChannelId, Alice.nodeParams.nodeId, remoteParams.nodeId, Alice.channelKeyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, annSigsA.nodeSignature, annSigsB.nodeSignature, annSigsA.bitcoinSignature, annSigsB.bitcoinSignature)
    // actual test starts here
    bob2alice.forward(alice, annSigsB)
    awaitAssert {
      val normal = alice.stateData.asInstanceOf[DATA_NORMAL]
      assert(normal.shortIds.real == RealScidStatus.Final(annSigsA.shortChannelId) && normal.channelAnnouncement.contains(channelAnn) && normal.channelUpdate.shortChannelId == annSigsA.shortChannelId)
    }
    // we use the real scid instead of remote alias as soon as the channel is announced
    val lcu = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(lcu.channelUpdate.shortChannelId == realShortChannelId)
    assert(lcu.channelAnnouncement_opt.contains(channelAnn))
    // we don't send directly the channel_update to our peer, public announcements are handled by the router
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv AnnouncementSignatures (re-send)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val realShortChannelId = initialState.shortIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    // existing funding tx coordinates
    val TxCoordinates(blockHeight, txIndex, _) = ShortChannelId.coordinates(realShortChannelId)

    alice ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    val annSigsA = alice2bob.expectMsgType[AnnouncementSignatures]
    bob ! WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, null)
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    import initialState.commitments.latest.{localParams, remoteParams}
    val channelAnn = Announcements.makeChannelAnnouncement(Alice.nodeParams.chainHash, annSigsA.shortChannelId, Alice.nodeParams.nodeId, remoteParams.nodeId, Alice.channelKeyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, annSigsA.nodeSignature, annSigsB.nodeSignature, annSigsA.bitcoinSignature, annSigsB.bitcoinSignature)
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.contains(channelAnn))

    // actual test starts here
    // simulate bob re-sending its sigs
    bob2alice.send(alice, annSigsA)
    // alice re-sends her sigs
    alice2bob.expectMsg(annSigsA)
  }

  test("recv AnnouncementSignatures (invalid)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    val channelId = alice.stateData.asInstanceOf[DATA_NORMAL].channelId
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    alice2bob.expectMsgType[AnnouncementSignatures]
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    // actual test starts here - Bob sends an invalid signature
    val annSigsB_invalid = annSigsB.copy(bitcoinSignature = annSigsB.nodeSignature, nodeSignature = annSigsB.bitcoinSignature)
    bob2alice.forward(alice, annSigsB_invalid)
    alice2bob.expectMsg(Error(channelId, InvalidAnnouncementSignatures(channelId, annSigsB_invalid).getMessage))
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(200 millis)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv BroadcastChannelUpdate", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    val realScid = bob2alice.expectMsgType[AnnouncementSignatures].shortChannelId
    bob2alice.forward(alice)
    val update1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1.channelUpdate.shortChannelId == realScid)

    // actual test starts here
    Thread.sleep(1100)
    alice ! BroadcastChannelUpdate(PeriodicRefresh)
    val update2 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update2.channelUpdate.shortChannelId == realScid)
    assert(update1.channelUpdate.timestamp < update2.channelUpdate.timestamp)
  }

  test("recv BroadcastChannelUpdate (no changes)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    channelUpdateListener.expectMsgType[LocalChannelUpdate]

    // actual test starts here
    Thread.sleep(1100)
    alice ! BroadcastChannelUpdate(Reconnected)
    channelUpdateListener.expectNoMessage(1 second)
  }

  test("recv INPUT_DISCONNECTED") { f =>
    import f._
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags.isEnabled)

    // actual test starts here
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    alice2bob.expectNoMessage(1 second)
    channelUpdateListener.expectNoMessage(1 second)
  }

  test("recv INPUT_DISCONNECTED (with pending unsigned htlcs)") { f =>
    import f._
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags.isEnabled)
    val sender = TestProbe()
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val (_, htlc2) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 2)

    // actual test starts here
    Thread.sleep(1100)
    alice ! INPUT_DISCONNECTED
    val addSettled1 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult]]
    assert(addSettled1.htlc == htlc1)
    assert(addSettled1.result.isInstanceOf[HtlcResult.DisconnectedBeforeSigned])
    val addSettled2 = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult]]
    assert(addSettled2.htlc == htlc2)
    assert(addSettled2.result.isInstanceOf[HtlcResult.DisconnectedBeforeSigned])
    assert(!channelUpdateListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
    awaitCond(alice.stateName == OFFLINE)
  }

  test("recv INPUT_DISCONNECTED (public channel)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    val update1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1.channelUpdate.channelFlags.isEnabled)

    // actual test starts here
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    channelUpdateListener.expectNoMessage(1 second)
  }

  test("recv INPUT_DISCONNECTED (public channel, with pending unsigned htlcs)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    val sender = TestProbe()
    alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    val update1a = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    val update1b = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1a.channelUpdate.channelFlags.isEnabled)
    val (_, htlc1) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val (_, htlc2) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice, sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.changes.localChanges.proposed.size == 2)

    // actual test starts here
    Thread.sleep(1100)
    alice ! INPUT_DISCONNECTED
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.DisconnectedBeforeSigned]].htlc.paymentHash == htlc1.paymentHash)
    assert(alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.DisconnectedBeforeSigned]].htlc.paymentHash == htlc2.paymentHash)
    val update2a = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1a.channelUpdate.timestamp < update2a.channelUpdate.timestamp)
    assert(!update2a.channelUpdate.channelFlags.isEnabled)
    awaitCond(alice.stateName == OFFLINE)
  }

}
