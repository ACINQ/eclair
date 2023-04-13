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
import fr.acinq.eclair.wire.protocol.CommonCodecs.{cltvExpiry, cltvExpiryDelta, featuresCodec}
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.TlvCodecs.{fixedLengthTlvField, tlvField, tmillisatoshi, tmillisatoshi32}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.ByteVector

import scala.util.{Failure, Success}

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
  case class PaymentRelay(cltvExpiryDelta: CltvExpiryDelta, feeProportionalMillionths: Long, feeBase: MilliSatoshi) extends RouteBlindingEncryptedDataTlv

  /** Constraints for the relaying node to enforce to prevent probing. */
  case class PaymentConstraints(maxCltvExpiry: CltvExpiry, minAmount: MilliSatoshi) extends RouteBlindingEncryptedDataTlv

  /**
   * Blinded routes constrain the features that can be used by relaying nodes to prevent probing.
   * Without this mechanism nodes supporting features that aren't widely supported could easily be identified.
   */
  case class AllowedFeatures(features: Features[Feature]) extends RouteBlindingEncryptedDataTlv

}

object BlindedRouteData {

  import RouteBlindingEncryptedDataTlv._

  def validateMessageRelayData(records: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, TlvStream[RouteBlindingEncryptedDataTlv]] = {
    if (records.get[OutgoingNodeId].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
    if (records.get[PathId].isDefined) return Left(ForbiddenTlv(UInt64(6)))
    if (records.get[PaymentRelay].isDefined) return Left(ForbiddenTlv(UInt64(10)))
    if (records.get[PaymentConstraints].isDefined) return Left(ForbiddenTlv(UInt64(12)))
    if (records.get[AllowedFeatures].exists(!_.features.isEmpty)) return Left(ForbiddenTlv(UInt64(14))) // we don't support custom blinded relay features yet
    Right(records)
  }

  def validateMessageRecipientData(records: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, TlvStream[RouteBlindingEncryptedDataTlv]] = {
    if (records.get[PaymentRelay].isDefined) return Left(ForbiddenTlv(UInt64(10)))
    if (records.get[PaymentConstraints].isDefined) return Left(ForbiddenTlv(UInt64(12)))
    Right(records)
  }

  def validatePaymentRelayData(records: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, TlvStream[RouteBlindingEncryptedDataTlv]] = {
    if (records.get[OutgoingChannelId].isEmpty) return Left(MissingRequiredTlv(UInt64(2)))
    if (records.get[PaymentRelay].isEmpty) return Left(MissingRequiredTlv(UInt64(10)))
    if (records.get[PaymentConstraints].isEmpty) return Left(MissingRequiredTlv(UInt64(12)))
    if (records.get[PathId].nonEmpty) return Left(ForbiddenTlv(UInt64(6)))
    if (records.get[AllowedFeatures].exists(!_.features.isEmpty)) return Left(ForbiddenTlv(UInt64(14))) // we don't support custom blinded relay features yet
    Right(records)
  }

  def validPaymentRecipientData(records: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, TlvStream[RouteBlindingEncryptedDataTlv]] = {
    if (records.get[PathId].isEmpty) return Left(MissingRequiredTlv(UInt64(6)))
    if (records.get[PaymentConstraints].isEmpty) return Left(MissingRequiredTlv(UInt64(12)))
    Right(records)
  }

}

object RouteBlindingEncryptedDataCodecs {

  import RouteBlindingEncryptedDataTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs.{publicKey, shortchannelid, varint}
  import scodec.codecs._
  import scodec.{Attempt, Codec, DecodeResult}

  private val padding: Codec[Padding] = tlvField(bytes)
  private val outgoingChannelId: Codec[OutgoingChannelId] = tlvField(shortchannelid)
  private val outgoingNodeId: Codec[OutgoingNodeId] = fixedLengthTlvField(33, publicKey)
  private val pathId: Codec[PathId] = tlvField(bytes)
  private val nextBlinding: Codec[NextBlinding] = fixedLengthTlvField(33, publicKey)
  private val paymentRelay: Codec[PaymentRelay] = tlvField(("cltv_expiry_delta" | cltvExpiryDelta) :: ("fee_proportional_millionths" | uint32) :: ("fee_base_msat" | tmillisatoshi32))
  private val paymentConstraints: Codec[PaymentConstraints] = tlvField(("max_cltv_expiry" | cltvExpiry) :: ("htlc_minimum_msat" | tmillisatoshi))
  private val allowedFeatures: Codec[AllowedFeatures] = tlvField(featuresCodec)

  private val encryptedDataTlvCodec = discriminated[RouteBlindingEncryptedDataTlv].by(varint)
    .typecase(UInt64(1), padding)
    .typecase(UInt64(2), outgoingChannelId)
    .typecase(UInt64(4), outgoingNodeId)
    .typecase(UInt64(6), pathId)
    .typecase(UInt64(8), nextBlinding)
    .typecase(UInt64(10), paymentRelay)
    .typecase(UInt64(12), paymentConstraints)
    .typecase(UInt64(14), allowedFeatures)

  val blindedRouteDataCodec = TlvCodecs.tlvStream[RouteBlindingEncryptedDataTlv](encryptedDataTlvCodec).complete

  // @formatter:off
  case class RouteBlindingDecryptedData(tlvs: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey)
  sealed trait InvalidEncryptedData { def message: String }
  case class CannotDecryptData(message: String) extends InvalidEncryptedData
  case class CannotDecodeData(message: String) extends InvalidEncryptedData
  // @formatter:on

  /**
   * Decrypt and decode the contents of an encrypted_recipient_data TLV field.
   *
   * @param nodePrivKey   this node's private key.
   * @param blindingKey   blinding point (usually provided in the lightning message).
   * @param encryptedData encrypted route blinding data (usually provided inside an onion).
   */
  def decode(nodePrivKey: PrivateKey, blindingKey: PublicKey, encryptedData: ByteVector): Either[InvalidEncryptedData, RouteBlindingDecryptedData] = {
    Sphinx.RouteBlinding.decryptPayload(nodePrivKey, blindingKey, encryptedData) match {
      case Failure(f) => Left(CannotDecryptData(f.getMessage))
      case Success((decryptedData, defaultNextBlinding)) => blindedRouteDataCodec.decode(decryptedData.bits) match {
        case Attempt.Failure(f) => Left(CannotDecodeData(f.message))
        case Attempt.Successful(DecodeResult(tlvs, _)) =>
          val nextBlinding = tlvs.get[NextBlinding].map(_.blinding).getOrElse(defaultNextBlinding)
          Right(RouteBlindingDecryptedData(tlvs, nextBlinding))
      }
    }
  }

}
