package fr.acinq.eclair.balance

import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.balance.CheckBalance.{ClosingBalance, MainAndHtlcBalance, OffChainBalance, PossiblyPublishedMainAndHtlcBalance, PossiblyPublishedMainBalance}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{apply => _, _}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.Helpers.Closing.{CurrentRemoteClose, LocalClose}
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.channel.{CLOSING, CMD_SIGN, DATA_CLOSING, DATA_NORMAL}
import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.pg.PgUtils.using
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import fr.acinq.eclair.wire.protocol.{CommitSig, Error, RevokeAndAck, TlvStream, UpdateAddHtlc, UpdateAddHtlcTlv}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.sqlite.SQLiteConfig

import java.io.File
import java.sql.DriverManager
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class CheckBalanceSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    within(30 seconds) {
      reachNormal(setup, test.tags)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("do not deduplicate htlc amounts") { f =>
    import f._

    // We add 3 identical htlcs Alice -> Bob
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    assert(CheckBalance.computeOffChainBalance(Seq(alice.stateData.asInstanceOf[DATA_NORMAL]), knownPreimages = Set.empty).normal ==
      MainAndHtlcBalance(
        toLocal = (TestConstants.fundingSatoshis - TestConstants.initiatorPushAmount - 30_000_000.msat).truncateToSatoshi,
        htlcs = 30_000.sat
      )
    )
  }

  test("take in-flight signed fulfills into account") { f =>
    import f._

    val (preimage, htlc) = addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlc.id, preimage, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]

    assert(CheckBalance.computeOffChainBalance(Seq(bob.stateData.asInstanceOf[DATA_NORMAL]), knownPreimages = Set.empty).normal ==
      MainAndHtlcBalance(
        toLocal = TestConstants.initiatorPushAmount.truncateToSatoshi,
        htlcs = htlc.amountMsat.truncateToSatoshi
      )
    )
  }

  test("take published remote commit tx into account") { f =>
    import f._

    // We add 3 htlcs Alice -> Bob (one of them below dust) and 2 htlcs Bob -> Alice
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (_, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (_, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // And fulfill one htlc in each direction without signing a new commit tx
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    // in response to that, alice publishes her claim txs
    alice2blockchain.expectMsgType[PublishFinalTx] // claim-main
    val claimHtlcTxs = (1 to 3).map(_ => alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.tx)

    val commitments = alice.stateData.asInstanceOf[DATA_CLOSING].commitments
    val remoteCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get
    val knownPreimages = Set((commitments.channelId, htlcb1.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.active.last.remoteCommit, remoteCommitPublished), knownPreimages) ==
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimHtlcTxs.map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi
      ))
    // assuming alice gets the preimage for the 2nd htlc
    val knownPreimages1 = Set((commitments.channelId, htlcb1.id), (commitments.channelId, htlcb2.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.active.last.remoteCommit, remoteCommitPublished), knownPreimages1) ==
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimHtlcTxs.map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi + htlcb2.amountMsat.truncateToSatoshi
      ))
  }

  test("take published next remote commit tx into account") { f =>
    import f._

    // We add 3 htlcs Alice -> Bob (one of them below dust) and 2 htlcs Bob -> Alice
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (_, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (_, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // And fulfill one htlc in each direction
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)
    // alice signs but we intercept bob's revocation
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]

    // as far as alice knows, bob currently has two valid unrevoked commitment transactions
    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.last.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.size == 5) // two main outputs and 3 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // in response to that, alice publishes her claim txs
    alice2blockchain.expectMsgType[PublishFinalTx] // claim-main
    val claimHtlcTxs = (1 to 2).map(_ => alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.tx)

    val commitments = alice.stateData.asInstanceOf[DATA_CLOSING].commitments
    val remoteCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get
    val knownPreimages = Set((commitments.channelId, htlcb1.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.active.last.nextRemoteCommit_opt.get.commit, remoteCommitPublished), knownPreimages) ==
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimHtlcTxs.map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi
      ))
    // assuming alice gets the preimage for the 2nd htlc
    val knownPreimages1 = Set((commitments.channelId, htlcb1.id), (commitments.channelId, htlcb2.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.active.last.nextRemoteCommit_opt.get.commit, remoteCommitPublished), knownPreimages1) ==
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimHtlcTxs.map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi + htlcb2.amountMsat.truncateToSatoshi
      ))
  }

  test("take published local commit tx into account") { f =>
    import f._

    // We add 4 htlcs Alice -> Bob (one of them below dust) and 2 htlcs Bob -> Alice
    val (_, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (_, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    // for this one we set a non-local upstream to simulate a relayed payment
    val (_, htlca4) = addHtlc(30000000 msat, CltvExpiryDelta(144), alice, bob, alice2bob, bob2alice, upstream = Upstream.Trampoline(UpdateAddHtlc(randomBytes32(), 42, 30003000 msat, randomBytes32(), CltvExpiry(144), TestConstants.emptyOnionPacket, TlvStream.empty[UpdateAddHtlcTlv]) :: Nil), replyTo = TestProbe().ref)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (_, _) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // And fulfill one htlc in each direction without signing a new commit tx
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)

    // alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.last.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid)
    assert(aliceCommitTx.txOut.size == 7) // two main outputs and 5 pending htlcs (one is dust)
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val commitments = alice.stateData.asInstanceOf[DATA_CLOSING].commitments
    val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    val knownPreimages = Set((commitments.channelId, htlcb1.id))
    assert(CheckBalance.computeLocalCloseBalance(commitments.changes, LocalClose(commitments.active.last.localCommit, localCommitPublished), commitments.originChannels, knownPreimages) ==
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(localCommitPublished.claimMainDelayedOutputTx.get.tx.txid -> localCommitPublished.claimMainDelayedOutputTx.get.tx.txOut.head.amount),
        htlcs = Map.empty,
        htlcsUnpublished = htlca4.amountMsat.truncateToSatoshi + htlcb1.amountMsat.truncateToSatoshi
      ))

    alice2blockchain.expectMsgType[PublishFinalTx] // claim-main
    val htlcTx1 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcTx2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcTx3 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val htlcTx4 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // commit tx
    alice2blockchain.expectMsgType[WatchTxConfirmed] // main-delayed
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 1
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 2
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 3
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 4
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 5

    // 3rd-stage txs are published when htlc-timeout txs confirm
    val claimHtlcDelayedTxs = Seq(htlcTx1, htlcTx2, htlcTx3, htlcTx4).map { htlcTimeoutTx =>
      alice ! WatchOutputSpentTriggered(htlcTimeoutTx)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == htlcTimeoutTx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(2701), 3, htlcTimeoutTx)
      val claimHtlcDelayedTx = alice2blockchain.expectMsgType[PublishFinalTx].tx
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimHtlcDelayedTx.txid)
      claimHtlcDelayedTx
    }
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs.length == 4)

    assert(CheckBalance.computeLocalCloseBalance(commitments.changes, LocalClose(commitments.active.last.localCommit, alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get), commitments.originChannels, knownPreimages) ==
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(localCommitPublished.claimMainDelayedOutputTx.get.tx.txid -> localCommitPublished.claimMainDelayedOutputTx.get.tx.txOut.head.amount),
        htlcs = claimHtlcDelayedTxs.map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = 0.sat
      ))
  }

  ignore("compute from eclair.sqlite") { _ =>
    val dbFile = new File("eclair.sqlite")
    val sqliteConfig = new SQLiteConfig()
    sqliteConfig.setReadOnly(true)
    val sqlite = DriverManager.getConnection(s"jdbc:sqlite:$dbFile", sqliteConfig.toProperties)
    val channels = using(sqlite.createStatement) { statement =>
      statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=0")
        .mapCodec(channelDataCodec)
    }
    val knownPreimages: Set[(ByteVector32, Long)] = using(sqlite.prepareStatement("SELECT channel_id, htlc_id FROM pending_relay")) { statement =>
      val rs = statement.executeQuery()
      var q: Queue[(ByteVector32, Long)] = Queue()
      while (rs.next()) {
        q = q :+ (rs.getByteVector32("channel_id"), rs.getLong("htlc_id"))
      }
      q.toSet
    }
    val res = CheckBalance.computeOffChainBalance(channels, knownPreimages)
    println(res)
    println(res.total)
  }

  test("tx pruning") { () =>
    val txids = (for (_ <- 0 until 20) yield randomBytes32()).toList
    val knownTxids = Set(txids(1), txids(3), txids(4), txids(6), txids(9), txids(12), txids(13))

    val bitcoinClient = new BitcoinCoreClient(null) {
      /** Get the number of confirmations of a given transaction. */
      override def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] =
        Future.successful(if (knownTxids.contains(txid)) Some(42) else None)
    }

    val bal1 = OffChainBalance(
      closing = ClosingBalance(
        localCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(0) -> 1000.sat,
            txids(1) -> 1000.sat,
            txids(2) -> 1000.sat),
          htlcs = Map(
            txids(3) -> 1000.sat,
            txids(4) -> 1000.sat,
            txids(5) -> 1000.sat)
        ),
        remoteCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(6) -> 1000.sat,
            txids(7) -> 1000.sat,
            txids(8) -> 1000.sat,
            txids(9) -> 1000.sat),
          htlcs = Map(
            txids(10) -> 1000.sat,
            txids(11) -> 1000.sat,
            txids(12) -> 1000.sat),
        ),
        mutualCloseBalance = PossiblyPublishedMainBalance(
          toLocal = Map(
            txids(13) -> 1000.sat,
            txids(14) -> 1000.sat
          )
        )
      )
    )

    val sender = TestProbe()
    CheckBalance.prunePublishedTransactions(bal1, bitcoinClient).pipeTo(sender.ref)
    val bal2 = sender.expectMsgType[OffChainBalance]

    assert(bal2 == OffChainBalance(
      closing = ClosingBalance(
        localCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(0) -> 1000.sat,
            txids(2) -> 1000.sat),
          htlcs = Map(
            txids(5) -> 1000.sat)
        ),
        remoteCloseBalance = PossiblyPublishedMainAndHtlcBalance(
          toLocal = Map(
            txids(7) -> 1000.sat,
            txids(8) -> 1000.sat),
          htlcs = Map(
            txids(10) -> 1000.sat,
            txids(11) -> 1000.sat),
        ),
        mutualCloseBalance = PossiblyPublishedMainBalance(
          toLocal = Map(
            txids(14) -> 1000.sat
          )
        )))
    )
  }

}
