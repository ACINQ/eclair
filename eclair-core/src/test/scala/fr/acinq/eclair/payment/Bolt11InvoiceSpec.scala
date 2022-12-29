/*
 * Copyright 2019 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, BtcDouble, ByteVector32, Crypto, MilliBtcDouble, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{PaymentMetadata, PaymentSecret, _}
import fr.acinq.eclair.payment.Bolt11Invoice._
import fr.acinq.eclair.{CltvExpiryDelta, Feature, FeatureSupport, Features, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TestConstants, TimestampSecond, TimestampSecondLong, ToMilliSatoshiConversion, UnknownFeature, randomBytes32}
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits._
import scodec.codecs.bits

import scala.concurrent.duration.DurationInt
import scala.util.Success

/**
 * Created by fabrice on 15/05/17.
 */

class Bolt11InvoiceSpec extends AnyFunSuite {

  val priv = PrivateKey(hex"e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734")
  val pub = priv.publicKey
  val nodeId = pub
  assert(nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))

  // Copy of Bolt11Invoice.apply that doesn't strip unknown features
  def createInvoiceUnsafe(chainHash: ByteVector32,
                          amount: Option[MilliSatoshi],
                          paymentHash: ByteVector32,
                          privateKey: PrivateKey,
                          description: Either[String, ByteVector32],
                          minFinalCltvExpiryDelta: CltvExpiryDelta,
                          fallbackAddress: Option[String] = None,
                          expirySeconds: Option[Long] = None,
                          extraHops: List[List[ExtraHop]] = Nil,
                          timestamp: TimestampSecond = TimestampSecond.now(),
                          paymentSecret: ByteVector32 = randomBytes32(),
                          paymentMetadata: Option[ByteVector] = None,
                          features: Features[Feature] = defaultFeatures.unscoped()): Bolt11Invoice = {
    require(features.hasFeature(Features.PaymentSecret, Some(FeatureSupport.Mandatory)), "invoices must require a payment secret")
    val prefix = prefixes(chainHash)
    val tags = {
      val defaultTags = List(
        Some(PaymentHash(paymentHash)),
        Some(description.fold(Description, DescriptionHash)),
        Some(Bolt11Invoice.PaymentSecret(paymentSecret)),
        paymentMetadata.map(Bolt11Invoice.PaymentMetadata),
        fallbackAddress.map(FallbackAddress(_)),
        expirySeconds.map(Expiry(_)),
        Some(MinFinalCltvExpiry(minFinalCltvExpiryDelta.toInt)),
        Some(InvoiceFeatures(features))
      ).flatten
      val routingInfoTags = extraHops.map(RoutingInfo)
      defaultTags ++ routingInfoTags
    }
    Bolt11Invoice(
      prefix = prefix,
      amount_opt = amount,
      createdAt = timestamp,
      nodeId = privateKey.publicKey,
      tags = tags,
      signature = ByteVector.empty
    ).sign(privateKey)
  }

  test("check minimal unit is used") {
    assert('p' == Amount.unit(1 msat))
    assert('p' == Amount.unit(99 msat))
    assert('n' == Amount.unit(100 msat))
    assert('p' == Amount.unit(101 msat))
    assert('n' == Amount.unit((1 sat).toMilliSatoshi))
    assert('u' == Amount.unit((100 sat).toMilliSatoshi))
    assert('n' == Amount.unit((101 sat).toMilliSatoshi))
    assert('u' == Amount.unit((1155400 sat).toMilliSatoshi))
    assert('m' == Amount.unit((1 millibtc).toMilliSatoshi))
    assert('m' == Amount.unit((10 millibtc).toMilliSatoshi))
    assert('m' == Amount.unit((1 btc).toMilliSatoshi))
  }

  test("decode empty amount") {
    assert(Amount.decode("") == Success(None))
    assert(Amount.decode("0") == Success(None))
    assert(Amount.decode("0p") == Success(None))
    assert(Amount.decode("0n") == Success(None))
    assert(Amount.decode("0u") == Success(None))
    assert(Amount.decode("0m") == Success(None))
  }

  test("check that we can still decode non-minimal amount encoding") {
    assert(Amount.decode("1000u") == Success(Some(100000000 msat)))
    assert(Amount.decode("1000000n") == Success(Some(100000000 msat)))
    assert(Amount.decode("1000000000p") == Success(Some(100000000 msat)))
  }

  test("data string -> bitvector") {
    assert(string2Bits("p") == bin"00001")
    assert(string2Bits("pz") == bin"0000100010")
  }

  test("minimal length long, left-padded to be multiple of 5") {
    assert(long2bits(0) == bin"")
    assert(long2bits(1) == bin"00001")
    assert(long2bits(42) == bin"0000101010")
    assert(long2bits(255) == bin"0011111111")
    assert(long2bits(256) == bin"0100000000")
    assert(long2bits(3600) == bin"000111000010000")
  }

  test("verify that padding is zero") {
    val codec = Bolt11Invoice.Codecs.alignedBytesCodec(bits)
    assert(codec.decode(bin"1010101000").require == DecodeResult(bin"10101010", BitVector.empty))
    assert(codec.decode(bin"1010101001").isFailure) // non-zero padding
  }

  test("Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad") {
    val ref = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.isEmpty)
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.paymentSecret.bytes == hex"1111111111111111111111111111111111111111111111111111111111111111")
    assert(invoice.features == Features(Features.VariableLengthOnion -> Mandatory, Features.PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("Please consider supporting this project"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.tags.size == 4)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Please send $3 for a cup of coffee to the same peer, within 1 minute") {
    val ref = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(250000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(Features.VariableLengthOnion -> Mandatory, Features.PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("1 cup coffee"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Please send 0.0025 BTC for a cup of nonsense (ナンセンス 1杯) to the same peer, within one minute") {
    val ref = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpu9qrsgqhtjpauu9ur7fw2thcl4y9vfvh4m9wlfyz2gem29g5ghe2aak2pm3ps8fdhtceqsaagty2vph7utlgj48u0ged6a337aewvraedendscp573dxr"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(250000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("ナンセンス 1杯"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Now send $24 for an entire list of things (hashed)") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.tags.size == 4)
    assert(invoice.sign(priv).toString == ref)
  }

  test("The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP") {
    val ref = "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989qrsgqdj545axuxtnfemtpwkc45hx9d2ft7x04mt8q7y6t0k2dge9e7h8kpy9p34ytyslj3yu569aalz2xdk8xkd7ltxqld94u8h2esmsmacgpghe9k8"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lntb")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().contains("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("Parse a signet invoice") {
    val ref = "lntbs2500u1p30ukj3pp5flaka84tayag36uj06k58tn5zukel0paskxwmc0gppc6t6utmp3sdq809hkcmcsp5uv40j7x3dx6sqfrefmj6lu933zakwedejyl4rsakv2lzg53fqxlsmqz9gxqrrsscqp79q2sqqqqqysgqmq30dcj5l8lc02e6pxq0wmagy5qafc05hv6e0yd6ftudes2awa8psu63tuf39dch0dk3hdckfd64g2v3y58tnpma68fxfyqr4vw22wsqm4jkrn"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lntbs")
    assert(invoice.amount_opt.contains(250000000 msat))
    assert(invoice.paymentHash.bytes == hex"4ffb6e9eabe93a88eb927ead43ae74172d9fbc3d858cede1e80871a5eb8bd863")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional))
    assert(invoice.createdAt == TimestampSecond(1660836433))
    assert(invoice.nodeId == PublicKey(hex"02e899d99662f2e64ea0eeaecb53c4628fa40a22d7185076e42e8a3d67fcb7b8e6"))
    assert(invoice.description == Left("yolo"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.tags.size == 7)
  }

  test("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzq9qrsgqdfjcdk6w3ak5pca9hwfwfh63zrrz06wwfya0ydlzpgzxkn5xagsqz7x9j4jwe7yj7vaf2k9lqsdk45kts2fd0fkr28am0u4w95tt2nsq76cqw0"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().contains("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    assert(invoice.routingInfo == List(List(
      ExtraHop(PublicKey(hex"029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId.fromCoordinates("66051x263430x1800").success.value, 1 msat, 20, CltvExpiryDelta(3)),
      ExtraHop(PublicKey(hex"039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId.fromCoordinates("197637x395016x2314").success.value, 2 msat, 30, CltvExpiryDelta(4))
    )))
    assert(invoice.tags.size == 6)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppj3a24vwu6r8ejrss3axul8rxldph2q7z99qrsgqz6qsgww34xlatfj6e3sngrwfy3ytkt29d2qttr8qz2mnedfqysuqypgqex4haa2h8fx3wnypranf3pdwyluftwe680jjcfp438u82xqphf75ym"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().contains("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7k9qrsgqt29a0wturnys2hhxpner2e3plp6jyj8qx7548zr2z7ptgjjc7hljm98xhjym0dg52sdrvqamxdezkmqg4gdrvwwnf0kv2jdfnl4xatsqmrnsse"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().contains("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q9qrsgq9vlvyj8cqvq6ggvpwd53jncp9nwc47xlrsnenq2zp70fq83qlgesn4u3uyf4tesfkkwwfg3qs54qe426hp3tz7z6sweqdjg05axsrjqp9yrrwc"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().contains("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3 and a minimum htlc cltv expiry of 12") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygscqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q9qrsgq999fraffdzl6c8j7qd325dfurcq7vl0mfkdpdvve9fy3hy4lw0x9j3zcj2qdh5e5pyrp6cncvmxrhchgey64culwmjtw9wym74xm6xqqevh9r0"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(invoice.fallbackAddress().contains("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(invoice.minFinalCltvExpiryDelta == CltvExpiryDelta(12))
    assert(invoice.tags.size == 6)
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, please send $30 for coffee beans to the same peer, which supports features 8, 14 and 99, using secret 0x1111111111111111111111111111111111111111111111111111111111111111") {
    val refs = Seq(
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2a25dxl5hrntdtn6zvydt7d66hyzsyhqs4wdynavys42xgl6sgx9c4g7me86a27t07mdtfry458rtjr0v92cnmswpsjscgt2vcse3sgpz3uapa",
      // All upper-case
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2a25dxl5hrntdtn6zvydt7d66hyzsyhqs4wdynavys42xgl6sgx9c4g7me86a27t07mdtfry458rtjr0v92cnmswpsjscgt2vcse3sgpz3uapa".toUpperCase,
      // With ignored fields
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqqsgq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqspnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnp5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnpkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqz599y53s3ujmcfjp5xrdap68qxymkqphwsexhmhr8wdz5usdzkzrse33chw6dlp3jhuhge9ley7j2ayx36kawe7kmgg8sv5ugdyusdcqzn8z9x"
    )

    for (ref <- refs) {
      val Success(invoice) = Bolt11Invoice.fromString(ref)
      assert(invoice.prefix == "lnbc")
      assert(invoice.amount_opt.contains(2500000000L msat))
      assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
      assert(invoice.paymentSecret.bytes == hex"1111111111111111111111111111111111111111111111111111111111111111")
      assert(invoice.createdAt == TimestampSecond(1496314658L))
      assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
      assert(invoice.description == Left("coffee beans"))
      assert(features2bits(invoice.features) == bin"1000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000100000000")
      assert(!invoice.features.hasFeature(BasicMultiPartPayment))
      assert(invoice.features.hasFeature(PaymentSecret, Some(Mandatory)))
      assert(!invoice.features.hasFeature(TrampolinePaymentPrototype))
      assert(TestConstants.Alice.nodeParams.features.invoiceFeatures().areSupported(invoice.features))
      assert(invoice.sign(priv).toString == ref.toLowerCase)
    }
  }

  test("On mainnet, please send $30 for coffee beans to the same peer, which supports features 8, 14, 99 and 100, using secret 0x1111111111111111111111111111111111111111111111111111111111111111") {
    val ref = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqqsgqtqyx5vggfcsll4wu246hz02kp85x4katwsk9639we5n5yngc3yhqkm35jnjw4len8vrnqnf5ejh0mzj9n3vz2px97evektfm2l6wqccp3y7372"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(2500000000L msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.paymentSecret.bytes == hex"1111111111111111111111111111111111111111111111111111111111111111")
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("coffee beans"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(features2bits(invoice.features) == bin"000011000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000100000000")
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.features.hasFeature(PaymentSecret, Some(Mandatory)))
    assert(!invoice.features.hasFeature(TrampolinePaymentPrototype))
    assert(!TestConstants.Alice.nodeParams.features.invoiceFeatures().areSupported(invoice.features))
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, please send 0.00967878534 BTC for a list of items within one week, amount in pico-BTC") {
    val ref = "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9q9qrsgqrvgkpnmps664wgkp43l22qsgdw4ve24aca4nymnxddlnp8vh9v2sdxlu5ywdxefsfvm0fq3sesf08uf6q9a2ke0hc9j6z6wlxg5z5kqpu2v9wz"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(967878534 msat))
    assert(invoice.paymentHash.bytes == hex"462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    assert(TestConstants.Alice.nodeParams.features.invoiceFeatures().areSupported(invoice.features))
    assert(invoice.createdAt == TimestampSecond(1572468703L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.description == Left("Blockstream Store: 88.85 USD for Blockstream Ledger Nano S x 1, \"Back In My Day\" Sticker x 2, \"I Got Lightning Working\" Sticker x 2 and 1 more items"))
    assert(invoice.fallbackAddress().isEmpty)
    assert(invoice.relativeExpiry == 604800.seconds)
    assert(invoice.minFinalCltvExpiryDelta == CltvExpiryDelta(10))
    assert(invoice.routingInfo == Seq(Seq(ExtraHop(PublicKey(hex"03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"), ShortChannelId.fromCoordinates("589390x3312x1").success.value, 1000 msat, 2500, CltvExpiryDelta(40)))))
    assert(invoice.sign(priv).toString == ref)
  }

  test("On mainnet, please send 0.01 BTC with payment metadata 0x01fafaf0") {
    val ref = "lnbc10m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdp9wpshjmt9de6zqmt9w3skgct5vysxjmnnd9jx2mq8q8a04uqsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q2gqqqqqqsgq7hf8he7ecf7n4ffphs6awl9t6676rrclv9ckg3d3ncn7fct63p6s365duk5wrk202cfy3aj5xnnp5gs3vrdvruverwwq7yzhkf5a3xqpd05wjc"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.prefix == "lnbc")
    assert(invoice.amount_opt.contains(1000000000 msat))
    assert(invoice.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(invoice.features == Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, PaymentMetadata -> Mandatory))
    assert(invoice.createdAt == TimestampSecond(1496314658L))
    assert(invoice.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(invoice.paymentSecret.bytes == hex"1111111111111111111111111111111111111111111111111111111111111111")
    assert(invoice.description == Left("payment metadata inside"))
    assert(invoice.paymentMetadata.contains(hex"01fafaf0"))
    assert(invoice.tags.size == 5)
    assert(invoice.sign(priv).toString == ref)
  }

  test("reject invalid invoices") {
    val refs = Seq(
      // Bech32 checksum is invalid.
      "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrnt",
      // Malformed bech32 string (no 1).
      "pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
      // Malformed bech32 string (mixed case).
      "LNBC2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
      // Signature is not recoverable.
      "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqwgt7mcn5yqw3yx0w94pswkpq6j9uh6xfqqqtsk4tnarugeektd4hg5975x9am52rz4qskukxdmjemg92vvqz8nvmsye63r5ykel43pgz7zq0g2",
      // String is too short.
      "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6na6hlh",
      // Invalid multiplier.
      "lnbc2500x1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqrrzc4cvfue4zp3hggxp47ag7xnrlr8vgcmkjxk3j5jqethnumgkpqp23z9jclu3v0a7e0aruz366e9wqdykw6dxhdzcjjhldxq0w6wgqcnu43j",
      // Invalid sub-millisatoshi precision.
      "lnbc2500000001p1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq0lzc236j96a95uv0m3umg28gclm5lqxtqqwk32uuk4k6673k6n5kfvx3d2h8s295fad45fdhmusm8sjudfhlf6dcsxmfvkeywmjdkxcp99202x"
    )
    for (ref <- refs) {
      assert(Bolt11Invoice.fromString(ref).isFailure)
    }
  }

  test("correctly serialize/deserialize variable-length tagged fields") {
    val number = 123456
    val codec = Bolt11Invoice.Codecs.dataCodec(scodec.codecs.bits).as[Bolt11Invoice.Expiry]
    val field = Bolt11Invoice.Expiry(number)
    assert(field.toLong == number)

    val serializedExpiry = codec.encode(field).require
    val field1 = codec.decodeValue(serializedExpiry).require
    assert(field1 == field)

    val invoice = Bolt11Invoice(chainHash = Block.LivenetGenesisBlock.hash, amount = Some(123 msat), paymentHash = ByteVector32(ByteVector.fill(32)(1)), privateKey = priv, description = Left("Some invoice"), minFinalCltvExpiryDelta = CltvExpiryDelta(18), expirySeconds = Some(123456), timestamp = 12345 unixsec)
    assert(invoice.minFinalCltvExpiryDelta == CltvExpiryDelta(18))
    val serialized = invoice.toString
    val Success(pr1) = Bolt11Invoice.fromString(serialized)
    assert(invoice == pr1)
  }

  test("ignore unknown tags") {
    val invoice = Bolt11Invoice(
      prefix = "lntb",
      amount_opt = Some(100000 msat),
      createdAt = TimestampSecond.now(),
      nodeId = nodeId,
      tags = List(
        PaymentHash(ByteVector32(ByteVector.fill(32)(1))),
        Description("description"),
        Bolt11Invoice.PaymentSecret(randomBytes32()),
        UnknownTag21(BitVector("some data we don't understand".getBytes))
      ),
      signature = ByteVector.empty).sign(priv)

    val serialized = invoice.toString
    val Success(pr1) = Bolt11Invoice.fromString(serialized)
    val Some(_) = pr1.tags.collectFirst { case u: UnknownTag21 => u }
  }

  test("ignore hash tags with invalid length") {
    // Bolt11: A reader: MUST skip over p, h, s or n fields that do NOT have data_lengths of 52, 52, 52 or 53, respectively.
    def bits(i: Int) = BitVector.fill(i * 5)(high = false)

    val inputs = Map(
      "ppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag1(bits(51)),
      "pp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag1(bits(53)),
      "hpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag23(bits(51)),
      "hp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag23(bits(53)),
      "spnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag16(bits(51)),
      "sp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> InvalidTag16(bits(53)),
      "np5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> UnknownTag19(bits(52)),
      "npkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq" -> UnknownTag19(bits(54))
    )

    for ((input, value) <- inputs) {
      val data = string2Bits(input)
      val decoded = Codecs.taggedFieldCodec.decode(data).require.value
      assert(decoded == value)
      val encoded = Codecs.taggedFieldCodec.encode(value).require
      assert(encoded == data)
    }
  }

  test("accept uppercase invoices") {
    val input = "lntb1500n1pwxx94fsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5q3xzmwuvxpkyhz6pvg3fcfxz0259kgh367qazj62af9rs0pw07dsdpa2fjkzep6yp58garswvaz7tmvd9nksarwd9hxw6n0w4kx2tnrdakj7grfwvs8wcqzysxqr23swwl9egjej7rvvt9zdxrtpy8xuu6cckdwajfccmtz7n90ea34k3j595w77pt69s5dx5a46f4k4w5avtvjkc4l4rm8n4xmk7fe3pms3pspdd032j"
    assert(Bolt11Invoice.fromString(input.toUpperCase()).get.toString == input)
  }

  test("Pay 1 BTC without multiplier") {
    val ref = "lnbc1000m1pdkmqhusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5n2ees808r98m0rh4472yyth0c5fptzcxmexcjznrzmq8xald0cgqdqsf4ujqarfwqsxymmccqp2pv37ezvhth477nu0yhhjlcry372eef57qmldhreqnr0kx82jkupp3n7nw42u3kdyyjskdr8jhjy2vugr3skdmy8ersft36969xplkxsp2v7c58"
    val Success(invoice) = Bolt11Invoice.fromString(ref)
    assert(invoice.amount_opt.contains(100000000000L msat))
    assert(features2bits(invoice.features) == BitVector.empty)
  }

  test("supported invoice features") {
    val nodeParams = TestConstants.Alice.nodeParams.copy(features = Features(knownFeatures.map(f => f -> Optional).toMap))
    case class Result(allowMultiPart: Boolean, requirePaymentSecret: Boolean, areSupported: Boolean) // "supported" is based on the "it's okay to be odd" rule
    val featureBits = Map(
      Features(bin"               00000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00100100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               00100100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      Features(bin"               01000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000010000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000011000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000110000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0000100000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"          0010000000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"     000001000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      // those are useful for nonreg testing of the areSupported method (which needs to be updated with every new supported mandatory bit)
      Features(bin"     000100000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"00000010000000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      Features(bin"00001000000000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false)
    )

    for ((features, res) <- featureBits) {
      val invoice = createInvoiceUnsafe(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = features)
      assert(Result(invoice.features.hasFeature(BasicMultiPartPayment), invoice.features.hasFeature(PaymentSecret, Some(Mandatory)), nodeParams.features.invoiceFeatures().areSupported(invoice.features)) == res)
      assert(Bolt11Invoice.fromString(invoice.toString).get == invoice)
    }
  }

  test("feature bits to minimally-encoded feature bytes") {
    val testCases = Seq(
      (bin"   0010000100000101", hex"  2105"),
      (bin"   1010000100000101", hex"  a105"),
      (bin"  11000000000000110", hex"018006"),
      (bin"  01000000000000110", hex"  8006"),
      (bin" 001000000000000000", hex"  8000"),
      (bin" 101000000000000000", hex"028000"),
      (bin"0101010000000000110", hex"02a006"),
      (bin"1000110000000000110", hex"046006")
    )

    for ((bitmask, featureBytes) <- testCases) {
      assert(Features(bitmask).toByteVector == featureBytes)
    }
  }

  test("payment secret") {
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18))
    assert(invoice.features == Features(PaymentSecret -> Mandatory, VariableLengthOnion -> Mandatory))
    assert(invoice.features.hasFeature(PaymentSecret, Some(Mandatory)))

    val Success(pr1) = Bolt11Invoice.fromString(invoice.toString)
    assert(pr1.paymentSecret == invoice.paymentSecret)

    // An invoice that sets the payment secret feature bit must provide a payment secret.
    assert(Bolt11Invoice.fromString("lnbc1230p1pwljzn3pp5qyqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdq52dhk6efqd9h8vmmfvdjs9qypqsqylvwhf7xlpy6xpecsnpcjjuuslmzzgeyv90mh7k7vs88k2dkxgrkt75qyfjv5ckygw206re7spga5zfd4agtdvtktxh5pkjzhn9dq2cqz9upw7").isFailure)

    // A multi-part invoice must use a payment secret.
    assertThrows[IllegalArgumentException](
      Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("MPP without secrets"), CltvExpiryDelta(18), features = Features(VariableLengthOnion -> Optional, PaymentSecret -> Optional))
    )
  }

  test("trampoline") {
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18))
    assert(!invoice.features.hasFeature(TrampolinePaymentPrototype))

    val pr1 = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, TrampolinePaymentPrototype -> Optional))
    assert(!pr1.features.hasFeature(BasicMultiPartPayment))
    assert(pr1.features.hasFeature(TrampolinePaymentPrototype))

    val pr2 = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, TrampolinePaymentPrototype -> Optional))
    assert(pr2.features.hasFeature(BasicMultiPartPayment))
    assert(pr2.features.hasFeature(TrampolinePaymentPrototype))

    val Success(pr3) = Bolt11Invoice.fromString("lnbc40n1pw9qjvwsp5q56txjvpzwz5lkd4atjpc894l8ppc0eeylfnelaytcgfaxjy76tspp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsqakczwg")
    assert(!pr3.features.hasFeature(TrampolinePaymentPrototype))
  }

  test("nonreg") {
    val requests = List(
      "lnbc40n1pw9qjvwsp5mehkatggqqk9f0h03rd0fpjtyd2gx9n97rvgldmfk3sqh24kfvfspp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsqgvq34k" -> PublicKey(hex"03950b5929d3bfef57926c0e05e0144a6952b48376da4a69d014cd96bdc5f1286e"),
      "lnbc1500n1pwyvqwfsp5p93pdtn2y9xpuktl5sg46eavxv04dgs4muc4xraz8af956mtqxjqpp5p5nxwpuk02nd2xtzwex97gtjlpdv0lxj5z08vdd0hes7a0h437qsdpa2fjkzep6yp8kumrfdejjqempd43xc6twvusxjueqd9kxcet8v9kzqct8v95kucqzysxqr23s8r9seqv6datylwtjcvlpdkukfep7g80hujz3w8t599saae7gap6j48gs97z4fvrx4t4ajra6pvdyf5ledw3tg7h2s3606qm79kk59zqpkuhphr" -> PublicKey(hex"0360d2ffd5c973b86fd46a6d8835448ad36a14779f194c1743aa85af17da33d8ee"),
      "lnbc800n1pwykdmfsp5l4v4pszfgdygefagcap02vj60k5fhf3dsaxpwzmyzm5lddvm653qpp5zqjae54l4ecmvm9v338vw2n07q2ehywvy4pvay53s7068t8yjvhqdqddpjkcmr0yysjzcqp27lya2lz7d80uxt6vevcwzy32227j3nsgyqlrxuwgs22u6728ldszlc70qgcs56wglrutn8jnnnelsk38d6yaqccmw8kmmdlfsyjd20qpvddzjr" -> PublicKey(hex"037041b5bfd757cf169001bf8b12ee6cbec6fd28580f602ac8265eefa86f2784c5"),
      "lnbc300n1pwzezrnsp5a23qqp833xvrerkjdgp0kj08e4j60tq4wdnmhqfjmk8c4t2yx43spp5zgwqadf4zjygmhf3xms8m4dd8f4mdq26unr5mfxuyzgqcgc049tqdq9dpjhjcqp23gxhs2rawqxdvr7f7lmj46tdvkncnsz8q5jp2kge8ndfm4dpevxrg5xj4ufp36x89gmaw04lgpap7e3x9jcjydwhcj9l84wmts2lg6qqv3vpl9" -> PublicKey(hex"03948339c067b6eb08f8ffa8c66f64b889f7bbef4f378da2cb5c48fc6bece78be7"),
      "lnbc10n1pdm2qaxsp5wdep78y2wfte86ppfpgfvjz5sxupujcskpq2jgg3qk3qx9gk2l8qpp5zlyxcc5dypurzyjamt6kk6a8rpad7je5r4w8fj79u6fktnqu085sdpl2pshjmt9de6zqen0wgsrzgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp2e3nq4xh20prn9kx8etqgjjekzzjhep27mnqtyy62makh4gqc4akrzhe3nmj8lnwtd40ne5gn8myruvrt9p6vpuwmc4ghk7587erwqncp34auqv" -> PublicKey(hex"03579c9b3566ed8b176ea8710b7d14f2bae4dae98c4a5848d91b586d0d3ef0eb74"),
      "lnbc800n1pwp5uuhsp5vjfgr7c6p2zehqwm6jnnrwjf2uvqemjgpvcv7ptmdmufkljx8luspp5y8aarm9j9x9cer0gah9ymfkcqq4j4rn3zr7y9xhsgar5pmaceaqqdqdvf5hgcm0d9hzzcqp2vf8ramzsgdznxd5yxhrxffuk43pst9ng7cqcez9p2zvykpcf039rp9vutpe6wfds744yr73ztyps2z58nkmflye9yt4v3d0qz8z3d9qqjufe9k" -> PublicKey(hex"03f731ec588868956323dc5f3395ebfa9390cb0714db8d28ff98600e34d0cdaddb"),
      "lnbc1500n1pdl686hsp5330mz3yl0ne33x4mf6m58nayktxurnk64zr4cz7ewwpsjav3yt4qpp5y7mz3lgvrfccqnk9es6trumjgqdpjwcecycpkdggnx7h6cuup90sdpa2fjkzep6ypqkymm4wssycnjzf9rjqurjda4x2cm5ypskuepqv93x7at5ypek7cqzysxqr23s5e864m06fcfp3axsefy276d77tzp0xzzzdfl6p46wvstkeqhu50khm9yxea2d9efp7lvthrta0ktmhsv52hf3tvxm0unsauhmfmp27cq9jd0gj" -> PublicKey(hex"02b7d6b398479661ff28f96ea6ac10d289e91563fc18fb60135a72e431d00302d4"),
      "lnbc80n1pwykw99sp53cn0wcslxj2ytcpvyax2ls49rmn53et6rnk2tfs9elxxmcrqhfeqpp5965lyj4uesussdrk0lfyd2qss9m23yjdjkpmhw0975zky2xlhdtsdpl2pshjmt9de6zqen0wgsrsgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp27677yc44l22jxexewew7lzka7g5864gdpr6y5v6s6tqmn8xztltk9qnna2qwrsm7gfyrpqvhaz4u3egcalpx2gxef3kvqwd44hekfxcqggwumu" -> PublicKey(hex"02dbd488b193820cd499045f34ac1497b0551192432328cfebd5610a1a53c69c74"),
      "lnbc2200n1pwp4pwnsp5j0t96kcckswuh6n3m98q6fcxpnqvjm6qxczjyft74m2j2ma0gxlqpp5xy5f5kl83ytwuz0sgyypmqaqtjs68s3hrwgnnt445tqv7stu5kyqdpyvf5hgcm0d9hzqmn0wssxymr0vd4kx6rpd9hqcqp25y9w3wc3ztxhemsqch640g4u00szvvfk4vxr7klsakvn8cjcunjq8rwejzy6cfwj90ulycahnq43lff8m84xqf3tusslq2w69htwwlcpnkwfxw" -> PublicKey(hex"02fb28834ae4f80ffbca829d8445e5c4e3e312a73e228e4412f8c8d1209701c79d"),
      "lnbc300n1pwp50ggsp5h2zsmzurz2c7xq5clu6tpmuu4p7gazgje6v8e06hpx5nqvkdtrwspp5x7x5a9zs26amr7rqngp2sjee2c3qc80ztvex00zxn7xkuzuhjkxqdq9dpjhjcqp2s464vnrpx7aynh26vsxx6s3m52x88dqen56pzxxnxmc9s7y5v0dprsdlv5q430zy33lcl5ll6uy60m7c9yrkjl8yxz7lgsqky3ka57qqsk3748" -> PublicKey(hex"02dfe5bf6ffbaddd3d48f7fc5a05b003ebd1814172ab5279d9cdff95f89a5299c5"),
      "lnbc10n1pd6jt93sp5wveqytyyr5pvdsgf4zujk8srvtgst439mkcxq3fv43myh830w7xqpp58vtzf4gup4vvqyknfakvh59avaek22hd0026snvpdnc846ypqrdsdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uq3sv9xkv2sgdf2nuvs97d2wkzj5g75rljnh5wy5wqhnauvqhxd9fpq898emtz8hul8cnxmc9wtj2777ehgnnyhcrs0y5zuhy8rs0jv6cq384rhu" -> PublicKey(hex"03944ffb39600b1745335e5c8b2d96ff4fca976fabc0fbc39bec777ddcebd32f24"),
      "lnbc890n1pwzu4uqsp500a268p7mvjaz0jtc8qt9u7qqju6c5ssayffstmajyeycl0we67spp5gy274lq0m5hzxuxy90vf65wchdszrazz9zxjdk30ed05kyjvwxrqdzq2pshjmt9de6zqen0wgsrswfqwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2qjvlfyl4rmc56gerx70lxcrjjlnrjfz677ezw4lwzy6syqh4rnlql6t6n3pdfxkcal9jp98plgf2zqzz8jxfza9vjw3vd4t62ws8gkgqxcg3x2" -> PublicKey(hex"020d0167363df13e119f27b0c14e789f8bdfa8050437ef243d385490150ea959a8"),
      "lnbc79760n1pd7cwyasp5yrpf5xetks0k05xxnu0uuspw9hz6lu8ru3p077jfayr9vk9kmypspp5gevl4mv968fs4le3tytzhr9r8tdk8cu3q7kfx348ut7xyntvnvmsdz92pskjepqw3hjqmrfva58gmnfdenjqumvda6zqmtpvd5xjmn9ypnx7u3qx5czq5msd9h8xcqzysxqrrssjzky68fdnhvee7aw089d5zltahfhy2ffa96pwf7fszjnm6mv0fzpv88jwaenm5qfg64pl768q8hf2vnvc5xsrpqd45nca2mewsv55wcptdz4vm" -> PublicKey(hex"037379da51b761e24190845862daea1a949a8575ff09f0ca3850d3ed0e39b34a61"),
      "lnbc90n1pduns5qsp5hs354r0zf8rvsq3ah8wgkthxmecucehxdx7s6864yxhdg7c05glqpp5f5h5ghga4cp7uj9de35ksk00a2ed9jf774zy7va37k5zet5cds8sdpl2pshjmt9de6zqen0wgsrjgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp28ynysm3clcq865y9umys8t2f54anlsu2wfpyfxgq09ht3qfez9x9z9fpff8wzqwzua2t9vayzm4ek3vf4k4s5cdg3a6hp9vsgg9klpgpx8x3tw" -> PublicKey(hex"020847eb6e38a84376c6bfae6dd607c617feb9c1cd7120abd648ff74933eb1b2a0"),
      "lnbc10u1pw9nehpsp5ll8eanktm4yhap8ylqx28evfkzn6wsdt5w054l3ssmz2k5qgwvfqpp5tf0cpc3nx3wpk6j2n9teqwd8kuvryh69hv65w7p5u9cqhse3nmgsdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp222vxxwq70temepf6n0xlzk0asr43ppqrt0mf6eclnfd5mxf6uhv5wvsqgdvht6uqxfw2vgdku5gfyhgguepvnjfu7s4kuthtnuxy0hsqqhlw7k" -> PublicKey(hex"02ff75181032781d956fd7e123cca2916e20bdf9d8a5b90bd4fb326085bb0697c5"),
      "lnbc30n1pw9qjwmsp5u3tgew9r5psus5g04nxz7ch4mzzs4nyprafx72tx4v32v4cnx9fspp5tcdc9wcr0avr5q96jlez09eax7djwmc475d5cylezsd652zvptjsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdf4cqzysxqrrss7r8gn9d6klf2urzdjrq3x67a4u25wpeju5utusnc539aj5462y7kv9w56mndcx8jad7aa7qz8f8qpdw9qlx52feyemwd7afqxu45jxsq0cu9rg" -> PublicKey(hex"038e14387b458d4515854a11cf5dff6a6f439cc11ace4d77d3ba1ca997560c5a90"),
      "lnbc10u1pw9x36xsp5m65zu20df3fyena6ctx6j25g3vqn8l4xk80jlywf0ywsgqvnn0sqpp5tlk00k0dftfx9vh40mtdlu844c9v65ad0kslnrvyuzfxqhdur46qdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp2fpudmf4tt0crardf0k7vk5qs4mvys88el6e7pg62hgdt9t6ckf48l6jh4ckp87zpcnal6xnu33hxdd8k27vq2702688ww04kc065r7cq7uhgna" -> PublicKey(hex"035fa2b6c6714bd2136bffd19ac7b7f5c4e30537896b9f6e8fb15ec24c95e753cb"),
      "lnbc40n1pd6jttksp5xetvqtnsr3gcpu76jrvgshtx3vkgq7z04qr0gsu44vvkgpcau5dspp5v8p97ezd3uz4ruw4w8w0gt4yr3ajtrmaeqe23ttxvpuh0cy79axqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uq3r88ajpz77z6lg4wc7srhsk7m26guuvhdlpea6889m9jnc9a25sx7rdtryjukew86mtcngl6d8zqh9trtu60cmmwfx6845q08z06p6qph8sx7j" -> PublicKey(hex"0227e979140f07126ba2af8089b196c0dfaf164989b50c2a0ae5a18a28baee4449"),
      "lnbc1pwr7fqhsp5zzauhsa8gaaxja4mgkpfx6gu3s5707hygwyu5atga26x65f3vkcqpp5vhur3ahtumqz5mkramxr22597gaa9rnrjch8gxwr9h7r56umsjpqdpl235hqurfdcs9xct5daeks6tngask6etnyq58g6tswp5kutndv55jsaf3x5unj2gcqzysxqyz5vq88jysqvrwhq6qe38jdulefx0z9j7sfw85wqc6athfx9h77fjnjxjvprz76ayna0rcjllgu5ka960rul3qxvsrr9zth5plaerq96ursgplxe9n2" -> PublicKey(hex"033aa80cd8512369f2d0afa689cffae62ae8269275a9889f69422aebd66ff90c34"),
      "lnbc10n1pw9rt5hsp5qs96eadkdprmuyapfa4m3jdhjptma48qdndrawv9l3sd239a48xspp5dsv5ux7xlmhmrpqnffgj6nf03mvx5zpns3578k2c5my3znnhz0gqdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwwp3cqzysxqrrssnrasvcr5ydng283zdfpw38qtqfxjnzhdmdx9wly9dsqmsxvksrkzkqrcenu6h36g4g55q56ejk429nm4zjfgssh8uhs7gs760z63ggcqwcppjk" -> PublicKey(hex"020d7848a29f1755731090f236a093245c0397f1ac8a668812c65ad16684c1df26"),
      "lnbc1500n1pd7u7p4sp5s8muhyew3mseenx5r458xyqgvf88xdjq3zh98wswtezf555eaa0spp5d54vffcehkcy79gm0fkqrthh3y576jy9flzpy9rf6syua0s5p0jqdpa2fjkzep6ypxhjgz90pcx2unfv4hxxefqdanzqargv5s9xetrdahxggzvd9nkscqzysxqr23sklptztnk25aqzwty35gk9q7jtfzjywdfx23d8a37g2eaejrv3d9nnt87m98s4eps87q87pzfd6hkd077emjupe0pcazpt9kaphehufqqrsh4xf" -> PublicKey(hex"02b8bc0708fd6b98ad9833b4aeab1fc1062e70f3b9791df644e0aa5fe404431a6a"),
      "lnbc10n1pdunsmgsp5gzuppftdn8l8wx4qxas6hmcuyqvf35auxegnm0l85ggznywq0zaqpp5wn90mffjvkd06pe84lpa6e370024wwv7xfw0tdxlt6qq8hc7d7rqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcngvscqzysxqyd9uqs0cqtrum6h7dct88nkjxwxvte7hjh9pusx64tp35u0m6qhqy5dgn9j27fs37mg0w3ruf7enxlsc9xmlasgjzyyaaxqdxu9x5w0md4fsp9snrjr" -> PublicKey(hex"026597032181d079159e335358083ba7fbe92f1b6f1a0006508518180d62c6bd8a"),
      "lnbc700n1pwp50wasp53r3s3v0s3ts7x5g5ycz4r9yaq45c7zepwv7qnjjxpd3sz97en3mspp5w7eearwr7qjhz5vk5zq4g0t75f90mrekwnw4e795qfjxyaq27dxsdqvdp6kuar9wgeqcqp20gfw78vvasjm45l6zfxmfwn59ac9dukp36mf0y3gpquhp7rptddxy7d32ptmqukeghvamlkmve9n94sxmxglun4zwtkyhk43e6lw8qspc7xfav" -> PublicKey(hex"03c621f5f531273ecb65647542e40a9c2fad1445d7d1a5d0eced1d2be83695cc8c"),
      "lnbc10n1pd6jvy5sp5035aml53fd69tkq4plvjyf727g4cr9j92290qcqfd0ye4qnmn0tqpp50x9lymptter9najcdpgrcnqn34wq34f49vmnllc57ezyvtlg8ayqdpdtfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yq6rvcqzysxqyd9uqcejk56vfz3y80u3npefpx82f0tghua88a8x2d33gmxcjm45q6l5xwurwyp9aj2p59cr0lknpk0eujfdax32v4px4m22u6zr5z40zxvqp49f9r5" -> PublicKey(hex"028e43a5f2af13ce793ea13ea16dac079aaa3f42bdfc843ed5170860b99db9b3b4"),
      "lnbc10n1pw9pqz7sp53qks4wmrmaewdr2l0ren6t2m45tcles3jtnuvwelf3feq6my9saqpp50782e2u9s25gqacx7mvnuhg3xxwumum89dymdq3vlsrsmaeeqsxsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwd3ccqzysxqrrsstxqhw2kvdfwsf7c27aaae45fheq9rzndesu4mph9dq08sawa0auz7e0z7jn9qf3zphegv2ermup0fgce0phqmf73j4zx88v3ksrgeeqqe6j7h2" -> PublicKey(hex"02c834531cdeb4054d526480fd6b3b766205ce2a9b3ccad367eb3522e2fa60abc2"),
      "lnbc1300n1pwq4fx7sp5xdyxfxnfk5d5ffpmy52e5je6t7q4erszjhypttduxuncmn79sr8qpp5sqmq97yfxhhk7xv7u8cuc8jgv5drse45f5pmtx6f5ng2cqm332uqdq4e2279q9zux62tc5q5t9fgcqp29a662u3p2h4h4ucdav4xrlxz2rtwvvtward7htsrldpsc5erknkyxu0x2xt9qv0u766jadeetsz9pj4rljpjy0g8ayqqt2q8esewsrqpddkjvq" -> PublicKey(hex"03634d414182398c1c11e653c0a29b51e33a9bd001173b5bd294d442706b8466a2"),
      "lnbc1u1pd7u7tnsp5j3fjqc8y83kkar2yfsdc5xppx3ljvzm04dlump5ptqruqy7h648spp5s9he3ccpsmfdkzrsjns7p3wpz7veen6xxwxdca3khwqyh2ezk8kqdqdg9jxgg8sn7f27cqzysxqr23ssm4krdc4s0zqhfk97n0aclxsmaga208pa8c0hz3zyauqsjjxfj7kw6t29dkucp68s8s4zfdgp97kkmzgy25yuj0dcec85d9c50sgjqgqc9cces" -> PublicKey(hex"02440cddd69f553b463659c6600f767347a6c4c5c8c5f8b881b709165f7a4f0f92"),
      "lnbc1200n1pwq5kf2sp5c24rlpxftd40h089rrjhvayus6fgh7w27ta82t724ujzy9ses2dqpp5snkm9kr0slgzfc806k4c8q93d4y57q3lz745v2hefx952rhuymrqdq509shjgrzd96xxmmfdcsscqp2w5ta9uwzhmxxp0mnhwwvnjdn6ev4huj3tha5d80ajv2p5phe8wk32yn7ch6lennx4zzawqtd34aqetataxjmrz39gzjl256walhw03gprr8gxs" -> PublicKey(hex"0207f88c9c3685c9ce0da283554673fafe1bdae9e32d59c0a62766d32073d022c0"),
      "lnbc1500n1pd7u7v0sp5dnh5mxkyrtkdc2gtjj5q37gf5h2xxa404ad69qq3s67qedcuv8gqpp5s6d0wqexag3aqzugaw3gs7hw7a2wrq6l8fh9s42ndqu8zu480m0sdqvg9jxgg8zn2sscqzysxqr23sm23myatjdsp3003rlasgzwg3rlr0ca8uqdt5d79lxmdwqptufr89r5rgk4np4ag0kcw7at6s6eqdany0k6m0ezjva0cyda5arpaw7lcqx63v8d" -> PublicKey(hex"03d92f5b667a8f796366064fc555881212446da1ba0c3a9922672d895fc92366b7"),
      "lnbc100n1pd6jv8ysp5ca8ndjtp4zssmn8s7fpsjk6xz6vmu5hg3d4uukaz2r6m0d6qty8qpp53p6fdd954h3ffmyj6av4nzcnwfuyvn9rrsc2u6y22xnfs0l0cssqdpdtfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqerscqzysxqyd9uqyefde4la0qmglafzv8q34wqsf4mtwd8ausufavkp2e7paewd3mqsg0gsdmvrknw80t92cuvu9raevrnxtpsye0utklhpunsz68a9veqpnmnqrs" -> PublicKey(hex"03ff844d1b1d93a1a41ef6b974447843346aff9d7721e3349f32fec6bd18ed9d41"),
      "lnbc2300n1pwp50w8sp5us86h5ky2mlympcehwasz30k8w6men7pqw4pgu3juvnjcwdvxxwqpp53030gw8rsqac6f3sqqa9exxwfvphsl4v4w484eynspwgv5v6vyrsdp9w35xjueqd9ejqmn0wssx67fqwpshxumhdaexgcqp2zmspcx992fvezxqkyf3rkcxc9dm2vr4ewfx42c0fccg4ea72fyd3pd6vn94tfy9t39y0hg0hupak2nv0n6pzy8culeceq8kzpwjy0tspa4ewlk" -> PublicKey(hex"0373f3c72218f1c3d5b6f25d72f2da066023e24288a387b2e5270f8ea3b0eb3e94"),
      "lnbc10n1pwykdlhsp589930rpa67fzwjdgsc48u55lll28mqetupazz69uw966aqtpuacspp53392ama65h3lnc4w55yqycp9v2ackexugl0ahz4jyc7fqtyuk85qdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwvejcqzysxqrrsszkwrx54an8lhr9h4h3d7lgpjrd370zucx0fdusaklqh2xgytr8hhgq5u0kvs56l8j53uktlmz3mqhhmn88kwwxfksnham9p6ws5pwxsq0pk8qg" -> PublicKey(hex"03ed59acd479b463bf7193a5595da4db4406ebbfb99902aaed91a008d43c8e281e"),
      "lnbc10470n1pw9qf40sp5v6y8xky0syu058y9t9ef78t5ysjz6a43885nr29a8hr7l7vt080qpp535pels2faqwau2rmqkgzn0rgtsu9u6qaxe5y6ttgjx5qm4pg0kgsdzy2pshjmt9de6zqen0wgsrzvp5xus8q6tcv4k8xgrpwss8xct5daeks6tn9ecxcctrv5hqxqzjccqp27sp3m204a7d47at5jkkewa7rvewdmpwaqh2ss72cajafyf7dts9ne67hw9pps2ud69p4fw95y9cdk35aef43cv35s0zzj37qu7s395cp60spue" -> PublicKey(hex"039ba54a84af0f325ca5e17a77c505d5ea6fe700176e445075851dbf4ca418b236"),
      "lnbc100n1pwytlgssp5d38cyayuw3du636myuj46z67ekekygdrx7yzuddmp3l45fwx0syqpp5365rx7ell807x5jsr7ykf2k7p5z77qvxjx8x6pfhh5298xnr6d2sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwvpscqzysxqrrssh9mphycg7e9lr58c267yerlcd9ka8lrljm8ygpnwu2v63jm7ax48y7qal25qy0ewpxw39r5whnqh93zw97gnnw64ss97n69975wh9gsq8lsfhh" -> PublicKey(hex"03663ba20c53d28167e1dfd5033e95b5e016eb63626c32aff5317e11e6b22cb99b"),
      "lnbc210n1pdunsefsp55ajklwxtyxg76ujw26hdmzlzr3ll8440q2474ksjv4m3xy3nwsyspp5jxn3hlj86evlwgsz5d70hquy78k28ahdwjmlagx6qly9x29pu4uqdzq2pshjmt9de6zqen0wgsryvfqwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2snr8trjcrr5xyy7g63uq7mewqyp9k3d0duznw23zhynaz6pj3uwk48yffqn8p0jugv2z03dxquc8azuwr8myjgwzh69a34fl2lnmq2spd9fhrk" -> PublicKey(hex"02c89047a2dc5d6fd55020ce0301248bb669c466c035bdfa028974f0c99e00019f"),
      "lnbc1700n1pwr7z98sp5sqys9f0zp8hlgsdkemtq3c7e8krg4qt2dwshxr9pm7yktyuzav7spp5j5r5q5c7syavjjz7czjvng4y95w0rd8zkl7q43sm7spg9ht2sjfqdquwf6kumnfdenjqmrfva58gmnfdenscqp2jrhlc758m734gw5td4gchcn9j5cp5p38zj3tcpvgkegxewat38d3h24kn0c2ac2pleuqp5dutvw5fmk4d2v3trcqhl5pdxqq8swnldcq30eu79" -> PublicKey(hex"03ea691548ce4e87820f04607a2d692523ac52113ae3bafeb5933260f328dda90f"),
      "lnbc1500n1pdl05k5sp5f8npjvatx6f4pa83vj3gyy0kemh0xcvf9a4mlwl723tg3zyej2tspp5nyd9netjpzn27slyj2np4slpmlz8dy69q7hygwm8ff4mey2jee5sdpa2fjkzep6ypxhjgz90pcx2unfv4hxxefqdanzqargv5s9xetrdahxggzvd9nkscqzysxqr23sqdd8t97qjc77pqa7jv7umc499jqkk0kwchapswj3xrukndr7g2nqna5x87n49uynty4pxexkt3fslyle7mwz708rs0rnnn44dnav9mgplk9g63" -> PublicKey(hex"036ed59f22c03389d4a5a297f97d79c6b5845437826469efe85c2b5f6764cdcf38"),
      "lnbc1u1pwyvxrpsp5xpz5u9mhc6wyaygy7sagej4glglq2vjzpda3ey2yuwru34n5hj2spp5nvm98wnqdee838wtfmhfjx9s49eduzu3rx0fqec2wenadth8pxqsdqdg9jxgg8sn7vgycqzysxqr23snuza3t8x0tvusu07epal9rqxh4cq22m64amuzd6x607s0w55a5xpefp2xlxmej9r6nktmwv5td3849y2sg7pckwk9r8vqqps8g4u66qqynqatk" -> PublicKey(hex"027f431caabe91e8a939b5ed457061946404e4ffd19959c6e10f7e9aec87992721"),
      "lnbc10n1pw9qjwpsp5cznqrumnpqsvntzlemnee5dulzkdgxzveckrp0tv64aadwzna76qpp55nx7xw3sytnfle67mh70dyukr4g4chyfmp4x4ag2hgjcts4kydnsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwd3ccqzysxqrrss7t24v6w7dwtd65g64qcz77clgye7n8l0j67qh32q4jrw9d2dk2444vma7j6nedgx2ywel3e9ns4r257zprsn7t5uca045xxudz9pqzsqpv4ds6" -> PublicKey(hex"0398514bc9269dd1fbd8b5b7eae4ba20149727ccc900c7a76e631e68594aaf1da6"),
      "lnbc10u1pw9x373sp5j2pkxqz8jqahvzts7fc6qgz4q4079etnwgpapp66tmz88pdamqqqpp549mpcznu3q0r4ml095kjg38pvsdptzja8vhpyvc2avatc2cegycsdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp2tgqwhzyjmpfymrshnaw6rwmy4rgrtjmmp66dr9v54xp52rsyzqd5htc3lu3k52t06fqk8yj05nsw0nnssak3ywev4n3xs3jgz42urmspke0txh" -> PublicKey(hex"032cff4f91aee88c67f0e5909fc91347966e43147cf28a9e28ef2ac5d9f1477877"),
      "lnbc1500n1pd7u7vusp5yyg3k2lcw2axnna9ha025dyrryy2tjxkeqr7v9tk8eh4xgv8a2sqpp54jm8s8lmgnnru0ndwpxhm5qwllkrarasr9fy9zkunf49ct8mw9ssdqvg9jxgg8zn2sscqzysxqr23s4njradkzzaswlsgs0a6zc3cd28xc08t5car0k7su6q3u3vjvqt6xq2kpaadgt5x9suxx50rkevfw563fupzqzpc9m6dqsjcr8qt6k2sqgcfjgu" -> PublicKey(hex"0232c51b987daa2badb544e47f369e4574eeda5238b82ad092525f2cfa3472fd06"),
      "lnbc720n1pwypj4esp5jywzdg50aekw72xe9wtxn8hzed5yerjn9825dzmdqlgzgh57k5lspp5k2saqsjznpvevsm9mzqfan3d9fz967x5lp39g3nwsxdkusps73csdzq2pshjmt9de6zqen0wgsrwv3qwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2d3ltxtq0r795emmp7yqjjmmzl55cgju004vw08f83e98d28xmw44t4styhfhgsrwxydf68m2kup7j358zdrmhevqwr0hlqwt2eceaxcqjzhezc" -> PublicKey(hex"02a620092880a394d075c6b71d71d70fc8aa5a251be15589daa730160431c49850"),
      "lnbc10n1pwykdacsp5glv27qqqx54sn7jn2f3szsc64celf7dpf5cvyh7vf2e242ceh4vqpp5kegv2kdkxmetm2tpnzfgt4640n7mgxl95jnpc6fkz6uyjdwahw8sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdp5cqzysxqrrssjlny2skwtnnese9nmw99xlh7jwgtdxurhce2zcwsamevmj37kd5yzxzu55mt567seewmajra2hwyry5cv9kfzf02paerhs7tf9acdcgqva6k7j" -> PublicKey(hex"020e3d0c063f7442b16a1f982dcc343dbe2a81e85b664f9dc2645965c715bfbfe3"),
      "lnbc3100n1pwp370ssp5tcrvxtavpnk80w3z3pq53zlwqhdu2nc8yjk2k3qsn223sa4t5pdqpp5ku7y6tfz5up840v00vgc2vmmqtpsu5ly98h09vxv9d7k9xtq8mrsdpjd35kw6r5de5kueevypkxjemgw3hxjmn89ssxc6t8dp6xu6twvucqp2sunrt8slx2wmvjzdv3vvlls9gez7g2gd37g2pwa4pnlswuxzy0w3hd5kkqdrpl4ylcdhvkvuamwjsfh79nkn52dq0qpzj8c4rf57jmgqe864ex" -> PublicKey(hex"03460feb831a3b31f2004b3c8484012e4a10f35b4f6a8f97f9711706c37c91bcaf"),
      "lnbc1500n1pwr7z8rsp500swxekcn6psptyjztm22w06atutqx759xmypmqjcsg8p25n25pqpp5hyfkmnwwx7x902ys52du8pph6hdkarnqvj6fwhh9swfsg5lp94vsdpa2fjkzep6ypph2um5dajxjctvypmkzmrvv468xgrpwfjjqetkd9kzqctwvss8ycqzysxqr23s64a2h7gn25pchh8r6jpe236h925fylw2jcm4pd92w8hkmpflreph8r6s8jnnml0zu47qv6t2sj6frnle2cpanf6e027vsddgkl8hk7gpw8209n" -> PublicKey(hex"03044dd84ef699fc273e02a4a459ba2acfebfcc05f09bdd54d24e9664603bffc28"),
      "lnbc1500n1pdl05v0sp5c44hx2kt6hsslqn4yv2wc67vgz9upwvmhmxw5d4vqhv9edrnm50qpp5c4t5p3renelctlh0z4jpznyxna7lw9zhws868wktp8vtn8t5a8uqdpa2fjkzep6ypxxjemgw35kueeqfejhgam0wf4jqnrfw96kjerfw3ujq5r0dakq6cqzysxqr23s7k3ktaae69gpl2tfleyy2rsm0m6cy5yvf8uq7g4dmpyrwvfxzslnvryx5me4xh0fsp9jfjsqkuwpzx9ydwe6ndrm0eznarhdrfwn5gsph3ulpz" -> PublicKey(hex"0386b9ebc56df241424a052ec92dd6eff5b833275bb84376ffa6780c269fe322f8"),
      "lnbc1500n1pwyvxp3sp55tutdhtnzx2kv66v0p0knc7wfup8jkrxqvujjc3fzlsh80slr2lqpp5ch8jx4g0ft0f6tzg008vr82wv92sredy07v46h7q3h3athx2nm2sdpa2fjkzep6ypyx7aeqfys8w6tndqsx67fqw35x2gzvv4jxwetjypvzqam0w4kxgcqzysxqr23s3hdgx90a6jcqgl84z36dv6kn6eg4klsaje2kdm84662rq7lzzzlycvne4l8d0steq5pctdp4ffeyhylgrt7ln92l8dyvrnsn9qg5qkgqvffgf6" -> PublicKey(hex"02dc6c9deef8f6033e24d6a9653902b68863086d8aedf395f0fc90c42d147538e5"),
      "lnbc1500n1pwr7z2psp55gly6gsxyqz4mfhk08el8s0amucxzgnd7kfec85gk7vrx0fjmpdqpp5cuzt0txjkkmpz6sgefdjjmdrsj9gl8fqyeu6hx7lj050f68yuceqdqvg9jxgg8zn2sscqzysxqr23s7442lgk6cj95qygw2hly9qw9zchhag5p5m3gyzrmws8namcsqh5nz2nm6a5sc2ln6jx59sln9a7t8vxtezels2exurr0gchz9gk0ufgp8rkm46" -> PublicKey(hex"02a41788407d9a013dd43c3324be954e962abb585906b39cfa7e27c85d7e1605f2"),
      "lnbc1500n1pd7u7g4sp57svjn6jgv6jtxz9ezh96uwr8dykqyz55nlldp4rn7g7ms07ust8spp5eam7uhxc0w4epnuflgkl62m64qu378nnhkg3vahkm7dhdcqnzl4sdqvg9jxgg8zn2sscqzysxqr23s870l2549nhsr2dfv9ehkl5z95p5rxpks5j2etr35e02z9r6haalrfjs7sz5y7wzenywp8t52w89c9u8taf9m76t2p0w0vxw243y7l4sp5y7gz6" -> PublicKey(hex"026792244df8b138c2000c92ba99dbbf06cea08d06764a1919726b0df3a999e629"),
      "lnbc5u1pwq2jqzsp5lgh8v9xwwuwnxhn84q74p33xdy2plr9vtzns8p7mq8xhx2hp0z7spp56zhpjmfm72e8p8vmfssspe07u7zmnm5hhgynafe4y4lwz6ypusvqdzsd35kw6r5de5kuemwv468wmmjddehgmmjv4ejucm0d40n2vpsta6hqan0w3jhxhmnw3hhye2fgs7nywfhcqp2tqnqpewrz28yrvvvyzjyrvwahuy595t4w4ar3cvt5cq9jx3rmxd4p7vjgmeylfkgjrssc66a9q9hhnd4aj7gqv2zj0jr2zt0gahnv0sp7d6zpp" -> PublicKey(hex"035e8657c3cef12d31bfaf020b15d7d3ced007d1363ec680c628ae28d8b90ec9d5"),
      "lnbc10n1pw9pqp3sp5798d8fpkjpchkfnlwzu5ymp6kphccf3vlff0plwddvqw69npptyspp562wg5n7atx369mt75feu233cnm5h508mx7j0d807lqe0w45gndnqdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrsszfg9lfawdhnp2m785cqgzg4c85mvgct44xdzjea9t0vu4mc22u4prjjz5qd4y7uhgg3wm57muh5wfz8l04kgyq8juwql3vaffm23akspxe0l8y" -> PublicKey(hex"02412be27f47a43062b428804a8cce4e68fbc95d8d1b4198e52df3b15a847f97fd"),
      "lnbc90n1pwypjnpsp5p3lurhdc3nvfmmk9lawwjjhm5ypzh568spq8t0pq5u0uary89z7qpp5m870lhg8qjrykj6hfegawaq0ukzc099ntfezhm8jr48cw5ywgpwqdpl2pshjmt9de6zqen0wgsrjgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp2s0n2u7msmypy9dh96e6exfas434td6a7f5qy5shzyk4r9dxwv0zhyxcqjkmxgnnkjvqhthadhkqvvd66f8gxkdna3jqyzhnnhfs6w3qpjn5kvn" -> PublicKey(hex"03d695ff91b452a279222f2785d7484f474ed5843670870f480fc3030cc1edd92a"),
      "lnbc100n1pdunsursp5m7rz0lyl87yg7rtpavgnlycjqck72luev3sz8u6s42k9nnlcj58qpp5af2vzgyjtj2q48dxl8hpfv9cskwk7q5ahefzyy3zft6jyrc4uv2qdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnyvccqzysxqyd9uqpcp608auvkcr22672nhwqqtul0q6dqrxryfsstttlwyvkzttxt29mxyshley6u45gf0sxc0d9dxr5fk48tj4z2z0wh6asfxhlsea57qphqhgpf" -> PublicKey(hex"03053547b3a1b6eaec43a9974264ae789ae215e60cb34d347a31abc47660a2d98e"),
      "lnbc100n1pd6hzfgsp5zkmdv5jru3dvvppmqdfyu8g8sfhe9af6ff20r5k5rc3yjdn7m9lqpp5au2d4u2f2gm9wyz34e9rls66q77cmtlw3tzu8h67gcdcvj0dsjdqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uqxg5n7462ykgs8a23l3s029dun9374xza88nlf2e34nupmc042lgps7tpwd0ue0he0gdcpfmc5mshmxkgw0hfztyg4j463ux28nh2gagq2vsysz" -> PublicKey(hex"02189f9d1eaf434694c99090e9adb6794062689ae436b8029a74088d908446d457"),
      "lnbc50n1pdl052esp5umfavy877tq93sjsjy6hnmv3v5p0pt3zgrcflpycnql6kds6h23qpp57549dnjwf2wqfz5hg8khu0wlkca8ggv72f9q7x76p0a7azkn3ljsdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnvvscqzysxqyd9uqa2z48kchpmnyafgq2qlt4pruwyjh93emh8cd5wczwy47pkx6qzarmvl28hrnqf98m2rnfa0gx4lnw2jvhlg9l4265240av6t9vdqpzsqfm9m7q" -> PublicKey(hex"02a95657ba003706d06a6cc8577b000c8232544459bbdfc42c1fe403940de0c6d3"),
      "lnbc100n1pd7cwrysp50xlh4d84a0ran48gafyxzhanvw9tkfn5h4qlm6wpz0aw2ha9f83spp57m4rft00sh6za2x0jwe7cqknj568k9xajtpnspql8dd38xmd7musdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcngvscqzysxqyd9uqsxfmfv96q0d7r3qjymwsem02t5jhtq58a30q8lu5dy3jft7wahdq2f5vc5qqymgrrdyshff26ak7m7n0vqyf7t694vam4dcqkvnr65qps26xwg" -> PublicKey(hex"02778f8356f2c47a8f0a2a58ea1d34b77e80d1f51aff7c22037a7797e5681e0099"),
      "lnbc100n1pw9qjdgsp5hxzeu9dtpxukstuadtlyhejc24h8q6hz7exmwhsdqg972a028nwspp5lmycszp7pzce0rl29s40fhkg02v7vgrxaznr6ys5cawg437h80nsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrss47kl34flydtmu2wnszuddrd0nwa6rnu4d339jfzje6hzk6an0uax3kteee2lgx5r0629wehjeseksz0uuakzwy47lmvy2g7hja7mnpsqrhfnrc" -> PublicKey(hex"02e813a1f2f6e2066fa989d4daba5d48a88d02d6ab81f0e271d919691961550c0f"),
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu" -> PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"),
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqpqsqq40wa3khl49yue3zsgm26jrepqr2eghqlx86rttutve3ugd05em86nsefzh4pfurpd9ek9w2vp95zxqnfe2u7ckudyahsa52q66tgzcp6t2dyk" -> PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9qrjgq7md5lu2hhkz657rs2a40xm2elaqda4krv6vy44my49x02azsqwr35puvgzjltd6dfth2awxcq49cx3srkl3zl34xhw7ppv840yf74wqq88rwr5" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9qr3gqjdynggx20rz4nh98uknmtp2wkwk95zru8lfmw0cz9s3t0xpevuzpzz4k34cprpg9jfc3yp8zc827psug69j4w4pkn70rrfddcqf9wnqqcm2nc4" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9q9sqsgqruuf6y6hd77533p6ufl3dapzzt55uj7t88mgty7hvfpy5lzvntpyn82j72fr3wqz985lh7l2f5pnju66nman5z09p24qvp2k8443skqqq38n4w" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
      "lnbc100n1pslczttpp5refxwyd5qvvnxsmswhqtqd50hdcwhk5edp02u3xpy6whf6eua3lqdq8w35hg6gsp56nrnqjjjj2g3wuhdwhy7r3sfu0wae603w9zme8wcq2f3myu3hm6qcqzrm9qxpqqsgqh88td9f8p8ls8r6devh9lhvppwqe6e0lkvehyu8ztu76m9s8nu2x0rfp5z9jmn2ta97mex2ne6yecvtz8r0qej62lvkngpaduhgytncqts4cxs" -> PublicKey(hex"036dc96e30210083a18762be096f13500004fc8af5bcca40f4872e18771ad58b4c"),
    )

    for ((req, nodeId) <- requests) {
      val Success(invoice) = Bolt11Invoice.fromString(req)
      assert(invoice.nodeId == nodeId)
      assert(invoice.toString == req)
    }
  }

  test("no unknown feature in invoice") {
    val invoiceFeatures = TestConstants.Alice.nodeParams.features.bolt11Features().remove(RouteBlinding)
    assert(invoiceFeatures.unknown.nonEmpty)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = invoiceFeatures)
    assert(invoice.features == Features(PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, VariableLengthOnion -> Mandatory))
    assert(Bolt11Invoice.fromString(invoice.toString).get == invoice)
  }

  test("filter non-invoice features when parsing invoices") {
    // The following invoice has feature bit 20 activated (option_anchor_outputs) without feature bit 12 (option_static_remotekey).
    // This doesn't satisfy the feature dependency graph, but since those aren't invoice features, we should ignore it.
    val features = Features(
      Map(VariableLengthOnion -> FeatureSupport.Mandatory, PaymentSecret -> FeatureSupport.Mandatory, AnchorOutputs -> Mandatory),
      Set(UnknownFeature(121), UnknownFeature(156))
    )
    val invoice = createInvoiceUnsafe(Block.LivenetGenesisBlock.hash, None, randomBytes32(), priv, Left("non-invoice features"), CltvExpiryDelta(6), features = features.unscoped()).toString
    val Success(pr) = Bolt11Invoice.fromString(invoice)
    assert(pr.features == features.remove(AnchorOutputs))
  }

  test("invoices can't have high features") {
    assertThrows[Exception](createInvoiceUnsafe(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, Left("Some invoice"), CltvExpiryDelta(18), features = Features[Feature](Map[Feature, FeatureSupport](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory), Set(UnknownFeature(424242)))))
  }
}
