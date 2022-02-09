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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.wire.protocol.OfferCodecs.{Bech32WithoutChecksum, invoiceCodec, invoiceTlvCodec}
import fr.acinq.eclair.wire.protocol.Offers._
import fr.acinq.eclair.wire.protocol.TlvStream
import fr.acinq.eclair.{CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, TimestampSecond}
import scodec.bits.ByteVector

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
 * Lightning Bolt 12 invoice
 * see https://github.com/lightning/bolts/blob/master/12-offer-encoding.md
 */
case class Bolt12Invoice(records: TlvStream[InvoiceTlv]) extends Invoice {
  import Bolt12Invoice._

  require(records.get[Amount].nonEmpty)
  require(records.get[NodeId].nonEmpty)
  require(records.get[PaymentHash].nonEmpty)
  require(records.get[Description].nonEmpty)
  require(records.get[CreatedAt].nonEmpty)
  require(records.get[Signature].nonEmpty)

  val amount: MilliSatoshi = records.get[Amount].map(_.amount).get

  override val amount_opt: Option[MilliSatoshi] = Some(amount)

  override val nodeId: Crypto.PublicKey = records.get[NodeId].get.nodeId

  override val paymentHash: ByteVector32 = records.get[PaymentHash].get.hash

  override val paymentSecret: Option[ByteVector32] = None

  override val paymentMetadata: Option[ByteVector] = None

  override val description: Either[String, ByteVector32] = Left(records.get[Description].get.description)

  override val routingInfo: Seq[Seq[ExtraHop]] = Seq.empty

  override val createdAt: TimestampSecond = records.get[CreatedAt].get.timestamp

  override val relativeExpiry: FiniteDuration = FiniteDuration(records.get[RelativeExpiry].map(_.seconds).getOrElse(DEFAULT_EXPIRY_SECONDS), TimeUnit.SECONDS)

  override val minFinalCltvExpiryDelta: Option[CltvExpiryDelta] = records.get[Cltv].map(_.minFinalCltvExpiry)

  override val features: Features[InvoiceFeature] = records.get[FeaturesTlv].map(_.features.invoiceFeatures()).getOrElse(Features.empty)

  override def toString: String = Bech32WithoutChecksum.encode("lni", invoiceCodec, this)

  val chain: ByteVector32 = records.get[Chain].map(_.hash).getOrElse(Block.LivenetGenesisBlock.hash)

  val offerId: Option[ByteVector32] = records.get[OfferId].map(_.offerId)

  private val paths: Option[Seq[RouteBlinding.BlindedRoute]] = records.get[Paths].map(_.paths)

  private val blindedpay: Option[Seq[PayInfo]] = records.get[BlindedPay].map(_.payInfos)

  private val blindedCapacities: Option[Seq[MilliSatoshi]] = records.get[BlindedCapacities].map(_.capacities)

  require(paths.map(_.map(_.blindedNodes.length - 1).sum) == blindedpay.map(_.length))
  require(blindedCapacities.forall(c => paths.map(_.length).contains(c.length)))

  val blindedPaths: Option[Seq[BlindedPath]] = paths.map(routes => {
    var remainingPayInfos = blindedpay.get
    routes.zip(blindedCapacities.map(_.map(Some(_))).getOrElse(routes.map(_ => None))).map {
      case (route, capacity) =>
        val payInfos = remainingPayInfos.take(route.blindedNodes.length - 1)
        remainingPayInfos = remainingPayInfos.drop(route.blindedNodes.length - 1)
        BlindedPath(route, payInfos, capacity)
    }
  })

  val issuer: Option[String] = records.get[Issuer].map(_.issuer)

  val quantity: Option[Long] = records.get[Quantity].map(_.quantity)

  val refundFor: Option[ByteVector32] = records.get[RefundFor].map(_.refundedPaymentHash)

  val payerKey: Option[ByteVector32] = records.get[PayerKey].map(_.key)

  val payerNote: Option[String] = records.get[PayerNote].map(_.note)

  val payerInfo: Option[ByteVector] = records.get[PayerInfo].map(_.info)

  val fallbacks: Option[Seq[FallbackAddress]] = records.get[Fallbacks].map(_.addresses)

  val refundSignature: Option[ByteVector64] = records.get[RefundSignature].map(_.signature)

  val replaceInvoice: Option[ByteVector32] = records.get[ReplaceInvoice].map(_.paymentHash)

  val signature: ByteVector64 = records.get[Signature].get.signature

  def isValidFor(offer: Offer, request: InvoiceRequest): Boolean = {
    nodeId.value.drop(1) == offer.nodeId.value.drop(1) &&
      checkSignature() &&
      offerId.contains(offer.offerId) &&
      offer.chains.contains(chain) &&
      !isExpired() &&
      request.amount.contains(amount) &&
      quantity == request.quantity_opt &&
      payerKey.contains(request.payerKey) &&
      payerInfo == request.payerInfo &&
      payerNote.forall(request.payerNote.contains(_)) && // It's OK to have a payer's note in the request but not in the invoice.
      description == Left(offer.description) &&
      issuer == offer.issuer &&
      request.features.areSupported(features)
  }

  def checkRefundSignature(): Boolean = {
    (refundSignature, refundFor, payerKey) match {
      case (Some(sig), Some(hash), Some(key)) =>
        verifySchnorr("lightning" + "invoice" + "payer_signature", hash, sig, key)
      case _ => false
    }
  }

  def checkSignature(): Boolean = {
    val withoutSig = TlvStream(records.records.filter { case _: Signature => false case _ => true }, records.unknown)
    verifySchnorr("lightning" + "invoice" + "signature", rootHash(withoutSig, invoiceTlvCodec).get, signature, ByteVector32(nodeId.value.drop(1)))
  }

  def withNodeId(id: PublicKey): Bolt12Invoice =
    Bolt12Invoice(TlvStream(records.records.map { case NodeId(_) => NodeId(id) case x => x }, records.unknown))
}

object Bolt12Invoice {
  val DEFAULT_EXPIRY_SECONDS: Long = 7200

  case class BlindedPath(route: RouteBlinding.BlindedRoute, payInfos: Seq[PayInfo], capacity: Option[MilliSatoshi])

  /**
   * Creates an invoice for a given offer and invoice request
   * @param offer    the offer this invoices corresponds to
   * @param request  the request this invoice responds to
   * @param preimage the preimage to use for the payment
   * @param nodeKey  the key that was used to generate the offer, may be different from our public nodeId if we're hiding behind a blinded route
   * @param features invoice features
   */
  def apply(offer: Offer, request: InvoiceRequest, preimage: ByteVector, nodeKey: PrivateKey, features: Features[InvoiceFeature]): Bolt12Invoice = {
    require(request.amount.nonEmpty || offer.amount.nonEmpty)
    val tlvs: Seq[InvoiceTlv] = Seq(
      Some(CreatedAt(TimestampSecond.now())),
      Some(PaymentHash(Crypto.sha256(preimage))),
      Some(OfferId(offer.offerId)),
      Some(NodeId(nodeKey.publicKey)),
      Some(Amount(request.amount.orElse(offer.amount.map(_ * request.quantity)).get)),
      Some(Description(offer.description)),
      request.quantity_opt.map(Quantity),
      Some(Chain(request.chain)),
      Some(PayerKey(request.payerKey)),
      request.payerInfo.map(PayerInfo),
      request.payerNote.map(PayerNote),
      request.replaceInvoice.map(ReplaceInvoice),
      offer.issuer.map(Issuer),
      Some(FeaturesTlv(features.unscoped()))
    ).flatten
    val signature = signSchnorr("lightning" + "invoice" + "signature", rootHash(TlvStream(tlvs), invoiceTlvCodec).get, nodeKey)
    Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
  }

  def fromString(input: String): Bolt12Invoice = Bech32WithoutChecksum.decode("lni", invoiceCodec, input.toLowerCase).get
}
