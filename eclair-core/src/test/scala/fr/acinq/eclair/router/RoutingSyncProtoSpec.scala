package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.wire._
import org.scalatest.FunSuiteLike

import scala.collection.immutable.TreeMap
import scala.concurrent.duration._


class RoutingSyncProtoSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  import RoutingSyncSpec.makeFakeRoutingInfo

  test("handle chanel range extended queries") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQueryDeprecated(remoteNodeId, sender.ref))
    val QueryChannelRangeDeprecated(chainHash, firstBlockNum, numberOfBlocks) = sender.expectMsgType[QueryChannelRangeDeprecated]
    sender.expectMsgType[GossipTimestampFilter]


    val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(350)
    val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo).map(t => t._1.shortChannelId -> t).toMap
    val initChannels = fakeRoutingInfo.values.map(_._1).foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdates = fakeRoutingInfo.values.flatMap(t => Seq(t._2, t._3)).map { u =>
      val desc = Router.getDesc(u, initChannels(u.shortChannelId))
      (desc) -> u
    }.toMap

    // split our anwser in 3 blocks
    val List(block1) = ShortChannelIdAndTimestampBlock.encode(firstBlockNum, numberOfBlocks, shortChannelIds.take(100), Router.getTimestamp(initChannels, initChannelUpdates), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val List(block2) = ShortChannelIdAndTimestampBlock.encode(firstBlockNum, numberOfBlocks, shortChannelIds.drop(100).take(100), Router.getTimestamp(initChannels, initChannelUpdates), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val List(block3) = ShortChannelIdAndTimestampBlock.encode(firstBlockNum, numberOfBlocks, shortChannelIds.drop(200).take(150), Router.getTimestamp(initChannels, initChannelUpdates), ChannelRangeQueries.UNCOMPRESSED_FORMAT)

    // send first block
    sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ReplyChannelRangeDeprecated(chainHash, block1.firstBlock, block1.numBlocks, 1, block1.shortChannelIdAndTimestamps)))
    // router should ask for our first block of ids
    val QueryShortChannelIdsDeprecated(_, _, data1) = sender.expectMsgType[QueryShortChannelIdsDeprecated]
    val (_, shortChannelIds1, false) = ShortChannelIdsBlock.decode(data1)
    assert(shortChannelIds1 == shortChannelIds.take(100))

    // send second block
    sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ReplyChannelRangeDeprecated(chainHash, block2.firstBlock, block2.numBlocks, 1, block2.shortChannelIdAndTimestamps)))

    // router should not ask for more ids, it already has a pending query !
    sender.expectNoMsg(1 second)

    // send the first 50 items
    shortChannelIds1.take(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // send the last 50 items
    shortChannelIds1.drop(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // now send our ReplyShortChannelIdsEnd message
    sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ReplyShortChannelIdsEndDeprecated(chainHash, 1.toByte)))

    // router should ask for our second block of ids
    val QueryShortChannelIdsDeprecated(_, _, data2) = sender.expectMsgType[QueryShortChannelIdsDeprecated]
    val (_, shortChannelIds2, false) = ShortChannelIdsBlock.decode(data2)
    assert(shortChannelIds2 == shortChannelIds.drop(100).take(100))
  }
}
