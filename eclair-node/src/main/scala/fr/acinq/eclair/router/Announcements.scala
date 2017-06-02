package fr.acinq.eclair.router

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256, verifySignature}
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.serializationResult
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, LightningMessageCodecs, NodeAnnouncement}
import scodec.bits.BitVector
import shapeless.HNil

import scala.compat.Platform


/**
  * Created by PM on 03/02/2017.
  */
object Announcements {

  def channelAnnouncementWitnessEncode(shortChannelId: Long, nodeId1: BinaryData, nodeId2: BinaryData, bitcoinKey1: BinaryData, bitcoinKey2: BinaryData, features: BinaryData): BinaryData =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelAnnouncementWitnessCodec.encode(shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: features :: HNil))))

  def nodeAnnouncementWitnessEncode(timestamp: Long, nodeId: BinaryData, rgbColor: (Byte, Byte, Byte), alias: String, features: BinaryData, addresses: List[InetSocketAddress]): BinaryData =
    sha256(sha256(serializationResult(LightningMessageCodecs.nodeAnnouncementWitnessCodec.encode(timestamp :: nodeId :: rgbColor :: alias :: features :: addresses :: HNil))))

  def channelUpdateWitnessEncode(shortChannelId: Long, timestamp: Long, flags: BinaryData, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long): BinaryData =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelUpdateWitnessCodec.encode(shortChannelId :: timestamp :: flags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: HNil))))

  def signChannelAnnouncement(shortChannelId: Long, localNodeSecret: PrivateKey, remoteNodeId: PublicKey, localFundingPrivKey: PrivateKey, remoteFundingKey: PublicKey, features: BinaryData): (BinaryData, BinaryData) = {
    val witness = if (LexicographicalOrdering.isLessThan(localNodeSecret.publicKey.toBin, remoteNodeId.toBin)) {
      channelAnnouncementWitnessEncode(shortChannelId, localNodeSecret.publicKey, remoteNodeId, localFundingPrivKey.publicKey, remoteFundingKey, features)
    } else {
      channelAnnouncementWitnessEncode(shortChannelId, remoteNodeId, localNodeSecret.publicKey, remoteFundingKey, localFundingPrivKey.publicKey, features)
    }
    val nodeSig = Crypto.encodeSignature(Crypto.sign(witness, localNodeSecret)) :+ 1.toByte
    val bitcoinSig = Crypto.encodeSignature(Crypto.sign(witness, localFundingPrivKey)) :+ 1.toByte
    (nodeSig, bitcoinSig)
  }

  def makeChannelAnnouncement(shortChannelId: Long, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, localNodeSignature: BinaryData, remoteNodeSignature: BinaryData, localBitcoinSignature: BinaryData, remoteBitcoinSignature: BinaryData): ChannelAnnouncement = {
    val (nodeId1, nodeId2, bitcoinKey1, bitcoinKey2, nodeSignature1, nodeSignature2, bitcoinSignature1, bitcoinSignature2) =
      if (LexicographicalOrdering.isLessThan(localNodeId.toBin, remoteNodeId.toBin)) {
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
      features = BinaryData("")
    )
  }

  def makeNodeAnnouncement(nodeSecret: PrivateKey, alias: String, color: (Byte, Byte, Byte), addresses: List[InetSocketAddress], timestamp: Long = Platform.currentTime / 1000): NodeAnnouncement = {
    require(alias.size <= 32)
    val witness = nodeAnnouncementWitnessEncode(timestamp, nodeSecret.publicKey, color, alias, "", addresses)
    val sig = Crypto.encodeSignature(Crypto.sign(witness, nodeSecret)) :+ 1.toByte
    NodeAnnouncement(
      signature = sig,
      timestamp = timestamp,
      nodeId = nodeSecret.publicKey,
      rgbColor = color,
      alias = alias,
      features = "",
      addresses = addresses
    )
  }


  def makeFlags(direction: Boolean, disable: Boolean): BinaryData = BitVector.bits(disable :: direction :: Nil).padLeft(16).toByteArray

  def makeChannelUpdate(nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: Long, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long, enable: Boolean = true, timestamp: Long = Platform.currentTime / 1000): ChannelUpdate = {
    val isNode1 = LexicographicalOrdering.isLessThan(nodeSecret.publicKey.toBin, remoteNodeId.toBin)
    val flags = makeFlags(direction = isNode1, disable = !enable)
    require(flags.size == 2, "flags must be a 2-bytes field")
    val witness = channelUpdateWitnessEncode(shortChannelId, timestamp, flags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths)
    val sig = Crypto.encodeSignature(Crypto.sign(witness, nodeSecret)) :+ 1.toByte
    ChannelUpdate(
      signature = sig,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      flags = flags,
      cltvExpiryDelta = cltvExpiryDelta,
      htlcMinimumMsat = htlcMinimumMsat,
      feeBaseMsat = feeBaseMsat,
      feeProportionalMillionths = feeProportionalMillionths
    )
  }

  def checkSigs(ann: ChannelAnnouncement): Boolean = {
    val witness = channelAnnouncementWitnessEncode(ann.shortChannelId, ann.nodeId1, ann.nodeId2, ann.bitcoinKey1, ann.bitcoinKey2, ann.features)
    verifySignature(witness, ann.nodeSignature1, PublicKey(ann.nodeId1)) &&
      verifySignature(witness, ann.nodeSignature2, PublicKey(ann.nodeId2)) &&
      verifySignature(witness, ann.bitcoinSignature1, PublicKey(ann.bitcoinKey1)) &&
      verifySignature(witness, ann.bitcoinSignature2, PublicKey(ann.bitcoinKey2))
  }

  def checkSig(ann: NodeAnnouncement): Boolean = {
    val witness = nodeAnnouncementWitnessEncode(ann.timestamp, ann.nodeId, ann.rgbColor, ann.alias, ann.features, ann.addresses)
    verifySignature(witness, ann.signature, PublicKey(ann.nodeId))
  }

  def checkSig(ann: ChannelUpdate, nodeId: BinaryData): Boolean = {
    val witness = channelUpdateWitnessEncode(ann.shortChannelId, ann.timestamp, ann.flags, ann.cltvExpiryDelta, ann.htlcMinimumMsat, ann.feeBaseMsat, ann.feeProportionalMillionths)
    verifySignature(witness, ann.signature, PublicKey(nodeId))
  }

}
