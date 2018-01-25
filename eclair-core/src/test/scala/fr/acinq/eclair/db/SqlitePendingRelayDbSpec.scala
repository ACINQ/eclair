package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.sqlite.SqlitePendingRelayDb
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.wire.FailureMessageCodecs
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

    val channelId1 = randomBytes(32)
    val channelId2 = randomBytes(32)
    val msg0 = CMD_FULFILL_HTLC(0, randomBytes(32))
    val msg1 = CMD_FULFILL_HTLC(1, randomBytes(32))
    val msg2 = CMD_FAIL_HTLC(2, Left(randomBytes(32)))
    val msg3 = CMD_FAIL_HTLC(3, Left(randomBytes(32)))
    val msg4 = CMD_FAIL_MALFORMED_HTLC(4, randomBytes(32), FailureMessageCodecs.BADONION)

    assert(db.listPendingRelay(channelId1).toSet === Set.empty)
    db.addPendingRelay(channelId1, msg0.id, msg0)
    db.addPendingRelay(channelId1, msg0.id, msg0) // duplicate
    db.addPendingRelay(channelId1, msg1.id, msg1)
    db.addPendingRelay(channelId1, msg2.id, msg2)
    db.addPendingRelay(channelId1, msg3.id, msg3)
    db.addPendingRelay(channelId1, msg4.id, msg4)
    db.addPendingRelay(channelId2, msg0.id, msg0) // same messages but for different channel
    db.addPendingRelay(channelId2, msg1.id, msg1)
    assert(db.listPendingRelay(channelId1).toSet === Set(msg0, msg1, msg2, msg3, msg4))
    assert(db.listPendingRelay(channelId2).toSet === Set(msg0, msg1))
    db.removePendingRelay(channelId1, msg1.id)
    assert(db.listPendingRelay(channelId1).toSet === Set(msg0, msg2, msg3, msg4))
  }

}
