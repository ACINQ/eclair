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

import fr.acinq.eclair.wire.protocol.CommonCodecs.varint
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvStream
import scodec.Codec
import scodec.codecs.discriminated

/**
 * Created by t-bast on 19/07/2021.
 */

sealed trait UpdateAddHtlcTlv extends Tlv

object UpdateAddHtlcTlv {
  val addHtlcTlvCodec: Codec[TlvStream[UpdateAddHtlcTlv]] = tlvStream(discriminated[UpdateAddHtlcTlv].by(varint))
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
  val commitSigTlvCodec: Codec[TlvStream[CommitSigTlv]] = tlvStream(discriminated[CommitSigTlv].by(varint))
}

sealed trait RevokeAndAckTlv extends Tlv

object RevokeAndAckTlv {
  val revokeAndAckTlvCodec: Codec[TlvStream[RevokeAndAckTlv]] = tlvStream(discriminated[RevokeAndAckTlv].by(varint))
}
