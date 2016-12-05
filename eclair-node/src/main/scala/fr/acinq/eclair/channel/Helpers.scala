package fr.acinq.eclair.channel

import Scripts._
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.TypeDefs.Change
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.wire.{ClosingSigned, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import lightning._

import scala.annotation.tailrec
import scala.util.Try

/**
  * Created by PM on 20/05/2016.
  */
object Helpers {

  def removeHtlc(changes: List[Change], id: Long): List[Change] = changes.filterNot(_ match {
    case u: UpdateAddHtlc if u.id == id => true
    case _ => false
  })

  def addHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateAddHtlc): CommitmentSpec = {
    val htlc = Htlc(direction, update, previousChannelId = None)
    direction match {
      case OUT => spec.copy(amount_us_msat = spec.amount_us_msat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
      case IN => spec.copy(amount_them_msat = spec.amount_them_msat - htlc.add.amountMsat, htlcs = spec.htlcs + htlc)
    }
  }

  // OUT means we are sending an UpdateFulfillHtlc message which means that we are fulfilling an HTLC that they sent
  def fulfillHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateFulfillHtlc): CommitmentSpec = {
    spec.htlcs.find(htlc => htlc.add.id == update.id && htlc.add.paymentHash == bin2sha256(Crypto.sha256(update.paymentPreimage))) match {
      case Some(htlc) if direction == OUT => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  // OUT means we are sending an UpdateFailHtlc message which means that we are failing an HTLC that they sent
  def failHtlc(spec: CommitmentSpec, direction: Direction, update: UpdateFailHtlc): CommitmentSpec = {
    spec.htlcs.find(_.add.id == update.id) match {
      case Some(htlc) if direction == OUT => spec.copy(amount_them_msat = spec.amount_them_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case Some(htlc) if direction == IN => spec.copy(amount_us_msat = spec.amount_us_msat + htlc.add.amountMsat, htlcs = spec.htlcs - htlc)
      case None => throw new RuntimeException(s"cannot find htlc id=${update.id}")
    }
  }

  def reduce(ourCommitSpec: CommitmentSpec, ourChanges: List[Change], theirChanges: List[Change]): CommitmentSpec = {
    val spec1 = ourChanges.foldLeft(ourCommitSpec) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec2 = theirChanges.foldLeft(spec1) {
      case (spec, u: UpdateAddHtlc) => addHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    val spec3 = ourChanges.foldLeft(spec2) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, OUT, u)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, OUT, u)
      case (spec, _) => spec
    }
    val spec4 = theirChanges.foldLeft(spec3) {
      case (spec, u: UpdateFulfillHtlc) => fulfillHtlc(spec, IN, u)
      case (spec, u: UpdateFailHtlc) => failHtlc(spec, IN, u)
      case (spec, _) => spec
    }
    spec4
  }

  def makeOurTxTemplate(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], ourRevocationHash: sha256_hash, spec: CommitmentSpec): TxTemplate = ???
    //makeCommitTxTemplate(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeOurTx(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], ourRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction = ???
    //makeCommitTx(inputs, ourParams.finalPubKey, theirParams.finalPubKey, ourParams.delay, ourRevocationHash, spec)

  def makeTheirTxTemplate(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): TxTemplate = ???
    //makeCommitTxTemplate(inputs, theirParams.finalPubKey, ourParams.finalPubKey, theirParams.delay, theirRevocationHash, spec)

  def makeTheirTx(localParams: LocalParams, RemoteParams: RemoteParams, inputs: Seq[TxIn], theirRevocationHash: sha256_hash, spec: CommitmentSpec): Transaction = ???
    //makeTheirTxTemplate(ourParams, theirParams, inputs, theirRevocationHash, spec).makeTx

  def sign(localParams: LocalParams, RemoteParams: RemoteParams, anchorAmount: Satoshi, tx: Transaction): BinaryData = ???
    //bin2signature(Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey))

  def addSigs(localParams: LocalParams, RemoteParams: RemoteParams, anchorAmount: Satoshi, tx: Transaction, ourSig: signature, theirSig: signature): Transaction = ??? /*{
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey)
    val witness = witness2of2(theirSig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey)
    tx.updateWitness(0, witness)
  }*/

  def checksig(localParams: LocalParams, RemoteParams: RemoteParams, anchorOutput: TxOut, tx: Transaction): Try[Unit] =
    Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def checkCloseSignature(closeSig: BinaryData, closeFee: Satoshi, d: DATA_NEGOTIATING_2): Try[Transaction] = ??? /*{
    val (finalTx, ourCloseSig) = Helpers.makeFinalTx(d.commitments, d.ourShutdown.scriptPubkey, d.theirShutdown.scriptPubkey, closeFee)
    val signedTx = addSigs(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput.amount, finalTx, ourCloseSig.sig, closeSig)
    checksig(d.commitments.ourParams, d.commitments.theirParams, d.commitments.anchorOutput, signedTx).map(_ => signedTx)
  }*/

  def isMutualClose(tx: Transaction, localParams: LocalParams, RemoteParams: RemoteParams, commitment: OurCommit): Boolean = {
    // we rebuild the closing tx as seen by both parties
    //TODO we should use the closing fee in pkts
    //val closingState = commitment.state.adjust_fees(Globals.closing_fee * 1000, ourParams.anchorAmount.isDefined)
    //val finalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, closingState)
    val finalTx: Transaction = null // = makeFinalTx(commitment.tx.txIn, ourParams.finalPubKey, theirParams.finalPubKey, closingState)
    // and only compare the outputs
    tx.txOut == finalTx.txOut
  }

  def isOurCommit(tx: Transaction, commitment: OurCommit): Boolean = tx == commitment.publishableTx

  def isTheirCommit(tx: Transaction, localParams: LocalParams, RemoteParams: RemoteParams, commitment: TheirCommit): Boolean = ???/*{
    // we rebuild their commitment tx
    val theirTx = makeTheirTx(ourParams, theirParams, tx.txIn, commitment.theirRevocationHash, commitment.spec)
    // and only compare the outputs
    tx.txOut == theirTx.txOut
  }*/

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
    * @return a (final tx, fee, our signature) tuple. The tx is not signed.
    */
  def makeFinalTx(commitments: Commitments, ourScriptPubKey: BinaryData, theirScriptPubKey: BinaryData, closeFee: Satoshi): (Transaction, Long, BinaryData) = ???/*{
    val amount_us = Satoshi(commitments.ourCommit.spec.amount_us_msat / 1000)
    val amount_them = Satoshi(commitments.theirCommit.spec.amount_us_msat / 1000)
    val finalTx = Scripts.makeFinalTx(commitments.ourCommit.publishableTx.txIn, ourScriptPubKey, theirScriptPubKey, amount_us, amount_them, closeFee)
    val ourSig = Helpers.sign(commitments.ourParams, commitments.theirParams, commitments.anchorOutput.amount, finalTx)
    (finalTx, close_signature(closeFee.toLong, ourSig))
  }*/

  /**
    *
    * @param commitments
    * @param ourScriptPubKey
    * @param theirScriptPubKey
    * @return a (final tx, fee, our signature) tuple. The tx is not signed. Bitcoin fees will be copied from our
    *         last commit tx
    */
  def makeFinalTx(commitments: Commitments, ourScriptPubKey: BinaryData, theirScriptPubKey: BinaryData): (Transaction, Long, BinaryData) = {
    val commitFee = commitments.anchorOutput.amount.toLong - commitments.ourCommit.publishableTx.txOut.map(_.amount.toLong).sum
    val closeFee = Satoshi(2 * (commitFee / 4))
    makeFinalTx(commitments, ourScriptPubKey, theirScriptPubKey, closeFee)
  }

  def revocationPreimage(seed: BinaryData, index: Long): BinaryData = ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - index)

  def revocationHash(seed: BinaryData, index: Long): BinaryData = Crypto.sha256(revocationPreimage(seed, index))

  /**
    * Claim a revoked commit tx using the matching revocation preimage, which allows us to claim all its inputs without a
    * delay
    *
    * @param theirTxTemplate    revoked commit tx template
    * @param revocationPreimage revocation preimage (which must match this specific commit tx)
    * @param privateKey         private key to send the claimed funds to (the returned tx will include a single P2WPKH output)
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
      lockTime = 0)

    // create tx inputs that spend each output that we can spend
    val inputs = outputsToClaim.map(outputTemplate => {
      val index = theirTx.txOut.indexOf(outputTemplate.txOut)
      TxIn(OutPoint(theirTx, index), signatureScript = BinaryData.empty, sequence = 0xffffffffL)
    })
    assert(inputs.length == outputsToClaim.length)

    // and sign them
    val tx1 = tx.copy(txIn = inputs)
    val witnesses = for (i <- 0 until tx1.txIn.length) yield {
      val sig = Transaction.signInput(tx1, i, outputsToClaim(i).redeemScript, SIGHASH_ALL, outputsToClaim(i).amount, 1, privateKey)
      val witness = ScriptWitness(sig :: revocationPreimage :: outputsToClaim(i).redeemScript :: Nil)
      witness
    }

    tx1.updateWitnesses(witnesses)
  }

  /**
    * claim an HTLC that we received using its payment preimage. This is used only when the other party publishes its
    * current commit tx which contains pending HTLCs.
    *
    * @param tx              commit tx published by the other party
    * @param htlcTemplate    HTLC template for an HTLC in the commit tx for which we have the preimage
    * @param paymentPreimage HTLC preimage
    * @param privateKey      private key which matches the pubkey  that the HTLC was sent to
    * @return a signed transaction that spends the HTLC in their published commit tx.
    *         This tx is not spendable right away: it has both an absolute CLTV time-out and a relative CSV time-out
    *         before which it can be published
    */
  def claimReceivedHtlc(tx: Transaction, htlcTemplate: HtlcTemplate, paymentPreimage: BinaryData, privateKey: BinaryData): Transaction = {
    require(htlcTemplate.htlc.add.paymentHash == BinaryData(Crypto.sha256(paymentPreimage)), "invalid payment preimage")
    // find its index in their tx
    val index = tx.txOut.indexOf(htlcTemplate.txOut)

    val tx1 = Transaction(version = 2,
      txIn = TxIn(OutPoint(tx, index), BinaryData.empty, sequence = Scripts.toSelfDelay2csv(htlcTemplate.delay)) :: Nil,
      txOut = TxOut(htlcTemplate.amount, Scripts.pay2pkh(Crypto.publicKeyFromPrivateKey(privateKey))) :: Nil,
      lockTime = ??? /*Scripts.locktime2long_cltv(htlcTemplate.htlc.add.expiry)*/)

    val sig = Transaction.signInput(tx1, 0, htlcTemplate.redeemScript, SIGHASH_ALL, htlcTemplate.amount, 1, privateKey)
    val witness = ScriptWitness(sig :: paymentPreimage :: htlcTemplate.redeemScript :: Nil)
    val tx2 = tx1.updateWitness(0, witness)
    tx2
  }

  /**
    * claim all the HTLCs that we've received from their current commit tx
    *
    * @param txTemplate  commit tx published by the other party
    * @param commitments our commitment data, which include payment preimages
    * @return a list of transactions (one per HTLC that we can claim)
    */
  def claimReceivedHtlcs(tx: Transaction, txTemplate: TxTemplate, commitments: Commitments): Seq[Transaction] = {
    val preImages = commitments.ourChanges.all.collect { case UpdateFulfillHtlc(_, id, paymentPreimage) => paymentPreimage }
    // TODO: FIXME !!!
    //val htlcTemplates = txTemplate.htlcSent
    val htlcTemplates = txTemplate.htlcReceived ++ txTemplate.htlcSent

    //@tailrec
    def loop(htlcs: Seq[HtlcTemplate], acc: Seq[Transaction] = Seq.empty[Transaction]): Seq[Transaction] = ???/*{
      htlcs.headOption match {
        case Some(head) =>
          preImages.find(preImage => head.htlc.add.rHash == bin2sha256(Crypto.sha256(preImage))) match {
            case Some(preImage) => loop(htlcs.tail, claimReceivedHtlc(tx, head, preImage, commitments.ourParams.finalPrivKey) +: acc)
            case None => loop(htlcs.tail, acc)
          }
        case None => acc
      }
    }*/
    loop(htlcTemplates)
  }

  def claimSentHtlc(tx: Transaction, htlcTemplate: HtlcTemplate, privateKey: BinaryData): Transaction = {
    val index = tx.txOut.indexOf(htlcTemplate.txOut)
    val tx1 = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(tx, index), Array.emptyByteArray, sequence = Scripts.toSelfDelay2csv(htlcTemplate.delay)) :: Nil,
      txOut = TxOut(htlcTemplate.amount, Scripts.pay2pkh(Crypto.publicKeyFromPrivateKey(privateKey))) :: Nil,
      lockTime = ???/*Scripts.locktime2long_cltv(htlcTemplate.htlc.add.expiry)*/)

    val sig = Transaction.signInput(tx1, 0, htlcTemplate.redeemScript, SIGHASH_ALL, htlcTemplate.amount, 1, privateKey)
    val witness = ScriptWitness(sig :: Hash.Zeroes :: htlcTemplate.redeemScript :: Nil)
    tx1.updateWitness(0, witness)
  }

  def claimSentHtlcs(tx: Transaction, txTemplate: TxTemplate, commitments: Commitments): Seq[Transaction] = ???/*{
    // txTemplate could be our template (we published our commit tx) or their template (they published their commit tx)
    val htlcs1 = txTemplate.htlcSent.filter(_.ourKey == commitments.ourParams.finalPubKey)
    val htlcs2 = txTemplate.htlcReceived.filter(_.theirKey == commitments.ourParams.finalPubKey)
    val htlcs = htlcs1 ++ htlcs2
    htlcs.map(htlcTemplate => claimSentHtlc(tx, htlcTemplate, commitments.ourParams.finalPrivKey))
  }*/
}
