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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv._
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, InvalidTlvPayload, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol.PaymentOnion._
import fr.acinq.eclair.wire.protocol.PaymentOnionCodecs._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, ShortChannelId, UInt64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

/**
 * Created by t-bast on 05/07/2019.
 */

class PaymentOnionSpec extends AnyFunSuite {

  test("encode/decode onion packet") {
    val bin = hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f917265f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"
    val expected = OnionRoutingPacket(0, hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619", hex"e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f9172", ByteVector32(hex"65f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"))

    val decoded = paymentOnionPacketCodec.decode(bin.bits).require.value
    assert(decoded == expected)

    val encoded = paymentOnionPacketCodec.encode(decoded).require
    assert(encoded.toByteVector == bin)
  }

  test("decode fixed-size (legacy) channel relay per-hop payload") {
    // The following payloads were valid when encoded with the deprecated legacy fixed-size format.
    val testCases = Seq(
      hex"00 0000000000000000 0000000000000000 00000000 000000000000000000000000",
      hex"00 000000000000002a 0000000000022ab0 0007a120 000000000000000000000000",
      hex"00 0000000000000231 0000000000000451 000006c1 000000000000000000000000"
    )
    for (bin <- testCases) {
      assert(perHopPayloadCodec.decode(bin.bits).isFailure)
    }
  }

  test("decode payload length") {
    val testCases = Seq(
      (1, hex"00"),
      (43, hex"2a 0000"),
      (253, hex"fc 0000"),
      (256, hex"fd00fd 000000"),
      (260, hex"fd0101 00"),
      (65538, hex"fdffff 00"),
      (65541, hex"fe00010000 00"),
      (4294967305L, hex"ff0000000100000000 00")
    )

    for ((payloadLength, bin) <- testCases) {
      assert(payloadLengthDecoder.decode(bin.bits).require.value == payloadLength)
    }
  }

  test("encode/decode channel relay per-hop payload") {
    val testCases = Map(
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingChannelId(ShortChannelId(1105))) -> hex"11 02020231 04012a 06080000000000000451",
      TlvStream[OnionPaymentPayloadTlv](Set[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingChannelId(ShortChannelId(1105))), Set(GenericTlv(65535, hex"06c1"))) -> hex"17 02020231 04012a 06080000000000000451 fdffff0206c1",
    )

    for ((expected, bin) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val Right(payload) = IntermediatePayload.ChannelRelay.Standard.validate(decoded)
      assert(payload.amountOut == 561.msat)
      assert(payload.cltvOut == CltvExpiry(42))
      assert(payload.outgoingChannelId == ShortChannelId(1105))
      val encoded = perHopPayloadCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode channel relay blinded per-hop payload") {
    val blindedTlvs = TlvStream[RouteBlindingEncryptedDataTlv](
      RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(42)),
      RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(144), 100, 10 msat),
      RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat),
    )
    val testCases = Map(
      TlvStream[OnionPaymentPayloadTlv](EncryptedRecipientData(hex"0123456789abcdef")) -> hex"0a 0a080123456789abcdef ",
      TlvStream[OnionPaymentPayloadTlv](EncryptedRecipientData(hex"0123456789abcdef"), BlindingPoint(PublicKey(hex"036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2"))) -> hex"2d 0a080123456789abcdef 0c21036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2",
    )

    for ((expected, bin) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val Right(payload) = IntermediatePayload.ChannelRelay.Blinded.validate(decoded, blindedTlvs, randomKey().publicKey)
      assert(payload.outgoingChannelId == ShortChannelId(42))
      assert(payload.amountToForward(10_000 msat) == 9990.msat)
      assert(payload.outgoingCltv(CltvExpiry(1000)) == CltvExpiry(856))
      assert(payload.allowedFeatures.isEmpty)
      val encoded = perHopPayloadCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode node relay per-hop payload") {
    val nodeId = PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")
    val expected = TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingNodeId(nodeId))
    val bin = hex"2e 02020231 04012a fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"

    val decoded = perHopPayloadCodec.decode(bin.bits).require.value
    assert(decoded == expected)
    val Right(payload) = IntermediatePayload.NodeRelay.Standard.validate(decoded)
    assert(payload.amountToForward == 561.msat)
    assert(payload.totalAmount == 561.msat)
    assert(payload.outgoingCltv == CltvExpiry(42))
    assert(payload.outgoingNodeId == nodeId)
    assert(payload.paymentSecret.isEmpty)
    assert(payload.invoiceFeatures.isEmpty)
    assert(payload.invoiceRoutingInfo.isEmpty)

    val encoded = perHopPayloadCodec.encode(expected).require.bytes
    assert(encoded == bin)
  }

  test("encode/decode node relay to legacy per-hop payload") {
    val nodeId = PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")
    val features = hex"0a"
    val (node1, node2, node3) = (PublicKey(hex"036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2"), PublicKey(hex"025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"), PublicKey(hex"02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
    val routingHints = List(
      List(ExtraHop(node1, ShortChannelId(1), 10 msat, 100, CltvExpiryDelta(144))),
      List(ExtraHop(node2, ShortChannelId(2), 20 msat, 150, CltvExpiryDelta(12)), ExtraHop(node3, ShortChannelId(3), 30 msat, 200, CltvExpiryDelta(24)))
    )
    val expected = TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 1105 msat), InvoiceFeatures(features), OutgoingNodeId(nodeId), InvoiceRoutingInfo(routingHints))
    val bin = hex"fa 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451 fe00010231010a fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 fe000102339b01036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e200000000000000010000000a00000064009002025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce148600000000000000020000001400000096000c02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee00000000000000030000001e000000c80018"

    val decoded = perHopPayloadCodec.decode(bin.bits).require.value
    assert(decoded == expected)
    val Right(payload) = IntermediatePayload.NodeRelay.Standard.validate(decoded)
    assert(payload.amountToForward == 561.msat)
    assert(payload.totalAmount == 1105.msat)
    assert(payload.paymentSecret.contains(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")))
    assert(payload.outgoingCltv == CltvExpiry(42))
    assert(payload.outgoingNodeId == nodeId)
    assert(payload.invoiceFeatures.contains(features))
    assert(payload.invoiceRoutingInfo.contains(routingHints))

    val encoded = perHopPayloadCodec.encode(expected).require.bytes
    assert(encoded == bin)
  }

  test("encode/decode final per-hop payload") {
    val testCases = Map(
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0 msat)) -> hex"29 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 1105 msat)) -> hex"2b 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 4294967295L msat)) -> hex"2d 02020231 04012a 0824eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619ffffffff",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 4294967296L msat)) -> hex"2e 02020231 04012a 0825eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190100000000",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 1099511627775L msat)) -> hex"2e 02020231 04012a 0825eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619ffffffffff",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingChannelId(ShortChannelId(1105)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0 msat)) -> hex"33 02020231 04012a 06080000000000000451 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619",
      TlvStream[OnionPaymentPayloadTlv](Set[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0 msat)), Set(GenericTlv(65535, hex"06c1"))) -> hex"2f 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 fdffff0206c1",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0 msat), TrampolineOnion(OnionRoutingPacket(0, hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619", hex"cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30ca63141f74db6e92795c6130877cfdac3d4bd3087ee73c65d627ddd709112a848cc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce487014b14816e5805f9fcacb4f8f88b8ff176f1b94f6ce6b00bc43221130c17d20ef629db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbeae3ac8d331c5994779dffb31e5fc0dbdedc0c592ca6d21c18e47fe3528d6975c19517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b586210979e3bb286ef2851bf9ce04e28c40a203df98fd648d2f1936fd2f1def0e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea691738a9954908195143b039dbeed98f26a92585f3d15cf742c953799d3272e0545e9b744be9d3b4c", ByteVector32(hex"bb079bfc4b35190eee9f59a1d7b41ba2f773179f322dafb4b1af900c289ebd6c")))) -> hex"fd0203 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 fe00010234fd01d20002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30ca63141f74db6e92795c6130877cfdac3d4bd3087ee73c65d627ddd709112a848cc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce487014b14816e5805f9fcacb4f8f88b8ff176f1b94f6ce6b00bc43221130c17d20ef629db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbeae3ac8d331c5994779dffb31e5fc0dbdedc0c592ca6d21c18e47fe3528d6975c19517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b586210979e3bb286ef2851bf9ce04e28c40a203df98fd648d2f1936fd2f1def0e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea691738a9954908195143b039dbeed98f26a92585f3d15cf742c953799d3272e0545e9b744be9d3b4cbb079bfc4b35190eee9f59a1d7b41ba2f773179f322dafb4b1af900c289ebd6c"
    )

    for ((expected, bin) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val Right(payload) = FinalPayload.Standard.validate(decoded)
      assert(payload.amount == 561.msat)
      assert(payload.expiry == CltvExpiry(42))

      val encoded = perHopPayloadCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode final per-hop payload with custom user records") {
    val tlvs = TlvStream[OnionPaymentPayloadTlv](Set[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), PaymentData(ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0 msat)), Set(GenericTlv(5432123456L, hex"16c7ec71663784ff100b6eface1e60a97b92ea9d18b8ece5e558586bc7453828")))
    val bin = hex"53 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 ff0000000143c7a0402016c7ec71663784ff100b6eface1e60a97b92ea9d18b8ece5e558586bc7453828"

    val encoded = perHopPayloadCodec.encode(tlvs).require.bytes
    assert(encoded == bin)
    assert(perHopPayloadCodec.decode(bin.bits).require.value == tlvs)
  }

  test("encode/decode final blinded per-hop payload") {
    val blindedTlvs = TlvStream[RouteBlindingEncryptedDataTlv](
      RouteBlindingEncryptedDataTlv.PathId(hex"2a2a2a2a"),
      RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat),
    )
    val testCases = Map(
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(1234567)), EncryptedRecipientData(hex"deadbeef"), TotalAmount(1105 msat)) -> hex"13 02020231 040312d687 0a04deadbeef 12020451",
      TlvStream[OnionPaymentPayloadTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(1234567)), EncryptedRecipientData(hex"deadbeef"), BlindingPoint(PublicKey(hex"036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2")), TotalAmount(1105 msat)) -> hex"36 02020231 040312d687 0a04deadbeef 0c21036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2 12020451",
    )

    for ((expected, bin) <- testCases) {
      val decoded = perHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded == expected)
      val Right(payload) = FinalPayload.Blinded.validate(decoded, blindedTlvs)
      assert(payload.amount == 561.msat)
      assert(payload.totalAmount == 1105.msat)
      assert(payload.expiry == CltvExpiry(1234567))
      assert(payload.pathId == hex"2a2a2a2a")
      val encoded = perHopPayloadCodec.encode(expected).require.bytes
      assert(encoded == bin)
    }
  }

  test("decode multi-part final per-hop payload") {
    val Right(multiPart) = FinalPayload.Standard.validate(perHopPayloadCodec.decode(hex"2b 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451".bits).require.value)
    assert(multiPart.amount == 561.msat)
    assert(multiPart.expiry == CltvExpiry(42))
    assert(multiPart.totalAmount == 1105.msat)
    assert(multiPart.paymentSecret == ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))

    val Right(multiPartNoTotalAmount) = FinalPayload.Standard.validate(perHopPayloadCodec.decode(hex"29 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619".bits).require.value)
    assert(multiPartNoTotalAmount.amount == 561.msat)
    assert(multiPartNoTotalAmount.expiry == CltvExpiry(42))
    assert(multiPartNoTotalAmount.totalAmount == 561.msat)
    assert(multiPartNoTotalAmount.paymentSecret == ByteVector32(hex"eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
  }

  test("decode invalid channel relay per-hop payload") {
    val testCases = Seq(
      (MissingRequiredTlv(UInt64(2)), hex"0d 04012a 06080000000000000451"), // missing amount
      (MissingRequiredTlv(UInt64(4)), hex"0e 02020231 06080000000000000451"), // missing cltv
      (MissingRequiredTlv(UInt64(6)), hex"07 02020231 04012a") // missing channel id
    )

    for ((expectedErr, bin) <- testCases) {
      assert(IntermediatePayload.ChannelRelay.Standard.validate(perHopPayloadCodec.decode(bin.bits).require.value) == Left(expectedErr))
    }
  }

  test("decode invalid channel relay blinded per-hop payload") {
    val validBlindedTlvs = TlvStream[RouteBlindingEncryptedDataTlv](
      RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(42)),
      RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(144), 100, 10 msat),
      RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat),
    )

    case class TestCase(err: InvalidTlvPayload, bin: ByteVector, blindedTlvs: TlvStream[RouteBlindingEncryptedDataTlv])

    val testCases = Seq(
      // Forbidden non-encrypted amount.
      TestCase(ForbiddenTlv(UInt64(0)), hex"0e 02020231 0a080123456789abcdef", validBlindedTlvs),
      // Forbidden non-encrypted expiry.
      TestCase(ForbiddenTlv(UInt64(0)), hex"0d 04012a 0a080123456789abcdef", validBlindedTlvs),
      // Forbidden outgoing channel id.
      TestCase(ForbiddenTlv(UInt64(0)), hex"14 06080000000000000451 0a080123456789abcdef", validBlindedTlvs),
      // Forbidden unknown tlv.
      TestCase(ForbiddenTlv(UInt64(51)), hex"0e 0a080123456789abcdef 33020102", validBlindedTlvs),
      // Missing encrypted data.
      TestCase(MissingRequiredTlv(UInt64(10)), hex"23 0c21036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2", validBlindedTlvs),
      // Missing encrypted outgoing channel.
      TestCase(MissingRequiredTlv(UInt64(2)), hex"0a 0a080123456789abcdef", TlvStream(RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(144), 100, 10 msat), RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat))),
      // Missing encrypted payment relay data.
      TestCase(MissingRequiredTlv(UInt64(10)), hex"0a 0a080123456789abcdef", TlvStream(RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(42)), RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat))),
      // Missing encrypted payment constraint.
      TestCase(MissingRequiredTlv(UInt64(12)), hex"0a 0a080123456789abcdef", TlvStream(RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(42)), RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(144), 100, 10 msat))), // Forbidden encrypted path id.
      // Forbidden encrypted path id.
      TestCase(ForbiddenTlv(UInt64(6)), hex"0a 0a080123456789abcdef", TlvStream(RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(42)), RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(144), 100, 10 msat), RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat), RouteBlindingEncryptedDataTlv.PathId(hex"deadbeef"))),
    )

    for (testCase <- testCases) {
      val decoded = perHopPayloadCodec.decode(testCase.bin.bits).require.value
      assert(IntermediatePayload.ChannelRelay.Blinded.validate(decoded, testCase.blindedTlvs, randomKey().publicKey) == Left(testCase.err))
    }
  }

  test("decode invalid node relay per-hop payload") {
    val testCases = Seq(
      (MissingRequiredTlv(UInt64(2)), hex"2a 04012a fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), // missing amount
      (MissingRequiredTlv(UInt64(4)), hex"2b 02020231 fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), // missing cltv
      (MissingRequiredTlv(UInt64(66098)), hex"07 02020231 04012a"), // missing node id
      (ForbiddenTlv(UInt64(10)), hex"34 02020231 04012a 0a04ffffffff fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), // forbidden encrypted data
      (ForbiddenTlv(UInt64(12)), hex"51 02020231 04012a 0c21036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2 fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), // forbidden blinding point
    )

    for ((expectedErr, bin) <- testCases) {
      assert(IntermediatePayload.NodeRelay.Standard.validate(perHopPayloadCodec.decode(bin.bits).require.value) == Left(expectedErr))
    }
  }

  test("decode invalid final per-hop payload") {
    val testCases = Seq(
      (MissingRequiredTlv(UInt64(2)), hex"25 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), // missing amount
      (MissingRequiredTlv(UInt64(4)), hex"26 02020231 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), // missing cltv
      (MissingRequiredTlv(UInt64(8)), hex"07 02020231 04012a"), // missing payment secret
    )

    for ((expectedErr, bin) <- testCases) {
      assert(FinalPayload.Standard.validate(perHopPayloadCodec.decode(bin.bits).require.value) == Left(expectedErr))
    }
  }

  test("decode invalid final blinded per-hop payload") {
    val blindedTlvs = TlvStream[RouteBlindingEncryptedDataTlv](
      RouteBlindingEncryptedDataTlv.PathId(hex"2a2a2a2a"),
      RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1500), 1 msat),
    )
    val testCases = Seq(
      (MissingRequiredTlv(UInt64(2)), hex"11 04012a 0a080123456789abcdef 12020451"), // missing amount
      (MissingRequiredTlv(UInt64(4)), hex"12 02020231 0a080123456789abcdef 12020451"), // missing expiry
      (MissingRequiredTlv(UInt64(10)), hex"0b 02020231 04012a 12020451"), // missing encrypted data
      (MissingRequiredTlv(UInt64(18)), hex"11 02020231 04012a 0a080123456789abcdef"), // missing total amount
      (ForbiddenTlv(UInt64(0)), hex"1f 02020231 04012a 06080000000000000451 0a080123456789abcdef 12020451"), // forbidden outgoing_channel_id
      (ForbiddenTlv(UInt64(0)), hex"39 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451 0a080123456789abcdef 12020451"), // forbidden payment_data
      (ForbiddenTlv(UInt64(0)), hex"1b 02020231 04012a 0a080123456789abcdef 1004deadbeef 12020451"), // forbidden payment_metadata
      (ForbiddenTlv(UInt64(65535)), hex"1b 02020231 04012a 0a080123456789abcdef 12020451 fdffff0206c1"), // forbidden unknown tlv
    )

    for ((expectedErr, bin) <- testCases) {
      assert(FinalPayload.Blinded.validate(perHopPayloadCodec.decode(bin.bits).require.value, blindedTlvs) == Left(expectedErr))
    }
  }

  test("decode invalid per-hop payload") {
    val testCases = Seq(
      // Invalid fixed-size (legacy) payload.
      hex"00 000000000000002a 000000000000002a", // invalid length
      // Invalid variable-length (tlv) payload.
      hex"01", // invalid length
      hex"01 0000", // invalid length
      hex"04 0000 2a00", // unknown even types
      hex"04 0000 0000", // duplicate types
      hex"04 0100 0000" // unordered types
    )

    for (testCase <- testCases) {
      assert(perHopPayloadCodec.decode(testCase.bits).isFailure)
    }
  }

  test("encode/decode empty AsyncPayment TLV") {
    val tlvs = TlvStream[OnionPaymentPayloadTlv](AsyncPayment())
    val bin = hex"06 fe0acecbae00"

    val encoded = perHopPayloadCodec.encode(tlvs).require.bytes
    assert(encoded == bin)
    assert(perHopPayloadCodec.decode(bin.bits).require.value == tlvs)
  }

}
