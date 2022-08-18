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
import fr.acinq.eclair.wire.protocol.CommonCodecs.varint
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.TlvCodecs.genericTlv
import fr.acinq.eclair.{CltvExpiryDelta, Feature, Features, InvoiceFeature, MilliSatoshi, TimestampSecond, UInt64, nodeFee, randomBytes32}
import fr.acinq.secp256k1.Secp256k1JvmKt
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.vector

import scala.util.{Failure, Try}

/**
 * Lightning Bolt 12 offers
 * see https://github.com/lightning/bolts/blob/master/12-offer-encoding.md
 */
object OfferTypes {

  sealed trait Bolt12Tlv extends Tlv

  sealed trait InvoiceTlv extends Bolt12Tlv

  sealed trait InvoiceRequestTlv extends InvoiceTlv

  sealed trait OfferTlv extends InvoiceRequestTlv

  sealed trait InvoiceErrorTlv extends Bolt12Tlv

  case class OfferChains(chains: Seq[ByteVector32]) extends OfferTlv

  case class OfferMetadata(data: ByteVector) extends OfferTlv

  case class OfferCurrency(iso4217: String) extends OfferTlv

  case class OfferAmount(amount: MilliSatoshi) extends OfferTlv

  case class OfferDescription(description: String) extends OfferTlv

  case class OfferFeatures(features: Features[Feature]) extends OfferTlv

  case class OfferAbsoluteExpiry(absoluteExpiry: TimestampSecond) extends OfferTlv

  case class OfferPaths(paths: Seq[BlindedRoute]) extends OfferTlv

  case class OfferIssuer(issuer: String) extends OfferTlv

  case class OfferQuantityMax(max: Long) extends OfferTlv

  case class OfferNodeId(publicKey: PublicKey) extends OfferTlv

  case class InvoiceRequestMetadata(data: ByteVector) extends InvoiceRequestTlv

  case class InvoiceRequestChain(hash: ByteVector32) extends InvoiceRequestTlv

  case class InvoiceRequestAmount(amount: MilliSatoshi) extends InvoiceRequestTlv

  case class InvoiceRequestFeatures(features: Features[Feature]) extends InvoiceRequestTlv

  case class InvoiceRequestQuantity(quantity: Long) extends InvoiceRequestTlv

  case class InvoiceRequestPayerId(publicKey: PublicKey) extends InvoiceRequestTlv

  case class InvoiceRequestPayerNote(note: String) extends InvoiceRequestTlv

  case class InvoicePaths(paths: Seq[BlindedRoute]) extends InvoiceTlv

  case class PaymentInfo(feeBase: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         cltvExpiryDelta: CltvExpiryDelta,
                         minHtlc: MilliSatoshi,
                         maxHtlc: MilliSatoshi,
                         allowedFeatures: Features[Feature]) {
    def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(feeBase, feeProportionalMillionths, amount)
  }

  case class InvoiceBlindedPay(paymentInfo: Seq[PaymentInfo]) extends InvoiceTlv

  case class InvoiceCreatedAt(timestamp: TimestampSecond) extends InvoiceTlv

  case class InvoiceRelativeExpiry(seconds: Long) extends InvoiceTlv

  case class InvoicePaymentHash(hash: ByteVector32) extends InvoiceTlv

  case class InvoiceAmount(amount: MilliSatoshi) extends InvoiceTlv

  case class FallbackAddress(version: Byte, value: ByteVector)

  case class InvoiceFallbacks(addresses: Seq[FallbackAddress]) extends InvoiceTlv

  case class InvoiceFeatures(features: Features[Feature]) extends InvoiceTlv

  case class InvoiceNodeId(nodeId: PublicKey) extends InvoiceTlv

  case class Signature(signature: ByteVector64) extends InvoiceRequestTlv

  case class ErroneousField(tag: Long) extends InvoiceErrorTlv

  case class SuggestedValue(value: ByteVector) extends InvoiceErrorTlv

  case class Error(message: String) extends InvoiceErrorTlv

  case class Offer(records: TlvStream[OfferTlv]) {
    val chains: Seq[ByteVector32] = records.get[OfferChains].map(_.chains).getOrElse(Seq(Block.LivenetGenesisBlock.hash))
    val metadata: Option[ByteVector] = records.get[OfferMetadata].map(_.data)
    val currency: Option[String] = records.get[OfferCurrency].map(_.iso4217)
    val amount: Option[MilliSatoshi] = currency match {
      case Some(_) => None // TODO: add exchange rates
      case None => records.get[OfferAmount].map(_.amount)
    }
    val description: String = records.get[OfferDescription].get.description
    val features: Features[InvoiceFeature] = records.get[OfferFeatures].map(_.features.invoiceFeatures()).getOrElse(Features.empty)
    val expiry: Option[TimestampSecond] = records.get[OfferAbsoluteExpiry].map(_.absoluteExpiry)
    private val paths: Option[Seq[BlindedRoute]] = records.get[OfferPaths].map(_.paths)
    val issuer: Option[String] = records.get[OfferIssuer].map(_.issuer)
    val quantityMax: Option[Long] = records.get[OfferQuantityMax].map(_.max).map { q => if (q == 0) Long.MaxValue else q }
    val nodeId: PublicKey = records.get[OfferNodeId].map(_.publicKey).get

    val contactInfo: Either[Seq[BlindedRoute], PublicKey] = paths.map(Left(_)).getOrElse(Right(nodeId))

    def encode(): String = {
      val data = OfferCodecs.offerTlvCodec.encode(records).require.bytes
      Bech32.encodeBytes(Offer.hrp, data.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    }

    override def toString: String = encode()
  }

  object Offer {
    val hrp = "lno"

    /**
     * @param amount_opt  amount if it can be determined at offer creation time.
     * @param description description of the offer.
     * @param nodeId      the nodeId to use for this offer, which should be different from our public nodeId if we're hiding behind a blinded route.
     * @param features    invoice features.
     * @param chain       chain on which the offer is valid.
     */
    def apply(amount_opt: Option[MilliSatoshi], description: String, nodeId: PublicKey, features: Features[InvoiceFeature], chain: ByteVector32): Offer = {
      val tlvs: Seq[OfferTlv] = Seq(
        if (chain != Block.LivenetGenesisBlock.hash) Some(OfferChains(Seq(chain))) else None,
        amount_opt.map(OfferAmount),
        Some(OfferDescription(description)),
        if (!features.isEmpty) Some(OfferFeatures(features.unscoped())) else None,
        Some(OfferNodeId(nodeId)),
      ).flatten
      Offer(TlvStream(tlvs))
    }

    def validate(records: TlvStream[OfferTlv]): Either[InvalidTlvPayload, Offer] = {
      if (records.get[OfferDescription].isEmpty) return Left(MissingRequiredTlv(UInt64(10)))
      if (records.get[OfferNodeId].isEmpty) return Left(MissingRequiredTlv(UInt64(22)))
      if (records.unknown.exists(_.tag >= UInt64(80))) return Left(ForbiddenTlv(records.unknown.find(_.tag >= UInt64(80)).get.tag))
      Right(Offer(records))
    }

    def decode(s: String): Try[Offer] = Try {
      val triple = Bech32.decodeBytes(s.toLowerCase, true)
      val prefix = triple.getFirst
      val encoded = triple.getSecond
      val encoding = triple.getThird
      require(prefix == hrp)
      require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
      val tlvs = OfferCodecs.offerTlvCodec.decode(ByteVector(encoded).bits).require.value
      validate(tlvs) match {
        case Left(f) => return Failure(new IllegalArgumentException(f.toString))
        case Right(offer) => offer
      }
    }
  }

  case class InvoiceRequest(records: TlvStream[InvoiceRequestTlv]) {
    val offer: Offer = Offer.validate(TlvStream[OfferTlv](records.records.collect { case tlv: OfferTlv => tlv }, records.unknown.filter(_.tag < UInt64(80)))).toOption.get

    val metadata: Option[ByteVector] = records.get[InvoiceRequestMetadata].map(_.data)
    val chain: ByteVector32 = records.get[InvoiceRequestChain].map(_.hash).getOrElse(Block.LivenetGenesisBlock.hash)
    val amount: Option[MilliSatoshi] = records.get[InvoiceRequestAmount].map(_.amount)
    val features: Features[InvoiceFeature] = records.get[InvoiceRequestFeatures].map(_.features.invoiceFeatures()).getOrElse(Features.empty)
    val quantity_opt: Option[Long] = records.get[InvoiceRequestQuantity].map(_.quantity)
    val quantity: Long = quantity_opt.getOrElse(1)
    val payerId: PublicKey = records.get[InvoiceRequestPayerId].get.publicKey
    val payerNote: Option[String] = records.get[InvoiceRequestPayerNote].map(_.note)
    private val signature: ByteVector64 = records.get[Signature].get.signature

    def isValidFor(otherOffer: Offer): Boolean = {
      val amountOk = offer.amount match {
        case Some(offerAmount) =>
          val baseInvoiceAmount = offerAmount * quantity
          amount.forall(baseInvoiceAmount <= _)
        case None => amount.nonEmpty
      }
      offer == otherOffer &&
        amountOk &&
        offer.chains.contains(chain) &&
        offer.quantityMax.forall(max => quantity_opt.nonEmpty && quantity <= max) &&
        quantity_opt.forall(_ => offer.quantityMax.nonEmpty) &&
        offer.features.areSupported(features) &&
        checkSignature()
    }

    def checkSignature(): Boolean = {
      verifySchnorr(InvoiceRequest.signatureTag, rootHash(removeSignature(records), OfferCodecs.invoiceRequestTlvCodec), signature, payerId)
    }

    def encode(): String = {
      val data = OfferCodecs.invoiceRequestTlvCodec.encode(records).require.bytes
      Bech32.encodeBytes(InvoiceRequest.hrp, data.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    }

    override def toString: String = encode()

    def unsigned: TlvStream[InvoiceRequestTlv] = removeSignature(records)
  }

  object InvoiceRequest {
    val hrp = "lnr"
    val signatureTag: ByteVector = ByteVector(("lightning" + "invoice_request" + "signature").getBytes)

    /**
     * Create a request to fetch an invoice for a given offer.
     *
     * @param offer    Bolt 12 offer.
     * @param amount   amount that we want to pay.
     * @param quantity quantity of items we're buying.
     * @param features invoice features.
     * @param payerKey private key identifying the payer: this lets us prove we're the ones who paid the invoice.
     * @param chain    chain we want to use to pay this offer.
     */
    def apply(offer: Offer, amount: MilliSatoshi, quantity: Long, features: Features[InvoiceFeature], payerKey: PrivateKey, chain: ByteVector32): InvoiceRequest = {
      require(offer.chains.contains(chain))
      require(quantity == 1 || offer.quantityMax.nonEmpty)
      val tlvs: Seq[InvoiceRequestTlv] = InvoiceRequestMetadata(randomBytes32()) +: (offer.records.records.toSeq ++ Seq(
        Some(InvoiceRequestChain(chain)),
        Some(InvoiceRequestAmount(amount)),
        if (offer.quantityMax.nonEmpty) Some(InvoiceRequestQuantity(quantity)) else None,
        if (!features.isEmpty) Some(InvoiceRequestFeatures(features.unscoped())) else None,
        Some(InvoiceRequestPayerId(payerKey.publicKey)),
      ).flatten)
      val signature = signSchnorr(signatureTag, rootHash(TlvStream(tlvs), OfferCodecs.invoiceRequestTlvCodec), payerKey)
      InvoiceRequest(TlvStream(tlvs :+ Signature(signature), offer.records.unknown))
    }

    def validate(records: TlvStream[InvoiceRequestTlv]): Either[InvalidTlvPayload, InvoiceRequest] = {
      Offer.validate(TlvStream[OfferTlv](records.records.collect { case tlv: OfferTlv => tlv }, records.unknown.filter(_.tag < UInt64(80)))).fold(
        invalidTlvPayload => return Left(invalidTlvPayload),
        _ -> ()
      )
      if (records.get[InvoiceRequestPayerId].isEmpty) return Left(MissingRequiredTlv(UInt64(88)))
      if (records.get[Signature].isEmpty) return Left(MissingRequiredTlv(UInt64(240)))
      if (records.unknown.exists(_.tag >= UInt64(160))) return Left(ForbiddenTlv(records.unknown.find(_.tag >= UInt64(160)).get.tag))
      Right(InvoiceRequest(records))
    }

    def decode(s: String): Try[InvoiceRequest] = Try {
      val triple = Bech32.decodeBytes(s.toLowerCase, true)
      val prefix = triple.getFirst
      val encoded = triple.getSecond
      val encoding = triple.getThird
      require(prefix == hrp)
      require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
      val tlvs = OfferCodecs.invoiceRequestTlvCodec.decode(ByteVector(encoded).bits).require.value
      validate(tlvs) match {
        case Left(f) => return Failure(new IllegalArgumentException(f.toString))
        case Right(invoiceRequest) => invoiceRequest
      }
    }
  }

  case class InvoiceError(records: TlvStream[InvoiceErrorTlv]) {
    val error = records.get[Error].get.message
  }

  object InvoiceError {
    def validate(records: TlvStream[InvoiceErrorTlv]): Either[InvalidTlvPayload, InvoiceError] = {
      if (records.get[Error].isEmpty) return Left(MissingRequiredTlv(UInt64(5)))
      Right(InvoiceError(records))
    }
  }

  def rootHash[T <: Tlv](tlvs: TlvStream[T], codec: Codec[TlvStream[T]]): ByteVector32 = {
    // Encoding tlvs is always safe, unless we have a bug in our codecs, so we can call `.require` here.
    val encoded = codec.encode(tlvs).require
    // Decoding tlvs that we just encoded is safe as well.
    // This encoding/decoding step ensures that the resulting tlvs are ordered.
    val genericTlvs = vector(genericTlv).decode(encoded).require.value
    val firstTlv = genericTlvs.minBy(_.tag)
    val nonceKey = ByteVector("LnNonce".getBytes) ++ genericTlv.encode(firstTlv).require.bytes

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
        val tlvType = varint.encode(genericTlvs(i).tag).require.bytes
        (hash(ByteVector("LnLeaf".getBytes), tlv), hash(nonceKey, tlvType))
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

  private def hash(tag: ByteVector, msg: ByteVector): ByteVector32 = {
    val tagHash = Crypto.sha256(tag)
    Crypto.sha256(tagHash ++ tagHash ++ msg)
  }

  def signSchnorr(tag: ByteVector, msg: ByteVector32, key: PrivateKey): ByteVector64 = {
    val h = hash(tag, msg)
    // NB: we don't add auxiliary random data to keep signatures deterministic.
    ByteVector64(ByteVector(Secp256k1JvmKt.getSecpk256k1.signSchnorr(h.toArray, key.value.toArray, null)))
  }

  def verifySchnorr(tag: ByteVector, msg: ByteVector32, signature: ByteVector64, publicKey: PublicKey): Boolean = {
    val h = hash(tag, msg)
    Secp256k1JvmKt.getSecpk256k1.verifySchnorr(signature.toArray, h.toArray, publicKey.value.drop(1).toArray)
  }

  /** We often need to remove the signature field to compute the merkle root. */
  def removeSignature[T <: Bolt12Tlv](records: TlvStream[T]): TlvStream[T] = {
    TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
  }

}

