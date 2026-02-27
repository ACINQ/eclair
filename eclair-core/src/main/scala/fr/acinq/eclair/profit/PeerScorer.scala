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
   * @param fundingCooldown    minimum time between funding the same peer, to evaluate effectiveness.
   */
  case class LiquidityConfig(autoFund: Boolean, autoClose: Boolean, minFundingAmount: Satoshi, maxFundingAmount: Satoshi, maxFundingTxPerDay: Int, minOnChainBalance: Satoshi, maxFeerate: FeeratePerKw, fundingCooldown: FiniteDuration)

  /**
   * @param autoUpdate                         if true, we will automatically update our relay fees.
   * @param minRelayFees                       we will not lower our relay fees below this value.
   * @param maxRelayFees                       we will not raise our relay fees above this value.
   * @param dailyPaymentVolumeThreshold        we only increase fees if the daily outgoing payment volume exceeds this threshold or [[dailyPaymentVolumeThresholdPercent]].
   * @param dailyPaymentVolumeThresholdPercent we only increase fees if the daily outgoing payment volume exceeds this percentage of our peer capacity or [[dailyPaymentVolumeThreshold]].
   */
  case class RelayFeesConfig(autoUpdate: Boolean, minRelayFees: RelayFees, maxRelayFees: RelayFees, dailyPaymentVolumeThreshold: Satoshi, dailyPaymentVolumeThresholdPercent: Double)

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
  private val config = nodeParams.peerScoringConfig

  private def run(history: DecisionHistory): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ScorePeers(replyTo_opt) =>
        statsTracker ! PeerStatsTracker.GetLatestStats(context.messageAdapter[PeerStatsTracker.LatestStats](e => WrappedLatestStats(e.peers)))
        waitForStats(replyTo_opt, history)
    }
  }

  private def waitForStats(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]], history: DecisionHistory): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedLatestStats(peers) => scorePeers(replyTo_opt, peers, history)
    }
  }

  private def scorePeers(replyTo_opt: Option[ActorRef[Seq[PeerInfo]]], peers: Seq[PeerInfo], history: DecisionHistory): Behavior[Command] = {
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
      .map { case (p, _) => FundingProposal(p, (bestDailyOutgoingFlow(p) * 4).truncateToSatoshi) }
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
      .map(p => FundingProposal(p, p.capacity * 0.5))
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
      .map(p => FundingProposal(p, p.capacity * 0.25))

    // Since we're not yet reading past events from the DB, we need to wait until we have collected enough data before
    // taking some actions such as opening or closing channels or updating relay fees.
    // TODO: remove this once we start reading past data from the AuditDb on restart.
    val hasPastData = bestPeersByVolume.exists(_.stats.drop(Bucket.bucketsPerDay).exists(_ != PeerStats.empty))
    if (hasPastData && replyTo_opt.isEmpty) {
      closeChannelsIfNeeded(peers)
      val history1 = updateRelayFeesIfNeeded(peers, history)
      fundChannelsIfNeeded(bestPeersThatNeedLiquidity, goodSmallPeers, peersToRevive, history1)
    } else {
      replyTo_opt.foreach(_ ! bestPeersThatNeedLiquidity.map(_.peer))
      run(history)
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

  private def updateRelayFeesIfNeeded(peers: Seq[PeerInfo], history: DecisionHistory): DecisionHistory = {
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
              val dailyVolume = p.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum
              Some(p.remoteNodeId -> FeeChangeDecision(FeeIncrease, u.relayFees, next, dailyVolume))
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
              val dailyVolume = p.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum
              Some(p.remoteNodeId -> FeeChangeDecision(FeeDecrease, u.relayFees, next, dailyVolume))
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
            val currentDailyVolume = p.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum
            val shouldRevert = record.direction match {
              case FeeIncrease => currentDailyVolume < record.dailyVolumeOutAtChange * 0.8
              case FeeDecrease => currentDailyVolume < record.dailyVolumeOutAtChange * 0.9
            }
            if (updateMatchesRecord && withinEvaluationWindow && notUpdatedRecently && shouldRevert) {
              val decision = record.direction match {
                case PeerScorer.FeeIncrease => FeeChangeDecision(FeeDecrease, u.relayFees, u.relayFees.copy(feeProportionalMillionths = u.relayFees.feeProportionalMillionths - 500), currentDailyVolume)
                case PeerScorer.FeeDecrease => FeeChangeDecision(FeeIncrease, u.relayFees, u.relayFees.copy(feeProportionalMillionths = u.relayFees.feeProportionalMillionths + 500), currentDailyVolume)
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
      log.debug("we should update our relay fees with the following peers:")
      log.debug("|                               node_id                              |  volume_variation | decision | current_fee | next_fee |")
      log.debug("|--------------------------------------------------------------------|-------------------|----------|-------------|----------|")
      (feeIncreases.toSeq ++ feeDecreases.toSeq ++ feeReverts.toSeq).foreach { case (remoteNodeId, decision) =>
        val volumeVariation = peers.find(_.remoteNodeId == remoteNodeId) match {
          case Some(p) if p.stats.slice(2, 3).map(_.totalAmountOut).sum != 0.msat => p.stats.take(2).map(_.totalAmountOut).sum.toLong.toDouble / (p.stats.slice(2, 3).map(_.totalAmountOut).sum.toLong * lastTwoBucketsRatio)
          case _ => 0.0
        }
        log.debug(f"| $remoteNodeId |             $volumeVariation%.2f | ${decision.direction} | ${decision.previousFee.feeProportionalMillionths}%11d | ${decision.newFee.feeProportionalMillionths}%8d |")
      }
      log.debug("|--------------------------------------------------------------------|-------------------|----------|-------------|----------|")
    }
    if (config.relayFees.autoUpdate) {
      (feeIncreases.toSeq ++ feeDecreases.toSeq ++ feeReverts.toSeq).foreach { case (remoteNodeId, decision) =>
        val cmd = CMD_UPDATE_RELAY_FEE(UntypedActorRef.noSender, decision.newFee.feeBase, decision.newFee.feeProportionalMillionths)
        peers.find(_.remoteNodeId == remoteNodeId).map(_.channels).getOrElse(Nil).foreach(c => register ! Register.Forward(context.system.ignoreRef, c.channelId, cmd))
      }
    }
    // Note that in order to avoid oscillating between reverts (reverting a revert), we remove the previous records when
    // reverting a change: this way, the normal algorithm resumes during the next run.
    history.addFeeChanges(feeIncreases ++ feeDecreases).revertFeeChanges(feeReverts.keySet)
  }

  private def fundChannelsIfNeeded(bestPeers: Seq[FundingProposal], smallPeers: Seq[FundingProposal], toRevive: Seq[FundingProposal], history: DecisionHistory): Behavior[Command] = {
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
                    val currentDailyVolume = f.peer.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum
                    if (currentDailyVolume <= record.dailyVolumeOutAtFunding * 1.1) {
                      log.info("skipping funding for remote_node_id={}: last funded {} ago, volume has not improved (was={}, now={})", f.remoteNodeId, now - record.timestamp, record.dailyVolumeOutAtFunding.truncateToSatoshi.toMilliBtc, currentDailyVolume.truncateToSatoshi.toMilliBtc)
                      false
                    } else {
                      log.info("re-funding remote_node_id={}: volume improved since last funding (was={}, now={})", f.remoteNodeId, record.dailyVolumeOutAtFunding.truncateToSatoshi.toMilliBtc, currentDailyVolume.truncateToSatoshi.toMilliBtc)
                      true
                    }
                  case _ => true
                }
              }
              // And we apply our configured funding limits to the liquidity suggestions.
              .map(f => f.copy(fundingAmount = f.fundingAmount.max(config.liquidity.minFundingAmount).min(config.liquidity.maxFundingAmount)))
              .foldLeft((confirmed - config.liquidity.minOnChainBalance, history)) {
                case ((available, history), f) if available < f.fundingAmount * 0.5 => (available, history)
                case ((available, history), f) =>
                  val fundingAmount = f.fundingAmount.min(available)
                  log.info("funding channel with remote_node_id={} (funding_amount={})", f.remoteNodeId, fundingAmount.toMilliBtc)
                  // TODO: when do we want to create a private channel? Maybe if we already have a bigger public channel?
                  val channelFlags = ChannelFlags(announceChannel = true)
                  val cmd = OpenChannel(f.remoteNodeId, fundingAmount, None, None, None, None, None, Some(channelFlags), Some(Timeout(60 seconds)))
                  register ! Register.ForwardNodeId(context.system.ignoreRef, f.remoteNodeId, cmd)
                  val dailyVolume = f.peer.stats.take(Bucket.bucketsPerDay).map(_.totalAmountOut).sum
                  (available - fundingAmount, history.addFunding(f.remoteNodeId, FundingDecision(fundingAmount, dailyVolume, now)))
              }._2
          }
          run(history1.cleanup(now, 7 days))
      }
    }
  }

}
