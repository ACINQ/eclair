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

import akka.Done
import akka.actor.{ActorRef, Props}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Router.{RegisterToChanges, keep, remoteNodeIdKey, split}
import fr.acinq.eclair.wire.{NodeAnnouncement, QueryChannelRange, QueryShortChannelIds, ReplyShortChannelIdsEnd}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, ShortChannelId}
import kamon.Kamon

import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.Promise

class FrontRouter(routerConf: RouterConf, remoteRouter: ActorRef, initialized: Option[Promise[Done]] = None) extends FSMDiagnosticActorLogging[FrontRouter.State, FrontRouter.Data] {

  import FrontRouter._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  remoteRouter ! RegisterToChanges(self)

  startWith(SYNCING, Data(Map.empty, SortedMap.empty))

  when(SYNCING) {
    updateTable {
      case Event("done", d) =>
        log.info("sync done nodes={} channels={}", d.nodes.size, d.channels.size)
        initialized.map(_.success(Done))
        goto(NORMAL) using d
    }
  }

  when(NORMAL) {
    updateTable {
      case Event(s: SendChannelQuery, _) =>
        remoteRouter forward s
        stay

      case Event(PeerRoutingMessage(peerConnection, remoteNodeId, routingMessage@QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks, extendedQueryFlags_opt)), d) =>
        sender ! TransportHandler.ReadAck(routingMessage)
        Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
          Kamon.runWithSpan(Kamon.spanBuilder("query-channel-range").start(), finishSpan = true) {
            log.info("received query_channel_range with firstBlockNum={} numberOfBlocks={} extendedQueryFlags_opt={}", firstBlockNum, numberOfBlocks, extendedQueryFlags_opt)
            // keep channel ids that are in [firstBlockNum, firstBlockNum + numberOfBlocks]
            val shortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(firstBlockNum, numberOfBlocks, _))
            log.info("replying with {} items for range=({}, {})", shortChannelIds.size, firstBlockNum, numberOfBlocks)
            val chunks = Kamon.runWithSpan(Kamon.spanBuilder("split-channel-ids").start(), finishSpan = true) {
              split(shortChannelIds, firstBlockNum, numberOfBlocks, routerConf.channelRangeChunkSize)
            }

            Kamon.runWithSpan(Kamon.spanBuilder("compute-timestamps-checksums").start(), finishSpan = true) {
              chunks.foreach { chunk =>
                val reply = Router.buildReplyChannelRange(chunk, chainHash, routerConf.encodingType, routingMessage.queryFlags_opt, d.channels)
                peerConnection ! reply
              }
            }
            stay
          }
        }

      case Event(PeerRoutingMessage(peerConnection, remoteNodeId, routingMessage@QueryShortChannelIds(chainHash, shortChannelIds, _)), d) =>
        sender ! TransportHandler.ReadAck(routingMessage)

        Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
          Kamon.runWithSpan(Kamon.spanBuilder("query-short-channel-ids").start(), finishSpan = true) {

            val flags = routingMessage.queryFlags_opt.map(_.array).getOrElse(List.empty[Long])

            var channelCount = 0
            var updateCount = 0
            var nodeCount = 0

            Router.processChannelQuery(d.nodes, d.channels)(
              shortChannelIds.array,
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
            log.info("received query_short_channel_ids with {} items, sent back {} channels and {} updates and {} nodes", shortChannelIds.array.size, channelCount, updateCount, nodeCount)
            peerConnection ! ReplyShortChannelIdsEnd(chainHash, 1)
            stay
          }
        }

      case Event(msg: PeerRoutingMessage, _) =>
        log.info("forwarding peer routing message class={}", msg.message.getClass.getSimpleName)
        remoteRouter forward msg
        stay
    }
  }

  def updateTable(s: StateFunction): StateFunction = {
    case Event(NodesDiscovered(nodes), d) =>
      log.debug("adding {} nodes", nodes.size)
      val nodes1 = nodes.map(n => n.nodeId -> n).toMap
      stay using d.copy(nodes = d.nodes ++ nodes1)

    case Event(NodeUpdated(n), d) =>
      log.debug("updating {} nodes", 1)
      stay using d.copy(nodes = d.nodes + (n.nodeId -> n))

    case Event(NodeLost(nodeId), d) =>
      log.debug("removing {} nodes", 1)
      stay using d.copy(nodes = d.nodes - nodeId)

    case Event(ChannelsDiscovered(channels), d) =>
      log.debug("adding {} channels", channels.size)
      val channels1 = channels.foldLeft(SortedMap.empty[ShortChannelId, PublicChannel]) {
        case (channels, sc) => channels + (sc.ann.shortChannelId -> PublicChannel(sc.ann, ByteVector32.Zeroes, sc.capacity, sc.u1_opt, sc.u2_opt))
      }
      stay using d.copy(channels = d.channels ++ channels1)

    case Event(ChannelLost(channelId), d) =>
      log.debug("removing {} channels", 1)
      stay using d.copy(channels = d.channels - channelId)

    case Event(ChannelUpdatesReceived(updates), d) =>
      log.debug("adding/updating {} channel_updates", updates.size)
      val channels1 = updates.foldLeft(d.channels) {
        case (channels, u) => channels.get(u.shortChannelId) match {
          case Some(c) => channels + (c.ann.shortChannelId -> c.updateChannelUpdateSameSideAs(u))
          case None => channels
        }
      }
      stay using d.copy(channels = channels1)

    case Event(_: SyncProgress, _) =>
      // we receive this as part of network events but it's useless
      stay

    case event if s.isDefinedAt(event) => s(event)
  }

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    currentMessage match {
      case PeerRoutingMessage(_, remoteNodeId, _) => Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId))
      case _ => Logs.mdc(category_opt)
    }
  }
}

object FrontRouter {

  def props(routerConf: RouterConf, remoteRouter: ActorRef, initialized: Option[Promise[Done]] = None) = Props(new FrontRouter(routerConf: RouterConf, remoteRouter: ActorRef, initialized))

  // @formatter:off
  sealed trait State
  case object SYNCING extends State
  case object NORMAL extends State
  // @formatter:on

  case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: SortedMap[ShortChannelId, PublicChannel])

}