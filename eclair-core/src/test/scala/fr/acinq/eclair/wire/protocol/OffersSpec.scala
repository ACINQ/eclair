/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.protocol.Offers.{InvoiceRequest, Offer}
import fr.acinq.eclair.{Features, MilliSatoshiLong, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

class OffersSpec extends AnyFunSuite {
  val rusty = PublicKey(hex"024b9a1fa8e006f1e3937f65f66c408e6da8e1ca728ea43222a7381df1cc449605")

  test("sign and check offer") {
    val key = randomKey()
    val offer = Offer(Some(100000 msat), "test offer", key.publicKey)
    assert(offer.signature.isEmpty)
    val signedOffer = offer.sign(key)
    assert(signedOffer.checkSignature)
  }

  test("invoice request is signed") {
    val sellerKey = randomKey()
    val offer = Offer(Some(100000 msat), "test offer", sellerKey.publicKey)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 100000 msat, 1, Features.empty, payerKey)
    assert(request.checkSignature)
  }

  test("basic offer") {
    val encoded = "lno1pg257enxv4ezqcneyp+ e82um50ynhxgrwdajx283qfwdpl28+qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczs"
    val offer = Offer.decode(encoded).get
    assert(offer.amount.isEmpty)
    assert(offer.signature.isEmpty)
    assert(offer.description === "Offer by rusty's node")
    assert(offer.nodeId === rusty)
  }

  test("basic signed offer") {
    val encodedSigned = "lno1pg257enxv4ezqcneype82um50ynhxgrwdajx283qfwdp+  l28qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczlqs85ck65ycmkdk92smwt9zuewdzfe7v4aavvaz5kgv9mkk63v3s0ge0f099kssh3yc95qztx504hu92hnx8ctzhtt08pgk0texz0509tk"
    val signedOffer = Offer.decode(encodedSigned).get
    assert(signedOffer.checkSignature)
    assert(signedOffer.amount.isEmpty)
    assert(signedOffer.description === "Offer by rusty's node")
    assert(signedOffer.nodeId === rusty)
  }

  test("offer with amount and quantity") {
    val encoded = "l+no1pqqnyzsmx5cx6umpwssx6atvw35j6ut4v9h8g6t50ysx7enxv4epgrmjw4ehgcm0wfczucm0d5hxzagkqyq3ugztng063cqx783exlm97ekyprnd4rsu5u5w5sez9fecrhcuc3ykq5"
    val offer = Offer.decode(encoded).get
    assert(offer.amount contains 50.msat)
    assert(offer.signature.isEmpty)
    assert(offer.description === "50msat multi-quantity offer")
    assert(offer.nodeId === rusty)
    assert(offer.issuer contains "rustcorp.com.au")
    assert(offer.quantityMin contains 1)
  }

  test("signed offer with amount and quantity") {
    val encodedSigned = "lno1pqqnyzsmx5cx6umpwssx6atvw35j6ut4v9h8g6t50ysx7enxv4epgrmjw4ehgcm0wfczucm0d5hxzagkqyq3ugztng063cqx783exlm97ekyprnd4rsu5u5w5sez9fecrhcuc3ykqhcypjju7unu05vav8yvhn27lztf46k9gqlga8uvu4uq62kpuywnu6me8srgh2q7puczukr8arectaapfl5d4rd6uc7st7tnqf0ttx39n40s"
    val signedOffer = Offer.decode(encodedSigned).get
    assert(signedOffer.checkSignature)
    assert(signedOffer.amount contains 50.msat)
    assert(signedOffer.description === "50msat multi-quantity offer")
    assert(signedOffer.nodeId === rusty)
    assert(signedOffer.issuer contains "rustcorp.com.au")
    assert(signedOffer.quantityMin contains 1)
  }
}
