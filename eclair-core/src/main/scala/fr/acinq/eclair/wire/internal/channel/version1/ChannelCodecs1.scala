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

package fr.acinq.eclair.wire.internal.channel.version1

import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Transaction, TxOut}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.UpdateMessage
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import scodec.codecs._
import scodec.{Attempt, Codec}

private[channel] object ChannelCodecs1 {

  private[version1] object Codecs {

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

    val closingTxCodec: Codec[ClosingTx] = txCodec.decodeOnly.xmap(
      tx => ChannelTypes0.migrateClosingTx(tx),
      closingTx => closingTx.tx
    )

    val inputInfoCodec: Codec[InputInfo] = (
      ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("redeemScript" | lengthDelimited(bytes))).as[InputInfo]

    // NB: we can safely set htlcId = 0 for htlc txs. This information is only used to find upstream htlcs to fail when a
    // downstream htlc times out, and `Helpers.Closing.timedOutHtlcs` explicitly handles the case where htlcId is missing.
    val txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
      .typecase(0x01, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx])
      .typecase(0x02, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | provide(0L))).as[HtlcSuccessTx])
      .typecase(0x03, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | provide(0L))).as[HtlcTimeoutTx])
      .typecase(0x04, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | provide(0L))).as[ClaimHtlcSuccessTx])
      .typecase(0x05, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | provide(0L))).as[ClaimHtlcTimeoutTx])
      .typecase(0x06, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimP2WPKHOutputTx])
      .typecase(0x07, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimLocalDelayedOutputTx])
      .typecase(0x08, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx])
      .typecase(0x09, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx])
      .typecase(0x10, (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | provide(Option.empty[OutputInfo]))).as[ClosingTx])

    val htlcTxAndSigsCodec: Codec[HtlcTxAndSigs] = (
      ("txinfo" | txWithInputInfoCodec.downcast[HtlcTx]) ::
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

    val spentMapCodec: Codec[Map[OutPoint, ByteVector32]] = mapCodec(outPointCodec, bytes32)

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

    val closingTxProposedCodec: Codec[ClosingTxProposed] = (
      ("unsignedTx" | closingTxCodec) ::
        ("localClosingSigned" | lengthDelimited(closingSignedCodec))).as[ClosingTxProposed]

    val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool8, txCodec)) ::
        ("htlcSuccessTxs" | listOfN(uint16, txCodec)) ::
        ("htlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.LocalCommitPublished].decodeOnly.map[LocalCommitPublished](_.migrate()).decodeOnly

    val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, txCodec)) ::
        ("claimHtlcSuccessTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcTimeoutTxs" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.RemoteCommitPublished].decodeOnly.map[RemoteCommitPublished](_.migrate()).decodeOnly

    val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, txCodec)) ::
        ("mainPenaltyTx" | optional(bool8, txCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, txCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, txCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes0.RevokedCommitPublished].decodeOnly.map[RevokedCommitPublished](_.migrate()).decodeOnly

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

    val DATA_NORMAL_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | shortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | provide(Option.empty[ClosingFeerates]))).as[DATA_NORMAL]

    val DATA_SHUTDOWN_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | provide(Option.empty[ClosingFeerates]))).as[DATA_SHUTDOWN]

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
    .typecase(0x20, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec)
    .typecase(0x21, Codecs.DATA_WAIT_FOR_FUNDING_LOCKED_Codec)
    .typecase(0x22, Codecs.DATA_NORMAL_Codec)
    .typecase(0x23, Codecs.DATA_SHUTDOWN_Codec)
    .typecase(0x24, Codecs.DATA_NEGOTIATING_Codec)
    .typecase(0x25, Codecs.DATA_CLOSING_Codec)
    .typecase(0x26, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec)
}
