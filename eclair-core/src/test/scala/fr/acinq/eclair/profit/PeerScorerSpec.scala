package fr.acinq.eclair.profit

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, BtcAmount, BtcDouble, ByteVector32, ByteVector64, SatoshiLong}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.profit.PeerScorer._
import fr.acinq.eclair.profit.PeerStatsTracker._
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.wire.protocol.ChannelUpdate.{ChannelFlags, MessageFlags}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, RealShortChannelId, TestConstants, TimestampSecond, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.Inside.inside
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class PeerScorerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  private val remoteNodeId1 = PublicKey(hex"024c9c77624899672c78d84b551ef1187cbb17618b2d96ef189d0ea36f307be76e")
  private val remoteNodeId2 = PublicKey(hex"02271ffb6969f6dc4769438637d7d24dc3358098cdef7a772f9ccfd31251470e28")
  private val remoteNodeId3 = PublicKey(hex"028f5be42aa013f9fd2e5a28a152563ac21acc095ef65cab2e835a789d2a4add96")

  private def channelUpdate(capacity: BtcAmount, fees: RelayFees = RelayFees(250 msat, 1000), timestamp: TimestampSecond = TimestampSecond.now(), announceChannel: Boolean = true): ChannelUpdate = {
    val messageFlags = MessageFlags(dontForward = !announceChannel)
    val channelFlags = ChannelFlags(isEnabled = true, isNode1 = true)
    ChannelUpdate(ByteVector64.Zeroes, Block.RegtestGenesisBlock.hash, RealShortChannelId(42), timestamp, messageFlags, channelFlags, CltvExpiryDelta(36), 1 msat, fees.feeBase, fees.feeProportionalMillionths, capacity.toMilliSatoshi)
  }

  private def peerStats(totalAmountIn: BtcAmount = 0 sat,
                        totalAmountOut: BtcAmount = 0 sat,
                        relayFeeEarned: BtcAmount = 0 sat,
                        onChainFeePaid: BtcAmount = 0 sat,
                        liquidityFeeEarned: BtcAmount = 0 sat,
                        liquidityFeePaid: BtcAmount = 0 sat): PeerStats = PeerStats(
    totalAmountIn = totalAmountIn.toMilliSatoshi,
    totalAmountOut = totalAmountOut.toMilliSatoshi,
    relayFeeEarned = relayFeeEarned.toMilliSatoshi,
    onChainFeePaid = onChainFeePaid.toMilliSatoshi.truncateToSatoshi,
    liquidityFeeEarned = liquidityFeeEarned.toMilliSatoshi.truncateToSatoshi,
    liquidityFeePaid = liquidityFeePaid.toMilliSatoshi.truncateToSatoshi
  )

  private def channelInfo(canSend: BtcAmount, canReceive: BtcAmount, channelId: ByteVector32 = randomBytes32(), isPublic: Boolean = true): ChannelInfo = ChannelInfo(
    channelId = channelId,
    capacity = canSend.toMilliSatoshi.truncateToSatoshi + canReceive.toMilliSatoshi.truncateToSatoshi,
    canSend = canSend.toMilliSatoshi,
    canReceive = canReceive.toMilliSatoshi,
    isPublic = isPublic
  )

  test("don't allocate liquidity if channel is pending") {
    val tracker = TestProbe[GetLatestStats]()
    val probe = TestProbe[ScoreBoard]()
    val scorer = testKit.spawn(PeerScorer(TestConstants.Alice.nodeParams, tracker.ref))

    // We have a stable, large outgoing flow with our peer and not much liquidity left: we should add liquidity.
    val stats = Seq.fill(10)(peerStats(totalAmountOut = 1 btc, relayFeeEarned = 0.01 btc))
    val channel = channelInfo(canSend = 0.1 btc, canReceive = 4.9 btc)

    // If there are no pending channel, we suggest adding liquidity.
    scorer.ref ! ScorePeers(Some(probe.ref))
    inside(tracker.expectMessageType[GetLatestStats]) { msg =>
      msg.replyTo ! LatestStats(Seq(PeerInfo(remoteNodeId1, stats, Seq(channel), None, hasPendingChannel = false)))
    }
    inside(probe.expectMessageType[ScoreBoard]) { s =>
      assert(s.liquiditySuggestions.exists(_.remoteNodeId == remoteNodeId1))
    }

    // If there is already a pending channel, we don't suggest adding liquidity.
    scorer.ref ! ScorePeers(Some(probe.ref))
    inside(tracker.expectMessageType[GetLatestStats]) { msg =>
      msg.replyTo ! LatestStats(Seq(PeerInfo(remoteNodeId1, stats, Seq(channel), None, hasPendingChannel = true)))
    }
    inside(probe.expectMessageType[ScoreBoard]) { s =>
      assert(s.liquiditySuggestions.isEmpty)
    }
  }

  test("simulate large outgoing flows") {
    val tracker = TestProbe[GetLatestStats]()
    val probe = TestProbe[ScoreBoard]()
    val nodeParams = {
      val alice = TestConstants.Alice.nodeParams
      alice.copy(
        peerScoringConfig = alice.peerScoringConfig.copy(
          liquidity = alice.peerScoringConfig.liquidity.copy(
            dailyFlowMultiplier = 5,
            minFundingAmount = 0.1.btc.toSatoshi,
            maxFundingAmount = 1.btc.toSatoshi,
          ),
          relayFees = alice.peerScoringConfig.relayFees.copy(relayFeeUpdateMinVolume = 0.01.btc.toSatoshi)
        )
      )
    }
    val scorer = testKit.spawn(PeerScorer(nodeParams, tracker.ref))

    // We have channels with 3 peers:
    //  - we have a very large capacity with our first peer
    //  - we have a smaller capacity with our second peer
    //  - we have medium capacity with our third peer
    val c1a = channelInfo(canSend = 0.5 btc, canReceive = 2.7 btc)
    val c1b = channelInfo(canSend = 0.3 btc, canReceive = 3.5 btc, isPublic = false)
    val c2a = channelInfo(canSend = 0.03 btc, canReceive = 0.25 btc)
    val c2b = channelInfo(canSend = 0.04 btc, canReceive = 0.17 btc)
    val c3 = channelInfo(canSend = 0.3 btc, canReceive = 0.7 btc)

    // We have a stable, large outgoing flow with our first peer: we should add liquidity.
    val peerInfo1 = PeerInfo(
      remoteNodeId = remoteNodeId1,
      stats = Seq(
        peerStats(totalAmountOut = 0.51 btc, relayFeeEarned = 0.01 btc),
        peerStats(totalAmountOut = 0.52 btc, relayFeeEarned = 0.01 btc),
        peerStats(totalAmountOut = 0.48 btc, relayFeeEarned = 0.01 btc),
        peerStats(totalAmountOut = 0.47 btc, relayFeeEarned = 0.01 btc),
        peerStats(totalAmountOut = 0.51 btc, relayFeeEarned = 0.01 btc),
      ),
      channels = Seq(c1a, c1b),
      latestUpdate_opt = Some(channelUpdate(c1a.capacity, timestamp = TimestampSecond.now() - Bucket.duration - 10.seconds)),
      hasPendingChannel = false,
    )

    // We have an increasing outgoing flow with our second peer: we should add liquidity and increase our routing fees.
    val peerInfo2 = PeerInfo(
      remoteNodeId = remoteNodeId2,
      stats = Seq(
        peerStats(totalAmountOut = 0.02 btc, relayFeeEarned = 0.0001 btc),
        peerStats(totalAmountOut = 0.015 btc, relayFeeEarned = 0.0001 btc),
        peerStats(totalAmountOut = 0.012 btc, relayFeeEarned = 0.0001 btc),
        peerStats(totalAmountOut = 0.011 btc, relayFeeEarned = 0.0001 btc),
        peerStats(totalAmountOut = 0.012 btc, relayFeeEarned = 0.0001 btc),
      ),
      channels = Seq(c2a, c2b),
      latestUpdate_opt = Some(channelUpdate(c2a.capacity, timestamp = TimestampSecond.now() - Bucket.duration - 15.seconds)),
      hasPendingChannel = false
    )

    // We have a decreasing outgoing flow with our third peer: we should decrease our routing fees and may add liquidity.
    val peerInfo3 = PeerInfo(
      remoteNodeId = remoteNodeId3,
      stats = Seq(
        peerStats(totalAmountOut = 0.02 btc, relayFeeEarned = 0.00007 btc),
        peerStats(totalAmountOut = 0.03 btc, relayFeeEarned = 0.00009 btc),
        peerStats(totalAmountOut = 0.07 btc, relayFeeEarned = 0.00012 btc),
      ),
      channels = Seq(c3),
      latestUpdate_opt = Some(channelUpdate(c3.capacity, timestamp = TimestampSecond.now() - Bucket.duration - 15.seconds)),
      hasPendingChannel = false
    )

    scorer.ref ! ScorePeers(Some(probe.ref))
    inside(tracker.expectMessageType[GetLatestStats]) { msg =>
      msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
    }
    inside(probe.expectMessageType[ScoreBoard]) { s =>
      assert(s.bestPeers.map(_.remoteNodeId) == Seq(remoteNodeId1, remoteNodeId2, remoteNodeId3))
      assert(s.liquiditySuggestions.map(_.remoteNodeId).toSet == Set(remoteNodeId1, remoteNodeId2, remoteNodeId3))
      assert(s.liquiditySuggestions.find(_.remoteNodeId == remoteNodeId1).exists(_.fundingAmount == nodeParams.peerScoringConfig.liquidity.maxFundingAmount))
      assert(s.liquiditySuggestions.filter(_.remoteNodeId != remoteNodeId1).forall(_.fundingAmount < nodeParams.peerScoringConfig.liquidity.maxFundingAmount))
      assert(s.routingFeeSuggestions.map(_.remoteNodeId).toSet == Set(remoteNodeId2, remoteNodeId3))
      assert(s.routingFeeSuggestions.find(_.remoteNodeId == remoteNodeId2).exists(_.increase))
      assert(s.routingFeeSuggestions.find(_.remoteNodeId == remoteNodeId3).exists(_.decrease))
    }
  }

  test("simulate exhausted large outgoing flow") {
    val tracker = TestProbe[GetLatestStats]()
    val probe = TestProbe[ScoreBoard]()
    val scorer = testKit.spawn(PeerScorer(TestConstants.Alice.nodeParams, tracker.ref))

    // Our first peer was very profitable, but it has exhausted its liquidity more than a week ago and doesn't have recent routing activity.
    val peerInfo1 = PeerInfo(
      remoteNodeId = remoteNodeId1,
      stats = Seq.fill(Bucket.bucketsPerDay * 7)(PeerStats.empty),
      channels = Seq(channelInfo(canSend = 10_000 sat, canReceive = 3 btc)),
      latestUpdate_opt = None,
      hasPendingChannel = false,
    )

    // Our second peer was very profitable as well, but has exhausted its liquidity more recently.
    val peerInfo2 = PeerInfo(
      remoteNodeId = remoteNodeId2,
      stats = Seq.fill(Bucket.bucketsPerDay * 3)(PeerStats.empty) ++ Seq.fill(Bucket.bucketsPerDay * 4)(peerStats(totalAmountOut = 0.3 btc, relayFeeEarned = 0.0005 btc)),
      channels = Seq(channelInfo(canSend = 10_000 sat, canReceive = 3 btc)),
      latestUpdate_opt = None,
      hasPendingChannel = false,
    )

    // Our third peer has balanced flows.
    val peerInfo3 = PeerInfo(
      remoteNodeId = remoteNodeId3,
      stats = Seq.fill(Bucket.bucketsPerDay * 7)(peerStats(totalAmountOut = 0.2 btc, totalAmountIn = 0.2 btc, relayFeeEarned = 0.001 btc)),
      channels = Seq(channelInfo(canSend = 100_000 sat, canReceive = 3 btc)),
      latestUpdate_opt = None,
      hasPendingChannel = false,
    )

    scorer.ref ! ScorePeers(Some(probe.ref))
    inside(tracker.expectMessageType[GetLatestStats]) { msg =>
      msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
    }
    inside(probe.expectMessageType[ScoreBoard]) { s =>
      assert(s.bestPeers.nonEmpty)
      // TODO: this is an issue: we should identify profitable peers that ran out of liquidity.
      assert(s.liquiditySuggestions.isEmpty)
    }
  }

  test("suggest closing idle channels with liquidity") {
    val tracker = TestProbe[GetLatestStats]()
    val probe = TestProbe[ScoreBoard]()
    val scorer = testKit.spawn(PeerScorer(TestConstants.Alice.nodeParams, tracker.ref))

    // We have several old channels where liquidity is on our side.
    val c1a = channelInfo(canSend = 0.5 btc, canReceive = 0.01 btc)
    val c1b = channelInfo(canSend = 0.8 btc, canReceive = 0.1 btc, isPublic = false)
    val c1c = channelInfo(canSend = 0.4 btc, canReceive = 0.05 btc)
    val c2a = channelInfo(canSend = 0.3 btc, canReceive = 0.1 btc)
    val c2b = channelInfo(canSend = 0.2 btc, canReceive = 0.01 btc)
    val c3 = channelInfo(canSend = 0.8 btc, canReceive = 0.05 btc)

    // The outgoing volume of the corresponding peers is negligible.
    val peerInfo1 = PeerInfo(
      remoteNodeId = remoteNodeId1,
      stats = Seq.fill(14)(peerStats(totalAmountOut = 10_000 sat, totalAmountIn = 5_000 sat, relayFeeEarned = 100 sat)),
      channels = Seq(c1a, c1b, c1c),
      latestUpdate_opt = None,
      hasPendingChannel = false
    )
    val peerInfo2 = PeerInfo(
      remoteNodeId = remoteNodeId2,
      stats = Seq.fill(14)(peerStats(totalAmountOut = 5_000 sat, totalAmountIn = 8_000 sat, relayFeeEarned = 80 sat)),
      channels = Seq(c2a, c2b),
      latestUpdate_opt = None,
      hasPendingChannel = false
    )
    val peerInfo3 = PeerInfo(
      remoteNodeId = remoteNodeId3,
      stats = Seq.fill(14)(peerStats(totalAmountOut = 5_000 sat, totalAmountIn = 5_000 sat, relayFeeEarned = 50 sat)),
      channels = Seq(c3),
      latestUpdate_opt = None,
      hasPendingChannel = false
    )

    // We want to keep one channel with each peer, and select the one that likely has the best score for path-finding
    // algorithms.
    scorer.ref ! ScorePeers(Some(probe.ref))
    inside(tracker.expectMessageType[GetLatestStats]) { msg =>
      msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
    }
    inside(probe.expectMessageType[ScoreBoard]) { s =>
      assert(s.channelsToClose.map(_.channelId).toSet == Set(c1b.channelId, c1c.channelId, c2b.channelId))
    }
  }

}
