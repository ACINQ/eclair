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
import fr.acinq.eclair.router.Router.{ChannelDesc, Data, hasChannels}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{BlockHeight, ShortChannelId, TimestampSecond, TxCoordinates}

import scala.concurrent.duration._

object StaleChannels {

  def handlePruneStaleChannels(d: Data, db: NetworkDb, currentBlockHeight: BlockHeight)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    // first we select channels that we will prune
    val staleChannelsMap = d.channels.filter { case (_, pc) => pc.isStale(currentBlockHeight) }
    val staleChannels = staleChannelsMap.values
    val staleChannelIds = staleChannels.map(_.ann.shortChannelId)
    // then we remove nodes that aren't tied to any channels anymore (and deduplicate them)
    val potentialStaleNodes = staleChannels.flatMap(c => Set(c.ann.nodeId1, c.ann.nodeId2)).toSet
    val channels1 = d.channels -- staleChannelIds
    val prunedChannels1 = d.prunedChannels ++ staleChannelsMap
    // no need to iterate on all nodes, just on those that are affected by current pruning
    val staleNodes = potentialStaleNodes.filterNot(nodeId => hasChannels(nodeId, channels1.values))

    staleChannelIds.foreach { shortChannelId =>
      log.info("pruning shortChannelId={} (stale)", shortChannelId)
      ctx.system.eventStream.publish(ChannelLost(shortChannelId))
    }

    val staleChannelsToRemove = staleChannels.flatMap(pc => Seq(ChannelDesc(pc.ann.shortChannelId, pc.ann.nodeId1, pc.ann.nodeId2), ChannelDesc(pc.ann.shortChannelId, pc.ann.nodeId2, pc.ann.nodeId1)))
    val graphWithBalances1 = d.graphWithBalances.removeEdges(staleChannelsToRemove)
    staleNodes.foreach { nodeId =>
      log.info("pruning nodeId={} (stale)", nodeId)
      db.removeNode(nodeId)
      ctx.system.eventStream.publish(NodeLost(nodeId))
    }
    d.copy(nodes = d.nodes -- staleNodes, channels = channels1, prunedChannels = prunedChannels1, graphWithBalances = graphWithBalances1)
  }

  def isStale(u: ChannelUpdate): Boolean = isStale(u.timestamp)

  def isStale(timestamp: TimestampSecond): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThreshold = TimestampSecond.now() - 14.days
    timestamp < staleThreshold
  }

  def isAlmostStale(timestamp: TimestampSecond): Boolean = {
    // we define almost stale as 2 weeks minus 4 days
    val almostStaleThreshold = TimestampSecond.now() - 10.days
    timestamp < almostStaleThreshold
  }

  /**
   * A channel is stale if:
   *  - it is older than 2 weeks (2*7*144 = 2016 blocks): we don't want to prune brand new channels for which we didn't
   *    yet receive a channel update
   *  - and has a channel update that is older than 2 weeks
   *
   * Note that we should not wait for *both* channel updates to be stale: as long as one of the peers is inactive, it's
   * very likely that we won't be able to route payments through that channel, so we should ignore it.
   *
   * @param update1_opt update corresponding to one side of the channel, if we have it
   * @param update2_opt update corresponding to the other side of the channel, if we have it
   */
  def isStale(channel: ChannelAnnouncement, update1_opt: Option[ChannelUpdate], update2_opt: Option[ChannelUpdate], currentBlockHeight: BlockHeight): Boolean = {
    val staleThresholdBlocks = currentBlockHeight - 2016
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(channel.shortChannelId)
    val channelIsOldEnough = blockHeight < staleThresholdBlocks
    val channelUpdateIsStale = update1_opt.forall(isStale) || update2_opt.forall(isStale)
    channelIsOldEnough && channelUpdateIsStale
  }

}
