package fr.acinq.eclair.profit

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, BtcAmount, BtcDouble, ByteVector32, ByteVector64, SatoshiLong}
import fr.acinq.eclair.blockchain.DummyBalanceChecker
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import fr.acinq.eclair.channel.{CMD_CLOSE, CMD_UPDATE_RELAY_FEE, Register}
import fr.acinq.eclair.io.Peer.OpenChannel
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.profit.PeerScorer._
import fr.acinq.eclair.profit.PeerStatsTracker._
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.wire.protocol.ChannelUpdate.{ChannelFlags, MessageFlags}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, RealShortChannelId, TestConstants, TimestampMilli, TimestampSecond, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.Inside.inside
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt
import scala.util.Random

class PeerScorerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  private val remoteNodeId1 = PublicKey(hex"024c9c77624899672c78d84b551ef1187cbb17618b2d96ef189d0ea36f307be76e")
  private val remoteNodeId2 = PublicKey(hex"02271ffb6969f6dc4769438637d7d24dc3358098cdef7a772f9ccfd31251470e28")
  private val remoteNodeId3 = PublicKey(hex"028f5be42aa013f9fd2e5a28a152563ac21acc095ef65cab2e835a789d2a4add96")

  private val weeklyBuckets = Bucket.bucketsPerDay * 7
  private val defaultConfig = Config(
    enabled = true,
    // We set this to 1 day to more easily match daily rate-limits.
    scoringFrequency = 1 day,
    topPeersCount = 10,
    topPeersWhitelist = Set.empty,
    liquidity = PeerScorer.LiquidityConfig(
      autoFund = true,
      autoClose = true,
      minFundingAmount = 1_000_000 sat, // 0.01 BTC
      maxFundingAmount = 100_000_000 sat, // 1 BTC
      minOnChainBalance = 5_000_000 sat, // 0.05 BTC
      maxFeerate = FeeratePerByte(100 sat).perKw,
      maxFundingTxPerDay = 100,
    ),
    relayFees = PeerScorer.RelayFeesConfig(
      autoUpdate = true,
      minRelayFees = RelayFees(1 msat, 500),
      maxRelayFees = RelayFees(10_000 msat, 5000),
      dailyPaymentVolumeThreshold = 1_000_000 sat, // 0.01 BTC
      dailyPaymentVolumeThresholdPercent = 0.1,
    )
  )

  private case class Fixture(tracker: TestProbe[GetLatestStats], register: TestProbe[Any], wallet: DummyBalanceChecker, scorer: ActorRef[PeerScorer.Command])

  private def withFixture(config: Config = defaultConfig, onChainBalance: BtcAmount = 0 sat)(testFun: Fixture => Any): Unit = {
    val tracker = TestProbe[GetLatestStats]()
    val register = TestProbe[Any]()
    val wallet = new DummyBalanceChecker(confirmedBalance = onChainBalance.toMilliSatoshi.truncateToSatoshi)
    val nodeParams = TestConstants.Alice.nodeParams.copy(peerScoringConfig = config)
    val scorer = testKit.spawn(PeerScorer(nodeParams, wallet, tracker.ref, register.ref.toClassic))
    testFun(Fixture(tracker, register, wallet, scorer))
  }

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

  test("simulate large outgoing flows") {
    withFixture(onChainBalance = 10 btc) { f =>
      import f._

      // We have channels with 3 peers:
      //  - we have a very large capacity with our first peer
      //  - we have a smaller capacity with our second peer
      //  - we have medium capacity with our third peer
      val c1a = channelInfo(canSend = 0.5 btc, canReceive = 2.7 btc)
      val c1b = channelInfo(canSend = 0.3 btc, canReceive = 3.5 btc, isPublic = false)
      val c2a = channelInfo(canSend = 0.03 btc, canReceive = 0.25 btc)
      val c2b = channelInfo(canSend = 0.04 btc, canReceive = 0.17 btc)
      val c3 = channelInfo(canSend = 0.3 btc, canReceive = 0.7 btc)

      // We need to scale the last bucket of test data based on the current timestamp, otherwise we will overestimate
      // the outgoing payment volume of the current bucket and thus always increase relay fees.
      val bucketRatio = Bucket.consumed(TimestampMilli.now())

      // We have a stable, large outgoing flow with our first peer: we should add liquidity and keep our relay fees unchanged.
      val peerInfo1 = PeerInfo(
        remoteNodeId = remoteNodeId1,
        stats = Seq(
          peerStats(totalAmountOut = 0.51.btc * bucketRatio, relayFeeEarned = 0.01.btc * bucketRatio),
          peerStats(totalAmountOut = 0.52 btc, relayFeeEarned = 0.01 btc),
          peerStats(totalAmountOut = 0.48 btc, relayFeeEarned = 0.01 btc),
          peerStats(totalAmountOut = 0.47 btc, relayFeeEarned = 0.01 btc),
          peerStats(totalAmountOut = 0.51 btc, relayFeeEarned = 0.01 btc),
        ) ++ Seq.fill(weeklyBuckets - 5)(peerStats(totalAmountOut = 0.5.btc + Random.nextInt(500_000).sat, relayFeeEarned = 0.01 btc)),
        channels = Seq(c1a, c1b),
        latestUpdate_opt = Some(channelUpdate(c1a.capacity, timestamp = TimestampSecond.now() - Bucket.duration * 2)),
        hasPendingChannel = false,
      )

      // We have an increasing outgoing flow with our second peer: we should add liquidity and increase our routing fees.
      val peerInfo2 = PeerInfo(
        remoteNodeId = remoteNodeId2,
        stats = Seq(
          peerStats(totalAmountOut = 0.02.btc * bucketRatio, relayFeeEarned = 0.0001.btc * bucketRatio),
          peerStats(totalAmountOut = 0.015 btc, relayFeeEarned = 0.0001 btc),
          peerStats(totalAmountOut = 0.012 btc, relayFeeEarned = 0.0001 btc),
          peerStats(totalAmountOut = 0.011 btc, relayFeeEarned = 0.0001 btc),
          peerStats(totalAmountOut = 0.012 btc, relayFeeEarned = 0.0001 btc),
        ) ++ Seq.fill(weeklyBuckets - 5)(peerStats(totalAmountOut = 0.0010 btc, relayFeeEarned = 0.0001 btc)),
        channels = Seq(c2a, c2b),
        latestUpdate_opt = Some(channelUpdate(c2a.capacity, timestamp = TimestampSecond.now() - Bucket.duration * 2)),
        hasPendingChannel = false
      )

      // We have a decreasing outgoing flow with our third peer: we should decrease our routing fees and may add liquidity.
      val peerInfo3 = PeerInfo(
        remoteNodeId = remoteNodeId3,
        stats = Seq(
          peerStats(totalAmountOut = 0.02.btc * bucketRatio, relayFeeEarned = 0.00007.btc * bucketRatio),
          peerStats(totalAmountOut = 0.03 btc, relayFeeEarned = 0.00009 btc),
          peerStats(totalAmountOut = 0.07 btc, relayFeeEarned = 0.00012 btc),
          peerStats(totalAmountOut = 0.07 btc, relayFeeEarned = 0.00011 btc),
        ) ++ Seq.fill(weeklyBuckets - 4)(peerStats(totalAmountOut = 0.005 btc, relayFeeEarned = 0.00001 btc)),
        channels = Seq(c3),
        latestUpdate_opt = Some(channelUpdate(c3.capacity, timestamp = TimestampSecond.now() - Bucket.duration * 2)),
        hasPendingChannel = false
      )

      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
      }

      // We increase our relay fees with our second peer, on all channels.
      val feeIncreases = Seq(
        register.expectMessageType[Register.Forward[CMD_UPDATE_RELAY_FEE]],
        register.expectMessageType[Register.Forward[CMD_UPDATE_RELAY_FEE]],
      )
      assert(feeIncreases.map(_.channelId).toSet == Set(c2a.channelId, c2b.channelId))
      assert(feeIncreases.map(_.message.feeProportionalMillionths).toSet == Set(1500))
      // We decrease our relay fees with our third peer.
      inside(register.expectMessageType[Register.Forward[CMD_UPDATE_RELAY_FEE]]) { cmd =>
        assert(cmd.channelId == c3.channelId)
        assert(cmd.message.feeProportionalMillionths == 500)
      }
      // We fund channels with all of our peers.
      val funding = Seq(
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
      )
      assert(funding.map(_.nodeId).toSet == Set(remoteNodeId1, remoteNodeId2, remoteNodeId3))
      funding.filter(_.nodeId != remoteNodeId1).foreach(f => assert(f.message.fundingAmount < defaultConfig.liquidity.maxFundingAmount))
      funding.filter(_.nodeId == remoteNodeId1).foreach(f => assert(f.message.fundingAmount == defaultConfig.liquidity.maxFundingAmount))
      register.expectNoMessage(100 millis)
    }
  }

  test("simulate exhausted large outgoing flow") {
    withFixture(onChainBalance = 10 btc) { f =>
      import f._

      // Our first peer was very profitable, but it has exhausted its liquidity more than a week ago and doesn't have recent routing activity.
      // But since it has a large capacity, we still fund a channel to try to revive it.
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
        channels = Seq(channelInfo(canSend = 0.3 btc, canReceive = 2.5 btc)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
      }
      val funding = Seq(
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
      )
      assert(funding.map(_.nodeId).toSet == Set(remoteNodeId1, remoteNodeId2))
      // We fund a smaller channel with the first node, because we don't know how it will perform.
      funding.filter(_.nodeId == remoteNodeId1).foreach(f => assert(f.message.fundingAmount < defaultConfig.liquidity.maxFundingAmount))
      funding.filter(_.nodeId == remoteNodeId2).foreach(f => assert(f.message.fundingAmount == defaultConfig.liquidity.maxFundingAmount))
      register.expectNoMessage(100 millis)
    }
  }

  test("simulate good small peers that need more capacity") {
    // We use a configuration that selects only our best peer: we should still select a smaller peer that performs well.
    // We set a large scoring frequency to remove the random rate-limit (of 1 small peer funding every 3 days).
    val config = defaultConfig.copy(topPeersCount = 1, scoringFrequency = 7 days)
    withFixture(onChainBalance = 10 btc, config = config) { f =>
      import f._

      // Our first peer has the biggest outgoing payment volume and capacity and needs more liquidity.
      val peerInfo1 = PeerInfo(
        remoteNodeId = remoteNodeId1,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountIn = 0.001 btc, totalAmountOut = 0.002 btc, relayFeeEarned = 10_000 sat)),
        channels = Seq(channelInfo(canSend = 0.3 btc, canReceive = 3.5 btc)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      // Our second peer, which has a smaller capacity, has a better per-capacity profit ratio.
      val peerInfo2 = PeerInfo(
        remoteNodeId = remoteNodeId2,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountIn = 0.0005 btc, totalAmountOut = 0.001 btc, relayFeeEarned = 5_000 sat)),
        channels = Seq(channelInfo(canSend = 0.01 btc, canReceive = 0.07 btc)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      // Our third peer also has a good per-capacity profit ratio, but doesn't need any liquidity.
      val peerInfo3 = PeerInfo(
        remoteNodeId = remoteNodeId3,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountIn = 0.0005 btc, totalAmountOut = 0.001 btc, relayFeeEarned = 5_000 sat)),
        channels = Seq(channelInfo(canSend = 0.04 btc, canReceive = 0.04 btc)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
      }
      val funding = Seq(
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
        register.expectMessageType[Register.ForwardNodeId[OpenChannel]],
      )
      assert(funding.map(_.nodeId).toSet == Set(remoteNodeId1, remoteNodeId2))
      // We increase small peers' capacity by 50%.
      funding.find(_.nodeId == remoteNodeId2).foreach(cmd => assert(cmd.message.fundingAmount == 0.04.btc.toSatoshi))
      register.expectNoMessage(100 millis)
    }
  }

  test("fund channels with whitelisted peers") {
    val config = defaultConfig.copy(topPeersWhitelist = Set(remoteNodeId3))
    withFixture(onChainBalance = 10 btc, config = config) { f =>
      import f._

      // Our first peer isn't very profitable and doesn't need liquidity (small outgoing flow).
      val peerInfo1 = PeerInfo(
        remoteNodeId = remoteNodeId1,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountIn = 250_000 sat, totalAmountOut = 300_000 sat, relayFeeEarned = 20_000 sat)),
        channels = Seq(channelInfo(canSend = 0.4 btc, canReceive = 0.5 btc)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      // Our second peer isn't very profitable either and doesn't need liquidity (small incoming flow).
      val peerInfo2 = PeerInfo(
        remoteNodeId = remoteNodeId2,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountIn = 300_000 sat, totalAmountOut = 280_000 sat, relayFeeEarned = 15_000 sat)),
        channels = Seq(channelInfo(canSend = 0.3 btc, canReceive = 0.2 btc)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      // Our third peer has exhausted its liquidity a while ago and lost most of its capacity: but it is whitelisted,
      // so we should fund a new channel with them.
      val peerInfo3 = PeerInfo(
        remoteNodeId = remoteNodeId3,
        stats = Seq.fill(weeklyBuckets)(PeerStats.empty),
        channels = Seq(channelInfo(canSend = 150_000 sat, canReceive = 1_400_000 sat)),
        latestUpdate_opt = None,
        hasPendingChannel = false,
      )

      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
      }
      inside(register.expectMessageType[Register.ForwardNodeId[OpenChannel]]) { cmd =>
        assert(cmd.nodeId == remoteNodeId3)
        assert(cmd.message.fundingAmount == defaultConfig.liquidity.minFundingAmount)
      }
      register.expectNoMessage(100 millis)
    }
  }

  test("don't fund channels if channel is already being created") {
    withFixture(onChainBalance = 5 btc) { f =>
      import f._

      // We have a stable, large outgoing flow with a single peer and not much liquidity left: we should add liquidity.
      val stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 1 btc, relayFeeEarned = 0.01 btc))
      val channel = channelInfo(canSend = 0.1 btc, canReceive = 4.9 btc)

      // If there are no pending channel, we suggest adding liquidity.
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(PeerInfo(remoteNodeId1, stats, Seq(channel), None, hasPendingChannel = false)))
      }
      inside(register.expectMessageType[Register.ForwardNodeId[OpenChannel]]) { cmd =>
        assert(cmd.nodeId == remoteNodeId1)
        assert(cmd.message.fundingAmount > 0.01.btc)
      }

      // If there is already a pending channel, we don't suggest adding liquidity.
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(PeerInfo(remoteNodeId1, stats, Seq(channel), None, hasPendingChannel = true)))
      }
      register.expectNoMessage(100 millis)
    }
  }

  test("don't fund channels if feerate is too high") {
    val config = defaultConfig.copy(liquidity = defaultConfig.liquidity.copy(maxFeerate = FeeratePerByte(1 sat).perKw))
    withFixture(onChainBalance = 5 btc, config = config) { f =>
      import f._

      // We have a stable, large outgoing flow with a single peer and not much liquidity left: we should add liquidity.
      val stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 1 btc, relayFeeEarned = 0.01 btc))
      val channel = channelInfo(canSend = 0.1 btc, canReceive = 4.9 btc)

      // But the feerate is too high compared to our configured threshold, so we don't do anything.
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(PeerInfo(remoteNodeId1, stats, Seq(channel), None, hasPendingChannel = false)))
      }
      register.expectNoMessage(100 millis)
    }
  }

  test("don't fund channels if on-chain balance is too low") {
    val config = defaultConfig.copy(liquidity = defaultConfig.liquidity.copy(minOnChainBalance = 1.5 btc))
    withFixture(onChainBalance = 1.5 btc, config = config) { f =>
      import f._

      // We have a stable, large outgoing flow with a single peer and not much liquidity left: we should add liquidity.
      val stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 1 btc, relayFeeEarned = 0.01 btc))
      val channel = channelInfo(canSend = 0.1 btc, canReceive = 4.9 btc)

      // But our balance is too low compared to our configured threshold, so we don't do anything.
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(PeerInfo(remoteNodeId1, stats, Seq(channel), None, hasPendingChannel = false)))
      }
      register.expectNoMessage(100 millis)
    }
  }

  test("don't update relay fees too frequently") {
    withFixture(onChainBalance = 0 btc) { f =>
      import f._

      val channel = channelInfo(canSend = 0.3 btc, canReceive = 0.5 btc)
      val latestUpdate = channelUpdate(channel.capacity, timestamp = TimestampSecond.now() - Bucket.duration * 3)

      // We have an increasing outgoing flow with our peer: we should increase our routing fees.
      val peerInfo = PeerInfo(
        remoteNodeId = remoteNodeId1,
        stats = Seq(
          peerStats(totalAmountOut = 0.02.btc, relayFeeEarned = 0.00015.btc),
          peerStats(totalAmountOut = 0.012 btc, relayFeeEarned = 0.0001 btc),
          peerStats(totalAmountOut = 0.005 btc, relayFeeEarned = 0.00007 btc),
          peerStats(totalAmountOut = 0.003 btc, relayFeeEarned = 0.00005 btc),
          peerStats(totalAmountOut = 0.001 btc, relayFeeEarned = 0.00001 btc),
        ) ++ Seq.fill(weeklyBuckets - 5)(peerStats(totalAmountOut = 0.001 btc, relayFeeEarned = 0.00001 btc)),
        channels = Seq(channel),
        latestUpdate_opt = Some(latestUpdate),
        hasPendingChannel = false
      )

      // We increase our routing fees.
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo))
      }
      inside(register.expectMessageType[Register.Forward[CMD_UPDATE_RELAY_FEE]]) { cmd =>
        assert(cmd.channelId == channel.channelId)
        assert(cmd.message.feeProportionalMillionths > latestUpdate.feeProportionalMillionths)
      }
      register.expectNoMessage(100 millis)

      // However, if our latest update was done recently, we don't update our routing fees.
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo.copy(latestUpdate_opt = Some(latestUpdate.copy(timestamp = TimestampSecond.now() - Bucket.duration)))))
      }
      register.expectNoMessage(100 millis)
    }
  }

  test("close idle channels with liquidity") {
    withFixture(onChainBalance = 0 btc) { f =>
      import f._

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
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 10_000 sat, totalAmountIn = 5_000 sat, relayFeeEarned = 100 sat)),
        channels = Seq(c1a, c1b, c1c),
        latestUpdate_opt = None,
        hasPendingChannel = false
      )
      val peerInfo2 = PeerInfo(
        remoteNodeId = remoteNodeId2,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 5_000 sat, totalAmountIn = 8_000 sat, relayFeeEarned = 80 sat)),
        channels = Seq(c2a, c2b),
        latestUpdate_opt = None,
        hasPendingChannel = false
      )
      val peerInfo3 = PeerInfo(
        remoteNodeId = remoteNodeId3,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 5_000 sat, totalAmountIn = 5_000 sat, relayFeeEarned = 50 sat)),
        channels = Seq(c3),
        latestUpdate_opt = None,
        hasPendingChannel = false
      )

      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo1, peerInfo2, peerInfo3))
      }
      // We want to keep one channel with each peer, and select the one that likely has the best score for path-finding algorithms.
      assert(Seq(
        register.expectMessageType[Register.Forward[CMD_CLOSE]],
        register.expectMessageType[Register.Forward[CMD_CLOSE]],
        register.expectMessageType[Register.Forward[CMD_CLOSE]]
      ).map(_.channelId).toSet == Set(c1b.channelId, c1c.channelId, c2b.channelId))
      register.expectNoMessage(100 millis)
    }
  }

  test("don't close idle channels if feerate is too high") {
    val config = defaultConfig.copy(liquidity = defaultConfig.liquidity.copy(maxFeerate = FeeratePerByte(1 sat).perKw))
    withFixture(onChainBalance = 0 btc, config = config) { f =>
      import f._

      // We have several old channels where liquidity is on our side.
      val c1a = channelInfo(canSend = 0.5 btc, canReceive = 0.01 btc)
      val c1b = channelInfo(canSend = 0.8 btc, canReceive = 0.1 btc)
      val c1c = channelInfo(canSend = 0.4 btc, canReceive = 0.05 btc)

      // The outgoing volume of the corresponding peer is negligible.
      val peerInfo = PeerInfo(
        remoteNodeId = remoteNodeId1,
        stats = Seq.fill(weeklyBuckets)(peerStats(totalAmountOut = 10_000 sat, totalAmountIn = 5_000 sat, relayFeeEarned = 100 sat)),
        channels = Seq(c1a, c1b, c1c),
        latestUpdate_opt = None,
        hasPendingChannel = false
      )

      // But the feerate is too high compared to our configured threshold, so we don't close channels.
      assert(TestConstants.Alice.nodeParams.currentFeeratesForFundingClosing.medium > config.liquidity.maxFeerate)
      scorer ! ScorePeers(None)
      inside(tracker.expectMessageType[GetLatestStats]) { msg =>
        msg.replyTo ! LatestStats(Seq(peerInfo))
      }
      register.expectNoMessage(100 millis)
    }
  }

}
