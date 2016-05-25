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

  def isAddHtlc(change: Change) = change match {
    case u:update_add_htlc => true
    case _ => false
  }

  def findHtlc(changes: List[Change], id: Long): Option[Change] = changes.find(_ match {
    case u:update_add_htlc if u.id == id => true
    case _ => false
  })

  def findHtlc(changes: List[Change], id: Long, r: sha256_hash): Option[Change] = changes.find(_ match {
    case u:update_add_htlc if u.id == id && u.rHash == bin2sha256(Crypto.sha256(r)) => true
    case u:update_add_htlc if u.id == id => throw new RuntimeException(s"invalid htlc preimage for htlc $id")
    case _ => false
  })

  def removeHtlc(changes: List[Change], id: Long) : List[Change] = changes.filterNot(_ match {
    case u: update_add_htlc if u.id == id => true
    case _ => false
  })

  def addHtlc(spec: CommitmentSpec, direction: Direction, update: update_add_htlc) : CommitmentSpec = {
    val htlc = Htlc(direction, update.id, update.amountMsat, update.rHash, update.expiry, previousChannelId = None)
    direction match {
      case OUT => spec.copy(amount_us_msat =  spec.amount_us_msat - htlc.amountMsat, htlcs = spec.htlcs + htlc)
      case IN => spec.copy(amount_them_msat = spec.amount_them_msat - htlc.amountMsat, htlcs = spec.htlcs + htlc)
    }
  }

  // OUT means we are sending an update_fulfill_htlc message which means that we are fulfilling an HTLC that they sent
  def fulfillHtlc(spec: CommitmentSpec, direction: Direction, update: update_fulfill_htlc) : CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.id == update.id && htlc.rHash == bin2sha256(Crypto.sha256(update.r))) match {
      case Some(htlc) => direction match {
        case OUT => spec.copy(amount_us_msat =  spec.amount_us_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
        case IN => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      }
    }
  }

  // OUT means we are sending an update_fail_htlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, update: update_fail_htlc) : CommitmentSpec = {
    spec.htlcs.find(_.id == update.id) match {
      case Some(htlc) => direction match {
        case OUT => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
        case IN => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      }
    }
  }

  def reduce(ourCommitSpec: CommitmentSpec, ourChanges: List[Change], theirChanges: List[Change]): CommitmentSpec = {
    val spec = ourCommitSpec.copy(htlcs = Set(), amount_us_msat = ourCommitSpec.initial_amount_us_msat, amount_them_msat = ourCommitSpec.initial_amount_them_msat)
    val spec1 = ourChanges.filter(isAddHtlc).foldLeft(spec)( (spec, change) => change match {
      case u: update_add_htlc => addHtlc(spec, OUT, u)
      case u: update_fulfill_htlc => fulfillHtlc(spec, OUT, u)
      case u: update_fail_htlc => failHtlc(spec, OUT, u)
    })
    val spec2 = theirChanges.filter(isAddHtlc).foldLeft(spec1)( (spec, change) => change match {
      case u: update_add_htlc => addHtlc(spec, IN, u)
      case u: update_fulfill_htlc => fulfillHtlc(spec, IN, u)
      case u: update_fail_htlc => failHtlc(spec, IN, u)
    })
    val spec3 = ourChanges.filterNot(isAddHtlc).foldLeft(spec2)( (spec, change) => change match {
      case u: update_add_htlc => addHtlc(spec, OUT, u)
      case u: update_fulfill_htlc => fulfillHtlc(spec, OUT, u)
      case u: update_fail_htlc => failHtlc(spec, OUT, u)
    })
    val spec4 = theirChanges.filterNot(isAddHtlc).foldLeft(spec3)( (spec, change) => change match {
      case u: update_add_htlc => addHtlc(spec, IN, u)
      case u: update_fulfill_htlc => fulfillHtlc(spec, IN, u)
      case u: update_fail_htlc => failHtlc(spec, IN, u)
    })
    spec4
  }

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
    Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess

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
