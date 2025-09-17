/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.wire.internal.channel.version4

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi, Transaction, TxId, TxOut}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.SignedSharedTransaction
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, InputInfo}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import fr.acinq.eclair.wire.internal.channel.version3.ChannelTypes3
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, LiquidityAds, Shutdown, TxSignatures}
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiryDelta, Features, InitFeature, MilliSatoshi, RealShortChannelId, UInt64, channel}
import scodec.bits.ByteVector

private[channel] object ChannelTypes4 {

  // We moved the real scid inside each commitment object when adding DATA_NORMAL_14_Codec.
  case class ShortIds(real_opt: Option[RealShortChannelId], localAlias: Alias, remoteAlias_opt: Option[Alias])

  // We split remote params into separate channel params and commitment params when moving to channel codecs v5.
  case class RemoteParams(nodeId: PublicKey,
                          dustLimit: Satoshi,
                          maxHtlcValueInFlightMsat: UInt64,
                          initialRequestedChannelReserve_opt: Option[Satoshi],
                          htlcMinimum: MilliSatoshi,
                          toSelfDelay: CltvExpiryDelta,
                          maxAcceptedHtlcs: Int,
                          revocationBasepoint: PublicKey,
                          paymentBasepoint: PublicKey,
                          delayedPaymentBasepoint: PublicKey,
                          htlcBasepoint: PublicKey,
                          initFeatures: Features[InitFeature],
                          upfrontShutdownScript_opt: Option[ByteVector]) {
    def migrate(): channel.RemoteChannelParams = channel.RemoteChannelParams(
      nodeId = nodeId,
      initialRequestedChannelReserve_opt = initialRequestedChannelReserve_opt,
      revocationBasepoint = revocationBasepoint,
      paymentBasepoint = paymentBasepoint,
      delayedPaymentBasepoint = delayedPaymentBasepoint,
      htlcBasepoint = htlcBasepoint,
      initFeatures = initFeatures,
      upfrontShutdownScript_opt = upfrontShutdownScript_opt,
    )
  }

  case class ChannelParams(channelId: ByteVector32,
                           channelConfig: channel.ChannelConfig,
                           channelFeatures: ChannelTypes3.ChannelFeatures,
                           localParams: ChannelTypes0.LocalParams, remoteParams: RemoteParams,
                           channelFlags: channel.ChannelFlags) {
    def migrate(): channel.ChannelParams = channel.ChannelParams(channelId, channelConfig, channelFeatures.migrate(), localParams.migrate(), remoteParams.migrate(), channelFlags)

    def localCommitParams(): channel.CommitParams = channel.CommitParams(localParams.dustLimit, localParams.htlcMinimum, localParams.maxHtlcValueInFlightMsat, localParams.maxAcceptedHtlcs, remoteParams.toSelfDelay)

    def remoteCommitParams(): channel.CommitParams = channel.CommitParams(remoteParams.dustLimit, remoteParams.htlcMinimum, remoteParams.maxHtlcValueInFlightMsat, remoteParams.maxAcceptedHtlcs, localParams.toSelfDelay)
  }

  case class Multisig2of2Input(info: InputInfo, fundingTxIndex: Long, remoteFundingPubkey: PublicKey) {
    def migrate(commitmentFormat: CommitmentFormat): InteractiveTxBuilder.SharedFundingInput = InteractiveTxBuilder.SharedFundingInput(info, fundingTxIndex, remoteFundingPubkey, commitmentFormat)
  }

  // We added the commitment format when moving to channel codecs v5.
  case class InteractiveTxParams(channelId: ByteVector32,
                                 isInitiator: Boolean,
                                 localContribution: Satoshi,
                                 remoteContribution: Satoshi,
                                 sharedInput_opt: Option[Multisig2of2Input],
                                 remoteFundingPubKey: PublicKey,
                                 localOutputs: List[TxOut],
                                 lockTime: Long,
                                 dustLimit: Satoshi,
                                 targetFeerate: FeeratePerKw,
                                 requireConfirmedInputs: InteractiveTxBuilder.RequireConfirmedInputs) {
    def migrate(commitmentFormat: CommitmentFormat): InteractiveTxBuilder.InteractiveTxParams = InteractiveTxBuilder.InteractiveTxParams(
      channelId = channelId,
      isInitiator = isInitiator,
      localContribution = localContribution,
      remoteContribution = remoteContribution,
      sharedInput_opt = sharedInput_opt.map(_.migrate(commitmentFormat)),
      remoteFundingPubKey = remoteFundingPubKey,
      localOutputs = localOutputs,
      commitmentFormat = commitmentFormat,
      lockTime = lockTime,
      dustLimit = dustLimit,
      targetFeerate = targetFeerate,
      requireConfirmedInputs = requireConfirmedInputs,
    )
  }

  // We removed the signed transaction when confirmed to save space when moving to channel codecs v5.
  sealed trait LocalFundingStatus {
    def migrate(commitmentFormat: CommitmentFormat, commitInput: InputInfo): channel.LocalFundingStatus
  }

  case class SingleFundedUnconfirmedFundingTx(signedTx_opt: Option[Transaction]) extends LocalFundingStatus {
    override def migrate(commitmentFormat: CommitmentFormat, commitInput: InputInfo): channel.LocalFundingStatus.SingleFundedUnconfirmedFundingTx = channel.LocalFundingStatus.SingleFundedUnconfirmedFundingTx(signedTx_opt)
  }

  case class DualFundedUnconfirmedFundingTx(sharedTx: SignedSharedTransaction, createdAt: BlockHeight, fundingParams: InteractiveTxParams, liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends LocalFundingStatus {
    override def migrate(commitmentFormat: CommitmentFormat, commitInput: InputInfo): channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx = channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx(sharedTx, createdAt, fundingParams.migrate(commitmentFormat), liquidityPurchase_opt)
  }

  case class ZeroconfPublishedFundingTx(tx: Transaction, localSigs_opt: Option[TxSignatures], liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends LocalFundingStatus {
    override def migrate(commitmentFormat: CommitmentFormat, commitInput: InputInfo): channel.LocalFundingStatus.ZeroconfPublishedFundingTx = channel.LocalFundingStatus.ZeroconfPublishedFundingTx(tx, localSigs_opt, liquidityPurchase_opt)
  }

  case class ConfirmedFundingTx(tx: Transaction, shortChannelId: RealShortChannelId, localSigs_opt: Option[TxSignatures], liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends LocalFundingStatus {
    override def migrate(commitmentFormat: CommitmentFormat, commitInput: InputInfo): channel.LocalFundingStatus.ConfirmedFundingTx = {
      val spentInputs = tx.txIn.map(_.outPoint)
      channel.LocalFundingStatus.ConfirmedFundingTx(spentInputs, commitInput.txOut, shortChannelId, localSigs_opt, liquidityPurchase_opt)
    }
  }

  // We move the input to the Commitment class instead of the LocalCommit when moving to channel codecs v5.
  case class LocalCommit(index: Long, spec: CommitmentSpec, txId: TxId, input: InputInfo, remoteSig: channel.ChannelSpendSignature, htlcRemoteSigs: List[ByteVector64]) {
    def migrate(): channel.LocalCommit = channel.LocalCommit(index, spec, txId, remoteSig, htlcRemoteSigs)
  }

  case class Commitment(fundingTxIndex: Long,
                        firstRemoteCommitIndex: Long,
                        remoteFundingPubKey: PublicKey,
                        localFundingStatus: LocalFundingStatus, remoteFundingStatus: channel.RemoteFundingStatus,
                        localCommit: LocalCommit, remoteCommit: channel.RemoteCommit, nextRemoteCommit_opt: Option[channel.NextRemoteCommit]) {
    def migrate(params: ChannelParams): channel.Commitment = channel.Commitment(
      fundingTxIndex = fundingTxIndex,
      firstRemoteCommitIndex = firstRemoteCommitIndex,
      fundingInput = localCommit.input.outPoint,
      fundingAmount = localCommit.input.txOut.amount,
      remoteFundingPubKey = remoteFundingPubKey,
      localFundingStatus = localFundingStatus.migrate(params.channelFeatures.commitmentFormat, localCommit.input),
      remoteFundingStatus = remoteFundingStatus,
      commitmentFormat = params.channelFeatures.commitmentFormat,
      localCommitParams = params.localCommitParams(),
      localCommit = localCommit.migrate(),
      remoteCommitParams = params.remoteCommitParams(),
      remoteCommit = remoteCommit,
      nextRemoteCommit_opt = nextRemoteCommit_opt
    )
  }

  case class Commitments(params: ChannelParams,
                         changes: channel.CommitmentChanges,
                         active: Seq[Commitment],
                         inactive: Seq[Commitment] = Nil,
                         remoteNextCommitInfo: Either[channel.WaitForRev, PublicKey],
                         remotePerCommitmentSecrets: ShaChain,
                         originChannels: Map[Long, channel.Origin],
                         remoteChannelData_opt: Option[ByteVector])

  case class UnsignedLocalCommit(index: Long, spec: CommitmentSpec, txId: TxId, input: InputInfo) {
    def migrate(): InteractiveTxSigningSession.UnsignedLocalCommit = InteractiveTxSigningSession.UnsignedLocalCommit(index, spec, txId)
  }

  case class WaitingForSigs(fundingParams: InteractiveTxParams,
                            fundingTxIndex: Long,
                            fundingTx: InteractiveTxBuilder.PartiallySignedSharedTransaction,
                            localCommit: Either[UnsignedLocalCommit, LocalCommit],
                            remoteCommit: channel.RemoteCommit,
                            liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) {
    def migrate(params: ChannelParams): InteractiveTxSigningSession.WaitingForSigs = InteractiveTxSigningSession.WaitingForSigs(
      fundingParams = fundingParams.migrate(params.channelFeatures.commitmentFormat),
      fundingTxIndex = fundingTxIndex,
      fundingTx = fundingTx,
      localCommitParams = params.localCommitParams(),
      localCommit = localCommit match {
        case Left(unsigned) => Left(unsigned.migrate())
        case Right(signed) => Right(signed.migrate())
      },
      remoteCommitParams = params.remoteCommitParams(),
      remoteCommit = remoteCommit,
      liquidityPurchase_opt = liquidityPurchase_opt,
    )

    def migrate(commitments: channel.Commitments): InteractiveTxSigningSession.WaitingForSigs = InteractiveTxSigningSession.WaitingForSigs(
      fundingParams = fundingParams.migrate(commitments.latest.commitmentFormat),
      fundingTxIndex = fundingTxIndex,
      fundingTx = fundingTx,
      localCommitParams = commitments.latest.localCommitParams,
      localCommit = localCommit match {
        case Left(unsigned) => Left(unsigned.migrate())
        case Right(signed) => Right(signed.migrate())
      },
      remoteCommitParams = commitments.latest.remoteCommitParams,
      remoteCommit = remoteCommit,
      liquidityPurchase_opt = liquidityPurchase_opt,
    )
  }

  // We moved the channel_announcement inside each commitment object when adding DATA_NORMAL_14_Codec.
  case class DATA_NORMAL_0e(commitments: channel.Commitments,
                            shortIds: ShortIds,
                            channelAnnouncement: Option[ChannelAnnouncement],
                            channelUpdate: ChannelUpdate,
                            localShutdown: Option[Shutdown],
                            remoteShutdown: Option[Shutdown],
                            closingFeerates: Option[channel.ClosingFeerates],
                            spliceStatus: channel.SpliceStatus) {
    def migrate(): channel.DATA_NORMAL = {
      val commitments1 = commitments.copy(
        active = commitments.active.map(c => setScidIfMatches(c, shortIds)),
        inactive = commitments.inactive.map(c => setScidIfMatches(c, shortIds)),
      )
      val aliases = channel.ShortIdAliases(shortIds.localAlias, shortIds.remoteAlias_opt)
      val closeStatus_opt = if (localShutdown.nonEmpty) {
        Some(channel.CloseStatus.Initiator(closingFeerates))
      } else if (remoteShutdown.nonEmpty) {
        Some(channel.CloseStatus.NonInitiator(closingFeerates))
      } else None
      channel.DATA_NORMAL(commitments1, aliases, channelAnnouncement, channelUpdate, spliceStatus, localShutdown, remoteShutdown, closeStatus_opt)
    }
  }

  case class DATA_WAIT_FOR_CHANNEL_READY_0b(commitments: channel.Commitments, shortIds: ShortIds) {
    def migrate(): channel.DATA_WAIT_FOR_CHANNEL_READY = {
      val commitments1 = commitments.copy(
        active = commitments.active.map(c => setScidIfMatches(c, shortIds)),
        inactive = commitments.inactive.map(c => setScidIfMatches(c, shortIds)),
      )
      val aliases = channel.ShortIdAliases(shortIds.localAlias, shortIds.remoteAlias_opt)
      channel.DATA_WAIT_FOR_CHANNEL_READY(commitments1, aliases)
    }
  }

  case class DATA_WAIT_FOR_DUAL_FUNDING_READY_0d(commitments: channel.Commitments, shortIds: ShortIds) {
    def migrate(): channel.DATA_WAIT_FOR_DUAL_FUNDING_READY = {
      val commitments1 = commitments.copy(
        active = commitments.active.map(c => setScidIfMatches(c, shortIds)),
        inactive = commitments.inactive.map(c => setScidIfMatches(c, shortIds)),
      )
      val aliases = channel.ShortIdAliases(shortIds.localAlias, shortIds.remoteAlias_opt)
      channel.DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, aliases)
    }
  }

  private def setScidIfMatches(c: channel.Commitment, shortIds: ShortIds): channel.Commitment = {
    c.localFundingStatus match {
      // We didn't support splicing on public channels in this version: the scid (if available) is for the initial
      // funding transaction. For private channels we don't care about the real scid, it will be set correctly after
      // the next splice.
      case f: channel.LocalFundingStatus.ConfirmedFundingTx if c.fundingTxIndex == 0 =>
        val scid = shortIds.real_opt.getOrElse(f.shortChannelId)
        c.copy(localFundingStatus = f.copy(shortChannelId = scid))
      case _ => c
    }
  }

}
