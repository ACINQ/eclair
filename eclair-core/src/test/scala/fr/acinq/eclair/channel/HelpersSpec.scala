/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.channel

import akka.testkit.{TestFSMRef, TestProbe}
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.TestUtils.NoLoggingDiagnostics
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingSpentTriggered
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher.PublishReplaceableTx
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestKitBaseClass, TimestampSecond, TimestampSecondLong, randomKey}
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.concurrent.duration._

class HelpersSpec extends TestKitBaseClass with AnyFunSuiteLike with ChannelStateTestsBase {

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  test("compute refresh delay") {
    import org.scalatest.matchers.should.Matchers._
    implicit val log: akka.event.DiagnosticLoggingAdapter = NoLoggingDiagnostics
    Helpers.nextChannelUpdateRefresh(1544400000 unixsec).toSeconds should equal(0)
    Helpers.nextChannelUpdateRefresh(TimestampSecond.now() - 9.days).toSeconds should equal(24 * 3600L +- 100)
    Helpers.nextChannelUpdateRefresh(TimestampSecond.now() - 3.days).toSeconds should equal(7 * 24 * 3600L +- 100)
    Helpers.nextChannelUpdateRefresh(TimestampSecond.now()).toSeconds should equal(10 * 24 * 3600L +- 100)
  }

  case class Fixture(alice: TestFSMRef[ChannelState, ChannelData, Channel],
                     aliceHtlcs: Set[UpdateAddHtlc],
                     aliceHtlcSuccessTxs: Seq[HtlcSuccessTx],
                     aliceHtlcTimeoutTxs: Seq[HtlcTimeoutTx],
                     bob: TestFSMRef[ChannelState, ChannelData, Channel],
                     bobHtlcs: Set[UpdateAddHtlc],
                     bobClaimHtlcSuccessTxs: Seq[ClaimHtlcSuccessTx],
                     bobClaimHtlcTimeoutTxs: Seq[ClaimHtlcTimeoutTx],
                     probe: TestProbe)

  def setupHtlcs(testTags: Set[String] = Set.empty): Fixture = {
    val probe = TestProbe()
    val setup = init()
    reachNormal(setup, testTags)
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // We have two identical HTLCs (MPP):
    val (_, htlca1a) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    val aliceMppCmd = CMD_ADD_HTLC(TestProbe().ref, 15_000_000 msat, htlca1a.paymentHash, htlca1a.cltvExpiry, htlca1a.onionRoutingPacket, None, Reputation.Score.max, None, Origin.Hot(TestProbe().ref, Upstream.Local(UUID.randomUUID())))
    val htlca1b = addHtlc(aliceMppCmd, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(16_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(500_000 msat, alice, bob, alice2bob, bob2alice) // below dust
    crossSign(alice, bob, alice2bob, bob2alice)
    // We have two identical HTLCs (MPP):
    val (_, htlcb1a) = addHtlc(17_000_000 msat, bob, alice, bob2alice, alice2bob)
    val bobMppCmd = CMD_ADD_HTLC(TestProbe().ref, 17_000_000 msat, htlcb1a.paymentHash, htlcb1a.cltvExpiry, htlcb1a.onionRoutingPacket, None, Reputation.Score.max, None, Origin.Hot(TestProbe().ref, Upstream.Local(UUID.randomUUID())))
    val htlcb1b = addHtlc(bobMppCmd, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(18_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(400_000 msat, bob, alice, bob2alice, alice2bob) // below dust
    crossSign(bob, alice, bob2alice, alice2bob)

    // Alice and Bob both know the preimage for only one of the two HTLCs they received.
    alice ! CMD_FULFILL_HTLC(htlcb2.id, rb2, None, replyTo_opt = Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
    bob ! CMD_FULFILL_HTLC(htlca2.id, ra2, None, replyTo_opt = Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Alice publishes her commitment.
    alice ! CMD_FORCECLOSE(probe.ref)
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
    val commitTx = alice2blockchain.expectFinalTxPublished("commit-tx")
    awaitCond(alice.stateName == CLOSING)

    val lcp = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    lcp.anchorOutput_opt.foreach(_ => alice2blockchain.expectReplaceableTxPublished[ClaimLocalAnchorTx])
    lcp.localOutput_opt.foreach(_ => alice2blockchain.expectFinalTxPublished("local-main-delayed"))
    // Alice is missing the preimage for 2 of the HTLCs she received.
    assert(lcp.htlcOutputs.size == 6)
    val htlcTxs = (0 until 4).map(_ => alice2blockchain.expectMsgType[PublishReplaceableTx]).map(_.txInfo).collect { case tx: SignedHtlcTx => tx }
    alice2blockchain.expectWatchTxConfirmed(commitTx.tx.txid)
    val htlcTimeoutTxs = htlcTxs.collect { case tx: HtlcTimeoutTx => tx }
    assert(htlcTimeoutTxs.length == 3)
    assert(lcp.outgoingHtlcs.values.toSet == htlcTimeoutTxs.map(_.htlcId).toSet)
    val htlcSuccessTxs = htlcTxs.collect { case tx: HtlcSuccessTx => tx }
    assert(htlcSuccessTxs.length == 1)
    assert(lcp.incomingHtlcs.values.toSet.contains(htlcSuccessTxs.head.htlcId))

    // Bob detects Alice's force-close.
    bob ! WatchFundingSpentTriggered(commitTx.tx)
    awaitCond(bob.stateName == CLOSING)

    val rcp = bob.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get
    rcp.anchorOutput_opt.foreach(_ => bob2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx])
    rcp.localOutput_opt.foreach(_ => bob2blockchain.expectFinalTxPublished("remote-main-delayed"))
    // Bob is missing the preimage for 2 of the HTLCs she received.
    assert(rcp.htlcOutputs.size == 6)
    val claimHtlcTxs = (0 until 4).map(_ => bob2blockchain.expectMsgType[PublishReplaceableTx])
    bob2blockchain.expectWatchTxConfirmed(commitTx.tx.txid)
    val claimHtlcTimeoutTxs = claimHtlcTxs.map(_.txInfo).collect { case tx: ClaimHtlcTimeoutTx => tx }
    assert(claimHtlcTimeoutTxs.length == 3)
    assert(rcp.outgoingHtlcs.values.toSet == claimHtlcTimeoutTxs.map(_.htlcId).toSet)
    val claimHtlcSuccessTxs = claimHtlcTxs.map(_.txInfo).collect { case tx: ClaimHtlcSuccessTx => tx }
    assert(claimHtlcSuccessTxs.length == 1)
    assert(rcp.incomingHtlcs.values.toSet.contains(claimHtlcSuccessTxs.head.htlcId))

    Fixture(alice, Set(htlca1a, htlca1b, htlca2), htlcSuccessTxs, htlcTimeoutTxs, bob, Set(htlcb1a, htlcb1b, htlcb2), claimHtlcSuccessTxs, claimHtlcTimeoutTxs, probe)
  }

  def findTimedOutHtlcs(f: Fixture): Unit = {
    import f._

    val localKeys = alice.underlyingActor.channelKeys
    val localCommitment = alice.stateData.asInstanceOf[DATA_CLOSING].commitments.latest
    val localCommit = localCommitment.localCommit
    val remoteKeys = bob.underlyingActor.channelKeys
    val remoteCommitment = bob.stateData.asInstanceOf[DATA_CLOSING].commitments.latest
    val remoteCommit = remoteCommitment.remoteCommit

    // Claim-HTLC txs can be modified to pay more (or less) fees by changing the output amount.
    val bobClaimHtlcTimeoutTxsModifiedFees = bobClaimHtlcTimeoutTxs.map(tx => tx.modify(_.tx.txOut).setTo(Seq(tx.tx.txOut.head.copy(amount = 5000 sat))))
    val bobClaimHtlcSuccessTxsModifiedFees = bobClaimHtlcSuccessTxs.map(tx => tx.modify(_.tx.txOut).setTo(Seq(tx.tx.txOut.head.copy(amount = 5000 sat))))

    val aliceTimedOutHtlcs = aliceHtlcTimeoutTxs.map(htlcTimeout => {
      val timedOutHtlcs = Closing.trimmedOrTimedOutHtlcs(localKeys, localCommitment, localCommit, htlcTimeout.tx)
      assert(timedOutHtlcs.size == 1)
      timedOutHtlcs.head
    })
    assert(aliceTimedOutHtlcs.toSet == aliceHtlcs)

    val bobTimedOutHtlcs = bobClaimHtlcTimeoutTxs.map(claimHtlcTimeout => {
      val timedOutHtlcs = Closing.trimmedOrTimedOutHtlcs(remoteKeys, remoteCommitment, remoteCommit, claimHtlcTimeout.tx)
      assert(timedOutHtlcs.size == 1)
      timedOutHtlcs.head
    })
    assert(bobTimedOutHtlcs.toSet == bobHtlcs)

    val bobTimedOutHtlcs2 = bobClaimHtlcTimeoutTxsModifiedFees.map(claimHtlcTimeout => {
      val timedOutHtlcs = Closing.trimmedOrTimedOutHtlcs(remoteKeys, remoteCommitment, remoteCommit, claimHtlcTimeout.tx)
      assert(timedOutHtlcs.size == 1)
      timedOutHtlcs.head
    })
    assert(bobTimedOutHtlcs2.toSet == bobHtlcs)

    aliceHtlcSuccessTxs.foreach(htlcSuccess => assert(Closing.trimmedOrTimedOutHtlcs(localKeys, localCommitment, localCommit, htlcSuccess.sign()).isEmpty))
    aliceHtlcSuccessTxs.foreach(htlcSuccess => assert(Closing.trimmedOrTimedOutHtlcs(remoteKeys, remoteCommitment, remoteCommit, htlcSuccess.sign()).isEmpty))
    bobClaimHtlcSuccessTxs.foreach(claimHtlcSuccess => assert(Closing.trimmedOrTimedOutHtlcs(localKeys, localCommitment, localCommit, claimHtlcSuccess.sign()).isEmpty))
    bobClaimHtlcSuccessTxsModifiedFees.foreach(claimHtlcSuccess => assert(Closing.trimmedOrTimedOutHtlcs(localKeys, localCommitment, localCommit, claimHtlcSuccess.sign()).isEmpty))
    bobClaimHtlcSuccessTxs.foreach(claimHtlcSuccess => assert(Closing.trimmedOrTimedOutHtlcs(remoteKeys, remoteCommitment, remoteCommit, claimHtlcSuccess.sign()).isEmpty))
    bobClaimHtlcSuccessTxsModifiedFees.foreach(claimHtlcSuccess => assert(Closing.trimmedOrTimedOutHtlcs(remoteKeys, remoteCommitment, remoteCommit, claimHtlcSuccess.sign()).isEmpty))
    aliceHtlcTimeoutTxs.foreach(htlcTimeout => assert(Closing.trimmedOrTimedOutHtlcs(remoteKeys, remoteCommitment, remoteCommit, htlcTimeout.sign()).isEmpty))
    bobClaimHtlcTimeoutTxs.foreach(claimHtlcTimeout => assert(Closing.trimmedOrTimedOutHtlcs(localKeys, localCommitment, localCommit, claimHtlcTimeout.sign()).isEmpty))
  }

  test("find timed out htlcs (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) {
    findTimedOutHtlcs(setupHtlcs(Set(ChannelStateTestsTags.AnchorOutputs)))
  }

  test("find timed out htlcs (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) {
    findTimedOutHtlcs(setupHtlcs(Set(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)))
  }

  test("check closing tx amounts above dust") {
    val p2pkhBelowDust = Seq(TxOut(545 sat, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000") :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil))
    val p2shBelowDust = Seq(TxOut(539 sat, OP_HASH160 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000") :: OP_EQUAL :: Nil))
    val p2wpkhBelowDust = Seq(TxOut(293 sat, OP_0 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000") :: Nil))
    val p2wshBelowDust = Seq(TxOut(329 sat, OP_0 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000000000000000000000000000") :: Nil))
    val futureSegwitBelowDust = Seq(TxOut(353 sat, OP_3 :: OP_PUSHDATA(hex"0000000000") :: Nil))
    val allOutputsAboveDust = Seq(
      TxOut(546 sat, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000") :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil),
      TxOut(540 sat, OP_HASH160 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000") :: OP_EQUAL :: Nil),
      TxOut(294 sat, OP_0 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000") :: Nil),
      TxOut(330 sat, OP_0 :: OP_PUSHDATA(hex"0000000000000000000000000000000000000000000000000000000000000000") :: Nil),
      TxOut(354 sat, OP_3 :: OP_PUSHDATA(hex"0000000000") :: Nil),
      TxOut(0 sat, OP_RETURN :: OP_PUSHDATA(hex"deadbeef") :: Nil),
    )

    def toClosingTx(txOut: Seq[TxOut]): ClosingTx = {
      ClosingTx(InputInfo(OutPoint(TxId(ByteVector32.Zeroes), 0), TxOut(1000 sat, Nil), ByteVector.empty), Transaction(2, Nil, txOut, 0), None)
    }

    assert(Closing.MutualClose.checkClosingDustAmounts(toClosingTx(allOutputsAboveDust)))
    assert(!Closing.MutualClose.checkClosingDustAmounts(toClosingTx(p2pkhBelowDust)))
    assert(!Closing.MutualClose.checkClosingDustAmounts(toClosingTx(p2shBelowDust)))
    assert(!Closing.MutualClose.checkClosingDustAmounts(toClosingTx(p2wpkhBelowDust)))
    assert(!Closing.MutualClose.checkClosingDustAmounts(toClosingTx(p2wshBelowDust)))
    assert(!Closing.MutualClose.checkClosingDustAmounts(toClosingTx(futureSegwitBelowDust)))
  }

  test("tell closing type") {
    val commitments = CommitmentsSpec.makeCommitments(10000 msat, 15000 msat)
    val tx1 :: tx2 :: tx3 :: tx4 :: tx5 :: tx6 :: Nil = List(
      Transaction.read("010000000110f01d4a4228ef959681feb1465c2010d0135be88fd598135b2e09d5413bf6f1000000006a473044022074658623424cebdac8290488b76f893cfb17765b7a3805e773e6770b7b17200102202892cfa9dda662d5eac394ba36fcfd1ea6c0b8bb3230ab96220731967bbdb90101210372d437866d9e4ead3d362b01b615d24cc0d5152c740d51e3c55fb53f6d335d82ffffffff01408b0700000000001976a914678db9a7caa2aca887af1177eda6f3d0f702df0d88ac00000000"),
      Transaction.read("0100000001be43e9788523ed4de0b24a007a90009bc25e667ddac0e9ee83049be03e220138000000006b483045022100f74dd6ad3e6a00201d266a0ed860a6379c6e68b473970423f3fc8a15caa1ea0f022065b4852c9da230d9e036df743cb743601ca5229e1cb610efdd99769513f2a2260121020636de7755830fb4a3f136e97ecc6c58941611957ba0364f01beae164b945b2fffffffff0150f80c000000000017a9146809053148799a10480eada3d56d15edf4a648c88700000000"),
      Transaction.read("0100000002b8682539550b3182966ecaca3d1fd5b2a96d0966a0fded143aaf771cbaf4222b000000006b483045022100c4484511ea7d9cf989797ca98e403c93372ded754ce30737af4914a222c84e8e022011648b42f8756ef4b83aa4f49e6b77f86ce7c54e1b70f25a16a94c4551c99cff012102506d400d2168a4a272b026d8b95ecb822cccd60277fb7268a6873fef0a85fe96ffffffff5a68052b6c23f6f718e09ffe56378ee90ad438b94c99b398c8d9e581a3c049d0300000006b483045022100d1b0eebc8250ebbb2d692c1e293260387b748115cf4cf892891ca4a1e81029cf02202fb5daa7647355e2c86d3f8bcfde7691a163f0dd99a002aca97f0a17cc72c5da012102fe1ec7be2f1e974c7e75932c0187f61667fa2825c4f79ccb964a83f48cce442cffffffff02ba100000000000001976a914a14c305babbd3d6984a20899f078980f078d433288acc8370800000000001976a914e0b4609a38d1a4dd1196f8c66e879b4923f9ea7388ac00000000"),
      Transaction.read("0200000001c8a8934fb38a44b969528252bc37be66ee166c7897c57384d1e561449e110c93010000006b483045022100dc6c50f445ed53d2fb41067fdcb25686fe79492d90e6e5db43235726ace247210220773d35228af0800c257970bee9cf75175d75217de09a8ecd83521befd040c4ca012102082b751372fe7e3b012534afe0bb8d1f2f09c724b1a10a813ce704e5b9c217ccfdffffff0247ba2300000000001976a914f97a7641228e6b17d4b0b08252ae75bd62a95fe788ace3de24000000000017a914a9fefd4b9a9282a1d7a17d2f14ac7d1eb88141d287f7d50800"),
      Transaction.read("010000000235a2f5c4fd48672534cce1ac063047edc38683f43c5a883f815d6026cb5f8321020000006a47304402206be5fd61b1702599acf51941560f0a1e1965aa086634b004967747f79788bd6e022002f7f719a45b8b5e89129c40a9d15e4a8ee1e33be3a891cf32e859823ecb7a510121024756c5adfbc0827478b0db042ce09d9b98e21ad80d036e73bd8e7f0ecbc254a2ffffffffb2387d3125bb8c84a2da83f4192385ce329283661dfc70191f4112c67ce7b4d0000000006b483045022100a2c737eab1c039f79238767ccb9bb3e81160e965ef0fc2ea79e8360c61b7c9f702202348b0f2c0ea2a757e25d375d9be183200ce0a79ec81d6a4ebb2ae4dc31bc3c9012102db16a822e2ec3706c58fc880c08a3617c61d8ef706cc8830cfe4561d9a5d52f0ffffffff01808d5b00000000001976a9141210c32def6b64d0d77ba8d99adeb7e9f91158b988ac00000000"),
      Transaction.read("0100000001b14ba6952c83f6f8c382befbf4e44270f13e479d5a5ff3862ac3a112f103ff2a010000006b4830450221008b097fd69bfa3715fc5e119a891933c091c55eabd3d1ddae63a1c2cc36dc9a3e02205666d5299fa403a393bcbbf4b05f9c0984480384796cdebcf69171674d00809c01210335b592484a59a44f40998d65a94f9e2eecca47e8d1799342112a59fc96252830ffffffff024bf308000000000017a914440668d018e5e0ba550d6e042abcf726694f515c8798dd1801000000001976a91453a503fe151dd32e0503bd9a2fbdbf4f9a3af1da88ac00000000")
    ).map(tx => ClosingTx(InputInfo(tx.txIn.head.outPoint, TxOut(10_000 sat, Nil), ByteVector.empty), tx, None))

    // only mutual close
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = tx1 :: tx2 :: tx3 :: Nil,
        mutualClosePublished = tx2 :: tx3 :: Nil,
        localCommitPublished = None,
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished = Nil)
    ).isEmpty)

    // mutual + local close, but local commit tx isn't confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = tx1 :: Nil,
        mutualClosePublished = tx1 :: Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx2.tx,
          localOutput_opt = Some(tx3.input.outPoint),
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map.empty
        )),
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished = Nil)
    ).isEmpty)

    // mutual + local close, local commit tx confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = tx1 :: Nil,
        mutualClosePublished = tx1 :: Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx2.tx,
          localOutput_opt = Some(tx3.input.outPoint),
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map(tx2.input.outPoint -> tx2.tx)
        )),
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished = Nil)
    ).exists(_.isInstanceOf[Closing.LocalClose]))

    // local close + remote close, none is confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = Nil,
        mutualClosePublished = Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx2.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map.empty
        )),
        remoteCommitPublished = Some(RemoteCommitPublished(
          commitTx = tx3.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          irrevocablySpent = Map.empty
        )),
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished = Nil)
    ).isEmpty)

    // mutual + local + remote close, remote commit tx confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = tx1 :: Nil,
        mutualClosePublished = tx1 :: Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx2.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map.empty
        )),
        remoteCommitPublished = Some(RemoteCommitPublished(
          commitTx = tx3.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          irrevocablySpent = Map(tx3.input.outPoint -> tx3.tx)
        )),
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished = Nil)
    ).exists(_.isInstanceOf[Closing.CurrentRemoteClose]))

    // mutual + local + remote + next remote close, next remote commit tx confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments
          .modify(_.active.at(0).nextRemoteCommit_opt).setTo(Some(NextRemoteCommit(null, commitments.active.head.remoteCommit)))
          .modify(_.remoteNextCommitInfo).setTo(Left(WaitForRev(7))),
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = tx1 :: Nil,
        mutualClosePublished = tx1 :: Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx2.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map.empty
        )),
        remoteCommitPublished = Some(RemoteCommitPublished(
          commitTx = tx3.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          irrevocablySpent = Map.empty
        )),
        nextRemoteCommitPublished = Some(RemoteCommitPublished(
          commitTx = tx4.tx,
          localOutput_opt = Some(tx5.input.outPoint),
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          irrevocablySpent = Map(tx4.input.outPoint -> tx4.tx)
        )),
        futureRemoteCommitPublished = None,
        revokedCommitPublished = Nil)
    ).exists(_.isInstanceOf[Closing.NextRemoteClose]))

    // future remote close, not confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = Nil,
        mutualClosePublished = Nil,
        localCommitPublished = None,
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = Some(RemoteCommitPublished(
          commitTx = tx4.tx,
          localOutput_opt = Some(tx5.input.outPoint),
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          irrevocablySpent = Map.empty
        )),
        revokedCommitPublished = Nil)
    ).isEmpty)

    // future remote close, confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = Nil,
        mutualClosePublished = Nil,
        localCommitPublished = None,
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = Some(RemoteCommitPublished(
          commitTx = tx4.tx,
          localOutput_opt = Some(tx5.input.outPoint),
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          irrevocablySpent = Map(tx4.input.outPoint -> tx4.tx)
        )),
        revokedCommitPublished = Nil)
    ).exists(_.isInstanceOf[Closing.RecoveryClose]))

    // local close + revoked close, none confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = Nil,
        mutualClosePublished = Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx1.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map.empty
        )),
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished =
          RevokedCommitPublished(
            commitTx = tx2.tx,
            localOutput_opt = Some(tx3.input.outPoint),
            remoteOutput_opt = None,
            htlcOutputs = Set.empty,
            htlcDelayedOutputs = Set.empty,
            irrevocablySpent = Map.empty
          ) ::
            RevokedCommitPublished(
              commitTx = tx4.tx,
              localOutput_opt = Some(tx5.input.outPoint),
              remoteOutput_opt = None,
              htlcOutputs = Set.empty,
              htlcDelayedOutputs = Set.empty,
              irrevocablySpent = Map.empty
            ) ::
            RevokedCommitPublished(
              commitTx = tx6.tx,
              localOutput_opt = None,
              remoteOutput_opt = None,
              htlcOutputs = Set.empty,
              htlcDelayedOutputs = Set.empty,
              irrevocablySpent = Map.empty
            ) :: Nil
      )
    ).isEmpty)

    // local close + revoked close, one revoked confirmed
    assert(Closing.isClosingTypeAlreadyKnown(
      DATA_CLOSING(
        commitments = commitments,
        waitingSince = BlockHeight(0),
        finalScriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey)),
        mutualCloseProposed = Nil,
        mutualClosePublished = Nil,
        localCommitPublished = Some(LocalCommitPublished(
          commitTx = tx1.tx,
          localOutput_opt = None,
          anchorOutput_opt = None,
          incomingHtlcs = Map.empty,
          outgoingHtlcs = Map.empty,
          htlcDelayedOutputs = Set.empty,
          irrevocablySpent = Map.empty
        )),
        remoteCommitPublished = None,
        nextRemoteCommitPublished = None,
        futureRemoteCommitPublished = None,
        revokedCommitPublished =
          RevokedCommitPublished(
            commitTx = tx2.tx,
            localOutput_opt = Some(tx3.input.outPoint),
            remoteOutput_opt = None,
            htlcOutputs = Set.empty,
            htlcDelayedOutputs = Set.empty,
            irrevocablySpent = Map.empty
          ) ::
            RevokedCommitPublished(
              commitTx = tx4.tx,
              localOutput_opt = Some(tx5.input.outPoint),
              remoteOutput_opt = None,
              htlcOutputs = Set.empty,
              htlcDelayedOutputs = Set.empty,
              irrevocablySpent = Map(tx4.input.outPoint -> tx4.tx)
            ) ::
            RevokedCommitPublished(
              commitTx = tx6.tx,
              localOutput_opt = None,
              remoteOutput_opt = None,
              htlcOutputs = Set.empty,
              htlcDelayedOutputs = Set.empty,
              irrevocablySpent = Map.empty
            ) :: Nil
      )
    ).exists(_.isInstanceOf[Closing.RevokedClose]))
  }

}

