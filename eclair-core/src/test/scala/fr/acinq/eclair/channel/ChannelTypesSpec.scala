package fr.acinq.eclair.channel

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, OutPoint, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.{CommitSig, RevokeAndAck, UpdateAddHtlc}
import fr.acinq.eclair.{MilliSatoshiLong, TestKitBaseClass}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

class ChannelTypesSpec extends TestKitBaseClass with AnyFunSuiteLike with StateTestsHelperMethods {

  test("standard channel features include deterministic channel key path") {
    assert(!ChannelVersion.ZEROES.hasPubkeyKeyPath)
    assert(ChannelVersion.STANDARD.hasPubkeyKeyPath)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasStaticRemotekey)
    assert(ChannelVersion.STATIC_REMOTEKEY.hasPubkeyKeyPath)
  }

  test("anchor outputs includes static remote key") {
    assert(ChannelVersion.ANCHOR_OUTPUTS.hasPubkeyKeyPath)
    assert(ChannelVersion.ANCHOR_OUTPUTS.hasStaticRemotekey)
  }

  test("channel version determines commitment format") {
    assert(ChannelVersion.ZEROES.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(ChannelVersion.STANDARD.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(ChannelVersion.STATIC_REMOTEKEY.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(ChannelVersion.ANCHOR_OUTPUTS.commitmentFormat === Transactions.AnchorOutputsCommitmentFormat)
  }

  test("pick channel version based on local and remote features") {
    import fr.acinq.eclair.FeatureSupport._
    import fr.acinq.eclair.Features._
    import fr.acinq.eclair.{ActivatedFeature, Features}

    case class TestCase(localFeatures: Features, remoteFeatures: Features, expectedChannelVersion: ChannelVersion)
    val testCases = Seq(
      TestCase(Features.empty, Features.empty, ChannelVersion.STANDARD),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), Features.empty, ChannelVersion.STANDARD),
      TestCase(Features.empty, Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), ChannelVersion.STANDARD),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), ChannelVersion.STATIC_REMOTEKEY),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Mandatory))), ChannelVersion.STATIC_REMOTEKEY),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Optional), ActivatedFeature(AnchorOutputs, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Optional))), ChannelVersion.STATIC_REMOTEKEY),
      TestCase(Features(Set(ActivatedFeature(StaticRemoteKey, Mandatory), ActivatedFeature(AnchorOutputs, Optional))), Features(Set(ActivatedFeature(StaticRemoteKey, Optional), ActivatedFeature(AnchorOutputs, Optional))), ChannelVersion.ANCHOR_OUTPUTS)
    )

    for (testCase <- testCases) {
      assert(ChannelVersion.pickChannelVersion(testCase.localFeatures, testCase.remoteFeatures) === testCase.expectedChannelVersion)
    }
  }

  case class HtlcWithPreimage(preimage: ByteVector32, htlc: UpdateAddHtlc)

  case class Fixture(alice: TestFSMRef[State, Data, Channel], alicePendingHtlc: HtlcWithPreimage, bob: TestFSMRef[State, Data, Channel], bobPendingHtlc: HtlcWithPreimage, probe: TestProbe)

  private def setupClosingChannel(testTags: Set[String] = Set.empty): Fixture = {
    val probe = TestProbe()
    val setup = init()
    reachNormal(setup, testTags)
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    val (ra1, htlca1) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(16_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(500_000 msat, alice, bob, alice2bob, bob2alice) // below dust
    crossSign(alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(17_000_000 msat, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(18_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(400_000 msat, bob, alice, bob2alice, alice2bob) // below dust
    crossSign(bob, alice, bob2alice, alice2bob)

    // Alice and Bob both know the preimage for only one of the two HTLCs they received.
    alice ! CMD_FULFILL_HTLC(htlcb1.id, rb1, replyTo_opt = Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
    bob ! CMD_FULFILL_HTLC(htlca1.id, ra1, replyTo_opt = Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Alice publishes her commitment.
    alice ! CMD_FORCECLOSE(probe.ref)
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
    awaitCond(alice.stateName == CLOSING)

    // Bob detects it.
    bob ! WatchEventSpent(BITCOIN_FUNDING_SPENT, alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.commitTx)
    awaitCond(bob.stateName == CLOSING)

    Fixture(alice, HtlcWithPreimage(rb2, htlcb2), bob, HtlcWithPreimage(ra2, htlca2), TestProbe())
  }

  test("local commit published") {
    val f = setupClosingChannel()
    import f._

    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(aliceClosing.localCommitPublished.nonEmpty)
    val lcp = aliceClosing.localCommitPublished.get
    assert(lcp.commitTx.txOut.length === 6)
    assert(lcp.claimMainDelayedOutputTx.nonEmpty)
    assert(lcp.htlcTimeoutTxs.length === 2)
    assert(lcp.htlcSuccessTxs.length === 1) // we only have the preimage for 1 of the 2 non-dust htlcs
    assert(lcp.claimHtlcDelayedTxs.length === 3)
    assert(!lcp.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp, aliceClosing.commitments))

    // Commit tx has been confirmed.
    val lcp1 = Closing.updateLocalCommitPublished(lcp, lcp.commitTx)
    assert(lcp1.irrevocablySpent.nonEmpty)
    assert(lcp1.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp1, aliceClosing.commitments))

    // Main output has been confirmed.
    val lcp2 = Closing.updateLocalCommitPublished(lcp1, lcp.claimMainDelayedOutputTx.get)
    assert(lcp2.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp2, aliceClosing.commitments))

    val bobClosing = bob.stateData.asInstanceOf[DATA_CLOSING]
    assert(bobClosing.remoteCommitPublished.nonEmpty)
    val rcp = bobClosing.remoteCommitPublished.get

    // Scenario 1: our HTLC txs are confirmed, they claim the remaining HTLC
    {
      val lcp3 = (lcp.htlcSuccessTxs ++ lcp.htlcTimeoutTxs ++ lcp.claimHtlcDelayedTxs).foldLeft(lcp2) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!Closing.isLocalCommitDone(lcp3, aliceClosing.commitments))

      val theirClaimHtlcTimeout = rcp.claimHtlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != lcp.htlcSuccessTxs.head.txIn.head.outPoint).get
      val lcp4 = Closing.updateLocalCommitPublished(lcp3, theirClaimHtlcTimeout)
      assert(Closing.isLocalCommitDone(lcp4, aliceClosing.commitments))
    }

    // Scenario 2: our HTLC txs are confirmed and we claim the remaining HTLC
    {
      val lcp3 = (lcp.htlcSuccessTxs ++ lcp.htlcTimeoutTxs ++ lcp.claimHtlcDelayedTxs).foldLeft(lcp2) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!Closing.isLocalCommitDone(lcp3, aliceClosing.commitments))

      alice ! CMD_FULFILL_HTLC(alicePendingHtlc.htlc.id, alicePendingHtlc.preimage, replyTo_opt = Some(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      val aliceClosing1 = alice.stateData.asInstanceOf[DATA_CLOSING]
      val lcp4 = aliceClosing1.localCommitPublished.get.copy(irrevocablySpent = lcp3.irrevocablySpent)
      assert(lcp4.htlcSuccessTxs.length === 2)
      assert(lcp4.claimHtlcDelayedTxs.length === 4)
      val newHtlcSuccessTx = lcp4.htlcSuccessTxs.find(tx => tx.txid != lcp.htlcSuccessTxs.head.txid).get
      val newClaimHtlcDelayedTx = lcp4.claimHtlcDelayedTxs.find(tx => tx.txIn.head.outPoint.txid === newHtlcSuccessTx.txid).get

      val lcp5 = Closing.updateLocalCommitPublished(lcp4, newHtlcSuccessTx)
      assert(!Closing.isLocalCommitDone(lcp5, aliceClosing1.commitments))

      val lcp6 = Closing.updateLocalCommitPublished(lcp5, newClaimHtlcDelayedTx)
      assert(Closing.isLocalCommitDone(lcp6, aliceClosing1.commitments))
    }

    // Scenario 3: they fulfill one of the HTLCs we sent them
    {
      val lcp3 = (lcp.htlcSuccessTxs ++ rcp.claimHtlcSuccessTxs).foldLeft(lcp2) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!Closing.isLocalCommitDone(lcp3, aliceClosing.commitments))

      val remainingHtlcTimeoutTxs = lcp.htlcTimeoutTxs.filter(tx => tx.txIn.head.outPoint != rcp.claimHtlcSuccessTxs.head.txIn.head.outPoint)
      val claimHtlcDelayedTxs = lcp.claimHtlcDelayedTxs.filter(tx => (remainingHtlcTimeoutTxs ++ lcp.htlcSuccessTxs).map(_.txid).contains(tx.txIn.head.outPoint.txid))
      val lcp4 = (remainingHtlcTimeoutTxs ++ claimHtlcDelayedTxs).foldLeft(lcp3) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!Closing.isLocalCommitDone(lcp4, aliceClosing.commitments))

      val theirClaimHtlcTimeout = rcp.claimHtlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != lcp.htlcSuccessTxs.head.txIn.head.outPoint).get
      val lcp5 = Closing.updateLocalCommitPublished(lcp4, theirClaimHtlcTimeout)
      assert(Closing.isLocalCommitDone(lcp5, aliceClosing.commitments))
    }

    // Scenario 4: they get back the HTLCs they sent us
    {
      val claimHtlcTimeoutDelayedTxs = lcp.claimHtlcDelayedTxs.filter(tx => lcp.htlcTimeoutTxs.map(_.txid).contains(tx.txIn.head.outPoint.txid))
      val lcp3 = (lcp.htlcTimeoutTxs ++ claimHtlcTimeoutDelayedTxs).foldLeft(lcp2) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!Closing.isLocalCommitDone(lcp3, aliceClosing.commitments))

      val lcp4 = Closing.updateLocalCommitPublished(lcp3, rcp.claimHtlcTimeoutTxs.head)
      assert(!Closing.isLocalCommitDone(lcp4, aliceClosing.commitments))

      val lcp5 = Closing.updateLocalCommitPublished(lcp4, rcp.claimHtlcTimeoutTxs.last)
      assert(Closing.isLocalCommitDone(lcp5, aliceClosing.commitments))
    }
  }

  test("remote commit published") {
    val f = setupClosingChannel()
    import f._

    val keyManager = bob.underlyingActor.nodeParams.channelKeyManager
    val bobClosing = bob.stateData.asInstanceOf[DATA_CLOSING]
    assert(bobClosing.remoteCommitPublished.nonEmpty)
    val rcp = bobClosing.remoteCommitPublished.get
    assert(rcp.commitTx.txOut.length === 6)
    assert(rcp.claimMainOutputTx.nonEmpty)
    assert(rcp.claimHtlcTimeoutTxs.length === 2)
    assert(rcp.claimHtlcSuccessTxs.length === 1) // we only have the preimage for 1 of the 2 non-dust htlcs
    assert(!rcp.isConfirmed)
    assert(!Closing.isRemoteCommitDone(keyManager, rcp, bobClosing.commitments))

    // Commit tx has been confirmed.
    val rcp1 = Closing.updateRemoteCommitPublished(rcp, rcp.commitTx)
    assert(rcp1.irrevocablySpent.nonEmpty)
    assert(rcp1.isConfirmed)
    assert(!Closing.isRemoteCommitDone(keyManager, rcp1, bobClosing.commitments))

    // Main output has been confirmed.
    val rcp2 = Closing.updateRemoteCommitPublished(rcp1, rcp.claimMainOutputTx.get)
    assert(rcp2.isConfirmed)
    assert(!Closing.isRemoteCommitDone(keyManager, rcp2, bobClosing.commitments))

    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(aliceClosing.localCommitPublished.nonEmpty)
    val lcp = aliceClosing.localCommitPublished.get

    // Scenario 1: our claim-HTLC txs are confirmed, they claim the remaining HTLC
    {
      val rcp3 = (rcp.claimHtlcSuccessTxs ++ rcp.claimHtlcTimeoutTxs).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      val theirHtlcTimeout = lcp.htlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != rcp.claimHtlcSuccessTxs.head.txIn.head.outPoint).get
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, theirHtlcTimeout)
      assert(Closing.isRemoteCommitDone(keyManager, rcp4, bobClosing.commitments))
    }

    // Scenario 2: our claim-HTLC txs are confirmed and we claim the remaining HTLC
    {
      val rcp3 = (rcp.claimHtlcSuccessTxs ++ rcp.claimHtlcTimeoutTxs).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      bob ! CMD_FULFILL_HTLC(bobPendingHtlc.htlc.id, bobPendingHtlc.preimage, replyTo_opt = Some(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      val bobClosing1 = bob.stateData.asInstanceOf[DATA_CLOSING]
      val rcp4 = bobClosing1.remoteCommitPublished.get.copy(irrevocablySpent = rcp3.irrevocablySpent)
      assert(rcp4.claimHtlcSuccessTxs.length === 2)
      val newClaimHtlcSuccessTx = rcp4.claimHtlcSuccessTxs.find(tx => tx.txid != rcp.claimHtlcSuccessTxs.head.txid).get

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, newClaimHtlcSuccessTx)
      assert(Closing.isRemoteCommitDone(keyManager, rcp5, bobClosing1.commitments))
    }

    // Scenario 3: they fulfill one of the HTLCs we sent them
    {
      val rcp3 = (lcp.htlcSuccessTxs ++ rcp.claimHtlcSuccessTxs).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      val remainingClaimHtlcTimeoutTx = rcp.claimHtlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != lcp.htlcSuccessTxs.head.txIn.head.outPoint).get
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, remainingClaimHtlcTimeoutTx)
      assert(!Closing.isRemoteCommitDone(keyManager, rcp4, bobClosing.commitments))

      val theirHtlcTimeout = lcp.htlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != rcp.claimHtlcSuccessTxs.head.txIn.head.outPoint).get
      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, theirHtlcTimeout)
      assert(Closing.isRemoteCommitDone(keyManager, rcp5, bobClosing.commitments))
    }

    // Scenario 4: they get back the HTLCs they sent us
    {
      val rcp3 = rcp.claimHtlcTimeoutTxs.foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, lcp.htlcTimeoutTxs.head)
      assert(!Closing.isRemoteCommitDone(keyManager, rcp4, bobClosing.commitments))

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, lcp.htlcTimeoutTxs.last)
      assert(Closing.isRemoteCommitDone(keyManager, rcp5, bobClosing.commitments))
    }
  }

  test("next remote commit published") {
    val probe = TestProbe()
    val setup = init()
    reachNormal(setup)
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    val (ra1, htlca1) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(16_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(500_000 msat, alice, bob, alice2bob, bob2alice) // below dust
    crossSign(alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(17_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(400_000 msat, bob, alice, bob2alice, alice2bob) // below dust
    crossSign(bob, alice, bob2alice, alice2bob)
    addHtlc(18_000_000 msat, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN(Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]

    // Alice and Bob both know the preimage for only one of the two HTLCs they received.
    alice ! CMD_FULFILL_HTLC(htlcb1.id, rb1, replyTo_opt = Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
    bob ! CMD_FULFILL_HTLC(htlca1.id, ra1, replyTo_opt = Some(probe.ref))
    probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]

    // Alice publishes her last commitment.
    alice ! CMD_FORCECLOSE(probe.ref)
    probe.expectMsgType[CommandSuccess[CMD_FORCECLOSE]]
    awaitCond(alice.stateName == CLOSING)
    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    val lcp = aliceClosing.localCommitPublished.get

    // Bob detects it.
    bob ! WatchEventSpent(BITCOIN_FUNDING_SPENT, alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.commitTx)
    awaitCond(bob.stateName == CLOSING)

    val keyManager = bob.underlyingActor.nodeParams.channelKeyManager
    val bobClosing = bob.stateData.asInstanceOf[DATA_CLOSING]
    assert(bobClosing.nextRemoteCommitPublished.nonEmpty)
    val rcp = bobClosing.nextRemoteCommitPublished.get
    assert(rcp.commitTx.txOut.length === 6)
    assert(rcp.claimMainOutputTx.nonEmpty)
    assert(rcp.claimHtlcTimeoutTxs.length === 2)
    assert(rcp.claimHtlcSuccessTxs.length === 1) // we only have the preimage for 1 of the 2 non-dust htlcs
    assert(!rcp.isConfirmed)
    assert(!Closing.isRemoteCommitDone(keyManager, rcp, bobClosing.commitments))

    // Commit tx has been confirmed.
    val rcp1 = Closing.updateRemoteCommitPublished(rcp, rcp.commitTx)
    assert(rcp1.irrevocablySpent.nonEmpty)
    assert(rcp1.isConfirmed)
    assert(!Closing.isRemoteCommitDone(keyManager, rcp1, bobClosing.commitments))

    // Main output has been confirmed.
    val rcp2 = Closing.updateRemoteCommitPublished(rcp1, rcp.claimMainOutputTx.get)
    assert(rcp2.isConfirmed)
    assert(!Closing.isRemoteCommitDone(keyManager, rcp2, bobClosing.commitments))

    // Scenario 1: our claim-HTLC txs are confirmed, they claim the remaining HTLC
    {
      val rcp3 = (rcp.claimHtlcSuccessTxs ++ rcp.claimHtlcTimeoutTxs).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      val theirHtlcTimeout = lcp.htlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != rcp.claimHtlcSuccessTxs.head.txIn.head.outPoint).get
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, theirHtlcTimeout)
      assert(Closing.isRemoteCommitDone(keyManager, rcp4, bobClosing.commitments))
    }

    // Scenario 2: our claim-HTLC txs are confirmed and we claim the remaining HTLC
    {
      val rcp3 = (rcp.claimHtlcSuccessTxs ++ rcp.claimHtlcTimeoutTxs).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      bob ! CMD_FULFILL_HTLC(htlca2.id, ra2, replyTo_opt = Some(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      val bobClosing1 = bob.stateData.asInstanceOf[DATA_CLOSING]
      val rcp4 = bobClosing1.nextRemoteCommitPublished.get.copy(irrevocablySpent = rcp3.irrevocablySpent)
      assert(rcp4.claimHtlcSuccessTxs.length === 2)
      val newClaimHtlcSuccessTx = rcp4.claimHtlcSuccessTxs.find(tx => tx.txid != rcp.claimHtlcSuccessTxs.head.txid).get

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, newClaimHtlcSuccessTx)
      assert(Closing.isRemoteCommitDone(keyManager, rcp5, bobClosing1.commitments))
    }

    // Scenario 3: they fulfill one of the HTLCs we sent them
    {
      val rcp3 = (lcp.htlcSuccessTxs ++ rcp.claimHtlcSuccessTxs).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      val remainingClaimHtlcTimeoutTx = rcp.claimHtlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != lcp.htlcSuccessTxs.head.txIn.head.outPoint).get
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, remainingClaimHtlcTimeoutTx)
      assert(!Closing.isRemoteCommitDone(keyManager, rcp4, bobClosing.commitments))

      val theirHtlcTimeout = lcp.htlcTimeoutTxs.find(tx => tx.txIn.head.outPoint != rcp.claimHtlcSuccessTxs.head.txIn.head.outPoint).get
      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, theirHtlcTimeout)
      assert(Closing.isRemoteCommitDone(keyManager, rcp5, bobClosing.commitments))
    }

    // Scenario 4: they get back the HTLCs they sent us
    {
      val rcp3 = rcp.claimHtlcTimeoutTxs.foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!Closing.isRemoteCommitDone(keyManager, rcp3, bobClosing.commitments))

      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, lcp.htlcTimeoutTxs.head)
      assert(!Closing.isRemoteCommitDone(keyManager, rcp4, bobClosing.commitments))

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, lcp.htlcTimeoutTxs.last)
      assert(Closing.isRemoteCommitDone(keyManager, rcp5, bobClosing.commitments))
    }
  }

  test("revoked commit published") {
    val setup = init()
    reachNormal(setup)
    import setup._
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    val (ra1, htlca1) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(16_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(500_000 msat, alice, bob, alice2bob, bob2alice) // below dust
    crossSign(alice, bob, alice2bob, bob2alice)
    addHtlc(17_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(18_000_000 msat, bob, alice, bob2alice, alice2bob)
    addHtlc(400_000 msat, bob, alice, bob2alice, alice2bob) // below dust
    crossSign(bob, alice, bob2alice, alice2bob)
    val revokedCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    fulfillHtlc(htlca1.id, ra1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)

    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedCommitTx)
    awaitCond(alice.stateName == CLOSING)
    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(aliceClosing.revokedCommitPublished.length === 1)
    val rvk = aliceClosing.revokedCommitPublished.head
    assert(rvk.claimMainOutputTx.nonEmpty)
    assert(rvk.mainPenaltyTx.nonEmpty)
    assert(rvk.htlcPenaltyTxs.length === 4)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)
    assert(!Closing.isRevokedCommitDone(rvk))

    // Commit tx has been confirmed.
    val rvk1 = Closing.updateRevokedCommitPublished(rvk, rvk.commitTx)
    assert(rvk1.irrevocablySpent.nonEmpty)
    assert(!Closing.isRevokedCommitDone(rvk1))

    // Main output has been confirmed.
    val rvk2 = Closing.updateRevokedCommitPublished(rvk1, rvk.claimMainOutputTx.get)
    assert(!Closing.isRevokedCommitDone(rvk2))

    // Two of our htlc penalty txs have been confirmed.
    val rvk3 = rvk.htlcPenaltyTxs.take(2).foldLeft(rvk2) {
      case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
    }
    assert(!Closing.isRevokedCommitDone(rvk3))

    // Scenario 1: the remaining penalty txs have been confirmed.
    {
      val rvk4a = rvk.htlcPenaltyTxs.drop(2).foldLeft(rvk3) {
        case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
      }
      assert(!Closing.isRevokedCommitDone(rvk4a))

      val rvk4b = Closing.updateRevokedCommitPublished(rvk4a, rvk.mainPenaltyTx.get)
      assert(Closing.isRevokedCommitDone(rvk4b))
    }

    // Scenario 2: they claim the remaining outputs.
    {
      val remoteMainOutput = rvk.mainPenaltyTx.get.copy(txOut = Seq(TxOut(35_000 sat, ByteVector.empty)))
      val rvk4a = Closing.updateRevokedCommitPublished(rvk3, remoteMainOutput)
      assert(!Closing.isRevokedCommitDone(rvk4a))

      val htlcSuccess = rvk.htlcPenaltyTxs(2).copy(txOut = Seq(TxOut(3_000 sat, ByteVector.empty), TxOut(2_500 sat, ByteVector.empty)))
      val htlcTimeout = rvk.htlcPenaltyTxs(3).copy(txOut = Seq(TxOut(3_500 sat, ByteVector.empty), TxOut(3_100 sat, ByteVector.empty)))
      // When Bob claims these outputs, the channel should call Helpers.claimRevokedHtlcTxOutputs to punish them by claiming the output of their htlc tx.
      // This is tested in ClosingStateSpec.
      val rvk4b = Seq(htlcSuccess, htlcTimeout).foldLeft(rvk4a) {
        case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
      }.copy(
        claimHtlcDelayedPenaltyTxs = List(
          Transaction(2, Seq(TxIn(OutPoint(htlcSuccess, 0), ByteVector.empty, 0)), Seq(TxOut(5_000 sat, ByteVector.empty)), 0),
          Transaction(2, Seq(TxIn(OutPoint(htlcTimeout, 0), ByteVector.empty, 0)), Seq(TxOut(6_000 sat, ByteVector.empty)), 0)
        )
      )
      assert(!Closing.isRevokedCommitDone(rvk4b))

      // We claim one of the remaining outputs, they claim the other.
      val rvk5a = Closing.updateRevokedCommitPublished(rvk4b, rvk4b.claimHtlcDelayedPenaltyTxs.head)
      assert(!Closing.isRevokedCommitDone(rvk5a))
      val theirClaimHtlcTimeout = rvk4b.claimHtlcDelayedPenaltyTxs(1).copy(txOut = Seq(TxOut(1_500.sat, ByteVector.empty), TxOut(2_500.sat, ByteVector.empty)))
      val rvk5b = Closing.updateRevokedCommitPublished(rvk5a, theirClaimHtlcTimeout)
      assert(Closing.isRevokedCommitDone(rvk5b))
    }
  }

}
