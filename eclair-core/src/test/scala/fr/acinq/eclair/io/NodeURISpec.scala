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

package fr.acinq.eclair.io

import org.scalatest.funsuite.AnyFunSuite


class NodeURISpec extends AnyFunSuite {

  val PUBKEY = "03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"
  val SHORT_PUB_KEY = "03933884aaf1d6b108397e5efe5c86bcf2d8ca"
  val NOT_HEXA_PUB_KEY = "03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fcghijklmn"

  val IPV4_ENDURANCE = "34.250.234.192"
  val NAME_ENDURANCE = "endurance.acinq.co"
  val IPV6 = "[2405:204:66a9:536c:873f:dc4a:f055:a298]"
  val IPV6_NO_BRACKETS = "2001:db8:a0b:12f0::1"
  val IPV6_PREFIX = "[2001:db8:a0b:12f0::1/64]"
  val IPV6_ZONE_IDENTIFIER = "[2001:db8:a0b:12f0::1%eth0]"

  test("default port") {
    assert(NodeURI.DEFAULT_PORT == 9735)
  }

  test("NodeURI parsing") {
    case class TestCase(uri: String, formattedAddr: String, port: Int)

    val testCases = List(
      TestCase(s"$PUBKEY@$IPV4_ENDURANCE:9737", IPV4_ENDURANCE, 9737),
      TestCase(s"$PUBKEY@$IPV4_ENDURANCE", IPV4_ENDURANCE, 9735),
      TestCase(s"$PUBKEY@$NAME_ENDURANCE:9737", "13.248.222.197", 9737),
      TestCase(s"$PUBKEY@$NAME_ENDURANCE", "13.248.222.197", 9735),
      TestCase(s"$PUBKEY@$IPV6:9737", "[2405:204:66a9:536c:873f:dc4a:f055:a298]", 9737),
      TestCase(s"$PUBKEY@$IPV6", "[2405:204:66a9:536c:873f:dc4a:f055:a298]", 9735),
      TestCase(s"$PUBKEY@$IPV6_ZONE_IDENTIFIER:9737", "[2001:db8:a0b:12f0::1]", 9737),
      TestCase(s"$PUBKEY@$IPV6_ZONE_IDENTIFIER", "[2001:db8:a0b:12f0::1]", 9735),
    )

    for (testCase <- testCases) {
      val nodeUri = NodeURI.parse(testCase.uri)
      assert(nodeUri.nodeId.toString() == PUBKEY)
      assert(nodeUri.address.host == testCase.formattedAddr)
      assert(nodeUri.address.port == testCase.port)
      assert(nodeUri.toString === s"$PUBKEY@${testCase.formattedAddr}:${testCase.port}")
    }
  }

  // ---------- fail if public key is not valid

  test("parsing should fail if the public key is not correct") {
    intercept[IllegalArgumentException](NodeURI.parse(s"$SHORT_PUB_KEY@$IPV4_ENDURANCE"))
    intercept[IllegalArgumentException](NodeURI.parse(s"$NOT_HEXA_PUB_KEY@$IPV4_ENDURANCE"))
  }

  // ---------- fail if host:port is not valid

  test("parsing should fail if host:port is not valid") {
    intercept[IllegalArgumentException](NodeURI.parse(s"$SHORT_PUB_KEY@1.2.3.4:abcd"))
    intercept[IllegalArgumentException](NodeURI.parse(s"$SHORT_PUB_KEY@1.2.3.4:999999999999999999999"))
  }

  test("parsing should fail if the uri is malformed") {
    intercept[IllegalArgumentException](NodeURI.parse("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@"))
    intercept[IllegalArgumentException](NodeURI.parse("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@123.45@654321"))
    intercept[IllegalArgumentException](NodeURI.parse("loremipsum"))
    intercept[IllegalArgumentException](NodeURI.parse(IPV6))
    intercept[IllegalArgumentException](NodeURI.parse(IPV4_ENDURANCE))
    intercept[IllegalArgumentException](NodeURI.parse(PUBKEY))
    intercept[IllegalArgumentException](NodeURI.parse(""))
    intercept[IllegalArgumentException](NodeURI.parse("@"))
    intercept[IllegalArgumentException](NodeURI.parse(":"))
  }

}



