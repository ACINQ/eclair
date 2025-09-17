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
import fr.acinq.bitcoin.scalacompat.{OutPoint, Transaction, TxId}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.transactions.Transactions._

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
  case class CheckPreconditions(replyTo: ActorRef[PreconditionsResult], txInfo: ForceCloseTransaction, commitTx: Transaction, concurrentCommitTxs: Set[TxId]) extends Command

  private case object ParentTxOk extends Command
  private case object FundingTxNotFound extends Command
  private case object CommitTxRecentlyConfirmed extends Command
  private case object CommitTxDeeplyConfirmed extends Command
  private case object ConcurrentCommitAvailable extends Command
  private case object ConcurrentCommitRecentlyConfirmed extends Command
  private case object ConcurrentCommitDeeplyConfirmed extends Command
  private case object HtlcOutputAlreadySpent extends Command
  private case class UnknownFailure(reason: Throwable) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait PreconditionsResult
  case object PreconditionsOk extends PreconditionsResult
  case class PreconditionsFailed(reason: TxPublisher.TxRejectedReason) extends PreconditionsResult
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(txPublishContext.mdc()) {
        Behaviors.receiveMessagePartial {
          case CheckPreconditions(replyTo, txInfo, commitTx, concurrentCommitTxs) =>
            val prePublisher = new ReplaceableTxPrePublisher(nodeParams, replyTo, bitcoinClient, context)
            txInfo match {
              case _: ClaimLocalAnchorTx => prePublisher.checkLocalCommitAnchorPreconditions(commitTx)
              case _: ClaimRemoteAnchorTx => prePublisher.checkRemoteCommitAnchorPreconditions(commitTx)
              case _: SignedHtlcTx | _: ClaimHtlcTx => prePublisher.checkHtlcPreconditions(txInfo.desc, txInfo.input.outPoint, commitTx, concurrentCommitTxs)
              case _ =>
                replyTo ! PreconditionsOk
                Behaviors.stopped
            }
        }
      }
    }
  }

}

private class ReplaceableTxPrePublisher(nodeParams: NodeParams,
                                        replyTo: ActorRef[ReplaceableTxPrePublisher.PreconditionsResult],
                                        bitcoinClient: BitcoinCoreClient,
                                        context: ActorContext[ReplaceableTxPrePublisher.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxPrePublisher._

  private val log = context.log

  /**
   * We only claim our anchor output for our local commitment if:
   *  - our local commitment is unconfirmed
   *  - and we haven't seen a remote commitment (in which case it is more interesting to spend than the local commitment)
   */
  private def checkLocalCommitAnchorPreconditions(commitTx: Transaction): Behavior[Command] = {
    val fundingOutpoint = commitTx.txIn.head.outPoint
    context.pipeToSelf(bitcoinClient.getTxConfirmations(fundingOutpoint.txid).flatMap {
      case Some(_) =>
        // The funding transaction was found, let's see if we can still spend it.
        bitcoinClient.isTransactionOutputSpendable(fundingOutpoint.txid, fundingOutpoint.index.toInt, includeMempool = true).flatMap {
          case true =>
            // The funding output is unspent: let's publish our anchor transaction to get our local commit confirmed.
            Future.successful(ParentTxOk)
          case false =>
            // The funding output is spent: we check whether our local commit is confirmed or in our mempool.
            bitcoinClient.getTxConfirmations(commitTx.txid).transformWith {
              case Success(Some(confirmations)) if confirmations >= nodeParams.channelConf.minDepth => Future.successful(CommitTxDeeplyConfirmed)
              case Success(Some(confirmations)) if confirmations > 0 => Future.successful(CommitTxRecentlyConfirmed)
              case Success(Some(0)) => Future.successful(ParentTxOk) // our commit tx is unconfirmed, let's publish our anchor transaction
              case _ =>
                // Our commit tx is unconfirmed and cannot be found in our mempool: this means that a remote commit is
                // either confirmed or in our mempool. In that case, we don't want to use our local commit tx: the
                // remote commit is more interesting to us because we won't have any CSV delays on our outputs.
                Future.successful(ConcurrentCommitAvailable)
            }
        }
      case None =>
        // If the funding transaction cannot be found (e.g. when using 0-conf), we should retry later.
        Future.successful(FundingTxNotFound)
    }) {
      case Success(result) => result
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        replyTo ! PreconditionsOk
        Behaviors.stopped
      case FundingTxNotFound =>
        log.debug("funding tx could not be found, we don't know yet if we need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case CommitTxRecentlyConfirmed =>
        log.debug("local commit tx was recently confirmed, let's check again later")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case CommitTxDeeplyConfirmed =>
        log.debug("local commit tx is deeply confirmed, no need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case ConcurrentCommitAvailable =>
        log.warn("not publishing local anchor for commitTxId={}: we will use the remote commit tx instead", commitTx.txid)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error("could not check local anchor preconditions, proceeding anyway: ", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our commit tx.
        replyTo ! PreconditionsOk
        Behaviors.stopped
    }
  }

  /**
   * We only claim our anchor output for a remote commitment if:
   *  - that remote commitment is unconfirmed
   *  - there is no other commitment that is already confirmed
   */
  private def checkRemoteCommitAnchorPreconditions(commitTx: Transaction): Behavior[Command] = {
    val fundingOutpoint = commitTx.txIn.head.outPoint
    context.pipeToSelf(bitcoinClient.getTxConfirmations(fundingOutpoint.txid).flatMap {
      case Some(_) =>
        // The funding transaction was found, let's see if we can still spend it. Note that in this case, we only look
        // at *confirmed* spending transactions (unlike the local commit case).
        bitcoinClient.isTransactionOutputSpendable(fundingOutpoint.txid, fundingOutpoint.index.toInt, includeMempool = false).flatMap {
          case true =>
            // The funding output is unspent, or spent by an *unconfirmed* transaction: let's publish our anchor
            // transaction, we may be able to replace our local commit with this (more interesting) remote commit.
            Future.successful(ParentTxOk)
          case false =>
            // The funding output is spent by a confirmed commit tx: we check the status of our anchor's commit tx.
            bitcoinClient.getTxConfirmations(commitTx.txid).transformWith {
              case Success(Some(confirmations)) if confirmations >= nodeParams.channelConf.minDepth => Future.successful(CommitTxDeeplyConfirmed)
              case Success(_) => Future.successful(CommitTxRecentlyConfirmed)
              // The spending tx is another commit tx: we can stop trying to publish this one.
              case _ => Future.successful(ConcurrentCommitAvailable)
            }
        }
      case None =>
        // If the funding transaction cannot be found (e.g. when using 0-conf), we should retry later.
        Future.successful(FundingTxNotFound)
    }) {
      case Success(result) => result
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        replyTo ! PreconditionsOk
        Behaviors.stopped
      case FundingTxNotFound =>
        log.debug("funding tx could not be found, we don't know yet if we need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case CommitTxRecentlyConfirmed =>
        log.debug("remote commit tx was recently confirmed, let's check again later")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case CommitTxDeeplyConfirmed =>
        log.debug("remote commit tx is deeply confirmed, no need to claim our anchor")
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case ConcurrentCommitAvailable =>
        log.warn("not publishing remote anchor for commitTxId={}: a concurrent commit tx is confirmed", commitTx.txid)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false))
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error("could not check remote anchor preconditions, proceeding anyway: ", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our commit tx.
        replyTo ! PreconditionsOk
        Behaviors.stopped
    }
  }

  /**
   * We first verify that the commit tx we're spending may confirm: if a conflicting commit tx is already confirmed, our
   * HTLC transaction has become obsolete. Then we check that the HTLC output that we're spending isn't already spent
   * by a confirmed transaction, which may happen in case of a race between HTLC-timeout and HTLC-success.
   */
  private def checkHtlcPreconditions(desc: String, input: OutPoint, commitTx: Transaction, concurrentCommitTxs: Set[TxId]): Behavior[Command] = {
    context.pipeToSelf(bitcoinClient.getTxConfirmations(commitTx.txid).flatMap {
      case Some(_) =>
        // If the HTLC output is already spent by a confirmed transaction, there is no need for RBF: either this is one
        // of our transactions (which thus has a high enough feerate), or it was a race with our peer and we lost.
        bitcoinClient.isTransactionOutputSpent(input.txid, input.index.toInt).map {
          case true => HtlcOutputAlreadySpent
          case false => ParentTxOk
        }
      case None =>
        // The parent commitment is unconfirmed: we shouldn't try to publish this HTLC transaction if a concurrent
        // commitment is deeply confirmed.
        checkConcurrentCommits(concurrentCommitTxs.toSeq).map {
          case Some(confirmations) if confirmations >= nodeParams.channelConf.minDepth => ConcurrentCommitDeeplyConfirmed
          case Some(_) => ConcurrentCommitRecentlyConfirmed
          case None => ParentTxOk
        }
    }) {
      case Success(result) => result
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case ParentTxOk =>
        replyTo ! PreconditionsOk
        Behaviors.stopped
      case ConcurrentCommitRecentlyConfirmed =>
        log.debug("cannot publish {} spending commitTxId={}: concurrent commit tx was recently confirmed, let's check again later", desc, commitTx.txid)
        // We keep retrying until the concurrent commit reaches min-depth to protect against reorgs.
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case ConcurrentCommitDeeplyConfirmed =>
        log.warn("cannot publish {} spending commitTxId={}: concurrent commit is deeply confirmed", desc, commitTx.txid)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case HtlcOutputAlreadySpent =>
        log.warn("cannot publish {}: htlc output {} has already been spent", desc, input)
        replyTo ! PreconditionsFailed(TxPublisher.TxRejectedReason.ConflictingTxConfirmed)
        Behaviors.stopped
      case UnknownFailure(reason) =>
        log.error(s"could not check $desc preconditions, proceeding anyway: ", reason)
        // If our checks fail, we don't want it to prevent us from trying to publish our htlc transactions.
        replyTo ! PreconditionsOk
        Behaviors.stopped
    }
  }

  /** Check the confirmation status of concurrent commitment transactions. */
  private def checkConcurrentCommits(txIds: Seq[TxId]): Future[Option[Int]] = {
    txIds.headOption match {
      case Some(txId) =>
        bitcoinClient.getTxConfirmations(txId).transformWith {
          case Success(Some(confirmations)) => Future.successful(Some(confirmations))
          case _ => checkConcurrentCommits(txIds.tail)
        }
      case None => Future.successful(None)
    }
  }

}
