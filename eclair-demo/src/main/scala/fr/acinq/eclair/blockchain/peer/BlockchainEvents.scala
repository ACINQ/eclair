package fr.acinq.eclair.blockchain.peer

import fr.acinq.bitcoin.{Block, Transaction}

/**
  * Created by PM on 24/08/2016.
  */

trait BlockchainEvent

case class NewBlock(block: Block) extends BlockchainEvent

case class NewTransaction(tx: Transaction) extends BlockchainEvent

case class CurrentBlockCount(blockcount: Long) extends BlockchainEvent
