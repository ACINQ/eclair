package fr.acinq.eclair.channel

import Scripts._
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.TypeDefs.Change
import lightning._

import scala.util.Try

/**
  * Created by PM on 20/05/2016.
  */
object Helpers {

  def reduce(ourCommitSpec: CommitmentSpec, ourChanges: List[Change], theirChanges: List[Change]): CommitmentSpec = {
    val spec0 = ourCommitSpec.copy(htlcs_in = Set(), htlcs_out = Set(), amount_us_msat = ourCommitSpec.initial_amount_us_msat, amount_them_msat = ourCommitSpec.initial_amount_them_msat)
    val spec1 = ourChanges.foldLeft(spec0) {
      case (spec, htlc: update_add_htlc) => spec.copy(amount_us_msat = spec.amount_us_msat - htlc.amountMsat, htlcs_out = spec.htlcs_out + htlc)
      case (spec, _) => spec
    }
    val spec2 = theirChanges.foldLeft(spec1) {
      case (spec, htlc: update_add_htlc) => spec.copy(amount_them_msat = spec.amount_them_msat - htlc.amountMsat, htlcs_in = spec.htlcs_in + htlc)
      case (spec, _) => spec
    }
    val spec3 = ourChanges.collect {
      case f: update_fulfill_htlc => (f, spec2.htlcs_in.find(_.id == f.id))
      case f: update_fail_htlc => (f, spec2.htlcs_in.find(_.id == f.id))
    }.foldLeft(spec2) {
      case (spec, (u: update_fulfill_htlc, Some(htlc))) => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs_in = spec.htlcs_in - htlc)
      case (spec, (u: update_fail_htlc, Some(htlc))) => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs_in = spec.htlcs_in - htlc)
      case (spec, _) => spec
    }
    val spec4 = theirChanges.collect {
      case f: update_fulfill_htlc => (f, spec2.htlcs_out.find(_.id == f.id))
      case f: update_fail_htlc => (f, spec2.htlcs_out.find(_.id == f.id))
    }.foldLeft(spec3) {
      case (spec, (u: update_fulfill_htlc, Some(htlc))) => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs_out = spec.htlcs_out - htlc)
      case (spec, (u: update_fail_htlc, Some(htlc))) => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs_out = spec.htlcs_out - htlc)
      case (spec, _) => spec
    }
    spec4
  }

  def makeOurTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], ourRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeTheirTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, spec)

  def sign(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorAmount: Long, tx: Transaction): signature =
    bin2signature(Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey))

  def addSigs(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorAmount: Long, tx: Transaction, ourSig: signature, theirSig: signature): Transaction = {
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey)
    val witness = witness2of2(theirSig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey)
    tx.copy(witness = Seq(witness))
  }

  def checksig(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorOutput: TxOut, tx: Transaction): Boolean =
    true

  // TODO : Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess

  def isMutualClose(tx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: OurCommit): Boolean = {
    // we rebuild the closing tx as seen by both parties
    //TODO we should use the closing fee in pkts
    //val closingState = commitment.state.adjust_fees(Globals.closing_fee * 1000, ourParams.anchorAmount.isDefined)
    //val finalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, closingState)
    val finalTx: Transaction = null // = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
    // and only compare the outputs
    tx.txOut == finalTx.txOut
  }

  def isOurCommit(tx: Transaction, commitment: OurCommit): Boolean = tx == commitment.publishableTx

  def isTheirCommit(tx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: TheirCommit): Boolean = {
    // we rebuild their commitment tx
    val theirTx = makeTheirTx(ourParams, theirParams, tx.txIn, commitment.theirRevocationHash, commitment.spec)
    // and only compare the outputs
    tx.txOut == theirTx.txOut
  }

  def isRevokedCommit(tx: Transaction): Boolean = {
    // TODO : for now we assume that every published tx which is none of (mutualclose, ourcommit, theircommit) is a revoked commit
    // which means ERR_INFORMATION_LEAK will never occur
    true
  }

  /*def handle_cmd_close(cmd: CMD_CLOSE, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): close_channel = {
  val closingState = commitment.state.adjust_fees(cmd.fee * 1000, ourParams.anchorAmount.isDefined)
  val finalTx = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
  val ourSig = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
  val anchorTxId = commitment.tx.txIn(0).outPoint.txid // commit tx only has 1 input, which is the anchor
  close_channel(ourSig, cmd.fee)
  }*/

  /*def handle_pkt_close(pkt: close_channel, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): (Transaction, close_channel_complete) = {
  val closingState = commitment.state.adjust_fees(pkt.closeFee * 1000, ourParams.anchorAmount.isDefined)
  val finalTx = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
  val ourSig = Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
  val signedFinalTx = finalTx.updateSigScript(0, sigScript2of2(pkt.sig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey))
  (signedFinalTx, close_channel_complete(ourSig))
  }*/

}
