package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, SIGHASH_ALL, Satoshi, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.eclair.wire.{ChannelAnnouncement, LightningMessage}

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

sealed trait Watch {
  def channel: ActorRef
  def event: BitcoinEvent
}
final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch
final case class WatchSpent(channel: ActorRef, txId: BinaryData, outputIndex: Int, event: BitcoinEvent) extends Watch
final case class WatchSpentBasic(channel: ActorRef, txId: BinaryData, outputIndex: Int, event: BitcoinEvent) extends Watch // we use this when we don't care about the spending tx, and we also assume txid exists
// TODO: notify me if confirmation number gets below minDepth?
final case class WatchLost(channel: ActorRef, txId: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch

trait WatchEvent {
  def event: BitcoinEvent
}
final case class WatchEventConfirmed(event: BitcoinEvent, blockHeight: Int, txIndex: Int) extends WatchEvent
final case class WatchEventSpent(event: BitcoinEvent, tx: Transaction) extends WatchEvent
final case class WatchEventSpentBasic(event: BitcoinEvent) extends WatchEvent
final case class WatchEventLost(event: BitcoinEvent) extends WatchEvent

/**
  * Publish the provided tx as soon as possible depending on locktime and csv
  */
final case class PublishAsap(tx: Transaction)
final case class MakeFundingTx(localCommitPub: PublicKey, remoteCommitPub: PublicKey, amount: Satoshi, feeRatePerKw: Long)
final case class MakeFundingTxResponse(parentTx: Transaction, fundingTx: Transaction, fundingTxOutputIndex: Int, priv: PrivateKey) {
  def replaceParent(newParentTx: Transaction) : MakeFundingTxResponse = {
    val utxo = newParentTx.txOut(fundingTx.txIn(0).outPoint.index.toInt)
    require(utxo.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
    val input = fundingTx.txIn(0)
    val input1 = input.copy(outPoint = input.outPoint.copy(hash = newParentTx.hash))
    val unsignedfundingTx = fundingTx.copy(txIn = Seq(input1))
    MakeFundingTxResponse(newParentTx, unsignedfundingTx, fundingTxOutputIndex, priv).sign
  }

  def sign : MakeFundingTxResponse = {
    val utxo = parentTx.txOut(fundingTx.txIn(0).outPoint.index.toInt)
    val pub = priv.publicKey
    val sig = Transaction.signInput(fundingTx, 0, Script.pay2pkh(pub), SIGHASH_ALL, utxo.amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
    val fundingTx1 = fundingTx.updateWitness(0, ScriptWitness(sig :: pub.toBin :: Nil))
    Transaction.correctlySpends(fundingTx1, parentTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    this.copy(fundingTx = fundingTx1)
  }
}

final case class GetTx(blockHeight: Int, txIndex: Int, outputIndex: Int, ctx: LightningMessage)
final case class GetTxResponse(tx: Transaction, isSpendable: Boolean, ctx: LightningMessage)
final case class ParallelGetRequest(ann: Seq[ChannelAnnouncement])
final case class IndividualResult(c: ChannelAnnouncement, tx: Option[Transaction], unspent: Boolean)
final case class ParallelGetResponse(r: Seq[IndividualResult])

// @formatter:on
