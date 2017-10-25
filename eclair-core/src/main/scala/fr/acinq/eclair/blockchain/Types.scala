package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.eclair.wire.ChannelAnnouncement

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

sealed trait Watch {
  def channel: ActorRef
  def event: BitcoinEvent
}
// we need a public key script to use the electrum api efficiently
final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, publicKeyScript: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch
final case class WatchSpent(channel: ActorRef, txId: BinaryData, outputIndex: Int, publicKeyScript: BinaryData, event: BitcoinEvent) extends Watch
final case class WatchSpentBasic(channel: ActorRef, txId: BinaryData, outputIndex: Int, publicKeyScript: BinaryData, event: BitcoinEvent) extends Watch // we use this when we don't care about the spending tx, and we also assume txid already exists
// TODO: notify me if confirmation number gets below minDepth?
final case class WatchLost(channel: ActorRef, txId: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch

trait WatchEvent {
  def event: BitcoinEvent
}
final case class WatchEventConfirmed(event: BitcoinEvent, blockHeight: Int, txIndex: Int) extends WatchEvent
final case class WatchEventSpent(event: BitcoinEvent, tx: Transaction) extends WatchEvent
final case class WatchEventSpentBasic(event: BitcoinEvent) extends WatchEvent
final case class WatchEventLost(event: BitcoinEvent) extends WatchEvent
final case class WatchEventDoubleSpent(event: BitcoinEvent) extends WatchEvent

/**
  * Publish the provided tx as soon as possible depending on locktime and csv
  */
final case class PublishAsap(tx: Transaction, parentPublicKeyScript: Option[BinaryData] = None)
final case class ParallelGetRequest(ann: Seq[ChannelAnnouncement])
final case class IndividualResult(c: ChannelAnnouncement, tx: Option[Transaction], unspent: Boolean)
final case class ParallelGetResponse(r: Seq[IndividualResult])

// @formatter:on
