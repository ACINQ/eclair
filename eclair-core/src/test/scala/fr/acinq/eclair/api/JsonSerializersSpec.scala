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

import fr.acinq.bitcoin.{BinaryData, OutPoint}
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.NodeAddress
import org.json4s.jackson.Serialization
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, Matchers}

@RunWith(classOf[JUnitRunner])
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
    val ipv4 = NodeAddress(new InetSocketAddress(InetAddress.getByAddress(Array(10, 0, 0, 1)), 8888))
    val ipv6LocalHost = NodeAddress(new InetSocketAddress(InetAddress.getByAddress(Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)), 9735))

    Serialization.write(ipv4)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""10.0.0.1:8888""""
    Serialization.write(ipv6LocalHost)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""[0:0:0:0:0:0:0:1]:9735""""
  }

  test("Direction serialization") {
    Serialization.write(IN)(org.json4s.DefaultFormats + new DirectionSerializer) shouldBe s""""IN""""
    Serialization.write(OUT)(org.json4s.DefaultFormats + new DirectionSerializer) shouldBe s""""OUT""""
  }

}
