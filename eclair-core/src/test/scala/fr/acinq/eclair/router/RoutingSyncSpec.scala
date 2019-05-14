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
import fr.acinq.bitcoin.{Block, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.scalatest.FunSuiteLike

import scala.collection.immutable.TreeMap
import scala.collection.{SortedSet, immutable, mutable}
import scala.compat.Platform


class RoutingSyncSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import RoutingSyncSpec._

  val fakeRoutingInfo: TreeMap[ShortChannelId, (ChannelAnnouncement, ChannelUpdate, ChannelUpdate, NodeAnnouncement, NodeAnnouncement)] = RoutingSyncSpec
    .shortChannelIds
    .take(2345)
    .foldLeft(TreeMap.empty[ShortChannelId, (ChannelAnnouncement, ChannelUpdate, ChannelUpdate, NodeAnnouncement, NodeAnnouncement)]) {
      case (m, shortChannelId) => m + (shortChannelId -> makeFakeRoutingInfo(shortChannelId))
    }

  class YesWatcher extends Actor {
    override def receive: Receive = {
      case ValidateRequest(c) =>
        val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
        val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
        val fakeFundingTx = Transaction(
          version = 2,
          txIn = Seq.empty[TxIn],
          txOut = List.fill(outputIndex + 1)(TxOut(Satoshi(0), pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
          lockTime = 0)
        sender ! ValidateResult(c, Right(fakeFundingTx, UtxoStatus.Unspent))
    }
  }

  case class BasicSyncResult(ranges: Int, queries: Int, channels: Int, updates: Int)

  case class SyncResult(ranges: Seq[ReplyChannelRange], queries: Seq[QueryShortChannelIds], channels: Seq[ChannelAnnouncement], updates: Seq[ChannelUpdate]) {
    def counts = BasicSyncResult(ranges.size, queries.size, channels.size, updates.size)
  }

  def sync(src: TestFSMRef[State, Data, Router], tgt: TestFSMRef[State, Data, Router], extendedQueryFlags_opt: Option[ExtendedQueryFlags]): SyncResult = {
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
      }
      // tgt replies with announcements
      announcements.foreach(ann => pipe.send(src, PeerRoutingMessage(pipe.ref, tgtId, ann)))
      // and tgt ends this chunk with a reply_short_channel_id_end
      val rscie = pipe.expectMsgType[ReplyShortChannelIdsEnd]
      pipe.send(src, PeerRoutingMessage(pipe.ref, tgtId, rscie))
    }
    SyncResult(rcrs, queries, channels, updates)
  }

  test("handle channel range extended") {
    val watcher = system.actorOf(Props(new YesWatcher()))
    val alice = TestFSMRef(new Router(Alice.nodeParams, watcher))
    val bob = TestFSMRef(new Router(Bob.nodeParams, watcher))
    val charlieId = randomKey.publicKey
    val sender = TestProbe()
    val extendedQueryFlags_opt = None

    // tell alice to sync with bob
    assert(BasicSyncResult(ranges = 1, queries = 0, channels = 0, updates = 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // add some channels and updates to bob and resync
    fakeRoutingInfo.take(40).map(_._2._1).foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    fakeRoutingInfo.take(40).map(_._2._2).foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    awaitCond(bob.stateData.channels.size === 40 && bob.stateData.updates.size === 40)
    assert(BasicSyncResult(ranges = 1, queries = 1, channels = 40, updates = 40) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // add some updates to bob and resync
    fakeRoutingInfo.take(40).map(_._2._3).foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    awaitCond(bob.stateData.channels.size === 40 && bob.stateData.updates.size === 80)
    assert(BasicSyncResult(ranges = 1, queries = 1, channels = 40, updates = 80) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // add everything (duplicates will be ignored)
    fakeRoutingInfo.values.foreach {
      case (c, u1, u2, _, _) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, u1))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, u2))
    }
    awaitCond(bob.stateData.channels.size === fakeRoutingInfo.size && bob.stateData.updates.size === 2 * fakeRoutingInfo.size)
    assert(BasicSyncResult(ranges = 2, queries = 24, channels = fakeRoutingInfo.size, updates = 2 * fakeRoutingInfo.size) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)
  }

  test("handle channel range extended (extended)") {
    val watcher = system.actorOf(Props(new YesWatcher()))
    val alice = TestFSMRef(new Router(Alice.nodeParams, watcher))
    val bob = TestFSMRef(new Router(Bob.nodeParams, watcher))
    val charlieId = randomKey.publicKey
    val sender = TestProbe()
    val extendedQueryFlags_opt = Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS)

    // tell alice to sync with bob
    assert(BasicSyncResult(ranges = 1, queries = 0, channels = 0, updates = 0) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // add some channels and updates to bob and resync
    fakeRoutingInfo.take(40).map(_._2._1).foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    fakeRoutingInfo.take(40).map(_._2._2).foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    awaitCond(bob.stateData.channels.size === 40 && bob.stateData.updates.size === 40)
    assert(BasicSyncResult(ranges = 1, queries = 1, channels = 40, updates = 40) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // add some updates to bob and resync
    fakeRoutingInfo.take(40).map(_._2._3).foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    awaitCond(bob.stateData.channels.size === 40 && bob.stateData.updates.size === 80)
    assert(BasicSyncResult(ranges = 1, queries = 1, channels = 0, updates = 40) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // add everything (duplicates will be ignored)
    fakeRoutingInfo.values.foreach {
      case (c, u1, u2, _, _) =>
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, u1))
        sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, u2))
    }
    awaitCond(bob.stateData.channels.size === fakeRoutingInfo.size && bob.stateData.updates.size === 2 * fakeRoutingInfo.size)
    assert(BasicSyncResult(ranges = 2, queries = 24, channels = fakeRoutingInfo.size - 40, updates = 2 * (fakeRoutingInfo.size - 40)) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)

    // bump random channel_updates
    def touchUpdate(shortChannelId: Int, side: Boolean) = {
      val (c, u1, u2, _, _) = fakeRoutingInfo.values.toList(shortChannelId)
      makeNewerChannelUpdate(c, if (side) u1 else u2)
    }

    val bumpedUpdates = (List(0, 42, 147, 153, 654, 834, 2301).map(touchUpdate(_, true)) ++ List(1, 42, 150, 200).map(touchUpdate(_, false))).toSet
    bumpedUpdates.foreach(c => sender.send(bob, PeerRoutingMessage(sender.ref, charlieId, c)))
    assert(BasicSyncResult(ranges = 2, queries = 2, channels = 0, updates = bumpedUpdates.size) === sync(alice, bob, extendedQueryFlags_opt).counts)
    awaitCond(alice.stateData.channels === bob.stateData.channels)
    awaitCond(alice.stateData.updates === bob.stateData.updates)
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

    val block1 = ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, fakeRoutingInfo.take(100).keys.toList), None, None)

    // send first block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block1))

    // router should ask for our first block of ids
    assert(transport.expectMsgType[QueryShortChannelIds] === QueryShortChannelIds(chainHash, block1.shortChannelIds, None))
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

    def req = QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(42))), None)

    val nodeidA = randomKey.publicKey
    val nodeidB = randomKey.publicKey

    val (sync1, _) = Router.updateSync(Map.empty, nodeidA, List(req, req, req, req))
    assert(Router.syncProgress(sync1) == SyncProgress(0.25D))

    val (sync2, _) = Router.updateSync(sync1, nodeidB, List(req, req, req, req, req, req, req, req, req, req, req, req))
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

  def makeFakeRoutingInfo(shortChannelId: ShortChannelId): (ChannelAnnouncement, ChannelUpdate, ChannelUpdate, NodeAnnouncement, NodeAnnouncement) = {
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
    val channelUpdate_12 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv1, priv2.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L, timestamp = timestamp)
    val channelUpdate_21 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv2, priv1.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L, timestamp = timestamp)
    val nodeAnnouncement_1 = makeNodeAnnouncement(priv1, "", Color(0, 0, 0), List())
    val nodeAnnouncement_2 = makeNodeAnnouncement(priv2, "", Color(0, 0, 0), List())
    (channelAnn_12, channelUpdate_12, channelUpdate_21, nodeAnnouncement_1, nodeAnnouncement_2)
  }

  def makeNewerChannelUpdate(channelAnnouncement: ChannelAnnouncement, channelUpdate: ChannelUpdate): ChannelUpdate = {
    val (local, remote) = if (Announcements.isNode1(channelUpdate.channelFlags)) (channelAnnouncement.nodeId1, channelAnnouncement.nodeId2) else (channelAnnouncement.nodeId2, channelAnnouncement.nodeId1)
    val priv = pub2priv(local)
    makeChannelUpdate(channelUpdate.chainHash, priv, remote, channelUpdate.shortChannelId,
      channelUpdate.cltvExpiryDelta, channelUpdate.htlcMinimumMsat,
      channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths,
      channelUpdate.htlcMinimumMsat, Announcements.isEnabled(channelUpdate.channelFlags), channelUpdate.timestamp + 5000)
  }
}
