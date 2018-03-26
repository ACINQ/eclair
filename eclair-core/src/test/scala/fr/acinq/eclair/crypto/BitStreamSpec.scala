/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.crypto

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.spongycastle.util.encoders.Hex

/**
  * Created by fabrice on 22/06/17.
  */
@RunWith(classOf[JUnitRunner])
class BitStreamSpec extends FunSuite {

  import BitStream._

  test("add bits") {
    val bits = BitStream.empty
    val bits1 = bits.writeBit(One)
    assert(bits1.bitCount == 1)
    assert(bits1.isSet(0))
    assert(Hex.toHexString(bits1.bytes.toArray) == "80")
    val bits2 = bits1.writeBit(Zero)
    assert(bits2.bitCount == 2)
    assert(bits2.isSet(0))
    assert(!bits2.isSet(1))
    assert(Hex.toHexString(bits2.bytes.toArray) == "80")
    val bits3 = bits2.writeBit(One)
    assert(bits3.bitCount == 3)
    assert(bits3.isSet(0))
    assert(!bits3.isSet(1))
    assert(bits3.isSet(2))
    assert(bits3.toHexString == "0xa0")
    assert(bits3.toBinString == "0b101")

    val (bits4, One) = bits3.popBit
    assert(bits4 == bits2)
    val (bits5, Zero) = bits4.popBit
    assert(bits5 == bits1)
    val (bits6, One) = bits5.popBit
    assert(bits6 == bits)

    val (bits7, One) = bits3.readBit
    val (bits8, Zero) = bits7.readBit
    val (bits9, One) = bits8.readBit
    assert(bits9.isEmpty)
  }

  test("add bytes") {
    val bits = BitStream.empty
    val bits1 = bits.writeByte(0xb5.toByte)
    assert(bits1.bitCount == 8)
    assert(bits1.toHexString == "0xb5")
    assert(bits1.toBinString == "0b10110101")
    // b5 = 1100 0101
    val bits2 = bits1.writeBit(Zero)
    assert(bits2.bitCount == 9)
    // 1100 0101 0
    assert(bits2.toHexString == "0xb500")
    assert(bits2.toBinString == "0b101101010")
    val bits3 = bits2.writeBit(One)
    assert(bits3.bitCount == 10)
    // 1100 0101 01
    assert(bits3.toHexString == "0xb540")
    assert(bits3.toBinString == "0b1011010101")

    // 1011 0101 01xx xxxx
    // 10xx xxxx and 1101 0101
    val (bits4, check) = bits3.popByte
    assert(check == 0xd5.toByte)
    assert(bits4.toBinString == "0b10")

    val (bits5, check5) = bits3.readByte
    assert(check5 == 0xb5.toByte)
  }
}
