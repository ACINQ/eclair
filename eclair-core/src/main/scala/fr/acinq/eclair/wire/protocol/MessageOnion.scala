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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvField
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
  case class InvoiceRequest(tlvs: TlvStream[OfferTypes.InvoiceRequestTlv]) extends OnionMessagePayloadTlv

  /**
   * When receiving an invoice request, we must send an onion message back containing an invoice corresponding to the
   * requested offer (if it was an offer we published).
   */
  case class Invoice(tlvs: TlvStream[OfferTypes.InvoiceTlv]) extends OnionMessagePayloadTlv

  /**
   * This message may be used when we receive an invalid invoice or invoice request.
   * It contains information helping senders figure out why their message was invalid.
   */
  case class InvoiceError(tlvs: TlvStream[OfferTypes.InvoiceErrorTlv]) extends OnionMessagePayloadTlv

}

object MessageOnion {

  import OnionMessagePayloadTlv._

  /** Per-hop payload from an onion message (after onion decryption and decoding). */
  sealed trait PerHopPayload {
    def records: TlvStream[OnionMessagePayloadTlv]
  }

  /** Per-hop payload for an intermediate node. */
  case class IntermediatePayload(records: TlvStream[OnionMessagePayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey) extends PerHopPayload {
    val nextNodeId: PublicKey = blindedRecords.get[RouteBlindingEncryptedDataTlv.OutgoingNodeId].get.nodeId
  }

  object IntermediatePayload {
    def validate(records: TlvStream[OnionMessagePayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey): Either[InvalidTlvPayload, IntermediatePayload] = {
      if (records.get[ReplyPath].nonEmpty) return Left(ForbiddenTlv(UInt64(2)))
      if (records.get[EncryptedData].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
      if (records.get[InvoiceRequest].nonEmpty) return Left(ForbiddenTlv(UInt64(64)))
      if (records.get[Invoice].nonEmpty) return Left(ForbiddenTlv(UInt64(66)))
      if (records.get[InvoiceError].nonEmpty) return Left(ForbiddenTlv(UInt64(68)))
      BlindedRouteData.validateMessageRelayData(blindedRecords).map(blindedRecords => IntermediatePayload(records, blindedRecords, nextBlinding))
    }
  }

  /** Per-hop payload for a final node. */
  case class FinalPayload(records: TlvStream[OnionMessagePayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv]) extends PerHopPayload {
    val pathId_opt: Option[ByteVector] = blindedRecords.get[RouteBlindingEncryptedDataTlv.PathId].map(_.data)
    val replyPath_opt: Option[BlindedRoute] = records.get[ReplyPath].map(_.blindedRoute)
  }

  object FinalPayload {
    def validate(records: TlvStream[OnionMessagePayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, FinalPayload] = {
      records.get[InvoiceRequest].map(i => OfferTypes.InvoiceRequest.validate(i.tlvs)) match {
        case Some(Left(failure)) => return Left(failure)
        case _ => // valid or missing
      }
      records.get[Invoice].map(i => Bolt12Invoice.validate(i.tlvs)) match {
        case Some(Left(failure)) => return Left(failure)
        case _ => // valid or missing
      }
      records.get[InvoiceError].map(i => OfferTypes.InvoiceError.validate(i.tlvs)) match {
        case Some(Left(failure)) => return Left(failure)
        case _ => // valid or missing
      }
      BlindedRouteData.validateMessageRecipientData(blindedRecords).map(blindedRecords => FinalPayload(records, blindedRecords))
    }
  }

}

object MessageOnionCodecs {

  import OnionMessagePayloadTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs._
  import scodec.Codec
  import scodec.codecs._

  private val replyHopCodec: Codec[BlindedNode] = (("nodeId" | publicKey) :: ("encryptedData" | variableSizeBytes(uint16, bytes))).as[BlindedNode]

  val blindedRouteCodec: Codec[BlindedRoute] = (("firstNodeId" | publicKey) :: ("blinding" | publicKey) :: ("path" | listOfN(uint8, replyHopCodec).xmap[Seq[BlindedNode]](_.toSeq, _.toList))).as[BlindedRoute]

  private val replyPathCodec: Codec[ReplyPath] = tlvField(blindedRouteCodec)

  private val encryptedDataCodec: Codec[EncryptedData] = tlvField(bytes)

  val onionTlvCodec = discriminated[OnionMessagePayloadTlv].by(varint)
    .typecase(UInt64(2), replyPathCodec)
    .typecase(UInt64(4), encryptedDataCodec)
    .typecase(UInt64(64), OfferCodecs.invoiceRequestCodec)
    .typecase(UInt64(66), OfferCodecs.invoiceCodec)
    .typecase(UInt64(68), OfferCodecs.invoiceErrorCodec)

  val perHopPayloadCodec: Codec[TlvStream[OnionMessagePayloadTlv]] = TlvCodecs.lengthPrefixedTlvStream[OnionMessagePayloadTlv](onionTlvCodec).complete

  val messageOnionPacketCodec: Codec[OnionRoutingPacket] = variableSizeBytes(uint16, bytes).exmap[OnionRoutingPacket](
    // The Sphinx packet header contains a version (1 byte), a public key (33 bytes) and a mac (32 bytes) -> total 66 bytes
    bytes => OnionRoutingCodecs.onionRoutingPacketCodec(bytes.length.toInt - 66).decode(bytes.bits).map(_.value),
    onion => OnionRoutingCodecs.onionRoutingPacketCodec(onion.payload.length.toInt).encode(onion).map(_.bytes)
  )

}
