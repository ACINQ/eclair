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
    val headers = makeHeaders(2016)
    val heightsAndHeaders = headers.zipWithIndex.map(_.swap)
    db.addHeaders(heightsAndHeaders)

    val headers1 = db.getHeaders(0)
    assert(headers1.map(_._2) === headers)

    val headers2 = db.getHeaders(50)
    assert(headers2.map(_._2) === headers.drop(50))

    headers.foreach(header => {
      val Some((_, check)) = db.getHeader(header.hash)
      assert(check == header)
    })
  }
}