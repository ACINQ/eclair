/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.blockchain.watchdogs

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.{BlockHeight, NodeParams, NotificationsLogger}
import sttp.client3.SttpBackend

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

/**
 * Created by t-bast on 29/09/2020.
 */

/** Monitor secondary blockchain sources to detect when we're being eclipsed. */
object BlockchainWatchdog {

  // @formatter:off
  case class BlockHeaderAt(blockHeight: BlockHeight, blockHeader: BlockHeader)
  case object NoBlockReceivedTimer

  sealed trait BlockchainWatchdogEvent
  /**
   * We are missing too many blocks compared to one of our blockchain watchdogs.
   * We may be eclipsed from the bitcoin network.
   *
   * @param recentHeaders headers fetched from the watchdog blockchain source.
   */
  case class DangerousBlocksSkew(recentHeaders: LatestHeaders) extends BlockchainWatchdogEvent

  sealed trait Command
  case class LatestHeaders(currentBlockHeight: BlockHeight, blockHeaders: Set[BlockHeaderAt], source: String) extends Command
  private[watchdogs] case class WrappedCurrentBlockHeight(currentBlockHeight: BlockHeight) extends Command
  private case class CheckLatestHeaders(currentBlockHeight: BlockHeight) extends Command
  private case class NoBlockReceivedSince(lastBlockHeight: BlockHeight) extends Command
  // @formatter:on

  /**
   * @param nodeParams     provides the chain we're interested in, connection parameters and the list of enabled sources
   * @param maxRandomDelay to avoid the herd effect whenever a block is created, we add a random delay before we query
   *                       secondary blockchain sources. This parameter specifies the maximum delay we'll allow.
   */
  def apply(nodeParams: NodeParams, maxRandomDelay: FiniteDuration, blockTimeout: FiniteDuration = 15 minutes): Behavior[Command] = {
    Behaviors.setup { context =>
      val socksProxy_opt = nodeParams.socksProxy_opt.flatMap(params => if (params.useForWatchdogs) Some(params) else None)
      implicit val sb: SttpBackend[Future, _] = ExplorerApi.createSttpBackend(socksProxy_opt)

      val explorers = Seq(
        ExplorerApi.BlockcypherExplorer(),
        // NB: if there is a proxy, we assume it is a tor proxy
        ExplorerApi.BlockstreamExplorer(useTorEndpoints = socksProxy_opt.isDefined),
        ExplorerApi.MempoolSpaceExplorer(useTorEndpoints = socksProxy_opt.isDefined)
      ).filter { e =>
        val enabled = nodeParams.blockchainWatchdogSources.contains(e.name)
        if (!enabled) {
          context.log.warn(s"blockchain watchdog ${e.name} is disabled")
        }
        enabled
      }

      val headersOverDnsEnabled = socksProxy_opt.isEmpty && nodeParams.blockchainWatchdogSources.contains(HeadersOverDns.Source)
      if (!headersOverDnsEnabled) {
        context.log.warn(s"blockchain watchdog ${HeadersOverDns.Source} is disabled")
      }

      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight](cbc => WrappedCurrentBlockHeight(cbc.blockHeight)))

      Behaviors.withTimers { timers =>
        // We start a timer to check blockchain watchdogs regularly even when we don't receive any block.
        timers.startSingleTimer(NoBlockReceivedTimer, NoBlockReceivedSince(BlockHeight(0)), blockTimeout)
        Behaviors.receiveMessage {
          case NoBlockReceivedSince(lastBlockCount) =>
            context.self ! CheckLatestHeaders(lastBlockCount)
            timers.startSingleTimer(NoBlockReceivedTimer, NoBlockReceivedSince(lastBlockCount), blockTimeout)
            Behaviors.same
          case WrappedCurrentBlockHeight(blockHeight) =>
            val delay = Random.nextInt(maxRandomDelay.toSeconds.toInt).seconds
            timers.startSingleTimer(CheckLatestHeaders(blockHeight), delay)
            timers.startSingleTimer(NoBlockReceivedTimer, NoBlockReceivedSince(blockHeight), blockTimeout)
            Behaviors.same
          case CheckLatestHeaders(blockHeight) =>
            val id = UUID.randomUUID()
            if (headersOverDnsEnabled) {
              context.spawn(HeadersOverDns(nodeParams.chainHash, blockHeight), s"${HeadersOverDns.Source}-${blockHeight}-$id") ! HeadersOverDns.CheckLatestHeaders(context.self)
            }
            explorers.foreach { explorer =>
              context.spawn(ExplorerApi(nodeParams.chainHash, blockHeight, explorer), s"${explorer.name}-${blockHeight}-$id") ! ExplorerApi.CheckLatestHeaders(context.self)
            }
            Behaviors.same
          case headers@LatestHeaders(blockHeight, blockHeaders, source) =>
            val missingBlocks = blockHeaders match {
              case h if h.isEmpty => 0
              case _ => blockHeaders.map(_.blockHeight).max - blockHeight
            }
            if (missingBlocks >= nodeParams.blockchainWatchdogThreshold) {
              context.log.warn("{}: we are {} blocks late: we may be eclipsed from the bitcoin network", source, missingBlocks)
              context.system.eventStream ! EventStream.Publish(DangerousBlocksSkew(headers))
              context.system.eventStream ! EventStream.Publish(NotifyNodeOperator(NotificationsLogger.Warning, s"we are $missingBlocks late according to $source: we may be eclipsed from the bitcoin network, check your bitcoind node."))
            } else {
              context.log.debug("{}: we are {} blocks late", source, missingBlocks)
            }
            Metrics.BitcoinBlocksSkew.withTag(Tags.Source, source).update(missingBlocks.toDouble)
            Behaviors.same
        }
      }
    }
  }

}
