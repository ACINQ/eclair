package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRouteDetails
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.{RouteBlindingDecryptedData, blindedRouteDataCodec}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, FeatureSupport, Features, MilliSatoshiLong, ShortChannelId, UInt64, UnknownFeature, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

class RouteBlindingSpec extends AnyFunSuiteLike {

  test("decode route blinding data (reference test vector)") {
    val payloads = Map[ByteVector, TlvStream[RouteBlindingEncryptedDataTlv]](
      // Payment reference test vector: see https://github.com/lightning/bolts/blob/master/bolt04/route-blinding-test.json
      hex"011a0000000000000000000000000000000000000000000000000000 020800000000000006c1 0a080024000000962710 0c06000b69e505dc 0e00 fd023103123456" -> TlvStream(Set[RouteBlindingEncryptedDataTlv](Padding(hex"0000000000000000000000000000000000000000000000000000"), OutgoingChannelId(ShortChannelId(1729)), PaymentRelay(CltvExpiryDelta(36), 150, 10000 msat), PaymentConstraints(CltvExpiry(748005), 1500 msat), AllowedFeatures(Features.empty)), Set(GenericTlv(UInt64(561), hex"123456"))),
      hex"02080000000000000451 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 0a0800300000006401f4 0c06000b69c105dc 0e00" -> TlvStream(OutgoingChannelId(ShortChannelId(1105)), NextBlinding(PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")), PaymentRelay(CltvExpiryDelta(48), 100, 500 msat), PaymentConstraints(CltvExpiry(747969), 1500 msat), AllowedFeatures(Features.empty)),
      hex"01230000000000000000000000000000000000000000000000000000000000000000000000 02080000000000000231 0a060090000000fa 0c06000b699105dc 0e00" -> TlvStream(Padding(hex"0000000000000000000000000000000000000000000000000000000000000000000000"), OutgoingChannelId(ShortChannelId(561)), PaymentRelay(CltvExpiryDelta(144), 250, 0 msat), PaymentConstraints(CltvExpiry(747921), 1500 msat), AllowedFeatures(Features.empty)),
      hex"011a0000000000000000000000000000000000000000000000000000 0604deadbeef 0c06000b690105dc 0e0f020000000000000000000000000000 fdffff0206c1" -> TlvStream(Set[RouteBlindingEncryptedDataTlv](Padding(hex"0000000000000000000000000000000000000000000000000000"), PathId(hex"deadbeef"), PaymentConstraints(CltvExpiry(747777), 1500 msat), AllowedFeatures(Features(Map.empty[Feature, FeatureSupport], Set(UnknownFeature(113))))), Set(GenericTlv(UInt64(65535), hex"06c1"))),
      // Onion message reference test vector.
      hex"01080000000000000000 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145" -> TlvStream(Padding(hex"0000000000000000"), OutgoingNodeId(PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"))),
      hex"0109000000000000000000 06204242424242424242424242424242424242424242424242424242424242424242" -> TlvStream(Padding(hex"000000000000000000"), PathId(hex"4242424242424242424242424242424242424242424242424242424242424242")),
      hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991" -> TlvStream(OutgoingNodeId(PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"))),
      hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145" -> TlvStream(OutgoingNodeId(PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"))),
      hex"010f000000000000000000000000000000 061000112233445566778899aabbccddeeff" -> TlvStream(Padding(hex"000000000000000000000000000000"), PathId(hex"00112233445566778899aabbccddeeff")),
      hex"0121000000000000000000000000000000000000000000000000000000000000000000 04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c" -> TlvStream(Padding(hex"000000000000000000000000000000000000000000000000000000000000000000"), OutgoingNodeId(PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c"))),
      hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f" -> TlvStream(OutgoingNodeId(PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")), NextBlinding(PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"))),
    )

    for ((encoded, data) <- payloads) {
      val decoded = blindedRouteDataCodec.decode(encoded.bits).require.value
      assert(decoded == data)
      val reEncoded = blindedRouteDataCodec.encode(data).require.bytes
      assert(reEncoded == encoded)
    }
  }

  test("decode payment onion route blinding data (reference test vector)") {
    // See https://github.com/lightning/bolts/blob/master/bolt04/blinded-payment-onion-test.json
    val payloads = Map[ByteVector, TlvStream[RouteBlindingEncryptedDataTlv]](
      hex"01200000000000000000000000000000000000000000000000000000000000000000 02080000000000000001 0a080032000000002710 0c05000b724632 0e00" -> TlvStream(Padding(hex"0000000000000000000000000000000000000000000000000000000000000000"), OutgoingChannelId(ShortChannelId(1)), PaymentRelay(CltvExpiryDelta(50), 0, 10000 msat), PaymentConstraints(CltvExpiry(750150), 50 msat), AllowedFeatures(Features.empty)),
      hex"02080000000000000002 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 0a07004b0000009664 0c05000b721432 0e00" -> TlvStream(OutgoingChannelId(ShortChannelId(2)), NextBlinding(PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")), PaymentRelay(CltvExpiryDelta(75), 150, 100 msat), PaymentConstraints(CltvExpiry(750100), 50 msat), AllowedFeatures(Features.empty)),
      hex"012200000000000000000000000000000000000000000000000000000000000000000000 02080000000000000003 0a06001900000064 0c05000b71c932 0e00" -> TlvStream(Padding(hex"00000000000000000000000000000000000000000000000000000000000000000000"), OutgoingChannelId(ShortChannelId(3)), PaymentRelay(CltvExpiryDelta(25), 100, 0 msat), PaymentConstraints(CltvExpiry(750025), 50 msat), AllowedFeatures(Features.empty)),
      hex"011c00000000000000000000000000000000000000000000000000000000 0616c9cf92f45ade68345bc20ae672e2012f4af487ed4415 0c05000b71b032 0e00" -> TlvStream(Padding(hex"00000000000000000000000000000000000000000000000000000000"), PathId(hex"c9cf92f45ade68345bc20ae672e2012f4af487ed4415"), PaymentConstraints(CltvExpiry(750000), 50 msat), AllowedFeatures(Features.empty)),
    )

    for ((encoded, data) <- payloads) {
      val decoded = blindedRouteDataCodec.decode(encoded.bits).require.value
      assert(decoded == data)
      val reEncoded = blindedRouteDataCodec.encode(data).require.bytes
      assert(reEncoded == encoded)
    }
  }

  test("reject non-empty allowed features for intermediate nodes") {
    {
      val encoded = hex"02080000000000000231 0a060090000000fa 0c06000b699105dc 0e0101"
      val decoded = blindedRouteDataCodec.decode(encoded.bits).require.value
      assert(BlindedRouteData.validatePaymentRelayData(decoded) == Left(ForbiddenTlv(UInt64(14))))
    }
    {
      val encoded = hex"01020000 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 0e020100"
      val decoded = blindedRouteDataCodec.decode(encoded.bits).require.value
      assert(BlindedRouteData.validateMessageRelayData(decoded) == Left(ForbiddenTlv(UInt64(14))))
    }
  }

  test("decode encrypted route blinding data") {
    val sessionKey = randomKey()
    val nodePrivKeys = Seq(randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
    val payloads = Seq[(TlvStream[RouteBlindingEncryptedDataTlv], ByteVector)](
      (TlvStream(Padding(hex"000000"), OutgoingChannelId(ShortChannelId(561)), PaymentRelay(CltvExpiryDelta(222), 300, 1000 msat), PaymentConstraints(CltvExpiry(734582), 60000074 msat), AllowedFeatures(Features.empty)), hex"0103000000 02080000000000000231 0a0800de0000012c03e8 0c08000b35760393874a 0e00"),
      (TlvStream(OutgoingNodeId(PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"))), hex"0421025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"),
      (TlvStream(OutgoingNodeId(PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")), AllowedFeatures(Features(Features.VariableLengthOnion -> FeatureSupport.Mandatory))), hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145 0e020100"),
      (TlvStream(PathId(hex"0101010101010101010101010101010101010101010101010101010101010101")), hex"06200101010101010101010101010101010101010101010101010101010101010101"),
      (TlvStream(Set[RouteBlindingEncryptedDataTlv](OutgoingChannelId(ShortChannelId(42)), PaymentRelay(CltvExpiryDelta(123), 200, 900 msat), PaymentConstraints(CltvExpiry(734576), 756001234 msat), AllowedFeatures(Features.empty)), Set(GenericTlv(UInt64(65535), hex"06c1"))), hex"0208000000000000002a 0a08007b000000c80384 0c08000b35702d0fa9d2 0e00 fdffff0206c1"),
    )

    val BlindedRouteDetails(blindedRoute, lastBlinding) = Sphinx.RouteBlinding.create(sessionKey, nodePrivKeys.map(_.publicKey), payloads.map(_._2))
    val blinding0 = sessionKey.publicKey
    val Right(RouteBlindingDecryptedData(decryptedPayload0, blinding1)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(0), blinding0, blindedRoute.encryptedPayloads(0))
    val Right(RouteBlindingDecryptedData(decryptedPayload1, blinding2)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(1), blinding1, blindedRoute.encryptedPayloads(1))
    val Right(RouteBlindingDecryptedData(decryptedPayload2, blinding3)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(2), blinding2, blindedRoute.encryptedPayloads(2))
    val Right(RouteBlindingDecryptedData(decryptedPayload3, blinding4)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(3), blinding3, blindedRoute.encryptedPayloads(3))
    val Right(RouteBlindingDecryptedData(decryptedPayload4, _)) = RouteBlindingEncryptedDataCodecs.decode(nodePrivKeys(4), blinding4, blindedRoute.encryptedPayloads(4))
    assert(Seq(decryptedPayload0, decryptedPayload1, decryptedPayload2, decryptedPayload3, decryptedPayload4) == payloads.map(_._1))
    assert(lastBlinding == blinding4)
  }

  test("decode invalid encrypted route blinding tlv stream") {
    val testCases = Seq(
      hex"02080000000000000231 0a0a00000000000003e8002a 0c08000b35702d0fa9d2 ff", // additional trailing bytes after tlv stream
      hex"01040000 02080000000000000231 0a0a00000000000003e8002a 0c08000b35702d0fa9d2", // invalid padding tlv
      hex"02080000000000000231 0a0a00000000000003e8002a 0820025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce14 0c08000b35702d0fa9d2", // invalid next blinding length
      hex"0a0a00000000000003e8002a 02080000000000000231 0103000000 0c08000b35702d0fa9d2", // invalid tlv stream ordering
      hex"02080000000000000231 0a0a00000000000003e8002a 0c08000b35702d0fa9d2 10080000000000000231", // unknown even tlv field
    )

    for (testCase <- testCases) {
      assert(blindedRouteDataCodec.decode(testCase.bits).isFailure)
    }
  }

  test("decode invalid encrypted route blinding data for payment relay") {
    val validPayload = hex"02080000000000000231 0a0a00000000000003e8002a 0c08000b35702d0fa9d2"
    assert(BlindedRouteData.validatePaymentRelayData(blindedRouteDataCodec.decode(validPayload.bits).require.value).isRight)

    val testCases = Seq(
      hex"0a0a00000000000003e8002a 0c08000b35702d0fa9d2" -> MissingRequiredTlv(UInt64(2)), // missing channel id
      hex"02080000000000000231 0c08000b35702d0fa9d2" -> MissingRequiredTlv(UInt64(10)), // missing payment relay data
      hex"02080000000000000231 0a0a00000000000003e8002a" -> MissingRequiredTlv(UInt64(12)), // missing payment constraints data
      hex"02080000000000000231 0603010203 0a0a00000000000003e8002a 0c08000b35702d0fa9d2" -> ForbiddenTlv(UInt64(6)), // forbidden path id
    )

    for ((bin, expected) <- testCases) {
      val decoded = blindedRouteDataCodec.decode(bin.bits).require.value
      assert(BlindedRouteData.validatePaymentRelayData(decoded) == Left(expected))
    }
  }

  test("decode invalid encrypted route blinding data for payment recipient") {
    val validPayload = hex"0603010203 0c08000b35702d0fa9d2"
    assert(BlindedRouteData.validPaymentRecipientData(blindedRouteDataCodec.decode(validPayload.bits).require.value).isRight)

    val testCases = Seq(
      hex"0c08000b35702d0fa9d2" -> MissingRequiredTlv(UInt64(6)), // missing path id
      hex"0603010203" -> MissingRequiredTlv(UInt64(12)), // missing payment constraints
    )

    for ((bin, expected) <- testCases) {
      val decoded = blindedRouteDataCodec.decode(bin.bits).require.value
      assert(BlindedRouteData.validPaymentRecipientData(decoded) == Left(expected))
    }
  }

  test("decode invalid encrypted route blinding data for message relay") {
    val validPayload = hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"
    assert(BlindedRouteData.validateMessageRelayData(blindedRouteDataCodec.decode(validPayload.bits).require.value).isRight)

    val testCases = Seq(
      hex"" -> MissingRequiredTlv(UInt64(4)), // missing node id
      hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 060100" -> ForbiddenTlv(UInt64(6)), // forbidden path id
      hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 0a0a00000000000003e8002a" -> ForbiddenTlv(UInt64(10)), // forbidden payment relay data
      hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 0c08000b35702d0fa9d2" -> ForbiddenTlv(UInt64(12)), // forbidden payment constraints
    )

    for ((bin, expected) <- testCases) {
      val decoded = blindedRouteDataCodec.decode(bin.bits).require.value
      assert(BlindedRouteData.validateMessageRelayData(decoded) == Left(expected))
    }
  }

  test("decode invalid encrypted route blinding data for message recipient") {
    val validPayload = hex"0604deadbeef"
    assert(BlindedRouteData.validateMessageRecipientData(blindedRouteDataCodec.decode(validPayload.bits).require.value).isRight)

    val testCases = Seq(
      hex"0604deadbeef 0a0a00000000000003e8002a" -> ForbiddenTlv(UInt64(10)), // forbidden payment relay data
      hex"0604deadbeef 0c08000b35702d0fa9d2" -> ForbiddenTlv(UInt64(12)), // forbidden payment constraints
    )

    for ((bin, expected) <- testCases) {
      val decoded = blindedRouteDataCodec.decode(bin.bits).require.value
      assert(BlindedRouteData.validateMessageRecipientData(decoded) == Left(expected))
    }
  }

}
