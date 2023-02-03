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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.{OfferCodecs, OfferTypes, TlvStream}
import fr.acinq.eclair.{Bolt12Feature, FeatureSupport, Features, InvoiceFeature, MilliSatoshi, TimestampSecond, UInt64}
import scodec.bits.ByteVector

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}

/**
 * Lightning Bolt 12 invoice
 * see https://github.com/lightning/bolts/blob/master/12-offer-encoding.md
 */
case class Bolt12Invoice(records: TlvStream[InvoiceTlv]) extends Invoice {

  import Bolt12Invoice._

  val invoiceRequest: InvoiceRequest = InvoiceRequest.validate(filterInvoiceRequestFields(records)).toOption.get

  val amount: MilliSatoshi = records.get[InvoiceAmount].map(_.amount).get
  override val amount_opt: Option[MilliSatoshi] = Some(amount)
  override val nodeId: Crypto.PublicKey = records.get[InvoiceNodeId].get.nodeId
  override val paymentHash: ByteVector32 = records.get[InvoicePaymentHash].get.hash
  override val description: Either[String, ByteVector32] = Left(invoiceRequest.offer.description)
  override val createdAt: TimestampSecond = records.get[InvoiceCreatedAt].get.timestamp
  override val relativeExpiry: FiniteDuration = FiniteDuration(records.get[InvoiceRelativeExpiry].map(_.seconds).getOrElse(DEFAULT_EXPIRY_SECONDS), TimeUnit.SECONDS)
  override val features: Features[InvoiceFeature] = {
    val f = records.get[InvoiceFeatures].map(_.features.invoiceFeatures()).getOrElse(Features.empty)
    // We add invoice features that are implicitly required for Bolt 12 (the spec doesn't allow explicitly setting them).
    f.add(Features.VariableLengthOnion, FeatureSupport.Mandatory).add(Features.RouteBlinding, FeatureSupport.Mandatory)
  }
  val blindedPaths: Seq[PaymentBlindedRoute] = records.get[InvoicePaths].get.paths.zip(records.get[InvoiceBlindedPay].get.paymentInfo).map { case (route, info) => PaymentBlindedRoute(route, info) }
  val fallbacks: Option[Seq[FallbackAddress]] = records.get[InvoiceFallbacks].map(_.addresses)
  val signature: ByteVector64 = records.get[Signature].get.signature

  // It is assumed that the request is valid for this offer.
  def validateFor(request: InvoiceRequest): Either[String, Unit] = {
    if (invoiceRequest.unsigned != request.unsigned) {
      Left("Invoice does not match request")
    } else if (nodeId != invoiceRequest.offer.nodeId) {
      Left("Wrong node id")
    } else if (isExpired()) {
      Left("Invoice expired")
    } else if (!request.amount.forall(_ == amount)) {
      Left("Incompatible amount")
    } else if (!Features.areCompatible(request.features, features.bolt12Features())) {
      Left("Incompatible features")
    } else if (!checkSignature()) {
      Left("Invalid signature")
    } else {
      Right(())
    }
  }

  def checkSignature(): Boolean = {
    verifySchnorr(signatureTag, rootHash(OfferTypes.removeSignature(records), OfferCodecs.invoiceTlvCodec), signature, nodeId)
  }

  override def toString: String = {
    val data = OfferCodecs.invoiceTlvCodec.encode(records).require.bytes
    Bech32.encodeBytes(hrp, data.toArray, Bech32.Encoding.Beck32WithoutChecksum)
  }

}

case class PaymentBlindedRoute(route: Sphinx.RouteBlinding.BlindedRoute, paymentInfo: PaymentInfo)

object Bolt12Invoice {
  val hrp = "lni"
  val signatureTag: ByteVector = ByteVector(("lightning" + "invoice" + "signature").getBytes)
  val DEFAULT_EXPIRY_SECONDS: Long = 7200

  /**
   * Creates an invoice for a given offer and invoice request.
   *
   * @param request  the request this invoice responds to
   * @param preimage the preimage to use for the payment
   * @param nodeKey  the key that was used to generate the offer, may be different from our public nodeId if we're hiding behind a blinded route
   * @param features invoice features
   * @param paths    the blinded paths to use to pay the invoice
   */
  def apply(request: InvoiceRequest,
            preimage: ByteVector32,
            nodeKey: PrivateKey,
            invoiceExpiry: FiniteDuration,
            features: Features[Bolt12Feature],
            paths: Seq[PaymentBlindedRoute]): Bolt12Invoice = {
    require(request.amount.nonEmpty || request.offer.amount.nonEmpty)
    val amount = request.amount.orElse(request.offer.amount.map(_ * request.quantity)).get
    val tlvs: Set[InvoiceTlv] = removeSignature(request.records).records ++ Set(
      Some(InvoicePaths(paths.map(_.route))),
      Some(InvoiceBlindedPay(paths.map(_.paymentInfo))),
      Some(InvoiceCreatedAt(TimestampSecond.now())),
      Some(InvoiceRelativeExpiry(invoiceExpiry.toSeconds)),
      Some(InvoicePaymentHash(Crypto.sha256(preimage))),
      Some(InvoiceAmount(amount)),
      if (!features.isEmpty) Some(InvoiceFeatures(features.unscoped())) else None,
      Some(InvoiceNodeId(nodeKey.publicKey)),
    ).flatten
    val signature = signSchnorr(signatureTag, rootHash(TlvStream(tlvs, request.records.unknown), OfferCodecs.invoiceTlvCodec), nodeKey)
    Bolt12Invoice(TlvStream(tlvs + Signature(signature), request.records.unknown))
  }

  def validate(records: TlvStream[InvoiceTlv]): Either[InvalidTlvPayload, Bolt12Invoice] = {
    InvoiceRequest.validate(filterInvoiceRequestFields(records)).fold(
      invalidTlvPayload => return Left(invalidTlvPayload),
      _ -> ()
    )
    if (records.get[InvoiceAmount].isEmpty) return Left(MissingRequiredTlv(UInt64(170)))
    if (records.get[InvoicePaths].forall(_.paths.isEmpty)) return Left(MissingRequiredTlv(UInt64(160)))
    if (records.get[InvoiceBlindedPay].map(_.paymentInfo.length) != records.get[InvoicePaths].map(_.paths.length)) return Left(MissingRequiredTlv(UInt64(162)))
    if (records.get[InvoiceNodeId].isEmpty) return Left(MissingRequiredTlv(UInt64(176)))
    if (records.get[InvoiceCreatedAt].isEmpty) return Left(MissingRequiredTlv(UInt64(164)))
    if (records.get[InvoicePaymentHash].isEmpty) return Left(MissingRequiredTlv(UInt64(168)))
    if (records.get[Signature].isEmpty) return Left(MissingRequiredTlv(UInt64(240)))
    Right(Bolt12Invoice(records))
  }

  def fromString(input: String): Try[Bolt12Invoice] = Try {
    val triple = Bech32.decodeBytes(input.toLowerCase, true)
    val prefix = triple.getFirst
    val encoded = triple.getSecond
    val encoding = triple.getThird
    require(prefix == hrp)
    require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
    val tlvs = OfferCodecs.invoiceTlvCodec.decode(ByteVector(encoded).bits).require.value
    validate(tlvs) match {
      case Left(f) => return Failure(new IllegalArgumentException(f.toString))
      case Right(invoice) => invoice
    }
  }
}
