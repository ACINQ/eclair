package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, SIGHASH_ALL, Satoshi, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.channel.{LocalParams, RemoteParams}
import fr.acinq.eclair.crypto.Generators.{Point, Scalar}

import scala.util.Try

/**
  * Created by PM on 07/12/2016.
  */
object Signature {

  def sign(localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Satoshi, tx: Transaction): BinaryData = {
    // this is because by convention in bitcoin-core-speak 32B keys are 'uncompressed' and 33B keys (ending by 0x01) are 'compressed'
    val localCompressedFundingPrivkey: Seq[Byte] = localParams.fundingPrivkey.data.toSeq :+ 1.toByte
    Transaction.signInput(tx, 0, OldScripts.multiSig2of2(localParams.fundingPrivkey.point, remoteParams.fundingPubkey), SIGHASH_ALL, fundingSatoshis, 1, localCompressedFundingPrivkey)
  }

  def addSigs(tx: Transaction, localFundingPubkey: Point, remoteFundingPubkey: Point, localSig: BinaryData, remoteSig: BinaryData): Transaction = {
    val witness = OldScripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    tx.updateWitness(0, witness)
  }

  def checksig(anchorOutput: TxOut, tx: Transaction): Try[Unit] =
    Try(Transaction.correctlySpends(tx, Map(tx.txIn(0).outPoint -> anchorOutput), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def signAndCheckSig(localParams: LocalParams, remoteParams: RemoteParams, anchorOutput: TxOut, localPerCommitmentPoint: Point, tx: Transaction, remoteSig: BinaryData): Try[Transaction] = {
    val localSig = sign(localParams: LocalParams, remoteParams: RemoteParams, anchorOutput.amount, tx)
    val signedTx = addSigs(tx, localParams.fundingPrivkey.point, remoteParams.fundingPubkey, localSig, remoteSig)
    checksig(anchorOutput, signedTx).map(_ => signedTx)
  }

}
