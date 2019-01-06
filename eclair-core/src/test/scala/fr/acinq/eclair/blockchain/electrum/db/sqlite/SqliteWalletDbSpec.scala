package fr.acinq.eclair.blockchain.electrum.db.sqlite

import java.sql.DriverManager
import java.util.Random

import fr.acinq.bitcoin.{BinaryData, Block, BlockHeader, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.GetMerkleResponse
import org.scalatest.FunSuite

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

  test("add/get/list transactions") {
    val db = new SqliteWalletDb(inmem)
    val tx = Transaction.read("0100000001b021a77dcaad3a2da6f1611d2403e1298a902af8567c25d6e65073f6b52ef12d000000006a473044022056156e9f0ad7506621bc1eb963f5133d06d7259e27b13fcb2803f39c7787a81c022056325330585e4be39bcf63af8090a2deff265bc29a3fb9b4bf7a31426d9798150121022dfb538041f111bb16402aa83bd6a3771fa8aa0e5e9b0b549674857fafaf4fe0ffffffff0210270000000000001976a91415c23e7f4f919e9ff554ec585cb2a67df952397488ac3c9d1000000000001976a9148982824e057ccc8d4591982df71aa9220236a63888ac00000000")
    val proof = GetMerkleResponse(tx.hash, List(BinaryData("01" * 32), BinaryData("02" * 32)), 100000, 15)
    db.addTransaction(tx, proof)

    val Some((tx1, proof1)) = db.getTransaction(tx.hash)
    assert(tx1 == tx)
    assert(proof1 == proof)
  }
}
