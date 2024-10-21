package fr.acinq.eclair.wire.internal.channel.version4

import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.scalacompat.Crypto.{PublicKey, XonlyPublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{ByteVector64, OutPoint, ScriptWitness, Transaction, TxOut}
import fr.acinq.eclair.blockchain.fee.{ConfirmationPriority, ConfirmationTarget}
import fr.acinq.eclair.channel.LocalFundingStatus._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{FullySignedSharedTransaction, PartiallySignedSharedTransaction}
import fr.acinq.eclair.channel.fund.InteractiveTxSigningSession.UnsignedLocalCommit
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, BlockHeight, FeatureSupport, Features, MilliSatoshiLong, PermanentChannelFeature, RealShortChannelId, channel}
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
        // We pad to keep codecs byte-aligned.
        ("isChannelOpener" | bool) :: ("paysCommitTxFees" | bool) :: ignore(6) ::
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

    private def baseCommitmentSpecCodec(directedHtlcCodec: Codec[DirectedHtlc]): Codec[CommitmentSpec] = (
      ("htlcs" | setCodec(directedHtlcCodec)) ::
        ("feeratePerKw" | feeratePerKw) ::
        ("toLocal" | millisatoshi) ::
        ("toRemote" | millisatoshi)).as[CommitmentSpec]

    /** HTLCs are stored separately to avoid duplicating data. */
    def minimalCommitmentSpecCodec(htlcs: Set[DirectedHtlc]): Codec[CommitmentSpec] = baseCommitmentSpecCodec(minimalDirectedHtlcCodec(htlcs))

    /** HTLCs are stored in full, the codec is stateless but creates duplication between local/remote commitment, and across commitments. */
    val commitmentSpecCodec: Codec[CommitmentSpec] = baseCommitmentSpecCodec(htlcCodec)

    val outPointCodec: Codec[OutPoint] = lengthDelimited(bytes.xmap(d => OutPoint.read(d.toArray), d => OutPoint.write(d)))

    val txOutCodec: Codec[TxOut] = lengthDelimited(bytes.xmap(d => TxOut.read(d.toArray), d => TxOut.write(d)))

    val txCodec: Codec[Transaction] = lengthDelimited(bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

    val scriptTreeCodec: Codec[ScriptTree] = lengthDelimited(bytes.xmap(d => ScriptTree.read(new ByteArrayInput(d.toArray)), d => ByteVector.view(d.write())))

    case class ScriptTreeAndInternalKey(scriptTree: Option[ScriptTree], internalKey: XonlyPublicKey)

    val xonlyPublicKey: Codec[XonlyPublicKey] = publicKey.xmap(p => p.xOnly, x => x.publicKey)

    val scriptTreeAndInternalKey: Codec[ScriptTreeAndInternalKey] = (optional(bool8, scriptTreeCodec) :: xonlyPublicKey).as[ScriptTreeAndInternalKey]

    private case class InputInfoEx(outPoint: OutPoint, txOut: TxOut, redeemScript: ByteVector, redeemScriptOrScriptTree: Either[ByteVector, ScriptTreeAndInternalKey])

    // To support the change from redeemScript to "either redeem script or script tree" while remaining backwards-compatible with the previous version 4 codec, we use
    // the redeem script itself as a left/write indicator: empty -> right, not empty -> left
    private def scriptOrTreeCodec(redeemScript: ByteVector): Codec[Either[ByteVector, ScriptTreeAndInternalKey]] = either(provide(redeemScript.isEmpty), provide(redeemScript), scriptTreeAndInternalKey)

    private val inputInfoExCodec: Codec[InputInfoEx] = {
      ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        (("redeemScript" | lengthDelimited(bytes)) >>:~ { redeemScript => scriptOrTreeCodec(redeemScript).hlist })
    }.as[InputInfoEx]

    val inputInfoCodec: Codec[InputInfo] = inputInfoExCodec.xmap(
      iex => iex.redeemScriptOrScriptTree match {
        case Left(redeemScript) => InputInfo.SegwitInput(iex.outPoint, iex.txOut, redeemScript)
        case Right(scriptTreeAndInternalKey) => InputInfo.TaprootInput(iex.outPoint, iex.txOut, scriptTreeAndInternalKey.internalKey, scriptTreeAndInternalKey.scriptTree)
      },
      i => i match {
        case InputInfo.SegwitInput(_, _, redeemScript) => InputInfoEx(i.outPoint, i.txOut, redeemScript, Left(redeemScript))
        case InputInfo.TaprootInput(_, _, internalKey, scriptTree_opt) => InputInfoEx(i.outPoint, i.txOut, ByteVector.empty, Right(ScriptTreeAndInternalKey(scriptTree_opt, internalKey)))
      }
    )

    val outputInfoCodec: Codec[OutputInfo] = (
      ("index" | uint32) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).as[OutputInfo]

    private val defaultConfirmationTarget: Codec[ConfirmationTarget.Absolute] = provide(ConfirmationTarget.Absolute(BlockHeight(0)))
    private val blockHeightConfirmationTarget: Codec[ConfirmationTarget.Absolute] = blockHeight.xmap(ConfirmationTarget.Absolute, _.confirmBefore)
    private val confirmationPriority: Codec[ConfirmationPriority] = discriminated[ConfirmationPriority].by(uint8)
      .typecase(0x01, provide(ConfirmationPriority.Slow))
      .typecase(0x02, provide(ConfirmationPriority.Medium))
      .typecase(0x03, provide(ConfirmationPriority.Fast))
    private val priorityConfirmationTarget: Codec[ConfirmationTarget.Priority] = confirmationPriority.xmap(ConfirmationTarget.Priority, _.priority)
    private val confirmationTarget: Codec[ConfirmationTarget] = discriminated[ConfirmationTarget].by(uint8)
      .typecase(0x00, blockHeightConfirmationTarget)
      .typecase(0x01, priorityConfirmationTarget)

    val commitTxCodec: Codec[CommitTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]
    val htlcSuccessTxCodec: Codec[HtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | blockHeightConfirmationTarget)).as[HtlcSuccessTx]
    val htlcTimeoutTxCodec: Codec[HtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | blockHeightConfirmationTarget)).as[HtlcTimeoutTx]
    private val htlcSuccessTxNoConfirmCodec: Codec[HtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | defaultConfirmationTarget)).as[HtlcSuccessTx]
    private val htlcTimeoutTxNoConfirmCodec: Codec[HtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | defaultConfirmationTarget)).as[HtlcTimeoutTx]
    val htlcDelayedTxCodec: Codec[HtlcDelayedTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcDelayedTx]
    private val legacyClaimHtlcSuccessTxCodec: Codec[LegacyClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | defaultConfirmationTarget)).as[LegacyClaimHtlcSuccessTx]
    val claimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | blockHeightConfirmationTarget)).as[ClaimHtlcSuccessTx]
    val claimHtlcTimeoutTxCodec: Codec[ClaimHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | blockHeightConfirmationTarget)).as[ClaimHtlcTimeoutTx]
    private val claimHtlcSuccessTxNoConfirmCodec: Codec[ClaimHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | defaultConfirmationTarget)).as[ClaimHtlcSuccessTx]
    private val claimHtlcTimeoutTxNoConfirmCodec: Codec[ClaimHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("htlcId" | uint64overflow) :: ("confirmationTarget" | defaultConfirmationTarget)).as[ClaimHtlcTimeoutTx]
    val claimLocalDelayedOutputTxCodec: Codec[ClaimLocalDelayedOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimLocalDelayedOutputTx]
    val claimP2WPKHOutputTxCodec: Codec[ClaimP2WPKHOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimP2WPKHOutputTx]
    val claimRemoteDelayedOutputTxCodec: Codec[ClaimRemoteDelayedOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimRemoteDelayedOutputTx]
    val mainPenaltyTxCodec: Codec[MainPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[MainPenaltyTx]
    val htlcPenaltyTxCodec: Codec[HtlcPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[HtlcPenaltyTx]
    val claimHtlcDelayedOutputPenaltyTxCodec: Codec[ClaimHtlcDelayedOutputPenaltyTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimHtlcDelayedOutputPenaltyTx]
    val claimLocalAnchorOutputTxCodec: Codec[ClaimLocalAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmationTarget" | confirmationTarget)).as[ClaimLocalAnchorOutputTx]
    private val claimLocalAnchorOutputTxBlockHeightConfirmCodec: Codec[ClaimLocalAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmationTarget" | blockHeightConfirmationTarget).upcast[ConfirmationTarget]).as[ClaimLocalAnchorOutputTx]
    private val claimLocalAnchorOutputTxNoConfirmCodec: Codec[ClaimLocalAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmationTarget" | defaultConfirmationTarget).upcast[ConfirmationTarget]).as[ClaimLocalAnchorOutputTx]
    private val claimRemoteAnchorOutputTxCodec: Codec[ClaimRemoteAnchorOutputTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[ClaimRemoteAnchorOutputTx]
    val closingTxCodec: Codec[ClosingTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | optional(bool8, outputInfoCodec))).as[ClosingTx]

    val claimRemoteCommitMainOutputTxCodec: Codec[ClaimRemoteCommitMainOutputTx] = discriminated[ClaimRemoteCommitMainOutputTx].by(uint8)
      .typecase(0x01, claimP2WPKHOutputTxCodec)
      .typecase(0x02, claimRemoteDelayedOutputTxCodec)

    val claimAnchorOutputTxCodec: Codec[ClaimAnchorOutputTx] = discriminated[ClaimAnchorOutputTx].by(uint8)
      // Important: order matters!
      .typecase(0x12, claimLocalAnchorOutputTxCodec)
      .typecase(0x11, claimLocalAnchorOutputTxBlockHeightConfirmCodec)
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
    
    // remoteSig is now either a signature or a partial signature with nonce. To retain compatibility with the previous codec, we use remoteSig as a left/right indicator,
    // a value of all zeroes meaning right (a valid signature cannot be all zeroes)
     val commitTxAndRemoteSigCodec: Codec[CommitTxAndRemoteSig] = (
      commitTxCodec :: bytes64.consume {
        sig => if (sig == ByteVector64.Zeroes)
          partialSignatureWithNonce.as[RemoteSignature.PartialSignatureWithNonce].upcast[RemoteSignature]
        else
          provide(RemoteSignature.FullSignature(sig)).upcast[RemoteSignature]
      } {
        case RemoteSignature.FullSignature(sig) => sig
        case _: RemoteSignature.PartialSignatureWithNonce => ByteVector64.Zeroes
      }
      ).as[CommitTxAndRemoteSig]

    val updateMessageCodec: Codec[UpdateMessage] = lengthDelimited(lightningMessageCodec.narrow[UpdateMessage](f => Attempt.successful(f.asInstanceOf[UpdateMessage]), g => g))

    val localChangesCodec: Codec[LocalChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec))).as[LocalChanges]

    val remoteChangesCodec: Codec[RemoteChanges] = (
      ("proposed" | listOfN(uint16, updateMessageCodec)) ::
        ("acked" | listOfN(uint16, updateMessageCodec)) ::
        ("signed" | listOfN(uint16, updateMessageCodec))).as[RemoteChanges]

    val upstreamLocalCodec: Codec[Upstream.Local] = ("id" | uuid).as[Upstream.Local]

    val upstreamChannelCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | millisatoshi)).as[Upstream.Cold.Channel]

    val legacyUpstreamChannelCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | millisatoshi) ::
        ("amountOut" | ignore(64))).as[Upstream.Cold.Channel]

    val upstreamChannelWithoutAmountCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | provide(0 msat))).as[Upstream.Cold.Channel]

    val legacyUpstreamTrampolineCodec: Codec[Upstream.Cold.Trampoline] = listOfN(uint16, upstreamChannelWithoutAmountCodec).as[Upstream.Cold.Trampoline]

    val upstreamTrampolineCodec: Codec[Upstream.Cold.Trampoline] = listOfN(uint16, upstreamChannelCodec).as[Upstream.Cold.Trampoline]

    val coldUpstreamCodec: Codec[Upstream.Cold] = discriminated[Upstream.Cold].by(uint16)
      // NB: order matters!
      .typecase(0x06, upstreamChannelCodec)
      .typecase(0x05, upstreamTrampolineCodec)
      .typecase(0x04, legacyUpstreamTrampolineCodec)
      .typecase(0x03, upstreamLocalCodec)
      .typecase(0x02, legacyUpstreamChannelCodec)

    val originCodec: Codec[Origin] = coldUpstreamCodec.xmap[Origin](
      upstream => Origin.Cold(upstream),
      {
        case Origin.Hot(_, upstream) => Upstream.Cold(upstream)
        case Origin.Cold(upstream) => upstream
      }
    )

    def mapCodec[K, V](keyCodec: Codec[K], valueCodec: Codec[V]): Codec[Map[K, V]] = listOfN(uint16, keyCodec ~ valueCodec).xmap(_.toMap, _.toList)

    val originsMapCodec: Codec[Map[Long, Origin]] = mapCodec(int64, originCodec)

    val spentMapCodec: Codec[Map[OutPoint, Transaction]] = mapCodec(outPointCodec, txCodec)

    private val multisig2of2InputCodec: Codec[InteractiveTxBuilder.Multisig2of2Input] = (
      ("info" | inputInfoCodec) ::
        ("fundingTxIndex" | uint32) ::
        ("remoteFundingPubkey" | publicKey)).as[InteractiveTxBuilder.Multisig2of2Input]

    private val musig2of2InputCodec: Codec[InteractiveTxBuilder.Musig2Input] = (
      ("info" | inputInfoCodec) ::
        ("fundingTxIndex" | uint32) ::
        ("remoteFundingPubkey" | publicKey) ::
        ("commitIndex" | uint32)).as[InteractiveTxBuilder.Musig2Input]

    private val sharedFundingInputCodec: Codec[InteractiveTxBuilder.SharedFundingInput] = discriminated[InteractiveTxBuilder.SharedFundingInput].by(uint16)
      .typecase(0x01, multisig2of2InputCodec)
      .typecase(0x02, musig2of2InputCodec)

    private val requireConfirmedInputsCodec: Codec[InteractiveTxBuilder.RequireConfirmedInputs] = (("forLocal" | bool8) :: ("forRemote" | bool8)).as[InteractiveTxBuilder.RequireConfirmedInputs]

    private val fundingParamsCodec: Codec[InteractiveTxBuilder.InteractiveTxParams] = (
      ("channelId" | bytes32) ::
        ("isInitiator" | bool8) ::
        ("localContribution" | satoshiSigned) ::
        ("remoteContribution" | satoshiSigned) ::
        ("sharedInput_opt" | optional(bool8, sharedFundingInputCodec)) ::
        ("remoteFundingPubKey" | publicKey) ::
        ("localOutputs" | listOfN(uint16, txOutCodec)) ::
        ("lockTime" | uint32) ::
        ("dustLimit" | satoshi) ::
        ("targetFeerate" | feeratePerKw) ::
        ("requireConfirmedInputs" | requireConfirmedInputsCodec)).as[InteractiveTxBuilder.InteractiveTxParams]

    // This codec was used by a first prototype version of splicing that only worked without HTLCs.
    private val sharedInteractiveTxInputWithoutHtlcsCodec: Codec[InteractiveTxBuilder.Input.Shared] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("publicKeyScript" | provide(ByteVector.empty)) ::
        ("sequence" | uint32) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | provide(0 msat))).as[InteractiveTxBuilder.Input.Shared]

    private val sharedInteractiveTxInputWithHtlcsCodec: Codec[InteractiveTxBuilder.Input.Shared] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("publicKeyScript" | provide(ByteVector.empty)) ::
        ("sequence" | uint32) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | millisatoshi)).as[InteractiveTxBuilder.Input.Shared]

    private val sharedInteractiveTxInputWithHtlcsAndPubkeyScriptCodec: Codec[InteractiveTxBuilder.Input.Shared] = (
      ("serialId" | uint64) ::
        ("outPoint" | outPointCodec) ::
        ("publicKeyScript" | lengthDelimited(bytes)) ::
        ("sequence" | uint32) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | millisatoshi)).as[InteractiveTxBuilder.Input.Shared]

    private val sharedInteractiveTxInputCodec: Codec[InteractiveTxBuilder.Input.Shared] = discriminated[InteractiveTxBuilder.Input.Shared].by(byte)
      .typecase(0x03, sharedInteractiveTxInputWithHtlcsAndPubkeyScriptCodec)
      .typecase(0x02, sharedInteractiveTxInputWithHtlcsCodec)
      .typecase(0x01, sharedInteractiveTxInputWithoutHtlcsCodec)

    private val sharedInteractiveTxOutputWithoutHtlcsCodec: Codec[InteractiveTxBuilder.Output.Shared] = (
      ("serialId" | uint64) ::
        ("scriptPubKey" | lengthDelimited(bytes)) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | provide(0 msat))).as[InteractiveTxBuilder.Output.Shared]

    private val sharedInteractiveTxOutputWithHtlcsCodec: Codec[InteractiveTxBuilder.Output.Shared] = (
      ("serialId" | uint64) ::
        ("scriptPubKey" | lengthDelimited(bytes)) ::
        ("localAmount" | millisatoshi) ::
        ("remoteAmount" | millisatoshi) ::
        ("htlcAmount" | millisatoshi)).as[InteractiveTxBuilder.Output.Shared]

    private val sharedInteractiveTxOutputCodec: Codec[InteractiveTxBuilder.Output.Shared] = discriminated[InteractiveTxBuilder.Output.Shared].by(byte)
      .typecase(0x02, sharedInteractiveTxOutputWithHtlcsCodec)
      .typecase(0x01, sharedInteractiveTxOutputWithoutHtlcsCodec)

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

    private val scriptWitnessCodec: Codec[ScriptWitness] = listOfN(uint16, lengthDelimited(bytes)).xmap(s => ScriptWitness(s.toSeq), w => w.stack.toList)

    private val fullySignedSharedTransactionCodec: Codec[InteractiveTxBuilder.FullySignedSharedTransaction] = (
      ("sharedTx" | sharedTransactionCodec) ::
        ("localSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("remoteSigs" | lengthDelimited(txSignaturesCodec)) ::
        ("sharedSigs_opt" | optional(bool8, scriptWitnessCodec))).as[InteractiveTxBuilder.FullySignedSharedTransaction]

    private val signedSharedTransactionCodec: Codec[InteractiveTxBuilder.SignedSharedTransaction] = discriminated[InteractiveTxBuilder.SignedSharedTransaction].by(uint16)
      .typecase(0x01, partiallySignedSharedTransactionCodec)
      .typecase(0x02, fullySignedSharedTransactionCodec)

    private val liquidityFeesCodec: Codec[LiquidityAds.Fees] = (("miningFees" | satoshi) :: ("serviceFees" | satoshi)).as[LiquidityAds.Fees]

    private val liquidityPurchaseCodec: Codec[LiquidityAds.PurchaseBasicInfo] = (
      ("isBuyer" | bool8) ::
        ("amount" | satoshi) ::
        ("fees" | liquidityFeesCodec)).as[LiquidityAds.PurchaseBasicInfo]

    private val dualFundedUnconfirmedFundingTxWithoutLiquidityPurchaseCodec: Codec[DualFundedUnconfirmedFundingTx] = (
      ("sharedTx" | signedSharedTransactionCodec) ::
        ("createdAt" | blockHeight) ::
        ("fundingParams" | fundingParamsCodec) ::
        ("liquidityPurchase" | provide(Option.empty[LiquidityAds.PurchaseBasicInfo]))).as[DualFundedUnconfirmedFundingTx].xmap(
      dfu => fillSharedInputScript(dfu),
      dfu => dfu
    )

    private val dualFundedUnconfirmedFundingTxCodec: Codec[DualFundedUnconfirmedFundingTx] = (
      ("sharedTx" | signedSharedTransactionCodec) ::
        ("createdAt" | blockHeight) ::
        ("fundingParams" | fundingParamsCodec) ::
        ("liquidityPurchase" | optional(bool8, liquidityPurchaseCodec))).as[DualFundedUnconfirmedFundingTx].xmap(
      dfu => fillSharedInputScript(dfu),
      dfu => dfu
    )

    // When decoding interactive-tx from older codecs, we fill the shared input publicKeyScript if necessary.
    private def fillSharedInputScript(dfu: DualFundedUnconfirmedFundingTx): DualFundedUnconfirmedFundingTx = {
      (dfu.sharedTx.tx.sharedInput_opt, dfu.fundingParams.sharedInput_opt) match {
        case (Some(sharedTxInput), Some(sharedFundingParamsInput)) if sharedTxInput.publicKeyScript.isEmpty =>
          val sharedTxInput1 = sharedTxInput.copy(publicKeyScript = sharedFundingParamsInput.info.txOut.publicKeyScript)
          val sharedTx1 = dfu.sharedTx.tx.copy(sharedInput_opt = Some(sharedTxInput1))
          val dfu1 = dfu.sharedTx match {
            case pt: PartiallySignedSharedTransaction => dfu.copy(sharedTx = pt.copy(tx = sharedTx1))
            case ft: FullySignedSharedTransaction => dfu.copy(sharedTx = ft.copy(tx = sharedTx1))
          }
          dfu1
        case _ => dfu
      }
    }

    val fundingTxStatusCodec: Codec[LocalFundingStatus] = discriminated[LocalFundingStatus].by(uint8)
      .typecase(0x0a, (txCodec :: realshortchannelid :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[ConfirmedFundingTx])
      .typecase(0x01, optional(bool8, txCodec).as[SingleFundedUnconfirmedFundingTx])
      .typecase(0x07, dualFundedUnconfirmedFundingTxCodec)
      .typecase(0x08, (txCodec :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[ZeroconfPublishedFundingTx])
      .typecase(0x09, (txCodec :: provide(RealShortChannelId(0)) :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[ConfirmedFundingTx])
      .typecase(0x02, dualFundedUnconfirmedFundingTxWithoutLiquidityPurchaseCodec)
      .typecase(0x05, (txCodec :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ZeroconfPublishedFundingTx])
      .typecase(0x06, (txCodec :: provide(RealShortChannelId(0)) :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ConfirmedFundingTx])
      .typecase(0x03, (txCodec :: provide(Option.empty[TxSignatures]) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ZeroconfPublishedFundingTx])
      .typecase(0x04, (txCodec :: provide(RealShortChannelId(0)) :: provide(Option.empty[TxSignatures]) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ConfirmedFundingTx])

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

    private def localCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("commitTxAndRemoteSig" | commitTxAndRemoteSigCodec) ::
        ("htlcTxsAndRemoteSigs" | listOfN(uint16, htlcTxsAndRemoteSigsCodec))).as[LocalCommit]

    private def remoteCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | txId) ::
        ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

    private def nextRemoteCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[NextRemoteCommit] = (
      ("sig" | lengthDelimited(commitSigCodec)) ::
        ("commit" | remoteCommitCodec(commitmentSpecCodec))).as[NextRemoteCommit]

    private def commitmentCodecWithoutFirstRemoteCommitIndex(htlcs: Set[DirectedHtlc]): Codec[Commitment] = (
      ("fundingTxIndex" | uint32) ::
        ("firstRemoteCommitIndex" | provide(0L)) ::
        ("fundingPubKey" | publicKey) ::
        ("fundingTxStatus" | fundingTxStatusCodec) ::
        ("remoteFundingStatus" | remoteFundingStatusCodec) ::
        ("localCommit" | localCommitCodec(minimalCommitmentSpecCodec(htlcs))) ::
        ("remoteCommit" | remoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))) ::
        ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))))).as[Commitment]

    private def commitmentCodec(htlcs: Set[DirectedHtlc]): Codec[Commitment] = (
      ("fundingTxIndex" | uint32) ::
        ("firstRemoteCommitIndex" | uint64overflow) ::
        ("fundingPubKey" | publicKey) ::
        ("fundingTxStatus" | fundingTxStatusCodec) ::
        ("remoteFundingStatus" | remoteFundingStatusCodec) ::
        ("localCommit" | localCommitCodec(minimalCommitmentSpecCodec(htlcs))) ::
        ("remoteCommit" | remoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))) ::
        ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))))).as[Commitment]

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
                                  inactive: List[Commitment],
                                  remoteNextCommitInfo: Either[WaitForRev, PublicKey],
                                  remotePerCommitmentSecrets: ShaChain,
                                  originChannels: Map[Long, Origin],
                                  remoteChannelData_opt: Option[ByteVector]) {
      def toCommitments: Commitments = {
        Commitments(
          params = params,
          changes = changes,
          active = active,
          inactive = inactive,
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
        // All active commitments have the same htlc set, but each inactive commitment may have a distinct htlc set
        val commitmentsSet = (commitments.active.head +: commitments.inactive).toSet
        val htlcs = commitmentsSet.flatMap(_.localCommit.spec.htlcs) ++
          commitmentsSet.flatMap(_.remoteCommit.spec.htlcs.map(_.opposite)) ++
          commitmentsSet.flatMap(_.nextRemoteCommit_opt.toList.flatMap(_.commit.spec.htlcs.map(_.opposite)))
        EncodedCommitments(
          params = commitments.params,
          changes = commitments.changes,
          htlcs = htlcs,
          active = commitments.active.toList,
          inactive = commitments.inactive.toList,
          remoteNextCommitInfo = commitments.remoteNextCommitInfo,
          remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets,
          originChannels = commitments.originChannels,
          remoteChannelData_opt = commitments.remoteChannelData_opt
        )
      }
    }

    val commitmentsCodecWithoutFirstRemoteCommitIndex: Codec[Commitments] = (
      ("params" | paramsCodec) ::
        ("changes" | changesCodec) ::
        (("htlcs" | setCodec(htlcCodec)) >>:~ { htlcs =>
          ("active" | listOfN(uint16, commitmentCodecWithoutFirstRemoteCommitIndex(htlcs))) ::
            ("inactive" | listOfN(uint16, commitmentCodecWithoutFirstRemoteCommitIndex(htlcs))) ::
            ("remoteNextCommitInfo" | either(bool8, waitForRevCodec, publicKey)) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
            ("originChannels" | originsMapCodec) ::
            ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))
        })).as[EncodedCommitments].xmap(
      encoded => encoded.toCommitments,
      commitments => EncodedCommitments(commitments)
    )

    val commitmentsCodec: Codec[Commitments] = (
      ("params" | paramsCodec) ::
        ("changes" | changesCodec) ::
        (("htlcs" | setCodec(htlcCodec)) >>:~ { htlcs =>
          ("active" | listOfN(uint16, commitmentCodec(htlcs))) ::
            ("inactive" | listOfN(uint16, commitmentCodec(htlcs))) ::
            ("remoteNextCommitInfo" | either(bool8, waitForRevCodec, publicKey)) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
            ("originChannels" | originsMapCodec) ::
            ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))
        })).as[EncodedCommitments].xmap(
      encoded => encoded.toCommitments,
      commitments => EncodedCommitments(commitments)
    )

    val versionedCommitmentsCodec: Codec[Commitments] = discriminated[Commitments].by(uint8)
      .typecase(0x01, commitmentsCodec)

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

    // We don't bother removing the duplication across HTLCs: this is a short-lived state during which the channel
    // cannot be used for payments.
    private val (interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec, interactiveTxWaitingForSigsCodec): (Codec[InteractiveTxSigningSession.WaitingForSigs], Codec[InteractiveTxSigningSession.WaitingForSigs]) = {
      val unsignedLocalCommitCodec: Codec[UnsignedLocalCommit] = (
        ("index" | uint64overflow) ::
          ("spec" | commitmentSpecCodec) ::
          ("commitTx" | commitTxCodec) ::
          ("htlcTxs" | listOfN(uint16, htlcTxCodec))).as[UnsignedLocalCommit]

      val waitingForSigsWithoutLiquidityPurchaseCodec: Codec[InteractiveTxSigningSession.WaitingForSigs] = (
        ("fundingParams" | fundingParamsCodec) ::
          ("fundingTxIndex" | uint32) ::
          ("fundingTx" | partiallySignedSharedTransactionCodec) ::
          ("localCommit" | either(bool8, unsignedLocalCommitCodec, localCommitCodec(commitmentSpecCodec))) ::
          ("remoteCommit" | remoteCommitCodec(commitmentSpecCodec)) ::
          ("liquidityPurchase" | provide(Option.empty[LiquidityAds.PurchaseBasicInfo]))).as[InteractiveTxSigningSession.WaitingForSigs]

      val waitingForSigsCodec: Codec[InteractiveTxSigningSession.WaitingForSigs] = (
        ("fundingParams" | fundingParamsCodec) ::
          ("fundingTxIndex" | uint32) ::
          ("fundingTx" | partiallySignedSharedTransactionCodec) ::
          ("localCommit" | either(bool8, unsignedLocalCommitCodec, localCommitCodec(commitmentSpecCodec))) ::
          ("remoteCommit" | remoteCommitCodec(commitmentSpecCodec)) ::
          ("liquidityPurchase" | optional(bool8, liquidityPurchaseCodec))).as[InteractiveTxSigningSession.WaitingForSigs]

      (waitingForSigsWithoutLiquidityPurchaseCodec, waitingForSigsCodec)
    }

    val dualFundingStatusCodec: Codec[DualFundingStatus] = discriminated[DualFundingStatus].by(uint8)
      .\(0x01) { case status: DualFundingStatus if !status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs] => DualFundingStatus.WaitingForConfirmations }(provide(DualFundingStatus.WaitingForConfirmations))
      .\(0x03) { case status: DualFundingStatus.RbfWaitingForSigs => status }(interactiveTxWaitingForSigsCodec.as[DualFundingStatus.RbfWaitingForSigs])
      .\(0x02) { case status: DualFundingStatus.RbfWaitingForSigs => status }(interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec.as[DualFundingStatus.RbfWaitingForSigs])

    val spliceStatusCodec: Codec[SpliceStatus] = discriminated[SpliceStatus].by(uint8)
      .\(0x01) { case status: SpliceStatus if !status.isInstanceOf[SpliceStatus.SpliceWaitingForSigs] => SpliceStatus.NoSplice }(provide(SpliceStatus.NoSplice))
      .\(0x03) { case status: SpliceStatus.SpliceWaitingForSigs => status }(interactiveTxWaitingForSigsCodec.as[channel.SpliceStatus.SpliceWaitingForSigs])
      .\(0x02) { case status: SpliceStatus.SpliceWaitingForSigs => status }(interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec.as[channel.SpliceStatus.SpliceWaitingForSigs])

    private val shortids: Codec[ChannelTypes4.ShortIds] = (
      ("real_opt" | optional(bool8, realshortchannelid)) ::
        ("localAlias" | discriminated[Alias].by(uint16).typecase(1, alias)) ::
        ("remoteAlias_opt" | optional(bool8, alias))
      ).as[ChannelTypes4.ShortIds].decodeOnly

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_00_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("waitingSince" | blockHeight) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_0a_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).as[DATA_WAIT_FOR_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_CHANNEL_READY_01_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("shortIds" | shortids)).as[ChannelTypes4.DATA_WAIT_FOR_CHANNEL_READY_0b].map(_.migrate()).decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_0b_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("shortIds" | shortids)).as[ChannelTypes4.DATA_WAIT_FOR_CHANNEL_READY_0b].map(_.migrate()).decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_15_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("aliases" | aliases)).as[DATA_WAIT_FOR_CHANNEL_READY]

    val DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_09_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED] = (
      ("channelParams" | paramsCodec) ::
        ("secondRemotePerCommitmentPoint" | publicKey) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("status" | interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec) ::
        ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))).as[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED]

    val DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_13_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED] = (
      ("channelParams" | paramsCodec) ::
        ("secondRemotePerCommitmentPoint" | publicKey) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("status" | interactiveTxWaitingForSigsCodec) ::
        ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))).as[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_02_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("waitingSince" | blockHeight) ::
        ("lastChecked" | blockHeight) ::
        ("status" | dualFundingStatusCodec) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_0c_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("waitingSince" | blockHeight) ::
        ("lastChecked" | blockHeight) ::
        ("status" | dualFundingStatusCodec) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_03_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("shortIds" | shortids)).as[ChannelTypes4.DATA_WAIT_FOR_DUAL_FUNDING_READY_0d].map(_.migrate()).decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_0d_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("shortIds" | shortids)).as[ChannelTypes4.DATA_WAIT_FOR_DUAL_FUNDING_READY_0d].map(_.migrate()).decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_16_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("aliases" | aliases)).as[DATA_WAIT_FOR_DUAL_FUNDING_READY]

    val DATA_NORMAL_04_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("shortids" | shortids) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec)) ::
        ("spliceStatus" | spliceStatusCodec)).as[ChannelTypes4.DATA_NORMAL_0e].map(_.migrate()).decodeOnly

    val DATA_NORMAL_0e_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("shortids" | shortids) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec)) ::
        ("spliceStatus" | spliceStatusCodec)).as[ChannelTypes4.DATA_NORMAL_0e].map(_.migrate()).decodeOnly

    val DATA_NORMAL_14_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("aliases" | aliases) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec)) ::
        ("spliceStatus" | spliceStatusCodec)).as[DATA_NORMAL]

    val DATA_SHUTDOWN_05_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_0f_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec))).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_06_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_NEGOTIATING_10_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    private val closingTxsCodec: Codec[ClosingTxs] = (
      ("localAndRemote_opt" | optional(bool8, closingTxCodec)) ::
        ("localOnly_opt" | optional(bool8, closingTxCodec)) ::
        ("remoteOnly_opt" | optional(bool8, closingTxCodec))).as[ClosingTxs]

    val DATA_NEGOTIATING_SIMPLE_17_Codec: Codec[DATA_NEGOTIATING_SIMPLE] = (
      ("commitments" | commitmentsCodec) ::
        ("lastClosingFeerate" | feeratePerKw) ::
        ("localScriptPubKey" | varsizebinarydata) ::
        ("remoteScriptPubKey" | varsizebinarydata) ::
        ("proposedClosingTxs" | listOfN(uint16, closingTxsCodec)) ::
        ("publishedClosingTxs" | listOfN(uint16, closingTxCodec))).as[DATA_NEGOTIATING_SIMPLE]

    val DATA_CLOSING_07_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("waitingSince" | blockHeight) ::
        ("finalScriptPubKey" | lengthDelimited(bytes)) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).as[DATA_CLOSING]

    val DATA_CLOSING_11_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | versionedCommitmentsCodec) ::
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
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_12_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x17, Codecs.DATA_NEGOTIATING_SIMPLE_17_Codec)
    .typecase(0x16, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_READY_16_Codec)
    .typecase(0x15, Codecs.DATA_WAIT_FOR_CHANNEL_READY_15_Codec)
    .typecase(0x14, Codecs.DATA_NORMAL_14_Codec)
    .typecase(0x13, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_13_Codec)
    .typecase(0x12, Codecs.DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_12_Codec)
    .typecase(0x11, Codecs.DATA_CLOSING_11_Codec)
    .typecase(0x10, Codecs.DATA_NEGOTIATING_10_Codec)
    .typecase(0x0f, Codecs.DATA_SHUTDOWN_0f_Codec)
    .typecase(0x0e, Codecs.DATA_NORMAL_0e_Codec)
    .typecase(0x0d, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_READY_0d_Codec)
    .typecase(0x0c, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_0c_Codec)
    .typecase(0x0b, Codecs.DATA_WAIT_FOR_CHANNEL_READY_0b_Codec)
    .typecase(0x0a, Codecs.DATA_WAIT_FOR_FUNDING_CONFIRMED_0a_Codec)
    .typecase(0x09, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_09_Codec)
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