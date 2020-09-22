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

package fr.acinq.eclair.wire

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.eclair.wire.ChannelTlv.UpfrontShutdownScript
import fr.acinq.eclair.wire.CommonCodecs.{varint, varintoverflow}
import fr.acinq.eclair.wire.OpenChannelTlv.ChannelVersionTlv
import fr.acinq.eclair.wire.TlvCodecs.tlvStream
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._
import scodec.codecs.{bits, bytes, discriminated, variableSizeBytesLong}
import scodec.{Attempt, Codec, DecodeResult}

class OpenTlvSpec extends AnyFunSuite {

  test("channel version tlv") {
    case class TestCase(expected: ChannelVersion, encoded: BitVector, reEncoded: BitVector)
    val testCases = Seq(
      TestCase(ChannelVersion.STANDARD, hex"fe47000000 00000001".bits, hex"fe47000000 00000001".bits),
      TestCase(ChannelVersion.STANDARD, hex"fe47000001 04 00000001".bits, hex"fe47000000 00000001".bits),
      TestCase(ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE, hex"fe47000000 00000009".bits, hex"fe47000000 00000009".bits),
      TestCase(ChannelVersion.STANDARD | ChannelVersion.ZERO_RESERVE, hex"fe47000001 04 00000009".bits, hex"fe47000000 00000009".bits)
    )

    for (testCase <- testCases) {
      assert(OpenChannelTlv.openTlvCodec.decode(testCase.encoded) === Attempt.successful(DecodeResult(TlvStream(ChannelVersionTlv(testCase.expected)), BitVector.empty)))
      assert(OpenChannelTlv.openTlvCodec.encode(TlvStream(ChannelVersionTlv(testCase.expected))) === Attempt.Successful(testCase.reEncoded))
    }
  }

  test("channel version tlv backwards-compatibility") {
    // This is the codec that was used previously.
    val previousCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(discriminated[OpenChannelTlv].by(varint)
      .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript])
      .typecase(UInt64(0x47000000), bits(ChannelVersion.LENGTH_BITS).as[ChannelVersion].as[ChannelVersionTlv])
    )

    assert(previousCodec.decode(hex"fe47000001 04 00000001".bits).require.value === TlvStream(Nil, Seq(GenericTlv(UInt64(0x47000001), hex"00000001"))))
    assert(previousCodec.decode(hex"fe47000001 04 00000009".bits).require.value === TlvStream(Nil, Seq(GenericTlv(UInt64(0x47000001), hex"00000009"))))
  }

}