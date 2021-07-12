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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, BtcDouble, ByteVector32, Crypto, MilliBtcDouble, Protocol, SatoshiLong}
import fr.acinq.eclair.Features.{PaymentSecret, _}
import fr.acinq.eclair.payment.PaymentRequest._
import fr.acinq.eclair.{CltvExpiryDelta, FeatureSupport, Features, MilliSatoshiLong, ShortChannelId, TestConstants, ToMilliSatoshiConversion}
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits._
import scodec.codecs.bits

import java.nio.ByteOrder

/**
 * Created by fabrice on 15/05/17.
 */

class PaymentRequestSpec extends AnyFunSuite {

  val priv = PrivateKey(hex"e126f68f7eafcc8b74f54d269fe206be715000f94dac067d1c04a8ca3b2db734")
  val pub = priv.publicKey
  val nodeId = pub
  assert(nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))

  test("check minimal unit is used") {
    assert('p' === Amount.unit(1 msat))
    assert('p' === Amount.unit(99 msat))
    assert('n' === Amount.unit(100 msat))
    assert('p' === Amount.unit(101 msat))
    assert('n' === Amount.unit((1 sat).toMilliSatoshi))
    assert('u' === Amount.unit((100 sat).toMilliSatoshi))
    assert('n' === Amount.unit((101 sat).toMilliSatoshi))
    assert('u' === Amount.unit((1155400 sat).toMilliSatoshi))
    assert('m' === Amount.unit((1 millibtc).toMilliSatoshi))
    assert('m' === Amount.unit((10 millibtc).toMilliSatoshi))
    assert('m' === Amount.unit((1 btc).toMilliSatoshi))
  }

  test("decode empty amount") {
    assert(Amount.decode("") === None)
    assert(Amount.decode("0") === None)
    assert(Amount.decode("0p") === None)
    assert(Amount.decode("0n") === None)
    assert(Amount.decode("0u") === None)
    assert(Amount.decode("0m") === None)
  }

  test("check that we can still decode non-minimal amount encoding") {
    assert(Amount.decode("1000u") === Some(100000000 msat))
    assert(Amount.decode("1000000n") === Some(100000000 msat))
    assert(Amount.decode("1000000000p") === Some(100000000 msat))
  }

  test("data string -> bitvector") {
    assert(string2Bits("p") === bin"00001")
    assert(string2Bits("pz") === bin"0000100010")
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
    val codec = PaymentRequest.Codecs.alignedBytesCodec(bits)
    assert(codec.decode(bin"1010101000").require == DecodeResult(bin"10101010", BitVector.empty))
    assert(codec.decode(bin"1010101001").isFailure) // non-zero padding
  }

  test("Please make a donation of any amount using payment_hash 0001020304050607080900010203040506070809000102030405060708090102 to me @03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad") {
    val ref = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq0py3tfrnxkt5xadpzangn5rry6r0kqt4f3g36lwln8wwpxtxqccn5agpyte3nx0v78uwn78zu6k30k5mgdgn50yvnd20namlmzp2ersq8065fg"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount.isEmpty)
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.paymentSecret.map(_.bytes) === Some(hex"1111111111111111111111111111111111111111111111111111111111111111"))
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("Please consider supporting this project"))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("Please send $3 for a cup of coffee to the same peer, within 1 minute") {
    val ref = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuu4tgyw2zt3v3c7eljkv2cn8a3etgn8kh8ukctqdmuxdfa7xup0w5ruwpt6eugv73pgzqczz3nc8tcqt2pcljp8ldkrnu5klff35vyscq6lp6ja"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(250000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("1 cup coffee"))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("Please send 0.0025 BTC for a cup of nonsense (ナンセンス 1杯) to the same peer, within one minute") {
    val ref = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuy0x9mrk2a32xsyvz6lzmrs38vzemux64pv6vdtmshnr9fc8eqgt4j74fnf3s60tzgeh6dnwzyv56wftaqwyyl3xu9nccq3py2hnnvsgqu5lqmm"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(250000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Left("ナンセンス 1杯"))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("Now send $24 for an entire list of things (hashed)") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs6e4fy93me7wjwdf9sxgrzr8xldm570z02ur92rv6pa7wkhzpfehnecuyhp4mdhsv5t7em4jz4tjtchs8zmx3tr555yl59lk848due0gqvkanpl"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === None)
    assert(pr.tags.size === 3)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("The same, on testnet, with a fallback address mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP") {
    val ref = "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989yc66dftyfrx5g53s7r80rxljxj8wugm7nhqcnfdmqzw758jda4yur0nxahhyg04ln2p47wmjdrz5weu2ajfqh4l2qya4244ujuytvgp6nw4dc"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lntb")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === Some("mk2QpYatsKicvFVuTAQLBryyccRXMUaGHP"))
    assert(pr.tags.size == 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback address 1RustyRX2oai4EYYDpQGWvEL62BBGqN9T with extra routing info to go via nodes 029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255 then 039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfpp3qjmp7lwpagxun9pygexvgpjdc4jdj85fr9yq20q82gphp2nflc7jtzrcazrra7wwgzxqc8u7754cdlpfrmccae92qgzqvzq2ps8pqqqqqqpqqqqq9qqqvpeuqafqxu92d8lr6fvg0r5gv0heeeqgcrqlnm6jhphu9y00rrhy4grqszsvpcgpy9qqqqqqgqqqqq7qqzqg042ea62q6wnxh909rfuu4x2amxc59mj99mrhr32kvqhytrm04us8edg0syy9n0ukgam0ud20jcxwskphv3rzpnengjsf8m3w9u5w8cqzrhtg2"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === Some("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T"))
    assert(pr.routingInfo === List(List(
      ExtraHop(PublicKey(hex"029e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId(72623859790382856L), 1 msat, 20, CltvExpiryDelta(3)),
      ExtraHop(PublicKey(hex"039e03a901b85534ff1e92c43c74431f7ce72046060fcf7a95c37e148f78c77255"), ShortChannelId(217304205466536202L), 2 msat, 30, CltvExpiryDelta(4))
    )))
    assert(Protocol.writeUInt64(0x0102030405060708L, ByteOrder.BIG_ENDIAN) == hex"0102030405060708")
    assert(Protocol.writeUInt64(0x030405060708090aL, ByteOrder.BIG_ENDIAN) == hex"030405060708090a")
    assert(pr.tags.size == 5)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2sh) address 3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppj3a24vwu6r8ejrss3axul8rxldph2q7z9859drl0equ52uf58vpc4ekc58katdnsa0wpxrp7x8m34kz9wh4rhlk2ke89c5uuwcngz8axxkhs9drar8j3h83k5yx27s7fw7l9va2cpt3h3em"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === Some("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX"))
    assert(pr.tags.size == 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2wpkh) address bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfppqw508d6qejxtdg4y5r3zarvary0c5xw7k2v7at953q5p0dyg8axg4xxfxsm5d4hl8x2j606a5usxzgzs35rjq2m05ly7qlt293get8qct3tsv2h0f87ygthvg5mentx24yf39v4gpgx7n58"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
    assert(pr.tags.size == 4)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qdplqpgddtaej8regxl46lj2jgu0s8yeent3dugye96zsldwtu2a3edz947azn7qksetrrk38mx2kryp0vxnh2nqgenuwcdau5flwa2cqcnjrk0"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr.tags.size == 4)
    assert(pr.features.bitmask.isEmpty)
    assert(!pr.features.allowMultiPart)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, with fallback (p2wsh) address bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3 and a minimum htlc cltv expiry of 12") {
    val ref = "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygscqpvpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqsfp4qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q2p29ulle8ent6hnchk9qeggmrvjvrs7j2vu6gdrcj2jz2r8qtcj3ge5nun8smpwem9sl4flev7j9ru3x5qgqr5dwrnzrz5gj8ck6d5sp52le9p"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix == "lnbc")
    assert(pr.amount === Some(2000000000 msat))
    assert(pr.paymentHash.bytes == hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.timestamp == 1496314658L)
    assert(pr.nodeId == PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description == Right(Crypto.sha256(ByteVector.view("One piece of chocolate cake, one icecream cone, one pickle, one slice of swiss cheese, one slice of salami, one lollypop, one piece of cherry pie, one sausage, one cupcake, and one slice of watermelon".getBytes))))
    assert(pr.fallbackAddress === Some("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"))
    assert(pr.minFinalCltvExpiryDelta === Some(CltvExpiryDelta(12)))
    assert(pr.tags.size == 5)
    assert(pr.features.bitmask.isEmpty)
    assert(!pr.features.allowMultiPart)
    assert(PaymentRequest.write(pr.sign(priv)) == ref)
  }

  test("On mainnet, please send $30 for coffee beans to the same peer, which supports features 9, 15 and 99, using secret 0x1111111111111111111111111111111111111111111111111111111111111111") {
    val refs = Seq(
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu",
      // All upper-case
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu".toUpperCase,
      // With ignored fields
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq2qrqqqfppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqppnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhpnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqspnqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsp4qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnp5qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqnpkqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq2jxxfsnucm4jf4zwtznpaxphce606fvhvje5x7d4gw7n73994hgs7nteqvenq8a4ml8aqtchv5d9pf7l558889hp4yyrqv6a7zpq9fgpskqhza"
    )

    for (ref <- refs) {
      val pr = PaymentRequest.read(ref)
      assert(pr.prefix === "lnbc")
      assert(pr.amount === Some(2500000000L msat))
      assert(pr.paymentHash.bytes === hex"0001020304050607080900010203040506070809000102030405060708090102")
      assert(pr.paymentSecret === Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
      assert(pr.timestamp === 1496314658L)
      assert(pr.nodeId === PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
      assert(pr.description === Left("coffee beans"))
      assert(pr.features.bitmask === bin"1000000000000000000000000000000000000000000000000000000000000000000000000000000000001000001000000000")
      assert(!pr.features.allowMultiPart)
      assert(!pr.features.requirePaymentSecret)
      assert(!pr.features.allowTrampoline)
      assert(pr.features.areSupported(TestConstants.Alice.nodeParams))
      assert(PaymentRequest.write(pr.sign(priv)) === ref.toLowerCase)
    }
  }

  test("On mainnet, please send $30 for coffee beans to the same peer, which supports features 9, 15, 99 and 100, using secret 0x1111111111111111111111111111111111111111111111111111111111111111") {
    val ref = "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqpqsqq40wa3khl49yue3zsgm26jrepqr2eghqlx86rttutve3ugd05em86nsefzh4pfurpd9ek9w2vp95zxqnfe2u7ckudyahsa52q66tgzcp6t2dyk"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix === "lnbc")
    assert(pr.amount === Some(2500000000L msat))
    assert(pr.paymentHash.bytes === hex"0001020304050607080900010203040506070809000102030405060708090102")
    assert(pr.paymentSecret === Some(ByteVector32(hex"1111111111111111111111111111111111111111111111111111111111111111")))
    assert(pr.timestamp === 1496314658L)
    assert(pr.nodeId === PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description === Left("coffee beans"))
    assert(pr.fallbackAddress().isEmpty)
    assert(pr.features.bitmask === bin"000011000000000000000000000000000000000000000000000000000000000000000000000000000000000001000001000000000")
    assert(!pr.features.allowMultiPart)
    assert(!pr.features.requirePaymentSecret)
    assert(!pr.features.allowTrampoline)
    assert(!pr.features.areSupported(TestConstants.Alice.nodeParams))
    assert(PaymentRequest.write(pr.sign(priv)) === ref)
  }

  test("On mainnet, please send 0.00967878534 BTC for a list of items within one week, amount in pico-BTC") {
    val ref = "lnbc9678785340p1pwmna7lsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddjhygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuwz87fcqqeuqqqyqqqqlgqqqqn3qq9qufzt0rlypjgwvq3k0p79gkjl2fadnswnl2x89puj8m5g3lj9q4y9ft5fs7v5t3g95dcavg23l634vatl46ww8ev6dx2m6xp2ehv4hssq0e27ql"
    val pr = PaymentRequest.read(ref)
    assert(pr.prefix === "lnbc")
    assert(pr.amount === Some(967878534 msat))
    assert(pr.paymentHash.bytes === hex"462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f")
    assert(pr.timestamp === 1572468703L)
    assert(pr.nodeId === PublicKey(hex"03e7156ae33b0a208d0744199163177e909e80176e55d97a2f221ede0f934dd9ad"))
    assert(pr.description === Left("Blockstream Store: 88.85 USD for Blockstream Ledger Nano S x 1, \"Back In My Day\" Sticker x 2, \"I Got Lightning Working\" Sticker x 2 and 1 more items"))
    assert(pr.fallbackAddress().isEmpty)
    assert(pr.expiry === Some(604800L))
    assert(pr.minFinalCltvExpiryDelta === Some(CltvExpiryDelta(10)))
    assert(pr.routingInfo === Seq(Seq(ExtraHop(PublicKey(hex"03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"), ShortChannelId("589390x3312x1"), 1000 msat, 2500, CltvExpiryDelta(40)))))
    assert(pr.features.areSupported(TestConstants.Alice.nodeParams))
    assert(PaymentRequest.write(pr.sign(priv)) === ref)
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
      "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaxtrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspk28uwq",
      // String is too short.
      "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6na6hlh",
      // Invalid multiplier.
      "lnbc2500x1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpujr6jxr9gq9pv6g46y7d20jfkegkg4gljz2ea2a3m9lmvvr95tq2s0kvu70u3axgelz3kyvtp2ywwt0y8hkx2869zq5dll9nelr83zzqqpgl2zg"
    )
    for (ref <- refs) {
      assertThrows[Exception](PaymentRequest.read(ref))
    }
  }

  test("correctly serialize/deserialize variable-length tagged fields") {
    val number = 123456
    val codec = PaymentRequest.Codecs.dataCodec(scodec.codecs.bits).as[PaymentRequest.Expiry]
    val field = PaymentRequest.Expiry(number)
    assert(field.toLong == number)

    val serializedExpiry = codec.encode(field).require
    val field1 = codec.decodeValue(serializedExpiry).require
    assert(field1 == field)

    // Now with a payment request
    val pr = PaymentRequest(chainHash = Block.LivenetGenesisBlock.hash, amount = Some(123 msat), paymentHash = ByteVector32(ByteVector.fill(32)(1)), privateKey = priv, description = "Some invoice", minFinalCltvExpiryDelta = CltvExpiryDelta(18), expirySeconds = Some(123456), timestamp = 12345)
    assert(pr.minFinalCltvExpiryDelta === Some(CltvExpiryDelta(18)))
    val serialized = PaymentRequest.write(pr)
    val pr1 = PaymentRequest.read(serialized)
    assert(pr == pr1)
  }

  test("ignore unknown tags") {
    val pr = PaymentRequest(
      prefix = "lntb",
      amount = Some(100000 msat),
      timestamp = System.currentTimeMillis() / 1000L,
      nodeId = nodeId,
      tags = List(
        PaymentHash(ByteVector32(ByteVector.fill(32)(1))),
        Description("description"),
        UnknownTag21(BitVector("some data we don't understand".getBytes))
      ),
      signature = ByteVector.empty).sign(priv)

    val serialized = PaymentRequest.write(pr)
    val pr1 = PaymentRequest.read(serialized)
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
      assert(decoded === value)
      val encoded = Codecs.taggedFieldCodec.encode(value).require
      assert(encoded === data)
    }
  }

  test("accept uppercase payment request") {
    val input = "lntb1500n1pwxx94fsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5q3xzmwuvxpkyhz6pvg3fcfxz0259kgh367qazj62af9rs0pw07dsdpa2fjkzep6yp58garswvaz7tmvd9nksarwd9hxw6n0w4kx2tnrdakj7grfwvs8wcqzysxqr23swwl9egjej7rvvt9zdxrtpy8xuu6cckdwajfccmtz7n90ea34k3j595w77pt69s5dx5a46f4k4w5avtvjkc4l4rm8n4xmk7fe3pms3pspdd032j"
    assert(PaymentRequest.write(PaymentRequest.read(input.toUpperCase())) == input)
  }

  test("Pay 1 BTC without multiplier") {
    val ref = "lnbc1000m1pdkmqhusp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5n2ees808r98m0rh4472yyth0c5fptzcxmexcjznrzmq8xald0cgqdqsf4ujqarfwqsxymmccqp2pv37ezvhth477nu0yhhjlcry372eef57qmldhreqnr0kx82jkupp3n7nw42u3kdyyjskdr8jhjy2vugr3skdmy8ersft36969xplkxsp2v7c58"
    val pr = PaymentRequest.read(ref)
    assert(pr.amount === Some(100000000000L msat))
    assert(pr.features.bitmask === BitVector.empty)
  }

  test("supported payment request features") {
    val nodeParams = TestConstants.Alice.nodeParams.copy(features = Features(knownFeatures.map(f => f -> FeatureSupport.Optional).toMap))
    case class Result(allowMultiPart: Boolean, requirePaymentSecret: Boolean, areSupported: Boolean) // "supported" is based on the "it's okay to be odd" rule
    val featureBits = Map(
      PaymentRequestFeatures(bin"               00000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"               00100100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"               00010100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"               00100100000100000000") -> Result(allowMultiPart = true, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"               01000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"          0000010000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"          0000011000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"          0000110000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"          0000100000101000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      // those are useful for nonreg testing of the areSupported method (which needs to be updated with every new supported mandatory bit)
      PaymentRequestFeatures(bin"          0010000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false),
      PaymentRequestFeatures(bin"     000001000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false),
      PaymentRequestFeatures(bin"     000100000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = true),
      PaymentRequestFeatures(bin"00000010000000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false),
      PaymentRequestFeatures(bin"00001000000000000000100000100000000") -> Result(allowMultiPart = false, requirePaymentSecret = true, areSupported = false)
    )

    for ((features, res) <- featureBits) {
      val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "Some invoice", CltvExpiryDelta(18), features = features)
      assert(Result(pr.features.allowMultiPart, pr.features.requirePaymentSecret, pr.features.areSupported(nodeParams)) === res)
      assert(PaymentRequest.read(PaymentRequest.write(pr)) === pr)
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
      assert(PaymentRequestFeatures(bitmask).toByteVector === featureBytes)
    }
  }

  test("payment secret") {
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "Some invoice", CltvExpiryDelta(18))
    assert(pr.paymentSecret.isDefined)
    assert(pr.features === PaymentRequestFeatures(PaymentSecret.mandatory, VariableLengthOnion.mandatory))
    assert(pr.features.requirePaymentSecret)

    val pr1 = PaymentRequest.read(PaymentRequest.write(pr))
    assert(pr1.paymentSecret === pr.paymentSecret)

    val pr2 = PaymentRequest.read("lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl")
    assert(!pr2.features.requirePaymentSecret)
    assert(pr2.paymentSecret === None)

    // An invoice that sets the payment secret feature bit must provide a payment secret.
    assertThrows[IllegalArgumentException](
      PaymentRequest.read("lnbc1230p1pwljzn3pp5qyqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqdq52dhk6efqd9h8vmmfvdjs9qypqsqylvwhf7xlpy6xpecsnpcjjuuslmzzgeyv90mh7k7vs88k2dkxgrkt75qyfjv5ckygw206re7spga5zfd4agtdvtktxh5pkjzhn9dq2cqz9upw7")
    )

    // A multi-part invoice must use a payment secret.
    assertThrows[IllegalArgumentException](
      PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "MPP without secrets", CltvExpiryDelta(18), features = PaymentRequestFeatures(BasicMultiPartPayment.optional, VariableLengthOnion.optional))
    )
  }

  test("trampoline") {
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "Some invoice", CltvExpiryDelta(18))
    assert(!pr.features.allowTrampoline)

    val pr1 = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "Some invoice", CltvExpiryDelta(18), features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, TrampolinePayment.optional))
    assert(!pr1.features.allowMultiPart)
    assert(pr1.features.allowTrampoline)

    val pr2 = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(123 msat), ByteVector32.One, priv, "Some invoice", CltvExpiryDelta(18), features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional, TrampolinePayment.optional))
    assert(pr2.features.allowMultiPart)
    assert(pr2.features.allowTrampoline)

    val pr3 = PaymentRequest.read("lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl")
    assert(!pr3.features.allowTrampoline)
  }

  test("nonreg") {
    val requests = List(
      "lnbc40n1pw9qjvwpp5qq3w2ln6krepcslqszkrsfzwy49y0407hvks30ec6pu9s07jur3sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdencqzysxqrrss7ju0s4dwx6w8a95a9p2xc5vudl09gjl0w2n02sjrvffde632nxwh2l4w35nqepj4j5njhh4z65wyfc724yj6dn9wajvajfn5j7em6wsq2elakl",
      "lnbc1500n1pwyvqwfpp5p5nxwpuk02nd2xtzwex97gtjlpdv0lxj5z08vdd0hes7a0h437qsdpa2fjkzep6yp8kumrfdejjqempd43xc6twvusxjueqd9kxcet8v9kzqct8v95kucqzysxqr23s8r9seqv6datylwtjcvlpdkukfep7g80hujz3w8t599saae7gap6j48gs97z4fvrx4t4ajra6pvdyf5ledw3tg7h2s3606qm79kk59zqpeygdhd",
      "lnbc800n1pwykdmfpp5zqjae54l4ecmvm9v338vw2n07q2ehywvy4pvay53s7068t8yjvhqdqddpjkcmr0yysjzcqp27lya2lz7d80uxt6vevcwzy32227j3nsgyqlrxuwgs22u6728ldszlc70qgcs56wglrutn8jnnnelsk38d6yaqccmw8kmmdlfsyjd20qp69knex",
      "lnbc300n1pwzezrnpp5zgwqadf4zjygmhf3xms8m4dd8f4mdq26unr5mfxuyzgqcgc049tqdq9dpjhjcqp23gxhs2rawqxdvr7f7lmj46tdvkncnsz8q5jp2kge8ndfm4dpevxrg5xj4ufp36x89gmaw04lgpap7e3x9jcjydwhcj9l84wmts2lg6qquvpque",
      "lnbc10n1pdm2qaxpp5zlyxcc5dypurzyjamt6kk6a8rpad7je5r4w8fj79u6fktnqu085sdpl2pshjmt9de6zqen0wgsrzgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp2e3nq4xh20prn9kx8etqgjjekzzjhep27mnqtyy62makh4gqc4akrzhe3nmj8lnwtd40ne5gn8myruvrt9p6vpuwmc4ghk7587erwqncpx9sds0",
      "lnbc800n1pwp5uuhpp5y8aarm9j9x9cer0gah9ymfkcqq4j4rn3zr7y9xhsgar5pmaceaqqdqdvf5hgcm0d9hzzcqp2vf8ramzsgdznxd5yxhrxffuk43pst9ng7cqcez9p2zvykpcf039rp9vutpe6wfds744yr73ztyps2z58nkmflye9yt4v3d0qz8z3d9qqq3kv54",
      "lnbc1500n1pdl686hpp5y7mz3lgvrfccqnk9es6trumjgqdpjwcecycpkdggnx7h6cuup90sdpa2fjkzep6ypqkymm4wssycnjzf9rjqurjda4x2cm5ypskuepqv93x7at5ypek7cqzysxqr23s5e864m06fcfp3axsefy276d77tzp0xzzzdfl6p46wvstkeqhu50khm9yxea2d9efp7lvthrta0ktmhsv52hf3tvxm0unsauhmfmp27cqqx4xxe",
      "lnbc80n1pwykw99pp5965lyj4uesussdrk0lfyd2qss9m23yjdjkpmhw0975zky2xlhdtsdpl2pshjmt9de6zqen0wgsrsgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp27677yc44l22jxexewew7lzka7g5864gdpr6y5v6s6tqmn8xztltk9qnna2qwrsm7gfyrpqvhaz4u3egcalpx2gxef3kvqwd44hekfxcqr7nwhf",
      "lnbc2200n1pwp4pwnpp5xy5f5kl83ytwuz0sgyypmqaqtjs68s3hrwgnnt445tqv7stu5kyqdpyvf5hgcm0d9hzqmn0wssxymr0vd4kx6rpd9hqcqp25y9w3wc3ztxhemsqch640g4u00szvvfk4vxr7klsakvn8cjcunjq8rwejzy6cfwj90ulycahnq43lff8m84xqf3tusslq2w69htwwlcpfqskmc",
      "lnbc300n1pwp50ggpp5x7x5a9zs26amr7rqngp2sjee2c3qc80ztvex00zxn7xkuzuhjkxqdq9dpjhjcqp2s464vnrpx7aynh26vsxx6s3m52x88dqen56pzxxnxmc9s7y5v0dprsdlv5q430zy33lcl5ll6uy60m7c9yrkjl8yxz7lgsqky3ka57qq4qeyz3",
      "lnbc10n1pd6jt93pp58vtzf4gup4vvqyknfakvh59avaek22hd0026snvpdnc846ypqrdsdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uq3sv9xkv2sgdf2nuvs97d2wkzj5g75rljnh5wy5wqhnauvqhxd9fpq898emtz8hul8cnxmc9wtj2777ehgnnyhcrs0y5zuhy8rs0jv6cqqe24tw",
      "lnbc890n1pwzu4uqpp5gy274lq0m5hzxuxy90vf65wchdszrazz9zxjdk30ed05kyjvwxrqdzq2pshjmt9de6zqen0wgsrswfqwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2qjvlfyl4rmc56gerx70lxcrjjlnrjfz677ezw4lwzy6syqh4rnlql6t6n3pdfxkcal9jp98plgf2zqzz8jxfza9vjw3vd4t62ws8gkgqhv9x28",
      "lnbc79760n1pd7cwyapp5gevl4mv968fs4le3tytzhr9r8tdk8cu3q7kfx348ut7xyntvnvmsdz92pskjepqw3hjqmrfva58gmnfdenjqumvda6zqmtpvd5xjmn9ypnx7u3qx5czq5msd9h8xcqzysxqrrssjzky68fdnhvee7aw089d5zltahfhy2ffa96pwf7fszjnm6mv0fzpv88jwaenm5qfg64pl768q8hf2vnvc5xsrpqd45nca2mewsv55wcpmhskah",
      "lnbc90n1pduns5qpp5f5h5ghga4cp7uj9de35ksk00a2ed9jf774zy7va37k5zet5cds8sdpl2pshjmt9de6zqen0wgsrjgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp28ynysm3clcq865y9umys8t2f54anlsu2wfpyfxgq09ht3qfez9x9z9fpff8wzqwzua2t9vayzm4ek3vf4k4s5cdg3a6hp9vsgg9klpgpmafvnv",
      "lnbc10u1pw9nehppp5tf0cpc3nx3wpk6j2n9teqwd8kuvryh69hv65w7p5u9cqhse3nmgsdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp222vxxwq70temepf6n0xlzk0asr43ppqrt0mf6eclnfd5mxf6uhv5wvsqgdvht6uqxfw2vgdku5gfyhgguepvnjfu7s4kuthtnuxy0hsq6wwv9d",
      "lnbc30n1pw9qjwmpp5tcdc9wcr0avr5q96jlez09eax7djwmc475d5cylezsd652zvptjsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdf4cqzysxqrrss7r8gn9d6klf2urzdjrq3x67a4u25wpeju5utusnc539aj5462y7kv9w56mndcx8jad7aa7qz8f8qpdw9qlx52feyemwd7afqxu45jxsqyzwns9",
      "lnbc10u1pw9x36xpp5tlk00k0dftfx9vh40mtdlu844c9v65ad0kslnrvyuzfxqhdur46qdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp2fpudmf4tt0crardf0k7vk5qs4mvys88el6e7pg62hgdt9t6ckf48l6jh4ckp87zpcnal6xnu33hxdd8k27vq2702688ww04kc065r7cqw3cqs3",
      "lnbc40n1pd6jttkpp5v8p97ezd3uz4ruw4w8w0gt4yr3ajtrmaeqe23ttxvpuh0cy79axqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uq3r88ajpz77z6lg4wc7srhsk7m26guuvhdlpea6889m9jnc9a25sx7rdtryjukew86mtcngl6d8zqh9trtu60cmmwfx6845q08z06p6qpl3l55t",
      "lnbc1pwr7fqhpp5vhur3ahtumqz5mkramxr22597gaa9rnrjch8gxwr9h7r56umsjpqdpl235hqurfdcs9xct5daeks6tngask6etnyq58g6tswp5kutndv55jsaf3x5unj2gcqzysxqyz5vq88jysqvrwhq6qe38jdulefx0z9j7sfw85wqc6athfx9h77fjnjxjvprz76ayna0rcjllgu5ka960rul3qxvsrr9zth5plaerq96ursgpsshuee",
      "lnbc10n1pw9rt5hpp5dsv5ux7xlmhmrpqnffgj6nf03mvx5zpns3578k2c5my3znnhz0gqdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwwp3cqzysxqrrssnrasvcr5ydng283zdfpw38qtqfxjnzhdmdx9wly9dsqmsxvksrkzkqrcenu6h36g4g55q56ejk429nm4zjfgssh8uhs7gs760z63ggcqp3gyd6",
      "lnbc1500n1pd7u7p4pp5d54vffcehkcy79gm0fkqrthh3y576jy9flzpy9rf6syua0s5p0jqdpa2fjkzep6ypxhjgz90pcx2unfv4hxxefqdanzqargv5s9xetrdahxggzvd9nkscqzysxqr23sklptztnk25aqzwty35gk9q7jtfzjywdfx23d8a37g2eaejrv3d9nnt87m98s4eps87q87pzfd6hkd077emjupe0pcazpt9kaphehufqqu7k37h",
      "lnbc10n1pdunsmgpp5wn90mffjvkd06pe84lpa6e370024wwv7xfw0tdxlt6qq8hc7d7rqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcngvscqzysxqyd9uqs0cqtrum6h7dct88nkjxwxvte7hjh9pusx64tp35u0m6qhqy5dgn9j27fs37mg0w3ruf7enxlsc9xmlasgjzyyaaxqdxu9x5w0md4fspgz8twv",
      "lnbc700n1pwp50wapp5w7eearwr7qjhz5vk5zq4g0t75f90mrekwnw4e795qfjxyaq27dxsdqvdp6kuar9wgeqcqp20gfw78vvasjm45l6zfxmfwn59ac9dukp36mf0y3gpquhp7rptddxy7d32ptmqukeghvamlkmve9n94sxmxglun4zwtkyhk43e6lw8qspc9y9ww",
      "lnbc10n1pd6jvy5pp50x9lymptter9najcdpgrcnqn34wq34f49vmnllc57ezyvtlg8ayqdpdtfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yq6rvcqzysxqyd9uqcejk56vfz3y80u3npefpx82f0tghua88a8x2d33gmxcjm45q6l5xwurwyp9aj2p59cr0lknpk0eujfdax32v4px4m22u6zr5z40zxvqp5m85cr",
      "lnbc10n1pw9pqz7pp50782e2u9s25gqacx7mvnuhg3xxwumum89dymdq3vlsrsmaeeqsxsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwd3ccqzysxqrrsstxqhw2kvdfwsf7c27aaae45fheq9rzndesu4mph9dq08sawa0auz7e0z7jn9qf3zphegv2ermup0fgce0phqmf73j4zx88v3ksrgeeqq9yzzad",
      "lnbc1300n1pwq4fx7pp5sqmq97yfxhhk7xv7u8cuc8jgv5drse45f5pmtx6f5ng2cqm332uqdq4e2279q9zux62tc5q5t9fgcqp29a662u3p2h4h4ucdav4xrlxz2rtwvvtward7htsrldpsc5erknkyxu0x2xt9qv0u766jadeetsz9pj4rljpjy0g8ayqqt2q8esewsrqpc8v4nw",
      "lnbc1u1pd7u7tnpp5s9he3ccpsmfdkzrsjns7p3wpz7veen6xxwxdca3khwqyh2ezk8kqdqdg9jxgg8sn7f27cqzysxqr23ssm4krdc4s0zqhfk97n0aclxsmaga208pa8c0hz3zyauqsjjxfj7kw6t29dkucp68s8s4zfdgp97kkmzgy25yuj0dcec85d9c50sgjqgq5jhl4e",
      "lnbc1200n1pwq5kf2pp5snkm9kr0slgzfc806k4c8q93d4y57q3lz745v2hefx952rhuymrqdq509shjgrzd96xxmmfdcsscqp2w5ta9uwzhmxxp0mnhwwvnjdn6ev4huj3tha5d80ajv2p5phe8wk32yn7ch6lennx4zzawqtd34aqetataxjmrz39gzjl256walhw03gpxz79rr",
      "lnbc1500n1pd7u7v0pp5s6d0wqexag3aqzugaw3gs7hw7a2wrq6l8fh9s42ndqu8zu480m0sdqvg9jxgg8zn2sscqzysxqr23sm23myatjdsp3003rlasgzwg3rlr0ca8uqdt5d79lxmdwqptufr89r5rgk4np4ag0kcw7at6s6eqdany0k6m0ezjva0cyda5arpaw7lcqgzjl7u",
      "lnbc100n1pd6jv8ypp53p6fdd954h3ffmyj6av4nzcnwfuyvn9rrsc2u6y22xnfs0l0cssqdpdtfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqerscqzysxqyd9uqyefde4la0qmglafzv8q34wqsf4mtwd8ausufavkp2e7paewd3mqsg0gsdmvrknw80t92cuvu9raevrnxtpsye0utklhpunsz68a9veqpkypx9j",
      "lnbc2300n1pwp50w8pp53030gw8rsqac6f3sqqa9exxwfvphsl4v4w484eynspwgv5v6vyrsdp9w35xjueqd9ejqmn0wssx67fqwpshxumhdaexgcqp2zmspcx992fvezxqkyf3rkcxc9dm2vr4ewfx42c0fccg4ea72fyd3pd6vn94tfy9t39y0hg0hupak2nv0n6pzy8culeceq8kzpwjy0tsp4fwqw5",
      "lnbc10n1pwykdlhpp53392ama65h3lnc4w55yqycp9v2ackexugl0ahz4jyc7fqtyuk85qdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwvejcqzysxqrrsszkwrx54an8lhr9h4h3d7lgpjrd370zucx0fdusaklqh2xgytr8hhgq5u0kvs56l8j53uktlmz3mqhhmn88kwwxfksnham9p6ws5pwxsqnpzyda",
      "lnbc10470n1pw9qf40pp535pels2faqwau2rmqkgzn0rgtsu9u6qaxe5y6ttgjx5qm4pg0kgsdzy2pshjmt9de6zqen0wgsrzvp5xus8q6tcv4k8xgrpwss8xct5daeks6tn9ecxcctrv5hqxqzjccqp27sp3m204a7d47at5jkkewa7rvewdmpwaqh2ss72cajafyf7dts9ne67hw9pps2ud69p4fw95y9cdk35aef43cv35s0zzj37qu7s395cp2vw5mu",
      "lnbc100n1pwytlgspp5365rx7ell807x5jsr7ykf2k7p5z77qvxjx8x6pfhh5298xnr6d2sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwvpscqzysxqrrssh9mphycg7e9lr58c267yerlcd9ka8lrljm8ygpnwu2v63jm7ax48y7qal25qy0ewpxw39r5whnqh93zw97gnnw64ss97n69975wh9gsqj7vudu",
      "lnbc210n1pdunsefpp5jxn3hlj86evlwgsz5d70hquy78k28ahdwjmlagx6qly9x29pu4uqdzq2pshjmt9de6zqen0wgsryvfqwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2snr8trjcrr5xyy7g63uq7mewqyp9k3d0duznw23zhynaz6pj3uwk48yffqn8p0jugv2z03dxquc8azuwr8myjgwzh69a34fl2lnmq2sppac733",
      "lnbc1700n1pwr7z98pp5j5r5q5c7syavjjz7czjvng4y95w0rd8zkl7q43sm7spg9ht2sjfqdquwf6kumnfdenjqmrfva58gmnfdenscqp2jrhlc758m734gw5td4gchcn9j5cp5p38zj3tcpvgkegxewat38d3h24kn0c2ac2pleuqp5dutvw5fmk4d2v3trcqhl5pdxqq8swnldcqtq0akh",
      "lnbc1500n1pdl05k5pp5nyd9netjpzn27slyj2np4slpmlz8dy69q7hygwm8ff4mey2jee5sdpa2fjkzep6ypxhjgz90pcx2unfv4hxxefqdanzqargv5s9xetrdahxggzvd9nkscqzysxqr23sqdd8t97qjc77pqa7jv7umc499jqkk0kwchapswj3xrukndr7g2nqna5x87n49uynty4pxexkt3fslyle7mwz708rs0rnnn44dnav9mgplf0aj7",
      "lnbc1u1pwyvxrppp5nvm98wnqdee838wtfmhfjx9s49eduzu3rx0fqec2wenadth8pxqsdqdg9jxgg8sn7vgycqzysxqr23snuza3t8x0tvusu07epal9rqxh4cq22m64amuzd6x607s0w55a5xpefp2xlxmej9r6nktmwv5td3849y2sg7pckwk9r8vqqps8g4u66qq85mp3g",
      "lnbc10n1pw9qjwppp55nx7xw3sytnfle67mh70dyukr4g4chyfmp4x4ag2hgjcts4kydnsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwd3ccqzysxqrrss7t24v6w7dwtd65g64qcz77clgye7n8l0j67qh32q4jrw9d2dk2444vma7j6nedgx2ywel3e9ns4r257zprsn7t5uca045xxudz9pqzsqfena6v",
      "lnbc10u1pw9x373pp549mpcznu3q0r4ml095kjg38pvsdptzja8vhpyvc2avatc2cegycsdzz2p6hycmgv9ek2gr0vcsrzgrxd3hhwetjyphkugzzd96xxmmfdcsywunpwejhjctjvscqp2tgqwhzyjmpfymrshnaw6rwmy4rgrtjmmp66dr9v54xp52rsyzqd5htc3lu3k52t06fqk8yj05nsw0nnssak3ywev4n3xs3jgz42urmspjeqyw0",
      "lnbc1500n1pd7u7vupp54jm8s8lmgnnru0ndwpxhm5qwllkrarasr9fy9zkunf49ct8mw9ssdqvg9jxgg8zn2sscqzysxqr23s4njradkzzaswlsgs0a6zc3cd28xc08t5car0k7su6q3u3vjvqt6xq2kpaadgt5x9suxx50rkevfw563fupzqzpc9m6dqsjcr8qt6k2sqelr838",
      "lnbc720n1pwypj4epp5k2saqsjznpvevsm9mzqfan3d9fz967x5lp39g3nwsxdkusps73csdzq2pshjmt9de6zqen0wgsrwv3qwp5hsetvwvsxzapqwdshgmmndp5hxtnsd3skxefwxqzjccqp2d3ltxtq0r795emmp7yqjjmmzl55cgju004vw08f83e98d28xmw44t4styhfhgsrwxydf68m2kup7j358zdrmhevqwr0hlqwt2eceaxcq7hezhx",
      "lnbc10n1pwykdacpp5kegv2kdkxmetm2tpnzfgt4640n7mgxl95jnpc6fkz6uyjdwahw8sdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdp5cqzysxqrrssjlny2skwtnnese9nmw99xlh7jwgtdxurhce2zcwsamevmj37kd5yzxzu55mt567seewmajra2hwyry5cv9kfzf02paerhs7tf9acdcgq24pqer",
      "lnbc3100n1pwp370spp5ku7y6tfz5up840v00vgc2vmmqtpsu5ly98h09vxv9d7k9xtq8mrsdpjd35kw6r5de5kueevypkxjemgw3hxjmn89ssxc6t8dp6xu6twvucqp2sunrt8slx2wmvjzdv3vvlls9gez7g2gd37g2pwa4pnlswuxzy0w3hd5kkqdrpl4ylcdhvkvuamwjsfh79nkn52dq0qpzj8c4rf57jmgqschvrr",
      "lnbc1500n1pwr7z8rpp5hyfkmnwwx7x902ys52du8pph6hdkarnqvj6fwhh9swfsg5lp94vsdpa2fjkzep6ypph2um5dajxjctvypmkzmrvv468xgrpwfjjqetkd9kzqctwvss8ycqzysxqr23s64a2h7gn25pchh8r6jpe236h925fylw2jcm4pd92w8hkmpflreph8r6s8jnnml0zu47qv6t2sj6frnle2cpanf6e027vsddgkl8hk7gpta89d0",
      "lnbc1500n1pdl05v0pp5c4t5p3renelctlh0z4jpznyxna7lw9zhws868wktp8vtn8t5a8uqdpa2fjkzep6ypxxjemgw35kueeqfejhgam0wf4jqnrfw96kjerfw3ujq5r0dakq6cqzysxqr23s7k3ktaae69gpl2tfleyy2rsm0m6cy5yvf8uq7g4dmpyrwvfxzslnvryx5me4xh0fsp9jfjsqkuwpzx9ydwe6ndrm0eznarhdrfwn5gsp949n7x",
      "lnbc1500n1pwyvxp3pp5ch8jx4g0ft0f6tzg008vr82wv92sredy07v46h7q3h3athx2nm2sdpa2fjkzep6ypyx7aeqfys8w6tndqsx67fqw35x2gzvv4jxwetjypvzqam0w4kxgcqzysxqr23s3hdgx90a6jcqgl84z36dv6kn6eg4klsaje2kdm84662rq7lzzzlycvne4l8d0steq5pctdp4ffeyhylgrt7ln92l8dyvrnsn9qg5qkgqrz2cra",
      "lnbc1500n1pwr7z2ppp5cuzt0txjkkmpz6sgefdjjmdrsj9gl8fqyeu6hx7lj050f68yuceqdqvg9jxgg8zn2sscqzysxqr23s7442lgk6cj95qygw2hly9qw9zchhag5p5m3gyzrmws8namcsqh5nz2nm6a5sc2ln6jx59sln9a7t8vxtezels2exurr0gchz9gk0ufgpwczm3r",
      "lnbc1500n1pd7u7g4pp5eam7uhxc0w4epnuflgkl62m64qu378nnhkg3vahkm7dhdcqnzl4sdqvg9jxgg8zn2sscqzysxqr23s870l2549nhsr2dfv9ehkl5z95p5rxpks5j2etr35e02z9r6haalrfjs7sz5y7wzenywp8t52w89c9u8taf9m76t2p0w0vxw243y7l4spqdue7w",
      "lnbc5u1pwq2jqzpp56zhpjmfm72e8p8vmfssspe07u7zmnm5hhgynafe4y4lwz6ypusvqdzsd35kw6r5de5kuemwv468wmmjddehgmmjv4ejucm0d40n2vpsta6hqan0w3jhxhmnw3hhye2fgs7nywfhcqp2tqnqpewrz28yrvvvyzjyrvwahuy595t4w4ar3cvt5cq9jx3rmxd4p7vjgmeylfkgjrssc66a9q9hhnd4aj7gqv2zj0jr2zt0gahnv0sp9y675y",
      "lnbc10n1pw9pqp3pp562wg5n7atx369mt75feu233cnm5h508mx7j0d807lqe0w45gndnqdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrsszfg9lfawdhnp2m785cqgzg4c85mvgct44xdzjea9t0vu4mc22u4prjjz5qd4y7uhgg3wm57muh5wfz8l04kgyq8juwql3vaffm23akspzkmj53",
      "lnbc90n1pwypjnppp5m870lhg8qjrykj6hfegawaq0ukzc099ntfezhm8jr48cw5ywgpwqdpl2pshjmt9de6zqen0wgsrjgrsd9ux2mrnypshggrnv96x7umgd9ejuurvv93k2tsxqzjccqp2s0n2u7msmypy9dh96e6exfas434td6a7f5qy5shzyk4r9dxwv0zhyxcqjkmxgnnkjvqhthadhkqvvd66f8gxkdna3jqyzhnnhfs6w3qpme2zfz",
      "lnbc100n1pdunsurpp5af2vzgyjtj2q48dxl8hpfv9cskwk7q5ahefzyy3zft6jyrc4uv2qdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnyvccqzysxqyd9uqpcp608auvkcr22672nhwqqtul0q6dqrxryfsstttlwyvkzttxt29mxyshley6u45gf0sxc0d9dxr5fk48tj4z2z0wh6asfxhlsea57qp45tfua",
      "lnbc100n1pd6hzfgpp5au2d4u2f2gm9wyz34e9rls66q77cmtlw3tzu8h67gcdcvj0dsjdqdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnqvscqzysxqyd9uqxg5n7462ykgs8a23l3s029dun9374xza88nlf2e34nupmc042lgps7tpwd0ue0he0gdcpfmc5mshmxkgw0hfztyg4j463ux28nh2gagqage30p",
      "lnbc50n1pdl052epp57549dnjwf2wqfz5hg8khu0wlkca8ggv72f9q7x76p0a7azkn3ljsdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcnvvscqzysxqyd9uqa2z48kchpmnyafgq2qlt4pruwyjh93emh8cd5wczwy47pkx6qzarmvl28hrnqf98m2rnfa0gx4lnw2jvhlg9l4265240av6t9vdqpzsqntwwyx",
      "lnbc100n1pd7cwrypp57m4rft00sh6za2x0jwe7cqknj568k9xajtpnspql8dd38xmd7musdp0tfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqcngvscqzysxqyd9uqsxfmfv96q0d7r3qjymwsem02t5jhtq58a30q8lu5dy3jft7wahdq2f5vc5qqymgrrdyshff26ak7m7n0vqyf7t694vam4dcqkvnr65qp6wdch9",
      "lnbc100n1pw9qjdgpp5lmycszp7pzce0rl29s40fhkg02v7vgrxaznr6ys5cawg437h80nsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrss47kl34flydtmu2wnszuddrd0nwa6rnu4d339jfzje6hzk6an0uax3kteee2lgx5r0629wehjeseksz0uuakzwy47lmvy2g7hja7mnpsqjmdct9",
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q5sqqqqqqqqqqqqqqqpqsq67gye39hfg3zd8rgc80k32tvy9xk2xunwm5lzexnvpx6fd77en8qaq424dxgt56cag2dpt359k3ssyhetktkpqh24jqnjyw6uqd08sgptq44qu",
      "lnbc25m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5vdhkven9v5sxyetpdeessp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q4psqqqqqqqqqqqqqqqpqsqq40wa3khl49yue3zsgm26jrepqr2eghqlx86rttutve3ugd05em86nsefzh4pfurpd9ek9w2vp95zxqnfe2u7ckudyahsa52q66tgzcp6t2dyk"
    )

    for (req <- requests) {
      assert(PaymentRequest.write(PaymentRequest.read(req)) == req)
    }
  }
}
