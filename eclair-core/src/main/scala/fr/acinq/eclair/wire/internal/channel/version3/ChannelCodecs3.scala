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

package fr.acinq.eclair.wire.internal.channel.version3

import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{OutPoint, Transaction, TxOut}
import fr.acinq.eclair.channel.FundingTxStatus._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.UpdateMessage
import fr.acinq.eclair.{Alias, BlockHeight, FeatureSupport, Features, PermanentChannelFeature}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}
import shapeless.{::, HNil}

private[channel] object ChannelCodecs3 {

  private[version3] object Codecs {

    val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]

    val channelConfigCodec: Codec[ChannelConfig] = lengthDelimited(bytes).xmap(b => {
      val activated: Set[ChannelConfigOption] = b.bits.toIndexedSeq.reverse.zipWithIndex.collect {
        case (true, 0) => ChannelConfig.FundingPubKeyBasedChannelKeyPath
      }.toSet
      ChannelConfig(activated)
    }, cfg => {
      val indices = cfg.options.map(_.supportBit)
      if (indices.isEmpty) {
        ByteVector.empty
      } else {
        // NB: when converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting bits.
        var buffer = BitVector.fill(indices.max + 1)(high = false).bytes.bits
        indices.foreach(i => buffer = buffer.set(i))
        buffer.reverse.bytes
      }
    })

    /** We use the same encoding as init features, even if we don't need the distinction between mandatory and optional */
    val channelFeaturesCodec: Codec[ChannelFeatures] = lengthDelimited(bytes).xmap(
      (b: ByteVector) => ChannelFeatures(Features(b).activated.keySet.collect { case f: PermanentChannelFeature => f }), // we make no difference between mandatory/optional, both are considered activated
      (cf: ChannelFeatures) => Features(cf.features.map(f => f -> FeatureSupport.Mandatory).toMap).toByteVector // we encode features as mandatory, by convention
    )

    def localParamsCodec(channelFeatures: ChannelFeatures): Codec[LocalParams] = (
      ("nodeId" | publicKey) ::
        ("channelPath" | keyPathCodec) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | millisatoshi) ::
        ("channelReserve" | conditional(!channelFeatures.hasFeature(Features.DualFunding), satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("isInitiator" | bool8) ::
        ("defaultFinalScriptPubKey" | lengthDelimited(bytes)) ::
        ("walletStaticPaymentBasepoint" | optional(provide(channelFeatures.paysDirectlyToWallet), publicKey)) ::
        ("features" | combinedFeaturesCodec)).as[LocalParams]

    def remoteParamsCodec(channelFeatures: ChannelFeatures): Codec[RemoteParams] = (
      ("nodeId" | publicKey) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(!channelFeatures.hasFeature(Features.DualFunding), satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("fundingPubKey" | publicKey) ::
        ("revocationBasepoint" | publicKey) ::
        ("paymentBasepoint" | publicKey) ::
        ("delayedPaymentBasepoint" | publicKey) ::
        ("htlcBasepoint" | publicKey) ::
        ("features" | combinedFeaturesCodec) ::
        ("shutdownScript" | optional(bool8, lengthDelimited(bytes)))).as[RemoteParams]

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
    val htlcSuccessTxCodec: Codec[HtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | blockHeight)).as[HtlcSuccessTx]
    val htlcTimeoutTxCodec: Codec[HtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | blockHeight)).as[HtlcTimeoutTx]
    private val htlcSuccessTxNoConfirmCodec: Codec[HtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | provide(BlockHeight(0)))).as[HtlcSuccessTx]
    private val htlcTimeoutTxNoConfirmCodec: Codec[HtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | provide(BlockHeight(0)))).as[HtlcTimeoutTx]
    val htlcDelayedTxCodec: Codec[HtlcDelayedTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcDelayedTx]
    private val legacyClaimHtlcSuccessTxCodec: Codec[LegacyClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | provide(BlockHeight(0)))).as[LegacyClaimHtlcSuccessTx]
    val claimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | blockHeight)).as[ClaimHtlcSuccessTx]
    val claimHtlcTimeoutTxCodec: Codec[ClaimHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | blockHeight)).as[ClaimHtlcTimeoutTx]
    private val claimHtlcSuccessTxNoConfirmCodec: Codec[ClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | provide(BlockHeight(0)))).as[ClaimHtlcSuccessTx]
    private val claimHtlcTimeoutTxNoConfirmCodec: Codec[ClaimHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmBefore" | provide(BlockHeight(0)))).as[ClaimHtlcTimeoutTx]
    val claimLocalDelayedOutputTxCodec: Codec[ClaimLocalDelayedOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimLocalDelayedOutputTx]
    val claimP2WPKHOutputTxCodec: Codec[ClaimP2WPKHOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimP2WPKHOutputTx]
    val claimRemoteDelayedOutputTxCodec: Codec[ClaimRemoteDelayedOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimRemoteDelayedOutputTx]
    val mainPenaltyTxCodec: Codec[MainPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx]
    val htlcPenaltyTxCodec: Codec[HtlcPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx]
    val claimHtlcDelayedOutputPenaltyTxCodec: Codec[ClaimHtlcDelayedOutputPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcDelayedOutputPenaltyTx]
    val claimLocalAnchorOutputTxCodec: Codec[ClaimLocalAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmBefore" | blockHeight)).as[ClaimLocalAnchorOutputTx]
    private val claimLocalAnchorOutputTxNoConfirmCodec: Codec[ClaimLocalAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmBefore" | provide(BlockHeight(0)))).as[ClaimLocalAnchorOutputTx]
    val claimRemoteAnchorOutputTxCodec: Codec[ClaimRemoteAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimRemoteAnchorOutputTx]
    val closingTxCodec: Codec[ClosingTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | optional(bool8, outputInfoCodec))).as[ClosingTx]

    val txWithInputInfoCodec: Codec[TransactionWithInputInfo] = discriminated[TransactionWithInputInfo].by(uint16)
      // Important: order matters!
      .typecase(0x20, claimLocalAnchorOutputTxCodec)
      .typecase(0x21, htlcSuccessTxCodec)
      .typecase(0x22, htlcTimeoutTxCodec)
      .typecase(0x23, claimHtlcSuccessTxCodec)
      .typecase(0x24, claimHtlcTimeoutTxCodec)
      .typecase(0x01, commitTxCodec)
      .typecase(0x02, htlcSuccessTxNoConfirmCodec)
      .typecase(0x03, htlcTimeoutTxNoConfirmCodec)
      .typecase(0x04, legacyClaimHtlcSuccessTxCodec)
      .typecase(0x05, claimHtlcTimeoutTxNoConfirmCodec)
      .typecase(0x06, claimP2WPKHOutputTxCodec)
      .typecase(0x07, claimLocalDelayedOutputTxCodec)
      .typecase(0x08, mainPenaltyTxCodec)
      .typecase(0x09, htlcPenaltyTxCodec)
      .typecase(0x10, closingTxCodec)
      .typecase(0x11, claimLocalAnchorOutputTxNoConfirmCodec)
      .typecase(0x12, claimRemoteAnchorOutputTxCodec)
      .typecase(0x13, claimRemoteDelayedOutputTxCodec)
      .typecase(0x14, claimHtlcDelayedOutputPenaltyTxCodec)
      .typecase(0x15, htlcDelayedTxCodec)
      .typecase(0x16, claimHtlcSuccessTxNoConfirmCodec)

    val claimRemoteCommitMainOutputTxCodec: Codec[ClaimRemoteCommitMainOutputTx] = discriminated[ClaimRemoteCommitMainOutputTx].by(uint8)
      .typecase(0x01, claimP2WPKHOutputTxCodec)
      .typecase(0x02, claimRemoteDelayedOutputTxCodec)

    val claimAnchorOutputTxCodec: Codec[ClaimAnchorOutputTx] = discriminated[ClaimAnchorOutputTx].by(uint8)
      // Important: order matters!
      .typecase(0x11, claimLocalAnchorOutputTxCodec)
      .typecase(0x01, claimLocalAnchorOutputTxNoConfirmCodec)
      .typecase(0x02, claimRemoteAnchorOutputTxCodec)

    val htlcTxCodec: Codec[HtlcTx] = discriminated[HtlcTx].by(uint8)
      // Important: order matters!
      .typecase(0x11, htlcSuccessTxCodec)
      .typecase(0x12, htlcTimeoutTxCodec)
      .typecase(0x01, htlcSuccessTxNoConfirmCodec)
      .typecase(0x02, htlcTimeoutTxNoConfirmCodec)

    val claimHtlcTxCodec: Codec[ClaimHtlcTx] = discriminated[ClaimHtlcTx].by(uint8)
      // Important: order matters!
      .typecase(0x22, claimHtlcTimeoutTxCodec)
      .typecase(0x23, claimHtlcSuccessTxCodec)
      .typecase(0x01, legacyClaimHtlcSuccessTxCodec)
      .typecase(0x02, claimHtlcTimeoutTxNoConfirmCodec)
      .typecase(0x03, claimHtlcSuccessTxNoConfirmCodec)

    val htlcTxsAndRemoteSigsCodec: Codec[HtlcTxAndRemoteSig] = (
      ("txinfo" | htlcTxCodec) ::
        ("remoteSig" | bytes64)).as[HtlcTxAndRemoteSig]

    val commitTxAndRemoteSigCodec: Codec[CommitTxAndRemoteSig] = (
      ("commitTx" | commitTxCodec) ::
        ("remoteSig" | bytes64)).as[CommitTxAndRemoteSig]

    val localCommitCodec: Codec[LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("commitTxAndRemoteSig" | commitTxAndRemoteSigCodec) ::
        ("htlcTxsAndRemoteSigs" | listOfN(uint16, htlcTxsAndRemoteSigsCodec))).as[LocalCommit]

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
        ("reSignAsap" | ignore(8))).as[WaitingForRevocation]

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

    val readOnlyCompatibilityCommitmentsCodec: Codec[Commitments] = (
      ("channelId" | bytes32) ::
        ("channelConfig" | channelConfigCodec) ::
        (("channelFeatures" | channelFeaturesCodec) >>:~ { channelFeatures =>
          ("localParams" | localParamsCodec(channelFeatures)) ::
            ("remoteParams" | remoteParamsCodec(channelFeatures)) ::
            ("channelFlags" | channelflags) ::
            ("localCommit" | localCommitCodec) ::
            ("remoteCommit" | remoteCommitCodec) ::
            ("localChanges" | localChangesCodec) ::
            ("remoteChanges" | remoteChangesCodec) ::
            ("localNextHtlcId" | uint64overflow) ::
            ("remoteNextHtlcId" | uint64overflow) ::
            ("originChannels" | originsMapCodec) ::
            ("remoteNextCommitInfo" | either(bool8, waitingForRevocationCodec, publicKey)) ::
            ("commitInput" | inputInfoCodec.map(_ => ()).decodeOnly) ::
            ("fundingTxStatus" | provide(UnknownFundingTx).upcast[FundingTxStatus]) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec))
        })).as[Commitments].decodeOnly

    private val remoteTxAddInputCodec: Codec[RemoteTxAddInput] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("sequence" | uint32)).as[RemoteTxAddInput]

    private val remoteTxAddOutputCodec: Codec[RemoteTxAddOutput] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[RemoteTxAddOutput]

    private val sharedTransactionCodec: Codec[SharedTransaction] = (
      ("localInputs" | listOfN(uint16, lengthDelimited(txAddInputCodec))) ::
        ("remoteInputs" | listOfN(uint16, remoteTxAddInputCodec)) ::
        ("localOutputs" | listOfN(uint16, lengthDelimited(txAddOutputCodec))) ::
        ("remoteOutputs" | listOfN(uint16, remoteTxAddOutputCodec)) ::
        ("lockTime" | uint32)).as[SharedTransaction]

    private val partiallySignedSharedTransactionCodec: Codec[PartiallySignedSharedTransaction] = (
      ("sharedTx" | sharedTransactionCodec) ::
        ("localSigs" | lengthDelimited(txSignaturesCodec))).as[PartiallySignedSharedTransaction]

    private val fullySignedSharedTransactionCodec: Codec[FullySignedSharedTransaction] = (
      ("sharedTx" | sharedTransactionCodec) ::
        ("localSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("remoteSigs" | lengthDelimited(txSignaturesCodec))).as[FullySignedSharedTransaction]

    private val signedSharedTransactionCodec: Codec[SignedSharedTransaction] = discriminated[SignedSharedTransaction].by(uint16)
      .typecase(0x01, partiallySignedSharedTransactionCodec)
      .typecase(0x02, fullySignedSharedTransactionCodec)

    val fundingTxStatusCodec: Codec[FundingTxStatus] = discriminated[FundingTxStatus].by(uint8)
      .typecase(0x00, optional(bool8, txCodec).as[SingleFundedUnconfirmedFundingTx])
      .typecase(0x01, txCodec.map(tx => SingleFundedUnconfirmedFundingTx(Some(tx))).decodeOnly) // for backward compat
      .typecase(0x02, signedSharedTransactionCodec.as[DualFundedUnconfirmedFundingTx])
      .typecase(0x03, txCodec.as[ConfirmedFundingTx])
      .typecase(0x04, provide(UnknownFundingTx))

    val unconfirmedFundingTxCodec: Codec[UnconfirmedFundingTx] = fundingTxStatusCodec.downcast[UnconfirmedFundingTx].decodeOnly

    val paramsCodec: Codec[Params] = (
      ("channelId" | bytes32) ::
        ("channelConfig" | channelConfigCodec) ::
        (("channelFeatures" | channelFeaturesCodec) >>:~ { channelFeatures =>
          ("localParams" | localParamsCodec(channelFeatures)) ::
            ("remoteParams" | remoteParamsCodec(channelFeatures)) ::
            ("channelFlags" | channelflags)
        })).as[Params]

    val waitForRevCodec: Codec[WaitForRev] = (
      ("sent" | lengthDelimited(commitSigCodec)) ::
        ("sentAfterLocalCommitIndex" | uint64overflow)
      ).as[WaitForRev]

    val commonCodec: Codec[Common] = (
      ("localChanges" | localChangesCodec) ::
        ("remoteChanges" | remoteChangesCodec) ::
        ("localNextHtlcId" | uint64overflow) ::
        ("remoteNextHtlcId" | uint64overflow) ::
        ("originChannels" | originsMapCodec) ::
        ("remoteNextCommitInfo" | either(bool8, waitForRevCodec, publicKey)) ::
        ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec))
      ).as[Common]

    val commitmentCodec: Codec[Commitment] = (
      ("fundingTxStatus" | fundingTxStatusCodec) ::
        ("localCommit" | localCommitCodec) ::
        ("remoteCommit" | remoteCommitCodec) ::
        ("nextRemoteCommit_opt" | optional(bool8, remoteCommitCodec))
      ).as[Commitment]

    /** Once a dual funding tx has been signed, we must remember the associated commitments. */
    case class DualFundingTx(fundingTx: SignedSharedTransaction, commitments: Commitments)

    private val dualFundingTxCodec: Codec[DualFundingTx] = (
      ("fundingTx" | signedSharedTransactionCodec) ::
        ("commitments" | readOnlyCompatibilityCommitmentsCodec)).as[DualFundingTx].decodeOnly

    private val fundingParamsCodec: Codec[InteractiveTxParams] = (
      ("channelId" | bytes32) ::
        ("isInitiator" | bool8) ::
        ("localAmount" | satoshi) ::
        ("remoteAmount" | satoshi) ::
        ("fundingPubkeyScript" | lengthDelimited(bytes)) ::
        ("lockTime" | uint32) ::
        ("dustLimit" | satoshi) ::
        ("targetFeerate" | feeratePerKw) ::
        ("requireConfirmedInputs" | (("forLocal" | bool8) :: ("forRemote" | bool8)).as[RequireConfirmedInputs])).as[InteractiveTxParams]

    /** used by state codecs version <= 0x0d */
    val readOnlyCompatibilityMetaCommitmentsCodec: Codec[MetaCommitments] = readOnlyCompatibilityCommitmentsCodec
      .map(commitments => MetaCommitments(commitments))
      .decodeOnly

    val metaCommitmentsCodec: Codec[MetaCommitments] = (
      ("params" | paramsCodec) ::
        ("common" | commonCodec) ::
        ("commitments" | listOfN(uint16, commitmentCodec)) ::
        ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))
      ).as[MetaCommitments]

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

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_00_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).map {
      case metaCommitments :: fundingTx :: waitingSince :: deferred :: lastSent :: HNil =>
        val metaCommitments1 = metaCommitments.modify(_.commitments.at(0).fundingTxStatus).setTo(SingleFundedUnconfirmedFundingTx(fundingTx))
        DATA_WAIT_FOR_FUNDING_CONFIRMED(metaCommitments1, waitingSince, deferred, lastSent)
    }.decodeOnly

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_15_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_CHANNEL_READY_01_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).map {
      case metaCommitments :: shortChannelId :: lastSent :: HNil =>
        DATA_WAIT_FOR_CHANNEL_READY(metaCommitments, shortIds = ShortIds(real = RealScidStatus.Temporary(shortChannelId), localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None), lastSent = lastSent)
    }.decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_0a_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("shortIds" | shortids) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).as[DATA_WAIT_FOR_CHANNEL_READY]

    val DATA_WAIT_FOR_CHANNEL_READY_14_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("shortIds" | shortids) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).as[DATA_WAIT_FOR_CHANNEL_READY]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_0b_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("fundingTx" | signedSharedTransactionCodec) ::
        ("fundingParams" | fundingParamsCodec) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("previousFundingTxs" | listOfN(uint16, dualFundingTxCodec)) ::
        ("waitingSince" | blockHeight) ::
        ("lastChecked" | blockHeight) ::
        ("rbfStatus" | provide[RbfStatus](RbfStatus.NoRbf)) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))).map {
      case metaCommitments :: fundingTx :: fundingParams :: localPushAmount :: remotePushAmount :: previousFundingTxs :: waitingSince :: lastChecked :: rbfStatus :: deferred :: HNil =>
        val metaCommitments1 = metaCommitments
          .modify(_.commitments.at(0).fundingTxStatus).setTo(DualFundedUnconfirmedFundingTx(fundingTx))
          .modify(_.commitments).using(_ ++ previousFundingTxs.map { case DualFundingTx(f, c) => c.commitment.copy(fundingTxStatus = DualFundedUnconfirmedFundingTx(f)) })
        DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(metaCommitments1, fundingParams, localPushAmount, remotePushAmount, waitingSince, lastChecked, rbfStatus, deferred)
    }.decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_13_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("fundingParams" | fundingParamsCodec) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("waitingSince" | blockHeight) ::
        ("lastChecked" | blockHeight) ::
        ("rbfStatus" | provide[RbfStatus](RbfStatus.NoRbf)) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_0c_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("shortIds" | shortids) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).as[DATA_WAIT_FOR_DUAL_FUNDING_READY]
      .decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_12_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("shortIds" | shortids) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).as[DATA_WAIT_FOR_DUAL_FUNDING_READY]

    val DATA_NORMAL_02_Codec: Codec[DATA_NORMAL] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | provide(Option.empty[ClosingFeerates]))).map {
      case metaCommitments :: shortChannelId :: buried :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closingFeerates :: HNil =>
        DATA_NORMAL(metaCommitments, shortIds = ShortIds(real = if (buried) RealScidStatus.Final(shortChannelId) else RealScidStatus.Temporary(shortChannelId), localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None), channelAnnouncement, channelUpdate, localShutdown, remoteShutdown, closingFeerates)
    }.decodeOnly

    val DATA_NORMAL_07_Codec: Codec[DATA_NORMAL] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).map {
      case metaCommitments :: shortChannelId :: buried :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closingFeerates :: HNil =>
        DATA_NORMAL(metaCommitments, shortIds = ShortIds(real = if (buried) RealScidStatus.Final(shortChannelId) else RealScidStatus.Temporary(shortChannelId), localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None), channelAnnouncement, channelUpdate, localShutdown, remoteShutdown, closingFeerates)
    }.decodeOnly

    val DATA_NORMAL_09_Codec: Codec[DATA_NORMAL] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("shortids" | shortids) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_NORMAL]

    val DATA_NORMAL_11_Codec: Codec[DATA_NORMAL] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("shortids" | shortids) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_NORMAL]

    val DATA_SHUTDOWN_03_Codec: Codec[DATA_SHUTDOWN] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | provide(Option.empty[ClosingFeerates]))).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_08_Codec: Codec[DATA_SHUTDOWN] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_10_Codec: Codec[DATA_SHUTDOWN] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_04_Codec: Codec[DATA_NEGOTIATING] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_NEGOTIATING_0f_Codec: Codec[DATA_NEGOTIATING] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_CLOSING_05_Codec: Codec[DATA_CLOSING] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).map {
      case metaCommitments :: fundingTx_opt :: waitingSince :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val metaCommitments1 = metaCommitments.modify(_.commitments.at(0).fundingTxStatus).setTo(SingleFundedUnconfirmedFundingTx(fundingTx_opt))
        DATA_CLOSING(metaCommitments1, waitingSince, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val DATA_CLOSING_0d_Codec: Codec[DATA_CLOSING] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, unconfirmedFundingTxCodec)) ::
        ("waitingSince" | blockHeight) ::
        ("alternativeCommitments" | listOfN(uint16, dualFundingTxCodec)) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).map {
      case metaCommitments :: fundingTx_opt :: waitingSince :: alternativeCommitments :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val metaCommitments1 = metaCommitments
          .modify(_.commitments.at(0).fundingTxStatus).setTo(fundingTx_opt.getOrElse(UnknownFundingTx))
          .modify(_.commitments).using(_ ++ alternativeCommitments.map { case DualFundingTx(f, c) => c.commitment.copy(fundingTxStatus = DualFundedUnconfirmedFundingTx(f)) })
        DATA_CLOSING(metaCommitments1, waitingSince, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val DATA_CLOSING_0e_Codec: Codec[DATA_CLOSING] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_06_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("metaCommitments" | readOnlyCompatibilityMetaCommitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_16_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("metaCommitments" | metaCommitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x16, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_16_Codec)
    .typecase(0x15, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_15_Codec)
    .typecase(0x14, Codecs.DATA_WAIT_FOR_CHANNEL_READY_14_Codec)
    .typecase(0x13, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_13_Codec)
    .typecase(0x12, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_READY_12_Codec)
    .typecase(0x11, Codecs.DATA_NORMAL_11_Codec)
    .typecase(0x10, Codecs.DATA_SHUTDOWN_10_Codec)
    .typecase(0x0f, Codecs.DATA_NEGOTIATING_0f_Codec)
    .typecase(0x0e, Codecs.DATA_CLOSING_0e_Codec)
    .typecase(0x0d, Codecs.DATA_CLOSING_0d_Codec)
    .typecase(0x0c, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_READY_0c_Codec)
    .typecase(0x0b, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_0b_Codec)
    .typecase(0x0a, Codecs.DATA_WAIT_FOR_CHANNEL_READY_0a_Codec)
    .typecase(0x09, Codecs.DATA_NORMAL_09_Codec)
    .typecase(0x08, Codecs.DATA_SHUTDOWN_08_Codec)
    .typecase(0x07, Codecs.DATA_NORMAL_07_Codec)
    .typecase(0x06, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_06_Codec)
    .typecase(0x05, Codecs.DATA_CLOSING_05_Codec)
    .typecase(0x04, Codecs.DATA_NEGOTIATING_04_Codec)
    .typecase(0x03, Codecs.DATA_SHUTDOWN_03_Codec)
    .typecase(0x02, Codecs.DATA_NORMAL_02_Codec)
    .typecase(0x01, Codecs.DATA_WAIT_FOR_CHANNEL_READY_01_Codec)
    .typecase(0x00, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_00_Codec)

}
