package fr.acinq.eclair.profit

import akka.actor.ActorRef
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, BtcAmount, BtcDouble, ByteVector32, ByteVector64, OutPoint, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.PaymentEvent.{IncomingPayment, OutgoingPayment}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, PaymentReceived, PaymentSent, TrampolinePaymentRelayed}
import fr.acinq.eclair.profit.PeerStatsTracker._
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, InputInfo}
import fr.acinq.eclair.wire.protocol.LegacyChannelUpdate.{ChannelFlags, MessageFlags}
import fr.acinq.eclair.wire.protocol.{LegacyChannelAnnouncement, LegacyChannelUpdate, LiquidityAds}
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiryDelta, Features, MilliSatoshiLong, RealShortChannelId, TestDatabases, TimestampMilli, TimestampSecond, ToMilliSatoshiConversion, randomBytes32, randomKey}
import org.scalatest.Inside.inside
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.concurrent.duration.DurationInt

class PeerStatsTrackerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  private val dummyPubKey = PrivateKey(ByteVector32.One).publicKey
  private val dummyAliases = ShortIdAliases(Alias(42), None)
  private val dummyChannelAnn = LegacyChannelAnnouncement(ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, Features.empty, Block.RegtestGenesisBlock.hash, RealShortChannelId(42), dummyPubKey, dummyPubKey, dummyPubKey, dummyPubKey)

  private val localNodeId = PublicKey(hex"03bd04635f1465d9347f3d69edc51f17cdf9548847533e084cd9d153a4abb065cd")
  private val remoteNodeId1 = PublicKey(hex"024c9c77624899672c78d84b551ef1187cbb17618b2d96ef189d0ea36f307be76e")
  private val remoteNodeId2 = PublicKey(hex"02271ffb6969f6dc4769438637d7d24dc3358098cdef7a772f9ccfd31251470e28")
  private val remoteNodeId3 = PublicKey(hex"028f5be42aa013f9fd2e5a28a152563ac21acc095ef65cab2e835a789d2a4add96")

  private val config = Config(pastEventsInitDelay = 1 millis, pastEventsChunkDelay = 1 millis)

  private def commitments(remoteNodeId: PublicKey, toLocal: BtcAmount, toRemote: BtcAmount, announceChannel: Boolean = true): Commitments = {
    CommitmentsSpec.makeCommitments(toLocal.toMilliSatoshi, toRemote.toMilliSatoshi, localNodeId, remoteNodeId, if (announceChannel) Some(dummyChannelAnn) else None)
  }

  private def updateChannelBalance(c: Commitments, toLocal: BtcAmount, toRemote: BtcAmount): Commitments = {
    val c1 = commitments(c.remoteNodeId, toLocal, toRemote, c.announceChannel)
    c1.copy(channelParams = c1.channelParams.copy(channelId = c.channelId))
  }

  private def channelUpdate(capacity: BtcAmount, fees: RelayFees = RelayFees(250 msat, 1000), timestamp: TimestampSecond = TimestampSecond.now(), announceChannel: Boolean = true): LegacyChannelUpdate = {
    val messageFlags = MessageFlags(dontForward = !announceChannel)
    val channelFlags = ChannelFlags(isEnabled = true, isNode1 = true)
    LegacyChannelUpdate(ByteVector64.Zeroes, Block.RegtestGenesisBlock.hash, RealShortChannelId(42), timestamp, messageFlags, channelFlags, CltvExpiryDelta(36), 1 msat, fees.feeBase, fees.feeProportionalMillionths, capacity.toMilliSatoshi)
  }

  private def channel(remoteNodeId: PublicKey, toLocal: BtcAmount, toRemote: BtcAmount, fees: RelayFees = RelayFees(250 msat, 1000), announceChannel: Boolean = true): DATA_NORMAL = {
    val c = commitments(remoteNodeId, toLocal, toRemote, announceChannel)
    val ann_opt = if (announceChannel) Some(dummyChannelAnn) else None
    val update = channelUpdate(c.capacity, fees, TimestampSecond.now(), announceChannel)
    DATA_NORMAL(c, dummyAliases, ann_opt, update, SpliceStatus.NoSplice, None, None, None)
  }

  test("create buckets") {
    // February 5th 2026 at 12h00 UTC.
    val timestamp = TimestampMilli(1770292800000L)
    assert(Bucket.from(timestamp) == Bucket(day = 5, month = 2, year = 2026, slot = Bucket.bucketsPerDay / 2))
    assert(Bucket.from(timestamp - 1.millis) == Bucket(day = 5, month = 2, year = 2026, slot = Bucket.bucketsPerDay / 2 - 1))
    assert(Bucket.from(timestamp - Bucket.duration) == Bucket(day = 5, month = 2, year = 2026, slot = Bucket.bucketsPerDay / 2 - 1))
    assert(Bucket.from(timestamp - Bucket.duration - 1.millis) == Bucket(day = 5, month = 2, year = 2026, slot = Bucket.bucketsPerDay / 2 - 2))
    assert(Bucket.from(timestamp - Bucket.duration * Bucket.bucketsPerDay / 2) == Bucket(day = 5, month = 2, year = 2026, slot = 0))
    assert(Bucket.from(timestamp - Bucket.duration * Bucket.bucketsPerDay / 2 - 1.millis) == Bucket(day = 4, month = 2, year = 2026, slot = Bucket.bucketsPerDay - 1))
  }

  test("sort buckets") {
    val b1 = Bucket(day = 30, month = 11, year = 2025, slot = 0)
    val b2 = Bucket(day = 30, month = 11, year = 2025, slot = 7)
    val b3 = Bucket(day = 30, month = 11, year = 2025, slot = 9)
    val b4 = Bucket(day = 1, month = 12, year = 2025, slot = 2)
    val b5 = Bucket(day = 1, month = 12, year = 2025, slot = 3)
    val b6 = Bucket(day = 15, month = 12, year = 2025, slot = 5)
    val b7 = Bucket(day = 1, month = 1, year = 2026, slot = 1)
    assert(b1 < b2 && b2 < b3 && b3 < b4 && b4 < b5 && b5 < b6 && b6 < b7)
    assert(Seq(b3, b6, b5, b1, b4, b2, b7).sorted == Seq(b1, b2, b3, b4, b5, b6, b7))
  }

  test("evaluate consumed bucket ratio") {
    // February 5th 2026 at 12h00 UTC.
    val timestamp = TimestampMilli(1770292800000L)
    assert(Bucket.consumed(timestamp) == 0.00)
    assert(Bucket.consumed(timestamp + Bucket.duration / 3) == 1.0 / 3)
    assert(Bucket.consumed(timestamp + Bucket.duration / 2) == 0.5)
    assert(Bucket.consumed(timestamp + Bucket.duration * 2 / 3) == 2.0 / 3)
    assert(Bucket.consumed(timestamp - 10.seconds) >= 0.99)
    assert(Bucket.consumed(timestamp - 10.seconds) < 1.0)
  }

  test("keep track of channel balances and state") {
    val now = TimestampMilli.now()
    val probe = TestProbe[LatestStats]()

    // We have 4 channels with our first peer: 2 of them are active.
    val c1a = DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments(remoteNodeId1, toLocal = 0.1 btc, toRemote = 0.2 btc), dummyAliases)
    val c1b = channel(remoteNodeId1, toLocal = 0.15 btc, toRemote = 0.25 btc)
    val c1c = channel(remoteNodeId1, toLocal = 0.07 btc, toRemote = 0.03 btc)
    val c1d = DATA_SHUTDOWN(commitments(remoteNodeId1, toLocal = 0.1 btc, toRemote = 0.2 btc), null, null, CloseStatus.Initiator(None))
    // We have 2 channels with our second peer: none of them are active.
    val c2a = DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments(remoteNodeId2, toLocal = 0.13 btc, toRemote = 0.24 btc), BlockHeight(750_000), None, null)
    val c2b = DATA_NEGOTIATING_SIMPLE(commitments(remoteNodeId2, toLocal = 0.5 btc, toRemote = 0.1 btc), FeeratePerKw(2000 sat), ByteVector.empty, ByteVector.empty, Nil, Nil)
    // We have 2 channels with our third peer: none of them are active.
    val c3a = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments(remoteNodeId3, toLocal = 0.7 btc, toRemote = 0.2 btc), 0 msat, 0 msat, BlockHeight(750_000), BlockHeight(750_000), DualFundingStatus.WaitingForConfirmations, None)
    val c3b = DATA_CLOSING(commitments(remoteNodeId3, toLocal = 0.2 btc, toRemote = 0.2 btc), BlockHeight(750_000), ByteVector.empty, Nil, ClosingTx(InputInfo(OutPoint(randomTxId(), 2), TxOut(500_000 sat, Script.pay2wpkh(dummyPubKey))), Transaction(2, Nil, TxOut(500_000 sat, Script.pay2wpkh(dummyPubKey)) :: Nil, 0), None) :: Nil)

    // We initialize our actor with these existing channels.
    val tracker = testKit.spawn(PeerStatsTracker(config, TestDatabases.inMemoryDb().audit, Seq(c1a, c1b, c1c, c1d, c2a, c2b, c3a, c3b)))
    // We have relayed payments through channels that have been closed since then.
    tracker.ref ! WrappedPaymentRelayed(ChannelPaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(IncomingPayment(c1b.channelId, remoteNodeId1, 58_000_000 msat, now - 5.minutes)),
      outgoing = Seq(OutgoingPayment(c3b.channelId, remoteNodeId3, 50_000_000 msat, now))
    ))
    // We have relayed payments through active channels as well.
    tracker.ref ! WrappedPaymentRelayed(ChannelPaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(IncomingPayment(c2b.channelId, remoteNodeId2, 15_000_000 msat, now - 5.minutes)),
      outgoing = Seq(OutgoingPayment(c1b.channelId, remoteNodeId1, 10_000_000 msat, now))
    ))
    // We need to wait for past events to be loaded from the DB.
    probe.awaitAssert({
      tracker.ref ! GetLatestStats(probe.ref)
      assert(probe.expectMessageType[LatestStats].peers.nonEmpty)
    })
    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      // We only have active channels with our first peer.
      assert(s.peers.map(_.remoteNodeId).toSet == Set(remoteNodeId1))
      // We only take into account the two active channels with our first peer.
      val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
      assert(peer1.channels.map(_.channelId).toSet == Set(c1b.channelId, c1c.channelId))
      assert(peer1.latestUpdate_opt.exists(u => Set(c1b.channelUpdate, c1c.channelUpdate).contains(u)))
      assert(peer1.capacity == 0.5.btc.toSatoshi)
      assert(peer1.hasPendingChannel)
      assert(0.21.btc.toMilliSatoshi <= peer1.canSend && peer1.canSend <= 0.22.btc.toMilliSatoshi)
      assert(0.27.btc.toMilliSatoshi <= peer1.canReceive && peer1.canReceive <= 0.28.btc.toMilliSatoshi)
    }

    // Our pending channel with our second peer becomes ready.
    val update2a = channelUpdate(c2a.commitments.capacity, RelayFees(500 msat, 500))
    tracker.ref ! WrappedLocalChannelUpdate(LocalChannelUpdate(
      channel = ActorRef.noSender,
      channelId = c2a.channelId,
      aliases = dummyAliases,
      remoteNodeId = remoteNodeId2,
      announcement_opt = None,
      channelUpdate = update2a,
      commitments = c2a.commitments,
    ))

    // Our pending channel with our third peer is aborted.
    tracker.ref ! WrappedLocalChannelDown(LocalChannelDown(
      channel = ActorRef.noSender,
      channelId = c3a.channelId,
      realScids = Nil,
      aliases = dummyAliases,
      remoteNodeId = remoteNodeId3
    ))

    // We forget about our third peer, with whom we don't have pending or active channels anymore.
    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      assert(s.peers.map(_.remoteNodeId).toSet == Set(remoteNodeId1, remoteNodeId2))
      assert(!s.peers.find(_.remoteNodeId == remoteNodeId2).get.hasPendingChannel)
      assert(s.peers.find(_.remoteNodeId == remoteNodeId2).get.latestUpdate_opt.contains(update2a))
    }

    // We relay a payment to our second peer.
    tracker.ref ! WrappedPaymentRelayed(ChannelPaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(IncomingPayment(c1b.channelId, remoteNodeId1, 30_000_000 msat, now - 5.minutes)),
      outgoing = Seq(OutgoingPayment(c2a.channelId, remoteNodeId2, 15_000_000 msat, now))
    ))
    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      assert(s.peers.map(_.remoteNodeId).toSet == Set(remoteNodeId1, remoteNodeId2))
      val peer2 = s.peers.find(_.remoteNodeId == remoteNodeId2).get
      assert(peer2.channels.map(_.channelId).toSet == Set(c2a.channelId))
      assert(peer2.latestUpdate_opt.contains(update2a))
    }

    // We update our routing fees with our first peer.
    val update1b = channelUpdate(c1b.commitments.capacity, RelayFees(100 msat, 600), TimestampSecond.now() + 10.seconds)
    tracker.ref ! WrappedLocalChannelUpdate(LocalChannelUpdate(
      channel = ActorRef.noSender,
      channelId = c1b.channelId,
      aliases = dummyAliases,
      remoteNodeId = remoteNodeId1,
      announcement_opt = None,
      channelUpdate = update1b,
      commitments = updateChannelBalance(c1b.commitments, toLocal = 0.3 btc, toRemote = 0.1 btc),
    ))

    // We ignore previous channel updates from another channel.
    val update1c = channelUpdate(c1c.commitments.capacity, RelayFees(150 msat, 400), update1b.timestamp - 10.seconds)
    tracker.ref ! WrappedLocalChannelUpdate(LocalChannelUpdate(
      channel = ActorRef.noSender,
      channelId = c1c.channelId,
      aliases = dummyAliases,
      remoteNodeId = remoteNodeId1,
      announcement_opt = None,
      channelUpdate = update1c,
      commitments = updateChannelBalance(c1c.commitments, toLocal = 0.05 btc, toRemote = 0.05 btc),
    ))

    // Channels with our second peer are closed.
    tracker.ref ! WrappedLocalChannelDown(LocalChannelDown(
      channel = ActorRef.noSender,
      channelId = c2a.channelId,
      realScids = Nil,
      aliases = dummyAliases,
      remoteNodeId = remoteNodeId2
    ))
    tracker.ref ! WrappedLocalChannelDown(LocalChannelDown(
      channel = ActorRef.noSender,
      channelId = c2b.channelId,
      realScids = Nil,
      aliases = dummyAliases,
      remoteNodeId = remoteNodeId2
    ))

    // The only remaining channels are with our first peer, with updated balances and the latest channel update.
    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      assert(s.peers.map(_.remoteNodeId).toSet == Set(remoteNodeId1))
      val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
      assert(peer1.channels.map(_.channelId).toSet == Set(c1b.channelId, c1c.channelId))
      assert(peer1.capacity == 0.5.btc.toSatoshi)
      assert(0.34.btc.toMilliSatoshi <= peer1.canSend && peer1.canSend <= 0.35.btc.toMilliSatoshi)
      assert(0.14.btc.toMilliSatoshi <= peer1.canReceive && peer1.canReceive <= 0.15.btc.toMilliSatoshi)
      assert(peer1.latestUpdate_opt.contains(update1b))
    }
  }

  test("keep track of peer statistics") {
    val now = TimestampMilli.now()
    val probe = TestProbe[LatestStats]()
    val tracker = testKit.spawn(PeerStatsTracker(config, TestDatabases.inMemoryDb().audit, Nil))

    // We have channels with 3 peers:
    val c1a = commitments(remoteNodeId1, toLocal = 0.5 btc, toRemote = 0.3 btc)
    val c1b = commitments(remoteNodeId1, toLocal = 0.4 btc, toRemote = 0.2 btc, announceChannel = false)
    val c2 = commitments(remoteNodeId2, toLocal = 0.01 btc, toRemote = 0.9 btc)
    val c3 = commitments(remoteNodeId3, toLocal = 0.7 btc, toRemote = 0.1 btc)
    Seq(c1a, c1b, c2, c3).foreach(c => tracker.ref ! WrappedAvailableBalanceChanged(AvailableBalanceChanged(
      channel = ActorRef.noSender,
      channelId = c.channelId,
      aliases = dummyAliases,
      commitments = c,
      lastAnnouncement_opt = None,
    )))

    // We have relayed some payments with all of those peers.
    tracker.ref ! WrappedPaymentRelayed(ChannelPaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(IncomingPayment(c1a.channelId, remoteNodeId1, 30_000_000 msat, now - Bucket.duration * 2)),
      outgoing = Seq(OutgoingPayment(c2.channelId, remoteNodeId2, 20_000_000 msat, now - Bucket.duration * 2))
    ))
    tracker.ref ! WrappedPaymentRelayed(ChannelPaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(IncomingPayment(c2.channelId, remoteNodeId2, 10_000_000 msat, now - Bucket.duration * 2)),
      outgoing = Seq(OutgoingPayment(c3.channelId, remoteNodeId3, 9_000_000 msat, now - Bucket.duration * 2))
    ))
    tracker.ref ! WrappedPaymentRelayed(ChannelPaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(
        IncomingPayment(c2.channelId, remoteNodeId2, 30_000_000 msat, now - Bucket.duration),
        IncomingPayment(c3.channelId, remoteNodeId3, 25_000_000 msat, now - Bucket.duration),
      ),
      outgoing = Seq(
        OutgoingPayment(c1a.channelId, remoteNodeId1, 50_000_000 msat, now - Bucket.duration),
      )
    ))
    tracker.ref ! WrappedPaymentRelayed(TrampolinePaymentRelayed(
      paymentHash = randomBytes32(),
      incoming = Seq(
        IncomingPayment(c2.channelId, remoteNodeId2, 21_000_000 msat, now),
        IncomingPayment(c2.channelId, remoteNodeId2, 34_000_000 msat, now),
      ),
      outgoing = Seq(
        OutgoingPayment(c3.channelId, remoteNodeId3, 22_000_000 msat, now),
        OutgoingPayment(c3.channelId, remoteNodeId3, 18_000_000 msat, now),
        OutgoingPayment(c1b.channelId, remoteNodeId1, 10_000_000 msat, now),
      ),
      nextTrampolineNodeId = randomKey().publicKey,
      nextTrampolineAmount = 50_000_000 msat,
    ))

    // We need to wait for past events to be loaded from the DB.
    probe.awaitAssert({
      tracker.ref ! GetLatestStats(probe.ref)
      assert(probe.expectMessageType[LatestStats].peers.nonEmpty)
    })

    // We keep track of aggregated statistics per bucket.
    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      assert(s.peers.map(_.remoteNodeId).toSet == Set(remoteNodeId1, remoteNodeId2, remoteNodeId3))
      assert(s.peers.flatMap(_.stats.map(_.profit)).sum == 21_000_000.msat)
      // We only have routing activity in the past 3 buckets.
      s.peers.foreach(p => assert(p.stats.drop(3).forall(olderStats => olderStats == PeerStats.empty)))
      // We verify that routing activity is correctly recorded in the right bucket.
      val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
      assert(peer1.capacity == 1.4.btc.toSatoshi)
      assert(peer1.canSend == c1a.availableBalanceForSend + c1b.availableBalanceForSend)
      assert(peer1.canReceive == c1a.availableBalanceForReceive + c1b.availableBalanceForReceive)
      assert(peer1.stats.head.totalAmountIn == 0.msat)
      assert(peer1.stats.head.totalAmountOut == 10_000_000.msat)
      assert(peer1.stats.head.relayFeeEarned == 1_000_000.msat)
      assert(peer1.stats(1).totalAmountIn == 0.msat)
      assert(peer1.stats(1).totalAmountOut == 50_000_000.msat)
      assert(peer1.stats(1).relayFeeEarned == 5_000_000.msat)
      assert(peer1.stats(2).totalAmountIn == 30_000_000.msat)
      assert(peer1.stats(2).totalAmountOut == 0.msat)
      assert(peer1.stats(2).relayFeeEarned == 0.msat)
      assert(peer1.stats.map(_.outgoingFlow).sum == 30_000_000.msat)
      val peer2 = s.peers.find(_.remoteNodeId == remoteNodeId2).get
      assert(peer2.capacity == 0.91.btc.toSatoshi)
      assert(peer2.canSend == c2.availableBalanceForSend)
      assert(peer2.canReceive == c2.availableBalanceForReceive)
      assert(peer2.stats.head.totalAmountIn == 55_000_000.msat)
      assert(peer2.stats.head.totalAmountOut == 0.msat)
      assert(peer2.stats.head.relayFeeEarned == 0.msat)
      assert(peer2.stats(1).totalAmountIn == 30_000_000.msat)
      assert(peer2.stats(1).totalAmountOut == 0.msat)
      assert(peer2.stats(1).relayFeeEarned == 0.msat)
      assert(peer2.stats(2).totalAmountIn == 10_000_000.msat)
      assert(peer2.stats(2).totalAmountOut == 20_000_000.msat)
      assert(peer2.stats(2).relayFeeEarned == 10_000_000.msat)
      assert(peer2.stats.map(_.outgoingFlow).sum == -75_000_000.msat)
      val peer3 = s.peers.find(_.remoteNodeId == remoteNodeId3).get
      assert(peer3.capacity == 0.8.btc.toSatoshi)
      assert(peer3.canSend == c3.availableBalanceForSend)
      assert(peer3.canReceive == c3.availableBalanceForReceive)
      assert(peer3.stats.head.totalAmountIn == 0.msat)
      assert(peer3.stats.head.totalAmountOut == 40_000_000.msat)
      assert(peer3.stats.head.relayFeeEarned == 4_000_000.msat)
      assert(peer3.stats(1).totalAmountIn == 25_000_000.msat)
      assert(peer3.stats(1).totalAmountOut == 0.msat)
      assert(peer3.stats(1).relayFeeEarned == 0.msat)
      assert(peer3.stats(2).totalAmountIn == 0.msat)
      assert(peer3.stats(2).totalAmountOut == 9_000_000.msat)
      assert(peer3.stats(2).relayFeeEarned == 1_000_000.msat)
      assert(peer3.stats.map(_.outgoingFlow).sum == 24_000_000.msat)
    }
  }

  test("initialize peer stats with past events") {
    val now = TimestampMilli.now()
    val probe = TestProbe[LatestStats]()
    val db = TestDatabases.inMemoryDb().audit
    val channelId1 = randomBytes32()
    val channelId2 = randomBytes32()
    val dummyTx = Transaction(2, Nil, Seq(TxOut(50_000 sat, Script.pay2wpkh(dummyPubKey))), 0)

    // PaymentSent through remoteNodeId1, 1 day ago.
    val sentAmount = 100_000_000 msat
    val sentFees = 1_000 msat
    val sentAt = now - 1.day
    db.add(PaymentSent(
      UUID.randomUUID(), randomBytes32(), sentAmount - sentFees, randomKey().publicKey,
      PaymentSent.PaymentPart(UUID.randomUUID(), OutgoingPayment(channelId1, remoteNodeId1, sentAmount, sentAt), sentFees, None, sentAt - 10.seconds) :: Nil,
      None, sentAt - 10.seconds
    ))

    // PaymentReceived through remoteNodeId2, 2 days ago.
    val receivedAmount = 50_000_000 msat
    val receivedAt = now - 2.days
    db.add(PaymentReceived(randomBytes32(), IncomingPayment(channelId2, remoteNodeId2, receivedAmount, receivedAt) :: Nil))

    // ChannelPaymentRelayed from remoteNodeId2 -> remoteNodeId1, 12 hours ago.
    val relayedInAmount = 25_000_000 msat
    val relayedOutAmount = 24_500_000 msat
    val relayedAt = now - 12.hours
    db.add(ChannelPaymentRelayed(
      randomBytes32(),
      Seq(IncomingPayment(channelId2, remoteNodeId2, relayedInAmount, relayedAt - 1.minute)),
      Seq(OutgoingPayment(channelId1, remoteNodeId1, relayedOutAmount, relayedAt))
    ))

    // TransactionPublished + TransactionConfirmed for remoteNodeId1 as buyer, 3 days ago.
    val buyerPurchase = LiquidityAds.PurchaseBasicInfo(isBuyer = true, 100_000 sat, LiquidityAds.Fees(50 sat, 200 sat))
    val txPublished1 = TransactionPublished(channelId1, remoteNodeId1, dummyTx, 300 sat, 0 sat, "funding", Some(buyerPurchase), now - 3.days)
    db.add(txPublished1)
    db.add(TransactionConfirmed(channelId1, remoteNodeId1, txPublished1.tx, now - 3.days + 10.minutes))

    // TransactionPublished + TransactionConfirmed for remoteNodeId2 as seller, 5 days ago.
    val sellerPurchase = LiquidityAds.PurchaseBasicInfo(isBuyer = false, 200_000 sat, LiquidityAds.Fees(100 sat, 500 sat))
    val dummyTx2 = Transaction(2, Nil, Seq(TxOut(200_000 sat, Script.pay2wpkh(dummyPubKey))), 0)
    val txPublished2 = TransactionPublished(channelId2, remoteNodeId2, dummyTx2, 150 sat, 0 sat, "splice", Some(sellerPurchase), now - 5.days)
    db.add(txPublished2)
    db.add(TransactionConfirmed(channelId2, remoteNodeId2, txPublished2.tx, now - 5.days + 20.minutes))

    // TransactionPublished + TransactionConfirmed for remoteNodeId1, plain (no liquidity purchase), 1 day ago.
    val dummyTx3 = Transaction(2, Nil, Seq(TxOut(30_000 sat, Script.pay2wpkh(dummyPubKey))), 0)
    val txPublished3 = TransactionPublished(channelId1, remoteNodeId1, dummyTx3, 200 sat, 0 sat, "mutual", None, now - 1.day)
    db.add(txPublished3)
    db.add(TransactionConfirmed(channelId1, remoteNodeId1, txPublished3.tx, now - 1.day + 5.minutes))

    // Event outside 7-day window (10 days ago) — should NOT appear in stats.
    val oldAt = now - 10.days
    db.add(PaymentSent(
      UUID.randomUUID(), randomBytes32(), 500_000_000 msat, randomKey().publicKey,
      PaymentSent.PaymentPart(UUID.randomUUID(), OutgoingPayment(channelId1, remoteNodeId1, 500_000_000 msat, oldAt), 0 msat, None, oldAt) :: Nil,
      None, oldAt
    ))

    // Spawn tracker with channels for both peers.
    val c1 = channel(remoteNodeId1, toLocal = 0.3 btc, toRemote = 0.2 btc)
    val c2 = channel(remoteNodeId2, toLocal = 0.1 btc, toRemote = 0.4 btc)
    val tracker = testKit.spawn(PeerStatsTracker(config, db, Seq(c1, c2)))

    probe.awaitAssert({
      tracker.ref ! GetLatestStats(probe.ref)
      inside(probe.expectMessageType[LatestStats]) { s =>
        assert(s.peers.map(_.remoteNodeId).toSet == Set(remoteNodeId1, remoteNodeId2))

        val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
        // remoteNodeId1: sent 100_000_000 msat out + relayed 24_500_000 msat out (the older event is ignored).
        assert(peer1.stats.map(_.totalAmountOut).sum == sentAmount + relayedOutAmount)
        assert(peer1.stats.map(_.totalAmountIn).sum == 0.msat)
        // on-chain fees: 300 sat (buyer tx) + 200 sat (plain tx)
        assert(peer1.stats.map(_.onChainFeePaid).sum == 500.sat)
        // liquidity fee paid as buyer: 50 + 200 = 250 sat
        assert(peer1.stats.map(_.liquidityFeePaid).sum == 250.sat)
        assert(peer1.stats.map(_.liquidityFeeEarned).sum == 0.sat)

        val peer2 = s.peers.find(_.remoteNodeId == remoteNodeId2).get
        // remoteNodeId2: received 50_000_000 msat + relayed 25_000_000 msat in
        assert(peer2.stats.map(_.totalAmountIn).sum == receivedAmount + relayedInAmount)
        assert(peer2.stats.map(_.totalAmountOut).sum == 0.msat)
        // on-chain fees: 150 sat (seller tx)
        assert(peer2.stats.map(_.onChainFeePaid).sum == 150.sat)
        // liquidity fee earned as seller: 100 + 500 = 600 sat
        assert(peer2.stats.map(_.liquidityFeeEarned).sum == 600.sat)
        assert(peer2.stats.map(_.liquidityFeePaid).sum == 0.sat)
      }
    })
  }

  test("read confirmed transactions when statistics are requested") {
    val probe = TestProbe[LatestStats]()
    val db = TestDatabases.inMemoryDb().audit
    val channelId1 = randomBytes32()
    val dummyTx = Transaction(2, Nil, Seq(TxOut(50_000 sat, Script.pay2wpkh(dummyPubKey))), 0)

    // Spawn tracker with a channel for remoteNodeId1 and empty DB.
    val c1 = channel(remoteNodeId1, toLocal = 0.3 btc, toRemote = 0.2 btc)
    val tracker = testKit.spawn(PeerStatsTracker(config, db, Seq(c1)))

    // Wait for initial loading to complete (should be almost instantaneous on an empty DB).
    probe.awaitAssert({
      tracker.ref ! GetLatestStats(probe.ref)
      assert(probe.expectMessageType[LatestStats].peers.nonEmpty)
    })

    // Add a confirmed transaction after starting.
    val now = TimestampMilli.now()
    val txPublished1 = TransactionPublished(channelId1, remoteNodeId1, dummyTx, 300 sat, 0 sat, "funding", None, now + 5.millis)
    db.add(txPublished1)
    db.add(TransactionConfirmed(channelId1, remoteNodeId1, txPublished1.tx, now + 10.millis))

    probe.awaitAssert({
      tracker.ref ! GetLatestStats(probe.ref)
      inside(probe.expectMessageType[LatestStats]) { s =>
        val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
        assert(peer1.stats.map(_.onChainFeePaid).sum == 300.sat)
      }
    })

    // Verify that we don't count transactions multiple times.
    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
      assert(peer1.stats.map(_.onChainFeePaid).sum == 300.sat)
    }

    // Add another confirmed transaction, verify the cumulative total.
    // Note that we subtract 1 millisecond, otherwise the test may be flaky if it runs too fast and processes the
    // message at exactly the same millisecond (because we read from the DB events < now).
    val now2 = TimestampMilli.now() - 1.millis
    val dummyTx2 = Transaction(2, Nil, Seq(TxOut(30_000 sat, Script.pay2wpkh(dummyPubKey))), 0)
    val txPublished2 = TransactionPublished(channelId1, remoteNodeId1, dummyTx2, 150 sat, 0 sat, "splice", None, now2)
    db.add(txPublished2)
    db.add(TransactionConfirmed(channelId1, remoteNodeId1, txPublished2.tx, now2))

    tracker.ref ! GetLatestStats(probe.ref)
    inside(probe.expectMessageType[LatestStats]) { s =>
      val peer1 = s.peers.find(_.remoteNodeId == remoteNodeId1).get
      assert(peer1.stats.map(_.onChainFeePaid).sum == 450.sat)
    }
  }

}
