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
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class RoutingSyncSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  import RoutingSyncSpec.makeFakeRoutingInfo

  test("handle chanel range queries") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQuery(remoteNodeId, sender.ref))
    val QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks) = sender.expectMsgType[QueryChannelRange]
    sender.expectMsgType[GossipTimestampFilter]


    val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(350)
    val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo).map(t => t._1.shortChannelId -> t).toMap

    // split our anwser in 3 blocks
    val List(block1) = ChannelRangeQueries.encodeShortChannelIds(firstBlockNum, numberOfBlocks, shortChannelIds.take(100), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val List(block2) = ChannelRangeQueries.encodeShortChannelIds(firstBlockNum, numberOfBlocks, shortChannelIds.drop(100).take(100), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val List(block3) = ChannelRangeQueries.encodeShortChannelIds(firstBlockNum, numberOfBlocks, shortChannelIds.drop(200).take(150), ChannelRangeQueries.UNCOMPRESSED_FORMAT)

    // send first block
    sender.send(router, PeerRoutingMessage(remoteNodeId, ReplyChannelRange(chainHash, block1.firstBlock, block1.numBlocks, 1, block1.shortChannelIds)))
    // router should ask for our first block of ids
    val QueryShortChannelIds(_, data1) = sender.expectMsgType[QueryShortChannelIds]
    val (_, shortChannelIds1, false) = ChannelRangeQueries.decodeShortChannelIds(data1)
    assert(shortChannelIds1 == shortChannelIds.take(100))

    // send second block
    sender.send(router, PeerRoutingMessage(remoteNodeId, ReplyChannelRange(chainHash, block2.firstBlock, block2.numBlocks, 1, block2.shortChannelIds)))

    // router should not ask for more ids, it already has a pending query !
    sender.expectNoMsg(1 second)

    // send the first 50 items
    shortChannelIds1.take(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // send the last 50 items
    shortChannelIds1.drop(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // now send our ReplyShortChannelIdsEnd message
    sender.send(router, PeerRoutingMessage(remoteNodeId, ReplyShortChannelIdsEnd(chainHash, 1.toByte)))

    // router should ask for our second block of ids
    val QueryShortChannelIds(_, data2) = sender.expectMsgType[QueryShortChannelIds]
    val (_, shortChannelIds2, false) = ChannelRangeQueries.decodeShortChannelIds(data2)
    assert(shortChannelIds2 == shortChannelIds.drop(100).take(100))
  }
}


object RoutingSyncSpec {
  def makeFakeRoutingInfo(shortChannelId: ShortChannelId): (ChannelAnnouncement, ChannelUpdate, ChannelUpdate, NodeAnnouncement, NodeAnnouncement) = {
    val (priv_a, priv_b, priv_funding_a, priv_funding_b) = (randomKey, randomKey, randomKey, randomKey)
    val channelAnn_ab = channelAnnouncement(shortChannelId, priv_a, priv_b, priv_funding_a, priv_funding_b)
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(shortChannelId)
    val channelUpdate_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_b.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, timestamp = blockHeight)
    val channelUpdate_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, priv_a.publicKey, shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, timestamp = blockHeight)
    val nodeAnnouncement_a = makeNodeAnnouncement(priv_a, "a", Alice.nodeParams.color, List())
    val nodeAnnouncement_b = makeNodeAnnouncement(priv_b, "b", Bob.nodeParams.color, List())
    (channelAnn_ab, channelUpdate_ab, channelUpdate_ba, nodeAnnouncement_a, nodeAnnouncement_b)
  }
}
