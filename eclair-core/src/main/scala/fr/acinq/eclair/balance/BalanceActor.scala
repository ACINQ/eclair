/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.balance

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, SatoshiLong}
import fr.acinq.eclair.NotificationsLogger
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.balance.BalanceActor._
import fr.acinq.eclair.balance.CheckBalance.{GlobalBalance, OffChainBalance}
import fr.acinq.eclair.balance.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.Utxo
import fr.acinq.eclair.channel.PersistentChannelData

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object BalanceActor {

  // @formatter:off
  sealed trait Command
  final case class ResetBalance(replyTo: ActorRef[Option[GlobalBalance]]) extends Command
  private final case object TickBalance extends Command
  final case class GetGlobalBalance(replyTo: ActorRef[Try[GlobalBalance]], channels: Map[ByteVector32, PersistentChannelData]) extends Command
  private final case class WrappedChannels(wrapped: ChannelsListener.GetChannelsResponse) extends Command
  private final case class WrappedGlobalBalanceWithChannels(wrapped: Try[GlobalBalance], channelsCount: Int) extends Command
  // @formatter:on

  def apply(bitcoinClient: BitcoinCoreClient, minDepth: Int, channelsListener: ActorRef[ChannelsListener.GetChannels], interval: FiniteDuration)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(TickBalance, interval)
        new BalanceActor(context, bitcoinClient, minDepth, channelsListener).apply(refBalance_opt = None, previousBalance_opt = None)
      }
    }
  }

}

private class BalanceActor(context: ActorContext[Command],
                           bitcoinClient: BitcoinCoreClient,
                           minDepth: Int,
                           channelsListener: ActorRef[ChannelsListener.GetChannels])(implicit ec: ExecutionContext) {

  private val log = context.log

  /**
   * @param refBalance_opt      the reference balance computed once at startup, useful for telling if we are making or losing money overall
   * @param previousBalance_opt the last computed balance, it is useful to make a detailed diff between two successive balance checks
   * @return
   */
  def apply(refBalance_opt: Option[GlobalBalance], previousBalance_opt: Option[GlobalBalance]): Behavior[Command] = Behaviors.receiveMessage {
    case ResetBalance(replyTo) =>
      log.info("resetting balance")
      // we use the last balance as new reference
      val newRefBalance_opt = previousBalance_opt
      replyTo ! previousBalance_opt
      apply(refBalance_opt = newRefBalance_opt, previousBalance_opt = previousBalance_opt)
    case TickBalance =>
      log.debug("checking balance...")
      channelsListener ! ChannelsListener.GetChannels(context.messageAdapter[ChannelsListener.GetChannelsResponse](WrappedChannels))
      Behaviors.same
    case WrappedChannels(res) =>
      val channelsCount = res.channels.size
      context.pipeToSelf(CheckBalance.computeGlobalBalance(res.channels, bitcoinClient, minDepth))(b => WrappedGlobalBalanceWithChannels(b, channelsCount))
      Behaviors.same
    case WrappedGlobalBalanceWithChannels(res, channelsCount) =>
      res match {
        case Success(balance) =>
          log.info("--------- balance details --------")
          // utxos metrics
          val utxos = balance.onChain.utxos
          val filteredByStatus: Map[String, Seq[Utxo]] = Map(
            Monitoring.Tags.UtxoStatuses.Confirmed -> utxos.filter(utxo => utxo.confirmations > 0),
            // We cannot create chains of unconfirmed transactions with more than 25 elements, so we ignore such utxos.
            Monitoring.Tags.UtxoStatuses.Unconfirmed -> utxos.filter(utxo => utxo.confirmations == 0 && utxo.ancestorCount_opt.getOrElse(1) < 25),
            Monitoring.Tags.UtxoStatuses.Safe -> utxos.filter(utxo => utxo.safe),
            Monitoring.Tags.UtxoStatuses.Unsafe -> utxos.filter(utxo => !utxo.safe),
          )
          filteredByStatus.foreach {
            case (status, filteredUtxos) =>
              val amount = filteredUtxos.map(_.amount.toSatoshi).sum.toMilliBtc
              log.info(s"we have {} {} utxos ({})", filteredUtxos.length, status, amount.toBtc)
              Monitoring.Metrics.UtxoCount.withTag(Monitoring.Tags.UtxoStatus, status).update(filteredUtxos.length)
              Monitoring.Metrics.BitcoinBalance.withTag(Monitoring.Tags.UtxoStatus, status).update(amount.toDouble)
          }
          previousBalance_opt match {
            case Some(previousBalance) =>
              // On-chain metrics:
              log.info("on-chain diff={}", balance.onChain.total - previousBalance.onChain.total)
              val utxosBefore = previousBalance.onChain.utxos.map(utxo => utxo.outPoint -> utxo).toMap
              val utxosAfter = balance.onChain.utxos.map(utxo => utxo.outPoint -> utxo).toMap
              val utxosAdded = (utxosAfter -- utxosBefore.keys).values
              val utxosRemoved = (utxosBefore -- utxosAfter.keys).values
              utxosAdded
                .toList.sortBy(_.amount)
                .foreach(utxo => log.info("+ utxo={} amount={}", utxo.outPoint, utxo.amount))
              utxosRemoved
                .toList.sortBy(_.amount)
                .foreach(utxo => log.info("- utxo={} amount={}", utxo.outPoint, utxo.amount))
              // Off-chain metrics:
              log.info("off-chain diff={}", balance.offChain.total - previousBalance.offChain.total)
              val offChainBalancesBefore = previousBalance.channels.view.mapValues(channel => OffChainBalance().addChannelBalance(channel, previousBalance.onChain.recentlySpentInputs).total)
              val offChainBalancesAfter = balance.channels.view.mapValues(channel => OffChainBalance().addChannelBalance(channel, balance.onChain.recentlySpentInputs).total)
              offChainBalancesAfter
                .map { case (channelId, balanceAfter) => (channelId, balanceAfter - offChainBalancesBefore.getOrElse(channelId, Btc(0))) }
                .filter { case (_, balanceDiff) => balanceDiff > 0.sat }
                .toList.sortBy(-_._2)
                .foreach { case (channelId, balanceDiff) => log.info("+ channelId={} amount={}", channelId, balanceDiff) }
              offChainBalancesBefore
                .map { case (channelId, balanceBefore) => (channelId, balanceBefore - offChainBalancesAfter.getOrElse(channelId, Btc(0))) }
                .filter { case (_, balanceDiff) => balanceDiff > 0.sat }
                .toList.sortBy(-_._2)
                .foreach { case (channelId, balanceDiff) => log.info("- channelId={} amount={}", channelId, balanceDiff) }
            case None => ()
          }
          log.info("current balance: total={} onchain.deeply-confirmed={} onchain.recently-confirmed={} onchain.unconfirmed={} offchain={}", balance.total.toDouble, balance.onChain.totalDeeplyConfirmed.toDouble, balance.onChain.totalRecentlyConfirmed.toDouble, balance.onChain.totalUnconfirmed.toDouble, balance.offChain.total.toDouble)
          log.debug("current balance details: {}", balance)
          // This is a very rough estimation of the fee we would need to pay for a force-close with 5 pending HTLCs at 100 sat/byte.
          val perChannelFeeBumpingReserve = 50_000.sat
          // Instead of scaling this linearly with the number of channels we have, we use sqrt(channelsCount) to reflect
          // the fact that if you have channels with many peers, only a subset of these peers will likely be malicious.
          val estimatedFeeBumpingReserve = perChannelFeeBumpingReserve * Math.sqrt(channelsCount)
          val totalConfirmedBalance = balance.onChain.totalDeeplyConfirmed + balance.onChain.totalRecentlyConfirmed
          if (totalConfirmedBalance < estimatedFeeBumpingReserve) {
            val message =
              s"""On-chain confirmed balance is low (${totalConfirmedBalance.toMilliBtc}): eclair may not be able to guarantee funds safety in case channels force-close.
                 |You have $channelsCount channels, which could cost $estimatedFeeBumpingReserve in fees if some of these channels are malicious.
                 |Please note that the value above is a very arbitrary estimation: the real cost depends on the feerate and the number of malicious channels.
                 |You should add more utxos to your bitcoin wallet to guarantee funds safety.
                 |""".stripMargin
            context.system.eventStream ! EventStream.Publish(NotifyNodeOperator(NotificationsLogger.Warning, message))
          }
          Metrics.GlobalBalance.withoutTags().update(balance.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnChainDeeplyConfirmed).update(balance.onChain.totalDeeplyConfirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnChainRecentlyConfirmed).update(balance.onChain.totalRecentlyConfirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnChainUnconfirmed).update(balance.onChain.totalUnconfirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OffChain).withTag(Tags.OffChainState, Tags.OffChainStates.waitForFundingConfirmed).update(balance.offChain.waitForFundingConfirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OffChain).withTag(Tags.OffChainState, Tags.OffChainStates.waitForChannelReady).update(balance.offChain.waitForChannelReady.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OffChain).withTag(Tags.OffChainState, Tags.OffChainStates.normal).update(balance.offChain.normal.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OffChain).withTag(Tags.OffChainState, Tags.OffChainStates.shutdown).update(balance.offChain.shutdown.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OffChain).withTag(Tags.OffChainState, Tags.OffChainStates.closing).update(balance.offChain.closing.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OffChain).withTag(Tags.OffChainState, Tags.OffChainStates.waitForPublishFutureCommitment).update(balance.offChain.waitForPublishFutureCommitment.toMilliBtc.toDouble)
          refBalance_opt match {
            case Some(refBalance) =>
              val normalizedValue = 100 + (if (refBalance.total.toSatoshi.toLong > 0) (balance.total.toSatoshi.toLong - refBalance.total.toSatoshi.toLong) * 1000D / refBalance.total.toSatoshi.toLong else 0)
              val diffValue = balance.total.toSatoshi.toLong - refBalance.total.toSatoshi.toLong
              log.info("relative balance: current={} reference={} normalized={} diff={}", balance.total.toDouble, refBalance.total.toDouble, normalizedValue, diffValue)
              Metrics.GlobalBalanceNormalized.withoutTags().update(normalizedValue)
              Metrics.GlobalBalanceDiff.withTag(Tags.DiffSign, Tags.DiffSigns.plus).update(diffValue.max(0).toDouble)
              Metrics.GlobalBalanceDiff.withTag(Tags.DiffSign, Tags.DiffSigns.minus).update((-diffValue).max(0).toDouble)
              apply(refBalance_opt = Some(refBalance), previousBalance_opt = Some(balance))
            case None =>
              log.info("using balance={} as reference", balance.total.toDouble)
              apply(refBalance_opt = Some(balance), previousBalance_opt = Some(balance))
          }
        case Failure(t) =>
          log.warn("could not compute balance: ", t)
          Behaviors.same
      }
    case GetGlobalBalance(replyTo, channels) =>
      CheckBalance.computeGlobalBalance(channels, bitcoinClient, minDepth) onComplete (replyTo ! _)
      Behaviors.same
  }
}
