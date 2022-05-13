package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingDeeplyBuriedTriggered
import fr.acinq.eclair.blockchain.bitcoind.{ZmqWatcher, ZmqWatcherSpec}
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.wire.protocol.AnnouncementSignatures
import fr.acinq.eclair.{BlockHeight, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

/**
 * This test checks the integration between Channel and Router (events, etc.)
 */
class ChannelRouterIntegrationSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(router: TestFSMRef[Router.State, Router.Data, Router], channels: SetupFixture, testTags: Set[String])

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

  test("private local channel") { f =>
    import f._

    reachNormal(channels, testTags)

    awaitCond(router.stateData.privateChannels.size == 1)

    {
      // only the local channel_update is known
      val pc = router.stateData.privateChannels.head._2
      assert(pc.update_1_opt.isDefined ^ pc.update_2_opt.isDefined)
    }

    val peerConnection = TestProbe()
    val bobChannelUpdate = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    router ! PeerRoutingMessage(peerConnection.ref, channels.bob.underlyingActor.nodeParams.nodeId, bobChannelUpdate)

    awaitCond {
      // only the local channel_update is known
      val pc = router.stateData.privateChannels.head._2
      pc.update_1_opt.isDefined && pc.update_2_opt.isDefined
    }

  }

  test("public local channel", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._

    val fundingTx = reachNormal(channels, testTags)

    awaitCond(router.stateData.privateChannels.size == 1)

    {
      val pc = router.stateData.privateChannels.head._2
      // only the local channel_update is known
      assert(pc.update_1_opt.isDefined ^ pc.update_2_opt.isDefined)
    }

    val peerConnection = TestProbe()
    val bobChannelUpdate = channels.bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    router ! PeerRoutingMessage(peerConnection.ref, channels.bob.underlyingActor.nodeParams.nodeId, bobChannelUpdate)

    awaitCond {
      val pc = router.stateData.privateChannels.head._2
      // both channel_updates are known
      pc.update_1_opt.isDefined && pc.update_2_opt.isDefined
    }

    // funding tx reaches 6 blocks, announcements are exchanged
    channels.alice ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    channels.alice2bob.expectMsgType[AnnouncementSignatures]
    channels.alice2bob.forward(channels.bob)

    channels.bob ! WatchFundingDeeplyBuriedTriggered(BlockHeight(400000), 42, null)
    channels.bob2alice.expectMsgType[AnnouncementSignatures]
    channels.bob2alice.forward(channels.alice)

    // router gets notified and attempts to validate the local channel
    val vr = channels.alice2blockchain.expectMsgType[ZmqWatcher.ValidateRequest]
    vr.replyTo ! ZmqWatcher.ValidateResult(vr.ann, Right((fundingTx, ZmqWatcher.UtxoStatus.Unspent)))

    awaitCond {
      router.stateData.privateChannels.isEmpty && router.stateData.channels.size == 1
    }

    awaitCond {
      val pc = router.stateData.channels.head._2
      // both channel updates are preserved
      pc.update_1_opt.isDefined && pc.update_2_opt.isDefined
    }

  }

}
