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

package fr.acinq.eclair.channel.publish

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishLogContext
import fr.acinq.eclair.channel.{Commitments, HtlcTxAndRemoteSig}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateFulfillHtlc

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 20/12/2021.
 */

/**
 * This actor verifies that preconditions are met before attempting to publish a replaceable transaction.
 * It verifies for example that we're not trying to publish htlc transactions while the remote commitment has already
 * been confirmed, or that we have all the data necessary to sign transactions.
 */
object ReplaceableTxPrePublisher {

  // @formatter:off
  sealed trait Command
  case class CheckPreconditions(replyTo: ActorRef[PreconditionsResult], cmd: TxPublisher.PublishReplaceableTx) extends Command
  case object Stop extends Command

  private case object ParentTxOk extends Command
  private case object CommitTxAlreadyConfirmed extends RuntimeException with Command
  private case object LocalCommitTxConfirmed extends Command
  private case object RemoteCommitTxConfirmed extends Command
  private case object RemoteCommitTxPublished extends Command
  private case class UnknownFailure(reason: Throwable) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait PreconditionsResult
  case class PreconditionsOk(txWithWitnessData: ReplaceableTxWithWitnessData) extends PreconditionsResult
  case class PreconditionsFailed(reason: TxPublisher.TxRejectedReason) extends PreconditionsResult

  /** Replaceable transaction with all the witness data necessary to finalize. */
  sealed trait ReplaceableTxWithWitnessData { def txInfo: ReplaceableTransactionWithInputInfo }
  /** Replaceable transaction for which we may need to add wallet inputs. */
  sealed trait ReplaceableTxWithWalletInputs extends ReplaceableTxWithWitnessData
  case class ClaimLocalAnchorWithWitnessData(txInfo: ClaimLocalAnchorOutputTx) extends ReplaceableTxWithWalletInputs
  sealed trait HtlcWithWitnessData extends ReplaceableTxWithWalletInputs { override def txInfo: HtlcTx }
  case class HtlcSuccessWithWitnessData(txInfo: HtlcSuccessTx, remoteSig: ByteVector64, preimage: ByteVector32) extends HtlcWithWitnessData
  case class HtlcTimeoutWithWitnessData(txInfo: HtlcTimeoutTx, remoteSig: ByteVector64) extends HtlcWithWitnessData
  sealed trait ClaimHtlcWithWitnessData extends ReplaceableTxWithWitnessData { override def txInfo: ClaimHtlcTx }
  case class ClaimHtlcSuccessWithWitnessData(txInfo: ClaimHtlcSuccessTx, preimage: ByteVector32) extends ClaimHtlcWithWitnessData
  case class LegacyClaimHtlcSuccessWithWitnessData(txInfo: LegacyClaimHtlcSuccessTx, preimage: ByteVector32) extends ClaimHtlcWithWitnessData
  case class ClaimHtlcTimeoutWithWitnessData(txInfo: ClaimHtlcTimeoutTx) extends ClaimHtlcWithWitnessData
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(loggingInfo.mdc()) {
        new ReplaceableTxPrePublisher(nodeParams, bitcoinClient, context).start()
      }
    }
  }

}

private class ReplaceableTxPrePublisher(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, context: ActorContext[ReplaceableTxPrePublisher.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxPrePublisher._

  private val log = context.log

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case CheckPreconditions(replyTo, cmd) =>
        cmd.txInfo match {
          case localAnchorTx: Transactions.ClaimLocalAnchorOutputTx => checkAnchorPreconditions(replyTo, cmd, localAnchorTx)
          case htlcTx: Transactions.HtlcTx => checkHtlcPreconditions(replyTo, cmd, htlcTx)
          case claimHtlcTx: Transactions.ClaimHtlcTx => checkClaimHtlcPreconditions(replyTo, cmd, claimHtlcTx)
        }
      case Stop => Behaviors.stopped
    }
  }

  def checkAnchorPreconditions(replyTo: ActorRef[PreconditionsResult], cmd: TxPublisher.PublishReplaceableTx, localAnchorTx: ClaimLocalAnchorOutputTx): Behavior[Command] = {
    // We verify that:
    //  - our commit is not confirmed (if it is, no need to claim our anchor)
    //  - their commit is not confirmed (if it is, no need to claim our anchor either)
    //  - our commit tx is in the mempool (otherwise we can't claim our anchor)
    val commitTx = cmd.commitments.fullySignedLocalCommitTx(nodeParams.channelKeyManager).tx
    val fundingOutpoint = cmd.commitments.commitInput.outPoint
    context.pipeToSelf(bitcoinClient.isTransactionOutputSpendable(fundingOutpoint.txid, fundingOutpoint.index.toInt, includeMempool = false).flatMap {
      case false => Future.failed(CommitTxAlreadyConfirmed)
      case true =>
        // We must ensure our local commit tx is in the mempool before publishing the anchor transaction.
        // If it's already published, this call will be a no-op.
        bitcoinClient.publishTransaction(commitTx)
    }) {
      case Success(_) => ParentTxOk
      case Failure(CommitTxAlreadyConfirmed) => CommitTxAlreadyConfirmed
      case Failure(reason) if reason.getMessage.contains("rejecting replacement") => RemoteCommitTxPublished
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        replyTo ! PreconditionsOk(ClaimLocalAnchorWithWitnessData(localAnchorTx))
        Behaviors.stopped
      case CommitTxAlreadyConfirmed =>
        log.debug("commit tx is already confirmed, no need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case RemoteCommitTxPublished =>
        log.warn("cannot publish commit tx: there is a conflicting tx in the mempool")
        // We retry until that conflicting commit tx is confirmed or we're able to publish our local commit tx.
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions, proceeding anyway", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our commit tx.
        replyTo ! PreconditionsOk(ClaimLocalAnchorWithWitnessData(localAnchorTx))
        Behaviors.stopped
      case Stop => Behaviors.stopped
    }
  }

  def checkHtlcPreconditions(replyTo: ActorRef[PreconditionsResult], cmd: TxPublisher.PublishReplaceableTx, htlcTx: HtlcTx): Behavior[Command] = {
    // We verify that:
    //  - their commit is not confirmed: if it is, there is no need to publish our htlc transactions
    //  - if this is an htlc-success transaction, we have the preimage
    context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.commitments.remoteCommit.txid)) {
      case Success(Some(depth)) if depth >= nodeParams.minDepthBlocks => RemoteCommitTxConfirmed
      case Success(_) => ParentTxOk
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        extractHtlcWitnessData(htlcTx, cmd.commitments) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
      case RemoteCommitTxConfirmed =>
        log.warn("cannot publish {}: remote commit has been confirmed", cmd.desc)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions, proceeding anyway", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our htlc transactions.
        extractHtlcWitnessData(htlcTx, cmd.commitments) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
      case Stop => Behaviors.stopped
    }
  }

  private def extractHtlcWitnessData(htlcTx: HtlcTx, commitments: Commitments): Option[ReplaceableTxWithWitnessData] = {
    htlcTx match {
      case tx: HtlcSuccessTx =>
        commitments.localCommit.htlcTxsAndRemoteSigs.collectFirst {
          case HtlcTxAndRemoteSig(HtlcSuccessTx(input, _, _, _), remoteSig) if input.outPoint == tx.input.outPoint => remoteSig
        } match {
          case Some(remoteSig) =>
            commitments.localChanges.all.collectFirst {
              case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == tx.paymentHash => u.paymentPreimage
            } match {
              case Some(preimage) => Some(HtlcSuccessWithWitnessData(tx, remoteSig, preimage))
              case None =>
                log.error("preimage not found for htlcId={}, skipping...", tx.htlcId)
                None
            }
          case None =>
            log.error("remote signature not found for htlcId={}, skipping...", tx.htlcId)
            None
        }
      case tx: HtlcTimeoutTx =>
        commitments.localCommit.htlcTxsAndRemoteSigs.collectFirst {
          case HtlcTxAndRemoteSig(HtlcTimeoutTx(input, _, _), remoteSig) if input.outPoint == tx.input.outPoint => remoteSig
        } match {
          case Some(remoteSig) => Some(HtlcTimeoutWithWitnessData(tx, remoteSig))
          case None =>
            log.error("remote signature not found for htlcId={}, skipping...", tx.htlcId)
            None
        }
    }
  }

  def checkClaimHtlcPreconditions(replyTo: ActorRef[PreconditionsResult], cmd: TxPublisher.PublishReplaceableTx, claimHtlcTx: ClaimHtlcTx): Behavior[Command] = {
    // We verify that:
    //  - our commit is not confirmed: if it is, there is no need to publish our claim-htlc transactions
    //  - if this is a claim-htlc-success transaction, we have the preimage
    context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid)) {
      case Success(Some(depth)) if depth >= nodeParams.minDepthBlocks => LocalCommitTxConfirmed
      case Success(_) => ParentTxOk
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        extractClaimHtlcWitnessData(claimHtlcTx, cmd.commitments) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
      case LocalCommitTxConfirmed =>
        log.warn("cannot publish {}: local commit has been confirmed", cmd.desc)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions, proceeding anyway", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our htlc transactions.
        extractClaimHtlcWitnessData(claimHtlcTx, cmd.commitments) match {
          case Some(txWithWitnessData) => replyTo ! PreconditionsOk(txWithWitnessData)
          case None => replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        }
        Behaviors.stopped
      case Stop => Behaviors.stopped
    }
  }

  private def extractClaimHtlcWitnessData(claimHtlcTx: ClaimHtlcTx, commitments: Commitments): Option[ReplaceableTxWithWitnessData] = {
    claimHtlcTx match {
      case tx: LegacyClaimHtlcSuccessTx =>
        commitments.localChanges.all.collectFirst {
          case u: UpdateFulfillHtlc if u.id == tx.htlcId => u.paymentPreimage
        } match {
          case Some(preimage) => Some(LegacyClaimHtlcSuccessWithWitnessData(tx, preimage))
          case None =>
            log.error("preimage not found for legacy htlcId={}, skipping...", tx.htlcId)
            None
        }
      case tx: ClaimHtlcSuccessTx =>
        commitments.localChanges.all.collectFirst {
          case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == tx.paymentHash => u.paymentPreimage
        } match {
          case Some(preimage) => Some(ClaimHtlcSuccessWithWitnessData(tx, preimage))
          case None =>
            log.error("preimage not found for htlcId={}, skipping...", tx.htlcId)
            None
        }
      case tx: ClaimHtlcTimeoutTx => Some(ClaimHtlcTimeoutWithWitnessData(tx))
    }
  }

}
