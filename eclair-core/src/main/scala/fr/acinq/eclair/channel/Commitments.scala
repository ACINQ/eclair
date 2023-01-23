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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.Monitoring.Metrics
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.crypto.{Generators, ShaChain}
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

  def nextRemoteCommit_opt: Option[RemoteCommit] = remoteNextCommitInfo.swap.toOption.map(_.nextRemoteCommit)

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
