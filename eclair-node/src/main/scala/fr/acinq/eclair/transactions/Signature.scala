package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, Satoshi, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.channel.{LocalParams, RemoteParams}

import scala.util.Try

/**
  * Created by PM on 07/12/2016.
  */
object Signature {

  def sign(localParams: LocalParams, RemoteParams: RemoteParams, anchorAmount: Satoshi, tx: Transaction): BinaryData = ???

  //bin2signature(Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey))

  def addSigs(localParams: LocalParams, RemoteParams: RemoteParams, anchorAmount: Satoshi, tx: Transaction, ourSig: BinaryData, theirSig: BinaryData): Transaction = ???

  /*{
     // TODO : Transaction.sign(...) should handle multisig
     val ourSig = Transaction.signInput(tx, 0, multiSig2of2(ourParams.commitPubKey, theirParams.commitPubKey), SIGHASH_ALL, anchorAmount, 1, ourParams.commitPrivKey)
     val witness = witness2of2(theirSig, ourSig, theirParams.commitPubKey, ourParams.commitPubKey)
     tx.updateWitness(0, witness)
   }*/

  def checksig(localParams: LocalParams, RemoteParams: RemoteParams, anchorOutput: TxOut, tx: Transaction): Try[Unit] =
    Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

}
