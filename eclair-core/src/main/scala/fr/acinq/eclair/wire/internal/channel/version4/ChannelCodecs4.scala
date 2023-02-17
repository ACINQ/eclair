package fr.acinq.eclair.wire.internal.channel.version4

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{OutPoint, ScriptWitness, Transaction, TxOut}
import fr.acinq.eclair.channel.LocalFundingStatus._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.{UpdateAddHtlc, UpdateMessage}
import fr.acinq.eclair.{BlockHeight, FeatureSupport, Features, PermanentChannelFeature}
import scodec.bits.{BitVector, ByteVector}
import scodec.codecs._
import scodec.{Attempt, Codec}

private[channel] object ChannelCodecs4 {

  private[version4] object Codecs {

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
        ("upfrontShutdownScript_opt" | optional(bool8, lengthDelimited(bytes))) ::
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

    def minimalHtlcCodec(htlcs: Set[UpdateAddHtlc]): Codec[UpdateAddHtlc] = uint64overflow.xmap[UpdateAddHtlc](id => htlcs.find(_.id == id).get, _.id)

    def minimalDirectedHtlcCodec(htlcs: Set[DirectedHtlc]): Codec[DirectedHtlc] = discriminated[DirectedHtlc].by(bool8)
      .typecase(true, minimalHtlcCodec(htlcs.collect(DirectedHtlc.incoming)).as[IncomingHtlc])
      .typecase(false, minimalHtlcCodec(htlcs.collect(DirectedHtlc.outgoing)).as[OutgoingHtlc])

    /** HTLCs are stored separately to avoid duplicating data. */
    def commitmentSpecCodec(htlcs: Set[DirectedHtlc]): Codec[CommitmentSpec] = (
      ("htlcs" | setCodec(minimalDirectedHtlcCodec(htlcs))) ::
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

    val updateMessageCodec: Codec[UpdateMessage] = lengthDelimited(lightningMessageCodec.narrow[UpdateMessage](f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g))

    val localChangesCodec: Codec[LocalChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

    val remoteChangesCodec: Codec[RemoteChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

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

    private val multisig2of2InputCodec: Codec[InteractiveTxBuilder.Multisig2of2Input] = (
      ("info" | inputInfoCodec) ::
        ("localFundingPubkey" | publicKey) ::
        ("remoteFundingPubkey" | publicKey)).as[InteractiveTxBuilder.Multisig2of2Input]

    private val sharedFundingInputCodec: Codec[InteractiveTxBuilder.SharedFundingInput] = discriminated[InteractiveTxBuilder.SharedFundingInput].by(uint16)
      .typecase(0x01, multisig2of2InputCodec)

    private val requireConfirmedInputsCodec: Codec[InteractiveTxBuilder.RequireConfirmedInputs] = (("forLocal" | bool8) :: ("forRemote" | bool8)).as[InteractiveTxBuilder.RequireConfirmedInputs]

    private val fundingParamsCodec: Codec[InteractiveTxBuilder.InteractiveTxParams] = (
      ("channelId" | bytes32) ::
        ("isInitiator" | bool8) ::
        ("localAmount" | satoshi) ::
        ("remoteAmount" | satoshi) ::
        ("sharedInput_opt" | optional(bool8, sharedFundingInputCodec)) ::
        ("fundingPubkeyScript" | lengthDelimited(bytes)) ::
        ("localOutputs" | listOfN(uint16, txOutCodec)) ::
        ("lockTime" | uint32) ::
        ("dustLimit" | satoshi) ::
        ("targetFeerate" | feeratePerKw) ::
        ("minDepth_opt" | optional(bool8, uint32)) ::
        ("requireConfirmedInputs" | requireConfirmedInputsCodec)).as[InteractiveTxBuilder.InteractiveTxParams]

    private val sharedInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Shared] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("sequence" | uint32) ::
        ("localAmount" | satoshi) ::
        ("remoteAmount" | satoshi)).as[InteractiveTxBuilder.Input.Shared]

    private val sharedInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Shared] = (
      ("serialId" | uint64) ::
        ("scriptPubKey" | lengthDelimited(bytes)) ::
        ("localAmount" | satoshi) ::
        ("remoteAmount" | satoshi)).as[InteractiveTxBuilder.Output.Shared]

    private val localInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Local] = (
      ("serialId" | uint64) ::
        ("previousTx" | txCodec) ::
        ("previousTxOutput" | uint32) ::
        ("sequence" | uint32)).as[InteractiveTxBuilder.Input.Local]

    private val remoteInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Remote] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("sequence" | uint32)).as[InteractiveTxBuilder.Input.Remote]

    private val localInteractiveTxChangeOutputCodec: Codec[InteractiveTxBuilder.Output.Local.Change] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[InteractiveTxBuilder.Output.Local.Change]

    private val localInteractiveTxNonChangeOutputCodec: Codec[InteractiveTxBuilder.Output.Local.NonChange] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[InteractiveTxBuilder.Output.Local.NonChange]

    private val localInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Local] = discriminated[InteractiveTxBuilder.Output.Local].by(uint16)
      .typecase(0x01, localInteractiveTxChangeOutputCodec)
      .typecase(0x02, localInteractiveTxNonChangeOutputCodec)

    private val remoteInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Remote] = (
      ("serialId" | uint64) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[InteractiveTxBuilder.Output.Remote]

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

    private val scriptWitnessCodec: Codec[ScriptWitness] = listOfN(uint16, lengthDelimited(bytes)).xmap(s => ScriptWitness(s.toSeq), w => w.stack.toList)

    private val fullySignedSharedTransactionCodec: Codec[InteractiveTxBuilder.FullySignedSharedTransaction] = (
      ("sharedTx" | sharedTransactionCodec) ::
        ("localSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("remoteSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("sharedSigs_opt" | optional(bool8, scriptWitnessCodec))).as[InteractiveTxBuilder.FullySignedSharedTransaction]

    private val signedSharedTransactionCodec: Codec[InteractiveTxBuilder.SignedSharedTransaction] = discriminated[InteractiveTxBuilder.SignedSharedTransaction].by(uint16)
      .typecase(0x01, partiallySignedSharedTransactionCodec)
      .typecase(0x02, fullySignedSharedTransactionCodec)

    private val dualFundedUnconfirmedFundingTxCodec: Codec[DualFundedUnconfirmedFundingTx] = (
      ("sharedTx" | signedSharedTransactionCodec) ::
        ("createdAt" | blockHeight) ::
        ("fundingParams" | fundingParamsCodec)).as[DualFundedUnconfirmedFundingTx]

    val fundingTxStatusCodec: Codec[LocalFundingStatus] = discriminated[LocalFundingStatus].by(uint8)
      .typecase(0x01, optional(bool8, txCodec).as[SingleFundedUnconfirmedFundingTx])
      .typecase(0x02, dualFundedUnconfirmedFundingTxCodec)
      .typecase(0x03, txCodec.as[ZeroconfPublishedFundingTx])
      .typecase(0x04, txCodec.as[ConfirmedFundingTx])

    val remoteFundingStatusCodec: Codec[RemoteFundingStatus] = discriminated[RemoteFundingStatus].by(uint8)
      .typecase(0x01, provide(RemoteFundingStatus.NotLocked))
      .typecase(0x02, provide(RemoteFundingStatus.Locked))

    val paramsCodec: Codec[ChannelParams] = (
      ("channelId" | bytes32) ::
        ("channelConfig" | channelConfigCodec) ::
        (("channelFeatures" | channelFeaturesCodec) >>:~ { channelFeatures =>
          ("localParams" | localParamsCodec(channelFeatures)) ::
            ("remoteParams" | remoteParamsCodec(channelFeatures)) ::
            ("channelFlags" | channelflags)
        })).as[ChannelParams]

    val waitForRevCodec: Codec[WaitForRev] = ("sentAfterLocalCommitIndex" | uint64overflow).as[WaitForRev]

    val changesCodec: Codec[CommitmentChanges] = (
      ("localChanges" | localChangesCodec) ::
        ("remoteChanges" | remoteChangesCodec) ::
        ("localNextHtlcId" | uint64overflow) ::
        ("remoteNextHtlcId" | uint64overflow)).as[CommitmentChanges]

    def commitmentCodec(htlcs: Set[DirectedHtlc]): Codec[Commitment] = {
      val localCommitCodec: Codec[LocalCommit] = (
        ("index" | uint64overflow) ::
          ("spec" | commitmentSpecCodec(htlcs)) ::
          ("commitTxAndRemoteSig" | commitTxAndRemoteSigCodec) ::
          ("htlcTxsAndRemoteSigs" | listOfN(uint16, htlcTxsAndRemoteSigsCodec))).as[LocalCommit]

      val remoteCommitCodec: Codec[RemoteCommit] = (
        ("index" | uint64overflow) ::
          ("spec" | commitmentSpecCodec(htlcs.map(_.opposite))) ::
          ("txid" | bytes32) ::
          ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

      val nextRemoteCommitCodec: Codec[NextRemoteCommit] = (
        ("sig" | lengthDelimited(commitSigCodec)) ::
          ("commit" | remoteCommitCodec)).as[NextRemoteCommit]

      val commitmentCodec: Codec[Commitment] = (
        ("fundingTxStatus" | fundingTxStatusCodec) ::
          ("remoteFundingStatus" | remoteFundingStatusCodec) ::
          ("localCommit" | localCommitCodec) ::
          ("remoteCommit" | remoteCommitCodec) ::
          ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec))
        ).as[Commitment]

      commitmentCodec
    }

    /**
     * When multiple commitments are active, htlcs are shared between all of these commitments.
     * There may be up to 2 * 483 = 966 htlcs, and every htlc uses at least 1452 bytes and at most 65536 bytes.
     * The resulting htlc set size is thus between 1,4 MB and 64 MB, which can be pretty large.
     * To avoid writing that htlc set multiple times to disk, we encode it separately.
     */
    case class EncodedCommitments(params: ChannelParams,
                                  changes: CommitmentChanges,
                                  // The direction we use is from our local point of view.
                                  htlcs: Set[DirectedHtlc],
                                  active: List[Commitment],
                                  remoteNextCommitInfo: Either[WaitForRev, PublicKey],
                                  remotePerCommitmentSecrets: ShaChain,
                                  originChannels: Map[Long, Origin],
                                  remoteChannelData_opt: Option[ByteVector]) {
      def toCommitments: Commitments = {
        Commitments(
          params = params,
          changes = changes,
          active = active,
          remoteNextCommitInfo,
          remotePerCommitmentSecrets,
          originChannels,
          remoteChannelData_opt
        )
      }
    }

    object EncodedCommitments {
      def apply(commitments: Commitments): EncodedCommitments = {
        // The direction we use is from our local point of view: we use sets, which deduplicates htlcs that are in both
        // local and remote commitments.
        val htlcs = commitments.active.head.localCommit.spec.htlcs ++
          commitments.active.head.remoteCommit.spec.htlcs.map(_.opposite) ++
          commitments.active.head.nextRemoteCommit_opt.map(_.commit.spec.htlcs.map(_.opposite)).getOrElse(Set.empty)
        EncodedCommitments(
          params = commitments.params,
          changes = commitments.changes,
          htlcs = htlcs,
          active = commitments.active.toList,
          remoteNextCommitInfo = commitments.remoteNextCommitInfo,
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets,
          originChannels = commitments.originChannels,
          remoteChannelData_opt = commitments.remoteChannelData_opt
        )
      }
    }

    val commitmentsCodec: Codec[Commitments] = (
      ("params" | paramsCodec) ::
        ("changes" | changesCodec) ::
        (("htlcs" | setCodec(htlcCodec)) >>:~ { htlcs =>
          ("active" | listOfN(uint16, commitmentCodec(htlcs))) ::
            ("remoteNextCommitInfo" | either(bool8, waitForRevCodec, publicKey)) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
            ("originChannels" | originsMapCodec) ::
            ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))
        })).as[EncodedCommitments].xmap(
      encoded => encoded.toCommitments,
      commitments => EncodedCommitments(commitments)
    )

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
      ("commitments" | commitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_CHANNEL_READY_01_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortIds" | shortids)).as[DATA_WAIT_FOR_CHANNEL_READY]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_02_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodec) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("waitingSince" | blockHeight) ::
        ("lastChecked" | blockHeight) ::
        ("rbfStatus" | provide[RbfStatus](RbfStatus.NoRbf)) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_03_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortIds" | shortids)).as[DATA_WAIT_FOR_DUAL_FUNDING_READY]

    val DATA_NORMAL_04_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortids" | shortids) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_NORMAL]

    val DATA_SHUTDOWN_05_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_06_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_CLOSING_07_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("finalScriptPubKey" | lengthDelimited(bytes)) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_08_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | commitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x08, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_08_Codec)
    .typecase(0x07, Codecs.DATA_CLOSING_07_Codec)
    .typecase(0x06, Codecs.DATA_NEGOTIATING_06_Codec)
    .typecase(0x05, Codecs.DATA_SHUTDOWN_05_Codec)
    .typecase(0x04, Codecs.DATA_NORMAL_04_Codec)
    .typecase(0x03, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_READY_03_Codec)
    .typecase(0x02, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_02_Codec)
    .typecase(0x01, Codecs.DATA_WAIT_FOR_CHANNEL_READY_01_Codec)
    .typecase(0x00, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_00_Codec)

}