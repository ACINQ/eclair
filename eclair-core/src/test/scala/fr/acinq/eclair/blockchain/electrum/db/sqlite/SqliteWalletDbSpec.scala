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

import fr.acinq.bitcoin.{Block, BlockHeader, OutPoint, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.{TestConstants, randomBytes, randomBytes32}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetMerkleResponse
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.PersistentData
import fr.acinq.eclair.blockchain.electrum.db.sqlite.SqliteWalletDb.version
import fr.acinq.eclair.wire.ChannelCodecs.txCodec
import org.scalatest.FunSuite
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs.{constant, listOfN, provide, uint16}

import scala.util.Random

class SqliteWalletDbSpec extends FunSuite {
  val random = new Random()

  def makeChildHeader(header: BlockHeader): BlockHeader = header.copy(hashPreviousBlock = header.hash, nonce = random.nextLong() & 0xffffffffL)

  def makeHeaders(n: Int, acc: Seq[BlockHeader] = Seq(Block.RegtestGenesisBlock.header)): Seq[BlockHeader] = {
    if (acc.size == n) acc else makeHeaders(n, acc :+ makeChildHeader(acc.last))
  }

  def randomTransaction = Transaction(version = 2,
    txIn = TxIn(OutPoint(randomBytes32, random.nextInt(100)), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
    txOut = TxOut(Satoshi(random.nextInt(10000000)), randomBytes(20)) :: Nil,
    0L
  )

  def randomHeight = if (random.nextBoolean()) random.nextInt(500000) else -1

  def randomHistoryItem = ElectrumClient.TransactionHistoryItem(randomHeight, randomBytes32)

  def randomHistoryItems = (0 to random.nextInt(100)).map(_ => randomHistoryItem).toList

  def randomProof = GetMerkleResponse(randomBytes32, ((0 until 10).map(_ => randomBytes32)).toList, random.nextInt(100000), 0)

  def randomPersistentData = {
    val transactions = for (i <- 0 until random.nextInt(100)) yield randomTransaction

    PersistentData(
      accountKeysCount = 10,
      changeKeysCount = 10,
      status = (for (i <- 0 until random.nextInt(100)) yield randomBytes32 -> random.nextInt(100000).toHexString).toMap,
      transactions = transactions.map(tx => tx.hash -> tx).toMap,
      heights = transactions.map(tx => tx.hash -> randomHeight).toMap,
      history = (for (i <- 0 until random.nextInt(100)) yield randomBytes32 -> randomHistoryItems).toMap,
      proofs = (for (i <- 0 until random.nextInt(100)) yield randomBytes32 -> randomProof).toMap,
      pendingTransactions = transactions.toList,
      locks = (for (i <- 0 until random.nextInt(10)) yield randomTransaction).toSet
    )
  }

  test("add/get/list headers") {
    val db = new SqliteWalletDb(TestConstants.sqliteInMemory())
    val headers = makeHeaders(100)
    db.addHeaders(2016, headers)

    val headers1 = db.getHeaders(2016, None)
    assert(headers1 === headers)

    val headers2 = db.getHeaders(2016, Some(50))
    assert(headers2 === headers.take(50))

    var height = 2016
    headers.foreach(header => {
      val Some((height1, header1)) = db.getHeader(header.hash)
      assert(height1 == height)
      assert(header1 == header)

      val Some(header2) = db.getHeader(height1)
      assert(header2 == header)
      height = height + 1
    })
  }

  test("serialize persistent data") {
    val db = new SqliteWalletDb(TestConstants.sqliteInMemory())
    assert(db.readPersistentData() == None)

    for (i <- 0 until 50) {
      val data = randomPersistentData
      db.persist(data)
      val Some(check) = db.readPersistentData()
      assert(check === data.copy(locks = Set.empty[Transaction]))
    }
  }

  test("read old persistent data") {
    import scodec.codecs._
    import SqliteWalletDb._
    import fr.acinq.eclair.wire.ChannelCodecs._

    val oldPersistentDataCodec: Codec[PersistentData] = (
      ("version" | constant(BitVector.fromInt(version))) ::
        ("accountKeysCount" | int32) ::
        ("changeKeysCount" | int32) ::
        ("status" | statusCodec) ::
        ("transactions" | transactionsCodec) ::
        ("heights" | heightsCodec) ::
        ("history" | historyCodec) ::
        ("proofs" | proofsCodec) ::
        ("pendingTransactions" | listOfN(uint16, txCodec)) ::
        ("locks" |  setCodec(txCodec))).as[PersistentData]

    for (i <- 0 until 50) {
      val data = randomPersistentData
      val encoded = oldPersistentDataCodec.encode(data).require
      val decoded = persistentDataCodec.decode(encoded).require.value
      assert(decoded === data.copy(locks = Set.empty[Transaction]))
    }
  }
}
