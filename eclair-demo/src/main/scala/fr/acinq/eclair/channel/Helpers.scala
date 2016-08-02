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
      case Some(htlc) if direction == OUT => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  // OUT means we are sending an update_fail_htlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, update: update_fail_htlc): CommitmentSpec = {
    spec.htlcs.find(_.id == update.id) match {
      case Some(htlc) if direction == OUT => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
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

  def makeTheirTxTemplate(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): TxTemplate =
    makeCommitTxTemplate(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, spec)

  def makeTheirTx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction =
    makeTheirTxTemplate(ourParams, theirParams, inputs, theirRevocationHash, spec).makeTx

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

  def checkCloseSignature(closeSig: BinaryData, closeFee: Satoshi, d: DATA_NEGOTIATING): Try[Transaction] = {
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
    * Claim a revoked commit tx using the matching revocation preimage, which allows us to claim all its inputs without a
    * delay
    * @param theirTxTemplate revoked commit tx template
    * @param revocationPreimage revocation preimage (which must match this specific commit tx)
    * @param privateKey private key to send the claimed funds to (the returned tx will include a single P2WPKH output)
    * @return a signed transaction that spends the revoked commit tx
    */
  def claimRevokedCommitTx(theirTxTemplate: TxTemplate, revocationPreimage: BinaryData, privateKey: BinaryData): Transaction = {
    val theirTx = theirTxTemplate.makeTx
    val outputs = collection.mutable.ListBuffer.empty[TxOut]

    // first, find out how much we can claim
    val outputsToClaim = (theirTxTemplate.ourOutput.toSeq ++ theirTxTemplate.htlcReceived ++ theirTxTemplate.htlcSent).filter(o => theirTx.txOut.indexOf(o.txOut) != -1)
    val totalAmount = outputsToClaim.map(_.amount).sum // TODO: substract a small network fee

    // create a tx that sends everything to our private key
    val tx = Transaction(version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(totalAmount, pay2wpkh(Crypto.publicKeyFromPrivateKey(privateKey))) :: Nil,
      witness = Seq.empty[ScriptWitness],
      lockTime = 0)

    // create tx inputs that spend each output that we can spend
    val inputs = outputsToClaim.map(outputTemplate => {
      val index = theirTx.txOut.indexOf(outputTemplate.txOut)
      TxIn(OutPoint(theirTx, index), signatureScript = BinaryData.empty, sequence = 0xffffffffL)
    })
    assert(inputs.length == outputsToClaim.length)

    // and sign them
    val tx1 = tx.copy(txIn = inputs)
    val witnesses = for(i <- 0 until tx1.txIn.length) yield {
      val sig = Transaction.signInput(tx1, i, outputsToClaim(i).redeemScript, SIGHASH_ALL, outputsToClaim(i).amount, 1, privateKey)
      val witness = ScriptWitness(sig :: revocationPreimage :: outputsToClaim(i).redeemScript :: Nil)
      witness
    }

    tx1.copy(witness = witnesses)
  }
}
