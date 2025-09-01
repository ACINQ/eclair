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

import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.scalacompat.{ByteVector64, Satoshi, TxId}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.channel.ChannelSpendSignature.PartialSignatureWithNonce
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream}
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

/**
 * Created by t-bast on 08/04/2022.
 */

sealed trait TxAddInputTlv extends Tlv

object TxAddInputTlv {
  /** When doing a splice, the initiator must provide the previous funding txId instead of the whole transaction. */
  case class SharedInputTxId(txId: TxId) extends TxAddInputTlv

  /**
   * When creating an interactive-tx where both participants sign a taproot input, we don't need to provide the entire
   * previous transaction in [[TxAddInput]]: signatures will commit to the txOut of *all* of the transaction's inputs,
   * which ensures that nodes cannot cheat and downgrade to a non-segwit input.
   */
  case class PrevTxOut(txId: TxId, amount: Satoshi, publicKeyScript: ByteVector) extends TxAddInputTlv

  object PrevTxOut {
    val codec: Codec[PrevTxOut] = tlvField((txIdAsHash :: satoshi :: bytes).as[PrevTxOut])
  }

  val txAddInputTlvCodec: Codec[TlvStream[TxAddInputTlv]] = tlvStream(discriminated[TxAddInputTlv].by(varint)
    // Note that we actually encode as a tx_hash to be consistent with other lightning messages.
    .typecase(UInt64(1105), tlvField(txIdAsHash.as[SharedInputTxId]))
    .typecase(UInt64(1111), PrevTxOut.codec)
  )
}

sealed trait TxAddOutputTlv extends Tlv

object TxAddOutputTlv {
  val txAddOutputTlvCodec: Codec[TlvStream[TxAddOutputTlv]] = tlvStream(discriminated[TxAddOutputTlv].by(varint))
}

sealed trait TxRemoveInputTlv extends Tlv

object TxRemoveInputTlv {
  val txRemoveInputTlvCodec: Codec[TlvStream[TxRemoveInputTlv]] = tlvStream(discriminated[TxRemoveInputTlv].by(varint))
}

sealed trait TxRemoveOutputTlv extends Tlv

object TxRemoveOutputTlv {
  val txRemoveOutputTlvCodec: Codec[TlvStream[TxRemoveOutputTlv]] = tlvStream(discriminated[TxRemoveOutputTlv].by(varint))
}

sealed trait TxCompleteTlv extends Tlv

object TxCompleteTlv {
  /**
   * Musig2 nonces for the commitment transaction(s), exchanged during an interactive tx session, when using a taproot
   * channel or upgrading a channel to use taproot.
   *
   * @param commitNonce     the sender's verification nonce for the current commit tx spending the interactive tx.
   * @param nextCommitNonce the sender's verification nonce for the next commit tx spending the interactive tx.
   */
  case class CommitNonces(commitNonce: IndividualNonce, nextCommitNonce: IndividualNonce) extends TxCompleteTlv

  /** When splicing a taproot channel, the sender's random signing nonce for the previous funding output. */
  case class FundingInputNonce(nonce: IndividualNonce) extends TxCompleteTlv

  val txCompleteTlvCodec: Codec[TlvStream[TxCompleteTlv]] = tlvStream(discriminated[TxCompleteTlv].by(varint)
    .typecase(UInt64(4), tlvField[CommitNonces, CommitNonces]((publicNonce :: publicNonce).as[CommitNonces]))
    .typecase(UInt64(6), tlvField[FundingInputNonce, FundingInputNonce](publicNonce.as[FundingInputNonce]))
  )
}

sealed trait TxSignaturesTlv extends Tlv

object TxSignaturesTlv {
  /** When doing a splice, each peer must provide their signature for the previous 2-of-2 funding output. */
  case class PreviousFundingTxSig(sig: ByteVector64) extends TxSignaturesTlv

  /** When doing a splice for a taproot channel, each peer must provide their partial signature for the previous musig2 funding output. */
  case class PreviousFundingTxPartialSig(partialSigWithNonce: PartialSignatureWithNonce) extends TxSignaturesTlv

  val txSignaturesTlvCodec: Codec[TlvStream[TxSignaturesTlv]] = tlvStream(discriminated[TxSignaturesTlv].by(varint)
    .typecase(UInt64(2), tlvField(partialSignatureWithNonce.as[PreviousFundingTxPartialSig]))
    .typecase(UInt64(601), tlvField(bytes64.as[PreviousFundingTxSig]))
  )
}

sealed trait TxAbortTlv extends Tlv

object TxAbortTlv {
  val txAbortTlvCodec: Codec[TlvStream[TxAbortTlv]] = tlvStream(discriminated[TxAbortTlv].by(varint))
}