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
        NodePayload(carol, PaymentOnion.FinalPayload.Standard.createTrampolinePayload(100_005_000 msat, 100_005_000 msat, CltvExpiry(800_250), paymentSecret, trampolineOnionForCarol)),
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
        NodePayload(eve, PaymentOnion.FinalPayload.Standard.createTrampolinePayload(100_000_000 msat, 100_000_000 msat, CltvExpiry(800_000), paymentSecret, trampolineOnionForEve)),
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
    val add = UpdateAddHtlc(randomBytes32(), 1, 100_000_000.msat, paymentHash, CltvExpiry(800_000), onionForEve, None, 1.0, None)
    val Right(FinalPacket(_, payloadForEve, _)) = decrypt(add, priv, Features.empty)
    assert(payloadForEve.isInstanceOf[FinalPayload.Standard])
    assert(payloadForEve.amount == 100_000_000.msat)
    assert(payloadForEve.expiry == CltvExpiry(800_000))
    assert(payloadForEve.asInstanceOf[FinalPayload.Standard].paymentSecret
      == ByteVector32.fromValidHex("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"))
    assert(payloadForEve.asInstanceOf[FinalPayload.Standard].isTrampoline)
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
