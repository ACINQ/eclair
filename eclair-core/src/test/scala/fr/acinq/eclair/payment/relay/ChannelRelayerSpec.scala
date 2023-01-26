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
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair.Features.ScidAlias
import fr.acinq.eclair.TestConstants.emptyOnionPacket
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.IncomingPaymentPacket.ChannelRelayPacket
import fr.acinq.eclair.payment.relay.ChannelRelayer._
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPaymentPacket, PaymentPacketSpec}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.PaymentOnion.IntermediatePayload
import fr.acinq.eclair.wire.protocol.PaymentOnion.IntermediatePayload.ChannelRelay
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, NodeParams, RealShortChannelId, TestConstants, randomBytes32, _}
import org.scalatest.Inside.inside
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
    inside(fwd.message) { case add: CMD_ADD_HTLC =>
      assert(add.amount == outAmount)
      assert(add.cltvExpiry == outExpiry)
      inside(add.origin) { case o: Origin.ChannelRelayedHot =>
        assert(o.amountOut == outAmount)
      }
    }
    assert(fwd.channelId == channelId)
    fwd
  }

  def basicRelayTest(f: FixtureParam)(relayPayloadScid: ShortChannelId, lcu: LocalChannelUpdate, success: Boolean): Unit = {
    import f._

    val payload = ChannelRelay.Standard(relayPayloadScid, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)

    channelRelayer ! WrappedLocalChannelUpdate(lcu)
    channelRelayer ! Relay(r)

    if (success) {
      expectFwdAdd(register, lcu.channelId, outgoingAmount, outgoingExpiry)
    } else {
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer()), commit = true))
    }
  }

  test("relay with real scid (channel update uses real scid)") { f =>
    basicRelayTest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1), success = true)
  }

  test("relay with real scid (channel update uses local alias)") { f =>
    basicRelayTest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1, channelUpdateScid_opt = Some(localAlias1)), success = true)
  }

  test("relay with local alias (channel update uses real scid)") { f =>
    basicRelayTest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1), success = true)
  }

  test("relay with local alias (channel update uses local alias)") { f =>
    basicRelayTest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1, channelUpdateScid_opt = Some(localAlias1)), success = true)
  }

  test("fail to relay with real scid when option_scid_alias is enabled (channel update uses real scid)") { f =>
    basicRelayTest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1, optionScidAlias = true), success = false)
  }

  test("fail to relay with real scid when option_scid_alias is enabled (channel update uses local alias)") { f =>
    basicRelayTest(f)(relayPayloadScid = realScid1, lcu = createLocalUpdate(channelId1, optionScidAlias = true, channelUpdateScid_opt = Some(localAlias1)), success = false)
  }

  test("relay with local alias when option_scid_alias is enabled (channel update uses real scid)") { f =>
    basicRelayTest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1, optionScidAlias = true), success = true)
  }

  test("relay with local alias when option_scid_alias is enabled (channel update uses local alias)") { f =>
    basicRelayTest(f)(relayPayloadScid = localAlias1, lcu = createLocalUpdate(channelId1, optionScidAlias = true, channelUpdateScid_opt = Some(localAlias1)), success = true)
  }

  test("relay with new real scid after reorg") { f =>
    import f._

    // initial channel update
    val lcu1 = createLocalUpdate(channelId1)
    val payload1 = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r1 = createValidIncomingPacket(payload1)
    channelRelayer ! WrappedLocalChannelUpdate(lcu1)
    channelRelayer ! Relay(r1)
    expectFwdAdd(register, lcu1.channelId, outgoingAmount, outgoingExpiry)

    // reorg happens
    val realScid1AfterReorg = RealShortChannelId(111112)
    val lcu2 = createLocalUpdate(channelId1).modify(_.shortIds.real).setTo(RealScidStatus.Final(realScid1AfterReorg))
    val payload2 = ChannelRelay.Standard(realScid1AfterReorg, outgoingAmount, outgoingExpiry)
    val r2 = createValidIncomingPacket(payload2)
    channelRelayer ! WrappedLocalChannelUpdate(lcu2)

    // both old and new real scids work
    channelRelayer ! Relay(r1)
    expectFwdAdd(register, lcu1.channelId, outgoingAmount, outgoingExpiry)
    // new real scid works
    channelRelayer ! Relay(r2)
    expectFwdAdd(register, lcu2.channelId, outgoingAmount, outgoingExpiry)
  }

  test("relay blinded payment") { f =>
    import f._

    val u = createLocalUpdate(channelId1, feeBaseMsat = 2500 msat, feeProportionalMillionths = 0)
    val payload = createBlindedPayload(u.channelUpdate, isIntroduction = false)
    val r = createValidIncomingPacket(payload, outgoingAmount + u.channelUpdate.feeBaseMsat, outgoingExpiry + u.channelUpdate.cltvExpiryDelta)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
  }

  test("relay with retries") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)

    // we tell the relayer about the first channel
    val u1 = createLocalUpdate(channelId1)
    channelRelayer ! WrappedLocalChannelUpdate(u1)

    // this is another channel, with less balance (it will be preferred)
    val u2 = createLocalUpdate(channelId2, balance = 80_000_000 msat)
    channelRelayer ! WrappedLocalChannelUpdate(u2)

    channelRelayer ! Relay(r)

    // first try
    val fwd1 = expectFwdAdd(register, channelIds(realScid2), outgoingAmount, outgoingExpiry)
    // channel returns an error
    fwd1.message.replyTo ! RES_ADD_FAILED(fwd1.message, HtlcValueTooHighInFlight(channelIds(realScid2), 1000000000 msat, 1516977616 msat), Some(u2.channelUpdate))

    // second try
    val fwd2 = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
    // failure again
    fwd1.message.replyTo ! RES_ADD_FAILED(fwd2.message, HtlcValueTooHighInFlight(channelIds(realScid1), 1000000000 msat, 1516977616 msat), Some(u1.channelUpdate))

    // the relayer should give up
    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(TemporaryChannelFailure(u1.channelUpdate)), commit = true))
  }

  test("fail to relay when we have no channel_update for the next channel") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)

    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer()), commit = true))
  }

  test("fail to relay when register returns an error") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    val fwd = expectFwdAdd(register, channelIds(realScid1), outgoingAmount, outgoingExpiry)
    fwd.replyTo ! Register.ForwardFailure(fwd)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer()), commit = true))
  }

  test("fail to relay when the channel is advertised as unusable (down)") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)
    val d = LocalChannelDown(null, channelId1, createShortIds(channelId1), outgoingNodeId)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedLocalChannelDown(d)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer()), commit = true))
  }

  test("fail to relay when channel is disabled") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1, enabled = false)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(ChannelDisabled(u.channelUpdate.messageFlags, u.channelUpdate.channelFlags, u.channelUpdate)), commit = true))
  }

  test("fail to relay when amount is below minimum") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1, htlcMinimum = outgoingAmount + 1.msat)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(AmountBelowMinimum(outgoingAmount, u.channelUpdate)), commit = true))
  }

  test("fail to relay blinded payment") { f =>
    import f._

    Seq(true, false).foreach { isIntroduction =>
      // The outgoing channel is disabled, so we won't be able to relay the payment.
      val u = createLocalUpdate(channelId1, feeBaseMsat = 5000 msat, feeProportionalMillionths = 0, enabled = false)
      val r = createValidIncomingPacket(createBlindedPayload(u.channelUpdate, isIntroduction), outgoingAmount + u.channelUpdate.feeBaseMsat, outgoingExpiry + u.channelUpdate.cltvExpiryDelta)

      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! Relay(r)

      val cmd = register.expectMessageType[Register.Forward[channel.Command]]
      assert(cmd.channelId == r.add.channelId)
      if (isIntroduction) {
        assert(cmd.message.isInstanceOf[CMD_FAIL_HTLC])
        val fail = cmd.message.asInstanceOf[CMD_FAIL_HTLC]
        assert(fail.id == r.add.id)
        assert(fail.reason == Right(InvalidOnionBlinding(Sphinx.hash(r.add.onionRoutingPacket))))
        assert(fail.delay_opt.nonEmpty)
      } else {
        assert(cmd.message.isInstanceOf[CMD_FAIL_MALFORMED_HTLC])
        val fail = cmd.message.asInstanceOf[CMD_FAIL_MALFORMED_HTLC]
        assert(fail.id == r.add.id)
        assert(fail.onionHash == Sphinx.hash(r.add.onionRoutingPacket))
        assert(fail.failureCode == InvalidOnionBlinding(Sphinx.hash(r.add.onionRoutingPacket)).code)
      }
    }
  }

  test("relay when expiry larger than our requirements") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val u = createLocalUpdate(channelId1)
    val r = createValidIncomingPacket(payload, expiryIn = outgoingExpiry + u.channelUpdate.cltvExpiryDelta + CltvExpiryDelta(1))

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdAdd(register, channelIds(realScid1), r.amountToForward, r.outgoingCltv).message
  }

  test("fail to relay when expiry is too small") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val u = createLocalUpdate(channelId1)
    val r = createValidIncomingPacket(payload, expiryIn = outgoingExpiry + u.channelUpdate.cltvExpiryDelta - CltvExpiryDelta(1))

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(r.outgoingCltv, u.channelUpdate)), commit = true))
  }

  test("fail to relay when fee is insufficient") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload, amountIn = outgoingAmount + 1.msat)
    val u = createLocalUpdate(channelId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! Relay(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(FeeInsufficient(r.add.amountMsat, u.channelUpdate)), commit = true))
  }

  test("relay that would fail (fee insufficient) with a recent channel update but succeed with the previous update") { f =>
    import f._

    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
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
    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)
    val u_disabled = createLocalUpdate(channelId1, enabled = false)

    case class TestCase(exc: ChannelException, update: ChannelUpdate, failure: FailureMessage)

    val testCases = Seq(
      TestCase(ExpiryTooSmall(channelId1, CltvExpiry(100), CltvExpiry(0), BlockHeight(0)), u.channelUpdate, ExpiryTooSoon(u.channelUpdate)),
      TestCase(ExpiryTooBig(channelId1, CltvExpiry(100), CltvExpiry(200), BlockHeight(0)), u.channelUpdate, ExpiryTooFar()),
      TestCase(TooManyAcceptedHtlcs(channelId1, 10), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
      TestCase(HtlcValueTooHighInFlight(channelId1, 250_000_000 msat, 300_000_000 msat), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
      TestCase(InsufficientFunds(channelId1, r.amountToForward, 100 sat, 0 sat, 0 sat), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
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
      val shortIds = ShortIds(real = RealScidStatus.Final(shortChannelId), localAlias = ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
      LocalChannelUpdate(null, channelId, shortIds, remoteNodeId, None, update, commitments)
    }

    val (a, b) = (randomKey().publicKey, randomKey().publicKey)

    val channelUpdates = Map(
      ShortChannelId(11111) -> dummyLocalUpdate(RealShortChannelId(11111), a, 100000000 msat, 200000 sat),
      ShortChannelId(12345) -> dummyLocalUpdate(RealShortChannelId(12345), a, 10000000 msat, 200000 sat),
      ShortChannelId(22222) -> dummyLocalUpdate(RealShortChannelId(22222), a, 10000000 msat, 100000 sat),
      ShortChannelId(22223) -> dummyLocalUpdate(RealShortChannelId(22223), a, 9000000 msat, 50000 sat),
      ShortChannelId(33333) -> dummyLocalUpdate(RealShortChannelId(33333), a, 100000 msat, 50000 sat),
      ShortChannelId(44444) -> dummyLocalUpdate(RealShortChannelId(44444), b, 1000000 msat, 10000 sat),
    )

    channelUpdates.values.foreach(u => channelRelayer ! WrappedLocalChannelUpdate(u))

    {
      val payload = ChannelRelay.Standard(ShortChannelId(12345), 998900 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 1000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      // select the channel to the same node, with the lowest capacity and balance but still high enough to handle the payment
      val cmd1 = expectFwdAdd(register, channelUpdates(ShortChannelId(22223)).channelId, r.amountToForward, r.outgoingCltv).message
      cmd1.replyTo ! RES_ADD_FAILED(cmd1, ChannelUnavailable(randomBytes32()), None)
      // select 2nd-to-best channel: higher capacity and balance
      val cmd2 = expectFwdAdd(register, channelUpdates(ShortChannelId(22222)).channelId, r.amountToForward, r.outgoingCltv).message
      cmd2.replyTo ! RES_ADD_FAILED(cmd2, TooManyAcceptedHtlcs(randomBytes32(), 42), Some(channelUpdates(ShortChannelId(22222)).channelUpdate))
      // select 3rd-to-best channel: same balance but higher capacity
      val cmd3 = expectFwdAdd(register, channelUpdates(ShortChannelId(12345)).channelId, r.amountToForward, r.outgoingCltv).message
      cmd3.replyTo ! RES_ADD_FAILED(cmd3, TooManyAcceptedHtlcs(randomBytes32(), 42), Some(channelUpdates(ShortChannelId(12345)).channelUpdate))
      // select 4th-to-best channel: same capacity but higher balance
      val cmd4 = expectFwdAdd(register, channelUpdates(ShortChannelId(11111)).channelId, r.amountToForward, r.outgoingCltv).message
      cmd4.replyTo ! RES_ADD_FAILED(cmd4, HtlcValueTooHighInFlight(randomBytes32(), 100000000 msat, 100000000 msat), Some(channelUpdates(ShortChannelId(11111)).channelUpdate))
      // all the suitable channels have been tried
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(TemporaryChannelFailure(channelUpdates(ShortChannelId(12345)).channelUpdate)), commit = true))
    }
    {
      // higher amount payment (have to increased incoming htlc amount for fees to be sufficient)
      val payload = ChannelRelay.Standard(ShortChannelId(12345), 50000000 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 60000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(11111)).channelId, r.amountToForward, r.outgoingCltv).message
    }
    {
      // lower amount payment
      val payload = ChannelRelay.Standard(ShortChannelId(12345), 1000 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 60000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(33333)).channelId, r.amountToForward, r.outgoingCltv).message
    }
    {
      // payment too high, no suitable channel found, we keep the requested one
      val payload = ChannelRelay.Standard(ShortChannelId(12345), 1000000000 msat, CltvExpiry(60))
      val r = createValidIncomingPacket(payload, 1010000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(12345)).channelId, r.amountToForward, r.outgoingCltv).message
    }
    {
      // cltv expiry larger than our requirements
      val payload = ChannelRelay.Standard(ShortChannelId(12345), 998900 msat, CltvExpiry(50))
      val r = createValidIncomingPacket(payload, 1000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdAdd(register, channelUpdates(ShortChannelId(22223)).channelId, r.amountToForward, r.outgoingCltv).message
    }
    {
      // cltv expiry too small, no suitable channel found
      val payload = ChannelRelay.Standard(ShortChannelId(12345), 998900 msat, CltvExpiry(61))
      val r = createValidIncomingPacket(payload, 1000000 msat, CltvExpiry(70))
      channelRelayer ! Relay(r)
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(CltvExpiry(61), channelUpdates(ShortChannelId(12345)).channelUpdate)), commit = true))
    }
  }

  test("settlement failure") { f =>
    import f._

    val u = createLocalUpdate(channelId1, feeBaseMsat = 5000 msat, feeProportionalMillionths = 0)
    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload, outgoingAmount + u.channelUpdate.feeBaseMsat, outgoingExpiry + u.channelUpdate.cltvExpiryDelta)
    val u_disabled = createLocalUpdate(channelId1, enabled = false)
    val downstream_htlc = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket, None)

    case class TestCase(result: HtlcResult, cmd: channel.HtlcSettlementCommand)

    val testCases = Seq(
      TestCase(HtlcResult.RemoteFail(UpdateFailHtlc(channelId1, downstream_htlc.id, hex"deadbeef")), CMD_FAIL_HTLC(r.add.id, Left(hex"deadbeef"), commit = true)),
      TestCase(HtlcResult.RemoteFailMalformed(UpdateFailMalformedHtlc(channelId1, downstream_htlc.id, ByteVector32.One, FailureMessageCodecs.BADONION | FailureMessageCodecs.PERM | 5)), CMD_FAIL_HTLC(r.add.id, Right(InvalidOnionHmac(ByteVector32.One)), commit = true)),
      TestCase(HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId1, downstream_htlc)), CMD_FAIL_HTLC(r.add.id, Right(PermanentChannelFailure()), commit = true)),
      TestCase(HtlcResult.DisconnectedBeforeSigned(u_disabled.channelUpdate), CMD_FAIL_HTLC(r.add.id, Right(TemporaryChannelFailure(u_disabled.channelUpdate)), commit = true)),
      TestCase(HtlcResult.ChannelFailureBeforeSigned, CMD_FAIL_HTLC(r.add.id, Right(PermanentChannelFailure()), commit = true))
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

  test("settlement failure (blinded payment)") { f =>
    import f._

    val u = createLocalUpdate(channelId1, feeBaseMsat = 5000 msat, feeProportionalMillionths = 0)
    val downstream = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket, None)

    val testCases = Seq(
      HtlcResult.RemoteFail(UpdateFailHtlc(channelId1, downstream.id, hex"deadbeef")),
      HtlcResult.RemoteFailMalformed(UpdateFailMalformedHtlc(channelId1, downstream.id, randomBytes32(), FailureMessageCodecs.BADONION | FailureMessageCodecs.PERM | 5)),
      HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId1, downstream)),
      HtlcResult.DisconnectedBeforeSigned(createLocalUpdate(channelId1, enabled = false).channelUpdate),
      HtlcResult.ChannelFailureBeforeSigned,
    )

    Seq(true, false).foreach { isIntroduction =>
      testCases.foreach { htlcResult =>
        val r = createValidIncomingPacket(createBlindedPayload(u.channelUpdate, isIntroduction), outgoingAmount + u.channelUpdate.feeBaseMsat, outgoingExpiry + u.channelUpdate.cltvExpiryDelta)
        channelRelayer ! WrappedLocalChannelUpdate(u)
        channelRelayer ! Relay(r)
        val fwd = expectFwdAdd(register, channelId1, outgoingAmount, outgoingExpiry)
        fwd.message.replyTo ! RES_SUCCESS(fwd.message, channelId1)
        fwd.message.origin.replyTo ! RES_ADD_SETTLED(fwd.message.origin, downstream, htlcResult)
        val cmd = register.expectMessageType[Register.Forward[channel.Command]]
        assert(cmd.channelId == r.add.channelId)
        if (isIntroduction) {
          assert(cmd.message.isInstanceOf[CMD_FAIL_HTLC])
          val fail = cmd.message.asInstanceOf[CMD_FAIL_HTLC]
          assert(fail.id == r.add.id)
          assert(fail.reason == Right(InvalidOnionBlinding(Sphinx.hash(r.add.onionRoutingPacket))))
          assert(fail.delay_opt.nonEmpty)
        } else {
          assert(cmd.message.isInstanceOf[CMD_FAIL_MALFORMED_HTLC])
          val fail = cmd.message.asInstanceOf[CMD_FAIL_MALFORMED_HTLC]
          assert(fail.id == r.add.id)
          assert(fail.onionHash == Sphinx.hash(r.add.onionRoutingPacket))
          assert(fail.failureCode == InvalidOnionBlinding(Sphinx.hash(r.add.onionRoutingPacket)).code)
        }
      }
    }
  }

  test("settlement success") { f =>
    import f._
    val eventListener = TestProbe[ChannelPaymentRelayed]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)

    val channelId1 = channelIds(realScid1)
    val payload = ChannelRelay.Standard(realScid1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(payload)
    val u = createLocalUpdate(channelId1)
    val downstream_htlc = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket, None)

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
      assert(paymentRelayed.copy(timestamp = 0 unixms) == ChannelPaymentRelayed(r.add.amountMsat, r.amountToForward, r.add.paymentHash, r.add.channelId, channelId1, timestamp = 0 unixms))
    }
  }

  test("get outgoing channels") { f =>
    import PaymentPacketSpec._
    import f._
    val channelId_ab = randomBytes32()
    val channelId_bc = randomBytes32()
    val shortIds_ab = ShortIds(RealScidStatus.Final(RealShortChannelId(channelUpdate_ab.shortChannelId.toLong)), ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
    val shortIds_bc = ShortIds(RealScidStatus.Final(RealShortChannelId(channelUpdate_bc.shortChannelId.toLong)), ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
    val a = PaymentPacketSpec.a

    val sender = TestProbe[Relayer.OutgoingChannels]()

    def getOutgoingChannels(enabledOnly: Boolean): Seq[Relayer.OutgoingChannel] = {
      channelRelayer ! GetOutgoingChannels(sender.ref.toClassic, Relayer.GetOutgoingChannels(enabledOnly))
      val Relayer.OutgoingChannels(channels) = sender.expectMessageType[Relayer.OutgoingChannels]
      channels
    }

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, shortIds_ab, a, None, channelUpdate_ab, makeCommitments(channelId_ab, -2000 msat, 300000 msat)))
    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_bc, shortIds_bc, c, None, channelUpdate_bc, makeCommitments(channelId_bc, 400000 msat, -5000 msat)))

    val channels1 = getOutgoingChannels(true)
    assert(channels1.size == 2)
    assert(channels1.head.channelUpdate == channelUpdate_ab)
    assert(channels1.head.toChannelBalance == Relayer.ChannelBalance(a, shortIds_ab, 0 msat, 300000 msat, isPublic = false, isEnabled = true))
    assert(channels1.last.channelUpdate == channelUpdate_bc)
    assert(channels1.last.toChannelBalance == Relayer.ChannelBalance(c, shortIds_bc, 400000 msat, 0 msat, isPublic = false, isEnabled = true))

    channelRelayer ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(null, channelId_bc, shortIds_ab, makeCommitments(channelId_bc, 200000 msat, 500000 msat)))
    val channels2 = getOutgoingChannels(true)
    assert(channels2.last.commitments.availableBalanceForReceive == 500000.msat && channels2.last.commitments.availableBalanceForSend == 200000.msat)

    channelRelayer ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(null, channelId_ab, shortIds_ab, makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    channelRelayer ! WrappedLocalChannelDown(LocalChannelDown(null, channelId_bc, shortIds_ab, c))
    val channels3 = getOutgoingChannels(true)
    assert(channels3.size == 1 && channels3.head.commitments.availableBalanceForSend == 100000.msat)

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, shortIds_ab, a, None, channelUpdate_ab.copy(channelFlags = ChannelUpdate.ChannelFlags(isEnabled = false, isNode1 = true)), makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    val channels4 = getOutgoingChannels(true)
    assert(channels4.isEmpty)
    val channels5 = getOutgoingChannels(false)
    assert(channels5.size == 1)

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, shortIds_ab, a, None, channelUpdate_ab, makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    val channels6 = getOutgoingChannels(true)
    assert(channels6.size == 1)
  }

}

object ChannelRelayerSpec {
  val paymentPreimage: ByteVector32 = randomBytes32()
  val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)

  val outgoingAmount: MilliSatoshi = 10_000_000 msat
  val outgoingExpiry: CltvExpiry = CltvExpiry(400000)
  val outgoingNodeId: PublicKey = randomKey().publicKey

  val realScid1: RealShortChannelId = RealShortChannelId(111111)
  val realScid2: RealShortChannelId = RealShortChannelId(222222)

  val localAlias1: Alias = Alias(111000)
  val localAlias2: Alias = Alias(222000)

  val channelId1: ByteVector32 = randomBytes32()
  val channelId2: ByteVector32 = randomBytes32()

  val channelIds = Map(
    realScid1 -> channelId1,
    realScid2 -> channelId2,
    localAlias1 -> channelId1,
    localAlias2 -> channelId2,
  )

  def createBlindedPayload(update: ChannelUpdate, isIntroduction: Boolean): ChannelRelay.Blinded = {
    val tlvs = TlvStream[OnionPaymentPayloadTlv](Set(
      Some(OnionPaymentPayloadTlv.EncryptedRecipientData(hex"2a")),
      if (isIntroduction) Some(OnionPaymentPayloadTlv.BlindingPoint(randomKey().publicKey)) else None,
    ).flatten[OnionPaymentPayloadTlv])
    val blindedTlvs = TlvStream[RouteBlindingEncryptedDataTlv](
      RouteBlindingEncryptedDataTlv.OutgoingChannelId(update.shortChannelId),
      RouteBlindingEncryptedDataTlv.PaymentRelay(update.cltvExpiryDelta, update.feeProportionalMillionths, update.feeBaseMsat),
      RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(500_000), 0 msat),
    )
    ChannelRelay.Blinded(tlvs, blindedTlvs, randomKey().publicKey)
  }

  def createValidIncomingPacket(payload: IntermediatePayload.ChannelRelay, amountIn: MilliSatoshi = 11_000_000 msat, expiryIn: CltvExpiry = CltvExpiry(400_100)): IncomingPaymentPacket.ChannelRelayPacket = {
    val nextBlinding_opt = payload match {
      case p: ChannelRelay.Blinded => Some(p.nextBlinding)
      case _: ChannelRelay.Standard => None
    }
    val add_ab = UpdateAddHtlc(channelId = randomBytes32(), id = 123456, amountIn, paymentHash, expiryIn, emptyOnionPacket, nextBlinding_opt)
    ChannelRelayPacket(add_ab, payload, emptyOnionPacket)
  }

  def createShortIds(channelId: ByteVector32) = {
    val realScid = channelIds.collectFirst { case (realScid: RealShortChannelId, cid) if cid == channelId => realScid }.get
    val localAlias = channelIds.collectFirst { case (localAlias: Alias, cid) if cid == channelId => localAlias }.get
    ShortIds(real = RealScidStatus.Final(realScid), localAlias, remoteAlias_opt = None)
  }

  def createLocalUpdate(channelId: ByteVector32, channelUpdateScid_opt: Option[ShortChannelId] = None, balance: MilliSatoshi = 100_000_000 msat, capacity: Satoshi = 5_000_000 sat, enabled: Boolean = true, htlcMinimum: MilliSatoshi = 0 msat, timestamp: TimestampSecond = 0 unixsec, feeBaseMsat: MilliSatoshi = 1000 msat, feeProportionalMillionths: Long = 100, optionScidAlias: Boolean = false): LocalChannelUpdate = {
    val shortIds = createShortIds(channelId)
    val channelUpdateScid = channelUpdateScid_opt.getOrElse(shortIds.real.toOption.get)
    val update = ChannelUpdate(ByteVector64(randomBytes(64)), Block.RegtestGenesisBlock.hash, channelUpdateScid, timestamp, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isNode1 = true, isEnabled = enabled), CltvExpiryDelta(100), htlcMinimum, feeBaseMsat, feeProportionalMillionths, capacity.toMilliSatoshi)
    val features: Set[PermanentChannelFeature] = Set(
      if (optionScidAlias) Some(ScidAlias) else None,
    ).flatten
    val channelFeatures = ChannelFeatures(features)
    val commitments = PaymentPacketSpec.makeCommitments(channelId, testAvailableBalanceForSend = balance, testCapacity = capacity, channelFeatures = channelFeatures)
    LocalChannelUpdate(null, channelId, shortIds, outgoingNodeId, None, update, commitments)
  }
}