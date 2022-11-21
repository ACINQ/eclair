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
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingDeeplyBuried, WatchFundingSpent}
import fr.acinq.eclair.channel.Helpers.getRelayFees
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{ANNOUNCEMENTS_MINCONF, BroadcastChannelUpdate, PeriodicRefresh, REFRESH_CHANNEL_UPDATE_INTERVAL}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelReady, ChannelReadyTlv, TlvStream}

import scala.concurrent.duration.DurationInt

/**
 * Created by t-bast on 18/08/2022.
 */

trait CommonFundingHandlers extends CommonHandlers {

  this: Channel =>

  def watchFundingTx(commitments: Commitments, additionalKnownSpendingTxs: Set[ByteVector32] = Set.empty): Unit = {
    val knownSpendingTxs = Set(commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid, commitments.remoteCommit.txid) ++ commitments.remoteNextCommitInfo.left.toSeq.map(_.nextRemoteCommit.txid).toSet ++ additionalKnownSpendingTxs
    blockchain ! WatchFundingSpent(self, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, knownSpendingTxs)
  }

  def createShortIds(channelId: ByteVector32, realScidStatus: RealScidStatus): ShortIds = {
    // the alias will use in our peer's channel_update message, the goal is to be able to use our channel as soon
    // as it reaches NORMAL state, and before it is announced on the network
    val shortIds = ShortIds(realScidStatus, ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
    context.system.eventStream.publish(ShortChannelIdAssigned(self, channelId, shortIds, remoteNodeId))
    shortIds
  }

  def createChannelReady(shortIds: ShortIds, commitments: Commitments): ChannelReady = {
    val channelKeyPath = keyManager.keyPath(commitments.localParams, commitments.channelConfig)
    val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
    // we always send our local alias, even if it isn't explicitly supported, that's an optional TLV anyway
    ChannelReady(commitments.channelId, nextPerCommitmentPoint, TlvStream(ChannelReadyTlv.ShortChannelIdTlv(shortIds.localAlias)))
  }

  def receiveChannelReady(shortIds: ShortIds, channelReady: ChannelReady, metaCommitments: MetaCommitments): DATA_NORMAL = {
    val commitments = metaCommitments.main
    val shortIds1 = shortIds.copy(remoteAlias_opt = channelReady.alias_opt)
    shortIds1.remoteAlias_opt.foreach(_ => context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, shortIds = shortIds1, remoteNodeId = remoteNodeId)))
    log.info("shortIds: real={} localAlias={} remoteAlias={}", shortIds1.real.toOption.getOrElse("none"), shortIds1.localAlias, shortIds1.remoteAlias_opt.getOrElse("none"))
    // we notify that the channel is now ready to route payments
    context.system.eventStream.publish(ChannelOpened(self, remoteNodeId, commitments.channelId))
    // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
    val scidForChannelUpdate = Helpers.scidForChannelUpdate(channelAnnouncement_opt = None, shortIds1.localAlias)
    log.info("using shortChannelId={} for initial channel_update", scidForChannelUpdate)
    val relayFees = getRelayFees(nodeParams, remoteNodeId, commitments)
    val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate, nodeParams.channelConf.expiryDelta, commitments.remoteParams.htlcMinimum, relayFees.feeBase, relayFees.feeProportionalMillionths, commitments.maxHtlcAmount, isPrivate = !commitments.announceChannel, enable = Helpers.aboveReserve(commitments))
    // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
    context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
    // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
    blockchain ! WatchFundingDeeplyBuried(self, commitments.fundingTxId, ANNOUNCEMENTS_MINCONF)
    DATA_NORMAL(metaCommitments.copy(all = commitments.copy(remoteNextCommitInfo = Right(channelReady.nextPerCommitmentPoint)) +: Nil), shortIds1, None, initialChannelUpdate, None, None, None)
  }

  def delayEarlyAnnouncementSigs(remoteAnnSigs: AnnouncementSignatures): Unit = {
    log.debug("received remote announcement signatures, delaying")
    // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
    // note: no need to persist their message, in case of disconnection they will resend it
    context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
  }

}
