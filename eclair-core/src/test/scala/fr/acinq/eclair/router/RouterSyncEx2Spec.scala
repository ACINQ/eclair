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


class RoutingSyncEx2Spec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  import RoutingSyncSpec.makeFakeRoutingInfo

  test("handle chanel range extended queries") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQueryEx2(remoteNodeId, sender.ref))
    val QueryChannelRangeEx2(chainHash, firstBlockNum, numberOfBlocks) = sender.expectMsgType[QueryChannelRangeEx2]
    sender.expectMsgType[GossipTimestampFilter]


    val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(350)
    val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo).map(t => t._1.shortChannelId -> t).toMap
    val initChannels = fakeRoutingInfo.values.map(_._1).foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdates = fakeRoutingInfo.values.flatMap(t => Seq(t._2, t._3)).map { u =>
      val desc = Router.getDesc(u, initChannels(u.shortChannelId))
      (desc) -> u
    }.toMap

    // split our anwser in 3 blocks
    val List(block1) = ShortChannelIdAndTimestampsBlock.encode(firstBlockNum, numberOfBlocks, shortChannelIds.take(100), Router.getTimestamps(initChannels, initChannelUpdates), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val List(block2) = ShortChannelIdAndTimestampsBlock.encode(firstBlockNum, numberOfBlocks, shortChannelIds.drop(100).take(100), Router.getTimestamps(initChannels, initChannelUpdates), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val List(block3) = ShortChannelIdAndTimestampsBlock.encode(firstBlockNum, numberOfBlocks, shortChannelIds.drop(200).take(150), Router.getTimestamps(initChannels, initChannelUpdates), ChannelRangeQueries.UNCOMPRESSED_FORMAT)

    // send first block
    sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ReplyChannelRangeEx2(chainHash, block1.firstBlock, block1.numBlocks, 1, block1.shortChannelIdAndTimestamps)))
    // router should ask for our first block of ids
    val QueryShortChannelIdsEx2(_, data1) = sender.expectMsgType[QueryShortChannelIdsEx2]
    val (_, shortChannelIdAndFlags1) = ShortChannelIdAndFlagsBlock.decode(data1)
    assert(shortChannelIdAndFlags1.keySet == shortChannelIds.take(100))
    assert(shortChannelIdAndFlags1.values.toSet == Set(ChannelRangeQueries.INCLUDE_ANNOUNCEMENT))

    // send second block
    sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ReplyChannelRangeEx2(chainHash, block2.firstBlock, block2.numBlocks, 1, block2.shortChannelIdAndTimestamps)))

    // router should not ask for more ids, it already has a pending query !
    sender.expectNoMsg(1 second)

    // send the first 50 items
    shortChannelIdAndFlags1.take(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id._1)
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // send the last 50 items
    shortChannelIdAndFlags1.drop(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id._1)
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // now send our ReplyShortChannelIdsEnd message
    sender.send(router, PeerRoutingMessage(sender.ref, remoteNodeId, ReplyShortChannelIdsEndEx2(chainHash, 1.toByte)))

    // router should ask for our second block of ids
    val QueryShortChannelIdsEx2(_, data2) = sender.expectMsgType[QueryShortChannelIdsEx2]
    val (_, shortChannelIds2) = ShortChannelIdAndFlagsBlock.decode(data2)
    assert(shortChannelIds2.keySet == shortChannelIds.drop(100).take(100))
  }
}
