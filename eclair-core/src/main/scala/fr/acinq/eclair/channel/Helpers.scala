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

package fr.acinq.eclair.channel

import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.OnChainAddressGenerator
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, FeeratePerKw}
import fr.acinq.eclair.channel.Channel.{ChannelConf, REFRESH_CHANNEL_UPDATE_INTERVAL}
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.DirectedHtlc._
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

/**
 * Created by PM on 20/05/2016.
 */

object Helpers {
  /**
   * We update local/global features at reconnection
   */
  def updateFeatures(data: HasCommitments, localInit: Init, remoteInit: Init): HasCommitments = {
    val commitments1 = data.commitments.copy(
      localParams = data.commitments.localParams.copy(initFeatures = localInit.features),
      remoteParams = data.commitments.remoteParams.copy(initFeatures = remoteInit.features))
    data match {
      case d: DATA_WAIT_FOR_FUNDING_CONFIRMED => d.copy(commitments = commitments1)
      case d: DATA_WAIT_FOR_FUNDING_LOCKED => d.copy(commitments = commitments1)
      case d: DATA_NORMAL => d.copy(commitments = commitments1)
      case d: DATA_SHUTDOWN => d.copy(commitments = commitments1)
      case d: DATA_NEGOTIATING => d.copy(commitments = commitments1)
      case d: DATA_CLOSING => d.copy(commitments = commitments1)
      case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => d.copy(commitments = commitments1)
    }
  }

  /**
   * Returns the number of confirmations needed to safely handle the funding transaction,
   * we make sure the cumulative block reward largely exceeds the channel size.
   *
   * @param fundingSatoshis funding amount of the channel
   * @return number of confirmations needed
   */
  def minDepthForFunding(channelConf: ChannelConf, fundingSatoshis: Satoshi): Long = fundingSatoshis match {
    case funding if funding <= Channel.MAX_FUNDING => channelConf.minDepthBlocks
    case funding =>
      val blockReward = 6.25 // this is true as of ~May 2020, but will be too large after 2024
      val scalingFactor = 15
      val blocksToReachFunding = (((scalingFactor * funding.toBtc.toDouble) / blockReward).ceil + 1).toInt
      channelConf.minDepthBlocks.max(blocksToReachFunding)
  }

  def extractShutdownScript(channelId: ByteVector32, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], upfrontShutdownScript_opt: Option[ByteVector]): Either[ChannelException, Option[ByteVector]] = {
    val canUseUpfrontShutdownScript = Features.canUseFeature(localFeatures, remoteFeatures, Features.UpfrontShutdownScript)
    val canUseAnySegwit = Features.canUseFeature(localFeatures, remoteFeatures, Features.ShutdownAnySegwit)
    extractShutdownScript(channelId, canUseUpfrontShutdownScript, canUseAnySegwit, upfrontShutdownScript_opt)
  }

  def extractShutdownScript(channelId: ByteVector32, hasOptionUpfrontShutdownScript: Boolean, allowAnySegwit: Boolean, upfrontShutdownScript_opt: Option[ByteVector]): Either[ChannelException, Option[ByteVector]] = {
    (hasOptionUpfrontShutdownScript, upfrontShutdownScript_opt) match {
      case (true, None) => Left(MissingUpfrontShutdownScript(channelId))
      case (true, Some(script)) if script.isEmpty => Right(None) // but the provided script can be empty
      case (true, Some(script)) if !Closing.MutualClose.isValidFinalScriptPubkey(script, allowAnySegwit) => Left(InvalidFinalScript(channelId))
      case (true, Some(script)) => Right(Some(script))
      case (false, Some(_)) => Right(None) // they provided a script but the feature is not active, we just ignore it
      case _ => Right(None)
    }
  }

  /**
   * Called by the fundee
   */
  def validateParamsFundee(nodeParams: NodeParams, channelType: SupportedChannelType, localFeatures: Features[InitFeature], open: OpenChannel, remoteNodeId: PublicKey, remoteFeatures: Features[InitFeature]): Either[ChannelException, (ChannelFeatures, Option[ByteVector])] = {
    // BOLT #2: if the chain_hash value, within the open_channel, message is set to a hash of a chain that is unknown to the receiver:
    // MUST reject the channel.
    if (nodeParams.chainHash != open.chainHash) return Left(InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash))

    if (open.fundingSatoshis < nodeParams.channelConf.minFundingSatoshis || open.fundingSatoshis > nodeParams.channelConf.maxFundingSatoshis) return Left(InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, nodeParams.channelConf.minFundingSatoshis, nodeParams.channelConf.maxFundingSatoshis))

    // BOLT #2: Channel funding limits
    if (open.fundingSatoshis >= Channel.MAX_FUNDING && !localFeatures.hasFeature(Features.Wumbo)) return Left(InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, nodeParams.channelConf.minFundingSatoshis, Channel.MAX_FUNDING))

    // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
    if (open.pushMsat > open.fundingSatoshis) return Left(InvalidPushAmount(open.temporaryChannelId, open.pushMsat, open.fundingSatoshis.toMilliSatoshi))

    // BOLT #2: The receiving node MUST fail the channel if: to_self_delay is unreasonably large.
    if (open.toSelfDelay > Channel.MAX_TO_SELF_DELAY || open.toSelfDelay > nodeParams.channelConf.maxToLocalDelay) return Left(ToSelfDelayTooHigh(open.temporaryChannelId, open.toSelfDelay, nodeParams.channelConf.maxToLocalDelay))

    // BOLT #2: The receiving node MUST fail the channel if: max_accepted_htlcs is greater than 483.
    if (open.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) return Left(InvalidMaxAcceptedHtlcs(open.temporaryChannelId, open.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))

    // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing.
    if (isFeeTooSmall(open.feeratePerKw)) return Left(FeerateTooSmall(open.temporaryChannelId, open.feeratePerKw))

    if (open.dustLimitSatoshis > nodeParams.channelConf.maxRemoteDustLimit) return Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, nodeParams.channelConf.maxRemoteDustLimit))

    // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
    if (open.dustLimitSatoshis > open.channelReserveSatoshis) return Left(DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, open.channelReserveSatoshis))

    // BOLT #2: The receiving node MUST fail the channel if both to_local and to_remote amounts for the initial commitment
    // transaction are less than or equal to channel_reserve_satoshis (see BOLT 3).
    val (toLocalMsat, toRemoteMsat) = (open.pushMsat, open.fundingSatoshis.toMilliSatoshi - open.pushMsat)
    if (toLocalMsat < open.channelReserveSatoshis && toRemoteMsat < open.channelReserveSatoshis) {
      return Left(ChannelReserveNotMet(open.temporaryChannelId, toLocalMsat, toRemoteMsat, open.channelReserveSatoshis))
    }

    // BOLT #2: The receiving node MUST fail the channel if: it considers feerate_per_kw too small for timely processing or unreasonably large.
    val localFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, channelType, open.fundingSatoshis, None)
    if (nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(channelType, localFeeratePerKw, open.feeratePerKw)) return Left(FeerateTooDifferent(open.temporaryChannelId, localFeeratePerKw, open.feeratePerKw))
    // only enforce dust limit check on mainnet
    if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
      if (open.dustLimitSatoshis < Channel.MIN_DUST_LIMIT) return Left(DustLimitTooSmall(open.temporaryChannelId, open.dustLimitSatoshis, Channel.MIN_DUST_LIMIT))
    }

    // we don't check that the funder's amount for the initial commitment transaction is sufficient for full fee payment
    // now, but it will be done later when we receive `funding_created`

    val reserveToFundingRatio = open.channelReserveSatoshis.toLong.toDouble / Math.max(open.fundingSatoshis.toLong, 1)
    if (reserveToFundingRatio > nodeParams.channelConf.maxReserveToFundingRatio) return Left(ChannelReserveTooHigh(open.temporaryChannelId, open.channelReserveSatoshis, reserveToFundingRatio, nodeParams.channelConf.maxReserveToFundingRatio))

    val channelFeatures = ChannelFeatures(channelType, localFeatures, remoteFeatures)
    extractShutdownScript(open.temporaryChannelId, localFeatures, remoteFeatures, open.upfrontShutdownScript_opt).map(script_opt => (channelFeatures, script_opt))
  }

  /**
   * Called by the funder
   */
  def validateParamsFunder(nodeParams: NodeParams, channelType: SupportedChannelType, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], open: OpenChannel, accept: AcceptChannel): Either[ChannelException, (ChannelFeatures, Option[ByteVector])] = {
    accept.channelType_opt match {
      case Some(theirChannelType) if accept.channelType_opt != open.channelType_opt =>
        // if channel_type is set, and channel_type was set in open_channel, and they are not equal types: MUST reject the channel.
        return Left(InvalidChannelType(open.temporaryChannelId, channelType, theirChannelType))
      case None if Features.canUseFeature(localFeatures, remoteFeatures, Features.ChannelType) =>
        // Bolt 2: if `option_channel_type` is negotiated: MUST set `channel_type`
        return Left(MissingChannelType(open.temporaryChannelId))
      case None if channelType != ChannelTypes.defaultFromFeatures(localFeatures, remoteFeatures) =>
        // If we have overridden the default channel type, but they didn't support explicit channel type negotiation,
        // we need to abort because they expect a different channel type than what we offered.
        return Left(InvalidChannelType(open.temporaryChannelId, channelType, ChannelTypes.defaultFromFeatures(localFeatures, remoteFeatures)))
      case _ => // we agree on channel type
    }

    if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) return Left(InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS))
    // only enforce dust limit check on mainnet
    if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
      if (accept.dustLimitSatoshis < Channel.MIN_DUST_LIMIT) return Left(DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimitSatoshis, Channel.MIN_DUST_LIMIT))
    }

    if (accept.dustLimitSatoshis > nodeParams.channelConf.maxRemoteDustLimit) return Left(DustLimitTooLarge(open.temporaryChannelId, accept.dustLimitSatoshis, nodeParams.channelConf.maxRemoteDustLimit))

    // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
    if (accept.dustLimitSatoshis > accept.channelReserveSatoshis) return Left(DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, accept.channelReserveSatoshis))

    // if minimum_depth is unreasonably large:
    // MAY reject the channel.
    if (accept.toSelfDelay > Channel.MAX_TO_SELF_DELAY || accept.toSelfDelay > nodeParams.channelConf.maxToLocalDelay) return Left(ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.channelConf.maxToLocalDelay))

    // if channel_reserve_satoshis is less than dust_limit_satoshis within the open_channel message:
    //  MUST reject the channel.
    if (accept.channelReserveSatoshis < open.dustLimitSatoshis) return Left(ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, accept.channelReserveSatoshis, open.dustLimitSatoshis))

    // if channel_reserve_satoshis from the open_channel message is less than dust_limit_satoshis:
    // MUST reject the channel. Other fields have the same requirements as their counterparts in open_channel.
    if (open.channelReserveSatoshis < accept.dustLimitSatoshis) return Left(DustLimitAboveOurChannelReserve(accept.temporaryChannelId, accept.dustLimitSatoshis, open.channelReserveSatoshis))

    val reserveToFundingRatio = accept.channelReserveSatoshis.toLong.toDouble / Math.max(open.fundingSatoshis.toLong, 1)
    if (reserveToFundingRatio > nodeParams.channelConf.maxReserveToFundingRatio) return Left(ChannelReserveTooHigh(open.temporaryChannelId, accept.channelReserveSatoshis, reserveToFundingRatio, nodeParams.channelConf.maxReserveToFundingRatio))

    val channelFeatures = ChannelFeatures(channelType, localFeatures, remoteFeatures)
    extractShutdownScript(accept.temporaryChannelId, localFeatures, remoteFeatures, accept.upfrontShutdownScript_opt).map(script_opt => (channelFeatures, script_opt))
  }

  /**
   * Compute the delay until we need to refresh the channel_update for our channel not to be considered stale by
   * other nodes.
   *
   * If current update more than [[Channel.REFRESH_CHANNEL_UPDATE_INTERVAL]] old then the delay will be zero.
   *
   * @return the delay until the next update
   */
  def nextChannelUpdateRefresh(currentUpdateTimestamp: TimestampSecond)(implicit log: DiagnosticLoggingAdapter): FiniteDuration = {
    val age = TimestampSecond.now() - currentUpdateTimestamp
    val delay = 0.days.max(REFRESH_CHANNEL_UPDATE_INTERVAL - age)
    Logs.withMdc(log)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
      log.debug("current channel_update was created {} days ago, will refresh it in {} days", age.toDays, delay.toDays)
    }
    delay
  }

  /**
   * @param remoteFeeratePerKw remote fee rate per kiloweight
   * @return true if the remote fee rate is too small
   */
  def isFeeTooSmall(remoteFeeratePerKw: FeeratePerKw): Boolean = {
    remoteFeeratePerKw < FeeratePerKw.MinimumFeeratePerKw
  }

  def makeAnnouncementSignatures(nodeParams: NodeParams, commitments: Commitments, shortChannelId: ShortChannelId): AnnouncementSignatures = {
    val features = Features.empty[FeatureScope] // empty features for now
    val fundingPubKey = nodeParams.channelKeyManager.fundingPublicKey(commitments.localParams.fundingKeyPath)
    val witness = Announcements.generateChannelAnnouncementWitness(
      nodeParams.chainHash,
      shortChannelId,
      nodeParams.nodeKeyManager.nodeId,
      commitments.remoteParams.nodeId,
      fundingPubKey.publicKey,
      commitments.remoteParams.fundingPubKey,
      features
    )
    val localBitcoinSig = nodeParams.channelKeyManager.signChannelAnnouncement(witness, fundingPubKey.path)
    val localNodeSig = nodeParams.nodeKeyManager.signChannelAnnouncement(witness)
    AnnouncementSignatures(commitments.channelId, shortChannelId, localNodeSig, localBitcoinSig)
  }

  /**
   * This indicates whether our side of the channel is above the reserve requested by our counterparty. In other words,
   * this tells if we can use the channel to make a payment.
   */
  def aboveReserve(commitments: Commitments)(implicit log: LoggingAdapter): Boolean = {
    val remoteCommit = commitments.remoteNextCommitInfo match {
      case Left(waitingForRevocation) => waitingForRevocation.nextRemoteCommit
      case _ => commitments.remoteCommit
    }
    val toRemoteSatoshis = remoteCommit.spec.toRemote.truncateToSatoshi
    // NB: this is an approximation (we don't take network fees into account)
    val result = toRemoteSatoshis > commitments.remoteParams.channelReserve
    log.debug(s"toRemoteSatoshis=$toRemoteSatoshis reserve=${commitments.remoteParams.channelReserve} aboveReserve=$result for remoteCommitNumber=${remoteCommit.index}")
    result
  }

  /** NB: this is a blocking call, use carefully! */
  def getFinalScriptPubKey(wallet: OnChainAddressGenerator, chainHash: ByteVector32)(implicit ec: ExecutionContext): ByteVector = {
    import scala.concurrent.duration._
    val finalAddress = Await.result(wallet.getReceiveAddress(), 40 seconds)
    Script.write(addressToPublicKeyScript(finalAddress, chainHash))
  }

  /** NB: this is a blocking call, use carefully! */
  def getWalletPaymentBasepoint(wallet: OnChainAddressGenerator)(implicit ec: ExecutionContext): PublicKey = {
    Await.result(wallet.getReceivePubkey(), 40 seconds)
  }

  def getRelayFees(nodeParams: NodeParams, remoteNodeId: PublicKey, commitments: Commitments): RelayFees = {
    val defaultFees = nodeParams.relayParams.defaultFees(commitments.announceChannel)
    nodeParams.db.peers.getRelayFees(remoteNodeId).getOrElse(defaultFees)
  }

  object Funding {

    def makeFundingInputInfo(fundingTxId: ByteVector32, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: PublicKey, fundingPubkey2: PublicKey): InputInfo = {
      val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
      InputInfo(OutPoint(fundingTxId, fundingTxOutputIndex), fundingTxOut, write(fundingScript))
    }

    /**
     * Creates both sides' first commitment transaction
     *
     * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
     */
    def makeFirstCommitTxs(keyManager: ChannelKeyManager, channelConfig: ChannelConfig, channelFeatures: ChannelFeatures, temporaryChannelId: ByteVector32, localParams: LocalParams, remoteParams: RemoteParams, fundingAmount: Satoshi, pushMsat: MilliSatoshi, initialFeeratePerKw: FeeratePerKw, fundingTxHash: ByteVector32, fundingTxOutputIndex: Int, remoteFirstPerCommitmentPoint: PublicKey): Either[ChannelException, (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx)] = {
      val toLocalMsat = if (localParams.isFunder) fundingAmount.toMilliSatoshi - pushMsat else pushMsat
      val toRemoteMsat = if (localParams.isFunder) pushMsat else fundingAmount.toMilliSatoshi - pushMsat

      val localSpec = CommitmentSpec(Set.empty[DirectedHtlc], initialFeeratePerKw, toLocal = toLocalMsat, toRemote = toRemoteMsat)
      val remoteSpec = CommitmentSpec(Set.empty[DirectedHtlc], initialFeeratePerKw, toLocal = toRemoteMsat, toRemote = toLocalMsat)

      if (!localParams.isFunder) {
        // they are funder, therefore they pay the fee: we need to make sure they can afford it!
        val toRemoteMsat = remoteSpec.toLocal
        val fees = commitTxTotalCost(remoteParams.dustLimit, remoteSpec, channelFeatures.commitmentFormat)
        val missing = toRemoteMsat.truncateToSatoshi - localParams.channelReserve - fees
        if (missing < Satoshi(0)) {
          return Left(CannotAffordFees(temporaryChannelId, missing = -missing, reserve = localParams.channelReserve, fees = fees))
        }
      }

      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
      val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, fundingAmount, fundingPubKey.publicKey, remoteParams.fundingPubKey)
      val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0)
      val (localCommitTx, _) = Commitments.makeLocalTxs(keyManager, channelConfig, channelFeatures, 0, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _) = Commitments.makeRemoteTxs(keyManager, channelConfig, channelFeatures, 0, localParams, remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec)

      Right(localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }

  }

  object Syncing {

    // @formatter:off
    sealed trait SyncResult
    object SyncResult {
      case class Success(retransmit: Seq[LightningMessage]) extends SyncResult
      sealed trait Failure extends SyncResult
      case class LocalLateProven(ourLocalCommitmentNumber: Long, theirRemoteCommitmentNumber: Long) extends Failure
      case class LocalLateUnproven(ourRemoteCommitmentNumber: Long, theirLocalCommitmentNumber: Long) extends Failure
      case class RemoteLying(ourLocalCommitmentNumber: Long, theirRemoteCommitmentNumber: Long, invalidPerCommitmentSecret: PrivateKey) extends Failure
      case object RemoteLate extends Failure
    }
    // @formatter:on

    /**
     * Check whether we are in sync with our peer.
     */
    def checkSync(keyManager: ChannelKeyManager, d: HasCommitments, remoteChannelReestablish: ChannelReestablish): SyncResult = {

      // This is done in two steps:
      // - step 1: we check our local commitment
      // - step 2: we check the remote commitment
      // step 2 depends on step 1 because we need to preserve ordering between commit_sig and revocation

      // step 2: we check the remote commitment
      def checkRemoteCommit(d: HasCommitments, remoteChannelReestablish: ChannelReestablish, retransmitRevocation_opt: Option[RevokeAndAck]): SyncResult = {
        d.commitments.remoteNextCommitInfo match {
          case Left(waitingForRevocation) if remoteChannelReestablish.nextLocalCommitmentNumber == waitingForRevocation.nextRemoteCommit.index =>
            // we just sent a new commit_sig but they didn't receive it
            // we resend the same updates and the same sig, and preserve the same ordering
            val signedUpdates = d.commitments.localChanges.signed
            val commitSig = waitingForRevocation.sent
            retransmitRevocation_opt match {
              case None =>
                SyncResult.Success(retransmit = signedUpdates :+ commitSig)
              case Some(revocation) if d.commitments.localCommit.index > waitingForRevocation.sentAfterLocalCommitIndex =>
                SyncResult.Success(retransmit = signedUpdates :+ commitSig :+ revocation)
              case Some(revocation) =>
                SyncResult.Success(retransmit = revocation +: signedUpdates :+ commitSig)
            }
          case Left(waitingForRevocation) if remoteChannelReestablish.nextLocalCommitmentNumber == (waitingForRevocation.nextRemoteCommit.index + 1) =>
            // we just sent a new commit_sig, they have received it but we haven't received their revocation
            SyncResult.Success(retransmit = retransmitRevocation_opt.toSeq)
          case Left(waitingForRevocation) if remoteChannelReestablish.nextLocalCommitmentNumber < waitingForRevocation.nextRemoteCommit.index =>
            // they are behind
            SyncResult.RemoteLate
          case Left(waitingForRevocation) =>
            // we are behind
            SyncResult.LocalLateUnproven(
              ourRemoteCommitmentNumber = waitingForRevocation.nextRemoteCommit.index,
              theirLocalCommitmentNumber = remoteChannelReestablish.nextLocalCommitmentNumber - 1
            )
          case Right(_) if remoteChannelReestablish.nextLocalCommitmentNumber == (d.commitments.remoteCommit.index + 1) =>
            // they have acknowledged the last commit_sig we sent
            SyncResult.Success(retransmit = retransmitRevocation_opt.toSeq)
          case Right(_) if remoteChannelReestablish.nextLocalCommitmentNumber < (d.commitments.remoteCommit.index + 1) =>
            // they are behind
            SyncResult.RemoteLate
          case Right(_) =>
            // we are behind
            SyncResult.LocalLateUnproven(
              ourRemoteCommitmentNumber = d.commitments.remoteCommit.index,
              theirLocalCommitmentNumber = remoteChannelReestablish.nextLocalCommitmentNumber - 1
            )
        }
      }

      // step 1: we check our local commitment
      if (d.commitments.localCommit.index == remoteChannelReestablish.nextRemoteRevocationNumber) {
        // our local commitment is in sync, let's check the remote commitment
        checkRemoteCommit(d, remoteChannelReestablish, retransmitRevocation_opt = None)
      } else if (d.commitments.localCommit.index == remoteChannelReestablish.nextRemoteRevocationNumber + 1) {
        // they just sent a new commit_sig, we have received it but they didn't receive our revocation
        val channelKeyPath = keyManager.keyPath(d.commitments.localParams, d.commitments.channelConfig)
        val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, d.commitments.localCommit.index - 1)
        val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, d.commitments.localCommit.index + 1)
        val revocation = RevokeAndAck(
          channelId = d.channelId,
          perCommitmentSecret = localPerCommitmentSecret,
          nextPerCommitmentPoint = localNextPerCommitmentPoint
        )
        checkRemoteCommit(d, remoteChannelReestablish, retransmitRevocation_opt = Some(revocation))
      } else if (d.commitments.localCommit.index > remoteChannelReestablish.nextRemoteRevocationNumber + 1) {
        SyncResult.RemoteLate
      } else {
        // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
        // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
        val channelKeyPath = keyManager.keyPath(d.commitments.localParams, d.commitments.channelConfig)
        if (keyManager.commitmentSecret(channelKeyPath, remoteChannelReestablish.nextRemoteRevocationNumber - 1) == remoteChannelReestablish.yourLastPerCommitmentSecret) {
          SyncResult.LocalLateProven(
            ourLocalCommitmentNumber = d.commitments.localCommit.index,
            theirRemoteCommitmentNumber = remoteChannelReestablish.nextRemoteRevocationNumber
          )
        } else {
          // they lied! the last per_commitment_secret they claimed to have received from us is invalid
          SyncResult.RemoteLying(
            ourLocalCommitmentNumber = d.commitments.localCommit.index,
            theirRemoteCommitmentNumber = remoteChannelReestablish.nextRemoteRevocationNumber,
            invalidPerCommitmentSecret = remoteChannelReestablish.yourLastPerCommitmentSecret
          )
        }
      }
    }
  }

  object Closing {

    // @formatter:off
    sealed trait ClosingType
    case class MutualClose(tx: ClosingTx) extends ClosingType
    case class LocalClose(localCommit: LocalCommit, localCommitPublished: LocalCommitPublished) extends ClosingType
    sealed trait RemoteClose extends ClosingType { def remoteCommit: RemoteCommit; def remoteCommitPublished: RemoteCommitPublished }
    case class CurrentRemoteClose(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished) extends RemoteClose
    case class NextRemoteClose(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished) extends RemoteClose
    case class RecoveryClose(remoteCommitPublished: RemoteCommitPublished) extends ClosingType
    case class RevokedClose(revokedCommitPublished: RevokedCommitPublished) extends ClosingType
    // @formatter:on

    /**
     * Indicates whether local has anything at stake in this channel
     *
     * @return true if channel was never open, or got closed immediately, had never any htlcs and local never had a positive balance
     */
    def nothingAtStake(data: HasCommitments): Boolean =
      data.commitments.localCommit.index == 0 &&
        data.commitments.localCommit.spec.toLocal == 0.msat &&
        data.commitments.remoteCommit.index == 0 &&
        data.commitments.remoteCommit.spec.toRemote == 0.msat &&
        data.commitments.remoteNextCommitInfo.isRight

    /**
     * As soon as a tx spending the funding tx has reached min_depth, we know what the closing type will be, before
     * the whole closing process finishes (e.g. there may still be delayed or unconfirmed child transactions). It can
     * save us from attempting to publish some transactions.
     *
     * Note that we can't tell for mutual close before it is already final, because only one tx needs to be confirmed.
     *
     * @param closing channel state data
     * @return the channel closing type, if applicable
     */
    def isClosingTypeAlreadyKnown(closing: DATA_CLOSING): Option[ClosingType] = {
      closing match {
        case _ if closing.localCommitPublished.exists(_.isConfirmed) =>
          Some(LocalClose(closing.commitments.localCommit, closing.localCommitPublished.get))
        case _ if closing.remoteCommitPublished.exists(_.isConfirmed) =>
          Some(CurrentRemoteClose(closing.commitments.remoteCommit, closing.remoteCommitPublished.get))
        case _ if closing.nextRemoteCommitPublished.exists(_.isConfirmed) =>
          val Left(waitingForRevocation) = closing.commitments.remoteNextCommitInfo
          Some(NextRemoteClose(waitingForRevocation.nextRemoteCommit, closing.nextRemoteCommitPublished.get))
        case _ if closing.futureRemoteCommitPublished.exists(_.isConfirmed) =>
          Some(RecoveryClose(closing.futureRemoteCommitPublished.get))
        case _ if closing.revokedCommitPublished.exists(_.isConfirmed) =>
          Some(RevokedClose(closing.revokedCommitPublished.find(_.isConfirmed).get))
        case _ => None // we don't know yet what the closing type will be
      }
    }

    /**
     * Checks if a channel is closed (i.e. its closing tx has been confirmed)
     *
     * @param data                      channel state data
     * @param additionalConfirmedTx_opt additional confirmed transaction; we need this for the mutual close scenario
     *                                  because we don't store the closing tx in the channel state
     * @return the channel closing type, if applicable
     */
    def isClosed(data: HasCommitments, additionalConfirmedTx_opt: Option[Transaction]): Option[ClosingType] = data match {
      case closing: DATA_CLOSING if additionalConfirmedTx_opt.exists(closing.mutualClosePublished.map(_.tx).contains) =>
        val closingTx = closing.mutualClosePublished.find(_.tx.txid == additionalConfirmedTx_opt.get.txid).get
        Some(MutualClose(closingTx))
      case closing: DATA_CLOSING if closing.localCommitPublished.exists(_.isDone) =>
        Some(LocalClose(closing.commitments.localCommit, closing.localCommitPublished.get))
      case closing: DATA_CLOSING if closing.remoteCommitPublished.exists(_.isDone) =>
        Some(CurrentRemoteClose(closing.commitments.remoteCommit, closing.remoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.nextRemoteCommitPublished.exists(_.isDone) =>
        val Left(waitingForRevocation) = closing.commitments.remoteNextCommitInfo
        Some(NextRemoteClose(waitingForRevocation.nextRemoteCommit, closing.nextRemoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.futureRemoteCommitPublished.exists(_.isDone) =>
        Some(RecoveryClose(closing.futureRemoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.revokedCommitPublished.exists(_.isDone) =>
        Some(RevokedClose(closing.revokedCommitPublished.find(_.isDone).get))
      case _ => None
    }

    object MutualClose {

      // used only to compute tx weights and estimate fees
      lazy val dummyPublicKey = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey

      def isValidFinalScriptPubkey(scriptPubKey: ByteVector, allowAnySegwit: Boolean): Boolean = {
        Try(Script.parse(scriptPubKey)) match {
          case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => true
          case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => true
          case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => true
          case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => true
          case Success((OP_1 | OP_2 | OP_3 | OP_4 | OP_5 | OP_6 | OP_7 | OP_8 | OP_9 | OP_10 | OP_11 | OP_12 | OP_13 | OP_14 | OP_15 | OP_16) :: OP_PUSHDATA(program, _) :: Nil) if allowAnySegwit && 2 <= program.length && program.length <= 40 => true
          case _ => false
        }
      }

      def firstClosingFee(commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feerates: ClosingFeerates)(implicit log: LoggingAdapter): ClosingFees = {
        import commitments._
        // this is just to estimate the weight, it depends on size of the pubkey scripts
        val actualLocalScript = if (channelFeatures.hasFeature(Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else localScriptPubkey
        val actualRemoteScript = if (channelFeatures.hasFeature(Features.UpfrontShutdownScript)) remoteParams.shutdownScript.getOrElse(remoteScriptPubkey) else remoteScriptPubkey
        val dummyClosingTx = Transactions.makeClosingTx(commitInput, actualLocalScript, actualRemoteScript, localParams.isFunder, Satoshi(0), Satoshi(0), localCommit.spec)
        val closingWeight = Transaction.weight(Transactions.addSigs(dummyClosingTx, dummyPublicKey, remoteParams.fundingPubKey, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig).tx)
        log.info(s"using feerates=$feerates for initial closing tx")
        feerates.computeFees(closingWeight)
      }

      def firstClosingFee(commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): ClosingFees = {
        val requestedFeerate = feeEstimator.getFeeratePerKw(feeTargets.mutualCloseBlockTarget)
        val preferredFeerate = commitments.commitmentFormat match {
          case DefaultCommitmentFormat =>
            // we "MUST set fee_satoshis less than or equal to the base fee of the final commitment transaction"
            requestedFeerate.min(commitments.localCommit.spec.commitTxFeerate)
          case _: AnchorOutputsCommitmentFormat => requestedFeerate
        }
        // NB: we choose a minimum fee that ensures the tx will easily propagate while allowing low fees since we can
        // always use CPFP to speed up confirmation if necessary.
        val closingFeerates = ClosingFeerates(preferredFeerate, preferredFeerate.min(feeEstimator.getFeeratePerKw(1008)), preferredFeerate * 2)
        firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey, closingFeerates)
      }

      def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = ((localClosingFee + remoteClosingFee) / 4) * 2

      def makeFirstClosingTx(keyManager: ChannelKeyManager, commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feeEstimator: FeeEstimator, feeTargets: FeeTargets, closingFeerates_opt: Option[ClosingFeerates])(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
        val closingFees = closingFeerates_opt match {
          case Some(closingFeerates) => firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey, closingFeerates)
          case None => firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey, feeEstimator, feeTargets)
        }
        makeClosingTx(keyManager, commitments, localScriptPubkey, remoteScriptPubkey, closingFees)
      }

      def makeClosingTx(keyManager: ChannelKeyManager, commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, closingFees: ClosingFees)(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
        import commitments._
        val actualLocalScript = if (channelFeatures.hasFeature(Features.UpfrontShutdownScript)) localParams.defaultFinalScriptPubKey else localScriptPubkey
        val actualRemoteScript = if (channelFeatures.hasFeature(Features.UpfrontShutdownScript)) remoteParams.shutdownScript.getOrElse(remoteScriptPubkey) else remoteScriptPubkey
        val allowAnySegwit = Features.canUseFeature(commitments.localParams.initFeatures, commitments.remoteParams.initFeatures, Features.ShutdownAnySegwit)
        require(isValidFinalScriptPubkey(actualLocalScript, allowAnySegwit), "invalid localScriptPubkey")
        require(isValidFinalScriptPubkey(actualRemoteScript, allowAnySegwit), "invalid remoteScriptPubkey")
        log.debug("making closing tx with closing fee={} and commitments:\n{}", closingFees.preferred, Commitments.specs2String(commitments))
        val dustLimit = localParams.dustLimit.max(remoteParams.dustLimit)
        val closingTx = Transactions.makeClosingTx(commitInput, actualLocalScript, actualRemoteScript, localParams.isFunder, dustLimit, closingFees.preferred, localCommit.spec)
        val localClosingSig = keyManager.sign(closingTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath), TxOwner.Local, commitmentFormat)
        val closingSigned = ClosingSigned(channelId, closingFees.preferred, localClosingSig, TlvStream(ClosingSignedTlv.FeeRange(closingFees.min, closingFees.max)))
        log.info(s"signed closing txid=${closingTx.tx.txid} with closing fee=${closingSigned.feeSatoshis}")
        log.debug(s"closingTxid=${closingTx.tx.txid} closingTx=${closingTx.tx}}")
        (closingTx, closingSigned)
      }

      def checkClosingSignature(keyManager: ChannelKeyManager, commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, remoteClosingFee: Satoshi, remoteClosingSig: ByteVector64)(implicit log: LoggingAdapter): Either[ChannelException, (ClosingTx, ClosingSigned)] = {
        import commitments._
        val lastCommitFeeSatoshi = commitments.commitInput.txOut.amount - commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.map(_.amount).sum
        if (remoteClosingFee > lastCommitFeeSatoshi && commitments.commitmentFormat == DefaultCommitmentFormat) {
          log.error(s"remote proposed a commit fee higher than the last commitment fee: remote closing fee=${remoteClosingFee.toLong} last commit fees=$lastCommitFeeSatoshi")
          Left(InvalidCloseFee(commitments.channelId, remoteClosingFee))
        } else {
          val (closingTx, closingSigned) = makeClosingTx(keyManager, commitments, localScriptPubkey, remoteScriptPubkey, ClosingFees(remoteClosingFee, remoteClosingFee, remoteClosingFee))
          if (checkClosingDustAmounts(closingTx)) {
            val signedClosingTx = Transactions.addSigs(closingTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, closingSigned.signature, remoteClosingSig)
            Transactions.checkSpendable(signedClosingTx) match {
              case Success(_) => Right(signedClosingTx, closingSigned)
              case _ => Left(InvalidCloseSignature(commitments.channelId, signedClosingTx.tx))
            }
          } else {
            Left(InvalidCloseAmountBelowDust(commitments.channelId, closingTx.tx))
          }
        }
      }

      /**
       * Check that all closing outputs are above bitcoin's dust limit for their script type, otherwise there is a risk
       * that the closing transaction will not be relayed to miners' mempool and will not confirm.
       * The various dust limits are detailed in https://github.com/lightningnetwork/lightning-rfc/blob/master/03-transactions.md#dust-limits
       */
      def checkClosingDustAmounts(closingTx: ClosingTx): Boolean = {
        closingTx.tx.txOut.forall(txOut => {
          Try(Script.parse(txOut.publicKeyScript)) match {
            case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => txOut.amount >= 546.sat
            case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => txOut.amount >= 540.sat
            case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => txOut.amount >= 294.sat
            case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => txOut.amount >= 330.sat
            case Success((OP_1 | OP_2 | OP_3 | OP_4 | OP_5 | OP_6 | OP_7 | OP_8 | OP_9 | OP_10 | OP_11 | OP_12 | OP_13 | OP_14 | OP_15 | OP_16) :: OP_PUSHDATA(program, _) :: Nil) if 2 <= program.length && program.length <= 40 => txOut.amount >= 354.sat
            case _ => txOut.amount >= 546.sat
          }
        })
      }
    }

    /** Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment. */
    private def withTxGenerationLog[T <: TransactionWithInputInfo](desc: String, logSuccess: Boolean = true, logSkipped: Boolean = true, logFailure: Boolean = true)(generateTx: => Either[TxGenerationSkipped, T])(implicit log: LoggingAdapter): Option[T] = {
      Try {
        generateTx
      } match {
        case Success(Right(txinfo)) =>
          if (logSuccess) log.info(s"tx generation success: desc=$desc txid=${txinfo.tx.txid} amount=${txinfo.tx.txOut.map(_.amount).sum} tx=${txinfo.tx}")
          Some(txinfo)
        case Success(Left(skipped)) =>
          if (logSkipped) log.info(s"tx generation skipped: desc=$desc reason: ${skipped.toString}")
          None
        case Failure(t) =>
          if (logFailure) log.warning(s"tx generation failure: desc=$desc reason: ${t.getMessage}")
          None
      }
    }

    /** Compute the fee paid by a commitment transaction. */
    def commitTxFee(commitInput: InputInfo, commitTx: Transaction, isFunder: Boolean): Satoshi = {
      require(commitTx.txIn.size == 1, "transaction must have only one input")
      require(commitTx.txIn.exists(txIn => txIn.outPoint == commitInput.outPoint), "transaction must spend the funding output")
      if (isFunder) commitInput.txOut.amount - commitTx.txOut.map(_.amount).sum else 0 sat
    }

    object LocalClose {

      /**
       * Claim all the HTLCs that we've received from our current commit tx. This will be done using 2nd stage HTLC transactions.
       *
       * @param commitments our commitment data, which include payment preimages
       * @return a list of transactions (one per output of the commit tx that we can claim)
       */
      def claimCommitTxOutputs(keyManager: ChannelKeyManager, commitments: Commitments, tx: Transaction, currentBlockHeight: BlockHeight, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): LocalCommitPublished = {
        import commitments._
        require(localCommit.commitTxAndRemoteSig.commitTx.tx.txid == tx.txid, "txid mismatch, provided tx is not the current local commit tx")
        val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index.toInt)
        val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
        val localDelayedPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
        val localFundingPubKey = keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey
        val feeratePerKwDelayed = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)

        // first we will claim our main output as soon as the delay is over
        val mainDelayedTx = withTxGenerationLog("local-main-delayed") {
          Transactions.makeClaimLocalDelayedOutputTx(tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwDelayed).map(claimDelayed => {
            val sig = keyManager.sign(claimDelayed, keyManager.delayedPaymentPoint(channelKeyPath), localPerCommitmentPoint, TxOwner.Local, commitmentFormat)
            Transactions.addSigs(claimDelayed, sig)
          })
        }

        val htlcTxs: Map[OutPoint, Option[HtlcTx]] = claimHtlcOutputs(keyManager, commitments)

        // If we don't have pending HTLCs, we don't have funds at risk, so we can aim for a slower confirmation.
        val confirmCommitBefore = htlcTxs.values.flatten.map(htlcTx => htlcTx.confirmBefore).minOption.getOrElse(currentBlockHeight + feeTargets.commitmentWithoutHtlcsBlockTarget)
        val claimAnchorTxs: List[ClaimAnchorOutputTx] = List(
          withTxGenerationLog("local-anchor") {
            Transactions.makeClaimLocalAnchorOutputTx(tx, localFundingPubKey, confirmCommitBefore)
          },
          withTxGenerationLog("remote-anchor") {
            Transactions.makeClaimRemoteAnchorOutputTx(tx, commitments.remoteParams.fundingPubKey)
          }
        ).flatten

        LocalCommitPublished(
          commitTx = tx,
          claimMainDelayedOutputTx = mainDelayedTx,
          htlcTxs = htlcTxs,
          claimHtlcDelayedTxs = Nil, // we will claim these once the htlc txs are confirmed
          claimAnchorTxs = claimAnchorTxs,
          irrevocablySpent = Map.empty)
      }

      /**
       * Claim the output of a local commit tx corresponding to HTLCs.
       */
      def claimHtlcOutputs(keyManager: ChannelKeyManager, commitments: Commitments)(implicit log: LoggingAdapter): Map[OutPoint, Option[HtlcTx]] = {
        import commitments._
        val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index.toInt)

        // those are the preimages to existing received htlcs
        val hash2Preimage: Map[ByteVector32, ByteVector32] = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }.map(r => Crypto.sha256(r) -> r).toMap
        val failedIncomingHtlcs: Set[Long] = commitments.localChanges.all.collect {
          case u: UpdateFailHtlc => u.id
          case u: UpdateFailMalformedHtlc => u.id
        }.toSet

        localCommit.htlcTxsAndRemoteSigs.collect {
          case HtlcTxAndRemoteSig(txInfo@HtlcSuccessTx(_, _, paymentHash, _, _), remoteSig) =>
            if (hash2Preimage.contains(paymentHash)) {
              // incoming htlc for which we have the preimage: we can spend it immediately
              Some(txInfo.input.outPoint -> withTxGenerationLog("htlc-success") {
                val localSig = keyManager.sign(txInfo, keyManager.htlcPoint(channelKeyPath), localPerCommitmentPoint, TxOwner.Local, commitmentFormat)
                Right(Transactions.addSigs(txInfo, localSig, remoteSig, hash2Preimage(paymentHash), commitmentFormat))
              })
            } else if (failedIncomingHtlcs.contains(txInfo.htlcId)) {
              // incoming htlc that we know for sure will never be fulfilled downstream: we can safely discard it
              None
            } else {
              // incoming htlc for which we don't have the preimage: we can't spend it immediately, but we may learn the
              // preimage later, otherwise it will eventually timeout and they will get their funds back
              Some(txInfo.input.outPoint -> None)
            }
          case HtlcTxAndRemoteSig(txInfo: HtlcTimeoutTx, remoteSig) =>
            // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
            Some(txInfo.input.outPoint -> withTxGenerationLog("htlc-timeout") {
              val localSig = keyManager.sign(txInfo, keyManager.htlcPoint(channelKeyPath), localPerCommitmentPoint, TxOwner.Local, commitmentFormat)
              Right(Transactions.addSigs(txInfo, localSig, remoteSig, commitmentFormat))
            })
        }.flatten.toMap
      }

      /**
       * Claim the output of a 2nd-stage HTLC transaction. If the provided transaction isn't an htlc, this will be a no-op.
       *
       * NB: with anchor outputs, it's possible to have transactions that spend *many* HTLC outputs at once, but we're not
       * doing that because it introduces a lot of subtle edge cases.
       */
      def claimHtlcDelayedOutput(localCommitPublished: LocalCommitPublished, keyManager: ChannelKeyManager, commitments: Commitments, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): (LocalCommitPublished, Option[HtlcDelayedTx]) = {
        import commitments._
        if (isHtlcSuccess(tx, localCommitPublished) || isHtlcTimeout(tx, localCommitPublished)) {
          val feeratePerKwDelayed = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
          val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
          val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index.toInt)
          val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
          val localDelayedPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
          val htlcDelayedTx = withTxGenerationLog("htlc-delayed") {
            Transactions.makeHtlcDelayedTx(tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwDelayed).map(claimDelayed => {
              val sig = keyManager.sign(claimDelayed, keyManager.delayedPaymentPoint(channelKeyPath), localPerCommitmentPoint, TxOwner.Local, commitmentFormat)
              Transactions.addSigs(claimDelayed, sig)
            })
          }
          val localCommitPublished1 = localCommitPublished.copy(claimHtlcDelayedTxs = localCommitPublished.claimHtlcDelayedTxs ++ htlcDelayedTx.toSeq)
          (localCommitPublished1, htlcDelayedTx)
        } else {
          (localCommitPublished, None)
        }
      }
    }

    object RemoteClose {

      /**
       * Claim all the HTLCs that we've received from their current commit tx, if the channel used option_static_remotekey
       * we don't need to claim our main output because it directly pays to one of our wallet's p2wpkh addresses.
       *
       * @param commitments  our commitment data, which include payment preimages
       * @param remoteCommit the remote commitment data to use to claim outputs (it can be their current or next commitment)
       * @param tx           the remote commitment transaction that has just been published
       * @return a list of transactions (one per output of the commit tx that we can claim)
       */
      def claimCommitTxOutputs(keyManager: ChannelKeyManager, commitments: Commitments, remoteCommit: RemoteCommit, tx: Transaction, currentBlockHeight: BlockHeight, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): RemoteCommitPublished = {
        require(remoteCommit.txid == tx.txid, "txid mismatch, provided tx is not the current remote commit tx")

        val htlcTxs: Map[OutPoint, Option[ClaimHtlcTx]] = claimHtlcOutputs(keyManager, commitments, remoteCommit, feeEstimator)

        // If we don't have pending HTLCs, we don't have funds at risk, so we can aim for a slower confirmation.
        val confirmCommitBefore = htlcTxs.values.flatten.map(htlcTx => htlcTx.confirmBefore).minOption.getOrElse(currentBlockHeight + feeTargets.commitmentWithoutHtlcsBlockTarget)
        val localFundingPubkey = keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey
        val claimAnchorTxs: List[ClaimAnchorOutputTx] = List(
          withTxGenerationLog("local-anchor") {
            Transactions.makeClaimLocalAnchorOutputTx(tx, localFundingPubkey, confirmCommitBefore)
          },
          withTxGenerationLog("remote-anchor") {
            Transactions.makeClaimRemoteAnchorOutputTx(tx, commitments.remoteParams.fundingPubKey)
          }
        ).flatten

        RemoteCommitPublished(
          commitTx = tx,
          claimMainOutputTx = claimMainOutput(keyManager, commitments, remoteCommit.remotePerCommitmentPoint, tx, feeEstimator, feeTargets),
          claimHtlcTxs = htlcTxs,
          claimAnchorTxs = claimAnchorTxs,
          irrevocablySpent = Map.empty
        )
      }

      /**
       * Claim our main output only
       *
       * @param commitments              either our current commitment data in case of usual remote uncooperative closing
       *                                 or our outdated commitment data in case of data loss protection procedure; in any case it is used only
       *                                 to get some constant parameters, not commitment data
       * @param remotePerCommitmentPoint the remote perCommitmentPoint corresponding to this commitment
       * @param tx                       the remote commitment transaction that has just been published
       * @return an optional [[ClaimRemoteCommitMainOutputTx]] transaction claiming our main output
       */
      def claimMainOutput(keyManager: ChannelKeyManager, commitments: Commitments, remotePerCommitmentPoint: PublicKey, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): Option[ClaimRemoteCommitMainOutputTx] = {
        if (commitments.channelFeatures.paysDirectlyToWallet) {
          // the commitment tx sends funds directly to our wallet, no claim tx needed
          None
        } else {
          val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelConfig)
          val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
          val localPaymentPoint = keyManager.paymentPoint(channelKeyPath).publicKey
          val feeratePerKwMain = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)

          commitments.commitmentFormat match {
            case DefaultCommitmentFormat => withTxGenerationLog("remote-main") {
              Transactions.makeClaimP2WPKHOutputTx(tx, commitments.localParams.dustLimit, localPubkey, commitments.localParams.defaultFinalScriptPubKey, feeratePerKwMain).map(claimMain => {
                val sig = keyManager.sign(claimMain, keyManager.paymentPoint(channelKeyPath), remotePerCommitmentPoint, TxOwner.Local, commitments.commitmentFormat)
                Transactions.addSigs(claimMain, localPubkey, sig)
              })
            }
            case _: AnchorOutputsCommitmentFormat => withTxGenerationLog("remote-main-delayed") {
              Transactions.makeClaimRemoteDelayedOutputTx(tx, commitments.localParams.dustLimit, localPaymentPoint, commitments.localParams.defaultFinalScriptPubKey, feeratePerKwMain).map(claimMain => {
                val sig = keyManager.sign(claimMain, keyManager.paymentPoint(channelKeyPath), TxOwner.Local, commitments.commitmentFormat)
                Transactions.addSigs(claimMain, sig)
              })
            }
          }
        }
      }

      /**
       * Claim our htlc outputs only
       */
      def claimHtlcOutputs(keyManager: ChannelKeyManager, commitments: Commitments, remoteCommit: RemoteCommit, feeEstimator: FeeEstimator)(implicit log: LoggingAdapter): Map[OutPoint, Option[ClaimHtlcTx]] = {
        val (remoteCommitTx, _) = Commitments.makeRemoteTxs(keyManager, commitments.channelConfig, commitments.channelFeatures, remoteCommit.index, commitments.localParams, commitments.remoteParams, commitments.commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
        require(remoteCommitTx.tx.txid == remoteCommit.txid, "txid mismatch, cannot recompute the current remote commit tx")
        val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelConfig)
        val localFundingPubkey = keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey
        val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
        val remoteHtlcPubkey = Generators.derivePubKey(commitments.remoteParams.htlcBasepoint, remoteCommit.remotePerCommitmentPoint)
        val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
        val remoteDelayedPaymentPubkey = Generators.derivePubKey(commitments.remoteParams.delayedPaymentBasepoint, remoteCommit.remotePerCommitmentPoint)
        val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
        val outputs = makeCommitTxOutputs(!commitments.localParams.isFunder, commitments.remoteParams.dustLimit, remoteRevocationPubkey, commitments.localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, commitments.remoteParams.fundingPubKey, localFundingPubkey, remoteCommit.spec, commitments.commitmentFormat)
        // we need to use a rather high fee for htlc-claim because we compete with the counterparty
        val feeratePerKwHtlc = feeEstimator.getFeeratePerKw(target = 2)

        // those are the preimages to existing received htlcs
        val hash2Preimage: Map[ByteVector32, ByteVector32] = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }.map(r => Crypto.sha256(r) -> r).toMap
        val failedIncomingHtlcs: Set[Long] = commitments.localChanges.all.collect {
          case u: UpdateFailHtlc => u.id
          case u: UpdateFailMalformedHtlc => u.id
        }.toSet

        // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa
        remoteCommit.spec.htlcs.collect {
          case OutgoingHtlc(add: UpdateAddHtlc) =>
            // NB: we first generate the tx skeleton and finalize it below if we have the preimage, so we set logSuccess to false to avoid logging twice
            withTxGenerationLog("claim-htlc-success", logSuccess = false) {
              Transactions.makeClaimHtlcSuccessTx(remoteCommitTx.tx, outputs, commitments.localParams.dustLimit, localHtlcPubkey, remoteHtlcPubkey, remoteRevocationPubkey, commitments.localParams.defaultFinalScriptPubKey, add, feeratePerKwHtlc, commitments.commitmentFormat)
            }.map(claimHtlcTx => {
              if (hash2Preimage.contains(add.paymentHash)) {
                // incoming htlc for which we have the preimage: we can spend it immediately
                Some(claimHtlcTx.input.outPoint -> withTxGenerationLog("claim-htlc-success") {
                  val sig = keyManager.sign(claimHtlcTx, keyManager.htlcPoint(channelKeyPath), remoteCommit.remotePerCommitmentPoint, TxOwner.Local, commitments.commitmentFormat)
                  Right(Transactions.addSigs(claimHtlcTx, sig, hash2Preimage(add.paymentHash)))
                })
              } else if (failedIncomingHtlcs.contains(add.id)) {
                // incoming htlc that we know for sure will never be fulfilled downstream: we can safely discard it
                None
              } else {
                // incoming htlc for which we don't have the preimage: we can't spend it immediately, but we may learn the
                // preimage later, otherwise it will eventually timeout and they will get their funds back
                Some(claimHtlcTx.input.outPoint -> None)
              }
            })
          case IncomingHtlc(add: UpdateAddHtlc) =>
            // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
            // NB: we first generate the tx skeleton and finalize it below, so we set logSuccess to false to avoid logging twice
            withTxGenerationLog("claim-htlc-timeout", logSuccess = false) {
              Transactions.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, outputs, commitments.localParams.dustLimit, localHtlcPubkey, remoteHtlcPubkey, remoteRevocationPubkey, commitments.localParams.defaultFinalScriptPubKey, add, feeratePerKwHtlc, commitments.commitmentFormat)
            }.map(claimHtlcTx => {
              Some(claimHtlcTx.input.outPoint -> withTxGenerationLog("claim-htlc-timeout") {
                val sig = keyManager.sign(claimHtlcTx, keyManager.htlcPoint(channelKeyPath), remoteCommit.remotePerCommitmentPoint, TxOwner.Local, commitments.commitmentFormat)
                Right(Transactions.addSigs(claimHtlcTx, sig))
              })
            })
        }.toSeq.flatten.flatten.toMap
      }
    }

    object RevokedClose {

      /**
       * When an unexpected transaction spending the funding tx is detected:
       * 1) we find out if the published transaction is one of remote's revoked txs
       * 2) and then:
       * a) if it is a revoked tx we build a set of transactions that will punish them by stealing all their funds
       * b) otherwise there is nothing we can do
       *
       * @return a [[RevokedCommitPublished]] object containing penalty transactions if the tx is a revoked commitment
       */
      def claimCommitTxOutputs(keyManager: ChannelKeyManager, commitments: Commitments, commitTx: Transaction, db: ChannelsDb, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): Option[RevokedCommitPublished] = {
        import commitments._
        require(commitTx.txIn.size == 1, "commitment tx should have 1 input")
        val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
        val obscuredTxNumber = Transactions.decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime)
        val localPaymentPoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
        // this tx has been published by remote, so we need to invert local/remote params
        val txNumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, localPaymentPoint)
        require(txNumber <= 0xffffffffffffL, "txNumber must be lesser than 48 bits long")
        log.warning(s"a revoked commit has been published with txnumber=$txNumber")
        // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
        remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txNumber)
          .map(d => PrivateKey(d))
          .map(remotePerCommitmentSecret => {
            val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey
            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
            val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
            val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

            val feeratePerKwMain = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
            // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
            val feeratePerKwPenalty = feeEstimator.getFeeratePerKw(target = 2)

            // first we will claim our main output right away
            val mainTx = channelFeatures match {
              case ct if ct.paysDirectlyToWallet =>
                log.info(s"channel uses option_static_remotekey to pay directly to our wallet, there is nothing to do")
                None
              case ct => ct.commitmentFormat match {
                case DefaultCommitmentFormat => withTxGenerationLog("claim-p2wpkh-output") {
                  Transactions.makeClaimP2WPKHOutputTx(commitTx, localParams.dustLimit, localPaymentPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwMain).map(claimMain => {
                    val sig = keyManager.sign(claimMain, keyManager.paymentPoint(channelKeyPath), remotePerCommitmentPoint, TxOwner.Local, commitmentFormat)
                    Transactions.addSigs(claimMain, localPaymentPubkey, sig)
                  })
                }
                case _: AnchorOutputsCommitmentFormat => withTxGenerationLog("remote-main-delayed") {
                  Transactions.makeClaimRemoteDelayedOutputTx(commitTx, localParams.dustLimit, localPaymentPoint, localParams.defaultFinalScriptPubKey, feeratePerKwMain).map(claimMain => {
                    val sig = keyManager.sign(claimMain, keyManager.paymentPoint(channelKeyPath), TxOwner.Local, commitmentFormat)
                    Transactions.addSigs(claimMain, sig)
                  })
                }
              }
            }

            // then we punish them by stealing their main output
            val mainPenaltyTx = withTxGenerationLog("main-penalty") {
              Transactions.makeMainPenaltyTx(commitTx, localParams.dustLimit, remoteRevocationPubkey, localParams.defaultFinalScriptPubKey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, feeratePerKwPenalty).map(txinfo => {
                val sig = keyManager.sign(txinfo, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret, TxOwner.Local, commitmentFormat)
                Transactions.addSigs(txinfo, sig)
              })
            }

            // we retrieve the information needed to rebuild htlc scripts
            val htlcInfos = db.listHtlcInfos(commitments.channelId, txNumber)
            log.info(s"got htlcs=${htlcInfos.size} for txnumber=$txNumber")
            val htlcsRedeemScripts = (
              htlcInfos.map { case (paymentHash, cltvExpiry) => Scripts.htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(paymentHash), cltvExpiry, commitmentFormat) } ++
                htlcInfos.map { case (paymentHash, _) => Scripts.htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(paymentHash), commitmentFormat) }
              )
              .map(redeemScript => Script.write(pay2wsh(redeemScript)) -> Script.write(redeemScript))
              .toMap

            // and finally we steal the htlc outputs
            val htlcPenaltyTxs = commitTx.txOut.zipWithIndex.collect { case (txOut, outputIndex) if htlcsRedeemScripts.contains(txOut.publicKeyScript) =>
              val htlcRedeemScript = htlcsRedeemScripts(txOut.publicKeyScript)
              withTxGenerationLog("htlc-penalty") {
                Transactions.makeHtlcPenaltyTx(commitTx, outputIndex, htlcRedeemScript, localParams.dustLimit, localParams.defaultFinalScriptPubKey, feeratePerKwPenalty).map(htlcPenalty => {
                  val sig = keyManager.sign(htlcPenalty, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret, TxOwner.Local, commitmentFormat)
                  Transactions.addSigs(htlcPenalty, sig, remoteRevocationPubkey)
                })
              }
            }.toList.flatten

            RevokedCommitPublished(
              commitTx = commitTx,
              claimMainOutputTx = mainTx,
              mainPenaltyTx = mainPenaltyTx,
              htlcPenaltyTxs = htlcPenaltyTxs,
              claimHtlcDelayedPenaltyTxs = Nil, // we will generate and spend those if they publish their HtlcSuccessTx or HtlcTimeoutTx
              irrevocablySpent = Map.empty
            )
          })
      }

      /**
       * Claims the output of an [[HtlcSuccessTx]] or [[HtlcTimeoutTx]] transaction using a revocation key.
       *
       * In case a revoked commitment with pending HTLCs is published, there are two ways the HTLC outputs can be taken as punishment:
       * - by spending the corresponding output of the commitment tx, using [[HtlcPenaltyTx]] that we generate as soon as we detect that a revoked commit
       * as been spent; note that those transactions will compete with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] published by the counterparty.
       * - by spending the delayed output of [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] if those get confirmed; because the output of these txs is protected by
       * an OP_CSV delay, we will have time to spend them with a revocation key. In that case, we generate the spending transactions "on demand",
       * this is the purpose of this method.
       *
       * NB: when anchor outputs is used, htlc transactions can be aggregated in a single transaction if they share the same
       * lockTime (thanks to the use of sighash_single | sighash_anyonecanpay), so we may need to claim multiple outputs.
       */
      def claimHtlcTxOutputs(keyManager: ChannelKeyManager, commitments: Commitments, revokedCommitPublished: RevokedCommitPublished, htlcTx: Transaction, feeEstimator: FeeEstimator)(implicit log: LoggingAdapter): (RevokedCommitPublished, Seq[ClaimHtlcDelayedOutputPenaltyTx]) = {
        val isHtlcTx = htlcTx.txIn.map(_.outPoint.txid).contains(revokedCommitPublished.commitTx.txid) &&
          htlcTx.txIn.map(_.witness).collect(Scripts.extractPreimageFromHtlcSuccess.orElse(Scripts.extractPaymentHashFromHtlcTimeout)).nonEmpty
        if (isHtlcTx) {
          log.info(s"looks like txid=${htlcTx.txid} could be a 2nd level htlc tx spending revoked commit txid=${revokedCommitPublished.commitTx.txid}")
          // Let's assume that htlcTx is an HtlcSuccessTx or HtlcTimeoutTx and try to generate a tx spending its output using a revocation key
          import commitments._
          val commitTx = revokedCommitPublished.commitTx
          val obscuredTxNumber = Transactions.decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime)
          val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
          val localPaymentPoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
          // this tx has been published by remote, so we need to invert local/remote params
          val txNumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, localPaymentPoint)
          // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
          remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txNumber)
            .map(d => PrivateKey(d))
            .map(remotePerCommitmentSecret => {
              val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey
              val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
              val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

              // we need to use a high fee here for punishment txs because after a delay they can be spent by the counterparty
              val feeratePerKwPenalty = feeEstimator.getFeeratePerKw(target = 1)

              val penaltyTxs = Transactions.makeClaimHtlcDelayedOutputPenaltyTxs(htlcTx, localParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwPenalty).flatMap(claimHtlcDelayedOutputPenaltyTx => {
                withTxGenerationLog("htlc-delayed-penalty") {
                  claimHtlcDelayedOutputPenaltyTx.map(htlcDelayedPenalty => {
                    val sig = keyManager.sign(htlcDelayedPenalty, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret, TxOwner.Local, commitmentFormat)
                    val signedTx = Transactions.addSigs(htlcDelayedPenalty, sig)
                    // we need to make sure that the tx is indeed valid
                    Transaction.correctlySpends(signedTx.tx, Seq(htlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                    signedTx
                  })
                }
              })
              val revokedCommitPublished1 = revokedCommitPublished.copy(claimHtlcDelayedPenaltyTxs = revokedCommitPublished.claimHtlcDelayedPenaltyTxs ++ penaltyTxs)
              (revokedCommitPublished1, penaltyTxs)
            }).getOrElse((revokedCommitPublished, Nil))
        } else {
          (revokedCommitPublished, Nil)
        }
      }
    }

    /**
     * In CLOSING state, any time we see a new transaction, we try to extract a preimage from it in order to fulfill the
     * corresponding incoming htlc in an upstream channel.
     *
     * Not doing that would result in us losing money, because the downstream node would pull money from one side, and
     * the upstream node would get refunded after a timeout.
     *
     * @return a set of pairs (add, preimage) if extraction was successful:
     *           - add is the htlc in the downstream channel from which we extracted the preimage
     *           - preimage needs to be sent to the upstream channel
     */
    def extractPreimages(localCommit: LocalCommit, tx: Transaction)(implicit log: LoggingAdapter): Set[(UpdateAddHtlc, ByteVector32)] = {
      val htlcSuccess = tx.txIn.map(_.witness).collect(Scripts.extractPreimageFromHtlcSuccess)
      htlcSuccess.foreach(r => log.info(s"extracted paymentPreimage=$r from tx=$tx (htlc-success)"))
      val claimHtlcSuccess = tx.txIn.map(_.witness).collect(Scripts.extractPreimageFromClaimHtlcSuccess)
      claimHtlcSuccess.foreach(r => log.info(s"extracted paymentPreimage=$r from tx=$tx (claim-htlc-success)"))
      val paymentPreimages = (htlcSuccess ++ claimHtlcSuccess).toSet
      paymentPreimages.flatMap { paymentPreimage =>
        // we only consider htlcs in our local commitment, because we only care about outgoing htlcs, which disappear first in the remote commitment
        // if an outgoing htlc is in the remote commitment, then:
        // - either it is in the local commitment (it was never fulfilled)
        // - or we have already received the fulfill and forwarded it upstream
        localCommit.spec.htlcs.collect {
          case OutgoingHtlc(add) if add.paymentHash == sha256(paymentPreimage) => (add, paymentPreimage)
        }
      }
    }

    def isHtlcTimeout(tx: Transaction, localCommitPublished: LocalCommitPublished): Boolean = {
      tx.txIn.filter(txIn => localCommitPublished.htlcTxs.get(txIn.outPoint) match {
        case Some(Some(_: HtlcTimeoutTx)) => true
        case _ => false
      }).map(_.witness).collect(Scripts.extractPaymentHashFromHtlcTimeout).nonEmpty
    }

    def isHtlcSuccess(tx: Transaction, localCommitPublished: LocalCommitPublished): Boolean = {
      tx.txIn.filter(txIn => localCommitPublished.htlcTxs.get(txIn.outPoint) match {
        case Some(Some(_: HtlcSuccessTx)) => true
        case _ => false
      }).map(_.witness).collect(Scripts.extractPreimageFromHtlcSuccess).nonEmpty
    }

    def isClaimHtlcTimeout(tx: Transaction, remoteCommitPublished: RemoteCommitPublished): Boolean = {
      tx.txIn.filter(txIn => remoteCommitPublished.claimHtlcTxs.get(txIn.outPoint) match {
        case Some(Some(_: ClaimHtlcTimeoutTx)) => true
        case _ => false
      }).map(_.witness).collect(Scripts.extractPaymentHashFromClaimHtlcTimeout).nonEmpty
    }

    def isClaimHtlcSuccess(tx: Transaction, remoteCommitPublished: RemoteCommitPublished): Boolean = {
      tx.txIn.filter(txIn => remoteCommitPublished.claimHtlcTxs.get(txIn.outPoint) match {
        case Some(Some(_: ClaimHtlcSuccessTx)) => true
        case _ => false
      }).map(_.witness).collect(Scripts.extractPreimageFromClaimHtlcSuccess).nonEmpty
    }

    /**
     * Before eclair v0.6.0, we didn't store the mapping between htlc txs and the htlc id.
     * This function is only used for channels that were closing before upgrading to eclair v0.6.0 (released in may 2021).
     * TODO: remove once we're confident all eclair nodes on the network have been upgraded.
     *
     * We may have multiple HTLCs with the same payment hash because of MPP.
     * When a timeout transaction is confirmed, we need to find the best matching HTLC to fail upstream.
     * We need to handle potentially duplicate HTLCs (same amount and expiry): this function will use a deterministic
     * ordering of transactions and HTLCs to handle this.
     */
    private def findTimedOutHtlc(tx: Transaction, paymentHash160: ByteVector, htlcs: Seq[UpdateAddHtlc], timeoutTxs: Seq[Transaction], extractPaymentHash: PartialFunction[ScriptWitness, ByteVector])(implicit log: LoggingAdapter): Option[UpdateAddHtlc] = {
      // We use a deterministic ordering to match HTLCs to their corresponding timeout tx.
      // We don't match on the expected amounts because this is error-prone: computing the correct weight of a timeout tx
      // is hard because signatures can be either 71, 72 or 73 bytes long (ECDSA DER encoding).
      // It's simpler to just use the amount as the first ordering key: since the feerate is the same for all timeout
      // transactions we will find the right HTLC to fail upstream.
      val matchingHtlcs = htlcs
        .filter(add => add.cltvExpiry.toLong == tx.lockTime && Crypto.ripemd160(add.paymentHash) == paymentHash160)
        .sortBy(add => (add.amountMsat.toLong, add.id))
      val matchingTxs = timeoutTxs
        .filter(timeoutTx => timeoutTx.lockTime == tx.lockTime && timeoutTx.txIn.map(_.witness).collect(extractPaymentHash).contains(paymentHash160))
        .sortBy(timeoutTx => (timeoutTx.txOut.map(_.amount.toLong).sum, timeoutTx.txIn.head.outPoint.index))
      if (matchingTxs.size != matchingHtlcs.size) {
        log.error(s"some htlcs don't have a corresponding timeout transaction: tx=$tx, htlcs=$matchingHtlcs, timeout-txs=$matchingTxs")
      }
      matchingHtlcs.zip(matchingTxs).collectFirst {
        // HTLC transactions cannot change when anchor outputs is not used, so we could just check that the txids match.
        // But claim-htlc transactions can be updated to pay more or less fees by changing the output amount, so we cannot
        // rely on txid equality for them.
        // We instead check that the mempool transaction spends exactly the same inputs and sends the funds to exactly
        // the same addresses as our transaction.
        case (add, timeoutTx) if timeoutTx.txIn.map(_.outPoint).toSet == tx.txIn.map(_.outPoint).toSet && timeoutTx.txOut.map(_.publicKeyScript).toSet == tx.txOut.map(_.publicKeyScript).toSet => add
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
     * more htlcs have timed out and need to be failed in an upstream channel. Trimmed htlcs can be failed as soon as
     * the commitment tx has been confirmed.
     *
     * @param tx a tx that has reached mindepth
     * @return a set of htlcs that need to be failed upstream
     */
    def trimmedOrTimedOutHtlcs(commitmentFormat: CommitmentFormat, localCommit: LocalCommit, localCommitPublished: LocalCommitPublished, localDustLimit: Satoshi, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val untrimmedHtlcs = Transactions.trimOfferedHtlcs(localDustLimit, localCommit.spec, commitmentFormat).map(_.add)
      if (tx.txid == localCommit.commitTxAndRemoteSig.commitTx.tx.txid) {
        // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
        localCommit.spec.htlcs.collect(outgoing) -- untrimmedHtlcs
      } else {
        // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
        val isMissingHtlcIndex = localCommitPublished.htlcTxs.values.collect { case Some(HtlcTimeoutTx(_, _, htlcId, _)) => htlcId }.toSet == Set(0)
        if (isMissingHtlcIndex && commitmentFormat == DefaultCommitmentFormat) {
          tx.txIn
            .map(_.witness)
            .collect(Scripts.extractPaymentHashFromHtlcTimeout)
            .flatMap { paymentHash160 =>
              log.info(s"htlc-timeout tx for paymentHash160=${paymentHash160.toHex} expiry=${tx.lockTime} has been confirmed (tx=$tx)")
              val timeoutTxs = localCommitPublished.htlcTxs.values.collect { case Some(HtlcTimeoutTx(_, tx, _, _)) => tx }.toSeq
              findTimedOutHtlc(tx, paymentHash160, untrimmedHtlcs, timeoutTxs, Scripts.extractPaymentHashFromHtlcTimeout)
            }.toSet
        } else {
          tx.txIn.flatMap(txIn => localCommitPublished.htlcTxs.get(txIn.outPoint) match {
            case Some(Some(HtlcTimeoutTx(_, _, htlcId, _))) if isHtlcTimeout(tx, localCommitPublished) =>
              untrimmedHtlcs.find(_.id == htlcId) match {
                case Some(htlc) =>
                  log.info(s"htlc-timeout tx for htlc #$htlcId paymentHash=${htlc.paymentHash} expiry=${tx.lockTime} has been confirmed (tx=$tx)")
                  Some(htlc)
                case None =>
                  log.error(s"could not find htlc #$htlcId for htlc-timeout tx=$tx")
                  None
              }
            case _ => None
          }).toSet
        }
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
     * more htlcs have timed out and need to be failed in an upstream channel. Trimmed htlcs can be failed as soon as
     * the commitment tx has been confirmed.
     *
     * @param tx a tx that has reached mindepth
     * @return a set of htlcs that need to be failed upstream
     */
    def trimmedOrTimedOutHtlcs(commitmentFormat: CommitmentFormat, remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished, remoteDustLimit: Satoshi, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val untrimmedHtlcs = Transactions.trimReceivedHtlcs(remoteDustLimit, remoteCommit.spec, commitmentFormat).map(_.add)
      if (tx.txid == remoteCommit.txid) {
        // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
        remoteCommit.spec.htlcs.collect(incoming) -- untrimmedHtlcs
      } else {
        // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
        val isMissingHtlcIndex = remoteCommitPublished.claimHtlcTxs.values.collect { case Some(ClaimHtlcTimeoutTx(_, _, htlcId, _)) => htlcId }.toSet == Set(0)
        if (isMissingHtlcIndex && commitmentFormat == DefaultCommitmentFormat) {
          tx.txIn
            .map(_.witness)
            .collect(Scripts.extractPaymentHashFromClaimHtlcTimeout)
            .flatMap { paymentHash160 =>
              log.info(s"claim-htlc-timeout tx for paymentHash160=${paymentHash160.toHex} expiry=${tx.lockTime} has been confirmed (tx=$tx)")
              val timeoutTxs = remoteCommitPublished.claimHtlcTxs.values.collect { case Some(ClaimHtlcTimeoutTx(_, tx, _, _)) => tx }.toSeq
              findTimedOutHtlc(tx, paymentHash160, untrimmedHtlcs, timeoutTxs, Scripts.extractPaymentHashFromClaimHtlcTimeout)
            }.toSet
        } else {
          tx.txIn.flatMap(txIn => remoteCommitPublished.claimHtlcTxs.get(txIn.outPoint) match {
            case Some(Some(ClaimHtlcTimeoutTx(_, _, htlcId, _))) if isClaimHtlcTimeout(tx, remoteCommitPublished) =>
              untrimmedHtlcs.find(_.id == htlcId) match {
                case Some(htlc) =>
                  log.info(s"claim-htlc-timeout tx for htlc #$htlcId paymentHash=${htlc.paymentHash} expiry=${tx.lockTime} has been confirmed (tx=$tx)")
                  Some(htlc)
                case None =>
                  log.error(s"could not find htlc #$htlcId for claim-htlc-timeout tx=$tx")
                  None
              }
            case _ => None
          }).toSet
        }
      }
    }

    /**
     * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
     * or not they actually have an output in the commitment tx).
     *
     * @param tx a transaction that is sufficiently buried in the blockchain
     */
    def onChainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: Option[RemoteCommit], tx: Transaction): Set[UpdateAddHtlc] = {
      if (localCommit.commitTxAndRemoteSig.commitTx.tx.txid == tx.txid) {
        localCommit.spec.htlcs.collect(outgoing)
      } else if (remoteCommit.txid == tx.txid) {
        remoteCommit.spec.htlcs.collect(incoming)
      } else if (nextRemoteCommit_opt.map(_.txid).contains(tx.txid)) {
        nextRemoteCommit_opt.get.spec.htlcs.collect(incoming)
      } else {
        Set.empty
      }
    }

    /**
     * If a commitment tx reaches min_depth, we need to fail the outgoing htlcs that will never reach the blockchain.
     * It could be because only us had signed them, or because a revoked commitment got confirmed.
     */
    def overriddenOutgoingHtlcs(d: DATA_CLOSING, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val localCommit = d.commitments.localCommit
      val remoteCommit = d.commitments.remoteCommit
      val nextRemoteCommit_opt = d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit)
      if (localCommit.commitTxAndRemoteSig.commitTx.tx.txid == tx.txid) {
        // our commit got confirmed, so any htlc that is in their commitment but not in ours will never reach the chain
        val htlcsInRemoteCommit = remoteCommit.spec.htlcs ++ nextRemoteCommit_opt.map(_.spec.htlcs).getOrElse(Set.empty)
        // NB: from the p.o.v of remote, their incoming htlcs are our outgoing htlcs
        htlcsInRemoteCommit.collect(incoming) -- localCommit.spec.htlcs.collect(outgoing)
      } else if (remoteCommit.txid == tx.txid) {
        // their commit got confirmed
        nextRemoteCommit_opt match {
          case Some(nextRemoteCommit) =>
            // we had signed a new commitment but they committed the previous one
            // any htlc that we signed in the new commitment that they didn't sign will never reach the chain
            nextRemoteCommit.spec.htlcs.collect(incoming) -- localCommit.spec.htlcs.collect(outgoing)
          case None =>
            // their last commitment got confirmed, so no htlcs will be overridden, they will timeout or be fulfilled on chain
            Set.empty
        }
      } else if (nextRemoteCommit_opt.map(_.txid).contains(tx.txid)) {
        // their last commitment got confirmed, so no htlcs will be overridden, they will timeout or be fulfilled on chain
        Set.empty
      } else if (d.revokedCommitPublished.map(_.commitTx.txid).contains(tx.txid)) {
        // a revoked commitment got confirmed: we will claim its outputs, but we also need to fail htlcs that are pending in the latest commitment:
        //  - outgoing htlcs that are in the local commitment but not in remote/nextRemote have already been fulfilled/failed so we don't care about them
        //  - outgoing htlcs that are in the remote/nextRemote commitment may not really be overridden, but since we are going to claim their output as a
        //    punishment we will never get the preimage and may as well consider them failed in the context of relaying htlcs
        nextRemoteCommit_opt.getOrElse(remoteCommit).spec.htlcs.collect(incoming)
      } else {
        Set.empty
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * local commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    def updateLocalCommitPublished(localCommitPublished: LocalCommitPublished, tx: Transaction): LocalCommitPublished = {
      // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter(outPoint => {
        // is this the commit tx itself? (we could do this outside of the loop...)
        val isCommitTx = localCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = localCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = localCommitPublished.claimHtlcDelayedTxs.map(_.input.outPoint).contains(outPoint)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      })
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      localCommitPublished.copy(irrevocablySpent = localCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx).toMap)
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * remote commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    def updateRemoteCommitPublished(remoteCommitPublished: RemoteCommitPublished, tx: Transaction): RemoteCommitPublished = {
      // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter(outPoint => {
        // is this the commit tx itself? (we could do this outside of the loop...)
        val isCommitTx = remoteCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the remote commitment tx?
        val spendsTheCommitTx = remoteCommitPublished.commitTx.txid == outPoint.txid
        isCommitTx || spendsTheCommitTx
      })
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      remoteCommitPublished.copy(irrevocablySpent = remoteCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx).toMap)
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
     * revoked commit scenario and keep track of it.
     *
     * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
     * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
     * want to wait forever before declaring that the channel is CLOSED.
     *
     * @param tx a transaction that has been irrevocably confirmed
     */
    def updateRevokedCommitPublished(revokedCommitPublished: RevokedCommitPublished, tx: Transaction): RevokedCommitPublished = {
      // even if our txs only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter(outPoint => {
        // is this the commit tx itself? (we could do this outside of the loop...)
        val isCommitTx = revokedCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the remote commitment tx?
        val spendsTheCommitTx = revokedCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txs? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = revokedCommitPublished.claimHtlcDelayedPenaltyTxs.map(_.input.outPoint).contains(outPoint)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      })
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      revokedCommitPublished.copy(irrevocablySpent = revokedCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx).toMap)
    }

    /**
     * This helper function tells if some of the utxos consumed by the given transaction have already been irrevocably spent (possibly by this very transaction).
     *
     * It can be useful to:
     *   - not attempt to publish this tx when we know this will fail
     *   - not watch for confirmations if we know the tx is already confirmed
     *   - not watch the corresponding utxo when we already know the final spending tx
     *
     * @param tx               an arbitrary transaction
     * @param irrevocablySpent a map of known spent outpoints
     * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
     */
    def inputsAlreadySpent(tx: Transaction, irrevocablySpent: Map[OutPoint, Transaction]): Boolean = {
      tx.txIn.exists(txIn => irrevocablySpent.contains(txIn.outPoint))
    }

    def inputAlreadySpent(input: OutPoint, irrevocablySpent: Map[OutPoint, Transaction]): Boolean = {
      irrevocablySpent.contains(input)
    }

  }

}
