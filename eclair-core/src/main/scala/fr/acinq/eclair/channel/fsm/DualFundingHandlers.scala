/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel.fsm

import fr.acinq.bitcoin.scalacompat.{Transaction, TxIn}
import fr.acinq.eclair.NotificationsLogger
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, NewTransaction}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.BITCOIN_FUNDING_DOUBLE_SPENT
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.wire.protocol.{ChannelReady, Error}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 06/05/2022.
 */

/**
 * This trait contains handlers related to dual-funding channel transactions.
 */
trait DualFundingHandlers extends CommonFundingHandlers {

  this: Channel =>

  def publishFundingTx(dualFundedTx: DualFundedUnconfirmedFundingTx): Unit = {
    dualFundedTx.sharedTx match {
      case _: PartiallySignedSharedTransaction =>
        log.info("we haven't received remote funding signatures yet: we cannot publish the funding transaction but our peer should publish it")
      case fundingTx: FullySignedSharedTransaction =>
        // Note that we don't use wallet.commit because we don't want to rollback on failure, since our peer may be able
        // to publish and we may be able to RBF.
        wallet.publishTransaction(fundingTx.signedTx).onComplete {
          case Success(_) =>
            context.system.eventStream.publish(TransactionPublished(dualFundedTx.fundingParams.channelId, remoteNodeId, fundingTx.signedTx, fundingTx.tx.localFees.truncateToSatoshi, "funding"))
            // We rely on Bitcoin Core ZMQ notifications to learn about transactions that appear in our mempool, but
            // it doesn't provide strong guarantees that we'll always receive an event. This can be an issue for 0-conf
            // funding transactions, where we end up delaying our channel_ready or splice_locked.
            // If we've successfully published the transaction, we can emit the event ourselves instead of waiting for
            // ZMQ: this is safe because duplicate events will be ignored.
            context.system.eventStream.publish(NewTransaction(fundingTx.signedTx))
          case Failure(t) => log.warning("error while publishing funding tx: {}", t.getMessage) // tx may be published by our peer, we can't fail-fast
        }
    }
  }

  /** Return true if we should stop waiting for confirmations when receiving our peer's channel_ready. */
  def switchToZeroConf(remoteChannelReady: ChannelReady, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED): Boolean = {
    if (!d.commitments.channelParams.zeroConf) {
      // We're not using zero-conf, but our peer decided to trust us anyway. We can skip waiting for confirmations if:
      //  - they provided a channel alias
      //  - there is a single version of the funding tx (otherwise we don't know which one to use)
      //  - they didn't contribute to the funding transaction (and thus cannot double-spend it)
      remoteChannelReady.alias_opt.isDefined &&
        d.commitments.active.size == 1 &&
        d.latestFundingTx.sharedTx.tx.remoteInputs.isEmpty
    } else {
      // We're already using zero-conf, but our peer was very fast and we received their channel_ready before our
      // watcher notification that the funding tx has been successfully published.
      false
    }
  }

  def handleNewBlockDualFundingUnconfirmed(c: CurrentBlockHeight, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) = {
    // We regularly notify the node operator that they may want to RBF this channel.
    val blocksSinceOpen = c.blockHeight - d.waitingSince
    if (d.latestFundingTx.fundingParams.isInitiator && (blocksSinceOpen % 288 == 0)) { // 288 blocks = 2 days
      context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Info, s"channelId=${d.channelId} is still unconfirmed after $blocksSinceOpen blocks, you may need to use the rbfopen RPC to make it confirm."))
    }
    if (Channel.DUAL_FUNDING_TIMEOUT_NON_INITIATOR < blocksSinceOpen && Closing.nothingAtStake(d)) {
      log.warning("funding transaction did not confirm in {} blocks and we have nothing at stake, forgetting channel", Channel.DUAL_FUNDING_TIMEOUT_NON_INITIATOR)
      handleFundingTimeout(d)
    } else if (d.lastChecked + 6 < c.blockHeight) {
      log.debug("checking if funding transactions have been double-spent")
      val fundingTxs = d.allFundingTxs.map(_.sharedTx.tx.buildUnsignedTx())
      // We check whether *all* funding attempts have been double-spent.
      // Since we only consider a transaction double-spent when the spending transaction is confirmed, this will not
      // return false positives when one of our transactions is confirmed, because its individual result will be false.
      Future.sequence(fundingTxs.map(tx => wallet.doubleSpent(tx))).map(_.forall(_ == true)).map {
        case true => self ! BITCOIN_FUNDING_DOUBLE_SPENT(fundingTxs.map(_.txid).toSet)
        case false => publishFundingTx(d.latestFundingTx) // we republish the highest feerate funding transaction
      }
      stay() using d.copy(lastChecked = c.blockHeight) storing()
    } else {
      stay()
    }
  }

  def handleDualFundingDoubleSpent(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) = {
    val fundingTxIds = d.commitments.active.map(_.fundingTxId).toSet
    if (fundingTxIds.subsetOf(e.fundingTxIds)) {
      log.warning("{} funding attempts have been double-spent, forgetting channel", fundingTxIds.size)
      d.allFundingTxs.map(_.sharedTx.tx.buildUnsignedTx()).foreach(tx => wallet.rollback(tx))
      goto(CLOSED) sending Error(d.channelId, FundingTxDoubleSpent(d.channelId).getMessage)
    } else {
      // Not all funding attempts have been double-spent, the channel may still confirm.
      // For example, we may have published an RBF attempt while we were checking if funding attempts were double-spent.
      stay()
    }
  }

  /**
   * In most cases we don't need to explicitly rollback funding transactions, as the locks are automatically removed by
   * bitcoind when transactions are published. But if we couldn't publish those transactions (e.g. because our peer
   * never sent us their signatures, or the transaction wasn't accepted in our mempool), their inputs may still be locked.
   */
  def rollbackDualFundingTxs(txs: Seq[SignedSharedTransaction]): Unit = {
    val inputs = txs.flatMap(sharedTx => sharedTx.tx.localInputs ++ sharedTx.tx.sharedInput_opt.toSeq).distinctBy(_.serialId).map(i => TxIn(i.outPoint, Nil, 0))
    if (inputs.nonEmpty) {
      wallet.rollback(Transaction(2, inputs, Nil, 0))
    }
  }

  def rollbackFundingAttempt(tx: SharedTransaction, previousTxs: Seq[SignedSharedTransaction]): Unit = {
    // We don't unlock previous inputs as the corresponding funding transaction may confirm.
    val previousInputs = previousTxs.flatMap(_.tx.localInputs.map(_.outPoint)).toSet
    val toUnlock = tx.localInputs.map(_.outPoint).toSet -- previousInputs
    if (toUnlock.nonEmpty) {
      val dummyTx = Transaction(2, toUnlock.toSeq.map(o => TxIn(o, Nil, 0)), Nil, 0)
      wallet.rollback(dummyTx)
    }
  }

  def rollbackRbfAttempt(signingSession: InteractiveTxSigningSession.WaitingForSigs, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED): Unit = {
    rollbackFundingAttempt(signingSession.fundingTx.tx, d.allFundingTxs.map(_.sharedTx))
  }

  def reportRbfFailure(fundingStatus: DualFundingStatus, f: Throwable): Unit = {
    fundingStatus match {
      case DualFundingStatus.RbfRequested(cmd) => cmd.replyTo ! RES_FAILURE(cmd, f)
      case DualFundingStatus.RbfInProgress(cmd_opt, txBuilder, _) =>
        txBuilder ! InteractiveTxBuilder.Abort
        cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, f))
      case _ => ()
    }
  }

}
