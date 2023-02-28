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
import akka.actor.typed.eventstream.EventStream.Publish
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PendingChannelsRateLimiter.filterPendingChannels
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{GetNode, PublicNode, UnknownNode}
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, InputInfo}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Features, MilliSatoshiLong, NodeParams, ShortChannelId, TestConstants, TimestampSecondLong, randomBytes32, randomBytes64, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration.DurationInt

class PendingChannelsRateLimiterSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val channelIdBelowLimit1: ByteVector32 = ByteVector32(hex"0111111110000000000000000000000000000000000000000000000000000000")
  val channelIdBelowLimit2: ByteVector32 = ByteVector32(hex"0222222220000000000000000000000000000000000000000000000000000000")
  val newChannelId1: ByteVector32 = ByteVector32(hex"0333333330000000000000000000000000000000000000000000000000000000")
  val newChannelId2: ByteVector32 = ByteVector32(hex"0444444440000000000000000000000000000000000000000000000000000000")
  val channelIdPrivate1: ByteVector32 = ByteVector32(hex"0555555550000000000000000000000000000000000000000000000000000000")
  val channelIdPrivate2: ByteVector32 = ByteVector32(hex"0666666660000000000000000000000000000000000000000000000000000000")
  val newChannelIdPrivate1: ByteVector32 = ByteVector32(hex"077777770000000000000000000000000000000000000000000000000000000")
  val channelIdAtLimit1: ByteVector32 = ByteVector32(hex"0888888880000000000000000000000000000000000000000000000000000000")
  val channelIdAtLimit2: ByteVector32 = ByteVector32(hex"0999999990000000000000000000000000000000000000000000000000000000")

  override protected def withFixture(test: OneArgTest): Outcome = {
    val router = TestProbe[Router.GetNode]()
    val probe = TestProbe[PendingChannelsRateLimiter.Response]()
    val peerOnWhitelist = randomKey().publicKey
    val peerOnWhitelistAtLimit = randomKey().publicKey
    val nodeParams = TestConstants.Alice.nodeParams.copy(channelConf = TestConstants.Alice.nodeParams.channelConf.copy(maxPendingChannelsPerPeer = 2, maxTotalPendingChannelsPrivateNodes = 2, channelOpenerWhitelist = Set(peerOnWhitelist, peerOnWhitelistAtLimit)))
    val tx = Transaction.read("010000000110f01d4a4228ef959681feb1465c2010d0135be88fd598135b2e09d5413bf6f1000000006a473044022074658623424cebdac8290488b76f893cfb17765b7a3805e773e6770b7b17200102202892cfa9dda662d5eac394ba36fcfd1ea6c0b8bb3230ab96220731967bbdb90101210372d437866d9e4ead3d362b01b615d24cc0d5152c740d51e3c55fb53f6d335d82ffffffff01408b0700000000001976a914678db9a7caa2aca887af1177eda6f3d0f702df0d88ac00000000")
    val closingTx = ClosingTx(InputInfo(tx.txIn.head.outPoint, TxOut(10_000 sat, Nil), Nil), tx, None)
    val channelsOnWhitelistAtLimit = Seq(
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(peerOnWhitelistAtLimit, randomBytes32()), BlockHeight(0), None, Left(FundingCreated(randomBytes32(), ByteVector32.Zeroes, 3, randomBytes64()))),
      DATA_WAIT_FOR_CHANNEL_READY(commitments(peerOnWhitelistAtLimit, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
    )
    val peerAtLimit1 = randomKey().publicKey
    val channelsAtLimit1 = Seq(
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(peerAtLimit1, channelIdAtLimit1), BlockHeight(0), None, Left(FundingCreated(channelIdAtLimit1, ByteVector32.Zeroes, 3, randomBytes64()))),
      DATA_WAIT_FOR_CHANNEL_READY(commitments(peerAtLimit1, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
    )
    val peerAtLimit2 = randomKey().publicKey
    val channelsAtLimit2 = Seq(
      DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments(peerAtLimit2, channelIdAtLimit2), 0 msat, 0 msat, BlockHeight(0), BlockHeight(0), RbfStatus.NoRbf, None),
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(peerAtLimit2, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
    )
    val peerBelowLimit1 = randomKey().publicKey
    val channelsBelowLimit1 = Seq(
      DATA_WAIT_FOR_CHANNEL_READY(commitments(peerBelowLimit1, channelIdBelowLimit1), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
    )
    val peerBelowLimit2 = randomKey().publicKey
    val channelsBelowLimit2 = Seq(
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(peerBelowLimit2, channelIdBelowLimit2), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
      DATA_NORMAL(commitments(peerBelowLimit2, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None), None, null, None, None, None),
      DATA_SHUTDOWN(commitments(peerBelowLimit2, randomBytes32()), Shutdown(randomBytes32(), ByteVector.empty), Shutdown(randomBytes32(), ByteVector.empty), None),
      DATA_CLOSING(commitments(peerBelowLimit2, randomBytes32()), BlockHeight(0), ByteVector.empty, List(), List(closingTx))
    )
    val privatePeer1 = randomKey().publicKey
    val privatePeer2 = randomKey().publicKey
    val privateChannels = Seq(
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(privatePeer1, channelIdPrivate1), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
      DATA_NORMAL(commitments(privatePeer2, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None), None, null, None, None, None),
    )
    val publicChannels = channelsOnWhitelistAtLimit ++ channelsAtLimit1 ++ channelsAtLimit2 ++ channelsBelowLimit1 ++ channelsBelowLimit2
    val publicPeers = publicChannels.map(_.commitments.remoteNodeId).toSet
    assert(Set(peerOnWhitelistAtLimit, peerAtLimit1, peerAtLimit2, peerBelowLimit1, peerBelowLimit2) == publicPeers)
    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, publicChannels ++ privateChannels))
    filterPendingChannels(publicChannels ++ privateChannels).foreach {
      case p if publicPeers.contains(p._1) => router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(p._1), 1, 1 sat)
      case p => router.expectMessageType[GetNode].replyTo ! UnknownNode(p._1)
    }
    router.expectNoMessage(10 millis)
    val requests = TestProbe[Int]()

    withFixture(test.toNoArgTest(FixtureParam(router, nodeParams, probe, limiter, Seq(peerAtLimit1, peerAtLimit2), Seq(peerBelowLimit1, peerBelowLimit2), Seq(peerOnWhitelist, peerOnWhitelistAtLimit), Seq(privatePeer1, privatePeer2), requests)))
  }

  def announcement(nodeId: PublicKey): NodeAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", NodeAddress.fromParts("1.2.3.4", 42000).get :: Nil)

  def commitments(remoteNodeId: PublicKey, channelId: ByteVector32): Commitments = {
    val commitments = CommitmentsSpec.makeCommitments(500_000 msat, 400_000 msat, TestConstants.Alice.nodeParams.nodeId, remoteNodeId, announceChannel = true)
    commitments.copy(params = commitments.params.copy(channelId = channelId))
  }

  case class FixtureParam(router: TestProbe[Router.GetNode], nodeParams: NodeParams, probe: TestProbe[PendingChannelsRateLimiter.Response], limiter: ActorRef[PendingChannelsRateLimiter.Command], peersAtLimit: Seq[PublicKey], peersBelowLimit: Seq[PublicKey], peersOnWhitelist: Seq[PublicKey], privatePeers: Seq[PublicKey], requests: TestProbe[Int])

  test("always accept requests from nodes on white list") { f =>
    import f._

    peersOnWhitelist.foreach { peer =>
      for (_ <- 0 to nodeParams.channelConf.maxPendingChannelsPerPeer + nodeParams.channelConf.maxTotalPendingChannelsPrivateNodes) {
        limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
        router.expectNoMessage(10 millis)
        probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
      }
    }
  }

  test("requests from public nodes are only accepted and tracked while under per node limit") { f =>
    import f._

    // peers at limit are rejected
    peersAtLimit.foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // peers below limit will accept and track one more channel request
    peersBelowLimit.foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
    }

    // peers now at limit reject and do not track additional channel requests
    peersBelowLimit.foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // peers initially at limit still reject channel requests
    peersAtLimit.foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // when new channel ids assigned, stop tracking the old channel id and only track the new one
    system.eventStream ! Publish(ChannelIdAssigned(null, peersBelowLimit.head, channelIdBelowLimit1, newChannelId1))
    system.eventStream ! Publish(ChannelIdAssigned(null, peersBelowLimit.last, channelIdBelowLimit2, newChannelId2))

    // ignore channel id assignments for untracked channels
    (peersBelowLimit ++ peersAtLimit).foreach { peer =>
      system.eventStream ! Publish(ChannelIdAssigned(null, peer, randomBytes32(), randomBytes32()))
    }

    // ignore channel id assignments for private peers
    system.eventStream ! Publish(ChannelIdAssigned(null, randomKey().publicKey, channelIdPrivate1, newChannelIdPrivate1))

    // ignore confirm/close/abort events for channels not tracked for a public peer
    system.eventStream ! Publish(ChannelOpened(null, peersAtLimit.head, newChannelId1))
    system.eventStream ! Publish(ChannelClosed(null, channelIdAtLimit1, null, commitments(peersBelowLimit.head, randomBytes32())))
    system.eventStream ! Publish(ChannelAborted(null, peersBelowLimit.last, randomBytes32()))

    // after channel events for untracked channels, new channel requests for public peers are still rejected
    (peersBelowLimit ++ peersAtLimit).foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // stop tracking channels that are confirmed/closed/aborted for a public peer
    limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
    requests.expectMessage(10)
    system.eventStream ! Publish(ChannelOpened(null, peersAtLimit.head, channelIdAtLimit1))
    system.eventStream ! Publish(ChannelClosed(null, newChannelId1, null, commitments(peersBelowLimit.head, newChannelId1)))
    system.eventStream ! Publish(ChannelAborted(null, peersBelowLimit.last, newChannelId2))
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
      requests.expectMessage(7)
    }

    // new channel requests for peers below limit are accepted after matching confirmed/closed/aborted
    (peersBelowLimit :+ peersAtLimit.head).foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
    }

    // new channels requests for untracked public peers does not change previously tracked peers
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(randomKey().publicKey), 1, 1 sat)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // public peers at limit still reject channel requests
    (peersBelowLimit ++ peersAtLimit).foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }
  }

  test("requests from private nodes are only accepted and tracked while under global limit") { f =>
    import f._

    // channels requests are accepted when below private channels limit
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, privatePeers.last, channelIdPrivate2)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(privatePeers.last)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // channels requests are rejected when at the private channels limit
    for (_ <- 0 until 2) {
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // when new channel ids assigned, stop tracking the old channel id and only track the new one
    system.eventStream ! Publish(ChannelIdAssigned(null, privatePeers.head, channelIdPrivate1, newChannelIdPrivate1))

    // ignore channel id assignments for untracked node/channel pairs
    system.eventStream ! Publish(ChannelIdAssigned(null, randomKey().publicKey, channelIdPrivate1, randomBytes32()))
    system.eventStream ! Publish(ChannelIdAssigned(null, privatePeers.head, randomBytes32(), randomBytes32()))

    // ignore channel id assignments for public peer channels
    system.eventStream ! Publish(ChannelIdAssigned(null, peersBelowLimit.head, channelIdBelowLimit1, newChannelId1))
    system.eventStream ! Publish(ChannelIdAssigned(null, peersBelowLimit.last, channelIdBelowLimit2, newChannelId2))

    // ignore confirm/close/abort events for node/channel pairs not tracked for a private peer
    system.eventStream ! Publish(ChannelOpened(null, privatePeers.head, newChannelId1))
    system.eventStream ! Publish(ChannelClosed(null, newChannelId1, null, commitments(privatePeers.last, newChannelId1)))
    system.eventStream ! Publish(ChannelAborted(null, peersBelowLimit.last, newChannelIdPrivate1))

    // after channel events for untracked channels, new channel requests for private peers are still rejected
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // stop tracking channels that are confirmed/closed/aborted for a private peer
    limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
    requests.expectMessage(2)
    system.eventStream ! Publish(ChannelOpened(null, privatePeers.head, newChannelIdPrivate1))
    system.eventStream ! Publish(ChannelClosed(null, channelIdPrivate2, null, commitments(privatePeers.last, channelIdPrivate2)))
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
      requests.expectMessage(0)
    }

    // new channel requests for peers below limit are accepted after matching confirmed/closed/aborted
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, privatePeers.head, channelIdPrivate1)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(privatePeers.head)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // second request from a different node but with the same channel id
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, channelIdPrivate1)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // abort the reused channel id for one private node; private channels now under the limit by one
    system.eventStream ! Publish(ChannelAborted(null, privatePeers.head, channelIdPrivate1))
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
      requests.expectMessage(1)
    }

    // new channels requests for untracked public peers do not increase the limit for private peers
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, channelIdPrivate1)
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(randomKey().publicKey), 1, 1 sat)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // add a new private node that reuses the channel id again; private channels will be at the limit again
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, channelIdPrivate1)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // reject channel requests from private peers when at the limit
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
  }

  test("reject any requests that come in during the restore") { f =>
    import f._

    val channels = Seq(
      DATA_WAIT_FOR_CHANNEL_READY(commitments(randomKey().publicKey, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(randomKey().publicKey, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None)),
      DATA_NORMAL(commitments(randomKey().publicKey, randomBytes32()), ShortIds(RealScidStatus.Unknown, ShortChannelId.generateLocalAlias(), None), None, null, None, None, None),
      DATA_SHUTDOWN(commitments(randomKey().publicKey, randomBytes32()), Shutdown(randomBytes32(), ByteVector.empty), Shutdown(randomBytes32(), ByteVector.empty), None),
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(randomKey().publicKey, randomBytes32()), BlockHeight(0), None, Left(FundingCreated(randomBytes32(), ByteVector32.Zeroes, 3, randomBytes64()))),
    )
    val restoredLimiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, channels))

    // process one restored private channel
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)

    // handle a request that comes in during the restore
    restoredLimiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // process one restored public peer channel
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(randomKey().publicKey), 1, 1 sat)

    // handle a request that comes in during the restore
    restoredLimiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // process last restored public peer channel
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(randomKey().publicKey), 1, 1 sat)

    // handle new channel requests for a private peer
    restoredLimiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
  }

}
