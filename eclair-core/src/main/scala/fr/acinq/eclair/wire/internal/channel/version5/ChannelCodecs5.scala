/*
 * Copyright 2025 ACINQ SAS
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

package fr.acinq.eclair.wire.internal.channel.version5

import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{OutPoint, ScriptWitness, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, ClosingTxs, InputInfo}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.{LiquidityAds, UpdateAddHtlc, UpdateMessage}
import fr.acinq.eclair.{FeatureSupport, Features, PermanentChannelFeature}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}

/**
 * Created by t-bast on 18/06/2025.
 */

private[channel] object ChannelCodecs5 {

  private[version5] object Codecs {
    private val keyPathCodec: Codec[KeyPath] = ("path" | listOfN(uint16, uint32)).xmap[KeyPath](l => KeyPath(l), keyPath => keyPath.path.toList).as[KeyPath]
    private val outPointCodec: Codec[OutPoint] = lengthDelimited(bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))
    private val txOutCodec: Codec[TxOut] = lengthDelimited(bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))
    private val txCodec: Codec[Transaction] = lengthDelimited(bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))
    private val scriptWitnessCodec: Codec[ScriptWitness] = listOfN(uint16, lengthDelimited(bytes)).xmap(s => ScriptWitness(s.toSeq), w => w.stack.toList)

    private val inputInfoCodec: Codec[InputInfo] = (("outPoint" | outPointCodec) :: ("txOut" | txOutCodec)).as[InputInfo]

    private val channelSpendSignatureCodec: Codec[ChannelSpendSignature] = discriminated[ChannelSpendSignature].by(uint8)
      .typecase(0x01, bytes64.as[ChannelSpendSignature.IndividualSignature])
      .typecase(0x02, partialSignatureWithNonce)

    private def setCodec[T](codec: Codec[T]): Codec[Set[T]] = listOfN(uint16, codec).xmap(_.toSet, _.toList)

    private def mapCodec[K, V](keyCodec: Codec[K], valueCodec: Codec[V]): Codec[Map[K, V]] = listOfN(uint16, keyCodec ~ valueCodec).xmap(_.toMap, _.toList)

    private val channelConfigCodec: Codec[ChannelConfig] = lengthDelimited(bytes).xmap(b => {
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
    private val channelFeaturesCodec: Codec[ChannelFeatures] = lengthDelimited(bytes).xmap(
      (b: ByteVector) => ChannelFeatures(Features(b).activated.keySet.collect { case f: PermanentChannelFeature => f }), // we make no difference between mandatory/optional, both are considered activated
      (cf: ChannelFeatures) => Features(cf.features.map(f => f -> FeatureSupport.Mandatory).toMap).toByteVector // we encode features as mandatory, by convention
    )

    private val commitmentFormatCodec: Codec[Transactions.CommitmentFormat] = discriminated[Transactions.CommitmentFormat].by(uint8)
      .typecase(0x00, provide(Transactions.DefaultCommitmentFormat))
      .typecase(0x01, provide(Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      .typecase(0x02, provide(Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
      .typecase(0x03, provide(Transactions.PhoenixSimpleTaprootChannelCommitmentFormat))
      .typecase(0x04, provide(Transactions.ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat))

    private val localChannelParamsCodec: Codec[LocalChannelParams] = (
      ("nodeId" | publicKey) ::
        ("channelPath" | keyPathCodec) ::
        ("channelReserve" | optional(bool8, satoshi)) ::
        // We pad to keep codecs byte-aligned.
        ("isChannelOpener" | bool) :: ("paysCommitTxFees" | bool) :: ignore(6) ::
        ("upfrontShutdownScript_opt" | optional(bool8, lengthDelimited(bytes))) ::
        ("walletStaticPaymentBasepoint" | optional(bool8, publicKey)) ::
        ("features" | combinedFeaturesCodec)).as[LocalChannelParams]

    val remoteChannelParamsCodec: Codec[RemoteChannelParams] = (
      ("nodeId" | publicKey) ::
        ("channelReserve" | optional(bool8, satoshi)) ::
        ("revocationBasepoint" | publicKey) ::
        ("paymentBasepoint" | publicKey) ::
        ("delayedPaymentBasepoint" | publicKey) ::
        ("htlcBasepoint" | publicKey) ::
        ("features" | combinedFeaturesCodec) ::
        ("shutdownScript" | optional(bool8, lengthDelimited(bytes)))).as[RemoteChannelParams]

    private val channelParamsCodec: Codec[ChannelParams] = (
      ("channelId" | bytes32) ::
        ("channelConfig" | channelConfigCodec) ::
        ("channelFeatures" | channelFeaturesCodec) ::
        ("localParams" | localChannelParamsCodec) ::
        ("remoteParams" | remoteChannelParamsCodec) ::
        ("channelFlags" | channelflags)).as[ChannelParams]

    private val commitParamsCodec: Codec[CommitParams] = (
      ("dustLimit" | satoshi) ::
        ("htlcMinimum" | millisatoshi) ::
        ("maxHtlcValueInFlight" | uint64) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("toSelfDelay" | cltvExpiryDelta)).as[CommitParams]

    private val interactiveTxSharedFundingInputCodec: Codec[InteractiveTxBuilder.SharedFundingInput] = (
      ("info" | inputInfoCodec) ::
        ("fundingTxIndex" | uint32) ::
        ("remoteFundingPubkey" | publicKey) ::
        ("commitmentFormat" | commitmentFormatCodec)).as[InteractiveTxBuilder.SharedFundingInput]

    private val sharedFundingInputCodec: Codec[InteractiveTxBuilder.SharedFundingInput] = discriminated[InteractiveTxBuilder.SharedFundingInput].by(uint16)
      .typecase(0x01, interactiveTxSharedFundingInputCodec)

    private val requireConfirmedInputsCodec: Codec[InteractiveTxBuilder.RequireConfirmedInputs] = (("forLocal" | bool8) :: ("forRemote" | bool8)).as[InteractiveTxBuilder.RequireConfirmedInputs]

    private val fundingParamsCodec: Codec[InteractiveTxBuilder.InteractiveTxParams] = (
      ("channelId" | bytes32) ::
        ("isInitiator" | bool8) ::
        ("localContribution" | satoshiSigned) ::
        ("remoteContribution" | satoshiSigned) ::
        ("sharedInput_opt" | optional(bool8, sharedFundingInputCodec)) ::
        ("remoteFundingPubKey" | publicKey) ::
        ("localOutputs" | listOfN(uint16, txOutCodec)) ::
        ("commitmentFormat" | commitmentFormatCodec) ::
        ("lockTime" | uint32) ::
        ("dustLimit" | satoshi) ::
        ("targetFeerate" | feeratePerKw) ::
        ("requireConfirmedInputs" | requireConfirmedInputsCodec)).as[InteractiveTxBuilder.InteractiveTxParams]

    private val liquidityFeesCodec: Codec[LiquidityAds.Fees] = (("miningFees" | satoshi) :: ("serviceFees" | satoshi)).as[LiquidityAds.Fees]
    private val liquidityPurchaseCodec: Codec[LiquidityAds.PurchaseBasicInfo] = (("isBuyer" | bool8) :: ("amount" | satoshi) :: ("fees" | liquidityFeesCodec)).as[LiquidityAds.PurchaseBasicInfo]

    private val sharedInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Shared] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("publicKeyScript" | lengthDelimited(bytes)) ::
        ("sequence" | uint32) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | millisatoshi)).as[InteractiveTxBuilder.Input.Shared]

    private val sharedInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Shared] = (
      ("serialId" | uint64) ::
        ("scriptPubKey" | lengthDelimited(bytes)) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | millisatoshi)).as[InteractiveTxBuilder.Output.Shared]

    private val localOnlyInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Local] = (
      ("serialId" | uint64) ::
        ("previousTx" | txCodec) ::
        ("previousTxOutput" | uint32) ::
        ("sequence" | uint32)).as[InteractiveTxBuilder.Input.Local]

    private val localInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Local] = discriminated[InteractiveTxBuilder.Input.Local].by(byte)
      .typecase(0x01, localOnlyInteractiveTxInputCodec)

    private val remoteOnlyInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Remote] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("sequence" | uint32)).as[InteractiveTxBuilder.Input.Remote]

    private val remoteInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Remote] = discriminated[InteractiveTxBuilder.Input.Remote].by(byte)
      .typecase(0x01, remoteOnlyInteractiveTxInputCodec)

    private val localInteractiveTxChangeOutputCodec: Codec[InteractiveTxBuilder.Output.Local.Change] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[InteractiveTxBuilder.Output.Local.Change]

    private val localInteractiveTxNonChangeOutputCodec: Codec[InteractiveTxBuilder.Output.Local.NonChange] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[InteractiveTxBuilder.Output.Local.NonChange]

    private val localInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Local] = discriminated[InteractiveTxBuilder.Output.Local].by(byte)
      .typecase(0x01, localInteractiveTxChangeOutputCodec)
      .typecase(0x02, localInteractiveTxNonChangeOutputCodec)

    private val remoteStandardInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Remote] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[InteractiveTxBuilder.Output.Remote]

    private val remoteInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Remote] = discriminated[InteractiveTxBuilder.Output.Remote].by(byte)
      .typecase(0x01, remoteStandardInteractiveTxOutputCodec)

    private val sharedTransactionCodec: Codec[InteractiveTxBuilder.SharedTransaction] = (
      ("sharedInput" | optional(bool8, sharedInteractiveTxInputCodec)) ::
        ("sharedOutput" | sharedInteractiveTxOutputCodec) ::
        ("localInputs" | listOfN(uint16, localInteractiveTxInputCodec)) ::
        ("remoteInputs" | listOfN(uint16, remoteInteractiveTxInputCodec)) ::
        ("localOutputs" | listOfN(uint16, localInteractiveTxOutputCodec)) ::
        ("remoteOutputs" | listOfN(uint16, remoteInteractiveTxOutputCodec)) ::
        ("lockTime" | uint32)).as[InteractiveTxBuilder.SharedTransaction]

    private val partiallySignedSharedTransactionCodec: Codec[InteractiveTxBuilder.PartiallySignedSharedTransaction] = (
      ("sharedTx" | sharedTransactionCodec) ::
        ("localSigs" | lengthDelimited(txSignaturesCodec))).as[InteractiveTxBuilder.PartiallySignedSharedTransaction]

    private val fullySignedSharedTransactionCodec: Codec[InteractiveTxBuilder.FullySignedSharedTransaction] = (
      ("sharedTx" | sharedTransactionCodec) ::
        ("localSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("remoteSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("sharedSigs_opt" | optional(bool8, scriptWitnessCodec))).as[InteractiveTxBuilder.FullySignedSharedTransaction]

    private val signedSharedTransactionCodec: Codec[InteractiveTxBuilder.SignedSharedTransaction] = discriminated[InteractiveTxBuilder.SignedSharedTransaction].by(uint16)
      .typecase(0x01, partiallySignedSharedTransactionCodec)
      .typecase(0x02, fullySignedSharedTransactionCodec)

    private val spentInputscodec: Codec[Seq[OutPoint]] = listOfN(uint16, outPointCodec).xmap(_.toSeq, _.toList)

    private val localFundingStatusCodec: Codec[LocalFundingStatus] = discriminated[LocalFundingStatus].by(uint8)
      .typecase(0x04, (spentInputscodec :: txOutCodec :: realshortchannelid :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[LocalFundingStatus.ConfirmedFundingTx])
      .typecase(0x03, (txCodec :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[LocalFundingStatus.ZeroconfPublishedFundingTx])
      .typecase(0x02, (signedSharedTransactionCodec :: blockHeight :: fundingParamsCodec :: optional(bool8, liquidityPurchaseCodec)).as[LocalFundingStatus.DualFundedUnconfirmedFundingTx])
      .typecase(0x01, optional(bool8, txCodec).as[LocalFundingStatus.SingleFundedUnconfirmedFundingTx])

    private val remoteFundingStatusCodec: Codec[RemoteFundingStatus] = discriminated[RemoteFundingStatus].by(uint8)
      .typecase(0x01, provide(RemoteFundingStatus.NotLocked))
      .typecase(0x02, provide(RemoteFundingStatus.Locked))

    private val htlcCodec: Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(uint8)
      .typecase(0x01, lengthDelimited(updateAddHtlcCodec).as[IncomingHtlc])
      .typecase(0x02, lengthDelimited(updateAddHtlcCodec).as[OutgoingHtlc])

    private def minimalHtlcCodec(htlcs: Set[UpdateAddHtlc]): Codec[UpdateAddHtlc] = uint64overflow.xmap[UpdateAddHtlc](id => htlcs.find(_.id == id).get, _.id)

    private def minimalDirectedHtlcCodec(htlcs: Set[DirectedHtlc]): Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(uint8)
      .typecase(0x01, minimalHtlcCodec(htlcs.collect(DirectedHtlc.incoming)).as[IncomingHtlc])
      .typecase(0x02, minimalHtlcCodec(htlcs.collect(DirectedHtlc.outgoing)).as[OutgoingHtlc])

    private def baseCommitmentSpecCodec(directedHtlcCodec: Codec[DirectedHtlc]): Codec[CommitmentSpec] = (
      ("htlcs" | setCodec(directedHtlcCodec)) ::
        ("feeratePerKw" | feeratePerKw) ::
        ("toLocal" | millisatoshi) ::
        ("toRemote" | millisatoshi)).as[CommitmentSpec]

    /** HTLCs are stored separately to avoid duplicating data. */
    private def minimalCommitmentSpecCodec(htlcs: Set[DirectedHtlc]): Codec[CommitmentSpec] = baseCommitmentSpecCodec(minimalDirectedHtlcCodec(htlcs))

    /** HTLCs are stored in full, the codec is stateless but creates duplication between local/remote commitment, and across commitments. */
    private val commitmentSpecCodec: Codec[CommitmentSpec] = baseCommitmentSpecCodec(htlcCodec)

    // Note that we use the default commitmentSpec codec that fully encodes HTLCs. This creates some duplication, but
    // it's fine because this is a short-lived state during which the channel cannot be used for payments.
    private val unsignedLocalCommitCodec: Codec[InteractiveTxSigningSession.UnsignedLocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txId" | txId)).as[InteractiveTxSigningSession.UnsignedLocalCommit]

    private def localCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txId" | txId) ::
        ("remoteSig" | channelSpendSignatureCodec) ::
        ("htlcRemoteSigs" | listOfN(uint16, bytes64))).as[LocalCommit]

    private def remoteCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | txId) ::
        ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

    private def nextRemoteCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[NextRemoteCommit] = (
      ("sig" | lengthDelimited(commitSigCodec)) ::
        ("commit" | remoteCommitCodec(commitmentSpecCodec))).as[NextRemoteCommit]

    private def commitmentCodec(htlcs: Set[DirectedHtlc]): Codec[Commitment] = (
      ("fundingTxIndex" | uint32) ::
        ("firstRemoteCommitIndex" | uint64overflow) ::
        ("fundingInput" | outPointCodec) ::
        ("fundingAmount" | satoshi) ::
        ("remoteFundingPubKey" | publicKey) ::
        ("fundingTxStatus" | localFundingStatusCodec) ::
        ("remoteFundingStatus" | remoteFundingStatusCodec) ::
        ("commitmentFormat" | commitmentFormatCodec) ::
        ("localCommitParams" | commitParamsCodec) ::
        ("localCommit" | localCommitCodec(minimalCommitmentSpecCodec(htlcs))) ::
        ("remoteCommitParams" | commitParamsCodec) ::
        ("remoteCommit" | remoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))) ::
        ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))))).as[Commitment]

    private val waitForRevCodec: Codec[WaitForRev] = ("sentAfterLocalCommitIndex" | uint64overflow).as[WaitForRev]

    private val updateMessageCodec: Codec[UpdateMessage] = lengthDelimited(lightningMessageCodec.narrow[UpdateMessage](f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g))

    private val localChangesCodec: Codec[LocalChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

    private val remoteChangesCodec: Codec[RemoteChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

    private val changesCodec: Codec[CommitmentChanges] = (
      ("localChanges" | localChangesCodec) ::
        ("remoteChanges" | remoteChangesCodec) ::
        ("localNextHtlcId" | uint64overflow) ::
        ("remoteNextHtlcId" | uint64overflow)).as[CommitmentChanges]

    private val upstreamChannelCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | millisatoshi)).as[Upstream.Cold.Channel]

    private val coldUpstreamCodec: Codec[Upstream.Cold] = discriminated[Upstream.Cold].by(uint16)
      // NB: order matters!
      .typecase(0x03, upstreamChannelCodec)
      .typecase(0x02, listOfN(uint16, upstreamChannelCodec).as[Upstream.Cold.Trampoline])
      .typecase(0x01, ("id" | uuid).as[Upstream.Local])

    private val originCodec: Codec[Origin] = coldUpstreamCodec.xmap[Origin](
      upstream => Origin.Cold(upstream),
      {
        case Origin.Hot(_, upstream) => Upstream.Cold(upstream)
        case Origin.Cold(upstream) => upstream
      }
    )

    private val originsMapCodec: Codec[Map[Long, Origin]] = mapCodec(int64, originCodec)

    private val commitmentsCodec: Codec[ChannelTypes5.EncodedCommitments] = (
      ("params" | channelParamsCodec) ::
        ("changes" | changesCodec) ::
        (("htlcs" | setCodec(htlcCodec)) >>:~ { htlcs =>
          ("active" | listOfN(uint16, commitmentCodec(htlcs))) ::
            ("inactive" | listOfN(uint16, commitmentCodec(htlcs))) ::
            ("remoteNextCommitInfo" | either(bool8, waitForRevCodec, publicKey)) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
            ("originChannels" | originsMapCodec) ::
            ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))
        })).as[ChannelTypes5.EncodedCommitments]

    private val versionedCommitmentsCodec: Codec[Commitments] = discriminated[Commitments].by(uint8)
      .typecase(0x01, commitmentsCodec.xmap(_.toCommitments, c => ChannelTypes5.EncodedCommitments.fromCommitments(c)))

    // Note that we use the default commitmentSpec codec that fully encodes HTLCs. This creates some duplication, but
    // it's fine because this is a short-lived state during which the channel cannot be used for payments.
    private val interactiveTxWaitingForSigsCodec: Codec[InteractiveTxSigningSession.WaitingForSigs] = (
      ("fundingParams" | fundingParamsCodec) ::
        ("fundingTxIndex" | uint32) ::
        ("fundingTx" | partiallySignedSharedTransactionCodec) ::
        ("localCommitParams" | commitParamsCodec) ::
        ("localCommit" | either(bool8, unsignedLocalCommitCodec, localCommitCodec(commitmentSpecCodec))) ::
        ("remoteCommitParams" | commitParamsCodec) ::
        ("remoteCommit" | remoteCommitCodec(commitmentSpecCodec)) ::
        ("liquidityPurchase" | optional(bool8, liquidityPurchaseCodec))).as[InteractiveTxSigningSession.WaitingForSigs]

    val dualFundingStatusCodec: Codec[DualFundingStatus] = discriminated[DualFundingStatus].by(uint8)
      .\(0x01) { case status: DualFundingStatus if !status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs] => DualFundingStatus.WaitingForConfirmations }(provide(DualFundingStatus.WaitingForConfirmations))
      .\(0x02) { case status: DualFundingStatus.RbfWaitingForSigs => status }(interactiveTxWaitingForSigsCodec.as[DualFundingStatus.RbfWaitingForSigs])

    private val spliceStatusCodec: Codec[SpliceStatus] = discriminated[SpliceStatus].by(uint8)
      .\(0x01) { case status: SpliceStatus if !status.isInstanceOf[SpliceStatus.SpliceWaitingForSigs] => SpliceStatus.NoSplice }(provide(SpliceStatus.NoSplice))
      .\(0x02) { case status: SpliceStatus.SpliceWaitingForSigs => status }(interactiveTxWaitingForSigsCodec.as[SpliceStatus.SpliceWaitingForSigs])

    private val closingFeeratesCodec: Codec[ClosingFeerates] = (("preferred" | feeratePerKw) :: ("min" | feeratePerKw) :: ("max" | feeratePerKw)).as[ClosingFeerates]

    private val closeStatusCodec: Codec[CloseStatus] = discriminated[CloseStatus].by(uint8)
      .typecase(0x01, optional(bool8, closingFeeratesCodec).as[CloseStatus.Initiator])
      .typecase(0x02, optional(bool8, closingFeeratesCodec).as[CloseStatus.NonInitiator])

    private val closingTxCodec: Codec[ClosingTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | optional(bool8, uint32))).as[ClosingTx]

    private val closingTxsCodec: Codec[ClosingTxs] = (
      ("localAndRemote_opt" | optional(bool8, closingTxCodec)) ::
        ("localOnly_opt" | optional(bool8, closingTxCodec)) ::
        ("remoteOnly_opt" | optional(bool8, closingTxCodec))).as[ClosingTxs]

    private val closingTxProposedCodec: Codec[ClosingTxProposed] = (("unsignedTx" | closingTxCodec) :: ("localClosingSigned" | lengthDelimited(closingSignedCodec))).as[ClosingTxProposed]

    private val spentMapCodec: Codec[Map[OutPoint, Transaction]] = mapCodec(outPointCodec, txCodec)

    private val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("localOutput_opt" | optional(bool8, outPointCodec)) ::
        ("anchorOutput_opt" | optional(bool8, outPointCodec)) ::
        ("incomingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("outgoingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("htlcDelayedOutputs" | setCodec(outPointCodec)) ::
        ("irrevocablySpent" | spentMapCodec)).as[LocalCommitPublished]

    private val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("localOutput_opt" | optional(bool8, outPointCodec)) ::
        ("anchorOutput_opt" | optional(bool8, outPointCodec)) ::
        ("incomingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("outgoingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("irrevocablySpent" | spentMapCodec)).as[RemoteCommitPublished]

    private val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("localOutput_opt" | optional(bool8, outPointCodec)) ::
        ("remoteOutput_opt" | optional(bool8, outPointCodec)) ::
        ("htlcOutputs" | setCodec(outPointCodec)) ::
        ("htlcDelayedOutputs" | setCodec(outPointCodec)) ::
        ("irrevocablySpent" | spentMapCodec)).as[RevokedCommitPublished]

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_CHANNEL_READY_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("aliases" | aliases)).as[DATA_WAIT_FOR_CHANNEL_READY]

    val DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED] = (
      ("channelParams" | channelParamsCodec) ::
        ("secondRemotePerCommitmentPoint" | publicKey) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("status" | interactiveTxWaitingForSigsCodec)).as[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("waitingSince" | blockHeight) ::
        ("lastChecked" | blockHeight) ::
        ("status" | dualFundingStatusCodec) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("aliases" | aliases)).as[DATA_WAIT_FOR_DUAL_FUNDING_READY]

    val DATA_NORMAL_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("aliases" | aliases) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("spliceStatus" | spliceStatusCodec) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closeStatus" | optional(bool8, closeStatusCodec))).as[DATA_NORMAL]

    val DATA_SHUTDOWN_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closeStatus" | closeStatusCodec)).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_NEGOTIATING_SIMPLE_Codec: Codec[DATA_NEGOTIATING_SIMPLE] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("lastClosingFeerate" | feeratePerKw) ::
        ("localScriptPubKey" | varsizebinarydata) ::
        ("remoteScriptPubKey" | varsizebinarydata) ::
        ("proposedClosingTxs" | listOfN(uint16, closingTxsCodec)) ::
        ("publishedClosingTxs" | listOfN(uint16, closingTxCodec))).as[DATA_NEGOTIATING_SIMPLE]

    val DATA_CLOSING_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("finalScriptPubKey" | lengthDelimited(bytes)) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec)) ::
        ("maxClosingFeerate" | optional(bool8, feeratePerKw))).as[DATA_CLOSING]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x0b, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_Codec)
    .typecase(0x0a, Codecs.DATA_CLOSING_Codec)
    .typecase(0x09, Codecs.DATA_NEGOTIATING_SIMPLE_Codec)
    .typecase(0x08, Codecs.DATA_NEGOTIATING_Codec)
    .typecase(0x07, Codecs.DATA_SHUTDOWN_Codec)
    .typecase(0x06, Codecs.DATA_NORMAL_Codec)
    .typecase(0x05, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_READY_Codec)
    .typecase(0x04, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_Codec)
    .typecase(0x03, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_Codec)
    .typecase(0x02, Codecs.DATA_WAIT_FOR_CHANNEL_READY_Codec)
    .typecase(0x01, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_Codec)

}
