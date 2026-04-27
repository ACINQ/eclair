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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.{ActorRef => UntypedActorRef}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.OnChainBalanceChecker
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{CMD_CLOSE, CMD_UPDATE_RELAY_FEE, ChannelFlags, Register}
import fr.acinq.eclair.io.Peer.OpenChannel
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.profit.PeerStatsTracker._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, NodeParams, TimestampMilli, TimestampSecond}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Random, Success}

/**
 * Created by t-bast on 30/01/2026.
 */

object PeerScorer {

  // @formatter:off
  sealed trait Command
  case class ScorePeers(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]]) extends Command
  case class UpdateConfig(replyTo: ActorRef[Boolean], cfg: ConfigOverrides) extends Command
  private case class WrappedLatestStats(peers: Seq[PeerInfo]) extends Command
  private case class OnChainBalance(confirmed: Satoshi, unconfirmed: Satoshi) extends Command
  private case class WalletError(e: Throwable) extends Command
  // @formatter:on

  /**
   * @param enabled           the [[PeerStatsTracker]] actor should only be created when [[enabled]] is true.
   * @param scoringFrequency  frequency at which we run our peer scoring algorithm.
   * @param topPeersCount     maximum number of peers to select as candidates for liquidity and relay fee updates.
   * @param topPeersWhitelist a whitelist of peers that the node operator wants to prioritize.
   */
  case class Config(enabled: Boolean,
                    scoringFrequency: FiniteDuration,
                    topPeersCount: Int,
                    topPeersWhitelist: Set[PublicKey],
                    liquidity: LiquidityConfig,
                    relayFees: RelayFeesConfig)

  /**
   * @param autoFund                      if true, we will automatically fund channels.
   * @param autoClose                     if true, we will automatically close unused channels to reclaim liquidity.
   * @param minFundingAmount              we always fund channels with at least this amount.
   * @param maxFundingAmount              we never fund channels with more than this amount.
   * @param maxPerPeerCapacity            maximum total capacity (across all channels) per peer.
   * @param maxFundingTxPerDay            we rate-limit the number of transactions we make per day (on average).
   * @param localBalanceClosingThreshold  we won't close channels if our local balance is below this amount.
   * @param remoteBalanceClosingThreshold we won't close channels where the remote balance exceeds this amount.
   * @param minOnChainBalance             we stop funding channels if our on-chain balance is below this amount.
   * @param maxFeerate                    we stop funding channels if the on-chain feerate is above this value.
   * @param fundingCooldown               minimum time between funding the same peer, to evaluate effectiveness.
   */
  case class LiquidityConfig(autoFund: Boolean,
                             autoClose: Boolean,
                             minFundingAmount: Satoshi,
                             maxFundingAmount: Satoshi,
                             maxPerPeerCapacity: Satoshi,
                             maxFundingTxPerDay: Int,
                             localBalanceClosingThreshold: Satoshi,
                             remoteBalanceClosingThreshold: Satoshi,
                             minOnChainBalance: Satoshi,
                             maxFeerate: FeeratePerKw,
                             fundingCooldown: FiniteDuration)

  /**
   * @param autoUpdate                         if true, we will automatically update our relay fees.
   * @param minRelayFees                       we will not lower our relay fees below this value.
   * @param maxRelayFees                       we will not raise our relay fees above this value.
   * @param dailyPaymentVolumeThreshold        we only increase fees if the daily outgoing payment volume exceeds this threshold or [[dailyPaymentVolumeThresholdPercent]].
   * @param dailyPaymentVolumeThresholdPercent we only increase fees if the daily outgoing payment volume exceeds this percentage of our peer capacity or [[dailyPaymentVolumeThreshold]].
   */
  case class RelayFeesConfig(autoUpdate: Boolean,
                             minRelayFees: RelayFees,
                             maxRelayFees: RelayFees,
                             dailyPaymentVolumeThreshold: Satoshi,
                             dailyPaymentVolumeThresholdPercent: Double)

  case class ConfigOverrides(autoFundOverride_opt: Option[Boolean],
                             autoCloseOverride_opt: Option[Boolean],
                             autoUpdateFeesOverride_opt: Option[Boolean],
                             addWhiteListedPeers: Set[PublicKey],
                             removeWhiteListedPeers: Set[PublicKey],
                             minFundingAmountOverride_opt: Option[Satoshi],
                             maxFundingAmountOverride_opt: Option[Satoshi],
                             maxPerPeerCapacityOverride_opt: Option[Satoshi],
                             maxFundingTxPerDayOverride_opt: Option[Int],
                             localBalanceClosingThresholdOverride_opt: Option[Satoshi],
                             remoteBalanceClosingThresholdOverride_opt: Option[Satoshi],
                             minOnChainBalanceOverride_opt: Option[Satoshi],
                             maxFeerateOverride_opt: Option[FeeratePerKw],
                             fundingCooldownOverride_opt: Option[FiniteDuration])

  private case class FundingProposal(peer: PeerInfo, fundingAmount: Satoshi) {
    val remoteNodeId: PublicKey = peer.remoteNodeId
  }

  private case class FundingDecision(fundingAmount: Satoshi, dailyVolumeOutAtFunding: MilliSatoshi, timestamp: TimestampMilli)

  // @formatter:off
  private sealed trait FeeDirection
  private case object FeeIncrease extends FeeDirection { override def toString: String = "increase" }
  private case object FeeDecrease extends FeeDirection { override def toString: String = "decrease" }
  // @formatter:on

  private case class FeeChangeDecision(direction: FeeDirection, previousFee: RelayFees, newFee: RelayFees, dailyVolumeOutAtChange: MilliSatoshi)

  private case class DecisionHistory(funding: Map[PublicKey, FundingDecision], feeChanges: Map[PublicKey, FeeChangeDecision]) {
    // @formatter:off
    def addFunding(nodeId: PublicKey, record: FundingDecision): DecisionHistory = copy(funding = funding.updated(nodeId, record))
    def addFeeChanges(records: Map[PublicKey, FeeChangeDecision]): DecisionHistory = copy(feeChanges = feeChanges.concat(records))
    def revertFeeChanges(remoteNodeIds: Set[PublicKey]): DecisionHistory = copy(feeChanges = feeChanges -- remoteNodeIds)
    def cleanup(now: TimestampMilli, maxAge: FiniteDuration): DecisionHistory = copy(funding = funding.filter { case (_, r) => now - r.timestamp < maxAge })
    // @formatter:on
  }

  private def rollingDailyStats(p: PeerInfo): Seq[Seq[PeerStats]] = {
    (0 until (p.stats.size - Bucket.bucketsPerDay)).map(i => p.stats.slice(i, i + Bucket.bucketsPerDay))
  }

  private def bestDailyVolumeOut(p: PeerInfo): MilliSatoshi = rollingDailyStats(p).map(s => s.map(_.totalAmountOut).sum).max

  private def bestDailyProfit(p: PeerInfo): MilliSatoshi = rollingDailyStats(p).map(s => s.map(_.profit).sum).max

  private def bestDailyOutgoingFlow(p: PeerInfo): MilliSatoshi = rollingDailyStats(p).map(s => s.map(_.outgoingFlow).sum).max

  def apply(nodeParams: NodeParams, wallet: OnChainBalanceChecker, statsTracker: ActorRef[PeerStatsTracker.GetLatestStats], register: UntypedActorRef): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(ScorePeers(None), nodeParams.peerScoringConfig.scoringFrequency)
        new PeerScorer(nodeParams, wallet, statsTracker, register, context).run(DecisionHistory(Map.empty, Map.empty))
      }
    }
  }

}

private class PeerScorer(nodeParams: NodeParams, wallet: OnChainBalanceChecker, statsTracker: ActorRef[PeerStatsTracker.GetLatestStats], register: UntypedActorRef, context: ActorContext[PeerScorer.Command]) {

  import PeerScorer._

  implicit val ec: ExecutionContext = context.system.executionContext
  private val log = context.log
  private var config = nodeParams.peerScoringConfig

  private def run(history: DecisionHistory): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ScorePeers(replyTo_opt) =>
        statsTracker ! PeerStatsTracker.GetLatestStats(context.messageAdapter[PeerStatsTracker.LatestStats](e => WrappedLatestStats(e.peers)))
        waitForStats(replyTo_opt, history)
      case UpdateConfig(replyTo, cfg) =>
        config = config.copy(
          topPeersWhitelist = config.topPeersWhitelist -- cfg.removeWhiteListedPeers ++ cfg.addWhiteListedPeers,
          liquidity = config.liquidity.copy(
            autoFund = cfg.autoFundOverride_opt.getOrElse(config.liquidity.autoFund),
            autoClose = cfg.autoCloseOverride_opt.getOrElse(config.liquidity.autoClose),
            minFundingAmount = cfg.minFundingAmountOverride_opt.getOrElse(config.liquidity.minFundingAmount),
            maxFundingAmount = cfg.maxFundingAmountOverride_opt.getOrElse(config.liquidity.maxFundingAmount),
            maxPerPeerCapacity = cfg.maxPerPeerCapacityOverride_opt.getOrElse(config.liquidity.maxPerPeerCapacity),
            maxFundingTxPerDay = cfg.maxFundingTxPerDayOverride_opt.getOrElse(config.liquidity.maxFundingTxPerDay),
            localBalanceClosingThreshold = cfg.localBalanceClosingThresholdOverride_opt.getOrElse(config.liquidity.localBalanceClosingThreshold),
            remoteBalanceClosingThreshold = cfg.remoteBalanceClosingThresholdOverride_opt.getOrElse(config.liquidity.remoteBalanceClosingThreshold),
            minOnChainBalance = cfg.minOnChainBalanceOverride_opt.getOrElse(config.liquidity.minOnChainBalance),
            maxFeerate = cfg.maxFeerateOverride_opt.getOrElse(config.liquidity.maxFeerate),
            fundingCooldown = cfg.fundingCooldownOverride_opt.getOrElse(config.liquidity.fundingCooldown),
          ),
          relayFees = config.relayFees.copy(
            autoUpdate = cfg.autoUpdateFeesOverride_opt.getOrElse(config.relayFees.autoUpdate)
          )
        )
        log.info("updated configuration={}", config)
        replyTo ! true
        Behaviors.same
    }
  }

  private def waitForStats(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]], history: DecisionHistory): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedLatestStats(peers) => scorePeers(replyTo_opt, peers, history)
      case UpdateConfig(replyTo, _) =>
        replyTo ! false
        Behaviors.same
    }
  }

  private def scorePeers(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]], peers: Seq[PeerInfo], history: DecisionHistory): Behavior[Command] = {
    log.info("scoring {} peers", peers.size)
    val dailyProfit = peers.map(_.stats.take(Bucket.bucketsPerDay).map(_.profit).sum).sum.truncateToSatoshi.toMilliBtc
    val weeklyProfit = peers.map(_.stats.map(_.profit).sum).sum.truncateToSatoshi.toMilliBtc
    Monitoring.Metrics.DailyProfit.withoutTags().update(dailyProfit.toDouble)
    Monitoring.Metrics.WeeklyProfit.withoutTags().update(weeklyProfit.toDouble)
    Monitoring.Metrics.DailyVolume.withoutTags().update(peers.map(_.dailyVolumeOut).sum.truncateToSatoshi.toMilliBtc.toDouble)
    Monitoring.Metrics.WeeklyVolume.withoutTags().update(peers.map(_.stats.map(_.totalAmountOut).sum).sum.truncateToSatoshi.toMilliBtc.toDouble)
    log.info("rolling daily profit = {} and weekly profit = {}", dailyProfit, weeklyProfit)

    // We select peers that have the largest outgoing payment volume of the past day.
    // This biases towards nodes that already have a large capacity and have liquidity available.
    val bestPeersByVolume = peers
      .map(p => (p, p.dailyVolumeOut))
      .filter(_._2 > 0.msat)
      .sortBy(_._2)(Ordering[MilliSatoshi].reverse)
      .take(config.topPeersCount)
      .map(_._1)
    log.info("top {} peers:", bestPeersByVolume.size)
    printDailyStats(bestPeersByVolume)

    // We select peers that have a low outgoing balance but were previously good peers, by looking at the largest daily
    // outgoing payment volume of the past week, using a rolling daily window. This also biases towards nodes that
    // already have a large capacity, but lets us identify good peers that ran out of liquidity.
    val bestPeersWithoutLiquidityByVolume = peers
      .filter(p => p.canSend <= p.capacity * 0.1)
      .map(p => (p, bestDailyVolumeOut(p)))
      .sortBy(_._2)(Ordering[MilliSatoshi].reverse)
      .take(config.topPeersCount)
      .map(_._1)

    // We identify peers that need additional liquidity.
    val bestPeersThatNeedLiquidity = bestPeersByVolume
      // We select peers that have a large outgoing flow, which means they will run out of liquidity in the next days.
      .filter(p => p.stats.take(Bucket.bucketsPerDay).map(_.outgoingFlow).sum >= p.canSend * 0.2)
      // And peers that have already ran out of liquidity.
      .appendedAll(bestPeersWithoutLiquidityByVolume)
      .distinctBy(_.remoteNodeId)
      // We order them by their best daily profit of the past week.
      .map(p => (p, bestDailyProfit(p)))
      .sortBy(_._2)(Ordering[MilliSatoshi].reverse)
      .take(config.topPeersCount)
      // We fund a multiple of our daily outgoing flow, to ensure we have enough liquidity for a few days.
      .map { case (p, _) => FundingProposal(p, (bestDailyOutgoingFlow(p) * 4).truncateToSatoshi) }
      .filter(_.fundingAmount > 0.sat)
    if (bestPeersThatNeedLiquidity.nonEmpty) {
      log.info("top {} peers that need liquidity:", bestPeersThatNeedLiquidity.size)
      printDailyStats(bestPeersThatNeedLiquidity.map(_.peer))
    }

    // We select peers that are performing well relative to their capacity, which lets us identify good peers that may
    // have a smaller capacity and may deserve a capacity increase.
    val goodSmallPeers = peers
      .map(p => (p, bestDailyVolumeOut(p).truncateToSatoshi.toLong.toDouble / p.capacity.toLong))
      .filter(_._2 > 0)
      .sortBy(_._2)(Ordering[Double].reverse)
      .take(config.topPeersCount)
      // We only keep peers that may run out of liquidity in the next days.
      .filter { case (p, _) => p.stats.take(Bucket.bucketsPerDay).map(_.outgoingFlow).sum >= p.canSend * 0.2 }
      .map(_._1)
      // We'd like to increase their capacity by 50%.
      .map(p => FundingProposal(p, p.capacity * 0.5))
    if (goodSmallPeers.nonEmpty) {
      log.info("we've identified {} smaller peers that perform well relative to their capacity", goodSmallPeers.size)
      printDailyStats(goodSmallPeers.map(_.peer))
    }

    // We try to identify peers that may have ran out of liquidity a long time ago but are interesting for us.
    val peersToRevive = peers
      // We prioritize peers that the node operator whitelisted.
      .filter(p => config.topPeersWhitelist.contains(p.remoteNodeId))
      .sortBy(_.capacity)(Ordering[Satoshi].reverse)
      // And we add peers with a large capacity that have a low balance.
      .appendedAll(peers
        .sortBy(_.capacity)(Ordering[Satoshi].reverse)
        .take(config.topPeersCount)
        .filter(p => p.canSend <= p.capacity * 0.1)
      )
      .distinctBy(_.remoteNodeId)
      // We'd like to increase their capacity by 10% (those peers have a large capacity, so 10% is already a non-negligible amount).
      .map(p => FundingProposal(p, p.capacity * 0.1))

    // Some actions such as opening or closing channels or updating relay fees should only run periodically, not when
    // explicitly requested by a caller (replyTo_opt).
    if (replyTo_opt.isEmpty) {
      closeUnbalancedChannelsIfNeeded(peers)
      closeIdleChannelsIfNeeded(peers)
      val (updatedPeers, history1) = updateRelayFeesIfNeeded(bestPeersByVolume, history)
      val history2 = decreaseIdleChannelsRelayFeesIfNeeded(peers.filterNot(p => updatedPeers.contains(p.remoteNodeId)), history1)
      fundChannelsIfNeeded(bestPeersThatNeedLiquidity, goodSmallPeers, peersToRevive, history2)
    } else {
      replyTo_opt.foreach(_ ! bestPeersThatNeedLiquidity.map(_.peer))
      run(history)
    }
  }

  private def printDailyStats(peers: Seq[PeerInfo]): Unit = {
    log.info("| rank |                               node_id                              |  daily_volume |  daily_profit |      can_send |   can_receive |")
    log.info("|------|--------------------------------------------------------------------|---------------|---------------|---------------|---------------|")
    peers.zipWithIndex.foreach { case (p, i) =>
      val dailyStats = p.stats.take(Bucket.bucketsPerDay)
      val dailyVolume = dailyStats.map(_.totalAmountOut).sum.truncateToSatoshi.toMilliBtc
      val dailyProfit = dailyStats.map(_.profit).sum.truncateToSatoshi.toMilliBtc
      val canSend = p.canSend.truncateToSatoshi.toMilliBtc
      val canReceive = p.canReceive.truncateToSatoshi.toMilliBtc
      log.info(f"| ${i + 1}%4d | ${p.remoteNodeId} | ${dailyVolume.toDouble}%8.2f mbtc | ${dailyProfit.toDouble}%8.2f mbtc | ${canSend.toDouble}%8.2f mbtc | ${canReceive.toDouble}%8.2f mbtc |")
    }
    log.info("|------|--------------------------------------------------------------------|---------------|---------------|---------------|---------------|")
  }

  /** We close channels where most of the liquidity is on our side and isn't actively used. */
  private def closeUnbalancedChannelsIfNeeded(peers: Seq[PeerInfo]): Unit = {
    peers
      // We only close channels when we have more than one.
      .filter(_.channels.size > 1)
      // We only close channels for which most of the liquidity is idle on our side.
      .filter(p => p.canSend >= p.capacity * 0.8 && p.stats.map(_.totalAmountOut).sum <= p.capacity * 0.05)
      .foreach(p => {
        val channels = sortChannelsToClose(p.channels)
        // We keep the best channel and close the others, unless their balance doesn't match our thresholds.
        val toClose = channels.tail
          // We only close channels where we have a high enough balance.
          .filter(c => c.canSend >= config.liquidity.localBalanceClosingThreshold)
          // We only close channels where our peer has a low enough balance.
          .filter(c => c.canReceive <= config.liquidity.remoteBalanceClosingThreshold)
          // We don't close channels that have been opened in the last 3 days.
          .filter(c => c.shortChannelId_opt.forall(scid => scid.blockHeight <= nodeParams.currentBlockHeight - 6 * 24 * 3))
        closeChannels(p.remoteNodeId, toClose)
      })
  }

  /** We close channels where liquidity has been idle for too long with minimal relay fees. */
  private def closeIdleChannelsIfNeeded(peers: Seq[PeerInfo]): Unit = {
    peers
      // We only close channels when we have more than one.
      .filter(_.channels.size > 1)
      // We only close channels for which liquidity is idle.
      .filter(p => p.stats.map(_.totalAmountOut).sum <= p.capacity * 0.05 && p.stats.map(_.totalAmountIn).sum <= p.capacity * 0.05)
      // And relay fees have been minimal for long enough to give a chance for routing to catch up.
      .filter(p => p.latestUpdate_opt.exists(u => u.relayFees.feeProportionalMillionths <= config.relayFees.minRelayFees.feeProportionalMillionths && u.timestamp <= TimestampSecond.now() - 1.day))
      .foreach(p => {
        // We keep the best channel and close the others.
        val toClose = sortChannelsToClose(p.channels).tail
          // We only close channels where we have a high enough balance.
          .filter(c => c.canSend >= config.liquidity.localBalanceClosingThreshold)
          // We don't close channels that have been opened in the last 3 days.
          .filter(c => c.shortChannelId_opt.forall(scid => scid.blockHeight <= nodeParams.currentBlockHeight - 6 * 24 * 3))
        closeChannels(p.remoteNodeId, toClose)
      })
  }

  private def sortChannelsToClose(channels: Seq[ChannelInfo]): Seq[ChannelInfo] = {
    channels.sortWith {
      // We want to keep a public channel over a private channel.
      case (c1, c2) if c1.isPublic != c2.isPublic => c1.isPublic
      // Otherwise, we keep the channel with the largest capacity.
      case (c1, c2) if c1.capacity != c2.capacity => c1.capacity >= c2.capacity
      // Otherwise, we keep the channel with the smallest balance (and thus highest inbound liquidity).
      case (c1, c2) => c1.canSend <= c2.canSend
    }
  }

  private def closeChannels(remoteNodeId: PublicKey, channels: Seq[ChannelInfo]): Unit = {
    channels.foreach { c =>
      if (!config.liquidity.autoClose) {
        log.info("we should close channel_id={} with remote_node_id={} (local={}, remote={})", c.channelId, remoteNodeId, c.canSend.truncateToSatoshi.toMilliBtc, c.canReceive.truncateToSatoshi.toMilliBtc)
      }
      if (config.liquidity.autoClose && nodeParams.currentFeeratesForFundingClosing.medium <= config.liquidity.maxFeerate) {
        log.info("closing channel_id={} with remote_node_id={} (local={}, remote={})", c.channelId, remoteNodeId, c.canSend.truncateToSatoshi.toMilliBtc, c.canReceive.truncateToSatoshi.toMilliBtc)
        val cmd = CMD_CLOSE(UntypedActorRef.noSender, None, None)
        register ! Register.Forward(context.system.ignoreRef, c.channelId, cmd)
      }
    }
  }

  /** We update the relay fees of our top peers to shape volume based on our available liquidity. */
  private def updateRelayFeesIfNeeded(peers: Seq[PeerInfo], history: DecisionHistory): (Set[PublicKey], DecisionHistory) = {
    // We configure *daily* absolute and proportional payment volume targets. We look at events from the current period
    // and the previous period, so we need to get the right ratio to convert those daily amounts.
    val now = TimestampMilli.now()
    val lastTwoBucketsRatio = 1.0 + Bucket.consumed(now)
    val lastTwoBucketsDailyRatio = (Bucket.duration * lastTwoBucketsRatio).toSeconds.toDouble / (24 * 3600)
    // We increase fees of channels that are performing better than we expected.
    val feeIncreases = peers
      // We select peers that have exceeded our payment volume target in the past two periods.
      .filter(p => p.stats.take(2).map(_.totalAmountOut).sum >= Seq(config.relayFees.dailyPaymentVolumeThreshold * lastTwoBucketsDailyRatio, p.capacity * config.relayFees.dailyPaymentVolumeThresholdPercent * lastTwoBucketsDailyRatio).min)
      // And that have an increasing payment volume compared to the period before that.
      .filter(p => p.stats.slice(2, 3).map(_.totalAmountOut).sum > 0.msat)
      .filter(p => (p.stats.take(2).map(_.totalAmountOut).sum / lastTwoBucketsRatio) > p.stats.slice(2, 3).map(_.totalAmountOut).sum * 1.1)
      .flatMap(p => {
        p.latestUpdate_opt match {
          // And for which we haven't updated our relay fees recently already.
          case Some(u) if u.timestamp <= now.toTimestampSecond - (Bucket.duration * 1.5).toSeconds =>
            val next = u.relayFees.copy(feeProportionalMillionths = u.feeProportionalMillionths + 500)
            if (next.feeBase <= config.relayFees.maxRelayFees.feeBase && next.feeProportionalMillionths <= config.relayFees.maxRelayFees.feeProportionalMillionths) {
              Some(p.remoteNodeId -> FeeChangeDecision(FeeIncrease, u.relayFees, next, p.dailyVolumeOut))
            } else {
              None
            }
          case _ => None
        }
      }).toMap
    // We decrease fees of channels that aren't performing well.
    val feeDecreases = peers
      // We select peers that have a recent 15% decrease in outgoing payment volume.
      .filter(p => p.stats.slice(2, 3).map(_.totalAmountOut).sum > 0.msat)
      .filter(p => p.stats.take(2).map(_.totalAmountOut).sum <= p.stats.slice(2, 3).map(_.totalAmountOut).sum * 0.85)
      // For which the volume was previously already stable or decreasing.
      .filter(p => p.stats.slice(2, 3).map(_.totalAmountOut).sum <= p.stats.slice(3, 4).map(_.totalAmountOut).sum)
      // And that have enough liquidity to relay outgoing payments.
      .filter(p => p.canSend >= Seq(config.relayFees.dailyPaymentVolumeThreshold, p.capacity * config.relayFees.dailyPaymentVolumeThresholdPercent).min)
      .flatMap(p => {
        p.latestUpdate_opt match {
          // And for which we haven't updated our relay fees recently already.
          case Some(u) if u.timestamp <= now.toTimestampSecond - (Bucket.duration * 1.5).toSeconds =>
            val next = u.relayFees.copy(feeProportionalMillionths = u.feeProportionalMillionths - 500)
            if (next.feeBase >= config.relayFees.minRelayFees.feeBase && next.feeProportionalMillionths >= config.relayFees.minRelayFees.feeProportionalMillionths) {
              Some(p.remoteNodeId -> FeeChangeDecision(FeeDecrease, u.relayFees, next, p.dailyVolumeOut))
            } else {
              None
            }
          case _ => None
        }
      }).toMap
    // We revert fee changes that had a negative impact on volume.
    val feeReverts = peers
      .filterNot(p => feeIncreases.contains(p.remoteNodeId) || feeDecreases.contains(p.remoteNodeId))
      .flatMap { p =>
        (history.feeChanges.get(p.remoteNodeId), p.latestUpdate_opt) match {
          case (Some(record), Some(u)) =>
            // Confirm our change is still in effect.
            val updateMatchesRecord = u.feeProportionalMillionths == record.newFee.feeProportionalMillionths
            // We expect around 6h-24h after the update to really see its effect on path-finding: after 24h, too many
            // other parameters may interfere with our evaluation.
            val withinEvaluationWindow = u.timestamp >= now.toTimestampSecond - (24 hours).toSeconds
            // We don't want to update fees too often.
            val notUpdatedRecently = u.timestamp <= now.toTimestampSecond - (Bucket.duration * 2).toSeconds
            // We revert both fee increases and fee decreases: if volume didn't increase after a fee decrease, we'd
            // rather collect our previous (bigger) relay fee.
            val shouldRevert = record.direction match {
              case FeeIncrease => p.dailyVolumeOut < record.dailyVolumeOutAtChange * 0.8
              case FeeDecrease => p.dailyVolumeOut < record.dailyVolumeOutAtChange * 0.9
            }
            if (updateMatchesRecord && withinEvaluationWindow && notUpdatedRecently && shouldRevert) {
              val decision = record.direction match {
                case PeerScorer.FeeIncrease => FeeChangeDecision(FeeDecrease, u.relayFees, u.relayFees.copy(feeProportionalMillionths = u.relayFees.feeProportionalMillionths - 500), p.dailyVolumeOut)
                case PeerScorer.FeeDecrease => FeeChangeDecision(FeeIncrease, u.relayFees, u.relayFees.copy(feeProportionalMillionths = u.relayFees.feeProportionalMillionths + 500), p.dailyVolumeOut)
              }
              Some(p.remoteNodeId -> decision)
            } else {
              None
            }
          case _ => None
        }
      }.toMap
    // We print the results to help debugging.
    if (feeIncreases.nonEmpty || feeDecreases.nonEmpty || feeReverts.nonEmpty) {
      log.info("we should update our relay fees with the following peers:")
      log.info("|                               node_id                              | volume_variation | decision | current_fee | next_fee |")
      log.info("|--------------------------------------------------------------------|------------------|----------|-------------|----------|")
      (feeIncreases.toSeq ++ feeDecreases.toSeq ++ feeReverts.toSeq).foreach { case (remoteNodeId, decision) =>
        val volumeVariation = peers.find(_.remoteNodeId == remoteNodeId) match {
          case Some(p) if p.stats.slice(2, 3).map(_.totalAmountOut).sum != 0.msat => p.stats.take(2).map(_.totalAmountOut).sum.toLong.toDouble / (p.stats.slice(2, 3).map(_.totalAmountOut).sum.toLong * lastTwoBucketsRatio)
          case _ => 0.0
        }
        log.info(f"| $remoteNodeId | $volumeVariation%16.2f | ${decision.direction} | ${decision.previousFee.feeProportionalMillionths}%11d | ${decision.newFee.feeProportionalMillionths}%8d |")
      }
      log.info("|--------------------------------------------------------------------|------------------|----------|-------------|----------|")
    }
    updateRelayFeesIfEnabled(peers, feeIncreases ++ feeDecreases ++ feeReverts)
    // Note that in order to avoid oscillating between reverts (reverting a revert), we remove the previous records when
    // reverting a change: this way, the normal algorithm resumes during the next run.
    val history1 = history.addFeeChanges(feeIncreases ++ feeDecreases).revertFeeChanges(feeReverts.keySet)
    val updatedPeers = feeIncreases.keySet ++ feeDecreases.keySet ++ feeReverts.keySet
    (updatedPeers, history1)
  }

  /**
   * When channels have been idle for too long, we decrease their relay fees to boost payment volume.
   * If that doesn't work, we will close these channels with [[closeIdleChannelsIfNeeded]].
   */
  private def decreaseIdleChannelsRelayFeesIfNeeded(peers: Seq[PeerInfo], history: DecisionHistory): DecisionHistory = {
    val feeDecreases = peers
      // We're only interested in channels for which liquidity is idle.
      // We ignore peers for which more than 80% of the funds are on their side: they have a higher incentive than us to
      // close those channels if they aren't useful, so we'll wait for them to do so.
      .filter(p => p.stats.map(_.totalAmountOut).sum <= p.capacity * 0.05 && p.stats.map(_.totalAmountIn).sum <= p.capacity * 0.05 && p.canSend >= p.capacity * 0.2)
      // And relay fees aren't already minimal.
      .filter(p => p.latestUpdate_opt.exists(u => u.relayFees.feeProportionalMillionths > config.relayFees.minRelayFees.feeProportionalMillionths))
      // And relay fees haven't been updated recently.
      .filter(p => p.latestUpdate_opt.exists(u => u.timestamp <= TimestampSecond.now() - 12.hours))
      .flatMap(p => {
        p.latestUpdate_opt match {
          case Some(u) =>
            val next = u.relayFees.copy(feeProportionalMillionths = (u.feeProportionalMillionths - 500).max(config.relayFees.minRelayFees.feeProportionalMillionths))
            Some(p.remoteNodeId -> FeeChangeDecision(FeeDecrease, u.relayFees, next, p.dailyVolumeOut))
          case None => None
        }
      }).toMap
    updateRelayFeesIfEnabled(peers, feeDecreases)
    history.addFeeChanges(feeDecreases)
  }

  private def updateRelayFeesIfEnabled(peers: Seq[PeerInfo], decisions: Map[PublicKey, FeeChangeDecision]): Unit = {
    if (config.relayFees.autoUpdate) {
      decisions.foreach {
        case (remoteNodeId, decision) =>
          val cmd = CMD_UPDATE_RELAY_FEE(UntypedActorRef.noSender, decision.newFee.feeBase, decision.newFee.feeProportionalMillionths)
          peers.find(_.remoteNodeId == remoteNodeId) match {
            case Some(p) =>
              // We store our decision in the DB, which ensures that it will not be reverted to default fees on reconnection.
              nodeParams.db.peers.addOrUpdateRelayFees(remoteNodeId, decision.newFee)
              p.channels.foreach(c => register ! Register.Forward(context.system.ignoreRef, c.channelId, cmd))
            case None => ()
          }
      }
    }
  }

  private def fundChannelsIfNeeded(bestPeers: Seq[FundingProposal], smallPeers: Seq[FundingProposal], toRevive: Seq[FundingProposal], history: DecisionHistory): Behavior[Command] = {
    // We don't want to fund peers every time our scoring algorithm runs, otherwise we may create too many on-chain
    // transactions. We draw from a random distribution to ensure that on average:
    //  - we follow our rate-limit for funding best peers and fund at most 3 at a time
    //  - we fund a small peer once every 3 days, chosen at random between our top 3
    //  - we revive an older peer once every 5 days, chosen at random between our top 3
    val toFund = {
      val scoringPerDay = (1 day).toSeconds / config.scoringFrequency.toSeconds
      // Note that we skip peers that have already reached the maximum capacity.
      val bestPeersWithinLimits = bestPeers.filter(_.peer.capacity < config.liquidity.maxPerPeerCapacity)
      val bestPeersToFund = bestPeersWithinLimits.headOption match {
        case Some(_) if Random.nextDouble() <= config.liquidity.maxFundingTxPerDay.toDouble / (scoringPerDay * bestPeersWithinLimits.size.min(3)) => bestPeersWithinLimits.take(3)
        case _ => Nil
      }
      val smallPeersWithinLimits = smallPeers.filter(_.peer.capacity < config.liquidity.maxPerPeerCapacity)
      val smallPeerToFund_opt = smallPeersWithinLimits.headOption match {
        case Some(_) if Random.nextDouble() <= (1.0 / (scoringPerDay * 3)) => Random.shuffle(smallPeersWithinLimits.take(3)).headOption
        case _ => None
      }
      val toReviveNotAlreadySelected = toRevive.filterNot(p => bestPeers.exists(_.remoteNodeId == p.remoteNodeId) || smallPeerToFund_opt.exists(_.remoteNodeId == p.remoteNodeId) || p.peer.capacity >= config.liquidity.maxPerPeerCapacity)
      val toRevive_opt = toReviveNotAlreadySelected.headOption match {
        case Some(_) if Random.nextDouble() <= (1.0 / (scoringPerDay * 5)) => Random.shuffle(toReviveNotAlreadySelected.take(3)).headOption
        case _ => None
      }
      (bestPeersToFund ++ toRevive_opt ++ smallPeerToFund_opt).distinctBy(_.remoteNodeId)
    }
    if (bestPeers.isEmpty && smallPeers.isEmpty && toRevive.isEmpty) {
      log.info("we haven't identified peers that require liquidity yet")
      run(history)
    } else if (!config.liquidity.autoFund) {
      log.debug("auto-funding peers is disabled")
      run(history)
    } else if (toFund.isEmpty) {
      log.info("we skip funding peers because of per-day rate-limits: increase eclair.peer-scoring.liquidity.max-funding-tx-per-day to fund more often")
      run(history)
    } else if (config.liquidity.maxFeerate < nodeParams.currentFeeratesForFundingClosing.medium) {
      log.info("we skip funding peers because current feerate is too high ({} < {}): increase eclair.peer-scoring.liquidity.max-feerate-sat-per-byte to start funding again", config.liquidity.maxFeerate, nodeParams.currentFeeratesForFundingClosing.medium)
      run(history)
    } else {
      context.pipeToSelf(wallet.onChainBalance()) {
        case Success(b) => OnChainBalance(b.confirmed, b.unconfirmed)
        case Failure(e) => WalletError(e)
      }
      Behaviors.receiveMessagePartial {
        case UpdateConfig(replyTo, _) =>
          replyTo ! false
          Behaviors.same
        case WalletError(e) =>
          log.warn("cannot get on-chain balance: {}", e.getMessage)
          run(history)
        case OnChainBalance(confirmed, unconfirmed) =>
          val now = TimestampMilli.now()
          val history1 = if (confirmed <= config.liquidity.minOnChainBalance) {
            log.info("we don't have enough on-chain balance to fund new channels (confirmed={}, unconfirmed={})", confirmed.toMilliBtc, unconfirmed.toMilliBtc)
            history
          } else {
            toFund
              // We don't fund peers that are already being funded.
              .filterNot(p => p.peer.hasPendingChannel)
              // We don't fund peers that were recently funded, unless volume improved since the last funding.
              .filter { f =>
                history.funding.get(f.remoteNodeId) match {
                  case Some(record) if now - record.timestamp < config.liquidity.fundingCooldown =>
                    if (f.peer.dailyVolumeOut < record.dailyVolumeOutAtFunding && !config.topPeersWhitelist.contains(f.remoteNodeId)) {
                      log.info("skipping funding for remote_node_id={}: last funded {} ago, volume has decreased (was={}, now={})", f.remoteNodeId, now - record.timestamp, record.dailyVolumeOutAtFunding.truncateToSatoshi.toMilliBtc, f.peer.dailyVolumeOut.truncateToSatoshi.toMilliBtc)
                      false
                    } else {
                      log.debug("remote_node_id={} was already funded recently, but we may fund it again (previous_daily_volume={}, current_daily_volume={}, whitelisted={})", f.remoteNodeId, record.dailyVolumeOutAtFunding.truncateToSatoshi.toMilliBtc, f.peer.dailyVolumeOut.truncateToSatoshi.toMilliBtc, config.topPeersWhitelist.contains(f.remoteNodeId))
                      true
                    }
                  case _ => true
                }
              }
              // And we apply our configured funding limits to the liquidity suggestions.
              .map(f => f.copy(fundingAmount = f.fundingAmount.max(config.liquidity.minFundingAmount).min(config.liquidity.maxFundingAmount)))
              .foldLeft[(Option[Satoshi], DecisionHistory)]((Some(confirmed - config.liquidity.minOnChainBalance), history)) {
                case ((Some(available), history), f) if available < f.fundingAmount * 0.5 =>
                  log.info("cannot fund channel with remote_node_id={}, not enough balance available ({} < {})", f.remoteNodeId, available, f.fundingAmount)
                  // We don't fund the next peers (if any) if we haven't been able to fund the current one: we want to
                  // preserve our liquidity for highest-ranking peers.
                  (None, history)
                case ((Some(available), history), f) =>
                  val fundingAmount = f.fundingAmount.min(available)
                  log.info("funding channel with remote_node_id={} (funding_amount={})", f.remoteNodeId, fundingAmount.toMilliBtc)
                  // TODO: when do we want to create a private channel? Maybe if we already have a bigger public channel?
                  val channelFlags = ChannelFlags(announceChannel = true)
                  val cmd = OpenChannel(f.remoteNodeId, fundingAmount, None, None, None, None, None, Some(channelFlags), Some(Timeout(60 seconds)))
                  register ! Register.ForwardNodeId(context.system.ignoreRef, f.remoteNodeId, cmd)
                  (Some(available - fundingAmount), history.addFunding(f.remoteNodeId, FundingDecision(fundingAmount, f.peer.dailyVolumeOut, now)))
                case ((None, history), f) =>
                  log.debug("not funding channel with remote_node_id={}, previous one was skipped", f.remoteNodeId)
                  (None, history)
              }._2
          }
          run(history1.cleanup(now, 7 days))
      }
    }
  }

}
