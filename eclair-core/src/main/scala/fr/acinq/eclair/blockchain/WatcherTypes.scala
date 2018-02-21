package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Script, ScriptWitness, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.eclair.wire.ChannelAnnouncement

import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

sealed trait Watch {
  def channel: ActorRef
  def event: BitcoinEvent
}
// we need a public key script to use electrum apis
final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, publicKeyScript: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch
object WatchConfirmed {
  // if we have the entire transaction, we can get the redeemScript from the witness, and re-compute the publicKeyScript
  // we support both p2pkh and p2wpkh scripts
  def apply(channel: ActorRef, tx: Transaction, minDepth: Long, event: BitcoinEvent): WatchConfirmed = WatchConfirmed(channel, tx.txid, tx.txOut.map(_.publicKeyScript).headOption.getOrElse(""), minDepth, event)

  def extractPublicKeyScript(witness: ScriptWitness): BinaryData = Try(PublicKey(witness.stack.last)) match {
    case Success(pubKey) =>
      // if last element of the witness is a public key, then this is a p2wpkh
      Script.write(Script.pay2wpkh(pubKey))
    case Failure(_) =>
      // otherwise this is a p2wsh
      witness.stack.last
  }
}

final case class WatchSpent(channel: ActorRef, txId: BinaryData, outputIndex: Int, publicKeyScript: BinaryData, event: BitcoinEvent) extends Watch
object WatchSpent {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(channel: ActorRef, tx: Transaction, outputIndex: Int, event: BitcoinEvent): WatchSpent = WatchSpent(channel, tx.txid, outputIndex, tx.txOut(outputIndex).publicKeyScript, event)
}
final case class WatchSpentBasic(channel: ActorRef, txId: BinaryData, outputIndex: Int, publicKeyScript: BinaryData, event: BitcoinEvent) extends Watch // we use this when we don't care about the spending tx, and we also assume txid already exists
object WatchSpentBasic {
  // if we have the entire transaction, we can get the publicKeyScript from the relevant output
  def apply(channel: ActorRef, tx: Transaction, outputIndex: Int, event: BitcoinEvent): WatchSpentBasic = WatchSpentBasic(channel, tx.txid, outputIndex, tx.txOut(outputIndex).publicKeyScript, event)
}
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
final case class ValidateRequest(ann: ChannelAnnouncement)
final case class ValidateResult(c: ChannelAnnouncement, tx: Option[Transaction], unspent: Boolean, t: Option[Throwable])

// @formatter:on
