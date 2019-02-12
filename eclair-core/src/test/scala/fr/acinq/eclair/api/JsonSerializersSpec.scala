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

package fr.acinq.eclair.api

import java.net.{InetAddress, InetSocketAddress}

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.{BinaryData, OutPoint}
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{NodeAddress, Tor2, Tor3}
import org.json4s.jackson.Serialization
import org.scalatest.{FunSuite, Matchers}


class JsonSerializersSpec extends FunSuite with Matchers {

  test("deserialize Map[OutPoint, BinaryData]") {
    val output1 = OutPoint("11418a2d282a40461966e4f578e1fdf633ad15c1b7fb3e771d14361127233be1", 0)
    val output2 = OutPoint("3d62bd4f71dc63798418e59efbc7532380c900b5e79db3a5521374b161dd0e33", 1)


    val map = Map(
      output1 -> BinaryData("dead"),
      output2 -> BinaryData("beef")
    )

    // it won't work with the default key serializer
    val error = intercept[org.json4s.MappingException] {
      Serialization.write(map)(org.json4s.DefaultFormats)
    }
    assert(error.msg.contains("Do not know how to serialize key of type class fr.acinq.bitcoin.OutPoint."))

    // but it works with our custom key serializer
    val json = Serialization.write(map)(org.json4s.DefaultFormats + new BinaryDataSerializer + new OutPointKeySerializer)
    assert(json === s"""{"${output1.txid}:0":"dead","${output2.txid}:1":"beef"}""")
  }

  test("NodeAddress serialization") {
    val ipv4 = NodeAddress.fromParts("10.0.0.1", 8888).get
    val ipv6LocalHost = NodeAddress.fromParts(InetAddress.getByAddress(Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)).getHostAddress, 9735).get
    val tor2 = Tor2("aaaqeayeaudaocaj", 7777)
    val tor3 = Tor3("aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypsaijc", 9999)

    Serialization.write(ipv4)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""10.0.0.1:8888""""
    Serialization.write(ipv6LocalHost)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""[0:0:0:0:0:0:0:1]:9735""""
    Serialization.write(tor2)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""aaaqeayeaudaocaj.onion:7777""""
    Serialization.write(tor3)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypsaijc.onion:9999""""
  }

  test("Direction serialization") {
    Serialization.write(IN)(org.json4s.DefaultFormats + new DirectionSerializer) shouldBe s""""IN""""
    Serialization.write(OUT)(org.json4s.DefaultFormats + new DirectionSerializer) shouldBe s""""OUT""""
  }

  test("Payment Request") {
    val ref = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    val pr = PaymentRequest.read(ref)
    Serialization.write(pr)(org.json4s.DefaultFormats + new PaymentRequestSerializer) shouldBe """{"prefix":"lnbc","amount":250000000,"timestamp":1496314658,"nodeId":"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad","description":"1 cup coffee","paymentHash":"0001020304050607080900010203040506070809000102030405060708090102","expiry":60,"minFinalCltvExpiry":null}"""
  }
}
