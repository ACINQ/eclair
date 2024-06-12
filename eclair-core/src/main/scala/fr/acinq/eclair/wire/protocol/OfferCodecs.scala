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

import fr.acinq.bitcoin.scalacompat.BlockHash
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tmillisatoshi, tu32, tu64overflow}
import fr.acinq.eclair.{EncodedNodeId, TimestampSecond, UInt64}
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

object OfferCodecs {
  private val offerChains: Codec[OfferChains] = tlvField(list(blockHash).xmap[Seq[BlockHash]](_.toSeq, _.toList))

  private val offerMetadata: Codec[OfferMetadata] = tlvField(bytes)

  private val offerCurrency: Codec[OfferCurrency] = tlvField(utf8)

  private val offerAmount: Codec[OfferAmount] = tlvField(tmillisatoshi)

  private val offerDescription: Codec[OfferDescription] = tlvField(utf8)

  private val offerFeatures: Codec[OfferFeatures] = tlvField(featuresCodec)

  private val offerAbsoluteExpiry: Codec[OfferAbsoluteExpiry] = tlvField(tu64overflow.as[TimestampSecond])

  private val isNode1: Codec[Boolean] = uint8.narrow(
    n => if (n == 0) Attempt.Successful(true) else if (n == 1) Attempt.Successful(false) else Attempt.Failure(new Err.MatchingDiscriminatorNotFound(n)),
    b => if (b) 0 else 1
  )

  private val shortChannelIdDirCodec: Codec[EncodedNodeId.ShortChannelIdDir] =
    (("isNode1" | isNode1) ::
      ("scid" | realshortchannelid)).as[EncodedNodeId.ShortChannelIdDir]

  private val plainNodeIdCodec: Codec[EncodedNodeId.WithPublicKey.Plain] = Codec[EncodedNodeId.WithPublicKey.Plain](
    (nodeId: EncodedNodeId.WithPublicKey.Plain) => bytes(33).encode(nodeId.publicKey.value),
    (wire: BitVector) => bytes(33).decode(wire).flatMap(res => res.value.head match {
      case 0x02 | 0x03 => Attempt.successful(res.map(bin => EncodedNodeId.WithPublicKey.Plain(PublicKey(bin))))
      case d => Attempt.Failure(new Err.MatchingDiscriminatorNotFound(d))
    })
  )

  private val walletNodeIdCodec: Codec[EncodedNodeId.WithPublicKey.Wallet] = Codec[EncodedNodeId.WithPublicKey.Wallet](
    (nodeId: EncodedNodeId.WithPublicKey.Wallet) => bytes(33).encode((nodeId.publicKey.value.head + 2).toByte +: nodeId.publicKey.value.tail),
    (wire: BitVector) => bytes(33).decode(wire).flatMap(res => res.value.head match {
      case 0x04 | 0x05 => Attempt.successful(res.map(bin => EncodedNodeId.WithPublicKey.Wallet(PublicKey((bin.head - 2).toByte +: bin.tail))))
      case d => Attempt.Failure(new Err.MatchingDiscriminatorNotFound(d))
    })
  )

  val encodedNodeIdCodec: Codec[EncodedNodeId] = choice(
    shortChannelIdDirCodec.upcast[EncodedNodeId],
    plainNodeIdCodec.upcast[EncodedNodeId],
    walletNodeIdCodec.upcast[EncodedNodeId],
  )

  private val blindedNodeCodec: Codec[BlindedNode] =
    (("nodeId" | publicKey) ::
      ("encryptedData" | variableSizeBytes(uint16, bytes))).as[BlindedNode]

  private val blindedNodesCodec: Codec[Seq[BlindedNode]] = listOfN(uint8, blindedNodeCodec).xmap(_.toSeq, _.toList)

  val blindedRouteCodec: Codec[BlindedRoute] =
    (("firstNodeId" | encodedNodeIdCodec) ::
      ("blinding" | publicKey) ::
      ("path" | blindedNodesCodec)).as[BlindedRoute]

  private val offerPaths: Codec[OfferPaths] = tlvField(list(blindedRouteCodec).xmap[Seq[BlindedRoute]](_.toSeq, _.toList))

  private val offerIssuer: Codec[OfferIssuer] = tlvField(utf8)

  private val offerQuantityMax: Codec[OfferQuantityMax] = tlvField(tu64overflow)

  private val offerNodeId: Codec[OfferNodeId] = tlvField(publicKey)

  val offerTlvCodec: Codec[TlvStream[OfferTlv]] = TlvCodecs.tlvStream[OfferTlv](discriminated[OfferTlv].by(varint)
    .typecase(UInt64(2), offerChains)
    .typecase(UInt64(4), offerMetadata)
    .typecase(UInt64(6), offerCurrency)
    .typecase(UInt64(8), offerAmount)
    .typecase(UInt64(10), offerDescription)
    .typecase(UInt64(12), offerFeatures)
    .typecase(UInt64(14), offerAbsoluteExpiry)
    .typecase(UInt64(16), offerPaths)
    .typecase(UInt64(18), offerIssuer)
    .typecase(UInt64(20), offerQuantityMax)
    .typecase(UInt64(22), offerNodeId)
  ).complete

  private val invoiceRequestMetadata: Codec[InvoiceRequestMetadata] = tlvField(bytes)

  private val invoiceRequestChain: Codec[InvoiceRequestChain] = tlvField(blockHash)

  private val invoiceRequestAmount: Codec[InvoiceRequestAmount] = tlvField(tmillisatoshi)

  private val invoiceRequestFeatures: Codec[InvoiceRequestFeatures] = tlvField(featuresCodec)

  private val invoiceRequestQuantity: Codec[InvoiceRequestQuantity] = tlvField(tu64overflow)

  private val invoiceRequestPayerId: Codec[InvoiceRequestPayerId] = tlvField(publicKey)

  private val invoiceRequestPayerNote: Codec[InvoiceRequestPayerNote] = tlvField(utf8)

  private val signature: Codec[Signature] = tlvField(bytes64)

  val invoiceRequestTlvCodec: Codec[TlvStream[InvoiceRequestTlv]] = TlvCodecs.tlvStream[InvoiceRequestTlv](discriminated[InvoiceRequestTlv].by(varint)
    .typecase(UInt64(0), invoiceRequestMetadata)
    // Offer part that must be copy-pasted from above
    .typecase(UInt64(2), offerChains)
    .typecase(UInt64(4), offerMetadata)
    .typecase(UInt64(6), offerCurrency)
    .typecase(UInt64(8), offerAmount)
    .typecase(UInt64(10), offerDescription)
    .typecase(UInt64(12), offerFeatures)
    .typecase(UInt64(14), offerAbsoluteExpiry)
    .typecase(UInt64(16), offerPaths)
    .typecase(UInt64(18), offerIssuer)
    .typecase(UInt64(20), offerQuantityMax)
    .typecase(UInt64(22), offerNodeId)
    // Invoice request part
    .typecase(UInt64(80), invoiceRequestChain)
    .typecase(UInt64(82), invoiceRequestAmount)
    .typecase(UInt64(84), invoiceRequestFeatures)
    .typecase(UInt64(86), invoiceRequestQuantity)
    .typecase(UInt64(88), invoiceRequestPayerId)
    .typecase(UInt64(89), invoiceRequestPayerNote)
    .typecase(UInt64(240), signature)
  ).complete

  private val invoicePaths: Codec[InvoicePaths] = tlvField(list(blindedRouteCodec).xmap[Seq[BlindedRoute]](_.toSeq, _.toList))

  val paymentInfo: Codec[PaymentInfo] =
    (("fee_base_msat" | millisatoshi32) ::
      ("fee_proportional_millionths" | uint32) ::
      ("cltv_expiry_delta" | cltvExpiryDelta) ::
      ("htlc_minimum_msat" | millisatoshi) ::
      ("htlc_maximum_msat" | millisatoshi) ::
      ("features" | lengthPrefixedFeaturesCodec)).as[PaymentInfo]

  private val invoiceBlindedPay: Codec[InvoiceBlindedPay] = tlvField(list(paymentInfo).xmap[Seq[PaymentInfo]](_.toSeq, _.toList))

  private val invoiceCreatedAt: Codec[InvoiceCreatedAt] = tlvField(tu64overflow.as[TimestampSecond])

  private val invoiceRelativeExpiry: Codec[InvoiceRelativeExpiry] = tlvField(tu32)

  private val invoicePaymentHash: Codec[InvoicePaymentHash] = tlvField(bytes32)

  private val invoiceAmount: Codec[InvoiceAmount] = tlvField(tmillisatoshi)

  private val fallbackAddress: Codec[FallbackAddress] = (("version" | byte) :: ("address" | variableSizeBytes(uint16, bytes))).as[FallbackAddress]

  private val invoiceFallbacks: Codec[InvoiceFallbacks] = tlvField(list(fallbackAddress).xmap[Seq[FallbackAddress]](_.toSeq, _.toList))

  private val invoiceFeatures: Codec[InvoiceFeatures] = tlvField(featuresCodec)

  private val invoiceNodeId: Codec[InvoiceNodeId] = tlvField(publicKey)

  val invoiceTlvCodec: Codec[TlvStream[InvoiceTlv]] = TlvCodecs.tlvStream[InvoiceTlv](discriminated[InvoiceTlv].by(varint)
    // Invoice request part that must be copy-pasted from above
    .typecase(UInt64(0), invoiceRequestMetadata)
    .typecase(UInt64(2), offerChains)
    .typecase(UInt64(4), offerMetadata)
    .typecase(UInt64(6), offerCurrency)
    .typecase(UInt64(8), offerAmount)
    .typecase(UInt64(10), offerDescription)
    .typecase(UInt64(12), offerFeatures)
    .typecase(UInt64(14), offerAbsoluteExpiry)
    .typecase(UInt64(16), offerPaths)
    .typecase(UInt64(18), offerIssuer)
    .typecase(UInt64(20), offerQuantityMax)
    .typecase(UInt64(22), offerNodeId)
    .typecase(UInt64(80), invoiceRequestChain)
    .typecase(UInt64(82), invoiceRequestAmount)
    .typecase(UInt64(84), invoiceRequestFeatures)
    .typecase(UInt64(86), invoiceRequestQuantity)
    .typecase(UInt64(88), invoiceRequestPayerId)
    .typecase(UInt64(89), invoiceRequestPayerNote)
    // Invoice part
    .typecase(UInt64(160), invoicePaths)
    .typecase(UInt64(162), invoiceBlindedPay)
    .typecase(UInt64(164), invoiceCreatedAt)
    .typecase(UInt64(166), invoiceRelativeExpiry)
    .typecase(UInt64(168), invoicePaymentHash)
    .typecase(UInt64(170), invoiceAmount)
    .typecase(UInt64(172), invoiceFallbacks)
    .typecase(UInt64(174), invoiceFeatures)
    .typecase(UInt64(176), invoiceNodeId)
    .typecase(UInt64(240), signature)
  ).complete

  private val invoiceErrorTlvCodec: Codec[TlvStream[InvoiceErrorTlv]] = TlvCodecs.tlvStream[InvoiceErrorTlv](discriminated[InvoiceErrorTlv].by(varint)
    .typecase(UInt64(1), tlvField(tu64overflow.as[ErroneousField]))
    .typecase(UInt64(3), tlvField(bytes.as[SuggestedValue]))
    .typecase(UInt64(5), tlvField(utf8.as[Error]))
  ).complete

  val invoiceRequestCodec: Codec[OnionMessagePayloadTlv.InvoiceRequest] = tlvField(invoiceRequestTlvCodec)
  val invoiceCodec: Codec[OnionMessagePayloadTlv.Invoice] = tlvField(invoiceTlvCodec)
  val invoiceErrorCodec: Codec[OnionMessagePayloadTlv.InvoiceError] = tlvField(invoiceErrorTlvCodec)

}
