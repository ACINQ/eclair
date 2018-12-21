package fr.acinq.eclair.db

import java.sql.DriverManager
import java.util.Random

import fr.acinq.bitcoin.{Block, BlockHeader}
import fr.acinq.eclair.db.sqlite.SqliteWalletDb
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
}
