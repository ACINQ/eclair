package fr.acinq.eclair.db

import java.sql.DriverManager
import java.util.Random

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.blockchain.electrum.ElectrumClient
import fr.acinq.eclair.db.sqlite.SqliteWalletDb
import org.scalatest.FunSuite

class SqliteWalletDbSpec extends FunSuite {
  val random = new Random()

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  def makeChildHeader(header: ElectrumClient.Header): ElectrumClient.Header = header.copy(block_height = header.block_height + 1, prev_block_hash = header.block_hash, nonce = random.nextLong() & 0xffffffffL)

  def makeHeaders(n: Int, acc: Seq[ElectrumClient.Header] = Seq(ElectrumClient.Header.makeHeader(1, Block.RegtestGenesisBlock.header))): Seq[ElectrumClient.Header] = {
    if (acc.size == n) acc else makeHeaders(n, acc :+ makeChildHeader(acc.last))
  }

  test("add/get/list headers") {
    val db = new SqliteWalletDb(inmem)
    val headers = makeHeaders(100)
    headers.map(db.addHeader)

    val headers1 = db.getHeaders(0)
    assert(headers1 === headers)

    val headers2 = db.getHeaders(50)
    assert(headers2 === headers.filter(_.block_height >= 50))

    headers.foreach(header => {
      val Some(check) = db.getHeader(header.block_hash)
      assert(check == header)
    })
  }
}