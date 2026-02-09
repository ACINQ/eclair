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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.profit.PeerStatsTracker._
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, NodeParams, TimestampMilli, ToMilliSatoshiConversion}

import scala.concurrent.duration.FiniteDuration

/**
 * Created by t-bast on 30/01/2026.
 */

object PeerScorer {

  // @formatter:off
  sealed trait Command
  case class ScorePeers(replyTo_opt: Option[ActorRef[ScoreBoard]]) extends Command
  private case class WrappedLatestStats(peers: Seq[PeerInfo]) extends Command
  // @formatter:on

  /**
   * @param enabled          the [[PeerStatsTracker]] actor should only be created when [[enabled]] is true.
   * @param scoringFrequency frequency at which we run our peer scoring algorithm.
   * @param topPeersCount    maximum number of peers to select as candidates for liquidity and relay fee updates.
   */
  case class Config(enabled: Boolean, scoringFrequency: FiniteDuration, topPeersCount: Int, liquidity: LiquidityConfig, relayFees: RelayFeesConfig)

  /**
   * @param autoFund            if true, we will automatically fund channels.
   * @param dailyFlowMultiplier we fund channels with enough liquidity for [[dailyFlowMultiplier]] days of outgoing flow.
   * @param minFundingAmount    we only fund channels if at least this amount is necessary.
   * @param maxFundingAmount    we never fund channels with more than this amount.
   * @param minOnChainBalance   we stop funding channels if our on-chain balance is below this amount.
   * @param maxFeerate          we stop funding channels if the on-chain feerate is above this value.
   * @param maxFrequency        rate-limit the frequency at which we automatically fund channels.
   */
  case class LiquidityConfig(autoFund: Boolean, dailyFlowMultiplier: Int, minFundingAmount: Satoshi, maxFundingAmount: Satoshi, minOnChainBalance: Satoshi, maxFeerate: FeeratePerKw, maxFrequency: FiniteDuration)

  /**
   * @param autoUpdate              if true, we will automatically update our relay fees.
   * @param minRelayFees            we will not lower our relay fees below this value.
   * @param maxRelayFees            we will not raise our relay fees above this value.
   * @param relayFeeUpdateMinVolume we won't update our relay fees if the payment volume is below this value.
   */
  case class RelayFeesConfig(autoUpdate: Boolean, minRelayFees: RelayFees, maxRelayFees: RelayFees, relayFeeUpdateMinVolume: Satoshi)

  case class ScoreBoard(bestPeers: Seq[PeerInfo], liquiditySuggestions: Seq[LiquiditySuggestion], routingFeeSuggestions: Seq[RoutingFeeSuggestion], channelsToClose: Seq[ChannelInfo])

  /** We recommend adding [[fundingAmount]] to a channel with [[remoteNodeId]]. */
  case class LiquiditySuggestion(remoteNodeId: PublicKey, fundingAmount: Satoshi)

  /** We recommend increasing or decreasing our routing fees with [[remoteNodeId]] because we detected a variation in payment volume. */
  case class RoutingFeeSuggestion(remoteNodeId: PublicKey, volumeVariation: Double, increase: Boolean, current: ChannelUpdate) {
    val decrease: Boolean = !increase
  }

  def score(peer: PeerInfo, bucketCount: Int, totalProfit: MilliSatoshi): Double = {
    // We compute a profit score based on:
    //  - volume relative to channel capacity: this indicator allows smaller peers that perform well to attract
    //    more liquidity and become larger peers (avoid always allocating to the already large peers).
    //  - fees earned relative to other peers: this indicator favors larger peers, but is useful because smaller peers
    //    that perform well won't necessarily perform as well for larger volumes, so the previous indicator alone is
    //    insufficient.
    val profit = peer.stats.take(bucketCount).map(_.profit).sum
    val volumeOut = peer.stats.take(bucketCount).map(_.totalAmountOut).sum
    if (peer.capacity > 0.sat && totalProfit > 0.msat) {
      (volumeOut.toLong.toDouble / peer.capacity.toMilliSatoshi.toLong) + (profit.toLong.toDouble / totalProfit.toLong)
    } else {
      0.0
    }
  }

  def apply(nodeParams: NodeParams, statsTracker: ActorRef[PeerStatsTracker.GetLatestStats]): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(ScorePeers(None), nodeParams.peerScoringConfig.scoringFrequency)
        new PeerScorer(nodeParams, statsTracker, context).run()
      }
    }
  }

}

private class PeerScorer(nodeParams: NodeParams, statsTracker: ActorRef[PeerStatsTracker.GetLatestStats], context: ActorContext[PeerScorer.Command]) {

  import PeerScorer._

  private val log = context.log
  private val config = nodeParams.peerScoringConfig

  private def run(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ScorePeers(replyTo_opt) =>
        statsTracker ! PeerStatsTracker.GetLatestStats(context.messageAdapter[PeerStatsTracker.LatestStats](e => WrappedLatestStats(e.peers)))
        waitingForStats(replyTo_opt)
    }
  }

  private def waitingForStats(replyTo_opt: Option[ActorRef[ScoreBoard]]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedLatestStats(peers) => scoring(replyTo_opt, peers)
    }
  }

  private def scoring(replyTo_opt: Option[ActorRef[ScoreBoard]], peers: Seq[PeerInfo]): Behavior[Command] = {
    val now = TimestampMilli.now()
    log.info("scoring {} peers", peers.size)

    // We compute the overall daily and weekly profit.
    val (totalDailyProfit, totalWeeklyProfit) = peers.map(p => {
      (p.stats.take(Bucket.bucketsPerDay).map(_.profit).sum, p.stats.map(_.profit).sum)
    }).reduceOption {
      (current, next) => (current._1 + next._1, current._2 + next._2)
    }.getOrElse(0 msat, 0 msat)
    log.info("rolling daily profit = {} and weekly profit = {}", totalDailyProfit, totalWeeklyProfit)

    // We start by selecting the top most profitable peers of the past day and week.
    val mostProfitableWeeklyPeers = peers
      .map(p => (p, p.stats.map(_.profit).sum))
      .filter { case (_, profit) => profit > 0.msat }
      .sortBy { case (_, profit) => profit }(Ordering[MilliSatoshi].reverse)
      .take(config.topPeersCount)
      .map(_._1)
    val mostProfitableDailyPeers = peers
      .map(p => (p, p.stats.take(Bucket.bucketsPerDay).map(_.profit).sum))
      .filter { case (_, profit) => profit > 0.msat }
      .sortBy { case (_, profit) => profit }(Ordering[MilliSatoshi].reverse)
      .take(config.topPeersCount)
      .map(_._1)
    // We assign a score to each of those nodes which lets us prioritize liquidity allocation.
    // We use the best of their daily and weekly scores.
    val bestScoringPeers = (mostProfitableDailyPeers ++ mostProfitableWeeklyPeers)
      .distinct
      .sortBy(p => Seq(score(p, Bucket.bucketsPerDay, totalDailyProfit), score(p, BucketedPeerStats.bucketsCount, totalWeeklyProfit)).max)(Ordering[Double].reverse)
    if (bestScoringPeers.nonEmpty) {
      log.info("top {} peers:", bestScoringPeers.size)
      log.info("| rank |                               node_id                              | daily_score | weekly_score | current_profit_sat | previous_profit_sat | daily_profit_sat | weekly_profit_sat | current_amount_in_sat | current_amount_out_sat | previous_amount_in_sat | previous_amount_out_sat | daily_amount_in_sat | daily_amount_out_sat | weekly_amount_in_sat | weekly_amount_out_sat | can_send_sat | can_receive_sat | capacity_sat |")
      log.info("|------|--------------------------------------------------------------------|-------------|--------------|--------------------|---------------------|------------------|-------------------|-----------------------|------------------------|------------------------|-------------------------|---------------------|----------------------|----------------------|-----------------------|--------------|-----------------|--------------|")
      bestScoringPeers.zipWithIndex.foreach { case (p, i) =>
        val currentProfit = p.stats.headOption.map(_.profit.truncateToSatoshi).getOrElse(0 sat).toLong
        val previousProfit = p.stats.drop(1).headOption.map(_.profit.truncateToSatoshi).getOrElse(0 sat).toLong
        val dailyProfit = p.stats.take(Bucket.bucketsPerDay).map(_.profit).sum.truncateToSatoshi.toLong
        val dailyScore = score(p, Bucket.bucketsPerDay, totalDailyProfit)
        val weeklyProfit = p.stats.map(_.profit).sum.truncateToSatoshi.toLong
        val weeklyScore = score(p, BucketedPeerStats.bucketsCount, totalWeeklyProfit)
        val currentAmountOut = p.stats.headOption.map(_.totalAmountOut.truncateToSatoshi).getOrElse(0 sat).toLong
        val previousAmountOut = p.stats.drop(1).headOption.map(_.totalAmountOut.truncateToSatoshi).getOrElse(0 sat).toLong
        val dailyAmountOut = p.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum.truncateToSatoshi.toLong
        val weeklyAmountOut = p.stats.map(_.totalAmountOut).sum.truncateToSatoshi.toLong
        val currentAmountIn = p.stats.headOption.map(_.totalAmountIn.truncateToSatoshi).getOrElse(0 sat).toLong
        val previousAmountIn = p.stats.drop(1).headOption.map(_.totalAmountIn.truncateToSatoshi).getOrElse(0 sat).toLong
        val dailyAmountIn = p.stats.take(Bucket.bucketsPerDay).map(_.totalAmountIn).sum.truncateToSatoshi.toLong
        val weeklyAmountIn = p.stats.map(_.totalAmountIn).sum.truncateToSatoshi.toLong
        log.info(f"| ${i + 1}%4d | ${p.remoteNodeId} |        $dailyScore%.2f |         $weeklyScore%.2f | $currentProfit%18d | $previousProfit%19d | $dailyProfit%16d | $weeklyProfit%17d | $currentAmountIn%21d | $currentAmountOut%22d | $previousAmountIn%22d | $previousAmountOut%23d | $dailyAmountIn%19d | $dailyAmountOut%20d | $weeklyAmountIn%20d | $weeklyAmountOut%21d | ${p.canSend.truncateToSatoshi.toLong}%12d | ${p.canReceive.truncateToSatoshi.toLong}%15d | ${p.capacity.toLong}%12d |")
      }
      log.info("|------|--------------------------------------------------------------------|-------------|--------------|--------------------|---------------------|------------------|-------------------|-----------------------|------------------------|------------------------|-------------------------|---------------------|----------------------|----------------------|-----------------------|--------------|-----------------|--------------|")
    }

    // If very profitable peers exhausted their liquidity more than a week ago, they won't get a good score.
    // We may thus incorrectly prioritize peers that have recent payment activity but are less profitable.
    // We try to find older peers that were profitable and reallocate liquidity towards them, if relevant.
    val olderPeersWithExhaustedLiquidity = peers
      // We look for channels where most of the liquidity is on the other side.
      .filter(p => (p.canSend.toLong.toDouble / p.capacity.toMilliSatoshi.toLong) < 0.1)
      // And that haven't had a lot of activity in the past week (and thus have exhausted their liquidity before that).
      .filter(p => p.stats.map(_.totalAmountOut).sum < p.capacity * 0.1)
      .sortBy(_.capacity)(Ordering[Satoshi].reverse)
      .take(config.topPeersCount)
    if (olderPeersWithExhaustedLiquidity.nonEmpty && bestScoringPeers.exists(_.stats.drop(Bucket.bucketsPerDay).exists(_ != PeerStats.empty))) {
      // TODO: we should read older payment events from the DB and compare the profit earned by those peers with our
      //  current best scoring peers.
      log.info("the following {} peers have exhausted their outgoing liquidity but may be profitable peers", olderPeersWithExhaustedLiquidity.size)
      log.info("|                               node_id                              | can_send_sat | capacity_sat |")
      log.info("|--------------------------------------------------------------------|--------------|--------------|")
      olderPeersWithExhaustedLiquidity.foreach(p => {
        log.info(f"| ${p.remoteNodeId} | ${p.canSend.truncateToSatoshi.toLong}%12d | ${p.capacity.toLong}%12d |")
      })
      log.info("|--------------------------------------------------------------------|--------------|--------------|")
    }

    // We compute suggestions for liquidity allocation based on estimated future outgoing flow.
    val liquidity = bestScoringPeers
      // We only consider allocating liquidity if there isn't already a channel being created.
      .filter(p => !p.hasPendingChannel)
      // We only consider allocating liquidity to nodes that have a positive outgoing flow.
      .filter(p => p.stats.map(_.outgoingFlow).sum > 0.msat || p.stats.take(Bucket.bucketsPerDay).map(_.outgoingFlow).sum > 0.msat)
      .map(s => {
        // We want to allocate liquidity to ensure that we can keep relaying payments for a given duration if the flow stays the same.
        val lastFlows = s.stats.take(Bucket.bucketsPerDay).map(_.outgoingFlow).sum
        val missing = (lastFlows * config.liquidity.dailyFlowMultiplier - s.canSend).max(0 msat).min(config.liquidity.maxFundingAmount)
        LiquiditySuggestion(s.remoteNodeId, missing.truncateToSatoshi)
      })
      .filter { l => l.fundingAmount > config.liquidity.minFundingAmount }
    if (liquidity.nonEmpty) {
      log.info("we recommend the following {} liquidity allocations:", liquidity.size)
      log.info("| rank |                               node_id                              | funding_amount_sat |")
      log.info("|------|--------------------------------------------------------------------|--------------------|")
      liquidity.zipWithIndex.foreach { case (l, i) =>
        log.info(f"| ${i + 1}%4d | ${l.remoteNodeId} | ${l.fundingAmount.toLong}%18d |")
      }
      log.info("|------|--------------------------------------------------------------------|--------------------|")
    }

    // We compute suggestions for updating our relay fees to match changes in outgoing volume.
    val routing = bestScoringPeers.flatMap(s => {
      val liquidityAvailable = s.canSend > config.relayFees.relayFeeUpdateMinVolume
      val current = s.stats.headOption.map(_.totalAmountOut).getOrElse(0 msat)
      val previous = s.stats.drop(1).headOption.map(_.totalAmountOut).getOrElse(0 msat)
      val older = s.stats.drop(2).headOption.map(_.totalAmountOut).getOrElse(0 msat)
      s.latestUpdate_opt match {
        case Some(u) if current > Seq(previous, config.relayFees.relayFeeUpdateMinVolume.toMilliSatoshi).max && previous > 0.msat && liquidityAvailable && u.timestamp.toTimestampMilli < now - Bucket.duration =>
          // If the current flow (which doesn't have a whole bucket of data yet) is greater than the previous flow
          // (which has a full bucket of data), and greater than a given threshold, we can try to increase our routing
          // fees if we haven't done so recently already.
          val variation = (current - previous).toLong.toDouble / previous.toLong
          Some(RoutingFeeSuggestion(s.remoteNodeId, variation, increase = true, u))
        case Some(u) if older > 0.msat && older > current + previous && liquidityAvailable && u.timestamp.toTimestampMilli < now - Bucket.duration =>
          // If the flow of the current and previous buckets are smaller than the flow of the bucket before that, and
          // we haven't already updated our fees recently, we may want to decrease our routing fees to attract traffic.
          val variation = (older - current - previous).toLong.toDouble / older.toLong
          Some(RoutingFeeSuggestion(s.remoteNodeId, variation, increase = false, u))
        case _ =>
          None
      }
    })
    if (routing.nonEmpty) {
      log.info("we recommend the following {} routing fee changes", routing.size)
      log.info("|                               node_id                              | decision | volume_variation | current_fee_base_msat | current_fee_proportional |")
      log.info("|--------------------------------------------------------------------|----------|------------------|-----------------------|--------------------------|")
      routing.foreach(r => {
        log.info(f"| ${r.remoteNodeId} | ${if (r.increase) "increase" else "decrease"} |             ${r.volumeVariation}%.2f | ${r.current.feeBaseMsat.toLong}%21d | ${r.current.feeProportionalMillionths}%24d |")
      })
      log.info("|--------------------------------------------------------------------|----------|------------------|-----------------------|--------------------------|")
    }

    // We compute suggestions for channels that can be closed to reclaim liquidity.
    // Since we're not yet reading past events from the DB, we need to wait until we have collected enough data.
    val channelsToClose = if (bestScoringPeers.exists(_.stats.drop(Bucket.bucketsPerDay).exists(_ != PeerStats.empty))) {
      peers
        .filter(_.channels.size > 1)
        .flatMap(p => {
          // Peers for which most of the liquidity is idle on our side are good candidates for reclaiming liquidity.
          val unbalanced = p.canSend >= p.canReceive * 4
          val volumeOut = p.stats.map(_.totalAmountOut).sum
          val lowVolume = volumeOut <= p.canSend * 0.1
          if (unbalanced && lowVolume) {
            // We always want to keep at least one channel with every peer, and want to keep the one with the best score
            // for routing algorithms (mostly influenced by capacity). We want to avoid closing the largest channel if
            // it isn't unbalanced, so we also take into account current balances in that channel.
            val maxCapacity = p.channels.map(_.capacity).max
            val channelToKeep = p.channels.filter(_.isPublic).map(c => {
              val score = 4 * (c.capacity.toLong.toDouble / maxCapacity.toLong) + (c.canSend.toLong.toDouble / c.capacity.toMilliSatoshi.toLong)
              (score, c)
            }).sortBy(_._1).lastOption.map(_._2.channelId)
            p.channels.filter(b => !channelToKeep.contains(b.channelId))
          } else {
            Nil
          }
        })
    } else {
      Nil
    }
    if (channelsToClose.nonEmpty) {
      log.info("we recommend closing the following {} channels to reclaim {}", channelsToClose.size, channelsToClose.map(_.canSend).sum)
      log.info("|                            channel_id                            | local_balance_sat | capacity_sat |")
      log.info("|------------------------------------------------------------------|-------------------|--------------|")
      channelsToClose.foreach(c => {
        log.info(f"| ${c.channelId} | ${c.canSend.truncateToSatoshi.toLong}%17d | ${c.capacity.toLong}%12d |")
      })
      log.info("|------------------------------------------------------------------|-------------------|--------------|")
    }

    replyTo_opt.foreach(_ ! ScoreBoard(bestScoringPeers, liquidity, routing, channelsToClose))
    run()
  }

}
