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
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, NodeParams, TimestampMilli}

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
  case class Config(enabled: Boolean, scoringFrequency: FiniteDuration, topPeersCount: Int, topPeersWhitelist: Set[PublicKey], liquidity: LiquidityConfig, relayFees: RelayFeesConfig)

  /**
   * @param autoFund           if true, we will automatically fund channels.
   * @param autoClose          if true, we will automatically close unused channels to reclaim liquidity.
   * @param minFundingAmount   we always fund channels with at least this amount.
   * @param maxFundingAmount   we never fund channels with more than this amount.
   * @param maxFundingTxPerDay we rate-limit the number of transactions we make per day (on average).
   * @param minOnChainBalance  we stop funding channels if our on-chain balance is below this amount.
   * @param maxFeerate         we stop funding channels if the on-chain feerate is above this value.
   */
  case class LiquidityConfig(autoFund: Boolean, autoClose: Boolean, minFundingAmount: Satoshi, maxFundingAmount: Satoshi, maxFundingTxPerDay: Int, minOnChainBalance: Satoshi, maxFeerate: FeeratePerKw)

  /**
   * @param autoUpdate                         if true, we will automatically update our relay fees.
   * @param minRelayFees                       we will not lower our relay fees below this value.
   * @param maxRelayFees                       we will not raise our relay fees above this value.
   * @param dailyPaymentVolumeThreshold        we only increase fees if the daily outgoing payment volume exceeds this threshold or [[dailyPaymentVolumeThresholdPercent]].
   * @param dailyPaymentVolumeThresholdPercent we only increase fees if the daily outgoing payment volume exceeds this percentage of our peer capacity or [[dailyPaymentVolumeThreshold]].
   */
  case class RelayFeesConfig(autoUpdate: Boolean, minRelayFees: RelayFees, maxRelayFees: RelayFees, dailyPaymentVolumeThreshold: Satoshi, dailyPaymentVolumeThresholdPercent: Double)

  private case class LiquidityDecision(peer: PeerInfo, fundingAmount: Satoshi) {
    val remoteNodeId: PublicKey = peer.remoteNodeId
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
        new PeerScorer(nodeParams, wallet, statsTracker, register, context).run()
      }
    }
  }

}

private class PeerScorer(nodeParams: NodeParams, wallet: OnChainBalanceChecker, statsTracker: ActorRef[PeerStatsTracker.GetLatestStats], register: UntypedActorRef, context: ActorContext[PeerScorer.Command]) {

  import PeerScorer._

  implicit val ec: ExecutionContext = context.system.executionContext
  private val log = context.log
  private val config = nodeParams.peerScoringConfig

  private def run(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ScorePeers(replyTo_opt) =>
        statsTracker ! PeerStatsTracker.GetLatestStats(context.messageAdapter[PeerStatsTracker.LatestStats](e => WrappedLatestStats(e.peers)))
        waitForStats(replyTo_opt)
    }
  }

  private def waitForStats(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedLatestStats(peers) => scorePeers(replyTo_opt, peers)
    }
  }

  private def scorePeers(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]], peers: Seq[PeerInfo]): Behavior[Command] = {
    log.info("scoring {} peers", peers.size)
    val dailyProfit = peers.map(_.stats.take(Bucket.bucketsPerDay).map(_.profit).sum).sum.truncateToSatoshi.toMilliBtc
    val weeklyProfit = peers.map(_.stats.map(_.profit).sum).sum.truncateToSatoshi.toMilliBtc
    log.info("rolling daily profit = {} and weekly profit = {}", dailyProfit, weeklyProfit)

    // We select peers that have the largest outgoing payment volume of the past day.
    // This biases towards nodes that already have a large capacity and have liquidity available.
    val bestPeersByVolume = peers
      .map(p => (p, p.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum))
      .filter(_._2 > 0.msat)
      .sortBy(_._2)(Ordering[MilliSatoshi].reverse)
      .take(config.topPeersCount)
      .map(_._1)
    log.debug("top {} peers:", bestPeersByVolume.size)
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
      .map { case (p, _) => LiquidityDecision(p, (bestDailyOutgoingFlow(p) * 4).truncateToSatoshi) }
      .filter(_.fundingAmount > 0.sat)
    if (bestPeersThatNeedLiquidity.nonEmpty) {
      log.debug("top {} peers that need liquidity:", bestPeersThatNeedLiquidity.size)
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
      .map(p => LiquidityDecision(p, p.capacity * 0.5))
    if (goodSmallPeers.nonEmpty) {
      log.debug("we've identified {} smaller peers that perform well relative to their capacity", goodSmallPeers.size)
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
      // We'd like to increase their capacity by 25%.
      .map(p => LiquidityDecision(p, p.capacity * 0.25))

    // Since we're not yet reading past events from the DB, we need to wait until we have collected enough data before
    // taking some actions such as opening or closing channels or updating relay fees.
    // TODO: remove this once we start reading past data from the AuditDb on restart.
    val hasPastData = bestPeersByVolume.exists(_.stats.drop(Bucket.bucketsPerDay).exists(_ != PeerStats.empty))
    if (hasPastData && replyTo_opt.isEmpty) {
      closeChannelsIfNeeded(peers)
      updateRelayFeesIfNeeded(peers)
      fundChannelsIfNeeded(bestPeersThatNeedLiquidity, goodSmallPeers, peersToRevive)
    } else {
      replyTo_opt.foreach(_ ! bestPeersThatNeedLiquidity.map(_.peer))
      run()
    }
  }

  private def printDailyStats(peers: Seq[PeerInfo]): Unit = {
    log.debug("| rank |                               node_id                              |  daily_volume |  daily_profit |      can_send |   can_receive |")
    log.debug("|------|--------------------------------------------------------------------|---------------|---------------|---------------|---------------|")
    peers.zipWithIndex.foreach { case (p, i) =>
      val dailyStats = p.stats.take(Bucket.bucketsPerDay)
      val dailyVolume = dailyStats.map(_.totalAmountOut).sum.truncateToSatoshi.toMilliBtc
      val dailyProfit = dailyStats.map(_.profit).sum.truncateToSatoshi.toMilliBtc
      val canSend = p.canSend.truncateToSatoshi.toMilliBtc
      val canReceive = p.canReceive.truncateToSatoshi.toMilliBtc
      log.debug(f"| ${i + 1}%4d | ${p.remoteNodeId} | ${dailyVolume.toDouble}%8.2f mbtc | ${dailyProfit.toDouble}%8.2f mbtc | ${canSend.toDouble}%8.2f mbtc | ${canReceive.toDouble}%8.2f mbtc |")
    }
    log.debug("|------|--------------------------------------------------------------------|---------------|---------------|---------------|---------------|")
  }

  private def closeChannelsIfNeeded(peers: Seq[PeerInfo]): Unit = {
    peers
      // We only close channels when we have more than one.
      .filter(_.channels.size > 1)
      // We only close channels for which most of the liquidity is idle on our side.
      .filter(p => p.canSend >= p.capacity * 0.8 && p.stats.map(_.totalAmountOut).sum <= p.capacity * 0.05)
      .foreach(p => {
        val channels = p.channels.sortWith {
          // We want to keep a public channel over a private channel.
          case (c1, c2) if c1.isPublic != c2.isPublic => c1.isPublic
          // Otherwise, we keep the channel with the largest capacity.
          case (c1, c2) if c1.capacity != c2.capacity => c1.capacity >= c2.capacity
          // Otherwise, we keep the channel with the smallest balance (and thus highest inbound liquidity).
          case (c1, c2) => c1.canSend <= c2.canSend
        }
        // We keep the best channel and close the others.
        channels.tail.foreach { c =>
          log.debug("we should close channel_id={} with remote_node_id={} (local={}, remote={})", c.channelId, p.remoteNodeId, c.canSend.truncateToSatoshi.toMilliBtc, c.canReceive.truncateToSatoshi.toMilliBtc)
          if (config.liquidity.autoClose && nodeParams.currentFeeratesForFundingClosing.medium <= config.liquidity.maxFeerate) {
            log.info("closing channel_id={} with remote_node_id={} (local={}, remote={})", c.channelId, p.remoteNodeId, c.canSend.truncateToSatoshi.toMilliBtc, c.canReceive.truncateToSatoshi.toMilliBtc)
            val cmd = CMD_CLOSE(UntypedActorRef.noSender, None, None)
            register ! Register.Forward(context.system.ignoreRef, c.channelId, cmd)
          }
        }
      })
  }

  private def updateRelayFeesIfNeeded(peers: Seq[PeerInfo]): Unit = {
    // We configure *daily* absolute and proportional payment volume targets. We look at events from the current period
    // and the previous period, so we need to get the right ratio to convert those daily amounts.
    val now = TimestampMilli.now()
    val lastTwoBucketsRatio = 1.0 + Bucket.consumed(now)
    val lastTwoBucketsDailyRatio = (Bucket.duration * lastTwoBucketsRatio).toSeconds.toDouble / (24 * 3600)
    // We increase fees of channels that are performing better than we expected.
    log.debug("we should update our relay fees with the following peers:")
    log.debug("|                               node_id                              |  volume_variation | decision | current_fee | next_fee |")
    log.debug("|--------------------------------------------------------------------|-------------------|----------|-------------|----------|")
    peers
      // We select peers that have exceeded our payment volume target in the past two periods.
      .filter(p => p.stats.take(2).map(_.totalAmountOut).sum >= Seq(config.relayFees.dailyPaymentVolumeThreshold * lastTwoBucketsDailyRatio, p.capacity * config.relayFees.dailyPaymentVolumeThresholdPercent * lastTwoBucketsDailyRatio).min)
      // And that have an increasing payment volume compared to the period before that.
      .filter(p => p.stats.slice(2, 3).map(_.totalAmountOut).sum > 0.msat)
      .filter(p => (p.stats.take(2).map(_.totalAmountOut).sum / lastTwoBucketsRatio) > p.stats.slice(2, 3).map(_.totalAmountOut).sum * 1.1)
      .foreach(p => {
        p.latestUpdate_opt match {
          // And for which we haven't updated our relay fees recently already.
          case Some(u) if u.timestamp <= now.toTimestampSecond - (Bucket.duration * 1.5).toSeconds =>
            val next = u.relayFees.feeProportionalMillionths + 500
            val volumeVariation = p.stats.take(2).map(_.totalAmountOut).sum.toLong.toDouble / (p.stats.slice(2, 3).map(_.totalAmountOut).sum.toLong * lastTwoBucketsRatio)
            log.debug(f"| ${p.remoteNodeId} |             $volumeVariation%.2f | increase | ${u.feeProportionalMillionths}%11d | $next%8d |")
            if (config.relayFees.autoUpdate && next <= config.relayFees.maxRelayFees.feeProportionalMillionths) {
              val cmd = CMD_UPDATE_RELAY_FEE(UntypedActorRef.noSender, u.relayFees.feeBase, next)
              p.channels.foreach(c => register ! Register.Forward(context.system.ignoreRef, c.channelId, cmd))
            }
          case _ => ()
        }
      })
    // We decrease fees of channels that aren't performing well.
    peers
      // We select peers that have a recent 15% decrease in outgoing payment volume.
      .filter(p => p.stats.slice(2, 3).map(_.totalAmountOut).sum > 0.msat)
      .filter(p => p.stats.take(2).map(_.totalAmountOut).sum <= p.stats.slice(2, 3).map(_.totalAmountOut).sum * 0.85)
      // For which the volume was previously already stable or decreasing.
      .filter(p => p.stats.slice(2, 3).map(_.totalAmountOut).sum <= p.stats.slice(3, 4).map(_.totalAmountOut).sum)
      // And that have enough liquidity to relay outgoing payments.
      .filter(p => p.canSend >= Seq(config.relayFees.dailyPaymentVolumeThreshold, p.capacity * config.relayFees.dailyPaymentVolumeThresholdPercent).min)
      .foreach(p => {
        p.latestUpdate_opt match {
          // And for which we haven't updated our relay fees recently already.
          case Some(u) if u.timestamp <= now.toTimestampSecond - (Bucket.duration * 1.5).toSeconds =>
            val next = u.relayFees.feeProportionalMillionths - 500
            val volumeVariation = p.stats.take(2).map(_.totalAmountOut).sum.toLong.toDouble / (p.stats.slice(2, 3).map(_.totalAmountOut).sum.toLong * lastTwoBucketsRatio)
            log.debug(f"| ${p.remoteNodeId} |             $volumeVariation%.2f | decrease | ${u.feeProportionalMillionths}%11d | $next%8d |")
            if (config.relayFees.autoUpdate && next >= config.relayFees.minRelayFees.feeProportionalMillionths) {
              val cmd = CMD_UPDATE_RELAY_FEE(UntypedActorRef.noSender, u.relayFees.feeBase, next)
              p.channels.foreach(c => register ! Register.Forward(context.system.ignoreRef, c.channelId, cmd))
            }
          case _ => ()
        }
      })
    log.debug("|--------------------------------------------------------------------|-------------------|----------|-------------|----------|")
  }

  private def fundChannelsIfNeeded(bestPeers: Seq[LiquidityDecision], smallPeers: Seq[LiquidityDecision], toRevive: Seq[LiquidityDecision]): Behavior[Command] = {
    // We don't want to fund peers every time our scoring algorithm runs, otherwise we may create too many on-chain
    // transactions. We draw from a random distribution to ensure that on average:
    //  - we follow our rate-limit for funding best peers and fund at most 3 at a time
    //  - we fund a small peer once every 3 days, chosen at random between our top 3
    //  - we revive an older peer once every day, chosen at random between our top 3
    val toFund = {
      val scoringPerDay = (1 day).toSeconds / config.scoringFrequency.toSeconds
      val bestPeersToFund = bestPeers.headOption match {
        case Some(_) if Random.nextDouble() <= config.liquidity.maxFundingTxPerDay.toDouble / (scoringPerDay * bestPeers.size.min(3)) => bestPeers.take(3)
        case _ => Nil
      }
      val smallPeerToFund_opt = smallPeers.headOption match {
        case Some(_) if Random.nextDouble() <= (1.0 / (scoringPerDay * 3)) => Random.shuffle(smallPeers.take(3)).headOption
        case _ => None
      }
      val toReviveNotAlreadySelected = toRevive.filterNot(p => bestPeers.exists(_.remoteNodeId == p.remoteNodeId) || smallPeerToFund_opt.exists(_.remoteNodeId == p.remoteNodeId))
      val toRevive_opt = toReviveNotAlreadySelected.headOption match {
        case Some(_) if Random.nextDouble() <= (1.0 / scoringPerDay) => Random.shuffle(toReviveNotAlreadySelected.take(3)).headOption
        case _ => None
      }
      (bestPeersToFund ++ toRevive_opt ++ smallPeerToFund_opt).distinctBy(_.remoteNodeId)
    }
    if (bestPeers.isEmpty && smallPeers.isEmpty && toRevive.isEmpty) {
      log.info("we haven't identified peers that require liquidity yet")
      run()
    } else if (toFund.isEmpty) {
      log.info("we skip funding peers because of per-day rate-limits: increase eclair.peer-scoring.liquidity.max-funding-tx-per-day to fund more often")
      run()
    } else if (config.liquidity.maxFeerate < nodeParams.currentFeeratesForFundingClosing.medium) {
      log.info("we skip funding peers because current feerate is too high ({} < {}): increase eclair.peer-scoring.liquidity.max-feerate-sat-per-byte to start funding again", config.liquidity.maxFeerate, nodeParams.currentFeeratesForFundingClosing.medium)
      run()
    } else {
      context.pipeToSelf(wallet.onChainBalance()) {
        case Success(b) => OnChainBalance(b.confirmed, b.unconfirmed)
        case Failure(e) => WalletError(e)
      }
      Behaviors.receiveMessagePartial {
        case WalletError(e) =>
          log.warn("cannot get on-chain balance: {}", e.getMessage)
          run()
        case OnChainBalance(confirmed, unconfirmed) =>
          if (confirmed <= config.liquidity.minOnChainBalance) {
            log.info("we don't have enough on-chain balance to fund new channels (confirmed={}, unconfirmed={})", confirmed.toMilliBtc, unconfirmed.toMilliBtc)
          } else {
            toFund
              // We don't fund peers that are already being funded.
              .filterNot(_.peer.hasPendingChannel)
              // And we apply our configured funding limits to the liquidity suggestions.
              .map(f => f.copy(fundingAmount = f.fundingAmount.max(config.liquidity.minFundingAmount).min(config.liquidity.maxFundingAmount)))
              .foldLeft(confirmed - config.liquidity.minOnChainBalance) {
                case (available, f) if available < f.fundingAmount * 0.5 => available
                case (available, f) =>
                  val fundingAmount = f.fundingAmount.min(available)
                  log.info("funding channel with remote_node_id={} (funding_amount={})", f.remoteNodeId, fundingAmount.toMilliBtc)
                  // TODO: when do we want to create a private channel? Maybe if we already have a bigger public channel?
                  val channelFlags = ChannelFlags(announceChannel = true)
                  val cmd = OpenChannel(f.remoteNodeId, fundingAmount, None, None, None, None, None, Some(channelFlags), Some(Timeout(60 seconds)))
                  register ! Register.ForwardNodeId(context.system.ignoreRef, f.remoteNodeId, cmd)
                  available - fundingAmount
              }
          }
          run()
      }
    }
  }

}
