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

import java.net.{Inet4Address, InetAddress}

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs._
import org.scalatest.FunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

/**
  * Created by PM on 31/05/2016.
  */

class LightningMessageCodecsSpec extends FunSuite {

  def bin(len: Int, fill: Byte) = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  def scalar(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill))

  def point(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  def publicKey(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  test("encode/decode live node_announcements") {
    val ann = hex"a58338c9660d135fd7d087eb62afd24a33562c54507a9334e79f0dc4f17d407e6d7c61f0e2f3d0d38599502f61704cf1ae93608df027014ade7ff592f27ce2690001025acdf50702d2eabbbacc7c25bbd73b39e65d28237705f7bde76f557e94fb41cb18a9ec00841122116c6e302e646563656e7465722e776f726c64000000000000000000000000000000130200000000000000000000ffffae8a0b082607"
    val bin = ann.toBitVector

    val node = nodeAnnouncementCodec.decode(bin).require.value
    val bin2 = nodeAnnouncementCodec.encode(node).require
    assert(bin === bin2)
  }

  test("encode/decode all channel messages") {
    val open = OpenChannel(randomBytes32, randomBytes32, 3, 4, 5, UInt64(6), 7, 8, 9, 10, 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte)
    val accept = AcceptChannel(randomBytes32, 3, UInt64(4), 5, 6, 7, 8, 9, publicKey(1), point(2), point(3), point(4), point(5), point(6))
    val funding_created = FundingCreated(randomBytes32, bin32(0), 3, randomBytes64)
    val funding_signed = FundingSigned(randomBytes32, randomBytes64)
    val funding_locked = FundingLocked(randomBytes32, point(2))
    val update_fee = UpdateFee(randomBytes32, 2)
    val shutdown = Shutdown(randomBytes32, bin(47, 0))
    val closing_signed = ClosingSigned(randomBytes32, 2, randomBytes64)
    val update_add_htlc = UpdateAddHtlc(randomBytes32, 2, 3, bin32(0), 4, TestConstants.emptyOnionPacket)
    val update_fulfill_htlc = UpdateFulfillHtlc(randomBytes32, 2, bin32(0))
    val update_fail_htlc = UpdateFailHtlc(randomBytes32, 2, bin(154, 0))
    val update_fail_malformed_htlc = UpdateFailMalformedHtlc(randomBytes32, 2, randomBytes32, 1111)
    val commit_sig = CommitSig(randomBytes32, randomBytes64, randomBytes64 :: randomBytes64 :: randomBytes64 :: Nil)
    val revoke_and_ack = RevokeAndAck(randomBytes32, scalar(0), point(1))
    val channel_announcement = ChannelAnnouncement(randomBytes64, randomBytes64, randomBytes64, randomBytes64, bin(7, 9), Block.RegtestGenesisBlock.hash, ShortChannelId(1), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
    val node_announcement = NodeAnnouncement(randomBytes64, bin(1, 2), 1, randomKey.publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil)
    val channel_update = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 42, 0, 3, 4, 5, 6, None)
    val announcement_signatures = AnnouncementSignatures(randomBytes32, ShortChannelId(42), randomBytes64, randomBytes64)
    val gossip_timestamp_filter = GossipTimestampFilter(Block.RegtestGenesisBlock.blockId, 100000, 1500)
    val query_short_channel_id = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, randomBytes(7515))
    val query_channel_range = QueryChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500)
    val reply_channel_range = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, 1, randomBytes(3200))
    val ping = Ping(100, bin(10, 1))
    val pong = Pong(bin(10, 1))
    val channel_reestablish = ChannelReestablish(randomBytes32, 242842L, 42L)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        update_add_htlc :: update_fulfill_htlc :: update_fail_htlc :: update_fail_malformed_htlc :: commit_sig :: revoke_and_ack ::
        channel_announcement :: node_announcement :: channel_update :: gossip_timestamp_filter :: query_short_channel_id :: query_channel_range :: reply_channel_range :: announcement_signatures :: ping :: pong :: channel_reestablish :: Nil

    msgs.foreach {
      msg => {
        val encoded = lightningMessageCodec.encode(msg).require
        val decoded = lightningMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

  test("encode/decode onion packet") {
    val bin = hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f917265f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"
    val expected = OnionPacket(0, hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619", hex"e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f9172", ByteVector32(hex"65f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"))

    val decoded = paymentOnionPacketCodec.decode(bin.toBitVector).require.value
    assert(decoded === expected)

    val encoded = paymentOnionPacketCodec.encode(decoded).require
    assert(encoded.toByteVector === bin)
  }

  test("encode/decode per-hop payload") {
    val payload = PerHopPayload(shortChannelId = ShortChannelId(42), amtToForward = 142000, outgoingCltvValue = 500000)
    val bin = perHopPayloadCodec.encode(payload).require
    assert(bin.toByteVector.size === 33)
    val payload1 = perHopPayloadCodec.decode(bin).require.value
    assert(payload === payload1)

    // realm (the first byte) should be 0
    val bin1 = bin.toByteVector.update(0, 1)
    intercept[IllegalArgumentException] {
      val payload2 = perHopPayloadCodec.decode(bin1.toBitVector).require.value
      assert(payload2 === payload1)
    }
  }

  test("decode channel_update with htlc_maximum_msat") {
    // this was generated by c-lightning
    val bin = hex"010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00"
    val update = lightningMessageCodec.decode(bin.toBitVector).require.value.asInstanceOf[ChannelUpdate]
    assert(update === ChannelUpdate(ByteVector64(hex"58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"), ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"), ShortChannelId(0x5a10000020000L), 1539791129, 1, 1, 6, 1, 1, 10, Some(980000000L)))
    val nodeId = PublicKey(hex"03370c9bac836e557eb4f017fe8f9cc047f44db39c1c4e410ff0f7be142b817ae4")
    assert(Announcements.checkSig(update, nodeId))
    val bin2 = ByteVector(lightningMessageCodec.encode(update).require.toByteArray)
    assert(bin === bin2)
  }

}
