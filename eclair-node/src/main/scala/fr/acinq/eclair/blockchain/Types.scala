package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

trait Watch {
  def channel: ActorRef
}
final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch
final case class WatchSpent(channel: ActorRef, txId: BinaryData, outputIndex: Int, minDepth: Int, event: BitcoinEvent) extends Watch
// TODO: notify me if confirmation number gets below minDepth?
final case class WatchLost(channel: ActorRef, txId: BinaryData, minDepth: Long, event: BitcoinEvent) extends Watch

trait WatchEvent {
  def event: BitcoinEvent
}
final case class WatchEventConfirmed(event: BitcoinEvent, blockHeight: Int, txIndex: Int) extends WatchEvent
final case class WatchEventSpent(event: BitcoinEvent, tx: Transaction) extends WatchEvent
final case class WatchEventLost(event: BitcoinEvent) extends WatchEvent

/**
  * Publish the provided tx as soon as possible depending on locktime and csv
  */
final case class PublishAsap(tx: Transaction)
final case class MakeFundingTx(ourCommitPub: PublicKey, theirCommitPub: PublicKey, amount: Satoshi)

// @formatter:on
