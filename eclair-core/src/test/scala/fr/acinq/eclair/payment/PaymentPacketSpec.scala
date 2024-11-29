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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
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
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRouteFromHops, channelHopFromUpdate}
import fr.acinq.eclair.router.BlindedRouteCreation
import fr.acinq.eclair.router.Router.{NodeHop, Route}
import fr.acinq.eclair.transactions.Transactions.InputInfo
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, OutgoingBlindedPerHopPayload}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Bolt11Feature, Bolt12Feature, CltvExpiry, CltvExpiryDelta, EncodedNodeId, FeatureSupport, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TestConstants, TimestampMilli, TimestampSecondLong, UInt64, UnknownFeature, nodeFee, randomBytes32, randomKey}
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, 1.0)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.onion.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)

    // let's peel the onion
    testPeelOnion(payment.cmd.onion)
  }

  def testPeelOnion(packet_b: OnionRoutingPacket): Unit = {
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, packet_b, None, 1.0, None)
    val Right(relay_b@ChannelRelayPacket(add_b2, payload_b, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(add_b2 == add_b)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward == amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoing.contains(channelUpdate_bc.shortChannelId))
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)

    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, 1.0, None)
    val Right(relay_c@ChannelRelayPacket(add_c2, payload_c, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(add_c2 == add_c)
    assert(packet_d.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_c.amountToForward == amount_cd)
    assert(relay_c.outgoingCltv == expiry_cd)
    assert(payload_c.outgoing.contains(channelUpdate_cd.shortChannelId))
    assert(relay_c.relayFeeMsat == fee_c)
    assert(relay_c.expiryDelta == channelUpdate_cd.cltvExpiryDelta)

    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None, 1.0, None)
    val Right(relay_d@ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(packet_e.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_d.amountToForward == amount_de)
    assert(relay_d.outgoingCltv == expiry_de)
    assert(payload_d.outgoing.contains(channelUpdate_de.shortChannelId))
    assert(relay_d.relayFeeMsat == fee_d)
    assert(relay_d.expiryDelta == channelUpdate_de.cltvExpiryDelta)

    val add_e = UpdateAddHtlc(randomBytes32(), 2, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    assert(payment.cmd.amount == finalAmount)
    assert(payment.cmd.cltvExpiry == finalExpiry)
    assert(payment.cmd.paymentHash == paymentHash)
    assert(payment.cmd.onion.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32(), 0, finalAmount, paymentHash, finalExpiry, payment.cmd.onion, None, 1.0, None)
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)

    // let's peel the onion
    val add_b = UpdateAddHtlc(randomBytes32(), 0, finalAmount + 100.msat, paymentHash, finalExpiry + CltvExpiryDelta(6), payment.cmd.onion, None, 1.0, None)
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount >= amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.nextPathKey_opt.isEmpty)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward >= amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoing.contains(channelUpdate_bc.shortChannelId))
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)
    assert(payload_b.isInstanceOf[IntermediatePayload.ChannelRelay.Standard])

    val add_c = UpdateAddHtlc(randomBytes32(), 1, relay_b.amountToForward, relay_b.add.paymentHash, relay_b.outgoingCltv, packet_c, None, 1.0, None)
    val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features(RouteBlinding -> Optional))
    assert(packet_d.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_c.amountToForward >= amount_cd)
    assert(relay_c.outgoingCltv == expiry_cd)
    assert(payload_c.outgoing.contains(channelUpdate_cd.shortChannelId))
    assert(relay_c.relayFeeMsat == fee_c)
    assert(relay_c.expiryDelta == channelUpdate_cd.cltvExpiryDelta)
    assert(payload_c.isInstanceOf[IntermediatePayload.ChannelRelay.Blinded])
    val pathKey_d = payload_c.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey

    val add_d = UpdateAddHtlc(randomBytes32(), 2, relay_c.amountToForward, relay_c.add.paymentHash, relay_c.outgoingCltv, packet_d, Some(pathKey_d), 1.0, None)
    val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    assert(packet_e.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_d.amountToForward >= amount_de)
    assert(relay_d.outgoingCltv == expiry_de)
    assert(payload_d.outgoing.contains(channelUpdate_de.shortChannelId))
    assert(relay_d.relayFeeMsat == fee_d)
    assert(relay_d.expiryDelta == channelUpdate_de.cltvExpiryDelta)
    assert(payload_d.isInstanceOf[IntermediatePayload.ChannelRelay.Blinded])
    val pathKey_e = payload_d.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey

    val add_e = UpdateAddHtlc(randomBytes32(), 2, relay_d.amountToForward, relay_d.add.paymentHash, relay_d.outgoingCltv, packet_e, Some(pathKey_e), 1.0, None)
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
    val paymentInfo = PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 1 msat, amount_bc, Features.empty)
    val invoice = Bolt12Invoice(invoiceRequest, paymentPreimage, recipientKey, 300 seconds, features, Seq(PaymentBlindedRoute(blindedRoute, paymentInfo)))
    val resolvedPaths = invoice.blindedPaths.map(path => {
      val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
      ResolvedPath(FullBlindedRoute(introductionNodeId, path.route.firstPathKey, path.route.blindedHops), path.paymentInfo)
    })
    val recipient = BlindedRecipient(invoice, resolvedPaths, amount_bc, expiry_bc, Set.empty)
    val hops = Seq(channelHopFromUpdate(a, b, channelUpdate_ab), channelHopFromUpdate(b, c, channelUpdate_bc))
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(amount_bc, hops, Some(recipient.blindedHops.head)), recipient, 1.0)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_ab)
    assert(payment.cmd.cltvExpiry == expiry_ab)
    assert(payment.cmd.nextPathKey_opt.isEmpty)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    assert(packet_c.payload.length == PaymentOnionCodecs.paymentOnionPayloadLength)
    assert(relay_b.amountToForward >= amount_bc)
    assert(relay_b.outgoingCltv == expiry_bc)
    assert(payload_b.outgoing.contains(channelUpdate_bc.shortChannelId))
    assert(relay_b.relayFeeMsat == fee_b)
    assert(relay_b.expiryDelta == channelUpdate_bc.cltvExpiryDelta)
    assert(payload_b.isInstanceOf[IntermediatePayload.ChannelRelay.Standard])

    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, 1.0, None)
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

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
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

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount + 100.msat, payment.cmd.paymentHash, payment.cmd.cltvExpiry + CltvExpiryDelta(6), payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Right(FinalPacket(_, payload_b, _)) = decrypt(add_b, priv_b.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_b.amount == finalAmount)
    assert(payload_b.totalAmount == finalAmount)
  }

  private def testRelayTrampolinePayment(invoice: Bolt11Invoice, payment: TrampolinePayment.OutgoingPayment): Unit = {
    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, 1.0, None)
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
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, 1.0)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
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
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, Features.TrampolinePayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)
    testRelayTrampolinePayment(invoice, payment)
  }

  test("build outgoing trampoline payment without MPP") {
    // simple trampoline route to e, but we don't include a payment_secret in the outer payload for c:
    //        .----.
    //       /      \
    // b -> c        e
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, Features.TrampolinePayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    // Note that we don't include a payment_secret for the trampoline node, since the spec allows omitting it when not using MPP to reach the trampoline node.
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalAmount, finalExpiry, trampolinePaymentSecret_opt = None, attemptNumber = 0)
    testRelayTrampolinePayment(invoice, payment)
  }

  test("build legacy outgoing trampoline payment") {
    // simple trampoline route to e between legacy wallets:
    //        .----.
    //       /      \
    // b -> c        e
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, Features.TrampolinePayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val trampolineOnion = {
      val finalPayload = PaymentOnion.FinalPayload.Standard.createPayload(finalAmount, finalAmount, finalExpiry, invoice.paymentSecret, invoice.paymentMetadata)
      val trampolinePayload = PaymentOnion.IntermediatePayload.NodeRelay.Standard(finalAmount, finalExpiry, invoice.nodeId)
      buildOnion(NodePayload(c, trampolinePayload) :: NodePayload(invoice.nodeId, finalPayload) :: Nil, invoice.paymentHash, None).toOption.get
    }
    val payment = {
      val trampolineAmount = finalAmount * 1.005 // 0.5% fees
      val trampolineExpiry = finalExpiry + CltvExpiryDelta(72)
      val payload = PaymentOnion.FinalPayload.Standard.createLegacyTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, randomBytes32(), trampolineOnion.packet)
      val paymentOnion = buildOnion(NodePayload(c, payload) :: Nil, invoice.paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get
      TrampolinePayment.OutgoingPayment(trampolineAmount, trampolineExpiry, paymentOnion, trampolineOnion)
    }

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, 1.0, None)
    val Right(RelayToTrampolinePacket(add_c2, outer_c, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(add_c2 == add_c)
    assert(outer_c.amount == payment.trampolineAmount)
    assert(outer_c.totalAmount == payment.trampolineAmount)
    assert(outer_c.expiry == payment.trampolineExpiry)
    assert(outer_c.records.get[OnionPaymentPayloadTlv.LegacyTrampolineOnion].nonEmpty)
    assert(inner_c.amountToForward == finalAmount)
    assert(inner_c.outgoingCltv == finalExpiry)
    assert(inner_c.outgoingNodeId == e)

    // c forwards the trampoline payment to e through d using a legacy trampoline onion.
    val recipient_e = ClearRecipient(e, Features(Map.empty[InvoiceFeature, FeatureSupport], Set(UnknownFeature(149))), inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, 1.0)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
    val Right(FinalPacket(add_e2, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(add_e2 == add_e)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == finalAmount)
    assert(payload_e.expiry == finalExpiry)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)
    assert(payload_e.asInstanceOf[FinalPayload.Standard].paymentMetadata.contains(hex"010203"))
    assert(payload_e.asInstanceOf[FinalPayload.Standard].isTrampoline)
  }

  // See bolt04/trampoline-payment-onion-test.json
  test("build outgoing trampoline payment (reference test vector)") {
    //                    .-> Dave -.
    //                   /           \
    // Alice -> Bob -> Carol         Eve
    val paymentHash = ByteVector32.fromValidHex("4242424242424242424242424242424242424242424242424242424242424242")

    // Alice creates a trampoline onion Carol -> Eve.
    val trampolineOnionForCarol = {
      val paymentSecret = ByteVector32.fromValidHex("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a")
      val carol = PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
      val eve = PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
      val payloads = Seq(
        NodePayload(carol, PaymentOnion.IntermediatePayload.NodeRelay.Standard(100_000_000 msat, CltvExpiry(800_000), eve)),
        NodePayload(eve, PaymentOnion.FinalPayload.Standard.createPayload(100_000_000 msat, 100_000_000 msat, CltvExpiry(800_000), paymentSecret)),
      )
      val sessionKey = PrivateKey(hex"0303030303030303030303030303030303030303030303030303030303030303")
      val trampolineOnionForCarol = OutgoingPaymentPacket.buildOnion(sessionKey, payloads, paymentHash, packetPayloadLength_opt = None).toOption.get.packet
      val encoded = OnionRoutingCodecs.onionRoutingPacketCodec(trampolineOnionForCarol.payload.length.toInt).encode(trampolineOnionForCarol).require.bytes
      assert(encoded == hex"0002531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe3371860c0749bfd613056cfc5718beecc25a2f255fc7abbea3cd75ff820e9d30807d19b30f33626452fa54bb2d822e918558ed3e6714deb3f9a2a10895e7553c6f088c9a852043530dbc9abcc486030894364b205f5de60171b451ff462664ebce23b672579bf2a444ebfe0a81875c26d2fa16d426795b9b02ccbc4bdf909c583f0c2ebe9136510645917153ecb05181ca0c1b207824578ee841804a148f4c3df7306dcea52d94222907c9187bc31c0880fc084f0d88716e195c0abe7672d15217623")
      trampolineOnionForCarol
    }

    // Alice wraps it into a payment onion Alice -> Bob -> Carol.
    val onionForBob = {
      val paymentSecret = ByteVector32.fromValidHex("2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b")
      val bob = PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
      val carol = PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
      val payloads = Seq(
        NodePayload(bob, PaymentOnion.IntermediatePayload.ChannelRelay.Standard(ShortChannelId.fromCoordinates("572330x7x1105").get, 100_005_000 msat, CltvExpiry(800_250))),
        NodePayload(carol, PaymentOnion.FinalPayload.Standard.createTrampolinePayload(100_005_000 msat, 100_005_000 msat, CltvExpiry(800_250), paymentSecret, trampolineOnionForCarol, None)),
      )
      val sessionKey = PrivateKey(hex"0404040404040404040404040404040404040404040404040404040404040404")
      val onionForBob = OutgoingPaymentPacket.buildOnion(sessionKey, payloads, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForBob).require.bytes
      assert(encoded == hex"0003462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b9149ce01cce1709194109ab594037113e897ab6120025c770527dd8537997e2528082b984fe078a5667978a573abeaf7977d9b8b6ee4f124d3352f7eea52cc66c0e76b8f6d7a25d4501a04ae190b17baff8e6378b36f165815f714559dfef275278eba897f5f229be70fc8a1980cf859d1c25fe90c77f006419770e19d29ba80be8f613d039dd05600734e0d1e218af441fe30877e717a26b7b37c2c071d62bf6d61dd17f7abfb81546d2c722c9a6dc581aa97fb6f3b513e5fbaf0d669fbf0714b2b016a0a8e356d55f267fa144f7501792f2a59269c5a22e555a914e2eb71eba5af519564f246cf58983ea3fa2674e3ab7d9969d8dffbb2bda2b2752657417937d46601eb8ebf1837221d4bdf55a4d6a97ecffde5a09bd409717fa19e440e55d775890aed89f72e65af515757e94a9b501e6bad048af55e1583adb2960a84f60fb5efd0352e77a34045fc6b221498f62810bd8294d995d9f513696f8633f29faaa9668d0c6fa0d0dd7fa13e2c185572485762bd2810dc12187f521fbefa9c320762ac1e107f7988d81c6ee201ab68a95d45d578027e271b6526154317877037dca17134ccd955a22a8481b8e1996d896fc4bf006154ed18ef279d4f255e3f1233d037aea2560011069a0ae56d6bfdd8327054ded12d85d120b8982bff970986db333baae7c95f85677726a8f74cc8bd1e5aca3d433c113048305ecce8e35caf0485a53df00284b52b42291a9ffe686b96442135b3107d8856bc652d674ee9a148e79b02c9972d3ca8c2b02609f3b358c4a67c540ba6769c4d83169bceda640b1d18b74d12b6df605b417dacf6f82d79d43bb40920898f818dc8344c036ae9c8bbf9ef52ea1ccf225c8825a4d8503df190b999e15a4be34c9d7bbf60d3b93bb7d6559f4a5916f5e40c3defeeca9337ccd1280e46d6727c5c91c2d898b685543d4ca7cfee23981323c43260b6387e7febb0fffb200a8c974ef36b3253d0fb9fe0c1c6017f2dbbdc169f3f061d9781521e8118164aeec31c3e59c199016f1025c295d8f7bdeb627d357105a2708c4c3a856b9e83ff37ed69f59f2d2e464ed1db5882925ebe2493a7ddb707e1a308fa445172a24b3ea60732f75f5c69b41fc11467ee93f37c9a6f7285ba42f716e2a0e30909056ea3e4f7985d14ca9ab280cc184ce98e2a0722d0447aa1a2eedc5e53ddfa53731df7eced406b10627b0bebd768a30bde0d470c0f1d10adc070f8d3029cacceec74e4833f4dc8c52c3f41733f5f896fceb425d0737e717a63bfb033df46286d99594dd01e2bd0a942ab792874177b32842f4833bc0340ddb74852e9cd6f29f1d997a4a4bf05dd5d12011f95e6ce18928e3a9b83b24d15f989bdf43370bcc657c3ac6601eaf5e951efdbd7ee69b1623dc5039b2dfc640692378ef032f17bc36cc00293ad90b7e18f5feb8f287a7061ed9713929aed9b14b8d566199fc7822b1c38daa16b6d83077b10af0e2b6e531ccc34ea248ea593128c9ff17defcee6618c29cd2d93cfed99b90319104b1fdcfea91e98b41d792782840fb7b25280d8565b0bcd874e79b1b323139e7fc88eb5f80f690ce30fcd81111076adb31de6aeced879b538c0b5f2b74c027cc582a540133952cb021424510312f13e15d403f700f3e15b41d677c5a1e7c4e692c5880cb4522c48e993381996a29615d2956781509cd74aec6a3c73b8536d1817e473dad4cbb1787e046606b692a44e5d21ef6b5219658b002f674367e90a2b610924e9ac543362257d4567728f2e61f61231cb5d7816e100bb6f6bd9a42329b728b18d7a696711650c16fd476e2f471f38af0f6b00d45c6e1fa492cc7962814953ab6ad1ce3d3f3dc950e64d18a8fdce6aabc14321576f06")
      onionForBob
    }

    // Bob decrypts the payment onion and forwards it to Carol.
    val onionForCarol = {
      val priv = PrivateKey(hex"4242424242424242424242424242424242424242424242424242424242424242")
      val add = UpdateAddHtlc(randomBytes32(), 1, 100_006_000 msat, paymentHash, CltvExpiry(800_300), onionForBob, None, 1.0, None)
      val Right(ChannelRelayPacket(_, _, onionForCarol, _)) = decrypt(add, priv, Features.empty)
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForCarol).require.bytes
      assert(encoded == hex"00036d7fa1507cac3a1fda3308b465b71d817a2ee8dfde598c6bdb1dec73c7acf0165136fdd0fd9f3f7eac074f42b015825614214ac3b7ec95234538c9cfd04fc1a5128fa47c8d56e21e51bb843da8252c0abafb72395cf6ca8a186bd1de72341cb0f988e79988c39e4d444a4495120ccf3577576177a45c2a0fdc88776291d3af9e62d700c06206c769260859715ba5e1e7c0dc5f97dbf80decb564c885d0d6f0e10bddb225ee3d82a1e02b6a3735ea81ab91dada382a5752a940814e38c709e62d3427d69bfd09a19955c507aea300bf10578e3bda3d632a5de159f3fc0ff9311b2fc5d4a6c03582c4cd85c92d29bc285971f1019cb468942a7d3706e096f6ab105e7d8d525586a4f7987135af70d166317dc2b5b6c58345c54e87615d277e7ade5f0b9f8baed5f16e1b340492c4fa6b443f94544a4f083e4dfb778badf1084c0c39e998cd67ff5f1a6526fb163cfd48e04ff34d928a91f061781463b9f668a0d084e6c5bb80413968ee3185abd545b38f63f496d9fa16e67d84c08414df8c03d0efb1925edcdd14a4134424f65372166be4a8e66906a428eb726ae43ea6cf81256f082382e18b765e78cd21819045b5bcc5f4464b812215f8838acf73c5a4748a09ee10b6bcec9c201dc38ef009b23b9072d653c81316a59b36533732f4c4eeb29863bcf420155aa90378a111f0393599fb9dd42f69808c3552654b7352a6a1e2a71db0a0214c8d9021ef52d667da4d351a9a44a0cdbff34894d1994e7cced665061b6979f9e508d98ac9b2193f01694597e8189122daf0bd3c82743f5994678b4efb309028be23987bc18720388bc78be39b02276a0f3577390e36a5cd0dbab97b08a5c7f45a5a952681a2669e653004977b2a1e098a5bfee2ee927c2f51fc9dc66af120b5a40b01738c5db1e091f7141096e3c4d5905a695f02c852fd40412c7288c15befb522eec41232899863c17f66cbfefb3597c346fc7483a03d0f3f2dcaa6ae56d508d0df9298d80b2bcfcb91b30b298ca2415a3cbc8284bb2f4a5cfc244efe2d78a446d36d350bebd7ff30d70a2015679f1a3a63d841e2333fa30ebabf9d84576616f3c93a78a42948d991e1c628c3dbb3ad856fe97f9a3ce3d2b7e8e3ff2e1c3b2eb494dd9c947878120a8912afda70ca7d7829b9011f13c848d10e69274f4bd918c4c5531c8382e5f1c0b72ecabbd34d14190cde1a3247e4473c8016a122077f4a9cddf21c11680c2c25c342dacc7676304dd2466b47a172641e33de3cf9c2f476f57e0a90cdb7f8398dc012fd65df9a685a73b8f6f02a2ba3045e0cb308a72645370c827ac43da67f614e2d68b7811805b8144e6f21e94b679003486aa79bad22db09735d72e5a32c5831c3e44c9100322ae70c74df52ba98653624361b62d9500b704450e6e23d3373aae9697fed5e6133d1d1677608be513344590fd72569c6e19c070d303e8aa6f7196e7ac0f4039912cf1e9f050c6927340f9a96de229adbe7906072bc87c2214dc476d8dc7d81f2cb56d5a7407fe9fb378703f04fe19f1bb4b8f938c84072a4ac0b18de581b4b8b5971ce411cc82a0484764a6df49f8ffc3c858a299b4ffc9f96b933bd12f4fc876b6ce34f7c022ded91d51a03c5f14c29a9f7b28e45395782d74a3d795ac596b44ba36f805d62e3ba7976f10904784af7f5994cc57817979a0adf87e3b3e32047f0a4d68c2609c9405612b264094c49dd27836f4bdab4d68256b2b4d8e10411ff166065265fdd0c04c6c3ad989530f258b9549128765f0cc6af5e50cf15d3fd856e91580bf66a7ebce267726aee798b580df6deaee59fa90c5a35e06f36d4960c326d0418adcbe3ff4248bf04dc24a3758de2c58f97fd9e4333beae43428d184e3872ad52d2b4dd4d770da0dca339bf70a6b22dd05cf8547ec0a7a8d49543")
      onionForCarol
    }

    // Carol decrypts the payment onion and the inner trampoline onion.
    val trampolineOnionForEve = {
      val priv = PrivateKey(hex"4343434343434343434343434343434343434343434343434343434343434343")
      val eve = PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
      val add = UpdateAddHtlc(randomBytes32(), 1, 100_005_000 msat, paymentHash, CltvExpiry(800_250), onionForCarol, None, 1.0, None)
      val Right(RelayToTrampolinePacket(_, outer_c, inner_c, trampolineOnionForEve, _)) = decrypt(add, priv, Features.empty)
      assert(outer_c.amount == 100_005_000.msat)
      assert(outer_c.expiry == CltvExpiry(800_250))
      assert(inner_c.amountToForward == 100_000_000.msat)
      assert(inner_c.outgoingCltv == CltvExpiry(800_000))
      assert(inner_c.outgoingNodeId == eve)
      val encoded = OnionRoutingCodecs.onionRoutingPacketCodec(trampolineOnionForEve.payload.length.toInt).encode(trampolineOnionForEve).require.bytes
      assert(encoded == hex"00035e5c85814fdb522b4efeef99b44fe8a5d3d3412057bc213b98d6f605edb022c2ae4a9141f6ac403790afeed975061f024e2723d485f9cb35a3eaf881732f468dc19009bf195b561590798fb895b7b7065b5537018dec330e509e8618700c9c6e1df5d15b900ac3c34104b6abb1099fd2eca3b640d7d5fda9370e20c09035168fc64d954baa80361b965314c400da2d7a64d0536bf9e494aebb80aec358327a4a1a667fcff1daf241c99dd8c4fa907de5b931fb9daed083c157f5ea1dd960d142952f8ebe4e1ccaee4d565a093e2b91f94b04a884ce2e8c60aced3565e8d2d10de5")
      trampolineOnionForEve
    }

    // Carol wraps the trampoline onion for Eve into a payment Carol -> Dave -> Eve.
    val onionForDave = {
      val paymentSecret = ByteVector32.fromValidHex("2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c")
      val dave = PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      val eve = PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
      val payloads = Seq(
        NodePayload(dave, PaymentOnion.IntermediatePayload.ChannelRelay.Standard(ShortChannelId.fromCoordinates("572330x42x1729").get, 100_000_000 msat, CltvExpiry(800_000))),
        NodePayload(eve, PaymentOnion.FinalPayload.Standard.createTrampolinePayload(100_000_000 msat, 100_000_000 msat, CltvExpiry(800_000), paymentSecret, trampolineOnionForEve, None)),
      )
      val sessionKey = PrivateKey(hex"0505050505050505050505050505050505050505050505050505050505050505")
      val onionForDave = OutgoingPaymentPacket.buildOnion(sessionKey, payloads, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForDave).require.bytes
      assert(encoded == hex"000362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f74125c590a7877d17303ddcdce79b0b33e006eaf4eff557631c70c7ab9a61105ffd738239016e84c2639ff5246f3d2167ea7ea7932138435a9cc427f7f9d838daa2b7f4c3bfb8c44e8e48d2fd744c1c5a7626d188b5690d36900eb0a498cd0b4139424bc1b65d74409a72fca8e36f239f4c80644963e80391ca1c707f727e3dc9656de66bfdf77823b0b5746c55c31978faffd65937b2c526478e4f30d08cc371fb9d045f65316af2d416c9a82ac412db84e4386901877670c8a2fcdd1b2f3276c5384f2feb23d4c62788cce78edc1194bf4fbd2af5670d2917cc940c41897fea944ebf908a1a90a1bd208b42209ccf2d480d2590bfce320ce185f12e77703f906e98b1a9ff701490b792a60faba11d75d691c2cecf867bb63062ec8c3bd1c2665dbd380e59cdffbfd028e5c86a1371fd3d5141e50986247a9f21143df0d1099e6df7e2044f1f4a87bc759cb7c2354616e39ace2d06165a580206ae9c5bc5005a6654215e7ab1bb619eb2df5bc11e3a8cfbc0a9c7e515c0f6d9d02512ef856d4782e54192ea63a173b4fcf02a11e85d2da6de47a6f8dd9bbfb30dccecd5e2195d5c9b0bf0bfc8b571b4962deacba4669afa017294b45e2668ad87168b9589f00f56275022f049f0cdece8c9e1f0f35035aa1af4a70103a7e8aa2b7a6579accf554c6a4f305981f5732036894765e086c167f5f342f313e4617da53b79303c72e0a6f03c3f592cb9c035c509c02dc09e5ea20b158a3f47b1722db86d354f7dfccbdaf6be21c7f473e143b459b2b06a21984f29ba80dfcd52696c76fb2a11f66383e33d88226f451317125fcfa02671015c359db52ee1462b1b820588d5c874765de4e7cc83b84dde8630b2a21325116cf53fd1eb369bed1330dfcbe0633698c518a376312624d78011922621e32e9b316a9329c3d1f967069d35844e60caf53e7a2bbbe695808de2e91dc16a9dd408ab2a8c363f2a5c34124f9c79010db4706e1315e1ff230741a9ab7e069318db587004bd0ccb71aad37c616b276bc0fe883865ba730b4d86ce2ae710185747d0860e00bb37b97fe71d79492a2e9a3bc07e5693f92de886fab3802ac62a8a4adcbf041eec05152cd28fd77154799e99674c8ea571519186ad9eb84a26edcef86473621e05515f3278810f931c662d037d9da73612c2f9d7d64e573595c402e9166299cbe356119ca38a3c6da77d6f864d61062b4300e388b631f60c25cb364b76561b4064c13e9e25d1ecb491472047157ea04fbbf6ccfe36cb2c030250b0335ae00255cf3670a61a5f207d72fccaac0b36a74d041f62341bc3759cd17d6e1c81aafcbbdc0f29906e54bc66dc1217031f881c9782eabe09de6835cdf4426113fb28e3bc0a73b007521c9a5abdc4a602c3c3358f0d3d81c8d84da5cc8acf1d15c9dd038ca64229097c666099a701b47bcf3a35e2541d4554a7bc1e3d4693b031c35f33b063d339558911870dd8bc3a52895612bee20ea8a7b0110da64362a357a4f9dbd7ff01155278c1173c57dd3d1b0947e58b571673544dbeff1c19cdb0ab9901671b3d043c4173fbcdf8e9cb03585bb9987414080046b6f283fc7c3aa245152941138636cd1b201e59080b8a7257bc2d7046c18d738c64804b088ac0983fbaeb92624f3ddc175afa3afc85cc8d83815bea41a195e883a4044a6406dbcb67682fc0522d2c920bc1d2372e95ea31408fcbe53e91c787e6da85255c40d0c9dbb0d4a5ded5886c90664bec4396f94782851fcd8565562a9df646025ad224078add8a05b8614ad0ce33141213a4650590ebaef22ef10b9cca5c4025eeaf58796baf052824d239586d7c706431fa1240f36a4f882d36ca608ece021b803386356f13a22bf3f42ef39d")
      onionForDave
    }

    // Dave decrypts the payment onion and forwards it to Eve.
    val onionForEve = {
      val priv = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
      val add = UpdateAddHtlc(randomBytes32(), 1, 100_001_000 msat, paymentHash, CltvExpiry(800_100), onionForDave, None, 1.0, None)
      val Right(ChannelRelayPacket(_, _, onionForEve, _)) = decrypt(add, priv, Features.empty)
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForEve).require.bytes
      assert(encoded == hex"00037d517980f2321ce95c8ecea4aebceb2f62ebbbac598973439c79e9f66d28ce5c728d3226c796b85df07009baec8b4e46d73bf6bbf6f8dfa8bcf610bda5de6ebaf395b5a8572e30e91e402688834a13db55d04c28dc1bfdcc07c602532330ee6ce1bd6acce875c81fd53b8f7f4243ed940bb5e4897252763968a00c2b59d6cbfdf73bd86e4b9135a63dcf99612da557962da6b525c68b3159e4f56cd49cf654ca6240ec5a0c2365731266eb4263e16c90aed2fbd662de9aa22ce7bf8af18687d99550e48477c6c46f8a84957d24ac323381e69b57342d82e06082c645fcd96eb77ed4f563f04e7e7913e4bac16a78e56d223baead194b4cd80c97fa7d892d0288780ac90f1020e0cb43e267721bbbdd6fb759da9df2744882f4259a4e5bab60aaca2847311122c1d60a483c978d7b3042ae189892f85e1e7e3ad89d48769404b5dea1ddf1794b3c6b002286995e976b1de9c2457895a00952a06983986a619863e4c60f17e40a210e89273fa7f55ebd83887d451b5e658b9092e81540de49a4e4a05a757aa103ca5dc63194094869f067d5cc36e2d59de9d038f3b5a4b6fa4fd5b276db7b77182ddc96eaf53bfbfa2f988b785643047a5639965dde3baafeac2db4efbdf04da3520766c012c988d64c9e3aa2f723baa4926e413b18b93bdeec4e0761ef55bedea1de8751b49cb8a67a15ddeae511e06f03d36c2158aba897997c53a2f12e2db98214b093c0ee3c5fe6763dc7a3418db28a571a88da50a851eac78f78c29c489d6a09751976f4e456ffa23b71b3894e9263476d490e842de6a41fd085bf218691b1de3b4cf077f560fc86dd7f8d24c06912e5b9d53fc7b36d3f5bcde6cb9f22d5db09c0ec4e870466d0549f5fcd0e6849aa925f3f238b1a613c022ea22dc308899330113b60576b7fc8904233a77cf24ad2f9482cdc1265f6e74353d92d4fbff4a0a42dfebb92ac71c7fc2c79ccd1b187bd4542ed2d1808735179bebaba664f49a75d2823f7e7041e1cc0f717899b7eb2c2b9550be185f1a0b2245a48fdc205c5339742ad14e370193158997f4d4edff05297a4668705f667b2a858a0b8af56aa4b93fb41b30e16a50a75fdc0ce33dd94da254d8b1e55c40aa49444aacf4796a6979f0feca13924ff3a886d3e859e51d2d585ee919abcc82c396da1df7a4e97f415a01c25c1a5ae2fe65f4cc385e16d91e54836e12e7588d89ee41dfef277b97eb7d6c6ebfc6ed4f89b13d776904fad6e405123bca86068dc558dbbc284c65947f295b8828e7e35e80fd0981ba46229d47e646afa73f55070ae4a202b8c46719e6449632a4377eedbe83a69d1f422e73bc159172e631165dc5fe63e09dcace38218de2598204127255535d6c2197003383195af636cfd8615bd8a5db96057fe4ee67156685351d90c3db5bf61d3e573877572f58f982d9fbb35bd678143ccc1f2cccf1fd34c20e0a59b4c837540fac3964068eec3ffb8981a2ab774c542f74168ccd7fa9b08141cd0bda0d99ecee10a3857818370456c3c00d3f7b514f30ff6c31f11147851c8438411de3fc71719fbf79df3cab963231732c95850d59df90144161c2ef84a8b1c76a9494b8dd7234782bc61a6fc23222599a14163f78e117c99f33b3d2a4b11339903b41e7cfc253f1319d4c3ab1dc3d31a503c0bf9c233cb9216201d71abf915b8e50c0612b1fdba8ea8f248767256597151ba2f58dd67d470f8cfdfdc0bffceba618587f652a2155c58717a85e1eff38149b521f99449b35ed2a5ecb474fe60257d261017386ae08ea61cf907ebb7d2d5b1a55e50088449563d1d788d8b4f18ee57e24c6cab40dcd569495c6ea13fa1ca68dbeb6fed7462444ca94b6561471b4e1a75945d7327e5e56348bbd5cae106bf74976cc9288394a731b3555401e59c2718001171b6d6")
      onionForEve
    }

    // Eve decrypts the payment onion and the inner trampoline onion.
    val priv = PrivateKey(hex"4545454545454545454545454545454545454545454545454545454545454545")
    val add = UpdateAddHtlc(randomBytes32(), 1, 100_000_000 msat, paymentHash, CltvExpiry(800_000), onionForEve, None, 1.0, None)
    val Right(FinalPacket(_, payloadForEve, _)) = decrypt(add, priv, Features.empty)
    assert(payloadForEve.isInstanceOf[FinalPayload.Standard])
    assert(payloadForEve.amount == 100_000_000.msat)
    assert(payloadForEve.expiry == CltvExpiry(800_000))
    assert(payloadForEve.asInstanceOf[FinalPayload.Standard].paymentSecret == ByteVector32.fromValidHex("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"))
    assert(payloadForEve.asInstanceOf[FinalPayload.Standard].isTrampoline)
  }

  // See bolt04/trampoline-to-blinded-path-payment-onion-test.json
  test("build outgoing trampoline payment to blinded recipient (reference test vector)") {
    val preimage = ByteVector32.fromValidHex("8bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c057")
    val paymentHash = Crypto.sha256(preimage)
    val evePriv = PrivateKey(hex"4545454545454545454545454545454545454545454545454545454545454545")
    val eve = evePriv.publicKey

    // Eve creates a blinded path to herself going through Dave.
    val pathId = hex"01caa1dcc994683479a217fb32ac30e9c2b6f7960121ca169ae732477e10349b560000000008f0d1808bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c05702414343fd4a723942a86d5f60d2cfecb6c5e8a65595c9995332ec2dba8fe004a20000000000000001000000000000000068656c6c6f188d0b54e9a7df32f0e00c62ace869bac50e0e9c77bcf4c9850bae33d5e48ada65267a3d56111e3c3d84366f3cd53d998256929b0440355dff20455396fe6aac"
    val path = {
      val dave = PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      val blindedPayloadEve = TlvStream[RouteBlindingEncryptedDataTlv](RouteBlindingEncryptedDataTlv.PathId(pathId))
      val blindedPayloadDave = TlvStream[RouteBlindingEncryptedDataTlv](
        RouteBlindingEncryptedDataTlv.OutgoingNodeId(EncodedNodeId(eve)),
        RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(36), 1000, 500 msat),
        RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(850_000), 1 msat),
      )
      assert(RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(blindedPayloadDave).require.bytes == hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 0a080024000003e801f4 0c05000cf85001")
      assert(RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(blindedPayloadEve).require.bytes == hex"06 bf 01caa1dcc994683479a217fb32ac30e9c2b6f7960121ca169ae732477e10349b560000000008f0d1808bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c05702414343fd4a723942a86d5f60d2cfecb6c5e8a65595c9995332ec2dba8fe004a20000000000000001000000000000000068656c6c6f188d0b54e9a7df32f0e00c62ace869bac50e0e9c77bcf4c9850bae33d5e48ada65267a3d56111e3c3d84366f3cd53d998256929b0440355dff20455396fe6aac")
      val sessionKey = PrivateKey(hex"090a684b173ac8da6716859095a779208943cf88680c38c249d3e8831e2caf7e")
      val blindedRouteDetails = Sphinx.RouteBlinding.create(sessionKey, Seq(dave, eve), Seq(blindedPayloadDave, blindedPayloadEve).map { p =>
        RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(p).require.bytes
      })
      assert(EncodedNodeId(dave) == blindedRouteDetails.route.firstNodeId)
      assert(PublicKey(hex"02c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7") == blindedRouteDetails.lastPathKey)
      assert(PublicKey(hex"02988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e") == blindedRouteDetails.route.firstPathKey)
      val blindedNodes = Seq(
        PublicKey(hex"0295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be"),
        PublicKey(hex"020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"),
      )
      assert(blindedNodes == blindedRouteDetails.route.blindedNodeIds)
      val encryptedPayloads = Seq(
        hex"0ccf3c8a58deaa603f657ee2a5ed9d604eb5c8ca1e5f801989afa8f3ea6d789bbdde2c7e7a1ef9ca8c38d2c54760febad8446d3f273ddb537569ef56613846ccd3aba78a",
        hex"bcd747394fbd4d99588da075a623316e15a576df5bc785cccc7cd6ec7b398acce6faf520175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d5f00716baf9fc9b3de50bc22950a36bda8fc27bfb1242e5860c7e687438d4133e058770361a19b6c271a2a07788d34dccc27e39b9829b061a4d960eac4a2c2b0f4de506c24f9af3868c0aff6dda27281c",
      )
      assert(encryptedPayloads == blindedRouteDetails.route.encryptedPayloads)
      val paymentInfo = OfferTypes.PaymentInfo(500 msat, 1000, CltvExpiryDelta(36), 1 msat, 500_000_000 msat, Features.empty)
      PaymentBlindedRoute(blindedRouteDetails.route, paymentInfo)
    }

    // Alice creates a trampoline onion using Eve's blinded path and starting at Carol (Carol -> Dave -> Eve).
    val trampolineOnion = {
      val carol = PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
      val dave = PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      assert(EncodedNodeId(dave) == path.route.firstNodeId)
      val payloadEve = PaymentOnion.FinalPayload.Blinded(
        records = TlvStream(
          OnionPaymentPayloadTlv.AmountToForward(150_000_000 msat),
          OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(800_000)),
          OnionPaymentPayloadTlv.TotalAmount(150_000_000 msat),
          OnionPaymentPayloadTlv.EncryptedRecipientData(path.route.encryptedPayloads.last),
        ),
        blindedRecords = TlvStream(RouteBlindingEncryptedDataTlv.PathId(pathId)),
      )
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadEve.records).require.bytes == hex"e4 020408f0d180 04030c3500 0ad1bcd747394fbd4d99588da075a623316e15a576df5bc785cccc7cd6ec7b398acce6faf520175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d5f00716baf9fc9b3de50bc22950a36bda8fc27bfb1242e5860c7e687438d4133e058770361a19b6c271a2a07788d34dccc27e39b9829b061a4d960eac4a2c2b0f4de506c24f9af3868c0aff6dda27281c 120408f0d180")
      val payloadDave = PaymentOnion.IntermediatePayload.NodeRelay.Blinded(
        records = TlvStream(
          OnionPaymentPayloadTlv.EncryptedRecipientData(path.route.encryptedPayloads.head),
          OnionPaymentPayloadTlv.PathKey(path.route.firstPathKey),
        ),
        paymentRelayData = BlindedRouteData.PaymentRelayData(TlvStream(
          RouteBlindingEncryptedDataTlv.OutgoingNodeId(EncodedNodeId(eve)),
          RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(36), 1000, 500 msat),
          RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(850_000), 1 msat),
        )),
        nextPathKey = randomKey().publicKey
      )
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadDave.records).require.bytes == hex"69 0a440ccf3c8a58deaa603f657ee2a5ed9d604eb5c8ca1e5f801989afa8f3ea6d789bbdde2c7e7a1ef9ca8c38d2c54760febad8446d3f273ddb537569ef56613846ccd3aba78a 0c2102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e")
      val payloadCarol = PaymentOnion.IntermediatePayload.NodeRelay.Standard(150_150_500 msat, CltvExpiry(800_036), dave)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadCarol.records).require.bytes == hex"2e 020408f31d64 04030c3524 0e21032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      val sessionKey = PrivateKey(hex"a64feb81abd58e473df290e9e1c07dc3e56114495cadf33191f44ba5448ebe99")
      val trampolineOnion = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(carol, payloadCarol) :: NodePayload(dave, payloadDave) :: NodePayload(path.route.blindedNodeIds.last, payloadEve) :: Nil, paymentHash, None).toOption.get.packet
      val encoded = OnionRoutingCodecs.onionRoutingPacketCodec(trampolineOnion.payload.length.toInt).encode(trampolineOnion).require.bytes
      assert(encoded == hex"0002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b4bba0e560f1a9deb602bfd98fe9167141d0b61d669df90c0149096d505b85d3d02806e6c12caeb308b878b6bc7f1b15839c038a6443cd3bec3a94c2293165375555f6d7720862b525930f41fddcc02260d197abd93fb58e60835fd97d9dc14e7979c12f59df08517b02e3e4d50e1817de4271df66d522c4e9675df71c635c4176a8381bc22b342ff4e9031cede87f74cc039fca74aa0a3786bc1db2e158a9a520ecb99667ef9a6bbfaf5f0e06f81c27ca48134ba2103229145937c5dc7b8ecc5201d6aeb592e78faa3c05d3a035df77628f0be9b1af3ef7d386dd5cc87b20778f47ebd40dbfcf12b9071c5d7112ab84c3e0c5c14867e684d09a18bc93ac47d73b7343e3403ef6e3b70366835988920e7d772c3719d3596e53c29c4017cb6938421a557ce81b4bb26701c25bf622d4c69f1359dc85857a375c5c74987a4d3152f66987001c68a50c4bf9e0b1dab4ad1a64b0535319bbf6c4fbe4f9c50cb65f5ef887bfb91b0a57c0f86ba3d91cbeea1607fb0c12c6c75d03bbb0d3a3019c40597027f5eebca23083e50ec79d41b1152131853525bf3fc13fb0be62c2e3ce733f59671eee5c4064863fb92ae74be9ca68b9c716f9519fd268478ee27d91d466b0de51404de3226b74217d28250ead9d2c95411e0230570f547d4cc7c1d589791623131aa73965dccc5aa17ec12b442215ce5d346df664d799190df5dd04a13")
      trampolineOnion
    }

    // Alice creates a payment onion for Carol (Alice -> Bob -> Carol).
    val onionForBob = {
      val sessionKey = PrivateKey(hex"4f777e8dac16e6dfe333066d9efb014f7a51d11762ff76eca4d3a95ada99ba3e")
      val bob = PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
      val carol = PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
      val paymentSecret = ByteVector32.fromValidHex("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da")
      val payloadBob = PaymentOnion.IntermediatePayload.ChannelRelay.Standard(ShortChannelId.fromCoordinates("572330x42x2821").get, 150_153_000 msat, CltvExpiry(800_060))
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadBob.records).require.bytes == hex"15 020408f32728 04030c353c 060808bbaa00002a0b05")
      val payloadCarol = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(150_153_000 msat, 150_153_000 msat, CltvExpiry(800_060), paymentSecret, trampolineOnion, None)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadCarol.records).require.bytes == hex"fd0255 020408f32728 04030c353c 08247494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da08f32728 14fd02200002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b4bba0e560f1a9deb602bfd98fe9167141d0b61d669df90c0149096d505b85d3d02806e6c12caeb308b878b6bc7f1b15839c038a6443cd3bec3a94c2293165375555f6d7720862b525930f41fddcc02260d197abd93fb58e60835fd97d9dc14e7979c12f59df08517b02e3e4d50e1817de4271df66d522c4e9675df71c635c4176a8381bc22b342ff4e9031cede87f74cc039fca74aa0a3786bc1db2e158a9a520ecb99667ef9a6bbfaf5f0e06f81c27ca48134ba2103229145937c5dc7b8ecc5201d6aeb592e78faa3c05d3a035df77628f0be9b1af3ef7d386dd5cc87b20778f47ebd40dbfcf12b9071c5d7112ab84c3e0c5c14867e684d09a18bc93ac47d73b7343e3403ef6e3b70366835988920e7d772c3719d3596e53c29c4017cb6938421a557ce81b4bb26701c25bf622d4c69f1359dc85857a375c5c74987a4d3152f66987001c68a50c4bf9e0b1dab4ad1a64b0535319bbf6c4fbe4f9c50cb65f5ef887bfb91b0a57c0f86ba3d91cbeea1607fb0c12c6c75d03bbb0d3a3019c40597027f5eebca23083e50ec79d41b1152131853525bf3fc13fb0be62c2e3ce733f59671eee5c4064863fb92ae74be9ca68b9c716f9519fd268478ee27d91d466b0de51404de3226b74217d28250ead9d2c95411e0230570f547d4cc7c1d589791623131aa73965dccc5aa17ec12b442215ce5d346df664d799190df5dd04a13")
      val onionForBob = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(bob, payloadBob) :: NodePayload(carol, payloadCarol) :: Nil, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForBob).require.bytes
      assert(encoded == hex"00025fd60556c134ae97e4baedba220a644037754ee67c54fd05e93bf40c17cbb73362fb9dee96001ff229945595b6edb59437a6bc143406d3f90f749892a84d8d430c6890437d26d5bfc599d565316ef51347521075bbab87c59c57bcf20af7e63d7192b46cf171e4f73cb11f9f603915389105d91ad630224bea95d735e3988add1e24b5bf28f1d7128db64284d90a839ba340d088c74b1fb1bd21136b1809428ec5399c8649e9bdf92d2dcfc694deae5046fa5b2bdf646847aaad73f5e95275763091c90e71031cae1f9a770fdea559642c9c02f424a2a28163dd0957e3874bd28a97bec67d18c0321b0e68bc804aa8345b17cb626e2348ca06c8312a167c989521056b0f25c55559d446507d6c491d50605cb79fa87929ce64b0a9860926eeaec2c431d926a1cadb9a1186e4061cb01671a122fc1f57602cbef06d6c194ec4b715c2e3dd4120baca3172cd81900b49fef857fb6d6afd24c983b608108b0a5ac0c1c6c52011f23b8778059ffadd1bb7cd06e2525417365f485a7fd1d4a9ba3818ede7cdc9e71afee8532252d08e2531ca52538655b7e8d912f7ec6d37bbcce8d7ec690709dbf9321e92c565b78e7fe2c22edf23e0902153d1ca15a112ad32fb19695ec65ce11ddf670da7915f05ad4b86c154fb908cb567315d1124f303f75fa075ebde8ef7bb12e27737ad9e4924439097338ea6d7a6fc3721b88c9b830a34e8d55f4c582b74a3895cc848fe57f4fe29f115dabeb6b3175be15d94408ed6771109cfaf57067ae658201082eae7605d26b1449af4425ae8e8f58cdda5c6265f1fd7a386fc6cea3074e4f25b909b96175883676f7610a00fdf34df9eb6c7b9a4ae89b839c69fd1f285e38cdceb634d782cc6d81179759bc9fd47d7fd060470d0b048287764c6837963274e708314f017ac7dc26d0554d59bfcfd3136225798f65f0b0fea337c6b256ebbb63a90b994c0ab93fd8b1d6bd4c74aebe535d6110014cd3d525394027dfe8faa98b4e9b2bee7949eb1961f1b026791092f84deea63afab66603dbe9b6365a102a1fef2f6b9744bc1bb091a8da9130d34d4d39f25dbad191649cfb67e10246364b7ce0c6ec072f9690cabb459d9fda0c849e17535de4357e9907270c75953fca3c845bb613926ecf73205219c7057a4b6bb244c184362bb4e2f24279dc4e60b94a5b1ec11c34081a628428ba5646c995b9558821053ba9c84a05afbf00dabd60223723096516d2f5668f3ec7e11612b01eb7a3a0506189a2272b88e89807943adb34291a17f6cb5516ffd6f945a1c42a524b21f096d66f350b1dad4db455741ae3d0e023309fbda5ef55fb0dc74f3297041448b2be76c525141963934c6afc53d263fb7836626df502d7c2ee9e79cbbd87afd84bbb8dfbf45248af3cd61ad5fac827e7683ca4f91dfad507a8eb9c17b2c9ac5ec051fe645a4a6cb37136f6f19b611e0ea8da7960af2d779507e55f57305bc74b7568928c5dd5132990fe54c22117df91c257d8c7b61935a018a28c1c3b17bab8e4294fa699161ec21123c9fc4e71079df31f300c2822e1246561e04765d3aab333eafd026c7431ac7616debb0e022746f4538e1c6348b600c988eeb2d051fc60c468dca260a84c79ab3ab8342dc345a764672848ea234e17332bc124799daf7c5fcb2e2358514a7461357e1c19c802c5ee32deccf1776885dd825bedd5f781d459984370a6b7ae885d4483a76ddb19b30f47ed47cd56aa5a079a89793dbcad461c59f2e002067ac98dd5a534e525c9c46c2af730741bf1f8629357ec0bfc0bc9ecb31af96777e507648ff4260dc3673716e098d9111dfd245f1d7c55a6de340deb8bd7a053e5d62d760f184dc70ca8fa255b9023b9b9aedfb6e419a5b5951ba0f83b603793830ee68d442d7b88ee1bbf6bbd1bcd6f68cc1af")
      onionForBob
    }

    // Bob decrypts the onion and relays to Carol.
    val onionForCarol = {
      val bobPriv = PrivateKey(hex"4242424242424242424242424242424242424242424242424242424242424242")
      val add = UpdateAddHtlc(randomBytes32(), 1, 150_155_000 msat, paymentHash, CltvExpiry(800_100), onionForBob, None, 1.0, None)
      val Right(packetForBob: ChannelRelayPacket) = decrypt(add, bobPriv, Features.empty)
      assert(packetForBob.payload.outgoing.contains(ShortChannelId.fromCoordinates("572330x42x2821").get))
      assert(packetForBob.amountToForward == 150_153_000.msat)
      assert(packetForBob.outgoingCltv == CltvExpiry(800_060))
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(packetForBob.nextPacket).require.bytes
      assert(encoded == hex"0003dc6a4c9b34bdcd2191fc4dacfc1aeb20f71991acbd17847b9ab17d69579c1614da1276d18820e55534d7352839caa436aa79a6d5be26c6ecbd1c79f74442b1b7bf8ed8b739b736b9248769c2c422eebc85fb0d580e9561b1cda1be3fd4cfa6ed0d839a2feb878acf686b112febaae9c1494a2ad20d97b2d2f7e59d6d296a104ba1b29e5d06d7a7d0279e627c51d6eed9c6a56bdbde81b22dc92e07e151546fc9568d4b27d0a1e217c3caf42e8f6e629829ee76e2a077fd1eb38dcce22217458c529cc7a9df4adc507ead08b267a722cb6b06cfa3d2a35b6387f878fd7b18e6debe7c2ff03603687adfc654606756b2c609a891956a9f4c2918d625632833ca371fe605da31a10044393c240bf4db8a1e413da7f8a6ea9dc80b0031e0dc43c1f5922ca5003e87f405ef73fc492bc813962192e3c4c4801c0f03baab2e0aba3a6cd101f8d09de15c027bff835beacc5ba09420323f4d5f75e6818939fbe02cb2aaa3e6651d512eee37ded2f27406974a3fedf77c8364beb2ce60d869b0de5ce33c466406b45e5791e189f0795c623d786d794c3d9b927b9fb7fde99df2b4359da128496ceb1a336049f05f06937c45d0fd90f10f7654ea4bb5734d5a9a3e0b2ace8ac771494a6e442fedd2314772e761704ad16f8aa9a16832b30987535c43963e880acda194119407de24fcf23558596a2848d6c98430f504e9281127b2bf649a25c6c1d35783d509f17fec8c2c0ee4004778d66822db24f01bc3361454efb60cde453c6cfe33f2f0177a3b0e326a077a0d8c433f8a21613fb62ceb2f314aa69f81b7756e76ac9f6c6dcfdace9a320e1afc738b223d192fcbc0adf5cd84f4cc161abe0455c26cdaa1270b2823fedbe7ee982ca5af8c8b8bec0ba90c3bb65a8e1ee89cc6c44114d658fb89985c7ca8e8eeb32bcf3fedae62330eff3da9654fda0a58281480d4be76c916889b2db9210e3a66c9ccaa3f06232150d5d96cbf6c18916d603e1495ab6f17baacb5005ed5ec17864c2da1bb400e6e68975cc84325e18215a6052313c3c75e25163648c840506916c2d845760063d6a4385df4f54bd0ec5da029b837202e45e399d1ee794160f49cd6d0149457d3efc2537e2ec36ce6a02727a5104d6f37fd612fec4e96f169f4f7d66706ea7d9ba344bef8e2e57664bde30f26249664bdc3eeb1dfa88a9f33e6d790581b67d57c30255d43624751f269da3f98df459a6bafe6e37d62be589eb938d8d223c7e80038a8dae2313126822fe16771a6ab6598d15bc350ddd76180e0963b5765834365254c611e2de46bde204a0377e13dab44afdacf5d77465dc035ca1ff70603f5887a023cca650ab9e3b4244f3a1870bb07b2556e3bcd47fafa3adec659aee17a881310c208b2d696dc14fbdd2209d89e7e61bbee19263ea98eb994eef0dc97e2ae0e56a6fc9592f9e27de5a22d749c9dbed19f2b2b8602ff890e82fedbdb41e019fbaf74256d6bcaf31538fff1329ed30b4b7bd991e9f1b5c6ef4f119387fcb7875f4f21f2d39b0ad01500201da644158e1260a58b4bcd1712ca3bc6e093951424452197b4fb3ac2aaf16e70fcedddd9fa96fcf46c2d60cb40a64c807fffb2448672c5bb2afce2205fa1d356d4cdb907a25b82c27e4fb735375c1f532fbbdd43c415a27e603cf15ff7f00f1ac96f346c2dcc00ffeb682db175b912cb5356f7528a834fe84df2df7453e34dd01008a087e799c18656eefe3038a03c71803bbc0990cd50f4a413329e6f779107d57158e78886728a9fa039c385abc92e230179051a02727402e7a613364b48cd93b6a26cb6888d5c4cc1d3c6a39cb442c2de12bb1ad396205c9a10023d7edc951fc60a28813867f635c3d74d4206f398849e94750b98ba43c5faca8502bf46929e3c0debfac32fc4e4a09c2436a0590cd53c")
      packetForBob.nextPacket
    }

    // Carol decrypts the onion and relays to Dave, without knowing that a blinded path starts at Dave.
    val onionForDave = {
      val carolPriv = PrivateKey(hex"4343434343434343434343434343434343434343434343434343434343434343")
      val dave = PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      val add = UpdateAddHtlc(randomBytes32(), 1, 150_153_000 msat, paymentHash, CltvExpiry(800_060), onionForCarol, None, 1.0, None)
      val Right(RelayToTrampolinePacket(_, payload, trampolinePayload, trampolineOnionForDave, _)) = decrypt(add, carolPriv, Features.empty)
      assert(payload.amount == 150_153_000.msat)
      assert(payload.expiry == CltvExpiry(800_060))
      assert(payload.paymentSecret == ByteVector32.fromValidHex("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da"))
      assert(trampolinePayload.amountToForward == 150_150_500.msat)
      assert(trampolinePayload.outgoingCltv == CltvExpiry(800_036))
      assert(trampolinePayload.outgoingNodeId == dave)
      val sessionKey = PrivateKey(hex"e4acea94d5ddce1a557229bc39f8953ec1398171f9c2c6bb97d20152933be4c4")
      val paymentSecret = ByteVector32.fromValidHex("d1818c04937ace92037fc46e57bafbfc7ec09521bf49ff1564b10832633c2a93")
      val payloadDave = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(150_150_500 msat, 150_150_500 msat, CltvExpiry(800_036), paymentSecret, trampolineOnionForDave, None)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadDave.records).require.bytes == hex"fd0255 020408f31d64 04030c3524 0824d1818c04937ace92037fc46e57bafbfc7ec09521bf49ff1564b10832633c2a9308f31d64 14fd02200003868a4ec9883bc8332dcd63bafc80086fe046bf2795c070445381f98f5b4678efc8c102fee084102c1ffb91cae87bbbdce3ef59e68af26deae97af39879713b71df2c31e56acbddf7cc8f85214162785839e981a3abb51749d7cab6e09956a7e384fa658323e144293c7328f6f9097705b05eed2f11107cafdf4f6f6de7a53512e192276386c83f91809462f8f2737b8729d35ce145999770edae36808757db3aa3e77dbd8dc517fb0437e2660b16ef728fbcadf7d7f3cb4395924d1bb50a14ce8ba68635e73a7fa3d55f2a9fa796635a8a1dc6c1a3b72c491d4b1fd5fe642e6decb93d28223e79e4a69ffe71bc6e595b949e4071a2ffa65bd9099d6af7bf7f26065f032969ce33b78195cc741e2c97f801311368aee7e75159de00f6dc2b0b2b2e77c583ce8fe4ae61b774491dfefacc2aa3dfb99d6d00689a344def2086405caa2e2dc2126dc7b47750f3393f492c8b5c96bcd609e1c56a2d713ec9f6c0618a33ddfb20f2f3cbe355424292de47b6374bc012390a433e02f31cfa8a9817bf6a5597ac42b063e1cf3aaf6666b5d420600c8fc8ce689678bd802ac3815f9aaf6a48d0d3a7f940f621bd74d3e738b40c4c67f5b54b258e57d15584cf84ee2ad61c8a1fabb0e035fdb67f92f54f14797fd20bddee25d3a1ea9982757778c311f77dba90013d37780535acc4ef2281ebabf1736cb188fe7f08dc861d61a4135f295d85eb02e3a8f0015c6bcd206c7b5162f0696c1d69a06e42918dbe8fd9affb")
      val onionForDave = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(dave, payloadDave) :: Nil, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForDave).require.bytes
      assert(encoded == hex"0003626d5fa7ed0b8706f975ca58ae2c645889514153568aaab7835325ecd3739a57cd1f65ed63fff058e29e81f660c134ea43937b9d93fb0caf3846b176734c292b7c7fd7a576391e63c0fe0023bdeabcc9971f4a44db079bf8203b0a52ec47c86653207ea6596c8d029793acd2327ff788dcf8a9ee424c2aa7798c95ce53fae6c9be85a0d276ff20c814c26f6e4f19a2152ffbd931a9d054976cafe556b00365ef54edc9a3f8021c73f17884ef209be13e7710df3b93efa8edd2b4eda306766f6dabb517ed720076cc3e7395373dc81e018f347109bcbc0265f0d5098489f83212a2e5c1583c8411dea509a17735713e8cc66d4a89cd56d0a35e0d3f59553b6b017eb5c9b6dcd724ca79c0d4aa5706a34503895655d2fc186ec772a17ba46e8961c37c61fe173f448d83efd1c6f78ce5f64fdfa352c8ab1c7d3aeddeeacc409a92fe87f86a07cfec33c5a7a2f3834d8dfba1291bed88271cb13c210fbfb1ced93440bee260f6335044a95d7aaa14bed06e1d3b9ec7c82db822543ffa4eff34e88a715ff8f8a23017982c6ae882ee18e0bdabba6f7d0e4285d034a2fd2a903b9ccfce534f9fba5acdc91f91d723ae359b9c2823b7e24dd2ee8d7ca3f6384976f5d319172785c948964426332c811682a643ce8c9575431e84c9af7fcf0dfdcdc67bc3e0b2415719e5a0da868dafef91be595c280bdb67e1d586c184c71617bddddfc653f4a1083658a96fe86cfa0eb93f9182fddccbb64f9e9e2b8f0c4b6edeb2aa81f3e16a24ffce789d183e0fa3689e437a1180b44dac0a5bb3bacd1ab0dab53c55194419c0e194f31ca683cf7c9b3ef304ff19dd490a6ad80233c1855b680f34e0eb2d252689ad5cd1750a793529a83b194e7d410f6cf027ae78f94b5d0405feeef397e272050d27581bb996eab562cd71d6aec3e4a793b93c950dcbadeb1d8cef4e9b5a466a06f1051f0eab1d08896c3eeb20d55118dec43ac5dcb8e90e1e3bcb4a67c419c4d825111ff450a6241b31087ef70f2112da8940a834441f2e0f7883eed5ef2dd09d57c23eec75ccf443ac02197f2f6cbf8c47fd8753cca90e1375c2c04dc985500f3fe147f72121bc1020f430fb199161897d38765bf0480e8d0505cb09eb6cefc842eb93edbf7d7a99c1f9f7f09db3e6dd3e5116f29d1b1497f940fc341b6ed90f187c68a14b00a845303a248187f7699ab9ebb0e6c9355ade7772703faa4380cfd88c9600f2147747b402d3621d2410f3a6c60367b3bed950186f966db77c58d83bb29305748b8fbf6da1e8d3d1d7251ce0812170e999fa35128cada2ff53d7cb42f37ebc3e758c6f571eb3c8e94f3535d422ebd1b11788c9ac75292d15b759a612250c97efa01fa869187cf8cdadf95ebccafa18ca9b40076828d459d7a295528fd3c77c5f3978d7fa6244466a056ebdf59902907b13bd6255904aee68cbbb46a81ce1f5cd541a3935a2229d0501f1c272624157501841eaaf6703b6b40a12a010e68bb563e2794ed7d3976ff9b59bb1f6719b6d06a0cd3d561bdcd0b2761ccbec1c4decb2faf13065ba05f633d114cdf8d61fd33c3d6f149319adc4b0367df11b77c92c75a57de20bb8775d0582be511e6139b27bea77e60e7180ba292a94944f87a91a20cabc8346ccde196e74b5bfde5a613ea5536292971cd3737980efac61d8189926b14cc1f5ea553b0f41afc2c3f6bc7d19f078cff09b2181d1b9dd068ac8a8116dd96a418c1e24f7cf22c54f7dd7c40812b7e36805d7adccbf5c8a703e3891c05caf6b4434192940ea2f164d0ae84355d1a33859d45107abcd41598da0afa4fe5f8c5a3ecb9b4857ac0e736fc76b4f51325391530a645618656a3dae74cba34a34bb7a3c0e9523f6db6b31694e8ceb9c67ae658af7db5a4c8de9e8322c3172fed09f27aa4420ae9a0a")
      onionForDave
    }

    // Dave decrypts the onion and blinded path data and relays to Eve.
    val onionForEve = {
      val davePriv = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
      val add = UpdateAddHtlc(randomBytes32(), 3, 150_150_500 msat, paymentHash, CltvExpiry(800_036), onionForDave, None, 1.0, None)
      val Right(RelayToBlindedTrampolinePacket(_, payload, innerPayload, trampolineOnionForEve, _)) = decrypt(add, davePriv, Features(Features.RouteBlinding -> FeatureSupport.Optional))
      assert(payload.amount == 150_150_500.msat)
      assert(payload.expiry == CltvExpiry(800_036))
      assert(payload.paymentSecret == ByteVector32.fromValidHex("d1818c04937ace92037fc46e57bafbfc7ec09521bf49ff1564b10832633c2a93"))
      assert(innerPayload.outgoing == Left(EncodedNodeId.WithPublicKey.Plain(eve)))
      assert(innerPayload.outgoingAmount(add.amountMsat) == 150_000_000.msat)
      assert(innerPayload.outgoingExpiry(add.cltvExpiry) == CltvExpiry(800_000))
      val sessionKey = PrivateKey(hex"cfeb31c76b7b6905be8da966ce2d9a87e3abbb03d236d7346c2852862c87a4b8")
      val paymentSecret = ByteVector32.fromValidHex("1221f15a9dece128347dac673d6171be13b3d92c9c77ff581506507045a1d2e8")
      val payloadEve = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(150_000_000 msat, 150_000_000 msat, CltvExpiry(800_000), paymentSecret, trampolineOnionForEve, Some(innerPayload.nextPathKey))
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadEve.records).require.bytes == hex"fd0278 020408f0d180 04030c3500 08241221f15a9dece128347dac673d6171be13b3d92c9c77ff581506507045a1d2e808f0d180 0c2102c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7 14fd022000038da50a45c30a086668ad1c34a23c11ee94bf0b4e7b8b8b184b7914645aef9e1ecf8f95df787c87965210644a84d1da8baf1731a02d0a292ae9c6e685a36e0e1679a8e0c38c27de47014966aaacfea446571ddaf7afcff7c3517e7bf57f87388720a1f226cc9ba1f670396435163a6872d39d2460adafdefb355bc5a89d51e62d427aac45e40b18d2b34587ca19753a8a0a7d704e38c190034b0c5b253bd566e20845a22e81d2d6a74071dfdfefe6fceb555f3d52a7f7d6b99e8e74a6cf4893f7374b473e28e62c9d99fc386ed220dd0ecc50274883d9f6a63e4aabdc1d6604827367dd3b3ddf233c2a8a7d577bf75736ca77c5d7d43f85db51c7cc6e33513225428e525ac0c22f6ef6c509e4ebbe4074f1fb726a8fd1e8643893e9fa38ae1eb6fd761e7fb12db8d3f20b5b26483b3fb92e6eb9fabd647870ddc39d61de48bdc39ce26eedf2f4d8da60adc13876844ddda3cc902792a8bd113980011279cddc625b9bcda8b0cc91cacaa4061d565a0b6e5daecf21ef3ce1be4d195c28ddc7337754e1d58908c4d8ffb45d0fbe936b83beb9851b88e57026c80e3e6d7b5b984785b4dd67498f86a9afcfc0548837b87ce07ef524696b68dc5a42312588dd051ea608f46dec1613c558e11d64e32c5cfd6b0e1c93691c724b257033d93dc7fffebca7f494d2b6391492985eac16d6919dcf60f1ab49e6ae216c90776b48ace0404128313220af7b6e546d1b89ab356cab83059301ae2d3a0eff524a610649c8")
      val onionForEve = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(eve, payloadEve) :: Nil, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForEve).require.bytes
      assert(encoded == hex"000318a5a814e6e22fa5e938e671528091a27ff4883b81353560bb24d65cf9c245e880d563cfd8717f5ffae349d5ebc982b674d752a867e515855af157e325294ce929e8a5418faa5c2c7913f9440d4aba5087aee5e1499f239b392fac71a935636b3bbf6146aaa995db6e090c3216191f5c0662796277854c618bfadac769cacf34c89e05435b0f739f505f8b1020055497bdfafd88cd35d35ad2e0657aadfa8cc47647ccadae9dde5ddedd4e2b3d9c90074406449bf51e22fcae388bab417573e3f104a80913305bc1f3078e2badb8cf519b77ebd5433d5d12ccb9ff420ae6451b1368fa648213fc831b456a700fcd88a387c5cdf1816b9a88dd696ee2bc9035cf19998740813b50ae131920a2879026fbdda8caea065950ee3288e875d9001b80c0f915d8ef3666473f8a1a2233ffb349a8b5d292cef9011deada08d839ca2a2b0f4910edb68d6ae4d3ee08ef97143aee5a6afcac6036d5be5bac5591ce46dd645438340839fff8e7e7ad6ec20bca4335ef8dab6e0e1ddf861352e33bb63c6d5a3531a68abd0b185d0ebe1545cb10b0ef4461068b3e8e95a549ac9756ce44653593bb198241e4169c3f419c3f0903d36220ad849323495606366e1adb3dc7db0ac6cbe7a43f2f954534d1eb4d8f617369e7ea4f281bcac1f8ff7e13625f6518d4e255f3695528afa24cc237ba69c4452b0645b95ff6465cab30ca6afd21f1957f331df501c997f2eb17e6e3c17619dc40671d29f68ab4844926c1d513c3a1209c8da2e9b4bac4c740ae3bc6953f1afa406c6b5999a9e2d72e41073171246fd66f52397c83cb9027e59f0d940b01596f5c631e43fdb5b320142c0e595383e39d0a333535b0c6ff9b9f8d3f892797c992e582a60f974788ffcaf252c691c550c56e9d43b6ca0278da91ef8b84eaf640d4551b7cc94c1b78968d3df85798fef2382353854ebba9c11da5d541535e2aca9d4adcc60fe9b978b9b096e35e3ffb7f740fc27721dab063db98100d581259f20a79fe66629fbe92c3576dfef5f8e23724bd68689553c88065ac19af4cabd19a6e7566278d212407884cd294947973acfb872788e6f7c2372410db55b1d6d7713c639957e2161dfd35c487ec706290e85a0bd0f12ac7f20c1cbfc12e1d13049d105d421a260b1b095c161df77f00eb74fc451c1bc3fee1ba6bb5ac0236ae249f11ee5f7b21e3e41c84fc423c9692f60a2cb4ad707757104b058033e57c3b23a6ef920a2a14efd1c39171c3085cb4f7f27fe93b6b8cd374d92b615406ca2b3dfa860dcebc22fb55a2c8a92e706d84798bf4268656c8ffd10436897b2f26ad4e7389915d7c10f82d20075a02eb24af6ef6d5e823837470c0c1ccaf6ae43d64a7ce1e0427b89e571ec38ad1d718065f6896656018f5ddb6189d8f60ad7f4e9218473cd203c8341259ee6a2ae1446057a493be99132521a3ee26944a67642ba6ee9071e9b15f6d644c93bb82bc4543105a36284c459e91ec5519afcea79f5b8b9485e1cabf3b551f959bd9664b3c301ee6fe2f562ce378cf570ddf3da5c35ec0273c9fe5f6b86c54298aa77c1bee5f20b77ee97d1928fb3684939768364b28313dafb7fad9fa690a882e52ddef1e6ae6730b55a1267ff7b05a92fa4ad77b60439b4a7b549a6f22130867da882c25ff512d5949702a72477c1b0c2b4d919eb92858eb7e67cfacb0ee368e278898d4dff3b489345a314502ad852a7037a208f143f240a3315a5d432c51ae4510e343df0d111d689963b624b4628e8e0a1604704f1778084e07807496d00d94d529284f55a81ee8de5077229501e7c02e80b7f82ce3c649246672c6cab48f0407e0e09772135524204bede73e3ab870edc6c8346f152ae6667fa381bad766a3312a17ab41cb059ad20be93f01d8fd59741e8871f9c6d0b1bf8dbfd042")
      onionForEve
    }

    // Eve receives the payment.
    val add = UpdateAddHtlc(randomBytes32(), 1, 150_000_000 msat, paymentHash, CltvExpiry(800_000), onionForEve, None, 1.0, None)
    val Right(FinalPacket(_, payload, _)) = decrypt(add, evePriv, Features(Features.RouteBlinding -> FeatureSupport.Optional))
    assert(payload.isInstanceOf[FinalPayload.Blinded])
    assert(payload.asInstanceOf[FinalPayload.Blinded].pathId == pathId)
    assert(payload.asInstanceOf[FinalPayload.Blinded].amount == 150_000_000.msat)
    assert(payload.asInstanceOf[FinalPayload.Blinded].totalAmount == 150_000_000.msat)
    assert(payload.asInstanceOf[FinalPayload.Blinded].expiry == CltvExpiry(800_000))
  }

  // See bolt04/trampoline-to-blinded-path-payment-onion-test.json
  test("build outgoing trampoline payment to blinded paths (reference test vector)") {
    val preimage = ByteVector32.fromValidHex("8bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c057")
    val paymentHash = Crypto.sha256(preimage)
    assert(paymentHash == ByteVector32.fromValidHex("e89bc505e84aaca09613833fc58c9069078fb43bfbea0488f34eec9db99b5f82"))
    val evePriv = PrivateKey(hex"4545454545454545454545454545454545454545454545454545454545454545")
    val eve = evePriv.publicKey
    assert(eve == PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"))

    // Eve creates a blinded path to herself going through Dave.
    val pathId = hex"0149792a42a127e421026a0c616e9490fb560d8fa5374a3d38d97aa618056a2ad70000000008f0d1808bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c05702414343fd4a723942a86d5f60d2cfecb6c5e8a65595c9995332ec2dba8fe004a20000000000000001000000000000000068656c6c6f7bcdd1f21161675ee57f03e449abd395867d703a0fa3c1c92fe9111ad9da9fe216f8c170fc25726261af0195732366dad38384c0ab24060c7cd65c49d1de8411"
    val (blindedPath, invoice) = {
      val dave = PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      val blindedPayloadEve = TlvStream[RouteBlindingEncryptedDataTlv](RouteBlindingEncryptedDataTlv.PathId(pathId))
      val blindedPayloadDave = TlvStream[RouteBlindingEncryptedDataTlv](
        RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId.fromCoordinates("572330x42x2465").get),
        RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(36), 1000, 500 msat),
        RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(850_000), 1 msat),
      )
      assert(RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(blindedPayloadDave).require.bytes == hex"020808bbaa00002a09a1 0a080024000003e801f4 0c05000cf85001")
      assert(RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(blindedPayloadEve).require.bytes == hex"06 bf 0149792a42a127e421026a0c616e9490fb560d8fa5374a3d38d97aa618056a2ad70000000008f0d1808bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c05702414343fd4a723942a86d5f60d2cfecb6c5e8a65595c9995332ec2dba8fe004a20000000000000001000000000000000068656c6c6f7bcdd1f21161675ee57f03e449abd395867d703a0fa3c1c92fe9111ad9da9fe216f8c170fc25726261af0195732366dad38384c0ab24060c7cd65c49d1de8411")
      val sessionKey = PrivateKey(hex"090a684b173ac8da6716859095a779208943cf88680c38c249d3e8831e2caf7e")
      val blindedRouteDetails = Sphinx.RouteBlinding.create(sessionKey, Seq(dave, eve), Seq(blindedPayloadDave, blindedPayloadEve).map { p =>
        RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(p).require.bytes
      })
      assert(blindedRouteDetails.route.firstNodeId == EncodedNodeId(dave))
      assert(blindedRouteDetails.lastPathKey == PublicKey(hex"02c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7"))
      assert(blindedRouteDetails.route.firstPathKey == PublicKey(hex"02988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e"))
      val blindedNodes = Seq(
        PublicKey(hex"0295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be"),
        PublicKey(hex"020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"),
      )
      assert(blindedNodes == blindedRouteDetails.route.blindedNodeIds)
      val encryptedPayloads = Seq(
        hex"0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86",
        hex"bcd747ba974bc6ac175df8d5dbd462acb1dc4f3fa1de21da4c5774d233d8ecd9b84b7420175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d59347cc1c013a2351f094cdafb5e0d1f5ccb1055d6a5dd086a69cd75d34ea06067659cb7bb02dda9c2d89978dc725168f93ab2fe22dff354bce6017b60d0cc5b29b01540595e6d024f3812adda1960b4d",
      )
      assert(encryptedPayloads == blindedRouteDetails.route.encryptedPayloads)
      val paymentInfo = OfferTypes.PaymentInfo(500 msat, 1000, CltvExpiryDelta(36), 1 msat, 500_000_000 msat, Features.empty)
      val paymentRoute = PaymentBlindedRoute(blindedRouteDetails.route, paymentInfo)
      val offerFeatures = Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional).bolt12Features()
      val offer = OfferTypes.Offer(None, Some("bolt12"), eve, offerFeatures, Block.RegtestGenesisBlock.hash)
      val alicePayerKey = PrivateKey(hex"40086168e170767e1c2587d503fea0eaa66ef21069c5858ec6e532503d6a4bd6")
      val invoiceRequest = OfferTypes.InvoiceRequest(offer, 150_000_000 msat, 1, offerFeatures, alicePayerKey, Block.RegtestGenesisBlock.hash)
      val invoice = Bolt12Invoice(invoiceRequest, preimage, Sphinx.RouteBlinding.derivePrivateKey(evePriv, blindedRouteDetails.lastPathKey), 60 seconds, offerFeatures, Seq(paymentRoute))
      (paymentRoute, invoice)
    }

    // Alice creates a trampoline onion for Carol that includes Eve's blinded path.
    val trampolineOnion = {
      val carol = PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
      val trampolinePayload = PaymentOnion.IntermediatePayload.NodeRelay.ToBlindedPaths(150_000_000 msat, CltvExpiry(800_000), invoice)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(trampolinePayload.records).require.bytes == hex"fd01b5 020408f0d180 04030c3500 1503020000 16fd01a1032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e66868099102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e020295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be002b0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f2200d1bcd747ba974bc6ac175df8d5dbd462acb1dc4f3fa1de21da4c5774d233d8ecd9b84b7420175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d59347cc1c013a2351f094cdafb5e0d1f5ccb1055d6a5dd086a69cd75d34ea06067659cb7bb02dda9c2d89978dc725168f93ab2fe22dff354bce6017b60d0cc5b29b01540595e6d024f3812adda1960b4d000001f4000003e800240000000000000001000000001dcd65000000")
      val sessionKey = PrivateKey(hex"a64feb81abd58e473df290e9e1c07dc3e56114495cadf33191f44ba5448ebe99")
      val trampolineOnion = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(carol, trampolinePayload) :: Nil, paymentHash, None).toOption.get.packet
      val encoded = OnionRoutingCodecs.onionRoutingPacketCodec(trampolineOnion.payload.length.toInt).encode(trampolineOnion).require.bytes
      assert(encoded == hex"0002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b98b9bf5cf80f093ee323cbb0c5b0713b14779893b07e4cc60110ce2d2240f16be3fd3c23062491fb57d229dac4edbad7a3b26242cffc2a2e9d5a0eae187390d4e096699d093f5ac82d86abdf0fdaae01bf16b80261e30f6ffda635ea7662dc0d124e1137367ab0178d6ed0de8e307a5c94a213b0b5705efcc94440308f477a185f5b41ab698e4c2dd7adea3aa47cccb5f47548c9ec2fee9573d32042eee6851a4f17406b6f6d13e2b794b0bd1676d0c3b33e4ee102823bb9e55f0ec29fc7f9df3332be5f9c68d4482ff60c0183c17742844baf01821cc1a2dbed1f764d124a5696f290db7f43608ddad007da504a56d0c714a0d34eeeed848d08c846609d29123df3f82484a7ae994c37487add9c878a737bb9d6e314139329b2eed131906a5717516f7790f0ec78f3e1a6c9b9c0680221dd290e3e219146039cb02f28eec46b88d5eceae7738182d9b1be14130636943dfa95aee4cf0f81bcdb04b8f92e3c9841f9928a7b39c3c8861dd4b73bf736b1e1b0d9a22c3bf3c12cdb1580c343a129b93cbda9e58675a52cde759040718c25504ea28df3b6da73e832b5bd7b51054a5663d407871c4a90e76824eca922ccde0bdd30e81f1ce9bed788416cc9660b016adccab6a45e0ac23d11030f7076b88184c247da4586d4fa3102e44f882ae88a46cf4a4dd874a9466c31eb94c834ac6c9cfb4bb9a6ef6a6a")
      trampolineOnion
    }

    // Alice creates a payment onion for Carol (Alice -> Bob -> Carol).
    val onionForBob = {
      val sessionKey = PrivateKey(hex"4f777e8dac16e6dfe333066d9efb014f7a51d11762ff76eca4d3a95ada99ba3e")
      val bob = PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
      val carol = PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
      val paymentSecret = ByteVector32.fromValidHex("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da")
      val payloadBob = PaymentOnion.IntermediatePayload.ChannelRelay.Standard(ShortChannelId.fromCoordinates("572330x42x2821").get, 150_153_000 msat, CltvExpiry(800_060))
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadBob.records).require.bytes == hex"15 020408f32728 04030c353c 060808bbaa00002a0b05")
      val payloadCarol = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(150_153_000 msat, 150_153_000 msat, CltvExpiry(800_060), paymentSecret, trampolineOnion, None)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadCarol.records).require.bytes == hex"fd024f 020408f32728 04030c353c 08247494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da08f32728 14fd021a0002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b98b9bf5cf80f093ee323cbb0c5b0713b14779893b07e4cc60110ce2d2240f16be3fd3c23062491fb57d229dac4edbad7a3b26242cffc2a2e9d5a0eae187390d4e096699d093f5ac82d86abdf0fdaae01bf16b80261e30f6ffda635ea7662dc0d124e1137367ab0178d6ed0de8e307a5c94a213b0b5705efcc94440308f477a185f5b41ab698e4c2dd7adea3aa47cccb5f47548c9ec2fee9573d32042eee6851a4f17406b6f6d13e2b794b0bd1676d0c3b33e4ee102823bb9e55f0ec29fc7f9df3332be5f9c68d4482ff60c0183c17742844baf01821cc1a2dbed1f764d124a5696f290db7f43608ddad007da504a56d0c714a0d34eeeed848d08c846609d29123df3f82484a7ae994c37487add9c878a737bb9d6e314139329b2eed131906a5717516f7790f0ec78f3e1a6c9b9c0680221dd290e3e219146039cb02f28eec46b88d5eceae7738182d9b1be14130636943dfa95aee4cf0f81bcdb04b8f92e3c9841f9928a7b39c3c8861dd4b73bf736b1e1b0d9a22c3bf3c12cdb1580c343a129b93cbda9e58675a52cde759040718c25504ea28df3b6da73e832b5bd7b51054a5663d407871c4a90e76824eca922ccde0bdd30e81f1ce9bed788416cc9660b016adccab6a45e0ac23d11030f7076b88184c247da4586d4fa3102e44f882ae88a46cf4a4dd874a9466c31eb94c834ac6c9cfb4bb9a6ef6a6a")
      val onionForBob = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(bob, payloadBob) :: NodePayload(carol, payloadCarol) :: Nil, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForBob).require.bytes
      assert(encoded == hex"00025fd60556c134ae97e4baedba220a644037754ee67c54fd05e93bf40c17cbb73362fb9dee96001ff229945595b6edb59437a6bc14340622675e61ad0fd4e5e9473ea41567f4f7b0938040e2076c378a98409260c7234f87c58657bcf20af7e63d7192b46cf171e4f73cb11f9f603915389105d91ad630224bea95d735e3988add1e24b5bf28f1d7128db64284d930839ba340d088c74b1fb1bd21136b1809428ec5399c8649e9bdf92d2dcfc694deae5095f9ea212871fc9229a545ddd273635939ed304ba8a2c0a80a1a2ff7f95df532cde150bb304cd84abf88abe6e09b405d10e5e422f6d839a245fd2a300b2f6b95eedecf88479a3950727e6eeac46a34b2930aa9b0d7dd02d021d59800c3f7d5bae58eb45d03f31cce59f04715d7f7158fb2413f9ffe83b869c52019a54f6e0e194479e2eb546a6efe27cdb69863b5ff4e218e57b3e7aff727296036ed6b6756b6b98b22607b699190ced7484df2fd487fd679bf7b327322afd8c9ed658564a2d715cd86e0d270f3fad64980ef2926b82c415cdc537ff5d037b0a2986a44857ce430dfabce135748b4bd4daf3afaac064b1571fbce1369b7d7166c2638d426b6a3a418e5f017699373f614815de8275c74cd57bcfb9f3c5a11183cbb8f488bb255f7a0c3299df1306fdeeca785d81a7bcba5036a4891cd20d1b16c5436c51992d4797e124df65f2d71479739923b46d3daa3a0ecc75404c0475e5cd01665bf375e3897b3a57d2fa89ce1fa7d667ecfe0c097cfb7d94634c5a2b7c6ad5a3de7f9980a0779b66dff957389bed1e19d4681299fbe762a6ca0f9fc0726c203dc2021e74375559453ba0d2c2825142ed007cefb1e1466bb99303dbf4ceaba5eb76d18204910df11e3e3747d6d147c599edfbaf898fbd09d867558dec280e8d1c44d6e440a3c8d3b1afcfe7f3b1731a42bee7b6f7227e755bcc936952b696740f387c0ab93fd8b1d6bd4c74aebe535d6110014cd3d525394027dfe8fad19477d69dc1671d1133f5d8d21b55ddc7f3c76dabf718ca6f02da0d6445e4326b781c6d9041e9e330e44950d10d5dbed7f98b708d1681b75f8fe56c99c7a424899c6a06f36e5b29f2c3db0050bebeffee8b729351518644f98246c1db892ff3305b7610cfb09d5465f5a94da4812d35275c42f4b3a9cbfe626cee01e1818cdbe71565104e112d1c2c74450488d06de19c831d3c3e5203e67229bd9619166aab97fb579623c418b23c05fabf39e21ba0d7baf4aa67034b1ba8c4bd4542825471c7dfb37c24cdfd2d8d06d0e7ddaca01f001449195cc04201a7ae2da86e74d515e2feef3707e29508768f18eb5741ef22dc07f078cf751da83ee2fe9927c760474cdce266fce9b66959d391d51b204fa50cd9a8ff7b45fdd043679a20afa0b440938a721fef14badb97b68ad5e5494dfb2aea8edc1cdb69eb6f13b75bbd496c8eb35a48f229a080ae6744dec87f58058296c2969f0916685ac57a0a44efe4691eb06236f414334f5747a11b100e1d6272ff6082510fa79c64bcfaa58e43525f9fbbea025aa741feb7b18925e2dbd0da2a73748a6c30fe625afb497189d7f188869602989a53892ad24624807e1581eeca2db2cef855aa65af66c4573f9c637699bcbe8ae5f6d9f0713ffe52d453faa39b44be3108e940b322db0d1dc008aff99d4909345ffcf996a382359e7e5b4592522d453fffa9744e1e32a21a237fff4c8c55c1f46fdc5b2e8de267419a3052b33c6065119f690e972ac9b19921bab489a572df128494a1158650665bc875bbc02de3cac75963cee5c10075768d921edacb382044c74848af73092641a57c2050ea0e68dbb6c6121b1bf012073c8812d68fac75a06a8a35bec984c71ff951eb3ef18e96e1158f653a837a9fec2df21cdd816d36bf998ee108b562a60a6")
      onionForBob
    }

    // Bob decrypts the onion and relays to Carol.
    val onionForCarol = {
      val bobPriv = PrivateKey(hex"4242424242424242424242424242424242424242424242424242424242424242")
      val add = UpdateAddHtlc(randomBytes32(), 1, 150_155_000 msat, paymentHash, CltvExpiry(800_100), onionForBob, None, 1.0, None)
      val Right(packetForBob: ChannelRelayPacket) = decrypt(add, bobPriv, Features.empty)
      assert(packetForBob.payload.outgoing.contains(ShortChannelId.fromCoordinates("572330x42x2821").get))
      assert(packetForBob.amountToForward == 150_153_000.msat)
      assert(packetForBob.outgoingCltv == CltvExpiry(800_060))
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(packetForBob.nextPacket).require.bytes
      assert(encoded == hex"0003dc6a4c9b34bdcd2191fc4dacfc1aeb20f71991acbd17847b9ab17d69579c1614da126cd18820e55534d7352839caa436aa79a6d5be26c6ecbd1c79f74442b1b7bf8ed8b739b736b9248769c2c422eebc85fb0d580e95618bcda1be3fd4cfa6ed0d839a2feb878acf686b112febaae9c1494a2ad20d97b2d2f7e54e6e9860e75e35671d5530ff9cf131b16b00a89337781aa37e5b867995d56578e69c031b7b272c4697727210e10bc8456e5cd58ae958d07e2811e2fb767b702c792b26fd6c352306b31f808a0e46d28ababda518d0d33c8f3a301adef4dd4f12fd2f78da4d548b7c12b0d890b6ab24e724e569106ae47b1acea4f5de055ba6d910bbe824810a11349ce7ea557abf02c5104740b52c910cd0bfea5d8666a41448703c054ed0612775e8617eda8df2fdccebf65193301738ba4308b61f447016a0b801de0eff2a7db374e6ccdadc9efbcb2fe0fb56c34fbaffd1bf87e5bf46cca75b77cdb7161532402fbc9323af57304e0b6cfa5082af683ef82a2731e89734b9e377184c647486c63ab57e18d4f42e9ddd55189a064cfc3a2800b8abd291043aab068c8c8ce57f17ef169945bba4d434d67bc883b68ba2c2c92dad42c788a209b4d7a2e00b375b811766ff67fc630ae047b8d2781e00291f6d1e31495a797a7e4ed135585da237cdcd067d37641e49f562f22dd619240bb2411fab802f834d96aa6451fd4b3f585dbde15bc78e692f49a491dcd8e44a8ef035bcbb4863462d7cf6066e0df516dcb6209674abe54e7d2faca26d17019bc2b6ec59ec94b51fd62064e7ff2230e73c375fadf7f305c307870a1d3dcb4eecddce6eeca54bff76b945823364ca823f7f3dc273c5eaa6d7aa3b510bf3bd274c8bd73570d15bc1ff0ba90c3bb65a8e1ee89cc6c44114d658fb89985c7ca8e8eeb32bc8be1e3ab951ff1a720bcc0d4c298ae4c1d06c164615c8af5bbda93f431d5d2be8bc40320c9c3a002bd9f2e39828abb6e7bfde83421d7faed6b16f355b9bd86d018fb3ec0f98ffdcaae8d521bd5003e93382459fc7957e2590409e5c8a88d7c1488884da0e148b01ec99aafe96d418d7cd76d7437d3c1d9d79e79386e3286210fac073eac6cd90031ac1c5b70b494d60e74d243ee44bfb8d0fcc57d3f8683aaadc5a2d346fce681a8d4a4931e932a39e2ab443141eb5c29a475679c5ee4e8b94e9d5de731f03963acaddd7301be90c7ccdfab314f70e843037a98656c31b22c822312719434f7a503bac9f18eb2f0cbc2c2790e93fc1664b82726eb1265a4ffa1e8e72d2898df1d8db9da1586675d242ff565aa008a35aad1c65b50c07ae6c0452bcdaa2f5410600acb3326e335971eba42c1dbac36005b5299ab7b852812717048aa51f272e8ec21c11e22a25b48ef60ed98540d879f5ae6820ac94cfa29e5d0aa74d91ca30ee28e97cd94968b4f246b3f93f36ecbf1c84f12844867f0738c3c775981a827cb05ddc5bebd288b6312b0b3f7d46f6eb4ddaf91e7c6a3afdbc291ac5a151675f3c4ae23ab301a9c3f5e1ba62aef64dc50cd977a34ffe58a78feda76c27cc3d5a3a1e05303e9cdd72d60ed17cc90c88b015f3c4891651537b52d837ef0d5f9a90b01e05a9339a623034aea961f7bdc148f129f61f7e12d4ebd1ed37565935cdaec4ea6b7020e62d5db3bad4a3b1141ec3c78d679498bbb348091f56279a3c01662db7694ba54efd8d8f1271f4b06cba94804c3197f92ea97e93bdef8fcb348a405792855e84c1c9625153187495825c5a293e1efc7b672ddb609aa90caca1e7182ba301313a17364b48cd93b6a26cb6888d5c4cc1d3c6a39cb442c2de12bb1ad396205c9a10023d7edc951fc60a28813867f635c3d74d4206f398849e65eb5a8d8fdeb952ae813073c3b617ed68c7bf1a18a6b9f9e3af316029be4dd8")
      packetForBob.nextPacket
    }

    // Carol decrypts the onion and relays to the blinded path's introduction node Dave.
    val onionForDave = {
      val carolPriv = PrivateKey(hex"4343434343434343434343434343434343434343434343434343434343434343")
      val dave = PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
      val add = UpdateAddHtlc(randomBytes32(), 1, 150_153_000 msat, paymentHash, CltvExpiry(800_060), onionForCarol, None, 1.0, None)
      val Right(RelayToBlindedPathsPacket(_, payload, trampolinePayload, _)) = decrypt(add, carolPriv, Features(Features.RouteBlinding -> FeatureSupport.Optional))
      assert(payload.amount == 150_153_000.msat)
      assert(payload.expiry == CltvExpiry(800_060))
      assert(payload.paymentSecret == ByteVector32.fromValidHex("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da"))
      assert(trampolinePayload.invoiceFeatures == Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional).toByteVector)
      assert(trampolinePayload.outgoingBlindedPaths.length == 1)
      assert(trampolinePayload.amountToForward == 150_000_000.msat)
      assert(trampolinePayload.outgoingCltv == CltvExpiry(800_000))
      val outgoingPath = trampolinePayload.outgoingBlindedPaths.head
      assert(outgoingPath.paymentInfo == blindedPath.paymentInfo)
      assert(outgoingPath.route == blindedPath.route)
      val sessionKey = PrivateKey(hex"e4acea94d5ddce1a557229bc39f8953ec1398171f9c2c6bb97d20152933be4c4")
      val payloadDave = PaymentOnion.OutgoingBlindedPerHopPayload.createIntroductionPayload(outgoingPath.route.encryptedPayloads.head, outgoingPath.route.firstPathKey)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadDave.records).require.bytes == hex"50 0a2b0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86 0c2102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e")
      val payloadEve = PaymentOnion.OutgoingBlindedPerHopPayload.createFinalPayload(trampolinePayload.amountToForward, trampolinePayload.amountToForward, trampolinePayload.outgoingCltv, outgoingPath.route.encryptedPayloads.last)
      assert(PaymentOnionCodecs.perHopPayloadCodec.encode(payloadEve.records).require.bytes == hex"e4 020408f0d180 04030c3500 0ad1bcd747ba974bc6ac175df8d5dbd462acb1dc4f3fa1de21da4c5774d233d8ecd9b84b7420175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d59347cc1c013a2351f094cdafb5e0d1f5ccb1055d6a5dd086a69cd75d34ea06067659cb7bb02dda9c2d89978dc725168f93ab2fe22dff354bce6017b60d0cc5b29b01540595e6d024f3812adda1960b4d 120408f0d180")
      val onionForDave = OutgoingPaymentPacket.buildOnion(sessionKey, NodePayload(dave, payloadDave) :: NodePayload(outgoingPath.route.blindedNodeIds.last, payloadEve) :: Nil, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get.packet
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(onionForDave).require.bytes
      assert(encoded == hex"0003626d5fa7ed0b8706f975ca58ae2c645889514153568aaab7835325ecd3739a5760171be581c1df1ce5267cd012e06f45a97fdbc5d8fc0c140c7432d34027b9ed0f2ab09fd388fe47525309f9c4509a7b9d74d88c28e40b1b6598e42fe2975f857e28b224316f266decf170cbfb5019dea2dab2ee7f1db089d44d1f974d6974bd06e515510cc1c178cd46c2442f07b9a083b4e4c7e9dd8d728508353959497fb8d25ebe8db83c60488566952fb1725267088ccb98acace7147d3846388464aa9512fe94f1962a54d8896d94105b185a41201e0dacf51f755da8e666a78261bc478ca77bd0ef5576bd7a4b24bb1fee9a97618bac0dcc4f1a34d64f5623446e2458089299be2f07592d69619bd6048c37a0460062b194f6f05da8f4ac1c5ff19681067398fdde459c60b4f448d5b3c1152988f6e29dc73b3a5407f1a502dcf2d656bcdba5f05eb4a7ecd3a1373c495dbc23109912aa456f0d9c1460f99f8151886ec8a69af2ac3ed76823ce372fb46a3c20ff114c04ee4a16ff673382b1abedcfc5e8d6f6e77c893dc346cf01f323bb043840546f9728b060514ddc4359c3ebc818abe56c8219260e26c833ff6faef7c02a3e669026dcc0a96ca4f0f8240185422355e0a5c9529bf65e7c52b384cfbe2eeb3ba32c118cfb6961068362b7bf41b2b1580cfc85757fd294840154cb8b13df456c6b86957a33391fd78e3aec6ff2fb9dabeefc63dc4faadfc12016d9d9501381c9751c581256cc24f8d8fecec2a1efaa9579935d5f5f7d14edb64ce97ab9deacb5c9d4c111325c70493ad7921966369315a1ec09c320c3cdc7a65ce52ff8ba5e9a71326e57f8b30766e6e5c77747b07e351c3e91efdf736a31410c9e278a683dfe1ca9c35d7868f11e1e429cb7655ff126438c83f69c3db2c5257e03f7d4f95e8d49400ee36e8d7f1629ac79f0b63430b115349df21e8d286a69b41d52e52e36553b16edf4c77acfb9d4596abd5054daf076b06abb3f84ecbea3e6d324965c7667ec7f83388ee05583f53f258291a806c025e300d63c81f5a411447b3ab3ee47b2dae485b8a87129224ad16fcc043a2d1b89e5c4f35f02675efa79730f5ee07d2de9d6ab503aa329f201ad0c9040d8c3437efde15c53b9212e93e0ece4a3ee7ae99a18b3fd75e8d1ee0ce9c73bfd5c2bbe30a91a3f92169a05887069dd31edf575265425d09998e2466bdf86919cdbfbbdb55c718b046197028b4370dd850833853b969a37e31f2cce96020a1fb22959b4529ae501f44d989b3f7473aaa787899ba200468b070079e2b9a3cd6b04b3caf2de5956aed477e4b3a9f0c93ac3f1042d16ee6a36744460e6d86144522215eeed052daebc7861d7189abe78edb67dd7ae47224b9bdb5907fdca6e6573dfe4bcf24ce1c6a4dfbae6991a8ac6976d9ec8a81f08dbdc34bf1cb18d93aa2e9d876335e0fce0d7a7b6c7080a70b1fc9bef912e4550931005210da7c46c76cf63fb02202df35d332e9ad779ef5ee086fd9fa993852be315691cf84c7e588ec61726b9fe5200ad30b2d43b1684f1dcd8df3f1598ae3841125eadfd534b074d560fd8e0eb9660c93a478ccfdd2308f587a45d5b933af280d39a77e19cd72c170931e4c8e44028bb6db77ec1e9b77af225e39db67bfb80afc6a0efe9864a80222fbfd6c4b6ad9afd43c76f2b9fc0cd0a4b07939147b005be7e6418295830a9bb114cc2c40bdb715077ef4219643455f2675ba00c0e6464f612c32cffa39f49d80ff91cf1363e109101e368114537fbd94428b6ed1934f6d8cd3b6cf2ec736ddcacf63007481fcd6dd9fca8ee39d9a4bbdd06349a5e86af75d8723eeeddc6f84575516997f7db931b91007bcfd21f1b5a8dc69ee846492493054b012e5a4ff3707d5aae44a4ca65210eb1c14c8d138441170f2e5e2920c1e4")
      onionForDave
    }

    // Dave decrypts the onion and relays to Eve.
    val onionForEve = {
      val davePriv = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
      val add = UpdateAddHtlc(randomBytes32(), 1, 150_150_500 msat, paymentHash, CltvExpiry(800_036), onionForDave, None, 1.0, None)
      val Right(packetForDave: ChannelRelayPacket) = decrypt(add, davePriv, Features(Features.RouteBlinding -> FeatureSupport.Optional))
      assert(packetForDave.payload.isInstanceOf[PaymentOnion.IntermediatePayload.ChannelRelay.Blinded])
      assert(packetForDave.amountToForward == 150_000_000.msat)
      assert(packetForDave.outgoingCltv == CltvExpiry(800_000))
      val encoded = PaymentOnionCodecs.paymentOnionPacketCodec.encode(packetForDave.nextPacket).require.bytes
      assert(encoded == hex"0002580900a2090fc8b09d77f87781aa5a5964372ee968bd8488da62e04e3f1d68bda5d48cb395a094d2d60f43d8af709c5b44bba1c51f3d590c462829104a18ec68d5c36989a3d6af086f2f61e791e619fd62bf6fccdcdb1dc01bb1798bff5550d385a3ea26ce909d6d218eb12cfa089d11d33a1cb1299510a4c5ac1f767ee18230960b2a37994dc05378ca9d6ce8c29c61dc543f11b676f1ffd3c0c0fe7d43168ecaa1760b115d397b4886c17daaf8dabaac2c5ce3e57f7b5441130e828c5368eb605c841045d84d137197512d9a6efa3bb8fb05a70af7b14f5d01518a61932717ebd04e5022e6925767f07f33b63894bbaff907967999001d6b4cb4b3bc42a9057b8b1d269172f638275688915ae9c07d276fa8aaf037a59069c3d2121a79e8eb869ade6b1dc5073922145d7e1246baa202544d5045fca6fb2974dabc145257d32f0ab5afdbb121b9d93dc1b3d345038714d70ed941be5dec56d4c5cb0582ebcb2d4a78356f75bf1696f82deecec4a97a23746b440082e07ee7d5ebac4c098a48fe0d64de53b303b960b52aabe8df029b9cb5b6079ecac2a2841dd662a2099e1c5995176b9dbc90054b789a07cfbd35e93c0da58eefa7150f7c793b37f4934e2541d6002be6953a0dfda018a881b4d7458d04d4ece3d6d570f1ac46e2eb7ad29652adae7f56ca804d88a1a92ecc17bad4ab7879e93aa56782c46f8b0fca6068a5c3593cddcd372db066bbd7ea615a0fc8b01b61849930959d3ec7951d619b93fc9feecd07c91ec6206a8a489023a55349c1d0b6c294104190090c2c82f1e00c1cf4c9349b09544157aedfed527fa5725408d38d8026916f6baac3218ab5469e157bc91475a5117947efab4af7a64373694cc62b08e0b8bfe1a35ba2f80fac95e043a17e850590bbfbb59d4c190a4fa1642d790e3403a34522f33de66e839c3e3706f9bea9d95efca2f9c7b012bfc39cb9f3e7dad4a1c7b52a8d02151a1b3524a64033d2868e9c450d496f66d71c414870c15911dee4365f1aab8a20b3968a67d04dd724955b0396a00dbdbaa0c0037a2bb8202061f6cc653a10e6ed8ce98a5b1315d5efa96603e989ce1cf315cf2e300f12696c96e45efd397776f8a781d12b8d4e3f265e49c8932cf54525af20977ab1c5b5e0a4f929074baf6b0d4fde175d02a78e0fccd4e814c0a2139475ea16517c33389a41160014f537c43c818f70ba1b9503987885f634f93b995c04f7302d1ff85add09232a2d2be27fbbf98d754c6a0e2c32f66b2a2cd6d5feef4ad10b62303ce05049e862e96987defc569cf6406585fedcc4bf4981ad67cb6af242e25f9bf701e5236deb61305bd0c20c2bfa0d17d6519979f3085427dcad1677959fa40565e16f2feee4b4974de401123f4b3e0f0e740305cdecc8f4b65d638cdd5b1af0013d5806c9d6661b96954463adee45cbacf33c16e836d8e544cab9eb47f9f661d415772a9dae0d4c3ffb44015bc6921e05e6bd8c5159893fd7e5291f6e40c84db19266a35c666afb1ec16d8c4bc507b887df09a2c71a599dbcdf75ced11eb8cd9c65f05a14a3a381971e615bdece5946affe0dbdbbb54a777e5d996e9cb9a5163bc503b88b15b31cd0fa3a8206701aa9f4068e6baac2b2f342e02f94ed22f43f285a6790ff1e216c917af77b5af726e403ce8615959b31e6d051c0a17f737ffef28264ec31c3f0f690f0f142c0b16c88507a44714516fdaee00b697288fdfea823a30bf11fa6cf3ae2215eb42b98aae1e80444c6f2688a5f8f80f1236fb3d12584f33bdfc33beb8c5b7bfdfeb94e25ed4c1fdf69f4a28f6cbb7fa0fb9424927e195908d0a8894555d02f285962a53a984fca3f6b3fb843e4d559e5294c2e01dd1dce5692664881c4dec168d52e42981c6d72f0a84caa78ebf409cb62584ec539f89147c1")
      packetForDave.nextPacket
    }

    // Eve receives the payment.
    val pathKey = PublicKey(hex"02c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7")
    val add = UpdateAddHtlc(randomBytes32(), 1, 150_000_000 msat, paymentHash, CltvExpiry(800_000), onionForEve, Some(pathKey), 1.0, None)
    val Right(FinalPacket(_, payload, _)) = decrypt(add, evePriv, Features(Features.RouteBlinding -> FeatureSupport.Optional))
    assert(payload.isInstanceOf[FinalPayload.Blinded])
    assert(payload.asInstanceOf[FinalPayload.Blinded].pathId == pathId)
    assert(payload.asInstanceOf[FinalPayload.Blinded].amount == 150_000_000.msat)
    assert(payload.asInstanceOf[FinalPayload.Blinded].totalAmount == 150_000_000.msat)
    assert(payload.asInstanceOf[FinalPayload.Blinded].expiry == CltvExpiry(800_000))
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

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, 1.0, None)
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
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, 1.0)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
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
      val payload = PaymentOnion.FinalPayload.Standard.createLegacyTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, randomBytes32(), trampolineOnion.packet)
      val paymentOnion = buildOnion(NodePayload(c, payload) :: Nil, invoice.paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get
      TrampolinePayment.OutgoingPayment(trampolineAmount, trampolineExpiry, paymentOnion, trampolineOnion)
    }

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, 1.0, None)
    val Right(RelayToNonTrampolinePacket(_, outer_c, inner_c, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    assert(outer_c.amount == payment.trampolineAmount)
    assert(outer_c.totalAmount == payment.trampolineAmount)
    assert(outer_c.expiry == payment.trampolineExpiry)
    assert(outer_c.paymentSecret != invoice.paymentSecret)
    assert(outer_c.records.get[OnionPaymentPayloadTlv.LegacyTrampolineOnion].get.packet.payload.size < 400)
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
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, 1.0)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment_e.cmd.amount == amount_cd)
    assert(payment_e.cmd.cltvExpiry == expiry_cd)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(add_d2, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    assert(add_d2 == add_d)
    assert(payload_d == IntermediatePayload.ChannelRelay.Standard(channelUpdate_de.shortChannelId, amount_de, expiry_de))

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
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
    val Left(failure) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    assert(failure == InvalidRouteRecipient(e, d))
  }

  test("fail to build outgoing blinded payment with invalid route") {
    val (_, route, recipient) = longBlindedHops(hex"deadbeef")
    assert(buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0).isRight)
    val routeMissingBlindedHop = route.copy(finalHop_opt = None)
    val Left(failure) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, routeMissingBlindedHop, recipient, 1.0)
    assert(failure == MissingBlindedHop(Set(c)))
  }

  test("fail to decrypt when the onion is invalid") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, 1.0)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion.copy(payload = payment.cmd.onion.payload.reverse), None, 1.0, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when the trampoline onion is invalid") {
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, Features.TrampolinePayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures, paymentMetadata = Some(hex"010203"))
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)

    val add_c = UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, 1.0, None)
    val Right(RelayToTrampolinePacket(_, _, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid trampoline onion to e through d.
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e.copy(payload = trampolinePacket_e.payload.reverse)))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, 1.0)
    assert(payment_e.outgoingChannel == channelUpdate_cd.shortChannelId)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    val add_e = UpdateAddHtlc(randomBytes32(), 4, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure.isInstanceOf[InvalidOnionHmac])
  }

  test("fail to decrypt when payment hash doesn't match associated data") {
    val recipient = ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash.reverse, Route(finalAmount, hops, None), recipient, 1.0)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, 1.0, None)
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
      val paymentInfo = OfferTypes.PaymentInfo(fee_b, 0, channelUpdate_bc.cltvExpiryDelta, 0 msat, amount_bc, Features.empty)
      val invoice = Bolt12Invoice(invoiceRequest, paymentPreimage, priv_c.privateKey, 300 seconds, features, Seq(PaymentBlindedRoute(blindedRoute, paymentInfo)))
      val resolvedPaths = invoice.blindedPaths.map(path => {
        val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
        ResolvedPath(FullBlindedRoute(introductionNodeId, path.route.firstPathKey, path.route.blindedHops), path.paymentInfo)
      })
      val recipient = BlindedRecipient(invoice, resolvedPaths, amount_bc, expiry_bc, Set.empty)
      val route = Route(amount_bc, Seq(channelHopFromUpdate(a, b, channelUpdate_ab)), Some(recipient.blindedHops.head))
      (route, recipient)
    }
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    assert(payment.outgoingChannel == channelUpdate_ab.shortChannelId)
    assert(payment.cmd.amount == amount_bc + fee_b)

    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Left(failure) = decrypt(add_b, priv_b.privateKey, Features(RouteBlinding -> Optional))
    assert(failure.isInstanceOf[InvalidOnionBlinding])
  }

  test("fail to decrypt blinded payment when route blinding is disabled") {
    val (route, recipient) = shortBlindedHops()
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    val add_d = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Left(failure) = decrypt(add_d, priv_d.privateKey, Features.empty) // d doesn't support route blinding
    assert(failure == InvalidOnionPayload(UInt64(10), 0))
  }

  test("fail to decrypt at the final node when amount has been modified by next-to-last node") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.take(1), None)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount - 100.msat, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, 1.0, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(payment.cmd.amount - 100.msat))
  }

  test("fail to decrypt at the final node when expiry has been modified by next-to-last node") {
    val recipient = ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret)
    val route = Route(finalAmount, hops.take(1), None)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    val add = UpdateAddHtlc(randomBytes32(), 1, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry - CltvExpiryDelta(12), payment.cmd.onion, None, 1.0, None)
    val Left(failure) = decrypt(add, priv_b.privateKey, Features.empty)
    assert(failure == FinalIncorrectCltvExpiry(payment.cmd.cltvExpiry - CltvExpiryDelta(12)))
  }

  test("fail to decrypt blinded payment at the final node when expiry is too low") {
    val (route, recipient) = shortBlindedHops()
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    assert(payment.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment.cmd.cltvExpiry == expiry_cd)

    // A smaller expiry is sent to d, who doesn't know that it's invalid.
    // Intermediate nodes can reduce the expiry by at most min_final_expiry_delta.
    val invalidExpiry = payment.cmd.cltvExpiry - Channel.MIN_CLTV_EXPIRY_DELTA - CltvExpiryDelta(1)
    val add_d = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, paymentHash, invalidExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    assert(payload_d.outgoing.contains(channelUpdate_de.shortChannelId))
    assert(relay_d.outgoingCltv < CltvExpiry(currentBlockCount))
    assert(payload_d.isInstanceOf[IntermediatePayload.ChannelRelay.Blinded])
    val pathKey_e = payload_d.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey

    // When e receives a smaller expiry than expected, it rejects the payment.
    val add_e = UpdateAddHtlc(randomBytes32(), 0, relay_d.amountToForward, paymentHash, relay_d.outgoingCltv, packet_e, Some(pathKey_e), 1.0, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features(RouteBlinding -> Optional))
    assert(failure.isInstanceOf[InvalidOnionBlinding])
  }

  test("fail to decrypt blinded payment at intermediate node when expiry is too high") {
    val routeExpiry = expiry_de - channelUpdate_de.cltvExpiryDelta
    val (route, recipient) = shortBlindedHops(routeExpiry)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    assert(payment.outgoingChannel == channelUpdate_cd.shortChannelId)
    assert(payment.cmd.cltvExpiry > expiry_de)

    val add_d = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
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
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, Features.TrampolinePayment -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_e.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures)
    val payment = TrampolinePayment.buildOutgoingPayment(c, invoice, finalExpiry)
    UpdateAddHtlc(randomBytes32(), 2, payment.trampolineAmount, paymentHash, payment.trampolineExpiry, payment.onion.packet, None, 1.0, None)
  }

  test("fail to decrypt at the final trampoline node when amount has been decreased by next-to-last trampoline") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(RelayToTrampolinePacket(_, _, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid amount to e through (the outer total amount doesn't match the inner amount).
    val invalidTotalAmount = inner_c.amountToForward - 1.msat
    val recipient_e = ClearRecipient(e, Features.empty, invalidTotalAmount, inner_c.outgoingCltv, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(invalidTotalAmount, afterTrampolineChannelHops, None), recipient_e, 1.0)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    val add_e = UpdateAddHtlc(randomBytes32(), 4, payload_d.amountToForward(add_d.amountMsat), paymentHash, payload_d.outgoingCltv(add_d.cltvExpiry), packet_e, None, 1.0, None)
    val Left(failure) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(failure == FinalIncorrectHtlcAmount(invalidTotalAmount))
  }

  test("fail to decrypt at the final trampoline node when expiry has been modified by next-to-last trampoline") {
    val add_c = createIntermediateTrampolinePayment()
    val Right(RelayToTrampolinePacket(_, _, inner_c, trampolinePacket_e, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)

    // c forwards an invalid amount to e through (the outer expiry doesn't match the inner expiry).
    val invalidExpiry = inner_c.outgoingCltv - CltvExpiryDelta(12)
    val recipient_e = ClearRecipient(e, Features.empty, inner_c.amountToForward, invalidExpiry, randomBytes32(), nextTrampolineOnion_opt = Some(trampolinePacket_e))
    val Right(payment_e) = buildOutgoingPayment(Origin.Hot(ActorRef.noSender, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_c, TimestampMilli(1687345927000L), b)))), paymentHash, Route(inner_c.amountToForward, afterTrampolineChannelHops, None), recipient_e, 1.0)
    val add_d = UpdateAddHtlc(randomBytes32(), 3, payment_e.cmd.amount, paymentHash, payment_e.cmd.cltvExpiry, payment_e.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)

    val add_e = UpdateAddHtlc(randomBytes32(), 4, payload_d.amountToForward(add_d.amountMsat), paymentHash, payload_d.outgoingCltv(add_d.cltvExpiry), packet_e, None, 1.0, None)
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, 1.0)
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, payment.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    val add_e = UpdateAddHtlc(randomBytes32(), 3, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), recipient, 1.0)
    val add_b = UpdateAddHtlc(randomBytes32(), 0, amount_ab, paymentHash, expiry_ab, payment.cmd.onion, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features.empty)
    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, None, 1.0, None)
    val Right(ChannelRelayPacket(_, _, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features.empty)
    val add_e = UpdateAddHtlc(randomBytes32(), 3, amount_de, paymentHash, expiry_de, packet_e, None, 1.0, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e.privateKey, Features.empty)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])

    // e returns a failure
    val failure = IncorrectOrUnknownPaymentDetails(finalAmount, BlockHeight(currentBlockCount))
    val Right(fail_e: UpdateFailHtlc) = buildHtlcFailure(priv_e.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_e.id, FailureReason.LocalFailure(failure), Some(TimestampMilli(672))), add_e, now = TimestampMilli(735))
    assert(fail_e.id == add_e.id)
    val Right(fail_d: UpdateFailHtlc) = buildHtlcFailure(priv_d.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_d.id, FailureReason.EncryptedDownstreamFailure(fail_e.reason, fail_e.attribution_opt), Some(TimestampMilli(349))), add_d, now = TimestampMilli(844))
    assert(fail_d.id == add_d.id)
    val Right(fail_c: UpdateFailHtlc) = buildHtlcFailure(priv_c.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_c.id, FailureReason.EncryptedDownstreamFailure(fail_d.reason, fail_d.attribution_opt), Some(TimestampMilli(295))), add_c, now = TimestampMilli(912))
    assert(fail_c.id == add_c.id)
    val Right(fail_b: UpdateFailHtlc) = buildHtlcFailure(priv_b.privateKey, useAttributableFailures = true, CMD_FAIL_HTLC(add_b.id, FailureReason.EncryptedDownstreamFailure(fail_c.reason, fail_c.attribution_opt), Some(TimestampMilli(0))), add_b, now = TimestampMilli(1265))
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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, 1.0)
    val add_b = UpdateAddHtlc(randomBytes32(), 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, 1.0, payment.cmd.fundingFee_opt)
    val Right(ChannelRelayPacket(_, _, packet_c, _)) = decrypt(add_b, priv_b.privateKey, Features.empty)
    val add_c = UpdateAddHtlc(randomBytes32(), 1, amount_bc, paymentHash, expiry_bc, packet_c, None, 1.0, None)
    val Right(ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c.privateKey, Features(RouteBlinding -> Optional))
    val pathKey_d = payload_c.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey
    val add_d = UpdateAddHtlc(randomBytes32(), 2, amount_cd, paymentHash, expiry_cd, packet_d, Some(pathKey_d), 1.0, None)
    val Right(ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d.privateKey, Features(RouteBlinding -> Optional))
    val pathKey_e = payload_d.asInstanceOf[IntermediatePayload.ChannelRelay.Blinded].nextPathKey
    val add_e = UpdateAddHtlc(randomBytes32(), 3, amount_de, paymentHash, expiry_de, packet_e, Some(pathKey_e), 1.0, None)
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
    val localParams = LocalParams(null, null, null, UInt64.MaxValue, Some(channelReserve), null, null, 0, isChannelOpener = true, paysCommitTxFees = true, None, None, Features.empty)
    val remoteParams = RemoteParams(randomKey().publicKey, null, UInt64.MaxValue, Some(channelReserve), null, null, maxAcceptedHtlcs = 0, null, null, null, null, null, None)
    val fundingTx = Transaction(2, Nil, Seq(TxOut(testCapacity, Nil)), 0)
    val commitInput = InputInfo(OutPoint(fundingTx, 0), fundingTx.txOut.head, ByteVector.empty)
    val localCommit = LocalCommit(0, null, randomTxId(), commitInput, IndividualSignature(ByteVector64.Zeroes), Nil)
    val remoteCommit = RemoteCommit(0, null, randomTxId(), randomKey().publicKey)
    val localChanges = LocalChanges(Nil, Nil, Nil)
    val remoteChanges = RemoteChanges(Nil, Nil, Nil)
    val localFundingStatus = announcement_opt match {
      case Some(ann) => LocalFundingStatus.ConfirmedFundingTx(fundingTx, ann.shortChannelId, None, None)
      case None => LocalFundingStatus.SingleFundedUnconfirmedFundingTx(None)
    }
    val channelFlags = ChannelFlags(announceChannel = announcement_opt.nonEmpty)
    new Commitments(
      ChannelParams(channelId, ChannelConfig.standard, channelFeatures, localParams, remoteParams, channelFlags),
      CommitmentChanges(localChanges, remoteChanges, 0, 0),
      List(Commitment(0, 0, null, localFundingStatus, RemoteFundingStatus.Locked, localCommit, remoteCommit, None)),
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
    val cmd = CMD_ADD_HTLC(ActorRef.noSender, finalAmount, paymentHash, finalExpiry, onion.packet, Some(blindedRoute.firstPathKey), 1.0, None, TestConstants.emptyOrigin, commit = true)
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
