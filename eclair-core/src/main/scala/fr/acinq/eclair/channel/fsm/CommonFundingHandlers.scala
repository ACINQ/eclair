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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.getRelayFees
import fr.acinq.eclair.channel.LocalFundingStatus.{ConfirmedFundingTx, DualFundedUnconfirmedFundingTx, SingleFundedUnconfirmedFundingTx}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{ANNOUNCEMENTS_MINCONF, BroadcastChannelUpdate, PeriodicRefresh, REFRESH_CHANNEL_UPDATE_INTERVAL}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelReady, ChannelReadyTlv, TlvStream}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 18/08/2022.
 */

trait CommonFundingHandlers extends CommonHandlers {

  this: Channel =>

  def watchFundingSpent(commitment: Commitment, additionalKnownSpendingTxs: Set[ByteVector32] = Set.empty): Unit = {
    val knownSpendingTxs = Set(commitment.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitment.remoteCommit.txid) ++ commitment.nextRemoteCommit_opt.map(_.commit.txid).toSet ++ additionalKnownSpendingTxs
    blockchain ! WatchFundingSpent(self, commitment.commitInput.outPoint.txid, commitment.commitInput.outPoint.index.toInt, knownSpendingTxs)
  }

  def watchFundingConfirmed(fundingTxId: ByteVector32, minDepth_opt: Option[Long]): Unit = {
    minDepth_opt match {
      case Some(fundingMinDepth) => blockchain ! WatchFundingConfirmed(self, fundingTxId, fundingMinDepth)
      // When using 0-conf, we make sure that the transaction was successfully published, otherwise there is a risk
      // of accidentally double-spending it later (e.g. restarting bitcoind would remove the utxo locks).
      case None => blockchain ! WatchPublished(self, fundingTxId)
    }
  }

  def acceptFundingTxConfirmed(w: WatchFundingConfirmedTriggered, d: PersistentChannelData): Either[Commitments, (Commitments, Commitment)] = {
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
    val fundingStatus = ConfirmedFundingTx(w.tx)
    context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, w.tx))
    d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus).map {
      case (commitments1, commitment) =>
        require(commitments1.active.size == 1 && commitment.fundingTxId == w.tx.txid, "there must be exactly one commitment after an initial funding tx is confirmed")
        // first of all, we watch the funding tx that is now confirmed
        watchFundingSpent(commitment)
        // in the dual-funding case we can forget all other transactions, they have been double spent by the tx that just confirmed
        val otherFundingTxs = d.commitments.active // note how we use the unpruned original commitments
          .filter(c => c.fundingTxId != commitment.fundingTxId)
          .map(_.localFundingStatus).collect { case fundingTx: DualFundedUnconfirmedFundingTx => fundingTx.sharedTx }
        rollbackDualFundingTxs(otherFundingTxs)
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
    // we always send our local alias, even if it isn't explicitly supported, that's an optional TLV anyway
    ChannelReady(params.channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(shortIds.localAlias)))
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
    val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate, nodeParams.channelConf.expiryDelta, commitments.params.remoteParams.htlcMinimum, relayFees.feeBase, relayFees.feeProportionalMillionths, commitments.params.maxHtlcAmount, isPrivate = !commitments.announceChannel, enable = Helpers.aboveReserve(commitments))
    // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
    context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
    // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
    blockchain ! WatchFundingDeeplyBuried(self, commitments.latest.fundingTxId, ANNOUNCEMENTS_MINCONF)
    val commitments1 = commitments
      .modify(_.remoteNextCommitInfo).setTo(Right(channelReady.nextPerCommitmentPoint))
      .modify(_.active.at(0).remoteFundingStatus).setTo(RemoteFundingStatus.Locked)
    DATA_NORMAL(commitments1, shortIds1, None, initialChannelUpdate, None, None, None)
  }

  def delayEarlyAnnouncementSigs(remoteAnnSigs: AnnouncementSignatures): Unit = {
    log.debug("received remote announcement signatures, delaying")
    // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
    // note: no need to persist their message, in case of disconnection they will resend it
    context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
  }

}
