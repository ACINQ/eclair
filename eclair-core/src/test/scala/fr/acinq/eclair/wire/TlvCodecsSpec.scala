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
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.wire.CommonCodecs.{publicKey, shortchannelid, uint64, varint}
import fr.acinq.eclair.wire.TlvCodecs._
import fr.acinq.eclair.{ShortChannelId, UInt64}
import org.scalatest.FunSuite
import scodec.Codec
import scodec.bits.HexStringSyntax
import scodec.codecs._

/**
  * Created by t-bast on 20/06/2019.
  */

class TlvCodecsSpec extends FunSuite {

  import TlvCodecsSpec._

  test("encode/decode truncated uint16") {
    val testCases = Seq(
      (hex"00", 0),
      (hex"01 01", 1),
      (hex"01 2a", 42),
      (hex"01 ff", 255),
      (hex"02 0100", 256),
      (hex"02 0231", 561),
      (hex"02 ffff", 65535)
    )

    for ((bin, expected) <- testCases) {
      val decoded = tu16.decode(bin.bits).require.value
      assert(decoded === expected)

      val encoded = tu16.encode(expected).require.bytes
      assert(encoded === bin)
    }
  }

  test("encode/decode truncated uint32") {
    val testCases = Seq(
      (hex"00", 0L),
      (hex"01 01", 1L),
      (hex"01 2a", 42L),
      (hex"01 ff", 255L),
      (hex"02 0100", 256L),
      (hex"02 0231", 561L),
      (hex"02 ffff", 65535L),
      (hex"03 010000", 65536L),
      (hex"03 ffffff", 16777215L),
      (hex"04 01000000", 16777216L),
      (hex"04 01020304", 16909060L),
      (hex"04 ffffffff", 4294967295L)
    )

    for ((bin, expected) <- testCases) {
      val decoded = tu32.decode(bin.bits).require.value
      assert(decoded === expected)

      val encoded = tu32.encode(expected).require.bytes
      assert(encoded === bin)
    }
  }

  test("encode/decode truncated uint64") {
    val testCases = Seq(
      (hex"00", UInt64(0)),
      (hex"01 01", UInt64(1)),
      (hex"01 2a", UInt64(42)),
      (hex"01 ff", UInt64(255)),
      (hex"02 0100", UInt64(256)),
      (hex"02 0231", UInt64(561)),
      (hex"02 ffff", UInt64(65535)),
      (hex"03 010000", UInt64(65536)),
      (hex"03 ffffff", UInt64(16777215)),
      (hex"04 01000000", UInt64(16777216)),
      (hex"04 01020304", UInt64(16909060)),
      (hex"04 ffffffff", UInt64(4294967295L)),
      (hex"05 0100000000", UInt64(4294967296L)),
      (hex"05 0102030405", UInt64(4328719365L)),
      (hex"05 ffffffffff", UInt64(1099511627775L)),
      (hex"06 010000000000", UInt64(1099511627776L)),
      (hex"06 010203040506", UInt64(1108152157446L)),
      (hex"06 ffffffffffff", UInt64(281474976710655L)),
      (hex"07 01000000000000", UInt64(281474976710656L)),
      (hex"07 01020304050607", UInt64(283686952306183L)),
      (hex"07 ffffffffffffff", UInt64(72057594037927935L)),
      (hex"08 0100000000000000", UInt64(72057594037927936L)),
      (hex"08 0102030405060708", UInt64(72623859790382856L)),
      (hex"08 ffffffffffffffff", UInt64.MaxValue)
    )

    for ((bin, expected) <- testCases) {
      val decoded = tu64.decode(bin.bits).require.value
      assert(decoded === expected)

      val encoded = tu64.encode(expected).require.bytes
      assert(encoded === bin)
    }
  }

  test("decode invalid truncated integers") {
    val testCases = Seq(
      (tu16, hex"01 00"), // not minimal
      (tu16, hex"02 0001"), // not minimal
      (tu16, hex"03 ffffff"), // length too big
      (tu32, hex"01 00"), // not minimal
      (tu32, hex"02 0001"), // not minimal
      (tu32, hex"03 000100"), // not minimal
      (tu32, hex"04 00010000"), // not minimal
      (tu32, hex"05 ffffffffff"), // length too big
      (tu64, hex"01 00"), // not minimal
      (tu64, hex"02 0001"), // not minimal
      (tu64, hex"03 000100"), // not minimal
      (tu64, hex"04 00010000"), // not minimal
      (tu64, hex"05 0001000000"), // not minimal
      (tu64, hex"06 000100000000"), // not minimal
      (tu64, hex"07 00010000000000"), // not minimal
      (tu64, hex"08 0001000000000000"), // not minimal
      (tu64, hex"09 ffffffffffffffffff") // length too big
    )

    for ((codec, bin) <- testCases) {
      assert(codec.decode(bin.bits).isFailure, bin)
    }
  }

  test("encode/decode tlv") {
    val testCases = Seq(
      (hex"01 01 2a", TestType1(42)),
      (hex"02 08 0000000000000226", TestType2(ShortChannelId(550))),
      (hex"03 31 02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 0000000000000231 0000000000000451", TestType3(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 561, 1105))
    )

    for ((bin, expected) <- testCases) {
      val decoded = testTlvCodec.decode(bin.bits).require.value.asInstanceOf[Tlv]
      assert(decoded === expected)
      val encoded = testTlvCodec.encode(expected).require.bytes
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
      assert(testTlvCodec.decode(testCase.bits).isFailure)
    }
  }

  test("decode invalid tlv stream") {
    val testCases = Seq(
      hex"01012a 02", // valid tlv record followed by invalid tlv record (only type, length and value are missing)
      hex"02080000000000000226 01012a", // valid tlv records but invalid ordering
      hex"02080000000000000231 02080000000000000451", // duplicate tlv type
      hex"01020100 2a0101", // unknown even type
      hex"0a020231 0b020451" // valid tlv records but from different namespace
    )

    for (testCase <- testCases) {
      assert(tlvStream(testTlvCodec).decode(testCase.bits).isFailure, testCase)
    }
  }

  test("create invalid tlv stream") {
    assertThrows[IllegalArgumentException](TlvStream(Seq(GenericTlv(42, hex"2a")))) // unknown even type
    assertThrows[IllegalArgumentException](TlvStream(Seq(TestType1(561), TestType2(ShortChannelId(1105)), GenericTlv(42, hex"2a")))) // unknown even type
    assertThrows[IllegalArgumentException](TlvStream(Seq(TestType1(561), TestType1(1105)))) // duplicate type
    assertThrows[IllegalArgumentException](TlvStream(Seq(TestType2(ShortChannelId(1105)), TestType1(561)))) // invalid ordering
  }

  test("encode/decode empty tlv stream") {
    assert(tlvStream(testTlvCodec).decode(hex"".bits).require.value === TlvStream(Nil))
    assert(tlvStream(testTlvCodec).encode(TlvStream(Nil)).require.bytes === hex"")
  }

  test("encode/decode tlv stream") {
    val bin = hex"01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451"
    val expected = Seq(
      TestType1(561),
      TestType2(ShortChannelId(1105)),
      TestType3(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 561, 1105)
    )

    val decoded = tlvStream(testTlvCodec).decode(bin.bits).require.value
    assert(decoded === TlvStream(expected))

    val encoded = tlvStream(testTlvCodec).encode(TlvStream(expected)).require.bytes
    assert(encoded === bin)
  }

  test("encode/decode tlv stream with unknown odd type") {
    val bin = hex"01020231 0b020451 0d02002a"
    val expected = Seq(
      TestType1(561),
      GenericTlv(11, hex"0451"),
      TestType13(42)
    )

    val decoded = tlvStream(testTlvCodec).decode(bin.bits).require.value
    assert(decoded === TlvStream(expected))

    val encoded = tlvStream(testTlvCodec).encode(TlvStream(expected)).require.bytes
    assert(encoded === bin)
  }

  test("encode/decode length-prefixed tlv stream") {
    val codec = lengthPrefixedTlvStream(testTlvCodec)
    val testCases = Seq(
      hex"41 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      hex"fd4d01 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451 ff6543210987654321 fd0001 10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101"
    )

    for (testCase <- testCases) {
      assert(codec.encode(codec.decode(testCase.bits).require.value).require.bytes === testCase)
    }
  }

  test("decode invalid length-prefixed tlv stream") {
    val testCases = Seq(
      hex"42 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      hex"40 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      hex"01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451"
    )

    for (testCase <- testCases) {
      assert(lengthPrefixedTlvStream(testTlvCodec).decode(testCase.bits).isFailure)
    }
  }

}

object TlvCodecsSpec {

  // @formatter:off
  sealed trait TestTlv extends Tlv
  case class TestType1(uintValue: UInt64) extends TestTlv { override val `type` = UInt64(1) }
  case class TestType2(shortChannelId: ShortChannelId) extends TestTlv { override val `type` = UInt64(2) }
  case class TestType3(nodeId: PublicKey, value1: UInt64, value2: UInt64) extends TestTlv { override val `type` = UInt64(3) }
  case class TestType13(intValue: Int) extends TestTlv { override val `type` = UInt64(13) }

  val testCodec1: Codec[TestType1] = ("value" | tu64).as[TestType1]
  val testCodec2: Codec[TestType2] = (("length" | constant(hex"08")) :: ("short_channel_id" | shortchannelid)).as[TestType2]
  val testCodec3: Codec[TestType3] = (("length" | constant(hex"31")) :: ("node_id" | publicKey) :: ("value_1" | uint64) :: ("value_2" | uint64)).as[TestType3]
  val testCodec13: Codec[TestType13] = (("length" | constant(hex"02")) :: ("value" | uint16)).as[TestType13]
  val testTlvCodec = discriminated[Tlv].by(varint)
    .typecase(1, testCodec1)
    .typecase(2, testCodec2)
    .typecase(3, testCodec3)
    .typecase(13, testCodec13)

  sealed trait OtherTlv extends Tlv
  case class OtherType1(uintValue: UInt64) extends OtherTlv { override val `type` = UInt64(10) }
  case class OtherType2(smallValue: Long) extends OtherTlv { override val `type` = UInt64(11) }

  val otherCodec1: Codec[OtherType1] = ("value" | tu64).as[OtherType1]
  val otherCodec2: Codec[OtherType2] = ("value" | tu32).as[OtherType2]
  val otherTlvCodec = discriminated[Tlv].by(varint)
    .typecase(10, otherCodec1)
    .typecase(11, otherCodec2)
  // @formatter:on

}
