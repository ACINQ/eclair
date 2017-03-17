package fr.acinq.eclair.channel

import java.nio.ByteOrder

import fr.acinq.bitcoin.{BinaryData, Crypto, Protocol}
import fr.acinq.eclair.wire.{ChannelUpdate, Codecs}

/**
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  * Created by fabrice on 14/03/17.
  */
object FailureMessage {
  val BADONION = 0x8000
  val PERM = 0x4000
  val NODE = 0x2000
  val UPDATE = 0x1000

  def encode(`type`: Int, data: BinaryData): BinaryData = Protocol.writeUInt16(`type`, ByteOrder.BIG_ENDIAN) ++ data

  val invalid_realm = encode(PERM | 1, BinaryData.empty)

  val temporary_node_failure = encode(NODE | 2, BinaryData.empty)

  val permanent_node_failure = encode(PERM | 2, BinaryData.empty)

  val required_node_feature_missing = encode(PERM | NODE | 3, BinaryData.empty)

  def invalid_onion_version(onion: BinaryData) = encode(BADONION | PERM | 4, Crypto.sha256(onion))

  def invalid_onion_hmac(onion: BinaryData) = encode(BADONION | PERM | 5, Crypto.sha256(onion))

  def invalid_onion_key(onion: BinaryData) = encode(BADONION | PERM | 6, Crypto.sha256(onion))

  val temporary_channel_failure = encode(7, BinaryData.empty)

  val permanent_channel_failure = encode(PERM | 8, BinaryData.empty)

  val unknown_next_peer = encode(PERM | 10, BinaryData.empty)

  def amount_below_minimum(amount_msat: Long, update: ChannelUpdate) = {
    val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
    encode(UPDATE | 11, Protocol.writeUInt32(amount_msat, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
  }

  def insufficient_fee(amount_msat: Long, update: ChannelUpdate) = {
    val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
    encode(UPDATE | 12, Protocol.writeUInt32(amount_msat, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
  }

  def incorrect_cltv_expiry(expiry: Long, update: ChannelUpdate) = {
    val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
    encode(UPDATE | 13, Protocol.writeUInt32(expiry, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
  }

  def expiry_too_soon(update: ChannelUpdate) = {
    val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
    encode(UPDATE | 14, Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
  }

  val unknown_payment_hash = encode(PERM | 15, BinaryData.empty)

  val incorrect_payment_amount = encode(PERM | 16, BinaryData.empty)

  val final_expiry_too_soon = encode(PERM | 17, BinaryData.empty)
}
