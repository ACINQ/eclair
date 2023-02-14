package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs._
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv._
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv.{OutgoingNodeId, PathId, PaymentConstraints, PaymentRelay}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, UInt64, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

class MessageOnionCodecsSpec extends AnyFunSuiteLike {

  test("encode/decode relay per-hop payload") {
    val testCases = Map(
      TlvStream[OnionMessagePayloadTlv](EncryptedData(hex"0a336970e870b473ddbc27e3098bfa45bb1aa54f1f637f803d957e6271d8ffeba89da2665d62123763d9b634e30714144a1c165ac9")) -> hex"37 04350a336970e870b473ddbc27e3098bfa45bb1aa54f1f637f803d957e6271d8ffeba89da2665d62123763d9b634e30714144a1c165ac9",
    )

    for ((expected, bin) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val nextNodeId = randomKey().publicKey
      val Right(payload) = IntermediatePayload.validate(decoded, TlvStream(RouteBlindingEncryptedDataTlv.OutgoingNodeId(nextNodeId)), randomKey().publicKey)
      assert(payload.nextNodeId == nextNodeId)
      val encoded = perHopPayloadCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode final per-hop payload") {
    val blindedRoute = RouteBlinding.create(
      PrivateKey(hex"123456789123456789123456789123456789123456789123456789123456789101"),
      List(PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")),
      List(hex"04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c", hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
    ).route
    val testCases = Map(
      TlvStream[OnionMessagePayloadTlv](EncryptedData(hex"deadbeef")) -> hex"06 0404deadbeef",
      TlvStream[OnionMessagePayloadTlv](ReplyPath(blindedRoute), EncryptedData(hex"deadbeef")) -> hex"f7 02ef02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661902c5ee5d5d559475814916957e167b8c257e06532ae6bfcbe4553e4549b9142ec702039dcddf597c0ea5bfe3c4de00630182d26c8f3cb588fa02c8cd19391a110f41a200330840ad82edc7378794e568deb3a836e3b9bc2e4a684412c34dbc5e50159ecf0b9c3844719f8656af9ff283e1eecb503f5e45b302aa42066bc9802597cac8f9f7193b8fd24b8671e3807e9c61dae8b330b695de780033d76f6388daa82694bcc63d43eaac1c5d189722cb84d0edb3b8b7dccb833c886eda7adb483f44498789f4139b2c12a0bfe8436a 0404deadbeef",
    )

    for ((expected, bin) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val Right(payload) = FinalPayload.validate(decoded, TlvStream.empty)
      assert(payload.pathId_opt.isEmpty)
      val encoded = perHopPayloadCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("decode invalid relay per-hop payload") {
    val testCases = Seq[(InvalidTlvPayload, ByteVector, TlvStream[RouteBlindingEncryptedDataTlv])](
      // Missing encrypted data.
      (MissingRequiredTlv(UInt64(4)), hex"00", TlvStream(OutgoingNodeId(PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")))),
      // Forbidden reply path.
      (ForbiddenTlv(UInt64(2)), hex"f9 02ef02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661902c5ee5d5d559475814916957e167b8c257e06532ae6bfcbe4553e4549b9142ec702039dcddf597c0ea5bfe3c4de00630182d26c8f3cb588fa02c8cd19391a110f41a200330840ad82edc7378794e568deb3a836e3b9bc2e4a684412c34dbc5e50159ecf0b9c3844719f8656af9ff283e1eecb503f5e45b302aa42066bc9802597cac8f9f7193b8fd24b8671e3807e9c61dae8b330b695de780033d76f6388daa82694bcc63d43eaac1c5d189722cb84d0edb3b8b7dccb833c886eda7adb483f44498789f4139b2c12a0bfe8436a 0406010203040506", TlvStream(OutgoingNodeId(PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")))),
      // Missing encrypted outgoing node id.
      (MissingRequiredTlv(UInt64(4)), hex"08 0406010203040506", TlvStream.empty),
      // Forbidden encrypted path id.
      (ForbiddenTlv(UInt64(6)), hex"08 0406010203040506", TlvStream(OutgoingNodeId(PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")), PathId(hex"ffff"))),
    )

    for ((err, bin, blindedTlvs) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(IntermediatePayload.validate(decoded, blindedTlvs, randomKey().publicKey) == Left(err))
    }
  }

  test("decode invalid final per-hop payload") {
    val testCases = Seq[(InvalidTlvPayload, ByteVector, TlvStream[RouteBlindingEncryptedDataTlv])](
      // Forbidden encrypted payment relay data.
      (ForbiddenTlv(UInt64(10)), hex"06 040411223344", TlvStream(PathId(hex"deadbeef"), PaymentRelay(CltvExpiryDelta(48), 250, 25 msat))),
      // Forbidden encrypted payment constraints.
      (ForbiddenTlv(UInt64(12)), hex"06 040411223344", TlvStream(PathId(hex"deadbeef"), PaymentConstraints(CltvExpiry(500), 1 msat))),
    )

    for ((err, bin, blindedTlvs) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(FinalPayload.validate(decoded, blindedTlvs) == Left(err))
    }
  }

  test("onion packet can be any size") {
    {
      // small onion
      val onion = OnionRoutingPacket(1, hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991", hex"0012345679abcdef", ByteVector32(hex"0000111122223333444455556666777788889999aaaabbbbccccddddeeee0000"))
      val serialized = hex"004a 01 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 0012345679abcdef 0000111122223333444455556666777788889999aaaabbbbccccddddeeee0000"
      assert(messageOnionPacketCodec.encode(onion).require.bytes == serialized)
      assert(messageOnionPacketCodec.decode(serialized.bits).require.value == onion)
    }
    {
      // larger onion
      val onion = OnionRoutingPacket(2, hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007", hex"0012345679abcdef012345679abcdef012345679abcdef012345679abcdef012345679abcdef", ByteVector32(hex"eeee0000111122223333444455556666777788889999aaaabbbbccccddddeeee"))
      val serialized = hex"0068 02 027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007 0012345679abcdef012345679abcdef012345679abcdef012345679abcdef012345679abcdef eeee0000111122223333444455556666777788889999aaaabbbbccccddddeeee"
      assert(messageOnionPacketCodec.encode(onion).require.bytes == serialized)
      assert(messageOnionPacketCodec.decode(serialized.bits).require.value == onion)
    }
    {
      // onion with trailing additional bytes
      val onion = OnionRoutingPacket(0, hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991", hex"ffffffff", ByteVector32.Zeroes)
      val serialized = hex"0046 00 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 ffffffff 0000000000000000000000000000000000000000000000000000000000000000 0a01020000030400000000"
      assert(messageOnionPacketCodec.encode(onion).require.bytes == serialized.dropRight(11))
      assert(messageOnionPacketCodec.decode(serialized.bits).require.value == onion)
    }
    {
      // onion with empty payload
      val onion = OnionRoutingPacket(0, hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991", hex"", ByteVector32.Zeroes)
      val serialized = hex"0042 00 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 0000000000000000000000000000000000000000000000000000000000000000"
      assert(messageOnionPacketCodec.encode(onion).require.bytes == serialized)
      assert(messageOnionPacketCodec.decode(serialized.bits).require.value == onion)
    }
    {
      // onion length too big
      val serialized = hex"0048 00 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 ffffffff 0000000000000000000000000000000000000000000000000000000000000000"
      assert(messageOnionPacketCodec.decode(serialized.bits).isFailure)
    }
    {
      // onion length way too big
      val serialized = hex"00ff 00 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991 ffffffff 0000000000000000000000000000000000000000000000000000000000000000"
      assert(messageOnionPacketCodec.decode(serialized.bits).isFailure)
    }
  }

}
