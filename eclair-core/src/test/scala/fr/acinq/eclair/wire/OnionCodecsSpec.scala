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

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.wire.Onion.{FinalLegacyPayload, FinalTlvPayload, RelayLegacyPayload, RelayTlvPayload}
import fr.acinq.eclair.wire.OnionCodecs._
import fr.acinq.eclair.wire.OnionTlv._
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, ShortChannelId, UInt64}
import org.scalatest.FunSuite
import scodec.Attempt
import scodec.bits.HexStringSyntax

/**
 * Created by t-bast on 05/07/2019.
 */

class OnionCodecsSpec extends FunSuite {

  test("encode/decode onion packet") {
    val bin = hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f917265f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"
    val expected = OnionRoutingPacket(0, hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619", hex"e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f9172", ByteVector32(hex"65f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"))

    val decoded = paymentOnionPacketCodec.decode(bin.bits).require.value
    assert(decoded === expected)

    val encoded = paymentOnionPacketCodec.encode(decoded).require
    assert(encoded.toByteVector === bin)
  }

  test("encode/decode fixed-size (legacy) relay per-hop payload") {
    val testCases = Map(
      RelayLegacyPayload(ShortChannelId(0), 0 msat, CltvExpiry(0)) -> hex"00 0000000000000000 0000000000000000 00000000 000000000000000000000000",
      RelayLegacyPayload(ShortChannelId(42), 142000 msat, CltvExpiry(500000)) -> hex"00 000000000000002a 0000000000022ab0 0007a120 000000000000000000000000",
      RelayLegacyPayload(ShortChannelId(561), 1105 msat, CltvExpiry(1729)) -> hex"00 0000000000000231 0000000000000451 000006c1 000000000000000000000000"
    )

    for ((expected, bin) <- testCases) {
      val decoded = relayPerHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded === expected)

      val encoded = relayPerHopPayloadCodec.encode(expected).require.bytes
      assert(encoded === bin)
    }
  }

  test("encode/decode fixed-size (legacy) final per-hop payload") {
    val testCases = Map(
      FinalLegacyPayload(0 msat, CltvExpiry(0)) -> hex"00 0000000000000000 0000000000000000 00000000 000000000000000000000000",
      FinalLegacyPayload(142000 msat, CltvExpiry(500000)) -> hex"00 0000000000000000 0000000000022ab0 0007a120 000000000000000000000000",
      FinalLegacyPayload(1105 msat, CltvExpiry(1729)) -> hex"00 0000000000000000 0000000000000451 000006c1 000000000000000000000000"
    )

    for ((expected, bin) <- testCases) {
      val decoded = finalPerHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded === expected)

      val encoded = finalPerHopPayloadCodec.encode(expected).require.bytes
      assert(encoded === bin)
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
      assert(payloadLengthDecoder.decode(bin.bits).require.value === payloadLength)
    }
  }

  test("encode/decode variable-length (tlv) relay per-hop payload") {
    val testCases = Map(
      TlvStream[OnionTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingChannelId(ShortChannelId(1105))) -> hex"11 02020231 04012a 06080000000000000451",
      TlvStream[OnionTlv](Seq(AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingChannelId(ShortChannelId(1105))), Seq(GenericTlv(65535, hex"06c1"))) -> hex"17 02020231 04012a 06080000000000000451 fdffff0206c1"
    )

    for ((expected, bin) <- testCases) {
      val decoded = relayPerHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded === RelayTlvPayload(expected))
      assert(decoded.amountToForward === 561.msat)
      assert(decoded.outgoingCltv === CltvExpiry(42))
      assert(decoded.outgoingChannelId === ShortChannelId(1105))

      val encoded = relayPerHopPayloadCodec.encode(RelayTlvPayload(expected)).require.bytes
      assert(encoded === bin)
    }
  }

  test("encode/decode variable-length (tlv) final per-hop payload") {
    val testCases = Map(
      TlvStream[OnionTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42))) -> hex"07 02020231 04012a",
      TlvStream[OnionTlv](AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42)), OutgoingChannelId(ShortChannelId(1105))) -> hex"11 02020231 04012a 06080000000000000451",
      TlvStream[OnionTlv](Seq(AmountToForward(561 msat), OutgoingCltv(CltvExpiry(42))), Seq(GenericTlv(65535, hex"06c1"))) -> hex"0d 02020231 04012a fdffff0206c1"
    )

    for ((expected, bin) <- testCases) {
      val decoded = finalPerHopPayloadCodec.decode(bin.bits).require.value
      assert(decoded === FinalTlvPayload(expected))
      assert(decoded.amount === 561.msat)
      assert(decoded.expiry === CltvExpiry(42))

      val encoded = finalPerHopPayloadCodec.encode(FinalTlvPayload(expected)).require.bytes
      assert(encoded === bin)
    }
  }

  test("decode variable-length (tlv) relay per-hop payload missing information") {
    val testCases = Seq(
      (InvalidOnionPayload(UInt64(2), 0), hex"0d 04012a 06080000000000000451"), // missing amount
      (InvalidOnionPayload(UInt64(4), 0), hex"0e 02020231 06080000000000000451"), // missing cltv
      (InvalidOnionPayload(UInt64(6), 0), hex"07 02020231 04012a") // missing channel id
    )

    for ((expectedErr, bin) <- testCases) {
      val decoded = relayPerHopPayloadCodec.decode(bin.bits)
      assert(decoded.isFailure)
      val Attempt.Failure(err: MissingRequiredTlv) = decoded
      assert(err.failureMessage === expectedErr)
    }
  }

  test("decode variable-length (tlv) final per-hop payload missing information") {
    val testCases = Seq(
      (InvalidOnionPayload(UInt64(2), 0), hex"03 04012a"), // missing amount
      (InvalidOnionPayload(UInt64(4), 0), hex"04 02020231") // missing cltv
    )

    for ((expectedErr, bin) <- testCases) {
      val decoded = finalPerHopPayloadCodec.decode(bin.bits)
      assert(decoded.isFailure)
      val Attempt.Failure(err: MissingRequiredTlv) = decoded
      assert(err.failureMessage === expectedErr)
    }
  }

  test("decode invalid per-hop payload") {
    val testCases = Seq(
      // Invalid fixed-size (legacy) payload.
      hex"00 000000000000002a 000000000000002a", // invalid length
      // Invalid variable-length (tlv) payload.
      hex"00", // empty payload is missing required information
      hex"01", // invalid length
      hex"01 0000", // invalid length
      hex"04 0000 2a00", // unknown even types
      hex"04 0000 0000", // duplicate types
      hex"04 0100 0000" // unordered types
    )

    for (testCase <- testCases) {
      assert(relayPerHopPayloadCodec.decode(testCase.bits).isFailure)
      assert(finalPerHopPayloadCodec.decode(testCase.bits).isFailure)
    }
  }

}
