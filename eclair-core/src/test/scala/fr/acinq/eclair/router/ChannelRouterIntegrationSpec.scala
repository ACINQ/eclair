package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingDeeplyBuriedTriggered
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelUpdate}
import fr.acinq.eclair.{BlockHeight, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ChannelRouterIntegrationSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(router: TestFSMRef[Router.State, Router.Data, Router], rebroadcastListener: TestProbe, channels: SetupFixture, testTags: Set[String])

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

  test("private local channel") { f =>
    import f._

    reachNormal(channels, testTags)

    awaitCond(router.stateData.privateChannels.size == 1)

    {
      // only the local channel_update is known (bob won't send his before the channel is deeply buried)
      val pc = router.stateData.privateChannels.values.head
      assert(pc.update_1_opt.isDefined ^ pc.update_2_opt.isDefined)
    }

    val peerConnection = TestProbe()
    // bob hasn't yet sent his channel_update but we can get it by looking at its internal data
    val bobChannelUpdate = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    router ! PeerRoutingMessage(peerConnection.ref, channels.bob.underlyingActor.nodeParams.nodeId, bobChannelUpdate)

    awaitCond {
      val pc = router.stateData.privateChannels.values.head
      // both channel_updates are known
      pc.update_1_opt.isDefined && pc.update_2_opt.isDefined
    }

    // manual rebroadcast
    router ! Router.TickBroadcast
    rebroadcastListener.expectNoMessage()

  }

  test("public local channel", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._

    reachNormal(channels, testTags, interceptChannelUpdates = false)

    //@formatter:off
    /** there is only one channel here */
    def privateChannel: PrivateChannel = router.stateData.privateChannels.values.head
    def publicChannel: PublicChannel = router.stateData.channels.values.head
    //@formatter:on

    // the router learns about the local, still unannounced, channel
    awaitCond(router.stateData.privateChannels.size == 1)

    // only alice's channel_update is known (NB : due to how node ids are constructed, 1 = alice and 2 = bob)
    assert(privateChannel.update_1_opt.isDefined)
    assert(privateChannel.update_2_opt.isEmpty)
    // alice already has a real scid because this is not a zeroconf channel
    assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].realShortChannelId_opt.isDefined)
    assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].remoteAlias_opt.isDefined)
    // alice uses bob's alias for her channel update
    assert(privateChannel.update_1_opt.get.shortChannelId != privateChannel.localAlias)
    assert(privateChannel.update_1_opt.get.shortChannelId == channels.alice.stateData.asInstanceOf[DATA_NORMAL].remoteAlias_opt.get)

    // alice and bob send their channel_updates using remote alias when they go to NORMAL state
    val aliceChannelUpdate1 = channels.alice2bob.expectMsgType[ChannelUpdate]
    val bobChannelUpdate1 = channels.bob2alice.expectMsgType[ChannelUpdate]
    // alice's channel_update uses bob's alias, and vice versa
    assert(aliceChannelUpdate1.shortChannelId == channels.bob.stateData.asInstanceOf[DATA_NORMAL].localAlias)
    assert(bobChannelUpdate1.shortChannelId == channels.alice.stateData.asInstanceOf[DATA_NORMAL].localAlias)
    // channel_updates are handled by the peer connection and sent to the router
    val peerConnection = TestProbe()
    router ! PeerRoutingMessage(peerConnection.ref, channels.bob.underlyingActor.nodeParams.nodeId, bobChannelUpdate1)

    // router processes bob's channel_update and now knows both channel updates
    awaitCond {
      privateChannel.update_1_opt.contains(aliceChannelUpdate1) && privateChannel.update_2_opt.contains(bobChannelUpdate1)
    }

    // there is nothing for the router to rebroadcast, channel is not announced
    assert(router.stateData.rebroadcast == Rebroadcast(Map.empty, Map.empty, Map.empty))

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

    // alice and bob won't send their channel_update directly to each other because the channel has been announced
    // but we can get the update from their data
    awaitCond {
      channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.isDefined &&
        channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.isDefined
    }
    val aliceChannelUpdate2 = channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    val bobChannelUpdate2 = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    // this time, they use the real scid
    assert(aliceChannelUpdate2.shortChannelId == channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get.shortChannelId)
    assert(bobChannelUpdate2.shortChannelId == channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get.shortChannelId)

    // the router has already processed the new local channel update from alice which uses the real scid, and keeps bob's previous channel update
    assert(publicChannel.update_1_opt.contains(aliceChannelUpdate2) && publicChannel.update_2_opt.contains(bobChannelUpdate1))

    // the router prepares to rebroadcast the channel announcement, the local update which use the real scid, and the first node announcement
    assert(router.stateData.rebroadcast == Rebroadcast(
      channels = Map(channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get -> Set[GossipOrigin](LocalGossip)),
      updates = Map(aliceChannelUpdate2 -> Set[GossipOrigin](LocalGossip)),
      nodes = Map(router.stateData.nodes.values.head -> Set[GossipOrigin](LocalGossip)))
    )

    // bob's channel_update reaches the router
    router ! PeerRoutingMessage(peerConnection.ref, channels.bob.underlyingActor.nodeParams.nodeId, bobChannelUpdate2)

    // router processes bob's channel_update and now knows both channel updates with real scids
    awaitCond {
      publicChannel.update_1_opt.contains(aliceChannelUpdate2) && publicChannel.update_2_opt.contains(bobChannelUpdate2)
    }

    // router is now ready to rebroadcast both channel updates
    assert(router.stateData.rebroadcast == Rebroadcast(
      channels = Map(channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get -> Set[GossipOrigin](LocalGossip)),
      updates = Map(
        aliceChannelUpdate2 -> Set[GossipOrigin](LocalGossip),
        bobChannelUpdate2 -> Set[GossipOrigin](RemoteGossip(peerConnection.ref, nodeId = channels.bob.underlyingActor.nodeParams.nodeId))),
      nodes = Map(router.stateData.nodes.values.head -> Set[GossipOrigin](LocalGossip)))
    )
  }

}
