package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.eclair.db.sqlite.SqlitePreimagesDb
import fr.acinq.eclair.randomBytes
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SqlitePreimagesDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqlitePreimagesDb(sqlite)
    val db2 = new SqlitePreimagesDb(sqlite)
  }

  test("add/remove/list preimages") {
    val sqlite = inmem
    val db = new SqlitePreimagesDb(sqlite)

    val channelId = randomBytes(32)
    val preimage0 = randomBytes(32)
    val preimage1 = randomBytes(32)
    val preimage2 = randomBytes(32)
    val preimage3 = randomBytes(32)

    assert(db.listPreimages(channelId).toSet === Set.empty)
    db.addPreimage(channelId, 0, preimage0)
    db.addPreimage(channelId, 0, preimage0) // duplicate
    db.addPreimage(channelId, 1, preimage1)
    db.addPreimage(channelId, 2, preimage2)
    assert(db.listPreimages(channelId).sortBy(_._2) === (channelId, 0, preimage0) :: (channelId, 1, preimage1) :: (channelId, 2, preimage2) :: Nil)
    db.removePreimage(channelId, 1)
    assert(db.listPreimages(channelId).sortBy(_._2) === (channelId, 0, preimage0) :: (channelId, 2, preimage2) :: Nil)
  }

}
