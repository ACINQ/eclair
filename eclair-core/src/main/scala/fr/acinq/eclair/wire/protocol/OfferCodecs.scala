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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Bech32, ByteVector32}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.wire.protocol.CommonCodecs.{bytes32, bytes64, millisatoshi, millisatoshi32, publicKey, varint, varintoverflow}
import fr.acinq.eclair.wire.protocol.Offers._
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.MissingRequiredTlv
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tmillisatoshi, tu32, tu64overflow}
import fr.acinq.eclair.{CltvExpiryDelta, FeatureScope, Features, MilliSatoshi, TimestampSecond, UInt64}
import scodec.bits.{ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Attempt, Codec}

import scala.util.Try
import scala.util.matching.Regex

object OfferCodecs {
  private val chains: Codec[Chains] = variableSizeBytesLong(varintoverflow, list(bytes32)).xmap[Seq[ByteVector32]](_.toSeq, _.toList).as[Chains]

  private val currency: Codec[Currency] = variableSizeBytesLong(varintoverflow, utf8).as[Currency]

  private val amount: Codec[Amount] = variableSizeBytesLong(varintoverflow, tmillisatoshi).as[Amount]

  private val description: Codec[Description] = variableSizeBytesLong(varintoverflow, utf8).as[Description]

  private val features: Codec[FeaturesTlv] = variableSizeBytesLong(varintoverflow, bytes).xmap[Features[FeatureScope]](Features(_), _.toByteVector).as[FeaturesTlv]

  private val absoluteExpiry: Codec[AbsoluteExpiry] = variableSizeBytesLong(varintoverflow, tu64overflow).as[TimestampSecond].as[AbsoluteExpiry]

  private val blindedNodeCodec: Codec[BlindedNode] = (("nodeId" | publicKey) :: ("encryptedData" | variableSizeBytes(uint16, bytes))).as[BlindedNode]

  private val pathCodec: Codec[BlindedRoute] = (("firstNodeId" | publicKey) :: ("blinding" | publicKey) :: ("path" | listOfN(uint8, blindedNodeCodec).xmap[Seq[BlindedNode]](_.toSeq, _.toList))).as[BlindedRoute]

  private val paths: Codec[Paths] = variableSizeBytesLong(varintoverflow, list(pathCodec)).xmap[Seq[BlindedRoute]](_.toSeq, _.toList).as[Paths]

  private val issuer: Codec[Issuer] = variableSizeBytesLong(varintoverflow, utf8).as[Issuer]

  private val quantityMin: Codec[QuantityMin] = variableSizeBytesLong(varintoverflow, tu64overflow).as[QuantityMin]

  private val quantityMax: Codec[QuantityMax] = variableSizeBytesLong(varintoverflow, tu64overflow).as[QuantityMax]

  // Only the x component of the key is serialized. We arbitrarily add hex"02" to get a valid key.
  // When using this key, we need to try both the version starting with hex"02" and the one starting with hex"03".
  private val nodeId: Codec[NodeId] = variableSizeBytesLong(varintoverflow, bytes32).xmap[PublicKey](b32 => PublicKey(hex"02" ++ b32), key => ByteVector32(key.value.drop(1))).as[NodeId]

  private val sendInvoice: Codec[SendInvoice] = variableSizeBytesLong(varintoverflow, provide(SendInvoice()))

  private val refundFor: Codec[RefundFor] = variableSizeBytesLong(varintoverflow, bytes32).as[RefundFor]

  private val signature: Codec[Signature] = variableSizeBytesLong(varintoverflow, bytes64).as[Signature]

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

  val offerCodec: Codec[Offer] = offerTlvCodec.narrow({ tlvs =>
    if (tlvs.get[Description].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(10)))
    }
    if (tlvs.get[NodeId].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(30)))
    }
    Attempt.successful(Offer(tlvs))
  }, {
    case Offer(tlvs) => tlvs
  })

  private val chain: Codec[Chain] = variableSizeBytesLong(varintoverflow, bytes32).as[Chain]

  private val offerId: Codec[OfferId] = variableSizeBytesLong(varintoverflow, bytes32).as[OfferId]

  private val quantity: Codec[Quantity] = variableSizeBytesLong(varintoverflow, tu64overflow).as[Quantity]

  private val payerKey: Codec[PayerKey] = variableSizeBytesLong(varintoverflow, bytes32).as[PayerKey]

  private val payerNote: Codec[PayerNote] = variableSizeBytesLong(varintoverflow, utf8).as[PayerNote]

  private val payerInfo: Codec[PayerInfo] = variableSizeBytesLong(varintoverflow, bytes).as[PayerInfo]

  private val replaceInvoice: Codec[ReplaceInvoice] = variableSizeBytesLong(varintoverflow, bytes32).as[ReplaceInvoice]

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

  val invoiceRequestCodec: Codec[InvoiceRequest] = invoiceRequestTlvCodec.narrow({ tlvs =>
    if (tlvs.get[OfferId].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(4)))
    } else if (tlvs.get[PayerKey].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(38)))
    } else if (tlvs.get[Signature].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(240)))
    } else {
      Attempt.successful(InvoiceRequest(tlvs))
    }
  }, {
    case InvoiceRequest(tlvs) => tlvs
  })

  private val payInfo: Codec[PayInfo] = variableSizeBytesLong(varintoverflow,
    ("fee_base_msat" | millisatoshi32) ::
      ("fee_proportional_millionths" | uint32) ::
      ("cltv_expiry_delta" | uint16.as[CltvExpiryDelta]) ::
      ("features" | variableSizeBytes(uint16, bytes).xmap[Features[FeatureScope]](Features(_), _.toByteVector))).as[PayInfo]

  private val blindedPay: Codec[BlindedPay] = variableSizeBytesLong(varintoverflow, list(payInfo)).xmap[Seq[PayInfo]](_.toSeq, _.toList).as[BlindedPay]

  private val blindedCapacities: Codec[BlindedCapacities] = variableSizeBytesLong(varintoverflow, list(millisatoshi)).xmap[Seq[MilliSatoshi]](_.toSeq, _.toList).as[BlindedCapacities]

  private val createdAt: Codec[CreatedAt] = variableSizeBytesLong(varintoverflow, tu64overflow).as[TimestampSecond].as[CreatedAt]

  private val paymentHash: Codec[PaymentHash] = variableSizeBytesLong(varintoverflow, bytes32).as[PaymentHash]

  private val relativeExpiry: Codec[RelativeExpiry] = variableSizeBytesLong(varintoverflow, tu32).as[RelativeExpiry]

  private val cltv: Codec[Cltv] = variableSizeBytesLong(varintoverflow, uint16).as[CltvExpiryDelta].as[Cltv]

  private val fallbackAddress: Codec[FallbackAddress] = variableSizeBytesLong(varintoverflow,
    ("version" | byte) ::
      ("address" | variableSizeBytes(uint16, bytes))).as[FallbackAddress]

  private val fallbacks: Codec[Fallbacks] = variableSizeBytesLong(varintoverflow, listOfN(uint8, fallbackAddress)).xmap[Seq[FallbackAddress]](_.toSeq, _.toList).as[Fallbacks]

  private val refundSignature: Codec[RefundSignature] = variableSizeBytesLong(varintoverflow, bytes64).as[RefundSignature]

  val invoiceTlvCodec: Codec[TlvStream[InvoiceTlv]] = TlvCodecs.tlvStream[InvoiceTlv](discriminated[InvoiceTlv].by(varint)
    .typecase(UInt64(3), chain)
    .typecase(UInt64(4), offerId)
    .typecase(UInt64(8), amount)
    .typecase(UInt64(10), description)
    .typecase(UInt64(12), features)
    .typecase(UInt64(16), paths)
    .typecase(UInt64(18), blindedPay)
    .typecase(UInt64(19), blindedCapacities)
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

  val invoiceCodec: Codec[Bolt12Invoice] = invoiceTlvCodec.narrow({ tlvs =>
    if (tlvs.get[Amount].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(8)))
    } else if (tlvs.get[Description].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(10)))
    } else if (tlvs.get[NodeId].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(30)))
    } else if (tlvs.get[CreatedAt].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(40)))
    } else if (tlvs.get[PaymentHash].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(42)))
    } else if (tlvs.get[Signature].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(240)))
    } else {
      Attempt.successful(Bolt12Invoice(tlvs))
    }
  }, {
    case Bolt12Invoice(tlvs) => tlvs
  })

  val invoiceErrorTlvCodec: Codec[TlvStream[InvoiceErrorTlv]] = TlvCodecs.tlvStream[InvoiceErrorTlv](discriminated[InvoiceErrorTlv].by(varint)
    .typecase(UInt64(1), variableSizeBytesLong(varintoverflow, tu64overflow).as[ErroneousField])
    .typecase(UInt64(3), variableSizeBytesLong(varintoverflow, bytes).as[SuggestedValue])
    .typecase(UInt64(5), variableSizeBytesLong(varintoverflow, utf8).as[Error])
  ).complete

  val invoiceErrorCodec: Codec[InvoiceError] = invoiceErrorTlvCodec.narrow({ tlvs =>
    if (tlvs.get[Error].isEmpty) {
      Attempt.failure(MissingRequiredTlv(UInt64(5)))
    } else {
      Attempt.successful(InvoiceError(tlvs))
    }
  }, {
    case InvoiceError(tlvs) => tlvs
  })

  object Bech32WithoutChecksum {
    def encode[A](hrp: String, codec: Codec[A], data: A): String = {
      val bits = codec.encode(data).require
      val int5s = Bech32.eight2five(bits.bytes.toArray)
      hrp + "1" + new String(int5s.map(i => Bech32.alphabet(i)))
    }

    def stripPluses(s: String): String = {
      val builder = new StringBuilder(s.length)
      require('a' <= s(0) && s(0) <= 'z')
      builder += s(0)
      var i = 1
      while(i < s.length){
        if (s(i) == '+') {
          i += 1
          while(s(i).isWhitespace) {
            i += 1
          }
        }
        require(('a' <= s(i) && s(i) <= 'z') || ('0' <= s(i) && s(i) <= '9'))
        builder += s(i)
        i += 1
      }
      builder.result()
    }

    def decode[A](hrp: String, codec: Codec[A], s: String): Try[A] = Try {
      val bech32withoutChecksum = stripPluses(s)
      val pos = bech32withoutChecksum.lastIndexOf('1')
      require(bech32withoutChecksum.take(pos) == hrp, s"unexpected hrp: ${bech32withoutChecksum.take(pos)}")
      val data = new Array[Bech32.Int5](bech32withoutChecksum.length - pos - 1)
      for (i <- data.indices) {
        val elt = Bech32.map(bech32withoutChecksum(pos + 1 + i))
        require(elt < 32, s"invalid bech32 character ${bech32withoutChecksum(pos + 1 + i)}")
        data(i) = elt
      }
      val bytes = ByteVector(Bech32.five2eight(data))
      codec.decode(bytes.bits).require.value
    }
  }
}
