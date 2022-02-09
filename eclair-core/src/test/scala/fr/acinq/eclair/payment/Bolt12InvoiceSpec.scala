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
import fr.acinq.eclair.wire.protocol.{GenericTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, FeatureScope, FeatureSupport, Features, InvoiceFeature, MilliSatoshiLong, TimestampSecond, UInt64, randomBytes, randomBytes32, randomBytes64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.concurrent.duration.DurationInt

class Bolt12InvoiceSpec extends AnyFunSuite {
  def signInvoice(invoice: Bolt12Invoice, key: PrivateKey): Bolt12Invoice = {
    val tlvs = invoice.records.records.filter { case _: Signature => false case _ => true }.toSeq
    val signature = signSchnorr("lightning" + "invoice" + "signature", rootHash(TlvStream(tlvs), invoiceTlvCodec).get, key)
    val signedInvoice = Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    assert(signedInvoice.checkSignature())
    signedInvoice
  }

  test("basic invoice for offer") {
    val nodeKey = randomKey()
    val payerKey = randomKey()
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey)
    val request = InvoiceRequest(offer, 10000 msat, 1, Features.empty, payerKey)
    val invoice = Bolt12Invoice(offer, request, hex"013a9e", nodeKey, Features.empty)
    assert(invoice.isValidFor(offer, request))
    assert(Bolt12Invoice.fromString(invoice.toString).toString === invoice.toString)
    // changing first byte of node id doesn't change anything
    assert(invoice.withNodeId(PublicKey(hex"02" ++ nodeKey.publicKey.value.drop(1))).isValidFor(offer, request))
    assert(invoice.withNodeId(PublicKey(hex"03" ++ nodeKey.publicKey.value.drop(1))).isValidFor(offer, request))
    // changing signature makes check fail
    assert(!Bolt12Invoice(TlvStream(invoice.records.records.map { case Signature(_) => Signature(randomBytes64()) case x => x }, invoice.records.unknown)).isValidFor(offer, request))
    // changing TLVs makes the signature invalid
    assert(!Bolt12Invoice(invoice.records.copy(unknown = Seq(GenericTlv(UInt64(7), hex"ade4")))).checkSignature())
    // invoice is not valid for another offer
    val otherOffer = Offer(Some(10000 msat), "other offer", nodeKey.publicKey)
    assert(!invoice.isValidFor(otherOffer, request))
    // chain must be the compatible with offer
    val withOtherChain = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map{case Chain(_) => Chain(randomBytes32()) case x => x}.toSeq)), nodeKey)
    assert(!withOtherChain.isValidFor(offer, request))
    // invoice is not expired
    val expired = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map{case CreatedAt(_) => CreatedAt(TimestampSecond(1644400000)) case x => x}.toSeq)), nodeKey)
    assert(!expired.isValidFor(offer, request))
    // amount is unchanged
    val withOtherAmount = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map{case Amount(_) => Amount(10001 msat) case x => x}.toSeq)), nodeKey)
    assert(!withOtherAmount.isValidFor(offer, request))
    // quantity is unchanged
    val withOtherQuantity = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ Quantity(2))), nodeKey)
    assert(!withOtherQuantity.isValidFor(offer, request))
    // payer key is unchanged
    val withOtherPayerKey = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map{case PayerKey(_) => PayerKey(randomBytes32()) case x => x}.toSeq)), nodeKey)
    assert(!withOtherPayerKey.isValidFor(offer, request))
    // payer info is unchanged
    val withOtherPayerInfo = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ PayerInfo(hex"ab12cd34"))), nodeKey)
    assert(!withOtherPayerInfo.isValidFor(offer, request))
    // payer note is unchanged
    val withOtherPayerNote = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ PayerNote("some note"))), nodeKey)
    assert(!withOtherPayerNote.isValidFor(offer, request))
    // description is unchanged
    val withOtherDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map{case Description(_) => Description("other description") case x => x}.toSeq)), nodeKey)
    assert(!withOtherDescription.isValidFor(offer, request))
    // issuer is unchanged
    val withOtherIssuer = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ Issuer("issuer"))), nodeKey)
    assert(!withOtherIssuer.isValidFor(offer, request))
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

  test("decode basic example"){
    val payerKey = PrivateKey(hex"7dd30ec116470c5f7f00af2c7e84968e28cdb43083b33ee832decbe73ec07f1a")
    val offer = Offer.decode("lno1pqpsrp4qpg9kyctnd93jqmmxvejhy83qe5e4y9yzexdr0s30py2syuq3j92guhu0wvf35f9wjmk2pddkxkss").get
    assert(offer.amount contains 100000.msat)
    val request = InvoiceRequest(offer, 100000 msat, 1, Features.empty, payerKey)
    val invoice = Bolt12Invoice.fromString("lni1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyyrz7ze6re3x8hrfdh4l760fdf7fm0np2c7z0zcsaldxx6fkcl82twzqrqxr2qzstvfshx6tryphkven9wgxqq83qe5e4y9yzexdr0s30py2syuq3j92guhu0wvf35f9wjmk2pddkxksjvg95tuyy05nqkcdetsaljgq4u6789jllc54qrpjrzzn3c38dj3tscu5qgcs99vjz5gxc4efaur5khh7wx5arx5fwgveq5as02kd9h2qc3e57vqagpcenp8cypcryysqs94wjlhh2ykhsrt3spf4urc9te5ga37rr3msq4u65rd3l7h9qkzmu96xsewhdvn4rl7yavdav2l8rgh4e45h7m6fua48npfjq")
    assert(invoice.isValidFor(offer, request))
  }

  test("decode example with quantity") {
    val payerKey = PrivateKey(hex"94c7a21a11efa16c5f73b093dc136d9525e2ff40ea7a958c43c1f6004bf6a676")
    val offer = Offer.decode("lno1pqpq05q2pd3827fqwdjhvetjv9kpvqgprcsqjqpsff9z90nppymwmu54n4z7nlfhu2ceynalcp0l7f8zmnhd5gq").get
    val request = InvoiceRequest(offer, 10000 msat, 5, Features.empty, payerKey)
    val invoice = Bolt12Invoice.fromString("lni1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyyru3vrzzw9surprhjnxvrx06d3vxgucww892rpt7nr23vw4qz5ckzzqzyugq5zmzw4ujqum9wejhyctvpsqpugqfqqcy5j3zhessjdhd722e630fl5m79vvjf7luqhllyn3demk6yqsqzpfxyrat02l8wtgtwuc4h5hw6dxhn0hcpdrtu3dpejfjdlw9h4j3nppxc2qyvgznp432yzjx4qx78glzqntj2pswm62gvsdjams7tnc7dnncl4weq89l4tertuzq8x7htc63gjp3g8lx5jz3wlw4tu456huetayp05s5m246pyryuk3arhvu4vg87y67g3gzta79vx2gxurhtl09xasmwf8wsmrwcpy7qfs")
    assert(invoice.isValidFor(offer, request))
  }

  test("invoice with many fields") {
    val chain = randomBytes32()
    val offerId = randomBytes32()
    val amount = 123456 msat
    val description = "invoice with many fields"
    val features = Features[FeatureScope](Features.VariableLengthOnion -> FeatureSupport.Mandatory)
    val issuer = "acinq.co"
    val nodeKey = randomKey()
    val quantity = 57
    val payerKey = randomBytes32()
    val payerNote = "I'm the king"
    val payerInfo = randomBytes(12)
    val createdAt = TimestampSecond(1654654654L)
    val paymentHash = Crypto.sha256(randomBytes(42))
    val relativeExpiry = 3600
    val cltv = CltvExpiryDelta(123)
    val fallbacks = Seq(FallbackAddress(4, hex"123d56f8"), FallbackAddress(6, hex"eb3adc68945ef601"))
    val replaceInvoice = randomBytes32()
    val tlvs: Seq[InvoiceTlv] = Seq(
      Chain(chain),
      OfferId(offerId),
      Amount(amount),
      Description(description),
      FeaturesTlv(features),
      Issuer(issuer),
      NodeId(nodeKey.publicKey),
      Quantity(quantity),
      PayerKey(payerKey),
      PayerNote(payerNote),
      PayerInfo(payerInfo),
      CreatedAt(createdAt),
      PaymentHash(paymentHash),
      RelativeExpiry(relativeExpiry),
      Cltv(cltv),
      Fallbacks(fallbacks),
      ReplaceInvoice(replaceInvoice)
    )
    val signature = signSchnorr("lightning" + "invoice" + "signature", rootHash(TlvStream(tlvs), invoiceTlvCodec).get, nodeKey)
    val invoice = Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    println(invoice)
    val codedDecoded = Bolt12Invoice.fromString(invoice.toString)
    assert(codedDecoded.chain === chain)
    assert(codedDecoded.offerId contains offerId)
    assert(codedDecoded.amount === amount)
    assert(codedDecoded.description === Left(description))
    assert(codedDecoded.features === features)
    assert(codedDecoded.issuer contains issuer)
    assert(codedDecoded.nodeId.value.drop(1) === nodeKey.publicKey.value.drop(1))
    assert(codedDecoded.quantity contains quantity)
    assert(codedDecoded.payerKey contains payerKey)
    assert(codedDecoded.payerNote contains payerNote)
    assert(codedDecoded.payerInfo contains payerInfo)
    assert(codedDecoded.createdAt === createdAt)
    assert(codedDecoded.paymentHash === paymentHash)
    assert(codedDecoded.relativeExpiry === relativeExpiry.seconds)
    assert(codedDecoded.minFinalCltvExpiryDelta contains cltv)
    assert(codedDecoded.fallbacks contains fallbacks)
    assert(codedDecoded.replaceInvoice contains replaceInvoice)
  }

  test("decode invoice with many fields") {
    val invoice = Bolt12Invoice.fromString("lni1qvsr3nyx0krgpwu3sa2j527gm4xc5d72w3jur897rflf8a5tggxzgkqyyr7gckpy50dt2rhaak9f2m9j9d4tf2squ05dh85qp20t5md09j42yzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqgqsq9qgv93kjmn39e3k783qgrrfvrr9atlvx33fc6097ns0qenlxqh8gfyzknk8uaut6mf4eupjqqfeycspvv8npcvff9jxyler9yf2cjk9zanw0ck82pmf0swkkpjgmwjne338p3yjwmfqw35x2grtd9hxw2qyv2sqd032yrqfalsl5tfm46pw47ava6dvhsycj8r03e0pcdtes8y4yqucdjtcztqzpcgzuqsq0vcp2qs8qsqqgy3a2muqkpsqpr4n4hrgj300vqfjpjem474mzw5y8xxgdgk9gwpqg7xs3rkuxuuzlhq8putp4ju5g9qpfjhx9esg6w247vywjkatevllqsysyarrnyrffeuhlwxklhs0q84zltw8g9fpykf0g2h4rhgfk5nwqckvhc7j5063earuvp0zj0538pt225x7ua3a3eq225wxc6xqs9vfw")
    assert(invoice.chain === ByteVector32(hex"38cc867d8680bb9187552a2bc8dd4d8a37ca7465c19cbe1a7e93f68b420c2458"))
    assert(invoice.offerId contains ByteVector32(hex"fc8c5824a3dab50efded8a956cb22b6ab4aa00e3e8db9e800a9eba6daf2caaa2"))
    assert(invoice.amount === 123456.msat)
    assert(invoice.description === Left("invoice with many fields"))
    assert(invoice.features === Features[InvoiceFeature](Features.VariableLengthOnion -> FeatureSupport.Mandatory))
    assert(invoice.issuer contains "acinq.co")
    assert(invoice.nodeId.value.drop(1) === hex"40c6960c65eafec34629c69e5f4e0f0667f302e742482b4ec7e778bd6d35cf03")
    assert(invoice.quantity contains 57)
    assert(invoice.payerKey contains ByteVector32(hex"1630f30e1894964627f232912ac4ac51766e7e2c7507697c1d6b0648dba53cc6"))
    assert(invoice.payerNote contains "I'm the king")
    assert(invoice.payerInfo contains hex"b3bafabb13a84398c86a2c54")
    assert(invoice.createdAt === TimestampSecond(1654654654L))
    assert(invoice.paymentHash === ByteVector32(hex"c09efe1fa2d3bae82eafbacee9acbc09891c6f8e5e1c357981c95203986c9781"))
    assert(invoice.relativeExpiry === 3600.seconds)
    assert(invoice.minFinalCltvExpiryDelta contains CltvExpiryDelta(123))
    assert(invoice.fallbacks contains Seq(FallbackAddress(4, hex"123d56f8"), FallbackAddress(6, hex"eb3adc68945ef601")))
    assert(invoice.replaceInvoice contains ByteVector32(hex"478d088edc37382fdc070f161acb94414014cae62e608d3955f308e95babcb3f"))
  }
}
