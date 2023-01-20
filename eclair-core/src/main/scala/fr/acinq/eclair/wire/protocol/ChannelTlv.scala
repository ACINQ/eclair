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

import fr.acinq.bitcoin.scalacompat.Satoshi
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

  case class RequireConfirmedInputsTlv() extends OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv

  val requireConfirmedInputsCodec: Codec[RequireConfirmedInputsTlv] = tlvField(provide(RequireConfirmedInputsTlv()))

  case class PushAmountTlv(amount: MilliSatoshi) extends OpenDualFundedChannelTlv with AcceptDualFundedChannelTlv

  val pushAmountCodec: Codec[PushAmountTlv] = tlvField(tmillisatoshi)

}

object OpenChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(discriminated[OpenChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
  )

}

object AcceptChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptChannelTlv]] = tlvStream(discriminated[AcceptChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
  )
}

object OpenDualFundedChannelTlv {

  import ChannelTlv._

  val openTlvCodec: Codec[TlvStream[OpenDualFundedChannelTlv]] = tlvStream(discriminated[OpenDualFundedChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    .typecase(UInt64(0x47000007), pushAmountCodec)
  )

}

object AcceptDualFundedChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptDualFundedChannelTlv]] = tlvStream(discriminated[AcceptDualFundedChannelTlv].by(varint)
    .typecase(UInt64(0), upfrontShutdownScriptCodec)
    .typecase(UInt64(1), channelTypeCodec)
    .typecase(UInt64(2), requireConfirmedInputsCodec)
    .typecase(UInt64(0x47000007), pushAmountCodec)
  )

}

sealed trait FundingCreatedTlv extends Tlv

object FundingCreatedTlv {
  val fundingCreatedTlvCodec: Codec[TlvStream[FundingCreatedTlv]] = tlvStream(discriminated[FundingCreatedTlv].by(varint))
}

sealed trait FundingSignedTlv extends Tlv

object FundingSignedTlv {
  val fundingSignedTlvCodec: Codec[TlvStream[FundingSignedTlv]] = tlvStream(discriminated[FundingSignedTlv].by(varint))
}

sealed trait ChannelReadyTlv extends Tlv

object ChannelReadyTlv {

  case class ShortChannelIdTlv(alias: Alias) extends ChannelReadyTlv

  private val channelAliasTlvCodec: Codec[ShortChannelIdTlv] = tlvField("alias" | alias)

  val channelReadyTlvCodec: Codec[TlvStream[ChannelReadyTlv]] = tlvStream(discriminated[ChannelReadyTlv].by(varint)
    .typecase(UInt64(1), channelAliasTlvCodec)
  )
}

sealed trait ChannelReestablishTlv extends Tlv

object ChannelReestablishTlv {
  val channelReestablishTlvCodec: Codec[TlvStream[ChannelReestablishTlv]] = tlvStream(discriminated[ChannelReestablishTlv].by(varint))
}

sealed trait UpdateFeeTlv extends Tlv

object UpdateFeeTlv {
  val updateFeeTlvCodec: Codec[TlvStream[UpdateFeeTlv]] = tlvStream(discriminated[UpdateFeeTlv].by(varint))
}

sealed trait ShutdownTlv extends Tlv

object ShutdownTlv {
  val shutdownTlvCodec: Codec[TlvStream[ShutdownTlv]] = tlvStream(discriminated[ShutdownTlv].by(varint))
}

sealed trait ClosingSignedTlv extends Tlv

object ClosingSignedTlv {

  case class FeeRange(min: Satoshi, max: Satoshi) extends ClosingSignedTlv

  private val feeRange: Codec[FeeRange] = tlvField(("min_fee_satoshis" | satoshi) :: ("max_fee_satoshis" | satoshi))

  val closingSignedTlvCodec: Codec[TlvStream[ClosingSignedTlv]] = tlvStream(discriminated[ClosingSignedTlv].by(varint)
    .typecase(UInt64(1), feeRange)
  )

}
