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

import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.TxId
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.channel.ChannelSpendSignature.PartialSignatureWithNonce
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream, tu16}
import scodec.bits.{ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

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
  case class AttributionData(data: ByteVector) extends UpdateFulfillHtlcTlv

  private val attributionData: Codec[AttributionData] = (("length" | constant(hex"fd0398")) :: ("data" | bytes(Sphinx.Attribution.totalLength))).as[AttributionData]

  val updateFulfillHtlcTlvCodec: Codec[TlvStream[UpdateFulfillHtlcTlv]] = tlvStream(discriminated[UpdateFulfillHtlcTlv].by(varint)
    .typecase(UInt64(1), attributionData)
  )
}

sealed trait UpdateFailHtlcTlv extends Tlv

object UpdateFailHtlcTlv {
  case class AttributionData(data: ByteVector) extends UpdateFailHtlcTlv

  private val attributionData: Codec[AttributionData] = (("length" | constant(hex"fd0398")) :: ("data" | bytes(Sphinx.Attribution.totalLength))).as[AttributionData]

  val updateFailHtlcTlvCodec: Codec[TlvStream[UpdateFailHtlcTlv]] = tlvStream(discriminated[UpdateFailHtlcTlv].by(varint)
    .typecase(UInt64(1), attributionData)
  )
}

sealed trait UpdateFailMalformedHtlcTlv extends Tlv

object UpdateFailMalformedHtlcTlv {
  val updateFailMalformedHtlcTlvCodec: Codec[TlvStream[UpdateFailMalformedHtlcTlv]] = tlvStream(discriminated[UpdateFailMalformedHtlcTlv].by(varint))
}

sealed trait CommitSigTlv extends Tlv

object CommitSigTlv {

  /** @param size the number of [[CommitSig]] messages in the batch */
  case class BatchTlv(size: Int) extends CommitSigTlv

  object BatchTlv {
    val codec: Codec[BatchTlv] = tlvField(tu16)
  }

  /** Partial signature signature for the current commitment transaction, along with the signing nonce used (when using taproot channels). */
  case class PartialSignatureWithNonceTlv(partialSigWithNonce: PartialSignatureWithNonce) extends CommitSigTlv

  object PartialSignatureWithNonceTlv {
    val codec: Codec[PartialSignatureWithNonceTlv] = tlvField(partialSignatureWithNonce)
  }

  val commitSigTlvCodec: Codec[TlvStream[CommitSigTlv]] = tlvStream(discriminated[CommitSigTlv].by(varint)
    .typecase(UInt64(2), PartialSignatureWithNonceTlv.codec)
    .typecase(UInt64(0x47010005), BatchTlv.codec)
  )

}

sealed trait RevokeAndAckTlv extends Tlv

object RevokeAndAckTlv {

  /**
   * Verification nonces used for the next commitment transaction, when using taproot channels.
   * There must be a nonce for each active commitment (when there are pending splices or RBF attempts), indexed by the
   * corresponding fundingTxId.
   */
  case class NextLocalNoncesTlv(nonces: Seq[(TxId, IndividualNonce)]) extends RevokeAndAckTlv

  object NextLocalNoncesTlv {
    val codec: Codec[NextLocalNoncesTlv] = tlvField(list(txIdAsHash ~ publicNonce).xmap[Seq[(TxId, IndividualNonce)]](_.toSeq, _.toList))
  }

  val revokeAndAckTlvCodec: Codec[TlvStream[RevokeAndAckTlv]] = tlvStream(discriminated[RevokeAndAckTlv].by(varint)
    .typecase(UInt64(22), NextLocalNoncesTlv.codec)
  )
}
