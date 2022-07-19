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

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, VariableLengthOnion}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Bolt12Invoice.{hrp, signatureTag}
import fr.acinq.eclair.wire.protocol.OfferCodecs.{invoiceRequestTlvCodec, invoiceTlvCodec}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.{GenericTlv, OfferTypes, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, Feature, FeatureSupport, Features, MilliSatoshiLong, TimestampSecond, TimestampSecondLong, UInt64, randomBytes32, randomBytes64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.concurrent.duration.DurationInt
import scala.util.Success

class Bolt12InvoiceSpec extends AnyFunSuite {

  def signInvoiceTlvs(tlvs: TlvStream[InvoiceTlv], key: PrivateKey): TlvStream[InvoiceTlv] = {
    val signature = signSchnorr(Bolt12Invoice.signatureTag("signature"), rootHash(tlvs, invoiceTlvCodec), key)
    tlvs.copy(records = tlvs.records ++ Seq(Signature(signature)))
  }

  def signInvoice(invoice: Bolt12Invoice, key: PrivateKey): Bolt12Invoice = {
    val tlvs = OfferTypes.removeSignature(invoice.records)
    val signedInvoice = Bolt12Invoice(signInvoiceTlvs(tlvs, key), None)
    assert(signedInvoice.checkSignature())
    signedInvoice
  }

  test("check invoice signature") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, CltvExpiryDelta(20), Features.empty, randomBytes32())
    assert(invoice.isValidFor(offer, request))
    assert(invoice.checkSignature())
    assert(!invoice.checkRefundSignature())
    assert(Bolt12Invoice.fromString(invoice.toString).get.toString == invoice.toString)
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
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, CltvExpiryDelta(20), Features.empty, randomBytes32())
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
    val withOtherNodeId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case NodeIdXOnly(_) => NodeIdXOnly(otherNodeKey.publicKey) case x => x }.toSeq), None), otherNodeKey)
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
    assert(request.quantity_opt.isEmpty) // when paying for a single item, the quantity field must not be present
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, CltvExpiryDelta(20), Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Optional), randomBytes32())
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
    val withPayerDetails = Bolt12Invoice(offer, requestWithPayerDetails, randomBytes32(), nodeKey, CltvExpiryDelta(20), Features.empty, randomBytes32())
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
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, CltvExpiryDelta(20), Features.empty, randomBytes32())
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
        NodeIdXOnly(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
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
        NodeIdXOnly(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
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
        NodeIdXOnly(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)), None)
    }
    assert(!invoiceOtherChain.isValidFor(offerBtc, requestBtc))
    val offerOtherChains = Offer(TlvStream(Seq(Chains(Seq(chain1, chain2)), Amount(amount), Description("testnets offer"), NodeIdXOnly(nodeKey.publicKey))))
    val requestOtherChains = InvoiceRequest(offerOtherChains, amount, 1, Features.empty, payerKey, chain1)
    val invoiceOtherChains = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(chain1),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerOtherChains.offerId),
        NodeIdXOnly(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
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
        NodeIdXOnly(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
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

  test("decode simple invoice") {
    val nodeKey = PrivateKey(hex"c6a75116a91dc5ff741b079c32c8ce7544656b98f047fb0c0fa011bfb2bb3c05")
    val payerKey = PrivateKey(hex"7dd30ec116470c5f7f00af2c7e84968e28cdb43083b33ee832decbe73ec07f1a")
    val Success(offer) = Offer.decode("lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqvqcdgq2pd3xzumfvvsx7enxv4epug9kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsuly")
    assert(offer.amount.contains(100_000 msat))
    assert(offer.nodeIdXOnly.xOnly == xOnlyPublicKey(nodeKey.publicKey))
    assert(offer.chains == Seq(Block.TestnetGenesisBlock.hash))
    val request = InvoiceRequest(offer, 100_000 msat, 1, Features.empty, payerKey, Block.TestnetGenesisBlock.hash)
    val Success(invoice) = Bolt12Invoice.fromString("lni1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyp53zuupqkwxpmdq0tjg58ntat5ujpejlvyn92r0l5xzh4wru8e5zzqrqxr2qzstvfshx6tryphkven9wgxqqyycq2mtwr56uje7v560k2zjpmxu9246d43jda9vy8n68289stqh3cw0jqls9hxx3tpkddkun48gvlnefhdkkyhrkycr6dqm77z7qffrvyew8qqsy6tpz4gc080qqrcj59pmfz2n7as9j532qhygpatrw6a4k4u0ctc3qqez8vunpqq4jt2gmtwwgjhzvqs9f80yjvws6g98nsyh64h6wjf84ns48pfkzytgrqz6ld07tl7szug7khd3ug9kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsulynzpdzlpprayc9krw2u80ujq90xh3evhl799gqcvscs5uwyfmv52ux89qzx94kurg4zpdh4kz4j7wvmx3jln9pqmrdryd9kcp98cv99pleghgmqe7ae6scq9cpqq98sgr0pwptagg7dutnx8mfwe7d84vp36f2afkycfqqum5lx3rz3p9heqx52w7zfytsa09mrq293apty6y330jgv322389cu6548twpxpeay")
    assert(invoice.isValidFor(offer, request))
  }

  test("decode invoice with quantity") {
    val nodeKey = PrivateKey(hex"c6a75116a91dc5ff741b079c32c8ce7544656b98f047fb0c0fa011bfb2bb3c05")
    val payerKey = PrivateKey(hex"94c7a21a11efa16c5f73b093dc136d9525e2ff40ea7a958c43c1f6004bf6a676")
    val Success(offer) = Offer.decode("lno1pqpzwyq2pf382mrtyphkven9wgtqzqgcqy9pug9kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsuly")
    assert(offer.amount.contains(10_000 msat))
    assert(offer.nodeIdXOnly.xOnly == xOnlyPublicKey(nodeKey.publicKey))
    assert(offer.chains == Seq(Block.LivenetGenesisBlock.hash))
    val request = InvoiceRequest(offer, 50_000 msat, 5, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    val Success(invoice) = Bolt12Invoice.fromString("lni1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyypl905mjhuzxlljf9vhgrsde2f78eugjgcaekxr97nlkhtqqssn9gzqzcdgq5znzw4kxkgr0venx2usvqqgfsq4kku8f4e9nuef5lv59yrkdc24t5mtrym62cg085w5wtqkp0rsulyp8jup0gy33yx0q4qzvkq82v2u2ms9fsr44m40rtg8a73v5qyrg7vgpqw9pvycg3sf6ppq0nlxj43k36ugcz28nu53wxvg08uummjgr3vgcqqpjdec35dl86njwq5a6lp0vd6tn2nyq095lvy60vl0v57jcq0xu5c7j4qtssfn2uuc27662gq7yyyumtecwrcstddcwntjt8ejnf7eg2g8vms42hfkkxfh54ss70gagukpvz78pe7fqqyzjvg86k747wukskae3t0fwa56d0xl0sz6xhez6rnynym7ut0t9rxzzds5qgckkm3mj5gytaax4mqrj9n37m85fs847yyc7kngwryk8387ggrgd07j5fexershqyqq57pqgjumr63gmrdl2v36ldad2e4s7ae9k4e792alaks3umm0np25jntxxz7sq84udgz7ph3ru4r3xac89mjz6sxyqjq7g9f8p27et76dm25")
    assert(invoice.amount == 50_000.msat)
    assert(invoice.quantity.contains(5))
    assert(invoice.isValidFor(offer, request))
  }

  test("decode invalid invoice") {
    val nodeKey = randomKey()
    val tlvs = Seq[InvoiceTlv](
      Amount(765432 msat),
      Description("minimal invoice"),
      NodeIdXOnly(nodeKey.publicKey),
      Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), randomKey().publicKey, randomBytes32()))),
      CreatedAt(TimestampSecond(123456789L)),
      PaymentHash(randomBytes32()),
    )
    // This minimal invoice is valid.
    val signed = signInvoiceTlvs(TlvStream[InvoiceTlv](tlvs), nodeKey)
    val signedEncoded = Bech32.encodeBytes(hrp, invoiceTlvCodec.encode(signed).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    assert(Bolt12Invoice.fromString(signedEncoded).isSuccess)
    // But removing any TLV makes it invalid.
    for (tlv <- tlvs){
      val incomplete = tlvs.filterNot(_ == tlv)
      val incompleteSigned = signInvoiceTlvs(TlvStream[InvoiceTlv](incomplete), nodeKey)
      val incompleteSignedEncoded = Bech32.encodeBytes(hrp, invoiceTlvCodec.encode(incompleteSigned).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
      assert(Bolt12Invoice.fromString(incompleteSignedEncoded).isFailure)
    }
    // Missing signature is also invalid.
    val unsignedEncoded = Bech32.encodeBytes(hrp, invoiceTlvCodec.encode(TlvStream[InvoiceTlv](tlvs)).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    assert(Bolt12Invoice.fromString(unsignedEncoded).isFailure)
  }

  test("encode/decode invoice with many fields") {
    val chain = Block.TestnetGenesisBlock.hash
    val offerId = ByteVector32.fromValidHex("8bc5978de5d625c90136dfa896a8a02cef33c5457027684687e3f98e0cfca4f0")
    val amount = 123456 msat
    val description = "invoice with many fields"
    val features = Features[Feature](Features.VariableLengthOnion -> FeatureSupport.Mandatory)
    val issuer = "acinq.co"
    val nodeKey = PrivateKey(hex"998cf8ecab46f949bb960813b79d3317cabf4193452a211795cd8af1b9a25d90")
    val path = Sphinx.RouteBlinding.createDirect(PrivateKey(hex"f0442c17bdd2cefe4a4ede210f163b068bb3fea6113ffacea4f322de7aa9737b"), nodeKey.publicKey, hex"76030536ba732cdc4e7bb0a883750bab2e88cb3dddd042b1952c44b4849c86bb")
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
      NodeIdXOnly(nodeKey.publicKey),
      Paths(Seq(path)),
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
    assert(invoice.toString == "lni1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyz9ut9uduhtztjgpxm06394g5qkw7v79g4czw6zxsl3lnrsvljj0qzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqgqsqyycq0zxw03kpc8tc2vv3kfdne0kntqhq8p70wtdncwq2zngaqp529mmcq5ecw92k3597h7kdndc64mg2xt709acf2gmxnnag5kq9a6wslznscqsyu5p4eckl7m69k0qpcppkpz3lq4chus9szjkgw9w7mgeknz7m7fpqqe02qmqdj08z62mz0jws0gxt45fyq8udel9jg5gd6xlgdrkdt5qywp0jsc93kcksk4x4yvk7s3dej984yh3gzrpvd5kuufwvdh3ugxyvulrvrswhs5cervjm8jldxkpwqwru7ukm8suq59x36qrg5thhssqzwfxyz864ht3k8mck93xtedsvxua9wygjyqjm3ad8p3xa6429gn3v9dx2fcvfynk6gr5dpjjq6mfdenjsprz5qrtu23q2x236nzneyzqxhct9y7unhcupeukwgf5xzhq0f0nuy6v6vej2dqjcqswzqhqyqrmxq2qwpqqqsfr64hcpvrqqz8t8twx39z77cqnyr9fadh9ym4vt8xehz0myquzquddqvl97ssxsgjkppmslfm8y5z5f9p9md2r58uuywlsxet65d7p7pq94u2jf9d7uz8xq064860j5578f26l3jv2thhh7q6knxnsk33djhyqwwhm4xktsrc69sjfegtsaxq4c0pdhw5kfr55z4q8vkutrgwskt7szdcrqypq8lgp8yqq")
    val Success(codedDecoded) = Bolt12Invoice.fromString(invoice.toString)
    assert(codedDecoded.chain == chain)
    assert(codedDecoded.offerId.contains(offerId))
    assert(codedDecoded.amount == amount)
    assert(codedDecoded.description == Left(description))
    assert(codedDecoded.features == features)
    assert(codedDecoded.issuer.contains(issuer))
    assert(codedDecoded.nodeId.value.drop(1) == nodeKey.publicKey.value.drop(1))
    assert(codedDecoded.blindedPaths == Seq(path))
    assert(codedDecoded.quantity.contains(quantity))
    assert(codedDecoded.payerKey.contains(payerKey))
    assert(codedDecoded.payerNote.contains(payerNote))
    assert(codedDecoded.payerInfo.contains(payerInfo))
    assert(codedDecoded.createdAt == createdAt)
    assert(codedDecoded.paymentHash == paymentHash)
    assert(codedDecoded.relativeExpiry == relativeExpiry.seconds)
    assert(codedDecoded.minFinalCltvExpiryDelta == cltv)
    assert(codedDecoded.fallbacks.contains(fallbacks))
    assert(codedDecoded.replaceInvoice.contains(replaceInvoice))
    assert(codedDecoded.records.unknown.toSet == Set(GenericTlv(UInt64(311), hex"010203"), GenericTlv(UInt64(313), hex"")))
  }

}
