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

import fr.acinq.eclair.wire.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.{FeatureScope, Features, InitFeature, KamonExt, NodeFeature}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}
import shapeless._

/**
 * Created by PM on 15/11/2016.
 */
object LightningMessageCodecs {

  val featuresCodec: Codec[Features[FeatureScope]] = varsizebinarydata.xmap[Features[FeatureScope]](
    { bytes => Features(bytes) },
    { features => features.toByteVector }
  )

  /** For historical reasons, features are divided into two feature bitmasks. We only send from the second one, but we allow receiving in both. */
  val combinedFeaturesCodec: Codec[Features[InitFeature]] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[(ByteVector, ByteVector)].xmap[Features[InitFeature]](
    { case (gf, lf) =>
      val length = gf.length.max(lf.length)
      Features(gf.padLeft(length) | lf.padLeft(length)).initFeatures()
    },
    { features => (ByteVector.empty, features.toByteVector) })

  val initCodec: Codec[Init] = (("features" | combinedFeaturesCodec) :: ("tlvStream" | InitTlvCodecs.initTlvCodec)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | bytes32) ::
      ("data" | varsizebinarydata) ::
      ("tlvStream" | ErrorTlv.errorTlvCodec)).as[Error]

  val warningCodec: Codec[Warning] = (
    ("channelId" | bytes32) ::
      ("data" | varsizebinarydata) ::
      ("tlvStream" | WarningTlv.warningTlvCodec)).as[Warning]

  val pingCodec: Codec[Ping] = (
    ("pongLength" | uint16) ::
      ("data" | varsizebinarydata) ::
      ("tlvStream" | PingTlv.pingTlvCodec)).as[Ping]

  val pongCodec: Codec[Pong] = (
    ("data" | varsizebinarydata) ::
      ("tlvStream" | PongTlv.pongTlvCodec)).as[Pong]

  val channelReestablishCodec: Codec[ChannelReestablish] = (
    ("channelId" | bytes32) ::
      ("nextLocalCommitmentNumber" | uint64overflow) ::
      ("nextRemoteRevocationNumber" | uint64overflow) ::
      ("yourLastPerCommitmentSecret" | privateKey) ::
      ("myCurrentPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | ChannelReestablishTlv.channelReestablishTlvCodec)).as[ChannelReestablish]

  val openChannelCodec: Codec[OpenChannel] = (
    ("chainHash" | bytes32) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingSatoshis" | satoshi) ::
      ("pushMsat" | millisatoshi) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("feeratePerKw" | feeratePerKw) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("channelFlags" | channelflags) ::
      ("tlvStream" | OpenChannelTlv.openTlvCodec)).as[OpenChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | bytes32) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("minimumDepth" | uint32) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | AcceptChannelTlv.acceptTlvCodec)).as[AcceptChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | bytes32) ::
      ("fundingTxid" | bytes32) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | bytes64) ::
      ("tlvStream" | FundingCreatedTlv.fundingCreatedTlvCodec)).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("tlvStream" | FundingSignedTlv.fundingSignedTlvCodec)).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("channelId" | bytes32) ::
      ("nextPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | FundingLockedTlv.fundingLockedTlvCodec)).as[FundingLocked]

  val shutdownCodec: Codec[Shutdown] = (
    ("channelId" | bytes32) ::
      ("scriptPubKey" | varsizebinarydata) ::
      ("tlvStream" | ShutdownTlv.shutdownTlvCodec)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | bytes32) ::
      ("feeSatoshis" | satoshi) ::
      ("signature" | bytes64) ::
      ("tlvStream" | ClosingSignedTlv.closingSignedTlvCodec)).as[ClosingSigned]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("amountMsat" | millisatoshi) ::
      ("paymentHash" | bytes32) ::
      ("expiry" | cltvExpiry) ::
      ("onionRoutingPacket" | PaymentOnionCodecs.paymentOnionPacketCodec) ::
      ("tlvStream" | UpdateAddHtlcTlv.addHtlcTlvCodec)).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("paymentPreimage" | bytes32) ::
      ("tlvStream" | UpdateFulfillHtlcTlv.updateFulfillHtlcTlvCodec)).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("reason" | varsizebinarydata) ::
      ("tlvStream" | UpdateFailHtlcTlv.updateFailHtlcTlvCodec)).as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec: Codec[UpdateFailMalformedHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16) ::
      ("tlvStream" | UpdateFailMalformedHtlcTlv.updateFailMalformedHtlcTlvCodec)).as[UpdateFailMalformedHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("htlcSignatures" | listofsignatures) ::
      ("tlvStream" | CommitSigTlv.commitSigTlvCodec)).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | bytes32) ::
      ("perCommitmentSecret" | privateKey) ::
      ("nextPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | RevokeAndAckTlv.revokeAndAckTlvCodec)).as[RevokeAndAck]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | bytes32) ::
      ("feeratePerKw" | feeratePerKw) ::
      ("tlvStream" | UpdateFeeTlv.updateFeeTlvCodec)).as[UpdateFee]

  val announcementSignaturesCodec: Codec[AnnouncementSignatures] = (
    ("channelId" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeSignature" | bytes64) ::
      ("bitcoinSignature" | bytes64) ::
      ("tlvStream" | AnnouncementSignaturesTlv.announcementSignaturesTlvCodec)).as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec =
    ("features" | featuresCodec) ::
      ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("bitcoinKey1" | publicKey) ::
      ("bitcoinKey2" | publicKey) ::
      ("tlvStream" | ChannelAnnouncementTlv.channelAnnouncementTlvCodec)

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] = (
    ("nodeSignature1" | bytes64) ::
      ("nodeSignature2" | bytes64) ::
      ("bitcoinSignature1" | bytes64) ::
      ("bitcoinSignature2" | bytes64) ::
      channelAnnouncementWitnessCodec).as[ChannelAnnouncement]

  val nodeAnnouncementWitnessCodec =
    ("features" | featuresCodec.xmap[Features[NodeFeature]](_.nodeAnnouncementFeatures(), _.unscoped())) ::
      ("timestamp" | timestampSecond) ::
      ("nodeId" | publicKey) ::
      ("rgbColor" | rgb) ::
      ("alias" | zeropaddedstring(32)) ::
      ("addresses" | listofnodeaddresses) ::
      ("tlvStream" | NodeAnnouncementTlv.nodeAnnouncementTlvCodec)

  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = (
    ("signature" | bytes64) ::
      nodeAnnouncementWitnessCodec).as[NodeAnnouncement]

  private case class MessageFlags(optionChannelHtlcMax: Boolean)

  private val messageFlagsCodec = ("messageFlags" | (ignore(7) :: bool)).as[MessageFlags]

  val reverseBool: Codec[Boolean] = bool.xmap[Boolean](b => !b, b => !b)

  /** BOLT 7 defines a 'disable' bit and a 'direction' bit, but it's easier to understand if we take the reverse. */
  val channelFlagsCodec = ("channelFlags" | (ignore(6) :: reverseBool :: reverseBool)).as[ChannelUpdate.ChannelFlags]

  val channelUpdateChecksumCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      (messageFlagsCodec >>:~ { messageFlags =>
        channelFlagsCodec ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional(messageFlags.optionChannelHtlcMax, millisatoshi))
      }).derive[MessageFlags].from {
        // The purpose of this is to tell scodec how to derive the message flags from the data, so we can remove that field
        // from the codec definition and the case class, making it purely a serialization detail.
        // see: https://github.com/scodec/scodec/blob/series/1.11.x/unitTests/src/test/scala/scodec/examples/ProductsExample.scala#L108-L127
        case _ :: _ :: _ :: _ :: _ :: htlcMaximumMsat_opt :: HNil => MessageFlags(optionChannelHtlcMax = htlcMaximumMsat_opt.isDefined)
      }

  val channelUpdateWitnessCodec =
    (("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | timestampSecond) ::
      (messageFlagsCodec >>:~ { messageFlags =>
        channelFlagsCodec ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional(messageFlags.optionChannelHtlcMax, millisatoshi)) ::
          ("tlvStream" | ChannelUpdateTlv.channelUpdateTlvCodec)
      })).derive[MessageFlags].from {
      // same comment above
      case _ :: _ :: _ :: _ :: _ :: _ :: _ :: _ :: htlcMaximumMsat_opt :: _ :: HNil => MessageFlags(optionChannelHtlcMax = htlcMaximumMsat_opt.isDefined)
    }

  val channelUpdateCodec: Codec[ChannelUpdate] = (
    ("signature" | bytes64) ::
      channelUpdateWitnessCodec).as[ChannelUpdate]

  val encodedShortChannelIdsCodec: Codec[EncodedShortChannelIds] =
    discriminated[EncodedShortChannelIds].by(byte)
      .\(0) {
        case a@EncodedShortChannelIds(_, Nil) => a // empty list is always encoded with encoding type 'uncompressed' for compatibility with other implementations
        case a@EncodedShortChannelIds(EncodingType.UNCOMPRESSED, _) => a
      }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(shortchannelid)).as[EncodedShortChannelIds])
      .\(1) {
        case a@EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, _) => a
      }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(shortchannelid))).as[EncodedShortChannelIds])

  val queryShortChannelIdsCodec: Codec[QueryShortChannelIds] = (
    ("chainHash" | bytes32) ::
      ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
      ("tlvStream" | QueryShortChannelIdsTlv.codec)).as[QueryShortChannelIds]

  val replyShortChanelIdsEndCodec: Codec[ReplyShortChannelIdsEnd] = (
    ("chainHash" | bytes32) ::
      ("complete" | byte) ::
      ("tlvStream" | ReplyShortChannelIdsEndTlv.replyShortChannelIdsEndTlvCodec)).as[ReplyShortChannelIdsEnd]

  val queryChannelRangeCodec: Codec[QueryChannelRange] = (
    ("chainHash" | bytes32) ::
      ("firstBlock" | blockHeight) ::
      ("numberOfBlocks" | uint32) ::
      ("tlvStream" | QueryChannelRangeTlv.codec)).as[QueryChannelRange]

  val replyChannelRangeCodec: Codec[ReplyChannelRange] = (
    ("chainHash" | bytes32) ::
      ("firstBlock" | blockHeight) ::
      ("numberOfBlocks" | uint32) ::
      ("complete" | byte) ::
      ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
      ("tlvStream" | ReplyChannelRangeTlv.codec)).as[ReplyChannelRange]

  val gossipTimestampFilterCodec: Codec[GossipTimestampFilter] = (
    ("chainHash" | bytes32) ::
      ("firstTimestamp" | timestampSecond) ::
      ("timestampRange" | uint32) ::
      ("tlvStream" | GossipTimestampFilterTlv.gossipTimestampFilterTlvCodec)).as[GossipTimestampFilter]

  val onionMessageCodec: Codec[OnionMessage] = (
    ("blindingKey" | publicKey) ::
      ("onionPacket" | MessageOnionCodecs.messageOnionPacketCodec) ::
      ("tlvStream" | OnionMessageTlv.onionMessageTlvCodec)).as[OnionMessage]

  // NB: blank lines to minimize merge conflicts

  //

  //

  //

  //

  //

  //

  //

  //

  val unknownMessageCodec: Codec[UnknownMessage] = (
    ("tag" | uint16) ::
      ("message" | varsizebinarydata)
    ).as[UnknownMessage]

  val lightningMessageCodec = discriminated[LightningMessage].by(uint16)
    .typecase(1, warningCodec)
    .typecase(16, initCodec)
    .typecase(17, errorCodec)
    .typecase(18, pingCodec)
    .typecase(19, pongCodec)
    .typecase(32, openChannelCodec)
    .typecase(33, acceptChannelCodec)
    .typecase(34, fundingCreatedCodec)
    .typecase(35, fundingSignedCodec)
    .typecase(36, fundingLockedCodec)
    .typecase(38, shutdownCodec)
    .typecase(39, closingSignedCodec)
    .typecase(128, updateAddHtlcCodec)
    .typecase(130, updateFulfillHtlcCodec)
    .typecase(131, updateFailHtlcCodec)
    .typecase(132, commitSigCodec)
    .typecase(133, revokeAndAckCodec)
    .typecase(134, updateFeeCodec)
    .typecase(135, updateFailMalformedHtlcCodec)
    .typecase(136, channelReestablishCodec)
    .typecase(256, channelAnnouncementCodec)
    .typecase(257, nodeAnnouncementCodec)
    .typecase(258, channelUpdateCodec)
    .typecase(259, announcementSignaturesCodec)
    .typecase(261, queryShortChannelIdsCodec)
    .typecase(262, replyShortChanelIdsEndCodec)
    .typecase(263, queryChannelRangeCodec)
    .typecase(264, replyChannelRangeCodec)
    .typecase(265, gossipTimestampFilterCodec)
    .typecase(513, onionMessageCodec)
  // NB: blank lines to minimize merge conflicts

  //

  //

  //

  //

  //

  //

  //

  //

  val lightningMessageCodecWithFallback: Codec[LightningMessage] =
    discriminatorWithDefault(lightningMessageCodec, unknownMessageCodec.upcast)

  val meteredLightningMessageCodec = Codec[LightningMessage](
    (msg: LightningMessage) => KamonExt.time(Metrics.EncodeDuration.withTag(Tags.MessageType, msg.getClass.getSimpleName))(lightningMessageCodecWithFallback.encode(msg)),
    (bits: BitVector) => {
      // this is a bit more involved, because we don't know beforehand what the type of the message will be
      val begin = System.nanoTime()
      val res = lightningMessageCodecWithFallback.decode(bits)
      val end = System.nanoTime()
      val messageType = res match {
        case Attempt.Successful(decoded) => decoded.value.getClass.getSimpleName
        case Attempt.Failure(_) => "unknown"
      }
      Metrics.DecodeDuration.withTag(Tags.MessageType, messageType).record(end - begin)
      res
    }
  )

}
