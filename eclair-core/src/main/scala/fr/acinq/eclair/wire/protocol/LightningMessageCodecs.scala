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

import fr.acinq.bitcoin.scalacompat.ScriptWitness
import fr.acinq.eclair.channel.ChannelSpendSignature
import fr.acinq.eclair.wire.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.{Features, InitFeature, KamonExt}
import scodec.bits.{BinStringSyntax, BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}

/**
 * Created by PM on 15/11/2016.
 */
object LightningMessageCodecs {

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
    ("chainHash" | blockHash) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingSatoshis" | satoshi) ::
      ("pushMsat" | millisatoshi) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) :: // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
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

  val openDualFundedChannelCodec: Codec[OpenDualFundedChannel] = (
    ("chainHash" | blockHash) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingFeerate" | feeratePerKw) ::
      ("commitmentFeerate" | feeratePerKw) ::
      ("fundingAmount" | satoshi) ::
      ("dustLimit" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) :: // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
      ("htlcMinimumMsat" | millisatoshi) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("lockTime" | uint32) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("secondPerCommitmentPoint" | publicKey) ::
      ("channelFlags" | channelflags) ::
      ("tlvStream" | OpenDualFundedChannelTlv.openTlvCodec)).as[OpenDualFundedChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | bytes32) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) :: // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
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

  val acceptDualFundedChannelCodec: Codec[AcceptDualFundedChannel] = (
    ("temporaryChannelId" | bytes32) ::
      ("fundingAmount" | satoshi) ::
      ("dustLimit" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) :: // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
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
      ("secondPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | AcceptDualFundedChannelTlv.acceptTlvCodec)).as[AcceptDualFundedChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | bytes32) ::
      ("fundingTxHash" | txIdAsHash) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | bytes64) ::
      ("tlvStream" | FundingCreatedTlv.fundingCreatedTlvCodec)).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("tlvStream" | FundingSignedTlv.fundingSignedTlvCodec)).as[FundingSigned]

  val channelReadyCodec: Codec[ChannelReady] = (
    ("channelId" | bytes32) ::
      ("nextPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | ChannelReadyTlv.channelReadyTlvCodec)).as[ChannelReady]

  val txAddInputCodec: Codec[TxAddInput] = (
    ("channelId" | bytes32) ::
      ("serialId" | uint64) ::
      ("previousTx" | variableSizeBytes(uint16, optional(bitsRemaining, txCodec))) ::
      ("previousTxOutput" | uint32) ::
      ("sequence" | uint32) ::
      ("tlvStream" | TxAddInputTlv.txAddInputTlvCodec)).as[TxAddInput]

  val txAddOutputCodec: Codec[TxAddOutput] = (
    ("channelId" | bytes32) ::
      ("serialId" | uint64) ::
      ("amount" | satoshi) ::
      ("scriptPubKey" | variableSizeBytes(uint16, bytes)) ::
      ("tlvStream" | TxAddOutputTlv.txAddOutputTlvCodec)).as[TxAddOutput]

  val txRemoveInputCodec: Codec[TxRemoveInput] = (
    ("channelId" | bytes32) ::
      ("serialId" | uint64) ::
      ("tlvStream" | TxRemoveInputTlv.txRemoveInputTlvCodec)).as[TxRemoveInput]

  val txRemoveOutputCodec: Codec[TxRemoveOutput] = (
    ("channelId" | bytes32) ::
      ("serialId" | uint64) ::
      ("tlvStream" | TxRemoveOutputTlv.txRemoveOutputTlvCodec)).as[TxRemoveOutput]

  val txCompleteCodec: Codec[TxComplete] = (
    ("channelId" | bytes32) ::
      ("tlvStream" | TxCompleteTlv.txCompleteTlvCodec)).as[TxComplete]

  private val witnessCodec: Codec[ScriptWitness] = bytes.xmap(b => ScriptWitness.read(b.toArray), w => ScriptWitness.write(w))
  private val witnessesCodec: Codec[Seq[ScriptWitness]] = listOfN(uint16, variableSizeBytes(uint16, witnessCodec)).xmap(l => l.toSeq, l => l.toList)

  val txSignaturesCodec: Codec[TxSignatures] = (
    ("channelId" | bytes32) ::
      ("txHash" | txIdAsHash) ::
      ("witnesses" | witnessesCodec) ::
      ("tlvStream" | TxSignaturesTlv.txSignaturesTlvCodec)).as[TxSignatures]

  val txInitRbfCodec: Codec[TxInitRbf] = (
    ("channelId" | bytes32) ::
      ("lockTime" | uint32) ::
      ("feerate" | feeratePerKw) ::
      ("tlvStream" | TxInitRbfTlv.txInitRbfTlvCodec)).as[TxInitRbf]

  val txAckRbfCodec: Codec[TxAckRbf] = (
    ("channelId" | bytes32) ::
      ("tlvStream" | TxAckRbfTlv.txAckRbfTlvCodec)).as[TxAckRbf]

  val txAbortCodec: Codec[TxAbort] = (
    ("channelId" | bytes32) ::
      ("data" | variableSizeBytes(uint16, bytes)) ::
      ("tlvStream" | TxAbortTlv.txAbortTlvCodec)).as[TxAbort]

  val shutdownCodec: Codec[Shutdown] = (
    ("channelId" | bytes32) ::
      ("scriptPubKey" | varsizebinarydata) ::
      ("tlvStream" | ShutdownTlv.shutdownTlvCodec)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | bytes32) ::
      ("feeSatoshis" | satoshi) ::
      ("signature" | bytes64) ::
      ("tlvStream" | ClosingSignedTlv.closingSignedTlvCodec)).as[ClosingSigned]

  val closingCompleteCodec: Codec[ClosingComplete] = (
    ("channelId" | bytes32) ::
      ("closerScriptPubKey" | varsizebinarydata) ::
      ("closeeScriptPubKey" | varsizebinarydata) ::
      ("fees" | satoshi) ::
      ("lockTime" | uint32) ::
      ("tlvStream" | ClosingCompleteTlv.closingCompleteTlvCodec)).as[ClosingComplete]

  val closingSigCodec: Codec[ClosingSig] = (
    ("channelId" | bytes32) ::
      ("closerScriptPubKey" | varsizebinarydata) ::
      ("closeeScriptPubKey" | varsizebinarydata) ::
      ("fees" | satoshi) ::
      ("lockTime" | uint32) ::
      ("tlvStream" | ClosingSigTlv.closingSigTlvCodec)).as[ClosingSig]

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
      ("signature" | bytes64.as[ChannelSpendSignature.IndividualSignature]) ::
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
      ("shortChannelId" | realshortchannelid) ::
      ("nodeSignature" | bytes64) ::
      ("bitcoinSignature" | bytes64) ::
      ("tlvStream" | AnnouncementSignaturesTlv.announcementSignaturesTlvCodec)).as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec =
    ("features" | lengthPrefixedFeaturesCodec) ::
      ("chainHash" | blockHash) ::
      ("shortChannelId" | realshortchannelid) ::
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
    ("features" | lengthPrefixedFeaturesCodec) ::
      ("timestamp" | timestampSecond) ::
      ("nodeId" | publicKey) ::
      ("rgbColor" | rgb) ::
      ("alias" | zeropaddedstring(32)) ::
      ("addresses" | listofnodeaddresses) ::
      ("tlvStream" | NodeAnnouncementTlv.nodeAnnouncementTlvCodec)

  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = (
    ("signature" | bytes64) ::
      nodeAnnouncementWitnessCodec).as[NodeAnnouncement]

  val messageFlagsCodec = ("messageFlags" | (ignore(6) :: bool :: constant(bin"1"))).as[ChannelUpdate.MessageFlags]

  val reverseBool: Codec[Boolean] = bool.xmap[Boolean](b => !b, b => !b)

  /** BOLT 7 defines a 'disable' bit and a 'direction' bit, but it's easier to understand if we take the reverse. */
  val channelFlagsCodec = ("channelFlags" | (ignore(6) :: reverseBool :: reverseBool)).as[ChannelUpdate.ChannelFlags]

  val channelUpdateChecksumCodec =
    ("chainHash" | blockHash) ::
      ("shortChannelId" | shortchannelid) ::
      messageFlagsCodec ::
      channelFlagsCodec ::
      ("cltvExpiryDelta" | cltvExpiryDelta) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("feeBaseMsat" | millisatoshi32) ::
      ("feeProportionalMillionths" | uint32) ::
      ("htlcMaximumMsat" | millisatoshi)

  val channelUpdateWitnessCodec =
    ("chainHash" | blockHash) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | timestampSecond) ::
      messageFlagsCodec ::
      channelFlagsCodec ::
      ("cltvExpiryDelta" | cltvExpiryDelta) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("feeBaseMsat" | millisatoshi32) ::
      ("feeProportionalMillionths" | uint32) ::
      ("htlcMaximumMsat" | millisatoshi) ::
      ("tlvStream" | ChannelUpdateTlv.channelUpdateTlvCodec)

  val channelUpdateCodec: Codec[ChannelUpdate] = (
    ("signature" | bytes64) ::
      channelUpdateWitnessCodec).as[ChannelUpdate]

  val encodedShortChannelIdsCodec: Codec[EncodedShortChannelIds] =
    discriminated[EncodedShortChannelIds].by(byte)
      .\(0) {
        case a@EncodedShortChannelIds(_, Nil) => a // empty list is always encoded with encoding type 'uncompressed' for compatibility with other implementations
        case a@EncodedShortChannelIds(EncodingType.UNCOMPRESSED, _) => a
      }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(realshortchannelid)).as[EncodedShortChannelIds])
      .\(1) {
        case a@EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, _) => a
      }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(realshortchannelid))).as[EncodedShortChannelIds])

  val queryShortChannelIdsCodec: Codec[QueryShortChannelIds] = (
    ("chainHash" | blockHash) ::
      ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
      ("tlvStream" | QueryShortChannelIdsTlv.codec)).as[QueryShortChannelIds]

  val replyShortChannelIdsEndCodec: Codec[ReplyShortChannelIdsEnd] = (
    ("chainHash" | blockHash) ::
      ("complete" | byte) ::
      ("tlvStream" | ReplyShortChannelIdsEndTlv.replyShortChannelIdsEndTlvCodec)).as[ReplyShortChannelIdsEnd]

  val queryChannelRangeCodec: Codec[QueryChannelRange] = (
    ("chainHash" | blockHash) ::
      ("firstBlock" | blockHeight) ::
      ("numberOfBlocks" | uint32) ::
      ("tlvStream" | QueryChannelRangeTlv.codec)).as[QueryChannelRange]

  val replyChannelRangeCodec: Codec[ReplyChannelRange] = (
    ("chainHash" | blockHash) ::
      ("firstBlock" | blockHeight) ::
      ("numberOfBlocks" | uint32) ::
      ("complete" | byte) ::
      ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
      ("tlvStream" | ReplyChannelRangeTlv.codec)).as[ReplyChannelRange]

  val gossipTimestampFilterCodec: Codec[GossipTimestampFilter] = (
    ("chainHash" | blockHash) ::
      ("firstTimestamp" | timestampSecond) ::
      ("timestampRange" | uint32) ::
      ("tlvStream" | GossipTimestampFilterTlv.gossipTimestampFilterTlvCodec)).as[GossipTimestampFilter]

  val onionMessageCodec: Codec[OnionMessage] = (
    ("pathKey" | publicKey) ::
      ("onionPacket" | MessageOnionCodecs.messageOnionPacketCodec) ::
      ("tlvStream" | OnionMessageTlv.onionMessageTlvCodec)).as[OnionMessage]

  val peerStorageStore: Codec[PeerStorageStore] = (
    ("blob" | variableSizeBytes(uint16, bytes)) ::
      ("tlvStream" | PeerStorageTlv.peerStorageTlvCodec)).as[PeerStorageStore]

  val peerStorageRetrieval: Codec[PeerStorageRetrieval] = (
    ("blob" | variableSizeBytes(uint16, bytes)) ::
      ("tlvStream" | PeerStorageTlv.peerStorageTlvCodec)).as[PeerStorageRetrieval]

  // NB: blank lines to minimize merge conflicts

  //

  //

  //

  //

  //

  //
  val spliceInitCodec: Codec[SpliceInit] = (
    ("channelId" | bytes32) ::
      ("fundingContribution" | satoshiSigned) ::
      ("feerate" | feeratePerKw) ::
      ("lockTime" | uint32) ::
      ("fundingPubkey" | publicKey) ::
      ("tlvStream" | SpliceInitTlv.spliceInitTlvCodec)).as[SpliceInit]

  val spliceAckCodec: Codec[SpliceAck] = (
    ("channelId" | bytes32) ::
      ("fundingContribution" | satoshiSigned) ::
      ("fundingPubkey" | publicKey) ::
      ("tlvStream" | SpliceAckTlv.spliceAckTlvCodec)).as[SpliceAck]

  val spliceLockedCodec: Codec[SpliceLocked] = (
    ("channelId" | bytes32) ::
      ("fundingTxHash" | txIdAsHash) ::
      ("tlvStream" | SpliceLockedTlv.spliceLockedTlvCodec)).as[SpliceLocked]

  val stfuCodec: Codec[Stfu] = (
    ("channelId" | bytes32) ::
      ("initiator" | byte.xmap[Boolean](b => b != 0, b => if (b) 1 else 0))).as[Stfu]

  //

  //

  val recommendedFeeratesCodec: Codec[RecommendedFeerates] = (
    ("chainHash" | blockHash) ::
      ("fundingFeerate" | feeratePerKw) ::
      ("commitmentFeerate" | feeratePerKw) ::
      ("tlvStream" | RecommendedFeeratesTlv.recommendedFeeratesTlvCodec)).as[RecommendedFeerates]

  val willAddHtlcCodec: Codec[WillAddHtlc] = (
    ("chainHash" | blockHash) ::
      ("id" | bytes32) ::
      ("amount" | millisatoshi) ::
      ("paymentHash" | bytes32) ::
      ("expiry" | cltvExpiry) ::
      ("onionRoutingPacket" | PaymentOnionCodecs.paymentOnionPacketCodec) ::
      ("tlvStream" | WillAddHtlcTlv.willAddHtlcTlvCodec)).as[WillAddHtlc]

  val willFailHtlcCodec: Codec[WillFailHtlc] = (
    ("id" | bytes32) ::
      ("paymentHash" | bytes32) ::
      ("reason" | varsizebinarydata) ::
      ("tlvStream" | UpdateFailHtlcTlv.updateFailHtlcTlvCodec)).as[WillFailHtlc]

  val willFailMalformedHtlcCodec: Codec[WillFailMalformedHtlc] = (
    ("id" | bytes32) ::
      ("paymentHash" | bytes32) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16)).as[WillFailMalformedHtlc]

  val cancelOnTheFlyFundingCodec: Codec[CancelOnTheFlyFunding] = (
    ("channelId" | bytes32) ::
      ("paymentHashes" | listOfN(uint16, bytes32)) ::
      ("reason" | varsizebinarydata)).as[CancelOnTheFlyFunding]

  val addFeeCreditCodec: Codec[AddFeeCredit] = (
    ("chainHash" | blockHash) ::
      ("preimage" | bytes32)).as[AddFeeCredit]

  val currentFeeCreditCodec: Codec[CurrentFeeCredit] = (
    ("chainHash" | blockHash) ::
      ("amount" | millisatoshi)).as[CurrentFeeCredit]

  val unknownMessageCodec: Codec[UnknownMessage] = (
    ("tag" | uint16) ::
      ("message" | bytes)
    ).as[UnknownMessage]

  val lightningMessageCodec = catchAllCodec(discriminated[LightningMessage].by(uint16)
    .typecase(1, warningCodec)
    .typecase(2, stfuCodec)
    .typecase(7, peerStorageStore)
    .typecase(9, peerStorageRetrieval)
    .typecase(16, initCodec)
    .typecase(17, errorCodec)
    .typecase(18, pingCodec)
    .typecase(19, pongCodec)
    .typecase(32, openChannelCodec)
    .typecase(33, acceptChannelCodec)
    .typecase(34, fundingCreatedCodec)
    .typecase(35, fundingSignedCodec)
    .typecase(36, channelReadyCodec)
    .typecase(38, shutdownCodec)
    .typecase(39, closingSignedCodec)
    .typecase(40, closingCompleteCodec)
    .typecase(41, closingSigCodec)
    .typecase(64, openDualFundedChannelCodec)
    .typecase(65, acceptDualFundedChannelCodec)
    .typecase(66, txAddInputCodec)
    .typecase(67, txAddOutputCodec)
    .typecase(68, txRemoveInputCodec)
    .typecase(69, txRemoveOutputCodec)
    .typecase(70, txCompleteCodec)
    .typecase(71, txSignaturesCodec)
    .typecase(72, txInitRbfCodec)
    .typecase(73, txAckRbfCodec)
    .typecase(74, txAbortCodec)
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
    .typecase(262, replyShortChannelIdsEndCodec)
    .typecase(263, queryChannelRangeCodec)
    .typecase(264, replyChannelRangeCodec)
    .typecase(265, gossipTimestampFilterCodec)
    .typecase(513, onionMessageCodec)
    // NB: blank lines to minimize merge conflicts

    //
    //
    .typecase(41041, willAddHtlcCodec)
    .typecase(41042, willFailHtlcCodec)
    .typecase(41043, willFailMalformedHtlcCodec)
    .typecase(41044, cancelOnTheFlyFundingCodec)
    //
    //
    .typecase(41045, addFeeCreditCodec)
    .typecase(41046, currentFeeCreditCodec)
    //
    .typecase(37000, spliceInitCodec)
    .typecase(37002, spliceAckCodec)
    .typecase(37004, spliceLockedCodec)
    //

    //
    .typecase(39409, recommendedFeeratesCodec)
  //

  //

  //

  //

  //
  )

  val lightningMessageCodecWithFallback: Codec[LightningMessage] = discriminatorWithDefault(lightningMessageCodec, catchAllCodec(unknownMessageCodec.upcast))

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
