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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.lengthPrefixedFeaturesCodec
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tmillisatoshi, tu32, tu64overflow}
import fr.acinq.eclair.{CltvExpiryDelta, Feature, Features, MilliSatoshi, TimestampSecond, UInt64}
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

object OfferCodecs {
  private val chains: Codec[Chains] = tlvField(list(bytes32).xmap[Seq[ByteVector32]](_.toSeq, _.toList).as[Chains])

  private val currency: Codec[Currency] = tlvField(utf8.as[Currency])

  private val amount: Codec[Amount] = tlvField(tmillisatoshi.as[Amount])

  private val description: Codec[Description] = tlvField(utf8.as[Description])

  private val features: Codec[FeaturesTlv] = tlvField(bytes.xmap[Features[Feature]](Features(_), _.toByteVector).as[FeaturesTlv])

  private val absoluteExpiry: Codec[AbsoluteExpiry] = tlvField(tu64overflow.as[TimestampSecond].as[AbsoluteExpiry])

  private val blindedNodeCodec: Codec[BlindedNode] = (("nodeId" | publicKey) :: ("encryptedData" | variableSizeBytes(uint16, bytes))).as[BlindedNode]
  private val blindedNodesCodec: Codec[Seq[BlindedNode]] = listOfN(uint8, blindedNodeCodec).exmap(
    nodes => if (nodes.length <= 1) Attempt.Failure(Err("blinded route must contain at least two nodes")) else Attempt.Successful(nodes.toSeq),
    nodes => if (nodes.length <= 1) Attempt.Failure(Err("blinded route must contain at least two nodes")) else Attempt.Successful(nodes.toList),
  )
  private val pathCodec: Codec[BlindedRoute] = (("firstNodeId" | publicKey) :: ("blinding" | publicKey) :: ("path" | blindedNodesCodec)).as[BlindedRoute]
  private val paths: Codec[Paths] = tlvField(list(pathCodec).xmap[Seq[BlindedRoute]](_.toSeq, _.toList).as[Paths])

  private val issuer: Codec[Issuer] = tlvField(utf8.as[Issuer])

  private val quantityMin: Codec[QuantityMin] = tlvField(tu64overflow.as[QuantityMin])

  private val quantityMax: Codec[QuantityMax] = tlvField(tu64overflow.as[QuantityMax])

  private val nodeId: Codec[NodeId] = tlvField(publicKey.as[NodeId])

  private val sendInvoice: Codec[SendInvoice] = tlvField(provide(SendInvoice()))

  private val refundFor: Codec[RefundFor] = tlvField(bytes32.as[RefundFor])

  private val signature: Codec[Signature] = tlvField(bytes64.as[Signature])

  val offerTlvCodec: Codec[TlvStream[OfferTlv]] = TlvCodecs.tlvStream[OfferTlv](discriminated[OfferTlv].by(varint)
    .typecase(UInt64(2), chains)
    .typecase(UInt64(6), currency)
    .typecase(UInt64(8), amount)
    .typecase(UInt64(10), description)
    .typecase(UInt64(12), features)
    .typecase(UInt64(14), absoluteExpiry)
    .typecase(UInt64(16), paths)
    .typecase(UInt64(20), issuer)
    .typecase(UInt64(22), quantityMin)
    .typecase(UInt64(24), quantityMax)
    .typecase(UInt64(30), nodeId)
    .typecase(UInt64(34), refundFor)
    .typecase(UInt64(54), sendInvoice)
    .typecase(UInt64(240), signature)).complete

  private val chain: Codec[Chain] = tlvField(bytes32.as[Chain])

  private val offerId: Codec[OfferId] = tlvField(bytes32.as[OfferId])

  private val quantity: Codec[Quantity] = tlvField(tu64overflow.as[Quantity])

  private val payerKey: Codec[PayerKey] = tlvField(bytes32.as[PayerKey])

  private val payerNote: Codec[PayerNote] = tlvField(utf8.as[PayerNote])

  private val payerInfo: Codec[PayerInfo] = tlvField(bytes.as[PayerInfo])

  private val replaceInvoice: Codec[ReplaceInvoice] = tlvField(bytes32.as[ReplaceInvoice])

  val invoiceRequestTlvCodec: Codec[TlvStream[InvoiceRequestTlv]] = TlvCodecs.tlvStream[InvoiceRequestTlv](discriminated[InvoiceRequestTlv].by(varint)
    .typecase(UInt64(3), chain)
    .typecase(UInt64(4), offerId)
    .typecase(UInt64(8), amount)
    .typecase(UInt64(12), features)
    .typecase(UInt64(32), quantity)
    .typecase(UInt64(38), payerKey)
    .typecase(UInt64(39), payerNote)
    .typecase(UInt64(50), payerInfo)
    .typecase(UInt64(56), replaceInvoice)
    .typecase(UInt64(240), signature)).complete

  private val paymentInfo: Codec[PaymentInfo] = (("fee_base_msat" | millisatoshi32) ::
    ("fee_proportional_millionths" | uint32) ::
    ("cltv_expiry_delta" | cltvExpiryDelta) ::
    ("htlc_minimum_msat" | millisatoshi) ::
    ("htlc_maximum_msat" | millisatoshi) ::
    ("features" | lengthPrefixedFeaturesCodec)).as[PaymentInfo]

  private val paymentPathsInfo: Codec[PaymentPathsInfo] = tlvField(list(paymentInfo).xmap[Seq[PaymentInfo]](_.toSeq, _.toList).as[PaymentPathsInfo])

  private val paymentPathsCapacities: Codec[PaymentPathsCapacities] = tlvField(list(millisatoshi).xmap[Seq[MilliSatoshi]](_.toSeq, _.toList).as[PaymentPathsCapacities])

  private val createdAt: Codec[CreatedAt] = tlvField(tu64overflow.as[TimestampSecond].as[CreatedAt])

  private val paymentHash: Codec[PaymentHash] = tlvField(bytes32.as[PaymentHash])

  private val relativeExpiry: Codec[RelativeExpiry] = tlvField(tu32.as[RelativeExpiry])

  private val cltv: Codec[Cltv] = tlvField(uint16.as[CltvExpiryDelta].as[Cltv])

  private val fallbackAddress: Codec[FallbackAddress] = variableSizeBytesLong(varintoverflow, ("version" | byte) :: ("address" | variableSizeBytes(uint16, bytes))).as[FallbackAddress]

  private val fallbacks: Codec[Fallbacks] = tlvField(list(fallbackAddress).xmap[Seq[FallbackAddress]](_.toSeq, _.toList).as[Fallbacks])

  private val refundSignature: Codec[RefundSignature] = tlvField(bytes64.as[RefundSignature])

  val invoiceTlvCodec: Codec[TlvStream[InvoiceTlv]] = TlvCodecs.tlvStream[InvoiceTlv](discriminated[InvoiceTlv].by(varint)
    .typecase(UInt64(3), chain)
    .typecase(UInt64(4), offerId)
    .typecase(UInt64(8), amount)
    .typecase(UInt64(10), description)
    .typecase(UInt64(12), features)
    // TODO: the spec for payment paths is not final, adjust codecs if changes are made to he spec.
    .typecase(UInt64(16), paths)
    .typecase(UInt64(18), paymentPathsInfo)
    .typecase(UInt64(19), paymentPathsCapacities)
    .typecase(UInt64(20), issuer)
    .typecase(UInt64(30), nodeId)
    .typecase(UInt64(32), quantity)
    .typecase(UInt64(34), refundFor)
    .typecase(UInt64(38), payerKey)
    .typecase(UInt64(39), payerNote)
    .typecase(UInt64(40), createdAt)
    .typecase(UInt64(42), paymentHash)
    .typecase(UInt64(44), relativeExpiry)
    .typecase(UInt64(46), cltv)
    .typecase(UInt64(48), fallbacks)
    .typecase(UInt64(50), payerInfo)
    .typecase(UInt64(52), refundSignature)
    .typecase(UInt64(56), replaceInvoice)
    .typecase(UInt64(240), signature)
  ).complete

  val invoiceErrorTlvCodec: Codec[TlvStream[InvoiceErrorTlv]] = TlvCodecs.tlvStream[InvoiceErrorTlv](discriminated[InvoiceErrorTlv].by(varint)
    .typecase(UInt64(1), tlvField(tu64overflow.as[ErroneousField]))
    .typecase(UInt64(3), tlvField(bytes.as[SuggestedValue]))
    .typecase(UInt64(5), tlvField(utf8.as[Error]))
  ).complete

}
