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


class RoutingSyncDeprecatedSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {
  import RoutingSyncSpec.makeFakeRoutingInfo

  test("handle channel range extended queries") {
    val params = TestConstants.Alice.nodeParams
    val router = TestFSMRef(new Router(params, TestProbe().ref))
    val transport = TestProbe()
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
    val block1 = ReplyChannelRangeDeprecated(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIdsWithTimestamp(EncodingTypes.UNCOMPRESSED, shortChannelIds.take(100).toList.map(id => ShortChannelIdWithTimestamp(id, Router.getTimestamp(initChannels, initChannelUpdates)(id)))))
    val block2 = ReplyChannelRangeDeprecated(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIdsWithTimestamp(EncodingTypes.UNCOMPRESSED, shortChannelIds.drop(100).take(100).toList.map(id => ShortChannelIdWithTimestamp(id, Router.getTimestamp(initChannels, initChannelUpdates)(id)))))

    // send first block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block1))
    // router should ask for our first block of ids
    assert(transport.expectMsgType[QueryShortChannelIdsDeprecated] === QueryShortChannelIdsDeprecated(chainHash, (FlagTypes.INCLUDE_ANNOUNCEMENT | FlagTypes.INCLUDE_CHANNEL_UPDATE_1 | FlagTypes.INCLUDE_CHANNEL_UPDATE_2).toByte, EncodedShortChannelIds(block1.data.encoding, block1.data.array.map(_.shortChannelId))))

    // send second block
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block2))

    // router should not ask for more ids, it already has a pending query !
    sender.expectNoMsg(1 second)

    // send the first 50 items
    block1.data.array.map(_.shortChannelId).take(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })

    sender.expectNoMsg(1 second)

    // send the last 50 items
    block1.data.array.map(_.shortChannelId).drop(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })

    // during that time, router should not have asked for more ids, it already has a pending query !
    transport.expectNoMsg(200 millis)

    // now send our ReplyShortChannelIdsEnd message
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ReplyShortChannelIdsEndDeprecated(chainHash, 1.toByte)))

    // router should ask for our second block of ids
    assert(transport.expectMsgType[QueryShortChannelIdsDeprecated] === QueryShortChannelIdsDeprecated(chainHash, (FlagTypes.INCLUDE_ANNOUNCEMENT | FlagTypes.INCLUDE_CHANNEL_UPDATE_1 | FlagTypes.INCLUDE_CHANNEL_UPDATE_2).toByte, EncodedShortChannelIds(block1.data.encoding, block2.data.array.map(_.shortChannelId))))
  }
}
