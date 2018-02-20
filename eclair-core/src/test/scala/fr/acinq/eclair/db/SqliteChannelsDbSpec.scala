package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.eclair.db.sqlite.{SqliteChannelsDb, SqlitePendingRelayDb}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SqliteChannelsDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqliteChannelsDb(sqlite)
    val db2 = new SqliteChannelsDb(sqlite)
  }

  test("add/remove/list channels") {
    val sqlite = inmem
    val db = new SqliteChannelsDb(sqlite)
    new SqlitePendingRelayDb(sqlite) // needed by db.removeChannel

    val channel = ChannelStateSpec.normal

    assert(db.listChannels().toSet === Set.empty)
    db.addOrUpdateChannel(channel)
    db.addOrUpdateChannel(channel)
    assert(db.listChannels() === List(channel))
    db.removeChannel(channel.channelId)
    assert(db.listChannels() === Nil)
  }

}
