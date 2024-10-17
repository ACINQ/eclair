/*
 * Copyright 2019 ACINQ SAS
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

import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi, TxId}
import fr.acinq.eclair.channel.{ChannelType, ChannelTypes, PartialSignatureWithNonce}
import fr.acinq.eclair.wire.protocol.ChannelTlv.{nexLocalNonceTlvCodec, nexLocalNoncesTlvCodec}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream, tmillisatoshi}
import fr.acinq.eclair.{Alias, FeatureSupport, Features, MilliSatoshi, UInt64}
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

sealed trait OpenChannelTlv extends Tlv

sealed trait AcceptChannelTlv extends Tlv

sealed trait OpenDualFundedChannelTlv extends Tlv

sealed trait AcceptDualFundedChannelTlv extends Tlv

sealed trait TxInitRbfTlv extends Tlv

sealed trait TxAckRbfTlv extends Tlv

sealed trait SpliceInitTlv extends Tlv

sealed trait SpliceAckTlv extends Tlv

sealed trait SpliceLockedTlv extends Tlv

object ChannelTlv {

  /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
  case class UpfrontShutdownScriptTlv(script: ByteVector) extends OpenChannelTlv with AcceptChannelTlv with OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv {
    val isEmpty: Boolean = script.isEmpty
  }

  val upfrontShutdownScriptCodec: Codec[UpfrontShutdownScriptTlv] = tlvField(bytes)

  /** A channel type is a set of even feature bits that represent persistent features which affect channel operations. */
  case class ChannelTypeTlv(channelType: ChannelType) extends OpenChannelTlv with AcceptChannelTlv with OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv

  val channelTypeCodec: Codec[ChannelTypeTlv] = tlvField(bytes.xmap[ChannelTypeTlv](
    b => ChannelTypeTlv(ChannelTypes.fromFeatures(Features(b).initFeatures())),
    tlv => Features(tlv.channelType.features.map(f => f -> FeatureSupport.Mandatory).toMap).toByteVector
  ))

  case class RequireConfirmedInputsTlv() extends OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv with TxInitRbfTlv with TxAckRbfTlv with SpliceInitTlv with SpliceAckTlv

  val requireConfirmedInputsCodec: Codec[RequireConfirmedInputsTlv] = tlvField(provide(RequireConfirmedInputsTlv()))

  /** Request inbound liquidity from our peer. */
  case class RequestFundingTlv(request: LiquidityAds.RequestFunding) extends OpenDualFundedChannelTlv with TxInitRbfTlv with SpliceInitTlv

  val requestFundingCodec: Codec[RequestFundingTlv] = tlvField(LiquidityAds.Codecs.requestFunding)

  /** Accept inbound liquidity request. */
  case class ProvideFundingTlv(willFund: LiquidityAds.WillFund) extends AcceptDualFundedChannelTlv with TxAckRbfTlv with SpliceAckTlv

  val provideFundingCodec: Codec[ProvideFundingTlv] = tlvField(LiquidityAds.Codecs.willFund)

  /** Fee credit that will be used for the given on-the-fly funding operation. */
  case class FeeCreditUsedTlv(amount: MilliSatoshi) extends AcceptDualFundedChannelTlv with SpliceAckTlv

  val feeCreditUsedCodec: Codec[FeeCreditUsedTlv] = tlvField(tmillisatoshi)

  case class PushAmountTlv(amount: MilliSatoshi) extends OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv with SpliceInitTlv with SpliceAckTlv

  val pushAmountCodec: Codec[PushAmountTlv] = tlvField(tmillisatoshi)

  /**
   * This is an internal TLV for which we DON'T specify a codec: this isn't meant to be read or written on the wire.
   * This is only used to decorate open_channel2 and splice_init with the [[Features.FundingFeeCredit]] available.
   */
  case class UseFeeCredit(amount: MilliSatoshi) extends OpenDualFundedChannelTlv with SpliceInitTlv

  case class NextLocalNonceTlv(nonce: IndividualNonce) extends OpenChannelTlv with AcceptChannelTlv with OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv with ChannelReadyTlv with ChannelReestablishTlv with SpliceInitTlv with SpliceAckTlv

  val nexLocalNonceTlvCodec: Codec[NextLocalNonceTlv] = tlvField(publicNonce)

  case class NextLocalNoncesTlv(nonces: List[IndividualNonce]) extends OpenChannelTlv with AcceptChannelTlv with OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv with ChannelReadyTlv with ChannelReestablishTlv with SpliceInitTlv with SpliceAckTlv

  val nexLocalNoncesTlvCodec: Codec[NextLocalNoncesTlv] = tlvField(list(publicNonce))
}

object OpenChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(discriminated[OpenChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(4), nexLocalNonceTlvCodec)
  )

}

object AcceptChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptChannelTlv]] = tlvStream(discriminated[AcceptChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(4), nexLocalNonceTlvCodec)
  )
}

object OpenDualFundedChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenDualFundedChannelTlv]] = tlvStream(discriminated[OpenDualFundedChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    .typecase(UInt64(4), nexLocalNoncesTlvCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), requestFundingCodec)
    .typecase(UInt64(0x47000007), pushAmountCodec)
  )
}

object TxRbfTlv {
  /**
   * Amount that the peer will contribute to the transaction's shared output.
   * When used for splicing, this is a signed value that represents funds that are added or removed from the channel.
   */
  case class SharedOutputContributionTlv(amount: Satoshi) extends TxInitRbfTlv with TxAckRbfTlv
}

object TxInitRbfTlv {

  import ChannelTlv._
  import TxRbfTlv._

  val txInitRbfTlvCodec: Codec[TlvStream[TxInitRbfTlv]] = tlvStream(discriminated[TxInitRbfTlv].by(varint)
    .typecase(UInt64(0), tlvField(satoshiSigned.as[SharedOutputContributionTlv]))
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), requestFundingCodec)
  )
}

object TxAckRbfTlv {

  import ChannelTlv._
  import TxRbfTlv._

  val txAckRbfTlvCodec: Codec[TlvStream[TxAckRbfTlv]] = tlvStream(discriminated[TxAckRbfTlv].by(varint)
    .typecase(UInt64(0), tlvField(satoshiSigned.as[SharedOutputContributionTlv]))
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), provideFundingCodec)
  )
}

object SpliceInitTlv {

  import ChannelTlv._

  val spliceInitTlvCodec: Codec[TlvStream[SpliceInitTlv]] = tlvStream(discriminated[SpliceInitTlv].by(varint)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    .typecase(UInt64(4), nexLocalNoncesTlvCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), requestFundingCodec)
    .typecase(UInt64(0x47000007), tlvField(tmillisatoshi.as[PushAmountTlv]))
  )
}

object SpliceAckTlv {

  import ChannelTlv._

  val spliceAckTlvCodec: Codec[TlvStream[SpliceAckTlv]] = tlvStream(discriminated[SpliceAckTlv].by(varint)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    .typecase(UInt64(4), nexLocalNoncesTlvCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), provideFundingCodec)
    .typecase(UInt64(41042), feeCreditUsedCodec)
    .typecase(UInt64(0x47000007), tlvField(tmillisatoshi.as[PushAmountTlv]))
  )
}

object SpliceLockedTlv {
  val spliceLockedTlvCodec: Codec[TlvStream[SpliceLockedTlv]] = tlvStream(discriminated[SpliceLockedTlv].by(varint))
}

object AcceptDualFundedChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptDualFundedChannelTlv]] = tlvStream(discriminated[AcceptDualFundedChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    .typecase(UInt64(4), nexLocalNoncesTlvCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), provideFundingCodec)
    .typecase(UInt64(41042), feeCreditUsedCodec)
    .typecase(UInt64(0x47000007), pushAmountCodec)
  )

}

case class PartialSignatureWithNonceTlv(partialSigWithNonce: PartialSignatureWithNonce) extends FundingCreatedTlv with FundingSignedTlv

object PartialSignatureWithNonceTlv {
  val codec: Codec[PartialSignatureWithNonceTlv] = tlvField(partialSignatureWithNonce)
}

sealed trait FundingCreatedTlv extends Tlv

object FundingCreatedTlv {
  val fundingCreatedTlvCodec: Codec[TlvStream[FundingCreatedTlv]] = tlvStream(discriminated[FundingCreatedTlv].by(varint)
    .typecase(UInt64(2), PartialSignatureWithNonceTlv.codec)
  )
}

sealed trait FundingSignedTlv extends Tlv

object FundingSignedTlv {
  val fundingSignedTlvCodec: Codec[TlvStream[FundingSignedTlv]] = tlvStream(discriminated[FundingSignedTlv].by(varint)
    .typecase(UInt64(2), PartialSignatureWithNonceTlv.codec)
  )
}

sealed trait ChannelReadyTlv extends Tlv

object ChannelReadyTlv {

  case class ShortChannelIdTlv(alias: Alias) extends ChannelReadyTlv

  private val channelAliasTlvCodec: Codec[ShortChannelIdTlv] = tlvField("alias" | alias)

  val channelReadyTlvCodec: Codec[TlvStream[ChannelReadyTlv]] = tlvStream(discriminated[ChannelReadyTlv].by(varint)
    .typecase(UInt64(1), channelAliasTlvCodec)
    .typecase(UInt64(4), nexLocalNonceTlvCodec)
  )
}

sealed trait ChannelReestablishTlv extends Tlv

object ChannelReestablishTlv {

  case class NextFundingTlv(txId: TxId) extends ChannelReestablishTlv

  object NextFundingTlv {
    val codec: Codec[NextFundingTlv] = tlvField(txIdAsHash)
  }

  val channelReestablishTlvCodec: Codec[TlvStream[ChannelReestablishTlv]] = tlvStream(discriminated[ChannelReestablishTlv].by(varint)
    .typecase(UInt64(0), NextFundingTlv.codec)
    .typecase(UInt64(4), nexLocalNoncesTlvCodec)
  )
}

sealed trait UpdateFeeTlv extends Tlv

object UpdateFeeTlv {
  val updateFeeTlvCodec: Codec[TlvStream[UpdateFeeTlv]] = tlvStream(discriminated[UpdateFeeTlv].by(varint))
}

sealed trait ShutdownTlv extends Tlv

object ShutdownTlv {
  case class ShutdownNonce(nonce: IndividualNonce) extends ShutdownTlv

  private val shutdownNonceCodec: Codec[ShutdownNonce] = tlvField(publicNonce)

  val shutdownTlvCodec: Codec[TlvStream[ShutdownTlv]] = tlvStream(discriminated[ShutdownTlv].by(varint)
    .typecase(UInt64(8), shutdownNonceCodec)
  )
}

sealed trait ClosingSignedTlv extends Tlv

object ClosingSignedTlv {

  case class FeeRange(min: Satoshi, max: Satoshi) extends ClosingSignedTlv

  private val feeRange: Codec[FeeRange] = tlvField(("min_fee_satoshis" | satoshi) :: ("max_fee_satoshis" | satoshi))

  case class PartialSignature(partialSignature: ByteVector32) extends ClosingSignedTlv

  private val partialSignature: Codec[PartialSignature] = tlvField(bytes32)

  val closingSignedTlvCodec: Codec[TlvStream[ClosingSignedTlv]] = tlvStream(discriminated[ClosingSignedTlv].by(varint)
    .typecase(UInt64(1), feeRange)
    .typecase(UInt64(6), partialSignature)
  )

}

sealed trait ClosingTlv extends Tlv

object ClosingTlv {
  /** Signature for a closing transaction containing only the closer's output. */
  case class CloserNoClosee(sig: ByteVector64) extends ClosingTlv

  /** Signature for a closing transaction containing only the closee's output. */
  case class NoCloserClosee(sig: ByteVector64) extends ClosingTlv

  /** Signature for a closing transaction containing the closer and closee's outputs. */
  case class CloserAndClosee(sig: ByteVector64) extends ClosingTlv

  /** Signature for a closing transaction containing only the closer's output. */
  case class CloserNoCloseePartialSignature(partialSigWithNonce: PartialSignatureWithNonce) extends ClosingTlv

  /** Signature for a closing transaction containing only the closee's output. */
  case class NoCloserCloseePartialSignature(partialSigWithNonce: PartialSignatureWithNonce) extends ClosingTlv

  /** Signature for a closing transaction containing the closer and closee's outputs. */
  case class CloserAndCloseePartialSignature(partialSigWithNonce: PartialSignatureWithNonce) extends ClosingTlv

  val closingTlvCodec: Codec[TlvStream[ClosingTlv]] = tlvStream(discriminated[ClosingTlv].by(varint)
    .typecase(UInt64(1), tlvField(bytes64.as[CloserNoClosee]))
    .typecase(UInt64(2), tlvField(bytes64.as[NoCloserClosee]))
    .typecase(UInt64(3), tlvField(bytes64.as[CloserAndClosee]))
    .typecase(UInt64(4), tlvField(partialSignatureWithNonce.as[CloserNoCloseePartialSignature]))
    .typecase(UInt64(5), tlvField(partialSignatureWithNonce.as[NoCloserCloseePartialSignature]))
    .typecase(UInt64(6), tlvField(partialSignatureWithNonce.as[CloserAndCloseePartialSignature]))
  )

}
