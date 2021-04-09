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

package fr.acinq.eclair.router

import akka.actor.ActorContext
import akka.event.LoggingAdapter
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Router.{ChannelDesc, Data, PublicChannel, hasChannels}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{ShortChannelId, TxCoordinates}

import scala.collection.mutable
import scala.concurrent.duration._

object StaleChannels {

  def handlePruneStaleChannels(d: Data, db: NetworkDb, currentBlockHeight: Long)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    // first we select channels that we will prune
    val staleChannels = getStaleChannels(d.channels.values, currentBlockHeight)
    val staleChannelIds = staleChannels.map(_.ann.shortChannelId)
    // then we remove nodes that aren't tied to any channels anymore (and deduplicate them)
    val potentialStaleNodes = staleChannels.flatMap(c => Set(c.ann.nodeId1, c.ann.nodeId2)).toSet
    val channels1 = d.channels -- staleChannelIds
    // no need to iterate on all nodes, just on those that are affected by current pruning
    val staleNodes = potentialStaleNodes.filterNot(nodeId => hasChannels(nodeId, channels1.values))

    // let's clean the db and send the events
    db.removeChannels(staleChannelIds) // NB: this also removes channel updates
    // we keep track of recently pruned channels so we don't revalidate them (zombie churn)
    db.addToPruned(staleChannelIds)
    staleChannelIds.foreach { shortChannelId =>
      log.info("pruning shortChannelId={} (stale)", shortChannelId)
      ctx.system.eventStream.publish(ChannelLost(shortChannelId))
    }

    val staleChannelsToRemove = new mutable.ArrayBuffer[ChannelDesc]
    staleChannels.foreach(ca => {
      staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId1, ca.ann.nodeId2)
      staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId2, ca.ann.nodeId1)
    })

    val graph1 = d.graph.removeEdges(staleChannelsToRemove)
    staleNodes.foreach {
      nodeId =>
        log.info("pruning nodeId={} (stale)", nodeId)
        db.removeNode(nodeId)
        ctx.system.eventStream.publish(NodeLost(nodeId))
    }
    d.copy(nodes = d.nodes -- staleNodes, channels = channels1, graph = graph1)
  }

  def isStale(u: ChannelUpdate): Boolean = isStale(u.timestamp)

  def isStale(timestamp: Long): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds = (System.currentTimeMillis.milliseconds - 14.days).toSeconds
    timestamp < staleThresholdSeconds
  }

  def isAlmostStale(timestamp: Long): Boolean = {
    // we define almost stale as 2 weeks minus 4 days
    val staleThresholdSeconds = (System.currentTimeMillis.milliseconds - 10.days).toSeconds
    timestamp < staleThresholdSeconds
  }

  /**
   * Is stale a channel that:
   * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
   * AND
   * (2) has no channel_update younger than 2 weeks
   *
   * @param update1_opt update corresponding to one side of the channel, if we have it
   * @param update2_opt update corresponding to the other side of the channel, if we have it
   * @return
   */
  def isStale(channel: ChannelAnnouncement, update1_opt: Option[ChannelUpdate], update2_opt: Option[ChannelUpdate], currentBlockHeight: Long): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update, so we keep them as long as they are less than 2 weeks (2016 blocks) old
    val staleThresholdBlocks = currentBlockHeight - 2016
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(channel.shortChannelId)
    blockHeight < staleThresholdBlocks && update1_opt.forall(isStale) && update2_opt.forall(isStale)
  }

  def getStaleChannels(channels: Iterable[PublicChannel], currentBlockHeight: Long): Iterable[PublicChannel] = channels.filter(data => isStale(data.ann, data.update_1_opt, data.update_2_opt, currentBlockHeight))

}
