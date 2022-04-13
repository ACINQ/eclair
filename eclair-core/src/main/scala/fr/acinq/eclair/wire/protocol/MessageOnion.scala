/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.wire.protocol.OfferCodecs.{invoiceCodec, invoiceErrorCodec, invoiceRequestCodec}
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, MissingRequiredTlv}
import scodec.bits.ByteVector

/** Tlv types used inside the onion of an [[OnionMessage]]. */
sealed trait OnionMessagePayloadTlv extends Tlv

object OnionMessagePayloadTlv {

  /**
   * Onion messages may provide a reply path, allowing the recipient to send a message back to the original sender.
   * The reply path uses route blinding, which ensures that the sender doesn't leak its identity to the recipient.
   */
  case class ReplyPath(blindedRoute: BlindedRoute) extends OnionMessagePayloadTlv

  /**
   * Onion messages always use route blinding, even in the forward direction.
   * This ensures that intermediate nodes can't know whether they're forwarding a message or its reply.
   * The sender must provide some encrypted data for each intermediate node which lets them locate the next node.
   */
  case class EncryptedData(data: ByteVector) extends OnionMessagePayloadTlv

  /**
   * In order to pay a Bolt 12 offer, we must send an onion message to request an invoice corresponding to that offer.
   * The creator of the offer will send us an invoice back through our blinded reply path.
   */
  case class InvoiceRequest(request: Offers.InvoiceRequest) extends OnionMessagePayloadTlv

  /**
   * When receiving an invoice request, we must send an onion message back containing an invoice corresponding to the
   * requested offer (if it was an offer we published).
   */
  case class Invoice(invoice: Bolt12Invoice) extends OnionMessagePayloadTlv

  /**
   * This message may be used when we receive an invalid invoice or invoice request.
   * It contains information helping senders figure out why their message was invalid.
   */
  case class InvoiceError(error: Offers.InvoiceError) extends OnionMessagePayloadTlv

}

object MessageOnion {

  /** Per-hop payload from an onion message (after onion decryption and decoding). */
  sealed trait PerHopPayload

  /** Per-hop payload for an intermediate node. */
  case class RelayPayload(records: TlvStream[OnionMessagePayloadTlv]) extends PerHopPayload {
    val encryptedData: ByteVector = records.get[OnionMessagePayloadTlv.EncryptedData].get.data
  }

  /** Per-hop payload for a final node. */
  case class FinalPayload(records: TlvStream[OnionMessagePayloadTlv]) extends PerHopPayload {
    val replyPath: Option[OnionMessagePayloadTlv.ReplyPath] = records.get[OnionMessagePayloadTlv.ReplyPath]
    val encryptedData: ByteVector = records.get[OnionMessagePayloadTlv.EncryptedData].get.data
    val invoiceRequest: Option[OnionMessagePayloadTlv.InvoiceRequest] = records.get[OnionMessagePayloadTlv.InvoiceRequest]
    val invoice: Option[OnionMessagePayloadTlv.Invoice] = records.get[OnionMessagePayloadTlv.Invoice]
    val invoiceError: Option[OnionMessagePayloadTlv.InvoiceError] = records.get[OnionMessagePayloadTlv.InvoiceError]
  }

}

object MessageOnionCodecs {

  import MessageOnion._
  import OnionMessagePayloadTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs._
  import scodec.codecs._
  import scodec.{Attempt, Codec}

  private val replyHopCodec: Codec[BlindedNode] = (("nodeId" | publicKey) :: ("encryptedData" | variableSizeBytes(uint16, bytes))).as[BlindedNode]

  val blindedRouteCodec: Codec[BlindedRoute] = (("firstNodeId" | publicKey) :: ("blinding" | publicKey) :: ("path" | list(replyHopCodec).xmap[Seq[BlindedNode]](_.toSeq, _.toList))).as[BlindedRoute]

  private val replyPathCodec: Codec[ReplyPath] = variableSizeBytesLong(varintoverflow, blindedRouteCodec).as[ReplyPath]

  private val encryptedDataCodec: Codec[EncryptedData] = variableSizeBytesLong(varintoverflow, bytes).as[EncryptedData]

  private val onionTlvCodec = discriminated[OnionMessagePayloadTlv].by(varint)
    .typecase(UInt64(2), replyPathCodec)
    .typecase(UInt64(4), encryptedDataCodec)
    .typecase(UInt64(64), variableSizeBytesLong(varintoverflow, invoiceRequestCodec.as[InvoiceRequest]))
    .typecase(UInt64(66), variableSizeBytesLong(varintoverflow, invoiceCodec.as[Invoice]))
    .typecase(UInt64(68), variableSizeBytesLong(varintoverflow, invoiceErrorCodec.as[InvoiceError]))


  val perHopPayloadCodec: Codec[TlvStream[OnionMessagePayloadTlv]] = TlvCodecs.lengthPrefixedTlvStream[OnionMessagePayloadTlv](onionTlvCodec).complete

  val relayPerHopPayloadCodec: Codec[RelayPayload] = perHopPayloadCodec.narrow({
    case tlvs if tlvs.get[EncryptedData].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case tlvs if tlvs.get[ReplyPath].nonEmpty => Attempt.failure(ForbiddenTlv(UInt64(2)))
    case tlvs => Attempt.successful(RelayPayload(tlvs))
  }, {
    case RelayPayload(tlvs) => tlvs
  })

  val finalPerHopPayloadCodec: Codec[FinalPayload] = perHopPayloadCodec.narrow({
    case tlvs if tlvs.get[EncryptedData].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case tlvs => Attempt.successful(FinalPayload(tlvs))
  }, {
    case FinalPayload(tlvs) => tlvs
  })

  def messageOnionPerHopPayloadCodec(isLastPacket: Boolean): Codec[PerHopPayload] = if (isLastPacket) finalPerHopPayloadCodec.upcast[PerHopPayload] else relayPerHopPayloadCodec.upcast[PerHopPayload]

  val messageOnionPacketCodec: Codec[OnionRoutingPacket] = variableSizeBytes(uint16, bytes).exmap[OnionRoutingPacket](
    // The Sphinx packet header contains a version (1 byte), a public key (33 bytes) and a mac (32 bytes) -> total 66 bytes
    bytes => OnionRoutingCodecs.onionRoutingPacketCodec(bytes.length.toInt - 66).decode(bytes.bits).map(_.value),
    onion => OnionRoutingCodecs.onionRoutingPacketCodec(onion.payload.length.toInt).encode(onion).map(_.bytes)
  )

}
