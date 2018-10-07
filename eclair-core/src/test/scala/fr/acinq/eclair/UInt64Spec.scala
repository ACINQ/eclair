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

package fr.acinq.eclair

import fr.acinq.bitcoin.BinaryData
import org.scalatest.FunSuite



class UInt64Spec extends FunSuite {

  test("handle values from 0 to 2^63-1") {
    val a = UInt64("0xffffffffffffffff")
    val b = UInt64("0xfffffffffffffffe")
    val c = UInt64(42)
    val z = UInt64(0)
    assert(a > b)
    assert(b < a)
    assert(z < a && z < b && z < c)
    assert(a == a)
    assert(BinaryData(a.toByteArray) === BinaryData("0xffffffffffffffff"))
    assert(a.toString === "18446744073709551615")
    assert(BinaryData(b.toByteArray) === BinaryData("0xfffffffffffffffe"))
    assert(b.toString === "18446744073709551614")
    assert(BinaryData(c.toByteArray) === BinaryData("0x2a"))
    assert(c.toString === "42")
    assert(BinaryData(z.toByteArray) === BinaryData("0x00"))
    assert(z.toString === "0")
  }

}