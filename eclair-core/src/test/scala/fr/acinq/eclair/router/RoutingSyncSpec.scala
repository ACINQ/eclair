package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.wire._
import org.scalatest.FunSuiteLike

import scala.compat.Platform
import scala.concurrent.duration._


class RoutingSyncSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import RoutingSyncSpec.makeFakeRoutingInfo

  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(350)
  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo).map(t => t._1.shortChannelId -> t).toMap

  test("handle channel range queries") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val transport = TestProbe()
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQuery(remoteNodeId, sender.ref))
    val QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks) = sender.expectMsgType[QueryChannelRange]
    sender.expectMsgType[GossipTimestampFilter]

    // split our answer in 3 blocks
    val block1 = ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIds(EncodingTypes.UNCOMPRESSED, shortChannelIds.take(100).toList))
    val block2 = ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIds(EncodingTypes.UNCOMPRESSED, shortChannelIds.drop(100).take(100).toList))

    // send first block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block1))
    // router should ask for our first block of ids
    assert(transport.expectMsgType[QueryShortChannelIds] === QueryShortChannelIds(chainHash, block1.data))

    // send second block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block2))

    // router should not ask for more ids, it already has a pending query !
    sender.expectNoMsg(1 second)

    // send the first 50 items
    block1.data.array.take(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })

    sender.expectNoMsg(1 second)

    // send the last 50 items
    block1.data.array.drop(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })

    // during that time, router should not have asked for more ids, it already has a pending query !
    transport.expectNoMsg(200 millis)

    // now send our ReplyShortChannelIdsEnd message
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ReplyShortChannelIdsEnd(chainHash, 1.toByte)))

    // router should ask for our second block of ids
    assert(transport.expectMsgType[QueryShortChannelIds] === QueryShortChannelIds(chainHash, block2.data))
  }

  test("reset sync state on reconnection") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val transport = TestProbe()
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQuery(remoteNodeId, sender.ref))
    val QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks) = sender.expectMsgType[QueryChannelRange]
    sender.expectMsgType[GossipTimestampFilter]

    val block1 = ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIds(EncodingTypes.UNCOMPRESSED, shortChannelIds.take(100).toList))

    // send first block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block1))

    // router should ask for our first block of ids
    assert(transport.expectMsgType[QueryShortChannelIds] === QueryShortChannelIds(chainHash, block1.data))
    // router should think that it is missing 100 channels, in one request
    val Some(sync) = router.stateData.sync.get(remoteNodeId)
    assert(sync.total == 1)

    // simulate a re-connection
    sender.send(router, SendChannelQuery(remoteNodeId, sender.ref))
    sender.expectMsgType[QueryChannelRange]
    sender.expectMsgType[GossipTimestampFilter]
    assert(router.stateData.sync.get(remoteNodeId).isEmpty)
  }

  test("sync progress") {

    def req = QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingTypes.UNCOMPRESSED, List(ShortChannelId(42))))

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
  val (priv_a, priv_b, priv_funding_a, priv_funding_b) = (randomKey, randomKey, randomKey, randomKey)
  val (priv1, priv2) = if (Announcements.isNode1(priv_a.publicKey.toBin, priv_b.publicKey.toBin))
    (priv_a, priv_b)
  else
    (priv_b, priv_a)

  def makeFakeRoutingInfo(shortChannelId: ShortChannelId): (ChannelAnnouncement, ChannelUpdate, ChannelUpdate, NodeAnnouncement, NodeAnnouncement) = {
    val timestamp = Platform.currentTime / 1000
    val channelAnn_ab = channelAnnouncement(shortChannelId, priv_a, priv_b, priv_funding_a, priv_funding_b)
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(shortChannelId)
    val channelUpdate_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_b.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L, timestamp = timestamp)
    val channelUpdate_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, priv_a.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L, timestamp = timestamp)
    val nodeAnnouncement_a = makeNodeAnnouncement(priv_a, "a", Alice.nodeParams.color, List())
    val nodeAnnouncement_b = makeNodeAnnouncement(priv_b, "b", Bob.nodeParams.color, List())
    (channelAnn_ab, channelUpdate_ab, channelUpdate_ba, nodeAnnouncement_a, nodeAnnouncement_b)
  }

  def makeNewerChannelUpdate(channelUpdate: ChannelUpdate) : ChannelUpdate = {
    val (priv, pub) = if (Announcements.isNode1(channelUpdate.channelFlags)) (priv1, priv2.publicKey) else (priv2, priv1.publicKey)
    makeChannelUpdate(channelUpdate.chainHash, priv, pub, channelUpdate.shortChannelId,
      channelUpdate.cltvExpiryDelta, channelUpdate.htlcMinimumMsat,
      channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths,
      channelUpdate.htlcMinimumMsat, Announcements.isEnabled(channelUpdate.channelFlags), channelUpdate.timestamp + 5000)
  }
}
