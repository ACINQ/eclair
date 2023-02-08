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

import akka.actor.typed.scaladsl.adapter.{TypedActorRefOps, actorRefAdapter}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{GetTxWithMeta, GetTxWithMetaResponse}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT, FUNDING_TIMEOUT_FUNDEE}
import fr.acinq.eclair.channel.publish.TxPublisher.PublishFinalTx
import fr.acinq.eclair.wire.protocol.Error

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 28/03/2022.
 */

/**
 * This trait contains handlers related to single-funder channel transactions.
 */
trait SingleFundingHandlers extends CommonFundingHandlers {

  this: Channel =>

  def publishFundingTx(channelId: ByteVector32, fundingTx: Transaction, fundingTxFee: Satoshi): Unit = {
    wallet.commit(fundingTx).onComplete {
      case Success(true) =>
        context.system.eventStream.publish(TransactionPublished(channelId, remoteNodeId, fundingTx, fundingTxFee, "funding"))
        channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(channelId)))
      case Success(false) =>
        channelOpenReplyToUser(Left(LocalError(new RuntimeException("couldn't publish funding tx"))))
        self ! BITCOIN_FUNDING_PUBLISH_FAILED // fail-fast: this should be returned only when we are really sure the tx has *not* been published
      case Failure(t) =>
        channelOpenReplyToUser(Left(LocalError(t)))
        log.error(t, "error while committing funding tx: ") // tx may still have been published, can't fail-fast
    }
  }

  /**
   * When we are funder, we use this function to detect when our funding tx has been double-spent (by another transaction
   * that we made for some reason). If the funding tx has been double spent we can forget about the channel.
   */
  private def checkDoubleSpent(fundingTx: Transaction): Unit = {
    log.debug(s"checking status of funding tx txid=${fundingTx.txid}")
    wallet.doubleSpent(fundingTx).onComplete {
      case Success(true) =>
        log.warning(s"funding tx has been double spent! fundingTxid=${fundingTx.txid} fundingTx=$fundingTx")
        self ! BITCOIN_FUNDING_PUBLISH_FAILED
      case Success(false) => ()
      case Failure(t) => log.error(t, s"error while testing status of funding tx fundingTxid=${fundingTx.txid}: ")
    }
  }

  def handleGetFundingTx(getTxResponse: GetTxWithMetaResponse, waitingSince: BlockHeight, fundingTx_opt: Option[Transaction]) = {
    import getTxResponse._
    tx_opt match {
      case Some(_) => () // the funding tx exists, nothing to do
      case None =>
        fundingTx_opt match {
          case Some(fundingTx) =>
            // if we are funder, we never give up
            // we cannot correctly set the fee, but it was correctly set when we initially published the transaction
            log.info(s"republishing the funding tx...")
            txPublisher ! PublishFinalTx(fundingTx, fundingTx.txIn.head.outPoint, "funding", 0 sat, None)
            // we also check if the funding tx has been double-spent
            checkDoubleSpent(fundingTx)
            context.system.scheduler.scheduleOnce(1 day, blockchain.toClassic, GetTxWithMeta(self, txid))
          case None if (nodeParams.currentBlockHeight - waitingSince) > FUNDING_TIMEOUT_FUNDEE =>
            // if we are fundee, we give up after some time
            log.warning(s"funding tx hasn't been published in ${nodeParams.currentBlockHeight - waitingSince} blocks")
            self ! BITCOIN_FUNDING_TIMEOUT
          case None =>
            // let's wait a little longer
            log.info(s"funding tx still hasn't been published in ${nodeParams.currentBlockHeight - waitingSince} blocks, will wait ${FUNDING_TIMEOUT_FUNDEE - (nodeParams.currentBlockHeight - waitingSince)} more blocks...")
            context.system.scheduler.scheduleOnce(1 day, blockchain.toClassic, GetTxWithMeta(self, txid))
        }
    }
    stay()
  }

  def handleFundingPublishFailed(d: PersistentChannelData) = {
    log.error(s"failed to publish funding tx")
    val exc = ChannelFundingError(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    // NB: we don't use the handleLocalError handler because it would result in the commit tx being published, which we don't want:
    // implementation *guarantees* that in case of BITCOIN_FUNDING_PUBLISH_FAILED, the funding tx hasn't and will never be published, so we can close the channel right away
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(exc), isFatal = true))
    goto(CLOSED) sending error
  }

  def handleFundingTimeout(d: PersistentChannelData) = {
    log.warning(s"funding tx hasn't been confirmed in time, cancelling channel delay=$FUNDING_TIMEOUT_FUNDEE")
    val exc = FundingTxTimedout(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(exc), isFatal = true))
    goto(CLOSED) sending error
  }

  def singleFundingMinDepth(d: PersistentChannelData) = {
    val minDepth_opt = if (d.commitments.params.localParams.isInitiator) {
      Helpers.Funding.minDepthFunder(d.commitments.params.localParams.initFeatures)
    } else {
      // when we're not the channel initiator we scale the min_depth confirmations depending on the funding amount
      Helpers.Funding.minDepthFundee(nodeParams.channelConf, d.commitments.params.localParams.initFeatures, d.commitments.latest.commitInput.txOut.amount)
    }
    val minDepth = minDepth_opt.getOrElse {
      val defaultMinDepth = nodeParams.channelConf.minDepthBlocks
      // If we are in state WAIT_FOR_FUNDING_CONFIRMED, then the computed minDepth should be > 0, otherwise we would
      // have skipped this state. Maybe the computation method was changed and eclair was restarted?
      log.warning("min_depth should be defined since we're waiting for the funding tx to confirm, using default minDepth={}", defaultMinDepth)
      defaultMinDepth.toLong
    }
    minDepth
  }

}
