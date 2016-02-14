package fr.acinq.eclair.blockchain

import akka.actor.ActorRef
import fr.acinq.bitcoin.{Transaction, TxOut, BinaryData}
import fr.acinq.eclair.channel.BlockchainEvent

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

trait Watch
final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, minDepth: Int, event: BlockchainEvent) extends Watch
final case class WatchConfirmedBasedOnOutputs(channel: ActorRef, txIdSpent: BinaryData, txOut: Seq[TxOut], minDepth: Int, event: BlockchainEvent) extends Watch
final case class WatchSpent(channel: ActorRef, txId: BinaryData, outputIndex: Int, minDepth: Int, event: BlockchainEvent) extends Watch
final case class WatchLost(channel: ActorRef, txId: BinaryData, minDepth: Int, event: BlockchainEvent) extends Watch // notify me if confirmation number gets below minDepth

final case class Publish(tx: Transaction)
final case class MakeAnchor(ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Long)
// @formatter:on
