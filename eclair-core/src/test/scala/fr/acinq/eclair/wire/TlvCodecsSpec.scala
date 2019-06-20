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
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.CommonCodecs.{publicKey, shortchannelid, uint64, varInt}
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
      (hex"0x01 08 000000000000002a", TestType1(42)),
      (hex"0x02 08 0000000000000226", TestType2(ShortChannelId(550))),
      (hex"0x03 31 02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 0000000000000231 0000000000000451", TestType3(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 561, 1105)),
      (hex"0xff1234567890123456 fd0001 10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101", GenericTlv(6211610197754262546L, hex"10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101"))
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
      hex"0xfd022a", // type truncated
      hex"0x2a fd022a", // length truncated
      hex"0x2a fd2602 0231", // value truncated
      hex"0x02 01 2a", // short channel id too short
      hex"0x02 09 010101010101010101" // short channel id length too big
    )

    for (testCase <- testCases) {
      assert(testTlvCodec.decode(testCase.toBitVector).isFailure)
    }
  }

}

object TlvCodecsSpec {

  // @formatter:off
  sealed trait TestTlv extends Tlv
  case class TestType1(longValue: Long) extends TestTlv
  case class TestType2(shortChannelId: ShortChannelId) extends TestTlv
  case class TestType3(nodeId: PublicKey, value1: Long, value2: Long) extends TestTlv

  val testCodec1: Codec[TestType1] = (("length" | constant(hex"0x08")) :: ("value" | uint64)).as[TestType1]
  val testCodec2: Codec[TestType2] = (("length" | constant(hex"0x08")) :: ("short_channel_id" | shortchannelid)).as[TestType2]
  val testCodec3: Codec[TestType3] = (("length" | constant(hex"0x31")) :: ("node_id" | publicKey) :: ("value_1" | uint64) :: ("value_2" | uint64)).as[TestType3]
  val testTlvCodec = tlvFallback(discriminated[Tlv].by(varInt)
    .typecase(1, testCodec1)
    .typecase(2, testCodec2)
    .typecase(3, testCodec3)
  )
  // @formatter:on

}
