package fr.acinq.eclair.db

import java.net.{InetAddress, InetSocketAddress}
import java.sql.DriverManager

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.router.Announcements
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.sqlite.SQLiteException

@RunWith(classOf[JUnitRunner])
class SqliteNetworkDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqliteNetworkDb(sqlite)
    val db2 = new SqliteNetworkDb(sqlite)
  }

  test("add/remove/list nodes") {
    val sqlite = inmem
    val db = new SqliteNetworkDb(sqlite)

    val node_1 = Announcements.makeNodeAnnouncement(randomKey, "node-alice", (100.toByte, 200.toByte, 300.toByte), new InetSocketAddress(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)), 42000) :: Nil)
    val node_2 = Announcements.makeNodeAnnouncement(randomKey, "node-bob", (100.toByte, 200.toByte, 300.toByte), new InetSocketAddress(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)), 42000) :: Nil)
    val node_3 = Announcements.makeNodeAnnouncement(randomKey, "node-charlie", (100.toByte, 200.toByte, 300.toByte), new InetSocketAddress(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)), 42000) :: Nil)

    assert(db.listNodes().size === 0)
    db.addNode(node_1)
    db.addNode(node_2)
    db.addNode(node_3)
    assert(db.listNodes().size === 3)
    db.removeNode(node_2.nodeId)
    assert(db.listNodes().size === 2)
    db.updateNode(node_1)
  }

  test("add/remove/list channels and channel_updates") {
    val sqlite = inmem
    val db = new SqliteNetworkDb(sqlite)

    def sig = Crypto.encodeSignature(Crypto.sign(randomKey.toBin, randomKey)) :+ 1.toByte

    val channel_1 = Announcements.makeChannelAnnouncement(42, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val channel_2 = Announcements.makeChannelAnnouncement(43, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val channel_3 = Announcements.makeChannelAnnouncement(44, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)

    assert(db.listChannels().size === 0)
    db.addChannel(channel_1)
    db.addChannel(channel_2)
    db.addChannel(channel_3)
    assert(db.listChannels().size === 3)
    db.removeChannel(channel_2.shortChannelId)
    assert(db.listChannels().size === 2)

    val channel_update_1 = Announcements.makeChannelUpdate(randomKey, randomKey.publicKey, 42, 5, 7000000, 50000, 100, true)
    val channel_update_2 = Announcements.makeChannelUpdate(randomKey, randomKey.publicKey, 43, 5, 7000000, 50000, 100, true)
    val channel_update_3 = Announcements.makeChannelUpdate(randomKey, randomKey.publicKey, 44, 5, 7000000, 50000, 100, true)

    assert(db.listChannelUpdates().size === 0)
    db.addChannelUpdate(channel_update_1)
    intercept[SQLiteException](db.addChannelUpdate(channel_update_2))
    db.addChannelUpdate(channel_update_3)
    db.removeChannel(channel_3.shortChannelId)
    assert(db.listChannels().size === 1)
    assert(db.listChannelUpdates().size === 1)
    db.updateChannelUpdate(channel_update_1)
  }

}
