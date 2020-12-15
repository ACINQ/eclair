package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{OutPoint, Transaction, TxIn, TxOut}
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{LongToBtcAmount, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

class ChannelTypesSpec extends AnyFunSuite {

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

  test("local commit published") {
    val (lcp, _, _) = createClosingTransactions()
    assert(!lcp.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp))

    // Commit tx has been confirmed.
    val lcp1 = Closing.updateLocalCommitPublished(lcp, lcp.commitTx)
    assert(lcp1.irrevocablySpent.nonEmpty)
    assert(lcp1.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp1))

    // Main output has been confirmed.
    val lcp2 = Closing.updateLocalCommitPublished(lcp1, lcp.claimMainDelayedOutputTx.get)
    assert(lcp2.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp2))

    // Our htlc-success txs and their 3rd-stage claim txs have been confirmed.
    val lcp3 = Seq(lcp.htlcSuccessTxs.head, lcp.claimHtlcDelayedTxs.head, lcp.htlcSuccessTxs(1), lcp.claimHtlcDelayedTxs(1)).foldLeft(lcp2) {
      case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
    }
    assert(lcp3.isConfirmed)
    assert(!Closing.isLocalCommitDone(lcp3))

    // Scenario 1: our htlc-timeout txs and their 3rd-stage claim txs have been confirmed.
    {
      val lcp4a = Seq(lcp.htlcTimeoutTxs.head, lcp.claimHtlcDelayedTxs(2), lcp.htlcTimeoutTxs(1)).foldLeft(lcp3) {
        case (current, tx) => Closing.updateLocalCommitPublished(current, tx)
      }
      assert(lcp4a.isConfirmed)
      assert(!Closing.isLocalCommitDone(lcp4a))

      val lcp4b = Closing.updateLocalCommitPublished(lcp4a, lcp.claimHtlcDelayedTxs(3))
      assert(lcp4b.isConfirmed)
      assert(Closing.isLocalCommitDone(lcp4b))
    }

    // Scenario 2: they claim the htlcs we sent before our htlc-timeout.
    {
      val claimHtlcSuccess1 = lcp.htlcTimeoutTxs.head.copy(txOut = Seq(TxOut(3000.sat, ByteVector.empty), TxOut(2500.sat, ByteVector.empty)))
      val lcp4a = Closing.updateLocalCommitPublished(lcp3, claimHtlcSuccess1)
      assert(lcp4a.isConfirmed)
      assert(!Closing.isLocalCommitDone(lcp4a))

      val claimHtlcSuccess2 = lcp.htlcTimeoutTxs(1).copy(txOut = Seq(TxOut(3500.sat, ByteVector.empty), TxOut(3100.sat, ByteVector.empty)))
      val lcp4b = Closing.updateLocalCommitPublished(lcp4a, claimHtlcSuccess2)
      assert(lcp4b.isConfirmed)
      assert(Closing.isLocalCommitDone(lcp4b))
    }
  }

  test("remote commit published") {
    val (_, rcp, _) = createClosingTransactions()
    assert(!rcp.isConfirmed)
    assert(!Closing.isRemoteCommitDone(rcp))

    // Commit tx has been confirmed.
    val rcp1 = Closing.updateRemoteCommitPublished(rcp, rcp.commitTx)
    assert(rcp1.irrevocablySpent.nonEmpty)
    assert(rcp1.isConfirmed)
    assert(!Closing.isRemoteCommitDone(rcp1))

    // Main output has been confirmed.
    val rcp2 = Closing.updateRemoteCommitPublished(rcp1, rcp.claimMainOutputTx.get)
    assert(rcp2.isConfirmed)
    assert(!Closing.isRemoteCommitDone(rcp2))

    // One of our claim-htlc-success and claim-htlc-timeout has been confirmed.
    val rcp3 = Seq(rcp.claimHtlcSuccessTxs.head, rcp.claimHtlcTimeoutTxs.head).foldLeft(rcp2) {
      case (current, tx) => Closing.updateRemoteCommitPublished(current, tx)
    }
    assert(rcp3.isConfirmed)
    assert(!Closing.isRemoteCommitDone(rcp3))

    // Scenario 1: our remaining claim-htlc txs have been confirmed.
    {
      val rcp4a = Closing.updateRemoteCommitPublished(rcp3, rcp.claimHtlcSuccessTxs(1))
      assert(rcp4a.isConfirmed)
      assert(!Closing.isRemoteCommitDone(rcp4a))

      val rcp4b = Closing.updateRemoteCommitPublished(rcp4a, rcp.claimHtlcTimeoutTxs(1))
      assert(rcp4b.isConfirmed)
      assert(Closing.isRemoteCommitDone(rcp4b))
    }

    // Scenario 2: they claim the remaining htlc outputs.
    {
      val htlcSuccess = rcp.claimHtlcSuccessTxs(1).copy(txOut = Seq(TxOut(3000.sat, ByteVector.empty), TxOut(2500.sat, ByteVector.empty)))
      val rcp4a = Closing.updateRemoteCommitPublished(rcp3, htlcSuccess)
      assert(rcp4a.isConfirmed)
      assert(!Closing.isRemoteCommitDone(rcp4a))

      val htlcTimeout = rcp.claimHtlcTimeoutTxs(1).copy(txOut = Seq(TxOut(3500.sat, ByteVector.empty), TxOut(3100.sat, ByteVector.empty)))
      val rcp4b = Closing.updateRemoteCommitPublished(rcp4a, htlcTimeout)
      assert(rcp4b.isConfirmed)
      assert(Closing.isRemoteCommitDone(rcp4b))
    }
  }

  test("revoked commit published") {
    val (_, _, rvk) = createClosingTransactions()
    assert(!Closing.isRevokedCommitDone(rvk))

    // Commit tx has been confirmed.
    val rvk1 = Closing.updateRevokedCommitPublished(rvk, rvk.commitTx)
    assert(rvk1.irrevocablySpent.nonEmpty)
    assert(!Closing.isRevokedCommitDone(rvk1))

    // Main output has been confirmed.
    val rvk2 = Closing.updateRevokedCommitPublished(rvk1, rvk.claimMainOutputTx.get)
    assert(!Closing.isRevokedCommitDone(rvk2))

    // Two of our htlc penalty txs have been confirmed.
    val rvk3 = Seq(rvk.htlcPenaltyTxs.head, rvk.htlcPenaltyTxs(1)).foldLeft(rvk2) {
      case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
    }
    assert(!Closing.isRevokedCommitDone(rvk3))

    // Scenario 1: the remaining penalty txs have been confirmed.
    {
      val rvk4a = Seq(rvk.htlcPenaltyTxs(2), rvk.htlcPenaltyTxs(3)).foldLeft(rvk3) {
        case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
      }
      assert(!Closing.isRevokedCommitDone(rvk4a))

      val rvk4b = Closing.updateRevokedCommitPublished(rvk4a, rvk.mainPenaltyTx.get)
      assert(Closing.isRevokedCommitDone(rvk4b))
    }

    // Scenario 2: they claim the remaining outputs.
    {
      val remoteMainOutput = rvk.mainPenaltyTx.get.copy(txOut = Seq(TxOut(35000.sat, ByteVector.empty)))
      val rvk4a = Closing.updateRevokedCommitPublished(rvk3, remoteMainOutput)
      assert(!Closing.isRevokedCommitDone(rvk4a))

      val htlcSuccess = rvk.htlcPenaltyTxs(2).copy(txOut = Seq(TxOut(3000.sat, ByteVector.empty), TxOut(2500.sat, ByteVector.empty)))
      val htlcTimeout = rvk.htlcPenaltyTxs(3).copy(txOut = Seq(TxOut(3500.sat, ByteVector.empty), TxOut(3100.sat, ByteVector.empty)))
      // When Bob claims these outputs, the channel should call Helpers.claimRevokedHtlcTxOutputs to punish them by claiming the output of their htlc tx.
      val rvk4b = Seq(htlcSuccess, htlcTimeout).foldLeft(rvk4a) {
        case (current, tx) => Closing.updateRevokedCommitPublished(current, tx)
      }.copy(
        claimHtlcDelayedPenaltyTxs = List(
          Transaction(2, Seq(TxIn(OutPoint(htlcSuccess, 0), ByteVector.empty, 0)), Seq(TxOut(5000.sat, ByteVector.empty)), 0),
          Transaction(2, Seq(TxIn(OutPoint(htlcTimeout, 0), ByteVector.empty, 0)), Seq(TxOut(6000.sat, ByteVector.empty)), 0)
        )
      )
      assert(!Closing.isRevokedCommitDone(rvk4b))

      // We claim one of the remaining outputs, they claim the other.
      val rvk5a = Closing.updateRevokedCommitPublished(rvk4b, rvk4b.claimHtlcDelayedPenaltyTxs.head)
      assert(!Closing.isRevokedCommitDone(rvk5a))
      val theyClaimHtlcTimeout = rvk4b.claimHtlcDelayedPenaltyTxs(1).copy(txOut = Seq(TxOut(1500.sat, ByteVector.empty), TxOut(2500.sat, ByteVector.empty)))
      val rvk5b = Closing.updateRevokedCommitPublished(rvk5a, theyClaimHtlcTimeout)
      assert(Closing.isRevokedCommitDone(rvk5b))
    }
  }

  private def createClosingTransactions(): (LocalCommitPublished, RemoteCommitPublished, RevokedCommitPublished) = {
    val commitTx = Transaction(
      2,
      Seq(TxIn(OutPoint(randomBytes32, 0), ByteVector.empty, 0)),
      Seq(
        TxOut(50000.sat, ByteVector.empty), // main output Alice
        TxOut(40000.sat, ByteVector.empty), // main output Bob
        TxOut(4000.sat, ByteVector.empty), // htlc received #1
        TxOut(5000.sat, ByteVector.empty), // htlc received #2
        TxOut(6000.sat, ByteVector.empty), // htlc sent #1
        TxOut(7000.sat, ByteVector.empty), // htlc sent #2
      ),
      0
    )
    val claimMainAlice = Transaction(2, Seq(TxIn(OutPoint(commitTx, 0), ByteVector.empty, 144)), Seq(TxOut(49500.sat, ByteVector.empty)), 0)
    val htlcSuccess1 = Transaction(2, Seq(TxIn(OutPoint(commitTx, 2), ByteVector.empty, 1)), Seq(TxOut(3500.sat, ByteVector.empty)), 0)
    val htlcSuccess2 = Transaction(2, Seq(TxIn(OutPoint(commitTx, 3), ByteVector.empty, 1)), Seq(TxOut(4500.sat, ByteVector.empty)), 0)
    val htlcTimeout1 = Transaction(2, Seq(TxIn(OutPoint(commitTx, 4), ByteVector.empty, 1)), Seq(TxOut(5500.sat, ByteVector.empty)), 0)
    val htlcTimeout2 = Transaction(2, Seq(TxIn(OutPoint(commitTx, 5), ByteVector.empty, 1)), Seq(TxOut(6500.sat, ByteVector.empty)), 0)

    val localCommit = {
      val claimHtlcDelayedTxs = List(
        Transaction(2, Seq(TxIn(OutPoint(htlcSuccess1, 0), ByteVector.empty, 1)), Seq(TxOut(3400.sat, ByteVector.empty)), 0),
        Transaction(2, Seq(TxIn(OutPoint(htlcSuccess2, 0), ByteVector.empty, 1)), Seq(TxOut(4400.sat, ByteVector.empty)), 0),
        Transaction(2, Seq(TxIn(OutPoint(htlcTimeout1, 0), ByteVector.empty, 1)), Seq(TxOut(5400.sat, ByteVector.empty)), 0),
        Transaction(2, Seq(TxIn(OutPoint(htlcTimeout2, 0), ByteVector.empty, 1)), Seq(TxOut(6400.sat, ByteVector.empty)), 0),
      )
      LocalCommitPublished(commitTx, Some(claimMainAlice), List(htlcSuccess1, htlcSuccess2), List(htlcTimeout1, htlcTimeout2), claimHtlcDelayedTxs, Map.empty)
    }
    val remoteCommit = RemoteCommitPublished(commitTx, Some(claimMainAlice), List(htlcSuccess1, htlcSuccess2), List(htlcTimeout1, htlcTimeout2), Map.empty)
    val revokedCommit = {
      val mainPenalty = Transaction(2, Seq(TxIn(OutPoint(commitTx, 1), ByteVector.empty, 0)), Seq(TxOut(39500.sat, ByteVector.empty)), 0)
      RevokedCommitPublished(commitTx, Some(claimMainAlice), Some(mainPenalty), List(htlcSuccess1, htlcSuccess2, htlcTimeout1, htlcTimeout2), Nil, Map.empty)
    }

    (localCommit, remoteCommit, revokedCommit)
  }

}
