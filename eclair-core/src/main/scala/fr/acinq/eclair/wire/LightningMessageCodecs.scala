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

import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.{KamonExt, wire}
import kamon.Kamon
import kamon.tag.TagSet
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult}

import scodec.bits._

/**
 * Created by PM on 15/11/2016.
 */
object LightningMessageCodecs {

  /** For historical reasons, features are divided into two feature bitmasks. We only send from the second one, but we allow receiving in both. */
  val combinedFeaturesCodec: Codec[ByteVector] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[(ByteVector, ByteVector)].xmap[ByteVector](
    { case (gf, lf) =>
      val length = gf.length.max(lf.length)
      gf.padLeft(length) | lf.padLeft(length)
    },
    { features => (ByteVector.empty, features) })

  val initCodec: Codec[Init] = (("features" | combinedFeaturesCodec) :: ("tlvStream" | InitTlvCodecs.initTlvCodec)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | bytes32) ::
      ("data" | varsizebinarydata)).as[Error]

  val pingCodec: Codec[Ping] = (
    ("pongLength" | uint16) ::
      ("data" | varsizebinarydata)).as[Ping]

  val pongCodec: Codec[Pong] =
    ("data" | varsizebinarydata).as[Pong]

  // this magic is used because not all fields are length-protected when we store channel data :-/
  // TODO: @pm47: we use a particular magic and encoding in order to have the same encoding as TLV and be future proof
  val magic: Codec[Boolean] = recover(constant(hex"fe 47010000"))

  // we have limited space for backup, largest message is commit_sig with 30 htlcs in each direction: 65535B - (32B + 64B + 2*30*64B) = 61599B ~= 60000B
  val channeldataoptional: Codec[Option[ByteVector]] = choice(optional(magic, limitedSizeBytes( 60000, variableSizeBytesLong(varintoverflow, bytes))), provide(Option.empty[ByteVector]))

  val channelReestablishCodec: Codec[ChannelReestablish] = (
    ("channelId" | bytes32) ::
      ("nextLocalCommitmentNumber" | uint64overflow) ::
      ("nextRemoteRevocationNumber" | uint64overflow) ::
      ("yourLastPerCommitmentSecret" | optional(bitsRemaining, privateKey)) ::
      ("myCurrentPerCommitmentPoint" | optional(bitsRemaining, publicKey)) ::
      ("channelData" | channeldataoptional)).as[ChannelReestablish]

  // Legacy nodes may encode an empty upfront_shutdown_script (0x0000) even if we didn't advertise support for option_upfront_shutdown_script.
  // To allow extending all messages with TLV streams, the upfront_shutdown_script field was made mandatory in https://github.com/lightningnetwork/lightning-rfc/pull/714.
  // This codec decodes both legacy and new versions, while always encoding with an upfront_shutdown_script (of length 0 if none actually provided).
  private val shutdownScriptGuard = Codec[Boolean](
    // Similar to bitsRemaining but encodes 0x0000 for an empty upfront_shutdown_script.
    (included: Boolean) => if (included) Attempt.Successful(BitVector.empty) else Attempt.Successful(hex"0000".bits),
    // Bolt 2 specifies that upfront_shutdown_scripts must be P2PKH/P2SH or segwit-v0 P2WPK/P2WSH.
    // The length of such scripts will always start with 0x00.
    // On top of that, since TLV records start with a varint, a TLV stream will never start with 0x00 unless the spec
    // assigns TLV type 0 to a new record. If that happens, that record should be the upfront_shutdown_script to allow
    // easy backwards-compatibility (as proposed here: https://github.com/lightningnetwork/lightning-rfc/pull/714).
    // That means we can discriminate on byte 0x00 to know whether we're decoding an upfront_shutdown_script or a TLV
    // stream.
    (b: BitVector) => Attempt.successful(DecodeResult(b.startsWith(hex"00".bits), b))
  )

  private def emptyToNone(script: Option[ByteVector]): Option[ByteVector] = script match {
    case Some(s) if s.nonEmpty => script
    case _ => None
  }

  private val upfrontShutdownScript = optional(shutdownScriptGuard, variableSizeBytes(uint16, bytes)).xmap(emptyToNone, emptyToNone)

  private def openChannelCodec_internal(upfrontShutdownScriptCodec: Codec[Option[ByteVector]]): Codec[OpenChannel] = (
    ("chainHash" | bytes32) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingSatoshis" | satoshi) ::
      ("pushMsat" | millisatoshi) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("feeratePerKw" | uint32) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("channelFlags" | byte) ::
      ("upfront_shutdown_script" | upfrontShutdownScriptCodec) ::
      ("tlvStream_opt" | optional(bitsRemaining, OpenTlv.openTlvCodec))).as[OpenChannel]

  val openChannelCodec = Codec[OpenChannel](
    (open: OpenChannel) => {
      // Phoenix versions <= 1.1.0 don't support the upfront_shutdown_script field (they interpret it as a tlv stream
      // with an unknown tlv record). For these channels we use an encoding that omits the upfront_shutdown_script for
      // backwards-compatibility (once enough Phoenix users have upgraded, we can remove work-around).
      val upfrontShutdownScriptCodec = if (open.tlvStream_opt.isDefined) provide(Option.empty[ByteVector]) else upfrontShutdownScript
      openChannelCodec_internal(upfrontShutdownScriptCodec).encode(open)
    },
    (bits: BitVector) => openChannelCodec_internal(upfrontShutdownScript).decode(bits)
  )

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
      ("upfront_shutdown_script" | upfrontShutdownScript)).as[AcceptChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | bytes32) ::
      ("fundingTxid" | bytes32) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | bytes64)).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("channelData" | channeldataoptional)).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("channelId" | bytes32) ::
      ("nextPerCommitmentPoint" | publicKey)).as[FundingLocked]

  val shutdownCodec: Codec[wire.Shutdown] = (
    ("channelId" | bytes32) ::
      ("scriptPubKey" | varsizebinarydata) ::
      ("channelData" | channeldataoptional)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | bytes32) ::
      ("feeSatoshis" | satoshi) ::
      ("signature" | bytes64) ::
      ("channelData" | channeldataoptional)).as[ClosingSigned]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("amountMsat" | millisatoshi) ::
      ("paymentHash" | bytes32) ::
      ("expiry" | cltvExpiry) ::
      ("onionRoutingPacket" | OnionCodecs.paymentOnionPacketCodec)).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("paymentPreimage" | bytes32)).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("reason" | varsizebinarydata)).as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec: Codec[UpdateFailMalformedHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16)).as[UpdateFailMalformedHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("htlcSignatures" | listofsignatures) ::
      ("channelData" | channeldataoptional)).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | bytes32) ::
      ("perCommitmentSecret" | privateKey) ::
      ("nextPerCommitmentPoint" | publicKey) ::
      ("channelData" | channeldataoptional)).as[RevokeAndAck]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | bytes32) ::
      ("feeratePerKw" | uint32)).as[UpdateFee]

  val announcementSignaturesCodec: Codec[AnnouncementSignatures] = (
    ("channelId" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeSignature" | bytes64) ::
      ("bitcoinSignature" | bytes64)).as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec =
    ("features" | varsizebinarydata) ::
      ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("bitcoinKey1" | publicKey) ::
      ("bitcoinKey2" | publicKey) ::
      ("unknownFields" | bytes)

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] = (
    ("nodeSignature1" | bytes64) ::
      ("nodeSignature2" | bytes64) ::
      ("bitcoinSignature1" | bytes64) ::
      ("bitcoinSignature2" | bytes64) ::
      channelAnnouncementWitnessCodec).as[ChannelAnnouncement]

  val nodeAnnouncementWitnessCodec =
    ("features" | varsizebinarydata) ::
      ("timestamp" | uint32) ::
      ("nodeId" | publicKey) ::
      ("rgbColor" | rgb) ::
      ("alias" | zeropaddedstring(32)) ::
      ("addresses" | listofnodeaddresses) ::
      ("unknownFields" | bytes)

  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = (
    ("signature" | bytes64) ::
      nodeAnnouncementWitnessCodec).as[NodeAnnouncement]

  val channelUpdateChecksumCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, millisatoshi))
      })

  val channelUpdateWitnessCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | uint32) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, millisatoshi)) ::
          ("unknownFields" | bytes)
      })

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


  val queryShortChannelIdsCodec: Codec[QueryShortChannelIds] = {
    Codec(
      ("chainHash" | bytes32) ::
        ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
        ("tlvStream" | QueryShortChannelIdsTlv.codec)
    ).as[QueryShortChannelIds]
  }

  val replyShortChanelIdsEndCodec: Codec[ReplyShortChannelIdsEnd] = (
    ("chainHash" | bytes32) ::
      ("complete" | byte)
    ).as[ReplyShortChannelIdsEnd]

  val queryChannelRangeCodec: Codec[QueryChannelRange] = {
    Codec(
      ("chainHash" | bytes32) ::
        ("firstBlockNum" | uint32) ::
        ("numberOfBlocks" | uint32) ::
        ("tlvStream" | QueryChannelRangeTlv.codec)
    ).as[QueryChannelRange]
  }

  val replyChannelRangeCodec: Codec[ReplyChannelRange] = {
    Codec(
      ("chainHash" | bytes32) ::
        ("firstBlockNum" | uint32) ::
        ("numberOfBlocks" | uint32) ::
        ("complete" | byte) ::
        ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
        ("tlvStream" | ReplyChannelRangeTlv.codec)
    ).as[ReplyChannelRange]
  }

  val gossipTimestampFilterCodec: Codec[GossipTimestampFilter] = (
    ("chainHash" | bytes32) ::
      ("firstTimestamp" | uint32) ::
      ("timestampRange" | uint32)
    ).as[GossipTimestampFilter]

  // NB: blank lines to minimize merge conflicts
  val payToOpenRequestCodec: Codec[PayToOpenRequest] = (
    ("chainHash" | bytes32) ::
      ("fundingSatoshis" | satoshi) ::
      ("pushMsat" | millisatoshi) ::
      ("feeSatoshis" | satoshi) ::
      ("paymentHash" | bytes32) ::
      ("feeThresholdSatoshis" | satoshi) ::
      ("feeProportionalMillionths" | uint32) ::
      ("expireAt" | uint32) ::
      ("htlc_opt" | optional(bool(8), updateAddHtlcCodec))).as[PayToOpenRequest]

  val payToOpenResponseCodec: Codec[PayToOpenResponse] = (
    ("chainHash" | bytes32) ::
      ("paymentHash" | bytes32) ::
      ("paymentPreimage" | bytes32)).as[PayToOpenResponse]
  //
  val swapInRequestCodec: Codec[SwapInRequest] =
    ("channelId" | bytes32).as[SwapInRequest]

  val swapInResponseCodec: Codec[SwapInResponse] = (
    ("channelId" | bytes32) ::
      ("bitcoinAddress" | variableSizeBytes(uint16, utf8))
    ).as[SwapInResponse]

  val swapInPendingCodec: Codec[SwapInPending] = (
    ("bitcoinAddress" | variableSizeBytes(uint16, utf8)) ::
      ("amount" | satoshi)
    ).as[SwapInPending]

  val swapInConfirmedCodec: Codec[SwapInConfirmed] = (
    ("bitcoinAddress" | variableSizeBytes(uint16, utf8)) ::
      ("amount" | millisatoshi)
    ).as[SwapInConfirmed]
  //
  val swapOutRequestCodec: Codec[SwapOutRequest] = (
    ("chainHash" | bytes32) ::
      ("amountSatoshis" | satoshi) ::
      ("bitcoinAddress" | variableSizeBytes(uint16, utf8)) ::
      ("feeratePerKw" | uint32)
    ).as[SwapOutRequest]

  val swapOutResponseCodec: Codec[SwapOutResponse] = (
    ("chainHash" | bytes32) ::
      ("amountSatoshis" | satoshi) ::
      ("feeSatoshis" | satoshi) ::
      ("paymentRequest" | variableSizeBytes(uint16, utf8))
    ).as[SwapOutResponse]
  //

  //

  //

  val lightningMessageCodec = discriminated[LightningMessage].by(uint16)
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
  // NB: blank lines to minimize merge conflicts
    .typecase(35001, payToOpenRequestCodec)
    .typecase(35003, payToOpenResponseCodec)
  //
    .typecase(35005, swapInPendingCodec)
    .typecase(35007, swapInRequestCodec)
    .typecase(35009, swapInResponseCodec)
    .typecase(35015, swapInConfirmedCodec)
  //
    .typecase(35011, swapOutRequestCodec)
    .typecase(35013, swapOutResponseCodec)
  //

  //

  //

  val meteredLightningMessageCodec = Codec[LightningMessage](
    (msg: LightningMessage) => KamonExt.time("scodec.encode.time", tags = TagSet.of("type", msg.getClass.getSimpleName))(lightningMessageCodec.encode(msg)),
    (bits: BitVector) => {
      // this is a bit more involved, because we don't know beforehand what the type of the message will be
      val timer = Kamon.timer("scodec.decode.time")
      val begin = System.nanoTime()
      val res = lightningMessageCodec.decode(bits)
      val end = System.nanoTime()
      res match {
        case Attempt.Successful(decoded) => timer.withTag("type", decoded.value.getClass.getSimpleName).record(end - begin)
        case Attempt.Failure(_) => timer.withTag("type", "unknown").record(end - begin)
      }
      res
    }
  )

}