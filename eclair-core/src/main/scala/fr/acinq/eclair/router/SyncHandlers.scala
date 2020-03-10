/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.router

import akka.actor.{ActorContext, ActorRef}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.router.Router.{addToSync, computeFlag, keep, remoteNodeIdKey, split, syncProgress}
import fr.acinq.eclair.wire.{EncodedShortChannelIds, EncodingType, QueryChannelRange, QueryShortChannelIds, QueryShortChannelIdsTlv, ReplyChannelRange, ReplyChannelRangeTlv, ReplyShortChannelIdsEnd, TlvStream}
import kamon.Kamon

import scala.annotation.tailrec
import scala.collection.SortedSet

object SyncHandlers {

  def handleQueryChannelRange(d: Data, routerConf: RouterConf, peerConnection: ActorRef, remoteNodeId: PublicKey, q: QueryChannelRange)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    ctx.sender ! TransportHandler.ReadAck(q)
    Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
      Kamon.runWithSpan(Kamon.spanBuilder("query-channel-range").start(), finishSpan = true) {
        log.info("received query_channel_range with firstBlockNum={} numberOfBlocks={} extendedQueryFlags_opt={}", q.firstBlockNum, q.numberOfBlocks, q.tlvStream)
        // keep channel ids that are in [firstBlockNum, firstBlockNum + numberOfBlocks]
        val shortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(q.firstBlockNum, q.numberOfBlocks, _))
        log.info("replying with {} items for range=({}, {})", shortChannelIds.size, q.firstBlockNum, q.numberOfBlocks)
        val chunks = Kamon.runWithSpan(Kamon.spanBuilder("split-channel-ids").start(), finishSpan = true) {
          split(shortChannelIds, q.firstBlockNum, q.numberOfBlocks, routerConf.channelRangeChunkSize)
        }

        Kamon.runWithSpan(Kamon.spanBuilder("compute-timestamps-checksums").start(), finishSpan = true) {
          chunks.foreach { chunk =>
            val reply = Router.buildReplyChannelRange(chunk, q.chainHash, routerConf.encodingType, q.queryFlags_opt, d.channels)
            peerConnection ! reply
          }
        }
        d
      }
    }
  }

  def handleReplyChannelRange(d: Data, routerConf: RouterConf, peerConnection: ActorRef, remoteNodeId: PublicKey, r: ReplyChannelRange)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    ctx.sender ! TransportHandler.ReadAck(r)

    Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
      Kamon.runWithSpan(Kamon.spanBuilder("reply-channel-range").start(), finishSpan = true) {

        @tailrec
        def loop(ids: List[ShortChannelId], timestamps: List[ReplyChannelRangeTlv.Timestamps], checksums: List[ReplyChannelRangeTlv.Checksums], acc: List[ShortChannelIdAndFlag] = List.empty[ShortChannelIdAndFlag]): List[ShortChannelIdAndFlag] = {
          ids match {
            case Nil => acc.reverse
            case head :: tail =>
              val flag = computeFlag(d.channels)(head, timestamps.headOption, checksums.headOption, routerConf.requestNodeAnnouncements)
              // 0 means nothing to query, just don't include it
              val acc1 = if (flag != 0) ShortChannelIdAndFlag(head, flag) :: acc else acc
              loop(tail, timestamps.drop(1), checksums.drop(1), acc1)
          }
        }

        val timestamps_opt = r.timestamps_opt.map(_.timestamps).getOrElse(List.empty[ReplyChannelRangeTlv.Timestamps])
        val checksums_opt = r.checksums_opt.map(_.checksums).getOrElse(List.empty[ReplyChannelRangeTlv.Checksums])

        val shortChannelIdAndFlags = Kamon.runWithSpan(Kamon.spanBuilder("compute-flags").start(), finishSpan = true) {
          loop(r.shortChannelIds.array, timestamps_opt, checksums_opt)
        }

        val (channelCount, updatesCount) = shortChannelIdAndFlags.foldLeft((0, 0)) {
          case ((c, u), ShortChannelIdAndFlag(_, flag)) =>
            val c1 = c + (if (QueryShortChannelIdsTlv.QueryFlagType.includeChannelAnnouncement(flag)) 1 else 0)
            val u1 = u + (if (QueryShortChannelIdsTlv.QueryFlagType.includeUpdate1(flag)) 1 else 0) + (if (QueryShortChannelIdsTlv.QueryFlagType.includeUpdate2(flag)) 1 else 0)
            (c1, u1)
        }
        log.info(s"received reply_channel_range with {} channels, we're missing {} channel announcements and {} updates, format={}", r.shortChannelIds.array.size, channelCount, updatesCount, r.shortChannelIds.encoding)

        def buildQuery(chunk: List[ShortChannelIdAndFlag]): QueryShortChannelIds = {
          // always encode empty lists as UNCOMPRESSED
          val encoding = if (chunk.isEmpty) EncodingType.UNCOMPRESSED else r.shortChannelIds.encoding
          QueryShortChannelIds(r.chainHash,
            shortChannelIds = EncodedShortChannelIds(encoding, chunk.map(_.shortChannelId)),
            if (r.timestamps_opt.isDefined || r.checksums_opt.isDefined)
              TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(encoding, chunk.map(_.flag)))
            else
              TlvStream.empty
          )
        }

        // we update our sync data to this node (there may be multiple channel range responses and we can only query one set of ids at a time)
        val replies = shortChannelIdAndFlags
          .grouped(routerConf.channelQueryChunkSize)
          .map(buildQuery)
          .toList

        val (sync1, replynow_opt) = addToSync(d.sync, remoteNodeId, replies)
        // we only send a reply right away if there were no pending requests
        replynow_opt.foreach(peerConnection ! _)
        val progress = syncProgress(sync1)
        ctx.system.eventStream.publish(progress)
        ctx.self ! progress
        d.copy(sync = sync1)
      }
    }
  }

  def handleQueryShortChannelIds(d: Data, routerConf: RouterConf, peerConnection: ActorRef, remoteNodeId: PublicKey, q: QueryShortChannelIds)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    ctx.sender ! TransportHandler.ReadAck(q)

    Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
      Kamon.runWithSpan(Kamon.spanBuilder("query-short-channel-ids").start(), finishSpan = true) {

        val flags = q.queryFlags_opt.map(_.array).getOrElse(List.empty[Long])

        var channelCount = 0
        var updateCount = 0
        var nodeCount = 0

        Router.processChannelQuery(d.nodes, d.channels)(
          q.shortChannelIds.array,
          flags,
          ca => {
            channelCount = channelCount + 1
            peerConnection ! ca
          },
          cu => {
            updateCount = updateCount + 1
            peerConnection ! cu
          },
          na => {
            nodeCount = nodeCount + 1
            peerConnection ! na
          }
        )
        log.info("received query_short_channel_ids with {} items, sent back {} channels and {} updates and {} nodes", q.shortChannelIds.array.size, channelCount, updateCount, nodeCount)
        peerConnection ! ReplyShortChannelIdsEnd(q.chainHash, 1)
        d
      }
    }
  }

  def handleReplyShortChannelIdsEnd(d: Data, peerConnection: ActorRef, remoteNodeId: PublicKey, r: ReplyShortChannelIdsEnd)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    ctx.sender ! TransportHandler.ReadAck(r)
    // have we more channels to ask this peer?
    val sync1 = d.sync.get(remoteNodeId) match {
      case Some(sync) =>
        sync.pending match {
          case nextRequest +: rest =>
            log.info(s"asking for the next slice of short_channel_ids (remaining=${sync.pending.size}/${sync.total})")
            peerConnection ! nextRequest
            d.sync + (remoteNodeId -> sync.copy(pending = rest))
          case Nil =>
            // we received reply_short_channel_ids_end for our last query and have not sent another one, we can now remove
            // the remote peer from our map
            log.info(s"sync complete (total=${sync.total})")
            d.sync - remoteNodeId
        }
      case _ => d.sync
    }
    val progress = syncProgress(sync1)
    ctx.system.eventStream.publish(progress)
    ctx.self ! progress
    d.copy(sync = sync1)
  }

}
