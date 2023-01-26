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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.wire.protocol.CommonCodecs.{publicKey, shortchannelid, uint64, varint}
import fr.acinq.eclair.wire.protocol.TlvCodecs._
import fr.acinq.eclair.{ShortChannelId, UInt64}
import org.scalatest.funsuite.AnyFunSuite
import scodec.Codec
import scodec.bits.HexStringSyntax
import scodec.codecs._

/**
 * Created by t-bast on 20/06/2019.
 */

class TlvCodecsSpec extends AnyFunSuite {

  import TlvCodecsSpec._

  test("encode/decode truncated uint16") {
    val testCases = Seq(
      (hex"", 0),
      (hex"01", 1),
      (hex"2a", 42),
      (hex"ff", 255),
      (hex"0100", 256),
      (hex"0231", 561),
      (hex"ffff", 65535)
    )

    for ((bin, expected) <- testCases) {
      val decoded = tu16.decode(bin.bits).require.value
      assert(decoded == expected)
      val encoded = tu16.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode truncated uint32") {
    val testCases = Seq(
      (hex"", 0L),
      (hex"01", 1L),
      (hex"2a", 42L),
      (hex"ff", 255L),
      (hex"0100", 256L),
      (hex"0231", 561L),
      (hex"ffff", 65535L),
      (hex"010000", 65536L),
      (hex"ffffff", 16777215L),
      (hex"01000000", 16777216L),
      (hex"01020304", 16909060L),
      (hex"ffffffff", 4294967295L)
    )

    for ((bin, expected) <- testCases) {
      val decoded = tu32.decode(bin.bits).require.value
      assert(decoded == expected)
      val encoded = tu32.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode truncated uint64") {
    val testCases = Seq(
      (hex"", UInt64(0)),
      (hex"01", UInt64(1)),
      (hex"2a", UInt64(42)),
      (hex"ff", UInt64(255)),
      (hex"0100", UInt64(256)),
      (hex"0231", UInt64(561)),
      (hex"ffff", UInt64(65535)),
      (hex"010000", UInt64(65536)),
      (hex"ffffff", UInt64(16777215)),
      (hex"01000000", UInt64(16777216)),
      (hex"01020304", UInt64(16909060)),
      (hex"ffffffff", UInt64(4294967295L)),
      (hex"0100000000", UInt64(4294967296L)),
      (hex"0102030405", UInt64(4328719365L)),
      (hex"ffffffffff", UInt64(1099511627775L)),
      (hex"010000000000", UInt64(1099511627776L)),
      (hex"010203040506", UInt64(1108152157446L)),
      (hex"ffffffffffff", UInt64(281474976710655L)),
      (hex"01000000000000", UInt64(281474976710656L)),
      (hex"01020304050607", UInt64(283686952306183L)),
      (hex"ffffffffffffff", UInt64(72057594037927935L)),
      (hex"0100000000000000", UInt64(72057594037927936L)),
      (hex"0102030405060708", UInt64(72623859790382856L)),
      (hex"ffffffffffffffff", UInt64.MaxValue)
    )

    for ((bin, expected) <- testCases) {
      val decoded = tu64.decode(bin.bits).require.value
      assert(decoded == expected)
      val encoded = tu64.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode truncated uint64 overflow") {
    assert(tu64overflow.encode(Long.MaxValue).require.toByteVector == hex"7fffffffffffffff")
    assert(tu64overflow.decode(hex"7fffffffffffffff".bits).require.value == Long.MaxValue)

    assert(tu64overflow.encode(42L).require.toByteVector == hex"2a")
    assert(tu64overflow.decode(hex"2a".bits).require.value == 42L)

    assert(tu64overflow.encode(-1L).isFailure)
    assert(tu64overflow.decode(hex"8000000000000000".bits).isFailure)
  }

  test("decode invalid truncated integers") {
    val testCases = Seq(
      (tu16, hex"00"), // not minimal
      (tu16, hex"0001"), // not minimal
      (tu16, hex"ffffff"), // length too big
      (tu32, hex"00"), // not minimal
      (tu32, hex"0001"), // not minimal
      (tu32, hex"000100"), // not minimal
      (tu32, hex"00010000"), // not minimal
      (tu32, hex"ffffffffff"), // length too big
      (tu64, hex"00"), // not minimal
      (tu64, hex"0001"), // not minimal
      (tu64, hex"000100"), // not minimal
      (tu64, hex"00010000"), // not minimal
      (tu64, hex"0001000000"), // not minimal
      (tu64, hex"000100000000"), // not minimal
      (tu64, hex"00010000000000"), // not minimal
      (tu64, hex"0001000000000000"), // not minimal
      (tu64, hex"ffffffffffffffffff") // length too big
    )

    for ((codec, bin) <- testCases) {
      assert(codec.decode(bin.bits).isFailure, bin)
    }
  }

  test("encode/decode tlv stream") {
    val testCases = Seq(
      (hex"", TlvStream[TestTlv]()),
      (hex"21 00", TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(33, hex"")))),
      (hex"fd0201 00", TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(513, hex"")))),
      (hex"fd00fd 00", TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(253, hex"")))),
      (hex"fd00ff 00", TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(255, hex"")))),
      (hex"fe02000001 00", TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(33554433, hex"")))),
      (hex"ff0200000000000001 00", TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(144115188075855873L, hex"")))),
      (hex"01 00", TlvStream[TestTlv](TestType1(0))),
      (hex"01 01 01", TlvStream[TestTlv](TestType1(1))),
      (hex"01 01 2a", TlvStream[TestTlv](TestType1(42))),
      (hex"01 02 0100", TlvStream[TestTlv](TestType1(256))),
      (hex"01 03 010000", TlvStream[TestTlv](TestType1(65536))),
      (hex"01 04 01000000", TlvStream[TestTlv](TestType1(16777216))),
      (hex"01 05 0100000000", TlvStream[TestTlv](TestType1(4294967296L))),
      (hex"01 06 010000000000", TlvStream[TestTlv](TestType1(1099511627776L))),
      (hex"01 07 01000000000000", TlvStream[TestTlv](TestType1(281474976710656L))),
      (hex"01 08 0100000000000000", TlvStream[TestTlv](TestType1(72057594037927936L))),
      (hex"02 08 0000000000000226", TlvStream[TestTlv](TestType2(ShortChannelId(550)))),
      (hex"03 31 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb 0000000000000231 0000000000000451", TlvStream[TestTlv](TestType3(PublicKey(hex"023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb"), 561, 1105))),
      (hex"fd00fe 02 0226", TlvStream[TestTlv](TestType254(550))),
      (hex"01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451", TlvStream[TestTlv](TestType1(561), TestType2(ShortChannelId(1105)), TestType3(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 561, 1105))),
      (hex"01020231 0b020451 fd00fe02002a", TlvStream[TestTlv](Set[TestTlv](TestType1(561), TestType254(42)), Set(GenericTlv(11, hex"0451"))))
    )

    for ((bin, expected) <- testCases) {
      val decoded = testTlvStreamCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val encoded = testTlvStreamCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("decode invalid tlv stream") {
    val testCases = Seq(
      // Type truncated.
      hex"fd",
      hex"fd01",
      // Not minimally encoded type.
      hex"fd0001 00",
      // Missing length.
      hex"fd0101",
      // Length truncated.
      hex"0f fd",
      hex"0f fd02",
      // Not minimally encoded length.
      hex"0f fd0001 00",
      hex"0f fe00000001 00",
      // Missing value.
      hex"0f fd2602",
      // Value truncated.
      hex"0f fd0201 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      // Unknown even type.
      hex"12 00",
      hex"0a 00",
      hex"fd0102 00",
      hex"01020101 0a0101",
      // Invalid TestTlv1.
      hex"01 01 00", // not minimally-encoded
      hex"01 02 0001", // not minimally-encoded
      hex"01 03 000100", // not minimally-encoded
      hex"01 04 00010000", // not minimally-encoded
      hex"01 05 0001000000", // not minimally-encoded
      hex"01 06 000100000000", // not minimally-encoded
      hex"01 07 00010000000000", // not minimally-encoded
      hex"01 08 0001000000000000", // not minimally-encoded
      // Invalid TestTlv2.
      hex"02 07 01010101010101", // invalid length
      hex"02 09 010101010101010101", // invalid length
      // Invalid TestTlv3.
      hex"03 21 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb", // invalid length
      hex"03 29 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb0000000000000001", // invalid length
      hex"03 30 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb000000000000000100000000000001", // invalid length
      hex"03 32 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb0000000000000001000000000000000001", // invalid length
      // Invalid TestTlv254.
      hex"fd00fe 00", // invalid length
      hex"fd00fe 01 01", // invalid length
      hex"fd00fe 03 010101", // invalid length
      // Invalid multi-record streams.
      hex"01012a 02", // valid tlv record followed by invalid tlv record (length missing)
      hex"01012a 0208", // valid tlv record followed by invalid tlv record (value missing)
      hex"01012a 020801010101", // valid tlv record followed by invalid tlv record (value truncated)
      hex"02080000000000000226 01012a", // valid tlv records but invalid ordering
      hex"1f00 0f012a", // valid tlv records but invalid ordering
      hex"02080000000000000231 02080000000000000451", // duplicate tlv type
      hex"01012a 0b020231 0b020451", // duplicate tlv type
      hex"1f00 1f012a", // duplicate tlv type
      hex"01012a 0a020231 0b020451" // valid tlv records but from different namespace
    )

    for (testCase <- testCases) {
      assert(testTlvStreamCodec.decode(testCase.bits).isFailure, testCase)
    }
  }

  test("encode/decode length-prefixed tlv stream") {
    val testCases = Seq(
      hex"41 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      hex"fd014d 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451 ff6543210987654321 fd0100 10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101"
    )

    for (testCase <- testCases) {
      assert(lengthPrefixedTestTlvStreamCodec.encode(lengthPrefixedTestTlvStreamCodec.decode(testCase.bits).require.value).require.bytes == testCase)
    }
  }

  test("decode invalid length-prefixed tlv stream") {
    val testCases = Seq(
      // Length too big.
      hex"42 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      // Length too short.
      hex"40 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      // Missing length.
      hex"01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
      // Valid length but duplicate types.
      hex"14 02080000000000000231 02080000000000000451",
      // Valid length but invalid ordering.
      hex"0e 02080000000000000451 01020231",
      // Valid length but unknown even type.
      hex"02 0a 00"
    )

    for (testCase <- testCases) {
      assert(lengthPrefixedTestTlvStreamCodec.decode(testCase.bits).isFailure)
    }
  }

  test("encode unordered tlv stream (codec should sort appropriately)") {
    val stream = TlvStream[TestTlv](Set[TestTlv](TestType254(42), TestType1(42)), Set(GenericTlv(13, hex"2a"), GenericTlv(11, hex"2b")))
    assert(testTlvStreamCodec.encode(stream).require.toByteVector == hex"01012a 0b012b 0d012a fd00fe02002a")
    assert(lengthPrefixedTestTlvStreamCodec.encode(stream).require.toByteVector == hex"0f 01012a 0b012b 0d012a fd00fe02002a")
  }

  test("encode/decode custom even tlv records") {
    val lowRangeEven = TlvStream[TestTlv](records = Set.empty[TestTlv], unknown = Set(GenericTlv(124, hex"2a")))
    val highRangeEven = TlvStream[TestTlv](records = Set.empty[TestTlv], unknown = Set(GenericTlv(67876545678L, hex"2b")))

    assert(testTlvStreamCodec.encode(lowRangeEven).isFailure)
    assert(testTlvStreamCodec.encode(highRangeEven).isSuccessful)
    assert(testTlvStreamCodec.decode(hex"7c 01 2a".toBitVector).isFailure) // lowRangeEven
    assert(testTlvStreamCodec.decode(testTlvStreamCodec.encode(highRangeEven).require).isSuccessful)
  }

  test("encode invalid tlv stream") {
    val testCases = Seq(
      // Unknown even type.
      TlvStream[TestTlv](Set.empty[TestTlv], Set(GenericTlv(42, hex"2a"))),
      TlvStream[TestTlv](Set[TestTlv](TestType1(561), TestType2(ShortChannelId(1105))), Set(GenericTlv(42, hex"2a"))),
      // Duplicate type.
      TlvStream[TestTlv](TestType1(561), TestType1(1105)),
      TlvStream[TestTlv](Set[TestTlv](TestType1(561)), Set(GenericTlv(1, hex"0451")))
    )

    for (stream <- testCases) {
      assert(testTlvStreamCodec.encode(stream).isFailure, stream)
      assert(lengthPrefixedTestTlvStreamCodec.encode(stream).isFailure, stream)
    }
  }

  test("get optional TLV field") {
    val stream = TlvStream[TestTlv](Set[TestTlv](TestType254(42), TestType1(42)), Set(GenericTlv(13, hex"2a"), GenericTlv(11, hex"2b")))
    assert(stream.get[TestType254].contains(TestType254(42)))
    assert(stream.get[TestType1].contains(TestType1(42)))
    assert(stream.get[TestType2].isEmpty)
  }
}

object TlvCodecsSpec {

  // See https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#appendix-a-type-length-value-test-vectors

  // @formatter:off
  sealed trait TestTlv extends Tlv
  case class TestType1(uintValue: UInt64) extends TestTlv
  case class TestType2(shortChannelId: ShortChannelId) extends TestTlv
  case class TestType3(nodeId: PublicKey, value1: UInt64, value2: UInt64) extends TestTlv
  case class TestType254(intValue: Int) extends TestTlv

  private val testCodec1: Codec[TestType1] = tlvField(tu64.as[TestType1])
  private val testCodec2: Codec[TestType2] = fixedLengthTlvField(8, shortchannelid.as[TestType2])
  private val testCodec3: Codec[TestType3] = fixedLengthTlvField(49, (("node_id" | publicKey) :: ("value_1" | uint64) :: ("value_2" | uint64)).as[TestType3])
  private val testCodec254: Codec[TestType254] = fixedLengthTlvField(2, uint16.as[TestType254])

  private val testTlvCodec = discriminated[TestTlv].by(varint)
    .typecase(1, testCodec1)
    .typecase(2, testCodec2)
    .typecase(3, testCodec3)
    .typecase(254, testCodec254)

  val testTlvStreamCodec = tlvStream(testTlvCodec)
  val lengthPrefixedTestTlvStreamCodec = lengthPrefixedTlvStream(testTlvCodec)

  sealed trait OtherTlv extends Tlv
  case class OtherType1(uintValue: UInt64) extends OtherTlv
  case class OtherType2(smallValue: Long) extends OtherTlv

  val otherCodec1: Codec[OtherType1] = tlvField(tu64.as[OtherType1])
  val otherCodec2: Codec[OtherType2] = tlvField(tu32.as[OtherType2])
  val otherTlvStreamCodec = tlvStream(discriminated[OtherTlv].by(varint)
    .typecase(10, otherCodec1)
    .typecase(11, otherCodec2))
  // @formatter:on

}
