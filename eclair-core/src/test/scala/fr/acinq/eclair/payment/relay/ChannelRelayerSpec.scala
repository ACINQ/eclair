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
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.TestConstants.emptyOnionPacket
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.IncomingPacket.ChannelRelayPacket
import fr.acinq.eclair.payment.relay.ChannelRelayer.{Command => _, _}
import fr.acinq.eclair.payment.relay.Relayer.{GetOutgoingChannels, OutgoingChannels, UsableBalance}
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPacket, PaymentPacketSpec}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.Onion.{ChannelRelayPayload, ChannelRelayTlvPayload, RelayLegacyPayload}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, NodeParams, TestConstants, randomBytes32, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

class ChannelRelayerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  import ChannelRelayerSpec._

  case class FixtureParam(nodeParams: NodeParams, channelRelayer: typed.ActorRef[ChannelRelayer.Command], register: TestProbe[Any])

  override def withFixture(test: OneArgTest): Outcome = {
    eventually {
      // we are node B in the route A -> B -> C -> ....
      val nodeParams = TestConstants.Bob.nodeParams
      val register = TestProbe[Any]("register")
      val channelRelayer = testKit.spawn(ChannelRelayer.apply(nodeParams, register.ref.toClassic))
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, channelRelayer, register)))
    }
  }

  def expectFwdFail(register: TestProbe[Any], channelId: ByteVector32, cmd: Command) = {
    val fwd = register.expectMessageType[Register.Forward[Command]]
    assert(fwd.channelId === channelId)
    assert(fwd.message === cmd)
    fwd
  }

  def expectFwdAdd(register: TestProbe[Any], shortChannelId: ShortChannelId, outAmount: MilliSatoshi, outExpiry: CltvExpiry) = {
    val fwd = register.expectMessageType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === shortChannelId)
    assert(fwd.message.amount === outAmount)
    assert(fwd.message.cltvExpiry === outExpiry)
    assert(fwd.message.origin.isInstanceOf[Origin.ChannelRelayedHot])
    val o = fwd.message.origin.asInstanceOf[Origin.ChannelRelayedHot]
    assert(o.amountOut === outAmount)
    fwd
  }

  test("relay htlc-add") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
  }

  test("relay an htlc-add with onion tlv payload") { f =>
    import f._
    import fr.acinq.eclair.wire.OnionTlv._

    val payload = ChannelRelayTlvPayload(TlvStream[OnionTlv](AmountToForward(outgoingAmount), OutgoingCltv(outgoingExpiry), OutgoingChannelId(shortId1)))
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
  }

  test("relay an htlc-add with retries") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)

    // we tell the relayer about the first channel
    val u1 = createChannelUpdate(shortId1)
    channelRelayer ! WrappedLocalChannelUpdate(u1)

    // this is another channel, with less balance (it will be preferred)
    val u2 = createChannelUpdate(shortId2, 8000000 msat)
    channelRelayer ! WrappedLocalChannelUpdate(u2)

    channelRelayer ! WrappedChannelRelayPacket(r)

    // first try
    val fwd1 = expectFwdAdd(register, shortId2, outgoingAmount, outgoingExpiry)
    // channel returns an error
    fwd1.message.replyTo ! RES_ADD_FAILED(fwd1.message, HtlcValueTooHighInFlight(channelIds(shortId2), UInt64(1000000000L), 1516977616L msat), Some(u2.channelUpdate))

    // second try
    val fwd2 = expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
    // failure again
    fwd1.message.replyTo ! RES_ADD_FAILED(fwd2.message, HtlcValueTooHighInFlight(channelIds(shortId1), UInt64(1000000000L), 1516977616L msat), Some(u1.channelUpdate))

    // the relayer should give up
    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(TemporaryNodeFailure), commit = true))
  }

  test("fail to relay an htlc-add when we have no channel_update for the next channel") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)

    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
  }

  test("fail to relay an htlc-add when register returns an error") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    val fwd = expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
    fwd.replyTo ! Register.ForwardShortIdFailure(fwd)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
  }

  test("fail to relay an htlc-add when the channel is advertised as unusable (down)") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)
    val d = LocalChannelDown(null, channelId = channelIds(shortId1), shortId1, outgoingNodeId)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedLocalChannelDown(d)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer), commit = true))
  }

  test("fail to relay an htlc-add (channel disabled)") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1, enabled = false)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(ChannelDisabled(u.channelUpdate.messageFlags, u.channelUpdate.channelFlags, u.channelUpdate)), commit = true))
  }

  test("fail to relay an htlc-add (amount below minimum)") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1, htlcMinimum = outgoingAmount + 1.msat)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(AmountBelowMinimum(outgoingAmount, u.channelUpdate)), commit = true))
  }

  test("fail to relay an htlc-add (expiry too small)") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry - CltvExpiryDelta(1))
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(payload.outgoingCltv, u.channelUpdate)), commit = true))
  }

  test("fail to relay an htlc-add (expiry too large)") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry + CltvExpiryDelta(1))
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(payload.outgoingCltv, u.channelUpdate)), commit = true))
  }

  test("fail to relay an htlc-add (fee insufficient)") { f =>
    import f._

    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(outgoingAmount + 1.msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)

    channelRelayer ! WrappedLocalChannelUpdate(u)
    channelRelayer ! WrappedChannelRelayPacket(r)

    expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(FeeInsufficient(r.add.amountMsat, u.channelUpdate)), commit = true))
  }

  test("fail to relay an htlc-add (local error)") { f =>
    import f._

    val channelId1 = channelIds(shortId1)
    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)
    val u_disabled = createChannelUpdate(shortId1, enabled = false)

    case class TestCase(exc: ChannelException, update: ChannelUpdate, failure: FailureMessage)

    val testCases = Seq(
      TestCase(ExpiryTooSmall(channelId1, CltvExpiry(100), CltvExpiry(0), 0), u.channelUpdate, ExpiryTooSoon(u.channelUpdate)),
      TestCase(ExpiryTooBig(channelId1, CltvExpiry(100), CltvExpiry(200), 0), u.channelUpdate, ExpiryTooFar),
      TestCase(InsufficientFunds(channelId1, payload.amountToForward, 100 sat, 0 sat, 0 sat), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
      TestCase(FeerateTooDifferent(channelId1, FeeratePerKw(1000 sat), FeeratePerKw(300 sat)), u.channelUpdate, TemporaryChannelFailure(u.channelUpdate)),
      TestCase(ChannelUnavailable(channelId1), u_disabled.channelUpdate, ChannelDisabled(u_disabled.channelUpdate.messageFlags, u_disabled.channelUpdate.channelFlags, u_disabled.channelUpdate))
    )

    testCases.foreach { testCase =>
      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! WrappedChannelRelayPacket(r)
      val fwd = expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
      fwd.message.replyTo ! RES_ADD_FAILED(fwd.message, testCase.exc, Some(testCase.update))
      expectFwdFail(register, r.add.channelId, CMD_FAIL_HTLC(r.add.id, Right(testCase.failure), commit = true))
    }
  }

  test("settlement failure") { f =>
    import f._

    val channelId1 = channelIds(shortId1)
    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)
    val u_disabled = createChannelUpdate(shortId1, enabled = false)
    val downstream_htlc = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket)

    case class TestCase(result: HtlcResult, cmd: Command with HasHtlcId)

    val testCases = Seq(
      TestCase(HtlcResult.RemoteFail(UpdateFailHtlc(channelId1, downstream_htlc.id, hex"deadbeef")), CMD_FAIL_HTLC(r.add.id, Left(hex"deadbeef"), commit = true)),
      TestCase(HtlcResult.RemoteFailMalformed(UpdateFailMalformedHtlc(channelId1, downstream_htlc.id, ByteVector32.One, FailureMessageCodecs.BADONION)), CMD_FAIL_MALFORMED_HTLC(r.add.id, ByteVector32.One, FailureMessageCodecs.BADONION, commit = true)),
      TestCase(HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId1, downstream_htlc)), CMD_FAIL_HTLC(r.add.id, Right(PermanentChannelFailure), commit = true)),
      TestCase(HtlcResult.Disconnected(u_disabled.channelUpdate), CMD_FAIL_HTLC(r.add.id, Right(TemporaryChannelFailure(u_disabled.channelUpdate)), commit = true))
    )

    testCases.foreach { testCase =>
      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! WrappedChannelRelayPacket(r)
      val fwd = expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
      fwd.message.replyTo ! RES_SUCCESS(fwd.message, channelId1)
      fwd.message.origin.replyTo ! RES_ADD_SETTLED(fwd.message.origin, downstream_htlc, testCase.result)
      expectFwdFail(register, r.add.channelId, testCase.cmd)
    }
  }

  test("settlement success") { f =>
    import f._
    val eventListener = TestProbe[ChannelPaymentRelayed]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)

    val channelId1 = channelIds(shortId1)
    val payload = RelayLegacyPayload(shortId1, outgoingAmount, outgoingExpiry)
    val r = createValidIncomingPacket(1100000 msat, CltvExpiry(400100), payload)
    val u = createChannelUpdate(shortId1)
    val downstream_htlc = UpdateAddHtlc(channelId1, 7, outgoingAmount, paymentHash, outgoingExpiry, emptyOnionPacket)

    case class TestCase(result: HtlcResult)

    val testCases = Seq(
      TestCase(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(channelId1, downstream_htlc.id, paymentPreimage)))
    )

    testCases.foreach { testCase =>
      channelRelayer ! WrappedLocalChannelUpdate(u)
      channelRelayer ! WrappedChannelRelayPacket(r)

      val fwd1 = expectFwdAdd(register, shortId1, outgoingAmount, outgoingExpiry)
      fwd1.message.replyTo ! RES_SUCCESS(fwd1.message, channelId1)
      fwd1.message.origin.replyTo ! RES_ADD_SETTLED(fwd1.message.origin, downstream_htlc, testCase.result)

      val fwd2 = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd2.channelId === r.add.channelId)
      assert(fwd2.message.id === r.add.id)
      assert(fwd2.message.r === paymentPreimage)

      val paymentRelayed = eventListener.expectMessageType[ChannelPaymentRelayed]
      assert(paymentRelayed.copy(timestamp = 0) === ChannelPaymentRelayed(r.add.amountMsat, r.payload.amountToForward, r.add.paymentHash, r.add.channelId, channelId1, timestamp = 0))
    }
  }

  test("get outgoing channels") { f =>
    import PaymentPacketSpec._
    import f._
    val channelId_ab = randomBytes32
    val channelId_bc = randomBytes32
    val a = PaymentPacketSpec.a

    val sender = TestProbe[OutgoingChannels]()

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, channelUpdate_ab.shortChannelId, a, None, channelUpdate_ab, makeCommitments(channelId_ab, -2000 msat, 300000 msat)))
    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc, 400000 msat, -5000 msat)))

    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels1) = sender.expectMessageType[OutgoingChannels]
    assert(channels1.size === 2)
    assert(channels1.head.channelUpdate === channelUpdate_ab)
    assert(channels1.head.toUsableBalance === UsableBalance(a, channelUpdate_ab.shortChannelId, 0 msat, 300000 msat, isPublic = false))
    assert(channels1.last.channelUpdate === channelUpdate_bc)
    assert(channels1.last.toUsableBalance === UsableBalance(c, channelUpdate_bc.shortChannelId, 400000 msat, 0 msat, isPublic = false))

    channelRelayer ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(null, channelId_bc, channelUpdate_bc.shortChannelId, makeCommitments(channelId_bc, 200000 msat, 500000 msat)))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels2) = sender.expectMessageType[OutgoingChannels]
    assert(channels2.last.commitments.availableBalanceForReceive === 500000.msat && channels2.last.commitments.availableBalanceForSend === 200000.msat)

    channelRelayer ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(null, channelId_ab, channelUpdate_ab.shortChannelId, makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    channelRelayer ! WrappedLocalChannelDown(LocalChannelDown(null, channelId_bc, channelUpdate_bc.shortChannelId, c))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels3) = sender.expectMessageType[OutgoingChannels]
    assert(channels3.size === 1 && channels3.head.commitments.availableBalanceForSend === 100000.msat)

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, channelUpdate_ab.shortChannelId, a, None, channelUpdate_ab.copy(channelFlags = 2), makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels4) = sender.expectMessageType[OutgoingChannels]
    assert(channels4.isEmpty)
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels(enabledOnly = false))
    val OutgoingChannels(channels5) = sender.expectMessageType[OutgoingChannels]
    assert(channels5.size === 1)

    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, channelUpdate_ab.shortChannelId, a, None, channelUpdate_ab, makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels6) = sender.expectMessageType[OutgoingChannels]
    assert(channels6.size === 1)

    // We should ignore faulty events that don't really change the shortChannelId:
    channelRelayer ! WrappedShortChannelIdAssigned(ShortChannelIdAssigned(null, channelId_ab, channelUpdate_ab.shortChannelId, Some(channelUpdate_ab.shortChannelId)))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels7) = sender.expectMessageType[OutgoingChannels]
    assert(channels7.size === 1)
    assert(channels7.head.channelUpdate.shortChannelId === channelUpdate_ab.shortChannelId)

    // Simulate a chain re-org that changes the shortChannelId:
    channelRelayer ! WrappedShortChannelIdAssigned(ShortChannelIdAssigned(null, channelId_ab, ShortChannelId(42), Some(channelUpdate_ab.shortChannelId)))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    sender.expectMessage(OutgoingChannels(Nil))

    // We should receive the updated channel update containing the new shortChannelId:
    channelRelayer ! WrappedLocalChannelUpdate(LocalChannelUpdate(null, channelId_ab, ShortChannelId(42), a, None, channelUpdate_ab.copy(shortChannelId = ShortChannelId(42)), makeCommitments(channelId_ab, 100000 msat, 200000 msat)))
    channelRelayer ! WrappedGetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    val OutgoingChannels(channels8) = sender.expectMessageType[OutgoingChannels]
    assert(channels8.size === 1)
    assert(channels8.head.channelUpdate.shortChannelId === ShortChannelId(42))
  }
}

object ChannelRelayerSpec {

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  val outgoingAmount = 1000000 msat
  val outgoingExpiry = CltvExpiry(400000)
  val outgoingNodeId = randomKey.publicKey

  val shortId1 = ShortChannelId(111111)
  val shortId2 = ShortChannelId(222222)

  val channelIds = Map(
    shortId1 -> randomBytes32,
    shortId2 -> randomBytes32
  )

  def createValidIncomingPacket(amountIn: MilliSatoshi, expiryIn: CltvExpiry, payload: ChannelRelayPayload): IncomingPacket.ChannelRelayPacket = {
    val add_ab = UpdateAddHtlc(channelId = randomBytes32, id = 123456, amountIn, paymentHash, expiryIn, emptyOnionPacket)
    ChannelRelayPacket(add_ab, payload, emptyOnionPacket)
  }

  def createChannelUpdate(shortChannelId: ShortChannelId, balance: MilliSatoshi = 10000000 msat, enabled: Boolean = true, htlcMinimum: MilliSatoshi = 0 msat): LocalChannelUpdate = {
    val channelId = channelIds(shortChannelId)
    val update = ChannelUpdate(ByteVector64.Zeroes, Block.RegtestGenesisBlock.hash, shortChannelId, 0, 1, Announcements.makeChannelFlags(isNode1 = true, enabled), CltvExpiryDelta(100), htlcMinimum, 1000 msat, 100, Some(500000000 msat))
    val commitments = PaymentPacketSpec.makeCommitments(channelId, testAvailableBalanceForSend = balance)
    LocalChannelUpdate(null, channelId, shortChannelId, outgoingNodeId, None, update, commitments)
  }
}