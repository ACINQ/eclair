package fr.acinq.eclair.blockchain.electrum.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, BlockHeader, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetMerkleResponse
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.eclair.db.sqlite.SqliteUtils

import scala.collection.immutable.Queue

class SqliteWalletDb(sqlite: Connection) extends WalletDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS headers (height INTEGER NOT NULL PRIMARY KEY, block_hash BLOB NOT NULL, header BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (tx_hash BLOB PRIMARY KEY, tx BLOB NOT NULL, proof BLOB NOT NULL)")
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

  override def addTransaction(tx: Transaction, proof: ElectrumClient.GetMerkleResponse): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO transactions VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, tx.hash)
      statement.setBytes(2, Transaction.write(tx))
      statement.setBytes(3, SqliteWalletDb.serialize(proof))
      statement.executeUpdate()
    }
  }

  override def getTransaction(tx_hash: BinaryData): Option[(Transaction, ElectrumClient.GetMerkleResponse)] = {
    using(sqlite.prepareStatement("SELECT tx, proof FROM transactions WHERE tx_hash = ?")) { statement =>
      statement.setBytes(1, tx_hash)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some((Transaction.read(rs.getBytes("tx")), SqliteWalletDb.deserialize((rs.getBytes("proof")))))
      } else {
        None
      }
    }
  }

  override def getTransactions(): Seq[(Transaction, ElectrumClient.GetMerkleResponse)] = {
    using(sqlite.prepareStatement("SELECT tx, proof FROM transactions")) { statement =>
      val rs = statement.executeQuery()
      var q: Queue[(Transaction, ElectrumClient.GetMerkleResponse)] = Queue()
      while (rs.next()) {
        q = q :+ (Transaction.read(rs.getBytes("tx")), SqliteWalletDb.deserialize(rs.getBytes("proof")))
      }
      q
    }
  }
}

object SqliteWalletDb {
  import fr.acinq.eclair.wire.LightningMessageCodecs.binarydata
  import scodec.Codec
  import scodec.bits.BitVector
  import scodec.codecs._

  val proofCodec: Codec[GetMerkleResponse] = (
    ("txid" | binarydata(32)) ::
      ("merkle" | listOfN(uint16, binarydata(32))) ::
      ("block_height" | uint24) ::
      ("pos" | uint24)).as[GetMerkleResponse]

  def serialize(proof: GetMerkleResponse) : BinaryData = proofCodec.encode(proof).require.toByteArray

  def deserialize(bin: BinaryData) : GetMerkleResponse = proofCodec.decode(BitVector(bin.toArray)).require.value
}