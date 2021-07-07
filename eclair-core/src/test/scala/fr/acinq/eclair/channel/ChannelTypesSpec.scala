package fr.acinq.eclair.channel

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, OutPoint, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.FeatureSupport._
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchFundingSpentTriggered
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{CommitSig, RevokeAndAck, UpdateAddHtlc}
import fr.acinq.eclair.{Features, MilliSatoshiLong, TestKitBaseClass}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

class ChannelTypesSpec extends TestKitBaseClass with AnyFunSuiteLike with StateTestsHelperMethods {

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  test("channel features determine commitment format") {
    val standard = ChannelFeatures(ChannelTypes.Standard.features)
    assert(standard.channelType === ChannelTypes.Standard)
    assert(!standard.hasFeature(StaticRemoteKey))
    assert(!standard.hasFeature(AnchorOutputs))
    assert(standard.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(!standard.paysDirectlyToWallet)

    val staticRemoteKey = ChannelFeatures(ChannelTypes.StaticRemoteKey.features)
    assert(staticRemoteKey.channelType === ChannelTypes.StaticRemoteKey)
    assert(staticRemoteKey.hasFeature(StaticRemoteKey))
    assert(!staticRemoteKey.hasFeature(AnchorOutputs))
    assert(staticRemoteKey.commitmentFormat === Transactions.DefaultCommitmentFormat)
    assert(staticRemoteKey.paysDirectlyToWallet)

    val anchorOutputs = ChannelFeatures(ChannelTypes.AnchorOutputs.features)
    assert(anchorOutputs.channelType === ChannelTypes.AnchorOutputs)
    assert(anchorOutputs.hasFeature(StaticRemoteKey))
    assert(anchorOutputs.hasFeature(AnchorOutputs))
    assert(anchorOutputs.commitmentFormat === Transactions.AnchorOutputsCommitmentFormat)
    assert(!anchorOutputs.paysDirectlyToWallet)
  }

  test("pick channel type based on local and remote features") {
    case class TestCase(localFeatures: Features, remoteFeatures: Features, expectedChannelType: ChannelType)
    val testCases = Seq(
      TestCase(Features.empty, Features.empty, ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional), Features.empty, ChannelTypes.Standard),
      TestCase(Features.empty, Features(StaticRemoteKey -> Optional), ChannelTypes.Standard),
      TestCase(Features.empty, Features(StaticRemoteKey -> Mandatory), ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Mandatory), Features(Wumbo -> Mandatory), ChannelTypes.Standard),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional), Features(StaticRemoteKey -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional, Wumbo -> Optional), Features(StaticRemoteKey -> Mandatory, Wumbo -> Mandatory), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional), ChannelTypes.StaticRemoteKey),
      TestCase(Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional), Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional), ChannelTypes.AnchorOutputs)
    )

    for (testCase <- testCases) {
      assert(ChannelTypes.pickChannelType(testCase.localFeatures, testCase.remoteFeatures) === testCase.expectedChannelType)
    }
  }

  test("create channel type from features") {
    val testCases = Seq(
      Features.empty -> Some(ChannelTypes.Standard),
      Features(Wumbo -> Optional) -> None,
      Features(StaticRemoteKey -> Optional) -> None,
      Features(StaticRemoteKey -> Mandatory, Wumbo -> Optional) -> None,
      Features(StaticRemoteKey -> Mandatory) -> Some(ChannelTypes.StaticRemoteKey),
      Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional) -> None,
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Optional) -> None,
      Features(StaticRemoteKey -> Optional, AnchorOutputs -> Mandatory) -> None,
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, Wumbo -> Optional) -> None,
      Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory) -> Some(ChannelTypes.AnchorOutputs),
    )

    for ((features, expected) <- testCases) {
      assert(ChannelTypes.fromFeatures(features) === expected)
    }
  }

  test("enrich channel type with other permanent channel features") {
    assert(ChannelFeatures(ChannelTypes.Standard, Features(Wumbo -> Optional), Features.empty).features === Features.empty)
    assert(ChannelFeatures(ChannelTypes.Standard, Features(Wumbo -> Optional), Features(Wumbo -> Optional)).features === Features(Wumbo -> Mandatory))
    assert(ChannelFeatures(ChannelTypes.Standard, Features(Wumbo -> Mandatory), Features(Wumbo -> Optional)).features === Features(Wumbo -> Mandatory))
    assert(ChannelFeatures(ChannelTypes.StaticRemoteKey, Features(Wumbo -> Optional), Features.empty).features === Features(StaticRemoteKey -> Mandatory))
    assert(ChannelFeatures(ChannelTypes.StaticRemoteKey, Features(Wumbo -> Optional), Features(Wumbo -> Optional)).features === Features(StaticRemoteKey -> Mandatory, Wumbo -> Mandatory))
    assert(ChannelFeatures(ChannelTypes.AnchorOutputs, Features.empty, Features(Wumbo -> Optional)).features === Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory))
    assert(ChannelFeatures(ChannelTypes.AnchorOutputs, Features(Wumbo -> Optional), Features(Wumbo -> Mandatory)).features === Features(StaticRemoteKey -> Mandatory, AnchorOutputs -> Mandatory, Wumbo -> Mandatory))
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
    bob ! WatchFundingSpentTriggered(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.commitTx)
    awaitCond(bob.stateName == CLOSING)

    Fixture(alice, HtlcWithPreimage(rb2, htlcb2), bob, HtlcWithPreimage(ra2, htlca2), TestProbe())
  }

  test("local commit published") {
    val f = setupClosingChannel()
    import f._

    val nodeParams = alice.underlyingActor.nodeParams
    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(aliceClosing.localCommitPublished.nonEmpty)
    val lcp = aliceClosing.localCommitPublished.get
    assert(lcp.commitTx.txOut.length === 6)
    assert(lcp.claimMainDelayedOutputTx.nonEmpty)
    assert(lcp.htlcTxs.size === 4) // we have one entry for each non-dust htlc
    val htlcTimeoutTxs = getHtlcTimeoutTxs(lcp)
    assert(htlcTimeoutTxs.length === 2)
    val htlcSuccessTxs = getHtlcSuccessTxs(lcp)
    assert(htlcSuccessTxs.length === 1) // we only have the preimage for 1 of the 2 non-dust htlcs
    val remainingHtlcOutpoint = lcp.htlcTxs.collect { case (outpoint, None) => outpoint }.head
    assert(lcp.claimHtlcDelayedTxs.length === 0) // we will publish 3rd-stage txs once htlc txs confirm
    assert(!lcp.isConfirmed)
    assert(!lcp.isDone)

    // Commit tx has been confirmed.
    val lcp1 = Closing.updateLocalCommitPublished(lcp, lcp.commitTx)
    assert(lcp1.irrevocablySpent.nonEmpty)
    assert(lcp1.isConfirmed)
    assert(!lcp1.isDone)

    // Main output has been confirmed.
    val lcp2 = Closing.updateLocalCommitPublished(lcp1, lcp.claimMainDelayedOutputTx.get.tx)
    assert(lcp2.isConfirmed)
    assert(!lcp2.isDone)

    val bobClosing = bob.stateData.asInstanceOf[DATA_CLOSING]
    assert(bobClosing.remoteCommitPublished.nonEmpty)
    val rcp = bobClosing.remoteCommitPublished.get

    // Scenario 1: our HTLC txs are confirmed, they claim the remaining HTLC
    {
      val lcp3 = (htlcSuccessTxs.map(_.tx) ++ htlcTimeoutTxs.map(_.tx)).foldLeft(lcp2) {
        case (current, tx) =>
          val (current1, Some(_)) = Closing.claimLocalCommitHtlcTxOutput(current, nodeParams.channelKeyManager, aliceClosing.commitments, tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
          Closing.updateLocalCommitPublished(current1, tx)
      }
      assert(!lcp3.isDone)
      assert(lcp3.claimHtlcDelayedTxs.length === 3)

      val lcp4 = lcp3.claimHtlcDelayedTxs.map(_.tx).foldLeft(lcp3) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!lcp4.isDone)

      val theirClaimHtlcTimeout = rcp.claimHtlcTxs(remainingHtlcOutpoint)
      assert(theirClaimHtlcTimeout !== None)
      val lcp5 = Closing.updateLocalCommitPublished(lcp4, theirClaimHtlcTimeout.get.tx)
      assert(lcp5.isDone)
    }

    // Scenario 2: our HTLC txs are confirmed and we claim the remaining HTLC
    {
      val lcp3 = (htlcSuccessTxs.map(_.tx) ++ htlcTimeoutTxs.map(_.tx)).foldLeft(lcp2) {
        case (current, tx) =>
          val (current1, Some(_)) = Closing.claimLocalCommitHtlcTxOutput(current, nodeParams.channelKeyManager, aliceClosing.commitments, tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
          Closing.updateLocalCommitPublished(current1, tx)
      }
      assert(!lcp3.isDone)
      assert(lcp3.claimHtlcDelayedTxs.length === 3)

      val lcp4 = lcp3.claimHtlcDelayedTxs.map(_.tx).foldLeft(lcp3) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!lcp4.isDone)

      alice ! CMD_FULFILL_HTLC(alicePendingHtlc.htlc.id, alicePendingHtlc.preimage, replyTo_opt = Some(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      val aliceClosing1 = alice.stateData.asInstanceOf[DATA_CLOSING]
      val lcp5 = aliceClosing1.localCommitPublished.get.copy(irrevocablySpent = lcp4.irrevocablySpent, claimHtlcDelayedTxs = lcp4.claimHtlcDelayedTxs)
      assert(lcp5.htlcTxs(remainingHtlcOutpoint) !== None)
      assert(lcp5.claimHtlcDelayedTxs.length === 3)

      val newHtlcSuccessTx = lcp5.htlcTxs(remainingHtlcOutpoint).get.tx
      val (lcp6, Some(newClaimHtlcDelayedTx)) = Closing.claimLocalCommitHtlcTxOutput(lcp5, nodeParams.channelKeyManager, aliceClosing.commitments, newHtlcSuccessTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
      assert(lcp6.claimHtlcDelayedTxs.length === 4)

      val lcp7 = Closing.updateLocalCommitPublished(lcp6, newHtlcSuccessTx)
      assert(!lcp7.isDone)

      val lcp8 = Closing.updateLocalCommitPublished(lcp7, newClaimHtlcDelayedTx.tx)
      assert(lcp8.isDone)
    }

    // Scenario 3: they fulfill one of the HTLCs we sent them
    {
      val remoteHtlcSuccess = rcp.claimHtlcTxs.values.collectFirst { case Some(tx: ClaimHtlcSuccessTx) => tx }.get
      val lcp3 = (htlcSuccessTxs.map(_.tx) ++ Seq(remoteHtlcSuccess.tx)).foldLeft(lcp2) {
        case (current, tx) =>
          val (current1, _) = Closing.claimLocalCommitHtlcTxOutput(current, nodeParams.channelKeyManager, aliceClosing.commitments, tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
          Closing.updateLocalCommitPublished(current1, tx)
      }
      assert(lcp3.claimHtlcDelayedTxs.length === 1)
      assert(!lcp3.isDone)

      val lcp4 = Closing.updateLocalCommitPublished(lcp3, lcp3.claimHtlcDelayedTxs.head.tx)
      assert(!lcp4.isDone)

      val remainingHtlcTimeoutTxs = htlcTimeoutTxs.filter(_.input.outPoint != remoteHtlcSuccess.input.outPoint)
      assert(remainingHtlcTimeoutTxs.length === 1)
      val (lcp5, Some(remainingClaimHtlcTx)) = Closing.claimLocalCommitHtlcTxOutput(lcp4, nodeParams.channelKeyManager, aliceClosing.commitments, remainingHtlcTimeoutTxs.head.tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
      assert(lcp5.claimHtlcDelayedTxs.length === 2)

      val lcp6 = (remainingHtlcTimeoutTxs.map(_.tx) ++ Seq(remainingClaimHtlcTx.tx)).foldLeft(lcp5) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!lcp6.isDone)

      val theirClaimHtlcTimeout = rcp.claimHtlcTxs(remainingHtlcOutpoint)
      val lcp7 = Closing.updateLocalCommitPublished(lcp6, theirClaimHtlcTimeout.get.tx)
      assert(lcp7.isDone)
    }

    // Scenario 4: they get back the HTLCs they sent us
    {
      val lcp3 = htlcTimeoutTxs.map(_.tx).foldLeft(lcp2) {
        case (current, tx) =>
          val (current1, Some(_)) = Closing.claimLocalCommitHtlcTxOutput(current, nodeParams.channelKeyManager, aliceClosing.commitments, tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
          Closing.updateLocalCommitPublished(current1, tx)
      }
      assert(!lcp3.isDone)
      assert(lcp3.claimHtlcDelayedTxs.length === 2)

      val lcp4 = lcp3.claimHtlcDelayedTxs.map(_.tx).foldLeft(lcp3) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(!lcp4.isDone)

      val remoteHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(rcp).map(_.tx)
      assert(remoteHtlcTimeoutTxs.length === 2)
      val lcp5 = Closing.updateLocalCommitPublished(lcp4, remoteHtlcTimeoutTxs.head)
      assert(!lcp5.isDone)

      val lcp6 = Closing.updateLocalCommitPublished(lcp5, remoteHtlcTimeoutTxs.last)
      assert(lcp6.isDone)
    }
  }

  test("remote commit published") {
    val f = setupClosingChannel()
    import f._

    val bobClosing = bob.stateData.asInstanceOf[DATA_CLOSING]
    assert(bobClosing.remoteCommitPublished.nonEmpty)
    val rcp = bobClosing.remoteCommitPublished.get
    assert(rcp.commitTx.txOut.length === 6)
    assert(rcp.claimMainOutputTx.nonEmpty)
    assert(rcp.claimHtlcTxs.size === 4) // we have one entry for each non-dust htlc
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(rcp)
    assert(claimHtlcTimeoutTxs.length === 2)
    val claimHtlcSuccessTxs = getClaimHtlcSuccessTxs(rcp)
    assert(claimHtlcSuccessTxs.length === 1) // we only have the preimage for 1 of the 2 non-dust htlcs
    val remainingHtlcOutpoint = rcp.claimHtlcTxs.collect { case (outpoint, None) => outpoint }.head
    assert(!rcp.isConfirmed)
    assert(!rcp.isDone)

    // Commit tx has been confirmed.
    val rcp1 = Closing.updateRemoteCommitPublished(rcp, rcp.commitTx)
    assert(rcp1.irrevocablySpent.nonEmpty)
    assert(rcp1.isConfirmed)
    assert(!rcp1.isDone)

    // Main output has been confirmed.
    val rcp2 = Closing.updateRemoteCommitPublished(rcp1, rcp.claimMainOutputTx.get.tx)
    assert(rcp2.isConfirmed)
    assert(!rcp2.isDone)

    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(aliceClosing.localCommitPublished.nonEmpty)
    val lcp = aliceClosing.localCommitPublished.get

    // Scenario 1: our claim-HTLC txs are confirmed, they claim the remaining HTLC
    {
      val rcp3 = (claimHtlcSuccessTxs ++ claimHtlcTimeoutTxs).map(_.tx).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      val theirHtlcTimeout = lcp.htlcTxs(remainingHtlcOutpoint)
      assert(theirHtlcTimeout !== None)
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, theirHtlcTimeout.get.tx)
      assert(rcp4.isDone)
    }

    // Scenario 2: our claim-HTLC txs are confirmed and we claim the remaining HTLC
    {
      val rcp3 = (claimHtlcSuccessTxs ++ claimHtlcTimeoutTxs).map(_.tx).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      bob ! CMD_FULFILL_HTLC(bobPendingHtlc.htlc.id, bobPendingHtlc.preimage, replyTo_opt = Some(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      val bobClosing1 = bob.stateData.asInstanceOf[DATA_CLOSING]
      val rcp4 = bobClosing1.remoteCommitPublished.get.copy(irrevocablySpent = rcp3.irrevocablySpent)
      assert(rcp4.claimHtlcTxs(remainingHtlcOutpoint) !== None)
      val newClaimHtlcSuccessTx = rcp4.claimHtlcTxs(remainingHtlcOutpoint).get

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, newClaimHtlcSuccessTx.tx)
      assert(rcp5.isDone)
    }

    // Scenario 3: they fulfill one of the HTLCs we sent them
    {
      val remoteHtlcSuccess = lcp.htlcTxs.values.collectFirst { case Some(tx: HtlcSuccessTx) => tx }.get
      val rcp3 = (remoteHtlcSuccess.tx +: claimHtlcSuccessTxs.map(_.tx)).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      val remainingClaimHtlcTimeoutTx = claimHtlcTimeoutTxs.filter(_.input.outPoint != remoteHtlcSuccess.input.outPoint)
      assert(remainingClaimHtlcTimeoutTx.length === 1)
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, remainingClaimHtlcTimeoutTx.head.tx)
      assert(!rcp4.isDone)

      val theirHtlcTimeout = lcp.htlcTxs(remainingHtlcOutpoint)
      assert(theirHtlcTimeout !== None)
      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, theirHtlcTimeout.get.tx)
      assert(rcp5.isDone)
    }

    // Scenario 4: they get back the HTLCs they sent us
    {
      val rcp3 = claimHtlcTimeoutTxs.map(_.tx).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      val htlcTimeoutTxs = getHtlcTimeoutTxs(lcp).map(_.tx)
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, htlcTimeoutTxs.head)
      assert(!rcp4.isDone)

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, htlcTimeoutTxs.last)
      assert(rcp5.isDone)
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
    bob ! WatchFundingSpentTriggered(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.commitTx)
    awaitCond(bob.stateName == CLOSING)

    val bobClosing = bob.stateData.asInstanceOf[DATA_CLOSING]
    assert(bobClosing.nextRemoteCommitPublished.nonEmpty)
    val rcp = bobClosing.nextRemoteCommitPublished.get
    assert(rcp.commitTx.txOut.length === 6)
    assert(rcp.claimMainOutputTx.nonEmpty)
    assert(rcp.claimHtlcTxs.size === 4) // we have one entry for each non-dust htlc
    val claimHtlcTimeoutTxs = getClaimHtlcTimeoutTxs(rcp)
    assert(claimHtlcTimeoutTxs.length === 2)
    val claimHtlcSuccessTxs = getClaimHtlcSuccessTxs(rcp)
    assert(claimHtlcSuccessTxs.length === 1) // we only have the preimage for 1 of the 2 non-dust htlcs
    val remainingHtlcOutpoint = rcp.claimHtlcTxs.collect { case (outpoint, None) => outpoint }.head
    assert(!rcp.isConfirmed)
    assert(!rcp.isDone)

    // Commit tx has been confirmed.
    val rcp1 = Closing.updateRemoteCommitPublished(rcp, rcp.commitTx)
    assert(rcp1.irrevocablySpent.nonEmpty)
    assert(rcp1.isConfirmed)
    assert(!rcp1.isDone)

    // Main output has been confirmed.
    val rcp2 = Closing.updateRemoteCommitPublished(rcp1, rcp.claimMainOutputTx.get.tx)
    assert(rcp2.isConfirmed)
    assert(!rcp2.isDone)

    // Scenario 1: our claim-HTLC txs are confirmed, they claim the remaining HTLC
    {
      val rcp3 = (claimHtlcSuccessTxs ++ claimHtlcTimeoutTxs).map(_.tx).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      val theirHtlcTimeout = lcp.htlcTxs(remainingHtlcOutpoint)
      assert(theirHtlcTimeout !== None)
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, theirHtlcTimeout.get.tx)
      assert(rcp4.isDone)
    }

    // Scenario 2: our claim-HTLC txs are confirmed and we claim the remaining HTLC
    {
      val rcp3 = (claimHtlcSuccessTxs ++ claimHtlcTimeoutTxs).map(_.tx).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      bob ! CMD_FULFILL_HTLC(htlca2.id, ra2, replyTo_opt = Some(probe.ref))
      probe.expectMsgType[CommandSuccess[CMD_FULFILL_HTLC]]
      val bobClosing1 = bob.stateData.asInstanceOf[DATA_CLOSING]
      val rcp4 = bobClosing1.nextRemoteCommitPublished.get.copy(irrevocablySpent = rcp3.irrevocablySpent)
      assert(rcp4.claimHtlcTxs(remainingHtlcOutpoint) !== None)
      val newClaimHtlcSuccessTx = rcp4.claimHtlcTxs(remainingHtlcOutpoint).get

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, newClaimHtlcSuccessTx.tx)
      assert(rcp5.isDone)
    }

    // Scenario 3: they fulfill one of the HTLCs we sent them
    {
      val remoteHtlcSuccess = lcp.htlcTxs.values.collectFirst { case Some(tx: HtlcSuccessTx) => tx }.get
      val rcp3 = (remoteHtlcSuccess.tx +: claimHtlcSuccessTxs.map(_.tx)).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      val remainingClaimHtlcTimeoutTx = claimHtlcTimeoutTxs.filter(_.input.outPoint != remoteHtlcSuccess.input.outPoint)
      assert(remainingClaimHtlcTimeoutTx.length === 1)
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, remainingClaimHtlcTimeoutTx.head.tx)
      assert(!rcp4.isDone)

      val theirHtlcTimeout = lcp.htlcTxs(remainingHtlcOutpoint)
      assert(theirHtlcTimeout !== None)
      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, theirHtlcTimeout.get.tx)
      assert(rcp5.isDone)
    }

    // Scenario 4: they get back the HTLCs they sent us
    {
      val rcp3 = claimHtlcTimeoutTxs.map(_.tx).foldLeft(rcp2) {
        case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
      }
      assert(!rcp3.isDone)

      val htlcTimeoutTxs = getHtlcTimeoutTxs(lcp).map(_.tx)
      val rcp4 = Closing.updateRemoteCommitPublished(rcp3, htlcTimeoutTxs.head)
      assert(!rcp4.isDone)

      val rcp5 = Closing.updateRemoteCommitPublished(rcp4, htlcTimeoutTxs.last)
      assert(rcp5.isDone)
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
    val revokedCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    fulfillHtlc(htlca1.id, ra1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)

    alice ! WatchFundingSpentTriggered(revokedCommitTx)
    awaitCond(alice.stateName == CLOSING)
    val aliceClosing = alice.stateData.asInstanceOf[DATA_CLOSING]
    assert(aliceClosing.revokedCommitPublished.length === 1)
    val rvk = aliceClosing.revokedCommitPublished.head
    assert(rvk.claimMainOutputTx.nonEmpty)
    assert(rvk.mainPenaltyTx.nonEmpty)
    assert(rvk.htlcPenaltyTxs.length === 4)
    assert(rvk.claimHtlcDelayedPenaltyTxs.isEmpty)
    assert(!rvk.isDone)

    // Commit tx has been confirmed.
    val rvk1 = Closing.updateRevokedCommitPublished(rvk, rvk.commitTx)
    assert(rvk1.irrevocablySpent.nonEmpty)
    assert(!rvk1.isDone)

    // Main output has been confirmed.
    val rvk2 = Closing.updateRevokedCommitPublished(rvk1, rvk.claimMainOutputTx.get.tx)
    assert(!rvk2.isDone)

    // Two of our htlc penalty txs have been confirmed.
    val rvk3 = rvk.htlcPenaltyTxs.map(_.tx).take(2).foldLeft(rvk2) {
      case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
    }
    assert(!rvk3.isDone)

    // Scenario 1: the remaining penalty txs have been confirmed.
    {
      val rvk4a = rvk.htlcPenaltyTxs.map(_.tx).drop(2).foldLeft(rvk3) {
        case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
      }
      assert(!rvk4a.isDone)

      val rvk4b = Closing.updateRevokedCommitPublished(rvk4a, rvk.mainPenaltyTx.get.tx)
      assert(rvk4b.isDone)
    }

    // Scenario 2: they claim the remaining outputs.
    {
      val remoteMainOutput = rvk.mainPenaltyTx.get.tx.copy(txOut = Seq(TxOut(35_000 sat, ByteVector.empty)))
      val rvk4a = Closing.updateRevokedCommitPublished(rvk3, remoteMainOutput)
      assert(!rvk4a.isDone)

      val htlcSuccess = rvk.htlcPenaltyTxs(2).tx.copy(txOut = Seq(TxOut(3_000 sat, ByteVector.empty), TxOut(2_500 sat, ByteVector.empty)))
      val htlcTimeout = rvk.htlcPenaltyTxs(3).tx.copy(txOut = Seq(TxOut(3_500 sat, ByteVector.empty), TxOut(3_100 sat, ByteVector.empty)))
      // When Bob claims these outputs, the channel should call Helpers.claimRevokedHtlcTxOutputs to punish them by claiming the output of their htlc tx.
      // This is tested in ClosingStateSpec.
      val rvk4b = Seq(htlcSuccess, htlcTimeout).foldLeft(rvk4a) {
        case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
      }.copy(
        claimHtlcDelayedPenaltyTxs = List(
          ClaimHtlcDelayedOutputPenaltyTx(InputInfo(OutPoint(htlcSuccess, 0), TxOut(2_500 sat, Nil), Nil), Transaction(2, Seq(TxIn(OutPoint(htlcSuccess, 0), ByteVector.empty, 0)), Seq(TxOut(5_000 sat, ByteVector.empty)), 0)),
          ClaimHtlcDelayedOutputPenaltyTx(InputInfo(OutPoint(htlcTimeout, 0), TxOut(3_000 sat, Nil), Nil), Transaction(2, Seq(TxIn(OutPoint(htlcTimeout, 0), ByteVector.empty, 0)), Seq(TxOut(6_000 sat, ByteVector.empty)), 0))
        )
      )
      assert(!rvk4b.isDone)

      // We claim one of the remaining outputs, they claim the other.
      val rvk5a = Closing.updateRevokedCommitPublished(rvk4b, rvk4b.claimHtlcDelayedPenaltyTxs.head.tx)
      assert(!rvk5a.isDone)
      val theirClaimHtlcTimeout = rvk4b.claimHtlcDelayedPenaltyTxs(1).tx.copy(txOut = Seq(TxOut(1_500.sat, ByteVector.empty), TxOut(2_500.sat, ByteVector.empty)))
      val rvk5b = Closing.updateRevokedCommitPublished(rvk5a, theirClaimHtlcTimeout)
      assert(rvk5b.isDone)
    }
  }

}
