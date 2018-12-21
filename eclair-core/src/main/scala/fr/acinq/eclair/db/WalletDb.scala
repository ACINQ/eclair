package fr.acinq.eclair.db

import fr.acinq.bitcoin.{BinaryData, BlockHeader, Transaction}
import fr.acinq.eclair.blockchain.electrum.{CheckPoint, ElectrumClient}

trait HeaderDb {
  def addHeader(height: Int, header: BlockHeader): Unit

  def addHeaders(startHeight: Int, headers: Seq[BlockHeader]): Unit

  def getHeader(height: Int): Option[BlockHeader]

  // TODO: should not be needed
  def getHeader(blockHash: BinaryData): Option[(Int, BlockHeader)]

  def getHeaders(startHeight: Int, maxCount: Option[Int]): Seq[BlockHeader]

  def getTip: Option[(Int, BlockHeader)]

  def addCheckpoint(height: Int, checkPoint: CheckPoint): Unit

  def getCheckpoints(): Seq[CheckPoint]
}

trait WalletDb extends HeaderDb
