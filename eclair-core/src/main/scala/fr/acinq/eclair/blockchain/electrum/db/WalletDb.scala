package fr.acinq.eclair.blockchain.electrum.db

import fr.acinq.bitcoin.{BinaryData, BlockHeader, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetMerkleResponse
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.PersistentData

trait HeaderDb {
  def addHeader(height: Int, header: BlockHeader): Unit

  def addHeaders(startHeight: Int, headers: Seq[BlockHeader]): Unit

  def getHeader(height: Int): Option[BlockHeader]

  // used only in unit tests
  def getHeader(blockHash: BinaryData): Option[(Int, BlockHeader)]

  def getHeaders(startHeight: Int, maxCount: Option[Int]): Seq[BlockHeader]

  def getTip: Option[(Int, BlockHeader)]
}

trait WalletDb extends HeaderDb {
  def persist(data: PersistentData): Unit

  def readPersistentData(): Option[PersistentData]
}
