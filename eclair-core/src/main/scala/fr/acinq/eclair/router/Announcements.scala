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

import fr.acinq.bitcoin.scalacompat.Crypto._
import fr.acinq.bitcoin.scalacompat.Musig2.{IndividualNonce, LocalNonce}
import fr.acinq.bitcoin.scalacompat.{BlockHash, ByteVector32, ByteVector64, Crypto, KotlinUtils, LexicographicalOrdering, Musig2, OutPoint, Satoshi, Script, TxOut}
import fr.acinq.eclair.channel.ChannelSpendSignature.PartialSignatureWithNonce
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, Feature, Features, MilliSatoshi, NodeFeature, RealShortChannelId, ShortChannelId, TimestampSecond, TimestampSecondLong, UInt64, serializationResult}
import scodec.bits.ByteVector
import shapeless.HNil

/**
 * Created by PM on 03/02/2017.
 */
object Announcements {

  def msgHash(messageName: String, fieldName: String, message: ByteVector): ByteVector32 = {
    val tag = ByteVector(("lightning" ++ messageName ++ fieldName).getBytes)
    sha256(sha256(tag) ++ sha256(tag) ++ sha256(message))
  }

  def channelAnnouncementWitnessEncode(chainHash: BlockHash, shortChannelId: RealShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, bitcoinKey1: PublicKey, bitcoinKey2: PublicKey, features: Features[Feature], tlvStream: TlvStream[LegacyChannelAnnouncementTlv]): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelAnnouncementWitnessCodec.encode(features :: chainHash :: shortChannelId :: nodeId1 :: nodeId2 :: bitcoinKey1 :: bitcoinKey2 :: tlvStream :: HNil))))

  def channelAnnouncementWitnessEncode(ann: ModernChannelAnnouncement): ByteVector = {
    val signedTlvs = ann.tlvStream.copy(
      records = ann.tlvStream.records.filterNot(_.isInstanceOf[ModernChannelAnnouncementTlv.Signature]),
      unknown = ann.tlvStream.unknown.filter(tlv => tlv.tag <= UInt64(159) || (UInt64(1_000_000_000L) <= tlv.tag && tlv.tag <= UInt64(2_999_999_999L))),
    )
    ModernChannelAnnouncementTlv.codec.encode(signedTlvs).require.bytes
  }

  def nodeAnnouncementWitnessEncode(timestamp: TimestampSecond, nodeId: PublicKey, rgbColor: Color, alias: String, features: Features[Feature], addresses: List[NodeAddress], tlvStream: TlvStream[LegacyNodeAnnouncementTlv]): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.nodeAnnouncementWitnessCodec.encode(features :: timestamp :: nodeId :: rgbColor :: alias :: addresses :: tlvStream :: HNil))))

  def nodeAnnouncementWitnessEncode(ann: ModernNodeAnnouncement): ByteVector = {
    val signedTlvs = ann.tlvStream.copy(
      records = ann.tlvStream.records.filterNot(_.isInstanceOf[ModernNodeAnnouncementTlv.Signature]),
      unknown = ann.tlvStream.unknown.filter(tlv => tlv.tag <= UInt64(159) || (UInt64(1_000_000_000L) <= tlv.tag && tlv.tag <= UInt64(2_999_999_999L))),
    )
    ModernNodeAnnouncementTlv.codec.encode(signedTlvs).require.bytes
  }

  def channelUpdateWitnessEncode(chainHash: BlockHash, shortChannelId: ShortChannelId, timestamp: TimestampSecond, messageFlags: LegacyChannelUpdate.MessageFlags, channelFlags: LegacyChannelUpdate.ChannelFlags, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, tlvStream: TlvStream[LegacyChannelUpdateTlv]): ByteVector =
    sha256(sha256(serializationResult(LightningMessageCodecs.channelUpdateWitnessCodec.encode(chainHash :: shortChannelId :: timestamp :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: tlvStream :: HNil))))

  def channelUpdateWitnessEncode(upd: ModernChannelUpdate): ByteVector = {
    val signedTlvs = upd.tlvStream.copy(
      records = upd.tlvStream.records.filterNot(_.isInstanceOf[ModernChannelUpdateTlv.Signature]),
      unknown = upd.tlvStream.unknown.filter(tlv => tlv.tag <= UInt64(159) || (UInt64(1_000_000_000L) <= tlv.tag && tlv.tag <= UInt64(2_999_999_999L))),
    )
    ModernChannelUpdateTlv.codec.encode(signedTlvs).require.bytes
  }

  def generateChannelAnnouncementWitness(chainHash: BlockHash, shortChannelId: RealShortChannelId, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, features: Features[Feature]): ByteVector =
    if (isNode1(localNodeId, remoteNodeId)) {
      channelAnnouncementWitnessEncode(chainHash, shortChannelId, localNodeId, remoteNodeId, localFundingKey, remoteFundingKey, features, TlvStream.empty)
    } else {
      channelAnnouncementWitnessEncode(chainHash, shortChannelId, remoteNodeId, localNodeId, remoteFundingKey, localFundingKey, features, TlvStream.empty)
    }

  def signChannelAnnouncement(witness: ByteVector, key: PrivateKey): ByteVector64 = Crypto.sign(witness, key)

  def makeChannelAnnouncement(chainHash: BlockHash, shortChannelId: RealShortChannelId, localNodeId: PublicKey, remoteNodeId: PublicKey, localFundingKey: PublicKey, remoteFundingKey: PublicKey, localNodeSignature: ByteVector64, remoteNodeSignature: ByteVector64, localBitcoinSignature: ByteVector64, remoteBitcoinSignature: ByteVector64): LegacyChannelAnnouncement = {
    val (nodeId1, nodeId2, bitcoinKey1, bitcoinKey2, nodeSignature1, nodeSignature2, bitcoinSignature1, bitcoinSignature2) =
      if (isNode1(localNodeId, remoteNodeId)) {
        (localNodeId, remoteNodeId, localFundingKey, remoteFundingKey, localNodeSignature, remoteNodeSignature, localBitcoinSignature, remoteBitcoinSignature)
      } else {
        (remoteNodeId, localNodeId, remoteFundingKey, localFundingKey, remoteNodeSignature, localNodeSignature, remoteBitcoinSignature, localBitcoinSignature)
      }
    LegacyChannelAnnouncement(
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

  def signChannelAnnouncement(ann: ModernChannelAnnouncement, key: PrivateKey, localNonce: LocalNonce, pubkeys: Seq[PublicKey], nonces: Seq[IndividualNonce]): Option[ByteVector32] = {
    val witness = msgHash("channel_announcement_2", "signature", channelAnnouncementWitnessEncode(ann))
    Musig2.signMessage(key, localNonce.secretNonce, witness, pubkeys, nonces).toOption
  }

  def makeChannelAnnouncement2(chainHash: BlockHash,
                               shortChannelId: RealShortChannelId,
                               fundingAmount: Satoshi,
                               fundingOutpoint: OutPoint,
                               localNodeId: PublicKey,
                               remoteNodeId: PublicKey,
                               localFundingKey: PublicKey,
                               remoteFundingKey: PublicKey,
                               localNodePartialSig: PartialSignatureWithNonce,
                               localBitcoinPartialSig: PartialSignatureWithNonce,
                               remoteNodePartialSig: PartialSignatureWithNonce,
                               remoteBitcoinPartialSig: PartialSignatureWithNonce): Option[ModernChannelAnnouncement] = {
    // TODO: note that we're not using the modified musig2 version proposed in the BOLT, but vanilla musig2 instead.
    val partialSigs = Seq(localNodePartialSig, localBitcoinPartialSig, remoteNodePartialSig, remoteBitcoinPartialSig).map(_.partialSig)
    val nonces = Seq(localNodePartialSig, localBitcoinPartialSig, remoteNodePartialSig, remoteBitcoinPartialSig).map(_.nonce)
    val publicKeys = Scripts.sort(Seq(localNodeId, remoteNodeId, localFundingKey, remoteFundingKey))
    val (nodeId1, nodeId2) = if (isNode1(localNodeId, remoteNodeId)) (localNodeId, remoteNodeId) else (remoteNodeId, localNodeId)
    val (bitcoinKey1, bitcoinKey2) = if (isNode1(localNodeId, remoteNodeId)) (localFundingKey, remoteFundingKey) else (remoteFundingKey, localFundingKey)
    val merkleRoot = KotlinUtils.kmp2scala(Musig2.aggregateKeys(Scripts.sort(Seq(localFundingKey, remoteFundingKey))).pub.value)
    val announcement = ModernChannelAnnouncement(chainHash, shortChannelId, fundingAmount, fundingOutpoint, nodeId1, nodeId2, bitcoinKey1, bitcoinKey2, merkleRoot, Features.empty, ByteVector64.Zeroes)
    Musig2.aggregatePartialSignatures(partialSigs, msgHash("channel_announcement_2", "signature", channelAnnouncementWitnessEncode(announcement)), publicKeys, nonces).toOption.map(sig => {
      ModernChannelAnnouncement(chainHash, shortChannelId, fundingAmount, fundingOutpoint, nodeId1, nodeId2, bitcoinKey1, bitcoinKey2, merkleRoot, Features.empty, sig)
    })
  }

  def makeNodeAnnouncement(nodeSecret: PrivateKey, alias: String, color: Color, nodeAddresses: List[NodeAddress], features: Features[NodeFeature], timestamp: TimestampSecond = TimestampSecond.now(), fundingRates_opt: Option[LiquidityAds.WillFundRates] = None): LegacyNodeAnnouncement = {
    require(alias.length <= 32)
    // sort addresses by ascending address descriptor type; do not reorder addresses within the same descriptor type
    val sortedAddresses = nodeAddresses.map {
      case address@(_: IPv4) => (1, address)
      case address@(_: IPv6) => (2, address)
      case address@(_: Tor2) => (3, address)
      case address@(_: Tor3) => (4, address)
      case address@(_: DnsHostname) => (5, address)
    }.sortBy(_._1).map(_._2)
    val tlvs = TlvStream(Set(fundingRates_opt.map(LegacyNodeAnnouncementTlv.OptionWillFund)).flatten[LegacyNodeAnnouncementTlv])
    val witness = nodeAnnouncementWitnessEncode(timestamp, nodeSecret.publicKey, color, alias, features.unscoped(), sortedAddresses, tlvs)
    val sig = Crypto.sign(witness, nodeSecret)
    LegacyNodeAnnouncement(
      signature = sig,
      timestamp = timestamp,
      nodeId = nodeSecret.publicKey,
      rgbColor = color,
      alias = alias,
      features = features.unscoped(),
      addresses = sortedAddresses,
      tlvStream = tlvs
    )
  }

  def makeNodeAnnouncement2(nodeSecret: PrivateKey, currentBlockHeight: BlockHeight, alias: String, color: Color, nodeAddresses: List[NodeAddress], features: Features[NodeFeature]): ModernNodeAnnouncement = {
    // TODO: add liquidity funding rates
    val sortedAddresses = nodeAddresses.map {
      case address@(_: IPv4) => (1, address)
      case address@(_: IPv6) => (2, address)
      case address@(_: Tor2) => (3, address)
      case address@(_: Tor3) => (4, address)
      case address@(_: DnsHostname) => (5, address)
    }.sortBy(_._1).map(_._2)
    val ann = ModernNodeAnnouncement(nodeSecret.publicKey, currentBlockHeight, sortedAddresses, features.unscoped(), ByteVector64.Zeroes, Some(alias), Some(color))
    val witness = msgHash("node_announcement_2", "signature", nodeAnnouncementWitnessEncode(ann))
    val sig = Crypto.signSchnorr(witness, nodeSecret)
    ModernNodeAnnouncement(nodeSecret.publicKey, currentBlockHeight, sortedAddresses, features.unscoped(), sig, Some(alias), Some(color))
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
  def areSame(u1: LegacyChannelUpdate, u2: LegacyChannelUpdate): Boolean =
    u1.copy(signature = ByteVector64.Zeroes, timestamp = 0 unixsec) == u2.copy(signature = ByteVector64.Zeroes, timestamp = 0 unixsec)

  def areSameRelayParams(u1: LegacyChannelUpdate, u2: LegacyChannelUpdate): Boolean =
    u1.feeBaseMsat == u2.feeBaseMsat &&
      u1.feeProportionalMillionths == u2.feeProportionalMillionths &&
      u1.cltvExpiryDelta == u2.cltvExpiryDelta &&
      u1.htlcMinimumMsat == u2.htlcMinimumMsat &&
      u1.htlcMaximumMsat == u2.htlcMaximumMsat

  def makeChannelUpdate(chainHash: BlockHash, nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: ShortChannelId, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi, isPrivate: Boolean = false, enable: Boolean = true, timestamp: TimestampSecond = TimestampSecond.now()): LegacyChannelUpdate = {
    val messageFlags = LegacyChannelUpdate.MessageFlags(isPrivate)
    val channelFlags = LegacyChannelUpdate.ChannelFlags(isNode1 = isNode1(nodeSecret.publicKey, remoteNodeId), isEnabled = enable)
    val witness = channelUpdateWitnessEncode(chainHash, shortChannelId, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat, TlvStream.empty)
    val sig = Crypto.sign(witness, nodeSecret)
    LegacyChannelUpdate(
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

  def makeChannelUpdate2(chainHash: BlockHash, currentBlockHeight: BlockHeight, nodeSecret: PrivateKey, remoteNodeId: PublicKey, shortChannelId: ShortChannelId, cltvExpiryDelta: CltvExpiryDelta, htlcMinimumMsat: MilliSatoshi, feeBaseMsat: MilliSatoshi, feeProportionalMillionths: Long, htlcMaximumMsat: MilliSatoshi): ModernChannelUpdate = {
    // TODO: handle disable/enable/direction
    val update = ModernChannelUpdate(chainHash, shortChannelId, currentBlockHeight, isNode1(nodeSecret.publicKey, remoteNodeId), cltvExpiryDelta, feeBaseMsat, feeProportionalMillionths, htlcMinimumMsat, htlcMaximumMsat, ByteVector64.Zeroes)
    val witness = msgHash("channel_update_2", "signature", channelUpdateWitnessEncode(update))
    val sig = Crypto.signSchnorr(witness, nodeSecret)
    ModernChannelUpdate(chainHash, shortChannelId, currentBlockHeight, isNode1(nodeSecret.publicKey, remoteNodeId), cltvExpiryDelta, feeBaseMsat, feeProportionalMillionths, htlcMinimumMsat, htlcMaximumMsat, sig)
  }

  def updateScid(nodeSecret: PrivateKey, u: LegacyChannelUpdate, scid: ShortChannelId): LegacyChannelUpdate = {
    // NB: we don't update the timestamp as we're not changing any parameter.
    val u1 = u.copy(shortChannelId = scid)
    val witness = channelUpdateWitnessEncode(u.chainHash, scid, u.timestamp, u.messageFlags, u.channelFlags, u.cltvExpiryDelta, u.htlcMinimumMsat, u.feeBaseMsat, u.feeProportionalMillionths, u.htlcMaximumMsat, u.tlvStream)
    val sig = Crypto.sign(witness, nodeSecret)
    u1.copy(signature = sig)
  }

  def checkSigs(ann: LegacyChannelAnnouncement): Boolean = {
    val witness = channelAnnouncementWitnessEncode(ann.chainHash, ann.shortChannelId, ann.nodeId1, ann.nodeId2, ann.bitcoinKey1, ann.bitcoinKey2, ann.features, ann.tlvStream)
    verifySignature(witness, ann.nodeSignature1, ann.nodeId1) &&
      verifySignature(witness, ann.nodeSignature2, ann.nodeId2) &&
      verifySignature(witness, ann.bitcoinSignature1, ann.bitcoinKey1) &&
      verifySignature(witness, ann.bitcoinSignature2, ann.bitcoinKey2)
  }

  def checkSig(ann: ModernChannelAnnouncement, txOut: TxOut): Boolean = {
    Script.pay2trOutputKey(txOut.publicKeyScript) match {
      case Some(outputKey) =>
        val witness = msgHash("channel_announcement_2", "signature", channelAnnouncementWitnessEncode(ann))
        (ann.bitcoinKey1_opt, ann.bitcoinKey2_opt, ann.rootHash_opt) match {
          case (None, None, _) =>
            val signingKey = Musig2.aggregateKeys(Scripts.sort(Seq(ann.nodeId1, ann.nodeId2, outputKey.publicKey)))
            verifySignatureSchnorr(witness, ann.signature, signingKey)
          case (Some(bitcoinKey1), Some(bitcoinKey2), None) =>
            val aggOut = Musig2.aggregateKeys(Scripts.sort(Seq(bitcoinKey1, bitcoinKey2)))
            val signingKey = Musig2.aggregateKeys(Scripts.sort(Seq(ann.nodeId1, ann.nodeId2, bitcoinKey1, bitcoinKey2)))
            aggOut == outputKey && verifySignatureSchnorr(witness, ann.signature, signingKey)
          case (Some(bitcoinKey1), Some(bitcoinKey2), Some(rootHash)) =>
            val expectedPubkeyScript = Script.write(Script.pay2tr(Musig2.aggregateKeys(Scripts.sort(Seq(bitcoinKey1, bitcoinKey2))), rootHash))
            val signingKey = Musig2.aggregateKeys(Scripts.sort(Seq(ann.nodeId1, ann.nodeId2, bitcoinKey1, bitcoinKey2)))
            expectedPubkeyScript == txOut.publicKeyScript && verifySignatureSchnorr(witness, ann.signature, signingKey)
          case _ => false
        }
      // We don't support announcing non-taproot channel announcement yet.
      case None => false
    }
  }

  def checkSig(ann: LegacyNodeAnnouncement): Boolean = {
    val witness = nodeAnnouncementWitnessEncode(ann.timestamp, ann.nodeId, ann.rgbColor, ann.alias, ann.features, ann.addresses, ann.tlvStream)
    verifySignature(witness, ann.signature, ann.nodeId)
  }

  def checkSig(ann: ModernNodeAnnouncement): Boolean = {
    val witness = msgHash("node_announcement_2", "signature", nodeAnnouncementWitnessEncode(ann))
    verifySignatureSchnorr(witness, ann.signature, ann.nodeId.xOnly)
  }

  def checkSig(upd: LegacyChannelUpdate, nodeId: PublicKey): Boolean = {
    val witness = channelUpdateWitnessEncode(upd.chainHash, upd.shortChannelId, upd.timestamp, upd.messageFlags, upd.channelFlags, upd.cltvExpiryDelta, upd.htlcMinimumMsat, upd.feeBaseMsat, upd.feeProportionalMillionths, upd.htlcMaximumMsat, upd.tlvStream)
    verifySignature(witness, upd.signature, nodeId)
  }

  def checkSig(upd: ModernChannelUpdate, nodeId: PublicKey): Boolean = {
    val witness = msgHash("channel_update_2", "signature", channelUpdateWitnessEncode(upd))
    verifySignatureSchnorr(witness, upd.signature, nodeId.xOnly)
  }
}
