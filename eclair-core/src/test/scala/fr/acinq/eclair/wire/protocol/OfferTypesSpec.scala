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

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.BasicMultiPartPayment
import fr.acinq.eclair.wire.protocol.OfferCodecs.{invoiceRequestTlvCodec, offerTlvCodec}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.{Features, MilliSatoshiLong, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

class OfferTypesSpec extends AnyFunSuite {
  val nodeKey = PrivateKey(hex"85d08273493e489b9330c85a3e54123874c8cd67c1bf531f4b926c9c555f8e1d")
  val nodeId = nodeKey.publicKey

  test("invoice request is signed") {
    val sellerKey = randomKey()
    val offer = Offer(Some(100_000 msat), "test offer", sellerKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 100_000 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.checkSignature())
  }

  test("minimal offer") {
    val tlvs = Set[OfferTlv](
      OfferDescription("basic offer"),
      OfferNodeId(nodeId))
    val offer = Offer(TlvStream(tlvs))
    val encoded = "lno1pg9kyctnd93jqmmxvejhy93pqvxl9c6mjgkeaxa6a0vtxqteql688v0ywa8qqwx4j05cyskn8ncrj"
    assert(Offer.decode(encoded).get == offer)
    assert(offer.amount.isEmpty)
    assert(offer.description == "basic offer")
    assert(offer.nodeId == nodeId)
    // Removing any TLV from the minimal offer makes it invalid.
    for (tlv <- tlvs) {
      val incomplete = TlvStream[OfferTlv](tlvs.filterNot(_ == tlv))
      assert(Offer.validate(incomplete).isLeft)
      val incompleteEncoded = Bech32.encodeBytes(Offer.hrp, offerTlvCodec.encode(incomplete).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
      assert(Offer.decode(incompleteEncoded).isFailure)
    }
  }

  test("offer with amount and quantity") {
    val offer = Offer(TlvStream[OfferTlv](
      OfferChains(Seq(Block.TestnetGenesisBlock.hash)),
      OfferAmount(50 msat),
      OfferDescription("offer with quantity"),
      OfferIssuer("alice@bigshop.com"),
      OfferQuantityMax(0),
      OfferNodeId(nodeKey.publicKey)))
    val encoded = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqyeq5ym0venx2u3qwa5hg6pqw96kzmn5d968jys3v9kxjcm9gp3xjemndphhqtnrdak3gqqkyypsmuhrtwfzm85mht4a3vcp0yrlgua3u3m5uqpc6kf7nqjz6v70qwg"
    assert(Offer.decode(encoded).get == offer)
    assert(offer.amount.contains(50 msat))
    assert(offer.description == "offer with quantity")
    assert(offer.nodeId == nodeId)
    assert(offer.issuer.contains("alice@bigshop.com"))
    assert(offer.quantityMax.contains(Long.MaxValue))
  }

  def signInvoiceRequest(request: InvoiceRequest, key: PrivateKey): InvoiceRequest = {
    val tlvs = removeSignature(request.records)
    val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(tlvs, invoiceRequestTlvCodec), key)
    val signedRequest = InvoiceRequest(tlvs.copy(records = tlvs.records ++ Seq(Signature(signature))))
    assert(signedRequest.checkSignature())
    signedRequest
  }

  test("check that invoice request matches offer") {
    val offer = Offer(Some(2500 msat), "basic offer", randomKey().publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 2500 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.isValidFor(offer))
    val biggerAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { case InvoiceRequestAmount(_) => InvoiceRequestAmount(3000 msat) case x => x })), payerKey)
    assert(biggerAmount.isValidFor(offer))
    val lowerAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { case InvoiceRequestAmount(_) => InvoiceRequestAmount(2000 msat) case x => x })), payerKey)
    assert(!lowerAmount.isValidFor(offer))
    val withQuantity = signInvoiceRequest(request.copy(records = TlvStream(request.records.records ++ Seq(InvoiceRequestQuantity(1)))), payerKey)
    assert(!withQuantity.isValidFor(offer))
  }

  test("check that invoice request matches offer (with features)") {
    val offer = Offer(Some(2500 msat), "offer with features", randomKey().publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 2500 msat, 1, Features(BasicMultiPartPayment -> Optional), payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.isValidFor(offer))
    val withoutFeatures = InvoiceRequest(offer, 2500 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(withoutFeatures.isValidFor(offer))
    val otherFeatures = InvoiceRequest(offer, 2500 msat, 1, Features(BasicMultiPartPayment -> Mandatory), payerKey, Block.LivenetGenesisBlock.hash)
    assert(!otherFeatures.isValidFor(offer))
  }

  test("check that invoice request matches offer (without amount)") {
    val offer = Offer(None, "offer without amount", randomKey().publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 500 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.isValidFor(offer))
    val withoutAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.filter { case InvoiceRequestAmount(_) => false case _ => true })), payerKey)
    assert(!withoutAmount.isValidFor(offer))
  }

  test("check that invoice request matches offer (chain compatibility)") {
    {
      val offer = Offer(TlvStream(OfferAmount(100 msat), OfferDescription("offer without chains"), OfferNodeId(randomKey().publicKey)))
      val payerKey = randomKey()
      val request = {
        val tlvs: Set[InvoiceRequestTlv] = offer.records.records ++ Set(
          InvoiceRequestMetadata(hex"012345"),
          InvoiceRequestAmount(100 msat),
          InvoiceRequestPayerId(payerKey.publicKey),
        )
        val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), invoiceRequestTlvCodec), payerKey)
        InvoiceRequest(TlvStream(tlvs + Signature(signature)))
      }
      assert(request.isValidFor(offer))
      val withDefaultChain = signInvoiceRequest(request.copy(records = TlvStream(request.records.records ++ Seq(InvoiceRequestChain(Block.LivenetGenesisBlock.hash)))), payerKey)
      assert(withDefaultChain.isValidFor(offer))
      val otherChain = signInvoiceRequest(request.copy(records = TlvStream(request.records.records ++ Seq(InvoiceRequestChain(Block.TestnetGenesisBlock.hash)))), payerKey)
      assert(!otherChain.isValidFor(offer))
    }
    {
      val (chain1, chain2) = (randomBytes32(), randomBytes32())
      val offer = Offer(TlvStream(OfferChains(Seq(chain1, chain2)), OfferAmount(100 msat), OfferDescription("offer with chains"), OfferNodeId(randomKey().publicKey)))
      val payerKey = randomKey()
      val request1 = InvoiceRequest(offer, 100 msat, 1, Features.empty, payerKey, chain1)
      assert(request1.isValidFor(offer))
      val request2 = InvoiceRequest(offer, 100 msat, 1, Features.empty, payerKey, chain2)
      assert(request2.isValidFor(offer))
      val noChain = signInvoiceRequest(request1.copy(records = TlvStream(request1.records.records.filter { case InvoiceRequestChain(_) => false case _ => true })), payerKey)
      assert(!noChain.isValidFor(offer))
      val otherChain = signInvoiceRequest(request1.copy(records = TlvStream(request1.records.records.map { case InvoiceRequestChain(_) => InvoiceRequestChain(Block.LivenetGenesisBlock.hash) case x => x })), payerKey)
      assert(!otherChain.isValidFor(offer))
    }
  }

  test("check that invoice request matches offer (multiple items)") {
    val offer = Offer(TlvStream(
      OfferAmount(500 msat),
      OfferDescription("offer for multiple items"),
      OfferNodeId(randomKey().publicKey),
      OfferQuantityMax(10),
    ))
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 1600 msat, 3, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.records.get[InvoiceRequestQuantity].nonEmpty)
    assert(request.isValidFor(offer))
    val invalidAmount = InvoiceRequest(offer, 2400 msat, 5, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(!invalidAmount.isValidFor(offer))
    val tooManyItems = InvoiceRequest(offer, 5500 msat, 11, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(!tooManyItems.isValidFor(offer))
  }

  test("minimal invoice request") {
    val payerKey = PrivateKey(hex"527d410ec920b626ece685e8af9abc976a48dbf2fe698c1b35d90a1c5fa2fbca")
    val tlvsWithoutSignature = Set[InvoiceRequestTlv](
      InvoiceRequestMetadata(hex"abcdef"),
      OfferDescription("basic offer"),
      OfferNodeId(nodeId),
      InvoiceRequestPayerId(payerKey.publicKey),
    )
    val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream[InvoiceRequestTlv](tlvsWithoutSignature), OfferCodecs.invoiceRequestTlvCodec), payerKey)
    val tlvs = tlvsWithoutSignature + Signature(signature)
    val invoiceRequest = InvoiceRequest(TlvStream(tlvs))
    val encoded = "lnr1qqp6hn00pg9kyctnd93jqmmxvejhy93pqvxl9c6mjgkeaxa6a0vtxqteql688v0ywa8qqwx4j05cyskn8ncrjkppqfxajawru7sa7rt300hfzs2lyk2jrxduxrkx9lmzy6lxcvfhk0j7ruzqc4mtjj5fwukrqp7faqrxn664nmwykad76pu997terewcklsx47apag59wf8exly4tky7y63prr7450n28stqssmzuf48w7e6rjad2eq"
    assert(InvoiceRequest.decode(encoded).get == invoiceRequest)
    assert(invoiceRequest.offer.amount.isEmpty)
    assert(invoiceRequest.offer.description == "basic offer")
    assert(invoiceRequest.offer.nodeId == nodeId)
    assert(invoiceRequest.metadata == hex"abcdef")
    assert(invoiceRequest.payerId == payerKey.publicKey)
    // Removing any TLV from the minimal invoice request makes it invalid.
    for (tlv <- tlvs) {
      val incomplete = TlvStream[InvoiceRequestTlv](tlvs.filterNot(_ == tlv))
      assert(InvoiceRequest.validate(incomplete).isLeft)
      val incompleteEncoded = Bech32.encodeBytes(InvoiceRequest.hrp, invoiceRequestTlvCodec.encode(incomplete).require.bytes.toArray, Bech32.Encoding.Beck32WithoutChecksum)
      assert(InvoiceRequest.decode(incompleteEncoded).isFailure)
    }
  }

  test("compute merkle tree root") {
    import scodec.Codec
    import scodec.codecs.list

    case class TestCase(tlvs: ByteVector, count: Int, expected: ByteVector32)

    val testCases = Seq(
      // Official test vectors.
      TestCase(hex"010203e8", 1, ByteVector32(hex"b013756c8fee86503a0b4abdab4cddeb1af5d344ca6fc2fa8b6c08938caa6f93")),
      TestCase(hex"010203e8 02080000010000020003", 2, ByteVector32(hex"c3774abbf4815aa54ccaa026bff6581f01f3be5fe814c620a252534f434bc0d1")),
      TestCase(hex"010203e8 02080000010000020003 03310266e4598d1d3c415f572a8488830b60f7e744ed9235eb0b1ba93283b315c0351800000000000000010000000000000002", 3, ByteVector32(hex"ab2e79b1283b0b31e0b035258de23782df6b89a38cfa7237bde69aed1a658c5d")),
      TestCase(hex"0008000000000000000006035553440801640a1741204d617468656d61746963616c205472656174697365162102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661958210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c", 6, ByteVector32(hex"608407c18ad9a94d9ea2bcdbe170b6c20c462a7833a197621c916f78cf18e624")),
      // Additional test vectors.
      TestCase(hex"010100", 1, ByteVector32(hex"14ffa5e1e5d861059abff167dad6e632c45483006f7d4dc4355586062a3da30d")),
      TestCase(hex"010100 020100", 2, ByteVector32(hex"ec0584e764b71cb49ebe60ce7edbab8387e42da20b6077031bd27ff345b38ff8")),
      TestCase(hex"010100 020100 030100", 3, ByteVector32(hex"cc68aea3dc863832ef6828b3da8689cce3478c934cc50a68522477506a35feb2")),
      TestCase(hex"010100 020100 030100 040100", 4, ByteVector32(hex"b531eaa1ca71956148a6756cf8f46bdf231879e6c392019877f23e56acb7b956")),
      TestCase(hex"010100 020100 030100 040100 050100", 5, ByteVector32(hex"104e383bfdcb620cd8cefa95245332e8bd32ffd8d974fffdafe1488b1f4a1fbd")),
      TestCase(hex"010100 020100 030100 040100 050100 060100", 6, ByteVector32(hex"d96f0769702cb3440abbe683d7211fd20bd152699352f09f45d2695a89d18cdc")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100", 7, ByteVector32(hex"30b8886e306c97dbc7b730a2e99138c1ea4fdf5c2f71e2a31e434f63f5eed228")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100", 8, ByteVector32(hex"783262efe5eeef4ec96bcee8d7cf5149ea44e0c28a78f4b1cb73d6cec9a0b378")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100", 9, ByteVector32(hex"6fd20b65a0097aff2bcc70753612a296edc27933ea335bac5df2e4c724cdb43c")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100", 10, ByteVector32(hex"9a3cf7785e9c84e03d6bc7fc04226a1cb19f158a69f16684663aa710bd90a14b")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100", 11, ByteVector32(hex"ace50a04d9dc82ce123c6ac6c2449fa607054560a9a7b8229cd2d47c01b94953")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100", 12, ByteVector32(hex"1a8e85042447a10ec312b35db34d0c8722caba4aaf6a170c4506d1fdb520aa66")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100", 13, ByteVector32(hex"8c3b8d9ba90eb9a4a34c890a7a24ba6ddc873529c5fd7c95f33a5b9ba589f54b")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100", 14, ByteVector32(hex"ed9e3694bbad2fca636576cc69af4c63ad64023bfeb788fe0f40b3533b248a6a")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100", 15, ByteVector32(hex"bab201e05786ae1eae4d685b4f815134158720ba297ea0f46a9420ffe5e94b16")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100", 16, ByteVector32(hex"44438261bb64672f374d8782e92dc9616e900378ce4bd64442753722bc2a1acb")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100", 17, ByteVector32(hex"bb6fbcd5cf426ec0b7e49d9f9ccc6c15319e01f007cce8f16fa802016718b9f7")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100", 18, ByteVector32(hex"64d8639e76af096223cad2c448d68fabf751d1c6a939bc86e1015b19188202dc")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100", 19, ByteVector32(hex"bcb88f8e06886a6d422d14bc2ed4e7fc06c0ad2adeedf630a73972c5b15538ca")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100", 20, ByteVector32(hex"9deddd5f0ab909e6a161fd4b9d44ed7384ee0a7fe8d3fbb637872767eab82f1e")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100", 21, ByteVector32(hex"4a32a2325bbd1c2b5b4915c6bec6b3e3d734d956e0c123f1fa6d70f7a8609dcd")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100", 22, ByteVector32(hex"a3ec28f0f9cb64db8d96dd7b9039fbf2240438401ea992df802d7bb70b3d02af")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100", 23, ByteVector32(hex"d025f268ec4f09baf51c4b94287e76707d9353e8cab31dc586ae47742ba0b266")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100", 24, ByteVector32(hex"cd5a2086a3919d67d0617da1e6e293f115bed8d8306498ed814c6c109ad370a4")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100", 25, ByteVector32(hex"f64113810b52f4d6a55380a3d84e59e34d26c145448121c2113a023cb63de71b")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100", 26, ByteVector32(hex"b99d7332ea2db048093a7bc0aaa85f82ccfa9da2b734fc0a14b79c5dac5a3a1c")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100", 27, ByteVector32(hex"fab01a3ce6e878942dc5c9c862cb18e88202d50e6026d2266748f7eda5f9db7f")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100", 28, ByteVector32(hex"2dc8b24a0e142d1ed36a144ed35ef0d4b7d0d1b51e198b2282248e45ebaf0417")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100", 29, ByteVector32(hex"3693a858cc97762d69d05b2191d3e5254c29ddb5abac5b9fe52b227fa216aa4c")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100", 30, ByteVector32(hex"db8787d4509265e764e60b7a81cf38efb9d3a7910d67c4ae68a1232436e1cd3b")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100 1f0100", 31, ByteVector32(hex"af49f35e5b2565cb229f342405783d330c56031f005a4a6ca01f87e5637d4614")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100 1f0100 200100", 32, ByteVector32(hex"2e9f8a8542576197650f61c882625f0f6838f962f9fa24ce809b687784a8a7de")),
    )
    testCases.foreach {
      case TestCase(tlvStream, tlvCount, expectedRoot) =>
        val genericTlvStream: Codec[TlvStream[GenericTlv]] = list(TlvCodecs.genericTlv).xmap(tlvs => TlvStream(tlvs.toSet), tlvs => tlvs.records.toList.sortBy(_.tag))
        val tlvs = genericTlvStream.decode(tlvStream.bits).require.value
        assert(tlvs.records.size == tlvCount)
        val root = OfferTypes.rootHash(tlvs, genericTlvStream)
      assert(root == expectedRoot)
    }
  }

}
