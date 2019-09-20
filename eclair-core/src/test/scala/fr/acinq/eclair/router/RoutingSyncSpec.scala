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

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.scalatest.{FunSuiteLike, ParallelTestExecution}
import scodec.bits.HexStringSyntax

import scala.collection.immutable.TreeMap
import scala.collection.{SortedSet, immutable, mutable}
import scala.compat.Platform
import scala.concurrent.duration._


class RoutingSyncSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with ParallelTestExecution {

  import RoutingSyncSpec._

  val fakeRoutingInfo: TreeMap[ShortChannelId, (PublicChannel, NodeAnnouncement, NodeAnnouncement)] = RoutingSyncSpec
    .shortChannelIds
    .take(60)
    .foldLeft(TreeMap.empty[ShortChannelId, (PublicChannel, NodeAnnouncement, NodeAnnouncement)]) {
      case (m, shortChannelId) => m + (shortChannelId -> makeFakeRoutingInfo(shortChannelId))
    }

  class YesWatcher extends Actor {
    override def receive: Receive = {
      case ValidateRequest(c) =>
        val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(c.bitcoinKey1, c.bitcoinKey2)))
        val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
        val fakeFundingTx = Transaction(
          version = 2,
          txIn = Seq.empty[TxIn],
          txOut = List.fill(outputIndex + 1)(TxOut(Satoshi(0), pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
          lockTime = 0)
        sender ! ValidateResult(c, Right(fakeFundingTx, UtxoStatus.Unspent))
    }
  }

  case class BasicSyncResult(ranges: Int, queries: Int, channels: Int, updates: Int, nodes: Int)

  case class SyncResult(ranges: Seq[ReplyChannelRange], queries: Seq[QueryShortChannelIds], channels: Seq[ChannelAnnouncement], updates: Seq[ChannelUpdate], nodes: Seq[NodeAnnouncement]) {
    def counts = BasicSyncResult(ranges.size, queries.size, channels.size, updates.size, nodes.size)
  }

  def sync(src: TestFSMRef[State, Data, Router], tgt: TestFSMRef[State, Data, Router], extendedQueryFlags_opt: Option[QueryChannelRangeTlv]): SyncResult = {
    val sender = TestProbe()
    val pipe = TestProbe()
    pipe.ignoreMsg {
      case _: TransportHandler.ReadAck => true
      case _: GossipTimestampFilter => true
    }
    val srcId = src.underlyingActor.nodeParams.nodeId
    val tgtId = tgt.underlyingActor.nodeParams.nodeId
    sender.send(src, SendChannelQuery(tgtId, pipe.ref, extendedQueryFlags_opt))
    // src sends a query_channel_range to bob
    val qcr = pipe.expectMsgType[QueryChannelRange]
    pipe.send(tgt, PeerRoutingMessage(pipe.ref, srcId, qcr))
    // this allows us to know when the last reply_channel_range has been set
    pipe.send(tgt, 'data)
    // tgt answers with reply_channel_ranges
    val rcrs = pipe.receiveWhile() {
      case rcr: ReplyChannelRange => rcr
    }
    pipe.expectMsgType[Data]
    rcrs.foreach(rcr => pipe.send(src, PeerRoutingMessage(pipe.ref, tgtId, rcr)))
    // then src will now query announcements
    var queries = Vector.empty[QueryShortChannelIds]
    var channels = Vector.empty[ChannelAnnouncement]
    var updates = Vector.empty[ChannelUpdate]
    var nodes = Vector.empty[NodeAnnouncement]
    while (src.stateData.sync.nonEmpty) {
      // for each chunk, src sends a query_short_channel_id
      val query = pipe.expectMsgType[QueryShortChannelIds]
      pipe.send(tgt, PeerRoutingMessage(pipe.ref, srcId, query))
      queries = queries :+ query
      val announcements = pipe.receiveWhile() {
        case c: ChannelAnnouncement =>
          channels = channels :+ c
          c
        case u: ChannelUpdate =>
          updates = updates :+ u
          u
        case n: NodeAnnouncement =>
          nodes = nodes :+ n
          n
      }
      // tgt replies with announcements
      announcements.foreach(ann => pipe.send(src, PeerRoutingMessage(pipe.ref, tgtId, ann)))
      // and tgt ends this chunk with a reply_short_channel_id_end
      val rscie = pipe.expectMsgType[ReplyShortChannelIdsEnd]
      pipe.send(src, PeerRoutingMessage(pipe.ref, tgtId, rscie))
    }
    SyncResult(rcrs, queries, channels, updates, nodes)
  }

  def countUpdates(channels: Map[ShortChannelId, PublicChannel]) = channels.values.foldLeft(0) {
    case (count, pc) => count + pc.update_1_opt.map(_ => 1).getOrElse(0) + pc.update_2_opt.map(_ => 1).getOrElse(0)
  }

  test("sync with standard channel queries") {
    val watcher = system.actorOf(Props(new YesWatcher()))
    val alice = TestFSMRef(new Router(Alice.nodeParams, watcher))
    val bob = TestFSMRef(new Router(Bob.nodeParams, watcher))
    val charlieId = randomKey.publicKey
    val sender = TestProbe()
    val extendedQueryFlags_opt = None

    // tell alice to sync with bob
    assert(BasicSyncResult(ranges = 1, queries = 0, channels = 0, updates = 0, nodes = 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.nodes === bob.stateData.nodes)

    // add some channels and updates to bob and resync
    fakeRoutingInfo.take(10).values.foreach {
      case (pc, na1, na2) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.ann))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_1_opt.get))
        // we don't send channel_update #2
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na1))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na2))
    }
    awaitCond(bob.stateData.channels.size === 10 && countUpdates(bob.stateData.channels) === 10)
    assert(BasicSyncResult(ranges = 1, queries = 2, channels = 10, updates = 10, nodes = 10 * 2) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)

    // add some updates to bob and resync
    fakeRoutingInfo.take(10).values.foreach {
      case (pc, _, _) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_2_opt.get))
    }
    awaitCond(bob.stateData.channels.size === 10 && countUpdates(bob.stateData.channels) === 10 * 2)
    assert(BasicSyncResult(ranges = 1, queries = 2, channels = 10, updates = 10 * 2, nodes = 10 * 2) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)

    // add everything (duplicates will be ignored)
    fakeRoutingInfo.values.foreach {
      case (pc, na1, na2) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.ann))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_1_opt.get))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_2_opt.get))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na1))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na2))
    }
    awaitCond(bob.stateData.channels.size === fakeRoutingInfo.size && countUpdates(bob.stateData.channels) === 2 * fakeRoutingInfo.size, max = 60 seconds)
    assert(BasicSyncResult(ranges = 3, queries = 12, channels = fakeRoutingInfo.size, updates = 2 * fakeRoutingInfo.size, nodes = 2 * fakeRoutingInfo.size) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels, max = 60 seconds)
  }

  def syncWithExtendedQueries(requestNodeAnnouncements: Boolean): Unit = {
    val watcher = system.actorOf(Props(new YesWatcher()))
    val alice = TestFSMRef(new Router(Alice.nodeParams.copy(routerConf = Alice.nodeParams.routerConf.copy(requestNodeAnnouncements = requestNodeAnnouncements)), watcher))
    val bob = TestFSMRef(new Router(Bob.nodeParams, watcher))
    val charlieId = randomKey.publicKey
    val sender = TestProbe()
    val extendedQueryFlags_opt = Some(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL))

    // tell alice to sync with bob
    assert(BasicSyncResult(ranges = 1, queries = 0, channels = 0, updates = 0, nodes = 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)

    // add some channels and updates to bob and resync
    fakeRoutingInfo.take(10).values.foreach {
      case (pc, na1, na2) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.ann))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_1_opt.get))
        // we don't send channel_update #2
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na1))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na2))
    }
    awaitCond(bob.stateData.channels.size === 10 && countUpdates(bob.stateData.channels) === 10)
    assert(BasicSyncResult(ranges = 1, queries = 2, channels = 10, updates = 10, nodes = if (requestNodeAnnouncements) 10 * 2 else 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels, max = 60 seconds)
    if (requestNodeAnnouncements) awaitCond(alice.stateData.nodes === bob.stateData.nodes)

    // add some updates to bob and resync
    fakeRoutingInfo.take(10).values.foreach {
      case (pc, _, _) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_2_opt.get))
    }
    awaitCond(bob.stateData.channels.size === 10 && countUpdates(bob.stateData.channels) === 10 * 2)
    assert(BasicSyncResult(ranges = 1, queries = 2, channels = 0, updates = 10, nodes = if (requestNodeAnnouncements) 10 * 2 else 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels, max = 60 seconds)

    // add everything (duplicates will be ignored)
    fakeRoutingInfo.values.foreach {
      case (pc, na1, na2) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.ann))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_1_opt.get))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, pc.update_2_opt.get))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na1))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, na2))
    }
    awaitCond(bob.stateData.channels.size === fakeRoutingInfo.size && countUpdates(bob.stateData.channels) === 2 * fakeRoutingInfo.size,  max = 60 seconds)
    assert(BasicSyncResult(ranges = 3, queries = 10, channels = fakeRoutingInfo.size - 10, updates = 2 * (fakeRoutingInfo.size - 10), nodes = if (requestNodeAnnouncements) 2 * (fakeRoutingInfo.size - 10) else 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels, max = 60 seconds)

    // bump random channel_updates
    def touchUpdate(shortChannelId: Int, side: Boolean) = {
      val PublicChannel(c, _, _, Some(u1), Some(u2)) = fakeRoutingInfo.values.toList(shortChannelId)._1
      makeNewerChannelUpdate(c, if (side) u1 else u2)
    }

    val bumpedUpdates = (List(0, 3, 7).map(touchUpdate(_, true)) ++ List(1, 3, 9).map(touchUpdate(_, false))).toSet
    bumpedUpdates.foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    assert(BasicSyncResult(ranges = 3, queries = 1, channels = 0, updates = bumpedUpdates.size, nodes = if (requestNodeAnnouncements) 5 * 2 else 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels, max = 60 seconds)
    if (requestNodeAnnouncements) awaitCond(alice.stateData.nodes === bob.stateData.nodes)
  }

  test("sync with extended channel queries (don't request node announcements)") {
    syncWithExtendedQueries(false)
  }

  test("sync with extended channel queries (request node announcements)") {
    syncWithExtendedQueries(true)
  }

  test("reset sync state on reconnection") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val transport = TestProbe()
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQuery(remoteNodeId, sender.ref, None))
    val QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks, _) = sender.expectMsgType[QueryChannelRange]
    sender.expectMsgType[GossipTimestampFilter]

    val block1 = ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, fakeRoutingInfo.take(params.routerConf.channelQueryChunkSize).keys.toList), None, None)

    // send first block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block1))

    // router should ask for our first block of ids
    assert(transport.expectMsgType[QueryShortChannelIds] === QueryShortChannelIds(chainHash, block1.shortChannelIds, TlvStream.empty))
    // router should think that it is missing 100 channels, in one request
    val Some(sync) = router.stateData.sync.get(remoteNodeId)
    assert(sync.total == 1)

    // simulate a re-connection
    sender.send(router, SendChannelQuery(remoteNodeId, sender.ref, None))
    sender.expectMsgType[QueryChannelRange]
    sender.expectMsgType[GossipTimestampFilter]
    assert(router.stateData.sync.get(remoteNodeId).isEmpty)
  }

  test("sync progress") {

    def req = QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(42))), TlvStream.empty)

    val nodeidA = randomKey.publicKey
    val nodeidB = randomKey.publicKey

    val (sync1, _) = Router.addToSync(Map.empty, nodeidA, List(req, req, req, req))
    assert(Router.syncProgress(sync1) == SyncProgress(0.25D))

    val (sync2, _) = Router.addToSync(sync1, nodeidB, List(req, req, req, req, req, req, req, req, req, req, req, req))
    assert(Router.syncProgress(sync2) == SyncProgress(0.125D))

    // let's assume we made some progress
    val sync3 = sync2
      .updated(nodeidA, sync2(nodeidA).copy(pending = List(req)))
      .updated(nodeidB, sync2(nodeidB).copy(pending = List(req)))
    assert(Router.syncProgress(sync3) == SyncProgress(0.875D))
  }
}

object RoutingSyncSpec {

  lazy val shortChannelIds: immutable.SortedSet[ShortChannelId] = (for {
    block <- 400000 to 420000
    txindex <- 0 to 5
    outputIndex <- 0 to 1
  } yield ShortChannelId(block, txindex, outputIndex)).foldLeft(SortedSet.empty[ShortChannelId])(_ + _)

  // this map will store private keys so that we can sign new announcements at will
  val pub2priv: mutable.Map[PublicKey, PrivateKey] = mutable.HashMap.empty

  val unused = randomKey

  def makeFakeRoutingInfo(shortChannelId: ShortChannelId): (PublicChannel, NodeAnnouncement, NodeAnnouncement) = {
    val timestamp = Platform.currentTime / 1000
    val (priv1, priv2) = {
      val (priv_a, priv_b) = (randomKey, randomKey)
      if (Announcements.isNode1(priv_a.publicKey, priv_b.publicKey)) (priv_a, priv_b) else (priv_b, priv_a)
    }
    val priv_funding1 = unused
    val priv_funding2 = unused
    pub2priv += (priv1.publicKey -> priv1)
    pub2priv += (priv2.publicKey -> priv2)
    val channelAnn_12 = channelAnnouncement(shortChannelId, priv1, priv2, priv_funding1, priv_funding2)
    val channelUpdate_12 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv1, priv2.publicKey, shortChannelId, cltvExpiryDelta = CltvExpiryDelta(7), 0 msat, feeBaseMsat = 766000 msat, feeProportionalMillionths = 10, 500000000L msat, timestamp = timestamp)
    val channelUpdate_21 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv2, priv1.publicKey, shortChannelId, cltvExpiryDelta = CltvExpiryDelta(7), 0 msat, feeBaseMsat = 766000 msat, feeProportionalMillionths = 10, 500000000L msat, timestamp = timestamp)
    val nodeAnnouncement_1 = makeNodeAnnouncement(priv1, "a", Color(0, 0, 0), List(), hex"0200")
    val nodeAnnouncement_2 = makeNodeAnnouncement(priv2, "b", Color(0, 0, 0), List(), hex"00")
    val publicChannel = PublicChannel(channelAnn_12, ByteVector32.Zeroes, Satoshi(0), Some(channelUpdate_12), Some(channelUpdate_21))
    (publicChannel, nodeAnnouncement_1, nodeAnnouncement_2)
  }

  def makeNewerChannelUpdate(channelAnnouncement: ChannelAnnouncement, channelUpdate: ChannelUpdate): ChannelUpdate = {
    val (local, remote) = if (Announcements.isNode1(channelUpdate.channelFlags)) (channelAnnouncement.nodeId1, channelAnnouncement.nodeId2) else (channelAnnouncement.nodeId2, channelAnnouncement.nodeId1)
    val priv = pub2priv(local)
    makeChannelUpdate(channelUpdate.chainHash, priv, remote, channelUpdate.shortChannelId,
      channelUpdate.cltvExpiryDelta, channelUpdate.htlcMinimumMsat,
      channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths,
      channelUpdate.htlcMinimumMsat, Announcements.isEnabled(channelUpdate.channelFlags), channelUpdate.timestamp + 5000)
  }

  def makeFakeNodeAnnouncement(nodeId: PublicKey): NodeAnnouncement = {
    val priv = pub2priv(nodeId)
    makeNodeAnnouncement(priv, "", Color(0, 0, 0), List(), hex"00")
  }

}
