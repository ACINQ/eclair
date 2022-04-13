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

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.wire.protocol.OfferCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.genericTlv
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, InvoiceFeature, MilliSatoshi, TimestampSecond}
import fr.acinq.secp256k1.Secp256k1JvmKt
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.vector

import scala.util.Try

/**
 * Lightning Bolt 12 offers
 * see https://github.com/lightning/bolts/blob/master/12-offer-encoding.md
 */
object Offers {

  sealed trait Bolt12Tlv extends Tlv

  sealed trait OfferTlv extends Bolt12Tlv

  sealed trait InvoiceRequestTlv extends Bolt12Tlv

  sealed trait InvoiceTlv extends Bolt12Tlv

  sealed trait InvoiceErrorTlv extends Bolt12Tlv

  case class Chains(chains: Seq[ByteVector32]) extends OfferTlv

  case class Currency(iso4217: String) extends OfferTlv

  case class Amount(amount: MilliSatoshi) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class Description(description: String) extends OfferTlv with InvoiceTlv

  case class FeaturesTlv(features: Features[Feature]) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class AbsoluteExpiry(absoluteExpiry: TimestampSecond) extends OfferTlv

  case class Paths(paths: Seq[BlindedRoute]) extends OfferTlv with InvoiceTlv

  case class PaymentInfo(feeBase: MilliSatoshi, feeProportionalMillionths: Long, cltvExpiryDelta: CltvExpiryDelta)
  case class PaymentPathsInfo(paymentInfo: Seq[PaymentInfo]) extends InvoiceTlv

  case class PaymentConstraints(maxCltvExpiry: CltvExpiry, minHtlc: MilliSatoshi, allowedFeatures: Features[Feature])
  case class PaymentPathsConstraints(paymentConstraints: Seq[PaymentConstraints]) extends InvoiceTlv

  case class Issuer(issuer: String) extends OfferTlv with InvoiceTlv

  case class QuantityMin(min: Long) extends OfferTlv

  case class QuantityMax(max: Long) extends OfferTlv

  case class NodeId(xonly: ByteVector32) extends OfferTlv with InvoiceTlv {
    val nodeId1: PublicKey = PublicKey(2 +: xonly)
    val nodeId2: PublicKey = PublicKey(3 +: xonly)
  }

  object NodeId {
    def apply(publicKey: PublicKey): NodeId = NodeId(xOnlyPublicKey(publicKey))
  }

  case class SendInvoice() extends OfferTlv with InvoiceTlv

  case class RefundFor(refundedPaymentHash: ByteVector32) extends OfferTlv with InvoiceTlv

  case class Signature(signature: ByteVector64) extends OfferTlv with InvoiceRequestTlv with InvoiceTlv

  case class Chain(hash: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class OfferId(offerId: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  case class Quantity(quantity: Long) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerKey(publicKey: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

  object PayerKey {
    def apply(publicKey: PublicKey): PayerKey = PayerKey(xOnlyPublicKey(publicKey))
  }

  case class PayerNote(note: String) extends InvoiceRequestTlv with InvoiceTlv

  case class PayerInfo(info: ByteVector) extends InvoiceRequestTlv with InvoiceTlv

  case class ReplaceInvoice(paymentHash: ByteVector32) extends InvoiceRequestTlv with InvoiceTlv

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

    require(records.get[NodeId].nonEmpty, "bolt 12 offers must provide a node id")
    require(records.get[Description].nonEmpty, "bolt 12 offers must provide a description")

    val offerId: ByteVector32 = rootHash(removeSignature(records), offerTlvCodec)

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

    val nodeIdXOnly: ByteVector32 = records.get[NodeId].get.xonly

    val sendInvoice: Boolean = records.get[SendInvoice].nonEmpty

    val refundFor: Option[ByteVector32] = records.get[RefundFor].map(_.refundedPaymentHash)

    val signature: Option[ByteVector64] = records.get[Signature].map(_.signature)

    val contact: Seq[OnionMessages.Destination] =
      records.get[Paths].flatMap(_.paths.headOption).map(OnionMessages.BlindedPath).map(Seq(_))
        .getOrElse(Seq(PublicKey(2.toByte +: nodeIdXOnly), PublicKey(3.toByte +: nodeIdXOnly)).map(nodeId => OnionMessages.Recipient(nodeId, None, None)))

    def sign(key: PrivateKey): Offer = {
      val sig = signSchnorr(Offer.signatureTag, rootHash(records, offerTlvCodec), key)
      Offer(TlvStream[OfferTlv](records.records ++ Seq(Signature(sig)), records.unknown))
    }

    def checkSignature(): Boolean = {
      signature match {
        case Some(sig) => verifySchnorr(Offer.signatureTag, rootHash(removeSignature(records), offerTlvCodec), sig, nodeIdXOnly)
        case None => false
      }
    }

    def encode(): String = {
      val data = offerCodec.encode(this).require.bytes
      Bech32.encodeBytes(Offer.hrp, data.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    }

    override def toString: String = encode()
  }

  object Offer {
    val hrp = "lno"
    val signatureTag: String = "lightning" + "offer" + "signature"

    /**
     * @param amount_opt  amount if it can be determined at offer creation time.
     * @param description description of the offer.
     * @param nodeId      the nodeId to use for this offer, which should be different from our public nodeId if we're hiding behind a blinded route.
     * @param features    invoice features.
     * @param chain       chain on which the offer is valid.
     */
    def apply(amount_opt: Option[MilliSatoshi], description: String, nodeId: PublicKey, features: Features[InvoiceFeature], chain: ByteVector32): Offer = {
      val tlvs: Seq[OfferTlv] = Seq(
        if (chain != Block.LivenetGenesisBlock.hash) Some(Chains(Seq(chain))) else None,
        amount_opt.map(Amount),
        Some(Description(description)),
        Some(NodeId(nodeId)),
        if (!features.isEmpty) Some(FeaturesTlv(features.unscoped())) else None,
      ).flatten
      Offer(TlvStream(tlvs))
    }

    def decode(s: String): Try[Offer] = Try {
      val triple = Bech32.decodeBytes(s.toLowerCase, true)
      val prefix = triple.getFirst
      val encoded = triple.getSecond
      val encoding = triple.getThird
      require(prefix == hrp)
      require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
      offerCodec.decode(ByteVector(encoded).bits).require.value
    }
  }

  case class InvoiceRequest(records: TlvStream[InvoiceRequestTlv]) {

    require(records.get[OfferId].nonEmpty, "bolt 12 invoice requests must provide an offer id")
    require(records.get[PayerKey].nonEmpty, "bolt 12 invoice requests must provide a payer key")
    require(records.get[Signature].nonEmpty, "bolt 12 invoice requests must provide a payer signature")

    val chain: ByteVector32 = records.get[Chain].map(_.hash).getOrElse(Block.LivenetGenesisBlock.hash)

    val offerId: ByteVector32 = records.get[OfferId].map(_.offerId).get

    val amount: Option[MilliSatoshi] = records.get[Amount].map(_.amount)

    val features: Features[InvoiceFeature] = records.get[FeaturesTlv].map(_.features.invoiceFeatures()).getOrElse(Features.empty)

    val quantity_opt: Option[Long] = records.get[Quantity].map(_.quantity)

    val quantity: Long = quantity_opt.getOrElse(1)

    val payerKey: ByteVector32 = records.get[PayerKey].get.publicKey

    val payerNote: Option[String] = records.get[PayerNote].map(_.note)

    val payerInfo: Option[ByteVector] = records.get[PayerInfo].map(_.info)

    val replaceInvoice: Option[ByteVector32] = records.get[ReplaceInvoice].map(_.paymentHash)

    val payerSignature: ByteVector64 = records.get[Signature].get.signature

    def isValidFor(offer: Offer): Boolean = {
      val amountOk = offer.amount match {
        case Some(offerAmount) =>
          val baseInvoiceAmount = offerAmount * quantity
          amount.forall(baseInvoiceAmount <= _)
        case None => amount.nonEmpty
      }
      amountOk &&
        offer.offerId == offerId &&
        offer.chains.contains(chain) &&
        offer.quantityMin.forall(min => quantity_opt.nonEmpty && min <= quantity) &&
        offer.quantityMax.forall(max => quantity_opt.nonEmpty && quantity <= max) &&
        quantity_opt.forall(_ => offer.quantityMin.nonEmpty || offer.quantityMax.nonEmpty) &&
        offer.features.areSupported(features) &&
        checkSignature()
    }

    def checkSignature(): Boolean = {
      verifySchnorr(InvoiceRequest.signatureTag, rootHash(removeSignature(records), invoiceRequestTlvCodec), payerSignature, payerKey)
    }

    def encode(): String = {
      val data = invoiceRequestCodec.encode(this).require.bytes
      Bech32.encodeBytes(InvoiceRequest.hrp, data.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    }

    override def toString: String = encode()
  }

  object InvoiceRequest {
    val hrp = "lnr"
    val signatureTag: String = "lightning" + "invoice_request" + "payer_signature"

    /**
     * Create a request to fetch an invoice for a given offer.
     *
     * @param offer     Bolt 12 offer.
     * @param amount    amount that we want to pay.
     * @param quantity  quantity of items we're buying.
     * @param features  invoice features.
     * @param payerKey  private key identifying the payer: this lets us prove we're the ones who paid the invoice.
     * @param chain     chain we want to use to pay this offer.
     */
    def apply(offer: Offer, amount: MilliSatoshi, quantity: Long, features: Features[InvoiceFeature], payerKey: PrivateKey, chain: ByteVector32): InvoiceRequest = {
      require(offer.chains contains chain)
      require(quantity == 1 || offer.quantityMin.nonEmpty || offer.quantityMax.nonEmpty)
      val tlvs: Seq[InvoiceRequestTlv] = Seq(
        Some(Chain(chain)),
        Some(OfferId(offer.offerId)),
        Some(Amount(amount)),
        if (offer.quantityMin.nonEmpty || offer.quantityMax.nonEmpty) Some(Quantity(quantity)) else None,
        Some(PayerKey(payerKey.publicKey)),
        Some(FeaturesTlv(features.unscoped()))
      ).flatten
      val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), invoiceRequestTlvCodec), payerKey)
      InvoiceRequest(TlvStream(tlvs :+ Signature(signature)))
    }

    def decode(s: String): Try[InvoiceRequest] = Try {
      val triple = Bech32.decodeBytes(s.toLowerCase, true)
      val prefix = triple.getFirst
      val encoded = triple.getSecond
      val encoding = triple.getThird
      require(prefix == hrp)
      require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
      invoiceRequestCodec.decode(ByteVector(encoded).bits).require.value
    }
  }

  case class InvoiceError(records: TlvStream[InvoiceErrorTlv]) {
    require(records.get[Error].nonEmpty, "bolt 12 invoice errors must provide an explanatory string")
  }

  def rootHash[T <: Tlv](tlvs: TlvStream[T], codec: Codec[TlvStream[T]]): ByteVector32 = {
    // Encoding tlvs is always safe, unless we have a bug in our codecs, so we can call `.require` here.
    val encoded = codec.encode(tlvs).require
    // Decoding tlvs that we just encoded is safe as well.
    // This encoding/decoding step ensures that the resulting tlvs are ordered.
    val genericTlvs = vector(genericTlv).decode(encoded).require.value
    val nonceKey = ByteVector("LnAll".getBytes) ++ encoded.bytes

    def previousPowerOfTwo(n: Int): Int = {
      var p = 1
      while (p < n) {
        p = p << 1
      }
      p >> 1
    }

    def merkleTree(i: Int, j: Int): ByteVector32 = {
      val (a, b) = if (j - i == 1) {
        val tlv = genericTlv.encode(genericTlvs(i)).require.bytes
        (hash(ByteVector("LnLeaf".getBytes), tlv), hash(nonceKey, tlv))
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

    merkleTree(0, genericTlvs.length)
  }

  private def hash(tag: String, msg: ByteVector): ByteVector32 = {
    ByteVector.encodeAscii(tag) match {
      case Right(bytes) => hash(bytes, msg)
      case Left(e) => throw e // NB: the tags we use are hard-coded, so we know they're always ASCII
    }
  }

  private def hash(tag: ByteVector, msg: ByteVector): ByteVector32 = {
    val tagHash = Crypto.sha256(tag)
    Crypto.sha256(tagHash ++ tagHash ++ msg)
  }

  def signSchnorr(tag: String, msg: ByteVector32, key: PrivateKey): ByteVector64 = {
    val h = hash(tag, msg)
    // NB: we don't add auxiliary random data to keep signatures deterministic.
    ByteVector64(ByteVector(Secp256k1JvmKt.getSecpk256k1.signSchnorr(h.toArray, key.value.toArray, null)))
  }

  def verifySchnorr(tag: String, msg: ByteVector32, signature: ByteVector64, publicKey: ByteVector32): Boolean = {
    val h = hash(tag, msg)
    Secp256k1JvmKt.getSecpk256k1.verifySchnorr(signature.toArray, h.toArray, publicKey.toArray)
  }

  def xOnlyPublicKey(publicKey: PublicKey): ByteVector32 = ByteVector32(publicKey.value.drop(1))

  /** We often need to remove the signature field to compute the merkle root. */
  def removeSignature[T <: Bolt12Tlv](records: TlvStream[T]): TlvStream[T] = {
    TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
  }

}

