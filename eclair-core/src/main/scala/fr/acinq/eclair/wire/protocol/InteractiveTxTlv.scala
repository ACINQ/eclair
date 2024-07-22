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

import fr.acinq.bitcoin.scalacompat.{ByteVector64, TxId}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs.{bytes64, txIdAsHash, varint}
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream}
import scodec.Codec
import scodec.codecs.discriminated

/**
 * Created by t-bast on 08/04/2022.
 */

sealed trait TxAddInputTlv extends Tlv

object TxAddInputTlv {
  /** When doing a splice, the initiator must provide the previous funding txId instead of the whole transaction. */
  case class SharedInputTxId(txId: TxId) extends TxAddInputTlv

  /** Same as [[SharedInputTxId]] for peers who only support the experimental version of splicing. */
  case class ExperimentalSharedInputTxId(txId: TxId) extends TxAddInputTlv

  val txAddInputTlvCodec: Codec[TlvStream[TxAddInputTlv]] = tlvStream(discriminated[TxAddInputTlv].by(varint)
    // Note that we actually encode as a tx_hash to be consistent with other lightning messages.
    .typecase(UInt64(0), tlvField(txIdAsHash.as[SharedInputTxId]))
    .typecase(UInt64(1105), tlvField(txIdAsHash.as[ExperimentalSharedInputTxId]))
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
  val txCompleteTlvCodec: Codec[TlvStream[TxCompleteTlv]] = tlvStream(discriminated[TxCompleteTlv].by(varint))
}

sealed trait TxSignaturesTlv extends Tlv

object TxSignaturesTlv {
  /** When doing a splice, each peer must provide their signature for the previous 2-of-2 funding output. */
  case class PreviousFundingTxSig(sig: ByteVector64) extends TxSignaturesTlv

  /** Same as [[PreviousFundingTxSig]] for peers who only support the experimental version of splicing. */
  case class ExperimentalPreviousFundingTxSig(sig: ByteVector64) extends TxSignaturesTlv

  val txSignaturesTlvCodec: Codec[TlvStream[TxSignaturesTlv]] = tlvStream(discriminated[TxSignaturesTlv].by(varint)
    .typecase(UInt64(0), tlvField(bytes64.as[PreviousFundingTxSig]))
    .typecase(UInt64(601), tlvField(bytes64.as[ExperimentalPreviousFundingTxSig]))
  )
}

sealed trait TxAbortTlv extends Tlv

object TxAbortTlv {
  val txAbortTlvCodec: Codec[TlvStream[TxAbortTlv]] = tlvStream(discriminated[TxAbortTlv].by(varint))
}