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
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.OutgoingPaymentPacket._
import fr.acinq.eclair.payment.{Bolt11Invoice, OutgoingPaymentPacket}
import fr.acinq.eclair.router.Router.{ChannelHop, NodeHop, Route}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol.{GenericTlv, OnionRoutingPacket, PaymentOnionCodecs}
import fr.acinq.eclair.{CltvExpiry, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId}
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
  def buildPayloads(finalAmount: MilliSatoshi, finalExpiry: CltvExpiry, finalPayload: NodePayload, hops: Seq[ChannelHop]): PaymentPayloads = {
    // We ignore the first hop since the route starts at our node.
    hops.tail.foldRight(PaymentPayloads(finalAmount, finalExpiry, Seq(finalPayload))) {
      case (hop, current) =>
        val payload = NodePayload(hop.nodeId, IntermediatePayload.ChannelRelay.Standard(hop.shortChannelId, current.amount, current.expiry))
        PaymentPayloads(current.amount + hop.fee(current.amount), current.expiry + hop.cltvExpiryDelta, payload +: current.payloads)
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
                          customTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads] = {
    ClearRecipient.validateRoute(nodeId, route).map(_ => {
      val finalPayload = nextTrampolineOnion_opt match {
        case Some(trampolinePacket) => NodePayload(nodeId, FinalPayload.Standard.createTrampolinePayload(route.amount, totalAmount, expiry, paymentSecret, trampolinePacket))
        case None => NodePayload(nodeId, FinalPayload.Standard.createPayload(route.amount, totalAmount, expiry, paymentSecret, paymentMetadata_opt, customTlvs))
      }
      Recipient.buildPayloads(route.amount, expiry, finalPayload, route.hops)
    })
  }
}

object ClearRecipient {
  def apply(invoice: Bolt11Invoice, totalAmount: MilliSatoshi, expiry: CltvExpiry, customTlvs: Seq[GenericTlv]): ClearRecipient = {
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
                                customTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override val features = Features.empty
  override val extraEdges = Nil

  override def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads] = {
    ClearRecipient.validateRoute(nodeId, route).map(_ => {
      val finalPayload = NodePayload(nodeId, FinalPayload.Standard.createKeySendPayload(route.amount, totalAmount, expiry, preimage, customTlvs))
      Recipient.buildPayloads(totalAmount, expiry, finalPayload, route.hops)
    })
  }
}

/** A payment recipient that can be reached through a given trampoline node (usually not found in the routing graph). */
case class ClearTrampolineRecipient(invoice: Bolt11Invoice,
                                    totalAmount: MilliSatoshi,
                                    expiry: CltvExpiry,
                                    trampolineHop: NodeHop,
                                    trampolinePaymentSecret: ByteVector32,
                                    customTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  require(trampolineHop.nextNodeId == invoice.nodeId, "trampoline hop must end at the recipient")

  val trampolineNodeId = trampolineHop.nodeId
  val trampolineFee = trampolineHop.fee(totalAmount)
  val trampolineAmount = totalAmount + trampolineFee
  val trampolineExpiry = expiry + trampolineHop.cltvExpiryDelta

  override val nodeId = invoice.nodeId
  override val features = invoice.features
  override val extraEdges = Seq(ExtraEdge(trampolineNodeId, nodeId, ShortChannelId.generateLocalAlias(), trampolineFee, 0, trampolineHop.cltvExpiryDelta, 1 msat, None))

  private def validateRoute(route: Route): Either[OutgoingPaymentError, NodeHop] = {
    route.finalHop_opt match {
      case Some(trampolineHop: NodeHop) => Right(trampolineHop)
      case None => Left(MissingTrampolineHop(trampolineNodeId))
    }
  }

  override def buildPayloads(paymentHash: ByteVector32, route: Route): Either[OutgoingPaymentError, PaymentPayloads] = {
    for {
      trampolineHop <- validateRoute(route)
      trampolineOnion <- createTrampolinePacket(paymentHash, trampolineHop)
    } yield {
      val trampolinePayload = NodePayload(trampolineHop.nodeId, FinalPayload.Standard.createTrampolinePayload(route.amount, trampolineAmount, trampolineExpiry, trampolinePaymentSecret, trampolineOnion.packet))
      Recipient.buildPayloads(route.amount, trampolineExpiry, trampolinePayload, route.hops)
    }
  }

  private def createTrampolinePacket(paymentHash: ByteVector32, trampolineHop: NodeHop): Either[OutgoingPaymentError, Sphinx.PacketAndSecrets] = {
    if (invoice.features.hasFeature(Features.TrampolinePaymentPrototype)) {
      // This is the payload the final recipient will receive, so we use the invoice's payment secret.
      val finalPayload = NodePayload(nodeId, FinalPayload.Standard.createPayload(totalAmount, totalAmount, expiry, invoice.paymentSecret, invoice.paymentMetadata, customTlvs))
      val trampolinePayload = NodePayload(trampolineHop.nodeId, IntermediatePayload.NodeRelay.Standard(totalAmount, expiry, nodeId))
      val payloads = Seq(trampolinePayload, finalPayload)
      OutgoingPaymentPacket.buildOnion(PaymentOnionCodecs.trampolineOnionPayloadLength, payloads, paymentHash)
    } else {
      // The recipient doesn't support trampoline: the trampoline node will convert the payment to a non-trampoline payment.
      // The final payload will thus never reach the recipient, so we create the smallest payload possible to avoid overflowing the trampoline onion size.
      val dummyFinalPayload = NodePayload(nodeId, IntermediatePayload.ChannelRelay.Standard(ShortChannelId(0), 0 msat, CltvExpiry(0)))
      val trampolinePayload = NodePayload(trampolineHop.nodeId, IntermediatePayload.NodeRelay.Standard.createNodeRelayToNonTrampolinePayload(totalAmount, totalAmount, expiry, nodeId, invoice))
      val payloads = Seq(trampolinePayload, dummyFinalPayload)
      OutgoingPaymentPacket.buildOnion(PaymentOnionCodecs.trampolineOnionPayloadLength, payloads, paymentHash)
    }
  }
}
