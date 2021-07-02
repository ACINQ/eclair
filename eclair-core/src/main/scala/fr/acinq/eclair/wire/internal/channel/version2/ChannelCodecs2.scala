/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.wire.internal.channel.version2

import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.bitcoin.{OutPoint, Transaction, TxOut}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.UpdateMessage
import scodec.codecs._
import scodec.{Attempt, Codec}

private[channel] object ChannelCodecs2 {

  private[version2] object Codecs {

    val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => new KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]

    val extendedPrivateKeyCodec: Codec[ExtendedPrivateKey] = (
      ("secretkeybytes" | bytes32) ::
        ("chaincode" | bytes32) ::
        ("depth" | uint16) ::
        ("path" | keyPathCodec) ::
        ("parent" | int64)).as[ExtendedPrivateKey]

    val channelVersionCodec: Codec[ChannelVersion] = bits(ChannelVersion.LENGTH_BITS).as[ChannelVersion]

    def localParamsCodec(channelVersion: ChannelVersion): Codec[LocalParams] = (
      ("nodeId" | publicKey) ::
        ("channelPath" | keyPathCodec) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | satoshi) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("isFunder" | bool8) ::
        ("defaultFinalScriptPubKey" | lengthDelimited(bytes)) ::
        ("walletStaticPaymentBasepoint" | optional(provide(channelVersion.paysDirectlyToWallet), publicKey)) ::
        ("features" | combinedFeaturesCodec)).as[LocalParams]

    val remoteParamsCodec: Codec[RemoteParams] = (
      ("nodeId" | publicKey) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | satoshi) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("fundingPubKey" | publicKey) ::
        ("revocationBasepoint" | publicKey) ::
        ("paymentBasepoint" | publicKey) ::
        ("delayedPaymentBasepoint" | publicKey) ::
        ("htlcBasepoint" | publicKey) ::
        ("features" | combinedFeaturesCodec)).as[RemoteParams]

    def setCodec[T](codec: Codec[T]): Codec[Set[T]] = listOfN(uint16, codec).xmap(_.toSet, _.toList)

    val htlcCodec: Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(bool8)
      .typecase(true, lengthDelimited(updateAddHtlcCodec).as[IncomingHtlc])
      .typecase(false, lengthDelimited(updateAddHtlcCodec).as[OutgoingHtlc])

    val commitmentSpecCodec: Codec[CommitmentSpec] = (
      ("htlcs" | setCodec(htlcCodec)) ::
        ("feeratePerKw" | feeratePerKw) ::
        ("toLocal" | millisatoshi) ::
        ("toRemote" | millisatoshi)).as[CommitmentSpec]

    val outPointCodec: Codec[OutPoint] = lengthDelimited(bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

    val txOutCodec: Codec[TxOut] = lengthDelimited(bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

    val txCodec: Codec[Transaction] = lengthDelimited(bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

    val inputInfoCodec: Codec[InputInfo] = (
      ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("redeemScript" | lengthDelimited(bytes))).as[InputInfo]

    val outputInfoCodec: Codec[OutputInfo] = (
      ("index" | uint32) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[OutputInfo]

    val commitTxCodec: Codec[CommitTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]
    val htlcSuccessTxCodec: Codec[HtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow)).as[HtlcSuccessTx]
    val htlcTimeoutTxCodec: Codec[HtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow)).as[HtlcTimeoutTx]
    val htlcDelayedTxCodec: Codec[HtlcDelayedTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcDelayedTx]
    val claimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow)).as[ClaimHtlcSuccessTx]
    val claimHtlcTimeoutTxCodec: Codec[ClaimHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow)).as[ClaimHtlcTimeoutTx]
    val claimLocalDelayedOutputTxCodec: Codec[ClaimLocalDelayedOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimLocalDelayedOutputTx]
    val claimP2WPKHOutputTxCodec: Codec[ClaimP2WPKHOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimP2WPKHOutputTx]
    val claimRemoteDelayedOutputTxCodec: Codec[ClaimRemoteDelayedOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimRemoteDelayedOutputTx]
    val mainPenaltyTxCodec: Codec[MainPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx]
    val htlcPenaltyTxCodec: Codec[HtlcPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx]
    val claimHtlcDelayedOutputPenaltyTxCodec: Codec[ClaimHtlcDelayedOutputPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcDelayedOutputPenaltyTx]
    val claimLocalAnchorOutputTxCodec: Codec[ClaimLocalAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimLocalAnchorOutputTx]
    val claimRemoteAnchorOutputTxCodec: Codec[ClaimRemoteAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimRemoteAnchorOutputTx]
    val closingTxCodec: Codec[ClosingTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | optional(bool8, outputInfoCodec))).as[ClosingTx]

    val txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
      .typecase(0x01, commitTxCodec)
      .typecase(0x02, htlcSuccessTxCodec)
      .typecase(0x03, htlcTimeoutTxCodec)
      .typecase(0x04, claimHtlcSuccessTxCodec)
      .typecase(0x05, claimHtlcTimeoutTxCodec)
      .typecase(0x06, claimP2WPKHOutputTxCodec)
      .typecase(0x07, claimLocalDelayedOutputTxCodec)
      .typecase(0x08, mainPenaltyTxCodec)
      .typecase(0x09, htlcPenaltyTxCodec)
      .typecase(0x10, closingTxCodec)
      .typecase(0x11, claimLocalAnchorOutputTxCodec)
      .typecase(0x12, claimRemoteAnchorOutputTxCodec)
      .typecase(0x13, claimRemoteDelayedOutputTxCodec)
      .typecase(0x14, claimHtlcDelayedOutputPenaltyTxCodec)
      .typecase(0x15, htlcDelayedTxCodec)

    val claimRemoteCommitMainOutputTxCodec: Codec[ClaimRemoteCommitMainOutputTx] = discriminated[ClaimRemoteCommitMainOutputTx].by(uint8)
      .typecase(0x01, claimP2WPKHOutputTxCodec)
      .typecase(0x02, claimRemoteDelayedOutputTxCodec)

    val claimAnchorOutputTxCodec: Codec[ClaimAnchorOutputTx] = discriminated[ClaimAnchorOutputTx].by(uint8)
      .typecase(0x01, claimLocalAnchorOutputTxCodec)
      .typecase(0x02, claimRemoteAnchorOutputTxCodec)

    val htlcTxCodec: Codec[HtlcTx] = discriminated[HtlcTx].by(uint8)
      .typecase(0x01, htlcSuccessTxCodec)
      .typecase(0x02, htlcTimeoutTxCodec)

    val claimHtlcTxCodec: Codec[ClaimHtlcTx] = discriminated[ClaimHtlcTx].by(uint8)
      .typecase(0x01, claimHtlcSuccessTxCodec)
      .typecase(0x02, claimHtlcTimeoutTxCodec)

    val htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
      ("txinfo" | htlcTxCodec) ::
        ("localSig" | lengthDelimited(bytes64)) :: // we store as variable length for historical purposes (we used to store as DER encoded)
        ("remoteSig" | lengthDelimited(bytes64))).as[HtlcTxAndSigs]

    val publishableTxsCodec: Codec[PublishableTxs] = (
      ("commitTx" | (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]) ::
        ("htlcTxsAndSigs" | listOfN(uint16, htlcTxAndSigsCodec))).as[PublishableTxs]

    val localCommitCodec: Codec[LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("publishableTxs" | publishableTxsCodec)).as[LocalCommit]

    val remoteCommitCodec: Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | bytes32) ::
        ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

    val updateMessageCodec: Codec[UpdateMessage] = lengthDelimited(lightningMessageCodec.narrow[UpdateMessage](f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g))

    val localChangesCodec: Codec[LocalChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

    val remoteChangesCodec: Codec[RemoteChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

    val waitingForRevocationCodec: Codec[WaitingForRevocation] = (
      ("nextRemoteCommit" | remoteCommitCodec) ::
        ("sent" | lengthDelimited(commitSigCodec)) ::
        ("sentAfterLocalCommitIndex" | uint64overflow) ::
        ("reSignAsap" | bool8)).as[WaitingForRevocation]

    val localColdCodec: Codec[Origin.LocalCold] = ("id" | uuid).as[Origin.LocalCold]

    val localCodec: Codec[Origin.Local] = localColdCodec.xmap[Origin.Local](o => o: Origin.Local, o => Origin.LocalCold(o.id))

    val relayedColdCodec: Codec[Origin.ChannelRelayedCold] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | millisatoshi) ::
        ("amountOut" | millisatoshi)).as[Origin.ChannelRelayedCold]

    val relayedCodec: Codec[Origin.ChannelRelayed] = relayedColdCodec.xmap[Origin.ChannelRelayed](o => o: Origin.ChannelRelayed, o => Origin.ChannelRelayedCold(o.originChannelId, o.originHtlcId, o.amountIn, o.amountOut))

    val trampolineRelayedColdCodec: Codec[Origin.TrampolineRelayedCold] = listOfN(uint16, bytes32 ~ int64).as[Origin.TrampolineRelayedCold]

    val trampolineRelayedCodec: Codec[Origin.TrampolineRelayed] = trampolineRelayedColdCodec.xmap[Origin.TrampolineRelayed](o => o: Origin.TrampolineRelayed, o => Origin.TrampolineRelayedCold(o.htlcs))

    val originCodec: Codec[Origin] = discriminated[Origin].by(uint16)
      .typecase(0x02, relayedCodec)
      .typecase(0x03, localCodec)
      .typecase(0x04, trampolineRelayedCodec)

    def mapCodec[K, V](keyCodec: Codec[K], valueCodec: Codec[V]): Codec[Map[K, V]] = listOfN(uint16, keyCodec ~ valueCodec).xmap(_.toMap, _.toList)

    val originsMapCodec: Codec[Map[Long, Origin]] = mapCodec(int64, originCodec)

    val spentMapCodec: Codec[Map[OutPoint, Transaction]] = mapCodec(outPointCodec, txCodec)

    val commitmentsCodec: Codec[Commitments] = (
      ("channelVersion" | channelVersionCodec) >>:~ { channelVersion =>
        ("localParams" | localParamsCodec(channelVersion)) ::
          ("remoteParams" | remoteParamsCodec) ::
          ("channelFlags" | byte) ::
          ("localCommit" | localCommitCodec) ::
          ("remoteCommit" | remoteCommitCodec) ::
          ("localChanges" | localChangesCodec) ::
          ("remoteChanges" | remoteChangesCodec) ::
          ("localNextHtlcId" | uint64overflow) ::
          ("remoteNextHtlcId" | uint64overflow) ::
          ("originChannels" | originsMapCodec) ::
          ("remoteNextCommitInfo" | either(bool8, waitingForRevocationCodec, publicKey)) ::
          ("commitInput" | inputInfoCodec) ::
          ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
          ("channelId" | bytes32)
      }).as[Commitments]

    val closingFeeratesCodec: Codec[ClosingFeerates] = (
      ("preferred" | feeratePerKw) ::
        ("min" | feeratePerKw) ::
        ("max" | feeratePerKw)).as[ClosingFeerates]

    val closingTxProposedCodec: Codec[ClosingTxProposed] = (
      ("unsignedTx" | closingTxCodec) ::
        ("localClosingSigned" | lengthDelimited(closingSignedCodec))).as[ClosingTxProposed]

    val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool8, claimLocalDelayedOutputTxCodec)) ::
        ("htlcTxs" | mapCodec(outPointCodec, optional(bool8, htlcTxCodec))) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, htlcDelayedTxCodec)) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[LocalCommitPublished]

    val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("claimHtlcTxs" | mapCodec(outPointCodec, optional(bool8, claimHtlcTxCodec))) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[RemoteCommitPublished]

    val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("mainPenaltyTx" | optional(bool8, mainPenaltyTxCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, htlcPenaltyTxCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, claimHtlcDelayedOutputPenaltyTxCodec)) ::
        ("spent" | spentMapCodec)).as[RevokedCommitPublished]

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx" | optional(bool8, txCodec)) ::
        ("initialRelayFees" | provide(Option.empty[(MilliSatoshi, Int)])) ::
        ("waitingSince" | int64) ::
        ("deferred" | optional(bool8, lengthDelimited(fundingLockedCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_FUNDING_LOCKED_Codec: Codec[DATA_WAIT_FOR_FUNDING_LOCKED] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | shortchannelid) ::
        ("lastSent" | lengthDelimited(fundingLockedCodec)) ::
        ("initialRelayFees" | provide(Option.empty[(MilliSatoshi, Int)]))).as[DATA_WAIT_FOR_FUNDING_LOCKED]

    val DATA_NORMAL_COMPAT_02_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | shortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | provide(Option.empty[ClosingFeerates]))).as[DATA_NORMAL]

    val DATA_NORMAL_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | shortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_NORMAL]

    val DATA_SHUTDOWN_COMPAT_03_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | provide(Option.empty[ClosingFeerates]))).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_CLOSING_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | commitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val stateDataCodec: Codec[HasCommitments] = discriminated[HasCommitments].by(uint16)
    .typecase(0x08, Codecs.DATA_SHUTDOWN_Codec)
    .typecase(0x07, Codecs.DATA_NORMAL_Codec)
    .typecase(0x06, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec)
    .typecase(0x05, Codecs.DATA_CLOSING_Codec)
    .typecase(0x04, Codecs.DATA_NEGOTIATING_Codec)
    .typecase(0x03, Codecs.DATA_SHUTDOWN_COMPAT_03_Codec)
    .typecase(0x02, Codecs.DATA_NORMAL_COMPAT_02_Codec)
    .typecase(0x01, Codecs.DATA_WAIT_FOR_FUNDING_LOCKED_Codec)
    .typecase(0x00, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec)

}
