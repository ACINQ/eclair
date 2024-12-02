/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.TxId
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream, tu16}
import scodec.{Attempt, Codec, Err}
import scodec.bits.HexStringSyntax
import scodec.codecs._

/**
 * Created by t-bast on 19/07/2021.
 */

sealed trait UpdateAddHtlcTlv extends Tlv

object UpdateAddHtlcTlv {
  /** Path key that should be used to derive shared secrets when using route blinding. */
  case class PathKey(publicKey: PublicKey) extends UpdateAddHtlcTlv

  private val pathKey: Codec[PathKey] = (("length" | constant(hex"21")) :: ("pathKey" | publicKey)).as[PathKey]

  case class Endorsement(level: Int) extends UpdateAddHtlcTlv

  private val endorsement: Codec[Endorsement] = tlvField(uint8.narrow[Endorsement](n => if (n >= 8) Attempt.failure(Err(s"invalid endorsement level: $n")) else Attempt.successful(Endorsement(n)), _.level))

  /** When on-the-fly funding is used, the liquidity fees may be taken from HTLCs relayed after funding. */
  case class FundingFeeTlv(fee: LiquidityAds.FundingFee) extends UpdateAddHtlcTlv

  private val fundingFee: Codec[FundingFeeTlv] = tlvField((("amount" | millisatoshi) :: ("txId" | txIdAsHash)).as[LiquidityAds.FundingFee])

  val addHtlcTlvCodec: Codec[TlvStream[UpdateAddHtlcTlv]] = tlvStream(discriminated[UpdateAddHtlcTlv].by(varint)
    .typecase(UInt64(0), pathKey)
    .typecase(UInt64(41041), fundingFee)
    .typecase(UInt64(106823), endorsement)
  )
}

sealed trait UpdateFulfillHtlcTlv extends Tlv

object UpdateFulfillHtlcTlv {
  val updateFulfillHtlcTlvCodec: Codec[TlvStream[UpdateFulfillHtlcTlv]] = tlvStream(discriminated[UpdateFulfillHtlcTlv].by(varint))
}

sealed trait UpdateFailHtlcTlv extends Tlv

object UpdateFailHtlcTlv {
  val updateFailHtlcTlvCodec: Codec[TlvStream[UpdateFailHtlcTlv]] = tlvStream(discriminated[UpdateFailHtlcTlv].by(varint))
}

sealed trait UpdateFailMalformedHtlcTlv extends Tlv

object UpdateFailMalformedHtlcTlv {
  val updateFailMalformedHtlcTlvCodec: Codec[TlvStream[UpdateFailMalformedHtlcTlv]] = tlvStream(discriminated[UpdateFailMalformedHtlcTlv].by(varint))
}

sealed trait CommitSigTlv extends Tlv

object CommitSigTlv {
  /**
   * While a splice is ongoing and not locked, we have multiple valid commitments.
   * We send one [[CommitSig]] message for each valid commitment.
   *
   * @param size        the number of [[CommitSig]] messages in the batch.
   * @param fundingTxId the funding transaction spent by this commitment.
   */
  case class BatchTlv(size: Int, fundingTxId: TxId) extends CommitSigTlv

  private val batchTlv: Codec[BatchTlv] = tlvField(uint16 :: txIdAsHash)

  /** Similar to [[BatchTlv]] for peers who only support the experimental version of splicing. */
  case class ExperimentalBatchTlv(size: Int) extends CommitSigTlv

  private val experimentalBatchTlv: Codec[ExperimentalBatchTlv] = tlvField(tu16)

  val commitSigTlvCodec: Codec[TlvStream[CommitSigTlv]] = tlvStream(discriminated[CommitSigTlv].by(varint)
    .typecase(UInt64(0), batchTlv)
    .typecase(UInt64(0x47010005), experimentalBatchTlv)
  )

}

sealed trait RevokeAndAckTlv extends Tlv

object RevokeAndAckTlv {
  val revokeAndAckTlvCodec: Codec[TlvStream[RevokeAndAckTlv]] = tlvStream(discriminated[RevokeAndAckTlv].by(varint))
}
