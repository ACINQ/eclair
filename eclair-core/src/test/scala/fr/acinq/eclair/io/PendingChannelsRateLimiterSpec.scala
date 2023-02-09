/*
 * Copyright 2023 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.io

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream.{Publish, Subscribe}
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PendingChannelsRateLimiter.{AcceptOpenChannel, AddOrRejectChannel, ChannelRateLimited}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{GetNode, PublicNode, UnknownNode}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Features, MilliSatoshiLong, NodeParams, RealShortChannelId, ShortChannelId, TestConstants, TimestampSecondLong, randomBytes32, randomBytes64}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class PendingChannelsRateLimiterSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val remoteNodeId: Crypto.PublicKey = normal.metaCommitments.remoteNodeId
  def randomNodeId(): Crypto.PublicKey = PrivateKey(randomBytes32()).publicKey
  val temporaryChannelId: ByteVector32 = ByteVector32.Zeroes
  val channelId: ByteVector32 = normal.channelId
  def makeChannelDataPending(remoteNodeId: Crypto.PublicKey, channelId: ByteVector32): DATA_WAIT_FOR_CHANNEL_READY = {
    val metaCommitments = makeChannelDataNormal(Seq(), Map()).metaCommitments.copy(params = normal.metaCommitments.params.copy(channelId = channelId, remoteParams = normal.metaCommitments.params.remoteParams.copy(nodeId = remoteNodeId)))
    DATA_WAIT_FOR_CHANNEL_READY(metaCommitments, ShortIds(RealScidStatus.Final(RealShortChannelId(42)), ShortChannelId.generateLocalAlias(), None))
  }
  val publicPending: PersistentChannelData = makeChannelDataPending(remoteNodeId, channelId)
  def privatePending(nodeId: Crypto.PublicKey = randomNodeId(), channelId: ByteVector32 = randomBytes32()): PersistentChannelData = makeChannelDataPending(nodeId, channelId)
  def announcement(nodeId: PublicKey): NodeAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", NodeAddress.fromParts("1.2.3.4", 42000).get :: Nil)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val router = TestProbe[Router.GetNode]()
    val nodeParams = TestConstants.Alice.nodeParams.copy(channelConf = TestConstants.Alice.nodeParams.channelConf.copy(maxPendingChannelsPerPeer = 1, maxTotalPendingChannelsPrivateNodes = 2))
    val probe = TestProbe[PendingChannelsRateLimiter.Response]()
    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(normal, normal, normal)))
    val eventListener = TestProbe[ChannelEvent]("event-listener")
    system.eventStream ! Subscribe(eventListener.ref)

    withFixture(test.toNoArgTest(FixtureParam(router, nodeParams, probe, limiter, eventListener)))
  }

  def channelData(channelId: ByteVector32): PersistentChannelData = {
    val commitments = CommitmentsSpec.makeCommitments(30000000 msat, 8000000 msat, TestConstants.Alice.nodeParams.nodeId, TestConstants.Bob.nodeParams.nodeId, announceChannel = false)
    DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, BlockHeight(0), None, Left(FundingCreated(channelId, ByteVector32.Zeroes, 3, randomBytes64())))
  }

  case class FixtureParam(router: TestProbe[Router.GetNode], nodeParams: NodeParams, probe: TestProbe[PendingChannelsRateLimiter.Response], limiter: ActorRef[PendingChannelsRateLimiter.Command], eventListener: TestProbe[ChannelEvent])

  test("accept channel open if remote node id on channel opener white list") { f =>
    import f._

    val nodeParams = TestConstants.Alice.nodeParams.copy(channelConf = TestConstants.Alice.nodeParams.channelConf.copy(channelOpenerWhitelist = Set(remoteNodeId)))
    val whiteListLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(normal, normal, normal)))

    whiteListLimiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, temporaryChannelId)
    router.expectNoMessage(10 millis)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
  }

  test("accept channel open if remote node is public and below rate limit") { f =>
    import f._

    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, temporaryChannelId)
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
  }

  test("accept channel open if remote node is private and below rate limit") { f =>
    import f._

    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, temporaryChannelId)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
  }

  test("after restore, accept channel opens from public node with normal channels") { f =>
    import f._
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, channelId)
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // reject second channel open from public node
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, channelId)
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
  }

  test("after restore, accept channel opens from private nodes with normal channels") { f =>
    import f._
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, channelId)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, channelId)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // reject third channel open from private node
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, remoteNodeId, channelId)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(ChannelRateLimited)
  }

  test("after restore accept channel open from new public node") { f =>
    import f._

    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(publicPending, privatePending())))
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)

    // accept open channel with different node id as the restored pending public channel
    val otherRemoteNodeId = PrivateKey(randomBytes32()).publicKey
    restoredLimiter ! AddOrRejectChannel(probe.ref, otherRemoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(otherRemoteNodeId), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)
  }

  test("after restore accept channel from new private node channel id") { f =>
    import f._

    // restore one pending public channel and one pending private channel
    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(publicPending, privatePending())))
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)

    // accept open channel with different channel id as the restored pending private channel
    restoredLimiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(AcceptOpenChannel)
  }

  test("after restore only reject channel open from public node above rate limit") { f =>
    import f._

    // restore one pending public channel and one pending private channel
    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(publicPending, privatePending())))
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)

    // reject new open channel from same node id as the restored pending public channel
    restoredLimiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(ChannelRateLimited)

    // accept new open channel from different node id as the restored pending public channel
    val otherRemoteNodeId = PrivateKey(randomBytes32()).publicKey
    restoredLimiter ! AddOrRejectChannel(probe.ref, otherRemoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(otherRemoteNodeId), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)
  }

  test("after restore reject channel open from private node when above rate limit") { f =>
    import f._

    // restore one pending public channel and two pending private channels
    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(publicPending, privatePending(), privatePending())))
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)

    // reject new open channel from private node
    restoredLimiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(ChannelRateLimited)
  }

  test("after channel id change and channel is opened, remote node is below rate limit") { f =>
    import f._

    // accept one channel from a public node
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, temporaryChannelId)
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)

    // change temporary channel id
    system.eventStream ! Publish(ChannelIdAssigned(TestProbe[Any]().ref.toClassic, remoteNodeId, temporaryChannelId, channelId))
    eventListener.expectMessageType[ChannelIdAssigned]

    // reject new channel from same node id until under rate limit
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(ChannelRateLimited)

    // remove new channel id
    system.eventStream ! Publish(ChannelOpened(TestProbe[Any]().ref.toClassic, remoteNodeId, channelId))
    eventListener.expectMessageType[ChannelOpened]

    // accept new channel from same node id
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)
  }

  test("after channel id change and channel aborted, private nodes are below rate limit") { f =>
    import f._

    // accept two channels from private node
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, temporaryChannelId)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(AcceptOpenChannel)
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(AcceptOpenChannel)

    // change temporary channel id from private node
    system.eventStream ! Publish(ChannelIdAssigned(TestProbe[Any]().ref.toClassic, remoteNodeId, temporaryChannelId, channelId))
    eventListener.expectMessageType[ChannelIdAssigned]

    // reject new channel from private node (over rate limit)
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(ChannelRateLimited)

    // remove channel from private node
    system.eventStream ! Publish(ChannelAborted(TestProbe[Any]().ref.toClassic, remoteNodeId, channelId))
    eventListener.expectMessageType[ChannelAborted]

    // accept new channel from private node
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(remoteNodeId)
    probe.expectMessage(AcceptOpenChannel)
  }

  test("after restore, accept more channels after a pending channel is opened/closed/aborted") { f =>
    import f._

    // restore one pending public channel and two pending private channels
    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(publicPending, privatePending(channelId = temporaryChannelId), privatePending())))
    for(_ <-1 to 3) {
      router.expectMessageType[GetNode] match {
        case r if r.nodeId == remoteNodeId => r.replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
        case r => r.replyTo ! UnknownNode(randomNodeId())
      }
    }

    Seq(
      ChannelOpened(TestProbe[Any]().ref.toClassic, remoteNodeId, channelId),
      ChannelClosed(TestProbe[Any]().ref.toClassic, channelId, null, publicPending.metaCommitments),
      ChannelAborted(TestProbe[Any]().ref.toClassic, remoteNodeId, channelId)
    ).foreach { e =>
      // reject new public channel from same node id
      restoredLimiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
      probe.expectMessage(ChannelRateLimited)

      // remove pending public channel from rate limiter
      system.eventStream ! Publish(e)
      eventListener.expectMessageType[ChannelEvent]

      // accept new channel from same public node id
      restoredLimiter ! AddOrRejectChannel(probe.ref, remoteNodeId, channelId)
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
      probe.expectMessage(AcceptOpenChannel)
    }

    Seq(
      ChannelOpened(TestProbe[Any]().ref.toClassic, randomNodeId(), temporaryChannelId),
      ChannelClosed(TestProbe[Any]().ref.toClassic, temporaryChannelId, null, privatePending().metaCommitments),
      ChannelAborted(TestProbe[Any]().ref.toClassic, randomNodeId(), temporaryChannelId)
    ).foreach { e =>
      // reject new private channel
      restoredLimiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
      router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())
      probe.expectMessage(ChannelRateLimited)

      // remove pending private channel from rate limiter
      system.eventStream ! Publish(e)
      eventListener.expectMessageType[ChannelEvent]

      // accept new private channel
      restoredLimiter ! AddOrRejectChannel(probe.ref, randomNodeId(), temporaryChannelId)
      router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())
      probe.expectMessage(AcceptOpenChannel)
    }
  }

  test("reject any requests that come in during the restore") { f =>
    import f._

    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Seq(publicPending, privatePending(channelId = temporaryChannelId), privatePending())))
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())

    restoredLimiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
    probe.expectMessage(ChannelRateLimited)
  }

  test("reject public node after accepting new public and private nodes") { f =>
    import f._

    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)

    limiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(randomNodeId()), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)

    limiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())
    probe.expectMessage(AcceptOpenChannel)

    // first public node is still rate limited
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(ChannelRateLimited)
  }

  test("reject public node after rejecting new public and private nodes") { f =>
    import f._

    // first public node and first two private nodes are accepted
    val publicNode1 = PrivateKey(randomBytes32()).publicKey
    limiter ! AddOrRejectChannel(probe.ref, publicNode1, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(publicNode1), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)
    limiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())
    probe.expectMessage(AcceptOpenChannel)
    limiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())
    probe.expectMessage(AcceptOpenChannel)

    // second public node is accepted
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(AcceptOpenChannel)

    // first public node is rate limited
    limiter ! AddOrRejectChannel(probe.ref, publicNode1, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(publicNode1), 1, 1 sat)
    probe.expectMessage(ChannelRateLimited)

    // new private node is rate limited
    limiter ! AddOrRejectChannel(probe.ref, randomNodeId(), randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomNodeId())
    probe.expectMessage(ChannelRateLimited)

    // second public node is still rate limited
    limiter ! AddOrRejectChannel(probe.ref, remoteNodeId, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(remoteNodeId), 1, 1 sat)
    probe.expectMessage(ChannelRateLimited)
  }

}
