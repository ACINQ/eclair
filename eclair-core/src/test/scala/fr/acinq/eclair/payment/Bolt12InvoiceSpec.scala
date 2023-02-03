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
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, VariableLengthOnion}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Bolt12Invoice.hrp
import fr.acinq.eclair.wire.protocol.OfferCodecs.{invoiceRequestTlvCodec, invoiceTlvCodec}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv.{AllowedFeatures, PathId, PaymentConstraints}
import fr.acinq.eclair.wire.protocol.{GenericTlv, OfferTypes, TlvStream}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, FeatureSupport, Features, MilliSatoshiLong, TimestampSecond, TimestampSecondLong, UInt64, randomBytes32, randomBytes64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.concurrent.duration.DurationInt
import scala.util.Success

class Bolt12InvoiceSpec extends AnyFunSuite {

  def signInvoiceTlvs(tlvs: TlvStream[InvoiceTlv], key: PrivateKey): TlvStream[InvoiceTlv] = {
    val signature = signSchnorr(Bolt12Invoice.signatureTag, rootHash(tlvs, invoiceTlvCodec), key)
    tlvs.copy(records = tlvs.records ++ Seq(Signature(signature)))
  }

  def signInvoice(invoice: Bolt12Invoice, key: PrivateKey): Bolt12Invoice = {
    val tlvs = OfferTypes.removeSignature(invoice.records)
    val signedInvoice = Bolt12Invoice(signInvoiceTlvs(tlvs, key))
    assert(signedInvoice.checkSignature())
    signedInvoice
  }

  def createPaymentBlindedRoute(nodeId: PublicKey, sessionKey: PrivateKey = randomKey(), pathId: ByteVector = randomBytes32()): PaymentBlindedRoute = {
    val selfPayload = blindedRouteDataCodec.encode(TlvStream(PathId(pathId), PaymentConstraints(CltvExpiry(1234567), 0 msat), AllowedFeatures(Features.empty))).require.bytes
    PaymentBlindedRoute(Sphinx.RouteBlinding.create(sessionKey, Seq(nodeId), Seq(selfPayload)).route, PaymentInfo(1 msat, 2, CltvExpiryDelta(3), 4 msat, 5 msat, Features.empty))
  }

  test("check invoice signature") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.checkSignature())
    assert(Bolt12Invoice.fromString(invoice.toString).get.toString == invoice.toString)
    // changing signature makes check fail
    val withInvalidSignature = Bolt12Invoice(TlvStream(invoice.records.records.map { case Signature(_) => Signature(randomBytes64()) case x => x }, invoice.records.unknown))
    assert(!withInvalidSignature.checkSignature())
    // changing fields makes the signature invalid
    val withModifiedUnknownTlv = Bolt12Invoice(invoice.records.copy(unknown = Set(GenericTlv(UInt64(7), hex"ade4"))))
    assert(!withModifiedUnknownTlv.checkSignature())
    val withModifiedAmount = Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferAmount(amount) => OfferAmount(amount + 100.msat) case x => x }, invoice.records.unknown))
    assert(!withModifiedAmount.checkSignature())
  }

  test("check invoice signature with unknown field from invoice request") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val basicRequest = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val requestWithUnknownTlv = basicRequest.copy(records = TlvStream(basicRequest.records.records, Set(GenericTlv(UInt64(87), hex"0404"))))
    val invoice = Bolt12Invoice(requestWithUnknownTlv, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.records.unknown == Set(GenericTlv(UInt64(87), hex"0404")))
    assert(invoice.validateFor(requestWithUnknownTlv).isRight)
    assert(Bolt12Invoice.fromString(invoice.toString).get.toString == invoice.toString)
  }

  test("check that invoice matches offer") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(10000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.validateFor(request).isRight)
    // amount must match the request
    val withOtherAmount = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferAmount(_) => OfferAmount(9000 msat) case x => x })), nodeKey)
    assert(withOtherAmount.validateFor(request).isLeft)
    // description must match the offer
    val withOtherDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferDescription(_) => OfferDescription("other description") case x => x })), nodeKey)
    assert(withOtherDescription.validateFor(request).isLeft)
    // nodeId must match the offer
    val otherNodeKey = randomKey()
    val withOtherNodeId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferNodeId(_) => OfferNodeId(otherNodeKey.publicKey) case x => x })), nodeKey)
    assert(withOtherNodeId.validateFor(request).isLeft)
    // issuer must match the offer
    val withOtherIssuer = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records ++ Seq(OfferIssuer("spongebob")))), nodeKey)
    assert(withOtherIssuer.validateFor(request).isLeft)
  }

  test("check that invoice matches invoice request") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(15000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 15000 msat, 1, Features.empty, payerKey, chain)
    assert(request.quantity_opt.isEmpty) // when paying for a single item, the quantity field must not be present
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features(BasicMultiPartPayment -> Optional), Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.validateFor(request).isRight)
    val withInvalidFeatures = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceFeatures(_) => InvoiceFeatures(Features(BasicMultiPartPayment -> Mandatory)) case x => x })), nodeKey)
    assert(withInvalidFeatures.validateFor(request).isLeft)
    val withAmountTooBig = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceRequestAmount(_) => InvoiceRequestAmount(20000 msat) case x => x })), nodeKey)
    assert(withAmountTooBig.validateFor(request).isLeft)
    val withQuantity = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + InvoiceRequestQuantity(2))), nodeKey)
    assert(withQuantity.validateFor(request).isLeft)
    val withOtherPayerKey = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceRequestPayerId(_) => InvoiceRequestPayerId(randomKey().publicKey) case x => x })), nodeKey)
    assert(withOtherPayerKey.validateFor(request).isLeft)
    val withPayerNote = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + InvoiceRequestPayerNote("I am Batman"))), nodeKey)
    assert(withPayerNote.validateFor(request).isLeft)
    val withOtherMetadata = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceRequestMetadata(_) => InvoiceRequestMetadata(hex"ae46c46b86") case x => x })), nodeKey)
    assert(withOtherMetadata.validateFor(request).isLeft)
    // Invoice request with more details about the payer.
    val requestWithPayerDetails = {
      val tlvs: Set[InvoiceRequestTlv] = Set(
        InvoiceRequestMetadata(hex"010203040506"),
        OfferDescription("offer description"),
        OfferNodeId(nodeKey.publicKey),
        InvoiceRequestAmount(15000 msat),
        InvoiceRequestPayerId(payerKey.publicKey),
        InvoiceRequestPayerNote("I am Batman"),
        OfferFeatures(Features(VariableLengthOnion -> Mandatory))
      )
      val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), invoiceRequestTlvCodec), payerKey)
      InvoiceRequest(TlvStream(tlvs + Signature(signature)))
    }
    val withPayerDetails = Bolt12Invoice(requestWithPayerDetails, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(withPayerDetails.validateFor(requestWithPayerDetails).isRight)
    assert(withPayerDetails.validateFor(request).isLeft)
    val withOtherPayerNote = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map { case InvoiceRequestPayerNote(_) => InvoiceRequestPayerNote("Or am I Bruce Wayne?") case x => x })), nodeKey)
    assert(withOtherPayerNote.validateFor(requestWithPayerDetails).isLeft)
    assert(withOtherPayerNote.validateFor(request).isLeft)
  }

  test("check invoice expiry") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), randomBytes32())
    val offer = Offer(Some(5000 msat), "test offer", nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 5000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(!invoice.isExpired())
    assert(invoice.validateFor(request).isRight)
    val expiredInvoice1 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceCreatedAt(_) => InvoiceCreatedAt(0 unixsec) case x => x })), nodeKey)
    assert(expiredInvoice1.isExpired())
    assert(expiredInvoice1.validateFor(request).isLeft) // when an invoice is expired, we mark it as invalid as well
    val expiredInvoice2 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
      case InvoiceCreatedAt(_) => InvoiceCreatedAt(TimestampSecond.now() - 2000)
      case InvoiceRelativeExpiry(_) => InvoiceRelativeExpiry(1800)
      case x => x
    })), nodeKey)
    assert(expiredInvoice2.isExpired())
  assert(expiredInvoice2.validateFor(request).isLeft) // when an invoice is expired, we mark it as invalid as well
  }

  test("decode invalid invoice") {
    val nodeKey = randomKey()
    val tlvs = Set[InvoiceTlv](
      InvoiceRequestMetadata(hex"012345"),
      OfferDescription("minimal invoice"),
      OfferNodeId(nodeKey.publicKey),
      InvoiceRequestPayerId(randomKey().publicKey),
      InvoicePaths(Seq(createPaymentBlindedRoute(randomKey().publicKey).route)),
      InvoiceBlindedPay(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 765432 msat, Features.empty))),
      InvoiceCreatedAt(TimestampSecond(123456789L)),
      InvoicePaymentHash(randomBytes32()),
      InvoiceAmount(1684 msat),
      InvoiceNodeId(nodeKey.publicKey),
    )
    // This minimal invoice is valid.
    val signed = signInvoiceTlvs(TlvStream(tlvs), nodeKey)
    val signedEncoded = Bech32.encodeBytes(hrp, invoiceTlvCodec.encode(signed).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    assert(Bolt12Invoice.fromString(signedEncoded).isSuccess)
    // But removing any TLV makes it invalid.
    for (tlv <- tlvs) {
      val incomplete = tlvs.filterNot(_ == tlv)
      val incompleteSigned = signInvoiceTlvs(TlvStream(incomplete), nodeKey)
      val incompleteSignedEncoded = Bech32.encodeBytes(hrp, invoiceTlvCodec.encode(incompleteSigned).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
      assert(Bolt12Invoice.fromString(incompleteSignedEncoded).isFailure)
    }
    // Missing signature is also invalid.
    val unsignedEncoded = Bech32.encodeBytes(hrp, invoiceTlvCodec.encode(TlvStream(tlvs)).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
    assert(Bolt12Invoice.fromString(unsignedEncoded).isFailure)
  }

  test("encode/decode invoice with many fields") {
    val chain = Block.TestnetGenesisBlock.hash
    val amount = 123456 msat
    val description = "invoice with many fields"
    val features = Features[Feature](Features.VariableLengthOnion -> FeatureSupport.Mandatory, Features.RouteBlinding -> FeatureSupport.Mandatory)
    val issuer = "alice"
    val nodeKey = PrivateKey(hex"998cf8ecab46f949bb960813b79d3317cabf4193452a211795cd8af1b9a25d90")
    val path = createPaymentBlindedRoute(nodeKey.publicKey, PrivateKey(hex"f0442c17bdd2cefe4a4ede210f163b068bb3fea6113ffacea4f322de7aa9737b"), hex"76030536ba732cdc4e7bb0a883750bab2e88cb3dddd042b1952c44b4849c86bb").copy(paymentInfo = PaymentInfo(2345 msat, 765, CltvExpiryDelta(324), 1000 msat, amount, Features.empty))
    val quantity = 57
    val payerKey = PublicKey(hex"024a8d96f4d13c4219f211b8a8e7b4ab7a898fd1b2e90274ca5a8737a9eda377f8")
    val payerNote = "I'm Bob"
    val payerInfo = hex"a9eb6e526eac59cd9b89fb20"
    val createdAt = TimestampSecond(1654654654L)
    val paymentHash = ByteVector32.fromValidHex("51951d4c53c904035f0b293dc9df1c0e7967213430ae07a5f3e134cd33325341")
    val relativeExpiry = 3600
    val fallbacks = Seq(FallbackAddress(4, hex"123d56f8"), FallbackAddress(6, hex"eb3adc68945ef601"))
    val tlvs = TlvStream[InvoiceTlv](Set[InvoiceTlv](
      InvoiceRequestMetadata(payerInfo),
      OfferChains(Seq(chain)),
      OfferAmount(amount),
      OfferDescription(description),
      OfferFeatures(Features.empty),
      OfferIssuer(issuer),
      OfferNodeId(nodeKey.publicKey),
      InvoiceRequestChain(chain),
      InvoiceRequestAmount(amount),
      InvoiceRequestQuantity(quantity),
      InvoiceRequestPayerId(payerKey),
      InvoiceRequestPayerNote(payerNote),
      InvoicePaths(Seq(path.route)),
      InvoiceBlindedPay(Seq(path.paymentInfo)),
      InvoiceCreatedAt(createdAt),
      InvoiceRelativeExpiry(relativeExpiry),
      InvoicePaymentHash(paymentHash),
      InvoiceAmount(amount),
      InvoiceFallbacks(fallbacks),
      InvoiceFeatures(Features.empty),
      InvoiceNodeId(nodeKey.publicKey),
    ), Set(GenericTlv(UInt64(121), hex"010203"), GenericTlv(UInt64(313), hex"baba")))
    val signature = signSchnorr(Bolt12Invoice.signatureTag, rootHash(tlvs, invoiceTlvCodec), nodeKey)
    val invoice = Bolt12Invoice(tlvs.copy(records = tlvs.records + Signature(signature)))
    assert(invoice.toString == "lni1qqx2n6mw2fh2ckwdnwylkgqzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqqfq2ctvd93k293pq0zxw03kpc8tc2vv3kfdne0kntqhq8p70wtdncwq2zngaqp529mmc5pqgdyhl4lcy62hzz855v8annkr46a8n9eqsn5satgpagesjqqqqqq9yqcpufq9vqfetqssyj5djm6dz0zzr8eprw9gu762k75f3lgm96gzwn994peh48k6xalctyr5jfmdyppx7cneqvqsyqaq5qpugee7xc8qa0pf3jxe9k0976dvzuqu8eaedk0pcpg2dr5qx3gh00qzn8pc426xsh6l6ekdhr2hdpge0euhhp9frv6w04zjcqhhf6ru2wrqzqnjsxh8zmlm0gkeuq8qyxcy28uzhzljqkq22epc4mmdrx6vtm0eyyqr4agrvpkfuutftvf7f6paqewk3ysql3h8ukfz3phgmap5we4wsq3c97205a96r6f3hsd705jl29xt8yj3cu8vpm6z8lztjw3pcqqqpy5sqqqzl5q5gqqqqqqqqqqraqqqqqqqqqq7ysqqqzjqgc4qq6l2vqswzz5zq5v4r4x98jgyqd0sk2fae803crnevusngv9wq7jl8cf5e5eny56p4gpsrcjq4sfqgqqyzg74d7qxqqywkwkudz29aasp4cqtqggrc3nnudswp67znrydjtv7ta56c9cpc0nmjmv7rszs568gqdz3w770qsx3axhvq3e7npme2pwslgxa8kfcnqjqyeztg5r5wgzjpufjswx4crvd6kzlqjzukq5e707kp9ez98mj0zkckeggkm8cp6g6vgzh3j2q0lgp8ypt4ws")
    val Success(codedDecoded) = Bolt12Invoice.fromString(invoice.toString)
    assert(codedDecoded.invoiceRequest.chain == chain)
    assert(codedDecoded.amount == amount)
    assert(codedDecoded.description == Left(description))
    assert(codedDecoded.features == features)
    assert(codedDecoded.invoiceRequest.offer.issuer.contains(issuer))
    assert(codedDecoded.nodeId.value.drop(1) == nodeKey.publicKey.value.drop(1))
    assert(codedDecoded.blindedPaths == Seq(path))
    assert(codedDecoded.invoiceRequest.quantity == quantity)
    assert(codedDecoded.invoiceRequest.payerId == payerKey)
    assert(codedDecoded.invoiceRequest.payerNote.contains(payerNote))
    assert(codedDecoded.invoiceRequest.metadata == payerInfo)
    assert(codedDecoded.createdAt == createdAt)
    assert(codedDecoded.paymentHash == paymentHash)
    assert(codedDecoded.relativeExpiry == relativeExpiry.seconds)
    assert(codedDecoded.fallbacks.contains(fallbacks))
    assert(codedDecoded.records.unknown.toSet == Set(GenericTlv(UInt64(121), hex"010203"), GenericTlv(UInt64(313), hex"baba")))
  }

  test("minimal tip") {
    val nodeKey = PrivateKey(hex"48c6e5fcf499f50436f54c3b3edecdb0cb5961ca29d74bea5ab764828f08bf47")
    assert(nodeKey.publicKey == PublicKey(hex"024ff5317f051c7f6eac0266c5cceaeb6c5775a940fab9854e47bfebf6bc7a0407"))
    val payerKey = PrivateKey(hex"d817e8896c67d0bcabfdb93da7eb7fc698c829a181f994dd0ad866a8eda745e8")
    assert(payerKey.publicKey == PublicKey(hex"031ef4439f638914de79220483dda32dfb7a431e799a5ce5a7643fbd70b2118e4e"))
    val preimage = ByteVector32(hex"317d1fd8fec5f3ea23044983c2ba2a8043395b2a0790a815c9b12719aa5f1516")
    val offer = Offer(None, "minimal tip", nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val encodedOffer = "lno1pg9k66twd9kkzmpqw35hq93pqf8l2vtlq5w87m4vqfnvtn82adk9wadfgratnp2wg7l7ha4u0gzqw"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 12000000 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    // Invoice request generation is not reproducible because we add randomness in the first TLV.
    val encodedRequest = "lnr1qqs289chx8swkpmwf3uzexfxr0kk9syavsjcmkuur5qgjqt60ayjdec2pdkkjmnfd4skcgr5d9cpvggzfl6nzlc9r3lkatqzvmzue6htd3tht22ql2uc2nj8hl4ld0r6qsr4qgr0u2xq4dh3kdevrf4zg6hx8a60jv0gxe0ptgyfc6xkryqqqqqqqpfq8dcmqpvzzqc773pe7cufzn08jgsys0w6xt0m0fp3u7v6tnj6weplh4ctyyvwfmcypemfjk6kryqxycnnmu2vp9tuw00eslf0grp6rf3hk6v76aynyn4lclra0fyyk2gxyf9hx73rnm775204tn8cltacw4s0fzd5c0lxm58s"
    val decodedRequest = InvoiceRequest.decode(encodedRequest).get
    assert(decodedRequest.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]) == request.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]))
    assert(request.isValidFor(offer))
    val invoice = Bolt12Invoice(decodedRequest, preimage, nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.validateFor(decodedRequest).isRight)
    // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
    val encodedInvoice = "lni1qqs289chx8swkpmwf3uzexfxr0kk9syavsjcmkuur5qgjqt60ayjdec2pdkkjmnfd4skcgr5d9cpvggzfl6nzlc9r3lkatqzvmzue6htd3tht22ql2uc2nj8hl4ld0r6qsr4qgr0u2xq4dh3kdevrf4zg6hx8a60jv0gxe0ptgyfc6xkryqqqqqqqpfq8dcmqpvzzqc773pe7cufzn08jgsys0w6xt0m0fp3u7v6tnj6weplh4ctyyvwf6s2qqj075ch7pgu0ah2cqnxchxw46mv2a66js86hxz5u3ala0mtc7syqup2a4g7lywy0zytzjzdhlar5uegx8qj8el2a2hpl7z30cv56fxkhwqpqgpnv93lzfep3m5ppkt3jry0kanpk3uxku733nr03snlzqjls3pejqp65tnf8nf8te9h67ge0lgzum5kypuvqrdz50t238n6g0wrdtv49nrgjk7k26rw7a24arfx9z4dup8379etdpw0tfkg3mwtngsuqqqqqqgqqqqqyqqrqqqqqqqqqqqqgqqqqqqqqqqqq5qqpfqyvwv9m2dxqgqje2pqshlyweee7p4m365legtkdgvy6s02rdqsv38mwnmk8p88cz03dt725qahrvqtqggzfl6nzlc9r3lkatqzvmzue6htd3tht22ql2uc2nj8hl4ld0r6qsrlqsxuf5rcjutppkh79vr6q7vma5yccxhf79ghfg5zkc6z4u3zqzyh0nf50g7w7q4gk32hqg97pn7p9kaz0ddm5fza65ztdqj2sry3gw6l2"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.invoiceRequest.unsigned == invoice.invoiceRequest.unsigned)
  }

  test("minimal offer") {
    val nodeKey = PrivateKey(hex"3b7a19e8320bb86431cf92cd7c69cc1dc0181c37d5a09875e4603c4e37d3705d")
    assert(nodeKey.publicKey == PublicKey(hex"03c48ac97e09f3cbbaeb35b02aaa6d072b57726841a34d25952157caca60a1caf5"))
    val payerKey = PrivateKey(hex"0e00a9ef505292f90a0e8a7aa99d31750e885c42a3ef8866dd2bf97919aa3891")
    assert(payerKey.publicKey == PublicKey(hex"033e94f2afd568d128f02ece844ad4a0a1ddf2a4e3a08beb2dba11b3f1134b0517"))
    val preimage = ByteVector32(hex"09ad5e952ec39d45461ebdeceac206fb45574ae9054b5a454dd02c65f5ba1b7c")
    val offer = Offer(Some(456000000 msat), "minimal offer", nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val encodedOffer = "lno1pqzpktszqq9q6mtfde5k6ctvyphkven9wgtzzq7y3tyhuz0newawkdds924x6pet2aexssdrf5je2g2het9xpgw275"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 456001234 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    // Invoice request generation is not reproducible because we add randomness in the first TLV.
    val encodedRequest = "lnr1qqsf4h8fsnpjkj057gjg9c3eqhv889440xh0z6f5kng9vsaad8pgq7sgqsdjuqsqpgxk66twd9kkzmpqdanxvetjzcss83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh42qsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqzjqsdjupkjtqssx05572ha26x39rczan5yft22pgwa72jw8gytavkm5ydn7yf5kpgh7pq2hlvh7twke5830a44wc0zlrs2kph4ghndm60ahwcznhcd0pcpl332qv5xuemksazy3zx5s63kqmqkphrn9jg4ln55pc6syrwqukejeq"
    val decodedRequest = InvoiceRequest.decode(encodedRequest).get
    assert(decodedRequest.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]) == request.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]))
    assert(request.isValidFor(offer))
    val invoice = Bolt12Invoice(decodedRequest, preimage, nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.validateFor(decodedRequest).isRight)
    // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
    val encodedInvoice = "lni1qqsf4h8fsnpjkj057gjg9c3eqhv889440xh0z6f5kng9vsaad8pgq7sgqsdjuqsqpgxk66twd9kkzmpqdanxvetjzcss83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh42qsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqzjqsdjupkjtqssx05572ha26x39rczan5yft22pgwa72jw8gytavkm5ydn7yf5kpgh5zsq83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh4qfjynufc627cuspz9lqzyk387xgzs4txcw0q97ugxfqm8x5zgj02gqgz4mnucmtxr620e5ttewtsg0s5n88euljnf7puagqje9j6gvaxk3pqqwsmahw79nhuq05zh8k29jk5qngpuny5l2vhjdrexg8hejukaee8fr7963dfag9q3lpcq9tt23f8s4h89cmjqa43u4fhk6l2y8qqqqqqzqqqqqpqqqcqqqqqqqqqqqzqqqqqqqqqqqq9qqq2gprrnp0zefszqyk2sgpvkrnmq53kv7r52rpnmtmd9ukredsnygsnymsurdy6e9la6l4hyz4qgxewqmftqggrcj9vjlsf709m46e4kq425mg89dthy6zp5dxjt9fp2l9v5c9pet6lqsy3s64amqgnlel7hn6fjrnk32xrn0ugr2xzct22ew28zftgmj70q9x2akqm34que8u2qe643cm38jpka6nfca4lfhuq6hgpnpwkpexrc"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.invoiceRequest.unsigned == invoice.invoiceRequest.unsigned)
  }

  test("offer with quantity") {
    val nodeKey = PrivateKey(hex"334a488858f260a2bb262493f6edcd35470f110bba62c7a5f90c78a047b364df")
    assert(nodeKey.publicKey == PublicKey(hex"0327afd599da3226f4608b96ab042fe558bf558211d3c5e67ecc8be9963220434f"))
    val payerKey = PrivateKey(hex"4b4129a801ea631e25903cd59dd7f7a6820c19d73aa0b095496e21027934becf")
    assert(payerKey.publicKey == PublicKey(hex"027c6d03fa8f366e2ef8017cdfaf5d3cf1a3b0123db1318263b662c0aa9ec9c959"))
    val preimage = ByteVector32(hex"99221825b86576e94391b179902be8b22c7cfa7c3d14aec6ae86657dfd9bd2a8")
    val offer = Offer(TlvStream[OfferTlv](
      OfferChains(Seq(Block.TestnetGenesisBlock.hash)),
      OfferAmount(100000 msat),
      OfferDescription("offer with quantity"),
      OfferIssuer("alice@bigshop.com"),
      OfferQuantityMax(1000),
      OfferNodeId(nodeKey.publicKey)))
    val encodedOffer = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqvqcdgq2zdhkven9wgs8w6t5dqs8zatpde6xjarezggkzmrfvdj5qcnfvaeksmms9e3k7mg5qgp7s93pqvn6l4vemgezdarq3wt2kpp0u4vt74vzz8futen7ej97n93jypp57"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 7200000 msat, 72, Features.empty, payerKey, Block.TestnetGenesisBlock.hash)
    // Invoice request generation is not reproducible because we add randomness in the first TLV.
    val encodedRequest = "lnr1qqs8lqvnh3kg9uj003lxlxyj8hthymgq4p9ms0ag0ryx5uw8gsuus4gzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrqxr2qzsndanxvetjypmkjargypch2ctww35hg7gjz9skc6trv4qxy6t8wd5x7upwvdhk69qzq05pvggry7hatxw6xgn0gcytj64sgtl9tzl4tqs360z7vlkv305evv3qgd84qgzrf9la07pxj4cs3a9rplvuasawhfuewgyyay826q02xvysqqqqqpfqxmwaqptqzjzcyyp8cmgrl28nvm3wlqqheha0t570rgaszg7mzvvzvwmx9s92nmyujk0sgpef8dt57nygu3dnfhglymt6mnle6j8s28rler8wv3zygen07v4ddfplc9qs7nkdzwcelm2rs552slkpv45xxng65ne6y4dlq2764gqv"
    val decodedRequest = InvoiceRequest.decode(encodedRequest).get
    assert(decodedRequest.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]) == request.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]))
    assert(request.isValidFor(offer))
    val invoice = Bolt12Invoice(decodedRequest, preimage, nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.validateFor(decodedRequest).isRight)
    // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
    val encodedInvoice = "lni1qqs8lqvnh3kg9uj003lxlxyj8hthymgq4p9ms0ag0ryx5uw8gsuus4gzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrqxr2qzsndanxvetjypmkjargypch2ctww35hg7gjz9skc6trv4qxy6t8wd5x7upwvdhk69qzq05pvggry7hatxw6xgn0gcytj64sgtl9tzl4tqs360z7vlkv305evv3qgd84qgzrf9la07pxj4cs3a9rplvuasawhfuewgyyay826q02xvysqqqqqpfqxmwaqptqzjzcyyp8cmgrl28nvm3wlqqheha0t570rgaszg7mzvvzvwmx9s92nmyujkdq5qpj0t74n8dryfh5vz9ed2cy9lj43064sgga830x0mxgh6vkxgsyxnczgew6pkkhja3cl3dfxthumcmp6gkp446ha4tcj884eqch6g57newqzquqmar5nynwtg9lknq98yzslwla3vdxefulhq2jkwnqnsf7umpl5cqr58qkj63hkpl7ffyd6f3qgn3m5kuegehhakvxw7fuw29tf3r5wgj37uecjdw2th4t5fp7f99xvk4f3gwl0wyf2a558wqa9w3pcqqqqqqsqqqqqgqqxqqqqqqqqqqqqsqqqqqqqqqqqpgqqzjqgcuctck2vqsp9j5zqlsxsv7uy23npygenelt4q5sdh8ftc3x7rpd0hqlachjnj9z834s4gpkmhgqkqssxfa06kva5v3x73sgh94tqsh72k9l2kppr579uelvezlfjcezqs607pqxa3afljxyf2ua9dlqs33wrfzakt5tpraklpzfpn63uxa7el475x4sc0w4hs75e3nhe689slfz4ldqlwja3zaq0w3mnz79f4ne0c3r3c"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.invoiceRequest.unsigned == invoice.invoiceRequest.unsigned)
  }

  test("cln invoice"){
    val encodedInvoice = "lni1qqgds4gweqxey37gexf5jus4kcrwuq3qqc3xu3s3rg94nj40zfsy866mhu5vxne6tcej5878k2mneuvgjy8s5predakx793pqfxv2rtqfajhp98c5tlsxxkkmzy0ntpzp2rtt9yum2495hqrq4wkj5pqqc3xu3s3rg94nj40zfsy866mhu5vxne6tcej5878k2mneuvgjy84yqucj6q9sggrnl24r93kfmdnatwpy72mxg7ygr9waxu0830kkpqx84pd5j65fhg2pxqzfnzs6cz0v4cff79zlup344kc3ru6cgs2s66ef8x64fd9cqc9t45s954fef6n3ql8urpc4r2vvunc0uv9yq37g485heph6lpuw34ywxadqypwq3hlcrpyk32zdvlrgfsdnx5jegumenll49v502862l9sq5erz3qqxte8tyk308ykd6fqy2lxkrsmeq77d8s5977pzmc68lgvs2xcn0kfvnlzud9fvkv900ggwe7yf9hf7lr6qz3pcqqqqqqqqqqqqqqq5qqqqqqqqqqqqqwjfvkl43fqqqqqqzjqgcuhrdv2sgq5spd8qp4ev2rw0v9r7cvvrntlzpvlwmd8vczycklu87336h55g24q8xykszczzqjvc5xkqnm9wz203ghlqvdddkyglxkzyz5xkk2fek42tfwqxp2ad8cypv26x5zxkyk675ep3v48grwydze6nvvg56cklgmvztuny58t5j0fl3hemx3lvd0ryx89jtf0h069z6r2qwqvjlyrewvzsfqmmfajs70q"
    val invoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(invoice.checkSignature())
    assert(invoice.amount == 10000000.msat)
    assert(invoice.nodeId == PublicKey(hex"024cc50d604f657094f8a2ff031ad6d888f9ac220a86b5949cdaaa5a5c03055d69"))
    assert(invoice.paymentHash == ByteVector32(hex"14805a7006b96286e7b0a3f618c1cd7f1059f76da766044c5bfc3fa31d5e9442"))
    assert(invoice.description == Left("yolo"))
  }
}
