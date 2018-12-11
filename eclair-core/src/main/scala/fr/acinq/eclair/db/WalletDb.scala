package fr.acinq.eclair.db

import fr.acinq.bitcoin.{BinaryData, BlockHeader}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient

trait WalletDb {
  def addHeader(height: Int, header: BlockHeader): Unit
  def addHeader(header: ElectrumClient.Header): Unit = addHeader(header.block_height.toInt, header.blockHeader)
  def getHeader(blockHash: BinaryData) : Option[ElectrumClient.Header]
  def getHeaders(minimumHeight: Int) : Seq[ElectrumClient.Header]
}
