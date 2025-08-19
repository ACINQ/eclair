package fr.acinq.eclair.wire.internal.channel.version4

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, SatoshiLong, ScriptWitness, Transaction, TxOut}
import fr.acinq.eclair.blockchain.fee.{ConfirmationPriority, ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{FullySignedSharedTransaction, PartiallySignedSharedTransaction}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import fr.acinq.eclair.wire.internal.channel.version2.ChannelTypes2
import fr.acinq.eclair.wire.internal.channel.version3.ChannelTypes3
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, CltvExpiry, CltvExpiryDelta, FeatureSupport, Features, InitFeature, MilliSatoshiLong, RealShortChannelId, channel}
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Attempt, Codec}
import shapeless.{::, HList, HNil}

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
    val channelFeaturesCodec: Codec[ChannelTypes3.ChannelFeatures] = lengthDelimited(bytes).xmap(
      (b: ByteVector) => ChannelTypes3.ChannelFeatures(Features(b).activated.keySet.collect { case f: InitFeature => f }), // we make no difference between mandatory/optional, both are considered activated
      (cf: ChannelTypes3.ChannelFeatures) => Features(cf.features.map(f => f -> FeatureSupport.Mandatory).toMap).toByteVector // we encode features as mandatory, by convention
    )

    def localParamsCodec(channelFeatures: ChannelTypes3.ChannelFeatures): Codec[ChannelTypes0.LocalParams] = (
      ("nodeId" | publicKey) ::
        ("channelPath" | keyPathCodec) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(!channelFeatures.features.contains(Features.DualFunding), satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        // We pad to keep codecs byte-aligned.
        ("isChannelOpener" | bool) :: ("paysCommitTxFees" | bool) :: ignore(6) ::
        ("upfrontShutdownScript_opt" | optional(bool8, lengthDelimited(bytes))) ::
        ("walletStaticPaymentBasepoint" | optional(provide(channelFeatures.paysDirectlyToWallet), publicKey)) ::
        ("features" | combinedFeaturesCodec)).as[ChannelTypes0.LocalParams]

    def remoteParamsCodec(channelFeatures: ChannelTypes3.ChannelFeatures): Codec[ChannelTypes4.RemoteParams] = (
      ("nodeId" | publicKey) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(!channelFeatures.features.contains(Features.DualFunding), satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("revocationBasepoint" | publicKey) ::
        ("paymentBasepoint" | publicKey) ::
        ("delayedPaymentBasepoint" | publicKey) ::
        ("htlcBasepoint" | publicKey) ::
        ("features" | combinedFeaturesCodec) ::
        ("shutdownScript" | optional(bool8, lengthDelimited(bytes)))).as[ChannelTypes4.RemoteParams]

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

    val inputInfoCodec: Codec[InputInfo] = (
      ("outPoint" | outPointCodec) ::
        ("txOut" | txOutCodec) ::
        ("redeemScript" | lengthDelimited(bytes))).map {
      case outpoint :: txOut :: _ :: HNil => InputInfo(outpoint, txOut)
    }.decodeOnly

    val outputInfoCodec: Codec[Long] = (
      ("index" | uint32) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).xmap(outputInfo => outputInfo.head, outputIndex => HList(outputIndex, 0 sat, ByteVector.empty))

    private val missingHtlcExpiry: Codec[CltvExpiry] = provide(CltvExpiry(0))
    private val missingPaymentHash: Codec[ByteVector32] = provide(ByteVector32.Zeroes)
    private val missingToSelfDelay: Codec[CltvExpiryDelta] = provide(CltvExpiryDelta(0))
    private val blockHeightConfirmationTarget: Codec[ConfirmationTarget.Absolute] = blockHeight.xmap(ConfirmationTarget.Absolute, _.confirmBefore)
    private val confirmationPriority: Codec[ConfirmationPriority] = discriminated[ConfirmationPriority].by(uint8)
      .typecase(0x01, provide(ConfirmationPriority.Slow))
      .typecase(0x02, provide(ConfirmationPriority.Medium))
      .typecase(0x03, provide(ConfirmationPriority.Fast))
    private val priorityConfirmationTarget: Codec[ConfirmationTarget.Priority] = confirmationPriority.xmap(ConfirmationTarget.Priority, _.priority)
    private val confirmationTarget: Codec[ConfirmationTarget] = discriminated[ConfirmationTarget].by(uint8)
      .typecase(0x00, blockHeightConfirmationTarget)
      .typecase(0x01, priorityConfirmationTarget)
    // Those fields have been added to our transactions after we stopped storing them in our channel data, so they're safe to ignore.
    private val unusedCommitmentFormat: Codec[CommitmentFormat] = provide(DefaultCommitmentFormat)
    private val dummyPrivateKey = PrivateKey(hex"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
    private val dummyPublicKey = dummyPrivateKey.publicKey
    private val unusedRemoteCommitKeys: Codec[RemoteCommitmentKeys] = provide(RemoteCommitmentKeys(Right(dummyPrivateKey), dummyPublicKey, dummyPublicKey, dummyPrivateKey, dummyPublicKey, dummyPublicKey))
    private val unusedLocalCommitKeys: Codec[LocalCommitmentKeys] = provide(LocalCommitmentKeys(dummyPrivateKey, dummyPublicKey, dummyPublicKey, dummyPrivateKey, dummyPublicKey, dummyPublicKey))
    private val unusedFundingKey: Codec[PrivateKey] = provide(dummyPrivateKey)
    private val unusedRevocationKey: Codec[PrivateKey] = provide(dummyPrivateKey)
    private val unusedRevokedRedeemInfo: Codec[RedeemInfo] = provide(RedeemInfo.P2wsh(Nil))

    val commitTxCodec: Codec[CommitTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec)).as[CommitTx]
    val htlcSuccessTxCodec: Codec[UnsignedHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcSuccessTx]
    val htlcTimeoutTxCodec: Codec[UnsignedHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcTimeoutTx]
    private val htlcTimeoutTxNoPaymentHashCodec: Codec[UnsignedHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcTimeoutTx]
    private val htlcSuccessTxNoConfirmCodec: Codec[UnsignedHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcSuccessTx]
    private val htlcTimeoutTxNoConfirmCodec: Codec[UnsignedHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcTimeoutTx]
    private val htlcDelayedTxNoToSelfDelayCodec: Codec[HtlcDelayedTx] = (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[HtlcDelayedTx]
    val htlcDelayedTxCodec: Codec[HtlcDelayedTx] = (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | cltvExpiryDelta) :: unusedCommitmentFormat).as[HtlcDelayedTx]
    private val legacyClaimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx]
    val claimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx]
    val claimHtlcTimeoutTxCodec: Codec[ClaimHtlcTimeoutTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[ClaimHtlcTimeoutTx]
    private val claimHtlcTimeoutTxNoPaymentHashCodec: Codec[ClaimHtlcTimeoutTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[ClaimHtlcTimeoutTx]
    private val claimHtlcSuccessTxNoConfirmCodec: Codec[ClaimHtlcSuccessTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx]
    private val claimHtlcTimeoutTxNoConfirmCodec: Codec[ClaimHtlcTimeoutTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcTimeoutTx]
    private val claimLocalDelayedOutputTxNoToSelfDelayCodec: Codec[ClaimLocalDelayedOutputTx] = (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[ClaimLocalDelayedOutputTx]
    val claimLocalDelayedOutputTxCodec: Codec[ClaimLocalDelayedOutputTx] = (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | cltvExpiryDelta) :: unusedCommitmentFormat).as[ClaimLocalDelayedOutputTx]
    val claimP2WPKHOutputTxCodec: Codec[ClaimP2WPKHOutputTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimP2WPKHOutputTx]
    val claimRemoteDelayedOutputTxCodec: Codec[ClaimRemoteDelayedOutputTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimRemoteDelayedOutputTx]
    private val mainPenaltyTxNoToSelfDelayCodec: Codec[MainPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[MainPenaltyTx]
    val mainPenaltyTxCodec: Codec[MainPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | cltvExpiryDelta) :: unusedCommitmentFormat).as[MainPenaltyTx]
    private val htlcPenaltyTxNoPaymentHashCodec: Codec[HtlcPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: unusedRevokedRedeemInfo :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[HtlcPenaltyTx]
    val htlcPenaltyTxCodec: Codec[HtlcPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: unusedRevokedRedeemInfo :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[HtlcPenaltyTx]
    private val claimHtlcDelayedOutputPenaltyTxNoToSelfDelayCodec: Codec[ClaimHtlcDelayedOutputPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[ClaimHtlcDelayedOutputPenaltyTx]
    val claimHtlcDelayedOutputPenaltyTxCodec: Codec[ClaimHtlcDelayedOutputPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | cltvExpiryDelta) :: unusedCommitmentFormat).as[ClaimHtlcDelayedOutputPenaltyTx]
    val claimLocalAnchorOutputTxCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    private val claimLocalAnchorOutputTxWithConfirmationTargetCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmationTarget" | confirmationTarget) :: unusedCommitmentFormat).map {
      case fundingKey :: commitKeys :: inputInfo :: tx :: _ :: commitmentFormat :: HNil => ClaimLocalAnchorTx(fundingKey, commitKeys, inputInfo, tx, commitmentFormat)
    }.decodeOnly
    private val claimLocalAnchorOutputTxBlockHeightConfirmCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmationTarget" | ignore(32)) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    private val claimLocalAnchorOutputTxNoConfirmCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    // We previously created an unused transaction spending the remote anchor (after the 16-blocks delay).
    private val unusedRemoteAnchorOutputTxCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    val closingTxCodec: Codec[ClosingTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | optional(bool8, outputInfoCodec))).as[ClosingTx]

    val claimRemoteCommitMainOutputTxCodec: Codec[ClaimRemoteCommitMainOutputTx] = discriminated[ClaimRemoteCommitMainOutputTx].by(uint8)
      .typecase(0x01, claimP2WPKHOutputTxCodec)
      .typecase(0x02, claimRemoteDelayedOutputTxCodec)

    val claimAnchorOutputTxCodec: Codec[ClaimLocalAnchorTx] = discriminated[ClaimLocalAnchorTx].by(uint8)
      // Important: order matters!
      .typecase(0x13, claimLocalAnchorOutputTxCodec)
      .typecase(0x12, claimLocalAnchorOutputTxWithConfirmationTargetCodec)
      .typecase(0x11, claimLocalAnchorOutputTxBlockHeightConfirmCodec)
      .typecase(0x01, claimLocalAnchorOutputTxNoConfirmCodec)
      .typecase(0x02, unusedRemoteAnchorOutputTxCodec)

    val htlcTxCodec: Codec[UnsignedHtlcTx] = discriminated[UnsignedHtlcTx].by(uint8)
      // Important: order matters!
      .typecase(0x13, htlcTimeoutTxCodec)
      .typecase(0x11, htlcSuccessTxCodec)
      .typecase(0x12, htlcTimeoutTxNoPaymentHashCodec)
      .typecase(0x01, htlcSuccessTxNoConfirmCodec)
      .typecase(0x02, htlcTimeoutTxNoConfirmCodec)

    val claimHtlcTxCodec: Codec[ClaimHtlcTx] = discriminated[ClaimHtlcTx].by(uint8)
      // Important: order matters!
      .typecase(0x24, claimHtlcTimeoutTxCodec)
      .typecase(0x22, claimHtlcTimeoutTxNoPaymentHashCodec)
      .typecase(0x23, claimHtlcSuccessTxCodec)
      .typecase(0x01, legacyClaimHtlcSuccessTxCodec)
      .typecase(0x02, claimHtlcTimeoutTxNoConfirmCodec)
      .typecase(0x03, claimHtlcSuccessTxNoConfirmCodec)

    val htlcTxsAndRemoteSigsCodec: Codec[ChannelTypes3.HtlcTxAndRemoteSig] = (
      ("txinfo" | htlcTxCodec) ::
        ("remoteSig" | bytes64)).as[ChannelTypes3.HtlcTxAndRemoteSig]

    val commitTxAndRemoteSigCodec: Codec[ChannelTypes3.CommitTxAndRemoteSig] = (
      ("commitTx" | commitTxCodec) ::
        ("remoteSig" | bytes64.as[ChannelSpendSignature.IndividualSignature])).as[ChannelTypes3.CommitTxAndRemoteSig]

    val channelSpendSignatureCodec: Codec[ChannelSpendSignature] = discriminated[ChannelSpendSignature].by(uint8)
      .typecase(0x01, bytes64.as[ChannelSpendSignature.IndividualSignature])
      .typecase(0x02, (("partialSig" | bytes32) :: ("nonce" | publicNonce)).as[ChannelSpendSignature.PartialSignatureWithNonce])

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

    private val multisig2of2InputCodec: Codec[ChannelTypes4.Multisig2of2Input] = (
      ("info" | inputInfoCodec) ::
        ("fundingTxIndex" | uint32) ::
        ("remoteFundingPubkey" | publicKey)).as[ChannelTypes4.Multisig2of2Input]

    private val sharedFundingInputCodec: Codec[ChannelTypes4.Multisig2of2Input] = discriminated[ChannelTypes4.Multisig2of2Input].by(uint16)
      .typecase(0x01, multisig2of2InputCodec)

    private val requireConfirmedInputsCodec: Codec[InteractiveTxBuilder.RequireConfirmedInputs] = (("forLocal" | bool8) :: ("forRemote" | bool8)).as[InteractiveTxBuilder.RequireConfirmedInputs]

    private val fundingParamsCodec: Codec[ChannelTypes4.InteractiveTxParams] = (
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
        ("requireConfirmedInputs" | requireConfirmedInputsCodec)).as[ChannelTypes4.InteractiveTxParams]

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

    private val dualFundedUnconfirmedFundingTxWithoutLiquidityPurchaseCodec: Codec[ChannelTypes4.DualFundedUnconfirmedFundingTx] = (
      ("sharedTx" | signedSharedTransactionCodec) ::
        ("createdAt" | blockHeight) ::
        ("fundingParams" | fundingParamsCodec) ::
        ("liquidityPurchase" | provide(Option.empty[LiquidityAds.PurchaseBasicInfo]))).as[ChannelTypes4.DualFundedUnconfirmedFundingTx].xmap(
      dfu => fillSharedInputScript(dfu),
      dfu => dfu
    )

    private val dualFundedUnconfirmedFundingTxCodec: Codec[ChannelTypes4.DualFundedUnconfirmedFundingTx] = (
      ("sharedTx" | signedSharedTransactionCodec) ::
        ("createdAt" | blockHeight) ::
        ("fundingParams" | fundingParamsCodec) ::
        ("liquidityPurchase" | optional(bool8, liquidityPurchaseCodec))).as[ChannelTypes4.DualFundedUnconfirmedFundingTx].xmap(
      dfu => fillSharedInputScript(dfu),
      dfu => dfu
    )

    // When decoding interactive-tx from older codecs, we fill the shared input publicKeyScript if necessary.
    private def fillSharedInputScript(dfu: ChannelTypes4.DualFundedUnconfirmedFundingTx): ChannelTypes4.DualFundedUnconfirmedFundingTx = {
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

    val fundingTxStatusCodec: Codec[ChannelTypes4.LocalFundingStatus] = discriminated[ChannelTypes4.LocalFundingStatus].by(uint8)
      .typecase(0x0a, (txCodec :: realshortchannelid :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[ChannelTypes4.ConfirmedFundingTx])
      .typecase(0x01, optional(bool8, txCodec).as[ChannelTypes4.SingleFundedUnconfirmedFundingTx])
      .typecase(0x07, dualFundedUnconfirmedFundingTxCodec)
      .typecase(0x08, (txCodec :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[ChannelTypes4.ZeroconfPublishedFundingTx])
      .typecase(0x09, (txCodec :: provide(RealShortChannelId(0)) :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: optional(bool8, liquidityPurchaseCodec)).as[ChannelTypes4.ConfirmedFundingTx])
      .typecase(0x02, dualFundedUnconfirmedFundingTxWithoutLiquidityPurchaseCodec)
      .typecase(0x05, (txCodec :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ChannelTypes4.ZeroconfPublishedFundingTx])
      .typecase(0x06, (txCodec :: provide(RealShortChannelId(0)) :: optional(bool8, lengthDelimited(txSignaturesCodec)) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ChannelTypes4.ConfirmedFundingTx])
      .typecase(0x03, (txCodec :: provide(Option.empty[TxSignatures]) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ChannelTypes4.ZeroconfPublishedFundingTx])
      .typecase(0x04, (txCodec :: provide(RealShortChannelId(0)) :: provide(Option.empty[TxSignatures]) :: provide(Option.empty[LiquidityAds.PurchaseBasicInfo])).as[ChannelTypes4.ConfirmedFundingTx])

    val remoteFundingStatusCodec: Codec[RemoteFundingStatus] = discriminated[RemoteFundingStatus].by(uint8)
      .typecase(0x01, provide(RemoteFundingStatus.NotLocked))
      .typecase(0x02, provide(RemoteFundingStatus.Locked))

    val paramsCodec: Codec[ChannelTypes4.ChannelParams] = (
      ("channelId" | bytes32) ::
        ("channelConfig" | channelConfigCodec) ::
        (("channelFeatures" | channelFeaturesCodec) >>:~ { channelFeatures =>
          ("localParams" | localParamsCodec(channelFeatures)) ::
            ("remoteParams" | remoteParamsCodec(channelFeatures)) ::
            ("channelFlags" | channelflags)
        })).as[ChannelTypes4.ChannelParams]

    val waitForRevCodec: Codec[WaitForRev] = ("sentAfterLocalCommitIndex" | uint64overflow).as[WaitForRev]

    val changesCodec: Codec[CommitmentChanges] = (
      ("localChanges" | localChangesCodec) ::
        ("remoteChanges" | remoteChangesCodec) ::
        ("localNextHtlcId" | uint64overflow) ::
        ("remoteNextHtlcId" | uint64overflow)).as[CommitmentChanges]

    private def localCommitWithTxsCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[ChannelTypes4.LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("commitTxAndRemoteSig" | commitTxAndRemoteSigCodec) ::
        ("htlcTxsAndRemoteSigs" | listOfN(uint16, htlcTxsAndRemoteSigsCodec))).map {
      case index :: spec :: commitTxAndRemoteSig :: htlcTxsAndRemoteSigs :: HNil =>
        ChannelTypes4.LocalCommit(index, spec, commitTxAndRemoteSig.commitTx.tx.txid, commitTxAndRemoteSig.commitTx.input, commitTxAndRemoteSig.remoteSig, htlcTxsAndRemoteSigs.map(_.remoteSig))
    }.decodeOnly

    private def localCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[ChannelTypes4.LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txId" | txId) ::
        ("input" | inputInfoCodec) ::
        ("remoteSig" | channelSpendSignatureCodec) ::
        ("htlcRemoteSigs" | listOfN(uint16, bytes64))).as[ChannelTypes4.LocalCommit]

    private def remoteCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | txId) ::
        ("remotePerCommitmentPoint" | publicKey)).as[RemoteCommit]

    private def nextRemoteCommitCodec(commitmentSpecCodec: Codec[CommitmentSpec]): Codec[NextRemoteCommit] = (
      ("sig" | lengthDelimited(commitSigCodec)) ::
        ("commit" | remoteCommitCodec(commitmentSpecCodec))).as[NextRemoteCommit]

    private def commitmentCodecWithoutFirstRemoteCommitIndex(htlcs: Set[DirectedHtlc]): Codec[ChannelTypes4.Commitment] = (
      ("fundingTxIndex" | uint32) ::
        ("firstRemoteCommitIndex" | provide(0L)) ::
        ("fundingPubKey" | publicKey) ::
        ("fundingTxStatus" | fundingTxStatusCodec) ::
        ("remoteFundingStatus" | remoteFundingStatusCodec) ::
        ("localCommit" | localCommitWithTxsCodec(minimalCommitmentSpecCodec(htlcs))) ::
        ("remoteCommit" | remoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))) ::
        ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))))).as[ChannelTypes4.Commitment]

    private def commitmentCodecWithLocalTxs(htlcs: Set[DirectedHtlc]): Codec[ChannelTypes4.Commitment] = (
      ("fundingTxIndex" | uint32) ::
        ("firstRemoteCommitIndex" | uint64overflow) ::
        ("fundingPubKey" | publicKey) ::
        ("fundingTxStatus" | fundingTxStatusCodec) ::
        ("remoteFundingStatus" | remoteFundingStatusCodec) ::
        ("localCommit" | localCommitWithTxsCodec(minimalCommitmentSpecCodec(htlcs))) ::
        ("remoteCommit" | remoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))) ::
        ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))))).as[ChannelTypes4.Commitment]

    private def commitmentCodec(htlcs: Set[DirectedHtlc]): Codec[ChannelTypes4.Commitment] = (
      ("fundingTxIndex" | uint32) ::
        ("firstRemoteCommitIndex" | uint64overflow) ::
        ("fundingPubKey" | publicKey) ::
        ("fundingTxStatus" | fundingTxStatusCodec) ::
        ("remoteFundingStatus" | remoteFundingStatusCodec) ::
        ("localCommit" | localCommitCodec(minimalCommitmentSpecCodec(htlcs))) ::
        ("remoteCommit" | remoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))) ::
        ("nextRemoteCommit_opt" | optional(bool8, nextRemoteCommitCodec(minimalCommitmentSpecCodec(htlcs.map(_.opposite)))))).as[ChannelTypes4.Commitment]

    /**
     * When multiple commitments are active, htlcs are shared between all of these commitments.
     * There may be up to 2 * 483 = 966 htlcs, and every htlc uses at least 1452 bytes and at most 65536 bytes.
     * The resulting htlc set size is thus between 1,4 MB and 64 MB, which can be pretty large.
     * To avoid writing that htlc set multiple times to disk, we encode it separately.
     */
    case class EncodedCommitments(channelParams: ChannelTypes4.ChannelParams,
                                  changes: CommitmentChanges,
                                  // The direction we use is from our local point of view.
                                  htlcs: Set[DirectedHtlc],
                                  active: List[ChannelTypes4.Commitment],
                                  inactive: List[ChannelTypes4.Commitment],
                                  remoteNextCommitInfo: Either[WaitForRev, PublicKey],
                                  remotePerCommitmentSecrets: ShaChain,
                                  originChannels: Map[Long, Origin],
                                  remoteChannelData_opt: Option[ByteVector]) {
      def toCommitments: Commitments = {
        Commitments(
          channelParams = channelParams.migrate(),
          changes = changes,
          active = active.map(_.migrate(channelParams)),
          inactive = inactive.map(_.migrate(channelParams)),
          remoteNextCommitInfo = remoteNextCommitInfo,
          remotePerCommitmentSecrets = remotePerCommitmentSecrets,
          originChannels = originChannels,
          remoteChannelData_opt = remoteChannelData_opt
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
        })).as[EncodedCommitments].map(_.toCommitments).decodeOnly

    val commitmentsCodecWithLocalTxs: Codec[Commitments] = (
      ("params" | paramsCodec) ::
        ("changes" | changesCodec) ::
        (("htlcs" | setCodec(htlcCodec)) >>:~ { htlcs =>
          ("active" | listOfN(uint16, commitmentCodecWithLocalTxs(htlcs))) ::
            ("inactive" | listOfN(uint16, commitmentCodecWithLocalTxs(htlcs))) ::
            ("remoteNextCommitInfo" | either(bool8, waitForRevCodec, publicKey)) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec)) ::
            ("originChannels" | originsMapCodec) ::
            ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))
        })).as[EncodedCommitments].map(_.toCommitments).decodeOnly

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
        })).as[EncodedCommitments].map(_.toCommitments).decodeOnly

    val versionedCommitmentsCodec: Codec[Commitments] = discriminated[Commitments].by(uint8)
      .typecase(0x02, commitmentsCodec)
      .typecase(0x01, commitmentsCodecWithLocalTxs)

    val closingFeeratesCodec: Codec[ClosingFeerates] = (
      ("preferred" | feeratePerKw) ::
        ("min" | feeratePerKw) ::
        ("max" | feeratePerKw)).as[ClosingFeerates]

    val closeStatusCodec: Codec[CloseStatus] = discriminated[CloseStatus].by(uint8)
      .typecase(0x01, optional(bool8, closingFeeratesCodec).as[CloseStatus.Initiator])
      .typecase(0x02, optional(bool8, closingFeeratesCodec).as[CloseStatus.NonInitiator])

    val closingTxProposedCodec: Codec[ClosingTxProposed] = (
      ("unsignedTx" | closingTxCodec) ::
        ("localClosingSigned" | lengthDelimited(closingSignedCodec))).as[ClosingTxProposed]

    private val localCommitPublishedCodec_07: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool8, claimLocalDelayedOutputTxNoToSelfDelayCodec)) ::
        ("htlcTxs" | mapCodec(outPointCodec, optional(bool8, htlcTxCodec))) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, htlcDelayedTxNoToSelfDelayCodec)) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.LocalCommitPublished].decodeOnly.map[LocalCommitPublished](_.migrate()).decodeOnly

    private val localCommitPublishedCodec_1a: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool8, claimLocalDelayedOutputTxCodec)) ::
        ("htlcTxs" | mapCodec(outPointCodec, optional(bool8, htlcTxCodec))) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, htlcDelayedTxCodec)) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.LocalCommitPublished].decodeOnly.map[LocalCommitPublished](_.migrate()).decodeOnly

    val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("localOutput_opt" | optional(bool8, outPointCodec)) ::
        ("anchorOutput_opt" | optional(bool8, outPointCodec)) ::
        ("incomingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("outgoingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("htlcDelayedOutputs" | setCodec(outPointCodec)) ::
        ("irrevocablySpent" | spentMapCodec)).as[LocalCommitPublished]

    private val remoteCommitPublishedCodec_07: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("claimHtlcTxs" | mapCodec(outPointCodec, optional(bool8, claimHtlcTxCodec))) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.RemoteCommitPublished].decodeOnly.map[RemoteCommitPublished](_.migrate()).decodeOnly

    val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("localOutput_opt" | optional(bool8, outPointCodec)) ::
        ("anchorOutput_opt" | optional(bool8, outPointCodec)) ::
        ("incomingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("outgoingHtlcs" | mapCodec(outPointCodec, uint64overflow)) ::
        ("irrevocablySpent" | spentMapCodec)).as[RemoteCommitPublished]

    private val revokedCommitPublishedCodec_07: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("mainPenaltyTx" | optional(bool8, mainPenaltyTxNoToSelfDelayCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, htlcPenaltyTxNoPaymentHashCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, claimHtlcDelayedOutputPenaltyTxNoToSelfDelayCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.RevokedCommitPublished].decodeOnly.map[RevokedCommitPublished](_.migrate()).decodeOnly

    private val revokedCommitPublishedCodec_1a: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("mainPenaltyTx" | optional(bool8, mainPenaltyTxCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, htlcPenaltyTxCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, claimHtlcDelayedOutputPenaltyTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.RevokedCommitPublished].decodeOnly.map[RevokedCommitPublished](_.migrate()).decodeOnly

    val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("localOutput_opt" | optional(bool8, outPointCodec)) ::
        ("remoteOutput_opt" | optional(bool8, outPointCodec)) ::
        ("htlcOutputs" | setCodec(outPointCodec)) ::
        ("htlcDelayedOutputs" | setCodec(outPointCodec)) ::
        ("irrevocablySpent" | spentMapCodec)).as[RevokedCommitPublished]

    // We don't bother removing the duplication across HTLCs: this is a short-lived state during which the channel
    // cannot be used for payments.
    private val (interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec, interactiveTxWaitingForSigsWithTxsCodec, interactiveTxWaitingForSigsCodec): (Codec[ChannelTypes4.WaitingForSigs], Codec[ChannelTypes4.WaitingForSigs], Codec[ChannelTypes4.WaitingForSigs]) = {
      val unsignedLocalCommitWithTxsCodec: Codec[ChannelTypes4.UnsignedLocalCommit] = (
        ("index" | uint64overflow) ::
          ("spec" | commitmentSpecCodec) ::
          ("commitTx" | commitTxCodec) ::
          ("htlcTxs" | listOfN(uint16, htlcTxCodec))).map {
        case index :: spec :: commitTx :: _ :: HNil => ChannelTypes4.UnsignedLocalCommit(index, spec, commitTx.tx.txid, commitTx.input)
      }.decodeOnly

      val unsignedLocalCommitCodec: Codec[ChannelTypes4.UnsignedLocalCommit] = (
        ("index" | uint64overflow) ::
          ("spec" | commitmentSpecCodec) ::
          ("txId" | txId) ::
          ("input" | inputInfoCodec)).as[ChannelTypes4.UnsignedLocalCommit]

      val waitingForSigsWithoutLiquidityPurchaseCodec: Codec[ChannelTypes4.WaitingForSigs] = (
        ("fundingParams" | fundingParamsCodec) ::
          ("fundingTxIndex" | uint32) ::
          ("fundingTx" | partiallySignedSharedTransactionCodec) ::
          ("localCommit" | either(bool8, unsignedLocalCommitWithTxsCodec, localCommitWithTxsCodec(commitmentSpecCodec))) ::
          ("remoteCommit" | remoteCommitCodec(commitmentSpecCodec)) ::
          ("liquidityPurchase" | provide(Option.empty[LiquidityAds.PurchaseBasicInfo]))).as[ChannelTypes4.WaitingForSigs]

      val waitingForSigsWithTxsCodec: Codec[ChannelTypes4.WaitingForSigs] = (
        ("fundingParams" | fundingParamsCodec) ::
          ("fundingTxIndex" | uint32) ::
          ("fundingTx" | partiallySignedSharedTransactionCodec) ::
          ("localCommit" | either(bool8, unsignedLocalCommitWithTxsCodec, localCommitWithTxsCodec(commitmentSpecCodec))) ::
          ("remoteCommit" | remoteCommitCodec(commitmentSpecCodec)) ::
          ("liquidityPurchase" | optional(bool8, liquidityPurchaseCodec))).as[ChannelTypes4.WaitingForSigs]

      val waitingForSigsCodec: Codec[ChannelTypes4.WaitingForSigs] = (
        ("fundingParams" | fundingParamsCodec) ::
          ("fundingTxIndex" | uint32) ::
          ("fundingTx" | partiallySignedSharedTransactionCodec) ::
          ("localCommit" | either(bool8, unsignedLocalCommitCodec, localCommitCodec(commitmentSpecCodec))) ::
          ("remoteCommit" | remoteCommitCodec(commitmentSpecCodec)) ::
          ("liquidityPurchase" | optional(bool8, liquidityPurchaseCodec))).as[ChannelTypes4.WaitingForSigs]

      (waitingForSigsWithoutLiquidityPurchaseCodec, waitingForSigsWithTxsCodec, waitingForSigsCodec)
    }

    def dualFundingStatusCodec(commitments: Commitments): Codec[DualFundingStatus] = discriminated[DualFundingStatus].by(uint8)
      .\(0x01) { case status: DualFundingStatus if !status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs] => DualFundingStatus.WaitingForConfirmations }(provide(DualFundingStatus.WaitingForConfirmations))
      .\(0x04) { case status: DualFundingStatus.RbfWaitingForSigs => status }(interactiveTxWaitingForSigsCodec.map(_.migrate(commitments)).decodeOnly.as[DualFundingStatus.RbfWaitingForSigs])
      .\(0x03) { case status: DualFundingStatus.RbfWaitingForSigs => status }(interactiveTxWaitingForSigsWithTxsCodec.map(_.migrate(commitments)).decodeOnly.as[DualFundingStatus.RbfWaitingForSigs])
      .\(0x02) { case status: DualFundingStatus.RbfWaitingForSigs => status }(interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec.map(_.migrate(commitments)).decodeOnly.as[DualFundingStatus.RbfWaitingForSigs])

    def spliceStatusCodec(commitments: Commitments): Codec[SpliceStatus] = discriminated[SpliceStatus].by(uint8)
      .\(0x01) { case status: SpliceStatus if !status.isInstanceOf[SpliceStatus.SpliceWaitingForSigs] => SpliceStatus.NoSplice }(provide(SpliceStatus.NoSplice))
      .\(0x04) { case status: SpliceStatus.SpliceWaitingForSigs => status }(interactiveTxWaitingForSigsCodec.map(_.migrate(commitments)).decodeOnly.as[channel.SpliceStatus.SpliceWaitingForSigs])
      .\(0x03) { case status: SpliceStatus.SpliceWaitingForSigs => status }(interactiveTxWaitingForSigsWithTxsCodec.map(_.migrate(commitments)).decodeOnly.as[channel.SpliceStatus.SpliceWaitingForSigs])
      .\(0x02) { case status: SpliceStatus.SpliceWaitingForSigs => status }(interactiveTxWaitingForSigsWithoutLiquidityPurchaseCodec.map(_.migrate(commitments)).decodeOnly.as[channel.SpliceStatus.SpliceWaitingForSigs])

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
        ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))).map {
      case channelParams :: secondRemotePerCommitmentPoint :: localPushAmount :: remotePushAmount :: status :: _ :: HNil =>
        DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(channelParams.migrate(), secondRemotePerCommitmentPoint, localPushAmount, remotePushAmount, status.migrate(channelParams))
    }.decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_13_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED] = (
      ("channelParams" | paramsCodec) ::
        ("secondRemotePerCommitmentPoint" | publicKey) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("status" | interactiveTxWaitingForSigsWithTxsCodec) ::
        ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))).map {
      case channelParams :: secondRemotePerCommitmentPoint :: localPushAmount :: remotePushAmount :: status :: _ :: HNil =>
        DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(channelParams.migrate(), secondRemotePerCommitmentPoint, localPushAmount, remotePushAmount, status.migrate(channelParams))
    }.decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_1c_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_SIGNED] = (
      ("channelParams" | paramsCodec) ::
        ("secondRemotePerCommitmentPoint" | publicKey) ::
        ("localPushAmount" | millisatoshi) ::
        ("remotePushAmount" | millisatoshi) ::
        ("status" | interactiveTxWaitingForSigsCodec) ::
        ("remoteChannelData_opt" | optional(bool8, varsizebinarydata))).map {
      case channelParams :: secondRemotePerCommitmentPoint :: localPushAmount :: remotePushAmount :: status :: _ :: HNil =>
        DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(channelParams.migrate(), secondRemotePerCommitmentPoint, localPushAmount, remotePushAmount, status.migrate(channelParams))
    }.decodeOnly

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_02_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) >>:~ { commitments =>
        ("localPushAmount" | millisatoshi) ::
          ("remotePushAmount" | millisatoshi) ::
          ("waitingSince" | blockHeight) ::
          ("lastChecked" | blockHeight) ::
          ("status" | dualFundingStatusCodec(commitments)) ::
          ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))
      }).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_0c_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = (
      ("commitments" | versionedCommitmentsCodec) >>:~ { commitments =>
        ("localPushAmount" | millisatoshi) ::
          ("remotePushAmount" | millisatoshi) ::
          ("waitingSince" | blockHeight) ::
          ("lastChecked" | blockHeight) ::
          ("status" | dualFundingStatusCodec(commitments)) ::
          ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec)))
      }).as[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]

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
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) >>:~ { commitments =>
        ("shortids" | shortids) ::
          ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
          ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
          ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("closingFeerates" | optional(bool8, closingFeeratesCodec)) ::
          ("spliceStatus" | spliceStatusCodec(commitments))
      }).as[ChannelTypes4.DATA_NORMAL_0e].map(_.migrate()).decodeOnly

    val DATA_NORMAL_0e_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | versionedCommitmentsCodec) >>:~ { commitments =>
        ("shortids" | shortids) ::
          ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
          ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
          ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("closingFeerates" | optional(bool8, closingFeeratesCodec)) ::
          ("spliceStatus" | spliceStatusCodec(commitments))
      }).as[ChannelTypes4.DATA_NORMAL_0e].map(_.migrate()).decodeOnly

    val DATA_NORMAL_14_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | versionedCommitmentsCodec) >>:~ { commitments =>
        ("aliases" | aliases) ::
          ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
          ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
          ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          // If there are closing fees defined we consider ourselves to be the closing initiator.
          ("closingFeerates" | optional(bool8, closingFeeratesCodec).map[Option[CloseStatus]](feerates_opt => Some(CloseStatus.Initiator(feerates_opt))).decodeOnly) ::
          ("spliceStatus" | spliceStatusCodec(commitments))
      }).map {
      case commitments :: aliases :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closeStatus :: spliceStatus :: HNil =>
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, spliceStatus, localShutdown, remoteShutdown, closeStatus)
    }.decodeOnly

    val DATA_NORMAL_18_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | versionedCommitmentsCodec) >>:~ { commitments =>
        ("aliases" | aliases) ::
          ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
          ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
          ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
          ("closeStatus" | optional(bool8, closeStatusCodec)) ::
          ("spliceStatus" | spliceStatusCodec(commitments))
      }).map {
      case commitments :: aliases :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closeStatus :: spliceStatus :: HNil =>
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, spliceStatus, localShutdown, remoteShutdown, closeStatus)
    }.decodeOnly

    val DATA_SHUTDOWN_05_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        // If there are closing fees defined we consider ourselves to be the closing initiator.
        ("closingFeerates" | optional(bool8, closingFeeratesCodec).map[CloseStatus](feerates_opt => CloseStatus.Initiator(feerates_opt)).decodeOnly)).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_0f_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        // If there are closing fees defined we consider ourselves to be the closing initiator.
        ("closingFeerates" | optional(bool8, closingFeeratesCodec).map[CloseStatus](feerates_opt => CloseStatus.Initiator(feerates_opt)).decodeOnly)).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_19_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closeStatus" | closeStatusCodec)).as[DATA_SHUTDOWN]

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
      ("commitments" | commitmentsCodecWithLocalTxs) ::
        ("lastClosingFeerate" | feeratePerKw) ::
        ("localScriptPubKey" | varsizebinarydata) ::
        ("remoteScriptPubKey" | varsizebinarydata) ::
        ("proposedClosingTxs" | listOfN(uint16, closingTxsCodec)) ::
        ("publishedClosingTxs" | listOfN(uint16, closingTxCodec))).as[DATA_NEGOTIATING_SIMPLE]

    val DATA_NEGOTIATING_SIMPLE_1d_Codec: Codec[DATA_NEGOTIATING_SIMPLE] = (
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
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec_07)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec_07)) ::
        ("maxClosingFeerate" | provide(Option.empty[FeeratePerKw]))).as[DATA_CLOSING]

    val DATA_CLOSING_11_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("finalScriptPubKey" | lengthDelimited(bytes)) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec_07)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec_07)) ::
        ("maxClosingFeerate" | provide(Option.empty[FeeratePerKw]))).as[DATA_CLOSING]

    val DATA_CLOSING_1a_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("waitingSince" | blockHeight) ::
        ("finalScriptPubKey" | lengthDelimited(bytes)) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec_1a)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec_07)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec_1a)) ::
        ("maxClosingFeerate" | provide(Option.empty[FeeratePerKw]))).as[DATA_CLOSING]

    val DATA_CLOSING_1b_Codec: Codec[DATA_CLOSING] = (
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
        ("maxClosingFeerate" | provide(Option.empty[FeeratePerKw]))).as[DATA_CLOSING]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_08_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | commitmentsCodecWithoutFirstRemoteCommitIndex) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_12_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | versionedCommitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
    .typecase(0x1d, Codecs.DATA_NEGOTIATING_SIMPLE_1d_Codec)
    .typecase(0x1c, Codecs.DATA_WAIT_FOR_DUAL_FUNDING_SIGNED_1c_Codec)
    .typecase(0x1b, Codecs.DATA_CLOSING_1b_Codec)
    .typecase(0x1a, Codecs.DATA_CLOSING_1a_Codec)
    .typecase(0x19, Codecs.DATA_SHUTDOWN_19_Codec)
    .typecase(0x18, Codecs.DATA_NORMAL_18_Codec)
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