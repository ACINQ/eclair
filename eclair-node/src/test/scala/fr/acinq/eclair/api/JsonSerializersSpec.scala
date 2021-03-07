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

import fr.acinq.bitcoin.{ByteVector32, OutPoint, Transaction}
import fr.acinq.eclair._
import fr.acinq.eclair.api.serde.{ByteVectorSerializer, DirectedHtlcSerializer, JsonSupport, NodeAddressSerializer, OriginSerializer, OutPointKeySerializer}
import fr.acinq.eclair.channel.Origin
import fr.acinq.eclair.payment.{PaymentRequest, PaymentSettlingOnChain}
import fr.acinq.eclair.transactions.{IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scodec.bits._

import java.net.InetAddress
import java.util.UUID

class JsonSerializersSpec extends AnyFunSuite with Matchers {

  test("deserialize Map[OutPoint, ByteVector]") {
    val output1 = OutPoint(ByteVector32(hex"11418a2d282a40461966e4f578e1fdf633ad15c1b7fb3e771d14361127233be1"), 0)
    val output2 = OutPoint(ByteVector32(hex"3d62bd4f71dc63798418e59efbc7532380c900b5e79db3a5521374b161dd0e33"), 1)
    val map = Map(
      output1 -> hex"dead",
      output2 -> hex"beef"
    )

    // it won't work with the default key serializer
    val error = intercept[org.json4s.MappingException] {
      JsonSupport.serialization.write(map)(org.json4s.DefaultFormats)
    }
    assert(error.msg.contains("Do not know how to serialize key of type class fr.acinq.bitcoin.OutPoint."))

    // but it works with our custom key serializer
    val json = JsonSupport.serialization.write(map)(org.json4s.DefaultFormats + new ByteVectorSerializer + new OutPointKeySerializer)
    assert(json === s"""{"${output1.txid}:0":"dead","${output2.txid}:1":"beef"}""")
  }

  test("NodeAddress serialization") {
    val ipv4 = NodeAddress.fromParts("10.0.0.1", 8888).get
    val ipv6LocalHost = NodeAddress.fromParts(InetAddress.getByAddress(Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)).getHostAddress, 9735).get
    val tor2 = Tor2("aaaqeayeaudaocaj", 7777)
    val tor3 = Tor3("aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypsaijc", 9999)

    JsonSupport.serialization.write(ipv4)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""10.0.0.1:8888""""
    JsonSupport.serialization.write(ipv6LocalHost)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""[0:0:0:0:0:0:0:1]:9735""""
    JsonSupport.serialization.write(tor2)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""aaaqeayeaudaocaj.onion:7777""""
    JsonSupport.serialization.write(tor3)(org.json4s.DefaultFormats + new NodeAddressSerializer) shouldBe s""""aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypsaijc.onion:9999""""
  }

  test("DirectedHtlc serialization") {
    val add = UpdateAddHtlc(
      channelId = ByteVector32(hex"345b2b05ec046ffe0c14d3b61838c79980713ad1cf8ae7a45c172ce90c9c0b9f"),
      id = 926,
      amountMsat = 12365.msat,
      paymentHash = ByteVector32(hex"9fcd45bbaa09c60c991ac0425704163c3f3d2d683c789fa409455b9c97792692"),
      cltvExpiry = CltvExpiry(621500),
      onionRoutingPacket = OnionRoutingPacket(
        version = 0,
        publicKey = hex"0270685ca81a8e4d4d01beec5781f4cc924684072ae52c507f8ebe9daf0caaab7b",
        payload = hex"3c7a66997c681a3de1bae56438abeee4fc50a16554725a430ade1dc8db6bdd76704d45c6151c4051d710cf487e63f8cbe9f5e537e6e518d7998f11c40277d551bee036d227a421f344c0185b4533660d200acbc4f4aa6148e29f813bb537e950a79ba961b80ccaa6ad808cb88f858ee73f8b1f129d3214d194f76fc011c46e18c2caec0fcb69715e79cb381e449e5be20281e0aaa92defa089c98b7e29c22181f2d1af9f5fe2a37ede4ac3163b123aa72318201b1128b17053c381e7bf111620dfb7ea3dcc5c28cafdd9cb7bb1a4202b64199aa02af145c563ba1b9f10288203ce2666f486aacb2ee385dc0b67915864c95174dfaac1e0ea195329d1741cd1febb4b49b33f84e0d10a5ec8ee0a1a94f73abf081ec69bb863edfeb46d24ce424b025ac3f593a7ba9419ec9b17fb39f0fbeac80e0898f95b6709cbc95f7c097e22e3a6ca0efbc947bbcd4a9077f6bd9daba25b18bb16179fca9d9cb2ce49fc7cbd2de589237926cacb87ea60e2cc60a90b47575517921b5529b8a95823dd0c3d02a7747d74c4ca927ba6b70c06c1c1ef27e14d371e8dd8f5d9380a65b08ae1e6384f9b3575c5d7278de22ce80e63612a27f3b3f45dbe32ee855185293c719e5a7203a682a08fd810c46fa12b67e61349831f8fae3f558090ea988e1a22ec877b790ea09169055529247c4dd597857aad74eaeb3a5879e96453e681e213f2796ed704d620509f34f91d9d16f881fd397e2836a0a4d2f1bcd230067f7acb5381a2b17e8c5135e38c4d258afbe4f69ac7ad39b789e99686ee926b3ad31b98993673313b7b18a4faaea238d8055824fde7339a791fc7777ef28cc4a1a5d177b3c3882ced4921c6cd85ae82e1fe13fe680ae432a9918ce37b15f88d4d18fb16b69e5369d18c204aaf7ee49b830bf2328380e6ad96e8f6a9e01bc2c97ffbaa402d5406dc7b83c6eb5d515ffc3bea8c42cf299c9e2bea693515246c6ff859d33ba6c4da4c4c1706e0c6b4a574e927f02eb92b7d56722cff80c3e6f6b98d1c84cb576abdcc27a6bc7b110fc2ac5fead57f05ad854c3331ce1ff94c0dc97303540ee797d71566497af09f20e3554d467528e1fed8e69438171072fe2deca3979a8f5ec9043b9bc4da921b095c29dc0294148c1b7001dafda4c48600d1194f745e6d0689c561bf19d20758c4d25fac64d81780607a4106e220ef546fc4026af7b9da8defb2fe3c21d48798ac67c794fb40aabe44618a8911673466be06808c6f54a772b87bcfafb4d120a9bebffb8051bf24bb332eaa769cf175c1aadb0186f8946dc32513fd81fe1a61bfb860886bdd070359a43e06e74607d300bd2e01a3f1ee900c4039e8db742170228db61ef0c77724c49d1573144564a80cc1ebc0449b34f84be35187ceba3fbc2facf5ad1f1e15945e3c6c236579aca7bc97e4cc76a3310022693b64008562b254a7d11c0086813e551c4817bbb72a1d6fbfc84d326ce973651200f80aa8ab0976c53c390249ca8e7e5ec21b80e70c3e0205983d313b28a5d1b9d9149501e05d3257c8ae88c6308e9e00feeab19121d1032a582e68ca1f9f64a1fd91cb5d8613b985fd4be22a4d5c14a132c20811a75ee3cc61de0b3fbc3254d61995d086603032269888b942ec0971ad26ea4b8df1746c5ec1de904ddeb5045abc0a6ede9d6a199ed0782cb69efa3a4dc00747553dbef12fb8299ca364b4cdf2ac3eba03b0d8b273684116bba4458b5717bc4aca5406901173a89b3643ccc076f22779fccf1ad69981e24eef18c711a2d58dfe5834b41f9e7166d54dc8628e754baaca1cbb7db8256f88ebc889de6078ba83a1af14a4",
        hmac = ByteVector32(hex"9442626f72c475963dbddf8a57ab2cef3013eb3d6a5e8afbea9e631dac4481f5")
      )
    )

    val expectedIn = """{"direction":"IN","add":{"channelId":"345b2b05ec046ffe0c14d3b61838c79980713ad1cf8ae7a45c172ce90c9c0b9f","id":926,"amountMsat":12365,"paymentHash":"9fcd45bbaa09c60c991ac0425704163c3f3d2d683c789fa409455b9c97792692","cltvExpiry":621500,"onionRoutingPacket":{"version":0,"publicKey":"0270685ca81a8e4d4d01beec5781f4cc924684072ae52c507f8ebe9daf0caaab7b","payload":"3c7a66997c681a3de1bae56438abeee4fc50a16554725a430ade1dc8db6bdd76704d45c6151c4051d710cf487e63f8cbe9f5e537e6e518d7998f11c40277d551bee036d227a421f344c0185b4533660d200acbc4f4aa6148e29f813bb537e950a79ba961b80ccaa6ad808cb88f858ee73f8b1f129d3214d194f76fc011c46e18c2caec0fcb69715e79cb381e449e5be20281e0aaa92defa089c98b7e29c22181f2d1af9f5fe2a37ede4ac3163b123aa72318201b1128b17053c381e7bf111620dfb7ea3dcc5c28cafdd9cb7bb1a4202b64199aa02af145c563ba1b9f10288203ce2666f486aacb2ee385dc0b67915864c95174dfaac1e0ea195329d1741cd1febb4b49b33f84e0d10a5ec8ee0a1a94f73abf081ec69bb863edfeb46d24ce424b025ac3f593a7ba9419ec9b17fb39f0fbeac80e0898f95b6709cbc95f7c097e22e3a6ca0efbc947bbcd4a9077f6bd9daba25b18bb16179fca9d9cb2ce49fc7cbd2de589237926cacb87ea60e2cc60a90b47575517921b5529b8a95823dd0c3d02a7747d74c4ca927ba6b70c06c1c1ef27e14d371e8dd8f5d9380a65b08ae1e6384f9b3575c5d7278de22ce80e63612a27f3b3f45dbe32ee855185293c719e5a7203a682a08fd810c46fa12b67e61349831f8fae3f558090ea988e1a22ec877b790ea09169055529247c4dd597857aad74eaeb3a5879e96453e681e213f2796ed704d620509f34f91d9d16f881fd397e2836a0a4d2f1bcd230067f7acb5381a2b17e8c5135e38c4d258afbe4f69ac7ad39b789e99686ee926b3ad31b98993673313b7b18a4faaea238d8055824fde7339a791fc7777ef28cc4a1a5d177b3c3882ced4921c6cd85ae82e1fe13fe680ae432a9918ce37b15f88d4d18fb16b69e5369d18c204aaf7ee49b830bf2328380e6ad96e8f6a9e01bc2c97ffbaa402d5406dc7b83c6eb5d515ffc3bea8c42cf299c9e2bea693515246c6ff859d33ba6c4da4c4c1706e0c6b4a574e927f02eb92b7d56722cff80c3e6f6b98d1c84cb576abdcc27a6bc7b110fc2ac5fead57f05ad854c3331ce1ff94c0dc97303540ee797d71566497af09f20e3554d467528e1fed8e69438171072fe2deca3979a8f5ec9043b9bc4da921b095c29dc0294148c1b7001dafda4c48600d1194f745e6d0689c561bf19d20758c4d25fac64d81780607a4106e220ef546fc4026af7b9da8defb2fe3c21d48798ac67c794fb40aabe44618a8911673466be06808c6f54a772b87bcfafb4d120a9bebffb8051bf24bb332eaa769cf175c1aadb0186f8946dc32513fd81fe1a61bfb860886bdd070359a43e06e74607d300bd2e01a3f1ee900c4039e8db742170228db61ef0c77724c49d1573144564a80cc1ebc0449b34f84be35187ceba3fbc2facf5ad1f1e15945e3c6c236579aca7bc97e4cc76a3310022693b64008562b254a7d11c0086813e551c4817bbb72a1d6fbfc84d326ce973651200f80aa8ab0976c53c390249ca8e7e5ec21b80e70c3e0205983d313b28a5d1b9d9149501e05d3257c8ae88c6308e9e00feeab19121d1032a582e68ca1f9f64a1fd91cb5d8613b985fd4be22a4d5c14a132c20811a75ee3cc61de0b3fbc3254d61995d086603032269888b942ec0971ad26ea4b8df1746c5ec1de904ddeb5045abc0a6ede9d6a199ed0782cb69efa3a4dc00747553dbef12fb8299ca364b4cdf2ac3eba03b0d8b273684116bba4458b5717bc4aca5406901173a89b3643ccc076f22779fccf1ad69981e24eef18c711a2d58dfe5834b41f9e7166d54dc8628e754baaca1cbb7db8256f88ebc889de6078ba83a1af14a4","hmac":"9442626f72c475963dbddf8a57ab2cef3013eb3d6a5e8afbea9e631dac4481f5"}}}"""

    JsonSupport.serialization.write(IncomingHtlc(add))(org.json4s.DefaultFormats + new DirectedHtlcSerializer) shouldBe expectedIn
    JsonSupport.serialization.write(OutgoingHtlc(add))(org.json4s.DefaultFormats + new DirectedHtlcSerializer) shouldBe expectedIn.replace("IN", "OUT")
  }

  test("HTLC origin serialization") {
    val localOrigin = Origin.LocalCold(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    val expectedLocalOrigin = """{"paymentId":"11111111-1111-1111-1111-111111111111"}"""
    JsonSupport.serialization.write(localOrigin)(org.json4s.DefaultFormats + new OriginSerializer) shouldBe expectedLocalOrigin

    val channelOrigin = Origin.ChannelRelayedCold(ByteVector32(hex"345b2b05ec046ffe0c14d3b61838c79980713ad1cf8ae7a45c172ce90c9c0b9f"), 7, 500 msat, 400 msat)
    val expectedChannelOrigin = """{"channelId":"345b2b05ec046ffe0c14d3b61838c79980713ad1cf8ae7a45c172ce90c9c0b9f","htlcId":7}"""
    JsonSupport.serialization.write(channelOrigin)(org.json4s.DefaultFormats + new OriginSerializer) shouldBe expectedChannelOrigin

    val trampolineOrigin = Origin.TrampolineRelayedCold((ByteVector32(hex"9fcd45bbaa09c60c991ac0425704163c3f3d2d683c789fa409455b9c97792692"), 3L) :: (ByteVector32(hex"70685ca81a8e4d4d01beec5781f4cc924684072ae52c507f8ebe9daf0caaab7b"), 7L) :: Nil)
    val expectedTrampolineOrigin = """[{"channelId":"9fcd45bbaa09c60c991ac0425704163c3f3d2d683c789fa409455b9c97792692","htlcId":3},{"channelId":"70685ca81a8e4d4d01beec5781f4cc924684072ae52c507f8ebe9daf0caaab7b","htlcId":7}]"""
    JsonSupport.serialization.write(trampolineOrigin)(org.json4s.DefaultFormats + new OriginSerializer) shouldBe expectedTrampolineOrigin
  }

  test("Payment Request") {
    val ref = "lnbcrt50n1p0fm9cdpp5al3wvsfkc6p7fxy89eu8gm4aww9mseu9syrcqtpa4mvx42qelkwqdq9v9ekgxqrrss9qypqsqsp5wl2t45v0hj4lgud0zjxcnjccd29ts0p2kh4vpw75vnhyyzyjtjtqarpvqg33asgh3z5ghfuvhvtf39xtnu9e7aqczpgxa9quwsxkd9rnwmx06pve9awgeewxqh90dqgrhzgsqc09ek6uejr93z8puafm6gsqgrk0hy"
    val pr = PaymentRequest.read(ref)
    JsonSupport.serialization.write(pr)(JsonSupport.formats) shouldBe """{"prefix":"lnbcrt","timestamp":1587386125,"nodeId":"03b207771ddba774e318970e9972da2491ff8e54f777ad0528b6526773730248a0","serialized":"lnbcrt50n1p0fm9cdpp5al3wvsfkc6p7fxy89eu8gm4aww9mseu9syrcqtpa4mvx42qelkwqdq9v9ekgxqrrss9qypqsqsp5wl2t45v0hj4lgud0zjxcnjccd29ts0p2kh4vpw75vnhyyzyjtjtqarpvqg33asgh3z5ghfuvhvtf39xtnu9e7aqczpgxa9quwsxkd9rnwmx06pve9awgeewxqh90dqgrhzgsqc09ek6uejr93z8puafm6gsqgrk0hy","description":"asd","paymentHash":"efe2e64136c683e498872e78746ebd738bb867858107802c3daed86aa819fd9c","expiry":3600,"amount":5000,"features":{"activated":{"var_onion_optin":"optional","payment_secret":"optional"},"unknown":[]}}"""
  }

  test("type hints") {
    val e1 = PaymentSettlingOnChain(UUID.randomUUID, 42 msat, randomBytes32)
    assert(JsonSupport.serialization.writePretty(e1)(JsonSupport.formats).contains("\"type\" : \"payment-settling-onchain\""))
  }

  test("transaction serializer") {
    val tx = Transaction.read("0200000001c8a8934fb38a44b969528252bc37be66ee166c7897c57384d1e561449e110c93010000006b483045022100dc6c50f445ed53d2fb41067fdcb25686fe79492d90e6e5db43235726ace247210220773d35228af0800c257970bee9cf75175d75217de09a8ecd83521befd040c4ca012102082b751372fe7e3b012534afe0bb8d1f2f09c724b1a10a813ce704e5b9c217ccfdffffff0247ba2300000000001976a914f97a7641228e6b17d4b0b08252ae75bd62a95fe788ace3de24000000000017a914a9fefd4b9a9282a1d7a17d2f14ac7d1eb88141d287f7d50800")
    assert(JsonSupport.serialization.write(tx)(JsonSupport.formats) == "{\"txid\":\"3ef63b5d297c9dcf93f33b45b9f102733c36e8ef61da1ccf2bc132a10584be18\",\"tx\":\"0200000001c8a8934fb38a44b969528252bc37be66ee166c7897c57384d1e561449e110c93010000006b483045022100dc6c50f445ed53d2fb41067fdcb25686fe79492d90e6e5db43235726ace247210220773d35228af0800c257970bee9cf75175d75217de09a8ecd83521befd040c4ca012102082b751372fe7e3b012534afe0bb8d1f2f09c724b1a10a813ce704e5b9c217ccfdffffff0247ba2300000000001976a914f97a7641228e6b17d4b0b08252ae75bd62a95fe788ace3de24000000000017a914a9fefd4b9a9282a1d7a17d2f14ac7d1eb88141d287f7d50800\"}")
  }
}
