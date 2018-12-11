package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, BlockHeader}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.db.WalletDb

import scala.collection.immutable.Queue

class SqliteWalletDb(sqlite: Connection) extends WalletDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS headers (block_hash BLOB NOT NULL PRIMARY KEY, height INTEGER NOT NULL, header BLOB NOT NULL)")
  }

  override def addHeader(height: Int, header: BlockHeader): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO headers VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, header.hash)
      statement.setLong(2, height)
      statement.setBytes(3, BlockHeader.write(header))
      statement.executeUpdate()
    }
  }

  override def getHeader(blockHash: BinaryData): Option[ElectrumClient.Header] = {
    using(sqlite.prepareStatement("SELECT block_hash, height, header FROM headers WHERE block_hash = ?")) { statement =>
      statement.setBytes(1, blockHash)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(ElectrumClient.Header.makeHeader(rs.getLong("height"), BlockHeader.read(rs.getBytes("header"))))
      } else {
        None
      }
    }
  }

  override def getHeaders(minimumHeight: Int): Seq[ElectrumClient.Header] = {
    using(sqlite.prepareStatement("SELECT block_hash, height, header FROM headers WHERE height >= ? ORDER BY height")) { statement =>
      statement.setLong(1, minimumHeight)
      val rs = statement.executeQuery()
      var q: Queue[ElectrumClient.Header] = Queue()
      while (rs.next()) {
        q = q :+ ElectrumClient.Header.makeHeader(rs.getLong("height"), BlockHeader.read(rs.getBytes("header")))
      }
      q
    }
  }
}
