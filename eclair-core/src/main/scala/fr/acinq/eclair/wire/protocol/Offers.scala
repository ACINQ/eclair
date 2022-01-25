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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.wire.protocol.OfferCodecs.{Bech32WithoutChecksum, invoiceRequestCodec, invoiceRequestTlvCodec, offerCodec, offerTlvCodec}
import fr.acinq.eclair.wire.protocol.TlvCodecs.genericTlv
import fr.acinq.eclair.{CltvExpiryDelta, FeatureScope, Features, InvoiceFeature, MilliSatoshi, TimestampSecond, randomBytes32}
import fr.acinq.secp256k1.Secp256k1JvmKt
import scodec.Attempt.{Failure, Successful}
import scodec.bits.ByteVector
import scodec.codecs.vector
import scodec.{Codec, DecodeResult}

import scala.util.Try

object Offers {

  sealed trait OfferTlv extends Tlv

  sealed trait InvoiceRequestTlv extends Tlv

  sealed trait InvoiceTlv extends Tlv

  sealed trait InvoiceErrorTlv extends Tlv

  case class Chains(chains: Seq[ByteVector32]) extends OfferTlv

  case class Currency(iso4217: String) extends OfferTlv

  case class Amount(amount: MilliSatoshi) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class Description(description: String) extends OfferTlv with InvoiceTlv

  case class FeaturesTlv(features: Features[FeatureScope]) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class AbsoluteExpiry(absoluteExpiry: TimestampSecond) extends OfferTlv

  case class Paths(paths: Seq[BlindedRoute]) extends OfferTlv with InvoiceTlv

  case class Issuer(issuer: String) extends OfferTlv with InvoiceTlv

  case class QuantityMin(min: Long) extends OfferTlv

  case class QuantityMax(max: Long) extends OfferTlv

  case class NodeId(nodeId: PublicKey) extends OfferTlv with InvoiceTlv

  case class SendInvoice() extends OfferTlv with InvoiceTlv

  case class RefundFor(refundedPaymentHash: ByteVector32) extends OfferTlv with InvoiceTlv

  case class Signature(signature: ByteVector64) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class Chain(hash: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class OfferId(offerId: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class Quantity(quantity: Long) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerKey(key: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerNote(note: String) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerInfo(info: ByteVector) extends InvoiceRequestTlv with InvoiceTlv

  case class ReplaceInvoice(paymentHash: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class PayInfo(feeBase: MilliSatoshi, feeProportionalMillionths: Long, cltvExpiryDelta: CltvExpiryDelta, features: Features[FeatureScope])

  case class BlindedPay(payInfos: Seq[PayInfo]) extends InvoiceTlv

  case class BlindedCapacities(capacities: Seq[MilliSatoshi]) extends InvoiceTlv

  case class CreatedAt(timestamp: TimestampSecond) extends InvoiceTlv

  case class PaymentHash(hash: ByteVector32) extends InvoiceTlv

  case class RelativeExpiry(seconds: Long) extends InvoiceTlv

  case class Cltv(minFinalCltvExpiry: CltvExpiryDelta) extends InvoiceTlv

  case class FallbackAddress(version: Byte, value: ByteVector)

  case class Fallbacks(addresses: Seq[FallbackAddress]) extends InvoiceTlv

  case class RefundSignature(signature: ByteVector64) extends InvoiceTlv

  case class ErroneousField(tag: Long) extends InvoiceErrorTlv

  case class SuggestedValue(value: ByteVector) extends InvoiceErrorTlv

  case class Error(message: String) extends InvoiceErrorTlv

  case class Offer(records: TlvStream[OfferTlv]) {
    val offerId: ByteVector32 = {
      val withoutSig = TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
      rootHash(withoutSig, offerTlvCodec).get
    }

    val chains: Seq[ByteVector32] = records.get[Chains].map(_.chains).getOrElse(Seq(Block.LivenetGenesisBlock.hash))

    val currency: Option[String] = records.get[Currency].map(_.iso4217)

    val amount: Option[MilliSatoshi] = currency match {
      case Some(_) => None // TODO: add exchange rates
      case None => records.get[Amount].map(_.amount)
    }

    val description: String = records.get[Description].get.description

    val features: Features[InvoiceFeature] = records.get[FeaturesTlv].map(_.features.invoiceFeatures()).getOrElse(Features.empty)

    val expiry: Option[TimestampSecond] = records.get[AbsoluteExpiry].map(_.absoluteExpiry)

    val issuer: Option[String] = records.get[Issuer].map(_.issuer)

    val quantityMin: Option[Long] = records.get[QuantityMin].map(_.min)
    val quantityMax: Option[Long] = records.get[QuantityMax].map(_.max)

    val nodeId: PublicKey = records.get[NodeId].get.nodeId

    val sendInvoice: Boolean = records.get[SendInvoice].nonEmpty

    val refundFor: Option[ByteVector32] = records.get[RefundFor].map(_.refundedPaymentHash)

    val signature: Option[ByteVector64] = records.get[Signature].map(_.signature)

    val contact: OnionMessages.Destination = records.get[Paths].flatMap(_.paths.headOption).map(OnionMessages.BlindedPath).getOrElse(OnionMessages.Recipient(nodeId, None, None))

    def sign(key: PrivateKey): Offer = {
      val sig = signSchnorr("lightning" + "offer" + "signature", rootHash(records, offerTlvCodec).get, key)
      Offer(TlvStream[OfferTlv](records.records ++ Seq(Signature(sig)), records.unknown))
    }

    def checkSignature: Boolean = {
      signature match {
        case Some(sig) =>
          val withoutSig = TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
          verifySchnorr("lightning" + "offer" + "signature", rootHash(withoutSig, offerTlvCodec).get, sig, ByteVector32(nodeId.value.drop(1)))
        case None => false
      }
    }

    def encode: String = Bech32WithoutChecksum.encode("lno", offerCodec, this)
  }

  object Offer {
    def apply(amount_opt: Option[MilliSatoshi], description: String, nodeId: PublicKey):Offer = {
      val tlvs: Seq[OfferTlv] = Seq(
        amount_opt.map(Amount),
        Some(Description(description)),
        Some(NodeId(nodeId))).flatten
      Offer(TlvStream(tlvs))
    }

    def decode(s: String): Try[Offer] = Bech32WithoutChecksum.decode("lno", offerCodec, s)
  }

  case class InvoiceRequest(records: TlvStream[InvoiceRequestTlv]) {
    val chain: ByteVector32 = records.get[Chain].map(_.hash).getOrElse(Block.LivenetGenesisBlock.hash)

    val offerId: ByteVector32 = records.get[OfferId].map(_.offerId).get

    val amount: Option[MilliSatoshi] = records.get[Amount].map(_.amount)

    val features: Features[InvoiceFeature] = records.get[FeaturesTlv].map(_.features.invoiceFeatures()).getOrElse(Features.empty)

    val quantity_opt: Option[Long] = records.get[Quantity].map(_.quantity)

    val quantity: Long = quantity_opt.getOrElse(1)

    val payerKey: ByteVector32 = records.get[PayerKey].get.key

    val payerNote: Option[String] = records.get[PayerNote].map(_.note)

    val payerInfo: Option[ByteVector] = records.get[PayerInfo].map(_.info)

    val replaceInvoice: Option[ByteVector32] = records.get[ReplaceInvoice].map(_.paymentHash)

    val payerSignature: ByteVector64 = records.get[Signature].get.signature

    def checkSignature: Boolean = {
      val withoutSig = TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
      verifySchnorr("lightning" + "invoice_request" + "payer_signature", rootHash(withoutSig, invoiceRequestTlvCodec).get, payerSignature, payerKey)
    }

    def encode: String = Bech32WithoutChecksum.encode("lnr", invoiceRequestCodec, this)
  }

  object InvoiceRequest {
    def apply(offer: Offer, amount: MilliSatoshi, quantity: Long, features: Features[InvoiceFeature], payerKey: PrivateKey, chain: ByteVector32 = Block.LivenetGenesisBlock.hash): InvoiceRequest = {
      val requestTlvs: Seq[InvoiceRequestTlv] = Seq(
        Some(Chain(chain)),
        Some(OfferId(offer.offerId)),
        Some(Amount(amount)),
        if (offer.quantityMin.nonEmpty || offer.quantityMax.nonEmpty) Some(Quantity(quantity)) else None,
        Some(PayerKey(ByteVector32(payerKey.publicKey.value.drop(1))))).flatten
      val signature = signSchnorr("lightning" + "invoice_request" + "payer_signature", rootHash(TlvStream(requestTlvs), invoiceRequestTlvCodec).get, payerKey)
      InvoiceRequest(TlvStream(requestTlvs :+ Signature(signature)))
    }

    def decode(s: String): Try[InvoiceRequest] = Bech32WithoutChecksum.decode("lnr", invoiceRequestCodec, s)
  }

  case class InvoiceError(error: TlvStream[InvoiceErrorTlv]) {
  }

  def rootHash[T <: Tlv](tlvs: TlvStream[T], codec: Codec[TlvStream[T]]): Option[ByteVector32] = {
    codec.encode(tlvs) match {
      case Failure(_) =>
        println("Can't encode")
        None
      case Successful(encoded) => vector(genericTlv).decode(encoded) match {
        case Failure(f) =>
          println(s"Can't decode ${encoded.toHex}: $f")
          None
        case Successful(DecodeResult(tlvVector, _)) =>

          def hash(tag: ByteVector, msg: ByteVector): ByteVector32 = {
            val tagHash = Crypto.sha256(tag)
            Crypto.sha256(tagHash ++ tagHash ++ msg)
          }

          def previousPowerOfTwo(n : Int) : Int = {
            var p = 1
            while (p < n) {
              p = p << 1
            }
            p >> 1
          }

          def merkleTree(i: Int, j: Int): ByteVector32 = {
            val (a, b) = if (j - i == 1) {
              val tlv = genericTlv.encode(tlvVector(i)).require.bytes
              (hash(ByteVector("LnLeaf".getBytes), tlv), hash(ByteVector("LnAll".getBytes) ++ encoded.bytes, tlv))
            } else {
              val k = i + previousPowerOfTwo(j - i)
              (merkleTree(i, k), merkleTree(k, j))
            }
            if (LexicographicalOrdering.isLessThan(a, b)) {
              hash(ByteVector("LnBranch".getBytes), a ++ b)
            } else {
              hash(ByteVector("LnBranch".getBytes), b ++ a)
            }
          }

          Some(merkleTree(0, tlvVector.length))
      }
    }
  }

  private def hashtag(tag: String): ByteVector = {
    ByteVector.encodeAscii(tag) match {
      case Right(bytes) => Crypto.sha256(bytes)
      case Left(e) => throw e
    }
  }

  def signSchnorr(tag: String, msg: ByteVector32, key: PrivateKey): ByteVector64 = {
    val h = hashtag(tag)
    val auxrand32 = randomBytes32()
    ByteVector64(ByteVector(Secp256k1JvmKt.getSecpk256k1.signSchnorr(Crypto.sha256(h ++ h ++ msg).toArray, key.value.toArray, auxrand32.toArray)))
  }

  def verifySchnorr(tag: String, msg: ByteVector32, signature: ByteVector64, key: ByteVector32): Boolean = {
    val h = hashtag(tag)
    Secp256k1JvmKt.getSecpk256k1.verifySchnorr(signature.toArray, Crypto.sha256(h ++ h ++ msg).toArray, key.toArray)
  }

}

