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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, VariableLengthOnion}
import fr.acinq.eclair.wire.protocol.OfferCodecs.invoiceRequestTlvCodec
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.{Features, MilliSatoshiLong, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.util.Success

class OfferTypesSpec extends AnyFunSuite {
  val nodeId = PublicKey(hex"024b9a1fa8e006f1e3937f65f66c408e6da8e1ca728ea43222a7381df1cc449605")

  test("sign and check offer") {
    val key = randomKey()
    val offer = Offer(Some(100_000 msat), "test offer", key.publicKey, Features(VariableLengthOnion -> Mandatory), Block.LivenetGenesisBlock.hash)
    assert(offer.signature.isEmpty)
    val signedOffer = offer.sign(key)
    assert(signedOffer.checkSignature())
  }

  test("invoice request is signed") {
    val sellerKey = randomKey()
    val offer = Offer(Some(100_000 msat), "test offer", sellerKey.publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 100_000 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.checkSignature())
  }

  test("basic offer") {
    val encoded = "lno1pg257enxv4ezqcneype82um50ynhxgrwdajx283qfwdpl28qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczs"
    val offer = Offer.decode(encoded).get
    assert(offer.amount.isEmpty)
    assert(offer.signature.isEmpty)
    assert(offer.description == "Offer by rusty's node")
    assert(offer.nodeId == nodeId)
  }

  test("basic signed offer") {
    val encodedSigned = "lno1pg257enxv4ezqcneype82um50ynhxgrwdajx283qfwdpl28qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczlqs85ck65ycmkdk92smwt9zuewdzfe7v4aavvaz5kgv9mkk63v3s0ge0f099kssh3yc95qztx504hu92hnx8ctzhtt08pgk0texz0509tk"
    val Success(signedOffer) = Offer.decode(encodedSigned)
    assert(signedOffer.checkSignature())
    assert(signedOffer.amount.isEmpty)
    assert(signedOffer.description == "Offer by rusty's node")
    assert(signedOffer.nodeId == nodeId)
  }

  test("offer with amount and quantity") {
    val encoded = "lno1pqqnyzsmx5cx6umpwssx6atvw35j6ut4v9h8g6t50ysx7enxv4epgrmjw4ehgcm0wfczucm0d5hxzagkqyq3ugztng063cqx783exlm97ekyprnd4rsu5u5w5sez9fecrhcuc3ykq5"
    val Success(offer) = Offer.decode(encoded)
    assert(offer.amount.contains(50 msat))
    assert(offer.signature.isEmpty)
    assert(offer.description == "50msat multi-quantity offer")
    assert(offer.nodeId == nodeId)
    assert(offer.issuer.contains("rustcorp.com.au"))
    assert(offer.quantityMin.contains(1))
  }

  test("signed offer with amount and quantity") {
    val encodedSigned = "lno1pqqnyzsmx5cx6umpwssx6atvw35j6ut4v9h8g6t50ysx7enxv4epgrmjw4ehgcm0wfczucm0d5hxzagkqyq3ugztng063cqx783exlm97ekyprnd4rsu5u5w5sez9fecrhcuc3ykqhcypjju7unu05vav8yvhn27lztf46k9gqlga8uvu4uq62kpuywnu6me8srgh2q7puczukr8arectaapfl5d4rd6uc7st7tnqf0ttx39n40s"
    val Success(signedOffer) = Offer.decode(encodedSigned)
    assert(signedOffer.checkSignature())
    assert(signedOffer.amount.contains(50 msat))
    assert(signedOffer.description == "50msat multi-quantity offer")
    assert(signedOffer.nodeId == nodeId)
    assert(signedOffer.issuer.contains("rustcorp.com.au"))
    assert(signedOffer.quantityMin.contains(1))
  }

  test("decode invalid offer") {
    val testCases = Seq(
      "lno1pgxx7enxv4e8xgrjda3kkgg", // missing node id
      "lno1rcsdhss957tylk58rmly849jupnmzs52ydhxhl8fgz7994xkf2hnwhg", // missing description
    )
    for (testCase <- testCases) {
      assert(Offer.decode(testCase).isFailure)
    }
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
    val biggerAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { case Amount(_) => Amount(3000 msat) case x => x })), payerKey)
    assert(biggerAmount.isValidFor(offer))
    val lowerAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { case Amount(_) => Amount(2000 msat) case x => x })), payerKey)
    assert(!lowerAmount.isValidFor(offer))
    val otherOfferId = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.map { case OfferId(_) => OfferId(randomBytes32()) case x => x })), payerKey)
    assert(!otherOfferId.isValidFor(offer))
    val withQuantity = signInvoiceRequest(request.copy(records = TlvStream(request.records.records ++ Seq(Quantity(1)))), payerKey)
    assert(!withQuantity.isValidFor(offer))
  }

  test("check that invoice request matches offer (with features)") {
    val offer = Offer(Some(2500 msat), "offer with features", randomKey().publicKey, Features(VariableLengthOnion -> Optional), Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 2500 msat, 1, Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Optional), payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.isValidFor(offer))
    val withoutFeatures = InvoiceRequest(offer, 2500 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(withoutFeatures.isValidFor(offer))
    val otherFeatures = InvoiceRequest(offer, 2500 msat, 1, Features(VariableLengthOnion -> Mandatory, BasicMultiPartPayment -> Mandatory), payerKey, Block.LivenetGenesisBlock.hash)
    assert(!otherFeatures.isValidFor(offer))
  }

  test("check that invoice request matches offer (without amount)") {
    val offer = Offer(None, "offer without amount", randomKey().publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 500 msat, 1, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.isValidFor(offer))
    val withoutAmount = signInvoiceRequest(request.copy(records = TlvStream(request.records.records.filter { case Amount(_) => false case _ => true })), payerKey)
    assert(!withoutAmount.isValidFor(offer))
  }

  test("check that invoice request matches offer (chain compatibility)") {
    {
      val offer = Offer(TlvStream(Seq(Amount(100 msat), Description("offer without chains"), NodeId(randomKey().publicKey))))
      val payerKey = randomKey()
      val request = {
        val tlvs: Seq[InvoiceRequestTlv] = Seq(
          OfferId(offer.offerId),
          Amount(100 msat),
          PayerKey(payerKey.publicKey),
          FeaturesTlv(Features.empty)
        )
        val signature = signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs), invoiceRequestTlvCodec), payerKey)
        InvoiceRequest(TlvStream(tlvs :+ Signature(signature)))
      }
      assert(request.isValidFor(offer))
      val withDefaultChain = signInvoiceRequest(request.copy(records = TlvStream(request.records.records ++ Seq(Chain(Block.LivenetGenesisBlock.hash)))), payerKey)
      assert(withDefaultChain.isValidFor(offer))
      val otherChain = signInvoiceRequest(request.copy(records = TlvStream(request.records.records ++ Seq(Chain(Block.TestnetGenesisBlock.hash)))), payerKey)
      assert(!otherChain.isValidFor(offer))
    }
    {
      val (chain1, chain2) = (randomBytes32(), randomBytes32())
      val offer = Offer(TlvStream(Seq(Chains(Seq(chain1, chain2)), Amount(100 msat), Description("offer with chains"), NodeId(randomKey().publicKey))))
      val payerKey = randomKey()
      val request1 = InvoiceRequest(offer, 100 msat, 1, Features.empty, payerKey, chain1)
      assert(request1.isValidFor(offer))
      val request2 = InvoiceRequest(offer, 100 msat, 1, Features.empty, payerKey, chain2)
      assert(request2.isValidFor(offer))
      val noChain = signInvoiceRequest(request1.copy(records = TlvStream(request1.records.records.filter { case Chain(_) => false case _ => true })), payerKey)
      assert(!noChain.isValidFor(offer))
      val otherChain = signInvoiceRequest(request1.copy(records = TlvStream(request1.records.records.map { case Chain(_) => Chain(Block.LivenetGenesisBlock.hash) case x => x })), payerKey)
      assert(!otherChain.isValidFor(offer))
    }
  }

  test("check that invoice request matches offer (multiple items)") {
    val offer = Offer(TlvStream(
      Amount(500 msat),
      Description("offer for multiple items"),
      NodeId(randomKey().publicKey),
      QuantityMin(3),
      QuantityMax(10),
    ))
    val payerKey = randomKey()
    val request = InvoiceRequest(offer, 1600 msat, 3, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(request.records.get[Quantity].nonEmpty)
    assert(request.isValidFor(offer))
    val invalidAmount = InvoiceRequest(offer, 2400 msat, 5, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(!invalidAmount.isValidFor(offer))
    val tooFewItems = InvoiceRequest(offer, 1000 msat, 2, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(!tooFewItems.isValidFor(offer))
    val tooManyItems = InvoiceRequest(offer, 5500 msat, 11, Features.empty, payerKey, Block.LivenetGenesisBlock.hash)
    assert(!tooManyItems.isValidFor(offer))
  }

  test("decode invoice request") {
    val encoded = "lnr1qvsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqqyypz8xu3xwsqpar9dd26lgrrvc7s63ljt0pgh6ag2utv5udez7n2mjzqzz47qcqczqgqzqqgzycsv2tmjgzc5l546aldq699wj9pdusvfred97l352p4aa862vqvzw5p8pdyjqctdyppxzardv9hrypx74klwluzqd0rqgeew2uhuagttuv6aqwklvm0xmlg52lfnagzw8ygt0wrtnv2tsx69m6tgug7njaw5ypa5fn369n9yzc87v02rqccj9h04dxf3nzc"
    val Success(request) = InvoiceRequest.decode(encoded)
    assert(request.amount == Some(5500 msat))
    assert(request.offerId == ByteVector32(hex"4473722674001e8cad6ab5f40c6cc7a1a8fe4b78517d750ae2d94e3722f4d5b9"))
    assert(request.quantity == 2)
    assert(request.features == Features(VariableLengthOnion -> Optional, BasicMultiPartPayment -> Optional))
    assert(request.records.get[Chain].nonEmpty)
    assert(request.chain == Block.LivenetGenesisBlock.hash)
    assert(request.payerKey == ByteVector32(hex"c52f7240b14fd2baefda0d14ae9142de41891e5a5f7e34506bde9f4a60182750"))
    assert(request.payerInfo == Some(hex"deadbeef"))
    assert(request.payerNote == Some("I am Batman"))
    assert(request.encode() == encoded)
  }

  test("decode invalid invoice request") {
    val testCases = Seq(
      // Missing offer id.
      "lnr1pqpp8zqvqqnzqq7pw52tqj6pj2mar5cgkmnt9xe3tj40nxc3pp95xml2e8v432ny7pq957u2v4r5cjxfmxtwk9qfu99hftq2ek48pz6c2ywynajha03ut4ffjf34htxxxp668dqd9jwvz2eal6up5mjfe4ad8ndccrtpkkke0g",
      // Missing payer key.
      "lnr1qss0h356hn94473j5yls8q3w4gkzu9j8rrach3hgms4ks8aumsx29vsgqgfcsrqq7pq957u2v4r5cjxfmxtwk9qfu99hftq2ek48pz6c2ywynajha03ut4ffjf34htxxxp668dqd9jwvz2eal6up5mjfe4ad8ndccrtpkkke0g",
      // Missing signature.
      "lnr1qss0h356hn94473j5yls8q3w4gkzu9j8rrach3hgms4ks8aumsx29vsgqgfcsrqqycsq8st4zjcyksvjklgaxz9ku6efkv2u4tuekyggfdpkl6kfm9v25eq",
    )
    for (testCase <- testCases) {
      assert(InvoiceRequest.decode(testCase).isFailure)
    }
  }

  test("compute merkle tree root") {
    import scodec.Codec
    import scodec.codecs.list

    case class TestCase(tlvs: ByteVector, count: Int, expected: ByteVector32)

    val testCases = Seq(
      // Official test vectors.
      TestCase(hex"010203e8", 1, ByteVector32(hex"aa0aa0f694c85492ac459c1de9831a37682985f5e840ecc9b1e28eece7dc5236")),
      TestCase(hex"010203e8 02080000010000020003", 2, ByteVector32(hex"013b756ed73554cbc4dd3d90f363cb7cba6d8a279465a21c464e582b173ff502")),
      TestCase(hex"010203e8 02080000010000020003 03310266e4598d1d3c415f572a8488830b60f7e744ed9235eb0b1ba93283b315c0351800000000000000010000000000000002", 3, ByteVector32(hex"016fcda3b6f9ca30b35936877ca591fa101365a761a1453cfd9436777d593656")),
      TestCase(hex"0603555344 080203e8 0a0f313055534420657665727920646179 141072757374792e6f7a6c6162732e6f7267 1a020101 1e204b9a1fa8e006f1e3937f65f66c408e6da8e1ca728ea43222a7381df1cc449605", 6, ByteVector32(hex"7cef68df49fd9222bed0138ca5603da06464b2f523ea773bc4edcb6bd07966e7")),
      // Additional test vectors.
      TestCase(hex"010100", 1, ByteVector32(hex"c8112b235945b06a11995bf69956a93ff0403c28de35bd33b4714da1b6239ebb")),
      TestCase(hex"010100 020100", 2, ByteVector32(hex"8271d606bea3ef49e59d610585317edfc6c53d8d1afd763731919d9a7d70a7d9")),
      TestCase(hex"010100 020100 030100", 3, ByteVector32(hex"c7eff290817749d87eede061d5335559e8211769e651a2ee5c5e7d2ddd655236")),
      TestCase(hex"010100 020100 030100 040100", 4, ByteVector32(hex"57883ef2f1e8df4a23e6f0a2e3acda2ed0b11e00ef2d39fe1caa2d71d7273c37")),
      TestCase(hex"010100 020100 030100 040100 050100", 5, ByteVector32(hex"85b74f254eced46c525a5369c52f86f249a41f6f6ccb3c918ffe4025ea22d8b6")),
      TestCase(hex"010100 020100 030100 040100 050100 060100", 6, ByteVector32(hex"6cf27da8a67b7cb199dd1824017cb008bd22bf1d57273a8c4544c5408275dc2d")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100", 7, ByteVector32(hex"2a038f022b51b1b969563679a22eb167ef603d5b2cb2d0dbe86dc4d2f48d8c6e")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100", 8, ByteVector32(hex"8ddbe97f6ed2e2a4a43e828e350f9cb6679b7d5f16837273cf0e6f7da342fa19")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100", 9, ByteVector32(hex"8050bed857ff7929a24251e3a517fc14f46fb0f02e6719cb9d53087f7f047f6d")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100", 10, ByteVector32(hex"e22aa818e746ff9613e6cccc99ebce257d93c35736b168b6b478d6f3762f56ce")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100", 11, ByteVector32(hex"626e3159cec72534155ccf691a84ab68da89e6cd679a118c70a28fd1f1bb10cc")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100", 12, ByteVector32(hex"f659da1c839d99a2c6b104d179ee44ffe3a9eaa55831de3c612c8c293c27401b")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100", 13, ByteVector32(hex"c165756a94718d19e66ff7b581347699009a9e17805e16cb6ba94c034c7dc757")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100", 14, ByteVector32(hex"573b85bbceacbf1b189412858ac6573e923bbf0c9cfdc37d37757996f6086208")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100", 15, ByteVector32(hex"84a3088fe74b82ee82a9415d48fdfad8dc6a286fec8e6fcdcefcf0bc02f3256e")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100", 16, ByteVector32(hex"a686f116fce33e43fa875fec2253c71694a0324e1ce7640ed1070b0cc3a14cc1")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100", 17, ByteVector32(hex"fbee87d6726c8b67a8d2e2bff92b13d0b1d9188f9d42af2d3afefceaafa6f3e5")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100", 18, ByteVector32(hex"5004f619c426b01e57c480a84d5dcdc3a70b4bf575ec573be60c3a75ed978b72")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100", 19, ByteVector32(hex"6f0a5e59f1fa5dc6c12ed3bbe0eb91c818b22a8d011d5a2160462c59e6158a58")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100", 20, ByteVector32(hex"e43f00c262e4578c5ed4413ab340e79cb8b258241b7c52550b7307f7b9c4d645")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100", 21, ByteVector32(hex"e46776637883bae1a62cbfb621c310c13e6c522092954e08d74c08328d53f035")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100", 22, ByteVector32(hex"813ebe9f07638005abbe270f11ae2749a5b9b0c5cf89a305598303a38f5f2da5")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100", 23, ByteVector32(hex"fdd7b779192dcbadb5695303e2bcee0fc175428278bdbfa4b4445251df6c9450")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100", 24, ByteVector32(hex"33c92b7820742d094548328ee3bfdf29bf3fe1f971171dcd2a6da0f185dceddb")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100", 25, ByteVector32(hex"888da09f5ba1b8e431b3ab1db62fca94c0cbbec6b145012d9308d20f68571ff2")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100", 26, ByteVector32(hex"a87cdc040109b855d81f13af4a6f57cdb7e31252eeb83bc03518fdd6dd81ec18")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100", 27, ByteVector32(hex"9829715a0d8cbb5c080de53704f274aa4da3590e8338d57ce99ab491d7a44e76")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100", 28, ByteVector32(hex"ce8bac7c3d10b528d59d5f391bf36bb6acd65b4bb3cbd0a769488e3b451b2c26")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100", 29, ByteVector32(hex"88d29ac3e4ae8761058af4b1baaa873ec4f76822166f8dfc2888bcbb51212130")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100", 30, ByteVector32(hex"b013259fe32c6eaf88d2b3b2d01350e5505bcc0fcdcdc7c360e5644fe827424d")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100 1f0100", 31, ByteVector32(hex"1c60489269d312c2ea94c637936e38a968d2900cab6c5544db091aa8b3bb5176")),
      TestCase(hex"010100 020100 030100 040100 050100 060100 070100 080100 090100 0a0100 0b0100 0c0100 0d0100 0e0100 0f0100 100100 110100 120100 130100 140100 150100 160100 170100 180100 190100 1a0100 1b0100 1c0100 1d0100 1e0100 1f0100 200100", 32, ByteVector32(hex"e0d88bd7685ffd55e0de4e45e190e7e6bf1ecc0a7d1a32fbdaa6b1b27e8bc37b")),
    )
    testCases.foreach {
      case TestCase(tlvStream, tlvCount, expectedRoot) =>
        val genericTlvStream: Codec[TlvStream[GenericTlv]] = list(TlvCodecs.genericTlv).xmap(tlvs => TlvStream(tlvs), tlvs => tlvs.records.toList)
        val tlvs = genericTlvStream.decode(tlvStream.bits).require.value
        assert(tlvs.records.size == tlvCount)
        val root = OfferTypes.rootHash(tlvs, genericTlvStream)
        assert(root == expectedRoot)
    }
  }

}
