package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.channel.{CMD_CLOSE, DATA_NORMAL}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelUpdate, Shutdown}
import fr.acinq.eclair.{BlockHeight, TestKitBaseClass, randomKey}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ChannelRouterIntegrationSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(router: TestFSMRef[Router.State, Router.Data, Router], channels: SetupFixture, testTags: Set[String]) {
    //@formatter:off
    /** there is only one channel here */
    def privateChannel: PrivateChannel = router.stateData.privateChannels.values.head
    def publicChannel: PublicChannel = router.stateData.channels.values.head
    //@formatter:on
  }

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val channels = init(tags = test.tags)
    val router: TestFSMRef[Router.State, Router.Data, Router] = {
      // we use alice's actor system so we share the same event stream
      implicit val system: ActorSystem = channels.alice.underlying.system
      TestFSMRef(new Router(channels.alice.underlyingActor.nodeParams, channels.alice.underlyingActor.blockchain, initialized = None))
    }
    withFixture(test.toNoArgTest(FixtureParam(router, channels, test.tags)))
  }

  private def internalTest(f: FixtureParam): Unit = {
    import f._

    val (aliceNodeId, bobNodeId) = (channels.alice.underlyingActor.nodeParams.nodeId, channels.bob.underlyingActor.nodeParams.nodeId)
    val fundingTx = reachNormal(channels, testTags + ChannelStateTestsTags.DoNotInterceptGossip)

    // The router learns about the local, still unannounced, channel.
    awaitCond(router.stateData.privateChannels.size == 1)

    // Alice only has her local channel_update at that point (NB: due to how node ids are constructed, 1 = alice and 2 = bob).
    assert(privateChannel.update_1_opt.isDefined)
    assert(privateChannel.update_2_opt.isEmpty)
    // Alice will only have a real scid if this is not a zeroconf channel.
    assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.real_opt.isEmpty == testTags.contains(ChannelStateTestsTags.ZeroConf))
    assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.remoteAlias_opt.isDefined)
    // Alice uses her alias for her internal channel update.
    val aliceInitialChannelUpdate = privateChannel.update_1_opt.value
    assert(aliceInitialChannelUpdate.shortChannelId == privateChannel.shortIds.localAlias)

    // If the channel is public and confirmed, announcement signatures are sent.
    val (annSigsA_opt, annSigsB_opt) = if (testTags.contains(ChannelStateTestsTags.ChannelsPublic) && !testTags.contains(ChannelStateTestsTags.ZeroConf)) {
      val annSigsA = channels.alice2bob.expectMsgType[AnnouncementSignatures]
      val annSigsB = channels.bob2alice.expectMsgType[AnnouncementSignatures]
      (Some(annSigsA), Some(annSigsB))
    } else {
      (None, None)
    }

    // In all cases, Alice and bob send their channel_updates using the remote alias when they go to NORMAL state.
    val aliceChannelUpdate1 = channels.alice2bob.expectMsgType[ChannelUpdate]
    val bobChannelUpdate1 = channels.bob2alice.expectMsgType[ChannelUpdate]
    // Alice's channel_update uses bob's alias, and vice versa.
    assert(aliceChannelUpdate1.shortChannelId == channels.bob.stateData.asInstanceOf[DATA_NORMAL].shortIds.localAlias)
    assert(bobChannelUpdate1.shortChannelId == channels.alice.stateData.asInstanceOf[DATA_NORMAL].shortIds.localAlias)
    // The channel_updates are handled by the peer connection and sent to the router.
    val peerConnection = TestProbe()
    router ! PeerRoutingMessage(peerConnection.ref, bobNodeId, bobChannelUpdate1)

    // The router processes bob's channel_update and now knows both channel updates.
    awaitCond(privateChannel.update_1_opt.contains(aliceInitialChannelUpdate) && privateChannel.update_2_opt.contains(bobChannelUpdate1))

    // There is nothing for the router to rebroadcast, because the channel is not announced yet.
    assert(router.stateData.rebroadcast == Rebroadcast(Map.empty, Map.empty, Map.empty))

    // The router graph contains a single channel between Alice and Bob.
    assert(router.stateData.graphWithBalances.graph.vertexSet() == Set(aliceNodeId, bobNodeId))
    assert(router.stateData.graphWithBalances.graph.edgeSet().toSet == Set(GraphEdge(aliceInitialChannelUpdate, privateChannel), GraphEdge(bobChannelUpdate1, privateChannel)))

    // The channel now confirms, if it hadn't confirmed already.
    if (testTags.contains(ChannelStateTestsTags.ZeroConf)) {
      channels.alice ! WatchFundingConfirmedTriggered(BlockHeight(400_000), 42, fundingTx)
      channels.alice2blockchain.expectMsgType[WatchFundingSpent]
      channels.bob ! WatchFundingConfirmedTriggered(BlockHeight(400_000), 42, fundingTx)
      channels.bob2blockchain.expectMsgType[WatchFundingSpent]
    }

    if (testTags.contains(ChannelStateTestsTags.ChannelsPublic)) {
      // The channel is public, so Alice and Bob exchange announcement signatures.
      val annSigsA = annSigsA_opt.getOrElse(channels.alice2bob.expectMsgType[AnnouncementSignatures])
      val annSigsB = annSigsB_opt.getOrElse(channels.bob2alice.expectMsgType[AnnouncementSignatures])
      channels.alice2bob.forward(channels.bob, annSigsA)
      channels.bob2alice.forward(channels.alice, annSigsB)

      // The router learns about the announcement and the channel graduates from private to public.
      awaitAssert {
        assert(router.stateData.privateChannels.isEmpty)
        assert(router.stateData.scid2PrivateChannels.isEmpty)
        assert(router.stateData.channels.size == 1)
      }

      // Alice and Bob won't send their channel_update directly to each other because the channel has been announced
      // but we can get the update from their data
      awaitAssert {
        assert(channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.isDefined)
        assert(channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.isDefined)
      }
      val aliceChannelUpdate2 = channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
      val bobChannelUpdate2 = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
      // Channel updates now use the real scid because the channel has been announced.
      val aliceAnn = channels.alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get
      val bobAnn = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement.get
      assert(aliceAnn == bobAnn)
      assert(aliceChannelUpdate2.shortChannelId == aliceAnn.shortChannelId)
      assert(!aliceChannelUpdate2.dontForward)
      assert(bobChannelUpdate2.shortChannelId == bobAnn.shortChannelId)
      assert(!bobChannelUpdate2.dontForward)

      // The router has already processed the new local channel update from alice which uses the real scid, and keeps bob's previous channel update.
      assert(publicChannel.update_1_opt.contains(aliceChannelUpdate2) && publicChannel.update_2_opt.contains(bobChannelUpdate1))

      // The router will rebroadcast the channel announcement, the local update which uses the real scid, and the first node announcement.
      assert(router.stateData.rebroadcast == Rebroadcast(
        channels = Map(aliceAnn -> Set[GossipOrigin](LocalGossip)),
        updates = Map(aliceChannelUpdate2 -> Set[GossipOrigin](LocalGossip)),
        nodes = Map(router.stateData.nodes.values.head -> Set[GossipOrigin](LocalGossip)))
      )

      // Bob's new channel_update (using the real scid) reaches the router.
      router ! PeerRoutingMessage(peerConnection.ref, bobNodeId, bobChannelUpdate2)

      // The router processes bob's channel_update and now uses channel updates with real scids.
      awaitCond(publicChannel.update_1_opt.contains(aliceChannelUpdate2) && publicChannel.update_2_opt.contains(bobChannelUpdate2))

      // The router is now ready to rebroadcast both channel updates.
      assert(router.stateData.rebroadcast == Rebroadcast(
        channels = Map(aliceAnn -> Set[GossipOrigin](LocalGossip)),
        updates = Map(
          aliceChannelUpdate2 -> Set[GossipOrigin](LocalGossip),
          bobChannelUpdate2 -> Set[GossipOrigin](RemoteGossip(peerConnection.ref, bobNodeId))
        ),
        nodes = Map(router.stateData.nodes.values.head -> Set[GossipOrigin](LocalGossip)))
      )

      // The router graph still contains a single channel, with public updates.
      assert(router.stateData.graphWithBalances.graph.vertexSet() == Set(aliceNodeId, bobNodeId))
      assert(router.stateData.graphWithBalances.graph.edgeSet().size == 2)
      assert(router.stateData.graphWithBalances.graph.edgeSet().toSet == Set(GraphEdge(aliceChannelUpdate2, publicChannel), GraphEdge(bobChannelUpdate2, publicChannel)))
    } else {
      // This is a private channel: alice and bob won't send a new channel_update to each other, even though the channel
      // is confirmed, because they will keep using the scid aliases.
      channels.alice2bob.expectNoMessage(100 millis)
      channels.bob2alice.expectNoMessage(100 millis)
      assert(privateChannel.update_1_opt.get.dontForward)
      assert(privateChannel.update_2_opt.get.dontForward)

      // The router graph still contains a single private channel.
      assert(router.stateData.graphWithBalances.graph.vertexSet() == Set(aliceNodeId, bobNodeId))
      assert(router.stateData.graphWithBalances.graph.edgeSet().toSet == Set(GraphEdge(aliceInitialChannelUpdate, privateChannel), GraphEdge(bobChannelUpdate1, privateChannel)))
    }

    // The channel closes.
    channels.alice ! CMD_CLOSE(TestProbe().ref, scriptPubKey = None, feerates = None)
    channels.alice2bob.expectMsgType[Shutdown]
    channels.alice2bob.forward(channels.bob)
    channels.bob2alice.expectMsgType[Shutdown]
    channels.bob2alice.forward(channels.alice)
    // If the channel was public, the router is notified when the funding tx is spent.
    if (testTags.contains(ChannelStateTestsTags.ChannelsPublic)) {
      val closingTx = Transaction(2, Nil, Seq(TxOut(100_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
      val watchSpent = channels.alice2blockchain.expectMsgType[WatchExternalChannelSpent]
      watchSpent.replyTo ! WatchExternalChannelSpentTriggered(watchSpent.shortChannelId, closingTx)
      val watchConfirmed = channels.alice2blockchain.expectMsgType[WatchTxConfirmed]
      assert(watchConfirmed.txId == closingTx.txid)
      watchConfirmed.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400_000), 42, closingTx)
    }
    // The router can now clean up the closed channel.
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
