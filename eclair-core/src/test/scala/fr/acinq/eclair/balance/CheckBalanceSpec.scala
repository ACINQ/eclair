package fr.acinq.eclair.balance

import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{ByteVector32, SatoshiLong, ScriptFlags, Transaction}
import fr.acinq.eclair.balance.CheckBalance.{ClosingBalance, OffChainBalance, PossiblyPublishedMainAndHtlcBalance, PossiblyPublishedMainBalance}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{apply => _, _}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.Helpers.Closing.{CurrentRemoteClose, LocalClose}
import fr.acinq.eclair.channel.publish.TxPublisher.PublishRawTx
import fr.acinq.eclair.channel.states.StateTestsBase
import fr.acinq.eclair.channel.{CLOSING, CMD_SIGN, DATA_CLOSING, DATA_NORMAL}
import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.pg.PgUtils.using
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.stateDataCodec
import fr.acinq.eclair.wire.protocol.{CommitSig, Error, RevokeAndAck}
import fr.acinq.eclair.{MilliSatoshiLong, TestKitBaseClass, randomBytes32}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.sqlite.SQLiteConfig

import java.io.File
import java.sql.DriverManager
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class CheckBalanceSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    within(30 seconds) {
      reachNormal(setup, test.tags)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("recv WatchFundingSpentTriggered (their commit w/ htlc)") { f =>
    import f._

    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // in response to that, alice publishes its claim txs
    val claimTxs = for (_ <- 0 until 4) yield alice2blockchain.expectMsgType[PublishRawTx].tx
    // in addition to its main output, alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxs) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut.head.amount
    }).sum
    // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
    assert(amountClaimed === 814880.sat)

    val commitments = alice.stateData.asInstanceOf[DATA_CLOSING].commitments
    val remoteCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get
    val knownPreimages = Set((commitments.channelId, htlcb1.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.remoteCommit, remoteCommitPublished), knownPreimages) ===
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimTxs.drop(1).map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi
      ))
    // assuming alice gets the preimage for the 2nd htlc
    val knownPreimages1 = Set((commitments.channelId, htlcb1.id), (commitments.channelId, htlcb2.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.remoteCommit, remoteCommitPublished), knownPreimages1) ===
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimTxs.drop(1).map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi + htlcb2.amountMsat.truncateToSatoshi
      ))
  }

  test("recv WatchFundingSpentTriggered (their *next* commit w/ htlc)") { f =>
    import f._

    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)
    // alice sign but we intercept bob's revocation
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]

    // as far as alice knows, bob currently has two valid unrevoked commitment transactions

    // at this point here is the situation from bob's pov with the latest sig received from alice,
    // and what alice should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 499 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx.txOut.size == 5) // two main outputs and 3 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // in response to that, alice publishes its claim txs
    val claimTxs = for (_ <- 0 until 3) yield alice2blockchain.expectMsgType[PublishRawTx].tx
    // in addition to its main output, alice can only claim 2 out of 3 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxs) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut.head.amount
    }).sum
    // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
    assert(amountClaimed === 822310.sat)

    val commitments = alice.stateData.asInstanceOf[DATA_CLOSING].commitments
    val remoteCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get
    val knownPreimages = Set((commitments.channelId, htlcb1.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.remoteNextCommitInfo.left.get.nextRemoteCommit, remoteCommitPublished), knownPreimages) ===
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimTxs.drop(1).map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi
      ))
    // assuming alice gets the preimage for the 2nd htlc
    val knownPreimages1 = Set((commitments.channelId, htlcb1.id), (commitments.channelId, htlcb2.id))
    assert(CheckBalance.computeRemoteCloseBalance(commitments, CurrentRemoteClose(commitments.remoteNextCommitInfo.left.get.nextRemoteCommit, remoteCommitPublished), knownPreimages1) ===
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(remoteCommitPublished.claimMainOutputTx.get.tx.txid -> remoteCommitPublished.claimMainOutputTx.get.tx.txOut.head.amount),
        htlcs = claimTxs.drop(1).map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi + htlcb2.amountMsat.truncateToSatoshi
      ))
  }

  test("recv Error") { f =>
    import f._
    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb1.id, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when she publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend using 2nd stage htlc-timeout
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend using 2nd stage htlc-timeout
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage using htlc-success
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === aliceCommitTx)
    assert(aliceCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val commitments = alice.stateData.asInstanceOf[DATA_CLOSING].commitments
    val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished.commitTx == aliceCommitTx)
    assert(localCommitPublished.htlcTxs.size === 4)
    assert(getHtlcSuccessTxs(localCommitPublished).length === 1)
    assert(getHtlcTimeoutTxs(localCommitPublished).length === 2)
    assert(localCommitPublished.claimHtlcDelayedTxs.isEmpty)

    val knownPreimages = Set((commitments.channelId, htlcb1.id))
    assert(CheckBalance.computeLocalCloseBalance(commitments, LocalClose(commitments.localCommit, localCommitPublished), knownPreimages) ===
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(localCommitPublished.claimMainDelayedOutputTx.get.tx.txid -> localCommitPublished.claimMainDelayedOutputTx.get.tx.txOut.head.amount),
        htlcs = Map.empty,
        htlcsUnpublished = htlca1.amountMsat.truncateToSatoshi + htlca3.amountMsat.truncateToSatoshi + htlcb1.amountMsat.truncateToSatoshi
      ))

    // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the htlc
    // so we expect 4 transactions:
    // - 1 tx to claim the main delayed output
    // - 3 txs for each htlc
    // NB: 3rd-stage txs will only be published once the htlc txs confirm
    val claimMain = alice2blockchain.expectMsgType[PublishRawTx].tx
    val htlcTx1 = alice2blockchain.expectMsgType[PublishRawTx].tx
    val htlcTx2 = alice2blockchain.expectMsgType[PublishRawTx].tx
    val htlcTx3 = alice2blockchain.expectMsgType[PublishRawTx].tx
    // the main delayed output and htlc txs spend the commitment transaction
    Seq(claimMain, htlcTx1, htlcTx2, htlcTx3).foreach(tx => Transaction.correctlySpends(tx, aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.txid) // main-delayed
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 1
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 2
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 3
    alice2blockchain.expectMsgType[WatchOutputSpent] // htlc 4
    alice2blockchain.expectNoMsg(1 second)

    // 3rd-stage txs are published when htlc txs confirm
    val claimHtlcDelayedTxs = Seq(htlcTx1, htlcTx2, htlcTx3).map { htlcTimeoutTx =>
      alice ! WatchOutputSpentTriggered(htlcTimeoutTx)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === htlcTimeoutTx.txid)
      alice ! WatchTxConfirmedTriggered(2701, 3, htlcTimeoutTx)
      val claimHtlcDelayedTx = alice2blockchain.expectMsgType[PublishRawTx].tx
      Transaction.correctlySpends(claimHtlcDelayedTx, htlcTimeoutTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === claimHtlcDelayedTx.txid)
      claimHtlcDelayedTx
    }
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.claimHtlcDelayedTxs.length == 3)
    alice2blockchain.expectNoMessage(1 second)

    assert(CheckBalance.computeLocalCloseBalance(commitments, LocalClose(commitments.localCommit, alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get), knownPreimages) ===
      PossiblyPublishedMainAndHtlcBalance(
        toLocal = Map(localCommitPublished.claimMainDelayedOutputTx.get.tx.txid -> localCommitPublished.claimMainDelayedOutputTx.get.tx.txOut.head.amount),
        htlcs = claimHtlcDelayedTxs.map(claimTx => claimTx.txid -> claimTx.txOut.head.amount.toBtc).toMap,
        htlcsUnpublished = htlca3.amountMsat.truncateToSatoshi
      ))
  }

  ignore("compute from eclair.sqlite") { _ =>
    val dbFile = new File("eclair.sqlite")
    val sqliteConfig = new SQLiteConfig()
    sqliteConfig.setReadOnly(true)
    val sqlite = DriverManager.getConnection(s"jdbc:sqlite:$dbFile", sqliteConfig.toProperties)
    val channels = using(sqlite.createStatement) { statement =>
      statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=0")
        .mapCodec(stateDataCodec)
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

  test("tx pruning") { _ =>
    val txids = (for (_ <- 0 until 20) yield randomBytes32()).toList
    val knownTxids = Set(txids(1), txids(3), txids(4), txids(6), txids(9), txids(12), txids(13))

    val bitcoinClient = new ExtendedBitcoinClient(null) {
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
