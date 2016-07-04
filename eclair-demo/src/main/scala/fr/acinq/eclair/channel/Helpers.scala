package fr.acinq.eclair.channel

import Scripts._
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.TypeDefs.Change
import fr.acinq.eclair.crypto.ShaChain
import lightning._

import scala.util.Try

/**
  * Created by PM on 20/05/2016.
  */
object Helpers {

  def removeHtlc(changes: List[Change], id: Long): List[Change] = changes.filterNot(_ match {
    case u: update_add_htlc if u.id == id => true
    case _ => false
  })

  def addHtlc(spec: CommitmentSpec, direction: Direction, update: update_add_htlc): CommitmentSpec = {
    val htlc = Htlc(direction, update.id, update.amountMsat, update.rHash, update.expiry, previousChannelId = None)
    direction match {
      case OUT => spec.copy(amount_us_msat = spec.amount_us_msat - htlc.amountMsat, htlcs = spec.htlcs + htlc)
      case IN => spec.copy(amount_them_msat = spec.amount_them_msat - htlc.amountMsat, htlcs = spec.htlcs + htlc)
    }
  }

  // OUT means we are sending an update_fulfill_htlc message which means that we are fulfilling an HTLC that they sent
  def fulfillHtlc(spec: CommitmentSpec, direction: Direction, update: update_fulfill_htlc): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.id == update.id && htlc.rHash == bin2sha256(Crypto.sha256(update.r))) match {
      case Some(htlc) => direction match {
        case OUT => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
        case IN => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      }
    }
  }

  // OUT means we are sending an update_fail_htlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, update: update_fail_htlc): CommitmentSpec = {
    spec.htlcs.find(_.id == update.id) match {
      case Some(htlc) => direction match {
        case OUT => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
        case IN => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      }
    }
  }

  def reduce(ourCommitSpec: CommitmentSpec, ourChanges: List[Change], theirChanges: List[Change]): CommitmentSpec = {
    val spec = ourCommitSpec.copy(htlcs = Set(), amount_us_msat = ourCommitSpec.initial_amount_us_msat, amount_them_msat = ourCommitSpec.initial_amount_them_msat)
    val spec1 = ourChanges.foldLeft(spec) {
      case (spec, u: update_add_htlc) => addHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec2 = theirChanges.foldLeft(spec1) {
      case (spec, u: update_add_htlc) => addHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    val spec3 = ourChanges.foldLeft(spec2) {
      case (spec, u: update_fulfill_htlc) => fulfillHtlc(spec, OUT, u)
      case (spec, u: update_fail_htlc) => failHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec4 = theirChanges.foldLeft(spec3) {
      case (spec, u: update_fulfill_htlc) => fulfillHtlc(spec, IN, u)
      case (spec, u: update_fail_htlc) => failHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    spec4
  }

  def makeOurTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], ourRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeTheirTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeCommitTx(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, spec)

  def sign(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorAmount: Satoshi, tx: Transaction): signature =
    bin2signature(Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey))

  def addSigs(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorAmount: Satoshi, tx: Transaction, ourSig: signature, theirSig: signature): Transaction = {
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey)
    val witness = witness2of2(theirSig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey)
    tx.copy(witness = Seq(witness))
  }

  def checksig(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorOutput: TxOut, tx: Transaction): Try[Unit] =
    Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def checkCloseSignature(closeSig: BinaryData, closeFee: Satoshi, d: DATA_NEGOCIATING): Try[Transaction] = {
    val (finalTx, ourCloseSig) = Helpers.makeFinalTx(d.commitments, d.ourClearing.scriptPubkey, d.theirClearing.scriptPubkey, closeFee)
    val signedTx = addSigs(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput.amount, finalTx, ourCloseSig.sig, closeSig)
    checksig(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput, signedTx).map(_ => signedTx)
  }

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
    val ourSig = Helpers.sign(commitments.ourParams, commitments.theirParams, commitments.anchorOutput.amount, finalTx)
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

  def revocationPreimage(seed: BinaryData, index: Long): BinaryData = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: BinaryData, index: Long): BinaryData = Crypto.sha256(revocationPreimage(seed, index))

  /**
    * Claim their revoked commit tx. If they published a revoked commit tx, we should be able to "steal" it with one
    * of the revocation preimages that we received.
    * Remainder: their commit tx sends:
    * - our money to our final key
    * - their money to (their final key + our delay) OR (our final key + secret)
    * We don't have anything to do about our output (which should probably show up in our bitcoin wallet), can steal their
    * money if we can find the preimage.
    * We use a basic brute force algorithm: try all the preimages that we have until we find a match
    *
    * @param commitTx    their revoked commit tx
    * @param commitments our commitment data
    * @return an optional transaction which "steals" their output
    */
  def claimTheirRevokedCommit(commitTx: Transaction, commitments: Commitments): Option[Transaction] = {

    // this is what their output script looks like
    def theirOutputScript(preimage: BinaryData) = {
      val revocationHash = Crypto.sha256(preimage)
      redeemSecretOrDelay(commitments.theirParams.finalPubKey, locktime2long_csv(commitments.theirParams.delay), commitments.ourParams.finalPubKey, revocationHash)
    }

    // find an output that we can claim with one of our preimages
    // the only that we're looking for is a pay-to-script (pay2wsh) so for each output that we try we need to generate
    // all possible output scripts, hash them and see if they match
    def findTheirOutputPreimage: Option[(Int, BinaryData)] = {
      for (i <- 0 until commitTx.txOut.length) {
        val actual = Script.parse(commitTx.txOut(i).publicKeyScript)
        val preimage = commitments.theirPreimages.iterator.find(preimage => {
          val expected = theirOutputScript(preimage)
          val hashOfExpected = pay2wsh(expected)
          hashOfExpected == actual
        })
        preimage.map(value => return Some(i, value))
      }
      None
    }

    findTheirOutputPreimage map {
      case (index, preimage) =>
        // TODO: substract network fee
        val amount = commitTx.txOut(index).amount
        val tx = Transaction(version = 2,
          txIn = TxIn(OutPoint(commitTx, index), BinaryData.empty, TxIn.SEQUENCE_FINAL) :: Nil,
          txOut = TxOut(amount, pay2pkh(commitments.ourParams.finalPubKey)) :: Nil,
          lockTime = 0xffffffffL)
        val redeemScript: BinaryData = Script.write(theirOutputScript(preimage))
        val sig: BinaryData = Transaction.signInput(tx, 0, redeemScript, SIGHASH_ALL, amount, 1, commitments.ourParams.finalPrivKey, randomize = false)
        val witness = ScriptWitness(sig :: preimage :: redeemScript :: Nil)
        val tx1 = tx.copy(witness = Seq(witness))

        // check that we can actually spend the commit tx
        Transaction.correctlySpends(tx1, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        tx1
    }
  }
}
