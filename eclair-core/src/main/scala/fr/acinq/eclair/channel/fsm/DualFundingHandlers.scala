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
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingConfirmedTriggered
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.InteractiveTxBuilder._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.BITCOIN_FUNDING_DOUBLE_SPENT
import fr.acinq.eclair.wire.protocol.Error

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 06/05/2022.
 */

/**
 * This trait contains handlers related to dual-funding channel transactions.
 */
trait DualFundingHandlers extends CommonFundingHandlers {

  this: Channel =>

  def publishFundingTx(fundingParams: InteractiveTxParams, fundingTx: SignedSharedTransaction): Unit = {
    fundingTx match {
      case _: PartiallySignedSharedTransaction =>
        log.info("we haven't received remote funding signatures yet: we cannot publish the funding transaction but our peer should publish it")
      case fundingTx: FullySignedSharedTransaction =>
        // Note that we don't use wallet.commit because we don't want to rollback on failure, since our peer may be able
        // to publish and we may be able to RBF.
        wallet.publishTransaction(fundingTx.signedTx).onComplete {
          case Success(_) =>
            context.system.eventStream.publish(TransactionPublished(fundingParams.channelId, remoteNodeId, fundingTx.signedTx, fundingTx.tx.localFees(fundingParams), "funding"))
            channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(fundingParams.channelId)))
          case Failure(t) =>
            channelOpenReplyToUser(Left(LocalError(t)))
            log.warning("error while publishing funding tx: {}", t.getMessage) // tx may be published by our peer, we can't fail-fast
        }
    }
  }

  def pruneCommitments(w: WatchFundingConfirmedTriggered, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED): Option[DualFundingTx] = {
    val allFundingTxs: Seq[DualFundingTx] = DualFundingTx(d.fundingTx, d.commitments) +: d.previousFundingTxs
    // We can forget other funding attempts now that one of the funding txs is confirmed.
    val otherFundingTxs = allFundingTxs.filter(_.commitments.fundingTxId != w.tx.txid).map(_.fundingTx)
    rollbackDualFundingTxs(otherFundingTxs)
    // We find which funding transaction got confirmed.
    allFundingTxs.find(_.commitments.fundingTxId == w.tx.txid) match {
      case Some(dft: DualFundingTx) =>
        log.info("channelId={} was confirmed at blockHeight={} txIndex={} with funding txid={}", d.channelId, w.blockHeight, w.txIndex, w.tx.txid)
        watchFundingTx(dft.commitments)
        context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, w.tx))
        Some(dft)
      case None =>
        // An unknown funding tx has been confirmed, this should never happen. Note that this isn't a case of
        // ERR_INFORMATION_LEAK, because here we receive a response from the watcher from a WatchConfirmed that we put
        // ourselves and somehow forgot.
        log.error(s"internal error: the funding tx that confirmed doesn't match any of our funding txs: ${w.tx.bin}")
        None
    }
  }

  /**
   * We could just ignore the event and handle it after the next reconnection, but this allows us to eagerly rollback
   * deprecated funding transactions
   */
  def handleDualFundingConfirmedOffline(w: WatchFundingConfirmedTriggered, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) = {
    pruneCommitments(w, d) match {
      case Some(DualFundingTx(fundingTx, commitments)) =>
        stay() using d.copy(commitments = commitments, fundingTx = fundingTx, previousFundingTxs = Nil) storing()
      case None =>
        stay()
    }
  }

  def handleNewBlockDualFundingUnconfirmed(c: CurrentBlockHeight, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) = {
    // We regularly notify the node operator that they may want to RBF this channel.
    val blocksSinceOpen = c.blockHeight - d.waitingSince
    if (d.fundingParams.isInitiator && (blocksSinceOpen % 288 == 0)) { // 288 blocks = 2 days
      context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Info, s"channelId=${d.channelId} is still unconfirmed after $blocksSinceOpen blocks, you may need to use the rbfopen RPC to make it confirm."))
    }
    if (Channel.FUNDING_TIMEOUT_FUNDEE < blocksSinceOpen && Closing.nothingAtStake(d)) {
      log.warning("funding transaction did not confirm in {} blocks and we have nothing at stake, forgetting channel", Channel.FUNDING_TIMEOUT_FUNDEE)
      handleFundingTimeout(d)
    } else if (d.lastChecked + 6 < c.blockHeight) {
      log.debug("checking if funding transactions have been double-spent")
      val fundingTxs = (d.fundingTx +: d.previousFundingTxs.map(_.fundingTx)).map(_.tx.buildUnsignedTx())
      // We check whether *all* funding attempts have been double-spent.
      // Since we only consider a transaction double-spent when the spending transaction is confirmed, this will not
      // return false positives when one of our transactions is confirmed, because its individual result will be false.
      Future.sequence(fundingTxs.map(tx => wallet.doubleSpent(tx))).map(_.forall(_ == true)).map {
        case true => self ! BITCOIN_FUNDING_DOUBLE_SPENT(fundingTxs.map(_.txid).toSet)
        case false => publishFundingTx(d.fundingParams, d.fundingTx) // we republish the highest feerate funding transaction
      }
      stay() using d.copy(lastChecked = c.blockHeight) storing()
    } else {
      stay()
    }
  }

  def handleDualFundingDoubleSpent(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) = {
    val fundingTxIds = (d.commitments +: d.previousFundingTxs.map(_.commitments)).map(_.fundingTxId).toSet
    if (fundingTxIds.subsetOf(e.fundingTxIds)) {
      log.warning("{} funding attempts have been double-spent, forgetting channel", fundingTxIds.size)
      (d.fundingTx +: d.previousFundingTxs.map(_.fundingTx)).foreach(tx => wallet.rollback(tx.tx.buildUnsignedTx()))
      channelOpenReplyToUser(Left(LocalError(FundingTxDoubleSpent(d.channelId))))
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
    val inputs = txs.flatMap(_.tx.localInputs).distinctBy(_.serialId).map(i => TxIn(toOutPoint(i), Nil, 0))
    if (inputs.nonEmpty) {
      wallet.rollback(Transaction(2, inputs, Nil, 0))
    }
  }

}
