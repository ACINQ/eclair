package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Btc, Crypto, MilliBtc, MilliSatoshi, Satoshi}
import fr.acinq.eclair.payment.PaymentRequest.DescriptionTag
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by fabrice on 15/05/17.
  */
@RunWith(classOf[JUnitRunner])
class PaymentRequestSpec extends FunSuite {

  import PaymentRequest._

  val priv = PrivateKey(BinaryData("e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734"), compressed = true)
  val pub = priv.publicKey
  val nodeId = pub
  assert(nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))

  test("check minimal unit is used") {
    assert('p' === Amount.unit(MilliSatoshi(1)))
    assert('p' === Amount.unit(MilliSatoshi(99)))
    assert('n' === Amount.unit(MilliSatoshi(100)))
    assert('p' === Amount.unit(MilliSatoshi(101)))
    assert('n' === Amount.unit(Satoshi(1)))
    assert('u' === Amount.unit(Satoshi(100)))
    assert('n' === Amount.unit(Satoshi(101)))
    assert('u' === Amount.unit(Satoshi(1155400)))
    assert('m' === Amount.unit(MilliBtc(1)))
    assert('m' === Amount.unit(MilliBtc(10)))
    assert('m' === Amount.unit(Btc(1)))
  }

  test("check that we can still decode non-minimal amount encoding") {
    assert(Some(MilliSatoshi(100000000)) == Amount.decode("1000u"))
    assert(Some(MilliSatoshi(100000000)) == Amount.decode("1000000n"))
    assert(Some(MilliSatoshi(100000000)) == Amount.decode("1000000000p"))
  }

  test("Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad") {
    val pr = PaymentRequest(prefix = "lnbc",
      amount = None,
      paymentHash = BinaryData("0001020304050607080900010203040506070809000102030405060708090102"),
      privateKey = priv,
      description = "Please consider supporting this project",
      timestamp = 1496314658L)
    val ref = PaymentRequest.write(pr)
    assert(ref == "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w")
    val pr1 = PaymentRequest.read(ref)
    assert(pr1 == pr)
    assert(pr1.prefix == "lnbc")
    assert(pr1.amount.isEmpty)
    assert(pr1.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr1.timestamp == 1496314658L)
    assert(pr1.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr1.description == Left("Please consider supporting this project"))
    assert(pr1.fallbackAddress === None)
    assert(pr1.tags.size === 2)
    assert(PaymentRequest.write(pr1.sign(priv)) == ref)
  }

  test("Please send $3 for a cup of coffee to the same peer, within 1 minute") {
    val ref = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(MilliSatoshi(250000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Left("1 cup coffee"))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("Now send $24 for an entire list of things (hashed)") {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqscc6gd6ql3jrc5yzme8v4ntcewwz5cnw92tz0pc8qcuufvq7khhr8wpald05e92xw006sq94mg8v2ndf4sefvf9sygkshp5zfem29trqq2yxxz7"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 2)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP") {
    val pr = PaymentRequest(
      prefix = "lntb",
      amount = Some(MilliSatoshi(2000000000L)),
      timestamp = 1496314658L,
      nodeId = priv.publicKey,
      tags = List(
        DescriptionHashTag(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)),
        PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")),
        FallbackAddressTag("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP")
      ),
      signature = BinaryData.empty).sign(priv)

    val ref = PaymentRequest.write(pr)
    assert(ref == "lntb20m1pvjluezhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un98kmzzhznpurw9sgl2v0nklu2g4d0keph5t7tj9tcqd8rexnd07ux4uv2cjvcqwaxgj7v4uwn5wmypjd5n69z2xm3xgksg28nwht7f6zspwp3f9t")
    val pr1 = PaymentRequest.read(ref)
    assert(pr1.prefix == "lntb")
    assert(pr1.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr1.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr1.timestamp == 1496314658L)
    assert(pr1.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr1.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr1.fallbackAddress === Some("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))
    assert(pr1.tags.size == 3)
    assert(PaymentRequest.write(pr1.sign(priv)) == ref)
  }

  test("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to get to node 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") {
    val pr = PaymentRequest(
      prefix = "lnbc",
      amount = Some(MilliSatoshi(2000000000L)),
      timestamp = 1496314658L,
      nodeId = priv.publicKey,
      tags = List(
        DescriptionHashTag(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)),
        PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")),
        FallbackAddressTag("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"),
        RoutingInfoTag(PublicKey("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), "0102030405060708", 20, 3)
      ),
      signature = BinaryData.empty).sign(priv)
    val ref = PaymentRequest.write(pr)
    assert(ref == "lnbc20m1pvjluezhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85frzjq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqqqqqqq9qqqvs2wmtfjy8myg3ha79lu9z9yv76sllvvpzpnp3xss3k62x2lw95u4hfm6ttg4vm647exd5k4ljrcqgazzh37x5unf6hgzkx8ayd5dg5cpta2dqs")
    val pr1 = PaymentRequest.read(ref)
    assert(pr1.prefix == "lnbc")
    assert(pr1.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr1.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr1.timestamp == 1496314658L)
    assert(pr1.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr1.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr1.fallbackAddress === Some("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    assert(pr1.routingInfo() === RoutingInfoTag(PublicKey("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), "0102030405060708", 20, 3) :: Nil)
    assert(pr1.tags.size == 4)
    assert(PaymentRequest.write(pr1.sign(priv)) == ref)
}


  test("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX") {
    val pr = PaymentRequest(
      prefix = "lnbc",
      amount = Some(MilliSatoshi(2000000000L)),
      timestamp = 1496314658L,
      nodeId = priv.publicKey,
      tags = List(
        DescriptionHashTag(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)),
        PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")),
        FallbackAddressTag("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX")
      ),
      signature = BinaryData.empty).sign(priv)
    val ref = PaymentRequest.write(pr)
    assert(ref == "lnbc20m1pvjluezhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppj3a24vwu6r8ejrss3axul8rxldph2q7z9kmrgvr7xlaqm47apw3d48zm203kzcq357a4ls9al2ea73r8jcceyjtya6fu5wzzpe50zrge6ulk4nvjcpxlekvmxl6qcs9j3tz0469gq5g658y")
    val pr1 = PaymentRequest.read(ref)
    assert(pr1.prefix == "lnbc")
    assert(pr1.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr1.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr1.timestamp == 1496314658L)
    assert(pr1.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr1.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr1.fallbackAddress === Some("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
    assert(pr1.tags.size == 3)
    assert(PaymentRequest.write(pr1.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") {
    val pr = PaymentRequest(
      prefix = "lnbc",
      amount = Some(MilliSatoshi(2000000000L)),
      timestamp = 1496314658L,
      nodeId = priv.publicKey,
      tags = List(
        DescriptionHashTag(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)),
        PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")),
        FallbackAddressTag("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
      ),
      signature = BinaryData.empty).sign(priv)

    val ref = PaymentRequest.write(pr)
    assert(ref == "lnbc20m1pvjluezhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7kepvrhrm9s57hejg0p662ur5j5cr03890fa7k2pypgttmh4897d3raaq85a293e9jpuqwl0rnfuwzam7yr8e690nd2ypcq9hlkdwdvycqa0qza8")
    val pr1 = PaymentRequest.read(ref)
    assert(pr1.prefix == "lnbc")
    assert(pr1.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr1.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr1.timestamp == 1496314658L)
    assert(pr1.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr1.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr1.fallbackAddress === Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    assert(pr1.tags.size == 3)
    assert(PaymentRequest.write(pr1.sign(priv)) == ref)
  }


  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3") {
    val pr = PaymentRequest(
      prefix = "lnbc",
      amount = Some(MilliSatoshi(2000000000L)),
      timestamp = 1496314658L,
      nodeId = priv.publicKey,
      tags = List(
        DescriptionHashTag(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)),
        PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")),
        FallbackAddressTag("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3")
      ),
      signature = BinaryData.empty).sign(priv)
    val ref = PaymentRequest.write(pr)
    assert(ref == "lnbc20m1pvjluezhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q28j0v3rwgy9pvjnd48ee2pl8xrpxysd5g44td63g6xcjcu003j3qe8878hluqlvl3km8rm92f5stamd3jw763n3hck0ct7p8wwj463cql26ava")
    val pr1 = PaymentRequest.read(ref)
    assert(pr1.prefix == "lnbc")
    assert(pr1.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr1.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr1.timestamp == 1496314658L)
    assert(pr1.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr1.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr1.fallbackAddress === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr1.tags.size == 3)
    assert(PaymentRequest.write(pr1.sign(priv)) == ref)
  }
}
