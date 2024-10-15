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
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.channel.PartialSignatureWithNonce
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
  /** Blinding ephemeral public key that should be used to derive shared secrets when using route blinding. */
  case class BlindingPoint(publicKey: PublicKey) extends UpdateAddHtlcTlv

  private val blindingPoint: Codec[BlindingPoint] = (("length" | constant(hex"21")) :: ("blinding" | publicKey)).as[BlindingPoint]

  case class Endorsement(level: Int) extends UpdateAddHtlcTlv

  private val endorsement: Codec[Endorsement] = tlvField(uint8.narrow[Endorsement](n => if (n >= 8) Attempt.failure(Err(s"invalid endorsement level: $n")) else Attempt.successful(Endorsement(n)), _.level))

  /** When on-the-fly funding is used, the liquidity fees may be taken from HTLCs relayed after funding. */
  case class FundingFeeTlv(fee: LiquidityAds.FundingFee) extends UpdateAddHtlcTlv

  private val fundingFee: Codec[FundingFeeTlv] = tlvField((("amount" | millisatoshi) :: ("txId" | txIdAsHash)).as[LiquidityAds.FundingFee])

  val addHtlcTlvCodec: Codec[TlvStream[UpdateAddHtlcTlv]] = tlvStream(discriminated[UpdateAddHtlcTlv].by(varint)
    .typecase(UInt64(0), blindingPoint)
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

  /** @param size the number of [[CommitSig]] messages in the batch */
  case class BatchTlv(size: Int) extends CommitSigTlv

  object BatchTlv {
    val codec: Codec[BatchTlv] = tlvField(tu16)
  }

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
  case class NextLocalNoncesTlv(nonces: List[IndividualNonce]) extends RevokeAndAckTlv

  object NextLocalNoncesTlv {
    val codec: Codec[NextLocalNoncesTlv] = tlvField(list(publicNonce))
  }

  val revokeAndAckTlvCodec: Codec[TlvStream[RevokeAndAckTlv]] = tlvStream(discriminated[RevokeAndAckTlv].by(varint)
    .typecase(UInt64(4), NextLocalNoncesTlv.codec)
  )
}
