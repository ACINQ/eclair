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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.payment.Bolt12Invoice._
import fr.acinq.eclair.wire.protocol.OfferCodecs.invoiceTlvCodec
import fr.acinq.eclair.wire.protocol.Offers._
import fr.acinq.eclair.wire.protocol.TlvStream
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, TimestampSecond, randomBytes, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

class Bolt12InvoiceSpec extends AnyFunSuite {
  test("basic invoice for offer") {
    val nodeKey = randomKey()
    val payerKey = randomKey()
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey)
    val request = InvoiceRequest(offer, 10000 msat, 1, Features.empty, payerKey)
    val invoice = Bolt12Invoice(offer, request, hex"013a9e", nodeKey)
    assert(invoice.isValidFor(offer, request))
    assert(Bolt12Invoice.fromString(invoice.toString).toString === invoice.toString)
  }

  test("blinded paths") {
    val nodeKey = PrivateKey(hex"6ea2305d17bbd81c0d86df874ba1665050ce69dfb1a7d1477ea99cf6fac222fd")
    val preimage = hex"48bd00d15c2f3c1b46a4c30388fa65bf640cb57d3da9eb7cff79015041f7f359"
    val offerId = ByteVector32(hex"8950e5d976167c299b5c3e29e57f9eca6bd0116a4b20bbe372492e34232af693")
    val payerKey = ByteVector32(hex"a43b329132880d482635cce4cb6731c0c3b9504963a566d2eb8779fa6bec88f0")

    val sessionKey1 = PrivateKey(hex"88030f067cea92a29ad27f5e89d9687190d268a6670382b1c31abff54d5d5caf")
    val route1Nodes = Seq(
      PublicKey(hex"02ffce9a2f37821fe445a9afb2e07ff1eaf1a3b21131aee17709149f1bf44cd545"),
      PublicKey(hex"02ef14a0b28e9f808901e7e9874a16d49384418d8d47f777968f0b3baefe3c2350"),
      PublicKey(hex"03a7914c3b9a040fdfc25e78de6bea682bc85ba73080560dbe8e8c2176df67e5eb"))
    val route1Payloads = Seq(
      hex"4e83d615d5868797827c40d72fe076a1c263cf2d0a58dc53fdbfbee1fd7cd6c2fc8f084822426784db9e74",
      hex"dce469194f9cb686358b025e38560cffcf8b7874cdcbe88fce27d87d8b57e0738f14fde5ad1b3ac4f27c348a7d7ab985e93d6932443b40d88cd2bd6024cf2be2ce739490264c841fa7d00cc2c5ed3d89a194",
      hex"8c522a7a19a0ab687709b9279a8b61f6f7c8407012c3987a4100b4b1844632d60c0550190209dee0b0afe27fddb3501583564b2103f35c0502f4aa1165c70205",
    )
    val route1 = RouteBlinding.create(sessionKey1, route1Nodes, route1Payloads)
    val payInfos1 = Seq(PayInfo(1000 msat, 600, CltvExpiryDelta(144), Features.empty), PayInfo(800 msat, 52, CltvExpiryDelta(300), Features.empty))
    val capacity1 = 2000000000 msat

    val sessionKey2 = PrivateKey(hex"a9cde7e58044b7110c93608b265ab680141ece6807339c90c8cafdcb3113aed0")
    val route2Nodes = Seq(
      PublicKey(hex"03ac8ae7a75db23b77e3df65df7dd3911d7d4b782410f154e1c5953c52ca0e80a0"),
      PublicKey(hex"0226fc83cd2279aeca5d90ebb339357d00223c6ee39e1ce41bc27193b63d24f5de"))
    val route2Payloads = Seq(
      hex"4e83d615d5868797827c40d72fe076a1c263cf2d0a58dc53fdbfbee1fd7cd6c2fc8f084822426784db9e74",
      hex"dce469194f9cb686358b025e38560cffcf8b7874cdcbe88fce27d87d8b57e0738f14fde5ad1b3ac4f27c348a7d7ab985e93d6932443b40d88cd2bd6024cf2be2ce739490264c841fa7d00cc2c5ed3d89a194",
    )
    val route2 = RouteBlinding.create(sessionKey2, route2Nodes, route2Payloads)
    val payInfos2 = Seq(PayInfo(1000 msat, 399, CltvExpiryDelta(64), Features.empty))
    val capacity2 = 150000000 msat

    val tlvs: Seq[InvoiceTlv] = Seq(
      OfferId(offerId),
      Amount(1234000 msat),
      Description("test invoice"),
      Paths(Seq(route1, route2)),
      BlindedPay(payInfos1 ++ payInfos2),
      BlindedCapacities(Seq(capacity1, capacity2)),
      NodeId(nodeKey.publicKey),
      PayerKey(payerKey),
      CreatedAt(TimestampSecond(1643881381L)),
      PaymentHash(Crypto.sha256(preimage)),
    )
    val signature = signSchnorr("lightning" + "invoice" + "signature", rootHash(TlvStream(tlvs), invoiceTlvCodec).get, nodeKey)
    val invoice = Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    val codedDecoded = Bolt12Invoice.fromString(invoice.toString)
    assert(codedDecoded.blindedPaths contains Seq(BlindedPath(route1, payInfos1, Some(capacity1)), BlindedPath(route2, payInfos2, Some(capacity2))))
  }
}
