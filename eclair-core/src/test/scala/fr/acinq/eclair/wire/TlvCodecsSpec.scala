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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.{ShortChannelId, UInt64}
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.wire.CommonCodecs.{publicKey, shortchannelid, uint64, varint}
import fr.acinq.eclair.wire.TlvCodecs._
import org.scalatest.FunSuite
import scodec.bits.HexStringSyntax
import scodec.codecs._
import scodec.Codec

/**
  * Created by t-bast on 20/06/2019.
  */

class TlvCodecsSpec extends FunSuite {

  import TlvCodecsSpec._

  test("encode/decode tlv") {
    val testCases = Seq(
      (hex"01 08 000000000000002a", TestType1(42)),
      (hex"02 08 0000000000000226", TestType2(ShortChannelId(550))),
      (hex"03 31 02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 0000000000000231 0000000000000451", TestType3(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 561, 1105)),
      (hex"ff1234567890123456 fd0001 10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101", GenericTlv(6211610197754262546L, hex"10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101"))
    )

    for ((bin, expected) <- testCases) {
      val decoded = testTlvCodec.decode(bin.toBitVector).require.value.asInstanceOf[Tlv]
      assert(decoded === expected)
      val encoded = testTlvCodec.encode(expected).require.toByteVector
      assert(encoded === bin)
    }
  }

  test("decode invalid tlv") {
    val testCases = Seq(
      hex"fd02", // type truncated
      hex"fd022a", // truncated after type
      hex"fd0100", // not minimally encoded type
      hex"2a fd02", // length truncated
      hex"2a fd0226", // truncated after length
      hex"2a fe01010000", // not minimally encoded length
      hex"2a fd2602 0231", // value truncated
      hex"02 01 2a", // short channel id too short
      hex"02 09 010101010101010101", // short channel id length too big
      hex"2a ff0000000000000080" // invalid length (too big to fit inside a long)
    )

    for (testCase <- testCases) {
      assert(testTlvCodec.decode(testCase.toBitVector).isFailure)
    }
  }

  test("decode invalid tlv stream") {
    val testCases = Seq(
      hex"0108000000000000002a 01", // valid tlv record followed by invalid tlv record (only type, length and value are missing)
      hex"02080000000000000226 0108000000000000002a", // valid tlv records but invalid ordering
      hex"02080000000000000231 02080000000000000451", // duplicate tlv type
      hex"0108000000000000002a 2a0101", // unknown even type
      hex"0a080000000000000231 0b0400000451" // valid tlv records but from different namespace
    )

    for (testCase <- testCases) {
      assert(tlvStream(testTlvCodec).decode(testCase.toBitVector).isFailure, testCase)
    }
  }

  test("encode invalid tlv stream") {
    val testCases = Seq(
      TlvStream(Seq(TestType1(561), TestType2(ShortChannelId(1105)), OtherType1(42))),
      TlvStream(Seq(TestType1(561), TestType1(1105)))
    )

    for (testCase <- testCases) {
      assert(tlvStream(testTlvCodec).encode(testCase).isFailure, testCase)
    }
  }

  test("encode/decode tlv stream") {
    val bin = hex"01080000000000000231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451"
    val expected = Seq(
      TestType1(561),
      TestType2(ShortChannelId(1105)),
      TestType3(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 561, 1105)
    )

    val decoded = tlvStream(testTlvCodec).decode(bin.toBitVector).require.value
    assert(decoded === TlvStream(expected))

    val encoded = tlvStream(testTlvCodec).encode(TlvStream(expected.reverse)).require.toByteVector
    assert(encoded === bin)
  }

  test("encode/decode tlv stream with unknown odd type") {
    val bin = hex"01080000000000000231 0b0400000451 0d02002a"
    val expected = Seq(
      TestType1(561),
      GenericTlv(11, hex"00000451"),
      TestType13(42)
    )

    val decoded = tlvStream(testTlvCodec).decode(bin.toBitVector).require.value
    assert(decoded === TlvStream(expected))

    val encoded = tlvStream(testTlvCodec).encode(TlvStream(expected.reverse)).require.toByteVector
    assert(encoded === bin)
  }

}

object TlvCodecsSpec {

  // @formatter:off
  sealed trait TestTlv extends Tlv
  case class TestType1(longValue: Long) extends TestTlv { override val `type` = UInt64(1) }
  case class TestType2(shortChannelId: ShortChannelId) extends TestTlv { override val `type` = UInt64(2) }
  case class TestType3(nodeId: PublicKey, value1: Long, value2: Long) extends TestTlv { override val `type` = UInt64(3) }
  case class TestType13(intValue: Int) extends TestTlv { override val `type` = UInt64(13) }

  val testCodec1: Codec[TestType1] = (("length" | constant(hex"08")) :: ("value" | uint64)).as[TestType1]
  val testCodec2: Codec[TestType2] = (("length" | constant(hex"08")) :: ("short_channel_id" | shortchannelid)).as[TestType2]
  val testCodec3: Codec[TestType3] = (("length" | constant(hex"31")) :: ("node_id" | publicKey) :: ("value_1" | uint64) :: ("value_2" | uint64)).as[TestType3]
  val testCodec13: Codec[TestType13] = (("length" | constant(hex"02")) :: ("value" | uint16)).as[TestType13]
  val testTlvCodec = tlvFallback(discriminated[Tlv].by(varint)
    .typecase(1, testCodec1)
    .typecase(2, testCodec2)
    .typecase(3, testCodec3)
    .typecase(13, testCodec13)
  )

  sealed trait OtherTlv extends Tlv
  case class OtherType1(longValue: Long) extends OtherTlv { override val `type` = UInt64(10) }
  case class OtherType2(lessLongValue: Long) extends OtherTlv { override val `type` = UInt64(11) }

  val otherCodec1: Codec[OtherType1] = (("length" | constant(hex"08")) :: ("value" | uint64)).as[OtherType1]
  val otherCodec2: Codec[OtherType2] = (("length" | constant(hex"04")) :: ("value" | uint32)).as[OtherType2]
  val otherTlvCodec = tlvFallback(discriminated[Tlv].by(varint)
    .typecase(10, otherCodec1)
    .typecase(11, otherCodec2)
  )
  // @formatter:on

}
