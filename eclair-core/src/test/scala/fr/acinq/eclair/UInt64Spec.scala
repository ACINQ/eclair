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

package fr.acinq.eclair

import org.scalatest.FunSuite
import scodec.bits._


class UInt64Spec extends FunSuite {

  test("handle values from 0 to 2^64-1") {
    val a = UInt64(hex"0xffffffffffffffff")
    val b = UInt64(hex"0xfffffffffffffffe")
    val c = UInt64(42)
    val z = UInt64(0)
    val l = UInt64(Long.MaxValue)
    val l1 = UInt64(hex"8000000000000000") // Long.MaxValue + 1

    assert(a > b)
    assert(a.toBigInt > b.toBigInt)
    assert(b < a)
    assert(b.toBigInt < a.toBigInt)
    assert(l.toBigInt < l1.toBigInt)
    assert(z < a && z < b && z < c && z < l && c < l && l < l1 && l < b && l < a)
    assert(a == a)
    assert(a == UInt64.MaxValue)
    assert(l.toByteVector == hex"7fffffffffffffff")
    assert(l.toString == Long.MaxValue.toString)
    assert(l.toBigInt == BigInt(Long.MaxValue))
    assert(l1.toByteVector == hex"8000000000000000")
    assert(l1.toString == "9223372036854775808")
    assert(l1.toBigInt == BigInt("9223372036854775808"))
    assert(a.toByteVector === hex"0xffffffffffffffff")
    assert(a.toString === "18446744073709551615") // 2^64 - 1
    assert(b.toByteVector === hex"0xfffffffffffffffe")
    assert(b.toString === "18446744073709551614")
    assert(c.toByteVector === hex"0x2a")
    assert(c.toString === "42")
    assert(z.toByteVector === hex"0x00")
    assert(z.toString === "0")
    assert(UInt64(hex"0xff").toByteVector == hex"0xff")
    assert(UInt64(hex"0x800").toByteVector == hex"0x800")
  }

}