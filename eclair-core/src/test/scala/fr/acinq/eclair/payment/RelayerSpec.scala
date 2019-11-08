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

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{ActorRef, Status}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.payment.Origin.Relayed
import fr.acinq.eclair.payment.Relayer._
import fr.acinq.eclair.payment.send.PaymentLifecycle.{buildCommand, buildOnion}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.Onion.{FinalLegacyPayload, FinalTlvPayload, PerHopPayload, RelayTlvPayload}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, NodeParams, ShortChannelId, TestConstants, TestkitBaseClass, UInt64, nodeFee, randomBytes32, randomKey}
import org.scalatest.Outcome
import scodec.Attempt
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class RelayerSpec extends TestkitBaseClass {

  import HtlcGenerationSpec._
  import RelayerSpec._

  case class FixtureParam(nodeParams: NodeParams, relayer: ActorRef, register: TestProbe, paymentHandler: TestProbe, sender: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = TestConstants.Bob.nodeParams
      val register = TestProbe()
      val paymentHandler = TestProbe()
      // we are node B in the route A -> B -> C -> ....
      val relayer = system.actorOf(Relayer.props(nodeParams, register.ref, paymentHandler.ref))
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, relayer, register, paymentHandler, TestProbe())))
    }
  }

  val channelId_ab = randomBytes32
  val channelId_bc = randomBytes32

  test("relay an htlc-add") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.amount === amount_bc)
    assert(fwd.message.cltvExpiry === expiry_bc)
    assert(fwd.message.upstream === Upstream.Relayed(add_ab))

    sender.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("relay an htlc-add with onion tlv payload") { f =>
    import f._
    import fr.acinq.eclair.wire.OnionTlv._

    // Use tlv payloads for all hops (final and intermediate)
    val finalPayload: Seq[PerHopPayload] = FinalTlvPayload(TlvStream[OnionTlv](AmountToForward(finalAmountMsat), OutgoingCltv(finalExpiry))) :: Nil
    val (firstAmountMsat, firstExpiry, payloads) = hops.drop(1).reverse.foldLeft((finalAmountMsat, finalExpiry, finalPayload)) {
      case ((amountMsat, expiry, currentPayloads), hop) =>
        val nextFee = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, amountMsat)
        val payload = RelayTlvPayload(TlvStream[OnionTlv](AmountToForward(amountMsat), OutgoingCltv(expiry), OutgoingChannelId(hop.lastUpdate.shortChannelId)))
        (amountMsat + nextFee, expiry + hop.lastUpdate.cltvExpiryDelta, payload +: currentPayloads)
    }
    val Sphinx.PacketAndSecrets(onion, _) = buildOnion(hops.map(_.nextNodeId), payloads, paymentHash)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, firstAmountMsat, paymentHash, firstExpiry, onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.amount === amount_bc)
    assert(fwd.message.cltvExpiry === expiry_bc)
    assert(fwd.message.upstream === Upstream.Relayed(add_ab))

    sender.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("relay an htlc-add with retries") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)

    // we tell the relayer about channel B-C
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    // this is another channel B-C, with less balance (it will be preferred)
    val (channelId_bc_1, channelUpdate_bc_1) = (randomBytes32, channelUpdate_bc.copy(shortChannelId = ShortChannelId("500000x1x1")))
    relayer ! LocalChannelUpdate(null, channelId_bc_1, channelUpdate_bc_1.shortChannelId, c, None, channelUpdate_bc_1, makeCommitments(channelId_bc_1, 49000000 msat))

    sender.send(relayer, ForwardAdd(add_ab))

    // first try
    val fwd1 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd1.shortChannelId === channelUpdate_bc_1.shortChannelId)
    assert(fwd1.message.upstream === Upstream.Relayed(add_ab))

    // channel returns an error
    val origin = Relayed(channelId_ab, originHtlcId = 42, amountIn = 1100000 msat, amountOut = 1000000 msat)
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc_1, paymentHash, HtlcValueTooHighInFlight(channelId_bc_1, UInt64(1000000000L), 1516977616L msat), origin, Some(channelUpdate_bc_1), originalCommand = Some(fwd1.message))))

    // second try
    val fwd2 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd2.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd2.message.upstream === Upstream.Relayed(add_ab))

    // failure again
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcValueTooHighInFlight(channelId_bc, UInt64(1000000000L), 1516977616L msat), origin, Some(channelUpdate_bc), originalCommand = Some(fwd2.message))))

    // the relayer should give up
    val fwdFail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwdFail.channelId === add_ab.channelId)
    assert(fwdFail.message.id === add_ab.id)
    assert(fwdFail.message.reason === Right(TemporaryNodeFailure))

    sender.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("relay an htlc-add at the final node to the payment handler") { f =>
    import f._

    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops.take(1), FinalLegacyPayload(finalAmountMsat, finalExpiry))
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    sender.send(relayer, ForwardAdd(add_ab))

    val fp = paymentHandler.expectMsgType[FinalPayload]
    assert(fp.add === add_ab)
    assert(fp.payload === FinalLegacyPayload(finalAmountMsat, finalExpiry))

    sender.expectNoMsg(50 millis)
    register.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when we have no channel_update for the next channel") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)

    sender.send(relayer, ForwardAdd(add_ab))

    val fwdFail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwdFail.channelId === add_ab.channelId)
    assert(fwdFail.message.id === add_ab.id)
    assert(fwdFail.message.reason === Right(UnknownNextPeer))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when register returns an error") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd1 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd1.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd1.message.upstream === Upstream.Relayed(add_ab))

    sender.send(relayer, Status.Failure(Register.ForwardShortIdFailure(fwd1)))

    val fwd2 = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd2.channelId === channelId_ab)
    assert(fwd2.message.id === add_ab.id)
    assert(fwd2.message.reason === Right(UnknownNextPeer))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when the channel is advertised as unusable (down)") { f =>
    import f._

    // check that payments are sent properly
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream === Upstream.Relayed(add_ab))

    sender.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)

    // now tell the relayer that the channel is down and try again
    relayer ! LocalChannelDown(sender.ref, channelId = channelId_bc, shortChannelId = channelUpdate_bc.shortChannelId, remoteNodeId = TestConstants.Bob.nodeParams.nodeId)

    val (cmd1, _) = buildCommand(UUID.randomUUID(), randomBytes32, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    val add_ab1 = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd1.amount, cmd1.paymentHash, cmd1.cltvExpiry, cmd1.onion)
    sender.send(relayer, ForwardAdd(add_ab1))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab1.id)
    assert(fail.reason === Right(UnknownNextPeer))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when the requested channel is disabled") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val channelUpdate_bc_disabled = channelUpdate_bc.copy(channelFlags = Announcements.makeChannelFlags(Announcements.isNode1(channelUpdate_bc.channelFlags), enable = false))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc_disabled, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(ChannelDisabled(channelUpdate_bc_disabled.messageFlags, channelUpdate_bc_disabled.channelFlags, channelUpdate_bc_disabled)))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when the onion is malformed") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc with an invalid onion (hmac)
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion.copy(hmac = cmd.onion.hmac.reverse))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_MALFORMED_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.onionHash == Sphinx.PaymentPacket.hash(add_ab.onionRoutingPacket))
    assert(fail.failureCode === (FailureMessageCodecs.BADONION | FailureMessageCodecs.PERM | 5))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when the onion payload is missing data") { f =>
    import f._
    import fr.acinq.eclair.wire.OnionTlv._

    // B is not the last hop and receives an onion missing some routing information.
    val invalidPayloads_bc = Seq(
      (InvalidOnionPayload(UInt64(2), 0), TlvStream[OnionTlv](OutgoingChannelId(channelUpdate_bc.shortChannelId), OutgoingCltv(expiry_bc))), // Missing forwarding amount.
      (InvalidOnionPayload(UInt64(4), 0), TlvStream[OnionTlv](OutgoingChannelId(channelUpdate_bc.shortChannelId), AmountToForward(amount_bc))), // Missing cltv expiry.
      (InvalidOnionPayload(UInt64(6), 0), TlvStream[OnionTlv](AmountToForward(amount_bc), OutgoingCltv(expiry_bc)))) // Missing channel id.
    val payload_cd = TlvStream[OnionTlv](OutgoingChannelId(channelUpdate_cd.shortChannelId), AmountToForward(amount_cd), OutgoingCltv(expiry_cd))

    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    for ((expectedErr, invalidPayload_bc) <- invalidPayloads_bc) {
      val onion = buildTlvOnion(Seq(b, c), Seq(invalidPayload_bc, payload_cd), paymentHash)
      val add_ab = UpdateAddHtlc(channelId_ab, 123456, amount_ab, paymentHash, expiry_ab, onion)
      sender.send(relayer, ForwardAdd(add_ab))

      register.expectMsg(Register.Forward(channelId_ab, CMD_FAIL_HTLC(add_ab.id, Right(expectedErr), commit = true)))
      register.expectNoMsg(50 millis)
      paymentHandler.expectNoMsg(50 millis)
    }
  }

  test("fail to relay an htlc-add when variable length onion is disabled") { f =>
    import f._
    import fr.acinq.eclair.wire.OnionTlv._

    val relayer = system.actorOf(Relayer.props(TestConstants.Bob.nodeParams.copy(globalFeatures = ByteVector.empty), register.ref, paymentHandler.ref))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    val payload_bc = TlvStream[OnionTlv](OutgoingChannelId(channelUpdate_bc.shortChannelId), AmountToForward(amount_bc), OutgoingCltv(expiry_bc))
    val payload_cd = TlvStream[OnionTlv](OutgoingChannelId(channelUpdate_cd.shortChannelId), AmountToForward(amount_cd), OutgoingCltv(expiry_cd))

    val onion = buildTlvOnion(Seq(b, c), Seq(payload_bc, payload_cd), paymentHash)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, amount_ab, paymentHash, expiry_ab, onion)
    sender.send(relayer, ForwardAdd(add_ab))

    register.expectMsg(Register.Forward(channelId_ab, CMD_FAIL_HTLC(add_ab.id, Right(InvalidRealm), commit = true)))
    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when amount is below the next hop's requirements") { f =>
    import f._

    // we use this to build a valid onion
    val finalPayload = FinalLegacyPayload(channelUpdate_bc.htlcMinimumMsat - (1 msat), finalExpiry)
    val zeroFeeHops = hops.map(hop => hop.copy(lastUpdate = hop.lastUpdate.copy(feeBaseMsat = 0 msat, feeProportionalMillionths = 0)))
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, zeroFeeHops, finalPayload)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(AmountBelowMinimum(cmd.amount, channelUpdate_bc)))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when expiry does not match next hop's requirements") { f =>
    import f._

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(cltvExpiryDelta = CltvExpiryDelta(0))))
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops1, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(IncorrectCltvExpiry(cmd.cltvExpiry, channelUpdate_bc)))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail to relay an htlc-add when relay fee isn't sufficient") { f =>
    import f._

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(feeBaseMsat = hops(1).lastUpdate.feeBaseMsat / 2)))
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops1, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FeeInsufficient(add_ab.amountMsat, channelUpdate_bc)))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail an htlc-add at the final node when amount has been modified by second-to-last node") { f =>
    import f._

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops1, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount - (1 msat), cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectHtlcAmount(add_ab.amountMsat)))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail an htlc-add at the final node when expiry has been modified by second-to-last node") { f =>
    import f._

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(UUID.randomUUID(), paymentHash, hops1, FinalLegacyPayload(finalAmountMsat, finalExpiry))
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry - CltvExpiryDelta(1), cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectCltvExpiry(add_ab.cltvExpiry)))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("fail an htlc-add at the final node when the onion payload is missing data") { f =>
    import f._
    import fr.acinq.eclair.wire.OnionTlv._

    // B is the last hop and receives an onion missing some payment information.
    val invalidFinalPayloads = Seq(
      (InvalidOnionPayload(UInt64(2), 0), TlvStream[OnionTlv](OutgoingCltv(expiry_bc))), // Missing forwarding amount.
      (InvalidOnionPayload(UInt64(4), 0), TlvStream[OnionTlv](AmountToForward(amount_bc)))) // Missing cltv expiry.

    for ((expectedErr, invalidFinalPayload) <- invalidFinalPayloads) {
      val onion = buildTlvOnion(Seq(b), Seq(invalidFinalPayload), paymentHash)
      val add_ab = UpdateAddHtlc(channelId_ab, 123456, amount_ab, paymentHash, expiry_ab, onion)
      sender.send(relayer, ForwardAdd(add_ab))

      register.expectMsg(Register.Forward(channelId_ab, CMD_FAIL_HTLC(add_ab.id, Right(expectedErr), commit = true)))
      register.expectNoMsg(50 millis)
      paymentHandler.expectNoMsg(50 millis)
    }
  }

  test("correctly translates errors returned by channel when attempting to add an htlc") { f =>
    import f._

    val paymentHash = randomBytes32
    val origin = Relayed(channelId_ab, originHtlcId = 42, amountIn = 1100000 msat, amountOut = 1000000 msat)

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ExpiryTooSmall(channelId_bc, CltvExpiry(100), CltvExpiry(0), 0), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ExpiryTooSoon(channelUpdate_bc)))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ExpiryTooBig(channelId_bc, CltvExpiry(100), CltvExpiry(200), 0), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ExpiryTooFar))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, InsufficientFunds(channelId_bc, origin.amountOut, 100 sat, 0 sat, 0 sat), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(TemporaryChannelFailure(channelUpdate_bc)))

    val channelUpdate_bc_disabled = channelUpdate_bc.copy(channelFlags = 2)
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ChannelUnavailable(channelId_bc), origin, Some(channelUpdate_bc_disabled), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ChannelDisabled(channelUpdate_bc_disabled.messageFlags, channelUpdate_bc_disabled.channelFlags, channelUpdate_bc_disabled)))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcTimedout(channelId_bc, Set.empty), origin, None, None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(PermanentChannelFailure))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcTimedout(channelId_bc, Set.empty), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(PermanentChannelFailure))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  test("relay an htlc-fulfill") { f =>
    import f._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fulfill_ba = UpdateFulfillHtlc(channelId = channelId_bc, id = 42, paymentPreimage = ByteVector32.Zeroes)
    val origin = Relayed(channelId_ab, 150, 11000000 msat, 10000000 msat)
    sender.send(relayer, ForwardFulfill(fulfill_ba, origin, add_bc))

    val fwd = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)

    val paymentRelayed = eventListener.expectMsgType[PaymentRelayed]
    assert(paymentRelayed.copy(timestamp = 0) === PaymentRelayed(origin.amountIn, origin.amountOut, add_bc.paymentHash, channelId_ab, channelId_bc, timestamp = 0))
  }

  test("handle an htlc-fulfill after restart") { f =>
    import f._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    val parentId = UUID.randomUUID()
    val (id1, id2, id3) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    val (preimage1, preimage2) = (randomBytes32, randomBytes32)
    val (paymentHash1, paymentHash2) = (Crypto.sha256(preimage1), Crypto.sha256(preimage2))

    val add1 = UpdateAddHtlc(channelId_bc, 72, 561 msat, paymentHash1, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fulfill1 = UpdateFulfillHtlc(channelId_bc, 72, preimage1)
    val origin1 = Origin.Local(id1, None)
    val add2 = UpdateAddHtlc(channelId_bc, 75, 1105 msat, paymentHash1, CltvExpiry(4250), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fulfill2 = UpdateFulfillHtlc(channelId_bc, 75, preimage1)
    val origin2 = Origin.Local(id2, None)
    val add3 = UpdateAddHtlc(channelId_bc, 78, 1729 msat, paymentHash2, CltvExpiry(4300), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fulfill3 = UpdateFulfillHtlc(channelId_bc, 78, preimage2)
    val origin3 = Origin.Local(id3, None)

    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id1, parentId, None, paymentHash1, add1.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id2, parentId, None, paymentHash1, add2.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id3, id3, None, paymentHash2, add3.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))

    sender.send(relayer, ForwardFulfill(fulfill1, origin1, add1))
    eventListener.expectNoMsg(100 millis)
    assert(nodeParams.db.payments.getOutgoingPayment(id2).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, ForwardFulfill(fulfill2, origin2, add2))
    val e1 = eventListener.expectMsgType[PaymentSent]
    assert(e1.id === parentId)
    assert(e1.paymentPreimage === preimage1)
    assert(e1.paymentHash === paymentHash1)
    assert(e1.parts.length === 2)
    assert(e1.amount === 1666.msat)
    assert(nodeParams.db.payments.getOutgoingPayment(id1).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(nodeParams.db.payments.getOutgoingPayment(id2).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(nodeParams.db.payments.getOutgoingPayment(id3).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, ForwardFulfill(fulfill3, origin3, add3))
    val e2 = eventListener.expectMsgType[PaymentSent]
    assert(e2.id === id3)
    assert(e2.paymentPreimage === preimage2)
    assert(e2.paymentHash === paymentHash2)
    assert(e2.parts.length === 1)
    assert(e2.amount === 1729.msat)
    assert(nodeParams.db.payments.getOutgoingPayment(id3).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
  }

  test("relay an htlc-fail") { f =>
    import f._

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fail_ba = UpdateFailHtlc(channelId = channelId_bc, id = 42, reason = Sphinx.FailurePacket.create(ByteVector32(ByteVector.fill(32)(1)), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Relayed(channelId_ab, 150, 11000000 msat, 10000000 msat)
    sender.send(relayer, ForwardFail(fail_ba, origin, add_bc))

    val fwd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)
  }

  test("handle an htlc-fail after restart") { f =>
    import f._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    val parentId = UUID.randomUUID()
    val (id1, id2, id3) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    val (paymentHash1, paymentHash2) = (randomBytes32, randomBytes32)

    val add1 = UpdateAddHtlc(channelId_bc, 72, 561 msat, paymentHash1, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fail1 = UpdateFailHtlc(channelId_bc, 72, ByteVector.empty)
    val origin1 = Origin.Local(id1, None)
    val add2 = UpdateAddHtlc(channelId_bc, 75, 1105 msat, paymentHash1, CltvExpiry(4250), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fail2 = UpdateFailHtlc(channelId_bc, 75, ByteVector.empty)
    val origin2 = Origin.Local(id2, None)
    val add3 = UpdateAddHtlc(channelId_bc, 78, 1729 msat, paymentHash2, CltvExpiry(4300), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fail3 = UpdateFailHtlc(channelId_bc, 78, ByteVector.empty)
    val origin3 = Origin.Local(id3, None)

    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id1, parentId, None, paymentHash1, add1.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id2, parentId, None, paymentHash1, add2.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id3, id3, None, paymentHash2, add3.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))

    sender.send(relayer, ForwardFail(fail1, origin1, add1))
    eventListener.expectNoMsg(100 millis)
    assert(nodeParams.db.payments.getOutgoingPayment(id2).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, ForwardFail(fail2, origin2, add2))
    val e1 = eventListener.expectMsgType[PaymentFailed]
    assert(e1.id === parentId)
    assert(e1.paymentHash === paymentHash1)
    assert(nodeParams.db.payments.getOutgoingPayment(id1).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(nodeParams.db.payments.getOutgoingPayment(id2).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(nodeParams.db.payments.getOutgoingPayment(id3).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, ForwardFail(fail3, origin3, add3))
    val e2 = eventListener.expectMsgType[PaymentFailed]
    assert(e2.id === id3)
    assert(e2.paymentHash === paymentHash2)
    assert(nodeParams.db.payments.getOutgoingPayment(id3).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
  }

  test("get outgoing channels") { f =>
    import f._

    relayer ! LocalChannelUpdate(null, channelId_ab, channelUpdate_ab.shortChannelId, a, None, channelUpdate_ab, makeCommitments(channelId_ab, -2000 msat, 300000 msat))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc, 400000 msat, -5000 msat))
    sender.send(relayer, GetOutgoingChannels())
    val OutgoingChannels(channels1) = sender.expectMsgType[OutgoingChannels]
    assert(channels1.size === 2)
    assert(channels1.head.channelUpdate === channelUpdate_ab)
    assert(channels1.head.toUsableBalance === UsableBalance(a, channelUpdate_ab.shortChannelId, 0 msat, 300000 msat, isPublic = false))
    assert(channels1.last.channelUpdate === channelUpdate_bc)
    assert(channels1.last.toUsableBalance === UsableBalance(c, channelUpdate_bc.shortChannelId, 400000 msat, 0 msat, isPublic = false))

    relayer ! AvailableBalanceChanged(null, channelId_bc, channelUpdate_bc.shortChannelId, 0 msat, makeCommitments(channelId_bc, 200000 msat, 500000 msat))
    sender.send(relayer, GetOutgoingChannels())
    val OutgoingChannels(channels2) = sender.expectMsgType[OutgoingChannels]
    assert(channels2.last.commitments.availableBalanceForReceive === 500000.msat && channels2.last.commitments.availableBalanceForSend === 200000.msat)

    relayer ! AvailableBalanceChanged(null, channelId_ab, channelUpdate_ab.shortChannelId, 0 msat, makeCommitments(channelId_ab, 100000 msat, 200000 msat))
    relayer ! LocalChannelDown(null, channelId_bc, channelUpdate_bc.shortChannelId, c)
    sender.send(relayer, GetOutgoingChannels())
    val OutgoingChannels(channels3) = sender.expectMsgType[OutgoingChannels]
    assert(channels3.size === 1 && channels3.head.commitments.availableBalanceForSend === 100000.msat)

    relayer ! LocalChannelUpdate(null, channelId_ab, channelUpdate_ab.shortChannelId, a, None, channelUpdate_ab.copy(channelFlags = 2), makeCommitments(channelId_ab, 100000 msat, 200000 msat))
    sender.send(relayer, GetOutgoingChannels())
    val OutgoingChannels(channels4) = sender.expectMsgType[OutgoingChannels]
    assert(channels4.isEmpty)
    sender.send(relayer, GetOutgoingChannels(enabledOnly = false))
    val OutgoingChannels(channels5) = sender.expectMsgType[OutgoingChannels]
    assert(channels5.size === 1)

    relayer ! LocalChannelUpdate(null, channelId_ab, channelUpdate_ab.shortChannelId, a, None, channelUpdate_ab, makeCommitments(channelId_ab, 100000 msat, 200000 msat))
    sender.send(relayer, GetOutgoingChannels())
    val OutgoingChannels(channels6) = sender.expectMsgType[OutgoingChannels]
    assert(channels6.size === 1)
  }

}

object RelayerSpec {

  /** Build onion from arbitrary tlv stream (potentially invalid). */
  def buildTlvOnion(nodes: Seq[PublicKey], payloads: Seq[TlvStream[OnionTlv]], associatedData: ByteVector32): OnionRoutingPacket = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey
    val payloadsBin: Seq[ByteVector] = payloads
      .map(OnionCodecs.tlvPerHopPayloadCodec.encode)
      .map {
        case Attempt.Successful(bitVector) => bitVector.toByteVector
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      }
    Sphinx.PaymentPacket.create(sessionKey, nodes, payloadsBin, associatedData).packet
  }

}