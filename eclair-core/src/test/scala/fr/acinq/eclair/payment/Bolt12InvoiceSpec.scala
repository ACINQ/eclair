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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, VariableLengthOnion}
import fr.acinq.eclair.payment.Bolt12Invoice.signatureTag
import fr.acinq.eclair.wire.protocol.OfferCodecs.{invoiceRequestTlvCodec, invoiceTlvCodec}
import fr.acinq.eclair.wire.protocol.Offers._
import fr.acinq.eclair.wire.protocol.{GenericTlv, Offers, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, Feature, FeatureSupport, Features, MilliSatoshiLong, TimestampSecond, TimestampSecondLong, UInt64, randomBytes32, randomBytes64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.concurrent.duration.DurationInt
import scala.util.Success

class Bolt12InvoiceSpec extends AnyFunSuite {

  def signInvoice(invoice: Bolt12Invoice, key: PrivateKey): Bolt12Invoice = {
    val tlvs = Offers.removeSignature(invoice.records)
    val signature = signSchnorr(Bolt12Invoice.signatureTag("signature"), rootHash(tlvs, invoiceTlvCodec), key)
    val signedInvoice = Bolt12Invoice(tlvs.copy(records = tlvs.records ++ Seq(Signature(signature))), None)
    assert(signedInvoice.checkSignature())
    signedInvoice
  }

  test("check invoice signature") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, Features.empty)
    assert(invoice.isValidFor(offer, request))
    assert(invoice.checkSignature())
    assert(!invoice.checkRefundSignature())
    assert(Bolt12Invoice.fromString(invoice.toString).get.toString === invoice.toString)
    // changing signature makes check fail
    val withInvalidSignature = Bolt12Invoice(TlvStream(invoice.records.records.map { case Signature(_) => Signature(randomBytes64()) case x => x }, invoice.records.unknown), None)
    assert(!withInvalidSignature.checkSignature())
    assert(!withInvalidSignature.isValidFor(offer, request))
    assert(!withInvalidSignature.checkRefundSignature())
    // changing fields makes the signature invalid
    val withModifiedUnknownTlv = Bolt12Invoice(invoice.records.copy(unknown = Seq(GenericTlv(UInt64(7), hex"ade4"))), None)
    assert(!withModifiedUnknownTlv.checkSignature())
    assert(!withModifiedUnknownTlv.isValidFor(offer, request))
    assert(!withModifiedUnknownTlv.checkRefundSignature())
    val withModifiedAmount = Bolt12Invoice(TlvStream(invoice.records.records.map { case Amount(amount) => Amount(amount + 100.msat) case x => x }, invoice.records.unknown), None)
    assert(!withModifiedAmount.checkSignature())
    assert(!withModifiedAmount.isValidFor(offer, request))
    assert(!withModifiedAmount.checkRefundSignature())
  }

  test("check that invoice matches offer") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, Features.empty)
    assert(invoice.isValidFor(offer, request))
    assert(!invoice.isValidFor(Offer(None, "test offer", randomKey().publicKey, Features.empty, chain), request))
    // amount must match the offer
    val withOtherAmount = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Amount(_) => Amount(9000 msat) case x => x }.toSeq), None), nodeKey)
    assert(!withOtherAmount.isValidFor(offer, request))
    // description must match the offer, may have appended info
    val withOtherDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Description(_) => Description("other description") case x => x }.toSeq), None), nodeKey)
    assert(!withOtherDescription.isValidFor(offer, request))
    val withExtendedDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Description(_) => Description("test offer + more") case x => x }.toSeq), None), nodeKey)
    assert(withExtendedDescription.isValidFor(offer, request))
    // nodeId must match the offer
    val otherNodeKey = randomKey()
    val withOtherNodeId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case NodeId(_) => NodeId(otherNodeKey.publicKey) case x => x }.toSeq), None), otherNodeKey)
    assert(!withOtherNodeId.isValidFor(offer, request))
    // offerId must match the offer
    val withOtherOfferId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferId(_) => OfferId(randomBytes32()) case x => x }.toSeq), None), nodeKey)
    assert(!withOtherOfferId.isValidFor(offer, request))
    // issuer must match the offer
    val withOtherIssuer = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records ++ Seq(Issuer("spongebob"))), None), nodeKey)
    assert(!withOtherIssuer.isValidFor(offer, request))
  }

  test("check that invoice matches invoice request") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(15000 msat), "test offer", nodeKey.publicKey, Features(VariableLengthOnion -> Mandatory), chain)
    val request = InvoiceRequest(offer, 15000 msat, 1, Features(VariableLengthOnion -> Mandatory), payerKey, chain)
    assert(request.quantity_opt === None) // when paying for a single item, the quantity field must not be present
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Optional))
    assert(invoice.isValidFor(offer, request))
    val withInvalidFeatures = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case FeaturesTlv(_) => FeaturesTlv(Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Mandatory)) case x => x }.toSeq), None), nodeKey)
    assert(!withInvalidFeatures.isValidFor(offer, request))
    val withAmountTooBig = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Amount(_) => Amount(20000 msat) case x => x }.toSeq), None), nodeKey)
    assert(!withAmountTooBig.isValidFor(offer, request))
    val withQuantity = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ Quantity(2)), None), nodeKey)
    assert(!withQuantity.isValidFor(offer, request))
    val withOtherPayerKey = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case PayerKey(_) => PayerKey(randomBytes32()) case x => x }.toSeq), None), nodeKey)
    assert(!withOtherPayerKey.isValidFor(offer, request))
    val withPayerNote = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ PayerNote("I am Batman")), None), nodeKey)
    assert(!withPayerNote.isValidFor(offer, request))
    val withPayerInfo = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ PayerInfo(hex"010203040506")), None), nodeKey)
    assert(!withPayerInfo.isValidFor(offer, request))
    // Invoice request with more details about the payer.
    val requestWithPayerDetails = {
      val tlvs: Seq[InvoiceRequestTlv] = Seq(
        OfferId(offer.offerId),
        Amount(15000 msat),
        PayerKey(payerKey.publicKey),
        PayerInfo(hex"010203040506"),
        PayerNote("I am Batman"),
        FeaturesTlv(Features(VariableLengthOnion -> Mandatory))
      )
      val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), invoiceRequestTlvCodec), payerKey)
      InvoiceRequest(TlvStream(tlvs :+ Signature(signature)))
    }
    val withPayerDetails = Bolt12Invoice(offer, requestWithPayerDetails, randomBytes32(), nodeKey, Features.empty)
    assert(withPayerDetails.isValidFor(offer, requestWithPayerDetails))
    assert(!withPayerDetails.isValidFor(offer, request))
    val withOtherPayerInfo = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map { case PayerInfo(_) => PayerInfo(hex"deadbeef") case x => x }.toSeq), None), nodeKey)
    assert(!withOtherPayerInfo.isValidFor(offer, requestWithPayerDetails))
    assert(!withOtherPayerInfo.isValidFor(offer, request))
    val withOtherPayerNote = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map { case PayerNote(_) => PayerNote("Or am I Bruce Wayne?") case x => x }.toSeq), None), nodeKey)
    assert(!withOtherPayerNote.isValidFor(offer, requestWithPayerDetails))
    assert(!withOtherPayerNote.isValidFor(offer, request))
  }

  test("check invoice expiry") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(5000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 5000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, Features.empty)
    assert(!invoice.isExpired())
    assert(invoice.isValidFor(offer, request))
    val expiredInvoice1 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case CreatedAt(_) => CreatedAt(0 unixsec) case x => x }), None), nodeKey)
    assert(expiredInvoice1.isExpired())
    assert(!expiredInvoice1.isValidFor(offer, request)) // when an invoice is expired, we mark it as invalid as well
    val expiredInvoice2 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case CreatedAt(_) => CreatedAt(TimestampSecond.now() - 2000) case x => x } ++ Seq(RelativeExpiry(1800))), None), nodeKey)
    assert(expiredInvoice2.isExpired())
    assert(!expiredInvoice2.isValidFor(offer, request)) // when an invoice is expired, we mark it as invalid as well
  }

  test("check chain compatibility") {
    val amount = 5000 msat
    val (nodeKey, payerKey) = (randomKey(), randomKey())
    val (chain1, chain2) = (randomBytes32(), randomBytes32())
    val offerBtc = Offer(Some(amount), "bitcoin offer", nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val requestBtc = InvoiceRequest(offerBtc, amount, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    val invoiceImplicitBtc = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerBtc.offerId),
        NodeId(nodeKey.publicKey),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)), None)
    }
    assert(invoiceImplicitBtc.isValidFor(offerBtc, requestBtc))
    val invoiceExplicitBtc = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(Block.LivenetGenesisBlock.hash),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerBtc.offerId),
        NodeId(nodeKey.publicKey),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)), None)
    }
    assert(invoiceExplicitBtc.isValidFor(offerBtc, requestBtc))
    val invoiceOtherChain = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(chain1),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerBtc.offerId),
        NodeId(nodeKey.publicKey),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)), None)
    }
    assert(!invoiceOtherChain.isValidFor(offerBtc, requestBtc))
    val offerOtherChains = Offer(TlvStream(Seq(Chains(Seq(chain1, chain2)), Amount(amount), Description("testnets offer"), NodeId(nodeKey.publicKey))))
    val requestOtherChains = InvoiceRequest(offerOtherChains, amount, 1, Features.empty, payerKey, chain1)
    val invoiceOtherChains = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(chain1),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerOtherChains.offerId),
        NodeId(nodeKey.publicKey),
        Amount(amount),
        Description(offerOtherChains.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)), None)
    }
    assert(invoiceOtherChains.isValidFor(offerOtherChains, requestOtherChains))
    val invoiceInvalidOtherChain = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(chain2),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerOtherChains.offerId),
        NodeId(nodeKey.publicKey),
        Amount(amount),
        Description(offerOtherChains.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)), None)
    }
    assert(!invoiceInvalidOtherChain.isValidFor(offerOtherChains, requestOtherChains))
    val invoiceMissingChain = signInvoice(Bolt12Invoice(TlvStream(invoiceOtherChains.records.records.filter { case Chain(_) => false case _ => true }), None), nodeKey)
    assert(!invoiceMissingChain.isValidFor(offerOtherChains, requestOtherChains))
  }

  test("decode invoice") {
    val nodeKey = PrivateKey(hex"c6a75116a91dc5ff741b079c32c8ce7544656b98f047fb0c0fa011bfb2bb3c05")
    val payerKey = PrivateKey(hex"7dd30ec116470c5f7f00af2c7e84968e28cdb43083b33ee832decbe73ec07f1a")
    val Success(offer) = Offer.decode("lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqvqcdgq2pd3xzumfvvsx7enxv4epug9kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsuly")
    assert(offer.amount === Some(100_000 msat))
    assert(offer.nodeIdXOnly === xOnlyPublicKey(nodeKey.publicKey))
    assert(offer.chains === Seq(Block.TestnetGenesisBlock.hash))
    val request = InvoiceRequest(offer, 100_000 msat, 1, Features.empty, payerKey, Block.TestnetGenesisBlock.hash)
    val Success(invoice) = Bolt12Invoice.fromString("lni1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyp53zuupqkwxpmdq0tjg58ntat5ujpejlvyn92r0l5xzh4wru8e5zzqrqxr2qzstvfshx6tryphkven9wgxqq83qk6msaxhyk0n9xnajs5swehp24wndvvn0ftppu7363evzc9uwrnujvg95tuyy05nqkcdetsaljgq4u6789jllc54qrpjrzzn3c38dj3tscu5qgcs2y4lj5gqlvq50uu7sce478j3j0l599nxfs6svx2cfefgn4a0675893wtzuckqfwlcrcq0qspa9zynlpdk9zzechehkemgaksklylxhr7yfjfx6h696th327nm4nsf52xzq0ukchx69g00c4vvk6kzc5jyklneyy05l9tef7a5jcjn5")
    assert(!invoice.isExpired())
    assert(invoice.isValidFor(offer, request))
  }

  test("decode invoice with quantity") {
    val nodeKey = PrivateKey(hex"c6a75116a91dc5ff741b079c32c8ce7544656b98f047fb0c0fa011bfb2bb3c05")
    val payerKey = PrivateKey(hex"94c7a21a11efa16c5f73b093dc136d9525e2ff40ea7a958c43c1f6004bf6a676")
    val Success(offer) = Offer.decode("lno1pqpzwyq2pf382mrtyphkven9wgtqzqgcqy9pug9kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsuly")
    assert(offer.amount === Some(10_000 msat))
    assert(offer.nodeIdXOnly === xOnlyPublicKey(nodeKey.publicKey))
    assert(offer.chains === Seq(Block.LivenetGenesisBlock.hash))
    val request = InvoiceRequest(offer, 50_000 msat, 5, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    val Success(invoice) = Bolt12Invoice.fromString("lni1qss8u47nw2lsgml7fy4jaqwph9f8cl83zfrrhxccvh6076avqzzzv4qgqtp4qzs2vf6kc6eqdanxvetjpsqpug9kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsulysqzpfxyrat02l8wtgtwuc4h5hw6dxhn0hcpdrtu3dpejfjdlw9h4j3nppxc2qyvg9z0lf2yq7wl9ygd6td4cj7whp3ye4cfxrtu7zq4r2mc0mcdspk3duzv7d0stqyh0upuq8sgr44r7aaluwqfw8pkd9f3cgk7ae2l8rkexznhegr0p7w4mlhvfkvlnr5k2lnw0hhsf6ckys3sst7kng5p7m2pxlvdxl3tan809vkk75j")
    assert(!invoice.isExpired())
    assert(invoice.amount === 50_000.msat)
    assert(invoice.quantity === Some(5))
    assert(invoice.isValidFor(offer, request))
  }

  test("decode invalid invoice") {
    val testCases = Seq(
      // Missing amount.
      "lni1qssqkquyqnwldm8mjekcm7ztejf0dzhwyvh95l5fz06ztnpfm0sgc3c2pf6x2um5yphkven9wgxqq83q2hfdphr3r8x07ej0z0swprnll58z4jlw36wye7kw63ssm95ru8qjvgxj40x4favsm7uue24lhleg7gng2r69g2plgwxm8xmpuw7wmnyh455qgcs29g3j5g8vpnxqvnzwu0sgqc20hdllxzr4qnpu0zge6drn8p3galht8f62uncyqyrhddpcla8pprdxwdkmjmutya0kvnrpeqjqa75sr02wff0le52ydckr8ww09gg4jyvxyma903fhh6v8t4edftg7vw6qz7h0tz20p5wq",
      // Missing node id.
      "lni1qssqkquyqnwldm8mjekcm7ztejf0dzhwyvh95l5fz06ztnpfm0sgc3cgqgfcszs2w3jhxapqdanxvetjpsqzvgxj40x4favsm7uue24lhleg7gng2r69g2plgwxm8xmpuw7wmnyh455qgcs29g3j5g8vpnxqvnzwu0sgqc20hdllxzr4qnpu0zge6drn8p3galht8f62uncypc7vg95clf4z8fklzua72nhavtjp5qp0u7pemgpecypn6q809qr5733c9y0thf6fdnegxleeupddgzgsyszrktay6cjv9cld3wzlw5pq",
      // Missing payment hash.
      "lni1qssqkquyqnwldm8mjekcm7ztejf0dzhwyvh95l5fz06ztnpfm0sgc3cgqgfcszs2w3jhxapqdanxvetjpsqpugz46tgdcugeenlkvncnursgullapc4vhm5wn3x04nk5vyxedqlpcynzp54te420tyxlh8x240al728jy6zs732zs06r3keekc0rhnkue9ad9qzxyz32y0cyqhfql7g2dp8w0s5xc0ccgelq4hrnkgmxxltvdq95rqzpf4e68j60h6dysm3evhnu4rwtrqp3dnekmk9sxklw267axtj6zangxtnfx2pq",
      // Missing description.
      "lni1qssqkquyqnwldm8mjekcm7ztejf0dzhwyvh95l5fz06ztnpfm0sgc3cgqgfcsrqqrcs9t5ksm3c3nn8lve838c8q3ell6r32e0hga8zvlt8dgcgdj6p7rsfxyrf2hn257kgdlwwv42lmlu50yf59paz59ql58rdnnds7808dejt662qyvg9z5ge2yrkqenqxf38w8cyqv98mkllnpp6sfs783yvax3ensc5wlm4n5a9wfuzqjraxg7gnskte8m9lzn2j5r55a4n3nhhfnflzd5953tau0h2auztf9und4psz6p34wx4vsjxwyvc33lezqvm208ntdczqneylzznt0cg",
      // Missing creation date.
      "lni1qssqkquyqnwldm8mjekcm7ztejf0dzhwyvh95l5fz06ztnpfm0sgc3cgqgfcszs2w3jhxapqdanxvetjpsqpugz46tgdcugeenlkvncnursgullapc4vhm5wn3x04nk5vyxedqlpcynzp54te420tyxlh8x240al728jy6zs732zs06r3keekc0rhnkue9ad9gswcrxvqexyaclqsps5lwml7vy82pxrc7y3n568xwrz3mlwkwn54e8sgp22vcpqylq4zcxep5faeysey8ucu6wrfgffphlt457rd9f7tlpnyltlqz0yd9eqjcehyu3zwvear2e4ksx32t3qtek9gucrephdmsk0",
      // Missing signature.
      "lni1qssqkquyqnwldm8mjekcm7ztejf0dzhwyvh95l5fz06ztnpfm0sgc3cgqgfcszs2w3jhxapqdanxvetjpsqpugz46tgdcugeenlkvncnursgullapc4vhm5wn3x04nk5vyxedqlpcynzp54te420tyxlh8x240al728jy6zs732zs06r3keekc0rhnkue9ad9qzxyz32yv4zpmqvesrycnhruzqxznam0lessagyc0rcjxwnguecv280a6e6wjhy",
    )
    for (testCase <- testCases) {
      assert(Bolt12Invoice.fromString(testCase).isFailure, testCase)
    }
  }

  test("encode/decode invoice with many fields") {
    val chain = Block.TestnetGenesisBlock.hash
    val offerId = ByteVector32.fromValidHex("8bc5978de5d625c90136dfa896a8a02cef33c5457027684687e3f98e0cfca4f0")
    val amount = 123456 msat
    val description = "invoice with many fields"
    val features = Features[Feature](Features.VariableLengthOnion -> FeatureSupport.Mandatory)
    val issuer = "acinq.co"
    val nodeKey = PrivateKey(hex"998cf8ecab46f949bb960813b79d3317cabf4193452a211795cd8af1b9a25d90")
    val quantity = 57
    val payerKey = ByteVector32.fromValidHex("8faadd71b1f78b16265e5b061b9d2b88891012dc7ad38626eeaaa2a271615a65")
    val payerNote = "I'm the king"
    val payerInfo = hex"a9eb6e526eac59cd9b89fb20"
    val createdAt = TimestampSecond(1654654654L)
    val paymentHash = ByteVector32.fromValidHex("51951d4c53c904035f0b293dc9df1c0e7967213430ae07a5f3e134cd33325341")
    val relativeExpiry = 3600
    val cltv = CltvExpiryDelta(123)
    val fallbacks = Seq(FallbackAddress(4, hex"123d56f8"), FallbackAddress(6, hex"eb3adc68945ef601"))
    val replaceInvoice = ByteVector32.fromValidHex("71ad033e5f42068225608770fa7672505449425db543a1f9c23bf03657aa37c1")
    val tlvs = TlvStream[InvoiceTlv](Seq(
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
    ), Seq(GenericTlv(UInt64(311), hex"010203"), GenericTlv(UInt64(313), hex"")))
    val signature = signSchnorr(Bolt12Invoice.signatureTag("signature"), rootHash(tlvs, invoiceTlvCodec), nodeKey)
    val invoice = Bolt12Invoice(tlvs.copy(records = tlvs.records ++ Seq(Signature(signature))), None)
    assert(invoice.toString === "lni1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyz9ut9uduhtztjgpxm06394g5qkw7v79g4czw6zxsl3lnrsvljj0qzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqgqsq9qgv93kjmn39e3k783qc3nnudswp67znrydjtv7ta56c9cpc0nmjmv7rszs568gqdz3w77zqqfeycsgl2kawxcl0zckye09kpsmn54c3zgsztw845uxymh24g4zw9s45ef8p3yjwmfqw35x2grtd9hxw2qyv2sqd032ypge282v20ysgq6lpv5nmjwlrs88jeepxsc2upa970snfnfnxff5ztqzpcgzuqsq0vcpgpcyqqzpy02klq9svqqgavadc6y5tmmqzvsv484ku5nw43vumxuflvsrsgr345pnuh6zq6pz2cy8wra8vujs23y5yhd4gwslns3m7qm9023hc8cyq6knwqxzve9r5kpufq3szuhn9f437cj05az5kqnsl9wefhfnwzenf5z68qh5jj48rmku97u0gzdm2wlkuwrylpvqfttdtw972cwdteal6qfhqvqsyqlaqyusq")
    val Success(codedDecoded) = Bolt12Invoice.fromString(invoice.toString)
    assert(codedDecoded.chain === chain)
    assert(codedDecoded.offerId === Some(offerId))
    assert(codedDecoded.amount === amount)
    assert(codedDecoded.description === Left(description))
    assert(codedDecoded.features === features)
    assert(codedDecoded.issuer === Some(issuer))
    assert(codedDecoded.nodeId.value.drop(1) === nodeKey.publicKey.value.drop(1))
    assert(codedDecoded.quantity === Some(quantity))
    assert(codedDecoded.payerKey === Some(payerKey))
    assert(codedDecoded.payerNote === Some(payerNote))
    assert(codedDecoded.payerInfo === Some(payerInfo))
    assert(codedDecoded.createdAt === createdAt)
    assert(codedDecoded.paymentHash === paymentHash)
    assert(codedDecoded.relativeExpiry === relativeExpiry.seconds)
    assert(codedDecoded.minFinalCltvExpiryDelta === cltv)
    assert(codedDecoded.fallbacks === Some(fallbacks))
    assert(codedDecoded.replaceInvoice === Some(replaceInvoice))
    assert(codedDecoded.records.unknown.toSet === Set(GenericTlv(UInt64(311), hex"010203"), GenericTlv(UInt64(313), hex"")))
  }

}
