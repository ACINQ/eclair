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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, sha256, verifySignature}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Feature, Features, MilliSatoshi, NodeFeature, RealShortChannelId, ShortChannelId, TimestampSecond, TimestampSecondLong, serializationResult}
import scodec.bits.ByteVector
import shapeless.HNil

/**
 * Created by PM on 03/02/2017.
 */
object Announcements {

  def channelAnnouncementWitnessEncode(chainHash: ByteVector32, shortChannelId: RealShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, bitcoinKey1: PublicKey, bitcoinKey2: PublicKey, features: Features[Feature], tlvStream: TlvStream[ChannelAnnouncementTlv]): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelAnnouncementWitnessCodec.encode(features :: chainHash :: shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: tlvStream :: HNil))))

  def nodeAnnouncementWitnessEncode(timestamp: TimestampSecond, nodeId: PublicKey, rgbColor: Color, alias: String, features: Features[Feature], addresses: List[NodeAddress], tlvStream: TlvStream[NodeAnnouncementTlv]): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.nodeAnnouncementWitnessCodec.encode(features :: timestamp :: nodeId :: rgbColor :: alias :: addresses :: tlvStream :: HNil))))

  def channelUpdateWitnessEncode(chainHash: ByteVector32, shortChannelId: ShortChannelId, timestamp: TimestampSecond, messageFlags: ChannelUpdate.MessageFlags, channelFlags: ChannelUpdate.ChannelFlags, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, tlvStream: TlvStream[ChannelUpdateTlv]): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelUpdateWitnessCodec.encode(chainHash :: shortChannelId :: timestamp :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: tlvStream :: HNil))))

  def generateChannelAnnouncementWitness(chainHash: ByteVector32, shortChannelId: RealShortChannelId, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, features: Features[Feature]): ByteVector =
    if (isNode1(localNodeId, remoteNodeId)) {
      channelAnnouncementWitnessEncode(chainHash, shortChannelId, localNodeId, remoteNodeId, localFundingKey, remoteFundingKey, features, TlvStream.empty)
    } else {
      channelAnnouncementWitnessEncode(chainHash, shortChannelId, remoteNodeId, localNodeId, remoteFundingKey, localFundingKey, features, TlvStream.empty)
    }

  def signChannelAnnouncement(witness: ByteVector, key: PrivateKey): ByteVector64 = Crypto.sign(witness, key)

  def makeChannelAnnouncement(chainHash: ByteVector32, shortChannelId: RealShortChannelId, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, localNodeSignature: ByteVector64, remoteNodeSignature: ByteVector64, localBitcoinSignature: ByteVector64, remoteBitcoinSignature: ByteVector64): ChannelAnnouncement = {
    val (nodeId1, nodeId2, bitcoinKey1, bitcoinKey2, nodeSignature1, nodeSignature2, bitcoinSignature1, bitcoinSignature2) =
      if (isNode1(localNodeId, remoteNodeId)) {
        (localNodeId, remoteNodeId, localFundingKey, remoteFundingKey, localNodeSignature, remoteNodeSignature, localBitcoinSignature, remoteBitcoinSignature)
      } else {
        (remoteNodeId, localNodeId, remoteFundingKey, localFundingKey, remoteNodeSignature, localNodeSignature, remoteBitcoinSignature, localBitcoinSignature)
      }
    ChannelAnnouncement(
      nodeSignature1 = nodeSignature1,
      nodeSignature2 = nodeSignature2,
      bitcoinSignature1 = bitcoinSignature1,
      bitcoinSignature2 = bitcoinSignature2,
      shortChannelId = shortChannelId,
      nodeId1 = nodeId1,
      nodeId2 = nodeId2,
      bitcoinKey1 = bitcoinKey1,
      bitcoinKey2 = bitcoinKey2,
      features = Features.empty,
      chainHash = chainHash
    )
  }

  def makeNodeAnnouncement(nodeSecret: PrivateKey, alias: String, color: Color, nodeAddresses: List[NodeAddress], features: Features[NodeFeature], timestamp: TimestampSecond = TimestampSecond.now()): NodeAnnouncement = {
    require(alias.length <= 32)
    // sort addresses by ascending address descriptor type; do not reorder addresses within the same descriptor type
    val sortedAddresses = nodeAddresses.map {
      case address@(_: IPv4) => (1, address)
      case address@(_: IPv6) => (2, address)
      case address@(_: Tor2) => (3, address)
      case address@(_: Tor3) => (4, address)
      case address@(_: DnsHostname) => (5, address)
    }.sortBy(_._1).map(_._2)
    val witness = nodeAnnouncementWitnessEncode(timestamp, nodeSecret.publicKey, color, alias, features.unscoped(), sortedAddresses, TlvStream.empty)
    val sig = Crypto.sign(witness, nodeSecret)
    NodeAnnouncement(
      signature = sig,
      timestamp = timestamp,
      nodeId = nodeSecret.publicKey,
      rgbColor = color,
      alias = alias,
      features = features.unscoped(),
      addresses = sortedAddresses
    )
  }

  case class AddressException(message: String) extends IllegalArgumentException(message)

  /**
   * BOLT 7:
   * The creating node MUST set node-id-1 and node-id-2 to the public keys of the
   * two nodes who are operating the channel, such that node-id-1 is the numerically-lesser
   * of the two DER encoded keys sorted in ascending numerical order,
   *
   * @return true if localNodeId is node1
   */
  def isNode1(localNodeId: PublicKey, remoteNodeId: PublicKey): Boolean = LexicographicalOrdering.isLessThan(localNodeId.value, remoteNodeId.value)

  /**
   * This method compares channel updates, ignoring fields that don't matter, like signature or timestamp
   *
   * @return true if channel updates are "equal"
   */
  def areSame(u1: ChannelUpdate, u2: ChannelUpdate): Boolean =
    u1.copy(signature = ByteVector64.Zeroes, timestamp = 0 unixsec) == u2.copy(signature = ByteVector64.Zeroes, timestamp = 0 unixsec)

  def areSameIgnoreFlags(u1: ChannelUpdate, u2: ChannelUpdate): Boolean =
    u1.feeBaseMsat == u2.feeBaseMsat &&
      u1.feeProportionalMillionths == u2.feeProportionalMillionths &&
      u1.cltvExpiryDelta == u2.cltvExpiryDelta &&
      u1.htlcMinimumMsat == u2.htlcMinimumMsat &&
      u1.htlcMaximumMsat == u2.htlcMaximumMsat

  def makeChannelUpdate(chainHash: ByteVector32, nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: ShortChannelId, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, isPrivate: Boolean = false, enable: Boolean = true, timestamp: TimestampSecond = TimestampSecond.now()): ChannelUpdate = {
    val messageFlags = ChannelUpdate.MessageFlags(isPrivate)
    val channelFlags = ChannelUpdate.ChannelFlags(isNode1 = isNode1(nodeSecret.publicKey, remoteNodeId), isEnabled = enable)
    val witness = channelUpdateWitnessEncode(chainHash, shortChannelId, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat, TlvStream.empty)
    val sig = Crypto.sign(witness, nodeSecret)
    ChannelUpdate(
      signature = sig,
      chainHash = chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      messageFlags = messageFlags,
      channelFlags = channelFlags,
      cltvExpiryDelta = cltvExpiryDelta,
      htlcMinimumMsat = htlcMinimumMsat,
      feeBaseMsat = feeBaseMsat,
      feeProportionalMillionths = feeProportionalMillionths,
      htlcMaximumMsat = htlcMaximumMsat
    )
  }

  def updateScid(nodeSecret: PrivateKey, u: ChannelUpdate, scid: ShortChannelId): ChannelUpdate = {
    // NB: we don't update the timestamp as we're not changing any parameter.
    val u1 = u.copy(shortChannelId = scid)
    val witness = channelUpdateWitnessEncode(u.chainHash, scid, u.timestamp, u.messageFlags, u.channelFlags, u.cltvExpiryDelta, u.htlcMinimumMsat, u.feeBaseMsat, u.feeProportionalMillionths, u.htlcMaximumMsat, u.tlvStream)
    val sig = Crypto.sign(witness, nodeSecret)
    u1.copy(signature = sig)
  }

  def checkSigs(ann: ChannelAnnouncement): Boolean = {
    val witness = channelAnnouncementWitnessEncode(ann.chainHash, ann.shortChannelId, ann.nodeId1, ann.nodeId2, ann.bitcoinKey1, ann.bitcoinKey2, ann.features, ann.tlvStream)
    verifySignature(witness, ann.nodeSignature1, ann.nodeId1) &&
      verifySignature(witness, ann.nodeSignature2, ann.nodeId2) &&
      verifySignature(witness, ann.bitcoinSignature1, ann.bitcoinKey1) &&
      verifySignature(witness, ann.bitcoinSignature2, ann.bitcoinKey2)
  }

  def checkSig(ann: NodeAnnouncement): Boolean = {
    val witness = nodeAnnouncementWitnessEncode(ann.timestamp, ann.nodeId, ann.rgbColor, ann.alias, ann.features, ann.addresses, ann.tlvStream)
    verifySignature(witness, ann.signature, ann.nodeId)
  }

  def checkSig(upd: ChannelUpdate, nodeId: PublicKey): Boolean = {
    val witness = channelUpdateWitnessEncode(upd.chainHash, upd.shortChannelId, upd.timestamp, upd.messageFlags, upd.channelFlags, upd.cltvExpiryDelta, upd.htlcMinimumMsat, upd.feeBaseMsat, upd.feeProportionalMillionths, upd.htlcMaximumMsat, upd.tlvStream)
    verifySignature(witness, upd.signature, nodeId)
  }
}
