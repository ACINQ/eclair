/*
 * Copyright 2018 ACINQ SAS
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

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Btc, Crypto, MilliBtc, MilliSatoshi, Protocol, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.payment.PaymentRequest._
import org.scalatest.FunSuite
import scodec.DecodeResult
import scodec.bits.BitVector

/**
  * Created by fabrice on 15/05/17.
  */

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

  test("data string -> bitvector") {
    import scodec.bits._
    assert(string2Bits("p") === bin"00001")
    assert(string2Bits("pz") === bin"0000100010")
  }

  test("minimal length long") {
    import scodec.bits._
    assert(long2bits(0) == bin"")
    assert(long2bits(1) == bin"1")
    assert(long2bits(42) == bin"101010")
    assert(long2bits(255) == bin"11111111")
    assert(long2bits(256) == bin"100000000")
    assert(long2bits(3600) == bin"111000010000")
  }

  test("verify that padding is zero") {
    import scodec.bits._
    import scodec.codecs._
    val codec = PaymentRequest.Codecs.alignedBytesCodec(bits)

    assert(codec.decode(bin"1010101000").require == DecodeResult(bin"10101010", BitVector.empty))
    assert(codec.decode(bin"1010101001").isFailure) // non-zero padding

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
    val pr = PaymentRequest(
      prefix = "lntb",
      amount = Some(MilliSatoshi(100000L)),
      timestamp = System.currentTimeMillis() / 1000L,
      nodeId = nodeId,
      tags = List(
        PaymentHash(BinaryData("01" * 32)),
        Description("description"),
        UnknownTag21(BitVector("some data we don't understand".getBytes))
      ),
      signature = BinaryData.empty).sign(priv)

    val serialized = PaymentRequest write pr
    val pr1 = PaymentRequest read serialized
    val Some(unknownTag) = pr1.tags.collectFirst { case u: UnknownTag21 => u }
  }

  test("accept uppercase payment request") {
    val input = "lntb1500n1pwxx94fpp5q3xzmwuvxpkyhz6pvg3fcfxz0259kgh367qazj62af9rs0pw07dsdpa2fjkzep6yp58garswvaz7tmvd9nksarwd9hxw6n0w4kx2tnrdakj7grfwvs8wcqzysxqr23sjzv0d8794te26xhexuc26eswf9sjpv4t8sma2d9y8dmpgf0qseg8259my8tcs6zte7ex0tz4exm5pjezuxrq9u0vjewa02qhedk9x4gppweupu"

    assert(PaymentRequest.write(PaymentRequest.read(input.toUpperCase())) == input)
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
      "lnbc100n1pw9qjdgpp5lmycszp7pzce0rl29s40fhkg02v7vgrxaznr6ys5cawg437h80nsdpstfshq5n9v9jzucm0d5s8vmm5v5s8qmmnwssyj3p6yqenwdejcqzysxqrrss47kl34flydtmu2wnszuddrd0nwa6rnu4d339jfzje6hzk6an0uax3kteee2lgx5r0629wehjeseksz0uuakzwy47lmvy2g7hja7mnpsqjmdct9"
    )

    for (req <- requests) { assert(PaymentRequest.write(PaymentRequest.read(req)) == req) }
  }
}
