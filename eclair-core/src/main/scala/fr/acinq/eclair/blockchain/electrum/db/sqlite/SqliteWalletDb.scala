/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BlockHeader, ByteVector32, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{GetMerkleResponse, TransactionHistoryItem}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.PersistentData
import fr.acinq.eclair.blockchain.electrum.db.WalletDb
import fr.acinq.eclair.blockchain.electrum.{ElectrumClient, ElectrumWallet}
import fr.acinq.eclair.db.sqlite.SqliteUtils

import scala.collection.immutable.Queue

class SqliteWalletDb(sqlite: Connection) extends WalletDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS headers (height INTEGER NOT NULL PRIMARY KEY, block_hash BLOB NOT NULL, header BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS wallet (data BLOB)")
  }

  override def addHeader(height: Int, header: BlockHeader): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO headers VALUES (?, ?, ?)")) { statement =>
      statement.setInt(1, height)
      statement.setBytes(2, header.hash.toArray)
      statement.setBytes(3, BlockHeader.write(header).toArray)
      statement.executeUpdate()
    }
  }

  override def addHeaders(startHeight: Int, headers: Seq[BlockHeader]): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO headers VALUES (?, ?, ?)"), disableAutoCommit = true) { statement =>
      var height = startHeight
      headers.foreach(header => {
        statement.setInt(1, height)
        statement.setBytes(2, header.hash.toArray)
        statement.setBytes(3, BlockHeader.write(header).toArray)
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

  override def getHeader(blockHash: ByteVector32): Option[(Int, BlockHeader)] = {
    using(sqlite.prepareStatement("SELECT height, header FROM headers WHERE block_hash = ?")) { statement =>
      statement.setBytes(1, blockHash.toArray)
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

  override def persist(data: ElectrumWallet.PersistentData): Unit = {
    val bin = SqliteWalletDb.serialize(data)
    using(sqlite.prepareStatement("UPDATE wallet SET data=(?)")) { update =>
      update.setBytes(1, bin)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO wallet VALUES (?)")) { statement =>
          statement.setBytes(1, bin)
          statement.executeUpdate()
        }
      }
    }
  }

  override def readPersistentData(): Option[ElectrumWallet.PersistentData] = {
    using(sqlite.prepareStatement("SELECT data FROM wallet")) { statement =>
      val rs = statement.executeQuery()
      if (rs.next()) {
        Option(rs.getBytes(1)).map(bin => SqliteWalletDb.deserializePersistentData(bin))
      } else {
        None
      }
    }
  }
}

object SqliteWalletDb {

  import fr.acinq.eclair.wire.ChannelCodecs._
  import fr.acinq.eclair.wire.LightningMessageCodecs._
  import scodec.Codec
  import scodec.bits.BitVector
  import scodec.codecs._

  val proofCodec: Codec[GetMerkleResponse] = (
    ("txid" | bytes32) ::
      ("merkle" | listOfN(uint16, bytes32)) ::
      ("block_height" | uint24) ::
      ("pos" | uint24)).as[GetMerkleResponse]

  def serializeMerkleProof(proof: GetMerkleResponse): Array[Byte] = proofCodec.encode(proof).require.toByteArray

  def deserializeMerkleProof(bin: Array[Byte]): GetMerkleResponse = proofCodec.decode(BitVector(bin)).require.value

  import fr.acinq.eclair.wire.LightningMessageCodecs._

  val statusListCodec: Codec[List[(ByteVector32, String)]] = listOfN(uint16, bytes32 ~ cstring)

  val statusCodec: Codec[Map[ByteVector32, String]] = Codec[Map[ByteVector32, String]](
    (map: Map[ByteVector32, String]) => statusListCodec.encode(map.toList),
    (wire: BitVector) => statusListCodec.decode(wire).map(_.map(_.toMap))
  )

  val heightsListCodec: Codec[List[(ByteVector32, Int)]] = listOfN(uint16, bytes32 ~ int32)

  val heightsCodec: Codec[Map[ByteVector32, Int]] = Codec[Map[ByteVector32, Int]](
    (map: Map[ByteVector32, Int]) => heightsListCodec.encode(map.toList),
    (wire: BitVector) => heightsListCodec.decode(wire).map(_.map(_.toMap))
  )

  val transactionListCodec: Codec[List[(ByteVector32, Transaction)]] = listOfN(uint16, bytes32 ~ txCodec)

  val transactionsCodec: Codec[Map[ByteVector32, Transaction]] = Codec[Map[ByteVector32, Transaction]](
    (map: Map[ByteVector32, Transaction]) => transactionListCodec.encode(map.toList),
    (wire: BitVector) => transactionListCodec.decode(wire).map(_.map(_.toMap))
  )

  val transactionHistoryItemCodec: Codec[ElectrumClient.TransactionHistoryItem] = (
    ("height" | int32) :: ("tx_hash" | bytes32)).as[ElectrumClient.TransactionHistoryItem]

  val seqOfTransactionHistoryItemCodec: Codec[List[TransactionHistoryItem]] = listOfN[TransactionHistoryItem](uint16, transactionHistoryItemCodec)

  val historyListCodec: Codec[List[(ByteVector32, List[ElectrumClient.TransactionHistoryItem])]] =
    listOfN[(ByteVector32, List[ElectrumClient.TransactionHistoryItem])](uint16, bytes32 ~ seqOfTransactionHistoryItemCodec)

  val historyCodec: Codec[Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]]] = Codec[Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]]](
    (map: Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]]) => historyListCodec.encode(map.toList),
    (wire: BitVector) => historyListCodec.decode(wire).map(_.map(_.toMap))
  )

  val proofsListCodec: Codec[List[(ByteVector32, GetMerkleResponse)]] = listOfN(uint16, bytes32 ~ proofCodec)

  val proofsCodec: Codec[Map[ByteVector32, GetMerkleResponse]] = Codec[Map[ByteVector32, GetMerkleResponse]](
    (map: Map[ByteVector32, GetMerkleResponse]) => proofsListCodec.encode(map.toList),
    (wire: BitVector) => proofsListCodec.decode(wire).map(_.map(_.toMap))
  )

  /**
    * change this value
    * -if the new codec is incompatible with the old one
    * - OR if you want to force a full sync from Electrum servers
    */
  val version = 0x0000

  val persistentDataCodec: Codec[PersistentData] = (
    ("version" | constant(BitVector.fromInt(version))) ::
      ("accountKeysCount" | int32) ::
      ("changeKeysCount" | int32) ::
      ("status" | statusCodec) ::
      ("transactions" | transactionsCodec) ::
      ("heights" | heightsCodec) ::
      ("history" | historyCodec) ::
      ("proofs" | proofsCodec) ::
      ("pendingTransactions" | listOfN(uint16, txCodec)) ::
      ("locks" | setCodec(txCodec))).as[PersistentData]

  def serialize(data: PersistentData): Array[Byte] = persistentDataCodec.encode(data).require.toByteArray

  def deserializePersistentData(bin: Array[Byte]): PersistentData = persistentDataCodec.decode(BitVector(bin)).require.value
}