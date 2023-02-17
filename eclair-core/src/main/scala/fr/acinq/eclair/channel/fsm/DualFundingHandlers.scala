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
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.BITCOIN_FUNDING_DOUBLE_SPENT
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.wire.protocol.Error

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
            context.system.eventStream.publish(TransactionPublished(dualFundedTx.fundingParams.channelId, remoteNodeId, fundingTx.signedTx, fundingTx.tx.localFees, "funding"))
            channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(dualFundedTx.fundingParams.channelId)))
          case Failure(t) =>
            channelOpenReplyToUser(Left(LocalError(t)))
            log.warning("error while publishing funding tx: {}", t.getMessage) // tx may be published by our peer, we can't fail-fast
        }
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
    val inputs = txs.flatMap(_.tx.localInputs).distinctBy(_.serialId).map(i => TxIn(i.outPoint, Nil, 0))
    if (inputs.nonEmpty) {
      wallet.rollback(Transaction(2, inputs, Nil, 0))
    }
  }

}
