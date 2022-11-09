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

import akka.actor.ActorRef
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, DeterministicWallet, OutPoint, Satoshi, SatoshiLong, TxOut}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.payment.IncomingPaymentPacket.{ChannelRelayPacket, FinalPacket, NodeRelayPacket, decrypt}
import fr.acinq.eclair.payment.OutgoingPaymentPacket._
import fr.acinq.eclair.payment.send.{ClearRecipient, ClearTrampolineRecipient}
import fr.acinq.eclair.router.BaseRouterSpec.channelHopFromUpdate
import fr.acinq.eclair.router.Router.{NodeHop, Route}
import fr.acinq.eclair.transactions.Transactions.InputInfo
import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv.{AmountToForward, OutgoingCltv, PaymentData}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TestConstants, TimestampSecondLong, nodeFee, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.util.Success

/**
 * Created by PM on 31/05/2016.
 */

class PaymentPacketSpec extends AnyFunSuite with BeforeAndAfterAll {

  import PaymentPacketSpec._

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  test("compute fees") {
    val feeBaseMsat = 150_000 msat
    val feeProportionalMillionth = 4
    val htlcAmountMsat = 42_000_000 msat
    // spec: fee-base-msat + htlc-amount-msat * fee-proportional-millionths / 1000000
    val ref = feeBaseMsat + htlcAmountMsat * feeProportionalMillionth / 1_000_000
    val fee = nodeFee(feeBaseMsat, feeProportionalMillionth, htlcAmountMsat)
    assert(ref == fee)
  }

  def testBuildOutgoingPayment(): Unit = {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, hops), recipient)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.onion.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)

    // let's peel the onion
    testPeelOnion(payment.cmd.onion)
  }

  def testPeelOnion(packet_b: OnionRoutingPacket): Unit = {
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, packet_b, None)
    val Right(relay_b@ChannelRelayPacket(add_b2, payload_b, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 == add_b)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward == amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoingChannelId == channelUpdate_bc.shortChannelId)
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)

    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None)
    val Right(relay_c@ChannelRelayPacket(add_c2, payload_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(add_c2 == add_c)
    assert(packet_d.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_c.amountToForward == amount_cd)
    assert(relay_c.outgoingCltv == expiry_cd)
    assert(payload_c.outgoingChannelId == channelUpdate_cd.shortChannelId)
    assert(relay_c.relayFeeMsat == fee_c)
    assert(relay_c.expiryDelta == channelUpdate_cd.cltvExpiryDelta)

    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None)
    val Right(relay_d@ChannelRelayPacket(add_d2, payload_d, packet_e)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(packet_e.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_d.amountToForward == amount_de)
    assert(relay_d.outgoingCltv == expiry_de)
    assert(payload_d.outgoingChannelId == channelUpdate_de.shortChannelId)
    assert(relay_d.relayFeeMsat == fee_d)
    assert(relay_d.expiryDelta == channelUpdate_de.cltvExpiryDelta)

    val add_e = UpdateAddHtlc(randomBytes32(), 2, amount_de, paymentHash, expiry_de, packet_e, None)
    val Right(FinalPacket(add_e2, payload_e)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(add_e2 == add_e)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == finalAmount)
    assert(payload_e.totalAmount == finalAmount)
    assert(payload_e.expiry == finalExpiry)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)
  }

  test("build outgoing payment onion") {
    testBuildOutgoingPayment()
  }

  test("build outgoing payment for direct peer") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret, paymentMetadata_opt = Some(paymentMetadata))
    val route = Route(finalAmount, hops.take(1))
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, route, recipient)
    assert(payment.cmd.amount == finalAmount)
    assert(payment.cmd.cltvExpiry == finalExpiry)
    assert(payment.cmd.paymentHash == paymentHash)
    assert(payment.cmd.onion.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32(), 0, finalAmount, paymentHash, finalExpiry, payment.cmd.onion, None)
    val Right(FinalPacket(add_b2, payload_b)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 == add_b)
    assert(payload_b.isInstanceOf[FinalPayload.Standard])
    assert(payload_b.amount == finalAmount)
    assert(payload_b.totalAmount == finalAmount)
    assert(payload_b.expiry == finalExpiry)
    assert(payload_b.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)
    assert(payload_b.asInstanceOf[FinalPayload.Standard].paymentMetadata.contains(paymentMetadata))
  }

  test("build outgoing payment with greater amount and expiry") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret, paymentMetadata_opt = Some(paymentMetadata))
    val route = Route(finalAmount, hops.take(1))
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, route, recipient)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32(), 0, finalAmount + 100.msat, paymentHash, finalExpiry + CltvExpiryDelta(6), payment.cmd.onion, None)
    val Right(FinalPacket(_, payload_b)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(payload_b.isInstanceOf[FinalPayload.Standard])
    assert(payload_b.amount == finalAmount)
    assert(payload_b.totalAmount == finalAmount)
    assert(payload_b.expiry == finalExpiry)
    assert(payload_b.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)
  }

  test("build outgoing trampoline payment") {
    // simple trampoline route to e:
    //             .--.   .--.
    //            /    \ /    \
    // a -> b -> c      d      e
    val invoiceFeatures = Features[InvoiceFeature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHops, randomBytes32())
    assert(recipient.trampolineAmount == amount_bc)
    assert(recipient.trampolineExpiry == expiry_bc)
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, trampolineChannelHops), recipient)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)

    val add_b = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    val Right(ChannelRelayPacket(add_b2, payload_b, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 == add_b)
    assert(payload_b == IntermediatePayload.ChannelRelay.Standard(channelUpdate_bc.shortChannelId, amount_bc, expiry_bc))

    val add_c = UpdateAddHtlc(randomBytes32(), 2, amount_bc, paymentHash, expiry_bc, packet_c, None)
    val Right(NodeRelayPacket(add_c2, outer_c, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(add_c2 == add_c)
    assert(outer_c.amount == amount_bc)
    assert(outer_c.totalAmount == amount_bc)
    assert(outer_c.expiry == expiry_bc)
    assert(outer_c.paymentSecret != invoice.paymentSecret)
    assert(inner_c.amountToForward == amount_cd)
    assert(inner_c.outgoingCltv == expiry_cd)
    assert(inner_c.outgoingNodeId == d)
    assert(inner_c.invoiceRoutingInfo.isEmpty)
    assert(inner_c.invoiceFeatures.isEmpty)
    assert(inner_c.paymentSecret.isEmpty)
    assert(inner_c.paymentMetadata.isEmpty)

    // c forwards the trampoline payment to d.
    val recipient_d = ClearRecipient(d, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_d))
    val Success(payment_d) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_c)), paymentHash, Route(inner_c.amountToForward, Seq(channelHopFromUpdate(c, d, channelUpdate_cd))), recipient_d)
    assert(payment_d.cmd.amount == amount_cd)
    assert(payment_d.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_d.cmd.amount, paymentHash, payment_d.cmd.cltvExpiry, payment_d.cmd.onion, None)
    val Right(NodeRelayPacket(add_d2, outer_d, inner_d, packet_e)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(outer_d.amount == amount_cd)
    assert(outer_d.totalAmount == amount_cd)
    assert(outer_d.expiry == expiry_cd)
    assert(inner_d.amountToForward == amount_de)
    assert(inner_d.outgoingCltv == expiry_de)
    assert(inner_d.outgoingNodeId == e)
    assert(inner_d.invoiceRoutingInfo.isEmpty)
    assert(inner_d.invoiceFeatures.isEmpty)
    assert(inner_d.paymentSecret.isEmpty)
    assert(inner_d.paymentMetadata.isEmpty)

    // d forwards the trampoline payment to e.
    val recipient_e = ClearRecipient(e, Features.empty, inner_d.amountToForward, inner_d.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_e))
    val Success(payment_e) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_d)), paymentHash, Route(inner_d.amountToForward, Seq(channelHopFromUpdate(d, e, channelUpdate_de))), recipient_e)
    assert(payment_e.cmd.amount == amount_de)
    assert(payment_e.cmd.cltvExpiry == expiry_de)
    val add_e = UpdateAddHtlc(randomBytes32(), 4, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None)
    val Right(FinalPacket(add_e2, payload_e)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(add_e2 == add_e)
    assert(payload_e == FinalPayload.Standard(TlvStream(AmountToForward(finalAmount), OutgoingCltv(finalExpiry), PaymentData(paymentSecret, finalAmount), OnionPaymentPayloadTlv.PaymentMetadata(hex"010203"))))
  }

  test("build outgoing trampoline payment with non-trampoline recipient") {
    // simple trampoline route to e where e doesn't support trampoline:
    //             .--.
    //            /    \
    // a -> b -> c      d -> e
    val routingHints = List(List(Bolt11Invoice.ExtraHop(randomKey().publicKey, ShortChannelId(42), 10 msat, 100, CltvExpiryDelta(144))))
    val invoiceFeatures = Features[InvoiceFeature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("#reckless"), CltvExpiryDelta(18), extraHops = routingHints, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHops, randomBytes32())
    assert(recipient.trampolineAmount == amount_bc)
    assert(recipient.trampolineExpiry == expiry_bc)
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, trampolineChannelHops), recipient)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)

    val add_b = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)

    val add_c = UpdateAddHtlc(randomBytes32(), 2, amount_bc, paymentHash, expiry_bc, packet_c, None)
    val Right(NodeRelayPacket(_, outer_c, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(outer_c.amount == amount_bc)
    assert(outer_c.totalAmount == amount_bc)
    assert(outer_c.expiry == expiry_bc)
    assert(outer_c.paymentSecret != invoice.paymentSecret)
    assert(inner_c.amountToForward == amount_cd)
    assert(inner_c.outgoingCltv == expiry_cd)
    assert(inner_c.outgoingNodeId == d)
    assert(inner_c.invoiceRoutingInfo.isEmpty)
    assert(inner_c.invoiceFeatures.isEmpty)
    assert(inner_c.paymentSecret.isEmpty)

    // c forwards the trampoline payment to d.
    val recipient_d = ClearRecipient(d, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_d))
    val Success(payment_d) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_c)), paymentHash, Route(inner_c.amountToForward, Seq(channelHopFromUpdate(c, d, channelUpdate_cd))), recipient_d)
    assert(payment_d.cmd.amount == amount_cd)
    assert(payment_d.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_d.cmd.amount, paymentHash, payment_d.cmd.cltvExpiry, payment_d.cmd.onion, None)
    val Right(NodeRelayPacket(_, outer_d, inner_d, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(outer_d.amount == amount_cd)
    assert(outer_d.totalAmount == amount_cd)
    assert(outer_d.expiry == expiry_cd)
    assert(outer_d.paymentSecret != invoice.paymentSecret)
    assert(inner_d.amountToForward == finalAmount)
    assert(inner_d.outgoingCltv == expiry_de)
    assert(inner_d.outgoingNodeId == e)
    assert(inner_d.totalAmount == finalAmount)
    assert(inner_d.paymentSecret.contains(invoice.paymentSecret))
    assert(inner_d.paymentMetadata.contains(hex"010203"))
    assert(inner_d.invoiceFeatures.contains(invoiceFeatures.toByteVector))
    assert(inner_d.invoiceRoutingInfo.contains(routingHints))
  }

  test("fail to build outgoing trampoline payment when too much invoice data is provided") {
    val routingHintOverflow = List(List.fill(7)(Bolt11Invoice.ExtraHop(randomKey().publicKey, ShortChannelId(1), 10 msat, 100, CltvExpiryDelta(12))))
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("#reckless"), CltvExpiryDelta(18), None, None, routingHintOverflow)
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHops, randomBytes32())
    assert(buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, trampolineChannelHops), recipient).isFailure)
  }

  test("fail to build outgoing trampoline payment when too much payment metadata is provided") {
    val paymentMetadata = ByteVector.fromValidHex("01" * 400)
    val invoiceFeatures = Features[InvoiceFeature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("Much payment very metadata"), CltvExpiryDelta(9), features = invoiceFeatures, paymentMetadata = Some(paymentMetadata))
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHops, randomBytes32())
    assert(buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, trampolineChannelHops), recipient).isFailure)
  }

  test("fail to decrypt when the onion is invalid") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, hops), recipient)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion.copy(payload = payment.cmd.onion.payload.reverse), None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when the trampoline onion is invalid") {
    val invoiceFeatures = Features[InvoiceFeature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHops, randomBytes32())
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, trampolineChannelHops), recipient)

    val add_b = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)

    val add_c = UpdateAddHtlc(randomBytes32(), 2, amount_bc, paymentHash, expiry_bc, packet_c, None)
    val Right(NodeRelayPacket(_, _, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid trampoline onion to d.
    val recipient_d = ClearRecipient(d, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_d.copy(payload = packet_d.payload.reverse)))
    val Success(payment_d) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_c)), paymentHash, Route(inner_c.amountToForward, Seq(channelHopFromUpdate(c, d, channelUpdate_cd))), recipient_d)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_d.cmd.amount, paymentHash, payment_d.cmd.cltvExpiry, payment_d.cmd.onion, None)
    val Left(failure) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when payment hash doesn't match associated data") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash.reverse, Route(finalAmount, hops), recipient)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt at the final node when amount has been modified by next-to-last node") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.take(1))
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, route, recipient)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount - 100.msat, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(payment.cmd.amount - 100.msat))
  }

  test("fail to decrypt at the final node when expiry has been modified by next-to-last node") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.take(1))
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, route, recipient)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry - CltvExpiryDelta(12), payment.cmd.onion, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure == FinalIncorrectCltvExpiry(payment.cmd.cltvExpiry - CltvExpiryDelta(12)))
  }

  /**
   * Create a trampoline payment to e:
   *
   * .--.   .--.
   * /    \ /    \
   * a -> b -> c      d      e
   *
   * and return the HTLC sent by b to c.
   */
  def createIntermediateTrampolinePayment(): UpdateAddHtlc = {
    val invoiceFeatures = Features[InvoiceFeature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures)
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHops, randomBytes32())
    val Success(payment) = buildOutgoingPayment(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, trampolineChannelHops), recipient)

    val add_b = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    val Right(ChannelRelayPacket(_, _, packet_c)) = decrypt(add_b, priv_b.privateKey, Features.empty)

    UpdateAddHtlc(randomBytes32(), 2, amount_bc, paymentHash, expiry_bc, packet_c, None)
  }

  test("fail to decrypt at the final trampoline node when amount has been decreased by next-to-last trampoline") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(NodeRelayPacket(_, _, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards the trampoline payment to d.
    val recipient_d = ClearRecipient(d, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_d))
    val Success(payment_d) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_c)), paymentHash, Route(inner_c.amountToForward, Seq(channelHopFromUpdate(c, d, channelUpdate_cd))), recipient_d)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_d.cmd.amount, paymentHash, payment_d.cmd.cltvExpiry, payment_d.cmd.onion, None)
    val Right(NodeRelayPacket(_, _, inner_d, packet_e)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    // d forwards an invalid amount to e (the outer total amount doesn't match the inner amount).
    val invalidTotalAmount = inner_d.amountToForward - 1.msat
    val recipient_e = ClearRecipient(e, Features.empty, invalidTotalAmount, inner_d.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_e))
    val Success(payment_e) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_d)), paymentHash, Route(inner_d.amountToForward, Seq(channelHopFromUpdate(d, e, channelUpdate_de))), recipient_e)
    val add_e = UpdateAddHtlc(randomBytes32(), 4, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(invalidTotalAmount))
  }

  test("fail to decrypt at the final trampoline node when expiry has been modified by next-to-last trampoline") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(NodeRelayPacket(_, _, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards the trampoline payment to d.
    val recipient_d = ClearRecipient(d, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_d))
    val Success(payment_d) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_c)), paymentHash, Route(inner_c.amountToForward, Seq(channelHopFromUpdate(c, d, channelUpdate_cd))), recipient_d)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_d.cmd.amount, paymentHash, payment_d.cmd.cltvExpiry, payment_d.cmd.onion, None)
    val Right(NodeRelayPacket(_, _, inner_d, packet_e)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    // d forwards an invalid amount to e (the outer total amount doesn't match the inner amount).
    val invalidExpiry = inner_d.outgoingCltv - CltvExpiryDelta(12)
    val recipient_e = ClearRecipient(e, Features.empty, inner_d.amountToForward, invalidExpiry, randomBytes32(), nextTrampolineOnion_opt = Some(packet_e))
    val Success(payment_e) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_d)), paymentHash, Route(inner_d.amountToForward, Seq(channelHopFromUpdate(d, e, channelUpdate_de))), recipient_e)
    val add_e = UpdateAddHtlc(randomBytes32(), 4, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure == FinalIncorrectCltvExpiry(invalidExpiry))
  }

  test("fail to decrypt at intermediate trampoline node when amount is invalid") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(NodeRelayPacket(_, _, inner_c, packet_d)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards the payment to d.
    val recipient_d = ClearRecipient(d, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(packet_d))
    val Success(payment_d) = buildOutgoingPayment(ActorRef.noSender, Upstream.Trampoline(Seq(add_c)), paymentHash, Route(inner_c.amountToForward, Seq(channelHopFromUpdate(c, d, channelUpdate_cd))), recipient_d)
    // A trampoline relay is very similar to a final node: it can validate that the HTLC amount matches the onion outer amount.
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_d.cmd.amount - 100.msat, paymentHash, payment_d.cmd.cltvExpiry, payment_d.cmd.onion, None)
    val Left(failure) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(inner_c.amountToForward - 100.msat))
  }

  test("fail to decrypt at intermediate trampoline node when expiry is invalid") {
    val add_c = createIntermediateTrampolinePayment()
    val invalidAdd = add_c.copy(cltvExpiry = add_c.cltvExpiry - CltvExpiryDelta(12))
    // A trampoline relay is very similar to a final node: it validates that the HTLC expiry matches the onion outer expiry.
    val Left(failure) = decrypt(invalidAdd, priv_c.privateKey, Features.empty)
    assert(failure == FinalIncorrectCltvExpiry(expiry_bc - CltvExpiryDelta(12)))
  }

}

object PaymentPacketSpec {

  def makeCommitments(channelId: ByteVector32, testAvailableBalanceForSend: MilliSatoshi = 50000000 msat, testAvailableBalanceForReceive: MilliSatoshi = 50000000 msat, testCapacity: Satoshi = 100000 sat, channelFeatures: ChannelFeatures = ChannelFeatures()): Commitments = {
    val channelReserve = testCapacity * 0.01
    val params = LocalParams(null, null, null, null, Some(channelReserve), null, null, 0, isInitiator = true, null, None, null)
    val remoteParams = RemoteParams(randomKey().publicKey, null, null, Some(channelReserve), null, null, maxAcceptedHtlcs = 0, null, null, null, null, null, null, None)
    val commitInput = InputInfo(OutPoint(randomBytes32(), 1), TxOut(testCapacity, Nil), Nil)
    val channelFlags = ChannelFlags.Private
    new Commitments(channelId, ChannelConfig.standard, channelFeatures, params, remoteParams, channelFlags, null, null, null, null, 0, 0, Map.empty, null, commitInput, null) {
      override lazy val availableBalanceForSend: MilliSatoshi = testAvailableBalanceForSend.max(0 msat)
      override lazy val availableBalanceForReceive: MilliSatoshi = testAvailableBalanceForReceive.max(0 msat)
    }
  }

  def randomExtendedPrivateKey: ExtendedPrivateKey = DeterministicWallet.generate(randomBytes32())

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (TestConstants.Alice.nodeKeyManager.nodeKey, TestConstants.Bob.nodeKeyManager.nodeKey, randomExtendedPrivateKey, randomExtendedPrivateKey, randomExtendedPrivateKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)
  val sig = Crypto.sign(Crypto.sha256(ByteVector.empty), priv_a.privateKey)
  val defaultChannelUpdate = ChannelUpdate(sig, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(0), 42000 msat, 0 msat, 0, 500_000_000 msat)
  val channelUpdate_ab = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 642_000 msat, feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 153_000 msat, feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = CltvExpiryDelta(10), feeBaseMsat = 60_000 msat, feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = CltvExpiryDelta(7), feeBaseMsat = 766_000 msat, feeProportionalMillionths = 10)

  // simple route a -> b -> c -> d -> e
  val hops = Seq(
    channelHopFromUpdate(a, b, channelUpdate_ab),
    channelHopFromUpdate(b, c, channelUpdate_bc),
    channelHopFromUpdate(c, d, channelUpdate_cd),
    channelHopFromUpdate(d, e, channelUpdate_de),
  )

  val finalAmount = 42_000_000 msat
  val currentBlockCount = 400_000
  val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
  val paymentPreimage = randomBytes32()
  val paymentHash = Crypto.sha256(paymentPreimage)
  val paymentSecret = randomBytes32()
  val paymentMetadata = randomBytes32().bytes

  val expiry_de = finalExpiry
  val amount_de = finalAmount
  val fee_d = nodeFee(channelUpdate_de.relayFees, amount_de)

  val expiry_cd = expiry_de + channelUpdate_de.cltvExpiryDelta
  val amount_cd = amount_de + fee_d
  val fee_c = nodeFee(channelUpdate_cd.relayFees, amount_cd)

  val expiry_bc = expiry_cd + channelUpdate_cd.cltvExpiryDelta
  val amount_bc = amount_cd + fee_c
  val fee_b = nodeFee(channelUpdate_bc.relayFees, amount_bc)

  val expiry_ab = expiry_bc + channelUpdate_bc.cltvExpiryDelta
  val amount_ab = amount_bc + fee_b

  // simple trampoline route to e:
  //             .--.   .--.
  //            /    \ /    \
  // a -> b -> c      d      e

  val trampolineHops = Seq(
    NodeHop(c, d, channelUpdate_cd.cltvExpiryDelta, fee_c),
    NodeHop(d, e, channelUpdate_de.cltvExpiryDelta, fee_d)
  )

  val trampolineChannelHops = Seq(
    channelHopFromUpdate(a, b, channelUpdate_ab),
    channelHopFromUpdate(b, c, channelUpdate_bc)
  )

}
