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
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, ByteVector32}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, VariableLengthOnion}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
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
    PaymentBlindedRoute(Sphinx.RouteBlinding.create(sessionKey, Seq(nodeId), Seq(selfPayload)).route, PaymentInfo(1 msat, 2, CltvExpiryDelta(3), 4 msat, 5 msat, ByteVector.empty))
  }

  test("check invoice signature") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(Some(10000 msat), Some("test offer"), nodeKey.publicKey, Features.empty, chain)
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
    val withModifiedAmount = Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferAmount(amount) => OfferAmount(amount + 100) case x => x }, invoice.records.unknown))
    assert(!withModifiedAmount.checkSignature())
  }

  test("check invoice signature with unknown field from invoice request") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(Some(10000 msat), Some("test offer"), nodeKey.publicKey, Features.empty, chain)
    val basicRequest = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val requestWithUnknownTlv = basicRequest.copy(records = TlvStream(basicRequest.records.records, Set(GenericTlv(UInt64(87), hex"0404"))))
    val invoice = Bolt12Invoice(requestWithUnknownTlv, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.records.unknown == Set(GenericTlv(UInt64(87), hex"0404")))
    assert(invoice.validateFor(requestWithUnknownTlv, nodeKey.publicKey).isRight)
    assert(Bolt12Invoice.fromString(invoice.toString).get.toString == invoice.toString)
  }

  test("check that invoice matches offer") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(Some(10000 msat), Some("test offer"), nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.validateFor(request, nodeKey.publicKey).isRight)
    // amount must match the request
    val withOtherAmount = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferAmount(_) => OfferAmount(9000) case x => x })), nodeKey)
    assert(withOtherAmount.validateFor(request, nodeKey.publicKey).isLeft)
    // description must match the offer
    val withOtherDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferDescription(_) => OfferDescription("other description") case x => x })), nodeKey)
    assert(withOtherDescription.validateFor(request, nodeKey.publicKey).isLeft)
    // nodeId must match the offer
    val otherNodeKey = randomKey()
    val withOtherNodeId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case OfferNodeId(_) => OfferNodeId(otherNodeKey.publicKey) case x => x })), nodeKey)
    assert(withOtherNodeId.validateFor(request, nodeKey.publicKey).isLeft)
    // issuer must match the offer
    val withOtherIssuer = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records ++ Seq(OfferIssuer("spongebob")))), nodeKey)
    assert(withOtherIssuer.validateFor(request, nodeKey.publicKey).isLeft)
  }

  test("check that invoice matches offer with implicit node id") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), BlockHash(randomBytes32()))
    val path1 = OnionMessages.buildRoute(randomKey(), Seq(IntermediateNode(randomKey().publicKey)), Recipient(nodeKey.publicKey, None))
    val path2 = OnionMessages.buildRoute(randomKey(), Seq(IntermediateNode(randomKey().publicKey)), Recipient(nodeKey.publicKey, None))
    val offer = Offer.withPaths(None, None, Seq(path1.route, path2.route), Features.empty, chain)
    val request = InvoiceRequest(offer, 11000 msat, 1, Features.empty, payerKey, chain)
    // Invoice is requested using path1.
    assert(RouteBlinding.derivePrivateKey(nodeKey, path1.lastPathKey).publicKey == path1.route.blindedNodeIds.last)
    val invoice = Bolt12Invoice(request, randomBytes32(), RouteBlinding.derivePrivateKey(nodeKey, path1.lastPathKey), 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.validateFor(request, nodeKey.publicKey).isLeft)
    assert(invoice.validateFor(request, path1.route.blindedNodeIds.last).isRight)
    assert(invoice.validateFor(request, path2.route.blindedNodeIds.last).isLeft)
  }

  test("check that invoice matches invoice request") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(Some(15000 msat), Some("test offer"), nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 15000 msat, 1, Features.empty, payerKey, chain)
    assert(request.quantity_opt.isEmpty) // when paying for a single item, the quantity field must not be present
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features(BasicMultiPartPayment -> Optional), Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(invoice.validateFor(request, nodeKey.publicKey).isRight)
    val withInvalidFeatures = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceFeatures(_) => InvoiceFeatures(Features(BasicMultiPartPayment -> Mandatory).toByteVector) case x => x })), nodeKey)
    assert(withInvalidFeatures.validateFor(request, nodeKey.publicKey).isLeft)
    val withAmountTooBig = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceRequestAmount(_) => InvoiceRequestAmount(20000 msat) case x => x })), nodeKey)
    assert(withAmountTooBig.validateFor(request, nodeKey.publicKey).isLeft)
    val withQuantity = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + InvoiceRequestQuantity(2))), nodeKey)
    assert(withQuantity.validateFor(request, nodeKey.publicKey).isLeft)
    val withOtherPayerKey = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceRequestPayerId(_) => InvoiceRequestPayerId(randomKey().publicKey) case x => x })), nodeKey)
    assert(withOtherPayerKey.validateFor(request, nodeKey.publicKey).isLeft)
    val withPayerNote = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + InvoiceRequestPayerNote("I am Batman"))), nodeKey)
    assert(withPayerNote.validateFor(request, nodeKey.publicKey).isLeft)
    val withOtherMetadata = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceRequestMetadata(_) => InvoiceRequestMetadata(hex"ae46c46b86") case x => x })), nodeKey)
    assert(withOtherMetadata.validateFor(request, nodeKey.publicKey).isLeft)
    // Invoice request with more details about the payer.
    val requestWithPayerDetails = {
      val tlvs: Set[InvoiceRequestTlv] = Set(
        InvoiceRequestMetadata(hex"010203040506"),
        OfferDescription("offer description"),
        OfferNodeId(nodeKey.publicKey),
        InvoiceRequestAmount(15000 msat),
        InvoiceRequestPayerId(payerKey.publicKey),
        InvoiceRequestPayerNote("I am Batman"),
        OfferFeatures(Features(VariableLengthOnion -> Mandatory).toByteVector)
      )
      val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), invoiceRequestTlvCodec), payerKey)
      InvoiceRequest(TlvStream(tlvs + Signature(signature)))
    }
    val withPayerDetails = Bolt12Invoice(requestWithPayerDetails, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(withPayerDetails.validateFor(requestWithPayerDetails, nodeKey.publicKey).isRight)
    assert(withPayerDetails.validateFor(request, nodeKey.publicKey).isLeft)
    val withOtherPayerNote = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map { case InvoiceRequestPayerNote(_) => InvoiceRequestPayerNote("Or am I Bruce Wayne?") case x => x })), nodeKey)
    assert(withOtherPayerNote.validateFor(requestWithPayerDetails, nodeKey.publicKey).isLeft)
    assert(withOtherPayerNote.validateFor(request, nodeKey.publicKey).isLeft)
  }

  test("check invoice expiry") {
    val (nodeKey, payerKey, chain) = (randomKey(), randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(Some(5000 msat), Some("test offer"), nodeKey.publicKey, Features.empty, chain)
    val request = InvoiceRequest(offer, 5000 msat, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(!invoice.isExpired())
    assert(invoice.validateFor(request, nodeKey.publicKey).isRight)
    val expiredInvoice1 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map { case InvoiceCreatedAt(_) => InvoiceCreatedAt(0 unixsec) case x => x })), nodeKey)
    assert(expiredInvoice1.isExpired())
    assert(expiredInvoice1.validateFor(request, nodeKey.publicKey).isLeft) // when an invoice is expired, we mark it as invalid as well
    val expiredInvoice2 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
      case InvoiceCreatedAt(_) => InvoiceCreatedAt(TimestampSecond.now() - 2000)
      case InvoiceRelativeExpiry(_) => InvoiceRelativeExpiry(1800)
      case x => x
    })), nodeKey)
    assert(expiredInvoice2.isExpired())
  assert(expiredInvoice2.validateFor(request, nodeKey.publicKey).isLeft) // when an invoice is expired, we mark it as invalid as well
  }

  test("decode invalid invoice") {
    val nodeKey = randomKey()
    val tlvs = Set[InvoiceTlv](
      InvoiceRequestMetadata(hex"012345"),
      OfferNodeId(nodeKey.publicKey),
      InvoiceRequestAmount(1684 msat),
      InvoiceRequestPayerId(randomKey().publicKey),
      InvoicePaths(Seq(createPaymentBlindedRoute(randomKey().publicKey).route)),
      InvoiceBlindedPay(Seq(PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 765432 msat, ByteVector.empty))),
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
    val chain = Block.Testnet3GenesisBlock.hash
    val amount = 123456 msat
    val description = "invoice with many fields"
    val features = Features.empty
    val issuer = "alice"
    val nodeKey = PrivateKey(hex"998cf8ecab46f949bb960813b79d3317cabf4193452a211795cd8af1b9a25d90")
    val path = createPaymentBlindedRoute(nodeKey.publicKey, PrivateKey(hex"f0442c17bdd2cefe4a4ede210f163b068bb3fea6113ffacea4f322de7aa9737b"), hex"76030536ba732cdc4e7bb0a883750bab2e88cb3dddd042b1952c44b4849c86bb").copy(paymentInfo = PaymentInfo(2345 msat, 765, CltvExpiryDelta(324), 1000 msat, amount, ByteVector.empty))
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
      OfferAmount(amount.toLong),
      OfferDescription(description),
      OfferFeatures(ByteVector.empty),
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
      InvoiceFeatures(ByteVector.empty),
      InvoiceNodeId(nodeKey.publicKey),
    ), Set(GenericTlv(UInt64(121), hex"010203"), GenericTlv(UInt64(313), hex"baba")))
    val signature = signSchnorr(Bolt12Invoice.signatureTag, rootHash(tlvs, invoiceTlvCodec), nodeKey)
    val invoice = Bolt12Invoice(tlvs.copy(records = tlvs.records + Signature(signature)))
    assert(invoice.toString == "lni1qqx2n6mw2fh2ckwdnwylkgqzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqqfq2ctvd93k293pq0zxw03kpc8tc2vv3kfdne0kntqhq8p70wtdncwq2zngaqp529mmc5pqgdyhl4lcy62hzz855v8annkr46a8n9eqsn5satgpagesjqqqqqq9yqcpufq9vqfetqssyj5djm6dz0zzr8eprw9gu762k75f3lgm96gzwn994peh48k6xalctyr5jfmdyppx7cneqvqsyqaq5qpugee7xc8qa0pf3jxe9k0976dvzuqu8eaedk0pcpg2dr5qx3gh00qzn8pc426xsh6l6ekdhr2hdpge0euhhp9frv6w04zjcqhhf6ru2wrqzqnjsxh8zmlm0gkeuq8qyxcy28uzhzljqkq22epc4mmdrx6vtm0eyyqr4agrvpkfuutftvf7f6paqewk3ysql3h8ukfz3phgmap5we4wsq3c97205a96r6f3hsd705jl29xt8yj3cu8vpm6z8lztjw3pcqqqpy5sqqqzl5q5gqqqqqqqqqqraqqqqqqqqqq7ysqqqzjqgc4qq6l2vqswzz5zq5v4r4x98jgyqd0sk2fae803crnevusngv9wq7jl8cf5e5eny56p4gpsrcjq4sfqgqqyzg74d7qxqqywkwkudz29aasp4cqtqggrc3nnudswp67znrydjtv7ta56c9cpc0nmjmv7rszs568gqdz3w770qsx3axhvq3e7npme2pwslgxa8kfcnqjqyeztg5r5wgzjpufjswx4crvd6kzlqjzukq5e707kp9ez98mj0zkckeggkm8cp6g6vgzh3j2q0lgp8ypt4ws")
    val Success(codedDecoded) = Bolt12Invoice.fromString(invoice.toString)
    assert(codedDecoded.invoiceRequest.chain == chain)
    assert(codedDecoded.amount == amount)
    assert(codedDecoded.description.contains(description))
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
    assert(codedDecoded.records.unknown == Set(GenericTlv(UInt64(121), hex"010203"), GenericTlv(UInt64(313), hex"baba")))
  }

  test("minimal tip") {
    val nodeKey = PrivateKey(hex"48c6e5fcf499f50436f54c3b3edecdb0cb5961ca29d74bea5ab764828f08bf47")
    assert(nodeKey.publicKey == PublicKey(hex"024ff5317f051c7f6eac0266c5cceaeb6c5775a940fab9854e47bfebf6bc7a0407"))
    val payerKey = PrivateKey(hex"d817e8896c67d0bcabfdb93da7eb7fc698c829a181f994dd0ad866a8eda745e8")
    assert(payerKey.publicKey == PublicKey(hex"031ef4439f638914de79220483dda32dfb7a431e799a5ce5a7643fbd70b2118e4e"))
    val preimage = ByteVector32(hex"317d1fd8fec5f3ea23044983c2ba2a8043395b2a0790a815c9b12719aa5f1516")
    val offer = Offer(None, None, nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val encodedOffer = "lno1zcssynl4x9ls28rld6kqyek9en4wkmzhwk55p74es48y00lt76785pq8"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 12000000 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    // Invoice request generation is not reproducible because we add randomness in the first TLV.
    val encodedRequest = "lnr1qqswluyyp7j9aamd8l2ma23jyvvuvujqu5wq73jp38t02yr72s23evskyypylaf30uz3clmw4spxd3wvat4kc4m449q04wv9ferml6lkh3aqgp6syph79rq2kmcmxukp563ydtnr7a8ex85rvhs45zyudrtpjqqqqqqqq5srkudsqkppqv00gsulvwy3fhneygzg8hdr9hah5sc70xd9eed8vslm6u9jzx8yauzqylul6xp50xd4hn9shs7nhe02yasj9yfxsgkxych4q52hmny95kgtxj73n74m3dkt988r2xppa5xpwxespv8hukqf8mh3m6t277plwmc"
    val decodedRequest = InvoiceRequest.decode(encodedRequest).get
    assert(decodedRequest.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]) == request.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]))
    assert(request.isValid)
    assert(request.offer == offer)
    val invoice = Bolt12Invoice(decodedRequest, preimage, nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.validateFor(decodedRequest, nodeKey.publicKey).isRight)
    // Invoice generation is not reproducible as the timestamp and path key will change but all other fields should be the same.
    val encodedInvoice = "lni1qqswluyyp7j9aamd8l2ma23jyvvuvujqu5wq73jp38t02yr72s23evskyypylaf30uz3clmw4spxd3wvat4kc4m449q04wv9ferml6lkh3aqgp6syph79rq2kmcmxukp563ydtnr7a8ex85rvhs45zyudrtpjqqqqqqqq5srkudsqkppqv00gsulvwy3fhneygzg8hdr9hah5sc70xd9eed8vslm6u9jzx8yag9qqf8l2vtlq5w87m4vqfnvtn82adk9wadfgratnp2wg7l7ha4u0gzqwqahgwxsycqwtqlvu32j8mqxln456sxzh50k6avmgsndugtcp6wqcvqsxft50dexrade3n9us6tegq60tjjuc5f50jg8h43jr02r263wjfnwqqapd2vrfrwj2es7ne0wla08xnndgg655spddpn0zlru8fvqk6776fff60jphldzuw6wxgtxlne7ttvlp4tpmsghfh54atau5gwqqqqqqyqqqqqzqqpsqqqqqqqqqqqyqqqqqqqqqqqq2qqq5szxvtagn6nqyqfv4qsgtlj8vuulq6aca20u59mx5xzdg84pksgxgnahfamrsnnup8ck4l92qwm3kq9syypylaf30uz3clmw4spxd3wvat4kc4m449q04wv9ferml6lkh3aqgplsgrznfv8aysjyphv0usapr06mc4svfj9hlg4k9s263xd50dp0qdttrffypamzdxz84ftcvd52afx0je8adu4ppxq9z7yse0zh9qjmdwgz"
    val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(decodedInvoice.amount == invoice.amount)
    assert(decodedInvoice.nodeId == invoice.nodeId)
    assert(decodedInvoice.paymentHash == invoice.paymentHash)
    assert(decodedInvoice.description == invoice.description)
    assert(decodedInvoice.invoiceRequest.unsigned == invoice.invoiceRequest.unsigned)
  }

  test("minimal offer with amount") {
    val nodeKey = PrivateKey(hex"3b7a19e8320bb86431cf92cd7c69cc1dc0181c37d5a09875e4603c4e37d3705d")
    assert(nodeKey.publicKey == PublicKey(hex"03c48ac97e09f3cbbaeb35b02aaa6d072b57726841a34d25952157caca60a1caf5"))
    val payerKey = PrivateKey(hex"0e00a9ef505292f90a0e8a7aa99d31750e885c42a3ef8866dd2bf97919aa3891")
    assert(payerKey.publicKey == PublicKey(hex"033e94f2afd568d128f02ece844ad4a0a1ddf2a4e3a08beb2dba11b3f1134b0517"))
    val preimage = ByteVector32(hex"09ad5e952ec39d45461ebdeceac206fb45574ae9054b5a454dd02c65f5ba1b7c")
    val offer = Offer(Some(456000000 msat), Some("minimal offer with amount"), nodeKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val encodedOffer = "lno1pqzpktszqq9pjmtfde5k6ctvyphkven9wgs8w6t5dqsxzmt0w4h8g93pq0zg4jt7p8euhwhtxkcz42ndqu44wunggx356fv4y9tu4jnq58902"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 456001234 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    // Invoice request generation is not reproducible because we add randomness in the first TLV.
    val encodedRequest = "lnr1qqswg5pzt6anzaxaypy8y46zknl8zn2a2jqyzrp74gtfm4lp6utpkzcgqsdjuqsqpgvk66twd9kkzmpqdanxvetjypmkjargypsk6mm4de6pvggrcj9vjlsf709m46e4kq425mg89dthy6zp5dxjt9fp2l9v5c9pet64qgr0u2xq4dh3kdevrf4zg6hx8a60jv0gxe0ptgyfc6xkryqqqqqqqpfqgxewqmf9sggr86209t74drgj3upwe6zy449q58wl9f8r5z97ktd6zxelzy6tq5tlqspdwx0zfhzu3mua0q2r7lgstw09p4qwtpgpewyuwytkpy2jm3hyupk52vc9tgx9dwvngdlgtgg335j029h0whqfxy28gkwewyu860g5x"
    val decodedRequest = InvoiceRequest.decode(encodedRequest).get
    assert(decodedRequest.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]) == request.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]))
    assert(request.isValid)
    assert(request.offer == offer)
    val invoice = Bolt12Invoice(decodedRequest, preimage, nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.validateFor(decodedRequest, nodeKey.publicKey).isRight)
    // Invoice generation is not reproducible as the timestamp and path key will change but all other fields should be the same.
    val encodedInvoice = "lni1qqswg5pzt6anzaxaypy8y46zknl8zn2a2jqyzrp74gtfm4lp6utpkzcgqsdjuqsqpgvk66twd9kkzmpqdanxvetjypmkjargypsk6mm4de6pvggrcj9vjlsf709m46e4kq425mg89dthy6zp5dxjt9fp2l9v5c9pet64qgr0u2xq4dh3kdevrf4zg6hx8a60jv0gxe0ptgyfc6xkryqqqqqqqpfqgxewqmf9sggr86209t74drgj3upwe6zy449q58wl9f8r5z97ktd6zxelzy6tq5t6pgqrcj9vjlsf709m46e4kq425mg89dthy6zp5dxjt9fp2l9v5c9pet6s9x0wj2xtjxkql2urqn70fsyyhy8pcervfcaxdygsu74qe9jcss8uqypwa9rd3q0jh7tpruvr7xq7e4uzrk8z3mn68n5vzhxu4ds6d83qr4cq8f0mp833xq58twvuwlpm4gqkv5uwv07gl665ye2a33mk0tdkkzls04h25z3943cv5nq6e64dharmudq37remmgdvdv2vpt4zrsqqqqqpqqqqqqsqqvqqqqqqqqqqqpqqqqqqqqqqqqzsqq9yq3nzl2gl5cpqzt9gyqktpeas2gmx0p69psea4akj7tpukcfjygfjdcwpkjdvjl7a06mjp2syrvhqd54syypufzkf0cyl8ja6av6mq242d5rjk4mjdpq6xnf9j5s40jk2vzsu4a0sgq5pde5afeshaze029mqk5r48v07ph0uykc3ks034czmw58khfcw9gpv6d9l3nea06ajl4dqjr7ryrv9alx0eff9rklp7gnrkra0vuj3"
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
      OfferChains(Seq(Block.Testnet3GenesisBlock.hash)),
      OfferAmount(100000),
      OfferDescription("offer with quantity"),
      OfferIssuer("alice@bigshop.com"),
      OfferQuantityMax(1000),
      OfferNodeId(nodeKey.publicKey)))
    val encodedOffer = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqvqcdgq2zdhkven9wgs8w6t5dqs8zatpde6xjarezggkzmrfvdj5qcnfvaeksmms9e3k7mg5qgp7s93pqvn6l4vemgezdarq3wt2kpp0u4vt74vzz8futen7ej97n93jypp57"
    assert(offer.toString == encodedOffer)
    assert(Offer.decode(encodedOffer).get == offer)
    val request = InvoiceRequest(offer, 7200000 msat, 72, Features.empty, payerKey, Block.Testnet3GenesisBlock.hash)
    // Invoice request generation is not reproducible because we add randomness in the first TLV.
    val encodedRequest = "lnr1qqs8lqvnh3kg9uj003lxlxyj8hthymgq4p9ms0ag0ryx5uw8gsuus4gzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrqxr2qzsndanxvetjypmkjargypch2ctww35hg7gjz9skc6trv4qxy6t8wd5x7upwvdhk69qzq05pvggry7hatxw6xgn0gcytj64sgtl9tzl4tqs360z7vlkv305evv3qgd84qgzrf9la07pxj4cs3a9rplvuasawhfuewgyyay826q02xvysqqqqqpfqxmwaqptqzjzcyyp8cmgrl28nvm3wlqqheha0t570rgaszg7mzvvzvwmx9s92nmyujk0sgpef8dt57nygu3dnfhglymt6mnle6j8s28rler8wv3zygen07v4ddfplc9qs7nkdzwcelm2rs552slkpv45xxng65ne6y4dlq2764gqv"
    val decodedRequest = InvoiceRequest.decode(encodedRequest).get
    assert(decodedRequest.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]) == request.unsigned.records.filterNot(_.isInstanceOf[InvoiceRequestMetadata]))
    assert(request.isValid)
    assert(request.offer == offer)
    val invoice = Bolt12Invoice(decodedRequest, preimage, nodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(nodeKey.publicKey)))
    assert(Bolt12Invoice.fromString(invoice.toString).get.records == invoice.records)
    assert(invoice.validateFor(decodedRequest, nodeKey.publicKey).isRight)
    // Invoice generation is not reproducible as the timestamp and path key will change but all other fields should be the same.
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
    assert(invoice.description.contains("yolo"))
  }

  test("invoice with non-minimally encoded feature bits"){
    val encodedInvoice = "lni1qqsyzre2s0lc77w5h33ck6540xxsyjehjl66f9tfp83w85zcxqyhltczyqrzymjxzydqkkw24ufxqslttwlj3s608f0rx2slc7etw0833zgs7zqyqh67zqq2qqgwsqktzd0na8g54f2r8secsaemc7ww2d6spl397celwcv20egnau2z8gp83d0dg7gvtkkvklnqlvp0erhq9nh9928rexerg578wnyew6dj6xczq2nqtavvd94k7jq2slng76uk560g6qeu38ru2gjjtdd4w9jxfqcc5qpnvvduearw4k75xdsgrc9ntzs274hwumtk5zwlrcr8yzwn8q0ry40f6lcmarq2nqkz9j2anajrlpchwwfguypms9x0uptvcsspwzjp3vg8srqx27crkqe8v9nzqaktzwwy5szk0rsq9sq7vhqncvv63mseqsx9lzmjraxhfnhc6f9tgnm05v7x0s4dhzwac9gruy44n9yht645cd4jzcssyjvcf2ptqztsenmzyw0e6kpx209mmmpal9ptutxpeygerepwh5rc2qsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzr6jqsae4jsq2spsyqqqtqss9g4l2s06jx69u2vtvezfmh07puh8pzhp76yddr7yvjpt2q38puqx5r7sgacrnpvghfhfzdzm9rertx4egjnarr2plwp26yfzcnv4ef536h9nu8lq9xyejhphnyv97axrqwr982vvedhfzj3cn5uhdymxwejfh55p2putqvpeskyt5m53x3dj3u34n2u5ff7334qlhq4dzy3vfk2u56gatje7rlsqgllx5cs3433fgn37scpz5ysn7df4tcfvgw5hgn998qut5l63vvmlv85xj4gj9rs6ja6gj45ddfjvwrcq9qthepk3xtpy4x8tsmmaqhas3v8k6chxp4ds8367lgw3q4mtpm5zmlr84tx4xpshtaxa0es0kcjuah80xt23pm08qprase5e2euq8ndvymuzcdznh78qyg28lw65wve2fpphd5zpwy4v3gfpa245dgtmqkp34gg8s4tfxytnx5vxhclwzmpzdy80jlfyznklk9t0karg42yvxqey68py3t0yg5rew5jke2sr6l5akw3r4x4cyp5f9ty27yjqtsn5ucqywkk84sxudl89xdxw34kvvtq67pk64r3kmyzz5dum0c66sjh7a5ylr6u38ycdmdq5rm7pp5m87rmsg7ntkqr4dcateeafrchaw085my236hxg47745nsrdtmjvnhy4a9ppd95g5m3u40wa0pcnmlhcm99xd0flh0484vht6ysx5cg5nmjxzaqsqv33sgptsrgmfuqgwjuvw5v58k379638h6hda8tqvpk4aexfmj27jsskj6y2dc72hhwhsufalmudjjnxh5lmh6n6kt4azgqg7en2fg446vmtj2zgncc9wv4sa8zhyxm60zadqlf664d8mhdx6g5g6cls2glkqdmayuvypt7fuljtswlmz4w5e8nkkpzr8m6txz7gzvfcexj9dmdhuhsx35lnwnmzm52vq2wgr49g25dwk4jlh0n2yq6yufpewngg7llkgxwqpr5nlruajj55sel09axp2tmkhaf2hkh2lsjyth098l2r2kfg7u9440ymwswpwd20j9zdp562ejm0yy0x68q4knmd6a6g4nz0a2nm3842yw4pdx8udqggqkxa03jwmrzzuzwp2mn6az3exhunlqcpmphsks3cur22l3hvzn74vqy0kf70r6hd5cy2va94czl9g594856j287cefqej8qlre5ewyc5l02wtsx0hcjr4jhup6z4rj46lmrylsr034r5w2csnsgcy83yz848lafh5wue9aue8grnpvghfhfzdzm9rertx4egjnarr2plwp26yfzcnv4ef536h9nu8lq86u0a3w8zcxwy9hj9gvdwv8fhahpdauyzmuegpkefl3xc798mft7qvpeskyt5m53x3dj3u34n2u5ff7334qlhq4dzy3vfk2u56gatje7rlsqg0xlmw039msmmqtt4jqkgqts08ervu9dsx05qwzr67dazwklna9yjzdker5mhmeghxde2jlu5gvl4wrshvrg6x6a0j7hqsgpcc3ngm0ucvftuq6k8q0tpgxknk3d3t8nc9p9frafrfndz788hkaut704urzsj06t45qy8qk5hewf9p3sej3m2xrwyk6ny5hg8t24aq50a7re8evssrd0nmtrpjttuj04nlhs8ygteqepyc6sg5lsdajrc63xjp26j7surx83vx5u4326qfk6vw0sqhme6cw9247ef75ymtz4mp3esduvl07ykrnzzre3aq5jgqzrzcj59yjdcvp38nq7uvdqwmnhvy0h7t9062znl8ly02k9d02tyxev6mf6we8ztfjrdu73wc6gctxg5lmgj4a8v8z9lzqdfvlsmcwzyznagl929pqqqqyfjqqqqqpszqcqqqqqqqqqq3xqqqqqqz0490jqqqgqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpzvsqqqqqvqsxqqqqqqqqqqyfsqqqqqqnaftusqqzqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqgnyqqqqqrqypsqqqqqqqqqpzvqqqqqqp4rw2vqqqsqqqqqqqqqqqqqqqqqqqqqqqqqzjqg6zm7ju2sgrmk0u67xstmskz34gfjfnjfxwvjltp3jsrd8rn40s7pgk8tzxwt64qgwu6egqtqggzfxvy4q4sp9cvea3z88uatqn98jaaas7ljs479nqujyv3usht6pu0qs8wdac52sykqfjnxg0xhva4fcv00hr4tqzjwkjnkayykkm9dnr97ladr5jjjx4xyjtun7ucye660akfv4nl9tupwnyemp0sasfxapvcw"
    val invoice = Bolt12Invoice.fromString(encodedInvoice).get
    assert(invoice.checkSignature())
    assert(invoice.amount == 1000000000.msat)
  }
}
