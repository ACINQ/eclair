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
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.OutgoingPaymentPacket._
import fr.acinq.eclair.payment.send.BlindedPathsResolver.{PartialBlindedRoute, ResolvedPath}
import fr.acinq.eclair.payment.{Bolt11Invoice, Bolt12Invoice}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, OutgoingBlindedPerHopPayload}
import fr.acinq.eclair.wire.protocol.{GenericTlv, OnionRoutingPacket}
import fr.acinq.eclair.{CltvExpiry, Features, InvoiceFeature, MilliSatoshi, ShortChannelId}
import scodec.bits.ByteVector

/**
 * Created by t-bast on 28/10/2022.
 */

sealed trait Recipient {
  /** Id of the final receiving node. */
  def nodeId: PublicKey

  /** Total amount that will be received by the final receiving node. */
  def totalAmount: MilliSatoshi

  /** CLTV expiry that will be received by the final receiving node. */
  def expiry: CltvExpiry

  /** Features supported by the recipient. */
  def features: Features[InvoiceFeature]

  /** Edges that aren't part of the public graph and can be used to reach the recipient. */
  def extraEdges: Seq[ExtraEdge]

  /** Build a payment to the recipient using the route provided. */
  def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads]
}

object Recipient {
  /** Iteratively build all the payloads for a payment relayed through channel hops. */
  def buildPayloads(finalPayloads: PaymentPayloads, hops: Seq[ChannelHop]): PaymentPayloads = {
    // We ignore the first hop since the route starts at our node.
    hops.tail.foldRight(finalPayloads) {
      case (hop, current) =>
        val payload = NodePayload(hop.nodeId, IntermediatePayload.ChannelRelay.Standard(hop.shortChannelId, current.amount, current.expiry))
        PaymentPayloads(current.amount + hop.fee(current.amount), current.expiry + hop.cltvExpiryDelta, payload +: current.payloads, None)
    }
  }
}

/** A payment recipient that can directly be found in the routing graph. */
case class ClearRecipient(nodeId: PublicKey,
                          features: Features[InvoiceFeature],
                          totalAmount: MilliSatoshi,
                          expiry: CltvExpiry,
                          paymentSecret: ByteVector32,
                          extraEdges: Seq[ExtraEdge] = Nil,
                          paymentMetadata_opt: Option[ByteVector] = None,
                          nextTrampolineOnion_opt: Option[OnionRoutingPacket] = None,
                          customTlvs: Set[GenericTlv] = Set.empty) extends Recipient {
  override def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads] = {
    ClearRecipient.validateRoute(nodeId, route).map(_ => {
      val finalPayload = nextTrampolineOnion_opt match {
        case Some(trampolinePacket) => NodePayload(nodeId, FinalPayload.Standard.createTrampolinePayload(route.amount, totalAmount, expiry, paymentSecret, trampolinePacket))
        case None => NodePayload(nodeId, FinalPayload.Standard.createPayload(route.amount, totalAmount, expiry, paymentSecret, paymentMetadata_opt, customTlvs))
      }
      Recipient.buildPayloads(PaymentPayloads(route.amount, expiry, Seq(finalPayload), None), route.hops)
    })
  }
}

object ClearRecipient {
  def apply(invoice: Bolt11Invoice, totalAmount: MilliSatoshi, expiry: CltvExpiry, customTlvs: Set[GenericTlv]): ClearRecipient = {
    ClearRecipient(invoice.nodeId, invoice.features, totalAmount, expiry, invoice.paymentSecret, invoice.extraEdges, invoice.paymentMetadata, None, customTlvs)
  }

  def validateRoute(nodeId: PublicKey, route: Route): Either[OutgoingPaymentError, Route] = {
    route.hops.lastOption match {
      case Some(hop) if hop.nextNodeId == nodeId => Right(route)
      case Some(hop) => Left(InvalidRouteRecipient(nodeId, hop.nextNodeId))
      case None => Left(EmptyRoute)
    }
  }
}

/** A payment recipient that doesn't expect to receive a payment and can directly be found in the routing graph. */
case class SpontaneousRecipient(nodeId: PublicKey,
                                totalAmount: MilliSatoshi,
                                expiry: CltvExpiry,
                                preimage: ByteVector32,
                                customTlvs: Set[GenericTlv] = Set.empty) extends Recipient {
  override val features = Features.empty
  override val extraEdges = Nil

  override def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads] = {
    ClearRecipient.validateRoute(nodeId, route).map(_ => {
      val finalPayload = NodePayload(nodeId, FinalPayload.Standard.createKeySendPayload(route.amount, expiry, preimage, customTlvs))
      Recipient.buildPayloads(PaymentPayloads(totalAmount, expiry, Seq(finalPayload), None), route.hops)
    })
  }
}

/** A payment recipient that hides its real identity using route blinding. */
case class BlindedRecipient(nodeId: PublicKey,
                            features: Features[InvoiceFeature],
                            totalAmount: MilliSatoshi,
                            expiry: CltvExpiry,
                            blindedHops: Seq[BlindedHop],
                            customTlvs: Set[GenericTlv]) extends Recipient {
  require(blindedHops.nonEmpty, "blinded routes must be provided")

  override val extraEdges = blindedHops.map { h =>
    ExtraEdge(h.nodeId, nodeId, h.dummyId, h.paymentInfo.feeBase, h.paymentInfo.feeProportionalMillionths, h.paymentInfo.cltvExpiryDelta, h.paymentInfo.minHtlc, Some(h.paymentInfo.maxHtlc))
  }

  private def validateRoute(route: Route): Either[OutgoingPaymentError, BlindedHop] = {
    route.finalHop_opt match {
      case Some(BlindedHop(_, ResolvedPath(r: PartialBlindedRoute, _))) if route.hops.length > 1 => Left(IndirectRelayInBlindedRoute(r.firstNodeId))
      case Some(blindedHop: BlindedHop) => Right(blindedHop)
      case _ => Left(MissingBlindedHop(blindedHops.map(_.nodeId).toSet))
    }
  }

  private def buildBlindedPayloads(amount: MilliSatoshi, blindedHop: BlindedHop): PaymentPayloads = {
    val introductionAmount = amount + blindedHop.fee(amount)
    val introductionExpiry = expiry + blindedHop.cltvExpiryDelta
    blindedHop.resolved.route match {
      case route: BlindedPathsResolver.FullBlindedRoute =>
        val payloads = if (route.blindedHops.length == 1) {
          // The recipient is also the introduction node.
          val payload = NodePayload(blindedHop.nodeId, OutgoingBlindedPerHopPayload.createFinalIntroductionPayload(amount, totalAmount, expiry, route.firstpathKey, route.encryptedPayloads.head, customTlvs))
          Seq(payload)
        } else {
          val introductionPayload = NodePayload(blindedHop.nodeId, OutgoingBlindedPerHopPayload.createIntroductionPayload(route.encryptedPayloads.head, route.firstpathKey))
          val intermediatePayloads = route.blindedHops.tail.dropRight(1).map(n => NodePayload(n.blindedPublicKey, OutgoingBlindedPerHopPayload.createIntermediatePayload(n.encryptedPayload)))
          val finalPayload = NodePayload(route.blindedNodeIds.last, OutgoingBlindedPerHopPayload.createFinalPayload(amount, totalAmount, expiry, route.encryptedPayloads.last, customTlvs))
          introductionPayload +: intermediatePayloads :+ finalPayload
        }
        // The route starts at a remote introduction node: we include the path key in the onion, not in the HTLC.
        PaymentPayloads(introductionAmount, introductionExpiry, payloads, outerPathKey_opt = None)
      case route: BlindedPathsResolver.PartialBlindedRoute =>
        // The route started at our node: we already peeled the introduction part.
        val intermediatePayloads = route.blindedHops.dropRight(1).map(n => NodePayload(n.blindedPublicKey, OutgoingBlindedPerHopPayload.createIntermediatePayload(n.encryptedPayload)))
        val finalPayload = NodePayload(route.blindedNodeIds.last, OutgoingBlindedPerHopPayload.createFinalPayload(amount, totalAmount, expiry, route.encryptedPayloads.last, customTlvs))
        // The next node is not the introduction node, so we must provide the path key in the HTLC.
        PaymentPayloads(introductionAmount, introductionExpiry, intermediatePayloads :+ finalPayload, outerPathKey_opt = Some(route.nextPathKey))
    }
  }

  override def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads] = {
    validateRoute(route).flatMap(blindedHop => {
      val blindedPayloads = buildBlindedPayloads(route.amount, blindedHop)
      Right(Recipient.buildPayloads(blindedPayloads, route.hops))
    })
  }
}

object BlindedRecipient {
  /**
   * @param invoice Bolt invoice. Paths from the invoice must be passed as `paths` with compact paths expanded to include the node id.
   * @param paths   Payment paths to use to reach the recipient.
   */
  def apply(invoice: Bolt12Invoice, paths: Seq[ResolvedPath], totalAmount: MilliSatoshi, expiry: CltvExpiry, customTlvs: Set[GenericTlv], duplicatePaths: Int = 3): BlindedRecipient =
    BlindedRecipient.fromPaths(invoice.nodeId, invoice.features, totalAmount, expiry, paths, customTlvs, duplicatePaths)

  def fromPaths(nodeId: PublicKey, features: Features[InvoiceFeature], totalAmount: MilliSatoshi, expiry: CltvExpiry, paths: Seq[ResolvedPath], customTlvs: Set[GenericTlv], duplicatePaths: Int = 3): BlindedRecipient = {
    val blindedHops = paths.flatMap(resolved => {
      // We want to be able to split payments *inside* a blinded route, because nodes inside the route may be connected
      // by multiple channels which may be imbalanced. A simple trick for that is to clone each blinded path three times,
      // which makes it look like there are 3 channels between each pair of nodes.
      (0 until duplicatePaths).map(_ => {
        // We don't know the scids of channels inside the blinded route, but it's useful to have an ID to refer to a
        // given edge in the graph, so we create a dummy one for the duration of the payment attempt.
        val dummyId = ShortChannelId.generateLocalAlias()
        BlindedHop(dummyId, resolved)
      })
    })
    BlindedRecipient(nodeId, features, totalAmount, expiry, blindedHops, customTlvs)
  }
}
