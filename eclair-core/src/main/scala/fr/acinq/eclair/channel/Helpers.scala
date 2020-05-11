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
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, ripemd160, sha256}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets}
import fr.acinq.eclair.channel.Channel.REFRESH_CHANNEL_UPDATE_INTERVAL
import fr.acinq.eclair.crypto.{Generators, KeyManager}
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.transactions.DirectedHtlc._
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{NodeParams, ShortChannelId, addressToPublicKeyScript, _}
import scodec.bits.ByteVector

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Created by PM on 20/05/2016.
 */

object Helpers {

  /**
   * Depending on the state, returns the current temporaryChannelId or channelId
   *
   * @return the long identifier of the channel
   */
  def getChannelId(stateData: Data): ByteVector32 = stateData match {
    case Nothing => ByteVector32.Zeroes
    case d: DATA_WAIT_FOR_OPEN_CHANNEL => d.initFundee.temporaryChannelId
    case d: DATA_WAIT_FOR_ACCEPT_CHANNEL => d.initFunder.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_INTERNAL => d.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_CREATED => d.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_SIGNED => d.channelId
    case d: HasCommitments => d.channelId
  }

  /**
   * We update local/global features at reconnection
   */
  def updateFeatures(data: HasCommitments, localInit: Init, remoteInit: Init): HasCommitments = {
    val commitments1 = data.commitments.copy(
      localParams = data.commitments.localParams.copy(features = localInit.features),
      remoteParams = data.commitments.remoteParams.copy(features = remoteInit.features))
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
  def minDepthForFunding(nodeParams: NodeParams, fundingSatoshis: Satoshi): Long = fundingSatoshis match {
    case funding if funding <= Channel.MAX_FUNDING => nodeParams.minDepthBlocks
    case funding if funding > Channel.MAX_FUNDING =>
      val blockReward = 6.25 // this is true as of ~May 2020, but will be too large after 2024
      val scalingFactor = 15
      val blocksToReachFunding = (((scalingFactor * funding.toBtc.toDouble) / blockReward).ceil + 1).toInt
      nodeParams.minDepthBlocks.max(blocksToReachFunding)
  }

  /**
   * Called by the fundee
   */
  def validateParamsFundee(nodeParams: NodeParams, open: OpenChannel): Unit = {
    // BOLT #2: if the chain_hash value, within the open_channel, message is set to a hash of a chain that is unknown to the receiver:
    // MUST reject the channel.
    if (nodeParams.chainHash != open.chainHash) throw InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash)

    if (open.fundingSatoshis < nodeParams.minFundingSatoshis || open.fundingSatoshis > nodeParams.maxFundingSatoshis) throw InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, nodeParams.minFundingSatoshis, nodeParams.maxFundingSatoshis)

    // BOLT #2: Channel funding limits
    if (open.fundingSatoshis >= Channel.MAX_FUNDING && !nodeParams.features.hasFeature(Features.Wumbo)) throw InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, nodeParams.minFundingSatoshis, Channel.MAX_FUNDING)

    // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
    if (open.pushMsat > open.fundingSatoshis) throw InvalidPushAmount(open.temporaryChannelId, open.pushMsat, open.fundingSatoshis.toMilliSatoshi)

    // BOLT #2: The receiving node MUST fail the channel if: to_self_delay is unreasonably large.
    if (open.toSelfDelay > Channel.MAX_TO_SELF_DELAY || open.toSelfDelay > nodeParams.maxToLocalDelayBlocks) throw ToSelfDelayTooHigh(open.temporaryChannelId, open.toSelfDelay, nodeParams.maxToLocalDelayBlocks)

    // BOLT #2: The receiving node MUST fail the channel if: max_accepted_htlcs is greater than 483.
    if (open.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) throw InvalidMaxAcceptedHtlcs(open.temporaryChannelId, open.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS)

    // BOLT #2: The receiving node MUST fail the channel if: push_msat is greater than funding_satoshis * 1000.
    if (isFeeTooSmall(open.feeratePerKw)) throw FeerateTooSmall(open.temporaryChannelId, open.feeratePerKw)

    // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
    if (open.dustLimitSatoshis > open.channelReserveSatoshis) throw DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, open.channelReserveSatoshis)

    // BOLT #2: The receiving node MUST fail the channel if both to_local and to_remote amounts for the initial commitment
    // transaction are less than or equal to channel_reserve_satoshis (see BOLT 3).
    val (toLocalMsat, toRemoteMsat) = (open.pushMsat, open.fundingSatoshis.toMilliSatoshi - open.pushMsat)
    if (toLocalMsat < open.channelReserveSatoshis && toRemoteMsat < open.channelReserveSatoshis) {
      throw ChannelReserveNotMet(open.temporaryChannelId, toLocalMsat, toRemoteMsat, open.channelReserveSatoshis)
    }

    val localFeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget)
    if (isFeeDiffTooHigh(open.feeratePerKw, localFeeratePerKw, nodeParams.onChainFeeConf.maxFeerateMismatch)) throw FeerateTooDifferent(open.temporaryChannelId, localFeeratePerKw, open.feeratePerKw)
    // only enforce dust limit check on mainnet
    if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
      if (open.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) throw DustLimitTooSmall(open.temporaryChannelId, open.dustLimitSatoshis, Channel.MIN_DUSTLIMIT)
    }

    // we don't check that the funder's amount for the initial commitment transaction is sufficient for full fee payment
    // now, but it will be done later when we receive `funding_created`

    val reserveToFundingRatio = open.channelReserveSatoshis.toLong.toDouble / Math.max(open.fundingSatoshis.toLong, 1)
    if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) throw ChannelReserveTooHigh(open.temporaryChannelId, open.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio)
  }

  /**
   * Called by the funder
   */
  def validateParamsFunder(nodeParams: NodeParams, open: OpenChannel, accept: AcceptChannel): Unit = {
    if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) throw InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS)
    // only enforce dust limit check on mainnet
    if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
      if (accept.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) throw DustLimitTooSmall(accept.temporaryChannelId, accept.dustLimitSatoshis, Channel.MIN_DUSTLIMIT)
    }

    // BOLT #2: The receiving node MUST fail the channel if: dust_limit_satoshis is greater than channel_reserve_satoshis.
    if (accept.dustLimitSatoshis > accept.channelReserveSatoshis) throw DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, accept.channelReserveSatoshis)

    // if minimum_depth is unreasonably large:
    // MAY reject the channel.
    if (accept.toSelfDelay > Channel.MAX_TO_SELF_DELAY || accept.toSelfDelay > nodeParams.maxToLocalDelayBlocks) throw ToSelfDelayTooHigh(accept.temporaryChannelId, accept.toSelfDelay, nodeParams.maxToLocalDelayBlocks)

    // if channel_reserve_satoshis is less than dust_limit_satoshis within the open_channel message:
    //  MUST reject the channel.
    if (accept.channelReserveSatoshis < open.dustLimitSatoshis) throw ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, accept.channelReserveSatoshis, open.dustLimitSatoshis)

    // if channel_reserve_satoshis from the open_channel message is less than dust_limit_satoshis:
    // MUST reject the channel. Other fields have the same requirements as their counterparts in open_channel.
    if (open.channelReserveSatoshis < accept.dustLimitSatoshis) throw DustLimitAboveOurChannelReserve(accept.temporaryChannelId, accept.dustLimitSatoshis, open.channelReserveSatoshis)

    val reserveToFundingRatio = accept.channelReserveSatoshis.toLong.toDouble / Math.max(open.fundingSatoshis.toLong, 1)
    if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) throw ChannelReserveTooHigh(open.temporaryChannelId, accept.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio)
  }

  /**
   * Compute the delay until we need to refresh the channel_update for our channel not to be considered stale by
   * other nodes.
   *
   * If current update more than [[Channel.REFRESH_CHANNEL_UPDATE_INTERVAL]] old then the delay will be zero.
   *
   * @return the delay until the next update
   */
  def nextChannelUpdateRefresh(currentUpdateTimestamp: Long)(implicit log: DiagnosticLoggingAdapter): FiniteDuration = {
    val age = System.currentTimeMillis.milliseconds - currentUpdateTimestamp.seconds
    val delay = 0.days.max(REFRESH_CHANNEL_UPDATE_INTERVAL - age)
    Logs.withMdc(log)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
      log.info("current channel_update was created {} days ago, will refresh it in {} days", age.toDays, delay.toDays)
    }
    delay
  }

  /**
   * @param referenceFeePerKw reference fee rate per kiloweight
   * @param currentFeePerKw   current fee rate per kiloweight
   * @return the "normalized" difference between i.e local and remote fee rate: |reference - current| / avg(current, reference)
   */
  def feeRateMismatch(referenceFeePerKw: Long, currentFeePerKw: Long): Double =
    Math.abs((2.0 * (referenceFeePerKw - currentFeePerKw)) / (currentFeePerKw + referenceFeePerKw))

  def shouldUpdateFee(commitmentFeeratePerKw: Long, networkFeeratePerKw: Long, updateFeeMinDiffRatio: Double): Boolean =
    feeRateMismatch(networkFeeratePerKw, commitmentFeeratePerKw) > updateFeeMinDiffRatio

  /**
   * @param referenceFeePerKw       reference fee rate per kiloweight
   * @param currentFeePerKw         current fee rate per kiloweight
   * @param maxFeerateMismatchRatio maximum fee rate mismatch ratio
   * @return true if the difference between current and reference fee rates is too high.
   *         the actual check is |reference - current| / avg(current, reference) > mismatch ratio
   */
  def isFeeDiffTooHigh(referenceFeePerKw: Long, currentFeePerKw: Long, maxFeerateMismatchRatio: Double): Boolean =
    feeRateMismatch(referenceFeePerKw, currentFeePerKw) > maxFeerateMismatchRatio

  /**
   * @param remoteFeeratePerKw remote fee rate per kiloweight
   * @return true if the remote fee rate is too small
   */
  def isFeeTooSmall(remoteFeeratePerKw: Long): Boolean = {
    remoteFeeratePerKw < fr.acinq.eclair.MinimumFeeratePerKw
  }

  def makeAnnouncementSignatures(nodeParams: NodeParams, commitments: Commitments, shortChannelId: ShortChannelId): AnnouncementSignatures = {
    val features = ByteVector.empty // empty features for now
    val fundingPubKey = nodeParams.keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath)
    val (localNodeSig, localBitcoinSig) = nodeParams.keyManager.signChannelAnnouncement(fundingPubKey.path, nodeParams.chainHash, shortChannelId, commitments.remoteParams.nodeId, commitments.remoteParams.fundingPubKey, features)
    AnnouncementSignatures(commitments.channelId, shortChannelId, localNodeSig, localBitcoinSig)
  }

  /**
   * This indicates whether our side of the channel is above the reserve requested by our counterparty. In other words,
   * this tells if we can use the channel to make a payment.
   *
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

  def getFinalScriptPubKey(wallet: EclairWallet, chainHash: ByteVector32): ByteVector = {
    import scala.concurrent.duration._
    val finalAddress = Await.result(wallet.getFinalAddress, 40 seconds)

    Script.write(addressToPublicKeyScript(finalAddress, chainHash))
  }

  object Funding {

    def makeFundingInputInfo(fundingTxId: ByteVector32, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: PublicKey, fundingPubkey2: PublicKey): InputInfo = {
      val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
      InputInfo(OutPoint(fundingTxId, fundingTxOutputIndex), fundingTxOut, write(fundingScript))
    }

    /**
     * Creates both sides's first commitment transaction
     *
     * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
     */
    def makeFirstCommitTxs(keyManager: KeyManager, channelVersion: ChannelVersion, temporaryChannelId: ByteVector32, localParams: LocalParams, remoteParams: RemoteParams, fundingAmount: Satoshi, pushMsat: MilliSatoshi, initialFeeratePerKw: Long, fundingTxHash: ByteVector32, fundingTxOutputIndex: Int, remoteFirstPerCommitmentPoint: PublicKey): (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx) = {
      val toLocalMsat = if (localParams.isFunder) fundingAmount.toMilliSatoshi - pushMsat else pushMsat
      val toRemoteMsat = if (localParams.isFunder) pushMsat else fundingAmount.toMilliSatoshi - pushMsat

      val localSpec = CommitmentSpec(Set.empty[DirectedHtlc], feeratePerKw = initialFeeratePerKw, toLocal = toLocalMsat, toRemote = toRemoteMsat)
      val remoteSpec = CommitmentSpec(Set.empty[DirectedHtlc], feeratePerKw = initialFeeratePerKw, toLocal = toRemoteMsat, toRemote = toLocalMsat)

      if (!localParams.isFunder) {
        // they are funder, therefore they pay the fee: we need to make sure they can afford it!
        val toRemoteMsat = remoteSpec.toLocal
        val fees = commitTxFee(remoteParams.dustLimit, remoteSpec)
        val missing = toRemoteMsat.truncateToSatoshi - localParams.channelReserve - fees
        if (missing < Satoshi(0)) {
          throw CannotAffordFees(temporaryChannelId, missing = -missing, reserve = localParams.channelReserve, fees = fees)
        }
      }

      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
      val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, fundingAmount, fundingPubKey.publicKey, remoteParams.fundingPubKey)
      val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0)
      val (localCommitTx, _, _) = Commitments.makeLocalTxs(keyManager, channelVersion, 0, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(keyManager, channelVersion, 0, localParams, remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec)

      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }

  }

  /**
   * Tells whether or not their expected next remote commitment number matches with our data
   *
   * @return
   *         - true if parties are in sync or remote is behind
   *         - false if we are behind
   */
  def checkLocalCommit(d: HasCommitments, nextRemoteRevocationNumber: Long): Boolean = {
    if (d.commitments.localCommit.index == nextRemoteRevocationNumber) {
      // they just sent a new commit_sig, we have received it but they didn't receive our revocation
      true
    } else if (d.commitments.localCommit.index == nextRemoteRevocationNumber + 1) {
      // we are in sync
      true
    } else if (d.commitments.localCommit.index > nextRemoteRevocationNumber + 1) {
      // remote is behind: we return true because things are fine on our side
      true
    } else {
      // we are behind
      false
    }
  }

  /**
   * Tells whether or not their expected next local commitment number matches with our data
   *
   * @return
   *         - true if parties are in sync or remote is behind
   *         - false if we are behind
   */
  def checkRemoteCommit(d: HasCommitments, nextLocalCommitmentNumber: Long): Boolean = {
    d.commitments.remoteNextCommitInfo match {
      case Left(waitingForRevocation) if nextLocalCommitmentNumber == waitingForRevocation.nextRemoteCommit.index =>
        // we just sent a new commit_sig but they didn't receive it
        true
      case Left(waitingForRevocation) if nextLocalCommitmentNumber == (waitingForRevocation.nextRemoteCommit.index + 1) =>
        // we just sent a new commit_sig, they have received it but we haven't received their revocation
        true
      case Left(waitingForRevocation) if nextLocalCommitmentNumber < waitingForRevocation.nextRemoteCommit.index =>
        // they are behind
        true
      case Right(_) if nextLocalCommitmentNumber == (d.commitments.remoteCommit.index + 1) =>
        // they have acknowledged the last commit_sig we sent
        true
      case Right(_) if nextLocalCommitmentNumber < (d.commitments.remoteCommit.index + 1) =>
        // they are behind
        true
      case _ =>
        // we are behind
        false
    }
  }


  object Closing {

    // @formatter:off
    sealed trait ClosingType
    case class MutualClose(tx: Transaction) extends ClosingType
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
     * the whole closing process finishes(e.g. there may still be delayed or unconfirmed child transactions). It can
     * save us from attempting to publish some transactions.
     *
     * Note that we can't tell for mutual close before it is already final, because only one tx needs to be confirmed.
     *
     * @param closing channel state data
     * @return the channel closing type, if applicable
     */
    def isClosingTypeAlreadyKnown(closing: DATA_CLOSING): Option[ClosingType] = {
      // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
      // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
      // the type of closing.
      def isLocalCommitConfirmed(lcp: LocalCommitPublished): Boolean = {
        val confirmedTxs = lcp.irrevocablySpent.values.toSet
        (lcp.commitTx :: lcp.claimMainDelayedOutputTx.toList ::: lcp.htlcSuccessTxs ::: lcp.htlcTimeoutTxs ::: lcp.claimHtlcDelayedTxs).exists(tx => confirmedTxs.contains(tx.txid))
      }

      def isRemoteCommitConfirmed(rcp: RemoteCommitPublished): Boolean = {
        val confirmedTxs = rcp.irrevocablySpent.values.toSet
        (rcp.commitTx :: rcp.claimMainOutputTx.toList ::: rcp.claimHtlcSuccessTxs ::: rcp.claimHtlcTimeoutTxs).exists(tx => confirmedTxs.contains(tx.txid))
      }

      closing match {
        case _ if closing.localCommitPublished.exists(isLocalCommitConfirmed) =>
          Some(LocalClose(closing.commitments.localCommit, closing.localCommitPublished.get))
        case _ if closing.remoteCommitPublished.exists(isRemoteCommitConfirmed) =>
          Some(CurrentRemoteClose(closing.commitments.remoteCommit, closing.remoteCommitPublished.get))
        case _ if closing.nextRemoteCommitPublished.exists(isRemoteCommitConfirmed) =>
          Some(NextRemoteClose(closing.commitments.remoteNextCommitInfo.left.get.nextRemoteCommit, closing.nextRemoteCommitPublished.get))
        case _ if closing.futureRemoteCommitPublished.exists(isRemoteCommitConfirmed) =>
          Some(RecoveryClose(closing.futureRemoteCommitPublished.get))
        case _ if closing.revokedCommitPublished.exists(rcp => rcp.irrevocablySpent.values.toSet.contains(rcp.commitTx.txid)) =>
          Some(RevokedClose(closing.revokedCommitPublished.find(rcp => rcp.irrevocablySpent.values.toSet.contains(rcp.commitTx.txid)).get))
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
      case closing: DATA_CLOSING if additionalConfirmedTx_opt.exists(closing.mutualClosePublished.contains) =>
        Some(MutualClose(additionalConfirmedTx_opt.get))
      case closing: DATA_CLOSING if closing.localCommitPublished.exists(Closing.isLocalCommitDone) =>
        Some(LocalClose(closing.commitments.localCommit, closing.localCommitPublished.get))
      case closing: DATA_CLOSING if closing.remoteCommitPublished.exists(Closing.isRemoteCommitDone) =>
        Some(CurrentRemoteClose(closing.commitments.remoteCommit, closing.remoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.nextRemoteCommitPublished.exists(Closing.isRemoteCommitDone) =>
        Some(NextRemoteClose(closing.commitments.remoteNextCommitInfo.left.get.nextRemoteCommit, closing.nextRemoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.futureRemoteCommitPublished.exists(Closing.isRemoteCommitDone) =>
        Some(RecoveryClose(closing.futureRemoteCommitPublished.get))
      case closing: DATA_CLOSING if closing.revokedCommitPublished.exists(Closing.isRevokedCommitDone) =>
        Some(RevokedClose(closing.revokedCommitPublished.find(Closing.isRevokedCommitDone).get))
      case _ => None
    }

    // used only to compute tx weights and estimate fees
    lazy val dummyPublicKey = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey

    def isValidFinalScriptPubkey(scriptPubKey: ByteVector): Boolean = {
      Try(Script.parse(scriptPubKey)) match {
        case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => true
        case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => true
        case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => true
        case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => true
        case _ => false
      }
    }

    def firstClosingFee(commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feeratePerKw: Long)(implicit log: LoggingAdapter): Satoshi = {
      import commitments._
      // this is just to estimate the weight, it depends on size of the pubkey scripts
      val dummyClosingTx = Transactions.makeClosingTx(commitInput, localScriptPubkey, remoteScriptPubkey, localParams.isFunder, Satoshi(0), Satoshi(0), localCommit.spec)
      val closingWeight = Transaction.weight(Transactions.addSigs(dummyClosingTx, dummyPublicKey, remoteParams.fundingPubKey, Transactions.PlaceHolderSig, Transactions.PlaceHolderSig).tx)
      log.info(s"using feeratePerKw=$feeratePerKw for initial closing tx")
      Transactions.weight2fee(feeratePerKw, closingWeight)
    }

    def firstClosingFee(commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): Satoshi = {
      val requestedFeerate = feeEstimator.getFeeratePerKw(feeTargets.mutualCloseBlockTarget)
      // we "MUST set fee_satoshis less than or equal to the base fee of the final commitment transaction"
      val feeratePerKw = Math.min(requestedFeerate, commitments.localCommit.spec.feeratePerKw)
      firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey, feeratePerKw)
    }

    def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = ((localClosingFee + remoteClosingFee) / 4) * 2

    def makeFirstClosingTx(keyManager: KeyManager, commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
      val closingFee = firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey, feeEstimator, feeTargets)
      makeClosingTx(keyManager, commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
    }

    def makeClosingTx(keyManager: KeyManager, commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, closingFee: Satoshi)(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
      import commitments._
      require(isValidFinalScriptPubkey(localScriptPubkey), "invalid localScriptPubkey")
      require(isValidFinalScriptPubkey(remoteScriptPubkey), "invalid remoteScriptPubkey")
      log.debug("making closing tx with closingFee={} and commitments:\n{}", closingFee, Commitments.specs2String(commitments))
      val dustLimitSatoshis = localParams.dustLimit.max(remoteParams.dustLimit)
      val closingTx = Transactions.makeClosingTx(commitInput, localScriptPubkey, remoteScriptPubkey, localParams.isFunder, dustLimitSatoshis, closingFee, localCommit.spec)
      val localClosingSig = keyManager.sign(closingTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath))
      val closingSigned = ClosingSigned(channelId, closingFee, localClosingSig)
      log.info(s"signed closing txid=${closingTx.tx.txid} with closingFeeSatoshis=${closingSigned.feeSatoshis}")
      log.debug(s"closingTxid=${closingTx.tx.txid} closingTx=${closingTx.tx}}")
      (closingTx, closingSigned)
    }

    def checkClosingSignature(keyManager: KeyManager, commitments: Commitments, localScriptPubkey: ByteVector, remoteScriptPubkey: ByteVector, remoteClosingFee: Satoshi, remoteClosingSig: ByteVector64)(implicit log: LoggingAdapter): Try[Transaction] = {
      import commitments._
      val lastCommitFeeSatoshi = commitments.commitInput.txOut.amount - commitments.localCommit.publishableTxs.commitTx.tx.txOut.map(_.amount).sum
      if (remoteClosingFee > lastCommitFeeSatoshi) {
        log.error(s"remote proposed a commit fee higher than the last commitment fee: remoteClosingFeeSatoshi=${remoteClosingFee.toLong} lastCommitFeeSatoshi=$lastCommitFeeSatoshi")
        Failure(InvalidCloseFee(commitments.channelId, remoteClosingFee))
      } else {
        val (closingTx, closingSigned) = makeClosingTx(keyManager, commitments, localScriptPubkey, remoteScriptPubkey, remoteClosingFee)
        val signedClosingTx = Transactions.addSigs(closingTx, keyManager.fundingPublicKey(commitments.localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey, closingSigned.signature, remoteClosingSig)
        Transactions.checkSpendable(signedClosingTx) match {
          case Success(_) => Success(signedClosingTx.tx)
          case _ => Failure(InvalidCloseSignature(commitments.channelId, signedClosingTx.tx))
        }
      }
    }

    /** Wraps transaction generation in a Try and filters failures to avoid one transaction negatively impacting a whole commitment. */
    private def generateTx(desc: String)(attempt: => Either[TxGenerationSkipped, TransactionWithInputInfo])(implicit log: LoggingAdapter): Option[TransactionWithInputInfo] = {
      Try {
        attempt
      } match {
        case Success(Right(txinfo)) =>
          log.info(s"tx generation success: desc=$desc txid=${txinfo.tx.txid} amount=${txinfo.tx.txOut.map(_.amount).sum} tx=${txinfo.tx}")
          Some(txinfo)
        case Success(Left(skipped)) =>
          log.info(s"tx generation skipped: desc=$desc reason: ${skipped.toString}")
          None
        case Failure(t) =>
          log.warning(s"tx generation failure: desc=$desc reason: ${t.getMessage}")
          None
      }
    }

    /**
     * Claim all the HTLCs that we've received from our current commit tx. This will be
     * done using 2nd stage HTLC transactions
     *
     * @param commitments our commitment data, which include payment preimages
     * @return a list of transactions (one per HTLC that we can claim)
     */
    def claimCurrentLocalCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): LocalCommitPublished = {
      import commitments._
      require(localCommit.publishableTxs.commitTx.tx.txid == tx.txid, "txid mismatch, provided tx is not the current local commit tx")
      val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
      val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitments.localCommit.index.toInt)
      val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
      val feeratePerKwDelayed = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)

      // first we will claim our main output as soon as the delay is over
      val mainDelayedTx = generateTx("main-delayed-output") {
        Transactions.makeClaimDelayedOutputTx(tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwDelayed).right.map(claimDelayed => {
          val sig = keyManager.sign(claimDelayed, keyManager.delayedPaymentPoint(channelKeyPath), localPerCommitmentPoint)
          Transactions.addSigs(claimDelayed, sig)
        })
      }

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      val htlcTxes = localCommit.publishableTxs.htlcTxsAndSigs.collect {
        // incoming htlc for which we have the preimage: we spend it directly
        case HtlcTxAndSigs(txinfo@HtlcSuccessTx(_, _, paymentHash), localSig, remoteSig) if preimages.exists(r => sha256(r) == paymentHash) =>
          generateTx("htlc-success") {
            val preimage = preimages.find(r => sha256(r) == paymentHash).get
            Right(Transactions.addSigs(txinfo, localSig, remoteSig, preimage))
          }

        // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)

        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case HtlcTxAndSigs(txinfo: HtlcTimeoutTx, localSig, remoteSig) =>
          generateTx("htlc-timeout") {
            Right(Transactions.addSigs(txinfo, localSig, remoteSig))
          }
      }.flatten

      // all htlc output to us are delayed, so we need to claim them as soon as the delay is over
      val htlcDelayedTxes = htlcTxes.flatMap {
        txinfo: TransactionWithInputInfo =>
          generateTx("claim-htlc-delayed") {
            Transactions.makeClaimDelayedOutputTx(txinfo.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwDelayed).right.map(claimDelayed => {
              val sig = keyManager.sign(claimDelayed, keyManager.delayedPaymentPoint(channelKeyPath), localPerCommitmentPoint)
              Transactions.addSigs(claimDelayed, sig)
            })
          }
      }

      LocalCommitPublished(
        commitTx = tx,
        claimMainDelayedOutputTx = mainDelayedTx.map(_.tx),
        htlcSuccessTxs = htlcTxes.collect { case c: HtlcSuccessTx => c.tx },
        htlcTimeoutTxs = htlcTxes.collect { case c: HtlcTimeoutTx => c.tx },
        claimHtlcDelayedTxs = htlcDelayedTxes.map(_.tx),
        irrevocablySpent = Map.empty)
    }

    /**
     * Claim all the HTLCs that we've received from their current commit tx
     *
     * @param commitments  our commitment data, which include payment preimages
     * @param remoteCommit the remote commitment data to use to claim outputs (it can be their current or next commitment)
     * @param tx           the remote commitment transaction that has just been published
     * @return a list of transactions (one per HTLC that we can claim)
     */
    def claimRemoteCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, remoteCommit: RemoteCommit, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): RemoteCommitPublished = {
      import commitments.{channelVersion, commitInput, localParams, remoteParams}
      require(remoteCommit.txid == tx.txid, "txid mismatch, provided tx is not the current remote commit tx")
      val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(keyManager, channelVersion, remoteCommit.index, localParams, remoteParams, commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
      require(remoteCommitTx.tx.txid == tx.txid, "txid mismatch, cannot recompute the current remote commit tx")
      val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
      val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
      val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remoteCommit.remotePerCommitmentPoint)
      val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
      val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remoteCommit.remotePerCommitmentPoint)
      val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remoteCommit.remotePerCommitmentPoint)
      val outputs = makeCommitTxOutputs(!localParams.isFunder, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteCommit.spec)

      // we need to use a rather high fee for htlc-claim because we compete with the counterparty
      val feeratePerKwHtlc = feeEstimator.getFeeratePerKw(target = 2)

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa
      val txes = remoteCommit.spec.htlcs.collect {
        // incoming htlc for which we have the preimage: we spend it directly.
        // NB: we are looking at the remote's commitment, from its point of view it's an outgoing htlc.
        case OutgoingHtlc(add: UpdateAddHtlc) if preimages.exists(r => sha256(r) == add.paymentHash) => generateTx("claim-htlc-success") {
          val preimage = preimages.find(r => sha256(r) == add.paymentHash).get
          Transactions.makeClaimHtlcSuccessTx(remoteCommitTx.tx, outputs, localParams.dustLimit, localHtlcPubkey, remoteHtlcPubkey, remoteRevocationPubkey, localParams.defaultFinalScriptPubKey, add, feeratePerKwHtlc).right.map(txinfo => {
            val sig = keyManager.sign(txinfo, keyManager.htlcPoint(channelKeyPath), remoteCommit.remotePerCommitmentPoint)
            Transactions.addSigs(txinfo, sig, preimage)
          })
        }

        // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)

        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case IncomingHtlc(add: UpdateAddHtlc) => generateTx("claim-htlc-timeout") {
          Transactions.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, outputs, localParams.dustLimit, localHtlcPubkey, remoteHtlcPubkey, remoteRevocationPubkey, localParams.defaultFinalScriptPubKey, add, feeratePerKwHtlc).right.map(txinfo => {
            val sig = keyManager.sign(txinfo, keyManager.htlcPoint(channelKeyPath), remoteCommit.remotePerCommitmentPoint)
            Transactions.addSigs(txinfo, sig)
          })
        }
      }.toSeq.flatten

      claimRemoteCommitMainOutput(keyManager, commitments, remoteCommit.remotePerCommitmentPoint, tx, feeEstimator, feeTargets).copy(
        claimHtlcSuccessTxs = txes.toList.collect { case c: ClaimHtlcSuccessTx => c.tx },
        claimHtlcTimeoutTxs = txes.toList.collect { case c: ClaimHtlcTimeoutTx => c.tx }
      )
    }

    /**
     * Claim our Main output only
     *
     * @param commitments              either our current commitment data in case of usual remote uncooperative closing
     *                                 or our outdated commitment data in case of data loss protection procedure; in any case it is used only
     *                                 to get some constant parameters, not commitment data
     * @param remotePerCommitmentPoint the remote perCommitmentPoint corresponding to this commitment
     * @param tx                       the remote commitment transaction that has just been published
     * @return a list of transactions (one per HTLC that we can claim)
     */
    def claimRemoteCommitMainOutput(keyManager: KeyManager, commitments: Commitments, remotePerCommitmentPoint: PublicKey, tx: Transaction, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): RemoteCommitPublished = {
      val channelKeyPath = keyManager.channelKeyPath(commitments.localParams, commitments.channelVersion)
      val localPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

      val feeratePerKwMain = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)

      val mainTx = generateTx("claim-p2wpkh-output") {
        Transactions.makeClaimP2WPKHOutputTx(tx, commitments.localParams.dustLimit, localPubkey, commitments.localParams.defaultFinalScriptPubKey, feeratePerKwMain).right.map(claimMain => {
          val sig = keyManager.sign(claimMain, keyManager.paymentPoint(channelKeyPath), remotePerCommitmentPoint)
          Transactions.addSigs(claimMain, localPubkey, sig)
        })
      }

      RemoteCommitPublished(
        commitTx = tx,
        claimMainOutputTx = mainTx.map(_.tx),
        claimHtlcSuccessTxs = Nil,
        claimHtlcTimeoutTxs = Nil,
        irrevocablySpent = Map.empty
      )
    }

    /**
     * When an unexpected transaction spending the funding tx is detected:
     * 1) we find out if the published transaction is one of remote's revoked txs
     * 2) and then:
     * a) if it is a revoked tx we build a set of transactions that will punish them by stealing all their funds
     * b) otherwise there is nothing we can do
     *
     * @return a [[RevokedCommitPublished]] object containing penalty transactions if the tx is a revoked commitment
     */
    def claimRevokedRemoteCommitTxOutputs(keyManager: KeyManager, commitments: Commitments, tx: Transaction, db: ChannelsDb, feeEstimator: FeeEstimator, feeTargets: FeeTargets)(implicit log: LoggingAdapter): Option[RevokedCommitPublished] = {
      import commitments._
      require(tx.txIn.size == 1, "commitment tx should have 1 input")
      val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
      val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn.head.sequence, tx.lockTime)
      // this tx has been published by remote, so we need to invert local/remote params
      val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, keyManager.paymentPoint(channelKeyPath).publicKey)
      require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
      log.warning(s"a revoked commit has been published with txnumber=$txnumber")
      // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
      remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber)
        .map(d => PrivateKey(d))
        .map { remotePerCommitmentSecret =>
          val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey
          val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
          val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
          val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
          val localPaymentPubkey = Generators.derivePubKey(keyManager.paymentPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
          val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

          val feeratePerKwMain = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
          // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
          val feeratePerKwPenalty = feeEstimator.getFeeratePerKw(target = 2)

          // first we will claim our main output right away
          val mainTx = generateTx("claim-p2wpkh-output") {
            Transactions.makeClaimP2WPKHOutputTx(tx, localParams.dustLimit, localPaymentPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwMain).right.map(claimMain => {
              val sig = keyManager.sign(claimMain, keyManager.paymentPoint(channelKeyPath), remotePerCommitmentPoint)
              Transactions.addSigs(claimMain, localPaymentPubkey, sig)
            })
          }

          // then we punish them by stealing their main output
          val mainPenaltyTx = generateTx("main-penalty") {
            Transactions.makeMainPenaltyTx(tx, localParams.dustLimit, remoteRevocationPubkey, localParams.defaultFinalScriptPubKey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, feeratePerKwPenalty).right.map(txinfo => {
              val sig = keyManager.sign(txinfo, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret)
              Transactions.addSigs(txinfo, sig)
            })
          }

          // we retrieve the informations needed to rebuild htlc scripts
          val htlcInfos = db.listHtlcInfos(commitments.channelId, txnumber)
          log.info(s"got htlcs=${htlcInfos.size} for txnumber=$txnumber")
          val htlcsRedeemScripts = (
            htlcInfos.map { case (paymentHash, cltvExpiry) => Scripts.htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(paymentHash), cltvExpiry) } ++
              htlcInfos.map { case (paymentHash, _) => Scripts.htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(paymentHash)) }
            )
            .map(redeemScript => Script.write(pay2wsh(redeemScript)) -> Script.write(redeemScript))
            .toMap

          // and finally we steal the htlc outputs
          val htlcPenaltyTxs = tx.txOut.zipWithIndex.collect { case (txOut, outputIndex) if htlcsRedeemScripts.contains(txOut.publicKeyScript) =>
            val htlcRedeemScript = htlcsRedeemScripts(txOut.publicKeyScript)
            generateTx("htlc-penalty") {
              Transactions.makeHtlcPenaltyTx(tx, outputIndex, htlcRedeemScript, localParams.dustLimit, localParams.defaultFinalScriptPubKey, feeratePerKwPenalty).right.map(htlcPenalty => {
                val sig = keyManager.sign(htlcPenalty, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret)
                Transactions.addSigs(htlcPenalty, sig, remoteRevocationPubkey)
              })
            }
          }.toList.flatten

          RevokedCommitPublished(
            commitTx = tx,
            claimMainOutputTx = mainTx.map(_.tx),
            mainPenaltyTx = mainPenaltyTx.map(_.tx),
            htlcPenaltyTxs = htlcPenaltyTxs.map(_.tx),
            claimHtlcDelayedPenaltyTxs = Nil, // we will generate and spend those if they publish their HtlcSuccessTx or HtlcTimeoutTx
            irrevocablySpent = Map.empty
          )
        }
    }

    /**
     * Claims the output of an [[HtlcSuccessTx]] or [[HtlcTimeoutTx]] transaction using a revocation key.
     *
     * In case a revoked commitment with pending HTLCs is published, there are two ways the HTLC outputs can be taken as punishment:
     * - by spending the corresponding output of the commitment tx, using [[HtlcPenaltyTx]] that we generate as soon as we detect that a revoked commit
     * as been spent; note that those transactions will compete with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] published by the counterparty.
     * - by spending the delayed output of [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] if those get confirmed; because the output of these txes is protected by
     * an OP_CSV delay, we will have time to spend them with a revocation key. In that case, we generate the spending transactions "on demand",
     * this is the purpose of this method.
     */
    def claimRevokedHtlcTxOutputs(keyManager: KeyManager, commitments: Commitments, revokedCommitPublished: RevokedCommitPublished, htlcTx: Transaction, feeEstimator: FeeEstimator)(implicit log: LoggingAdapter): (RevokedCommitPublished, Option[Transaction]) = {
      if (htlcTx.txIn.map(_.outPoint.txid).contains(revokedCommitPublished.commitTx.txid) &&
        !(revokedCommitPublished.claimMainOutputTx ++ revokedCommitPublished.mainPenaltyTx ++ revokedCommitPublished.htlcPenaltyTxs).map(_.txid).toSet.contains(htlcTx.txid)) {
        log.info(s"looks like txid=${htlcTx.txid} could be a 2nd level htlc tx spending revoked commit txid=${revokedCommitPublished.commitTx.txid}")
        // Let's assume that htlcTx is an HtlcSuccessTx or HtlcTimeoutTx and try to generate a tx spending its output using a revocation key
        import commitments._
        val tx = revokedCommitPublished.commitTx
        val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn.head.sequence, tx.lockTime)
        val channelKeyPath = keyManager.channelKeyPath(localParams, channelVersion)
        // this tx has been published by remote, so we need to invert local/remote params
        val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, keyManager.paymentPoint(channelKeyPath).publicKey)
        // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
        remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber)
          .map(d => PrivateKey(d))
          .flatMap { remotePerCommitmentSecret =>
            val remotePerCommitmentPoint = remotePerCommitmentSecret.publicKey
            val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
            val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)

            // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
            val feeratePerKwPenalty = feeEstimator.getFeeratePerKw(target = 1)

            generateTx("claim-htlc-delayed-penalty") {
              Transactions.makeClaimDelayedOutputPenaltyTx(htlcTx, localParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localParams.defaultFinalScriptPubKey, feeratePerKwPenalty).right.map(htlcDelayedPenalty => {
                val sig = keyManager.sign(htlcDelayedPenalty, keyManager.revocationPoint(channelKeyPath), remotePerCommitmentSecret)
                val signedTx = Transactions.addSigs(htlcDelayedPenalty, sig)
                // we need to make sure that the tx is indeed valid
                Transaction.correctlySpends(signedTx.tx, Seq(htlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                signedTx
              })
            }
          } match {
          case Some(tx) =>
            val revokedCommitPublished1 = revokedCommitPublished.copy(claimHtlcDelayedPenaltyTxs = revokedCommitPublished.claimHtlcDelayedPenaltyTxs :+ tx.tx)
            (revokedCommitPublished1, Some(tx.tx))
          case None =>
            (revokedCommitPublished, None)
        }
      } else {
        (revokedCommitPublished, None)
      }
    }

    /**
     * In CLOSING state, any time we see a new transaction, we try to extract a preimage from it in order to fulfill the
     * corresponding incoming htlc in an upstream channel.
     *
     * Not doing that would result in us losing money, because the downstream node would pull money from one side, and
     * the upstream node would get refunded after a timeout.
     *
     * @return   a set of pairs (add, preimage) if extraction was successful:
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

    /**
     * We may have multiple HTLCs with the same payment hash because of MPP.
     * When a timeout transaction is confirmed, we need to find the best matching HTLC to fail upstream.
     * We need to handle potentially duplicate HTLCs (same amount and expiry): this function will use a deterministic
     * ordering of transactions and HTLCs to handle this.
     */
    private def findTimedOutHtlc(tx: Transaction, paymentHash160: ByteVector, htlcs: Seq[UpdateAddHtlc], timeoutTxs: Seq[Transaction], extractPaymentHash: PartialFunction[ScriptWitness, ByteVector])(implicit log: LoggingAdapter): Option[UpdateAddHtlc] = {
      // We use a deterministic ordering to match HTLCs to their corresponding HTLC-timeout tx.
      // We don't match on the expected amounts because this is error-prone: computing the correct weight of a claim-htlc-timeout
      // is hard because signatures can be either 71, 72 or 73 bytes long (ECDSA DER encoding).
      // We could instead look at the spent outpoint, but that requires more lookups and access to the published commitment transaction.
      // It's simpler to just use the amount as the first ordering key: since the feerate is the same for all timeout
      // transactions we will find the right HTLC to fail upstream.
      val matchingHtlcs = htlcs
        .filter(add => add.cltvExpiry.toLong == tx.lockTime && ripemd160(add.paymentHash) == paymentHash160)
        .sortBy(add => (add.amountMsat.toLong, add.id))
      val matchingTxs = timeoutTxs
        .filter(timeoutTx => timeoutTx.lockTime == tx.lockTime && timeoutTx.txIn.map(_.witness).collect(extractPaymentHash).contains(paymentHash160))
        .sortBy(timeoutTx => (timeoutTx.txOut.map(_.amount.toLong).sum, timeoutTx.txid.toHex))
      if (matchingTxs.size != matchingHtlcs.size) {
        log.error(s"some htlcs don't have a corresponding timeout transaction: tx=$tx, htlcs=$matchingHtlcs, timeout-txs=$matchingTxs")
      }
      matchingHtlcs.zip(matchingTxs).collectFirst {
        case (add, timeoutTx) if timeoutTx.txid == tx.txid => add
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
     * more htlcs have timed out and need to be failed in an upstream channel.
     *
     * @param tx a tx that has reached mindepth
     * @return a set of htlcs that need to be failed upstream
     */
    def timedoutHtlcs(localCommit: LocalCommit, localCommitPublished: LocalCommitPublished, localDustLimit: Satoshi, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val untrimmedHtlcs = Transactions.trimOfferedHtlcs(localDustLimit, localCommit.spec).map(_.add)
      if (tx.txid == localCommit.publishableTxs.commitTx.tx.txid) {
        // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
        localCommit.spec.htlcs.collect(outgoing) -- untrimmedHtlcs
      } else {
        // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
        tx.txIn
          .map(_.witness)
          .collect(Scripts.extractPaymentHashFromHtlcTimeout)
          .flatMap { paymentHash160 =>
            log.info(s"extracted paymentHash160=$paymentHash160 and expiry=${tx.lockTime} from tx=$tx (htlc-timeout)")
            findTimedOutHtlc(tx,
              paymentHash160,
              untrimmedHtlcs,
              localCommitPublished.htlcTimeoutTxs,
              Scripts.extractPaymentHashFromHtlcTimeout)
          }
          .toSet
      }
    }

    /**
     * In CLOSING state, when we are notified that a transaction has been confirmed, we analyze it to find out if one or
     * more htlcs have timed out and need to be failed in an upstream channel.
     *
     * @param tx a tx that has reached mindepth
     * @return a set of htlcs that need to be failed upstream
     */
    def timedoutHtlcs(remoteCommit: RemoteCommit, remoteCommitPublished: RemoteCommitPublished, remoteDustLimit: Satoshi, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val untrimmedHtlcs = Transactions.trimReceivedHtlcs(remoteDustLimit, remoteCommit.spec).map(_.add)
      if (tx.txid == remoteCommit.txid) {
        // the tx is a commitment tx, we can immediately fail all dust htlcs (they don't have an output in the tx)
        remoteCommit.spec.htlcs.collect(incoming) -- untrimmedHtlcs
      } else {
        // maybe this is a timeout tx, in that case we can resolve and fail the corresponding htlc
        tx.txIn
          .map(_.witness)
          .collect(Scripts.extractPaymentHashFromClaimHtlcTimeout)
          .flatMap { paymentHash160 =>
            log.info(s"extracted paymentHash160=$paymentHash160 and expiry=${tx.lockTime} from tx=$tx (claim-htlc-timeout)")
            findTimedOutHtlc(tx,
              paymentHash160,
              untrimmedHtlcs,
              remoteCommitPublished.claimHtlcTimeoutTxs,
              Scripts.extractPaymentHashFromClaimHtlcTimeout)
          }
          .toSet
      }
    }

    /**
     * As soon as a local or remote commitment reaches min_depth, we know which htlcs will be settled on-chain (whether
     * or not they actually have an output in the commitment tx).
     *
     * @param tx a transaction that is sufficiently buried in the blockchain
     */
    def onchainOutgoingHtlcs(localCommit: LocalCommit, remoteCommit: RemoteCommit, nextRemoteCommit_opt: Option[RemoteCommit], tx: Transaction): Set[UpdateAddHtlc] = {
      if (localCommit.publishableTxs.commitTx.tx.txid == tx.txid) {
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
     * If a local commitment tx reaches min_depth, we need to fail the outgoing htlcs that only us had signed, because
     * they will never reach the blockchain.
     *
     * Those are only present in the remote's commitment.
     */
    def overriddenOutgoingHtlcs(d: DATA_CLOSING, tx: Transaction)(implicit log: LoggingAdapter): Set[UpdateAddHtlc] = {
      val localCommit = d.commitments.localCommit
      val remoteCommit = d.commitments.remoteCommit
      val nextRemoteCommit_opt = d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit)
      if (localCommit.publishableTxs.commitTx.tx.txid == tx.txid) {
        // our commit got confirmed, so any htlc that we signed but they didn't sign will never reach the chain
        val mostRecentRemoteCommit = nextRemoteCommit_opt.getOrElse(remoteCommit)
        // NB: from the p.o.v of remote, their incoming htlcs are our outgoing htlcs
        mostRecentRemoteCommit.spec.htlcs.collect(incoming) -- localCommit.spec.htlcs.collect(outgoing)
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
      // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter { outPoint =>
        // is this the commit tx itself ? (we could do this outside of the loop...)
        val isCommitTx = localCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = localCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = localCommitPublished.claimHtlcDelayedTxs.map(_.txid).contains(tx.txid)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      }
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      localCommitPublished.copy(irrevocablySpent = localCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx.txid).toMap)
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
      // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter { outPoint =>
        // is this the commit tx itself ? (we could do this outside of the loop...)
        val isCommitTx = remoteCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = remoteCommitPublished.commitTx.txid == outPoint.txid
        isCommitTx || spendsTheCommitTx
      }
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      remoteCommitPublished.copy(irrevocablySpent = remoteCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx.txid).toMap)
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
      // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter { outPoint =>
        // is this the commit tx itself ? (we could do this outside of the loop...)
        val isCommitTx = revokedCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = revokedCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = revokedCommitPublished.claimHtlcDelayedPenaltyTxs.map(_.txid).contains(tx.txid)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      }
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      revokedCommitPublished.copy(irrevocablySpent = revokedCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => o -> tx.txid).toMap)
    }

    /**
     * A local commit is considered done when:
     * - all commitment tx outputs that we can spend have been spent and confirmed (even if the spending tx was not ours)
     * - all 3rd stage txes (txes spending htlc txes) have been confirmed
     */
    def isLocalCommitDone(localCommitPublished: LocalCommitPublished): Boolean = {
      // is the commitment tx buried? (we need to check this because we may not have any outputs)
      val isCommitTxConfirmed = localCommitPublished.irrevocablySpent.values.toSet.contains(localCommitPublished.commitTx.txid)
      // are there remaining spendable outputs from the commitment tx? we just subtract all known spent outputs from the ones we control
      val commitOutputsSpendableByUs = (localCommitPublished.claimMainDelayedOutputTx.toSeq ++ localCommitPublished.htlcSuccessTxs ++ localCommitPublished.htlcTimeoutTxs)
        .flatMap(_.txIn.map(_.outPoint)).toSet -- localCommitPublished.irrevocablySpent.keys
      // which htlc delayed txes can we expect to be confirmed?
      val unconfirmedHtlcDelayedTxes = localCommitPublished.claimHtlcDelayedTxs
        .filter(tx => (tx.txIn.map(_.outPoint.txid).toSet -- localCommitPublished.irrevocablySpent.values).isEmpty) // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
        .filterNot(tx => localCommitPublished.irrevocablySpent.values.toSet.contains(tx.txid)) // has the tx already been confirmed?
      isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty && unconfirmedHtlcDelayedTxes.isEmpty
    }

    /**
     * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
     * (even if the spending tx was not ours).
     */
    def isRemoteCommitDone(remoteCommitPublished: RemoteCommitPublished): Boolean = {
      // is the commitment tx buried? (we need to check this because we may not have any outputs)
      val isCommitTxConfirmed = remoteCommitPublished.irrevocablySpent.values.toSet.contains(remoteCommitPublished.commitTx.txid)
      // are there remaining spendable outputs from the commitment tx?
      val commitOutputsSpendableByUs = (remoteCommitPublished.claimMainOutputTx.toSeq ++ remoteCommitPublished.claimHtlcSuccessTxs ++ remoteCommitPublished.claimHtlcTimeoutTxs)
        .flatMap(_.txIn.map(_.outPoint)).toSet -- remoteCommitPublished.irrevocablySpent.keys
      isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty
    }

    /**
     * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
     * (even if the spending tx was not ours).
     */
    def isRevokedCommitDone(revokedCommitPublished: RevokedCommitPublished): Boolean = {
      // is the commitment tx buried? (we need to check this because we may not have any outputs)
      val isCommitTxConfirmed = revokedCommitPublished.irrevocablySpent.values.toSet.contains(revokedCommitPublished.commitTx.txid)
      // are there remaining spendable outputs from the commitment tx?
      val commitOutputsSpendableByUs = (revokedCommitPublished.claimMainOutputTx.toSeq ++ revokedCommitPublished.mainPenaltyTx ++ revokedCommitPublished.htlcPenaltyTxs)
        .flatMap(_.txIn.map(_.outPoint)).toSet -- revokedCommitPublished.irrevocablySpent.keys
      // which htlc delayed txes can we expect to be confirmed?
      val unconfirmedHtlcDelayedTxes = revokedCommitPublished.claimHtlcDelayedPenaltyTxs
        .filter(tx => (tx.txIn.map(_.outPoint.txid).toSet -- revokedCommitPublished.irrevocablySpent.values).isEmpty) // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
        .filterNot(tx => revokedCommitPublished.irrevocablySpent.values.toSet.contains(tx.txid)) // has the tx already been confirmed?
      isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty && unconfirmedHtlcDelayedTxes.isEmpty
    }

    /**
     * This helper function tells if the utxo consumed by the given transaction has already been irrevocably spent (possibly by this very transaction)
     *
     * It can be useful to:
     *   - not attempt to publish this tx when we know this will fail
     *   - not watch for confirmations if we know the tx is already confirmed
     *   - not watch the corresponding utxo when we already know the final spending tx
     *
     * @param tx               a tx with only one input
     * @param irrevocablySpent a map of known spent outpoints
     * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
     */
    def inputsAlreadySpent(tx: Transaction, irrevocablySpent: Map[OutPoint, ByteVector32]): Boolean = {
      require(tx.txIn.size == 1, "only tx with one input is supported")
      val outPoint = tx.txIn.head.outPoint
      irrevocablySpent.contains(outPoint)
    }

    /**
     * This helper function returns the fee paid by the given transaction.
     *
     * It relies on the current channel data to find the parent tx and compute the fee, and also provides a description.
     *
     * @param tx a tx for which we want to compute the fee
     * @param d  current channel data
     * @return if the parent tx is found, a tuple (fee, description)
     */
    def networkFeePaid(tx: Transaction, d: DATA_CLOSING): Option[(Satoshi, String)] = {
      // only funder pays the fee
      if (d.commitments.localParams.isFunder) {
        // we build a map with all known txes (that's not particularly efficient, but it doesn't really matter)
        val txes: Map[ByteVector32, (Transaction, String)] = (
          d.mutualClosePublished.map(_ -> "mutual") ++
            d.localCommitPublished.map(_.commitTx).map(_ -> "local-commit").toSeq ++
            d.localCommitPublished.flatMap(_.claimMainDelayedOutputTx).map(_ -> "local-main-delayed") ++
            d.localCommitPublished.toSeq.flatMap(_.htlcSuccessTxs).map(_ -> "local-htlc-success") ++
            d.localCommitPublished.toSeq.flatMap(_.htlcTimeoutTxs).map(_ -> "local-htlc-timeout") ++
            d.localCommitPublished.toSeq.flatMap(_.claimHtlcDelayedTxs).map(_ -> "local-htlc-delayed") ++
            d.remoteCommitPublished.map(_.commitTx).map(_ -> "remote-commit") ++
            d.remoteCommitPublished.toSeq.flatMap(_.claimMainOutputTx).map(_ -> "remote-main") ++
            d.remoteCommitPublished.toSeq.flatMap(_.claimHtlcSuccessTxs).map(_ -> "remote-htlc-success") ++
            d.remoteCommitPublished.toSeq.flatMap(_.claimHtlcTimeoutTxs).map(_ -> "remote-htlc-timeout") ++
            d.nextRemoteCommitPublished.map(_.commitTx).map(_ -> "remote-commit") ++
            d.nextRemoteCommitPublished.toSeq.flatMap(_.claimMainOutputTx).map(_ -> "remote-main") ++
            d.nextRemoteCommitPublished.toSeq.flatMap(_.claimHtlcSuccessTxs).map(_ -> "remote-htlc-success") ++
            d.nextRemoteCommitPublished.toSeq.flatMap(_.claimHtlcTimeoutTxs).map(_ -> "remote-htlc-timeout") ++
            d.revokedCommitPublished.map(_.commitTx).map(_ -> "revoked-commit") ++
            d.revokedCommitPublished.flatMap(_.claimMainOutputTx).map(_ -> "revoked-main") ++
            d.revokedCommitPublished.flatMap(_.mainPenaltyTx).map(_ -> "revoked-main-penalty") ++
            d.revokedCommitPublished.flatMap(_.htlcPenaltyTxs).map(_ -> "revoked-htlc-penalty") ++
            d.revokedCommitPublished.flatMap(_.claimHtlcDelayedPenaltyTxs).map(_ -> "revoked-htlc-penalty-delayed")
          )
          .map { case (tx, desc) => tx.txid -> (tx, desc) } // will allow easy lookup of parent transaction
          .toMap

        def fee(child: Transaction): Option[Satoshi] = {
          require(child.txIn.size == 1, "transaction must have exactly one input")
          val outPoint = child.txIn.head.outPoint
          val parentTxOut_opt = if (outPoint == d.commitments.commitInput.outPoint) {
            Some(d.commitments.commitInput.txOut)
          }
          else {
            txes.get(outPoint.txid) map { case (parent, _) => parent.txOut(outPoint.index.toInt) }
          }
          parentTxOut_opt map (parentTxOut => parentTxOut.amount - child.txOut.map(_.amount).sum)
        }

        txes.get(tx.txid) flatMap {
          case (_, desc) => fee(tx).map(_ -> desc)
        }
      } else {
        None
      }
    }
  }

}
