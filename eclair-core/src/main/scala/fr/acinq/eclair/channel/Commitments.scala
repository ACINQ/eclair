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

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.Monitoring.Metrics
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.crypto.{Generators, ShaChain}
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.transactions.DirectedHtlc._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage], signed: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class HtlcTxAndRemoteSig(htlcTx: HtlcTx, remoteSig: ByteVector64)
case class CommitTxAndRemoteSig(commitTx: CommitTx, remoteSig: ByteVector64)
case class LocalCommit(index: Long, spec: CommitmentSpec, commitTxAndRemoteSig: CommitTxAndRemoteSig, htlcTxsAndRemoteSigs: List[HtlcTxAndRemoteSig])
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: ByteVector32, remotePerCommitmentPoint: PublicKey)
case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long)
// @formatter:on

// @formatter:off
trait AbstractCommitments {
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc]
  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc]
  def localNodeId: PublicKey
  def remoteNodeId: PublicKey
  def capacity: Satoshi
  def availableBalanceForReceive: MilliSatoshi
  def availableBalanceForSend: MilliSatoshi
  def originChannels: Map[Long, Origin]
  def channelId: ByteVector32
  def announceChannel: Boolean
}
// @formatter:on

/**
 * about remoteNextCommitInfo:
 * we either:
 * - have built and signed their next commit tx with their next revocation hash which can now be discarded
 * - have their next per-commitment point
 * So, when we've signed and sent a commit message and are waiting for their revocation message,
 * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
 */
case class Commitments(channelId: ByteVector32,
                       channelConfig: ChannelConfig,
                       channelFeatures: ChannelFeatures,
                       localParams: LocalParams, remoteParams: RemoteParams,
                       channelFlags: ChannelFlags,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: LocalChanges, remoteChanges: RemoteChanges,
                       localNextHtlcId: Long, remoteNextHtlcId: Long,
                       originChannels: Map[Long, Origin], // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
                       remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey],
                       localFundingStatus: LocalFundingStatus,
                       remoteFundingStatus: RemoteFundingStatus,
                       remotePerCommitmentSecrets: ShaChain) extends AbstractCommitments {

  import Commitments._

  def nextRemoteCommit_opt: Option[RemoteCommit] = remoteNextCommitInfo.swap.toOption.map(_.nextRemoteCommit)

  /**
   * Add a change to our proposed change list.
   *
   * @param proposal proposed change to add.
   * @return an updated commitment instance.
   */
  private def addLocalProposal(proposal: UpdateMessage): Commitments =
    copy(localChanges = localChanges.copy(proposed = localChanges.proposed :+ proposal))

  private def addRemoteProposal(proposal: UpdateMessage): Commitments =
    copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed :+ proposal))

  /**
   * @param cmd add HTLC command
   * @return either Left(failure, error message) where failure is a failure message (see BOLT #4 and the Failure Message class) or Right(new commitments, updateAddHtlc)
   */
  def sendAdd(cmd: CMD_ADD_HTLC, currentHeight: BlockHeight, feeConf: OnChainFeeConf): Either[ChannelException, (Commitments, UpdateAddHtlc)] = {
    // we must ensure we're not relaying htlcs that are already expired, otherwise the downstream channel will instantly close
    // NB: we add a 3 blocks safety to reduce the probability of running into this when our bitcoin node is slightly outdated
    val minExpiry = CltvExpiry(currentHeight + 3)
    if (cmd.cltvExpiry < minExpiry) {
      return Left(ExpiryTooSmall(channelId, minimum = minExpiry, actual = cmd.cltvExpiry, blockHeight = currentHeight))
    }
    // we don't want to use too high a refund timeout, because our funds will be locked during that time if the payment is never fulfilled
    val maxExpiry = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(currentHeight)
    if (cmd.cltvExpiry >= maxExpiry) {
      return Left(ExpiryTooBig(channelId, maximum = maxExpiry, actual = cmd.cltvExpiry, blockHeight = currentHeight))
    }

    // even if remote advertises support for 0 msat htlc, we limit ourselves to values strictly positive, hence the max(1 msat)
    val htlcMinimum = remoteParams.htlcMinimum.max(1 msat)
    if (cmd.amount < htlcMinimum) {
      return Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = cmd.amount))
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before offering new HTLCs
    // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
    val localFeeratePerKw = feeConf.getCommitmentFeerate(remoteNodeId, channelType, capacity, None)
    val remoteFeeratePerKw = localCommit.spec.commitTxFeerate +: remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
    remoteFeeratePerKw.find(feerate => feeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(channelType, localFeeratePerKw, feerate)) match {
      case Some(feerate) => return Left(FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = feerate))
      case None =>
    }

    // let's compute the current commitment *as seen by them* with this change taken into account
    val add = UpdateAddHtlc(channelId, localNextHtlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion, cmd.nextBlindingKey_opt)
    // we increment the local htlc index and add an entry to the origins map
    val commitments1 = addLocalProposal(add).copy(localNextHtlcId = localNextHtlcId + 1, originChannels = originChannels + (add.id -> cmd.origin))
    // we need to base the next current commitment on the last sig we sent, even if we didn't yet receive their revocation
    val remoteCommit1 = commitments1.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(commitments1.remoteCommit)
    val reduced = CommitmentSpec.reduce(remoteCommit1.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)
    // the HTLC we are about to create is outgoing, but from their point of view it is incoming
    val outgoingHtlcs = reduced.htlcs.collect(incoming)

    // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
    val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitmentFormat)
    // the initiator needs to keep an extra buffer to be able to handle a x2 feerate increase and an additional htlc to avoid
    // getting the channel stuck (see https://github.com/lightningnetwork/lightning-rfc/issues/728).
    val funderFeeBuffer = commitTxTotalCostMsat(commitments1.remoteParams.dustLimit, reduced.copy(commitTxFeerate = reduced.commitTxFeerate * 2), commitmentFormat) + htlcOutputFee(reduced.commitTxFeerate * 2, commitmentFormat)
    // NB: increasing the feerate can actually remove htlcs from the commit tx (if they fall below the trim threshold)
    // which may result in a lower commit tx fee; this is why we take the max of the two.
    val missingForSender = reduced.toRemote - commitments1.localChannelReserve - (if (commitments1.localParams.isInitiator) fees.max(funderFeeBuffer.truncateToSatoshi) else 0.sat)
    val missingForReceiver = reduced.toLocal - commitments1.remoteChannelReserve - (if (commitments1.localParams.isInitiator) 0.sat else fees)
    if (missingForSender < 0.msat) {
      return Left(InsufficientFunds(channelId, amount = cmd.amount, missing = -missingForSender.truncateToSatoshi, reserve = commitments1.localChannelReserve, fees = if (commitments1.localParams.isInitiator) fees else 0.sat))
    } else if (missingForReceiver < 0.msat) {
      if (localParams.isInitiator) {
        // receiver is not the channel initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      } else {
        return Left(RemoteCannotAffordFeesForNewHtlc(channelId, amount = cmd.amount, missing = -missingForReceiver.truncateToSatoshi, reserve = commitments1.remoteChannelReserve, fees = fees))
      }
    }

    // We apply local *and* remote restrictions, to ensure both peers are happy with the resulting number of HTLCs.
    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since outgoingHtlcs is a Set).
    val htlcValueInFlight = outgoingHtlcs.toSeq.map(_.amountMsat).sum
    val allowedHtlcValueInFlight = commitments1.maxHtlcAmount
    if (allowedHtlcValueInFlight < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(channelId, maximum = allowedHtlcValueInFlight, actual = htlcValueInFlight))
    }
    if (Seq(commitments1.localParams.maxAcceptedHtlcs, commitments1.remoteParams.maxAcceptedHtlcs).min < outgoingHtlcs.size) {
      return Left(TooManyAcceptedHtlcs(channelId, maximum = Seq(commitments1.localParams.maxAcceptedHtlcs, commitments1.remoteParams.maxAcceptedHtlcs).min))
    }

    // If sending this htlc would overflow our dust exposure, we reject it.
    val maxDustExposure = feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure
    val localReduced = DustExposure.reduceForDustExposure(localCommit.spec, commitments1.localChanges.all, remoteChanges.all)
    val localDustExposureAfterAdd = DustExposure.computeExposure(localReduced, localParams.dustLimit, commitmentFormat)
    if (localDustExposureAfterAdd > maxDustExposure) {
      return Left(LocalDustHtlcExposureTooHigh(channelId, maxDustExposure, localDustExposureAfterAdd))
    }
    val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit1.spec, remoteChanges.all, commitments1.localChanges.all)
    val remoteDustExposureAfterAdd = DustExposure.computeExposure(remoteReduced, remoteParams.dustLimit, commitmentFormat)
    if (remoteDustExposureAfterAdd > maxDustExposure) {
      return Left(RemoteDustHtlcExposureTooHigh(channelId, maxDustExposure, remoteDustExposureAfterAdd))
    }

    Right(commitments1, add)
  }

  def receiveAdd(add: UpdateAddHtlc, feeConf: OnChainFeeConf): Either[ChannelException, Commitments] = {
    if (add.id != remoteNextHtlcId) {
      return Left(UnexpectedHtlcId(channelId, expected = remoteNextHtlcId, actual = add.id))
    }

    // we used to not enforce a strictly positive minimum, hence the max(1 msat)
    val htlcMinimum = localParams.htlcMinimum.max(1 msat)
    if (add.amountMsat < htlcMinimum) {
      return Left(HtlcValueTooSmall(channelId, minimum = htlcMinimum, actual = add.amountMsat))
    }

    // we allowed mismatches between our feerates and our remote's as long as commitments didn't contain any HTLC at risk
    // we need to verify that we're not disagreeing on feerates anymore before accepting new HTLCs
    // NB: there may be a pending update_fee that hasn't been applied yet that needs to be taken into account
    val localFeeratePerKw = feeConf.getCommitmentFeerate(remoteNodeId, channelType, capacity, None)
    val remoteFeeratePerKw = localCommit.spec.commitTxFeerate +: remoteChanges.all.collect { case f: UpdateFee => f.feeratePerKw }
    remoteFeeratePerKw.find(feerate => feeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(channelType, localFeeratePerKw, feerate)) match {
      case Some(feerate) => return Left(FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = feerate))
      case None =>
    }

    // let's compute the current commitment *as seen by us* including this change
    val commitments1 = addRemoteProposal(add).copy(remoteNextHtlcId = remoteNextHtlcId + 1)
    val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)
    val incomingHtlcs = reduced.htlcs.collect(incoming)

    // note that the initiator pays the fee, so if sender != initiator, both sides will have to afford this payment
    val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitmentFormat)
    // NB: we don't enforce the funderFeeReserve (see sendAdd) because it would confuse a remote initiator that doesn't have this mitigation in place
    // We could enforce it once we're confident a large portion of the network implements it.
    val missingForSender = reduced.toRemote - commitments1.remoteChannelReserve - (if (commitments1.localParams.isInitiator) 0.sat else fees)
    val missingForReceiver = reduced.toLocal - commitments1.localChannelReserve - (if (commitments1.localParams.isInitiator) fees else 0.sat)
    if (missingForSender < 0.sat) {
      return Left(InsufficientFunds(channelId, amount = add.amountMsat, missing = -missingForSender.truncateToSatoshi, reserve = commitments1.remoteChannelReserve, fees = if (commitments1.localParams.isInitiator) 0.sat else fees))
    } else if (missingForReceiver < 0.sat) {
      if (localParams.isInitiator) {
        return Left(CannotAffordFees(channelId, missing = -missingForReceiver.truncateToSatoshi, reserve = commitments1.localChannelReserve, fees = fees))
      } else {
        // receiver is not the channel initiator; it is ok if it can't maintain its channel_reserve for now, as long as its balance is increasing, which is the case if it is receiving a payment
      }
    }

    // NB: we need the `toSeq` because otherwise duplicate amountMsat would be removed (since incomingHtlcs is a Set).
    val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
    if (commitments1.localParams.maxHtlcValueInFlightMsat < htlcValueInFlight) {
      return Left(HtlcValueTooHighInFlight(channelId, maximum = commitments1.localParams.maxHtlcValueInFlightMsat, actual = htlcValueInFlight))
    }

    if (incomingHtlcs.size > commitments1.localParams.maxAcceptedHtlcs) {
      return Left(TooManyAcceptedHtlcs(channelId, maximum = commitments1.localParams.maxAcceptedHtlcs))
    }

    Right(commitments1)
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Either[ChannelException, (Commitments, UpdateFulfillHtlc)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if alreadyProposed(localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(channelId, cmd.id))
      case Some(htlc) if htlc.paymentHash == sha256(cmd.r) =>
        val fulfill = UpdateFulfillHtlc(channelId, cmd.id, cmd.r)
        val commitments1 = addLocalProposal(fulfill)
        payment.Monitoring.Metrics.recordIncomingPaymentDistribution(remoteParams.nodeId, htlc.amountMsat)
        Right((commitments1, fulfill))
      case Some(_) => Left(InvalidHtlcPreimage(channelId, cmd.id))
      case None => Left(UnknownHtlcId(channelId, cmd.id))
    }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] =
    getOutgoingHtlcCrossSigned(fulfill.id) match {
      case Some(htlc) if htlc.paymentHash == sha256(fulfill.paymentPreimage) => originChannels.get(fulfill.id) match {
        case Some(origin) =>
          payment.Monitoring.Metrics.recordOutgoingPaymentDistribution(remoteParams.nodeId, htlc.amountMsat)
          Right(addRemoteProposal(fulfill), origin, htlc)
        case None => Left(UnknownHtlcId(channelId, fulfill.id))
      }
      case Some(_) => Left(InvalidHtlcPreimage(channelId, fulfill.id))
      case None => Left(UnknownHtlcId(channelId, fulfill.id))
    }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (Commitments, HtlcFailureMessage)] =
    getIncomingHtlcCrossSigned(cmd.id) match {
      case Some(htlc) if alreadyProposed(localChanges.proposed, htlc.id) =>
        // we have already sent a fail/fulfill for this htlc
        Left(UnknownHtlcId(channelId, cmd.id))
      case Some(htlc) =>
        // we need the shared secret to build the error packet
        OutgoingPaymentPacket.buildHtlcFailure(nodeSecret, cmd, htlc).map(fail => (addLocalProposal(fail), fail))
      case None => Left(UnknownHtlcId(channelId, cmd.id))
    }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (Commitments, UpdateFailMalformedHtlc)] = {
    // BADONION bit must be set in failure_code
    if ((cmd.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(channelId))
    } else {
      getIncomingHtlcCrossSigned(cmd.id) match {
        case Some(htlc) if alreadyProposed(localChanges.proposed, htlc.id) =>
          // we have already sent a fail/fulfill for this htlc
          Left(UnknownHtlcId(channelId, cmd.id))
        case Some(_) =>
          val fail = UpdateFailMalformedHtlc(channelId, cmd.id, cmd.onionHash, cmd.failureCode)
          val commitments1 = addLocalProposal(fail)
          Right((commitments1, fail))
        case None => Left(UnknownHtlcId(channelId, cmd.id))
      }
    }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] =
    getOutgoingHtlcCrossSigned(fail.id) match {
      case Some(htlc) => originChannels.get(fail.id) match {
        case Some(origin) => Right(addRemoteProposal(fail), origin, htlc)
        case None => Left(UnknownHtlcId(channelId, fail.id))
      }
      case None => Left(UnknownHtlcId(channelId, fail.id))
    }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[ChannelException, (Commitments, Origin, UpdateAddHtlc)] = {
    // A receiving node MUST fail the channel if the BADONION bit in failure_code is not set for update_fail_malformed_htlc.
    if ((fail.failureCode & FailureMessageCodecs.BADONION) == 0) {
      Left(InvalidFailureCode(channelId))
    } else {
      getOutgoingHtlcCrossSigned(fail.id) match {
        case Some(htlc) => originChannels.get(fail.id) match {
          case Some(origin) => Right(addRemoteProposal(fail), origin, htlc)
          case None => Left(UnknownHtlcId(channelId, fail.id))
        }
        case None => Left(UnknownHtlcId(channelId, fail.id))
      }
    }
  }

  def sendFee(cmd: CMD_UPDATE_FEE, feeConf: OnChainFeeConf): Either[ChannelException, (Commitments, UpdateFee)] = {
    if (!localParams.isInitiator) {
      Left(NonInitiatorCannotSendUpdateFee(channelId))
    } else {
      // let's compute the current commitment *as seen by them* with this change taken into account
      val fee = UpdateFee(channelId, cmd.feeratePerKw)
      // update_fee replace each other, so we can remove previous ones
      val commitments1 = copy(localChanges = localChanges.copy(proposed = localChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
      val reduced = CommitmentSpec.reduce(commitments1.remoteCommit.spec, commitments1.remoteChanges.acked, commitments1.localChanges.proposed)

      // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
      // we look from remote's point of view, so if local is initiator remote doesn't pay the fees
      val fees = commitTxTotalCost(commitments1.remoteParams.dustLimit, reduced, commitmentFormat)
      val missing = reduced.toRemote.truncateToSatoshi - commitments1.localChannelReserve - fees
      if (missing < 0.sat) {
        return Left(CannotAffordFees(channelId, missing = -missing, reserve = commitments1.localChannelReserve, fees = fees))
      }

      // if we would overflow our dust exposure with the new feerate, we avoid sending this fee update
      if (feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.closeOnUpdateFeeOverflow) {
        val maxDustExposure = feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure
        // this is the commitment as it would be if our update_fee was immediately signed by both parties (it is only an
        // estimate because there can be concurrent updates)
        val localReduced = DustExposure.reduceForDustExposure(localCommit.spec, commitments1.localChanges.all, remoteChanges.all)
        val localDustExposureAfterFeeUpdate = DustExposure.computeExposure(localReduced, cmd.feeratePerKw, localParams.dustLimit, commitmentFormat)
        if (localDustExposureAfterFeeUpdate > maxDustExposure) {
          return Left(LocalDustHtlcExposureTooHigh(channelId, maxDustExposure, localDustExposureAfterFeeUpdate))
        }
        val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit.spec, remoteChanges.all, commitments1.localChanges.all)
        val remoteDustExposureAfterFeeUpdate = DustExposure.computeExposure(remoteReduced, cmd.feeratePerKw, remoteParams.dustLimit, commitmentFormat)
        if (remoteDustExposureAfterFeeUpdate > maxDustExposure) {
          return Left(RemoteDustHtlcExposureTooHigh(channelId, maxDustExposure, remoteDustExposureAfterFeeUpdate))
        }
      }

      Right(commitments1, fee)
    }
  }

  def receiveFee(fee: UpdateFee, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, Commitments] = {
    if (localParams.isInitiator) {
      Left(NonInitiatorCannotSendUpdateFee(channelId))
    } else if (fee.feeratePerKw < FeeratePerKw.MinimumFeeratePerKw) {
      Left(FeerateTooSmall(channelId, remoteFeeratePerKw = fee.feeratePerKw))
    } else {
      Metrics.RemoteFeeratePerKw.withoutTags().record(fee.feeratePerKw.toLong)
      val localFeeratePerKw = feeConf.getCommitmentFeerate(remoteNodeId, channelType, capacity, None)
      log.info("remote feeratePerKw={}, local feeratePerKw={}, ratio={}", fee.feeratePerKw, localFeeratePerKw, fee.feeratePerKw.toLong.toDouble / localFeeratePerKw.toLong)
      if (feeConf.feerateToleranceFor(remoteNodeId).isFeeDiffTooHigh(channelType, localFeeratePerKw, fee.feeratePerKw) && commitment.hasPendingOrProposedHtlcs(common)) {
        Left(FeerateTooDifferent(channelId, localFeeratePerKw = localFeeratePerKw, remoteFeeratePerKw = fee.feeratePerKw))
      } else {
        // NB: we check that the initiator can afford this new fee even if spec allows to do it at next signature
        // It is easier to do it here because under certain (race) conditions spec allows a lower-than-normal fee to be paid,
        // and it would be tricky to check if the conditions are met at signing
        // (it also means that we need to check the fee of the initial commitment tx somewhere)

        // let's compute the current commitment *as seen by us* including this change
        // update_fee replace each other, so we can remove previous ones
        val commitments1 = copy(remoteChanges = remoteChanges.copy(proposed = remoteChanges.proposed.filterNot(_.isInstanceOf[UpdateFee]) :+ fee))
        val reduced = CommitmentSpec.reduce(commitments1.localCommit.spec, commitments1.localChanges.acked, commitments1.remoteChanges.proposed)

        // a node cannot spend pending incoming htlcs, and need to keep funds above the reserve required by the counterparty, after paying the fee
        val fees = commitTxTotalCost(commitments1.localParams.dustLimit, reduced, commitmentFormat)
        val missing = reduced.toRemote.truncateToSatoshi - commitments1.remoteChannelReserve - fees
        if (missing < 0.sat) {
          return Left(CannotAffordFees(channelId, missing = -missing, reserve = commitments1.remoteChannelReserve, fees = fees))
        }

        // if we would overflow our dust exposure with the new feerate, we reject this fee update
        if (feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.closeOnUpdateFeeOverflow) {
          val maxDustExposure = feeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure
          val localReduced = DustExposure.reduceForDustExposure(localCommit.spec, localChanges.all, commitments1.remoteChanges.all)
          val localDustExposureAfterFeeUpdate = DustExposure.computeExposure(localReduced, fee.feeratePerKw, localParams.dustLimit, commitmentFormat)
          if (localDustExposureAfterFeeUpdate > maxDustExposure) {
            return Left(LocalDustHtlcExposureTooHigh(channelId, maxDustExposure, localDustExposureAfterFeeUpdate))
          }
          // this is the commitment as it would be if their update_fee was immediately signed by both parties (it is only an
          // estimate because there can be concurrent updates)
          val remoteReduced = DustExposure.reduceForDustExposure(remoteCommit.spec, commitments1.remoteChanges.all, localChanges.all)
          val remoteDustExposureAfterFeeUpdate = DustExposure.computeExposure(remoteReduced, fee.feeratePerKw, remoteParams.dustLimit, commitmentFormat)
          if (remoteDustExposureAfterFeeUpdate > maxDustExposure) {
            return Left(RemoteDustHtlcExposureTooHigh(channelId, maxDustExposure, remoteDustExposureAfterFeeUpdate))
          }
        }

        Right(commitments1)
      }
    }
  }

  def localHasUnsignedOutgoingHtlcs: Boolean = localChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined

  def remoteHasUnsignedOutgoingHtlcs: Boolean = remoteChanges.proposed.collectFirst { case u: UpdateAddHtlc => u }.isDefined

  def localHasUnsignedOutgoingUpdateFee: Boolean = localChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined

  def remoteHasUnsignedOutgoingUpdateFee: Boolean = remoteChanges.proposed.collectFirst { case u: UpdateFee => u }.isDefined

  def localHasChanges: Boolean = remoteChanges.acked.nonEmpty || localChanges.proposed.nonEmpty

  def remoteHasChanges: Boolean = localChanges.acked.nonEmpty || remoteChanges.proposed.nonEmpty

  def sendCommit(keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (Commitments, CommitSig)] = {
    remoteNextCommitInfo match {
      case Right(_) if !localHasChanges =>
        Left(CannotSignWithoutChanges(channelId))
      case Right(remoteNextPerCommitmentPoint) =>
        // remote commitment will includes all local changes + remote acked changes
        val spec = CommitmentSpec.reduce(remoteCommit.spec, remoteChanges.acked, localChanges.proposed)
        val (remoteCommitTx, htlcTxs) = makeRemoteTxs(keyManager, channelConfig, channelFeatures, remoteCommit.index + 1, localParams, remoteParams, commitInput, remoteNextPerCommitmentPoint, spec)
        val sig = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath), TxOwner.Remote, commitmentFormat)

        val sortedHtlcTxs: Seq[TransactionWithInputInfo] = htlcTxs.sortBy(_.input.outPoint.index)
        val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
        val htlcSigs = sortedHtlcTxs.map(keyManager.sign(_, keyManager.htlcPoint(channelKeyPath), remoteNextPerCommitmentPoint, TxOwner.Remote, commitmentFormat))

        // NB: IN/OUT htlcs are inverted because this is the remote commit
        log.info(s"built remote commit number=${remoteCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.commitTxFeerate} txid=${remoteCommitTx.tx.txid} tx={}", spec.htlcs.collect(outgoing).map(_.id).mkString(","), spec.htlcs.collect(incoming).map(_.id).mkString(","), remoteCommitTx.tx)
        Metrics.recordHtlcsInFlight(spec, remoteCommit.spec)

        val commitSig = CommitSig(
          channelId = channelId,
          signature = sig,
          htlcSignatures = htlcSigs.toList)
        val commitments1 = copy(
          remoteNextCommitInfo = Left(WaitingForRevocation(RemoteCommit(remoteCommit.index + 1, spec, remoteCommitTx.tx.txid, remoteNextPerCommitmentPoint), commitSig, localCommit.index)),
          localChanges = localChanges.copy(proposed = Nil, signed = localChanges.proposed),
          remoteChanges = remoteChanges.copy(acked = Nil, signed = remoteChanges.acked))
        Right(commitments1, commitSig)
      case Left(_) =>
        Left(CannotSignBeforeRevocation(channelId))
    }
  }

  def receiveCommit(commit: CommitSig, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (Commitments, RevokeAndAck)] = {
    // they sent us a signature for *their* view of *our* next commit tx
    // so in terms of rev.hashes and indexes we have:
    // ourCommit.index -> our current revocation hash, which is about to become our old revocation hash
    // ourCommit.index + 1 -> our next revocation hash, used by *them* to build the sig we've just received, and which
    // is about to become our current revocation hash
    // ourCommit.index + 2 -> which is about to become our next revocation hash
    // we will reply to this sig with our old revocation hash preimage (at index) and our next revocation hash (at index + 1)
    // and will increment our index

    // lnd sometimes sends a new signature without any changes, which is a (harmless) spec violation
    if (!remoteHasChanges) {
      //  throw CannotSignWithoutChanges(channelId)
      log.warning("received a commit sig with no changes (probably coming from lnd)")
    }

    val spec = CommitmentSpec.reduce(localCommit.spec, localChanges.acked, remoteChanges.proposed)
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, localCommit.index + 1)
    val (localCommitTx, htlcTxs) = makeLocalTxs(keyManager, channelConfig, channelFeatures, localCommit.index + 1, localParams, remoteParams, commitInput, localPerCommitmentPoint, spec)

    log.info(s"built local commit number=${localCommit.index + 1} toLocalMsat=${spec.toLocal.toLong} toRemoteMsat=${spec.toRemote.toLong} htlc_in={} htlc_out={} feeratePerKw=${spec.commitTxFeerate} txid=${localCommitTx.tx.txid} tx={}", spec.htlcs.collect(incoming).map(_.id).mkString(","), spec.htlcs.collect(outgoing).map(_.id).mkString(","), localCommitTx.tx)

    if (!Transactions.checkSig(localCommitTx, commit.signature, remoteParams.fundingPubKey, TxOwner.Remote, commitmentFormat)) {
      return Left(InvalidCommitmentSignature(channelId, localCommitTx.tx.txid))
    }

    val sortedHtlcTxs: Seq[HtlcTx] = htlcTxs.sortBy(_.input.outPoint.index)
    if (commit.htlcSignatures.size != sortedHtlcTxs.size) {
      return Left(HtlcSigCountMismatch(channelId, sortedHtlcTxs.size, commit.htlcSignatures.size))
    }

    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val htlcTxsAndRemoteSigs = sortedHtlcTxs.zip(commit.htlcSignatures).toList.map {
      case (htlcTx: HtlcTx, remoteSig) =>
        if (!Transactions.checkSig(htlcTx, remoteSig, remoteHtlcPubkey, TxOwner.Remote, commitmentFormat)) {
          return Left(InvalidHtlcSignature(channelId, htlcTx.tx.txid))
        }
        HtlcTxAndRemoteSig(htlcTx, remoteSig)
    }

    // we will send our revocation preimage + our next revocation hash
    val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, localCommit.index)
    val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, localCommit.index + 2)
    val revocation = RevokeAndAck(
      channelId = channelId,
      perCommitmentSecret = localPerCommitmentSecret,
      nextPerCommitmentPoint = localNextPerCommitmentPoint
    )

    // update our commitment data
    val localCommit1 = LocalCommit(
      index = localCommit.index + 1,
      spec,
      commitTxAndRemoteSig = CommitTxAndRemoteSig(localCommitTx, commit.signature),
      htlcTxsAndRemoteSigs = htlcTxsAndRemoteSigs)
    val ourChanges1 = localChanges.copy(acked = Nil)
    val theirChanges1 = remoteChanges.copy(proposed = Nil, acked = remoteChanges.acked ++ remoteChanges.proposed)
    val commitments1 = copy(localCommit = localCommit1, localChanges = ourChanges1, remoteChanges = theirChanges1)

    Right(commitments1, revocation)
  }

  def receiveRevocation(revocation: RevokeAndAck, maxDustExposure: Satoshi): Either[ChannelException, (Commitments, Seq[PostRevocationAction])] = {
    // we receive a revocation because we just sent them a sig for their next commit tx
    remoteNextCommitInfo match {
      case Left(_) if revocation.perCommitmentSecret.publicKey != remoteCommit.remotePerCommitmentPoint =>
        Left(InvalidRevocation(channelId))
      case Left(WaitingForRevocation(theirNextCommit, _, _)) =>
        val receivedHtlcs = remoteChanges.signed.collect {
          // we forward adds downstream only when they have been committed by both sides
          // it always happen when we receive a revocation, because they send the add, then they sign it, then we sign it
          case add: UpdateAddHtlc => add
        }
        val failedHtlcs = remoteChanges.signed.collect {
          // same for fails: we need to make sure that they are in neither commitment before propagating the fail upstream
          case fail: UpdateFailHtlc =>
            val origin = originChannels(fail.id)
            val add = remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
            RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFail(fail))
          // same as above
          case fail: UpdateFailMalformedHtlc =>
            val origin = originChannels(fail.id)
            val add = remoteCommit.spec.findIncomingHtlcById(fail.id).map(_.add).get
            RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFailMalformed(fail))
        }
        val (acceptedHtlcs, rejectedHtlcs) = {
          // the received htlcs have already been added to commitments (they've been signed by our peer), and may already
          // overflow our dust exposure (we cannot prevent them from adding htlcs): we artificially remove them before
          // deciding which we'll keep and relay and which we'll fail without relaying.
          val localSpecWithoutNewHtlcs = localCommit.spec.copy(htlcs = localCommit.spec.htlcs.filter {
            case IncomingHtlc(add) if receivedHtlcs.contains(add) => false
            case _ => true
          })
          val remoteSpecWithoutNewHtlcs = theirNextCommit.spec.copy(htlcs = theirNextCommit.spec.htlcs.filter {
            case OutgoingHtlc(add) if receivedHtlcs.contains(add) => false
            case _ => true
          })
          val localReduced = DustExposure.reduceForDustExposure(localSpecWithoutNewHtlcs, localChanges.all, remoteChanges.acked)
          val localCommitDustExposure = DustExposure.computeExposure(localReduced, localParams.dustLimit, commitmentFormat)
          val remoteReduced = DustExposure.reduceForDustExposure(remoteSpecWithoutNewHtlcs, remoteChanges.acked, localChanges.all)
          val remoteCommitDustExposure = DustExposure.computeExposure(remoteReduced, remoteParams.dustLimit, commitmentFormat)
          // we sort incoming htlcs by decreasing amount: we want to prioritize higher amounts.
          val sortedReceivedHtlcs = receivedHtlcs.sortBy(_.amountMsat).reverse
          DustExposure.filterBeforeForward(
            maxDustExposure,
            localReduced,
            localParams.dustLimit,
            localCommitDustExposure,
            remoteReduced,
            remoteParams.dustLimit,
            remoteCommitDustExposure,
            sortedReceivedHtlcs,
            commitmentFormat)
        }
        val actions = acceptedHtlcs.map(add => PostRevocationAction.RelayHtlc(add)) ++
          rejectedHtlcs.map(add => PostRevocationAction.RejectHtlc(add)) ++
          failedHtlcs.map(res => PostRevocationAction.RelayFailure(res))
        // the outgoing following htlcs have been completed (fulfilled or failed) when we received this revocation
        // they have been removed from both local and remote commitment
        // (since fulfill/fail are sent by remote, they are (1) signed by them, (2) revoked by us, (3) signed by us, (4) revoked by them
        val completedOutgoingHtlcs = remoteCommit.spec.htlcs.collect(incoming).map(_.id) -- theirNextCommit.spec.htlcs.collect(incoming).map(_.id)
        // we remove the newly completed htlcs from the origin map
        val originChannels1 = originChannels -- completedOutgoingHtlcs
        val commitments1 = copy(
          localChanges = localChanges.copy(signed = Nil, acked = localChanges.acked ++ localChanges.signed),
          remoteChanges = remoteChanges.copy(signed = Nil),
          remoteCommit = theirNextCommit,
          remoteNextCommitInfo = Right(revocation.nextPerCommitmentPoint),
          remotePerCommitmentSecrets = remotePerCommitmentSecrets.addHash(revocation.perCommitmentSecret.value, 0xFFFFFFFFFFFFL - remoteCommit.index),
          originChannels = originChannels1)
        Right(commitments1, actions)
      case Right(_) =>
        Left(UnexpectedRevocation(channelId))
    }
  }

  def specs2String: String = {
    s"""specs:
       |localcommit:
       |  toLocal: ${localCommit.spec.toLocal}
       |  toRemote: ${localCommit.spec.toRemote}
       |  htlcs:
       |${localCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |remotecommit:
       |  toLocal: ${remoteCommit.spec.toLocal}
       |  toRemote: ${remoteCommit.spec.toRemote}
       |  htlcs:
       |${remoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |next remotecommit:
       |  toLocal: ${remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toLocal).getOrElse("N/A")}
       |  toRemote: ${remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toRemote).getOrElse("N/A")}
       |  htlcs:
       |${remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")).getOrElse("N/A")}""".stripMargin
  }

  def validateSeed(keyManager: ChannelKeyManager): Boolean = {
    val localFundingKey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val remoteFundingKey = remoteParams.fundingPubKey
    val fundingScript = Script.write(Scripts.multiSig2of2(localFundingKey, remoteFundingKey))
    commitInput.redeemScript == fundingScript
  }

  def params: Params = Params(channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags)

  def common: Common = Common(localChanges, remoteChanges, localNextHtlcId, remoteNextHtlcId, localCommit.index, remoteCommit.index, originChannels, remoteNextCommitInfo.swap.map(waitingForRevocation => WaitForRev(waitingForRevocation.sent, waitingForRevocation.sentAfterLocalCommitIndex)).swap, remotePerCommitmentSecrets)

  def commitment: Commitment = Commitment(localFundingStatus, remoteFundingStatus, localCommit, remoteCommit, remoteNextCommitInfo.swap.map(_.nextRemoteCommit).toOption)

  def commitInput: InputInfo = commitment.commitInput

  def fundingTxId: ByteVector32 = commitment.fundingTxId

  def commitmentFormat: CommitmentFormat = params.commitmentFormat

  def channelType: SupportedChannelType = params.channelType

  def localNodeId: PublicKey = params.localNodeId

  def remoteNodeId: PublicKey = params.remoteNodeId

  def announceChannel: Boolean = params.announceChannel

  def capacity: Satoshi = commitment.capacity

  def maxHtlcAmount: MilliSatoshi = params.maxHtlcAmount

  def localChannelReserve: Satoshi = commitment.localChannelReserve(params)

  def remoteChannelReserve: Satoshi = commitment.remoteChannelReserve(params)

  def availableBalanceForSend: MilliSatoshi = commitment.availableBalanceForSend(params, common)

  def availableBalanceForReceive: MilliSatoshi = commitment.availableBalanceForReceive(params, common)

  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = commitment.getOutgoingHtlcCrossSigned(htlcId)

  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = commitment.getIncomingHtlcCrossSigned(htlcId)

  def fullySignedLocalCommitTx(keyManager: ChannelKeyManager): CommitTx = commitment.fullySignedLocalCommitTx(params, keyManager)

}

object Commitments {

  /** A 1:1 conversion helper to facilitate migration, nothing smart here. */
  def apply(params: Params, common: Common, commitment: Commitment): Commitments = Commitments(
    channelId = params.channelId,
    channelConfig = params.channelConfig,
    channelFeatures = params.channelFeatures,
    localParams = params.localParams,
    remoteParams = params.remoteParams,
    channelFlags = params.channelFlags,
    localCommit = commitment.localCommit,
    remoteCommit = commitment.remoteCommit,
    localChanges = common.localChanges,
    remoteChanges = common.remoteChanges,
    localNextHtlcId = common.localNextHtlcId,
    remoteNextHtlcId = common.remoteNextHtlcId,
    originChannels = common.originChannels,
    remoteNextCommitInfo = common.remoteNextCommitInfo.swap.map(waitForRev => WaitingForRevocation(commitment.nextRemoteCommit_opt.get, waitForRev.sent, waitForRev.sentAfterLocalCommitIndex)).swap,
    localFundingStatus = commitment.localFundingStatus,
    remoteFundingStatus = commitment.remoteFundingStatus,
    remotePerCommitmentSecrets = common.remotePerCommitmentSecrets
  )

  def alreadyProposed(changes: List[UpdateMessage], id: Long): Boolean = changes.exists {
    case u: UpdateFulfillHtlc => id == u.id
    case u: UpdateFailHtlc => id == u.id
    case u: UpdateFailMalformedHtlc => id == u.id
    case _ => false
  }

  // @formatter:off
  sealed trait PostRevocationAction
  object PostRevocationAction {
    case class RelayHtlc(incomingHtlc: UpdateAddHtlc) extends PostRevocationAction
    case class RejectHtlc(incomingHtlc: UpdateAddHtlc) extends PostRevocationAction
    case class RelayFailure(result: RES_ADD_SETTLED[Origin, HtlcResult]) extends PostRevocationAction
  }
  // @formatter:on

  def makeLocalTxs(keyManager: ChannelKeyManager,
                   channelConfig: ChannelConfig,
                   channelFeatures: ChannelFeatures,
                   commitTxNumber: Long,
                   localParams: LocalParams,
                   remoteParams: RemoteParams,
                   commitmentInput: InputInfo,
                   localPerCommitmentPoint: PublicKey,
                   spec: CommitmentSpec): (CommitTx, Seq[HtlcTx]) = {
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localDelayedPaymentPubkey = Generators.derivePubKey(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, localPerCommitmentPoint)
    val remotePaymentPubkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey)) {
      remoteParams.paymentBasepoint
    } else {
      Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    }
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
    val outputs = makeCommitTxOutputs(localParams.isInitiator, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, remotePaymentPubkey, localHtlcPubkey, remoteHtlcPubkey, localFundingPubkey, remoteParams.fundingPubKey, spec, channelFeatures.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, localPaymentBasepoint, remoteParams.paymentBasepoint, localParams.isInitiator, outputs)
    val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, localParams.dustLimit, localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPaymentPubkey, spec.htlcTxFeerate(channelFeatures.commitmentFormat), outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
  }

  def makeRemoteTxs(keyManager: ChannelKeyManager,
                    channelConfig: ChannelConfig,
                    channelFeatures: ChannelFeatures,
                    commitTxNumber: Long,
                    localParams: LocalParams,
                    remoteParams: RemoteParams,
                    commitmentInput: InputInfo,
                    remotePerCommitmentPoint: PublicKey,
                    spec: CommitmentSpec): (CommitTx, Seq[HtlcTx]) = {
    val channelKeyPath = keyManager.keyPath(localParams, channelConfig)
    val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
    val localPaymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey)
    val localPaymentPubkey = if (channelFeatures.hasFeature(Features.StaticRemoteKey)) {
      localPaymentBasepoint
    } else {
      Generators.derivePubKey(localPaymentBasepoint, remotePerCommitmentPoint)
    }
    val localHtlcPubkey = Generators.derivePubKey(keyManager.htlcPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(keyManager.revocationPoint(channelKeyPath).publicKey, remotePerCommitmentPoint)
    val outputs = makeCommitTxOutputs(!localParams.isInitiator, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, localPaymentPubkey, remoteHtlcPubkey, localHtlcPubkey, remoteParams.fundingPubKey, localFundingPubkey, spec, channelFeatures.commitmentFormat)
    val commitTx = Transactions.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localPaymentBasepoint, !localParams.isInitiator, outputs)
    val htlcTxs = Transactions.makeHtlcTxs(commitTx.tx, remoteParams.dustLimit, remoteRevocationPubkey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, spec.htlcTxFeerate(channelFeatures.commitmentFormat), outputs, channelFeatures.commitmentFormat)
    (commitTx, htlcTxs)
  }

  def msg2String(msg: LightningMessage): String = msg match {
    case u: UpdateAddHtlc => s"add-${u.id}"
    case u: UpdateFulfillHtlc => s"ful-${u.id}"
    case u: UpdateFailHtlc => s"fail-${u.id}"
    case _: UpdateFee => s"fee"
    case _: CommitSig => s"sig"
    case _: RevokeAndAck => s"rev"
    case _: Error => s"err"
    case _: ChannelReady => s"channel_ready"
    case _ => "???"
  }

}
