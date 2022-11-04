package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchExternalChannelSpent, WatchExternalChannelSpentTriggered, WatchFundingDeeplyBuriedTriggered}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.channel.{CMD_CLOSE, DATA_NORMAL}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelUpdate, Shutdown}
import fr.acinq.eclair.{BlockHeight, TestKitBaseClass}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ChannelRouterIntegrationSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(router: TestFSMRef[Router.State, Router.Data, Router], rebroadcastListener: TestProbe, channels: SetupFixture, testTags: Set[String]) {
    //@formatter:off
    /** there is only one channel here */
    def privateChannel: PrivateChannel = router.stateData.privateChannels.values.head
    def publicChannel: PublicChannel = router.stateData.channels.values.head
    //@formatter:on
  }

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val channels = init(tags = test.tags)
    val rebroadcastListener = TestProbe()
    val router: TestFSMRef[Router.State, Router.Data, Router] = {
      // we use alice's actor system so we share the same event stream
      implicit val system: ActorSystem = channels.alice.underlying.system
      system.eventStream.subscribe(rebroadcastListener.ref, classOf[Router.Rebroadcast])
      TestFSMRef(new Router(channels.alice.underlyingActor.nodeParams, channels.alice.underlyingActor.blockchain, initialized = None))
    }
    withFixture(test.toNoArgTest(FixtureParam(router, rebroadcastListener, channels, test.tags)))
  }

  private def internalTest(f: FixtureParam): Unit = {
    import f._

    val (aliceNodeId, bobNodeId) = (channels.alice.underlyingActor.nodeParams.nodeId, channels.bob.underlyingActor.nodeParams.nodeId)
    reachNormal(channels, testTags, interceptChannelUpdates = false)

    // the router learns about the local, still unannounced, channel
    awaitCond(router.stateData.privateChannels.size == 1)

    // only alice's channel_update is known (NB : due to how node ids are constructed, 1 = alice and 2 = bob)
    assert(privateChannel.update_1_opt.isDefined)
    assert(privateChannel.update_2_opt.isEmpty)
    // alice will only have a real scid if this is not a zeroconf channel
    assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real.toOption.isEmpty == f.testTags.contains(ChannelStateTestsTags.ZeroConf))
    assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.remoteAlias_opt.isDefined)
    // alice uses her alias for her internal channel update
    val aliceInitialChannelUpdate = privateChannel.update_1_opt.value
    assert(aliceInitialChannelUpdate.shortChannelId == privateChannel.shortIds.localAlias)

    // alice and bob send their channel_updates using remote alias when they go to NORMAL state
    val aliceChannelUpdate1 = channels.alice2bob.expectMsgType[ChannelUpdate]
    val bobChannelUpdate1 = channels.bob2alice.expectMsgType[ChannelUpdate]
    // alice's channel_update uses bob's alias, and vice versa
    assert(aliceChannelUpdate1.shortChannelId == channels.bob.stateData.asInstanceOf[DATA_NORMAL].shortIds.localAlias)
    assert(bobChannelUpdate1.shortChannelId == channels.alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.localAlias)
    // channel_updates are handled by the peer connection and sent to the router
    val peerConnection = TestProbe()
    router ! PeerRoutingMessage(peerConnection.ref, bobNodeId, bobChannelUpdate1)

    // router processes bob's channel_update and now knows both channel updates
    awaitCond {
      privateChannel.update_1_opt.contains(aliceInitialChannelUpdate) && privateChannel.update_2_opt.contains(bobChannelUpdate1)
    }

    // there is nothing for the router to rebroadcast, channel is not announced
    assert(router.stateData.rebroadcast == Rebroadcast(Map.empty, Map.empty, Map.empty))

    // router graph contains a single channel
    assert(router.stateData.graphWithBalances.graph.vertexSet() == Set(aliceNodeId, bobNodeId))
    assert(router.stateData.graphWithBalances.graph.edgeSet().toSet == Set(GraphEdge(aliceInitialChannelUpdate, privateChannel), GraphEdge(bobChannelUpdate1, privateChannel)))

    if (testTags.contains(ChannelStateTestsTags.ChannelsPublic)) {
      // this is a public channel
      // funding tx reaches 6 blocks, announcements are exchanged
      channels.alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
      channels.alice2bob.expectMsgType[AnnouncementSignatures]
      channels.alice2bob.forward(channels.bob)

      channels.bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
      channels.bob2alice.expectMsgType[AnnouncementSignatures]
      channels.bob2alice.forward(channels.alice)

      // the router learns about the announcement and channel graduates from private to public
      awaitCond {
        router.stateData.privateChannels.isEmpty && router.stateData.channels.size == 1
      }

      // router has cleaned up the scid mapping
      assert(router.stateData.scid2PrivateChannels.isEmpty)

      // alice and bob won't send their channel_update directly to each other because the channel has been announced
      // but we can get the update from their data
      awaitCond {
        channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.isDefined &&
          channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.isDefined
      }
      val aliceChannelUpdate2 = channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
      val bobChannelUpdate2 = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
      // this time, they use the real scid
      val aliceAnn = channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get
      val bobAnn = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get
      assert(aliceAnn == bobAnn)
      assert(aliceChannelUpdate2.shortChannelId == aliceAnn.shortChannelId)
      assert(!aliceChannelUpdate2.dontForward)
      assert(bobChannelUpdate2.shortChannelId == bobAnn.shortChannelId)
      assert(!bobChannelUpdate2.dontForward)

      // the router has already processed the new local channel update from alice which uses the real scid, and keeps bob's previous channel update
      assert(publicChannel.update_1_opt.contains(aliceChannelUpdate2) && publicChannel.update_2_opt.contains(bobChannelUpdate1))

      // the router prepares to rebroadcast the channel announcement, the local update which uses the real scid, and the first node announcement
      assert(router.stateData.rebroadcast == Rebroadcast(
        channels = Map(aliceAnn -> Set[GossipOrigin](LocalGossip)),
        updates = Map(aliceChannelUpdate2 -> Set[GossipOrigin](LocalGossip)),
        nodes = Map(router.stateData.nodes.values.head -> Set[GossipOrigin](LocalGossip)))
      )

      // bob's channel_update reaches the router
      router ! PeerRoutingMessage(peerConnection.ref, bobNodeId, bobChannelUpdate2)

      // router processes bob's channel_update and now knows both channel updates with real scids
      awaitCond {
        publicChannel.update_1_opt.contains(aliceChannelUpdate2) && publicChannel.update_2_opt.contains(bobChannelUpdate2)
      }

      // router is now ready to rebroadcast both channel updates
      assert(router.stateData.rebroadcast == Rebroadcast(
        channels = Map(aliceAnn -> Set[GossipOrigin](LocalGossip)),
        updates = Map(
          aliceChannelUpdate2 -> Set[GossipOrigin](LocalGossip),
          bobChannelUpdate2 -> Set[GossipOrigin](RemoteGossip(peerConnection.ref, bobNodeId))
        ),
        nodes = Map(router.stateData.nodes.values.head -> Set[GossipOrigin](LocalGossip)))
      )

      // router graph contains a single channel
      assert(router.stateData.graphWithBalances.graph.vertexSet() == Set(aliceNodeId, bobNodeId))
      assert(router.stateData.graphWithBalances.graph.edgeSet().size == 2)
      assert(router.stateData.graphWithBalances.graph.edgeSet().toSet == Set(GraphEdge(aliceChannelUpdate2, publicChannel), GraphEdge(bobChannelUpdate2, publicChannel)))
    } else {
      // this is a private channel
      // funding tx reaches 6 blocks, no announcements are exchanged because the channel is private
      channels.alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
      channels.bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)

      // alice and bob won't send their channel_update directly to each other because they haven't changed
      channels.alice2bob.expectNoMessage(100 millis)
      channels.bob2alice.expectNoMessage(100 millis)
      assert(privateChannel.update_1_opt.get.dontForward)
      assert(privateChannel.update_2_opt.get.dontForward)

      // router graph contains a single channel
      assert(router.stateData.graphWithBalances.graph.vertexSet() == Set(aliceNodeId, bobNodeId))
      assert(router.stateData.graphWithBalances.graph.edgeSet().toSet == Set(GraphEdge(aliceInitialChannelUpdate, privateChannel), GraphEdge(bobChannelUpdate1, privateChannel)))
    }
    // channel closes
    channels.alice ! CMD_CLOSE(TestProbe().ref, scriptPubKey = None, feerates = None)
    channels.alice2bob.expectMsgType[Shutdown]
    channels.alice2bob.forward(channels.bob)
    channels.bob2alice.expectMsgType[Shutdown]
    channels.bob2alice.forward(channels.alice)
    if (testTags.contains(ChannelStateTestsTags.ChannelsPublic)) {
      // if the channel was public, the router asked the watcher to watch the funding tx and will be notified
      val watchSpentBasic = channels.alice2blockchain.expectMsgType[WatchExternalChannelSpent]
      watchSpentBasic.replyTo ! WatchExternalChannelSpentTriggered(watchSpentBasic.shortChannelId)
    }
    // router cleans up the channel
    awaitAssert {
      assert(router.stateData.nodes == Map.empty)
      assert(router.stateData.channels == Map.empty)
      assert(router.stateData.privateChannels == Map.empty)
      assert(router.stateData.scid2PrivateChannels == Map.empty)
      assert(router.stateData.graphWithBalances.graph.edgeSet().isEmpty)
      // TODO: we're not currently pruning nodes without channels from the graph, but we should!
      // assert(router.stateData.graphWithBalances.graph.vertexSet().isEmpty)
    }
  }

  test("private local channel") { f =>
    internalTest(f)
  }

  test("private local channel (zeroconf)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    internalTest(f)
  }

  test("public local channel", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    internalTest(f)
  }

  test("public local channel (zeroconf)", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    internalTest(f)
  }

}
