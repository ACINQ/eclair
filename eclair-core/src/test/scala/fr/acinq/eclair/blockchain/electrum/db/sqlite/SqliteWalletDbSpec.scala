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

import java.sql.DriverManager

import fr.acinq.bitcoin.{BinaryData, Block, BlockHeader, OutPoint, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetMerkleResponse
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.PersistentData
import org.scalatest.FunSuite

import scala.util.Random

class SqliteWalletDbSpec extends FunSuite {
  val random = new Random()

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  def makeChildHeader(header: BlockHeader): BlockHeader = header.copy(hashPreviousBlock = header.hash, nonce = random.nextLong() & 0xffffffffL)

  def makeHeaders(n: Int, acc: Seq[BlockHeader] = Seq(Block.RegtestGenesisBlock.header)): Seq[BlockHeader] = {
    if (acc.size == n) acc else makeHeaders(n, acc :+ makeChildHeader(acc.last))
  }

  test("add/get/list headers") {
    val db = new SqliteWalletDb(inmem)
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
    val db = new SqliteWalletDb(inmem)

    def randomBytes(size: Int): BinaryData = {
      val buffer = new Array[Byte](size)
      random.nextBytes(buffer)
      buffer
    }

    def randomTransaction = Transaction(version = 2,
      txIn = TxIn(OutPoint(randomBytes(32), random.nextInt(100)), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(Satoshi(random.nextInt(10000000)), randomBytes(20)) :: Nil,
      0L
    )

    def randomHeight = if (random.nextBoolean()) random.nextInt(500000) else -1

    def randomHistoryItem = ElectrumClient.TransactionHistoryItem(randomHeight, randomBytes(32))

    def randomHistoryItems = (0 to random.nextInt(100)).map(_ => randomHistoryItem).toList

    def randomProof = GetMerkleResponse(randomBytes(32), ((0 until 10).map(_ => randomBytes(32))).toList, random.nextInt(100000), 0)

    def randomPersistentData = {
      val transactions = for (i <- 0 until random.nextInt(100)) yield randomTransaction

      PersistentData(
        accountKeysCount = 10,
        changeKeysCount = 10,
        status = (for (i <- 0 until random.nextInt(100)) yield randomBytes(32) -> random.nextInt(100000).toHexString).toMap,
        transactions = transactions.map(tx => tx.hash -> tx).toMap,
        heights = transactions.map(tx => tx.hash -> randomHeight).toMap,
        history = (for (i <- 0 until random.nextInt(100)) yield randomBytes(32) -> randomHistoryItems).toMap,
        proofs = (for (i <- 0 until random.nextInt(100)) yield randomBytes(32) -> randomProof).toMap,
        pendingTransactions = transactions.toList,
        locks = (for (i <- 0 until random.nextInt(10)) yield randomTransaction).toSet
      )
    }

    assert(db.readPersistentData() == None)

    for (i <- 0 until 50) {
      val data = randomPersistentData
      db.persist(data)
      val Some(check) = db.readPersistentData()
      assert(check === data)
    }
  }
}
