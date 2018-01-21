package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.eclair.db.sqlite.SqlitePendingRelayDb
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.wire.{FailureMessageCodecs, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SqlitePendingRelayDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqlitePendingRelayDb(sqlite)
    val db2 = new SqlitePendingRelayDb(sqlite)
  }

  test("add/remove/list messages") {
    val sqlite = inmem
    val db = new SqlitePendingRelayDb(sqlite)

    val channelId = randomBytes(32)
    val msg0 = UpdateFulfillHtlc(randomBytes(32), 42, randomBytes(32))
    val msg1 = UpdateFulfillHtlc(randomBytes(32), 43, randomBytes(32))
    val msg2 = UpdateFailHtlc(randomBytes(32), 44, randomBytes(32))
    val msg3 = UpdateFailMalformedHtlc(randomBytes(32), 45, randomBytes(32), FailureMessageCodecs.BADONION)

    assert(db.listPendingRelay(channelId).toSet === Set.empty)
    db.addPendingRelay(channelId, 0, msg0)
    db.addPendingRelay(channelId, 0, msg0) // duplicate
    db.addPendingRelay(channelId, 1, msg1)
    db.addPendingRelay(channelId, 2, msg2)
    db.addPendingRelay(channelId, 3, msg3)
    assert(db.listPendingRelay(channelId).sortBy(_._2) === (channelId, 0, msg0) :: (channelId, 1, msg1) :: (channelId, 2, msg2) :: (channelId, 3, msg3) :: Nil)
    db.removePendingRelay(channelId, 1)
    assert(db.listPendingRelay(channelId).sortBy(_._2) === (channelId, 0, msg0) :: (channelId, 2, msg2) :: (channelId, 3, msg3) :: Nil)
  }

}
