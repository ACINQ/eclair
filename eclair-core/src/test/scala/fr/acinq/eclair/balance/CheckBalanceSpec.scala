package fr.acinq.eclair.balance

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.balance.CheckBalance.{MainAndHtlcBalance, OffChainBalance}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{apply => _, _}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher.PublishReplaceableTx
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.pg.PgUtils.using
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions.{ClaimHtlcSuccessTx, ClaimHtlcTimeoutTx, ClaimRemoteAnchorTx}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import fr.acinq.eclair.wire.protocol.{CommitSig, RevokeAndAck}
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import org.sqlite.SQLiteConfig

import java.io.File
import java.sql.DriverManager
import scala.concurrent.duration.DurationInt

class CheckBalanceSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    within(30 seconds) {
      reachNormal(setup, test.tags)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("do not deduplicate htlc amounts") { f =>
    import f._

    // We add 3 identical outgoing htlcs Alice -> Bob.
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val expected1 = MainAndHtlcBalance(
      toLocal = (TestConstants.fundingSatoshis - TestConstants.initiatorPushAmount - 30_000_000.msat).truncateToSatoshi,
      htlcs = 0 sat, // outgoing HTLCs are considered paid
    )
    assert(CheckBalance.computeOffChainBalance(Seq(alice.stateData.asInstanceOf[DATA_NORMAL]), recentlySpentInputs = Set.empty).normal == expected1)

    // We add 3 identical incoming htlcs Bob -> Alice.
    addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    val expected2 = expected1.copy(htlcs = 60_000 sat)
    assert(CheckBalance.computeOffChainBalance(Seq(alice.stateData.asInstanceOf[DATA_NORMAL]), recentlySpentInputs = Set.empty).normal == expected2)

    // We add our balance to an existing off-chain balance.
    val previous = OffChainBalance(normal = MainAndHtlcBalance(toLocal = 100_000 sat, htlcs = 25_000 sat))
    val expected3 = expected2.copy(toLocal = expected2.toLocal + 100_000.sat, htlcs = 85_000 sat)
    assert(previous.addChannelBalance(alice.stateData.asInstanceOf[DATA_NORMAL], recentlySpentInputs = Set.empty).normal == expected3)
  }

  test("take in-flight signed fulfills into account") { f =>
    import f._

    val (preimage, htlc) = addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlc.id, preimage, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]

    val expected = MainAndHtlcBalance(
      toLocal = TestConstants.initiatorPushAmount.truncateToSatoshi,
      htlcs = htlc.amountMsat.truncateToSatoshi
    )
    assert(CheckBalance.computeOffChainBalance(Seq(bob.stateData.asInstanceOf[DATA_NORMAL]), recentlySpentInputs = Set.empty).normal == expected)
  }

  test("channel closing with unpublished closing tx", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._

    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val expected = MainAndHtlcBalance(
      toLocal = (TestConstants.fundingSatoshis - TestConstants.initiatorPushAmount).truncateToSatoshi,
      htlcs = 0 sat,
    )
    assert(CheckBalance.computeOffChainBalance(Seq(alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE]), recentlySpentInputs = Set.empty).negotiating == expected)
  }

  test("channel closing with published closing tx", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._

    mutualClose(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
    val closingTxInput = alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE].commitments.latest.fundingInput
    val expected = MainAndHtlcBalance(toLocal = 0 sat, htlcs = 0 sat)
    assert(CheckBalance.computeOffChainBalance(Seq(alice.stateData.asInstanceOf[DATA_NEGOTIATING_SIMPLE]), recentlySpentInputs = Set(closingTxInput)).negotiating == expected)
  }

  test("channel closed with remote commit tx", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // We add 3 htlcs Alice -> Bob (one of them below dust) and 2 htlcs Bob -> Alice
    addHtlc(250_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (ra, htlca) = addHtlc(100_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    val (rb, htlcb) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(55_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // And fulfill one htlc in each direction without signing a new commit tx.
    fulfillHtlc(htlca.id, ra, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlcb.id, rb, alice, bob, alice2bob, bob2alice)

    // Bob publishes his current commit tx.
    val bobCommitTx = bob.signCommitTx()
    assert(bobCommitTx.txOut.size == 8) // two anchor outputs, two main outputs and 4 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    // In response to that, alice publishes her claim txs.
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMain = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    val mainAmount = bobCommitTx.txOut(claimMain.input.index.toInt).amount
    val claimHtlcTxs = (1 to 3).map(_ => alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo)
    assert(claimHtlcTxs.collect { case tx: ClaimHtlcSuccessTx => tx }.size == 1)
    assert(claimHtlcTxs.collect { case tx: ClaimHtlcTimeoutTx => tx }.size == 2)
    alice ! WatchTxConfirmedTriggered(BlockHeight(600_000), 5, bobCommitTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.exists(_.isConfirmed))

    // We already have an off-chain balance from other channels.
    val balance = OffChainBalance(
      normal = MainAndHtlcBalance(toLocal = 100_000_000 sat, htlcs = 250_000 sat),
      closing = MainAndHtlcBalance(toLocal = 50_000_000 sat, htlcs = 100_000 sat),
    )
    // We add our main balance and the amount of the incoming HTLCs.
    val expected1 = balance.copy(closing = MainAndHtlcBalance(toLocal = 50_000_000.sat + mainAmount, htlcs = 100_000.sat + 50_000.sat + 55_000.sat))
    val balance1 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance1 == expected1)
    // When our transactions are in the mempool or recently confirmed, we stop including them in our off-chain balance.
    val expected2 = balance.copy(closing = MainAndHtlcBalance(toLocal = 50_000_000.sat, htlcs = 100_000.sat + 50_000.sat + 55_000.sat))
    val balance2 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set(claimMain.input))
    assert(balance2 == expected2)
    val htlcOutpoint = claimHtlcTxs.collectFirst { case tx: ClaimHtlcSuccessTx if tx.htlcId == htlcb.id => tx.input.outPoint }.get
    val expected3 = balance.copy(closing = MainAndHtlcBalance(toLocal = 50_000_000.sat, htlcs = 100_000.sat + 55_000.sat))
    val balance3 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set(claimMain.input, htlcOutpoint))
    assert(balance3 == expected3)
    // When our HTLC transaction confirms, we stop including it in our off-chain balance: it appears in our on-chain balance.
    alice ! WatchTxConfirmedTriggered(BlockHeight(601_000), 2, claimHtlcTxs.collectFirst { case tx: ClaimHtlcSuccessTx => tx }.get.tx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.irrevocablySpent.size == 2)
    val expected4 = balance.copy(closing = MainAndHtlcBalance(toLocal = 50_000_000.sat + mainAmount, htlcs = 100_000.sat + 55_000.sat))
    val balance4 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance4 == expected4)
  }

  test("channel closed with next remote commit tx", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // We add 3 htlcs Alice -> Bob (one of them below dust) and 2 htlcs Bob -> Alice.
    addHtlc(250_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(100_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    val (_, htlcb) = addHtlc(55_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Alice fails one of her incoming htlcs.
    failHtlc(htlcb.id, alice, bob, alice2bob, bob2alice)
    // Alice signs but we intercept bob's revocation.
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]

    // Bob publishes his next commit tx.
    val bobCommitTx = bob.signCommitTx()
    assert(bobCommitTx.txOut.size == 7) // two anchor outputs, two main outputs and 3 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    // In response to that, alice publishes her claim txs
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMain = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    val mainAmount = bobCommitTx.txOut(claimMain.input.index.toInt).amount
    (1 to 2).map(_ => alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx])
    alice ! WatchTxConfirmedTriggered(BlockHeight(600_000), 5, bobCommitTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.exists(_.isConfirmed))

    // We already have an off-chain balance from other channels.
    val balance = OffChainBalance(
      negotiating = MainAndHtlcBalance(toLocal = 200_000_000 sat, htlcs = 150_000 sat),
      closing = MainAndHtlcBalance(toLocal = 20_000_000 sat, htlcs = 0 sat),
    )
    // We add our main balance and the amount of the remaining incoming HTLC.
    val expected1 = balance.copy(closing = MainAndHtlcBalance(toLocal = 20_000_000.sat + mainAmount, htlcs = 50_000.sat))
    val balance1 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance1 == expected1)
    // We deduplicate our main balance with our on-chain balance.
    val expected2 = balance.copy(closing = MainAndHtlcBalance(toLocal = 20_000_000.sat, htlcs = 50_000.sat))
    val balance2 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set(claimMain.input))
    assert(balance2 == expected2)
  }

  test("channel closed with local commit tx") { f =>
    import f._

    // We add 2 htlcs Alice -> Bob and 4 htlcs Bob -> Alice (one of them below dust).
    addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(60_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (_, htlcb1) = addHtlc(35_000_000 msat, bob, alice, bob2alice, alice2bob)
    val (rb1, htlcb2) = addHtlc(30_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(50_000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb3) = addHtlc(25_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Alice has the preimage for 2 of her incoming HTLCs.
    fulfillHtlc(htlcb2.id, rb1, alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlcb3.id, rb2, alice, bob, alice2bob, bob2alice)

    // Alice publishes her commit tx.
    val (localCommitPublished, localClosingTxs) = localClose(alice, alice2blockchain, htlcSuccessCount = 2, htlcTimeoutCount = 2)
    alice ! WatchTxConfirmedTriggered(BlockHeight(750_000), 1, localCommitPublished.commitTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.exists(_.isConfirmed))
    val mainTx = localClosingTxs.mainTx_opt.get
    val mainBalance = localCommitPublished.localOutput_opt.map(o => localCommitPublished.commitTx.txOut(o.index.toInt).amount).get

    // We already have an off-chain balance from other channels.
    val balance = OffChainBalance(
      waitForChannelReady = 500_000 sat,
      shutdown = MainAndHtlcBalance(toLocal = 100_000_000 sat, htlcs = 0 sat),
      closing = MainAndHtlcBalance(toLocal = 250_000 sat, htlcs = 50_000 sat),
    )
    // We add our main balance and the amount of the incoming HTLCs.
    val expected1 = balance.copy(closing = MainAndHtlcBalance(toLocal = 250_000.sat + mainBalance, htlcs = 50_000.sat + 35_000.sat + 30_000.sat + 25_000.sat))
    val balance1 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance1 == expected1)
    // If our main transaction is published, we don't include it.
    val expected1b = balance.copy(closing = MainAndHtlcBalance(toLocal = 250_000.sat, htlcs = 50_000.sat + 35_000.sat + 30_000.sat + 25_000.sat))
    val balance1b = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = mainTx.txIn.map(_.outPoint).toSet)
    assert(balance1b == expected1b)

    // The incoming HTLCs for which Alice has the preimage confirm: we keep including them until the 3rd-stage transaction confirms.
    assert(localClosingTxs.htlcSuccessTxs.size == 2)
    val htlcDelayedTxs = localClosingTxs.htlcSuccessTxs.map(tx => {
      alice ! WatchTxConfirmedTriggered(BlockHeight(760_000), 3, tx)
      val htlcDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
      alice2blockchain.expectWatchOutputSpent(htlcDelayedTx.input)
      htlcDelayedTx
    })
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.exists(_.htlcDelayedOutputs.size == 2))
    assert(balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty) == expected1)
    assert(balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = mainTx.txIn.map(_.outPoint).toSet) == expected1b)

    // Bob claims the remaining incoming HTLC using his HTLC-timeout transaction: we remove it from our balance.
    val (remoteCommitPublished, remoteClosingTxs) = remoteClose(localCommitPublished.commitTx, bob, bob2blockchain, htlcTimeoutCount = 3)
    val bobHtlcTimeoutTx = remoteCommitPublished.outgoingHtlcs
      .collectFirst { case (outpoint, htlcId) if htlcId == htlcb1.id => outpoint }
      .flatMap(outpoint => remoteClosingTxs.htlcTimeoutTxs.find(_.txIn.head.outPoint == outpoint))
      .get
    alice ! WatchTxConfirmedTriggered(BlockHeight(760_010), 0, bobHtlcTimeoutTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.exists(_.irrevocablySpent.contains(bobHtlcTimeoutTx.txIn.head.outPoint)))
    val expected2 = balance.copy(closing = MainAndHtlcBalance(toLocal = 250_000.sat + mainBalance, htlcs = 50_000.sat + 30_000.sat + 25_000.sat))
    val balance2 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance2 == expected2)

    // Alice's 3rd-stage transactions are published in our mempool: we stop including them in our off-chain balance, they will appear in our on-chain balance.
    val expected3 = balance.copy(closing = MainAndHtlcBalance(toLocal = 250_000.sat + mainBalance, htlcs = 50_000.sat))
    val balance3 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = htlcDelayedTxs.map(_.input).toSet)
    assert(balance3 == expected3)

    // Alice's 3rd-stage transactions confirm: we stop including them in our off-chain balance, they will appear in our on-chain balance.
    htlcDelayedTxs.foreach(txInfo => alice ! WatchTxConfirmedTriggered(BlockHeight(765_000), 3, txInfo.tx))
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.exists(lcp => htlcDelayedTxs.map(_.input).forall(o => lcp.irrevocablySpent.contains(o))))
    val expected4 = balance.copy(closing = MainAndHtlcBalance(toLocal = 250_000.sat + mainBalance, htlcs = 50_000.sat))
    val balance4 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance4 == expected4)

    // Alice's main transaction confirms: we stop including it in our off-chain balance, it will appear in our on-chain balance.
    alice ! WatchTxConfirmedTriggered(BlockHeight(765_100), 2, mainTx)
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.exists(_.irrevocablySpent.contains(mainTx.txIn.head.outPoint)))
    val balance5 = balance.addChannelBalance(alice.stateData.asInstanceOf[DATA_CLOSING], recentlySpentInputs = Set.empty)
    assert(balance5 == balance)
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
    val res = CheckBalance.computeOffChainBalance(channels, recentlySpentInputs = Set.empty)
    println(res)
    println(res.total)
  }

}
