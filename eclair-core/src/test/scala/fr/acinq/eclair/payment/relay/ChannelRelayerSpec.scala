/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.payment.relay

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair.Features.ScidAlias
import fr.acinq.eclair.TestConstants.emptyOnionPacket
import fr.acinq.eclair.TestUtils.realScid
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.IncomingPaymentPacket.ChannelRelayPacket
import fr.acinq.eclair.payment.relay.ChannelRelayer._
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPaymentPacket, PaymentPacketSpec}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.PaymentOnion.{ChannelRelayPayload, ChannelRelayTlvPayload, RelayLegacyPayload}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, NodeParams, RealShortChannelId, TestConstants, randomBytes32, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

class ChannelRelayerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  import ChannelRelayerSpec._

  case class FixtureParam(nodeParams: NodeParams, channelRelayer: typed.ActorRef[ChannelRelayer.Command], register: TestProbe[Any])

  override def withFixture(test: OneArgTest): Outcome = {
    // we are node B in the route A -> B -> C -> ....
    val nodeParams = TestConstants.Bob.nodeParams
    val register = TestProbe[Any]("register")
    val channelRelayer = testKit.spawn(ChannelRelayer.apply(nodeParams, register.ref.toClassic))
    try {
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, channelRelayer, register)))
    } finally {
      testKit.stop(channelRelayer)
    }
  }

  def expectFwdFail(register: TestProbe[Any], channelId: ByteVector32, cmd: channel.Command): Register.Forward[channel.Command] = {
    val fwd = register.expectMessageType[Register.Forward[channel.Command]]
    assert(fwd.message == cmd)
    assert(fwd.channelId == channelId)
    fwd
  }

  def expectFwdAdd(register: TestProbe[Any], channelId: ByteVector32, outAmount: MilliSatoshi, outExpiry: CltvExpiry): Register.Forward[CMD_ADD_HTLC] = {
    val fwd = register.expectMessageType[Register.Forward[CMD_ADD_HTLC]]
    assert(fwd.message.isInstanceOf[CMD_ADD_HTLC]) // the line above doesn't check the type due to type erasure
    assert(fwd.channelId == channelId)
    assert(fwd.message.amount == outAmount)
    assert(fwd.message.cltvExpiry == outExpiry)
    assert(fwd.message.origin.isInstanceOf[Origin.ChannelRelayedHot])
    val o = fwd.message.origin.asInstanceOf[Origin.ChannelRelayedHot]
    assert(o.amountOut == outAmount)
    fwd
  }

  def basicRelaytest(f: FixtureParam)(relayPayloadScid: ShortChannelId, lcu: LocalChannelUpdate, success: Boolean): Unit = {
    import f._

    val payload = RelayLegacyPayload(relayPayloadScid, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)

    channelRelayer ! WrappedLocalChannelUpdate(lcu)
    channelRelayer ! Relay(r)

    if (success) {
      expectFwdAdd(register, lcu.channelId, outgoingAmount, outgoingExpiry)
    } else {
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
    }
  }

  test("relay with real scid (channel update uses real scid)") { f =>
    basicRelaytest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1), success = true)
  }

  test("relay with real scid (channel update uses remote alias)") { f =>
    basicRelaytest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1, channelUpdateScid_opt = Some(remoteAlias1)), success = true)
  }

  test("relay with local alias (channel update uses real scid)") { f =>
    basicRelaytest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1), success = true)
  }

  test("relay with local alias (channel update uses remote alias)") { f =>
    // we use a random value to simulate a remote alias
    basicRelaytest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1, channelUpdateScid_opt = Some(remoteAlias1)), success = true)
  }

  test("fail to relay with real scid when option_scid_alias is enabled (channel update uses real scid)") { f =>
    basicRelaytest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1, optionScidAlias = true), success = false)
  }

  test("fail to relay with real scid when option_scid_alias is enabled (channel update uses remote alias)") { f =>
    basicRelaytest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1, optionScidAlias = true, channelUpdateScid_opt = Some(remoteAlias1)), success = false)
  }

  test("relay with local alias when option_scid_alias is enabled (channel update uses real scid)") { f =>
    basicRelaytest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1, optionScidAlias = true), success = true)
  }

  test("relay with local alias when option_scid_alias is enabled (channel update uses remote alias)") { f =>
    // we use a random value to simulate a remote alias
    basicRelaytest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1, optionScidAlias = true, channelUpdateScid_opt = Some(remoteAlias1)), success = true)
  }

  test("relay with new real scid after reorg") { f =>
    import f._
    import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv._

    // initial channel update
    val lcu1 = createLocalUpdate(channelId1)
    val payload1 = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r1 = createValidIncomingPacket(payload1)
    channelRelayer ! WrappedLocalChannelUpdate(lcu1)
    channelRelayer ! Relay(r1)
    expectFwdAdd(register, lcu1.channelId, outgoingAmount, outgoingExpiry)

    // reorg happens
    val realScid1AfterReorg = ShortChannelId(111112).toReal
    val lcu2 = createLocalUpdate(channelId1).copy(realShortChannelId_opt = Some(realScid1AfterReorg))
    val payload2 = RelayLegacyPayload(realScid1AfterReorg, outgoingAmount, outgoingExpiry)
    val r2 = createValidIncomingPacket(payload2)
    channelRelayer ! WrappedLocalChannelUpdate(lcu2)

    // both old and new real scids work
    channelRelayer ! Relay(r1)
    expectFwdAdd(register, lcu1.channelId, outgoingAmount, outgoingExpiry)
      // new real scid works
    channelRelayer ! Relay(r2)
    expectFwdAdd(register, lcu2.channelId, outgoingAmount, outgoingExpiry)
  }


  test("relay with onion tlv payload") { f =>
    import f._
    import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv._

    val payload = ChannelRelayTlvPayload(TlvStream[OnionPaymentPayloadTlv](AmountToForward(outgoingAmount), OutgoingCltv(outgoingExpiry), OutgoingChannelId(realScid1)))
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
  }

  test("relay with retries") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)

    // we tell the relayer about the first channel
    val u1 = createLocalUpdate(channelId1)
    channelRelayer ! WrappedLocalChannelUpdate(u1)

    // this is another channel, with less balance (it will be preferred)
    val u2 = createLocalUpdate(channelId2, balance = 8000000 msat)
    channelRelayer ! WrappedLocalChannelUpdate(u2)

    channelRelayer ! Relay(r)

    // first try
    val fwd1 = expectFwdAdd(register, channelIds(realScId2), outgoingAmount, outgoingExpiry)
    // channel returns an error
    fwd1.message.replyTo ! RES_ADD_FAILED(fwd1.message, HtlcValueTooHighInFlight(channelIds(realScId2), UInt64(1000000000L), 1516977616L msat), Some(u2.channelUpdate))

    // second try
    val fwd2 = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
    // failure again
    fwd1.message.replyTo ! RES_ADD_FAILED(fwd2.message, HtlcValueTooHighInFlight(channelIds(realScid1), UInt64(1000000000L), 1516977616L msat), Some(u1.channelUpdate))

    // the relayer should give up
    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(TemporaryNodeFailure), commit = true))
  }

  test("fail to relay when we have no channel_update for the next channel") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)

    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
  }

  test("fail to relay when register returns an error") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    val fwd = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
    fwd.replyTo ! Register.ForwardFailure(fwd)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
  }

  test("fail to relay when the channel is advertised as unusable (down)") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)
    val d = LocalChannelDown(null, channelId = channelIds(realScid1), Some(realScid1.toReal), null, outgoingNodeId)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedLocalChannelDown(d)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
  }

  test("fail to relay when channel is disabled") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1, enabled = false)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(ChannelDisabled(u.channelUpdate.messageFlags, u.channelUpdate.channelFlags, u.channelUpdate)), commit = true))
  }

  test("fail to relay when amount is below minimum") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1, htlcMinimum = outgoingAmount + 1.msat)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(AmountBelowMinimum(outgoingAmount, u.channelUpdate)), commit = true))
  }

  test("relay when expiry larger than our requirements") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val u = createLocalUpdate(channelId1)
    val r = createValidIncomingPacket(payload, expiryIn = outgoingExpiry + u.channelUpdate.cltvExpiryDelta + CltvExpiryDelta(1))

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdAdd(register, channelIds(realScid1), payload.amountToForward, payload.outgoingCltv).message
  }

  test("fail to relay when expiry is too small") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val u = createLocalUpdate(channelId1)
    val r = createValidIncomingPacket(payload, expiryIn = outgoingExpiry + u.channelUpdate.cltvExpiryDelta - CltvExpiryDelta(1))

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(payload.outgoingCltv, u.channelUpdate)), commit = true))
  }

  test("fail to relay when fee is insufficient") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload, amountIn = outgoingAmount + 1.msat)
    val u = createLocalUpdate(channelId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(FeeInsufficient(r.add.amountMsat, u.channelUpdate)), commit = true))
  }

  test("relay that would fail (fee insufficient) with a recent channel update but succeed with the previous update") { f =>
    import f._

    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload, amountIn = outgoingAmount + 1.msat)
    val u1 = createLocalUpdate(channelId1, timestamp = TimestampSecond.now(), feeBaseMsat = 1 msat, feeProportionalMillionths = 0)

    channelRelayer ! WrappedLocalChannelUpdate(u1)
    channelRelayer ! Relay(r)

    // relay succeeds with current channel update (u1) with lower fees
    expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)

    val u2 = createLocalUpdate(channelId1, timestamp = TimestampSecond.now() - 530)

    channelRelayer ! WrappedLocalChannelUpdate(u2)
    channelRelayer ! Relay(r)

    // relay succeeds because the current update (u2) with higher fees occurred less than 10 minutes ago
    expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)

    val u3 = createLocalUpdate(channelId1, timestamp = TimestampSecond.now() - 601)

    channelRelayer ! WrappedLocalChannelUpdate(u1)
    channelRelayer ! WrappedLocalChannelUpdate(u3)
    channelRelayer ! Relay(r)

    // relay fails because the current update (u3) with higher fees occurred more than 10 minutes ago
    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(FeeInsufficient(r.add.amountMsat, u3.channelUpdate)), commit = true))
  }

  test("fail to relay when there is a local error") { f =>
    import f._

    val channelId1 = channelIds(realScid1)
    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)
    val u_disabled = createLocalUpdate(channelId1, enabled = false)

    case class TestCase(exc: ChannelException, update: ChannelUpdate, failure: FailureMessage)

    val testCases = Seq(
      TestCase(ExpiryTooSmall(channelId1, CltvExpiry(100), CltvExpiry(0), BlockHeight(0)), u.channelUpdate, ExpiryTooSoon(u.channelUpdate)),
      TestCase(ExpiryTooBig(channelId1, CltvExpiry(100), CltvExpiry(200), BlockHeight(0)), u.channelUpdate, ExpiryTooFar),
      TestCase(InsufficientFunds(channelId1, payload.amountToForward, 100 sat, 0 sat, 0 sat), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
      TestCase(FeerateTooDifferent(channelId1, FeeratePerKw(1000 sat), FeeratePerKw(300 sat)), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
      TestCase(ChannelUnavailable(channelId1), u_disabled.channelUpdate, ChannelDisabled(u_disabled.channelUpdate.messageFlags, u_disabled.channelUpdate.channelFlags, u_disabled.channelUpdate))
    )

    testCases.foreach { testCase =>
      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! Relay(r)
      val fwd = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
      fwd.message.replyTo ! RES_ADD_FAILED(fwd.message, testCase.exc, Some(testCase.update))
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(testCase.failure), commit = true))
    }
  }

  test("select preferred channels") { f =>
    import f._

    /** This is just a simplified helper function with random values for fields we are not using here */
    def dummyLocalUpdate(shortChannelId: RealShortChannelId, remoteNodeId: PublicKey, availableBalanceForSend: MilliSatoshi, capacity: Satoshi) = {
      val channelId = randomBytes32()
      val update = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey(), remoteNodeId, shortChannelId, CltvExpiryDelta(10), 100 msat, 1000 msat, 100, capacity.toMilliSatoshi)
      val commitments = PaymentPacketSpec.makeCommitments(channelId, availableBalanceForSend, testCapacity = capacity)
      LocalChannelUpdate(null, channelId, Some(shortChannelId), null, remoteNodeId, None, update, commitments)
    }

    val (a, b) = (randomKey().publicKey, randomKey().publicKey)

    val channelUpdates = Map(
      ShortChannelId(11111) -> dummyLocalUpdate(realScid(11111), a, 100000000 msat, 200000 sat),
      ShortChannelId(12345) -> dummyLocalUpdate(realScid(12345), a, 10000000 msat, 200000 sat),
      ShortChannelId(22222) -> dummyLocalUpdate(realScid(22222), a, 10000000 msat, 100000 sat),
      ShortChannelId(22223) -> dummyLocalUpdate(realScid(22223), a, 9000000 msat, 50000 sat),
      ShortChannelId(33333) -> dummyLocalUpdate(realScid(33333), a, 100000 msat, 50000 sat),
      ShortChannelId(44444) -> dummyLocalUpdate(realScid(44444), b, 1000000 msat, 10000 sat),
    )

    channelUpdates.values.foreach(u => channelRelayer ! WrappedLocalChannelUpdate(u))

    {
      val payload = RelayLegacyPayload(ShortChannelId(12345), 998900 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 1000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      // select the channel to the same node, with the lowest capacity and balance but still high enough to handle the payment
      val cmd1 = expectFwdAdd(register, channelUpdates(ShortChannelId(22223)).channelId, payload.amountToForward, payload.outgoingCltv).message
      cmd1.replyTo ! RES_ADD_FAILED(cmd1, ChannelUnavailable(randomBytes32()), None)
      // select 2nd-to-best channel: higher capacity and balance
      val cmd2 = expectFwdAdd(register, channelUpdates(ShortChannelId(22222)).channelId, payload.amountToForward, payload.outgoingCltv).message
      cmd2.replyTo ! RES_ADD_FAILED(cmd2, TooManyAcceptedHtlcs(randomBytes32(), 42), Some(channelUpdates(ShortChannelId(22222)).channelUpdate))
      // select 3rd-to-best channel: same balance but higher capacity
      val cmd3 = expectFwdAdd(register, channelUpdates(ShortChannelId(12345)).channelId, payload.amountToForward, payload.outgoingCltv).message
      cmd3.replyTo ! RES_ADD_FAILED(cmd3, TooManyAcceptedHtlcs(randomBytes32(), 42), Some(channelUpdates(ShortChannelId(12345)).channelUpdate))
      // select 4th-to-best channel: same capacity but higher balance
      val cmd4 = expectFwdAdd(register, channelUpdates(ShortChannelId(11111)).channelId, payload.amountToForward, payload.outgoingCltv).message
      cmd4.replyTo ! RES_ADD_FAILED(cmd4, HtlcValueTooHighInFlight(randomBytes32(), UInt64(100000000), 100000000 msat), Some(channelUpdates(ShortChannelId(11111)).channelUpdate))
      // all the suitable channels have been tried
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(TemporaryChannelFailure(channelUpdates(ShortChannelId(12345)).channelUpdate)), commit = true))
    }
    {
      // higher amount payment (have to increased incoming htlc amount for fees to be sufficient)
      val payload = RelayLegacyPayload(ShortChannelId(12345), 50000000 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 60000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(11111)).channelId, payload.amountToForward, payload.outgoingCltv).message
    }
    {
      // lower amount payment
      val payload = RelayLegacyPayload(ShortChannelId(12345), 1000 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 60000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(33333)).channelId, payload.amountToForward, payload.outgoingCltv).message
    }
    {
      // payment too high, no suitable channel found, we keep the requested one
      val payload = RelayLegacyPayload(ShortChannelId(12345), 1000000000 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 1010000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(12345)).channelId, payload.amountToForward, payload.outgoingCltv).message
    }
    {
      // cltv expiry larger than our requirements
      val payload = RelayLegacyPayload(ShortChannelId(12345), 998900 msat, CltvExpiry(50))
      val r = createValidIncomingPacket(payload, 1000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(22223)).channelId, payload.amountToForward, payload.outgoingCltv).message
    }
    {
      // cltv expiry too small, no suitable channel found
      val payload = RelayLegacyPayload(ShortChannelId(12345), 998900 msat, CltvExpiry(61))
      val r = createValidIncomingPacket(payload, 1000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(CltvExpiry(61), channelUpdates(ShortChannelId(12345)).channelUpdate)), commit = true))
    }
  }

  test("settlement failure") { f =>
    import f._

    val channelId1 = channelIds(realScid1)
    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload, 1100000 msat, CltvExpiry(400100))
    val u = createLocalUpdate(channelId1)
    val u_disabled = createLocalUpdate(channelId1, enabled = false)
    val downstream_htlc = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket)

    case class TestCase(result: HtlcResult, cmd: channel.HtlcSettlementCommand)

    val testCases = Seq(
      TestCase(HtlcResult.RemoteFail(UpdateFailHtlc(channelId1, downstream_htlc.id, hex"deadbeef")), CMD_FAIL_HTLC(r.add.id, Left(hex"deadbeef"), commit = true)),
      TestCase(HtlcResult.RemoteFailMalformed(UpdateFailMalformedHtlc(channelId1, downstream_htlc.id, ByteVector32.One, FailureMessageCodecs.BADONION)), CMD_FAIL_MALFORMED_HTLC(r.add.id, ByteVector32.One, FailureMessageCodecs.BADONION, commit = true)),
      TestCase(HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId1, downstream_htlc)), CMD_FAIL_HTLC(r.add.id, Right(PermanentChannelFailure), commit = true)),
      TestCase(HtlcResult.DisconnectedBeforeSigned(u_disabled.channelUpdate), CMD_FAIL_HTLC(r.add.id, Right(TemporaryChannelFailure(u_disabled.channelUpdate)), commit = true)),
      TestCase(HtlcResult.ChannelFailureBeforeSigned, CMD_FAIL_HTLC(r.add.id, Right(PermanentChannelFailure), commit = true))
    )

    testCases.foreach { testCase =>
      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! Relay(r)
      val fwd = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
      fwd.message.replyTo ! RES_SUCCESS(fwd.message, channelId1)
      fwd.message.origin.replyTo ! RES_ADD_SETTLED(fwd.message.origin, downstream_htlc, testCase.result)
      expectFwdFail(register, r.add.channelId, testCase.cmd)
    }
  }

  test("settlement success") { f =>
    import f._
    val eventListener = TestProbe[ChannelPaymentRelayed]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)

    val channelId1 = channelIds(realScid1)
    val payload = RelayLegacyPayload(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)
    val downstream_htlc = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket)

    case class TestCase(result: HtlcResult)

    val testCases = Seq(
      TestCase(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(channelId1, downstream_htlc.id, paymentPreimage))),
      TestCase(HtlcResult.OnChainFulfill(paymentPreimage))
    )

    testCases.foreach { testCase =>
      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! Relay(r)

      val fwd1 = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
      fwd1.message.replyTo ! RES_SUCCESS(fwd1.message, channelId1)
      fwd1.message.origin.replyTo ! RES_ADD_SETTLED(fwd1.message.origin, downstream_htlc, testCase.result)

      val fwd2 = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd2.channelId == r.add.channelId)
      assert(fwd2.message.id == r.add.id)
      assert(fwd2.message.r == paymentPreimage)

      val paymentRelayed = eventListener.expectMessageType[ChannelPaymentRelayed]
      assert(paymentRelayed.copy(timestamp = 0 unixms) == ChannelPaymentRelayed(r.add.amountMsat, r.payload.amountToForward, r.add.paymentHash, r.add.channelId, channelId1, timestamp = 0 unixms))
    }
  }

  test("get outgoing channels") { f =>
    import PaymentPacketSpec._
    import f._
    val channelId_ab = randomBytes32()
    val channelId_bc = randomBytes32()
    val alias_ab = ShortChannelId.generateLocalAlias()
    val alias_bc = ShortChannelId.generateLocalAlias()
    val a = PaymentPacketSpec.a

    val sender = TestProbe[Relayer.OutgoingChannels]()

    def getOutgoingChannels(enabledOnly: Boolean): Seq[Relayer.OutgoingChannel] = {
      channelRelayer ! GetOutgoingChannels(sender.ref.toClassic, Relayer.GetOutgoingChannels(enabledOnly))
      val Relayer.OutgoingChannels(channels) = sender.expectMessageType[Relayer.OutgoingChannels]
      channels
    }

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, Some(channelUpdate_ab.shortChannelId.toReal), alias_ab, a, None, channelUpdate_ab, makeCommitments(channelId_ab, -2000 msat, 300000 msat)))
    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_bc, Some(channelUpdate_bc.shortChannelId.toReal), alias_bc, c, None, channelUpdate_bc, makeCommitments(channelId_bc, 400000 msat, -5000 msat)))

    val channels1 = getOutgoingChannels(true)
    assert(channels1.size == 2)
    assert(channels1.head.channelUpdate == channelUpdate_ab)
    assert(channels1.head.toChannelBalance == Relayer.ChannelBalance(a, channelUpdate_ab.shortChannelId, 0 msat, 300000 msat, isPublic = false, isEnabled = true))
    assert(channels1.last.channelUpdate == channelUpdate_bc)
    assert(channels1.last.toChannelBalance == Relayer.ChannelBalance(c, channelUpdate_bc.shortChannelId, 400000 msat, 0 msat, isPublic = false, isEnabled = true))

    channelRelayer ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(null, channelId_bc, Some(channelUpdate_bc.shortChannelId.toReal), alias_ab, makeCommitments(channelId_bc, 200000 msat, 500000 msat)))
    val channels2 = getOutgoingChannels(true)
    assert(channels2.last.commitments.availableBalanceForReceive == 500000.msat && channels2.last.commitments.availableBalanceForSend == 200000.msat)

    channelRelayer ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(null, channelId_ab, Some(channelUpdate_ab.shortChannelId.toReal), alias_ab, makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    channelRelayer ! WrappedLocalChannelDown(LocalChannelDown(null, channelId_bc, Some(channelUpdate_bc.shortChannelId.toReal), alias_ab, c))
    val channels3 = getOutgoingChannels(true)
    assert(channels3.size == 1 && channels3.head.commitments.availableBalanceForSend == 100000.msat)

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, Some(channelUpdate_ab.shortChannelId.toReal), alias_ab, a, None, channelUpdate_ab.copy(channelFlags = ChannelUpdate.ChannelFlags(isEnabled = false, isNode1 = true)), makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    val channels4 = getOutgoingChannels(true)
    assert(channels4.isEmpty)
    val channels5 = getOutgoingChannels(false)
    assert(channels5.size == 1)

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, Some(channelUpdate_ab.shortChannelId.toReal), alias_ab, a, None, channelUpdate_ab, makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    val channels6 = getOutgoingChannels(true)
    assert(channels6.size == 1)
  }

}

object ChannelRelayerSpec {
  val paymentPreimage: ByteVector32 = randomBytes32()
  val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)

  val outgoingAmount: MilliSatoshi = 1000000 msat
  val outgoingExpiry: CltvExpiry = CltvExpiry(400000)
  val outgoingNodeId: PublicKey = randomKey().publicKey

  val realScid1: RealShortChannelId = ShortChannelId(111111).toReal
  val realScId2: RealShortChannelId = ShortChannelId(222222).toReal

  val localAlias1: LocalAlias = ShortChannelId(111000).toAlias
  val localAlias2: LocalAlias = ShortChannelId(222000).toAlias

  val remoteAlias1: ShortChannelId = ShortChannelId(111999)

  val channelId1: ByteVector32 = randomBytes32()
  val channelId2: ByteVector32 = randomBytes32()

  val channelIds = Map(
    realScid1 -> channelId1,
    realScId2 -> channelId2,
    localAlias1 -> channelId1,
    localAlias2 -> channelId2,
  )

  def createValidIncomingPacket(payload: ChannelRelayPayload, amountIn: MilliSatoshi = 1_100_000 msat, expiryIn: CltvExpiry = CltvExpiry(400_100)): IncomingPaymentPacket.ChannelRelayPacket = {
    val add_ab = UpdateAddHtlc(channelId = randomBytes32(), id = 123456, amountIn, paymentHash, expiryIn, emptyOnionPacket)
    ChannelRelayPacket(add_ab, payload, emptyOnionPacket)
  }

  def createLocalUpdate(channelId: ByteVector32, channelUpdateScid_opt: Option[ShortChannelId] = None, balance: MilliSatoshi = 10000000 msat, capacity: Satoshi = 500000 sat, enabled: Boolean = true, htlcMinimum: MilliSatoshi = 0 msat, timestamp: TimestampSecond = 0 unixsec, feeBaseMsat: MilliSatoshi = 1000 msat, feeProportionalMillionths: Long = 100, optionScidAlias: Boolean = false): LocalChannelUpdate = {
    val realScid = channelIds.collectFirst { case (realScid: RealShortChannelId, cid) if cid == channelId => realScid }.get
    val localAlias = channelIds.collectFirst { case (localAlias: LocalAlias, cid) if cid == channelId => localAlias }.get
    val channelUpdateScid = channelUpdateScid_opt.getOrElse(realScid)
    val update = ChannelUpdate(ByteVector64(randomBytes(64)), Block.RegtestGenesisBlock.hash, channelUpdateScid, timestamp, ChannelUpdate.ChannelFlags(isNode1 = true, isEnabled = enabled), CltvExpiryDelta(100), htlcMinimum, feeBaseMsat, feeProportionalMillionths, Some(capacity.toMilliSatoshi))
    val channelFeatures = if (optionScidAlias) ChannelFeatures(ScidAlias) else ChannelFeatures()
    val commitments = PaymentPacketSpec.makeCommitments(channelId, testAvailableBalanceForSend = balance, testCapacity = capacity, channelFeatures = channelFeatures)
    LocalChannelUpdate(null, channelId, realShortChannelId_opt = Some(realScid), localAlias = localAlias, outgoingNodeId, None, update, commitments)
  }
}