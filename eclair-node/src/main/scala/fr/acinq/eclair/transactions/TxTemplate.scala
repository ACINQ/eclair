package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, Crypto, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script, ScriptElt, Transaction, TxIn, TxOut}
import fr.acinq.eclair.transactions.OldScripts._

/**
  * Created by PM on 06/12/2016.
  */
sealed trait TxTemplate

case class CommitTxTemplate(inputs: Seq[TxIn], localOutput: Option[OutputTemplate], remoteOutput: Option[OutputTemplate], htlcSent: Seq[HTLCTemplate], htlcReceived: Seq[HTLCTemplate]) extends TxTemplate {
  def makeTx: Transaction = {
    val outputs = localOutput.toSeq ++ remoteOutput.toSeq ++ htlcSent ++ htlcReceived
    val tx = Transaction(
      version = 2,
      txIn = inputs,
      txOut = outputs.map(_.txOut),
      lockTime = 0
    )
    permuteOutputs(tx)
  }

  /**
    *
    * @return true is their is an output that we can claim: either our output or any HTLC (even the ones that we sent
    *         could be claimed by us if the tx is revoked and we have the revocation preimage)
    */
  def weHaveAnOutput: Boolean = localOutput.isDefined || !htlcReceived.isEmpty || !htlcSent.isEmpty
}

object CommitTxTemplate {

  /**
    * Creates a commitment publishable by 'Local' (meaning that main output to local is delayed)
    * @param inputs
    * @param localRevocationPubkey
    * @param toLocalDelay
    * @param localPubkey
    * @param remotePubkey
    * @param spec
    * @return
    */
  def makeCommitTxTemplate(inputs: Seq[TxIn], localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, spec: CommitmentSpec): CommitTxTemplate = {

    // TODO: no fees!!!
    val (toLocal: Satoshi, toRemote: Satoshi) = (Satoshi(spec.to_local_msat / 1000), Satoshi(spec.to_remote_msat / 1000))

    val toLocalDelayedOutputScript = OutputScripts.toLocal(localRevocationPubkey, toLocalDelay, localPubkey)
    val toLocalDelayedOutput_opt = if (spec.to_local_msat >= 546000) Some(P2WSHTemplate(toLocal, toLocalDelayedOutputScript)) else None

    val toRemoteOutputScript = OutputScripts.toRemote(remotePubkey)
    val toRemoteOutput_opt = if (spec.to_remote_msat >= 546000) Some(P2WSHTemplate(toRemote, toRemoteOutputScript)) else None

    assert(spec.htlcs.isEmpty, "not implemented")

    CommitTxTemplate(inputs, toLocalDelayedOutput_opt, toRemoteOutput_opt, Nil, Nil)
  }

  /*def makeCommitTxTemplate(inputs: Seq[TxIn], ourFinalKey: BinaryData, theirFinalKey: BinaryData, theirDelay: Int, revocationHash: BinaryData, commitmentSpec: CommitmentSpec): CommitTxTemplate = {
    val redeemScript = redeemSecretOrDelay(ourFinalKey, toSelfDelay2csv(theirDelay), theirFinalKey, revocationHash: BinaryData)
    val htlcs = commitmentSpec.htlcs.filter(_.add.amountMsat >= 546000).toSeq
    val fee_msat = computeFee(commitmentSpec.feeRate, htlcs.size) * 1000
    val (amount_us_msat: Long, amount_them_msat: Long) = (commitmentSpec.to_local_msat, commitmentSpec.to_remote_msat) match {
      case (us, them) if us >= fee_msat / 2 && them >= fee_msat / 2 => (us - fee_msat / 2, them - fee_msat / 2)
      case (us, them) if us < fee_msat / 2 => (0L, Math.max(0L, them - fee_msat + us))
      case (us, them) if them < fee_msat / 2 => (Math.max(us - fee_msat + them, 0L), 0L)
    }

    // our output is a pay2wsh output than can be claimed by them if they know the preimage, or by us after a delay
    // when * they * publish a revoked commit tx, we use the preimage that they sent us to claim it
    val ourOutput = if (amount_us_msat >= 546000) Some(P2WSHTemplate(Satoshi(amount_us_msat / 1000), redeemScript)) else None

    // their output is a simple pay2pkh output that sends money to their final key and can only be claimed by them
    // when * they * publish a revoked commit tx we don't have anything special to do about it
    val theirOutput = if (amount_them_msat >= 546000) Some(P2WPKHTemplate(Satoshi(amount_them_msat / 1000), theirFinalKey)) else None

    val sendOuts: Seq[HTLCTemplate] = htlcs.filter(_.direction == OUT).map(htlc => {
      HTLCTemplate(htlc, ourFinalKey, theirFinalKey, theirDelay, revocationHash)
    })
    val receiveOuts: Seq[HTLCTemplate] = htlcs.filter(_.direction == IN).map(htlc => {
      HTLCTemplate(htlc, ourFinalKey, theirFinalKey, theirDelay, revocationHash)
    })
    CommitTxTemplate(inputs, ourOutput, theirOutput, sendOuts, receiveOuts)
  }*/
}

case class HTLCSuccessTxTemplate() extends TxTemplate

case class HTLCTimeoutTxTemplate() extends TxTemplate

sealed trait OutputTemplate {
  def amount: Satoshi

  def txOut: TxOut

  // this is the actual script that must be used to claim this output
  def redeemScript: BinaryData
}

case class HTLCTemplate(htlc: Htlc, ourKey: BinaryData, theirKey: BinaryData, delay: Int, revocationHash: BinaryData) extends OutputTemplate {
  override def amount = Satoshi(htlc.add.amountMsat / 1000)

  override def redeemScript = htlc.direction match {
    case IN => Script.write(OldScripts.scriptPubKeyHtlcReceive(ourKey, theirKey, expiry2cltv(htlc.add.expiry), toSelfDelay2csv(delay), htlc.add.paymentHash, revocationHash))
    case OUT => Script.write(OldScripts.scriptPubKeyHtlcSend(ourKey, theirKey, expiry2cltv(htlc.add.expiry), toSelfDelay2csv(delay), htlc.add.paymentHash, revocationHash))
  }

  override def txOut = TxOut(amount, pay2wsh(redeemScript))
}

case class P2WSHTemplate(amount: Satoshi, script: BinaryData) extends OutputTemplate {
  override def txOut: TxOut = TxOut(amount, pay2wsh(script))

  override def redeemScript = script
}

object P2WSHTemplate {
  def apply(amount: Satoshi, script: Seq[ScriptElt]): P2WSHTemplate = new P2WSHTemplate(amount, Script.write(script))
}

case class P2WPKHTemplate(amount: Satoshi, publicKey: BinaryData) extends OutputTemplate {
  override def txOut: TxOut = TxOut(amount, pay2wpkh(publicKey))

  override def redeemScript = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(publicKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
}