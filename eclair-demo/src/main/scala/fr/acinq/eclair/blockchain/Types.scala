package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.{Transaction, TxOut, BinaryData}

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

trait Watch
final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, minDepth: Int, event: AnyRef) extends Watch
final case class WatchConfirmedBasedOnOutputs(channel: ActorRef, txIdSpent: BinaryData, txOut: Seq[TxOut], minDepth: Int, event: AnyRef) extends Watch
final case class WatchSpent(channel: ActorRef, txId: BinaryData, minDepth: Int, event: AnyRef) extends Watch
final case class WatchLost(channel: ActorRef, txId: BinaryData, minDepth: Int, event: AnyRef) extends Watch // notify me if confirmation number gets below minDepth

final case class TxConfirmed(txId: BinaryData, blockId: BinaryData, confirmations: Int)
final case class TxSpent(txId: BinaryData, blockId: BinaryData, confirmations: Int)
final case class Publish(tx: Transaction)

// @formatter:on
