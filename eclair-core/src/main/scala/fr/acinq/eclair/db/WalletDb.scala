package fr.acinq.eclair.db

import fr.acinq.bitcoin.{BinaryData, BlockHeader, Transaction}
import fr.acinq.eclair.blockchain.electrum.{CheckPoint, ElectrumClient}

trait WalletDb {
  def addHeader(height: Int, header: BlockHeader): Unit

  def addHeaders(headers: Seq[(Int, BlockHeader)]): Array[Int]

  def getHeader(blockHash: BinaryData): Option[(Int, BlockHeader)]

  def getHeaders(minimumHeight: Int): Seq[(Int, BlockHeader)]

  def addCheckpoint(height: Int, checkPoint: CheckPoint): Unit

  def getCheckpoints(): Seq[CheckPoint]
}
