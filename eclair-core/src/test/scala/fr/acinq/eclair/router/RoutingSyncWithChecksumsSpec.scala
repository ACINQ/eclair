package fr.acinq.eclair.router

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{ValidateRequest, ValidateResult}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.scalatest.FunSuiteLike

import scala.collection.immutable.TreeMap
import scala.concurrent.duration._


class RoutingSyncWithChecksumsSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import RoutingSyncSpec.makeFakeRoutingInfo

  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(350)
  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo).map(t => t._1.shortChannelId -> t).toMap
  val initChannels = fakeRoutingInfo.values.map(_._1).foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
  val initChannelUpdates = fakeRoutingInfo.values.flatMap(t => Seq(t._2, t._3)).map { u =>
    val desc = Router.getDesc(u, initChannels(u.shortChannelId))
    (desc) -> u
  }.toMap

  class FakeWatcher extends Actor {
    override def receive: Receive = {
      case ValidateRequest(c) =>
        val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
        val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
        val fakeFundingTx = Transaction(
          version = 2,
          txIn = Seq.empty[TxIn],
          txOut = List.fill(outputIndex + 1)(TxOut(Satoshi(0), pubkeyScript)), // quick and dirty way to be sure that the outputIndex'th output is of the expected format
          lockTime = 0)
        sender ! ValidateResult(c, Some(fakeFundingTx), true, None)
    }
  }

  test("handle channel range extended (full sync)") {
    val params = TestConstants.Alice.nodeParams
    val watcher = system.actorOf(Props(new FakeWatcher()))
    val router = TestFSMRef(new Router(params, watcher))
    val transport = TestProbe()
    val sender = TestProbe()
    sender.ignoreMsg { case _: TransportHandler.ReadAck => true }
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // ask router to send a channel range query
    sender.send(router, SendChannelQueryWithChecksums(remoteNodeId, sender.ref))
    val QueryChannelRangeWithChecksums(chainHash, firstBlockNum, numberOfBlocks) = sender.expectMsgType[QueryChannelRangeWithChecksums]
    sender.expectMsgType[GossipTimestampFilter]

    // send back all our ids and timestamps
    val block = ReplyChannelRangeWithChecksums(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIdsWithChecksums(EncodingTypes.UNCOMPRESSED, shortChannelIds.toList.map(Router.getChannelDigestInfo(initChannels, initChannelUpdates))))
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block))

    // router should ask for our first block of ids
    val shortChannelIdAndFlags = block.data.array.map(info => ShortChannelIdAndFlag(info.shortChannelId, (FlagTypes.INCLUDE_ANNOUNCEMENT | FlagTypes.INCLUDE_CHANNEL_UPDATE_1 | FlagTypes.INCLUDE_CHANNEL_UPDATE_2).toByte))
    val shortChannelIdAndFlags1 = shortChannelIdAndFlags.take(Router.SHORTID_WINDOW)
    assert(transport.expectMsgType[QueryShortChannelIdsWithFlags] === QueryShortChannelIdsWithFlags(chainHash, EncodedShortChannelIdsAndFlag(block.data.encoding, shortChannelIdAndFlags1)))

    // send the first 50 items
    shortChannelIdAndFlags1.take(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id.shortChannelId)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // send the last 50 items
    shortChannelIdAndFlags1.drop(50).foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id.shortChannelId)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })
    sender.expectNoMsg(1 second)

    // now send our ReplyShortChannelIdsEnd message
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ReplyShortChannelIdsWithFlagsEnd(chainHash, 1.toByte)))

    // router should ask for our second block of ids
    val shortChannelIdAndFlags2 = shortChannelIdAndFlags.drop(Router.SHORTID_WINDOW).take(Router.SHORTID_WINDOW)
    assert(transport.expectMsgType[QueryShortChannelIdsWithFlags] === QueryShortChannelIdsWithFlags(chainHash, EncodedShortChannelIdsAndFlag(block.data.encoding, shortChannelIdAndFlags2)))

    // send block #2
    shortChannelIdAndFlags2.foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id.shortChannelId)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ReplyShortChannelIdsWithFlagsEnd(chainHash, 1.toByte)))

    // router should ask for our third block of ids
    val shortChannelIdAndFlags3 = shortChannelIdAndFlags.drop(2 * Router.SHORTID_WINDOW).take(Router.SHORTID_WINDOW)
    assert(transport.expectMsgType[QueryShortChannelIdsWithFlags] === QueryShortChannelIdsWithFlags(chainHash, EncodedShortChannelIdsAndFlag(block.data.encoding, shortChannelIdAndFlags3)))

    // send block #3
    shortChannelIdAndFlags3.foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id.shortChannelId)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ReplyShortChannelIdsWithFlagsEnd(chainHash, 1.toByte)))

    // router should ask for our fourth block of ids
    val shortChannelIdAndFlags4 = shortChannelIdAndFlags.drop(3 * Router.SHORTID_WINDOW).take(Router.SHORTID_WINDOW)
    assert(transport.expectMsgType[QueryShortChannelIdsWithFlags] === QueryShortChannelIdsWithFlags(chainHash, EncodedShortChannelIdsAndFlag(block.data.encoding, shortChannelIdAndFlags4)))

    // send block #4
    shortChannelIdAndFlags4.foreach(id => {
      val (ca, cu1, cu2, _, _) = fakeRoutingInfo(id.shortChannelId)
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ca))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu1))
      sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, cu2))
    })
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, ReplyShortChannelIdsWithFlagsEnd(chainHash, 1.toByte)))

    awaitCond({
      router.stateData.channels == initChannels
    }, max = 30 seconds, interval = 500 millis)


    val updatedIds = shortChannelIds.drop(100).take(50)
    val recentChannelUpdates = updatedIds.foldLeft(initChannelUpdates) {
      case (updates, id) =>
        val desc = ChannelDesc(id, RoutingSyncSpec.priv1.publicKey, RoutingSyncSpec.priv2.publicKey)
        val update = updates(desc)
        val foo = Announcements.isNode1(update.channelFlags)
        assert(foo)
        val newUpdate = RoutingSyncSpec.makeNewerChannelUpdate(update)
        updates.updated(desc, newUpdate)
    }
    // ask router to send a channel range query
    sender.send(router, SendChannelQueryWithChecksums(remoteNodeId, sender.ref))
    sender.expectMsgType[QueryChannelRangeWithChecksums]
    sender.expectMsgType[GossipTimestampFilter]

    // send back all our ids and timestamps
    val block1 = ReplyChannelRangeWithChecksums(chainHash, firstBlockNum, numberOfBlocks, 1, EncodedShortChannelIdsWithChecksums(EncodingTypes.UNCOMPRESSED, shortChannelIds.toList.map(Router.getChannelDigestInfo(initChannels, recentChannelUpdates))))
    sender.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, block1))

    // router should ask for our new channel updates
    val shortChannelIdAndFlags5 = block1.data.array.map(info => ShortChannelIdAndFlag(info.shortChannelId, FlagTypes.INCLUDE_CHANNEL_UPDATE_1)).filter(info => updatedIds.contains(info.shortChannelId))
    assert(transport.expectMsgType[QueryShortChannelIdsWithFlags] === QueryShortChannelIdsWithFlags(chainHash, EncodedShortChannelIdsAndFlag(block.data.encoding, shortChannelIdAndFlags5)))

  }
}
