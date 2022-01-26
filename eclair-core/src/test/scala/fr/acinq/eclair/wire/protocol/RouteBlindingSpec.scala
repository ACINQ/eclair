package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.BlindedRouteData.{FinalRecipientData, MessageRelayData, PaymentRelayData}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.{finalRecipientDataCodec, messageRelayDataCodec, paymentRelayDataCodec}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshiLong, ShortChannelId, UInt64, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.Codec
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.util.Success

class RouteBlindingSpec extends AnyFunSuiteLike {

  test("decode route blinding data (reference test vector)") {
    val payloads = Map[ByteVector, BlindedRouteData.Data](
      hex"0208000000000000002a 0a0a000003e80000012c00de 3903123456" -> PaymentRelayData(TlvStream(Seq(OutgoingChannelId(ShortChannelId(42)), PaymentRelay(1000 msat, 300, CltvExpiryDelta(222))), Seq(GenericTlv(UInt64(57), hex"123456")))),
      hex"011900000000000000000000000000000000000000000000000000 02080000000000000231 0a0a00000384000000c8007b 3b00 fdffff0206c1" -> PaymentRelayData(TlvStream(Seq(Padding(hex"00000000000000000000000000000000000000000000000000"), OutgoingChannelId(ShortChannelId(561)), PaymentRelay(900 msat, 200, CltvExpiryDelta(123))), Seq(GenericTlv(UInt64(59), hex""), GenericTlv(UInt64(65535), hex"06c1")))),
      hex"02080000000000000451 0a0a00000000000003e8002a 0c0e000c0a8300000000000027100000" -> PaymentRelayData(TlvStream(OutgoingChannelId(ShortChannelId(1105)), PaymentRelay(0 msat, 1000, CltvExpiryDelta(42)), PaymentConstraints(CltvExpiry(789123), 10000 msat, Features.empty))),
      hex"01080000000000000000 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145" -> MessageRelayData(TlvStream(Padding(hex"0000000000000000"), OutgoingNodeId(PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")))),
      hex"0109000000000000000000 06204242424242424242424242424242424242424242424242424242424242424242" -> FinalRecipientData(TlvStream(Padding(hex"000000000000000000"), PathId(hex"4242424242424242424242424242424242424242424242424242424242424242"))),
      hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991" -> MessageRelayData(TlvStream(OutgoingNodeId(PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")))),
      hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145" -> MessageRelayData(TlvStream(OutgoingNodeId(PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")))),
      hex"010f000000000000000000000000000000 061000112233445566778899aabbccddeeff" -> FinalRecipientData(TlvStream(Padding(hex"000000000000000000000000000000"), PathId(hex"00112233445566778899aabbccddeeff"))),
      hex"0121000000000000000000000000000000000000000000000000000000000000000000 04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c" -> MessageRelayData(TlvStream(Padding(hex"000000000000000000000000000000000000000000000000000000000000000000"), OutgoingNodeId(PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")))),
      hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" -> MessageRelayData(TlvStream(OutgoingNodeId(PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")), NextBlinding(PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")))),
    )

    def testEncoding[T](encoded: ByteVector, data: T, codec: Codec[T]) = {
      val decoded = codec.decode(encoded.bits).require.value
      assert(decoded === data)
      val reEncoded = codec.encode(data).require.bytes
      assert(reEncoded === encoded)
    }

    for ((encoded, data) <- payloads) {
      data match {
        case data: MessageRelayData => testEncoding(encoded, data, messageRelayDataCodec)
        case data: PaymentRelayData => testEncoding(encoded, data, paymentRelayDataCodec)
        case data: FinalRecipientData => testEncoding(encoded, data, finalRecipientDataCodec)
      }
    }
  }

  test("decode encrypted route blinding data") {
    val sessionKey = randomKey()
    val nodePrivKeys = Seq(randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
    val payloads = Seq[(BlindedRouteData.Data, ByteVector)](
      (PaymentRelayData(TlvStream(Padding(hex"000000"), OutgoingChannelId(ShortChannelId(561)), PaymentRelay(1000 msat, 300, CltvExpiryDelta(222)))), hex"0103000000 02080000000000000231 0a0a000003e80000012c00de"),
      (MessageRelayData(TlvStream(OutgoingNodeId(PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")))), hex"0421025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"),
      (MessageRelayData(TlvStream(OutgoingNodeId(PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")), NextBlinding(PublicKey(hex"027710df7a1d7ad02e3572841a829d141d9f56b17de9ea124d2f83ea687b2e0461")))), hex"0421025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486 0821027710df7a1d7ad02e3572841a829d141d9f56b17de9ea124d2f83ea687b2e0461"),
      (FinalRecipientData(TlvStream(PathId(hex"0101010101010101010101010101010101010101010101010101010101010101"))), hex"06200101010101010101010101010101010101010101010101010101010101010101"),
      (PaymentRelayData(TlvStream(Seq(OutgoingChannelId(ShortChannelId(42)), PaymentRelay(900 msat, 200, CltvExpiryDelta(123))), Seq(GenericTlv(UInt64(65535), hex"06c1")))), hex"0208000000000000002a 0a0a00000384000000c8007b fdffff0206c1"),
    )

    val blindedRoute = Sphinx.RouteBlinding.create(sessionKey, nodePrivKeys.map(_.publicKey), payloads.map(_._2))
    val blinding0 = sessionKey.publicKey
    val Success((decryptedPayload0, blinding1)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(0), blinding0, blindedRoute.encryptedPayloads(0), paymentRelayDataCodec)
    val Success((decryptedPayload1, blinding2)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(1), blinding1, blindedRoute.encryptedPayloads(1), messageRelayDataCodec)
    val Success((decryptedPayload2, blinding3)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(2), blinding2, blindedRoute.encryptedPayloads(2), messageRelayDataCodec)
    val Success((decryptedPayload3, blinding4)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(3), blinding3, blindedRoute.encryptedPayloads(3), finalRecipientDataCodec)
    val Success((decryptedPayload4, _)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(4), blinding4, blindedRoute.encryptedPayloads(4), paymentRelayDataCodec)
    assert(Seq(decryptedPayload0, decryptedPayload1, decryptedPayload2, decryptedPayload3, decryptedPayload4) === payloads.map(_._1))
  }

  test("decode invalid encrypted route blinding data for payments") {
    val testCases = Seq(
      hex"0a0a00000000000003e8002a", // missing channel id
      hex"02080000000000000231", // missing payment relay data
      hex"02080000000000000231 0a0a00000000000003e8002a ff", // additional trailing bytes after tlv stream
      hex"01040000 02080000000000000231 0a0a00000000000003e8002a", // invalid padding tlv
      hex"02080000000000000231 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 0a0a00000000000003e8002a", // next node id is not valid for payments
      hex"02080000000000000231 0a0a00000000000003e8002a 0c20025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce14", // invalid next blinding length
      hex"0a0a00000000000003e8002a 02080000000000000231 0103000000", // invalid tlv stream ordering
      hex"02080000000000000231 0a0a00000000000003e8002a 10080000000000000231", // unknown even tlv field
    )

    for (testCase <- testCases) {
      val nodePrivKeys = Seq(randomKey(), randomKey())
      val payloads = Seq(hex"02080000000000000231 0a0a00000000000003e8002a", testCase)
      val blindingPrivKey = randomKey()
      val blindedRoute = Sphinx.RouteBlinding.create(blindingPrivKey, nodePrivKeys.map(_.publicKey), payloads)
      // The payload for the first node is valid.
      val blinding0 = blindingPrivKey.publicKey
      val Success((_, blinding1)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.head, blinding0, blindedRoute.encryptedPayloads.head, paymentRelayDataCodec)
      // If the first node is given invalid decryption material, it cannot decrypt recipient data.
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.last, blinding0, blindedRoute.encryptedPayloads.head, paymentRelayDataCodec).isFailure)
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.head, blinding1, blindedRoute.encryptedPayloads.head, paymentRelayDataCodec).isFailure)
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.head, blinding0, blindedRoute.encryptedPayloads.last, paymentRelayDataCodec).isFailure)
      // The payload for the last node is invalid, even with valid decryption material.
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.last, blinding1, blindedRoute.encryptedPayloads.last, paymentRelayDataCodec).isFailure)
    }
  }

  test("decode invalid encrypted route blinding data for messages") {
    val testCases = Seq(
      hex"", // missing next node id
      hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 ff", // additional trailing bytes after tlv stream
      hex"01040000 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145", // invalid padding tlv
      hex"02080000000000000231 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145", // channel id is not valid for payments
      hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 0c20025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce14", // invalid next blinding length
      hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 0103000000", // invalid tlv stream ordering
      hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 10080000000000000231", // unknown even tlv field
    )

    for (testCase <- testCases) {
      val nodePrivKeys = Seq(randomKey(), randomKey())
      val payloads = Seq(hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145", testCase)
      val blindingPrivKey = randomKey()
      val blindedRoute = Sphinx.RouteBlinding.create(blindingPrivKey, nodePrivKeys.map(_.publicKey), payloads)
      // The payload for the first node is valid.
      val blinding0 = blindingPrivKey.publicKey
      val Success((_, blinding1)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.head, blinding0, blindedRoute.encryptedPayloads.head, messageRelayDataCodec)
      // If the first node is given invalid decryption material, it cannot decrypt recipient data.
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.last, blinding0, blindedRoute.encryptedPayloads.head, messageRelayDataCodec).isFailure)
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.head, blinding1, blindedRoute.encryptedPayloads.head, messageRelayDataCodec).isFailure)
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.head, blinding0, blindedRoute.encryptedPayloads.last, messageRelayDataCodec).isFailure)
      // The payload for the last node is invalid, even with valid decryption material.
      assert(RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys.last, blinding1, blindedRoute.encryptedPayloads.last, messageRelayDataCodec).isFailure)
    }
  }
}
