package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto}
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

  test("Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad") {
    val pr = PaymentRequest.read("lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqq7fshvguvjs864g4yj47aedw4y402hdl9g2tqqhyed3nuhr7c908g6uhq9llj7w3s58k3sej3tcg4weqxrxmp3cwxuvy9kfr0uzy8jgpy6uzal")
    assert(pr.amount.isEmpty)
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))

    val pr1 = PaymentRequest(
      "lnbc",
      amount = "",
      timestamp = 1496314658L,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")) :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqq7fshvguvjs864g4yj47aedw4y402hdl9g2tqqhyed3nuhr7c908g6uhq9llj7w3s58k3sej3tcg4weqxrxmp3cwxuvy9kfr0uzy8jgpy6uzal")
  }

  test("Please send $3 for a cup of coffee to the same peer, within 1 minute") {
    val pr = PaymentRequest.read("lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuazh8qt5w7qeewkmxtv55khqxvdfs9zzradsvj7rcej9knpzdwjykcq8gv4v2dl705pjadhpsc967zhzdpuwn5qzjm0s4hqm2u0vuhhqq7vc09u")
    assert(pr.amount == "2500u")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val expiry = pr.tags.collectFirst {
      case ExpiryTag(expiry) => expiry
    }
    assert(expiry == Some(60))

    val pr1 = PaymentRequest(
      "lnbc",
      amount = "2500u",
      timestamp = pr.timestamp,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")) :: DescriptionTag("1 cup coffee") :: ExpiryTag(60) :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuazh8qt5w7qeewkmxtv55khqxvdfs9zzradsvj7rcej9knpzdwjykcq8gv4v2dl705pjadhpsc967zhzdpuwn5qzjm0s4hqm2u0vuhhqq7vc09u")
  }


  test("Now send $24 for an entire list of things (hashed)") {
    val pr = PaymentRequest.read("lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsvjfls3ljx9e93jkw0kw40yxn4pevgzflf83qh2852esjddv4xk4z70nehrdcxa4fk0t6hlcc6vrxywke6njenk7yzkzw0quqcwxphkcpvam37w")
    assert(pr.amount == "20m")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val hash = pr.tags.collectFirst {
      case HashTag(hash) => hash
    }
    assert(hash == Some(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))

    val pr1 = PaymentRequest(
      "lnbc",
      amount = "20m",
      timestamp = pr.timestamp,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")) :: HashTag(hash.get) :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsvjfls3ljx9e93jkw0kw40yxn4pevgzflf83qh2852esjddv4xk4z70nehrdcxa4fk0t6hlcc6vrxywke6njenk7yzkzw0quqcwxphkcpvam37w")

  }

  test("The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP") {
    val pr = PaymentRequest.read("lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un98hp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsqh84fmvn2klvglsjxfy0vq2mz6t9kjfzlxfwgljj35w2kwa60qv49k7jlsgx43yhs9nuutllkhhnt090mmenuhp8ue33pv4klmrzlcqpus2s2r")
    assert(pr.amount == "20m")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val hash = pr.tags.collectFirst {
      case HashTag(hash) => hash
    }
    assert(hash == Some(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes)))
    val address = pr.tags.collectFirst {
      case f@FallbackAddressTag(version, hash) => pr.address(f)
    }
    assert(address == Some("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))

    val pr1 = PaymentRequest(
      "lntb",
      amount = "20m",
      timestamp = pr.timestamp,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102")) :: FallbackAddressTag("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP") :: HashTag(hash.get)  :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lntb20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un98hp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsqh84fmvn2klvglsjxfy0vq2mz6t9kjfzlxfwgljj35w2kwa60qv49k7jlsgx43yhs9nuutllkhhnt090mmenuhp8ue33pv4klmrzlcqpus2s2r")

  }

  test("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to get to node 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") {
    val pr = PaymentRequest.read("lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqrzjq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqqqqqqq9qqqvfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsjtf8rrkd7dujvdvrxhuk5a0tt9x9qh0t95jemn4tpen9y3nn7yt8jrmlyzffjh0hue8edkkq3090hruc8shpfu6wk4chfdvdusakycgpqtn4sp")
    assert(pr.amount == "20m")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val address = pr.tags.collectFirst {
      case f@FallbackAddressTag(version, hash) => pr.address(f)
    }
    assert(address == Some("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    val routingInfo = pr.tags.collectFirst {
      case RoutingInfoTag(pubkey, channelId, fee, cltv) => s"$pubkey/$channelId/$fee/$cltv"
    }
    assert(routingInfo == Some("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255/0102030405060708/20/3"))


    val pr1 = PaymentRequest(
      "lnbc",
      amount = "20m",
      timestamp = pr.timestamp,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
        :: RoutingInfoTag(BinaryData("029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") ,BinaryData("0102030405060708") ,20,3)
        :: FallbackAddressTag("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T")
        :: HashTag(Crypto.sha256("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))
        :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqrzjq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqqqqqqq9qqqvfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsjtf8rrkd7dujvdvrxhuk5a0tt9x9qh0t95jemn4tpen9y3nn7yt8jrmlyzffjh0hue8edkkq3090hruc8shpfu6wk4chfdvdusakycgpqtn4sp")
  }


  test("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX") {
    val pr = PaymentRequest.read("lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppj3a24vwu6r8ejrss3axul8rxldph2q7z93xufve9n04786ust96l3dj0cp22fw7wyvcjrdjtg57qws9u96n2kv4xf8x9yu2ja6f00vjgp5y4lvj30xxy0duwqgz8yfqypfmxgjksq00galp")
    assert(pr.amount == "20m")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val address = pr.tags.collectFirst {
      case f@FallbackAddressTag(version, hash) => pr.address(f)
    }
    assert(address == Some("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
  }

  test("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") {
    val pr = PaymentRequest.read("lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7k2s057u6sfxswv5ysyvmzqemfnxew76stk45gfk0y0azxd8kglwrquhcxcvhww4f7zaxv8kpxwfvxnfdrzu20u56ajnxk3hj3r6p63jqpdsuvna")
    assert(pr.amount == "20m")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val address = pr.tags.collectFirst {
      case f@FallbackAddressTag(version, hash) => pr.address(f)
    }
    assert(address == Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))

    val pr1 = PaymentRequest(
      "lnbc",
      amount = "20m",
      timestamp = pr.timestamp,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
        :: FallbackAddressTag("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7k2s057u6sfxswv5ysyvmzqemfnxew76stk45gfk0y0azxd8kglwrquhcxcvhww4f7zaxv8kpxwfvxnfdrzu20u56ajnxk3hj3r6p63jqpdsuvna")
  }


  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3") {
    val pr = PaymentRequest.read("lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qhkm9qa8yszl8hqzaz9ctqagexxk2l0fyjcy0xhlsaggveqstwmz8rfc3afujc966fgjk47mzg0zzcrcg8zs89722vp2egxja0j3eucsq38r7dh")
    assert(pr.amount == "20m")
    assert(pr.paymentHash == BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
    assert(pr.nodeId == PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")))
    val address = pr.tags.collectFirst {
      case f@FallbackAddressTag(version, hash) => pr.address(f)
    }
    assert(address == Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))

    val pr1 = PaymentRequest(
      "lnbc",
      amount = "20m",
      timestamp = pr.timestamp,
      nodeId = PublicKey(BinaryData("03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad")),
      tags = PaymentHashTag(BinaryData("0001020304050607080900010203040506070809000102030405060708090102"))
        :: FallbackAddressTag("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3")
        :: Nil,
      signature = BinaryData.empty
    )

    val pr2 = pr1.sign(priv)
    assert(PaymentRequest.write(pr2) == "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qhkm9qa8yszl8hqzaz9ctqagexxk2l0fyjcy0xhlsaggveqstwmz8rfc3afujc966fgjk47mzg0zzcrcg8zs89722vp2egxja0j3eucsq38r7dh")
  }
}
