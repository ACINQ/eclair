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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
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
    val signedInvoice = Bolt12Invoice(signInvoiceTlvs(tlvs, key))
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
    val withInvalidSignature = Bolt12Invoice(TlvStream(invoice.records.records.map { case Signature(_) => Signature(randomBytes64()) case x => x }, invoice.records.unknown))
    assert(!withInvalidSignature.checkSignature())
    assert(!withInvalidSignature.isValidFor(offer, request))
    assert(!withInvalidSignature.checkRefundSignature())
    // changing fields makes the signature invalid
    val withModifiedUnknownTlv = Bolt12Invoice(invoice.records.copy(unknown = Seq(GenericTlv(UInt64(7), hex"ade4"))))
    assert(!withModifiedUnknownTlv.checkSignature())
    assert(!withModifiedUnknownTlv.isValidFor(offer, request))
    assert(!withModifiedUnknownTlv.checkRefundSignature())
    val withModifiedAmount = Bolt12Invoice(TlvStream(invoice.records.records.map { case Amount(amount) => Amount(amount + 100.msat) case x => x }, invoice.records.unknown))
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
    val withOtherAmount = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Amount(_) => Amount(9000 msat) case x => x }.toSeq)), nodeKey)
    assert(!withOtherAmount.isValidFor(offer, request))
    // description must match the offer, may have appended info
    val withOtherDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Description(_) => Description("other description") case x => x }.toSeq)), nodeKey)
    assert(!withOtherDescription.isValidFor(offer, request))
    val withExtendedDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Description(_) => Description("test offer + more") case x => x }.toSeq)), nodeKey)
    assert(withExtendedDescription.isValidFor(offer, request))
    // nodeId must match the offer
    val otherNodeKey = randomKey()
    val withOtherNodeId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case NodeId(_) => NodeId(otherNodeKey.publicKey) case x => x }.toSeq)), otherNodeKey)
    assert(!withOtherNodeId.isValidFor(offer, request))
    // offerId must match the offer
    val withOtherOfferId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferId(_) => OfferId(randomBytes32()) case x => x }.toSeq)), nodeKey)
    assert(!withOtherOfferId.isValidFor(offer, request))
    // issuer must match the offer
    val withOtherIssuer = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records ++ Seq(Issuer("spongebob")))), nodeKey)
    assert(!withOtherIssuer.isValidFor(offer, request))
  }

  test("check that invoice matches invoice request") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(15000 msat), "test offer", nodeKey.publicKey, Features(VariableLengthOnion -> Mandatory), chain)
    val request = InvoiceRequest(offer, 15000 msat, 1, Features(VariableLengthOnion -> Mandatory), payerKey, chain)
    assert(request.quantity_opt.isEmpty) // when paying for a single item, the quantity field must not be present
    val invoice = Bolt12Invoice(offer, request, randomBytes32(), nodeKey, CltvExpiryDelta(20), Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Optional), randomBytes32())
    assert(invoice.isValidFor(offer, request))
    val withInvalidFeatures = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case FeaturesTlv(_) => FeaturesTlv(Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Mandatory)) case x => x }.toSeq)), nodeKey)
    assert(!withInvalidFeatures.isValidFor(offer, request))
    val withAmountTooBig = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case Amount(_) => Amount(20000 msat) case x => x }.toSeq)), nodeKey)
    assert(!withAmountTooBig.isValidFor(offer, request))
    val withQuantity = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ Quantity(2))), nodeKey)
    assert(!withQuantity.isValidFor(offer, request))
    val withOtherPayerKey = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case PayerKey(_) => PayerKey(randomBytes32()) case x => x }.toSeq)), nodeKey)
    assert(!withOtherPayerKey.isValidFor(offer, request))
    val withPayerNote = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ PayerNote("I am Batman"))), nodeKey)
    assert(!withPayerNote.isValidFor(offer, request))
    val withPayerInfo = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.toSeq :+ PayerInfo(hex"010203040506"))), nodeKey)
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
    val withOtherPayerInfo = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map { case PayerInfo(_) => PayerInfo(hex"deadbeef") case x => x }.toSeq)), nodeKey)
    assert(!withOtherPayerInfo.isValidFor(offer, requestWithPayerDetails))
    assert(!withOtherPayerInfo.isValidFor(offer, request))
    val withOtherPayerNote = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map { case PayerNote(_) => PayerNote("Or am I Bruce Wayne?") case x => x }.toSeq)), nodeKey)
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
    val expiredInvoice1 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case CreatedAt(_) => CreatedAt(0 unixsec) case x => x })), nodeKey)
    assert(expiredInvoice1.isExpired())
    assert(!expiredInvoice1.isValidFor(offer, request)) // when an invoice is expired, we mark it as invalid as well
    val expiredInvoice2 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case CreatedAt(_) => CreatedAt(TimestampSecond.now() - 2000) case x => x } ++ Seq(RelativeExpiry(1800)))), nodeKey)
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
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
        PaymentPathsInfo(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, amount, Features.empty))),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    }
    assert(invoiceImplicitBtc.isValidFor(offerBtc, requestBtc))
    val invoiceExplicitBtc = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(Block.LivenetGenesisBlock.hash),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerBtc.offerId),
        NodeId(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
        PaymentPathsInfo(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, amount, Features.empty))),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    }
    assert(invoiceExplicitBtc.isValidFor(offerBtc, requestBtc))
    val invoiceOtherChain = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(chain1),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerBtc.offerId),
        NodeId(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
        PaymentPathsInfo(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, amount, Features.empty))),
        Amount(amount),
        Description(offerBtc.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
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
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
        PaymentPathsInfo(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, amount, Features.empty))),
        Amount(amount),
        Description(offerOtherChains.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    }
    assert(invoiceOtherChains.isValidFor(offerOtherChains, requestOtherChains))
    val invoiceInvalidOtherChain = {
      val tlvs: Seq[InvoiceTlv] = Seq(
        Chain(chain2),
        CreatedAt(TimestampSecond.now()),
        PaymentHash(Crypto.sha256(randomBytes32())),
        OfferId(offerOtherChains.offerId),
        NodeId(nodeKey.publicKey),
        Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), nodeKey.publicKey, randomBytes32()))),
        PaymentPathsInfo(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, amount, Features.empty))),
        Amount(amount),
        Description(offerOtherChains.description),
        PayerKey(payerKey.publicKey)
      )
      val signature = signSchnorr(signatureTag("signature"), rootHash(TlvStream(tlvs), invoiceTlvCodec), nodeKey)
      Bolt12Invoice(TlvStream(tlvs :+ Signature(signature)))
    }
    assert(!invoiceInvalidOtherChain.isValidFor(offerOtherChains, requestOtherChains))
    val invoiceMissingChain = signInvoice(Bolt12Invoice(TlvStream(invoiceOtherChains.records.records.filter { case Chain(_) => false case _ => true })), nodeKey)
    assert(!invoiceMissingChain.isValidFor(offerOtherChains, requestOtherChains))
  }

  test("decode invalid invoice") {
    val nodeKey = randomKey()
    val tlvs = Seq[InvoiceTlv](
      Amount(765432 msat),
      Description("minimal invoice"),
      NodeId(nodeKey.publicKey),
      Paths(Seq(Sphinx.RouteBlinding.createDirect(randomKey(), randomKey().publicKey, randomBytes32()))),
      PaymentPathsInfo(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 765432 msat, Features.empty))),
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
    val issuer = "alice"
    val nodeKey = PrivateKey(hex"998cf8ecab46f949bb960813b79d3317cabf4193452a211795cd8af1b9a25d90")
    val path = Sphinx.RouteBlinding.createDirect(PrivateKey(hex"f0442c17bdd2cefe4a4ede210f163b068bb3fea6113ffacea4f322de7aa9737b"), nodeKey.publicKey, hex"76030536ba732cdc4e7bb0a883750bab2e88cb3dddd042b1952c44b4849c86bb")
    val payInfo = PaymentInfo(2345 msat, 765, CltvExpiryDelta(324), 1000 msat, amount, Features.empty)
    val quantity = 57
    val payerKey = ByteVector32.fromValidHex("8faadd71b1f78b16265e5b061b9d2b88891012dc7ad38626eeaaa2a271615a65")
    val payerNote = "I'm Bob"
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
      Paths(Seq(path)),
      PaymentPathsInfo(Seq(payInfo)),
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
    val invoice = Bolt12Invoice(tlvs.copy(records = tlvs.records ++ Seq(Signature(signature))))
    assert(invoice.toString == "lni1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyz9ut9uduhtztjgpxm06394g5qkw7v79g4czw6zxsl3lnrsvljj0qzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqgqsqyycq0zxw03kpc8tc2vv3kfdne0kntqhq8p70wtdncwq2zngaqp529mmcq5ecw92k3597h7kdndc64mg2xt709acf2gmxnnag5kq9a6wslznscqsyu5p4eckl7m69k0qpcppkpz3lq4chus9szjkgw9w7mgeknz7m7fpqqe02qmqdj08z62mz0jws0gxt45fyq8udel9jg5gd6xlgdrkdt5qywp0jsc93kcksk4x4yvk7s3dej984yh3y8sqqqyjjqqqqt7sz3qqqqqqqqqqq05qqqqqqqqqrcjqqqqqqqq5q4skc6trv50zzq7yvulrvrswhs5cervjm8jldxkpwqwru7ukm8suq59x36qrg5thhssqzwfxyz864ht3k8mck93xtedsvxua9wygjyqjm3ad8p3xa6429gn3v9dx2fc8fynk6gzzda3zsprz5qrtu23q2x236nzneyzqxhct9y7unhcupeukwgf5xzhq0f0nuy6v6vej2dqjcqswzqhqyqrmxq2qwpqqqsfr64hcpvrqqz8t8twx39z77cqnyr9fadh9ym4vt8xehz0myquzquddqvl97ssxsgjkppmslfm8y5z5f9p9md2r58uuywlsxet65d7p7pqgrnx80xlmayz6d2kfupl65rk4mh07a6qllgs6cn0qgdqtpzyesh52h62dpegw6a6v2d38lr3jx07yasxr943wq6kuwa9gkuwq6279tl7szdcrqypq8lgp8yqq")
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

  test("minimal tip") {
    val nodeKey = PrivateKey(hex"48c6e5fcf499f50436f54c3b3edecdb0cb5961ca29d74bea5ab764828f08bf47")
    assert(nodeKey.publicKey == PublicKey(hex"024ff5317f051c7f6eac0266c5cceaeb6c5775a940fab9854e47bfebf6bc7a0407"))
    val payerKey = PrivateKey(hex"d817e8896c67d0bcabfdb93da7eb7fc698c829a181f994dd0ad866a8eda745e8")
    assert(payerKey.publicKey == PublicKey(hex"031ef4439f638914de79220483dda32dfb7a431e799a5ce5a7643fbd70b2118e4e"))
    val preimage = ByteVector32(hex"317d1fd8fec5f3ea23044983c2ba2a8043395b2a0790a815c9b12719aa5f1516")
    val offer = Offer(None, "minimal tip", nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val encodedOffer = "lno1pg9k66twd9kkzmpqw35hq83pqf8l2vtlq5w87m4vqfnvtn82adk9wadfgratnp2wg7l7ha4u0gzqw"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 12000000 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    val encodedRequest = "lnr1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyyrfgrkke8dp3jww26jz8zgvhxhdhzgj062ejxecv9uqsdhh2x9lnjzqrkudsqf3qrm6y88mr3y2du7fzqjpamgedldayx8nenfwwtfmy877hpvs33e80qszhudm9rdk99qpnzktv6emdwq3gda2l77c6av7nn542sl3uhzq5yau26508s7n0mf3ztpnwr6f8vlxhjrlhc34w6sehs9jwydpxhxnws"
    assert(request.toString == encodedRequest)
    assert(InvoiceRequest.decode(encodedRequest).get == request)
    assert(request.isValidFor(offer))
    val invoice = Bolt12Invoice(offer, request, preimage, nodeKey, CltvExpiryDelta(22), Features.empty, hex"")
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.isValidFor(offer, request))
    // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
    val encodedInvoice = "lni1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyyrfgrkke8dp3jww26jz8zgvhxhdhzgj062ejxecv9uqsdhh2x9lnjzqrkudsqzstd45ku6tdv9kzqarfwqg8sqj075ch7pgu0ah2cqnxchxw46mv2a66js86hxz5u3ala0mtc7syqupyrp8cxgf355un0gqnfhgqtnde9xqaazd4fcsrd5khlm4rns5v4aqpqd7aj6ja7pmk34s4llfjeju5y2lkuegn2dvjvnktkqvkxz05qvdjgqqjhfax8gmt6fgfunnhj99m3xk60auwvys7qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpdcmqqqqqqqqrcssynl4x9ls28rld6kqyek9en4wkmzhwk55p74es48y00lt76785pq8ycspaazrna3cj9x70y3qfq7a5vklk7jrreue5h895ajrl0tskggcun3gq33ds9tg9gsgtlj8vuulq6aca20u59mx5xzdg84pksgxgnahfamrsnnup8ck4lpwqgqpduzqrvfdar6jwu6c66w93stjn3qute7tgser2geyyadl0p5u0t4p08523c2tk9jlj9l2j5q47qdpta0rqtndwwdsqdfajpskxkrfrcqk4jc"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.payerKey == invoice.payerKey)
    assert(decodedInvoice.chain == invoice.chain)
  }

  test("minimal offer") {
    val nodeKey = PrivateKey(hex"3b7a19e8320bb86431cf92cd7c69cc1dc0181c37d5a09875e4603c4e37d3705d")
    assert(nodeKey.publicKey == PublicKey(hex"03c48ac97e09f3cbbaeb35b02aaa6d072b57726841a34d25952157caca60a1caf5"))
    val payerKey = PrivateKey(hex"0e00a9ef505292f90a0e8a7aa99d31750e885c42a3ef8866dd2bf97919aa3891")
    assert(payerKey.publicKey == PublicKey(hex"033e94f2afd568d128f02ece844ad4a0a1ddf2a4e3a08beb2dba11b3f1134b0517"))
    val preimage = ByteVector32(hex"09ad5e952ec39d45461ebdeceac206fb45574ae9054b5a454dd02c65f5ba1b7c")
    val offer = Offer(Some(456000000 msat), "minimal offer", nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val encodedOffer = "lno1pqzpktszqq9q6mtfde5k6ctvyphkven9wg0zzq7y3tyhuz0newawkdds924x6pet2aexssdrf5je2g2het9xpgw275"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 456001234 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    val encodedRequest = "lnr1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyypmpsc7ww3cxguwl27ela95ykset7t8tlvyfy7a200eujcnhczws6zqyrvhqd53xyqlffu40645dz28s9m8ggjk55zsamu4yuwsgh6edhggm8ugnfvz30uzqjq9tsnv60r570yqfypx3jghrff92qlcjwff0azwatsuehd0vkxxvz2wx07qlurz42ca0r96x6a4xh5h9gpz39w4em3687k6n3w9349g"
    assert(request.toString == encodedRequest)
    assert(InvoiceRequest.decode(encodedRequest).get == request)
    assert(request.isValidFor(offer))
    val invoice = Bolt12Invoice(offer, request, preimage, nodeKey, CltvExpiryDelta(22), Features.empty, hex"747e01a7152169b058a1fbc0024c254077db7e399308483e0c30e2352ba1d6cc")
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.isValidFor(offer, request))
    // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
    val encodedInvoice = "lni1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyypmpsc7ww3cxguwl27ela95ykset7t8tlvyfy7a200eujcnhczws6zqyrvhqd5s2p4kkjmnfd4skcgr0venx2ussnqpufzkf0cyl8ja6av6mq242d5rjk4mjdpq6xnf9j5s40jk2vzsu4agze6zapx6yyjyjxsahmqvurucz66plh2wsvum6el7ff0ugmghn4f7szqlfaudyqmqfvwk3q092y0n7n5zmajxp59t8dl6wpal5wwykj8fkrcqr9xe2umxlml4myefpzj9q207yn4zp23nfxkhmnu8mspu5lscnvgysc3rjnh8yzalyaw5nn6tqnn3emnu6wys7qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpktsx6gqqqqqqrcss83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh4ycsra98j4l2k35fg7qhvapz26js2rh0j5n36pzlt9kaprvl3zd9s29egq33dsxf29gszev88kpfrveu8g5xr8khk6tev8jmpxg3pxfhpcx6f4jtlm4ltwgpwqgqpduzqpkmntgyk3ms0p5fe0ps22ed9v7zvzgwtvge2kl6ey7qhvz0s7u694fl75ushaga2syfvw46kuz7tjstwq4jgz52n599ph5nt0854hrq"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.payerKey == invoice.payerKey)
    assert(decodedInvoice.chain == invoice.chain)
  }

  test("offer with quantity") {
    val nodeKey = PrivateKey(hex"334a488858f260a2bb262493f6edcd35470f110bba62c7a5f90c78a047b364df")
    assert(nodeKey.publicKey == PublicKey(hex"0327afd599da3226f4608b96ab042fe558bf558211d3c5e67ecc8be9963220434f"))
    val payerKey = PrivateKey(hex"4b4129a801ea631e25903cd59dd7f7a6820c19d73aa0b095496e21027934becf")
    assert(payerKey.publicKey == PublicKey(hex"027c6d03fa8f366e2ef8017cdfaf5d3cf1a3b0123db1318263b662c0aa9ec9c959"))
    val preimage = ByteVector32(hex"99221825b86576e94391b179902be8b22c7cfa7c3d14aec6ae86657dfd9bd2a8")
    val offer = Offer(TlvStream[OfferTlv](
      Chains(Seq(Block.TestnetGenesisBlock.hash)),
      Amount(100000 msat),
      Description("offer with quantity"),
      Issuer("alice@bigshop.com"),
      QuantityMin(50),
      QuantityMax(1000),
      NodeId(nodeKey.publicKey)))
    val encodedOffer = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqvqcdgq2zdhkven9wgs8w6t5dqs8zatpde6xjarezsgkzmrfvdj5qcnfvaeksmms9e3k7mgkqyepsqsraq0zzqe84l2enk3jym6xpzuk4vzzle2cha2cyywnchn8anytaxtrygzrfu"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 7200000 msat, 72, Features.empty, payerKey, Block.TestnetGenesisBlock.hash)
    val encodedRequest = "lnr1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyqcnw8ucesh0ttrka67a62qyf04tsprv4ul6uyrpctdm596q7av2zzqrdhwsqgqpfqnzqlrdq0ag7dnw9muqzlxl4awneudrkqfrmvf3sf3mvckq420vnj2e7pq998np5gs3khqdqpgztenk5k5wqhzlxjg0ed4q9439yh8dayzz7q24kay7qsrhxg8tf303g223fknj8d79d3dvj78nlkg9s8c5hyqgz5"
    assert(request.toString == encodedRequest)
    assert(InvoiceRequest.decode(encodedRequest).get == request)
    assert(request.isValidFor(offer))
    val invoice = Bolt12Invoice(offer, request, preimage, nodeKey, CltvExpiryDelta(34), Features.empty, hex"9134d86e269a13203bd85bb3fd05bf396b72fcb9fd5206e3a392f6a0ab94011d")
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.isValidFor(offer, request))
    // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
    val encodedInvoice = "lni1qvsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqyyqcnw8ucesh0ttrka67a62qyf04tsprv4ul6uyrpctdm596q7av2zzqrdhwsqzsndanxvetjypmkjargypch2ctww35hg7gsnqpj0t74n8dryfh5vz9ed2cy9lj43064sgga830x0mxgh6vkxgsyxncrjczxkxg06n3zvk280qntncsgx4kfducc57dy3cj0qcprf9sgnqfqzq3pntdcen5g5nuy84ny8e9geerst688u82xmyenwhuy5dju9er3rsqryttw2zsv5xn6sht2lvakdved7thlw4ggk9g3ga9qpc6rlh2xhwhjkt3rnlcz03szw6ww2khdx3rv4m0x2ys7qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqmwaqqqqqqqqzsgkzmrfvdj5qcnfvaeksmms9e3k7mg7yypj0t74n8dryfh5vz9ed2cy9lj43064sgga830x0mxgh6vkxgsyxneqq9yzvgrud5pl4rekdch0sqtum7h460835wcpy0d3xxpx8dnzcz4fajwfty5qgckcypdj5gr7q6pnms32xvy3rx08aw5zjpkua90zymcv947urlhz72wg5g7xkqhqyqpz7pqrs69fh5xv6gvfxahya334fc6ycjly4lzqzap5w543qrpx42pjn5ez8mtx73k0vlvhjjskspmjyukdqugwy54zf9qxxss3hh7q2uxlcg"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.payerKey == invoice.payerKey)
    assert(decodedInvoice.chain == invoice.chain)
  }
}
