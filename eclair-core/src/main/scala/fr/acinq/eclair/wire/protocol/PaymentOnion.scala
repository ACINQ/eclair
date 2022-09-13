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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.TlvCodecs._
import fr.acinq.eclair.{CltvExpiry, Features, MilliSatoshi, MilliSatoshiLong, ShortChannelId, UInt64}
import scodec.bits.{BitVector, ByteVector}

/**
 * Created by t-bast on 05/07/2019.
 */

/*
We support multiple payment flows, each having different requirements for what the onions contain. The following is an
overview of the onion contents we support.

STANDARD PAYMENT (fully source-routed, single-part):

    a -------------> b --------------------------> c --------------------------> d ---------------------------> e
          +-----------------------+     +-----------------------+     +-----------------------+     +-----------------------+
          | amount_fwd: 1025 msat |     | amount_fwd: 1010 msat |     | amount_fwd: 1000 msat |     | amount_fwd: 1000 msat |
          | expiry: 600030        |     | expiry: 600012        |     | expiry: 600000        |     | expiry: 600000        |
          | channel_id: 1105      |     | channel_id: 561       |     | channel_id: 42        |     | secret: xyz (opt)     |
          |-----------------------|     |-----------------------|     |-----------------------|     +-----------------------+
          |     (encrypted)       |     |     (encrypted)       |     |     (encrypted)       |     |          EOF          |
          +-----------------------+     +-----------------------+     +-----------------------+     +-----------------------+

STANDARD MULTI-PART PAYMENT (fully source-routed, multi-part):

    a -------------> b --------------------------> c --------------------------> d ---------------------------> e
          +-----------------------+     +-----------------------+     +-----------------------+     +-------------------------+
          | amount_fwd: 1025 msat |     | amount_fwd: 1010 msat |     | amount_fwd: 1000 msat |     | amount_fwd: 1000 msat   |
          | expiry: 600030        |     | expiry: 600012        |     | expiry: 600000        |     | expiry: 600000          |
          | channel_id: 1105      |     | channel_id: 561       |     | channel_id: 42        |     | secret: xyz             |
          |-----------------------|     |-----------------------|     |-----------------------|     | total_amount: 1500 msat |
          |     (encrypted)       |     |     (encrypted)       |     |     (encrypted)       |     +-------------------------+
          +-----------------------+     +-----------------------+     +-----------------------+     |           EOF           |
                                                                                                    +-------------------------+

TRAMPOLINE PAYMENT (partially source-routed, multi-part):

    a -------------> b ---------------------------> t1 -----------------------------> t2 -------------------------------> e
          +----------------------+     +---------------------------+     +---------------------------+     +-----------------------------+
          | amount_fwd: 900 msat |     | amount_fwd: 900 msat      |     | amount_fwd: 750 msat      |     | amount_fwd: 1000 msat       |
          | expiry: 600112       |     | expiry: 600112            |     | expiry: 600042            |     | expiry: 600000              |
          | channel_id: 42       |     | secret: aaaaa             |     | secret: zzzzz             |     | secret: xxxxx               | <- randomly generated by t2 (NOT the invoice secret)
          |----------------------|     | total_amount: 1650 msat   |     | total_amount: 1600 msat   |     | total_amount: 1500 msat     | <- t2 is using multi-part to pay e, still 500 msat more to receive
          |     (encrypted)      |     | trampoline_onion:         |     | trampoline_onion:         |     | trampoline_onion:           |
          +----------------------+     | +-----------------------+ |     | +-----------------------+ |     | +-------------------------+ |
                                       | | amount_fwd: 1600 msat | |     | | amount_fwd: 1500 msat | |     | | amount_fwd: 1500 msat   | |
                                       | | expiry: 600042        | |     | | expiry: 600000        | |     | | expiry: 600000          | |
                                       | | node_id: t2           | |     | | node_id: e            | |     | | total_amount: 2500 msat | | <- may be bigger than amount_fwd in case the payment is split among multiple trampoline routes
                                       | +-----------------------+ |     | +-----------------------+ |     | | secret: yyyyy           | | <- invoice secret
                                       | |      (encrypted)      | |     | |      (encrypted)      | |     | +-------------------------+ |
                                       | +-----------------------+ |     | +-----------------------+ |     | |         EOF             | |
                                       +---------------------------+     +---------------------------+     | +-------------------------+ |
                                       |             EOF           |     |             EOF           |     +-----------------------------+
                                       +---------------------------+     +---------------------------+     |             EOF             |
                                                                                                           +-----------------------------+

Notes:
  - there may be two layers of multi-part: a may split the payment between multiple trampoline routes, and inside each
  trampoline route payments may be split into multiple parts.
  - when multi-part is used to reach trampoline nodes, the payment secret in the outer onion is NOT the invoice secret.
  We want only the recipient to receive the invoice payment secret. The payment secrets in outer onions are generated
  randomly by the sender to simply prevent next-to-last non-trampoline nodes from probing their position in the route or
  steal some fees.

TRAMPOLINE PAYMENT TO LEGACY RECIPIENT (the last trampoline node converts to a standard payment to the final recipient):

    a -------------> b ----------------------------> t1 -----------------------------> t2 ---------------------------------- -> e ---------------------------> f
         +-----------------------+     +---------------------------+     +---------------------------------+        +-----------------------+     +-------------------------+
         | amount_fwd: 1750 msat |     | amount_fwd: 1750 msat     |     | amount_fwd: 1600 msat           |        | amount_fwd: 1000 msat |     | amount_fwd: 1000 msat   |
         | expiry: 600112        |     | expiry: 600112            |     | expiry: 600042                  |        | expiry: 600000        |     | expiry: 600000          |
         | channel_id: 42        |     | secret: yyyyy             |     | secret: zzzzz                   |  +---->| channel_id: 42        |---->| secret: xyz             | <- invoice secret (omitted if not supported by invoice)
         +-----------------------+     | total_amount: 1750 msat   |     | total_amount: 1600 msat         |  |     +-----------------------+     | total_amount: 2500 msat | <- t2 is using multi-part to pay 1500 msat to f, for a total payment
         |     (encrypted)       |     | trampoline_onion:         |     | trampoline_onion:               |  |     |     (encrypted)       |     +-------------------------+    of 2500 msat split between multiple trampoline routes (omitted if
         +-----------------------+     | +-----------------------+ |     | +-----------------------------+ |  |     +-----------------------+     |           EOF           |    MPP not supported by invoice).
                                       | | amount_fwd: 1600 msat | |     | | amount_fwd: 1500 msat       | |  |                                   +-------------------------+    The remaining 1000 msat needed to reach the total 2500 msat have
                                       | | expiry: 600042        | |     | | expiry: 600000              | |--+                                                                  been sent via a completely separate trampoline route (not included
                                       | | node_id: t2           | |     | | total_amount: 2500 msat     | |  |     +-----------------------+     +-------------------------+    in this diagram).
                                       | +-----------------------+ |     | | secret: xyz                 | |  |     | amount_fwd: 500 msat  |     | amount_fwd: 500 msat    |
                                       | |      (encrypted)      | |     | | node_id: f                  | |  |     | expiry: 600000        |     | expiry: 600000          |
                                       | +-----------------------+ |     | | invoice_features: 0x0a      | |  +---->| channel_id: 43        |---->| secret: xyz             |
                                       +---------------------------+     | | invoice_routing_info: ..... | |        +-----------------------+     | total_amount: 2500 msat |
                                       |             EOF           |     | +-----------------------------+ |        |     (encrypted)       |     +-------------------------+
                                       +---------------------------+     | |      (encrypted)            | |        +-----------------------+     |           EOF           |
                                                                         | +-----------------------------+ |                                      +-------------------------+
                                                                         +---------------------------------+
                                                                         |             EOF                 |
                                                                         +---------------------------------+

Notes:
  - the last trampoline node learns the payment details (who the recipient is, the payment amount and secret)
  - but it doesn't learn the sender's identity
  - if the invoice doesn't specify an amount, the last trampoline node can pay a lower amount than what the sender
  intended, thus stealing a lot of fees for himself (the wallet should disable paying to a 0-value invoice via
  trampoline to prevent this attack)
  - if f doesn't support MPP, t2 will send a single-part payment or fail if there isn't enough capacity
  - as in normal trampoline scenario, payment secrets in the outer onion are NOT the invoice secret

*/

/** Tlv types used inside a payment onion. */
sealed trait OnionPaymentPayloadTlv extends Tlv

object OnionPaymentPayloadTlv {

  /** Amount to forward to the next node. */
  case class AmountToForward(amount: MilliSatoshi) extends OnionPaymentPayloadTlv

  /** CLTV value to use for the HTLC offered to the next node. */
  case class OutgoingCltv(cltv: CltvExpiry) extends OnionPaymentPayloadTlv

  /** Id of the channel to use to forward a payment to the next node. */
  case class OutgoingChannelId(shortChannelId: ShortChannelId) extends OnionPaymentPayloadTlv

  /**
   * Bolt 11 payment details (only included for the last node).
   *
   * @param secret      payment secret specified in the Bolt 11 invoice.
   * @param totalAmount total amount in multi-part payments. When missing, assumed to be equal to AmountToForward.
   */
  case class PaymentData(secret: ByteVector32, totalAmount: MilliSatoshi) extends OnionPaymentPayloadTlv

  /** Id of the next node. */
  case class OutgoingNodeId(nodeId: PublicKey) extends OnionPaymentPayloadTlv

  /**
   * Route blinding lets the recipient provide some encrypted data for each intermediate node in the blinded part of the
   * route. This data cannot be decrypted or modified by the sender and usually contains information to locate the next
   * node without revealing it to the sender.
   */
  case class EncryptedRecipientData(data: ByteVector) extends OnionPaymentPayloadTlv

  /** Blinding ephemeral public key for the introduction node of a blinded route. */
  case class BlindingPoint(publicKey: PublicKey) extends OnionPaymentPayloadTlv

  /** Total amount in blinded multi-part payments. */
  case class TotalAmount(totalAmount: MilliSatoshi) extends OnionPaymentPayloadTlv

  /**
   * When payment metadata is included in a Bolt 11 invoice, we should send it as-is to the recipient.
   * This lets recipients generate invoices without having to store anything on their side until the invoice is paid.
   */
  case class PaymentMetadata(data: ByteVector) extends OnionPaymentPayloadTlv

  /**
   * Invoice feature bits. Only included for intermediate trampoline nodes when they should convert to a legacy payment
   * because the final recipient doesn't support trampoline.
   */
  case class InvoiceFeatures(features: ByteVector) extends OnionPaymentPayloadTlv

  /**
   * Invoice routing hints. Only included for intermediate trampoline nodes when they should convert to a legacy payment
   * because the final recipient doesn't support trampoline.
   */
  case class InvoiceRoutingInfo(extraHops: List[List[Bolt11Invoice.ExtraHop]]) extends OnionPaymentPayloadTlv

  /** An encrypted trampoline onion packet. */
  case class TrampolineOnion(packet: OnionRoutingPacket) extends OnionPaymentPayloadTlv

  /** Pre-image included by the sender of a payment in case of a donation */
  case class KeySend(paymentPreimage: ByteVector32) extends OnionPaymentPayloadTlv
}

object PaymentOnion {

  import OnionPaymentPayloadTlv._

  /*
   *                              PerHopPayload
   *                                    |
   *                                    |
   *                     +--------------+---------------+
   *                     |                              |
   *                     |                              |
   *            IntermediatePayload                FinalPayload
   *                     |                              |
   *                     |                              |
   *           +---------+---------+             +------+------+
   *           |                   |             |             |
   *           |                   |             |             |
   *     ChannelRelay          NodeRelay      Standard      Blinded
   *           |                   |
   *           |                   |
   *    +------+------+            |
   *    |             |            |
   *    |             |            |
   * Standard      Blinded      Standard
   */

  /** Per-hop payload from an HTLC's payment onion (after decryption and decoding). */
  sealed trait PerHopPayload {
    def records: TlvStream[OnionPaymentPayloadTlv]
  }

  /** Per-hop payload for an intermediate node. */
  sealed trait IntermediatePayload extends PerHopPayload

  object IntermediatePayload {
    sealed trait ChannelRelay extends IntermediatePayload {
      // @formatter:off
      def outgoingChannelId: ShortChannelId
      def amountToForward(incomingAmount: MilliSatoshi): MilliSatoshi
      def outgoingCltv(incomingCltv: CltvExpiry): CltvExpiry
      // @formatter:on
    }

    object ChannelRelay {
      case class Standard(records: TlvStream[OnionPaymentPayloadTlv]) extends ChannelRelay {
        // @formatter:off
        val amountOut = records.get[AmountToForward].get.amount
        val cltvOut = records.get[OutgoingCltv].get.cltv
        override val outgoingChannelId = records.get[OutgoingChannelId].get.shortChannelId
        override def amountToForward(incomingAmount: MilliSatoshi): MilliSatoshi = amountOut
        override def outgoingCltv(incomingCltv: CltvExpiry): CltvExpiry = cltvOut
        // @formatter:on
      }

      object Standard {
        def apply(outgoingChannelId: ShortChannelId, amountToForward: MilliSatoshi, outgoingCltv: CltvExpiry): Standard = {
          Standard(TlvStream(OnionPaymentPayloadTlv.AmountToForward(amountToForward), OnionPaymentPayloadTlv.OutgoingCltv(outgoingCltv), OnionPaymentPayloadTlv.OutgoingChannelId(outgoingChannelId)))
        }

        def validate(records: TlvStream[OnionPaymentPayloadTlv]): Either[InvalidTlvPayload, Standard] = {
          if (records.get[AmountToForward].isEmpty) return Left(MissingRequiredTlv(UInt64(2)))
          if (records.get[OutgoingCltv].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
          if (records.get[OutgoingChannelId].isEmpty) return Left(MissingRequiredTlv(UInt64(6)))
          Right(Standard(records))
        }
      }

      /**
       * @param blindedRecords decrypted tlv stream from the encrypted_recipient_data tlv.
       * @param nextBlinding   blinding point that must be forwarded to the next hop.
       */
      case class Blinded(records: TlvStream[OnionPaymentPayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey) extends ChannelRelay {
        // @formatter:off
        override val outgoingChannelId = blindedRecords.get[RouteBlindingEncryptedDataTlv.OutgoingChannelId].get.shortChannelId
        val paymentRelay = blindedRecords.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get
        val paymentConstraints = blindedRecords.get[RouteBlindingEncryptedDataTlv.PaymentConstraints].get
        val allowedFeatures = blindedRecords.get[RouteBlindingEncryptedDataTlv.AllowedFeatures].map(_.features).getOrElse(Features.empty)
        override def amountToForward(incomingAmount: MilliSatoshi): MilliSatoshi = ((incomingAmount - paymentRelay.feeBase).toLong * 1_000_000 + 1_000_000 + paymentRelay.feeProportionalMillionths - 1).msat / (1_000_000 + paymentRelay.feeProportionalMillionths)
        override def outgoingCltv(incomingCltv: CltvExpiry): CltvExpiry = incomingCltv - paymentRelay.cltvExpiryDelta
        // @formatter:on
      }

      object Blinded {
        def validate(records: TlvStream[OnionPaymentPayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey): Either[InvalidTlvPayload, Blinded] = {
          if (records.get[EncryptedRecipientData].isEmpty) return Left(MissingRequiredTlv(UInt64(10)))
          // Bolt 4: MUST return an error if the payload contains other tlv fields than `encrypted_recipient_data` and `current_blinding_point`.
          if (records.unknown.nonEmpty) return Left(ForbiddenTlv(records.unknown.head.tag))
          records.records.find {
            case _: EncryptedRecipientData => false
            case _: BlindingPoint => false
            case _ => true
          } match {
            case Some(_) => return Left(ForbiddenTlv(UInt64(0)))
            case None => // no forbidden tlv found
          }
          BlindedRouteData.validatePaymentRelayData(blindedRecords).map(blindedRecords => Blinded(records, blindedRecords, nextBlinding))
        }
      }
    }

    sealed trait NodeRelay extends IntermediatePayload {
      def outgoingNodeId: PublicKey
    }

    object NodeRelay {
      case class Standard(records: TlvStream[OnionPaymentPayloadTlv]) extends NodeRelay {
        val amountToForward = records.get[AmountToForward].get.amount
        val outgoingCltv = records.get[OutgoingCltv].get.cltv
        val outgoingNodeId = records.get[OutgoingNodeId].get.nodeId
        // The following fields are only included in the trampoline-to-legacy case.
        val totalAmount = records.get[PaymentData].map(_.totalAmount match {
          case MilliSatoshi(0) => amountToForward
          case totalAmount => totalAmount
        }).getOrElse(amountToForward)
        val paymentSecret = records.get[PaymentData].map(_.secret)
        val paymentMetadata = records.get[PaymentMetadata].map(_.data)
        val invoiceFeatures = records.get[InvoiceFeatures].map(_.features)
        val invoiceRoutingInfo = records.get[InvoiceRoutingInfo].map(_.extraHops)
      }

      object Standard {
        def apply(amount: MilliSatoshi, expiry: CltvExpiry, nextNodeId: PublicKey): Standard = {
          Standard(TlvStream(AmountToForward(amount), OutgoingCltv(expiry), OutgoingNodeId(nextNodeId)))
        }

        def validate(records: TlvStream[OnionPaymentPayloadTlv]): Either[InvalidTlvPayload, Standard] = {
          if (records.get[AmountToForward].isEmpty) return Left(MissingRequiredTlv(UInt64(2)))
          if (records.get[OutgoingCltv].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
          if (records.get[OutgoingNodeId].isEmpty) return Left(MissingRequiredTlv(UInt64(66098)))
          if (records.get[EncryptedRecipientData].nonEmpty) return Left(ForbiddenTlv(UInt64(10)))
          if (records.get[BlindingPoint].nonEmpty) return Left(ForbiddenTlv(UInt64(12)))
          Right(Standard(records))
        }

        /** Create a trampoline inner payload instructing the trampoline node to relay via a non-trampoline payment. */
        // TODO: Allow sending blinded routes to trampoline nodes instead of routing hints to support BOLT12Invoice
        def createNodeRelayToNonTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, targetNodeId: PublicKey, invoice: Bolt11Invoice): Standard = {
          val tlvs = Seq(
            Some(AmountToForward(amount)),
            Some(OutgoingCltv(expiry)),
            invoice.paymentSecret.map(s => PaymentData(s, totalAmount)),
            invoice.paymentMetadata.map(m => PaymentMetadata(m)),
            Some(OutgoingNodeId(targetNodeId)),
            Some(InvoiceFeatures(invoice.features.toByteVector)),
            Some(InvoiceRoutingInfo(invoice.routingInfo.toList.map(_.toList)))
          ).flatten
          Standard(TlvStream(tlvs))
        }
      }
    }
  }

  /** Per-hop payload for the final recipient. */
  sealed trait FinalPayload extends PerHopPayload {
    // @formatter:off
    def amount: MilliSatoshi
    def totalAmount: MilliSatoshi
    def expiry: CltvExpiry
    // @formatter:on
  }

  object FinalPayload {
    case class Standard(records: TlvStream[OnionPaymentPayloadTlv]) extends FinalPayload {
      override val amount = records.get[AmountToForward].get.amount
      override val totalAmount = records.get[PaymentData].map(_.totalAmount match {
        case MilliSatoshi(0) => amount
        case totalAmount => totalAmount
      }).getOrElse(amount)
      override val expiry = records.get[OutgoingCltv].get.cltv
      val paymentSecret = records.get[PaymentData].get.secret
      val paymentPreimage = records.get[KeySend].map(_.paymentPreimage)
      val paymentMetadata = records.get[PaymentMetadata].map(_.data)
    }

    object Standard {
      def validate(records: TlvStream[OnionPaymentPayloadTlv]): Either[InvalidTlvPayload, Standard] = {
        if (records.get[AmountToForward].isEmpty) return Left(MissingRequiredTlv(UInt64(2)))
        if (records.get[OutgoingCltv].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
        if (records.get[PaymentData].isEmpty) return Left(MissingRequiredTlv(UInt64(8)))
        Right(Standard(records))
      }

      def createSinglePartPayload(amount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, paymentMetadata: Option[ByteVector], userCustomTlvs: Seq[GenericTlv] = Nil): Standard = {
        val tlvs = Seq(
          Some(AmountToForward(amount)),
          Some(OutgoingCltv(expiry)),
          Some(PaymentData(paymentSecret, amount)),
          paymentMetadata.map(m => PaymentMetadata(m))
        ).flatten
        Standard(TlvStream(tlvs, userCustomTlvs))
      }

      def createMultiPartPayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, paymentMetadata: Option[ByteVector], additionalTlvs: Seq[OnionPaymentPayloadTlv] = Nil, userCustomTlvs: Seq[GenericTlv] = Nil): Standard = {
        val tlvs = Seq(
          Some(AmountToForward(amount)),
          Some(OutgoingCltv(expiry)),
          Some(PaymentData(paymentSecret, totalAmount)),
          paymentMetadata.map(m => PaymentMetadata(m))
        ).flatten
        Standard(TlvStream(tlvs ++ additionalTlvs, userCustomTlvs))
      }

      /** Create a trampoline outer payload. */
      def createTrampolinePayload(amount: MilliSatoshi, totalAmount: MilliSatoshi, expiry: CltvExpiry, paymentSecret: ByteVector32, trampolinePacket: OnionRoutingPacket): Standard = {
        Standard(TlvStream(AmountToForward(amount), OutgoingCltv(expiry), PaymentData(paymentSecret, totalAmount), TrampolineOnion(trampolinePacket)))
      }
    }

    /**
     * @param blindedRecords decrypted tlv stream from the encrypted_recipient_data tlv.
     */
    case class Blinded(records: TlvStream[OnionPaymentPayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv]) extends FinalPayload {
      override val amount = records.get[AmountToForward].get.amount
      override val totalAmount = records.get[TotalAmount].map(_.totalAmount).getOrElse(amount)
      override val expiry = records.get[OutgoingCltv].get.cltv
      val pathId = blindedRecords.get[RouteBlindingEncryptedDataTlv.PathId].get.data
      val paymentConstraints = blindedRecords.get[RouteBlindingEncryptedDataTlv.PaymentConstraints].get
      val allowedFeatures = blindedRecords.get[RouteBlindingEncryptedDataTlv.AllowedFeatures].map(_.features).getOrElse(Features.empty)
    }

    object Blinded {
      def validate(records: TlvStream[OnionPaymentPayloadTlv], blindedRecords: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, Blinded] = {
        if (records.get[AmountToForward].isEmpty) return Left(MissingRequiredTlv(UInt64(2)))
        if (records.get[OutgoingCltv].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
        if (records.get[EncryptedRecipientData].isEmpty) return Left(MissingRequiredTlv(UInt64(10)))
        // Bolt 4: MUST return an error if the payload contains other tlv fields than `encrypted_recipient_data`, `current_blinding_point`, `amt_to_forward`, `outgoing_cltv_value` and `total_amount_msat`.
        if (records.unknown.nonEmpty) return Left(ForbiddenTlv(records.unknown.head.tag))
        records.records.find {
          case _: AmountToForward => false
          case _: OutgoingCltv => false
          case _: EncryptedRecipientData => false
          case _: BlindingPoint => false
          case _: TotalAmount => false
          case _ => true
        } match {
          case Some(_) => return Left(ForbiddenTlv(UInt64(0)))
          case None => // no forbidden tlv found
        }
        BlindedRouteData.validPaymentRecipientData(blindedRecords).map(blindedRecords => Blinded(records, blindedRecords))
      }
    }
  }

}

object PaymentOnionCodecs {

  import OnionPaymentPayloadTlv._
  import scodec.bits.HexStringSyntax
  import scodec.codecs._
  import scodec.{Codec, DecodeResult, Decoder}

  val paymentOnionPayloadLength = 1300
  val trampolineOnionPayloadLength = 400
  val paymentOnionPacketCodec: Codec[OnionRoutingPacket] = OnionRoutingCodecs.onionRoutingPacketCodec(paymentOnionPayloadLength)
  val trampolineOnionPacketCodec: Codec[OnionRoutingPacket] = OnionRoutingCodecs.onionRoutingPacketCodec(trampolineOnionPayloadLength)

  /**
   * The 1.1 BOLT spec changed the payment onion frame format to use variable-length per-hop payloads.
   * The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
   * That varint is considered to be part of the payload, so the payload length includes the number of bytes used by
   * the varint prefix.
   */
  val payloadLengthDecoder = Decoder[Long]((bits: BitVector) =>
    varintoverflow.decode(bits).map(d => DecodeResult(d.value + (bits.length - d.remainder.length) / 8, d.remainder)))

  private val amountToForward: Codec[AmountToForward] = ("amount_msat" | ltmillisatoshi).as[AmountToForward]

  private val outgoingCltv: Codec[OutgoingCltv] = ("cltv" | ltu32).xmap(cltv => OutgoingCltv(CltvExpiry(cltv)), (c: OutgoingCltv) => c.cltv.toLong)

  private val outgoingChannelId: Codec[OutgoingChannelId] = variableSizeBytesLong(varintoverflow, "short_channel_id" | shortchannelid).as[OutgoingChannelId]

  private val paymentData: Codec[PaymentData] = variableSizeBytesLong(varintoverflow, ("payment_secret" | bytes32) :: ("total_msat" | tmillisatoshi)).as[PaymentData]

  private val encryptedRecipientData: Codec[EncryptedRecipientData] = variableSizeBytesLong(varintoverflow, "encrypted_data" | bytes).as[EncryptedRecipientData]

  private val blindingPoint: Codec[BlindingPoint] = (("length" | constant(hex"21")) :: ("blinding" | publicKey)).as[BlindingPoint]

  private val outgoingNodeId: Codec[OutgoingNodeId] = (("length" | constant(hex"21")) :: ("node_id" | publicKey)).as[OutgoingNodeId]

  private val paymentMetadata: Codec[PaymentMetadata] = variableSizeBytesLong(varintoverflow, "payment_metadata" | bytes).as[PaymentMetadata]

  private val totalAmount: Codec[TotalAmount] = ("total_amount_msat" | ltmillisatoshi).as[TotalAmount]

  private val invoiceFeatures: Codec[InvoiceFeatures] = variableSizeBytesLong(varintoverflow, bytes).as[InvoiceFeatures]

  private val invoiceRoutingInfo: Codec[InvoiceRoutingInfo] = variableSizeBytesLong(varintoverflow, list(listOfN(uint8, Bolt11Invoice.Codecs.extraHopCodec))).as[InvoiceRoutingInfo]

  private val trampolineOnion: Codec[TrampolineOnion] = variableSizeBytesLong(varintoverflow, trampolineOnionPacketCodec).as[TrampolineOnion]

  private val keySend: Codec[KeySend] = variableSizeBytesLong(varintoverflow, bytes32).as[KeySend]

  private val onionTlvCodec = discriminated[OnionPaymentPayloadTlv].by(varint)
    .typecase(UInt64(2), amountToForward)
    .typecase(UInt64(4), outgoingCltv)
    .typecase(UInt64(6), outgoingChannelId)
    .typecase(UInt64(8), paymentData)
    .typecase(UInt64(10), encryptedRecipientData)
    .typecase(UInt64(12), blindingPoint)
    .typecase(UInt64(16), paymentMetadata)
    .typecase(UInt64(18), totalAmount)
    // Types below aren't specified - use cautiously when deploying (be careful with backwards-compatibility).
    .typecase(UInt64(66097), invoiceFeatures)
    .typecase(UInt64(66098), outgoingNodeId)
    .typecase(UInt64(66099), invoiceRoutingInfo)
    .typecase(UInt64(66100), trampolineOnion)
    .typecase(UInt64(5482373484L), keySend)

  val perHopPayloadCodec: Codec[TlvStream[OnionPaymentPayloadTlv]] = TlvCodecs.lengthPrefixedTlvStream[OnionPaymentPayloadTlv](onionTlvCodec).complete

}