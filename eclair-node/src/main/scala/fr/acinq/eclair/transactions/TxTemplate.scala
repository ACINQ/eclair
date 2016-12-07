package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, Crypto, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script, ScriptElt, Transaction, TxIn, TxOut}
import OldScripts._

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