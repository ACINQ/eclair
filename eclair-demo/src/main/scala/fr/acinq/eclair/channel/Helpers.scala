package fr.acinq.eclair.channel

import Scripts._
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.TypeDefs.Change
import lightning.{sha256_hash, signature, update_add_htlc, update_fulfill_htlc}

import scala.util.Try

/**
  * Created by PM on 20/05/2016.
  */
object Helpers {

  def reduce(initialSpec: CommitmentSpec, in: List[Change], out: List[Change]): CommitmentSpec = ???

  /*{

     (in ++ out).sortBy().foldLeft(initialSpec.copy(htlcs = initialSpec.htlcs) {
       case (spec, f: update_add_htlc) => null
       case (spec, f: update_fulfill_htlc) => null
       case (spec, f: update_fail_htlc) => null
       case (spec, f: update_fee_htlc) => null
     }
     null

   in.foldLeft(initialSpec.copy(htlcs = initialSpec.htlcs) {
   case (spec, f: update_fulfill_htlc) =>
  }

  val new_htlcs = in.collect { case u: update_add_htlc => Htlc(IN, u.id, u.amountMsat, u.rHash, u.expiry, Nil, None) } ++
   out.collect { case u: update_add_htlc => Htlc(OUT, u.id, u.amountMsat, u.rHash, u.expiry, Nil, None) }


  in.foldLeft(initialSpec.copy(htlcs = initialSpec.htlcs ++ new_htlcs)) {
   case (spec, f: update_fulfill_htlc) =>
     val htlc = spec.htlcs.find(h => h.direction == OUT && h.id == f.id).getOrElse(???)
     spec.copy(htlcs = spec.htlcs - htlc, amount_them = spec.amount_them + )
  }*/
  //  .foldLeft(initialSpec.htlcs) { case (htlcs, add:) => //


  def makeOurTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], ourRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeTheirTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, spec)

  def sign(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorAmount: Long,  tx: Transaction): signature =
    bin2signature(Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey))

  def addSigs(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorAmount: Long,  tx: Transaction, ourSig: signature, theirSig: signature): Transaction = {
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
