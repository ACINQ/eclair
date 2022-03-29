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

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.scalacompat.ByteVector32
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

/**
  * Created by t-bast on 04/07/19.
  */

class MacSpec extends AnyFunSuite {

  test("HMAC-256 mac/verify") {
    val keys = Seq(
      hex"0000000000000000000000000000000000000000000000000000000000000000",
      hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619",
      hex"24653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c7f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007"
    )
    val messages = Seq(
      hex"2a",
      hex"451",
      hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619",
      hex"fd0001000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
    )

    for (key <- keys) {
      val instance = Hmac256(key)
      for (message <- messages) {
        assert(instance.verify(instance.mac(message), message))
      }
    }
  }

  test("HMAC-256 invalid macs") {
    val instance = Hmac256(ByteVector32.Zeroes)
    val testCases = Seq(
      (hex"0000000000000000000000000000000000000000000000000000000000000000", hex"2a"),
      (hex"4aa79e2da0cb5beae9b5dad4006909cb402e4201e191733bc2b5279629e4ed80", hex"fd0001000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f30313233")
    ).map(testCase => (ByteVector32(testCase._1), testCase._2))

    for ((mac, message) <- testCases) {
      assert(!instance.verify(mac, message))
    }
  }

}
