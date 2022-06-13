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

import akka.actor.Status
import akka.actor.typed.scaladsl.adapter.{TypedActorRefOps, actorRefAdapter}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.{Alias, BlockHeight, ShortChannelId}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{GetTxWithMeta, GetTxWithMetaResponse, WatchFundingLost, WatchFundingSpent}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{BITCOIN_FUNDING_PUBLISH_FAILED, BITCOIN_FUNDING_TIMEOUT, FUNDING_TIMEOUT_FUNDEE}
import fr.acinq.eclair.channel.publish.TxPublisher.PublishFinalTx
import fr.acinq.eclair.wire.protocol.{ChannelReady, ChannelReadyTlv, Error, TlvStream}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 28/03/2022.
 */

/**
 * This trait contains handlers related to funding channel transactions.
 */
trait FundingHandlers extends CommonHandlers {

  this: Channel =>

  /**
   * This function is used to return feedback to user at channel opening
   */
  def channelOpenReplyToUser(message: Either[ChannelOpenError, ChannelOpenResponse]): Unit = {
    val m = message match {
      case Left(LocalError(t)) => Status.Failure(t)
      case Left(RemoteError(e)) => Status.Failure(new RuntimeException(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}"))
      case Right(s) => s
    }
    origin_opt.foreach(_ ! m)
  }

  def watchFundingTx(commitments: Commitments, additionalKnownSpendingTxs: Set[ByteVector32] = Set.empty): Unit = {
    // TODO: should we wait for an acknowledgment from the watcher?
    val knownSpendingTxs = Set(commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitments.remoteCommit.txid) ++ commitments.remoteNextCommitInfo.left.toSeq.map(_.nextRemoteCommit.txid).toSet ++ additionalKnownSpendingTxs
    blockchain ! WatchFundingSpent(self, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, knownSpendingTxs)
    // TODO: implement this? (not needed if we use a reasonable min_depth)
    //blockchain ! WatchLost(self, commitments.commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks, BITCOIN_FUNDING_LOST)
  }

  /** When using 0-conf, we don't wait for the funding tx to confirm and instantly send channel_ready. */
  def skipFundingConfirmation(commitments: Commitments, remoteAlias_opt: Option[Alias], emitEvent: Boolean): (ShortIds, ChannelReady) = {
    blockchain ! WatchFundingLost(self, commitments.commitInput.outPoint.txid, nodeParams.channelConf.minDepthBlocks)
    val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelConfig)
    val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
    val shortIds = ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), remoteAlias_opt)
    if (emitEvent) context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, shortIds, remoteNodeId))
    val channelReady = ChannelReady(commitments.channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(shortIds.localAlias)))
    (shortIds, channelReady)
  }

  /**
   * When we are funder, we use this function to detect when our funding tx has been double-spent (by another transaction
   * that we made for some reason). If the funding tx has been double spent we can forget about the channel.
   */
  def checkDoubleSpent(fundingTx: Transaction): Unit = {
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

}
