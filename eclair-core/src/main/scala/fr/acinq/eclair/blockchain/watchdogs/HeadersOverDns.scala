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

import akka.actor.Status
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.{ActorRef, Behavior}
import akka.io.dns.{AAAARecord, DnsProtocol}
import akka.io.{Dns, IO}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.BlockHeaderAt
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import org.slf4j.Logger
import scodec.bits.BitVector

/**
 * Created by t-bast on 29/09/2020.
 */

/** This actor queries https://bitcoinheaders.net/ to fetch block headers. */
object HeadersOverDns {

  val Source = "bitcoinheaders.net"

  // @formatter:off
  sealed trait Command
  case class CheckLatestHeaders(replyTo: ActorRef[BlockchainWatchdog.LatestHeaders]) extends Command
  private case class WrappedDnsResolved(response: DnsProtocol.Resolved) extends Command
  private case class WrappedDnsFailed(cause: Throwable) extends Command
  // @formatter:on

  def apply(chainHash: ByteVector32, currentBlockHeight: BlockHeight): Behavior[Command] = {
    Behaviors.setup { context =>
      val dnsAdapters = {
        context.messageAdapter[DnsProtocol.Resolved](WrappedDnsResolved)
        context.messageAdapter[Status.Failure](f => WrappedDnsFailed(f.cause))
      }.toClassic
      Behaviors.receiveMessage {
        case CheckLatestHeaders(replyTo) => chainHash match {
          case Block.LivenetGenesisBlock.hash =>
            // We try to get the next 10 blocks; if we're late by more than 10 blocks, this is bad, no need to even look further.
            (currentBlockHeight.toLong until currentBlockHeight.toLong + 10).foreach(blockHeight => {
              val hostname = s"$blockHeight.${blockHeight / 10000}.bitcoinheaders.net"
              IO(Dns)(context.system.classicSystem).tell(DnsProtocol.resolve(hostname, DnsProtocol.Ip(ipv4 = false, ipv6 = true)), dnsAdapters)
            })
            collect(replyTo, currentBlockHeight, Set.empty, 10)
          case _ =>
            context.log.debug("bitcoinheaders.net is only supported on mainnet - skipped")
            Behaviors.stopped
        }
        case _ => Behaviors.unhandled
      }
    }
  }

  private def collect(replyTo: ActorRef[BlockchainWatchdog.LatestHeaders], currentBlockHeight: BlockHeight, received: Set[BlockHeaderAt], remaining: Int): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case WrappedDnsResolved(response) =>
          val blockHeader_opt = for {
            blockHeight <- parseBlockCount(response)(context.log)
            blockHeader <- parseBlockHeader(response)(context.log)
          } yield BlockHeaderAt(blockHeight, blockHeader)
          val received1 = blockHeader_opt match {
            case Some(blockHeader) => received + blockHeader
            case None =>
              Metrics.WatchdogError.withTag(Tags.Source, Source).increment()
              received
          }
          stopOrCollect(replyTo, currentBlockHeight, received1, remaining - 1)

        case WrappedDnsFailed(ex) =>
          context.log.warn("bitcoinheaders.net failed to resolve: {}", ex)
          stopOrCollect(replyTo, currentBlockHeight, received, remaining - 1)

        case _ => Behaviors.unhandled
      }
    }
  }

  private def stopOrCollect(replyTo: ActorRef[BlockchainWatchdog.LatestHeaders], currentBlockHeight: BlockHeight, received: Set[BlockHeaderAt], remaining: Int): Behavior[Command] = remaining match {
    case 0 =>
      replyTo ! BlockchainWatchdog.LatestHeaders(currentBlockHeight, received, Source)
      Behaviors.stopped
    case _ =>
      collect(replyTo, currentBlockHeight, received, remaining)
  }

  private def parseBlockCount(response: DnsProtocol.Resolved)(implicit log: Logger): Option[BlockHeight] = {
    response.name.split('.').headOption match {
      case Some(blockHeight) => blockHeight.toLongOption.map(l => BlockHeight(l))
      case None =>
        log.error("bitcoinheaders.net response did not contain block count: {}", response)
        None
    }
  }

  private def parseBlockHeader(response: DnsProtocol.Resolved)(implicit log: Logger): Option[BlockHeader] = {
    val addresses = response.records.collect { case record: AAAARecord => record.ip.getAddress }
    if (addresses.nonEmpty) {
      val countOk = addresses.length == 6
      // addresses must be prefixed with 0x2001
      val prefixOk = addresses.forall(_.startsWith(Array(0x20.toByte, 0x01.toByte)))
      // the first nibble after the prefix encodes the order since nameservers often reorder responses
      val orderOk = addresses.map(a => a(2) & 0xf0).toSet == Set(0x00, 0x10, 0x20, 0x30, 0x40, 0x50)
      if (countOk && prefixOk && orderOk) {
        val header = addresses.sortBy(a => a(2)).foldLeft(BitVector.empty) {
          case (current, address) =>
            // The first address contains an additional 0x00 prefix
            val toDrop = if (current.isEmpty) 28 else 20
            current ++ BitVector(address).drop(toDrop)
        }.bytes
        header.length match {
          case 80 => Some(BlockHeader.read(header.toArray))
          case _ =>
            log.error("bitcoinheaders.net response did not contain block header (invalid length): {}", response)
            None
        }
      } else {
        log.error("invalid response from bitcoinheaders.net: {}", response)
        None
      }
    } else {
      // Instead of not resolving the DNS request when block height is unknown, bitcoinheaders sometimes returns an empty response.
      None
    }
  }

}