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

import akka.actor.{ActorContext, ActorRef}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.router.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, RealShortChannelId, TimestampSecond, TimestampSecondLong, serializationResult}
import scodec.bits.ByteVector
import shapeless.HNil

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.util.Random

object Sync {

  // maximum number of ids we can keep in a single chunk and still have an encoded reply that is smaller than 65Kb
  // please note that:
  // - this is based on the worst case scenario where peer want timestamps and checksums and the reply is not compressed
  // - the maximum number of public channels in a single block so far is less than 300, and the maximum number of tx per
  //   block almost never exceeds 2800 so this should very rarely be limiting
  val MAXIMUM_CHUNK_SIZE = 2700

  def handleSendChannelQuery(d: Data, s: SendChannelQuery)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    // we currently send query_channel_range when:
    //  * we just (re)connected to a peer with whom we have channels
    //  * we validate our first channel with a peer
    // we must ensure we don't send a new query_channel_range while another query is still in progress
    if (s.replacePrevious || !d.sync.contains(s.remoteNodeId)) {
      // ask for everything
      val query = QueryChannelRange(s.chainHash, firstBlock = BlockHeight(0), numberOfBlocks = Int.MaxValue.toLong, TlvStream(s.flags_opt.toSet))
      log.info("sending query_channel_range={}", query)
      s.to ! query

      // we also set a pass-all filter for now (we can update it later) for the future gossip messages, by setting
      // the first_timestamp field to the current date/time and timestamp_range to the maximum value
      // NB: we can't just set firstTimestamp to 0, because in that case peer would send us all past messages matching
      // that (i.e. the whole routing table)
      val filter = GossipTimestampFilter(s.chainHash, firstTimestamp = TimestampSecond.now(), timestampRange = Int.MaxValue)
      s.to ! filter

      // reset our sync state for this peer: we create an entry to ensure we reject duplicate queries and unsolicited reply_channel_range
      d.copy(sync = d.sync + (s.remoteNodeId -> Syncing(Nil, 0)))
    } else {
      log.debug("not sending query_channel_range: sync already in progress")
      d
    }
  }

  def handleQueryChannelRange(channels: SortedMap[RealShortChannelId, PublicChannel], routerConf: RouterConf, origin: RemoteGossip, q: QueryChannelRange)(implicit ctx: ActorContext, log: LoggingAdapter): Unit = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    ctx.sender() ! TransportHandler.ReadAck(q)
    Metrics.QueryChannelRange.Blocks.withoutTags().record(q.numberOfBlocks)
    log.info("received query_channel_range with firstBlockNum={} numberOfBlocks={} extendedQueryFlags_opt={}", q.firstBlock, q.numberOfBlocks, q.tlvStream)
    // keep channel ids that are in [firstBlockNum, firstBlockNum + numberOfBlocks]
    val shortChannelIds: SortedSet[RealShortChannelId] = channels.keySet.filter(keep(q.firstBlock, q.numberOfBlocks, _))
    log.info("replying with {} items for range=({}, {})", shortChannelIds.size, q.firstBlock, q.numberOfBlocks)
    val chunks = split(shortChannelIds, q.firstBlock, q.numberOfBlocks, routerConf.channelRangeChunkSize)
    Metrics.QueryChannelRange.Replies.withoutTags().record(chunks.size)
    chunks.zipWithIndex.foreach { case (chunk, i) =>
      val syncComplete = i == chunks.size - 1
      val reply = buildReplyChannelRange(chunk, syncComplete, q.chainHash, routerConf.encodingType, q.queryFlags_opt, channels)
      origin.peerConnection ! reply
      Metrics.ReplyChannelRange.Blocks.withTag(Tags.Direction, Tags.Directions.Outgoing).record(reply.numberOfBlocks)
      Metrics.ReplyChannelRange.ShortChannelIds.withTag(Tags.Direction, Tags.Directions.Outgoing).record(reply.shortChannelIds.array.size)
    }
  }

  def handleReplyChannelRange(d: Data, routerConf: RouterConf, origin: RemoteGossip, r: ReplyChannelRange)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    ctx.sender() ! TransportHandler.ReadAck(r)

    d.sync.get(origin.nodeId) match {
      case None =>
        log.info("received unsolicited reply_channel_range with {} channels", r.shortChannelIds.array.size)
        d // we didn't request a sync from this node, ignore
      case Some(currentSync) if currentSync.remainingQueries.isEmpty && r.shortChannelIds.array.isEmpty =>
        // NB: this case deals with peers who don't return any sync data. We're currently not correctly detecting the end
        // of a stream of reply_channel_range, but it's not an issue in practice (we instead rely on the remaining query_short_channel_ids).
        // We should fix that once https://github.com/lightningnetwork/lightning-rfc/pull/826 is deployed.
        log.info("received empty reply_channel_range, sync is complete")
        d.copy(sync = d.sync - origin.nodeId)
      case Some(currentSync) =>
        Metrics.ReplyChannelRange.Blocks.withTag(Tags.Direction, Tags.Directions.Incoming).record(r.numberOfBlocks)
        Metrics.ReplyChannelRange.ShortChannelIds.withTag(Tags.Direction, Tags.Directions.Incoming).record(r.shortChannelIds.array.size)

        @tailrec
        def loop(ids: List[RealShortChannelId], timestamps: List[ReplyChannelRangeTlv.Timestamps], checksums: List[ReplyChannelRangeTlv.Checksums], acc: List[ShortChannelIdAndFlag] = List.empty[ShortChannelIdAndFlag]): List[ShortChannelIdAndFlag] = {
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
        val shortChannelIdAndFlags = loop(r.shortChannelIds.array, timestamps_opt, checksums_opt)
        val (channelCount, updatesCount) = shortChannelIdAndFlags.foldLeft((0, 0)) {
          case ((c, u), ShortChannelIdAndFlag(_, flag)) =>
            val c1 = c + (if (QueryShortChannelIdsTlv.QueryFlagType.includeChannelAnnouncement(flag)) 1 else 0)
            val u1 = u + (if (QueryShortChannelIdsTlv.QueryFlagType.includeUpdate1(flag)) 1 else 0) + (if (QueryShortChannelIdsTlv.QueryFlagType.includeUpdate2(flag)) 1 else 0)
            (c1, u1)
        }
        log.info(s"received reply_channel_range with {} channels, we're missing {} channel announcements and {} updates, format={}", r.shortChannelIds.array.size, channelCount, updatesCount, r.shortChannelIds.encoding)
        Metrics.ReplyChannelRange.NewChannelAnnouncements.withoutTags().record(channelCount)
        Metrics.ReplyChannelRange.NewChannelUpdates.withoutTags().record(updatesCount)

        def buildQuery(chunk: List[ShortChannelIdAndFlag]): QueryShortChannelIds = {
          // always encode empty lists as UNCOMPRESSED
          val encoding = if (chunk.isEmpty) EncodingType.UNCOMPRESSED else r.shortChannelIds.encoding
          val flags: TlvStream[QueryShortChannelIdsTlv] = if (r.timestamps_opt.isDefined || r.checksums_opt.isDefined) {
            TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(encoding, chunk.map(_.flag)))
          } else {
            TlvStream.empty
          }
          QueryShortChannelIds(r.chainHash, EncodedShortChannelIds(encoding, chunk.map(_.shortChannelId)), flags)
        }

        // we update our sync data to this node (there may be multiple channel range responses and we can only query one set of ids at a time)
        val replies = shortChannelIdAndFlags
          .grouped(routerConf.channelQueryChunkSize)
          .map(buildQuery)
          .toList

        val (sync1, replynow_opt) = addToSync(d.sync, currentSync, origin.nodeId, replies)
        // we only send a reply right away if there were no pending requests
        replynow_opt.foreach(origin.peerConnection ! _)
        val progress = syncProgress(sync1)
        ctx.system.eventStream.publish(progress)
        ctx.self ! progress
        d.copy(sync = sync1)
    }
  }

  def handleQueryShortChannelIds(nodes: Map[PublicKey, NodeAnnouncement], channels: SortedMap[RealShortChannelId, PublicChannel], origin: RemoteGossip, q: QueryShortChannelIds)(implicit ctx: ActorContext, log: LoggingAdapter): Unit = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    ctx.sender() ! TransportHandler.ReadAck(q)

    val flags = q.queryFlags_opt.map(_.array).getOrElse(List.empty[Long])
    var channelCount = 0
    var updateCount = 0
    var nodeCount = 0

    processChannelQuery(nodes, channels)(
      q.shortChannelIds.array,
      flags,
      ca => {
        channelCount = channelCount + 1
        origin.peerConnection ! ca
      },
      cu => {
        updateCount = updateCount + 1
        origin.peerConnection ! cu
      },
      na => {
        nodeCount = nodeCount + 1
        origin.peerConnection ! na
      }
    )
    Metrics.QueryShortChannelIds.Nodes.withoutTags().record(nodeCount)
    Metrics.QueryShortChannelIds.ChannelAnnouncements.withoutTags().record(channelCount)
    Metrics.QueryShortChannelIds.ChannelUpdates.withoutTags().record(updateCount)
    log.info("received query_short_channel_ids with {} items, sent back {} channels and {} updates and {} nodes", q.shortChannelIds.array.size, channelCount, updateCount, nodeCount)
    origin.peerConnection ! ReplyShortChannelIdsEnd(q.chainHash, 1)
  }

  def handleReplyShortChannelIdsEnd(d: Data, origin: RemoteGossip, r: ReplyShortChannelIdsEnd)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    ctx.sender() ! TransportHandler.ReadAck(r)
    // do we have more channels to request from this peer?
    val sync1 = d.sync.get(origin.nodeId) match {
      case Some(sync) =>
        sync.remainingQueries match {
          case nextRequest :: rest =>
            log.debug(s"asking for the next slice of short_channel_ids (remaining=${sync.remainingQueries.size}/${sync.totalQueries})")
            origin.peerConnection ! nextRequest
            d.sync + (origin.nodeId -> sync.copy(remainingQueries = rest))
          case Nil =>
            // we received reply_short_channel_ids_end for our last query and have not sent another one, we can now remove
            // the remote peer from our map
            log.info(s"sync complete (total=${sync.totalQueries})")
            d.sync - origin.nodeId
        }
      case _ => d.sync
    }
    val progress = syncProgress(sync1)
    ctx.system.eventStream.publish(progress)
    ctx.self ! progress
    d.copy(sync = sync1)
  }

  /**
   * Filters channels that we want to send to nodes asking for a channel range
   */
  def keep(firstBlock: BlockHeight, numberOfBlocks: Long, id: RealShortChannelId): Boolean = {
    val height = id.blockHeight
    height >= firstBlock && height < (firstBlock + numberOfBlocks)
  }

  def shouldRequestUpdate(ourTimestamp: TimestampSecond, ourChecksum: Long, theirTimestamp_opt: Option[TimestampSecond], theirChecksum_opt: Option[Long]): Boolean = {
    (theirTimestamp_opt, theirChecksum_opt) match {
      case (Some(theirTimestamp), Some(theirChecksum)) =>
        // we request their channel_update if all those conditions are met:
        // - it is more recent than ours
        // - it is different from ours, or it is the same but ours is about to be stale
        // - it is not stale
        val theirsIsMoreRecent = ourTimestamp < theirTimestamp
        val areDifferent = ourChecksum != theirChecksum
        val oursIsAlmostStale = StaleChannels.isAlmostStale(ourTimestamp)
        val theirsIsStale = StaleChannels.isStale(theirTimestamp)
        theirsIsMoreRecent && (areDifferent || oursIsAlmostStale) && !theirsIsStale
      case (Some(theirTimestamp), None) =>
        // if we only have their timestamp, we request their channel_update if theirs is more recent than ours
        val theirsIsMoreRecent = ourTimestamp < theirTimestamp
        val theirsIsStale = StaleChannels.isStale(theirTimestamp)
        theirsIsMoreRecent && !theirsIsStale
      case (None, Some(theirChecksum)) =>
        // if we only have their checksum, we request their channel_update if it is different from ours
        // NB: a zero checksum means that they don't have the data
        val areDifferent = theirChecksum != 0 && ourChecksum != theirChecksum
        areDifferent
      case (None, None) =>
        // if we have neither their timestamp nor their checksum we request their channel_update
        true
    }
  }

  def computeFlag(channels: SortedMap[RealShortChannelId, PublicChannel])(
    shortChannelId: RealShortChannelId,
    theirTimestamps_opt: Option[ReplyChannelRangeTlv.Timestamps],
    theirChecksums_opt: Option[ReplyChannelRangeTlv.Checksums],
    includeNodeAnnouncements: Boolean): Long = {
    import QueryShortChannelIdsTlv.QueryFlagType._

    val flagsNodes = if (includeNodeAnnouncements) INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2 else 0

    val flags = if (!channels.contains(shortChannelId)) {
      INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2
    } else {
      // we already know this channel
      val (ourTimestamps, ourChecksums) = getChannelDigestInfo(channels)(shortChannelId)
      // if they don't provide timestamps or checksums, we set appropriate default values:
      // - we assume their timestamp is more recent than ours by setting timestamp = Long.MaxValue
      // - we assume their update is different from ours by setting checkum = Long.MaxValue (NB: our default value for checksum is 0)
      val shouldRequestUpdate1 = shouldRequestUpdate(ourTimestamps.timestamp1, ourChecksums.checksum1, theirTimestamps_opt.map(_.timestamp1), theirChecksums_opt.map(_.checksum1))
      val shouldRequestUpdate2 = shouldRequestUpdate(ourTimestamps.timestamp2, ourChecksums.checksum2, theirTimestamps_opt.map(_.timestamp2), theirChecksums_opt.map(_.checksum2))
      val flagUpdate1 = if (shouldRequestUpdate1) INCLUDE_CHANNEL_UPDATE_1 else 0
      val flagUpdate2 = if (shouldRequestUpdate2) INCLUDE_CHANNEL_UPDATE_2 else 0
      flagUpdate1 | flagUpdate2
    }

    if (flags == 0) 0 else flags | flagsNodes
  }

  /**
   * Handle a query message, which includes a list of channel ids and flags.
   *
   * @param nodes     node id -> node announcement
   * @param channels  channel id -> channel announcement + updates
   * @param ids       list of channel ids
   * @param flags     list of query flags, either empty one flag per channel id
   * @param onChannel called when a channel announcement matches (i.e. its bit is set in the query flag and we have it)
   * @param onUpdate  called when a channel update matches
   * @param onNode    called when a node announcement matches
   *
   */
  def processChannelQuery(nodes: Map[PublicKey, NodeAnnouncement],
                          channels: SortedMap[RealShortChannelId, PublicChannel])(
                           ids: List[RealShortChannelId],
                           flags: List[Long],
                           onChannel: ChannelAnnouncement => Unit,
                           onUpdate: ChannelUpdate => Unit,
                           onNode: NodeAnnouncement => Unit)(implicit log: LoggingAdapter): Unit = {
    import QueryShortChannelIdsTlv.QueryFlagType

    // we loop over channel ids and query flag. We track node Ids for node announcement
    // we've already sent to avoid sending them multiple times, as requested by the BOLTs
    @tailrec
    def loop(ids: List[RealShortChannelId], flags: List[Long], numca: Int = 0, numcu: Int = 0, nodesSent: Set[PublicKey] = Set.empty[PublicKey]): (Int, Int, Int) = ids match {
      case Nil => (numca, numcu, nodesSent.size)
      case head :: tail if !channels.contains(head) =>
        log.warning("received query for shortChannelId={} that we don't have", head)
        loop(tail, flags.drop(1), numca, numcu, nodesSent)
      case head :: tail =>
        val numca1 = numca
        val numcu1 = numcu
        var sent1 = nodesSent
        val pc = channels(head)
        val flag_opt = flags.headOption
        // no flag means send everything

        val includeChannel = flag_opt.forall(QueryFlagType.includeChannelAnnouncement)
        val includeUpdate1 = flag_opt.forall(QueryFlagType.includeUpdate1)
        val includeUpdate2 = flag_opt.forall(QueryFlagType.includeUpdate2)
        val includeNode1 = flag_opt.forall(QueryFlagType.includeNodeAnnouncement1)
        val includeNode2 = flag_opt.forall(QueryFlagType.includeNodeAnnouncement2)

        if (includeChannel) {
          onChannel(pc.ann)
        }
        if (includeUpdate1) {
          pc.update_1_opt.foreach { u =>
            onUpdate(u)
          }
        }
        if (includeUpdate2) {
          pc.update_2_opt.foreach { u =>
            onUpdate(u)
          }
        }
        if (includeNode1 && !sent1.contains(pc.ann.nodeId1)) {
          nodes.get(pc.ann.nodeId1).foreach { n =>
            onNode(n)
            sent1 = sent1 + pc.ann.nodeId1
          }
        }
        if (includeNode2 && !sent1.contains(pc.ann.nodeId2)) {
          nodes.get(pc.ann.nodeId2).foreach { n =>
            onNode(n)
            sent1 = sent1 + pc.ann.nodeId2
          }
        }
        loop(tail, flags.drop(1), numca1, numcu1, sent1)
    }

    loop(ids, flags)
  }

  /**
   * Returns overall progress on synchronization
   *
   * @return a sync progress indicator (1 means fully synced)
   */
  def syncProgress(sync: Map[PublicKey, Syncing]): SyncProgress = {
    // NB: progress is in terms of requests, not individual channels
    val (pending, total) = sync.foldLeft((0, 0)) {
      case ((p, t), (_, sync)) => (p + sync.remainingQueries.size, t + sync.totalQueries)
    }
    if (total == 0) {
      SyncProgress(1)
    } else {
      SyncProgress((total - pending) / (1.0 * total))
    }
  }

  def getChannelDigestInfo(channels: SortedMap[RealShortChannelId, PublicChannel])(shortChannelId: RealShortChannelId): (ReplyChannelRangeTlv.Timestamps, ReplyChannelRangeTlv.Checksums) = {
    val c = channels(shortChannelId)
    val timestamp1 = c.update_1_opt.map(_.timestamp).getOrElse(0L unixsec)
    val timestamp2 = c.update_2_opt.map(_.timestamp).getOrElse(0L unixsec)
    val checksum1 = c.update_1_opt.map(getChecksum).getOrElse(0L)
    val checksum2 = c.update_2_opt.map(getChecksum).getOrElse(0L)
    (ReplyChannelRangeTlv.Timestamps(timestamp1 = timestamp1, timestamp2 = timestamp2), ReplyChannelRangeTlv.Checksums(checksum1 = checksum1, checksum2 = checksum2))
  }

  def crc32c(data: ByteVector): Long = {
    import com.google.common.hash.Hashing
    Hashing.crc32c().hashBytes(data.toArray).asInt() & 0xFFFFFFFFL
  }

  def getChecksum(u: ChannelUpdate): Long = {
    import u._

    val data = serializationResult(LightningMessageCodecs.channelUpdateChecksumCodec.encode(chainHash :: shortChannelId :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: HNil))
    crc32c(data)
  }

  case class ShortChannelIdsChunk(firstBlock: BlockHeight, numBlocks: Long, shortChannelIds: List[RealShortChannelId]) {
    /**
     * @param maximumSize maximum size of the short channel ids list
     * @return a chunk with at most `maximumSize` ids
     */
    def enforceMaximumSize(maximumSize: Int): ShortChannelIdsChunk = {
      if (shortChannelIds.size <= maximumSize) {
        this
      } else {
        // we use a random offset here, so even if shortChannelIds.size is much bigger than maximumSize (which should
        // not happen) peers will eventually receive info about all channels in this chunk
        val offset = Random.nextInt(shortChannelIds.size - maximumSize + 1)
        this.copy(shortChannelIds = this.shortChannelIds.slice(offset, offset + maximumSize))
      }
    }
  }

  /**
   * Split short channel ids into chunks, because otherwise message could be too big
   * there could be several reply_channel_range messages for a single query, but we make sure that the returned
   * chunks fully covers the [firstBlockNum, numberOfBlocks] range that was requested
   *
   * @param shortChannelIds       list of short channel ids to split
   * @param firstBlock            first block height requested by our peers
   * @param numberOfBlocks        number of blocks requested by our peer
   * @param channelRangeChunkSize target chunk size. All ids that have the same block height will be grouped together, so
   *                              returned chunks may still contain more than `channelRangeChunkSize` elements
   * @return a list of short channel id chunks
   */
  def split(shortChannelIds: SortedSet[RealShortChannelId], firstBlock: BlockHeight, numberOfBlocks: Long, channelRangeChunkSize: Int): List[ShortChannelIdsChunk] = {
    // see BOLT7: MUST encode a short_channel_id for every open channel it knows in blocks first_blocknum to first_blocknum plus number_of_blocks minus one
    val it = shortChannelIds.iterator.dropWhile(_.blockHeight < firstBlock).takeWhile(_.blockHeight < firstBlock + numberOfBlocks)
    if (it.isEmpty) {
      List(ShortChannelIdsChunk(firstBlock, numberOfBlocks, List.empty))
    } else {
      // we want to split ids in different chunks, with the following rules by order of priority
      // ids that have the same block height must be grouped in the same chunk
      // chunk should contain `channelRangeChunkSize` ids
      @tailrec
      def loop(currentChunk: List[RealShortChannelId], acc: List[ShortChannelIdsChunk]): List[ShortChannelIdsChunk] = {
        if (it.hasNext) {
          val id = it.next()
          val currentHeight = currentChunk.head.blockHeight
          if (id.blockHeight == currentHeight) {
            loop(id :: currentChunk, acc) // same height => always add to the current chunk
          } else if (currentChunk.size < channelRangeChunkSize) {
            loop(id :: currentChunk, acc) // different height but we're under the size target => add to the current chunk
          } else {
            // different height and over the size target => start a new chunk
            // we always prepend because it's more efficient so we have to reverse the current chunk
            // for the first chunk, we make sure that we start at the request first block
            // for the next chunks we start at the end of the range covered by the last chunk
            val first = if (acc.isEmpty) firstBlock else acc.head.firstBlock + acc.head.numBlocks
            val count = currentChunk.head.blockHeight - first + 1
            loop(id :: Nil, ShortChannelIdsChunk(first, count, currentChunk.reverse) :: acc)
          }
        }
        else {
          // for the last chunk, we make sure that we cover the requested block range
          val first = if (acc.isEmpty) firstBlock else acc.head.firstBlock + acc.head.numBlocks
          val count = numberOfBlocks - (first - firstBlock)
          (ShortChannelIdsChunk(first, count, currentChunk.reverse) :: acc).reverse
        }
      }

      val first = it.next()
      val chunks = loop(first :: Nil, Nil)

      // make sure that all our chunks match our max size policy
      enforceMaximumSize(chunks)
    }
  }

  /**
   * Enforce max-size constraints for each chunk
   *
   * @param chunks list of short channel id chunks
   * @return a processed list of chunks
   */
  def enforceMaximumSize(chunks: List[ShortChannelIdsChunk]): List[ShortChannelIdsChunk] = chunks.map(_.enforceMaximumSize(MAXIMUM_CHUNK_SIZE))

  /**
   * Build a `reply_channel_range` message
   *
   * @param chunk           chunk of scids
   * @param chainHash       chain hash
   * @param defaultEncoding default encoding
   * @param queryFlags_opt  query flag set by the requester
   * @param channels        channels map
   * @return a ReplyChannelRange object
   */
  def buildReplyChannelRange(chunk: ShortChannelIdsChunk, syncComplete: Boolean, chainHash: ByteVector32, defaultEncoding: EncodingType, queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags], channels: SortedMap[RealShortChannelId, PublicChannel]): ReplyChannelRange = {
    val encoding = if (chunk.shortChannelIds.isEmpty) EncodingType.UNCOMPRESSED else defaultEncoding
    val (timestamps, checksums) = queryFlags_opt match {
      case Some(extension) if extension.wantChecksums | extension.wantTimestamps =>
        // we always compute timestamps and checksums even if we don't need both, overhead is negligible
        val (timestamps, checksums) = chunk.shortChannelIds.map(getChannelDigestInfo(channels)).unzip
        val encodedTimestamps = if (extension.wantTimestamps) Some(ReplyChannelRangeTlv.EncodedTimestamps(encoding, timestamps)) else None
        val encodedChecksums = if (extension.wantChecksums) Some(ReplyChannelRangeTlv.EncodedChecksums(checksums)) else None
        (encodedTimestamps, encodedChecksums)
      case _ => (None, None)
    }
    ReplyChannelRange(chainHash, chunk.firstBlock, chunk.numBlocks,
      syncComplete = if (syncComplete) 1 else 0,
      shortChannelIds = EncodedShortChannelIds(encoding, chunk.shortChannelIds),
      timestamps = timestamps,
      checksums = checksums)
  }

  def addToSync(syncMap: Map[PublicKey, Syncing], current: Syncing, remoteNodeId: PublicKey, pending: List[QueryShortChannelIds]): (Map[PublicKey, Syncing], Option[QueryShortChannelIds]) = {
    pending match {
      case head :: rest =>
        // they may send back several reply_channel_range messages for a single query_channel_range query, and we must not
        // send another query_short_channel_ids query if they're still processing one
        if (current.started) {
          // we already have a pending query with this peer, add missing ids to our "sync" state
          (syncMap + (remoteNodeId -> Syncing(current.remainingQueries ++ pending, current.totalQueries + pending.size)), None)
        } else {
          // we don't have a pending query with this peer, let's send it
          (syncMap + (remoteNodeId -> Syncing(rest, pending.size)), Some(head))
        }
      case Nil =>
        // there is nothing to send
        (syncMap, None)
    }
  }

}
