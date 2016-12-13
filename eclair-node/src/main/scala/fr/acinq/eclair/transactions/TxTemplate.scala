package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, Crypto, OP_CHECKSIG, OP_DUP, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, OutPoint, Satoshi, Script, ScriptElt, Transaction, TxIn, TxOut}
import fr.acinq.eclair.transactions.Common._
import fr.acinq.eclair.transactions.OutputScripts._
import fr.acinq.eclair.wire.UpdateAddHtlc

/**
  * Created by PM on 06/12/2016.
  */
sealed trait TxTemplate {
  def makeTx: Transaction
}

case class CommitTxTemplate(inputs: Seq[TxIn], localOutput: Option[DelayedPaymentOutputTemplate], remoteOutput: Option[P2WPKHOutputTemplate], htlcOfferedOutputs: Seq[OfferedHTLCOutputTemplate], htlcReceivedOutputs: Seq[ReceivedHTLCOutputTemplate]) extends TxTemplate {
  def makeTx = {
    val outputs = localOutput.toSeq ++ remoteOutput.toSeq ++ htlcOfferedOutputs ++ htlcReceivedOutputs
    val tx = Transaction(
      version = 2,
      txIn = inputs,
      txOut = outputs.map(_.txOut),
      lockTime = 0
    )
    permuteOutputs(tx)
  }
}

case class HTLCSuccessTxTemplate(inputs: Seq[TxIn], successOutputTemplate: SuccessOrTimeoutOutputTemplate, locktime: Long) extends TxTemplate {
  def makeTx = Transaction(
    version = 2,
    txIn = inputs,
    txOut = successOutputTemplate.txOut :: Nil,
    lockTime = locktime)
}

case class HTLCTimeoutTxTemplate(inputs: Seq[TxIn], successOutputTemplate: SuccessOrTimeoutOutputTemplate) extends TxTemplate {
  def makeTx = Transaction(
    version = 2,
    txIn = inputs,
    txOut = successOutputTemplate.txOut :: Nil,
    lockTime = 0)
}

object CommitTxTemplate {

  /**
    * Creates a commitment publishable by 'Local' (meaning that main output to local is delayed)
    *
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

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = (Satoshi(spec.to_local_msat / 1000), Satoshi(spec.to_remote_msat / 1000))

    val toLocalDelayedOutput_opt = if (spec.to_local_msat >= 546000) Some(DelayedPaymentOutputTemplate(toLocalAmount, localRevocationPubkey, toLocalDelay, localPubkey)) else None

    val toRemoteOutput_opt = if (spec.to_remote_msat >= 546000) Some(P2WPKHOutputTemplate(toRemoteAmount, toRemote(remotePubkey))) else None

    val htlcSentOutputs = spec.htlcs.
      filter(_.direction == OUT)
      .map(htlc => OfferedHTLCOutputTemplate(htlc.add, localPubkey, remotePubkey)).toSeq

    val htlcReceivedOutputs = spec.htlcs
      .filter(_.direction == IN)
      .map(htlc => ReceivedHTLCOutputTemplate(htlc.add, localPubkey, remotePubkey, htlc.add.paymentHash)).toSeq

    CommitTxTemplate(inputs, toLocalDelayedOutput_opt, toRemoteOutput_opt, htlcSentOutputs, htlcReceivedOutputs)
  }

  def makeHtlcTxTemplates(commitTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, spec: CommitmentSpec): (Seq[HTLCTimeoutTxTemplate], Seq[HTLCSuccessTxTemplate]) = {

    // TODO: no fees!!!

    val commitTxId = commitTx.txid
    val indexed = commitTx.txOut.zipWithIndex.toMap

    val htlcTimeoutTxTemplates = spec.htlcs
      .filter(_.direction == OUT)
      .map(htlc => {
        val outputIndex = indexed(OfferedHTLCOutputTemplate(htlc.add, localPubkey, remotePubkey).txOut)
        val inputs = TxIn(OutPoint(commitTxId, outputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil
        HTLCTimeoutTxTemplate(inputs, SuccessOrTimeoutOutputTemplate(htlc.add, localRevocationPubkey, toLocalDelay, localPubkey))
      }).toSeq

    val htlcSuccessTxTemplates = spec.htlcs
      .filter(_.direction == OUT)
      .map(htlc => {
        val outputIndex = indexed(OfferedHTLCOutputTemplate(htlc.add, localPubkey, remotePubkey).txOut)
        val inputs = TxIn(OutPoint(commitTxId, outputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil
        HTLCSuccessTxTemplate(inputs, SuccessOrTimeoutOutputTemplate(htlc.add, localRevocationPubkey, toLocalDelay, localPubkey), htlc.add.expiry)
      }).toSeq

    (htlcTimeoutTxTemplates, htlcSuccessTxTemplates)
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

sealed trait OutputTemplate {
  def amount: Satoshi

  def txOut: TxOut

  // this is the actual script that must be used to claim this output
  def redeemScript: BinaryData
}

class P2WSHOutputTemplate(val amount: Satoshi, val script: BinaryData) extends OutputTemplate {

  def this(amount: Satoshi, script: Seq[ScriptElt]) = this(amount, Script.write(script))

  override def txOut: TxOut = TxOut(amount, pay2wsh(script))

  override def redeemScript = script
}

case class DelayedPaymentOutputTemplate(override val amount: Satoshi, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData)
  extends P2WSHOutputTemplate(amount, toLocal(localRevocationPubkey, toLocalDelay, localPubkey))

case class OfferedHTLCOutputTemplate(htlc: UpdateAddHtlc, localPubkey: BinaryData, remotePubKey: BinaryData)
  extends P2WSHOutputTemplate(Satoshi(htlc.amountMsat / 1000), htlcOffered(localPubkey, remotePubKey, htlc.paymentHash))

case class ReceivedHTLCOutputTemplate(htlc: UpdateAddHtlc, localPubkey: BinaryData, remotePubKey: BinaryData, paymentHash: BinaryData)
  extends P2WSHOutputTemplate(Satoshi(htlc.amountMsat / 1000), htlcReceived(localPubkey, remotePubKey, paymentHash, htlc.expiry))

case class SuccessOrTimeoutOutputTemplate(htlc: UpdateAddHtlc, revocationPubkey: BinaryData, toSelfDelay: Long, localDelayedPubkey: BinaryData)
  extends P2WSHOutputTemplate(Satoshi(htlc.amountMsat / 1000), htlcSuccessOrTimeout(revocationPubkey, toSelfDelay, localDelayedPubkey))

case class P2WPKHOutputTemplate(amount: Satoshi, pubKey: BinaryData) extends OutputTemplate {
  override def txOut: TxOut = TxOut(amount, pay2wpkh(pubKey))

  override def redeemScript = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pubKey)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
}
