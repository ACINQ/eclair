package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.eclair.wire.LightningMessage

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
final case class MakeFundingTxResponse(fundingTx: Transaction, fundingTxOutputIndex: Int)
final case class GetTx(blockHeight: Int, txIndex: Int, outputIndex: Int, ctx: LightningMessage)
final case class GetTxResponse(tx: Transaction, isSpendable: Boolean, ctx: LightningMessage)

// @formatter:on
