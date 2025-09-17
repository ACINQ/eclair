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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Transaction, TxOut}
import fr.acinq.eclair.channel.LocalFundingStatus._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import fr.acinq.eclair.wire.internal.channel.version2.ChannelTypes2
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.UpdateMessage
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, FeatureSupport, Features, InitFeature, MilliSatoshiLong}
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Attempt, Codec, Err}
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
        ("isChannelOpener" | bool) :: ("paysCommitTxFees" | bool) :: ignore(6) ::
        ("upfrontShutdownScript_opt" | lengthDelimited(bytes).map(Option(_)).decodeOnly) ::
        ("walletStaticPaymentBasepoint" | optional(provide(channelFeatures.paysDirectlyToWallet), publicKey)) ::
        ("features" | combinedFeaturesCodec)).as[ChannelTypes0.LocalParams]

    def remoteParamsCodec(channelFeatures: ChannelTypes3.ChannelFeatures): Codec[ChannelTypes0.RemoteParams] = (
      ("nodeId" | publicKey) ::
        ("dustLimit" | satoshi) ::
        ("maxHtlcValueInFlightMsat" | uint64) ::
        ("channelReserve" | conditional(!channelFeatures.features.contains(Features.DualFunding), satoshi)) ::
        ("htlcMinimum" | millisatoshi) ::
        ("toSelfDelay" | cltvExpiryDelta) ::
        ("maxAcceptedHtlcs" | uint16) ::
        ("fundingPubKey" | publicKey) ::
        ("revocationBasepoint" | publicKey) ::
        ("paymentBasepoint" | publicKey) ::
        ("delayedPaymentBasepoint" | publicKey) ::
        ("htlcBasepoint" | publicKey) ::
        ("features" | combinedFeaturesCodec) ::
        ("shutdownScript" | optional(bool8, lengthDelimited(bytes)))).as[ChannelTypes0.RemoteParams]

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
        ("redeemScript" | lengthDelimited(bytes))).map {
      case outpoint :: txOut :: _ :: HNil => InputInfo(outpoint, txOut)
    }.decodeOnly

    val outputInfoCodec: Codec[Long] = (
      ("index" | uint32) ::
        ("amount" | satoshi) ::
        ("scriptPubKey" | lengthDelimited(bytes))).map {
      case index :: _ :: _ :: HNil => index
    }.decodeOnly

    private val missingHtlcExpiry: Codec[CltvExpiry] = provide(CltvExpiry(0))
    private val missingPaymentHash: Codec[ByteVector32] = provide(ByteVector32.Zeroes)
    private val missingToSelfDelay: Codec[CltvExpiryDelta] = provide(CltvExpiryDelta(0))
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
    val htlcTimeoutTxCodec: Codec[UnsignedHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcTimeoutTx]
    private val htlcSuccessTxNoConfirmCodec: Codec[UnsignedHtlcSuccessTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcSuccessTx]
    private val htlcTimeoutTxNoConfirmCodec: Codec[UnsignedHtlcTimeoutTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[UnsignedHtlcTimeoutTx]
    val htlcDelayedTxCodec: Codec[HtlcDelayedTx] = (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[HtlcDelayedTx]
    private val legacyClaimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx]
    val claimHtlcSuccessTxCodec: Codec[ClaimHtlcSuccessTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx]
    val claimHtlcTimeoutTxCodec: Codec[ClaimHtlcTimeoutTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | cltvExpiry) :: unusedCommitmentFormat).as[ClaimHtlcTimeoutTx]
    private val claimHtlcSuccessTxNoConfirmCodec: Codec[ClaimHtlcSuccessTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | bytes32) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcSuccessTx]
    private val claimHtlcTimeoutTxNoConfirmCodec: Codec[ClaimHtlcTimeoutTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcId" | uint64overflow) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[ClaimHtlcTimeoutTx]
    val claimLocalDelayedOutputTxCodec: Codec[ClaimLocalDelayedOutputTx] = (unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[ClaimLocalDelayedOutputTx]
    val claimP2WPKHOutputTxCodec: Codec[ClaimP2WPKHOutputTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimP2WPKHOutputTx]
    val claimRemoteDelayedOutputTxCodec: Codec[ClaimRemoteDelayedOutputTx] = (unusedRemoteCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimRemoteDelayedOutputTx]
    val mainPenaltyTxCodec: Codec[MainPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[MainPenaltyTx]
    val htlcPenaltyTxCodec: Codec[HtlcPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: unusedRevokedRedeemInfo :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("paymentHash" | missingPaymentHash) :: ("htlcExpiry" | missingHtlcExpiry) :: unusedCommitmentFormat).as[HtlcPenaltyTx]
    val claimHtlcDelayedOutputPenaltyTxCodec: Codec[ClaimHtlcDelayedOutputPenaltyTx] = (unusedRemoteCommitKeys :: unusedRevocationKey :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("toSelfDelay" | missingToSelfDelay) :: unusedCommitmentFormat).as[ClaimHtlcDelayedOutputPenaltyTx]
    val claimLocalAnchorOutputTxCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    private val claimLocalAnchorOutputTxWithConfirmationTargetCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("confirmationTarget" | ignore(32)) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    private val claimLocalAnchorOutputTxNoConfirmCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    // We previously created an unused transaction spending the remote anchor (after the 16-blocks delay).
    val unusedRemoteAnchorOutputTxCodec: Codec[ClaimLocalAnchorTx] = (unusedFundingKey :: unusedLocalCommitKeys :: ("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: unusedCommitmentFormat).as[ClaimLocalAnchorTx]
    val closingTxCodec: Codec[ClosingTx] = (("inputInfo" | inputInfoCodec) :: ("tx" | txCodec) :: ("outputIndex" | optional(bool8, outputInfoCodec))).as[ClosingTx]

    val claimRemoteCommitMainOutputTxCodec: Codec[ClaimRemoteCommitMainOutputTx] = discriminated[ClaimRemoteCommitMainOutputTx].by(uint8)
      .typecase(0x01, claimP2WPKHOutputTxCodec)
      .typecase(0x02, claimRemoteDelayedOutputTxCodec)

    val claimAnchorOutputTxCodec: Codec[ClaimLocalAnchorTx] = discriminated[ClaimLocalAnchorTx].by(uint8)
      // Important: order matters!
      .typecase(0x12, claimLocalAnchorOutputTxCodec)
      .typecase(0x11, claimLocalAnchorOutputTxWithConfirmationTargetCodec)
      .typecase(0x01, claimLocalAnchorOutputTxNoConfirmCodec)
      .typecase(0x02, unusedRemoteAnchorOutputTxCodec)

    val htlcTxCodec: Codec[UnsignedHtlcTx] = discriminated[UnsignedHtlcTx].by(uint8)
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

    val htlcTxsAndRemoteSigsCodec: Codec[ChannelTypes3.HtlcTxAndRemoteSig] = (
      ("txinfo" | htlcTxCodec) ::
        ("remoteSig" | bytes64)).as[ChannelTypes3.HtlcTxAndRemoteSig]

    val commitTxAndRemoteSigCodec: Codec[ChannelTypes3.CommitTxAndRemoteSig] = (
      ("commitTx" | commitTxCodec) ::
        ("remoteSig" | bytes64.as[ChannelSpendSignature.IndividualSignature])).as[ChannelTypes3.CommitTxAndRemoteSig]

    val localCommitCodec: Codec[ChannelTypes3.LocalCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("commitTxAndRemoteSig" | commitTxAndRemoteSigCodec) ::
        ("htlcTxsAndRemoteSigs" | listOfN(uint16, htlcTxsAndRemoteSigsCodec))).as[ChannelTypes3.LocalCommit]

    val remoteCommitCodec: Codec[RemoteCommit] = (
      ("index" | uint64overflow) ::
        ("spec" | commitmentSpecCodec) ::
        ("txid" | txId) ::
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

    val waitingForRevocationCodec: Codec[ChannelTypes3.WaitingForRevocation] = (
      ("nextRemoteCommit" | remoteCommitCodec) ::
        ("sent" | lengthDelimited(commitSigCodec)) ::
        ("sentAfterLocalCommitIndex" | uint64overflow) ::
        ("reSignAsap" | ignore(8))).as[ChannelTypes3.WaitingForRevocation]

    val upstreamLocalCodec: Codec[Upstream.Local] = ("id" | uuid).as[Upstream.Local]

    val upstreamChannelCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | millisatoshi) ::
        ("amountOut" | ignore(64))).as[Upstream.Cold.Channel]

    val upstreamChannelWithoutAmountCodec: Codec[Upstream.Cold.Channel] = (
      ("originChannelId" | bytes32) ::
        ("originHtlcId" | int64) ::
        ("amountIn" | provide(0 msat))).as[Upstream.Cold.Channel]

    val upstreamTrampolineCodec: Codec[Upstream.Cold.Trampoline] = listOfN(uint16, upstreamChannelWithoutAmountCodec).as[Upstream.Cold.Trampoline]

    val coldUpstreamCodec: Codec[Upstream.Cold] = discriminated[Upstream.Cold].by(uint16)
      .typecase(0x02, upstreamChannelCodec)
      .typecase(0x03, upstreamLocalCodec)
      .typecase(0x04, upstreamTrampolineCodec)

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

    val commitmentsCodec: Codec[Commitments] = (
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
            ("fundingTxStatus" | provide(SingleFundedUnconfirmedFundingTx(None)).upcast[LocalFundingStatus]) ::
            ("remoteFundingTxStatus" | provide(RemoteFundingStatus.Locked).upcast[RemoteFundingStatus]) ::
            ("remotePerCommitmentSecrets" | byteAligned(ShaChain.shaChainCodec))
        })).as[ChannelTypes3.Commitments].decodeOnly.map[Commitments](_.migrate()).decodeOnly

    /** Once a dual funding tx has been signed, we must remember the associated commitments. */
    case class DualFundingTx(fundingTx: SignedSharedTransaction, commitments: ChannelTypes3.Commitments)

    private val dualFundingTxCodec: Codec[DualFundingTx] = fail[DualFundingTx](Err("you have been running dual funding before it was officially released! contact developers"))

    val closingFeeratesCodec: Codec[ClosingFeerates] = (
      ("preferred" | feeratePerKw) ::
        ("min" | feeratePerKw) ::
        ("max" | feeratePerKw)).as[ClosingFeerates]

    /** If there are closing fees defined we consider ourselves to be the closing initiator. */
    val closeStatusCompatCodec: Codec[Option[CloseStatus]] = optional(bool8, closingFeeratesCodec).map(feerates_opt => Some(CloseStatus.Initiator(feerates_opt))).decodeOnly

    val closingTxProposedCodec: Codec[ClosingTxProposed] = (
      ("unsignedTx" | closingTxCodec) ::
        ("localClosingSigned" | lengthDelimited(closingSignedCodec))).as[ClosingTxProposed]

    val localCommitPublishedCodec: Codec[LocalCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainDelayedOutputTx" | optional(bool8, claimLocalDelayedOutputTxCodec)) ::
        ("htlcTxs" | mapCodec(outPointCodec, optional(bool8, htlcTxCodec))) ::
        ("claimHtlcDelayedTx" | listOfN(uint16, htlcDelayedTxCodec)) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.LocalCommitPublished].decodeOnly.map[LocalCommitPublished](_.migrate()).decodeOnly

    val remoteCommitPublishedCodec: Codec[RemoteCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("claimHtlcTxs" | mapCodec(outPointCodec, optional(bool8, claimHtlcTxCodec))) ::
        ("claimAnchorTxs" | listOfN(uint16, claimAnchorOutputTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.RemoteCommitPublished].decodeOnly.map[RemoteCommitPublished](_.migrate()).decodeOnly

    val revokedCommitPublishedCodec: Codec[RevokedCommitPublished] = (
      ("commitTx" | txCodec) ::
        ("claimMainOutputTx" | optional(bool8, claimRemoteCommitMainOutputTxCodec)) ::
        ("mainPenaltyTx" | optional(bool8, mainPenaltyTxCodec)) ::
        ("htlcPenaltyTxs" | listOfN(uint16, htlcPenaltyTxCodec)) ::
        ("claimHtlcDelayedPenaltyTxs" | listOfN(uint16, claimHtlcDelayedOutputPenaltyTxCodec)) ::
        ("spent" | spentMapCodec)).as[ChannelTypes2.RevokedCommitPublished].decodeOnly.map[RevokedCommitPublished](_.migrate()).decodeOnly

    private val shortids: Codec[ShortIdAliases] = (
      ("real_opt" | optional(bool8, realshortchannelid)) ::
        ("localAlias" | discriminated[Alias].by(uint16).typecase(1, alias)) ::
        ("remoteAlias_opt" | optional(bool8, alias))
      ).map {
      case _ :: localAlias :: remoteAlias_opt :: HNil => ShortIdAliases(localAlias, remoteAlias_opt)
    }.decodeOnly

    val DATA_WAIT_FOR_FUNDING_CONFIRMED_00_Codec: Codec[DATA_WAIT_FOR_FUNDING_CONFIRMED] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("deferred" | optional(bool8, lengthDelimited(channelReadyCodec))) ::
        ("lastSent" | either(bool8, lengthDelimited(fundingCreatedCodec), lengthDelimited(fundingSignedCodec)))).map {
      case commitments :: fundingTx :: waitingSince :: deferred :: lastSent :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx))
        DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments1, waitingSince, deferred, lastSent)
    }.decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_01_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("lastSent" | lengthDelimited(channelReadyCodec))).map {
      case commitments :: shortChannelId :: _ :: HNil =>
        DATA_WAIT_FOR_CHANNEL_READY(commitments, aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None))
    }.decodeOnly

    val DATA_WAIT_FOR_CHANNEL_READY_0a_Codec: Codec[DATA_WAIT_FOR_CHANNEL_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortIds" | shortids)).as[DATA_WAIT_FOR_CHANNEL_READY]

    val DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED_0b_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED] = fail(Err("you have been running dual funding before it was officially released! contact developers"))

    val DATA_WAIT_FOR_DUAL_FUNDING_READY_0c_Codec: Codec[DATA_WAIT_FOR_DUAL_FUNDING_READY] = (
      ("commitments" | commitmentsCodec) ::
        ("shortIds" | shortids)).as[DATA_WAIT_FOR_DUAL_FUNDING_READY]
      .decodeOnly

    val DATA_NORMAL_02_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closeStatus" | provide(Option.empty[CloseStatus]))).map {
      case commitments :: shortChannelId :: _ :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closeStatus :: HNil =>
        val aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None)
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, SpliceStatus.NoSplice, localShutdown, remoteShutdown, closeStatus)
    }.decodeOnly

    val DATA_NORMAL_07_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortChannelId" | realshortchannelid) ::
        ("buried" | bool8) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closeStatus" | closeStatusCompatCodec)).map {
      case commitments :: shortChannelId :: _ :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closeStatus :: HNil =>
        val aliases = ShortIdAliases(localAlias = Alias(shortChannelId.toLong), remoteAlias_opt = None)
        DATA_NORMAL(commitments, aliases, channelAnnouncement, channelUpdate, SpliceStatus.NoSplice, localShutdown, remoteShutdown, closeStatus)
    }.decodeOnly

    val DATA_NORMAL_09_Codec: Codec[DATA_NORMAL] = (
      ("commitments" | commitmentsCodec) ::
        ("shortids" | shortids) ::
        ("channelAnnouncement" | optional(bool8, lengthDelimited(channelAnnouncementCodec))) ::
        ("channelUpdate" | lengthDelimited(channelUpdateCodec)) ::
        ("localShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("remoteShutdown" | optional(bool8, lengthDelimited(shutdownCodec))) ::
        ("closeStatus" | closeStatusCompatCodec) ::
        ("spliceStatus" | provide[SpliceStatus](SpliceStatus.NoSplice))).map {
      case commitments :: shortIds :: channelAnnouncement :: channelUpdate :: localShutdown :: remoteShutdown :: closeStatus :: spliceStatus :: HNil =>
        DATA_NORMAL(commitments, shortIds, channelAnnouncement, channelUpdate, spliceStatus, localShutdown, remoteShutdown, closeStatus)
    }.decodeOnly

    val DATA_SHUTDOWN_03_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closeStatus" | provide[CloseStatus](CloseStatus.Initiator(None)))).as[DATA_SHUTDOWN]

    val DATA_SHUTDOWN_08_Codec: Codec[DATA_SHUTDOWN] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingFeerates" | optional(bool8, closingFeeratesCodec).map[CloseStatus](feerates_opt => CloseStatus.Initiator(feerates_opt)).decodeOnly)).as[DATA_SHUTDOWN]

    val DATA_NEGOTIATING_04_Codec: Codec[DATA_NEGOTIATING] = (
      ("commitments" | commitmentsCodec) ::
        ("localShutdown" | lengthDelimited(shutdownCodec)) ::
        ("remoteShutdown" | lengthDelimited(shutdownCodec)) ::
        ("closingTxProposed" | listOfN(uint16, listOfN(uint16, lengthDelimited(closingTxProposedCodec)))) ::
        ("bestUnpublishedClosingTx_opt" | optional(bool8, closingTxCodec))).as[DATA_NEGOTIATING]

    val DATA_CLOSING_05_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
        ("fundingTx_opt" | optional(bool8, txCodec)) ::
        ("waitingSince" | int64.as[BlockHeight]) ::
        ("mutualCloseProposed" | listOfN(uint16, closingTxCodec)) ::
        ("mutualClosePublished" | listOfN(uint16, closingTxCodec)) ::
        ("localCommitPublished" | optional(bool8, localCommitPublishedCodec)) ::
        ("remoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("nextRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("futureRemoteCommitPublished" | optional(bool8, remoteCommitPublishedCodec)) ::
        ("revokedCommitPublished" | listOfN(uint16, revokedCommitPublishedCodec))).map {
      case commitments :: fundingTx_opt :: waitingSince :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx_opt))
        DATA_CLOSING(commitments1, waitingSince, commitments1.localChannelParams.upfrontShutdownScript_opt.get, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val unconfirmedFundingTxCodec: Codec[UnconfirmedFundingTx] = discriminated[UnconfirmedFundingTx].by(uint8)
      .typecase(0x01, txCodec.map(tx => SingleFundedUnconfirmedFundingTx(Some(tx))).decodeOnly)
      .typecase(0x02, fail[DualFundedUnconfirmedFundingTx](Err("you have been running dual funding before it was officially released! contact developers")))

    val DATA_CLOSING_0d_Codec: Codec[DATA_CLOSING] = (
      ("commitments" | commitmentsCodec) ::
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
      case commitments :: fundingTx_opt :: waitingSince :: _ :: mutualCloseProposed :: mutualClosePublished :: localCommitPublished :: remoteCommitPublished :: nextRemoteCommitPublished :: futureRemoteCommitPublished :: revokedCommitPublished :: HNil =>
        val commitments1 = ChannelTypes0.setFundingStatus(commitments, SingleFundedUnconfirmedFundingTx(fundingTx_opt.flatMap(_.signedTx_opt)))
        DATA_CLOSING(commitments1, waitingSince, commitments.localChannelParams.upfrontShutdownScript_opt.get, mutualCloseProposed, mutualClosePublished, localCommitPublished, remoteCommitPublished, nextRemoteCommitPublished, futureRemoteCommitPublished, revokedCommitPublished)
    }.decodeOnly

    val DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT_06_Codec: Codec[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT] = (
      ("commitments" | commitmentsCodec) ::
        ("remoteChannelReestablish" | channelReestablishCodec)).as[DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT]
  }

  // Order matters!
  val channelDataCodec: Codec[PersistentChannelData] = discriminated[PersistentChannelData].by(uint16)
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
