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

package fr.acinq.eclair.api

import java.net.InetAddress
import java.util.UUID

import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, OutPoint, Transaction}
import fr.acinq.eclair._
import fr.acinq.eclair.payment.{PaymentRequest, PaymentSettlingOnChain}
import fr.acinq.eclair.api.JsonSupport.CustomTypeHints
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{NodeAddress, Tor2, Tor3}
import org.json4s.jackson.Serialization
import org.scalatest.{FunSuite, Matchers}
import scodec.bits._

class JsonSerializersSpec extends FunSuite with Matchers {

  test("deserialize Map[OutPoint, ByteVector]") {
    val output1 = OutPoint(ByteVector32(hex"11418a2d282a40461966e4f578e1fdf633ad15c1b7fb3e771d14361127233be1"), 0)
    val output2 = OutPoint(ByteVector32(hex"3d62bd4f71dc63798418e59efbc7532380c900b5e79db3a5521374b161dd0e33"), 1)


    val map = Map(
      output1 -> hex"dead",
      output2 -> hex"beef"
    )

    // it won't work with the default key serializer
    val error = intercept[org.json4s.MappingException] {
      Serialization.write(map)(org.json4s.DefaultFormats)
    }
    assert(error.msg.contains("Do not know how to serialize key of type class fr.acinq.bitcoin.OutPoint."))

    // but it works with our custom key serializer
    val json = Serialization.write(map)(org.json4s.DefaultFormats + new ByteVectorSerializer + new OutPointKeySerializer)
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
    JsonSupport.serialization.write(pr)(JsonSupport.formats) shouldBe """{"prefix":"lnbc","timestamp":1496314658,"nodeId":"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad","serialized":"lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp","description":"1 cup coffee","paymentHash":"0001020304050607080900010203040506070809000102030405060708090102","expiry":60,"amount":250000000}"""
  }

  test("type hints") {
    implicit val formats = JsonSupport.formats.withTypeHintFieldName("type") + CustomTypeHints(Map(classOf[PaymentSettlingOnChain] -> "payment-settling-onchain")) + new MilliSatoshiSerializer
    val e1 = PaymentSettlingOnChain(UUID.randomUUID, MilliSatoshi(42), randomBytes32)
    assert(Serialization.writePretty(e1).contains("\"type\" : \"payment-settling-onchain\""))
  }

  test("transaction serializer") {
    implicit val formats = JsonSupport.formats

    val tx = Transaction.read("0200000001c8a8934fb38a44b969528252bc37be66ee166c7897c57384d1e561449e110c93010000006b483045022100dc6c50f445ed53d2fb41067fdcb25686fe79492d90e6e5db43235726ace247210220773d35228af0800c257970bee9cf75175d75217de09a8ecd83521befd040c4ca012102082b751372fe7e3b012534afe0bb8d1f2f09c724b1a10a813ce704e5b9c217ccfdffffff0247ba2300000000001976a914f97a7641228e6b17d4b0b08252ae75bd62a95fe788ace3de24000000000017a914a9fefd4b9a9282a1d7a17d2f14ac7d1eb88141d287f7d50800")

    assert(JsonSupport.serialization.write(tx) == "{\"txid\":\"3ef63b5d297c9dcf93f33b45b9f102733c36e8ef61da1ccf2bc132a10584be18\",\"tx\":\"0200000001c8a8934fb38a44b969528252bc37be66ee166c7897c57384d1e561449e110c93010000006b483045022100dc6c50f445ed53d2fb41067fdcb25686fe79492d90e6e5db43235726ace247210220773d35228af0800c257970bee9cf75175d75217de09a8ecd83521befd040c4ca012102082b751372fe7e3b012534afe0bb8d1f2f09c724b1a10a813ce704e5b9c217ccfdffffff0247ba2300000000001976a914f97a7641228e6b17d4b0b08252ae75bd62a95fe788ace3de24000000000017a914a9fefd4b9a9282a1d7a17d2f14ac7d1eb88141d287f7d50800\"}")

  }
}
