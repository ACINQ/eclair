/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.payment.send

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.OutgoingPaymentPacket.{NodePayload, PaymentPayloads}
import fr.acinq.eclair.payment.{Bolt11Invoice, Bolt12Invoice, Invoice, OutgoingPaymentPacket}
import fr.acinq.eclair.router.Router.{BlindedHop, ChannelHop, NodeHop, Route}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, OutgoingBlindedPerHopPayload}
import fr.acinq.eclair.wire.protocol.{GenericTlv, OnionRoutingPacket, PaymentOnionCodecs}
import fr.acinq.eclair.{CltvExpiry, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId}
import scodec.bits.ByteVector

import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 28/10/2022.
 */

sealed trait Recipient {
  /** Id of the final receiving node. */
  def nodeId: PublicKey

  /** Total amount to send to the final receiving node. */
  def totalAmount: MilliSatoshi

  /** Expiry at the receiving node (CLTV for the receiving node's received HTLCs). */
  def expiry: CltvExpiry

  /** Features supported by the recipient. */
  def features: Features[InvoiceFeature]

  /** Edges that aren't part of the public graph and can be used to reach the recipient. */
  def extraEdges: Seq[Invoice.ExtraEdge]

  /** Build a payment to the recipient using the route provided. */
  def buildPayloads(paymentHash: ByteVector32, route: Route): Try[PaymentPayloads]
}

object Recipient {
  def verifyChannelHopsOnly(route: Route): Try[Seq[ChannelHop]] = {
    val channelHops = route.hops.collect { case hop: ChannelHop => hop }
    if (channelHops.length != route.hops.length) {
      Failure(new IllegalArgumentException(s"cannot send payment: route contains non-channel hops (${route.printChannels()})"))
    } else {
      Success(channelHops)
    }
  }
}

/** A payment recipient that can directly be found in the routing graph. */
case class ClearRecipient(nodeId: PublicKey,
                          features: Features[InvoiceFeature],
                          totalAmount: MilliSatoshi,
                          expiry: CltvExpiry,
                          paymentSecret: ByteVector32,
                          extraEdges: Seq[Invoice.ChannelEdge] = Nil,
                          paymentMetadata_opt: Option[ByteVector] = None,
                          nextTrampolineOnion_opt: Option[OnionRoutingPacket] = None,
                          customTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override def buildPayloads(paymentHash: ByteVector32, route: Route): Try[PaymentPayloads] = {
    Recipient.verifyChannelHopsOnly(route).map(hops => {
      val finalPayload = nextTrampolineOnion_opt match {
        case Some(trampolinePacket) => NodePayload(nodeId, FinalPayload.Standard.createTrampolinePayload(route.amount, totalAmount, expiry, paymentSecret, trampolinePacket))
        case None => NodePayload(nodeId, FinalPayload.Standard.createPayload(route.amount, totalAmount, expiry, paymentSecret, paymentMetadata_opt, customTlvs))
      }
      OutgoingPaymentPacket.buildPayloads(route.amount, expiry, finalPayload, hops)
    })
  }
}

object ClearRecipient {
  def apply(invoice: Bolt11Invoice, totalAmount: MilliSatoshi, expiry: CltvExpiry, customTlvs: Seq[GenericTlv]): ClearRecipient = {
    ClearRecipient(invoice.nodeId, invoice.features, totalAmount, expiry, invoice.paymentSecret, invoice.extraEdges, invoice.paymentMetadata, None, customTlvs)
  }
}

/** A payment recipient that doesn't expect to receive a payment and can directly be found in the routing graph. */
case class SpontaneousRecipient(nodeId: PublicKey,
                                amount: MilliSatoshi,
                                expiry: CltvExpiry,
                                preimage: ByteVector32,
                                customTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override val totalAmount = amount
  override val features = Features.empty
  override val extraEdges = Nil

  override def buildPayloads(paymentHash: ByteVector32, route: Route): Try[PaymentPayloads] = {
    Recipient.verifyChannelHopsOnly(route).map(hops => {
      val finalPayload = NodePayload(nodeId, FinalPayload.Standard.createKeySendPayload(route.amount, amount, expiry, preimage, customTlvs))
      OutgoingPaymentPacket.buildPayloads(amount, expiry, finalPayload, hops)
    })
  }
}

/** A payment recipient that hides its real identity using route blinding. */
case class BlindedRecipient(nodeId: PublicKey,
                            features: Features[InvoiceFeature],
                            totalAmount: MilliSatoshi,
                            expiry: CltvExpiry,
                            extraEdges: Seq[Invoice.BlindedEdge],
                            customTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override def buildPayloads(paymentHash: ByteVector32, route: Route): Try[PaymentPayloads] = {
    extractRoute(route).map { case (channelHops, blindedHop) =>
      val introductionPayload = NodePayload(blindedHop.route.introductionNodeId, OutgoingBlindedPerHopPayload.createIntroductionPayload(blindedHop.route.introductionNode.encryptedPayload, blindedHop.route.introductionNode.blindingEphemeralKey))
      val intermediateBlindedPayloads = blindedHop.route.subsequentNodes.dropRight(1).map(n => NodePayload(n.blindedPublicKey, OutgoingBlindedPerHopPayload.createIntermediatePayload(n.encryptedPayload)))
      val finalBlindedPayload = NodePayload(blindedHop.route.blindedNodes.last.blindedPublicKey, OutgoingBlindedPerHopPayload.createFinalPayload(route.amount, totalAmount, expiry, blindedHop.route.blindedNodes.last.encryptedPayload, customTlvs))
      val introductionAmount = route.amount + blindedHop.paymentInfo.fee(route.amount)
      val introductionExpiry = expiry + blindedHop.paymentInfo.cltvExpiryDelta
      val blindedPayloads = PaymentPayloads(introductionAmount, introductionExpiry, introductionPayload +: intermediateBlindedPayloads :+ finalBlindedPayload)
      if (channelHops.isEmpty) {
        // We are the introduction node of the blinded route.
        blindedPayloads
      } else {
        channelHops.tail.reverse.foldLeft(blindedPayloads) {
          case (current, channelHop) =>
            val payload = NodePayload(channelHop.nodeId, IntermediatePayload.ChannelRelay.Standard(channelHop.shortChannelId, current.amount, current.expiry))
            PaymentPayloads(current.amount + channelHop.fee(current.amount), current.expiry + channelHop.cltvExpiryDelta, payload +: current.payloads)
        }
      }
    }
  }

  private def extractRoute(route: Route): Try[(Seq[ChannelHop], BlindedHop)] = {
    // The payment route must start with channel hops to reach the introduction node of a blinded route.
    val channelHops = route.hops.dropRight(1).collect { case hop: ChannelHop => hop }
    val blindedHop_opt = route.hops.takeRight(1).collectFirst { case hop: BlindedHop => hop }
    blindedHop_opt match {
      case Some(blindedHop) if channelHops.length == route.hops.length - 1 => Success((channelHops, blindedHop))
      case _ => Failure(new IllegalArgumentException(s"cannot send payment: invalid blinded route (${route.printChannels()})"))
    }
  }
}

object BlindedRecipient {
  def apply(invoice: Bolt12Invoice, totalAmount: MilliSatoshi, expiry: CltvExpiry, customTlvs: Seq[GenericTlv]): BlindedRecipient = {
    val extraEdges = invoice.blindedPaths.zip(invoice.blindedPathsInfo).map {
      case (route, info) =>
        // We don't know the scids of channels inside the blinded route, but it's useful to have an ID to refer to a given
        // edge in the graph, so we create a dummy one for the duration of the payment attempt.
        val dummyId = ShortChannelId.generateLocalAlias()
        Invoice.BlindedEdge(dummyId, invoice.nodeId, route, info)
    }
    BlindedRecipient(invoice.nodeId, invoice.features, totalAmount, expiry, extraEdges, customTlvs)
  }
}

sealed trait TrampolineRecipient extends Recipient {
  // @formatter:off
  def trampolineNodeId: PublicKey
  def trampolineFees: MilliSatoshi
  def trampolineAmount: MilliSatoshi
  def trampolineExpiry: CltvExpiry
  // @formatter:on
}

/**
 * A payment recipient that can be paid without full knowledge of the routing graph.
 * We do not yet support splitting a payment across multiple trampoline routes.
 */
case class ClearTrampolineRecipient(invoice: Bolt11Invoice,
                                    totalAmount: MilliSatoshi,
                                    expiry: CltvExpiry,
                                    trampolineRoute: Seq[NodeHop],
                                    trampolinePaymentSecret: ByteVector32,
                                    customTlvs: Seq[GenericTlv] = Nil) extends TrampolineRecipient {
  require(trampolineRoute.nonEmpty, "trampoline route must be provided to reach a trampoline recipient")
  require(trampolineRoute.last.nextNodeId == invoice.nodeId, "trampoline route must end at the recipient")

  override val nodeId = invoice.nodeId
  override val features = invoice.features
  override val extraEdges = Nil

  override val trampolineNodeId = trampolineRoute.head.nodeId
  override val trampolineFees = trampolineRoute.map(_.fee).sum
  override val trampolineAmount = totalAmount + trampolineFees
  override val trampolineExpiry = trampolineRoute.foldLeft(expiry) { case (current, hop) => current + hop.cltvExpiryDelta }

  override def buildPayloads(paymentHash: ByteVector32, route: Route): Try[PaymentPayloads] = {
    require(route.hops.last.nextNodeId == trampolineNodeId, "route must reach the desired trampoline node")
    Recipient.verifyChannelHopsOnly(route).flatMap(hops => {
      createTrampolinePacket(paymentHash).map { case Sphinx.PacketAndSecrets(trampolinePacket, _) =>
        val trampolinePayload = NodePayload(trampolineNodeId, FinalPayload.Standard.createTrampolinePayload(route.amount, trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolinePacket))
        OutgoingPaymentPacket.buildPayloads(trampolineAmount, trampolineExpiry, trampolinePayload, hops)
      }
    })
  }

  def createTrampolinePacket(paymentHash: ByteVector32): Try[Sphinx.PacketAndSecrets] = {
    if (invoice.features.hasFeature(Features.TrampolinePaymentPrototype)) {
      // This is the payload the final recipient will receive, so we use the invoice's payment secret.
      val finalPayload = NodePayload(nodeId, FinalPayload.Standard.createPayload(totalAmount, totalAmount, expiry, invoice.paymentSecret, invoice.paymentMetadata, customTlvs))
      val payloads = trampolineRoute.reverse.foldLeft(PaymentPayloads(totalAmount, expiry, Seq(finalPayload))) {
        case (current, hop) =>
          val payload = NodePayload(hop.nodeId, IntermediatePayload.NodeRelay.Standard(current.amount, current.expiry, hop.nextNodeId))
          PaymentPayloads(current.amount + hop.fee, current.expiry + hop.cltvExpiryDelta, payload +: current.payloads)
      }.payloads
      OutgoingPaymentPacket.buildOnion(PaymentOnionCodecs.trampolineOnionPayloadLength, payloads, paymentHash)
    } else {
      // The recipient doesn't support trampoline: the next-to-last node in the trampoline route will convert the
      // payment to a non-trampoline payment. The final payload will thus never reach the recipient, so we create the
      // smallest payload possible to avoid overflowing the trampoline onion size.
      val dummyFinalPayload = NodePayload(nodeId, IntermediatePayload.ChannelRelay.Standard(ShortChannelId(0), 0 msat, CltvExpiry(0)))
      val lastTrampolinePayload = NodePayload(trampolineRoute.last.nodeId, IntermediatePayload.NodeRelay.Standard.createNodeRelayToNonTrampolinePayload(totalAmount, totalAmount, expiry, nodeId, invoice))
      val lastTrampolineAmount = totalAmount + trampolineRoute.last.fee
      val lastTrampolineExpiry = expiry + trampolineRoute.last.cltvExpiryDelta
      val payloads = trampolineRoute.reverse.tail.foldLeft(PaymentPayloads(lastTrampolineAmount, lastTrampolineExpiry, Seq(lastTrampolinePayload, dummyFinalPayload))) {
        case (current, hop) =>
          val payload = NodePayload(hop.nodeId, IntermediatePayload.NodeRelay.Standard(current.amount, current.expiry, hop.nextNodeId))
          PaymentPayloads(current.amount + hop.fee, current.expiry + hop.cltvExpiryDelta, payload +: current.payloads)
      }.payloads
      OutgoingPaymentPacket.buildOnion(PaymentOnionCodecs.trampolineOnionPayloadLength, payloads, paymentHash)
    }
  }
}
