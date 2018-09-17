package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.ResumeAnnouncements
import fr.acinq.eclair.router.RoutingSyncSpec.makeFakeRoutingInfo
import fr.acinq.eclair.router.{ChannelRangeQueries, ChannelRangeQueriesSpec, Rebroadcast, RouteCalculationSpec}
import fr.acinq.eclair.{ShortChannelId, TestkitBaseClass, randomKey, wire}
import org.junit.runner.RunWith
import org.scalatest.Outcome
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class PeerSpec extends TestkitBaseClass {
  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(100)
  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)
  val channels = fakeRoutingInfo.map(_._1).toList
  val updates = (fakeRoutingInfo.map(_._2) ++ fakeRoutingInfo.map(_._3)).toList
  val nodes = (fakeRoutingInfo.map(_._4) ++ fakeRoutingInfo.map(_._5)).toList

  override type FixtureParam = TestProbe

  override protected def withFixture(test: OneArgTest): Outcome = {
    val probe = TestProbe()
    test(probe)
  }

  test("filter gossip message (no filtering)") { probe =>
    val rebroadcast = Rebroadcast(channels.map(_ -> Set.empty[ActorRef]).toMap, updates.map(_ -> Set.empty[ActorRef]).toMap, nodes.map(_ -> Set.empty[ActorRef]).toMap)
    val (channels1, updates1, nodes1) = Peer.filterGossipMessages(rebroadcast, probe.ref, None)
    assert(channels1.toSet == channels.toSet)
    assert(updates1.toSet == updates.toSet)
    assert(nodes1.toSet == nodes.toSet)
  }

  test("filter gossip message (filtered by origin)") { probe =>
    val rebroadcast = Rebroadcast(
      channels.map(_ -> Set.empty[ActorRef]).toMap + (channels(5) -> Set(probe.ref)),
      updates.map(_ -> Set.empty[ActorRef]).toMap + (updates(6) -> Set(probe.ref)) + (updates(10) -> Set(probe.ref)),
      nodes.map(_ -> Set.empty[ActorRef]).toMap + (nodes(4) -> Set(probe.ref)))
    val (channels1, updates1, nodes1) = Peer.filterGossipMessages(rebroadcast, probe.ref, None)
    assert(channels1.toSet == channels.toSet - channels(5))
    assert(updates1.toSet == updates.toSet - updates(6) - updates(10))
    assert(nodes1.toSet == nodes.toSet - nodes(4))
  }

  test("filter gossip message (filtered by timestamp)") { probe =>
    val rebroadcast = Rebroadcast(channels.map(_ -> Set.empty[ActorRef]).toMap, updates.map(_ -> Set.empty[ActorRef]).toMap, nodes.map(_ -> Set.empty[ActorRef]).toMap)
    val timestamps = updates.map(_.timestamp).sorted.drop(10).take(20)
    val (channels1, updates1, nodes1) = Peer.filterGossipMessages(rebroadcast, probe.ref, Some(wire.GossipTimestampFilter(Block.RegtestGenesisBlock.blockId, timestamps.head, timestamps.last - timestamps.head)))
    assert(updates1.toSet == updates.filter(u => timestamps.contains(u.timestamp)).toSet)
    assert(nodes1.toSet == nodes.filter(u => timestamps.contains(u.timestamp)).toSet)
    assert(channels1.toSet == channels.filter(ca => updates1.map(_.shortChannelId).toSet.contains(ca.shortChannelId)).toSet)
  }

  test("react to peer's bad behavior") { probe =>
    val authenticator = TestProbe()
    val watcher = TestProbe()
    val router = TestProbe()
    val relayer = TestProbe()
    val connection = TestProbe()
    val transport = TestProbe()
    val wallet: EclairWallet = null // unused
    val remoteNodeId = Bob.nodeParams.nodeId
    val peer = system.actorOf(Peer.props(Alice.nodeParams, remoteNodeId, authenticator.ref, watcher.ref, router.ref, relayer.ref, wallet))

    // let's simulate a connection
    probe.send(peer, Peer.Init(None, Set.empty))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, InetSocketAddress.createUnresolved("foo.bar", 42000), false, None))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peer, wire.Init(Bob.nodeParams.globalFeatures, Bob.nodeParams.localFeatures))
    transport.expectMsgType[TransportHandler.ReadAck]
    router.expectNoMsg(1 second) // bob's features require no sync
    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")

    val channels = for (_ <- 0 until 12) yield RouteCalculationSpec.makeChannel(Random.nextInt(10000000), randomKey.publicKey, randomKey.publicKey)
    val updates = for (_ <- 0 until 20) yield RouteCalculationSpec.makeUpdate(Random.nextInt(10000000), randomKey.publicKey, randomKey.publicKey, Random.nextInt(1000), Random.nextInt(1000))._2
    val query = wire.QueryShortChannelIds(Block.RegtestGenesisBlock.hash, ChannelRangeQueries.encodeShortChannelIdsSingle(Seq(ShortChannelId(42000)), ChannelRangeQueries.UNCOMPRESSED_FORMAT, useGzip = false))

    // make sure that routing messages go through
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, ann))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages

    // let's assume that the router isn't happy with those channels because the funding tx is already spent
    for (c <- channels) {
      router.send(peer, Peer.FundingTxAlreadySpent(c))
    }
    // peer will temporary ignore announcements coming from bob
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      transport.expectMsg(TransportHandler.ReadAck(ann))
    }
    // other routing messages go through
    transport.send(peer, query)
    router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, query))

    // after a while the ban is lifted
    probe.send(peer, ResumeAnnouncements)

    // and announcements are processed again
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, ann))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages

    // now let's assume that the router isn't happy with those channels because the funding tx is not found
    for (c <- channels) {
      router.send(peer, Peer.FundingTxNotFound(c))
    }
    // peer will temporary ignore announcements coming from bob
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      transport.expectMsg(TransportHandler.ReadAck(ann))
    }
    // other routing messages go through
    transport.send(peer, query)
    router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, query))

    // after a while the ban is lifted
    probe.send(peer, ResumeAnnouncements)

    // and announcements are processed again
    for (c <- channels) {
      transport.send(peer, c)
      router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, c))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages
  }
}
