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
import akka.actor.typed.eventstream.EventStream.Publish
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong, Transaction, TxId, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PendingChannelsRateLimiter.filterPendingChannels
import fr.acinq.eclair.router.Router.{GetNode, PublicNode, UnknownNode}
import fr.acinq.eclair.router.{Announcements, Router}
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, InputInfo}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Features, MilliSatoshiLong, NodeParams, RealShortChannelId, ShortChannelId, TestConstants, TimestampSecondLong, randomBytes32, randomBytes64, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration.DurationInt

class PendingChannelsRateLimiterSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  // We only allow 2 pending channels per public peer and 2 pending channels for all private peers.
  val maxPendingChannelsPerPeer = 2
  val maxTotalPendingChannelsPrivateNodes = 2

  val channelIdBelowLimit1: ByteVector32 = ByteVector32(hex"0111111110000000000000000000000000000000000000000000000000000000")
  val channelIdBelowLimit2: ByteVector32 = ByteVector32(hex"0222222220000000000000000000000000000000000000000000000000000000")
  val newChannelId1: ByteVector32 = ByteVector32(hex"0333333330000000000000000000000000000000000000000000000000000000")
  val newChannelId2: ByteVector32 = ByteVector32(hex"0444444440000000000000000000000000000000000000000000000000000000")
  val channelIdPrivate1: ByteVector32 = ByteVector32(hex"0555555550000000000000000000000000000000000000000000000000000000")
  val channelIdPrivate2: ByteVector32 = ByteVector32(hex"0666666660000000000000000000000000000000000000000000000000000000")
  val newChannelIdPrivate1: ByteVector32 = ByteVector32(hex"077777770000000000000000000000000000000000000000000000000000000")
  val channelIdAtLimit1: ByteVector32 = ByteVector32(hex"0888888880000000000000000000000000000000000000000000000000000000")
  val channelIdAtLimit2: ByteVector32 = ByteVector32(hex"0999999990000000000000000000000000000000000000000000000000000000")

  // This peer is whitelisted and starts tests with pending channels at the rate-limit (which should be ignored because it is whitelisted).
  val peerOnWhitelistAtLimit: PublicKey = randomKey().publicKey
  // The following two peers start tests already at their rate-limit.
  val peerAtLimit1: PublicKey = randomKey().publicKey
  val peerAtLimit2: PublicKey = randomKey().publicKey
  val peersAtLimit: Seq[PublicKey] = Seq(peerAtLimit1, peerAtLimit2)
  // The following two peers start tests with one available slot before reaching the rate-limit.
  val peerBelowLimit1: PublicKey = randomKey().publicKey
  val peerBelowLimit2: PublicKey = randomKey().publicKey
  val peersBelowLimit: Seq[PublicKey] = Seq(peerBelowLimit1, peerBelowLimit2)
  val publicPeers: Seq[PublicKey] = Seq(peerOnWhitelistAtLimit, peerAtLimit1, peerAtLimit2, peerBelowLimit1, peerBelowLimit2)
  // This peer has one pending private channel.
  val privatePeer1: PublicKey = randomKey().publicKey
  // This peer has one private channel that isn't pending.
  val privatePeer2: PublicKey = randomKey().publicKey

  case class FixtureParam(router: TestProbe[Router.GetNode], nodeParams: NodeParams, probe: TestProbe[PendingChannelsRateLimiter.Response], allChannels: Seq[PersistentChannelData], requests: TestProbe[Int])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val router = TestProbe[Router.GetNode]()
    val probe = TestProbe[PendingChannelsRateLimiter.Response]()
    val nodeParams = TestConstants.Alice.nodeParams.copy(channelConf = TestConstants.Alice.nodeParams.channelConf.copy(maxPendingChannelsPerPeer = maxPendingChannelsPerPeer, maxTotalPendingChannelsPrivateNodes = maxTotalPendingChannelsPrivateNodes, channelOpenerWhitelist = Set(peerOnWhitelistAtLimit)))
    val tx = Transaction.read("010000000110f01d4a4228ef959681feb1465c2010d0135be88fd598135b2e09d5413bf6f1000000006a473044022074658623424cebdac8290488b76f893cfb17765b7a3805e773e6770b7b17200102202892cfa9dda662d5eac394ba36fcfd1ea6c0b8bb3230ab96220731967bbdb90101210372d437866d9e4ead3d362b01b615d24cc0d5152c740d51e3c55fb53f6d335d82ffffffff01408b0700000000001976a914678db9a7caa2aca887af1177eda6f3d0f702df0d88ac00000000")
    val closingTx = ClosingTx(InputInfo(tx.txIn.head.outPoint, TxOut(10_000 sat, Nil)), tx, None)
    val channelsOnWhitelistAtLimit: Seq[PersistentChannelData] = Seq(
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(peerOnWhitelistAtLimit, randomBytes32()), BlockHeight(0), None, Left(FundingCreated(randomBytes32(), TxId(ByteVector32.Zeroes), 3, randomBytes64()))),
      DATA_WAIT_FOR_CHANNEL_READY(commitments(peerOnWhitelistAtLimit, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
    )
    val channelsAtLimit1 = Seq(
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(peerAtLimit1, channelIdAtLimit1), BlockHeight(0), None, Left(FundingCreated(channelIdAtLimit1, TxId(ByteVector32.Zeroes), 3, randomBytes64()))),
      DATA_WAIT_FOR_CHANNEL_READY(commitments(peerAtLimit1, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
    )
    val channelsAtLimit2 = Seq(
      DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments(peerAtLimit2, channelIdAtLimit2), 0 msat, 0 msat, BlockHeight(0), BlockHeight(0), DualFundingStatus.WaitingForConfirmations, None),
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(peerAtLimit2, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
    )
    val channelsBelowLimit1 = Seq(
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(peerBelowLimit1, channelIdBelowLimit1), BlockHeight(0), None, Left(FundingCreated(channelIdBelowLimit1, TxId(ByteVector32.Zeroes), 3, randomBytes64()))),
    )
    val channelsBelowLimit2 = Seq(
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(peerBelowLimit2, channelIdBelowLimit2), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
      DATA_NORMAL(commitments(peerBelowLimit2, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None), None, null, SpliceStatus.NoSplice, None, None, None),
      DATA_SHUTDOWN(commitments(peerBelowLimit2, randomBytes32()), Shutdown(randomBytes32(), ByteVector.empty), Shutdown(randomBytes32(), ByteVector.empty), CloseStatus.Initiator(None)),
      DATA_CLOSING(commitments(peerBelowLimit2, randomBytes32()), BlockHeight(0), ByteVector.empty, List(), List(closingTx))
    )
    val privateChannels = Seq(
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(privatePeer1, channelIdPrivate1), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
      DATA_NORMAL(commitments(privatePeer2, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None), None, null, SpliceStatus.NoSplice, None, None, None),
    )
    val initiatorChannels = Seq(
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(peerBelowLimit1, randomBytes32(), isOpener = true), BlockHeight(0), None, Left(FundingCreated(channelIdAtLimit1, TxId(ByteVector32.Zeroes), 3, randomBytes64()))),
      DATA_WAIT_FOR_CHANNEL_READY(commitments(peerBelowLimit1, randomBytes32(), isOpener = true), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
      DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments(peerAtLimit1, randomBytes32(), isOpener = true), 0 msat, 0 msat, BlockHeight(0), BlockHeight(0), DualFundingStatus.WaitingForConfirmations, None),
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(peerAtLimit1, randomBytes32(), isOpener = true), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
    )
    val publicChannels = channelsOnWhitelistAtLimit ++ channelsAtLimit1 ++ channelsAtLimit2 ++ channelsBelowLimit1 ++ channelsBelowLimit2
    val allChannels = publicChannels ++ privateChannels ++ initiatorChannels
    val requests = TestProbe[Int]()
    withFixture(test.toNoArgTest(FixtureParam(router, nodeParams, probe, allChannels, requests)))
  }

  def announcement(nodeId: PublicKey): NodeAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", NodeAddress.fromParts("1.2.3.4", 42000).get :: Nil)

  def commitments(remoteNodeId: PublicKey, channelId: ByteVector32, isOpener: Boolean = false): Commitments = {
    val ann = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(42), TestConstants.Alice.nodeParams.nodeId, remoteNodeId, randomKey().publicKey, randomKey().publicKey, randomBytes64(), randomBytes64(), randomBytes64(), randomBytes64())
    CommitmentsSpec.makeCommitments(500_000 msat, 400_000 msat, TestConstants.Alice.nodeParams.nodeId, remoteNodeId, announcement_opt = Some(ann))
      .modify(_.channelParams.channelId).setTo(channelId)
      .modify(_.channelParams.localParams.isChannelOpener).setTo(isOpener)
  }

  def processRestoredChannels(f: FixtureParam, restoredChannels: Seq[PersistentChannelData]): Unit = {
    import f._
    filterPendingChannels(nodeParams, restoredChannels).foreach {
      case p if publicPeers.contains(p._1) => router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(p._1), 1, 1 sat)
      case p => router.expectMessageType[GetNode].replyTo ! UnknownNode(p._1)
    }
  }

  test("always accept requests from nodes on white list") { f =>
    import f._

    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, allChannels))
    processRestoredChannels(f, allChannels)
    router.expectNoMessage(100 millis)
    for (_ <- 0 to maxPendingChannelsPerPeer + maxTotalPendingChannelsPrivateNodes) {
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peerOnWhitelistAtLimit, randomBytes32())
      router.expectNoMessage(10 millis)
      probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
    }
  }

  test("requests from public nodes are only accepted and tracked while under per node limit") { f =>
    import f._

    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, allChannels))
    processRestoredChannels(f, allChannels)
    router.expectNoMessage(100 millis)
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
    system.eventStream ! Publish(ChannelIdAssigned(null, peerBelowLimit1, channelIdBelowLimit1, newChannelId1))
    system.eventStream ! Publish(ChannelIdAssigned(null, peerBelowLimit2, channelIdBelowLimit2, newChannelId2))

    // ignore channel id assignments for untracked channels
    (peersBelowLimit ++ peersAtLimit).foreach { peer =>
      system.eventStream ! Publish(ChannelIdAssigned(null, peer, randomBytes32(), randomBytes32()))
    }

    // ignore channel id assignments for private peers
    system.eventStream ! Publish(ChannelIdAssigned(null, randomKey().publicKey, channelIdPrivate1, newChannelIdPrivate1))

    // ignore confirm/close/abort events for channels not tracked for a public peer
    system.eventStream ! Publish(ChannelOpened(null, peerAtLimit1, newChannelId1))
    system.eventStream ! Publish(ChannelClosed(null, channelIdAtLimit1, null, commitments(peerBelowLimit1, randomBytes32())))
    system.eventStream ! Publish(ChannelAborted(null, peerBelowLimit2, randomBytes32()))

    // after channel events for untracked channels, new channel requests for public peers are still rejected
    (peersBelowLimit ++ peersAtLimit).foreach { peer =>
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // stop tracking channels that are confirmed/closed/aborted for a public peer
    limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
    val pendingChannels = requests.expectMessageType[Int]
    system.eventStream ! Publish(ChannelOpened(null, peerAtLimit1, channelIdAtLimit1))
    system.eventStream ! Publish(ChannelClosed(null, newChannelId1, null, commitments(peerBelowLimit1, newChannelId1)))
    system.eventStream ! Publish(ChannelAborted(null, peerBelowLimit2, newChannelId2))
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
      requests.expectMessage(pendingChannels - 3)
    }

    // new channel requests for peers below limit are accepted after matching confirmed/closed/aborted
    (peersBelowLimit :+ peerAtLimit1).foreach { peer =>
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

    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, allChannels))
    processRestoredChannels(f, allChannels)
    router.expectNoMessage(100 millis)
    // channels requests are accepted when below private channels limit
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, privatePeer2, channelIdPrivate2)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(privatePeer2)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // channels requests are rejected when at the private channels limit
    for (_ <- 0 until 2) {
      limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
      router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
      probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)
    }

    // when new channel ids assigned, stop tracking the old channel id and only track the new one
    system.eventStream ! Publish(ChannelIdAssigned(null, privatePeer1, channelIdPrivate1, newChannelIdPrivate1))

    // ignore channel id assignments for untracked node/channel pairs
    system.eventStream ! Publish(ChannelIdAssigned(null, randomKey().publicKey, channelIdPrivate1, randomBytes32()))
    system.eventStream ! Publish(ChannelIdAssigned(null, privatePeer1, randomBytes32(), randomBytes32()))

    // ignore channel id assignments for public peer channels
    system.eventStream ! Publish(ChannelIdAssigned(null, peerBelowLimit1, channelIdBelowLimit1, newChannelId1))
    system.eventStream ! Publish(ChannelIdAssigned(null, peerBelowLimit2, channelIdBelowLimit2, newChannelId2))

    // ignore confirm/close/abort events for node/channel pairs not tracked for a private peer
    system.eventStream ! Publish(ChannelOpened(null, privatePeer1, newChannelId1))
    system.eventStream ! Publish(ChannelClosed(null, newChannelId1, null, commitments(privatePeer2, newChannelId1)))
    system.eventStream ! Publish(ChannelAborted(null, peerBelowLimit2, newChannelIdPrivate1))

    // after channel events for untracked channels, new channel requests for private peers are still rejected
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // stop tracking channels that are confirmed/closed/aborted for a private peer
    limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
    requests.expectMessage(2)
    system.eventStream ! Publish(ChannelOpened(null, privatePeer1, newChannelIdPrivate1))
    system.eventStream ! Publish(ChannelClosed(null, channelIdPrivate2, null, commitments(privatePeer2, channelIdPrivate2)))
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
      requests.expectMessage(0)
    }

    // new channel requests for peers below limit are accepted after matching confirmed/closed/aborted
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, privatePeer1, channelIdPrivate1)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(privatePeer1)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // second request from a different node but with the same channel id
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, channelIdPrivate1)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
    requests.expectMessage(2)
    // abort the reused channel id for one private node; private channels now under the limit by one
    system.eventStream ! Publish(ChannelAborted(null, privatePeer1, channelIdPrivate1))
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
      DATA_WAIT_FOR_CHANNEL_READY(commitments(randomKey().publicKey, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(randomKey().publicKey, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None)),
      DATA_NORMAL(commitments(randomKey().publicKey, randomBytes32()), ShortIdAliases(ShortChannelId.generateLocalAlias(), None), None, null, SpliceStatus.NoSplice, None, None, None),
      DATA_SHUTDOWN(commitments(randomKey().publicKey, randomBytes32()), Shutdown(randomBytes32(), ByteVector.empty), Shutdown(randomBytes32(), ByteVector.empty), CloseStatus.Initiator(None)),
      DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(randomKey().publicKey, randomBytes32()), BlockHeight(0), None, Left(FundingCreated(randomBytes32(), TxId(ByteVector32.Zeroes), 3, randomBytes64()))),
    )
    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, channels))

    // process one restored private channel
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)

    // handle a request that comes in during the restore
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // process one restored public peer channel
    val r1 = router.expectMessageType[GetNode]
    r1.replyTo ! PublicNode(announcement(r1.nodeId), 1, 1 sat)

    // handle a request that comes in during the restore
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // process last restored public peer channel
    val r2 = router.expectMessageType[GetNode]
    r2.replyTo ! PublicNode(announcement(r2.nodeId), 1, 1 sat)

    // handle new channel requests for a private peer
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)
    router.expectNoMessage(100 millis) // the remaining channels are filtered because they aren't pending
  }

  test("track requests from peers that change from private to public") { f =>
    import f._

    // start a new/empty limiter actor
    val limiter = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, Nil))
    processRestoredChannels(f, Nil)
    router.expectNoMessage(100 millis)
    val peer = randomKey().publicKey
    val (channelId1, channelId2, channelId3) = (randomBytes32(), randomBytes32(), randomBytes32())

    // peer makes first pending channel open requests
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, channelId1)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(peer)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // peer makes second pending channel open requests
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, channelId2)
    router.expectMessageType[GetNode].replyTo ! UnknownNode(peer)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // private channels are now at the limit
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, randomKey().publicKey, randomBytes32())
    router.expectMessageType[GetNode].replyTo ! UnknownNode(randomKey().publicKey)
    probe.expectMessage(PendingChannelsRateLimiter.ChannelRateLimited)

    // the peer becomes public, and can now have public pending channels
    limiter ! PendingChannelsRateLimiter.AddOrRejectChannel(probe.ref, peer, channelId3)
    router.expectMessageType[GetNode].replyTo ! PublicNode(announcement(peer), 1, 1 sat)
    probe.expectMessage(PendingChannelsRateLimiter.AcceptOpenChannel)

    // when the first pending channel request is confirmed, the first tracked private channel is removed
    // AND the peer becomes public, but still has a tracked channel request as a private node
    system.eventStream ! Publish(ChannelOpened(null, peer, channelId1))
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
      requests.expectMessage(1)
    }

    // the private pending channel request from peer receives a new channel id
    val finalChannelId2 = randomBytes32()
    system.eventStream ! Publish(ChannelIdAssigned(null, peer, channelId2, finalChannelId2))

    // the private channel request from peer is aborted and removed from the tracker
    system.eventStream ! Publish(ChannelAborted(null, peer, finalChannelId2))

    // the first two private pending channel open requests are now removed from the tracker
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
      requests.expectMessage(0)
    }

    // the third pending channel request from peer is still tracked as a public peer
    eventually {
      limiter ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
      requests.expectMessage(1)
    }
  }

  test("receive events during the restore") { f =>
    import f._

    // We first simulate a normal restore.
    val limiter1 = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, allChannels))
    processRestoredChannels(f, allChannels)
    limiter1 ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
    val publicChannelsCount = requests.expectMessageType[Int]
    limiter1 ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
    val privateChannelsCount = requests.expectMessageType[Int]

    // If we receive events while we're restoring, it affects the final number of pending channels.
    val limiter2 = testKit.spawn(PendingChannelsRateLimiter(nodeParams, router.ref, allChannels))
    // A first public channel is closed after a channel_id change.
    limiter2 ! PendingChannelsRateLimiter.ReplaceChannelId(peerAtLimit1, channelIdAtLimit1, newChannelId1)
    limiter2 ! PendingChannelsRateLimiter.RemoveChannelId(peerAtLimit1, newChannelId1)
    // Another public channel is directly closed.
    limiter2 ! PendingChannelsRateLimiter.RemoveChannelId(peerBelowLimit2, channelIdBelowLimit2)
    // A private channel is closed.
    limiter2 ! PendingChannelsRateLimiter.RemoveChannelId(privatePeer1, channelIdPrivate1)
    processRestoredChannels(f, allChannels)

    eventually {
      limiter2 ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = true)
      requests.expectMessage(publicChannelsCount - 2)
    }
    eventually {
      limiter2 ! PendingChannelsRateLimiter.CountOpenChannelRequests(requests.ref, publicPeers = false)
      requests.expectMessage(privateChannelsCount - 1)
    }
  }
}
