package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, Satoshi, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.channel.{LocalParams, RemoteParams}

import scala.util.Try

/**
  * Created by PM on 07/12/2016.
  */
object Signature {

  def sign(localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Satoshi, tx: Transaction): BinaryData = ???

  // Transaction.signInput(tx, 0, OldScripts.multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey)

  def addSigs(localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Satoshi, tx: Transaction, localSig: BinaryData, remoteSig: BinaryData): Transaction = ???

  /*{
     // TODO: Transaction.sign(...) should handle multisig
     val ourSig = Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey)
     val witness = witness2of2(theirSig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey)
     tx.updateWitness(0, witness)
   }*/

  def checksig(localParams: LocalParams, remoteParams: RemoteParams, anchorOutput: TxOut, tx: Transaction): Try[Unit] =
    Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))


  def signAndCheckSig(localParams: LocalParams, remoteParams: RemoteParams, anchorOutput: TxOut, tx: Transaction, remoteSig: BinaryData): Try[Transaction] = {
    val localSig = sign(localParams, remoteParams, anchorOutput.amount, tx)
    val signedTx = addSigs(localParams, remoteParams, anchorOutput.amount, tx, localSig, remoteSig)
    checksig(localParams, remoteParams, anchorOutput, signedTx).map(_ => signedTx)
  }

}
