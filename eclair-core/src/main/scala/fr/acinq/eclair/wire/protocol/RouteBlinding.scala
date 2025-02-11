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
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, EncodedNodeId, Feature, Features, MilliSatoshi, ShortChannelId, UInt64, amountAfterFee}
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

  /**
   * Id of the next node.
   *
   * WARNING: the spec only allows a public key here. We allow reading any type of [[EncodedNodeId]] to support relaying
   * to mobile wallets, but we should always write an [[EncodedNodeId.WithPublicKey.Plain]].
   */
  case class OutgoingNodeId(nodeId: EncodedNodeId) extends RouteBlindingEncryptedDataTlv

  object OutgoingNodeId {
    def apply(publicKey: PublicKey): OutgoingNodeId = OutgoingNodeId(EncodedNodeId.WithPublicKey.Plain(publicKey))
  }

  /**
   * The final recipient may store some data in the encrypted payload for itself to avoid storing it locally.
   * It can for example put a payment_hash to verify that the route is used for the correct invoice.
   * It should use that field to detect when blinded routes are used outside of their intended use (malicious probing)
   * and react accordingly (ignore the message or send an error depending on the use-case).
   */
  case class PathId(data: ByteVector) extends RouteBlindingEncryptedDataTlv

  /** Path key override for the rest of the route. */
  case class NextPathKey(pathKey: PublicKey) extends RouteBlindingEncryptedDataTlv

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
    if (records.get[OutgoingNodeId].isEmpty && records.get[OutgoingChannelId].isEmpty) return Left(MissingRequiredTlv(UInt64(4)))
    if (records.get[OutgoingNodeId].isDefined && records.get[OutgoingChannelId].isDefined) return Left(ForbiddenTlv(UInt64(4)))
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

  case class PaymentRelayData(records: TlvStream[RouteBlindingEncryptedDataTlv]) {
    val outgoing: Either[EncodedNodeId.WithPublicKey, ShortChannelId] = records.get[RouteBlindingEncryptedDataTlv.OutgoingChannelId] match {
      case Some(r) => Right(r.shortChannelId)
      case None => Left(records.get[RouteBlindingEncryptedDataTlv.OutgoingNodeId].get.nodeId.asInstanceOf[EncodedNodeId.WithPublicKey])
    }
    val paymentRelay: PaymentRelay = records.get[RouteBlindingEncryptedDataTlv.PaymentRelay].get
    val paymentConstraints: PaymentConstraints = records.get[RouteBlindingEncryptedDataTlv.PaymentConstraints].get
    val allowedFeatures: Features[Feature] = records.get[RouteBlindingEncryptedDataTlv.AllowedFeatures].map(_.features).getOrElse(Features.empty)

    def amountToForward(incomingAmount: MilliSatoshi): MilliSatoshi = amountAfterFee(paymentRelay.feeBase, paymentRelay.feeProportionalMillionths, incomingAmount)

    def outgoingCltv(incomingCltv: CltvExpiry): CltvExpiry = incomingCltv - paymentRelay.cltvExpiryDelta
  }

  def validatePaymentRelayData(records: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, PaymentRelayData] = {
    if (records.get[OutgoingChannelId].isEmpty && records.get[OutgoingNodeId].isEmpty) return Left(MissingRequiredTlv(UInt64(2)))
    if (records.get[OutgoingNodeId].nonEmpty && !records.get[OutgoingNodeId].get.nodeId.isInstanceOf[EncodedNodeId.WithPublicKey]) return Left(ForbiddenTlv(UInt64(4)))
    if (records.get[PaymentRelay].isEmpty) return Left(MissingRequiredTlv(UInt64(10)))
    if (records.get[PaymentConstraints].isEmpty) return Left(MissingRequiredTlv(UInt64(12)))
    if (records.get[PathId].nonEmpty) return Left(ForbiddenTlv(UInt64(6)))
    if (records.get[AllowedFeatures].exists(!_.features.isEmpty)) return Left(ForbiddenTlv(UInt64(14))) // we don't support custom blinded relay features yet
    Right(PaymentRelayData(records))
  }

  def validPaymentRecipientData(records: TlvStream[RouteBlindingEncryptedDataTlv]): Either[InvalidTlvPayload, TlvStream[RouteBlindingEncryptedDataTlv]] = {
    if (records.get[PathId].isEmpty) return Left(MissingRequiredTlv(UInt64(6)))
    Right(records)
  }

}

object RouteBlindingEncryptedDataCodecs {

  import RouteBlindingEncryptedDataTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs.{publicKey, shortchannelid, varint}
  import fr.acinq.eclair.wire.protocol.OfferCodecs.encodedNodeIdCodec
  import scodec.codecs._
  import scodec.{Attempt, Codec, DecodeResult}

  private val padding: Codec[Padding] = tlvField(bytes)
  private val outgoingChannelId: Codec[OutgoingChannelId] = tlvField(shortchannelid)
  private val outgoingNodeId: Codec[OutgoingNodeId] = tlvField(encodedNodeIdCodec)
  private val pathId: Codec[PathId] = tlvField(bytes)
  private val nextPathKey: Codec[NextPathKey] = fixedLengthTlvField(33, publicKey)
  private val paymentRelay: Codec[PaymentRelay] = tlvField(("cltv_expiry_delta" | cltvExpiryDelta) :: ("fee_proportional_millionths" | uint32) :: ("fee_base_msat" | tmillisatoshi32))
  private val paymentConstraints: Codec[PaymentConstraints] = tlvField(("max_cltv_expiry" | cltvExpiry) :: ("htlc_minimum_msat" | tmillisatoshi))
  private val allowedFeatures: Codec[AllowedFeatures] = tlvField(featuresCodec)

  private val encryptedDataTlvCodec = discriminated[RouteBlindingEncryptedDataTlv].by(varint)
    .typecase(UInt64(1), padding)
    .typecase(UInt64(2), outgoingChannelId)
    .typecase(UInt64(4), outgoingNodeId)
    .typecase(UInt64(6), pathId)
    .typecase(UInt64(8), nextPathKey)
    .typecase(UInt64(10), paymentRelay)
    .typecase(UInt64(12), paymentConstraints)
    .typecase(UInt64(14), allowedFeatures)

  val blindedRouteDataCodec = TlvCodecs.tlvStream[RouteBlindingEncryptedDataTlv](encryptedDataTlvCodec).complete

  // @formatter:off
  case class RouteBlindingDecryptedData(tlvs: TlvStream[RouteBlindingEncryptedDataTlv], nextPathKey: PublicKey)
  sealed trait InvalidEncryptedData { def message: String }
  case class CannotDecryptData(message: String) extends InvalidEncryptedData
  case class CannotDecodeData(message: String) extends InvalidEncryptedData
  // @formatter:on

  /**
   * Decrypt and decode the contents of an encrypted_recipient_data TLV field.
   *
   * @param nodePrivKey   this node's private key.
   * @param pathKey   path key (usually provided in the lightning message).
   * @param encryptedData encrypted route blinding data (usually provided inside an onion).
   */
  def decode(nodePrivKey: PrivateKey, pathKey: PublicKey, encryptedData: ByteVector): Either[InvalidEncryptedData, RouteBlindingDecryptedData] = {
    Sphinx.RouteBlinding.decryptPayload(nodePrivKey, pathKey, encryptedData) match {
      case Failure(f) => Left(CannotDecryptData(f.getMessage))
      case Success((decryptedData, defaultNextPathKey)) => blindedRouteDataCodec.decode(decryptedData.bits) match {
        case Attempt.Failure(f) => Left(CannotDecodeData(f.message))
        case Attempt.Successful(DecodeResult(tlvs, _)) =>
          val nextPathKey = tlvs.get[NextPathKey].map(_.pathKey).getOrElse(defaultNextPathKey)
          Right(RouteBlindingDecryptedData(tlvs, nextPathKey))
      }
    }
  }

}
