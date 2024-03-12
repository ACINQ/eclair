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
import com.softwaremill.quicklens.{ModifyPimp, QuicklensEach}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction, TxId}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.getRelayFees
import fr.acinq.eclair.channel.LocalFundingStatus.{ConfirmedFundingTx, DualFundedUnconfirmedFundingTx, SingleFundedUnconfirmedFundingTx}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{ANNOUNCEMENTS_MINCONF, BroadcastChannelUpdate, PeriodicRefresh, REFRESH_CHANNEL_UPDATE_INTERVAL}
import fr.acinq.eclair.db.RevokedHtlcInfoCleaner
import fr.acinq.eclair.transactions.Transactions.SimpleTaprootChannelsStagingCommitmentFormat
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelReady, ChannelReadyTlv, ChannelTlv, TlvStream}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 18/08/2022.
 */

trait CommonFundingHandlers extends CommonHandlers {

  this: Channel =>

  /**
   * @param delay_opt optional delay to reduce herd effect at startup.
   */
  def watchFundingSpent(commitment: Commitment, additionalKnownSpendingTxs: Set[TxId], delay_opt: Option[FiniteDuration]): Unit = {
    val knownSpendingTxs = Set(commitment.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitment.remoteCommit.txid) ++ commitment.nextRemoteCommit_opt.map(_.commit.txid).toSet ++ additionalKnownSpendingTxs
    val watch = WatchFundingSpent(self, commitment.commitInput.outPoint.txid, commitment.commitInput.outPoint.index.toInt, knownSpendingTxs)
    delay_opt match {
      case Some(delay) => context.system.scheduler.scheduleOnce(delay, blockchain.toClassic, watch)
      case None => blockchain ! watch
    }
  }

  /**
   * @param delay_opt optional delay to reduce herd effect at startup.
   */
  def watchFundingConfirmed(fundingTxId: TxId, minDepth_opt: Option[Long], delay_opt: Option[FiniteDuration]): Unit = {
    val watch = minDepth_opt match {
      case Some(fundingMinDepth) => WatchFundingConfirmed(self, fundingTxId, fundingMinDepth)
      // When using 0-conf, we make sure that the transaction was successfully published, otherwise there is a risk
      // of accidentally double-spending it later (e.g. restarting bitcoind would remove the utxo locks).
      case None => WatchPublished(self, fundingTxId)
    }
    delay_opt match {
      case Some(delay) => context.system.scheduler.scheduleOnce(delay, blockchain.toClassic, watch)
      case None => blockchain ! watch
    }
  }

  def acceptFundingTxConfirmed(w: WatchFundingConfirmedTriggered, d: ChannelDataWithCommitments): Either[Commitments, (Commitments, Commitment)] = {
    log.info("funding txid={} was confirmed at blockHeight={} txIndex={}", w.tx.txid, w.blockHeight, w.txIndex)
    d.commitments.latest.localFundingStatus match {
      case _: SingleFundedUnconfirmedFundingTx =>
        // in the single-funding case, as fundee, it is the first time we see the full funding tx, we must verify that it is
        // valid (it pays the correct amount to the correct script). We also check as funder even if it's not really useful
        Try(Transaction.correctlySpends(d.commitments.latest.fullySignedLocalCommitTx(keyManager).tx, Seq(w.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
          case Success(_) => ()
          case Failure(t) =>
            log.error(t, s"rejecting channel with invalid funding tx: ${w.tx.bin}")
            throw InvalidFundingTx(d.channelId)
        }
      case _ => () // in the dual-funding case, we have already verified the funding tx
    }
    val fundingStatus = ConfirmedFundingTx(w.tx, d.commitments.localFundingSigs(w.tx.txid), d.commitments.liquidityPurchase(w.tx.txid))
    context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, w.tx))
    // When a splice transaction confirms, it double-spends all the commitment transactions that only applied to the
    // previous funding transaction. Our peer cannot publish the corresponding revoked commitments anymore, so we can
    // clean-up the htlc data that we were storing for the matching penalty transactions.
    d.commitments.all.find(_.fundingTxId == w.tx.txid).map(_.firstRemoteCommitIndex).foreach {
      commitIndex => context.system.eventStream.publish(RevokedHtlcInfoCleaner.ForgetHtlcInfos(d.channelId, beforeCommitIndex = commitIndex))
    }
    d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus).map {
      case (commitments1, commitment) =>
        // First of all, we watch the funding tx that is now confirmed.
        // Children splice transactions may already spend that confirmed funding transaction.
        val spliceSpendingTxs = commitments1.all.collect { case c if c.fundingTxIndex == commitment.fundingTxIndex + 1 => c.fundingTxId }
        watchFundingSpent(commitment, additionalKnownSpendingTxs = spliceSpendingTxs.toSet, None)
        // in the dual-funding case we can forget all other transactions, they have been double spent by the tx that just confirmed
        rollbackDualFundingTxs(d.commitments.active // note how we use the unpruned original commitments
          .filter(c => c.fundingTxIndex == commitment.fundingTxIndex && c.fundingTxId != commitment.fundingTxId)
          .map(_.localFundingStatus).collect { case fundingTx: DualFundedUnconfirmedFundingTx => fundingTx.sharedTx })
        (commitments1, commitment)
    }
  }

  def createShortIds(channelId: ByteVector32, realScidStatus: RealScidStatus): ShortIds = {
    // the alias will use in our peer's channel_update message, the goal is to be able to use our channel as soon
    // as it reaches NORMAL state, and before it is announced on the network
    val shortIds = ShortIds(realScidStatus, ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
    context.system.eventStream.publish(ShortChannelIdAssigned(self, channelId, shortIds, remoteNodeId))
    shortIds
  }

  def createChannelReady(shortIds: ShortIds, params: ChannelParams): ChannelReady = {
    val channelKeyPath = keyManager.keyPath(params.localParams, params.channelConfig)
    val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
    val tlvStream: TlvStream[ChannelReadyTlv] = params.commitmentFormat match {
      case SimpleTaprootChannelsStagingCommitmentFormat =>
        // TODO: fundingTxIndex = 0 ?
        val (_, nextLocalNonce) = keyManager.verificationNonce(params.localParams.fundingKeyPath, fundingTxIndex = 0, channelKeyPath, 1)
        TlvStream(ChannelReadyTlv.ShortChannelIdTlv(shortIds.localAlias), ChannelTlv.NextLocalNonceTlv(nextLocalNonce))
      case _ =>
        TlvStream(ChannelReadyTlv.ShortChannelIdTlv(shortIds.localAlias))
    }
    // we always send our local alias, even if it isn't explicitly supported, that's an optional TLV anyway
    ChannelReady(params.channelId, nextPerCommitmentPoint, tlvStream)
  }

  def receiveChannelReady(shortIds: ShortIds, channelReady: ChannelReady, commitments: Commitments): DATA_NORMAL = {
    val shortIds1 = shortIds.copy(remoteAlias_opt = channelReady.alias_opt)
    shortIds1.remoteAlias_opt.foreach(_ => context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, shortIds = shortIds1, remoteNodeId = remoteNodeId)))
    log.info("shortIds: real={} localAlias={} remoteAlias={}", shortIds1.real.toOption.getOrElse("none"), shortIds1.localAlias, shortIds1.remoteAlias_opt.getOrElse("none"))
    // we notify that the channel is now ready to route payments
    context.system.eventStream.publish(ChannelOpened(self, remoteNodeId, commitments.channelId))
    // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
    val scidForChannelUpdate = Helpers.scidForChannelUpdate(channelAnnouncement_opt = None, shortIds1.localAlias)
    log.info("using shortChannelId={} for initial channel_update", scidForChannelUpdate)
    val relayFees = getRelayFees(nodeParams, remoteNodeId, commitments.announceChannel)
    val initialChannelUpdate = Helpers.makeChannelUpdate(nodeParams, remoteNodeId, scidForChannelUpdate, commitments, relayFees)
    // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
    context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
    // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
    blockchain ! WatchFundingDeeplyBuried(self, commitments.latest.fundingTxId, ANNOUNCEMENTS_MINCONF)
    val commitments1 = commitments.modify(_.remoteNextCommitInfo).setTo(Right(channelReady.nextPerCommitmentPoint))
    this.remoteNextLocalNonce_opt = channelReady.nexLocalNonce_opt // TODO: this is wrong, there should be a different nonce for each commitment
    peer ! ChannelReadyForPayments(self, remoteNodeId, commitments.channelId, fundingTxIndex = 0)
    DATA_NORMAL(commitments1, shortIds1, None, initialChannelUpdate, None, None, None, SpliceStatus.NoSplice)
  }

  def delayEarlyAnnouncementSigs(remoteAnnSigs: AnnouncementSignatures): Unit = {
    log.debug("received remote announcement signatures, delaying")
    // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
    // note: no need to persist their message, in case of disconnection they will resend it
    context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
  }

}
