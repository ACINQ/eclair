package fr.acinq.eclair.payment

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Bech32, BinaryData, Block, Btc, Crypto, MilliBtc, MilliSatoshi, Protocol, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.payment.PaymentRequest._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by fabrice on 15/05/17.
  */
@RunWith(classOf[JUnitRunner])
class PaymentRequestSpec extends FunSuite {

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
    val ref = "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount.isEmpty)
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Left("Please consider supporting this project"))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 2)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
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
    val ref = "lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3x9et2e20v6pu37c5d9vax37wxq72un98k6vcx9fz94w0qf237cm2rqv9pmn5lnexfvf5579slr4zq3u8kmczecytdx0xg9rwzngp7e6guwqpqlhssu04sucpnz4axcv2dstmknqq6jsk2l"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lntb")
    assert(pr.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === Some("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzqj9n4evl6mr5aj9f58zp6fyjzup6ywn3x6sk8akg5v4tgn2q8g4fhx05wf6juaxu9760yp46454gpg5mtzgerlzezqcqvjnhjh8z3g2qqdhhwkj"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === Some("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    assert(pr.routingInfo === List(List(
      ExtraHop(PublicKey("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId(72623859790382856L), 1, 20, 3),
      ExtraHop(PublicKey("039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId(217304205466536202L), 2, 30, 4)
    )))
    assert(BinaryData(Protocol.writeUInt64(0x0102030405060708L, ByteOrder.BIG_ENDIAN)) == BinaryData("0102030405060708"))
    assert(BinaryData(Protocol.writeUInt64(0x030405060708090aL, ByteOrder.BIG_ENDIAN)) == BinaryData("030405060708090a"))
    assert(pr.tags.size == 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }


  test("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX") {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppj3a24vwu6r8ejrss3axul8rxldph2q7z9kk822r8plup77n9yq5ep2dfpcydrjwzxs0la84v3tfw43t3vqhek7f05m6uf8lmfkjn7zv7enn76sq65d8u9lxav2pl6x3xnc2ww3lqpagnh0u"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === Some("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfppqw508d6qejxtdg4y5r3zarvary0c5xw7kknt6zz5vxa8yh8jrnlkl63dah48yh6eupakk87fjdcnwqfcyt7snnpuz7vp83txauq4c60sys3xyucesxjf46yqnpplj0saq36a554cp9wt865"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }


  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3") {
    val ref = "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qvnjha2auylmwrltv2pkp2t22uy8ura2xsdwhq5nm7s574xva47djmnj2xeycsu7u5v8929mvuux43j0cqhhf32wfyn2th0sv4t9x55sppz5we8"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr.tags.size == 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3 and a minimum htlc cltv expiry of 12") {
    val ref = "lnbc20m1pvjluezcqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q90qkf3gd7fcqs0ewr7t3xf72ptmc4n38evg0xhy4p64nlg7hgrmq6g997tkrvezs8afs0x0y8v4vs8thwsk6knkvdfvfa7wmhhpcsxcqw0ny48"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount == Some(MilliSatoshi(2000000000L)))
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    assert(pr.description == Right(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    assert(pr.fallbackAddress === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr.minFinalCltvExpiry === Some(12))
    assert(pr.tags.size == 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("expiry is a variable-length unsigned value") {
    val pr = PaymentRequest(Block.RegtestGenesisBlock.hash, Some(MilliSatoshi(100000L)), BinaryData("0001020304050607080900010203040506070809000102030405060708090102"),
      priv, "test", fallbackAddress = None, expirySeconds = Some(21600), timestamp = System.currentTimeMillis() / 1000L)

    val serialized = PaymentRequest write pr
    val pr1 = PaymentRequest read serialized
    assert(pr.expiry === Some(21600))
  }

  test("ignore unknown tags") {
    // create a new tag that we don't know about
    class MyExpiryTag(override val seconds: Long) extends ExpiryTag(seconds) {
      // replace the tag with 'j'  which is not used yet
      override def toInt5s = super.toInt5s.updated(0, Bech32.map('j'))
    }

    val pr = PaymentRequest(
      prefix = "lntb",
      amount = Some(MilliSatoshi(100000L)),
      timestamp = System.currentTimeMillis() / 1000L,
      nodeId = nodeId,
      tags = List(
        PaymentHashTag(BinaryData("01" * 32)),
        DescriptionTag("description"),
        new MyExpiryTag(42L)
      ),
      signature = BinaryData.empty).sign(priv)

    val serialized = PaymentRequest write pr
    val pr1 = PaymentRequest read serialized
    val Some(unknownTag) = pr1.tags.collectFirst { case u: UnknownTag => u }
    assert(unknownTag.tag == Bech32.map('j'))
    assert(unknownTag.toInt5s == (new MyExpiryTag(42L)).toInt5s)
  }
}
