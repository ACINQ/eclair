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
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash}
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog.BlockHeaderAt
import fr.acinq.eclair.blockchain.watchdogs.Monitoring.{Metrics, Tags}
import org.slf4j.Logger
import scodec.bits.ByteVector

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

  def apply(chainHash: BlockHash, currentBlockHeight: BlockHeight): Behavior[Command] = {
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
              val hostname = s"v2.$blockHeight.${blockHeight / 10_000}.bitcoinheaders.net"
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
            blockHeight <- parseBlockHeight(response)(context.log)
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
          context.log.warn(s"bitcoinheaders.net failed to resolve: $ex")
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

  private def parseBlockHeight(response: DnsProtocol.Resolved)(implicit log: Logger): Option[BlockHeight] = {
    // v2.height.(height / 10000).bitcoinheaders.net
    val parts = response.name.split('.')
    if (parts.length < 2) {
      log.error("bitcoinheaders.net response did not contain block height: {}", response)
      None
    } else {
      parts(1).toLongOption.map(l => BlockHeight(l))
    }
  }

  private def parseBlockHeader(response: DnsProtocol.Resolved)(implicit log: Logger): Option[BlockHeader] = {
    val addresses = response.records.collect { case record: AAAARecord => record.ip.getAddress }
    if (addresses.nonEmpty) {
      // From https://bitcoinheaders.net/:
      // All headers are encoded with an arbitrary one byte prefix (which you must ignore, as it may change in the
      // future), followed by a 0-indexed order byte (as nameservers often reorder responses). Entries are then prefixed
      // by a single version byte (currently version 1) and placed into the remaining bytes of the IPv6 addresses.
      // For example with the genesis block:
      // v2.0.0.bitcoinheaders.net. 604800 IN	AAAA	2603:7b12:b27a:c72c:3e67:768f:617f:c81b
      // v2.0.0.bitcoinheaders.net. 604800 IN	AAAA	2600:101::
      // v2.0.0.bitcoinheaders.net. 604800 IN	AAAA	2601::
      // v2.0.0.bitcoinheaders.net. 604800 IN	AAAA	2602::3b:a3ed:fd7a
      // v2.0.0.bitcoinheaders.net. 604800 IN	AAAA	2605:ab5f:49ff:ff00:1d1d:ac2b:7c00:0
      // v2.0.0.bitcoinheaders.net. 604800 IN	AAAA	2604:c388:8a51:323a:9fb8:aa4b:1e5e:4a29
      // Which decodes to 0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c.
      val data = addresses
        .filter(_.length >= 2)
        .map(_.tail) // the first byte is a prefix that we must ignore
        .sortBy(_.head) // the second byte is a 0-indexed order byte
        .flatMap(_.tail) // the remaining bytes contain the header chunks
      if (data.length < 81) {
        log.error("bitcoinheaders.net response did not contain a 1-byte version followed by a block header: {}", ByteVector(data).toHex)
        None
      } else if (data.head != 0x01) {
        log.error("bitcoinheaders.net response is not using version 1: version={}", data.head)
        None
      } else {
        Some(BlockHeader.read(data.tail.take(80).toArray))
      }
    } else {
      // When the block height is unknown, bitcoinheaders returns an empty response.
      None
    }
  }

}