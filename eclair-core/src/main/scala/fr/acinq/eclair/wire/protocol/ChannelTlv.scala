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
import fr.acinq.eclair.channel.ChannelSpendSignature.PartialSignatureWithNonce
import fr.acinq.eclair.channel.{ChannelType, ChannelTypes}
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
  case class ChannelTypeTlv(channelType: ChannelType) extends OpenChannelTlv with AcceptChannelTlv with OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv with SpliceInitTlv with SpliceAckTlv

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

  /** Verification nonce used for the next commitment transaction that will be signed (when using taproot channels). */
  case class NextLocalNonceTlv(nonce: IndividualNonce) extends OpenChannelTlv with AcceptChannelTlv with ChannelReadyTlv with ClosingTlv

  val nextLocalNonceCodec: Codec[NextLocalNonceTlv] = tlvField(publicNonce)

  /** Partial signature along with the signer's nonce, which is usually randomly created at signing time (when using taproot channels). */
  case class PartialSignatureWithNonceTlv(partialSigWithNonce: PartialSignatureWithNonce) extends FundingCreatedTlv with FundingSignedTlv with ClosingTlv

  val partialSignatureWithNonceCodec: Codec[PartialSignatureWithNonceTlv] = tlvField(partialSignatureWithNonce)

}

object OpenChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(discriminated[OpenChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(4), nextLocalNonceCodec)
  )

}

object AcceptChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptChannelTlv]] = tlvStream(discriminated[AcceptChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(4), nextLocalNonceCodec)
  )
}

object OpenDualFundedChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenDualFundedChannelTlv]] = tlvStream(discriminated[OpenDualFundedChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
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
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), requestFundingCodec)
    .typecase(UInt64(0x47000007), tlvField(tmillisatoshi.as[PushAmountTlv]))
    .typecase(UInt64(0x47000011), tlvField(channelTypeCodec.as[ChannelTypeTlv]))
  )
}

object SpliceAckTlv {

  import ChannelTlv._

  val spliceAckTlvCodec: Codec[TlvStream[SpliceAckTlv]] = tlvStream(discriminated[SpliceAckTlv].by(varint)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), provideFundingCodec)
    .typecase(UInt64(41042), feeCreditUsedCodec)
    .typecase(UInt64(0x47000007), tlvField(tmillisatoshi.as[PushAmountTlv]))
    .typecase(UInt64(0x47000011), tlvField(channelTypeCodec.as[ChannelTypeTlv]))
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
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), provideFundingCodec)
    .typecase(UInt64(41042), feeCreditUsedCodec)
    .typecase(UInt64(0x47000007), pushAmountCodec)
  )

}

sealed trait FundingCreatedTlv extends Tlv

object FundingCreatedTlv {
  val fundingCreatedTlvCodec: Codec[TlvStream[FundingCreatedTlv]] = tlvStream(discriminated[FundingCreatedTlv].by(varint)
    .typecase(UInt64(2), ChannelTlv.partialSignatureWithNonceCodec)
  )
}

sealed trait FundingSignedTlv extends Tlv

object FundingSignedTlv {
  val fundingSignedTlvCodec: Codec[TlvStream[FundingSignedTlv]] = tlvStream(discriminated[FundingSignedTlv].by(varint)
    .typecase(UInt64(2), ChannelTlv.partialSignatureWithNonceCodec)
  )
}

sealed trait ChannelReadyTlv extends Tlv

object ChannelReadyTlv {

  case class ShortChannelIdTlv(alias: Alias) extends ChannelReadyTlv

  private val channelAliasTlvCodec: Codec[ShortChannelIdTlv] = tlvField("alias" | alias)

  val channelReadyTlvCodec: Codec[TlvStream[ChannelReadyTlv]] = tlvStream(discriminated[ChannelReadyTlv].by(varint)
    .typecase(UInt64(1), channelAliasTlvCodec)
    .typecase(UInt64(4), ChannelTlv.nextLocalNonceCodec)
  )
}

sealed trait ChannelReestablishTlv extends Tlv

object ChannelReestablishTlv {

  case class NextFundingTlv(txId: TxId) extends ChannelReestablishTlv
  case class YourLastFundingLockedTlv(txId: TxId) extends ChannelReestablishTlv
  case class MyCurrentFundingLockedTlv(txId: TxId) extends ChannelReestablishTlv

  /**
   * When disconnected during an interactive tx session, we'll include a verification nonce for our *current* commitment
   * which our peer will need to re-send a commit sig for our current commitment transaction spending the interactive tx.
   */
  case class CurrentCommitNonceTlv(nonce: IndividualNonce) extends ChannelReestablishTlv

  /**
   * Verification nonces used for the next commitment transaction, when using taproot channels.
   * There must be a nonce for each active commitment (when there are pending splices or RBF attempts), indexed by the
   * corresponding fundingTxId.
   */
  case class NextLocalNoncesTlv(nonces: Seq[(TxId, IndividualNonce)]) extends ChannelReestablishTlv

  object NextFundingTlv {
    val codec: Codec[NextFundingTlv] = tlvField(txIdAsHash)
  }

  object YourLastFundingLockedTlv {
    val codec: Codec[YourLastFundingLockedTlv] = tlvField("your_last_funding_locked_txid" | txIdAsHash)
  }

  object MyCurrentFundingLockedTlv {
    val codec: Codec[MyCurrentFundingLockedTlv] = tlvField("my_current_funding_locked_txid" | txIdAsHash)
  }

  object CurrentCommitNonceTlv {
    val codec: Codec[CurrentCommitNonceTlv] = tlvField("current_commit_nonce" | publicNonce)
  }

  object NextLocalNoncesTlv {
    val codec: Codec[NextLocalNoncesTlv] = tlvField(list(txIdAsHash ~ publicNonce).xmap[Seq[(TxId, IndividualNonce)]](_.toSeq, _.toList))
  }

  val channelReestablishTlvCodec: Codec[TlvStream[ChannelReestablishTlv]] = tlvStream(discriminated[ChannelReestablishTlv].by(varint)
    .typecase(UInt64(0), NextFundingTlv.codec)
    .typecase(UInt64(1), YourLastFundingLockedTlv.codec)
    .typecase(UInt64(3), MyCurrentFundingLockedTlv.codec)
    .typecase(UInt64(22), NextLocalNoncesTlv.codec)
    .typecase(UInt64(24), CurrentCommitNonceTlv.codec)
  )
}

sealed trait UpdateFeeTlv extends Tlv

object UpdateFeeTlv {
  val updateFeeTlvCodec: Codec[TlvStream[UpdateFeeTlv]] = tlvStream(discriminated[UpdateFeeTlv].by(varint))
}

sealed trait ShutdownTlv extends Tlv

object ShutdownTlv {
  /** When closing taproot channels, local nonce that will be used to sign the remote closing transaction. */
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

  val closingSignedTlvCodec: Codec[TlvStream[ClosingSignedTlv]] = tlvStream(discriminated[ClosingSignedTlv].by(varint)
    .typecase(UInt64(1), feeRange)
  )

}

sealed trait ClosingTlv extends Tlv

object ClosingTlv {
  /** Signature for a closing transaction containing only the closer's output. */
  case class CloserOutputOnly(sig: ByteVector64) extends ClosingTlv with ClosingCompleteTlv with ClosingSigTlv

  /** Signature for a closing transaction containing only the closee's output. */
  case class CloseeOutputOnly(sig: ByteVector64) extends ClosingTlv with ClosingCompleteTlv with ClosingSigTlv

  /** Signature for a closing transaction containing the closer and closee's outputs. */
  case class CloserAndCloseeOutputs(sig: ByteVector64) extends ClosingTlv with ClosingCompleteTlv with ClosingSigTlv
}

sealed trait ClosingCompleteTlv extends ClosingTlv

object ClosingCompleteTlv {
  /** When closing taproot channels, partial signature for a closing transaction containing only the closer's output. */
  case class CloserOutputOnlyPartialSignature(partialSignature: PartialSignatureWithNonce) extends ClosingCompleteTlv

  /** When closing taproot channels, partial signature for a closing transaction containing only the closee's output. */
  case class CloseeOutputOnlyPartialSignature(partialSignature: PartialSignatureWithNonce) extends ClosingCompleteTlv

  /** When closing taproot channels, partial signature for a closing transaction containing the closer and closee's outputs. */
  case class CloserAndCloseeOutputsPartialSignature(partialSignature: PartialSignatureWithNonce) extends ClosingCompleteTlv

  val closingCompleteTlvCodec: Codec[TlvStream[ClosingCompleteTlv]] = tlvStream(discriminated[ClosingCompleteTlv].by(varint)
    .typecase(UInt64(1), tlvField(bytes64.as[ClosingTlv.CloserOutputOnly]))
    .typecase(UInt64(2), tlvField(bytes64.as[ClosingTlv.CloseeOutputOnly]))
    .typecase(UInt64(3), tlvField(bytes64.as[ClosingTlv.CloserAndCloseeOutputs]))
    .typecase(UInt64(5), tlvField(partialSignatureWithNonce.as[CloserOutputOnlyPartialSignature]))
    .typecase(UInt64(6), tlvField(partialSignatureWithNonce.as[CloseeOutputOnlyPartialSignature]))
    .typecase(UInt64(7), tlvField(partialSignatureWithNonce.as[CloserAndCloseeOutputsPartialSignature]))
  )
}

sealed trait ClosingSigTlv extends ClosingTlv

object ClosingSigTlv {
  /** When closing taproot channels, partial signature for a closing transaction containing only the closer's output. */
  case class CloserOutputOnlyPartialSignature(partialSignature: ByteVector32) extends ClosingSigTlv

  /** When closing taproot channels, partial signature for a closing transaction containing only the closee's output. */
  case class CloseeOutputOnlyPartialSignature(partialSignature: ByteVector32) extends ClosingSigTlv

  /** When closing taproot channels, partial signature for a closing transaction containing the closer and closee's outputs. */
  case class CloserAndCloseeOutputsPartialSignature(partialSignature: ByteVector32) extends ClosingSigTlv

  /** When closing taproot channels, local nonce that will be used to sign the next remote closing transaction. */
  case class NextCloseeNonce(nonce: IndividualNonce) extends ClosingSigTlv

  val closingSigTlvCodec: Codec[TlvStream[ClosingSigTlv]] = tlvStream(discriminated[ClosingSigTlv].by(varint)
    .typecase(UInt64(1), tlvField(bytes64.as[ClosingTlv.CloserOutputOnly]))
    .typecase(UInt64(2), tlvField(bytes64.as[ClosingTlv.CloseeOutputOnly]))
    .typecase(UInt64(3), tlvField(bytes64.as[ClosingTlv.CloserAndCloseeOutputs]))
    .typecase(UInt64(5), tlvField(bytes32.as[CloserOutputOnlyPartialSignature]))
    .typecase(UInt64(6), tlvField(bytes32.as[CloseeOutputOnlyPartialSignature]))
    .typecase(UInt64(7), tlvField(bytes32.as[CloserAndCloseeOutputsPartialSignature]))
    .typecase(UInt64(22), tlvField(publicNonce.as[NextCloseeNonce]))
  )
}

