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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.CommonCodecs.{cltvExpiry, cltvExpiryDelta, millisatoshi, millisatoshi32}
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.featuresCodec
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.MissingRequiredTlv
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, MilliSatoshi, ShortChannelId, UInt64}
import scodec.Attempt
import scodec.bits.ByteVector

import scala.util.Try

/**
 * Created by t-bast on 19/10/2021.
 */

sealed trait RouteBlindingEncryptedDataTlv extends Tlv

object RouteBlindingEncryptedDataTlv {

  /** Some padding can be added to ensure all payloads are the same size to improve privacy. */
  case class Padding(dummy: ByteVector) extends RouteBlindingEncryptedDataTlv

  /** Id of the outgoing channel, used to identify the next node. */
  case class OutgoingChannelId(shortChannelId: ShortChannelId) extends RouteBlindingEncryptedDataTlv

  /** Id of the next node. */
  case class OutgoingNodeId(nodeId: PublicKey) extends RouteBlindingEncryptedDataTlv

  /**
   * The final recipient may store some data in the encrypted payload for itself to avoid storing it locally.
   * It can for example put a payment_hash to verify that the route is used for the correct invoice.
   * It should use that field to detect when blinded routes are used outside of their intended use (malicious probing)
   * and react accordingly (ignore the message or send an error depending on the use-case).
   */
  case class PathId(data: ByteVector) extends RouteBlindingEncryptedDataTlv

  /** Blinding override for the rest of the route. */
  case class NextBlinding(blinding: PublicKey) extends RouteBlindingEncryptedDataTlv

  /** Information for the relaying node to build the next HTLC. */
  case class PaymentRelay(feeBase: MilliSatoshi, feeProportionalMillionths: Long, cltvExpiryDelta: CltvExpiryDelta) extends RouteBlindingEncryptedDataTlv

  /** Constraints for the relaying node to enforce to prevent probing. */
  case class PaymentConstraints(maxCltvExpiry: CltvExpiry, minAmount: MilliSatoshi, allowedFeatures: Features[Feature]) extends RouteBlindingEncryptedDataTlv
}

object BlindedRouteData {
  import RouteBlindingEncryptedDataTlv._

  sealed trait Data
  sealed trait MessageData extends Data
  sealed trait PaymentData extends Data

  case class MessageRelayData(records: TlvStream[RouteBlindingEncryptedDataTlv]) extends MessageData {
    val nextNodeId: PublicKey = records.get[OutgoingNodeId].get.nodeId
    val nextBlinding_opt: Option[PublicKey] = records.get[NextBlinding].map(_.blinding)
  }

  case class PaymentRelayData(records: TlvStream[RouteBlindingEncryptedDataTlv]) extends PaymentData {
    val outgoingChannelId: ShortChannelId = records.get[OutgoingChannelId].get.shortChannelId
    val nextBlinding_opt: Option[PublicKey] = records.get[NextBlinding].map(_.blinding)

    private val paymentRelay: PaymentRelay = records.get[PaymentRelay].get

    def amountToForward(amountReceived: MilliSatoshi): MilliSatoshi =
      MilliSatoshi(((amountReceived - paymentRelay.feeBase).toLong.toDouble / (1.0 + paymentRelay.feeProportionalMillionths.toDouble / 1e-6)).ceil.toLong)

    def outgoingCltv(incomingCltv: CltvExpiry): CltvExpiry = incomingCltv + paymentRelay.cltvExpiryDelta

    def isValidPayment(amount: MilliSatoshi, cltvExpiry: CltvExpiry, features: Features[Feature]): Boolean = {
      records.get[PaymentConstraints].forall(constraints =>
        amount >= constraints.minAmount &&
          cltvExpiry <= constraints.maxCltvExpiry &&
          Features.areCompatible(features, constraints.allowedFeatures)
      )
    }
  }

  case class FinalRecipientData(records: TlvStream[RouteBlindingEncryptedDataTlv]) extends MessageData with PaymentData {
    val pathId_opt: Option[ByteVector] = records.get[PathId].map(_.data)

    def isValidPayment(amount: MilliSatoshi, cltvExpiry: CltvExpiry, features: Features[Feature]): Boolean = {
      records.get[PaymentConstraints].forall(constraints =>
        amount >= constraints.minAmount &&
          cltvExpiry <= constraints.maxCltvExpiry &&
          Features.areCompatible(features, constraints.allowedFeatures)
      )
    }
  }
}

object RouteBlindingEncryptedDataCodecs {

  import BlindedRouteData._
  import RouteBlindingEncryptedDataTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs.{publicKey, shortchannelid, varint, varintoverflow}
  import scodec.Codec
  import scodec.bits.HexStringSyntax
  import scodec.codecs._

  private val padding: Codec[Padding] = variableSizeBytesLong(varintoverflow, "padding" | bytes).as[Padding]
  private val outgoingChannelId: Codec[OutgoingChannelId] = variableSizeBytesLong(varintoverflow, "short_channel_id" | shortchannelid).as[OutgoingChannelId]
  private val outgoingNodeId: Codec[OutgoingNodeId] = (("length" | constant(hex"21")) :: ("node_id" | publicKey)).as[OutgoingNodeId]
  private val pathId: Codec[PathId] = variableSizeBytesLong(varintoverflow, "path_id" | bytes).as[PathId]
  private val nextBlinding: Codec[NextBlinding] = (("length" | constant(hex"21")) :: ("blinding" | publicKey)).as[NextBlinding]
  private val paymentRelay: Codec[PaymentRelay] = (
    ("length" | constant(hex"0a")) ::
    ("fee_base_msat" | millisatoshi32) ::
      ("fee_proportional_millionths" | uint32) ::
      ("cltv_expiry_delta" | cltvExpiryDelta)).as[PaymentRelay]
  private val paymentConstraints: Codec[PaymentConstraints] = variableSizeBytesLong(varintoverflow,
    ("max_cltv_expiry" | cltvExpiry) ::
      ("htlc_minimum_msat" | millisatoshi) ::
      ("allowed_features" | featuresCodec)).as[PaymentConstraints]


  private val messageRelayDataTlvCodec = discriminated[RouteBlindingEncryptedDataTlv].by(varint)
    .typecase(UInt64(1), padding)
    .typecase(UInt64(4), outgoingNodeId)
    .typecase(UInt64(8), nextBlinding)

  val messageRelayDataCodec: Codec[MessageRelayData] = TlvCodecs.tlvStream[RouteBlindingEncryptedDataTlv](messageRelayDataTlvCodec).complete.narrow({
    case tlvs if tlvs.get[OutgoingNodeId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case tlvs => Attempt.successful(MessageRelayData(tlvs))
  },{
    case MessageRelayData(tlvs) => tlvs
  })

  private val paymentRelayDataTlvCodec = discriminated[RouteBlindingEncryptedDataTlv].by(varint)
    .typecase(UInt64(1), padding)
    .typecase(UInt64(2), outgoingChannelId)
    .typecase(UInt64(8), nextBlinding)
    .typecase(UInt64(10), paymentRelay)
    .typecase(UInt64(12), paymentConstraints)

  val paymentRelayDataCodec: Codec[PaymentRelayData] = TlvCodecs.tlvStream[RouteBlindingEncryptedDataTlv](paymentRelayDataTlvCodec).complete.narrow({
    case tlvs if tlvs.get[OutgoingChannelId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(2)))
    case tlvs if tlvs.get[PaymentRelay].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(10)))
    case tlvs => Attempt.successful(PaymentRelayData(tlvs))
  }, {
    case PaymentRelayData(tlvs) => tlvs
  })

  private val finalRecipientDataTlvCodec = discriminated[RouteBlindingEncryptedDataTlv].by(varint)
    .typecase(UInt64(1), padding)
    .typecase(UInt64(6), pathId)
    .typecase(UInt64(12), paymentConstraints)

  val finalRecipientDataCodec: Codec[FinalRecipientData] = TlvCodecs.tlvStream[RouteBlindingEncryptedDataTlv](finalRecipientDataTlvCodec).complete.xmap(
    tlvs => FinalRecipientData(tlvs),
    { case FinalRecipientData(tlvs) => tlvs })

  /**
   * Decrypt and decode the contents of an encrypted_recipient_data TLV field.
   *
   * @param nodePrivKey        this node's private key.
   * @param blindingKey        blinding point (usually provided in the lightning message).
   * @param encryptedData      encrypted route blinding data (usually provided inside an onion).
   * @param encryptedDataCodec codec to parse the data
   * @return decrypted contents of the encrypted recipient data, which usually contain information about the next node,
   *         and the blinding point that should be sent to the next node.
   */
  def decode[T <: BlindedRouteData.Data](nodePrivKey: PrivateKey, blindingKey: PublicKey, encryptedData: ByteVector, encryptedDataCodec: Codec[T]): Try[(T, PublicKey)] = {
    Sphinx.RouteBlinding.decryptPayload(nodePrivKey, blindingKey, encryptedData).flatMap {
      case (payload, nextBlindingKey) => encryptedDataCodec.decode(payload.bits).map(r => (r.value, nextBlindingKey)).toTry
    }
  }

}
