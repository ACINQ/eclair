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
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Transaction, TxOut}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.channel.ChannelSpendSignature.IndividualSignature
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.Sphinx.HoldTime
import fr.acinq.eclair.crypto.{ShaChain, Sphinx}
import fr.acinq.eclair.payment.IncomingPaymentPacket._
import fr.acinq.eclair.payment.OutgoingPaymentPacket._
import fr.acinq.eclair.payment.send.BlindedPathsResolver.{FullBlindedRoute, ResolvedPath}
import fr.acinq.eclair.payment.send.{BlindedRecipient, ClearRecipient, TrampolinePayment}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRouteFromHops, channelHopFromUpdate}
import fr.acinq.eclair.router.BlindedRouteCreation
import fr.acinq.eclair.router.Router.{NodeHop, Route}
import fr.acinq.eclair.transactions.Transactions.DefaultCommitmentFormat
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, OutgoingBlindedPerHopPayload}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Bolt11Feature, Bolt12Feature, CltvExpiry, CltvExpiryDelta, EncodedNodeId, Features, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TestConstants, TimestampMilli, TimestampSecondLong, UInt64, nodeFee, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration._

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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, Reputation.Score.max)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.onion.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)

    // let's peel the onion
    testPeelOnion(payment.cmd.onion)
  }

  def testPeelOnion(packet_b: OnionRoutingPacket): Unit = {
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, packet_b, None, Reputation.maxEndorsement, None)
    val Right(relay_b@ChannelRelayPacket(add_b2, payload_b, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 == add_b)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward == amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoing.contains(channelUpdate_bc.shortChannelId))
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)

    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, Reputation.maxEndorsement, None)
    val Right(relay_c@ChannelRelayPacket(add_c2, payload_c, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(add_c2 == add_c)
    assert(packet_d.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_c.amountToForward == amount_cd)
    assert(relay_c.outgoingCltv == expiry_cd)
    assert(payload_c.outgoing.contains(channelUpdate_cd.shortChannelId))
    assert(relay_c.relayFeeMsat == fee_c)
    assert(relay_c.expiryDelta == channelUpdate_cd.cltvExpiryDelta)

    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None, Reputation.maxEndorsement, None)
    val Right(relay_d@ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(packet_e.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_d.amountToForward == amount_de)
    assert(relay_d.outgoingCltv == expiry_de)
    assert(payload_d.outgoing.contains(channelUpdate_de.shortChannelId))
    assert(relay_d.relayFeeMsat == fee_d)
    assert(relay_d.expiryDelta == channelUpdate_de.cltvExpiryDelta)

    val add_e = UpdateAddHtlc(randomBytes32(), 2, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(add_e2, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
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
    val route = Route(finalAmount, hops.take(1), None)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    assert(payment.cmd.amount == finalAmount)
    assert(payment.cmd.cltvExpiry == finalExpiry)
    assert(payment.cmd.paymentHash == paymentHash)
    assert(payment.cmd.onion.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32(), 0, finalAmount, paymentHash, finalExpiry, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(add_b2, payload_b, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
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
    val route = Route(finalAmount, hops.take(1), None)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32(), 0, finalAmount + 100.msat, paymentHash, finalExpiry + CltvExpiryDelta(6), payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(_, payload_b, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(payload_b.isInstanceOf[FinalPayload.Standard])
    assert(payload_b.amount == finalAmount)
    assert(payload_b.totalAmount == finalAmount)
    assert(payload_b.expiry == finalExpiry)
    assert(payload_b.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)
  }

  test("build outgoing blinded payment") {
    val (invoice, route, recipient) = longBlindedHops(hex"deadbeef")
    assert(recipient.extraEdges.length == 1)
    assert(recipient.extraEdges.head.sourceNodeId == c)
    assert(recipient.extraEdges.head.targetNodeId == invoice.nodeId)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount >= amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.nextPathKey_opt.isEmpty)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward >= amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoing.contains(channelUpdate_bc.shortChannelId))
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)
    assert(payload_b.isInstanceOf[IntermediatePayload.ChannelRelay.Standard])

    val add_c = UpdateAddHtlc(randomBytes32(), 1, relay_b.amountToForward, relay_b.add.paymentHash, relay_b.outgoingCltv, packet_c, None, Reputation.maxEndorsement, None)
    val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features(RouteBlinding -> Optional))
    assert(packet_d.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_c.amountToForward >= amount_cd)
    assert(relay_c.outgoingCltv == expiry_cd)
    assert(payload_c.outgoing.contains(channelUpdate_cd.shortChannelId))
    assert(relay_c.relayFeeMsat == fee_c)
    assert(relay_c.expiryDelta == channelUpdate_cd.cltvExpiryDelta)
    assert(payload_c.isInstanceOf[IntermediatePayload.ChannelRelay.Blinded])
    val pathKey_d = payload_c.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey

    val add_d = UpdateAddHtlc(randomBytes32(), 2, relay_c.amountToForward, relay_c.add.paymentHash, relay_c.outgoingCltv, packet_d, Some(pathKey_d), Reputation.maxEndorsement, None)
    val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    assert(packet_e.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_d.amountToForward >= amount_de)
    assert(relay_d.outgoingCltv == expiry_de)
    assert(payload_d.outgoing.contains(channelUpdate_de.shortChannelId))
    assert(relay_d.relayFeeMsat == fee_d)
    assert(relay_d.expiryDelta == channelUpdate_de.cltvExpiryDelta)
    assert(payload_d.isInstanceOf[IntermediatePayload.ChannelRelay.Blinded])
    val pathKey_e = payload_d.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey

    val add_e = UpdateAddHtlc(randomBytes32(), 2, relay_d.amountToForward, relay_d.add.paymentHash, relay_d.outgoingCltv, packet_e, Some(pathKey_e), Reputation.maxEndorsement, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_e.amount == finalAmount)
    assert(payload_e.totalAmount == finalAmount)
    assert(add_e.cltvExpiry == finalExpiry)
    assert(payload_e.expiry == finalExpiry - Channel.MIN_CLTV_EXPIRY_DELTA) // the expiry in the onion doesn't take the min_final_expiry_delta into account
    assert(payload_e.isInstanceOf[FinalPayload.Blinded])
    assert(payload_e.asInstanceOf[FinalPayload.Blinded].pathId == hex"deadbeef")
  }

  test("build outgoing blinded payment for introduction node") {
    // a -> b -> c where c uses a 0-hop blinded route.
    val recipientKey = randomKey()
    val features = Features[Bolt12Feature](BasicMultiPartPayment -> Optional)
    val offer = Offer(None, Some("Bolt12 r0cks"), recipientKey.publicKey, features, Block.RegtestGenesisBlock.hash)
    val invoiceRequest = InvoiceRequest(offer, amount_bc, 1, features, randomKey(), Block.RegtestGenesisBlock.hash)
    val blindedRoute = BlindedRouteCreation.createBlindedRouteFromHops(Nil, c, hex"deadbeef", 1 msat, CltvExpiry(500_000)).route
    val paymentInfo = PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 1 msat, amount_bc, ByteVector.empty)
    val invoice = Bolt12Invoice(invoiceRequest, paymentPreimage, recipientKey, 300 seconds, features, Seq(PaymentBlindedRoute(blindedRoute, paymentInfo)))
    val resolvedPaths = invoice.blindedPaths.map(path => {
      val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
      ResolvedPath(FullBlindedRoute(introductionNodeId, path.route.firstPathKey, path.route.blindedHops), path.paymentInfo)
    })
    val recipient = BlindedRecipient(invoice, resolvedPaths, amount_bc, expiry_bc, Set.empty)
    val hops = Seq(channelHopFromUpdate(a, b, channelUpdate_ab), channelHopFromUpdate(b, c, channelUpdate_bc))
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(amount_bc, hops, Some(recipient.blindedHops.head)), recipient, Reputation.Score.max)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.nextPathKey_opt.isEmpty)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward >= amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoing.contains(channelUpdate_bc.shortChannelId))
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)
    assert(payload_b.isInstanceOf[IntermediatePayload.ChannelRelay.Standard])

    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(_, payload_c, _)) = decrypt(add_c, priv_c.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_c.amount == amount_bc)
    assert(payload_c.totalAmount == amount_bc)
    assert(payload_c.expiry == expiry_bc)
    assert(payload_c.isInstanceOf[FinalPayload.Blinded])
    assert(payload_c.asInstanceOf[FinalPayload.Blinded].pathId == hex"deadbeef")
  }

  test("build outgoing blinded payment starting at our node") {
    val Right(payment) = buildOutgoingBlindedPaymentAB(paymentHash)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == finalAmount)
    assert(payment.cmd.cltvExpiry == finalExpiry)
    assert(payment.cmd.nextPathKey_opt.nonEmpty)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Right(FinalPacket(_, payload_b, _)) = decrypt(add_b, priv_b.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_b.amount == finalAmount)
    assert(payload_b.totalAmount == finalAmount)
    assert(add_b.cltvExpiry == finalExpiry)
    assert(payload_b.isInstanceOf[FinalPayload.Blinded])
    assert(payload_b.asInstanceOf[FinalPayload.Blinded].pathId == hex"deadbeef")
  }

  test("build outgoing blinded payment with greater amount and expiry") {
    val Right(payment) = buildOutgoingBlindedPaymentAB(paymentHash)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount + 100.msat, payment.cmd.paymentHash, payment.cmd.cltvExpiry + CltvExpiryDelta(6), payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Right(FinalPacket(_, payload_b, _)) = decrypt(add_b, priv_b.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_b.amount == finalAmount)
    assert(payload_b.totalAmount == finalAmount)
  }

  private def testRelayTrampolinePayment(invoice: Bolt11Invoice, payment: TrampolinePayment.OutgoingPayment): Unit = {
    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, Reputation.maxEndorsement, None)
    val Right(RelayToTrampolinePacket(add_c2, outer_c, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(add_c2 == add_c)
    assert(outer_c.amount == payment.trampolineAmount)
    assert(outer_c.totalAmount == payment.trampolineAmount)
    assert(outer_c.expiry == payment.trampolineExpiry)
    assert(outer_c.paymentSecret != invoice.paymentSecret)
    assert(inner_c.amountToForward == finalAmount)
    assert(inner_c.outgoingCltv == finalExpiry)
    assert(inner_c.outgoingNodeId == e)

    // c forwards the trampoline payment to e through d.
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b, 0.1)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, Reputation.Score.max)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(add_e2, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(add_e2 == add_e)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == finalAmount)
    assert(payload_e.expiry == finalExpiry)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentMetadata == invoice.paymentMetadata)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].isTrampoline)
  }

  test("build outgoing trampoline payment") {
    // simple trampoline route to e:
    //        .----.
    //       /      \
    // b -> c        e
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)
    testRelayTrampolinePayment(invoice, payment)
  }

  test("build outgoing trampoline payment without MPP") {
    // simple trampoline route to e, but we don't include a payment_secret in the outer payload for c:
    //        .----.
    //       /      \
    // b -> c        e
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    // Note that we don't include a payment_secret for the trampoline node, since the spec allows omitting it when not using MPP to reach the trampoline node.
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalAmount, finalExpiry, trampolinePaymentSecret_opt = None, attemptNumber = 0)
    testRelayTrampolinePayment(invoice, payment)
  }

  test("build outgoing trampoline payment with non-trampoline recipient") {
    // simple trampoline route to e where e doesn't support trampoline:
    //        .----.
    //       /      \
    // b -> c        e
    val routingHints = List(List(Bolt11Invoice.ExtraHop(randomKey().publicKey, ShortChannelId(42), 10 msat, 100, CltvExpiryDelta(144))))
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("#reckless"), CltvExpiryDelta(18), extraHops = routingHints, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, Reputation.maxEndorsement, None)
    val Right(RelayToNonTrampolinePacket(_, outer_c, inner_c, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(outer_c.amount == payment.trampolineAmount)
    assert(outer_c.totalAmount == payment.trampolineAmount)
    assert(outer_c.expiry == payment.trampolineExpiry)
    assert(outer_c.paymentSecret != invoice.paymentSecret)
    assert(outer_c.records.get[OnionPaymentPayloadTlv.TrampolineOnion].get.packet.payload.size < 400)
    assert(inner_c.amountToForward == finalAmount)
    assert(inner_c.totalAmount == finalAmount)
    assert(inner_c.outgoingCltv == finalExpiry)
    assert(inner_c.outgoingNodeId == e)
    assert(inner_c.paymentSecret == invoice.paymentSecret)
    assert(inner_c.paymentMetadata.contains(hex"010203"))
    assert(inner_c.invoiceFeatures == invoiceFeatures.toByteVector)
    assert(inner_c.invoiceRoutingInfo == routingHints)

    // c forwards the trampoline payment to e through d.
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, inner_c.paymentSecret, invoice.extraEdges, inner_c.paymentMetadata)
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b, 0.1)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, Reputation.Score.max)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(add_e2, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(add_e2 == add_e)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == finalAmount)
    assert(payload_e.expiry == finalExpiry)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentSecret == invoice.paymentSecret)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentMetadata.contains(hex"010203"))
    assert(!payload_e.asInstanceOf[FinalPayload.Standard].isTrampoline)
  }

  test("build outgoing trampoline payment with non-trampoline recipient and dummy trampoline packet") {
    // simple trampoline route to e where e doesn't support trampoline:
    //        .----.
    //       /      \
    // b -> c        e
    val routingHints = List(List(Bolt11Invoice.ExtraHop(randomKey().publicKey, ShortChannelId(42), 10 msat, 100, CltvExpiryDelta(144))))
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("#reckless"), CltvExpiryDelta(18), extraHops = routingHints, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val trampolineOnion = {
      // The payer is a wallet using the legacy trampoline feature, which includes a dummy trampoline payload that never
      // reaches the recipient. It relies on the trampoline node to detect that some invoice data is provided to convert
      // the payment to a non-trampoline payment.
      val dummyPayload = PaymentOnion.IntermediatePayload.ChannelRelay.Standard(ShortChannelId(0), 0 msat, CltvExpiry(0))
      val trampolinePayload = PaymentOnion.IntermediatePayload.NodeRelay.ToNonTrampoline(finalAmount, finalAmount, finalExpiry, invoice.nodeId, invoice)
      buildOnion(NodePayload(c, trampolinePayload) :: NodePayload(invoice.nodeId, dummyPayload) :: Nil, invoice.paymentHash, None).toOption.get
    }
    val payment = {
      val trampolineAmount = finalAmount * 1.001 // 0.1% fees
      val trampolineExpiry = finalExpiry + CltvExpiryDelta(144)
      val payload = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, randomBytes32(), trampolineOnion.packet)
      val paymentOnion = buildOnion(NodePayload(c, payload) :: Nil, invoice.paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get
      TrampolinePayment.OutgoingPayment(trampolineAmount, trampolineExpiry, paymentOnion, trampolineOnion)
    }

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, Reputation.maxEndorsement, None)
    val Right(RelayToNonTrampolinePacket(_, outer_c, inner_c, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(outer_c.amount == payment.trampolineAmount)
    assert(outer_c.totalAmount == payment.trampolineAmount)
    assert(outer_c.expiry == payment.trampolineExpiry)
    assert(outer_c.paymentSecret != invoice.paymentSecret)
    assert(outer_c.records.get[OnionPaymentPayloadTlv.TrampolineOnion].get.packet.payload.size < 400)
    assert(inner_c.amountToForward == finalAmount)
    assert(inner_c.totalAmount == finalAmount)
    assert(inner_c.outgoingCltv == finalExpiry)
    assert(inner_c.outgoingNodeId == e)
    assert(inner_c.paymentSecret == invoice.paymentSecret)
    assert(inner_c.paymentMetadata.contains(hex"010203"))
    assert(inner_c.invoiceFeatures == invoiceFeatures.toByteVector)
    assert(inner_c.invoiceRoutingInfo == routingHints)

    // c forwards the trampoline payment to e through d.
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, inner_c.paymentSecret, invoice.extraEdges, inner_c.paymentMetadata)
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b, 0.1)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, Reputation.Score.max)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(add_e2, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(add_e2 == add_e)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == finalAmount)
    assert(payload_e.expiry == finalExpiry)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentSecret == invoice.paymentSecret)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentMetadata.contains(hex"010203"))
    assert(!payload_e.asInstanceOf[FinalPayload.Standard].isTrampoline)
  }

  test("fail to build outgoing payment with invalid route") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.dropRight(1), None) // route doesn't reach e
    val Left(failure) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    assert(failure == InvalidRouteRecipient(e, d))
  }

  test("fail to build outgoing blinded payment with invalid route") {
    val (_, route, recipient) = longBlindedHops(hex"deadbeef")
    assert(buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max).isRight)
    val routeMissingBlindedHop = route.copy(finalHop_opt = None)
    val Left(failure) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, routeMissingBlindedHop, recipient, Reputation.Score.max)
    assert(failure == MissingBlindedHop(Set(c)))
  }

  test("fail to decrypt when the onion is invalid") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, Reputation.Score.max)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion.copy(payload = payment.cmd.onion.payload.reverse), None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when the trampoline onion is invalid") {
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, Reputation.maxEndorsement, None)
    val Right(RelayToTrampolinePacket(_, _, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid trampoline onion to e through d.
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e.copy(payload = trampolinePacket_e.payload.reverse)))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b, 0.1)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, Reputation.Score.max)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when payment hash doesn't match associated data") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash.reverse, Route(finalAmount, hops, None), recipient, Reputation.Score.max)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when blinded route data is invalid") {
    val (route, recipient) = {
      val features = Features[Bolt12Feature](BasicMultiPartPayment -> Optional)
      val offer = Offer(None, Some("Bolt12 r0cks"), c, features, Block.RegtestGenesisBlock.hash)
      val invoiceRequest = InvoiceRequest(offer, amount_bc, 1, features, randomKey(), Block.RegtestGenesisBlock.hash)
      // We send the wrong blinded payload to the introduction node.
      val tmpBlindedRoute = BlindedRouteCreation.createBlindedRouteFromHops(Seq(channelHopFromUpdate(b, c, channelUpdate_bc)), c, hex"deadbeef", 1 msat, CltvExpiry(500_000)).route
      val blindedRoute = tmpBlindedRoute.copy(blindedHops = tmpBlindedRoute.blindedHops.reverse)
      val paymentInfo = OfferTypes.PaymentInfo(fee_b, 0, channelUpdate_bc.cltvExpiryDelta, 0 msat, amount_bc, ByteVector.empty)
      val invoice = Bolt12Invoice(invoiceRequest, paymentPreimage, priv_c.privateKey, 300 seconds, features, Seq(PaymentBlindedRoute(blindedRoute, paymentInfo)))
      val resolvedPaths = invoice.blindedPaths.map(path => {
        val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
        ResolvedPath(FullBlindedRoute(introductionNodeId, path.route.firstPathKey, path.route.blindedHops), path.paymentInfo)
      })
      val recipient = BlindedRecipient(invoice, resolvedPaths, amount_bc, expiry_bc, Set.empty)
      val route = Route(amount_bc, Seq(channelHopFromUpdate(a, b, channelUpdate_ab)), Some(recipient.blindedHops.head))
      (route, recipient)
    }
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_bc + fee_b)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Left(failure) = decrypt(add_b, priv_b.privateKey, Features(RouteBlinding -> Optional))
    assert(failure.isInstanceOf[InvalidOnionBlinding])
  }

  test("fail to decrypt blinded payment when route blinding is disabled") {
    val (route, recipient) = shortBlindedHops()
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    val add_d = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Left(failure) = decrypt(add_d, priv_d.privateKey, Features.empty) // d doesn't support route blinding
    assert(failure == InvalidOnionPayload(UInt64(10), 0))
  }

  test("fail to decrypt at the final node when amount has been modified by next-to-last node") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.take(1), None)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount - 100.msat, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(payment.cmd.amount - 100.msat))
  }

  test("fail to decrypt at the final node when expiry has been modified by next-to-last node") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.take(1), None)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry - CltvExpiryDelta(12), payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure == FinalIncorrectCltvExpiry(payment.cmd.cltvExpiry - CltvExpiryDelta(12)))
  }

  test("fail to decrypt blinded payment at the final node when expiry is too low") {
    val (route, recipient) = shortBlindedHops()
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    assert(payment.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment.cmd.cltvExpiry == expiry_cd)

    // A smaller expiry is sent to d, who doesn't know that it's invalid.
    // Intermediate nodes can reduce the expiry by at most min_final_expiry_delta.
    val invalidExpiry = payment.cmd.cltvExpiry - Channel.MIN_CLTV_EXPIRY_DELTA - CltvExpiryDelta(1)
    val add_d = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, paymentHash, invalidExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_d.outgoing.contains(channelUpdate_de.shortChannelId))
    assert(relay_d.outgoingCltv < CltvExpiry(currentBlockCount))
    assert(payload_d.isInstanceOf[IntermediatePayload.ChannelRelay.Blinded])
    val pathKey_e = payload_d.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey

    // When e receives a smaller expiry than expected, it rejects the payment.
    val add_e = UpdateAddHtlc(randomBytes32(), 0, relay_d.amountToForward, paymentHash, relay_d.outgoingCltv, packet_e, Some(pathKey_e), Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features(RouteBlinding -> Optional))
    assert(failure.isInstanceOf[InvalidOnionBlinding])
  }

  test("fail to decrypt blinded payment at intermediate node when expiry is too high") {
    val routeExpiry = expiry_de - channelUpdate_de.cltvExpiryDelta
    val (route, recipient) = shortBlindedHops(routeExpiry)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    assert(payment.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment.cmd.cltvExpiry > expiry_de)

    val add_d = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Left(failure) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    assert(failure.isInstanceOf[InvalidOnionBlinding])
  }

  // Create a trampoline payment to e:
  //        .----.
  //       /      \
  // b -> c        e
  //
  // and return the HTLC sent by b to c.
  def createIntermediateTrampolinePayment(): UpdateAddHtlc = {
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures)
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)
    UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, Reputation.maxEndorsement, None)
  }

  test("fail to decrypt at the final trampoline node when amount has been decreased by next-to-last trampoline") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(RelayToTrampolinePacket(_, _, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid amount to e through (the outer total amount doesn't match the inner amount).
    val invalidTotalAmount = inner_c.amountToForward - 1.msat
    val recipient_e = ClearRecipient(e, Features.empty, invalidTotalAmount, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b, 0.1)))), paymentHash, Route(invalidTotalAmount, afterTrampolineChannelHops, None), recipient_e, Reputation.Score.max)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    val add_e = UpdateAddHtlc(randomBytes32(), 4, payload_d.amountToForward(add_d.amountMsat), paymentHash, payload_d.outgoingCltv(add_d.cltvExpiry), packet_e, None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(invalidTotalAmount))
  }

  test("fail to decrypt at the final trampoline node when expiry has been modified by next-to-last trampoline") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(RelayToTrampolinePacket(_, _, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid amount to e through (the outer expiry doesn't match the inner expiry).
    val invalidExpiry = inner_c.outgoingCltv - CltvExpiryDelta(12)
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, invalidExpiry, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b, 0.1)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, Reputation.Score.max)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    val add_e = UpdateAddHtlc(randomBytes32(), 4, payload_d.amountToForward(add_d.amountMsat), paymentHash, payload_d.outgoingCltv(add_d.cltvExpiry), packet_e, None, Reputation.maxEndorsement, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure == FinalIncorrectCltvExpiry(invalidExpiry))
  }

  test("fail to decrypt at intermediate trampoline node when amount is invalid") {
    val add_c = createIntermediateTrampolinePayment()
    // A trampoline relay is very similar to a final node: it can validate that the HTLC amount matches the onion outer amount.
    val Left(failure) = decrypt(add_c.copy(amountMsat = add_c.amountMsat - 100.msat), priv_c.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(add_c.amountMsat - 100.msat))
  }

  test("fail to decrypt at intermediate trampoline node when expiry is invalid") {
    val add_c = createIntermediateTrampolinePayment()
    val invalidAdd = add_c.copy(cltvExpiry = add_c.cltvExpiry - CltvExpiryDelta(12))
    // A trampoline relay is very similar to a final node: it validates that the HTLC expiry matches the onion outer expiry.
    val Left(failure) = decrypt(invalidAdd, priv_c.privateKey, Features.empty)
    assert(failure.isInstanceOf[FinalIncorrectCltvExpiry])
  }

  test("build htlc failure onion") {
    // a -> b -> c -> d -> e
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, Reputation.Score.max)
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    val add_e = UpdateAddHtlc(randomBytes32(), 3, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])

    // e returns a failure
    val failure = IncorrectOrUnknownPaymentDetails(finalAmount, BlockHeight(currentBlockCount))
    val Right(fail_e: UpdateFailHtlc) = buildHtlcFailure(priv_e.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_e.id, FailureReason.LocalFailure(failure), None), add_e)
    assert(fail_e.id == add_e.id)
    val Right(fail_d: UpdateFailHtlc) = buildHtlcFailure(priv_d.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_d.id, FailureReason.EncryptedDownstreamFailure(fail_e.reason, None), None), add_d)
    assert(fail_d.id == add_d.id)
    val Right(fail_c: UpdateFailHtlc) = buildHtlcFailure(priv_c.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_c.id, FailureReason.EncryptedDownstreamFailure(fail_d.reason, None), None), add_c)
    assert(fail_c.id == add_c.id)
    val Right(fail_b: UpdateFailHtlc) = buildHtlcFailure(priv_b.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_b.id, FailureReason.EncryptedDownstreamFailure(fail_c.reason, None), None), add_b)
    assert(fail_b.id == add_b.id)
    val Right(Sphinx.DecryptedFailurePacket(failingNode, decryptedFailure)) = Sphinx.FailurePacket.decrypt(fail_b.reason, fail_b.attribution_opt, payment.sharedSecrets).failure
    assert(failingNode == e)
    assert(decryptedFailure == failure)
  }

  test("build htlc failure onion with attribution data") {
    // a -> b -> c -> d -> e
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, Reputation.Score.max)
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, _, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    val add_e = UpdateAddHtlc(randomBytes32(), 3, amount_de, paymentHash, expiry_de, packet_e, None, Reputation.maxEndorsement, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])

    // e returns a failure
    val failure = IncorrectOrUnknownPaymentDetails(finalAmount, BlockHeight(currentBlockCount))
    val Right(fail_e: UpdateFailHtlc) = buildHtlcFailure(priv_e.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_e.id, FailureReason.LocalFailure(failure), Some(FailureAttributionData(TimestampMilli(672), None))), add_e, now = TimestampMilli(735))
    assert(fail_e.id == add_e.id)
    val Right(fail_d: UpdateFailHtlc) = buildHtlcFailure(priv_d.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_d.id, FailureReason.EncryptedDownstreamFailure(fail_e.reason, fail_e.attribution_opt), Some(FailureAttributionData(TimestampMilli(349), None))), add_d, now = TimestampMilli(844))
    assert(fail_d.id == add_d.id)
    val Right(fail_c: UpdateFailHtlc) = buildHtlcFailure(priv_c.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_c.id, FailureReason.EncryptedDownstreamFailure(fail_d.reason, fail_d.attribution_opt), Some(FailureAttributionData(TimestampMilli(295), None))), add_c, now = TimestampMilli(912))
    assert(fail_c.id == add_c.id)
    val Right(fail_b: UpdateFailHtlc) = buildHtlcFailure(priv_b.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_b.id, FailureReason.EncryptedDownstreamFailure(fail_c.reason, fail_c.attribution_opt), Some(FailureAttributionData(TimestampMilli(0), None))), add_b, now = TimestampMilli(1265))
    assert(fail_b.id == add_b.id)
    val htlcFailure = Sphinx.FailurePacket.decrypt(fail_b.reason, fail_b.attribution_opt, payment.sharedSecrets)
    assert(htlcFailure.holdTimes == Seq(HoldTime(1200 milliseconds, b), HoldTime(600 milliseconds, c), HoldTime(400 milliseconds, d), HoldTime(0 milliseconds, e)))
    val Right(Sphinx.DecryptedFailurePacket(failingNode, decryptedFailure)) = htlcFailure.failure
    assert(failingNode == e)
    assert(decryptedFailure == failure)
  }

  test("build htlc failure onion (blinded payment)") {
    // a -> b -> c -> d -> e, blinded after c
    val (_, route, recipient) = longBlindedHops(hex"0451")
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    val Right(ChannelRelayPacket(_, _, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features(RouteBlinding -> Optional))
    val pathKey_d = payload_c.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey
    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, Some(pathKey_d), Reputation.maxEndorsement, None)
    val Right(ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    val pathKey_e = payload_d.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey
    val add_e = UpdateAddHtlc(randomBytes32(), 3, amount_de, paymentHash, expiry_de, packet_e, Some(pathKey_e), Reputation.maxEndorsement, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_e.isInstanceOf[FinalPayload.Blinded])

    // nodes after the introduction node cannot send `update_fail_htlc` messages
    val Right(fail_e: UpdateFailMalformedHtlc) = buildHtlcFailure(priv_e.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_e.id, FailureReason.LocalFailure(TemporaryNodeFailure()), None), add_e)
    assert(fail_e.id == add_e.id)
    assert(fail_e.onionHash == Sphinx.hash(add_e.onionRoutingPacket))
    assert(fail_e.failureCode == InvalidOnionBlinding(fail_e.onionHash).code)
    val Right(fail_d: UpdateFailMalformedHtlc) = buildHtlcFailure(priv_d.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_d.id, FailureReason.LocalFailure(UnknownNextPeer()), None), add_d)
    assert(fail_d.id == add_d.id)
    assert(fail_d.onionHash == Sphinx.hash(add_d.onionRoutingPacket))
    assert(fail_d.failureCode == InvalidOnionBlinding(fail_d.onionHash).code)
    // only the introduction node is allowed to send an `update_fail_htlc` message
    val failure = InvalidOnionBlinding(Sphinx.hash(add_c.onionRoutingPacket))
    val Right(fail_c: UpdateFailHtlc) = buildHtlcFailure(priv_c.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_c.id, FailureReason.LocalFailure(failure), None), add_c)
    assert(fail_c.id == add_c.id)
    val Right(fail_b: UpdateFailHtlc) = buildHtlcFailure(priv_b.privateKey, useAttributableFailures = false, CMD_FAIL_HTLC(add_b.id, FailureReason.EncryptedDownstreamFailure(fail_c.reason, None), None), add_b)
    assert(fail_b.id == add_b.id)
    val Right(Sphinx.DecryptedFailurePacket(failingNode, decryptedFailure)) = Sphinx.FailurePacket.decrypt(fail_b.reason, fail_b.attribution_opt, payment.sharedSecrets).failure
    assert(failingNode == c)
    assert(decryptedFailure == failure)
  }

}

object PaymentPacketSpec {

  def makeCommitments(channelId: ByteVector32, testAvailableBalanceForSend: MilliSatoshi = 50000000 msat, testAvailableBalanceForReceive: MilliSatoshi = 50000000 msat, testCapacity: Satoshi = 100000 sat, channelFeatures: ChannelFeatures = ChannelFeatures(), announcement_opt: Option[ChannelAnnouncement] = None): Commitments = {
    val channelReserve = testCapacity * 0.01
    val localChannelParams = LocalChannelParams(null, null, Some(channelReserve), isChannelOpener = true, paysCommitTxFees = true, None, None, Features.empty)
    val remoteChannelParams = RemoteChannelParams(randomKey().publicKey, Some(channelReserve), null, null, null, null, null, None)
    val commitParams = CommitParams(546 sat, 1 msat, UInt64.MaxValue, 30, CltvExpiryDelta(720))
    val fundingTx = Transaction(2, Nil, Seq(TxOut(testCapacity, Nil)), 0)
    val localCommit = LocalCommit(0, null, randomTxId(), IndividualSignature(ByteVector64.Zeroes), Nil)
    val remoteCommit = RemoteCommit(0, null, randomTxId(), randomKey().publicKey)
    val localChanges = LocalChanges(Nil, Nil, Nil)
    val remoteChanges = RemoteChanges(Nil, Nil, Nil)
    val localFundingStatus = announcement_opt match {
      case Some(ann) => LocalFundingStatus.ConfirmedFundingTx(Nil, fundingTx.txOut.head, ann.shortChannelId, None, None)
      case None => LocalFundingStatus.SingleFundedUnconfirmedFundingTx(None)
    }
    val channelFlags = ChannelFlags(announceChannel = announcement_opt.nonEmpty)
    val commitmentFormat = DefaultCommitmentFormat
    new Commitments(
      ChannelParams(channelId, ChannelConfig.standard, channelFeatures, localChannelParams, remoteChannelParams, channelFlags),
      CommitmentChanges(localChanges, remoteChanges, 0, 0),
      List(Commitment(0, 0, OutPoint(fundingTx, 0), testCapacity, randomKey().publicKey, localFundingStatus, RemoteFundingStatus.Locked, commitmentFormat, commitParams, localCommit, commitParams, remoteCommit, None)),
      inactive = Nil,
      Right(randomKey().publicKey),
      ShaChain.init,
      Map.empty,
    ) {
      override lazy val availableBalanceForSend: MilliSatoshi = testAvailableBalanceForSend.max(0 msat)
      override lazy val availableBalanceForReceive: MilliSatoshi = testAvailableBalanceForReceive.max(0 msat)
    }
  }

  def randomExtendedPrivateKey(): ExtendedPrivateKey = DeterministicWallet.generate(randomBytes32())

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (TestConstants.Alice.nodeKeyManager.nodeKey, TestConstants.Bob.nodeKeyManager.nodeKey, randomExtendedPrivateKey(), randomExtendedPrivateKey(), randomExtendedPrivateKey())
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

  def buildOutgoingBlindedPaymentAB(paymentHash: ByteVector32, routeExpiry: CltvExpiry = CltvExpiry(500_000)): Either[OutgoingPaymentError, OutgoingPaymentPacket] = {
    val blindedRoute = BlindedRouteCreation.createBlindedRouteFromHops(Nil, b, hex"deadbeef", 1.msat, routeExpiry).route
    val finalPayload = NodePayload(blindedRoute.firstNode.blindedPublicKey, OutgoingBlindedPerHopPayload.createFinalPayload(finalAmount, finalAmount, finalExpiry, blindedRoute.firstNode.encryptedPayload))
    val onion = buildOnion(Seq(finalPayload), paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get // BOLT 2 requires that associatedData == paymentHash
    val cmd = CMD_ADD_HTLC(ActorRef.noSender, finalAmount, paymentHash, finalExpiry, onion.packet, Some(blindedRoute.firstPathKey), Reputation.Score.max, None, TestConstants.emptyOrigin, commit = true)
    Right(OutgoingPaymentPacket(cmd, channelUpdate_ab.shortChannelId, onion.sharedSecrets))
  }

  // route c -> d -> e, blinded after d
  def shortBlindedHops(routeExpiry: CltvExpiry = CltvExpiry(500_000)): (Route, BlindedRecipient) = {
    val (_, blindedHop, recipient) = blindedRouteFromHops(finalAmount, CltvExpiry(currentBlockCount), Seq(channelHopFromUpdate(d, e, channelUpdate_de)), routeExpiry, paymentPreimage)
    (Route(finalAmount, Seq(channelHopFromUpdate(c, d, channelUpdate_cd)), Some(blindedHop)), recipient)
  }

  // route a -> b -> c -> d -> e, blinded after c
  def longBlindedHops(pathId: ByteVector): (Bolt12Invoice, Route, BlindedRecipient) = {
    val hopsToBlind = Seq(
      channelHopFromUpdate(c, d, channelUpdate_cd),
      channelHopFromUpdate(d, e, channelUpdate_de),
    )
    val (invoice, blindedHop, recipient) = blindedRouteFromHops(finalAmount, CltvExpiry(currentBlockCount), hopsToBlind, CltvExpiry(500_000), paymentPreimage, pathId)
    val hops = Seq(
      channelHopFromUpdate(a, b, channelUpdate_ab),
      channelHopFromUpdate(b, c, channelUpdate_bc),
    )
    (invoice, Route(finalAmount, hops, Some(blindedHop)), recipient)
  }

  // simple trampoline route to e:
  //             .----.
  //            /      \
  // a -> b -> c        e

  val trampolineHop = NodeHop(c, e, channelUpdate_cd.cltvExpiryDelta + channelUpdate_de.cltvExpiryDelta, fee_c + fee_d)

  val trampolineChannelHops = Seq(
    channelHopFromUpdate(a, b, channelUpdate_ab),
    channelHopFromUpdate(b, c, channelUpdate_bc)
  )

  val afterTrampolineChannelHops = Seq(
    channelHopFromUpdate(c, d, channelUpdate_cd),
    channelHopFromUpdate(d, e, channelUpdate_de),
  )

}
