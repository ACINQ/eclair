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
import com.softwaremill.sttp.{SttpBackend, SttpBackendOptions}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.{BlockHeader, ByteVector32}
import fr.acinq.eclair.{NodeParams, randomBytes}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.tor.Socks5ProxyParams

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
  case class BlockHeaderAt(blockCount: Long, blockHeader: BlockHeader)
  case object NoBlockReceivedTimer

  trait SupportsTor {
    /** Tor proxy connection parameters */
    def socksProxy_opt: Option[Socks5ProxyParams]
  }

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
  private case class NoBlockReceivedSince(lastBlockCount: Long) extends Command
  // @formatter:on

  /**
   * @param chainHash      chain we're interested in.
   * @param maxRandomDelay to avoid the herd effect whenever a block is created, we add a random delay before we query
   *                       secondary blockchain sources. This parameter specifies the maximum delay we'll allow.
   */
  def apply(nodeParams: NodeParams, maxRandomDelay: FiniteDuration, blockTimeout: FiniteDuration = 15 minutes): Behavior[Command] = {
    Behaviors.setup { context =>
      implicit val sttpBackend = ExplorerApi.createSttpBackend(nodeParams.socksProxy_opt)
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
      Behaviors.withTimers { timers =>
        // We start a timer to check blockchain watchdogs regularly even when we don't receive any block.
        timers.startSingleTimer(NoBlockReceivedTimer, NoBlockReceivedSince(0), blockTimeout)
        Behaviors.receiveMessage {
          case NoBlockReceivedSince(lastBlockCount) =>
            context.self ! CheckLatestHeaders(lastBlockCount)
            timers.startSingleTimer(NoBlockReceivedTimer, NoBlockReceivedSince(lastBlockCount), blockTimeout)
            Behaviors.same
          case WrappedCurrentBlockCount(blockCount) =>
            val delay = Random.nextInt(maxRandomDelay.toSeconds.toInt).seconds
            timers.startSingleTimer(CheckLatestHeaders(blockCount), delay)
            timers.startSingleTimer(NoBlockReceivedTimer, NoBlockReceivedSince(blockCount), blockTimeout)
            Behaviors.same
          case CheckLatestHeaders(blockCount) =>
            val id = UUID.randomUUID()
            val chainHash = nodeParams.chainHash
            val socksProxy_opt = nodeParams.socksProxy_opt
            val sources = nodeParams.blockchainWatchdogSources
            if (socksProxy_opt.isEmpty && sources.contains(HeadersOverDns.Source)) {
              context.spawn(HeadersOverDns(chainHash, blockCount), s"${HeadersOverDns.Source}-$blockCount-$id") ! HeadersOverDns.CheckLatestHeaders(context.self)
            } else {
              context.log.warn(s"blockchain watchdog ${HeadersOverDns.Source} is disabled")
            }
            val explorers = Seq(ExplorerApi.BlockstreamExplorer(socksProxy_opt), ExplorerApi.BlockcypherExplorer(socksProxy_opt), ExplorerApi.MempoolSpaceExplorer(socksProxy_opt))
              .foldLeft(Seq.empty[ExplorerApi.Explorer]) { (acc, w) =>
                if (sources.contains(w.name)) {
                  acc :+ w
                } else {
                  context.log.warn(s"blockchain watchdog ${w.name} is disabled")
                  acc
                }
              }
            explorers.foreach { explorer =>
              context.spawn(ExplorerApi(chainHash, blockCount, explorer), s"${explorer.name}-$blockCount-$id") ! ExplorerApi.CheckLatestHeaders(context.self)
            }
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
            Metrics.BitcoinBlocksSkew.withTag(Tags.Source, source).update(missingBlocks.toDouble)
            Behaviors.same
        }
      }
    }
  }

}
