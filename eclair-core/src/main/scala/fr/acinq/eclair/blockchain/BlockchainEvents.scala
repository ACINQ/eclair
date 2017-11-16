package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.{Block, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw

/**
  * Created by PM on 24/08/2016.
  */

trait BlockchainEvent

case class NewBlock(block: Block) extends BlockchainEvent

case class NewTransaction(tx: Transaction) extends BlockchainEvent

case class CurrentBlockCount(blockCount: Long) extends BlockchainEvent

case class CurrentFeerates(feeratesPerKw: FeeratesPerKw) extends BlockchainEvent
