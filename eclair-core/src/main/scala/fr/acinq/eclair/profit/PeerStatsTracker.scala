/*
 * Copyright 2026 ACINQ SAS
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

package fr.acinq.eclair.profit

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{Features, MilliSatoshi, MilliSatoshiLong, TimestampMilli, ToMilliSatoshiConversion}

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Created by t-bast on 30/01/2026.
 */

object PeerStatsTracker {
  // @formatter:off
  sealed trait Command
  case class GetLatestStats(replyTo: ActorRef[LatestStats]) extends Command
  private[profit] case object RemoveOldBuckets extends Command
  private[profit] case class WrappedPaymentSent(e: PaymentSent) extends Command
  private[profit] case class WrappedPaymentRelayed(e: PaymentRelayed) extends Command
  private[profit] case class WrappedPaymentReceived(e: PaymentReceived) extends Command
  private[profit] case class ChannelCreationInProgress(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  private[profit] case class ChannelCreationAborted(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  private[profit] case class WrappedLocalChannelUpdate(e: LocalChannelUpdate) extends Command
  private[profit] case class WrappedLocalChannelDown(e: LocalChannelDown) extends Command
  private[profit] case class WrappedAvailableBalanceChanged(e: AvailableBalanceChanged) extends Command
  // @formatter:on

  def apply(db: AuditDb, channels: Seq[PersistentChannelData]): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new PeerStatsTracker(db, timers, context).start(channels)
      }
    }
  }

  /** Returns the latest statistics for all of our public peers. */
  case class LatestStats(peers: Seq[PeerInfo])

  /** NB: stats are ordered, with the most recent events first. */
  case class PeerInfo(remoteNodeId: PublicKey, stats: Seq[PeerStats], channels: Seq[ChannelInfo], latestUpdate_opt: Option[ChannelUpdate], hasPendingChannel: Boolean) {
    val capacity: Satoshi = channels.map(_.capacity).sum
    val canSend: MilliSatoshi = channels.map(_.canSend).sum
    val canReceive: MilliSatoshi = channels.map(_.canReceive).sum
  }

  /** We compute peer statistics per buckets spanning a few hours. */
  case class Bucket(private val day: Int, private val month: Int, private val year: Int, private val slot: Int) extends Ordered[Bucket] {
    override def compare(that: Bucket): Int = that match {
      case Bucket(_, _, y, _) if y != year => year - y
      case Bucket(_, m, _, _) if m != month => month - m
      case Bucket(d, _, _, _) if d != day => day - d
      case Bucket(_, _, _, s) => slot - s
    }

    override def toString: String = f"$year-$month%02d-$day%02d-$slot"
  }

  object Bucket {
    val duration: FiniteDuration = 3 hours
    val bucketsPerDay: Int = 8

    def from(ts: TimestampMilli): Bucket = {
      val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts.toLong), ZoneId.of("UTC"))
      Bucket(date.getDayOfMonth, date.getMonthValue, date.getYear, date.getHour * bucketsPerDay / 24)
    }

    /** Returns the percentage (between 0.00 and 1.00) of the bucket consumed at the given timestamp. */
    def consumed(ts: TimestampMilli): Double = {
      val bucket = from(ts)
      val start = ZonedDateTime.of(bucket.year, bucket.month, bucket.day, bucket.slot * 24 / bucketsPerDay, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond
      (ts.toTimestampSecond.toLong - start).toDouble / duration.toSeconds
    }
  }

  case class PeerStats(totalAmountIn: MilliSatoshi, totalAmountOut: MilliSatoshi, relayFeeEarned: MilliSatoshi, onChainFeePaid: Satoshi, liquidityFeeEarned: Satoshi, liquidityFeePaid: Satoshi) {
    val outgoingFlow: MilliSatoshi = totalAmountOut - totalAmountIn
    val profit: MilliSatoshi = relayFeeEarned + liquidityFeeEarned.toMilliSatoshi - onChainFeePaid.toMilliSatoshi - liquidityFeePaid.toMilliSatoshi
  }

  object PeerStats {
    def empty: PeerStats = PeerStats(0 msat, 0 msat, 0 msat, 0 sat, 0 sat, 0 sat)
  }

  /**
   * We aggregate events into buckets to avoid storing too much data per peer, while providing enough granularity to
   * detect variations in volume and flows. Note that we store an entry for every peer with whom we have a channel,
   * even for peers that don't have any activity (which lets us detect those peers and potentially reclaim liquidity).
   */
  case class BucketedPeerStats(private val stats: Map[PublicKey, Map[Bucket, PeerStats]]) {
    def peers: Iterable[PublicKey] = stats.keys

    /** Returns stats for the given peer in all buckets (most recent bucket first). */
    def getPeerStats(remoteNodeId: PublicKey, now: TimestampMilli): Seq[PeerStats] = {
      stats.get(remoteNodeId) match {
        case Some(_) => (0 until BucketedPeerStats.bucketsCount).map(b => getPeerStatsForBucket(remoteNodeId, Bucket.from(now - b * Bucket.duration)))
        case None => Seq.fill(BucketedPeerStats.bucketsCount)(PeerStats.empty)
      }
    }

    /** Returns stats for the given bucket. */
    private def getPeerStatsForBucket(remoteNodeId: PublicKey, bucket: Bucket): PeerStats = {
      stats.get(remoteNodeId).flatMap(_.get(bucket)).getOrElse(PeerStats.empty)
    }

    /**
     * When our first channel with a peer is created, we want to add an entry for it, even though there are no payments yet.
     * This lets us detect new peers that are idle, which can be useful in many scenarios.
     */
    def initializePeerIfNeeded(remoteNodeId: PublicKey): BucketedPeerStats = {
      stats.get(remoteNodeId) match {
        case Some(_) => this // already initialized
        case None => this.copy(stats = stats + (remoteNodeId -> Map.empty))
      }
    }

    private def addOrUpdate(remoteNodeId: PublicKey, bucket: Bucket, peerStats: PeerStats): BucketedPeerStats = {
      val buckets = stats.getOrElse(remoteNodeId, Map.empty[Bucket, PeerStats])
      copy(stats = stats + (remoteNodeId -> (buckets + (bucket -> peerStats))))
    }

    def addPaymentSent(e: PaymentSent): BucketedPeerStats = {
      e.parts.foldLeft(this) {
        case (current, p) =>
          val bucket = Bucket.from(p.settledAt)
          val peerStats = current.getPeerStatsForBucket(p.remoteNodeId, bucket)
          val peerStats1 = peerStats.copy(totalAmountOut = peerStats.totalAmountOut + p.amountWithFees)
          current.addOrUpdate(p.remoteNodeId, bucket, peerStats1)
      }
    }

    def addPaymentReceived(e: PaymentReceived): BucketedPeerStats = {
      e.parts.foldLeft(this) {
        case (current, p) =>
          val bucket = Bucket.from(p.receivedAt)
          val peerStats = current.getPeerStatsForBucket(p.remoteNodeId, bucket)
          val peerStats1 = peerStats.copy(totalAmountIn = peerStats.totalAmountIn + p.amount)
          current.addOrUpdate(p.remoteNodeId, bucket, peerStats1)
      }
    }

    def addPaymentRelayed(e: PaymentRelayed): BucketedPeerStats = {
      val withIncoming = e.incoming.foldLeft(this) {
        case (current, i) =>
          val bucket = Bucket.from(i.receivedAt)
          val peerStats = current.getPeerStatsForBucket(i.remoteNodeId, bucket)
          val peerStats1 = peerStats.copy(totalAmountIn = peerStats.totalAmountIn + i.amount)
          current.addOrUpdate(i.remoteNodeId, bucket, peerStats1)
      }
      e.outgoing.foldLeft(withIncoming) {
        case (current, o) =>
          val bucket = Bucket.from(o.settledAt)
          val peerStats = current.getPeerStatsForBucket(o.remoteNodeId, bucket)
          // When using MPP and trampoline, payments can be relayed through multiple nodes at once.
          // We split the fee according to the proportional amount relayed through the requested node.
          val relayFee = e.relayFee * (o.amount.toLong.toDouble / e.amountOut.toLong)
          val peerStats1 = peerStats.copy(totalAmountOut = peerStats.totalAmountOut + o.amount, relayFeeEarned = peerStats.relayFeeEarned + relayFee)
          current.addOrUpdate(o.remoteNodeId, bucket, peerStats1)
      }
    }

    /** Remove old buckets that exceed our retention window. This should be called frequently to avoid memory leaks. */
    def removeOldBuckets(now: TimestampMilli): BucketedPeerStats = {
      val oldestBucket = Bucket.from(now - Bucket.duration * BucketedPeerStats.bucketsCount)
      copy(stats = stats.map {
        case (remoteNodeId, peerStats) => remoteNodeId -> peerStats.filter { case (bucket, _) => bucket >= oldestBucket }
      })
    }

    /** Remove a peer from our list: this should only happen when we don't have channels with that peer anymore. */
    def removePeer(remoteNodeId: PublicKey): BucketedPeerStats = copy(stats = stats - remoteNodeId)
  }

  object BucketedPeerStats {
    // We keep 7 days of past history.
    val bucketsCount: Int = 7 * Bucket.bucketsPerDay

    def empty(peers: Set[PublicKey]): BucketedPeerStats = BucketedPeerStats(peers.map(remoteNodeId => remoteNodeId -> Map.empty[Bucket, PeerStats]).toMap)
  }

  /** We keep minimal information about open channels to allow running our heuristics. */
  case class ChannelInfo(channelId: ByteVector32, capacity: Satoshi, canSend: MilliSatoshi, canReceive: MilliSatoshi, isPublic: Boolean)

  object ChannelInfo {
    def apply(commitments: Commitments): ChannelInfo = ChannelInfo(commitments.channelId, commitments.latest.capacity, commitments.availableBalanceForSend, commitments.availableBalanceForReceive, commitments.announceChannel)
  }

  /**
   * Note that we keep channel updates separately: we're only interested in the relay fees, which are the same for every channel.
   * We also keep track of pending channels (channels being created), to avoid creating many channels at the same time.
   */
  case class PeerChannels(private val channels: Map[PublicKey, Seq[ChannelInfo]], private val updates: Map[PublicKey, ChannelUpdate], private val pending: Map[PublicKey, Set[ByteVector32]]) {
    def peers: Set[PublicKey] = channels.keySet

    def getChannels(remoteNodeId: PublicKey): Seq[ChannelInfo] = channels.getOrElse(remoteNodeId, Nil)

    def getUpdate(remoteNodeId: PublicKey): Option[ChannelUpdate] = updates.get(remoteNodeId)

    def hasChannels(remoteNodeId: PublicKey): Boolean = channels.contains(remoteNodeId)

    def hasPendingChannel(remoteNodeId: PublicKey): Boolean = pending.contains(remoteNodeId)

    def updateChannel(e: LocalChannelUpdate): PeerChannels = {
      // Note that creating our channel update implicitly means that the channel isn't pending anymore.
      updates.get(e.remoteNodeId) match {
        case Some(u) if u.timestamp > e.channelUpdate.timestamp => updateChannel(e.commitments).removePendingChannel(e.remoteNodeId, e.channelId)
        case _ => updateChannel(e.commitments).copy(updates = updates + (e.remoteNodeId -> e.channelUpdate)).removePendingChannel(e.remoteNodeId, e.channelId)
      }
    }

    def updateChannel(e: AvailableBalanceChanged): PeerChannels = {
      updateChannel(e.commitments)
    }

    private def updateChannel(commitments: Commitments): PeerChannels = {
      if (!commitments.channelParams.channelFeatures.hasFeature(Features.PhoenixZeroReserve)) {
        val peerChannels1 = channels.getOrElse(commitments.remoteNodeId, Nil).filter(_.channelId != commitments.channelId) :+ ChannelInfo(commitments)
        copy(channels = channels + (commitments.remoteNodeId -> peerChannels1))
      } else {
        // We filter out channels with mobile wallets.
        copy(channels = channels - commitments.remoteNodeId)
      }
    }

    def addPendingChannel(e: ChannelCreationInProgress): PeerChannels = {
      val pending1 = pending + (e.remoteNodeId -> (pending.getOrElse(e.remoteNodeId, Set.empty) + e.channelId))
      copy(pending = pending1)
    }

    def removeChannel(e: LocalChannelDown): PeerChannels = {
      val updated = channels.get(e.remoteNodeId) match {
        case Some(peerChannels) =>
          val peerChannels1 = peerChannels.filter(_.channelId != e.channelId)
          if (peerChannels1.isEmpty) {
            copy(channels = channels - e.remoteNodeId, updates = updates - e.remoteNodeId)
          } else {
            copy(channels = channels + (e.remoteNodeId -> peerChannels1))
          }
        case None => this
      }
      updated.removePendingChannel(e.remoteNodeId, e.channelId)
    }

    private def removePendingChannel(remoteNodeId: PublicKey, channelId: ByteVector32): PeerChannels = {
      val pending1 = pending.get(remoteNodeId) match {
        case Some(channels) =>
          val channels1 = channels - channelId
          if (channels1.isEmpty) {
            pending - remoteNodeId
          } else {
            pending + (remoteNodeId -> channels1)
          }
        case None => pending
      }
      copy(pending = pending1)
    }

    def removeChannel(e: ChannelCreationAborted): PeerChannels = {
      removePendingChannel(e.remoteNodeId, e.channelId)
    }
  }

  private object PeerChannels {
    def apply(channels: Seq[PersistentChannelData]): PeerChannels = {
      channels.foldLeft(PeerChannels(Map.empty[PublicKey, Seq[ChannelInfo]], Map.empty[PublicKey, ChannelUpdate], Map.empty[PublicKey, Set[ByteVector32]])) {
        case (current, channel) => channel match {
          // We include pending channels.
          case _: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED | _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED | _: DATA_WAIT_FOR_DUAL_FUNDING_READY | _: DATA_WAIT_FOR_FUNDING_CONFIRMED | _: DATA_WAIT_FOR_CHANNEL_READY if !channel.channelParams.channelFeatures.hasFeature(Features.PhoenixZeroReserve) =>
            val pending1 = current.pending.getOrElse(channel.remoteNodeId, Set.empty) + channel.channelId
            current.copy(pending = current.pending + (channel.remoteNodeId -> pending1))
          // We filter out channels with mobile wallets.
          case d: DATA_NORMAL if !d.commitments.channelParams.channelFeatures.hasFeature(Features.PhoenixZeroReserve) =>
            val peerChannels1 = current.channels.getOrElse(d.remoteNodeId, Nil) :+ ChannelInfo(d.commitments)
            val update1 = current.updates.get(d.remoteNodeId) match {
              case Some(update) if update.timestamp > d.channelUpdate.timestamp => update
              case _ => d.channelUpdate
            }
            current.copy(
              channels = current.channels + (d.remoteNodeId -> peerChannels1),
              updates = current.updates + (d.remoteNodeId -> update1)
            )
          case _ => current
        }
      }
    }
  }

}

private class PeerStatsTracker(db: AuditDb, timers: TimerScheduler[PeerStatsTracker.Command], context: ActorContext[PeerStatsTracker.Command]) {

  import PeerStatsTracker._

  private val log = context.log

  private def start(channels: Seq[PersistentChannelData]): Behavior[Command] = {
    // We subscribe to channel events to update channel balances.
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelIdAssigned](e => ChannelCreationInProgress(e.remoteNodeId, e.channelId)))
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelAborted](e => ChannelCreationAborted(e.remoteNodeId, e.channelId)))
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedLocalChannelDown))
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedLocalChannelUpdate))
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedAvailableBalanceChanged))
    val peerChannels = PeerChannels(channels)
    log.info("gathering statistics for {} peers", peerChannels.peers.size)
    // We subscribe to payment events to update statistics.
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedPaymentSent))
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedPaymentRelayed))
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedPaymentReceived))
    // TODO: read events that happened before startedAt from the DB to initialize statistics from past data.
    val stats = BucketedPeerStats.empty(peerChannels.peers)
    timers.startTimerWithFixedDelay(RemoveOldBuckets, Bucket.duration)
    listening(stats, peerChannels)
  }

  private def listening(stats: BucketedPeerStats, channels: PeerChannels): Behavior[Command] = {
    Behaviors.receiveMessage {
      case WrappedPaymentSent(e) =>
        listening(stats.addPaymentSent(e), channels)
      case WrappedPaymentReceived(e) =>
        listening(stats.addPaymentReceived(e), channels)
      case WrappedPaymentRelayed(e) =>
        listening(stats.addPaymentRelayed(e), channels)
      case e: ChannelCreationInProgress =>
        listening(stats, channels.addPendingChannel(e))
      case e: ChannelCreationAborted =>
        listening(stats, channels.removeChannel(e))
      case WrappedLocalChannelUpdate(e) =>
        listening(stats.initializePeerIfNeeded(e.remoteNodeId), channels.updateChannel(e))
      case WrappedAvailableBalanceChanged(e) =>
        listening(stats, channels.updateChannel(e))
      case WrappedLocalChannelDown(e) =>
        val channels1 = channels.removeChannel(e)
        val stats1 = if (channels1.getChannels(e.remoteNodeId).isEmpty && !channels1.hasPendingChannel(e.remoteNodeId)) {
          stats.removePeer(e.remoteNodeId)
        } else {
          stats
        }
        listening(stats1, channels1)
      case RemoveOldBuckets =>
        listening(stats.removeOldBuckets(TimestampMilli.now()), channels)
      case GetLatestStats(replyTo) =>
        // TODO: do a db.listConfirmed() to update on-chain stats (we cannot rely on events only because data comes from
        //  the TransactionPublished event, but should only be applied after TransactionConfirmed so we need permanent
        //  storage). We'll need the listConfirmed() function added in https://github.com/ACINQ/eclair/pull/3245.
        log.debug("statistics available for {} peers", stats.peers.size)
        val now = TimestampMilli.now()
        val latest = stats.peers
          // We only return statistics for peers with whom we have channels available for payments.
          .filter(nodeId => channels.hasChannels(nodeId))
          .map(nodeId => PeerInfo(nodeId, stats.getPeerStats(nodeId, now), channels.getChannels(nodeId), channels.getUpdate(nodeId), channels.hasPendingChannel(nodeId)))
          .toSeq
        replyTo ! LatestStats(latest)
        listening(stats, channels)
    }
  }

}
