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
import fr.acinq.eclair.{Bolt12Feature, CltvExpiryDelta, Feature, Features, MilliSatoshi, TimestampSecond, UInt64, nodeFee, randomBytes32}
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

  /**
   * Chains for which the offer is valid. If empty, bitcoin mainnet is implied.
   */
  case class OfferChains(chains: Seq[ByteVector32]) extends OfferTlv

  /**
   * Data from the offer creator to themselves, for instance a signature that authenticates the offer so that they don't need to store the offer.
   */
  case class OfferMetadata(data: ByteVector) extends OfferTlv

  /**
   * Three-letter code of the currency the offer is denominated in. If empty, bitcoin is implied.
   */
  case class OfferCurrency(iso4217: String) extends OfferTlv

  /**
   * Amount to pay per item. As we only support bitcoin, the amount is in msat.
   */
  case class OfferAmount(amount: MilliSatoshi) extends OfferTlv

  /**
   * Description of the purpose of the payment.
   */
  case class OfferDescription(description: String) extends OfferTlv

  /**
   * Features supported to pay the offer.
   */
  case class OfferFeatures(features: Features[Feature]) extends OfferTlv

  /**
   * Time after which the offer is no longer valid.
   */
  case class OfferAbsoluteExpiry(absoluteExpiry: TimestampSecond) extends OfferTlv

  /**
   * Paths that can be used to retrieve an invoice.
   */
  case class OfferPaths(paths: Seq[BlindedRoute]) extends OfferTlv

  /**
   * Name of the offer creator.
   */
  case class OfferIssuer(issuer: String) extends OfferTlv

  /**
   * If present, the item described in the offer can be purchased multiple times with a single payment.
   * If max = 0, there is no limit on the quantity that can be purchased in a single payment.
   * If max > 1, it corresponds to the maximum number of items that be purchased in a single payment.
   */
  case class OfferQuantityMax(max: Long) extends OfferTlv

  /**
   * Public key of the offer creator.
   * If `OfferPaths` is present, they must be used to retrieve an invoice even if this public key corresponds to a node id in the public network.
   * If `OfferPaths` is not present, this public key must correspond to a node id in the public network that needs to be contacted to retrieve an invoice.
   */
  case class OfferNodeId(publicKey: PublicKey) extends OfferTlv

  /**
   * Random data to provide enough entropy so that some fields of the invoice request / invoice can be revealed without revealing the others.
   */
  case class InvoiceRequestMetadata(data: ByteVector) extends InvoiceRequestTlv

  /**
   * If `OfferChains` is present, this specifies which chain is going to be used to pay.
   */
  case class InvoiceRequestChain(hash: ByteVector32) extends InvoiceRequestTlv

  /**
   * Amount that the sender is going to send.
   */
  case class InvoiceRequestAmount(amount: MilliSatoshi) extends InvoiceRequestTlv

  /**
   * Features supported by the sender to pay the offer.
   */
  case class InvoiceRequestFeatures(features: Features[Feature]) extends InvoiceRequestTlv

  /**
   * Number of items to purchase. Only use if the offer supports purchasing multiple items at once.
   */
  case class InvoiceRequestQuantity(quantity: Long) extends InvoiceRequestTlv

  /**
   * A public key for which the sender know the corresponding private key.
   * This can be used to prove that you are the sender.
   */
  case class InvoiceRequestPayerId(publicKey: PublicKey) extends InvoiceRequestTlv

  /**
   * A message from the sender.
   */
  case class InvoiceRequestPayerNote(note: String) extends InvoiceRequestTlv

  /**
   * Payment paths to send the payment to.
   */
  case class InvoicePaths(paths: Seq[BlindedRoute]) extends InvoiceTlv

  case class PaymentInfo(feeBase: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         cltvExpiryDelta: CltvExpiryDelta,
                         minHtlc: MilliSatoshi,
                         maxHtlc: MilliSatoshi,
                         allowedFeatures: Features[Feature]) {
    def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(feeBase, feeProportionalMillionths, amount)
  }

  /**
   * Costs and parameters of the paths in `InvoicePaths`.
   */
  case class InvoiceBlindedPay(paymentInfo: Seq[PaymentInfo]) extends InvoiceTlv

  /**
   * Time at which the invoice was created.
   */
  case class InvoiceCreatedAt(timestamp: TimestampSecond) extends InvoiceTlv

  /**
   * Duration after which the invoice can no longer be paid.
   */
  case class InvoiceRelativeExpiry(seconds: Long) extends InvoiceTlv

  /**
   * Hash whose preimage will be released in exchange for the payment.
   */
  case class InvoicePaymentHash(hash: ByteVector32) extends InvoiceTlv

  /**
   * Amount to pay. Must be the same as `InvoiceRequestAmount` if it was present.
   */
  case class InvoiceAmount(amount: MilliSatoshi) extends InvoiceTlv

  case class FallbackAddress(version: Byte, value: ByteVector)

  /**
   * Onchain addresses to use to pay the invoice in case the lightning payment fails.
   */
  case class InvoiceFallbacks(addresses: Seq[FallbackAddress]) extends InvoiceTlv

  /**
   * Features supported to pay the invoice.
   */
  case class InvoiceFeatures(features: Features[Feature]) extends InvoiceTlv

  /**
   * Public key of the invoice recipient.
   */
  case class InvoiceNodeId(nodeId: PublicKey) extends InvoiceTlv

  /**
   * Signature from the sender when used in an invoice request.
   * Signature from the recipient when used in an invoice.
   */
  case class Signature(signature: ByteVector64) extends InvoiceRequestTlv with InvoiceTlv

  def filterOfferFields(tlvs: TlvStream[InvoiceRequestTlv]): TlvStream[OfferTlv] =
    // Offer TLVs are in the range (0, 80).
    TlvStream[OfferTlv](tlvs.records.collect { case tlv: OfferTlv => tlv }, tlvs.unknown.filter(_.tag < UInt64(80)))

  def filterInvoiceRequestFields(tlvs: TlvStream[InvoiceTlv]): TlvStream[InvoiceRequestTlv] =
    // Invoice request TLVs are in the range [0, 160): invoice request metadata (tag 0), offer TLVs, and additional invoice request TLVs in the range [80, 160).
    TlvStream[InvoiceRequestTlv](tlvs.records.collect { case tlv: InvoiceRequestTlv => tlv }, tlvs.unknown.filter(_.tag < UInt64(160)))

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
    val features: Features[Bolt12Feature] = records.get[OfferFeatures].map(_.features.bolt12Features()).getOrElse(Features.empty)
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

    val offerId: ByteVector32 = rootHash(records, OfferCodecs.offerTlvCodec)
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
    def apply(amount_opt: Option[MilliSatoshi], description: String, nodeId: PublicKey, features: Features[Bolt12Feature], chain: ByteVector32): Offer = {
      val tlvs: Set[OfferTlv] = Set(
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
    val offer: Offer = Offer.validate(filterOfferFields(records)).toOption.get

    val metadata: ByteVector = records.get[InvoiceRequestMetadata].get.data
    val chain: ByteVector32 = records.get[InvoiceRequestChain].map(_.hash).getOrElse(Block.LivenetGenesisBlock.hash)
    val amount: Option[MilliSatoshi] = records.get[InvoiceRequestAmount].map(_.amount)
    val features: Features[Bolt12Feature] = records.get[InvoiceRequestFeatures].map(_.features.bolt12Features()).getOrElse(Features.empty)
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
        Features.areCompatible(offer.features, features) &&
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
    def apply(offer: Offer, amount: MilliSatoshi, quantity: Long, features: Features[Bolt12Feature], payerKey: PrivateKey, chain: ByteVector32): InvoiceRequest = {
      require(offer.chains.contains(chain))
      require(quantity == 1 || offer.quantityMax.nonEmpty)
      val tlvs: Set[InvoiceRequestTlv] = offer.records.records ++ Set(
        Some(InvoiceRequestMetadata(randomBytes32())),
        Some(InvoiceRequestChain(chain)),
        Some(InvoiceRequestAmount(amount)),
        if (offer.quantityMax.nonEmpty) Some(InvoiceRequestQuantity(quantity)) else None,
        if (!features.isEmpty) Some(InvoiceRequestFeatures(features.unscoped())) else None,
        Some(InvoiceRequestPayerId(payerKey.publicKey)),
      ).flatten
      val signature = signSchnorr(signatureTag, rootHash(TlvStream(tlvs, offer.records.unknown), OfferCodecs.invoiceRequestTlvCodec), payerKey)
      InvoiceRequest(TlvStream(tlvs + Signature(signature), offer.records.unknown))
    }

    def validate(records: TlvStream[InvoiceRequestTlv]): Either[InvalidTlvPayload, InvoiceRequest] = {
      Offer.validate(filterOfferFields(records)).fold(
        invalidTlvPayload => return Left(invalidTlvPayload),
        _ -> ()
      )
      if (records.get[InvoiceRequestMetadata].isEmpty) return Left(MissingRequiredTlv(UInt64(0)))
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
    val xonlyPublicKey = publicKey.value.drop(1) // Schnorr signature only use 32 bytes keys.
    Secp256k1JvmKt.getSecpk256k1.verifySchnorr(signature.toArray, h.toArray, xonlyPublicKey.toArray)
  }

  /** We often need to remove the signature field to compute the merkle root. */
  def removeSignature[T <: Bolt12Tlv](records: TlvStream[T]): TlvStream[T] = {
    TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
  }

}

