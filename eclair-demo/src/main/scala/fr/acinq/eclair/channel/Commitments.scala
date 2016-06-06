package fr.acinq.eclair.channel

import com.google.protobuf.ByteString
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.TypeDefs.Change
import fr.acinq.eclair.crypto.ShaChain
import lightning._
import monocle.macros.GenLens

case class Commitments(ourParams: OurChannelParams, theirParams: TheirChannelParams,
                       ourCommit: OurCommit, theirCommit: TheirCommit,
                       ourChanges: OurChanges, theirChanges: TheirChanges,
                       theirNextRevocationHashOpt: Option[BinaryData],
                       anchorOutput: TxOut) {
  def anchorId: BinaryData = {
    assert(ourCommit.publishableTx.txIn.size == 1, "commitment tx should only have one input")
    ourCommit.publishableTx.txIn(0).outPoint.hash
  }

  def hasNoPendingHtlcs: Boolean = ourCommit.spec.htlcs.isEmpty && theirCommit.spec.htlcs.isEmpty

  def addOurProposal(proposal: Change): Commitments = Commitments.addOurProposal(this, proposal)

  def addTheirProposal(proposal: Change): Commitments = Commitments.addTheirProposal(this, proposal)
}

object Commitments {
  val _ourChanges = GenLens[Commitments](_.ourChanges)
  val _ourChangesProposed = _ourChanges composeLens GenLens[OurChanges](_.proposed)
  val _ourChangesSigned = _ourChanges composeLens GenLens[OurChanges](_.signed)
  val _ourChangesAcked = _ourChanges composeLens GenLens[OurChanges](_.acked)
  val _theirChanges = GenLens[Commitments](_.theirChanges)
  val _theirChangesProposed = _theirChanges composeLens GenLens[TheirChanges](_.proposed)
  val _theirChangesAcked = _theirChanges composeLens GenLens[TheirChanges](_.acked)

  def addOurProposal(commitments: Commitments, proposal: Change): Commitments = _ourChangesProposed.modify(_ :+ proposal)(commitments)

  def addTheirProposal(commitments: Commitments, proposal: Change): Commitments = _theirChangesProposed.modify(_ :+ proposal)(commitments)

  def sendFulfill(commitments: Commitments, cmd: CMD_FULFILL_HTLC): (Commitments, update_fulfill_htlc) = {
    commitments.theirChanges.acked.collectFirst { case u: update_add_htlc if u.id == cmd.id => u } match {
      case Some(htlc) if htlc.rHash == bin2sha256(Crypto.sha256(cmd.r)) =>
        val fulfill = update_fulfill_htlc(cmd.id, cmd.r)
        val commitments1 = addOurProposal(commitments, fulfill)
        (commitments1, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc ${cmd.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFulfill(commitments: Commitments, fulfill: update_fulfill_htlc): Commitments = {
    commitments.ourChanges.acked.collectFirst { case u: update_add_htlc if u.id == fulfill.id => u } match {
      case Some(htlc) if htlc.rHash == bin2sha256(Crypto.sha256(fulfill.r)) => addTheirProposal(commitments, fulfill)
      case Some(htlc) => throw new RuntimeException(s"invalid htlc preimage for htlc ${fulfill.id}")
      case None => throw new RuntimeException(s"unknown htlc id=${fulfill.id}") // TODO : we should fail the channel
    }
  }

  def sendFail(commitments: Commitments, cmd: CMD_FAIL_HTLC): (Commitments, update_fail_htlc) = {
    commitments.theirChanges.acked.collectFirst { case u: update_add_htlc if u.id == cmd.id => u } match {
      case Some(htlc) =>
        val fail = update_fail_htlc(cmd.id, fail_reason(ByteString.copyFromUtf8(cmd.reason)))
        val commitments1 = addOurProposal(commitments, fail)
        (commitments1, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${cmd.id}")
    }
  }

  def receiveFail(commitments: Commitments, fail: update_fail_htlc): Commitments = {
    commitments.ourChanges.acked.collectFirst { case u: update_add_htlc if u.id == fail.id => u } match {
      case Some(htlc) =>
        addTheirProposal(commitments, fail)
      case None => throw new RuntimeException(s"unknown htlc id=${fail.id}") // TODO : we should fail the channel
    }
  }

  def sendCommit(commitments: Commitments): (Commitments, update_commit) = {
    import commitments._
    theirNextRevocationHashOpt match {
      case Some(theirNextRevocationHash) =>
        // sign all our proposals + their acked proposals
        // their commitment now includes all our changes  + their acked changes
        val spec = Helpers.reduce(theirCommit.spec, theirChanges.acked, ourChanges.acked ++ ourChanges.signed ++ ourChanges.proposed)
        val theirTx = Helpers.makeTheirTx(ourParams, theirParams, ourCommit.publishableTx.txIn, theirNextRevocationHash, spec)
        val ourSig = Helpers.sign(ourParams, theirParams, anchorOutput.amount.toLong, theirTx)
        val commit = update_commit(ourSig)
        val commitments1 = commitments.copy(
          theirCommit = TheirCommit(theirCommit.index + 1, spec, theirNextRevocationHash),
          ourChanges = ourChanges.copy(proposed = Nil, signed = ourChanges.signed ++ ourChanges.proposed),
          theirNextRevocationHashOpt = None)
        (commitments1, commit)
      case None =>
        throw new RuntimeException(s"cannot send two update_commit in a row (must wait for revocation)")
    }
  }

  def receiveCommit(commitments: Commitments, commit: update_commit): (Commitments, update_revocation) = {
    import commitments._
    // check that their signature is valid
    val spec = Helpers.reduce(ourCommit.spec, ourChanges.acked, theirChanges.acked ++ theirChanges.proposed)
    val ourNextRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, ourCommit.index + 1))
    val ourTx = Helpers.makeOurTx(ourParams, theirParams, ourCommit.publishableTx.txIn, ourNextRevocationHash, spec)
    val ourSig = Helpers.sign(ourParams, theirParams, anchorOutput.amount.toLong, ourTx)
    val signedTx = Helpers.addSigs(ourParams, theirParams, anchorOutput.amount.toLong, ourTx, ourSig, commit.sig)
    Helpers.checksig(ourParams, theirParams, anchorOutput, signedTx).get

    // we will send our revocation preimage+ our next revocation hash
    val ourRevocationPreimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, ourCommit.index)
    val ourNextRevocationHash1 = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, ourCommit.index + 2))
    val revocation = update_revocation(ourRevocationPreimage, ourNextRevocationHash1)

    // update our commitment data
    val ourCommit1 = ourCommit.copy(index = ourCommit.index + 1, spec, publishableTx = signedTx)
    val theirChanges1 = theirChanges.copy(proposed = Nil, acked = theirChanges.acked ++ theirChanges.proposed)
    val commitments1 = commitments.copy(ourCommit = ourCommit1, theirChanges = theirChanges1)

    (commitments1, revocation)
  }

  def receiveRevocation(commitments: Commitments, revocation: update_revocation): Commitments = {
    import commitments._
    // TODO: check preimage
    commitments.copy(ourChanges = ourChanges.copy(signed = Nil, acked = ourChanges.acked ++ ourChanges.signed), theirNextRevocationHashOpt = Some(revocation.nextRevocationHash))
  }

  /**
    *
    * @param commitments
    * @param ourScriptPubKey
    * @param theirScriptPubKey
    * @param closeFee bitcoin fee for the final tx
    * @return a (final tx, our signature) tuple. The tx is not signed.
    */
  def makeFinalTx(commitments: Commitments, ourScriptPubKey: BinaryData, theirScriptPubKey: BinaryData, closeFee: Satoshi): (Transaction, close_signature) = {
    val amount_us = Satoshi(commitments.ourCommit.spec.amount_us_msat / 1000)
    val amount_them = Satoshi(commitments.theirCommit.spec.amount_us_msat / 1000)
    val finalTx = Scripts.makeFinalTx(commitments.ourCommit.publishableTx.txIn, ourScriptPubKey, theirScriptPubKey, amount_us, amount_them, closeFee)
    val ourSig = Helpers.sign(commitments.ourParams, commitments.theirParams, commitments.anchorOutput.amount.toLong, finalTx)
    (finalTx, close_signature(closeFee.toLong, ourSig))
  }

  /**
    *
    * @param commitments
    * @param ourScriptPubKey
    * @param theirScriptPubKey
    * @return a (final tx, our signature) tuple. The tx is not signed. Bitcoin fees will be copied from our
    *         last commit tx
    */
  def makeFinalTx(commitments: Commitments, ourScriptPubKey: BinaryData, theirScriptPubKey: BinaryData): (Transaction, close_signature) = {
    val commitFee = commitments.anchorOutput.amount.toLong - commitments.ourCommit.publishableTx.txOut.map(_.amount.toLong).sum
    val closeFee = Satoshi(2 * (commitFee / 4))
    makeFinalTx(commitments, ourScriptPubKey, theirScriptPubKey, closeFee)
  }
}


