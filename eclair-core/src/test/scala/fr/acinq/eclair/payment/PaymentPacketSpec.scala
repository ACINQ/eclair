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

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, DeterministicWallet}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel.{Channel, ChannelVersion, Commitments}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.IncomingPacket.{ChannelRelayPacket, FinalPacket, NodeRelayPacket, decrypt}
import fr.acinq.eclair.payment.OutgoingPacket._
import fr.acinq.eclair.payment.PaymentRequest.PaymentRequestFeatures
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.router.Router.{ChannelHop, NodeHop}
import fr.acinq.eclair.wire.Onion.{FinalLegacyPayload, FinalTlvPayload, RelayLegacyPayload}
import fr.acinq.eclair.wire.OnionTlv.{AmountToForward, OutgoingCltv, PaymentData}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{ActivatedFeature, CltvExpiry, CltvExpiryDelta, Features, LongToBtcAmount, MilliSatoshi, ShortChannelId, TestConstants, UInt64, nodeFee, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scodec.Attempt
import scodec.bits.{ByteVector, HexStringSyntax}

/**
 * Created by PM on 31/05/2016.
 */

class PaymentPacketSpec extends AnyFunSuite with BeforeAndAfterAll {

  import PaymentPacketSpec._

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  test("compute fees") {
    val feeBaseMsat = 150000 msat
    val feeProportionalMillionth = 4L
    val htlcAmountMsat = 42000000 msat
    // spec: fee-base-msat + htlc-amount-msat * fee-proportional-millionths / 1000000
    val ref = feeBaseMsat + htlcAmountMsat * feeProportionalMillionth / 1000000
    val fee = nodeFee(feeBaseMsat, feeProportionalMillionth, htlcAmountMsat)
    assert(ref === fee)
  }

  def testBuildOnion(legacy: Boolean): Unit = {
    val finalPayload = if (legacy) {
      FinalLegacyPayload(finalAmount, finalExpiry)
    } else {
      FinalTlvPayload(TlvStream(AmountToForward(finalAmount), OutgoingCltv(finalExpiry)))
    }
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, hops, finalPayload)
    assert(firstAmount === amount_ab)
    assert(firstExpiry === expiry_ab)
    assert(onion.packet.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    val features = if (legacy) Features.empty else variableLengthOnionFeature
    testPeelOnion(onion.packet, features)
  }

  def testPeelOnion(packet_b: OnionRoutingPacket, features: Features): Unit = {
    val add_b = UpdateAddHtlc(randomBytes32, 0, amount_ab, paymentHash, expiry_ab, packet_b)
    val Right(relay_b@ChannelRelayPacket(add_b2, payload_b, packet_c)) = decrypt(add_b, priv_b.privateKey, features)
    assert(add_b2 === add_b)
    assert(packet_c.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_b.amountToForward === amount_bc)
    assert(payload_b.outgoingCltv === expiry_bc)
    assert(payload_b.outgoingChannelId === channelUpdate_bc.shortChannelId)
    assert(relay_b.relayFeeMsat === fee_b)
    assert(relay_b.expiryDelta === channelUpdate_bc.cltvExpiryDelta)

    val add_c = UpdateAddHtlc(randomBytes32, 1, amount_bc, paymentHash, expiry_bc, packet_c)
    val Right(relay_c@ChannelRelayPacket(add_c2, payload_c, packet_d)) = decrypt(add_c, priv_c.privateKey, features)
    assert(add_c2 === add_c)
    assert(packet_d.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_c.amountToForward === amount_cd)
    assert(payload_c.outgoingCltv === expiry_cd)
    assert(payload_c.outgoingChannelId === channelUpdate_cd.shortChannelId)
    assert(relay_c.relayFeeMsat === fee_c)
    assert(relay_c.expiryDelta === channelUpdate_cd.cltvExpiryDelta)

    val add_d = UpdateAddHtlc(randomBytes32, 2, amount_cd, paymentHash, expiry_cd, packet_d)
    val Right(relay_d@ChannelRelayPacket(add_d2, payload_d, packet_e)) = decrypt(add_d, priv_d.privateKey, features)
    assert(add_d2 === add_d)
    assert(packet_e.payload.length === Sphinx.PaymentPacket.PayloadLength)
    assert(payload_d.amountToForward === amount_de)
    assert(payload_d.outgoingCltv === expiry_de)
    assert(payload_d.outgoingChannelId === channelUpdate_de.shortChannelId)
    assert(relay_d.relayFeeMsat === fee_d)
    assert(relay_d.expiryDelta === channelUpdate_de.cltvExpiryDelta)

    val add_e = UpdateAddHtlc(randomBytes32, 2, amount_de, paymentHash, expiry_de, packet_e)
    val Right(FinalPacket(add_e2, payload_e)) = decrypt(add_e, priv_e.privateKey, features)
    assert(add_e2 === add_e)
    assert(payload_e.amount === finalAmount)
    assert(payload_e.totalAmount === finalAmount)
    assert(payload_e.expiry === finalExpiry)
    assert(payload_e.paymentSecret === None)
  }

  test("build onion with final legacy payload") {
    testBuildOnion(legacy = true)
  }

  test("build onion with final tlv payload") {
    testBuildOnion(legacy = false)
  }

  test("build a command including the onion") {
    val (add, _) = buildCommand(ActorRef.noSender, Upstream.Local(UUID.randomUUID), paymentHash, hops, FinalLegacyPayload(finalAmount, finalExpiry))
    assert(add.amount > finalAmount)
    assert(add.cltvExpiry === finalExpiry + channelUpdate_de.cltvExpiryDelta + channelUpdate_cd.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    testPeelOnion(add.onion, Features.empty)
  }

  test("build a command with no hops") {
    val (add, _) = buildCommand(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, hops.take(1), FinalLegacyPayload(finalAmount, finalExpiry))
    assert(add.amount === finalAmount)
    assert(add.cltvExpiry === finalExpiry)
    assert(add.paymentHash === paymentHash)
    assert(add.onion.payload.length === Sphinx.PaymentPacket.PayloadLength)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32, 0, finalAmount, paymentHash, finalExpiry, add.onion)
    val Right(FinalPacket(add_b2, payload_b)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 === add_b)
    assert(payload_b.amount === finalAmount)
    assert(payload_b.totalAmount === finalAmount)
    assert(payload_b.expiry === finalExpiry)
    assert(payload_b.paymentSecret === None)
  }

  test("build a trampoline payment") {
    // simple trampoline route to e:
    //             .--.   .--.
    //            /    \ /    \
    // a -> b -> c      d      e

    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createMultiPartPayload(finalAmount, finalAmount * 3, finalExpiry, paymentSecret))
    assert(amount_ac === amount_bc)
    assert(expiry_ac === expiry_bc)

    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    assert(firstAmount === amount_ab)
    assert(firstExpiry === expiry_ab)

    val add_b = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet)
    val Right(ChannelRelayPacket(add_b2, payload_b, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 === add_b)
    assert(payload_b === RelayLegacyPayload(channelUpdate_bc.shortChannelId, amount_bc, expiry_bc))

    val add_c = UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc, packet_c)
    val Right(NodeRelayPacket(add_c2, outer_c, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, variableLengthOnionFeature)
    assert(add_c2 === add_c)
    assert(outer_c.amount === amount_bc)
    assert(outer_c.totalAmount === amount_bc)
    assert(outer_c.expiry === expiry_bc)
    assert(inner_c.amountToForward === amount_cd)
    assert(inner_c.outgoingCltv === expiry_cd)
    assert(inner_c.outgoingNodeId === d)
    assert(inner_c.invoiceRoutingInfo === None)
    assert(inner_c.invoiceFeatures === None)
    assert(inner_c.paymentSecret === None)

    // c forwards the trampoline payment to d.
    val (amount_d, expiry_d, onion_d) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(c, d, channelUpdate_cd) :: Nil, Onion.createTrampolinePayload(amount_cd, amount_cd, expiry_cd, randomBytes32, packet_d))
    assert(amount_d === amount_cd)
    assert(expiry_d === expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32, 3, amount_d, paymentHash, expiry_d, onion_d.packet)
    val Right(NodeRelayPacket(add_d2, outer_d, inner_d, packet_e)) = decrypt(add_d, priv_d.privateKey, variableLengthOnionFeature)
    assert(add_d2 === add_d)
    assert(outer_d.amount === amount_cd)
    assert(outer_d.totalAmount === amount_cd)
    assert(outer_d.expiry === expiry_cd)
    assert(inner_d.amountToForward === amount_de)
    assert(inner_d.outgoingCltv === expiry_de)
    assert(inner_d.outgoingNodeId === e)
    assert(inner_d.invoiceRoutingInfo === None)
    assert(inner_d.invoiceFeatures === None)
    assert(inner_d.paymentSecret === None)

    // d forwards the trampoline payment to e.
    val (amount_e, expiry_e, onion_e) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(d, e, channelUpdate_de) :: Nil, Onion.createTrampolinePayload(amount_de, amount_de, expiry_de, randomBytes32, packet_e))
    assert(amount_e === amount_de)
    assert(expiry_e === expiry_de)
    val add_e = UpdateAddHtlc(randomBytes32, 4, amount_e, paymentHash, expiry_e, onion_e.packet)
    val Right(FinalPacket(add_e2, payload_e)) = decrypt(add_e, priv_e.privateKey, variableLengthOnionFeature)
    assert(add_e2 === add_e)
    assert(payload_e === FinalTlvPayload(TlvStream(AmountToForward(finalAmount), OutgoingCltv(finalExpiry), PaymentData(paymentSecret, finalAmount * 3))))
  }

  test("build a trampoline payment with non-trampoline recipient") {
    // simple trampoline route to e where e doesn't support trampoline:
    //             .--.
    //            /    \
    // a -> b -> c      d -> e

    val routingHints = List(List(PaymentRequest.ExtraHop(randomKey.publicKey, ShortChannelId(42), 10 msat, 100, CltvExpiryDelta(144))))
    val invoiceFeatures = PaymentRequestFeatures(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional)
    val invoice = PaymentRequest(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_a.privateKey, "#reckless", CltvExpiryDelta(18), None, None, routingHints, features = Some(invoiceFeatures))
    val (amount_ac, expiry_ac, trampolineOnion) = buildTrampolineToLegacyPacket(invoice, trampolineHops, FinalLegacyPayload(finalAmount, finalExpiry))
    assert(amount_ac === amount_bc)
    assert(expiry_ac === expiry_bc)

    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    assert(firstAmount === amount_ab)
    assert(firstExpiry === expiry_ab)

    val add_b = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet)
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)

    val add_c = UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc, packet_c)
    val Right(NodeRelayPacket(_, outer_c, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, variableLengthOnionFeature)
    assert(outer_c.amount === amount_bc)
    assert(outer_c.totalAmount === amount_bc)
    assert(outer_c.expiry === expiry_bc)
    assert(outer_c.paymentSecret !== invoice.paymentSecret)
    assert(inner_c.amountToForward === amount_cd)
    assert(inner_c.outgoingCltv === expiry_cd)
    assert(inner_c.outgoingNodeId === d)
    assert(inner_c.invoiceRoutingInfo === None)
    assert(inner_c.invoiceFeatures === None)
    assert(inner_c.paymentSecret === None)

    // c forwards the trampoline payment to d.
    val (amount_d, expiry_d, onion_d) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(c, d, channelUpdate_cd) :: Nil, Onion.createTrampolinePayload(amount_cd, amount_cd, expiry_cd, randomBytes32, packet_d))
    assert(amount_d === amount_cd)
    assert(expiry_d === expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32, 3, amount_d, paymentHash, expiry_d, onion_d.packet)
    val Right(NodeRelayPacket(_, outer_d, inner_d, _)) = decrypt(add_d, priv_d.privateKey, variableLengthOnionFeature)
    assert(outer_d.amount === amount_cd)
    assert(outer_d.totalAmount === amount_cd)
    assert(outer_d.expiry === expiry_cd)
    assert(outer_d.paymentSecret !== invoice.paymentSecret)
    assert(inner_d.amountToForward === finalAmount)
    assert(inner_d.outgoingCltv === expiry_de)
    assert(inner_d.outgoingNodeId === e)
    assert(inner_d.totalAmount === finalAmount)
    assert(inner_d.paymentSecret === invoice.paymentSecret)
    assert(inner_d.invoiceFeatures === Some(hex"028200")) // var_onion_optin, payment_secret, basic_mpp
    assert(inner_d.invoiceRoutingInfo === Some(routingHints))
  }

  test("fail to build a trampoline payment when too much invoice data is provided") {
    val routingHintOverflow = List(List.fill(7)(PaymentRequest.ExtraHop(randomKey.publicKey, ShortChannelId(1), 10 msat, 100, CltvExpiryDelta(12))))
    val invoice = PaymentRequest(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_a.privateKey, "#reckless", CltvExpiryDelta(18), None, None, routingHintOverflow)
    assertThrows[IllegalArgumentException](
      buildTrampolineToLegacyPacket(invoice, trampolineHops, FinalLegacyPayload(finalAmount, finalExpiry))
    )
  }

  test("fail to decrypt when the onion is invalid") {
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, hops, FinalLegacyPayload(finalAmount, finalExpiry))
    val add = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet.copy(payload = onion.packet.payload.reverse))
    val Left(failure) = decrypt(add, priv_b.privateKey, variableLengthOnionFeature)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when the trampoline onion is invalid") {
    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createMultiPartPayload(finalAmount, finalAmount * 2, finalExpiry, paymentSecret))
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet.copy(payload = trampolineOnion.packet.payload.reverse)))
    val add_b = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet)
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc, packet_c)
    val Left(failure) = decrypt(add_c, priv_c.privateKey, variableLengthOnionFeature)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when payment hash doesn't match associated data") {
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash.reverse, hops, FinalLegacyPayload(finalAmount, finalExpiry))
    val add = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet)
    val Left(failure) = decrypt(add, priv_b.privateKey, variableLengthOnionFeature)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when variable length onion is disabled") {
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, hops.take(1), FinalTlvPayload(TlvStream(AmountToForward(finalAmount), OutgoingCltv(finalExpiry))))
    val add = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty) // tlv payload requires setting the variable-length onion feature bit
    assert(failure === InvalidRealm)
  }

  test("fail to decrypt at the final node when amount has been modified by next-to-last node") {
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, hops.take(1), FinalLegacyPayload(finalAmount, finalExpiry))
    val add = UpdateAddHtlc(randomBytes32, 1, firstAmount - 100.msat, paymentHash, firstExpiry, onion.packet)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure === FinalIncorrectHtlcAmount(firstAmount - 100.msat))
  }

  test("fail to decrypt at the final node when expiry has been modified by next-to-last node") {
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, hops.take(1), FinalLegacyPayload(finalAmount, finalExpiry))
    val add = UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry - CltvExpiryDelta(12), onion.packet)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure === FinalIncorrectCltvExpiry(firstExpiry - CltvExpiryDelta(12)))
  }

  test("fail to decrypt at the final trampoline node when amount has been modified by next-to-last trampoline") {
    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret))
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet), priv_b.privateKey, Features.empty)
    val Right(NodeRelayPacket(_, _, _, packet_d)) = decrypt(UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc, packet_c), priv_c.privateKey, variableLengthOnionFeature)
    // c forwards the trampoline payment to d.
    val (amount_d, expiry_d, onion_d) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(c, d, channelUpdate_cd) :: Nil, Onion.createTrampolinePayload(amount_cd, amount_cd, expiry_cd, randomBytes32, packet_d))
    val Right(NodeRelayPacket(_, _, _, packet_e)) = decrypt(UpdateAddHtlc(randomBytes32, 3, amount_d, paymentHash, expiry_d, onion_d.packet), priv_d.privateKey, variableLengthOnionFeature)
    // d forwards an invalid amount to e (the outer total amount doesn't match the inner amount).
    val invalidTotalAmount = amount_de + 100.msat
    val (amount_e, expiry_e, onion_e) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(d, e, channelUpdate_de) :: Nil, Onion.createTrampolinePayload(amount_de, invalidTotalAmount, expiry_de, randomBytes32, packet_e))
    val Left(failure) = decrypt(UpdateAddHtlc(randomBytes32, 4, amount_e, paymentHash, expiry_e, onion_e.packet), priv_e.privateKey, variableLengthOnionFeature)
    assert(failure === FinalIncorrectHtlcAmount(invalidTotalAmount))
  }

  test("fail to decrypt at the final trampoline node when expiry has been modified by next-to-last trampoline") {
    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret))
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet), priv_b.privateKey, Features.empty)
    val Right(NodeRelayPacket(_, _, _, packet_d)) = decrypt(UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc, packet_c), priv_c.privateKey, variableLengthOnionFeature)
    // c forwards the trampoline payment to d.
    val (amount_d, expiry_d, onion_d) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(c, d, channelUpdate_cd) :: Nil, Onion.createTrampolinePayload(amount_cd, amount_cd, expiry_cd, randomBytes32, packet_d))
    val Right(NodeRelayPacket(_, _, _, packet_e)) = decrypt(UpdateAddHtlc(randomBytes32, 3, amount_d, paymentHash, expiry_d, onion_d.packet), priv_d.privateKey, variableLengthOnionFeature)
    // d forwards an invalid expiry to e (the outer expiry doesn't match the inner expiry).
    val invalidExpiry = expiry_de - CltvExpiryDelta(12)
    val (amount_e, expiry_e, onion_e) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(d, e, channelUpdate_de) :: Nil, Onion.createTrampolinePayload(amount_de, amount_de, invalidExpiry, randomBytes32, packet_e))
    val Left(failure) = decrypt(UpdateAddHtlc(randomBytes32, 4, amount_e, paymentHash, expiry_e, onion_e.packet), priv_e.privateKey, variableLengthOnionFeature)
    assert(failure === FinalIncorrectCltvExpiry(invalidExpiry))
  }

  test("fail to decrypt at the final trampoline node when payment secret is missing") {
    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createSinglePartPayload(finalAmount, finalExpiry)) // no payment secret
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet), priv_b.privateKey, Features.empty)
    val Right(NodeRelayPacket(_, _, _, packet_d)) = decrypt(UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc, packet_c), priv_c.privateKey, variableLengthOnionFeature)
    // c forwards the trampoline payment to d.
    val (amount_d, expiry_d, onion_d) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(c, d, channelUpdate_cd) :: Nil, Onion.createTrampolinePayload(amount_cd, amount_cd, expiry_cd, randomBytes32, packet_d))
    val Right(NodeRelayPacket(_, _, _, packet_e)) = decrypt(UpdateAddHtlc(randomBytes32, 3, amount_d, paymentHash, expiry_d, onion_d.packet), priv_d.privateKey, variableLengthOnionFeature)
    // d forwards the trampoline payment to e.
    val (amount_e, expiry_e, onion_e) = buildPacket(Sphinx.PaymentPacket)(paymentHash, ChannelHop(d, e, channelUpdate_de) :: Nil, Onion.createTrampolinePayload(amount_de, amount_de, expiry_de, randomBytes32, packet_e))
    val Left(failure) = decrypt(UpdateAddHtlc(randomBytes32, 4, amount_e, paymentHash, expiry_e, onion_e.packet), priv_e.privateKey, variableLengthOnionFeature)
    assert(failure === InvalidOnionPayload(UInt64(8), 0))
  }

  test("fail to decrypt at intermediate trampoline node when amount is invalid") {
    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createSinglePartPayload(finalAmount, finalExpiry)) // no payment secret
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet), priv_b.privateKey, Features.empty)
    // A trampoline relay is very similar to a final node: it can validate that the HTLC amount matches the onion outer amount.
    val Left(failure) = decrypt(UpdateAddHtlc(randomBytes32, 2, amount_bc - 100.msat, paymentHash, expiry_bc, packet_c), priv_c.privateKey, variableLengthOnionFeature)
    assert(failure === FinalIncorrectHtlcAmount(amount_bc - 100.msat))
  }

  test("fail to decrypt at intermediate trampoline node when expiry is invalid") {
    val (amount_ac, expiry_ac, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createSinglePartPayload(finalAmount, finalExpiry)) // no payment secret
    val (firstAmount, firstExpiry, onion) = buildPacket(Sphinx.PaymentPacket)(paymentHash, trampolineChannelHops, Onion.createTrampolinePayload(amount_ac, amount_ac, expiry_ac, randomBytes32, trampolineOnion.packet))
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(UpdateAddHtlc(randomBytes32, 1, firstAmount, paymentHash, firstExpiry, onion.packet), priv_b.privateKey, Features.empty)
    // A trampoline relay is very similar to a final node: it can validate that the HTLC expiry matches the onion outer expiry.
    val Left(failure) = decrypt(UpdateAddHtlc(randomBytes32, 2, amount_bc, paymentHash, expiry_bc - CltvExpiryDelta(12), packet_c), priv_c.privateKey, variableLengthOnionFeature)
    assert(failure === FinalIncorrectCltvExpiry(expiry_bc - CltvExpiryDelta(12)))
  }

}

object PaymentPacketSpec {

  val variableLengthOnionFeature = Features(Set(ActivatedFeature(VariableLengthOnion, Optional)))

  /** Build onion from arbitrary tlv stream (potentially invalid). */
  def buildTlvOnion[T <: Onion.PacketType](packetType: Sphinx.OnionRoutingPacket[T])(nodes: Seq[PublicKey], payloads: Seq[TlvStream[OnionTlv]], associatedData: ByteVector32): OnionRoutingPacket = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey
    val payloadsBin: Seq[ByteVector] = payloads.map(OnionCodecs.tlvPerHopPayloadCodec.encode)
      .map {
        case Attempt.Successful(bitVector) => bitVector.bytes
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      }
    packetType.create(sessionKey, nodes, payloadsBin, associatedData).packet
  }

  def makeCommitments(channelId: ByteVector32, testAvailableBalanceForSend: MilliSatoshi = 50000000 msat, testAvailableBalanceForReceive: MilliSatoshi = 50000000 msat): Commitments =
    new Commitments(ChannelVersion.STANDARD, null, null, 0.toByte, null, null, null, null, 0, 0, Map.empty, null, null, null, channelId) {
      override lazy val availableBalanceForSend: MilliSatoshi = testAvailableBalanceForSend.max(0 msat)
      override lazy val availableBalanceForReceive: MilliSatoshi = testAvailableBalanceForReceive.max(0 msat)
    }

  def randomExtendedPrivateKey: ExtendedPrivateKey = DeterministicWallet.generate(randomBytes32)

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (TestConstants.Alice.keyManager.nodeKey, TestConstants.Bob.keyManager.nodeKey, randomExtendedPrivateKey, randomExtendedPrivateKey, randomExtendedPrivateKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)
  val sig = Crypto.sign(Crypto.sha256(ByteVector.empty), priv_a.privateKey)
  val defaultChannelUpdate = ChannelUpdate(sig, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(0), 42000 msat, 0 msat, 0, Some(500000000 msat))
  val channelUpdate_ab = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 642000 msat, feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 153000 msat, feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = CltvExpiryDelta(10), feeBaseMsat = 60000 msat, feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = CltvExpiryDelta(7), feeBaseMsat = 766000 msat, feeProportionalMillionths = 10)

  // simple route a -> b -> c -> d -> e

  val hops =
    ChannelHop(a, b, channelUpdate_ab) ::
      ChannelHop(b, c, channelUpdate_bc) ::
      ChannelHop(c, d, channelUpdate_cd) ::
      ChannelHop(d, e, channelUpdate_de) :: Nil

  val finalAmount = 42000000 msat
  val currentBlockCount = 400000
  val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)
  val paymentSecret = randomBytes32

  val expiry_de = finalExpiry
  val amount_de = finalAmount
  val fee_d = nodeFee(channelUpdate_de.feeBaseMsat, channelUpdate_de.feeProportionalMillionths, amount_de)

  val expiry_cd = expiry_de + channelUpdate_de.cltvExpiryDelta
  val amount_cd = amount_de + fee_d
  val fee_c = nodeFee(channelUpdate_cd.feeBaseMsat, channelUpdate_cd.feeProportionalMillionths, amount_cd)

  val expiry_bc = expiry_cd + channelUpdate_cd.cltvExpiryDelta
  val amount_bc = amount_cd + fee_c
  val fee_b = nodeFee(channelUpdate_bc.feeBaseMsat, channelUpdate_bc.feeProportionalMillionths, amount_bc)

  val expiry_ab = expiry_bc + channelUpdate_bc.cltvExpiryDelta
  val amount_ab = amount_bc + fee_b

  // simple trampoline route to e:
  //             .--.   .--.
  //            /    \ /    \
  // a -> b -> c      d      e

  val trampolineHops =
    NodeHop(a, c, channelUpdate_ab.cltvExpiryDelta + channelUpdate_bc.cltvExpiryDelta, fee_b) ::
      NodeHop(c, d, channelUpdate_cd.cltvExpiryDelta, fee_c) ::
      NodeHop(d, e, channelUpdate_de.cltvExpiryDelta, fee_d) :: Nil

  val trampolineChannelHops =
    ChannelHop(a, b, channelUpdate_ab) ::
      ChannelHop(b, c, channelUpdate_bc) :: Nil

}
