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
import fr.acinq.bitcoin.{BlockHeader, ByteVector32}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}

import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

/**
 * Created by t-bast on 29/09/2020.
 */

/** Monitor secondary blockchain sources to detect when we're being eclipsed. */
object BlockchainWatchdog {

  case class BlockHeaderAt(blockCount: Long, blockHeader: BlockHeader)

  // @formatter:off
  sealed trait BlockchainWatchdogEvent
  /**
   * We are missing too many blocks compared to one of our blockchain watchdogs.
   * We may be eclipsed from the bitcoin network.
   *
   * @param recentHeaders headers fetched from the watchdog blockchain source.
   */
  case class DangerousBlocksSkew(recentHeaders: LatestHeaders) extends BlockchainWatchdogEvent

  sealed trait Command
  case class LatestHeaders(currentBlockCount: Long, blockHeaders: Set[BlockHeaderAt], source: String) extends Command
  private[watchdogs] case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  private case class CheckLatestHeaders(currentBlockCount: Long) extends Command
  // @formatter:on

  /**
   * @param chainHash      chain we're interested in.
   * @param maxRandomDelay to avoid the herd effect whenever a block is created, we add a random delay before we query
   *                       secondary blockchain sources. This parameter specifies the maximum delay we'll allow.
   */
  def apply(chainHash: ByteVector32, maxRandomDelay: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
      Behaviors.withTimers { timers =>
        Behaviors.receiveMessage {
          case WrappedCurrentBlockCount(blockCount) =>
            val delay = Random.nextInt(maxRandomDelay.toSeconds.toInt).seconds
            timers.startSingleTimer(CheckLatestHeaders(blockCount), delay)
            Behaviors.same
          case CheckLatestHeaders(blockCount) =>
            val id = UUID.randomUUID()
            context.spawn(HeadersOverDns(chainHash, blockCount), s"${HeadersOverDns.Source}-$blockCount-$id") ! HeadersOverDns.CheckLatestHeaders(context.self)
            context.spawn(ExplorerApi(chainHash, blockCount, ExplorerApi.BlockstreamExplorer()), s"blockstream-$blockCount-$id") ! ExplorerApi.CheckLatestHeaders(context.self)
            context.spawn(ExplorerApi(chainHash, blockCount, ExplorerApi.BlockcypherExplorer()), s"blockcypher-$blockCount-$id") ! ExplorerApi.CheckLatestHeaders(context.self)
            context.spawn(ExplorerApi(chainHash, blockCount, ExplorerApi.MempoolSpaceExplorer()), s"mempool.space-$blockCount-$id") ! ExplorerApi.CheckLatestHeaders(context.self)
            Behaviors.same
          case headers@LatestHeaders(blockCount, blockHeaders, source) =>
            val missingBlocks = blockHeaders match {
              case h if h.isEmpty => 0
              case _ => blockHeaders.map(_.blockCount).max - blockCount
            }
            if (missingBlocks >= 6) {
              context.log.warn("{}: we are {} blocks late: we may be eclipsed from the bitcoin network", source, missingBlocks)
              context.system.eventStream ! EventStream.Publish(DangerousBlocksSkew(headers))
            } else {
              context.log.info("{}: we are {} blocks late", source, missingBlocks)
            }
            Metrics.BitcoinBlocksSkew.withTag(Tags.Source, source).update(missingBlocks)
            Behaviors.same
        }
      }
    }
  }

}
