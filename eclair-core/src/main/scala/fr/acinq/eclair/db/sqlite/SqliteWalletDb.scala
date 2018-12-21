package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, BlockHeader}
import fr.acinq.eclair.blockchain.electrum.CheckPoint
import fr.acinq.eclair.db.WalletDb

import scala.collection.immutable.Queue

class SqliteWalletDb(sqlite: Connection) extends WalletDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS headers (height INTEGER NOT NULL PRIMARY KEY, block_hash BLOB NOT NULL, header BLOB NOT NULL)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS headers_hash_idx ON headers(block_hash)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS checkpoints (height INTEGER NOT NULL PRIMARY KEY, block_hash BLOB NOT NULL, next_bits INTEGER NOT NULL)")
  }

  override def addHeader(height: Int, header: BlockHeader): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO headers VALUES (?, ?, ?)")) { statement =>
      statement.setInt(1, height)
      statement.setBytes(2, header.hash)
      statement.setBytes(3, BlockHeader.write(header))
      statement.executeUpdate()
    }
  }

  override def addHeaders(startHeight: Int, headers: Seq[BlockHeader]): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO headers VALUES (?, ?, ?)"), disableAutoCommit = true) { statement =>
      var height = startHeight
      headers.foreach(header => {
        statement.setInt(1, height)
        statement.setBytes(2, header.hash)
        statement.setBytes(3, BlockHeader.write(header))
        statement.addBatch()
        height = height + 1
      })
      val result = statement.executeBatch()
    }
  }

  override def getHeader(height: Int): Option[BlockHeader] = {
    using(sqlite.prepareStatement("SELECT header FROM headers WHERE height = ?")) { statement =>
      statement.setInt(1, height)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(BlockHeader.read(rs.getBytes("header")))
      } else {
        None
      }
    }
  }

  override def getHeader(blockHash: BinaryData): Option[(Int, BlockHeader)] = {
    using(sqlite.prepareStatement("SELECT height, header FROM headers WHERE block_hash = ?")) { statement =>
      statement.setBytes(1, blockHash)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some((rs.getInt("height"), BlockHeader.read(rs.getBytes("header"))))
      } else {
        None
      }
    }
  }

  override def getHeaders(startHeight: Int, maxCount: Option[Int]): Seq[BlockHeader] = {
    val query = "SELECT height, header FROM headers WHERE height >= ? ORDER BY height " + maxCount.map(m => s" LIMIT $m").getOrElse("")
    using(sqlite.prepareStatement(query)) { statement =>
      statement.setInt(1, startHeight)
      val rs = statement.executeQuery()
      var q: Queue[BlockHeader] = Queue()
      while (rs.next()) {
        q = q :+ BlockHeader.read(rs.getBytes("header"))
      }
      q
    }
  }


  override def getTip: Option[(Int, BlockHeader)] = {
    using(sqlite.prepareStatement("SELECT t.height, t.header FROM headers t INNER JOIN (SELECT MAX(height) AS maxHeight FROM headers) q ON t.height = q.maxHeight")) { statement =>
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some((rs.getInt("height"), BlockHeader.read(rs.getBytes("header"))))
      } else {
        None
      }
    }
  }

  override def addCheckpoint(height: Int, checkPoint: CheckPoint): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO checkpoints VALUES (?, ?, ?)")) { statement =>
      statement.setInt(1, height)
      statement.setBytes(2, checkPoint.hash)
      statement.setLong(3, checkPoint.nextBits)
      statement.executeUpdate()
    }
  }

  override def getCheckpoints(): Seq[CheckPoint] = {
    using(sqlite.prepareStatement("SELECT height, block_hash, next_bits FROM checkpoints ORDER BY height")) { statement =>
      val rs = statement.executeQuery()
      var q: Queue[CheckPoint] = Queue()
      while (rs.next()) {
        q = q :+ CheckPoint(rs.getBytes("block_hash"), rs.getLong("next_bits"))
      }
      q
    }
  }
}
