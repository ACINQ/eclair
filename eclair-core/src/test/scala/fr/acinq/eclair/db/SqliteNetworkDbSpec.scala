/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.db

import java.sql.Connection

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.router.{Announcements, PublicChannel}
import fr.acinq.eclair.wire.{Color, NodeAddress, Tor2}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, ShortChannelId, TestConstants, randomBytes32, randomKey}
import org.scalatest.FunSuite
import scodec.bits.HexStringSyntax

import scala.collection.{SortedMap, mutable}

class SqliteNetworkDbSpec extends FunSuite {

  val shortChannelIds = (42 to (5000 + 42)).map(i => ShortChannelId(i))

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqliteNetworkDb(sqlite)
    val db2 = new SqliteNetworkDb(sqlite)
  }

  test("migration test 1->2") {
    val sqlite = TestConstants.sqliteInMemory()

    using(sqlite.createStatement()) { statement =>
      getVersion(statement, "network", 1) // this will set version to 1
      statement.execute("PRAGMA foreign_keys = ON")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid STRING NOT NULL, data BLOB NOT NULL, capacity_sat INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(short_channel_id, node_flag), FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_updates_idx ON channel_updates(short_channel_id)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
    }

    using(sqlite.createStatement()) { statement =>
      assert(getVersion(statement, "network", 2) == 1)
    }

    // first round: this will trigger a migration
    simpleTest(sqlite)

    using(sqlite.createStatement()) { statement =>
      assert(getVersion(statement, "network", 2) == 2)
    }

    using(sqlite.createStatement()) { statement =>
      statement.executeUpdate("DELETE FROM nodes")
      statement.executeUpdate("DELETE FROM channels")
    }

    // second round: no migration
    simpleTest(sqlite)

    using(sqlite.createStatement()) { statement =>
      assert(getVersion(statement, "network", 2) == 2)
    }
  }

  test("add/remove/list nodes") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteNetworkDb(sqlite)

    val node_1 = Announcements.makeNodeAnnouncement(randomKey, "node-alice", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, hex"")
    val node_2 = Announcements.makeNodeAnnouncement(randomKey, "node-bob", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, hex"0200")
    val node_3 = Announcements.makeNodeAnnouncement(randomKey, "node-charlie", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, hex"0200")
    val node_4 = Announcements.makeNodeAnnouncement(randomKey, "node-charlie", Color(100.toByte, 200.toByte, 300.toByte), Tor2("aaaqeayeaudaocaj", 42000) :: Nil, hex"00")

    assert(db.listNodes().toSet === Set.empty)
    db.addNode(node_1)
    db.addNode(node_1) // duplicate is ignored
    assert(db.getNode(node_1.nodeId) === Some(node_1))
    assert(db.listNodes().size === 1)
    db.addNode(node_2)
    db.addNode(node_3)
    db.addNode(node_4)
    assert(db.listNodes().toSet === Set(node_1, node_2, node_3, node_4))
    db.removeNode(node_2.nodeId)
    assert(db.listNodes().toSet === Set(node_1, node_3, node_4))
    db.updateNode(node_1)

    assert(node_4.addresses == List(Tor2("aaaqeayeaudaocaj", 42000)))
  }

  test("correctly handle txids that start with 0") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteNetworkDb(sqlite)
    val sig = ByteVector64.Zeroes
    val c = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val txid = ByteVector32.fromValidHex("0001" * 16)
    db.addChannel(c, txid, Satoshi(42))
    assert(db.listChannels() === SortedMap(c.shortChannelId -> PublicChannel(c, txid, Satoshi(42), None, None)))
  }

  def simpleTest(sqlite: Connection) = {
    val db = new SqliteNetworkDb(sqlite)

    def sig = Crypto.sign(randomBytes32, randomKey)

    def generatePubkeyHigherThan(priv: PrivateKey) = {
      var res = priv
      while (!Announcements.isNode1(priv.publicKey, res.publicKey)) res = randomKey
      res
    }

    // in order to differentiate channel_updates 1/2 we order public keys
    val a = randomKey
    val b = generatePubkeyHigherThan(a)
    val c = generatePubkeyHigherThan(b)

    val channel_1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), a.publicKey, b.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val channel_2 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(43), a.publicKey, c.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val channel_3 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(44), b.publicKey, c.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)

    val txid_1 = randomBytes32
    val txid_2 = randomBytes32
    val txid_3 = randomBytes32
    val capacity = 10000 sat

    assert(db.listChannels().toSet === Set.empty)
    db.addChannel(channel_1, txid_1, capacity)
    db.addChannel(channel_1, txid_1, capacity) // duplicate is ignored
    assert(db.listChannels().size === 1)
    db.addChannel(channel_2, txid_2, capacity)
    db.addChannel(channel_3, txid_3, capacity)
    assert(db.listChannels() === SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, None, None),
      channel_2.shortChannelId -> PublicChannel(channel_2, txid_2, capacity, None, None),
      channel_3.shortChannelId -> PublicChannel(channel_3, txid_3, capacity, None, None)))
    db.removeChannel(channel_2.shortChannelId)
    assert(db.listChannels() === SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, None, None),
      channel_3.shortChannelId -> PublicChannel(channel_3, txid_3, capacity, None, None)))

    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)
    val channel_update_3 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, c.publicKey, ShortChannelId(44), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)

    db.updateChannel(channel_update_1)
    db.updateChannel(channel_update_1) // duplicate is ignored
    db.updateChannel(channel_update_2)
    db.updateChannel(channel_update_3)
    assert(db.listChannels() === SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, Some(channel_update_1), Some(channel_update_2)),
      channel_3.shortChannelId -> PublicChannel(channel_3, txid_3, capacity, Some(channel_update_3), None)))
    db.removeChannel(channel_3.shortChannelId)
    assert(db.listChannels() === SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, Some(channel_update_1), Some(channel_update_2))))
  }

  test("add/remove/list channels and channel_updates") {
    val sqlite = TestConstants.sqliteInMemory()
    simpleTest(sqlite)
  }

  test("creating a table that already exists but with different column types is ignored") {
    val sqlite = TestConstants.sqliteInMemory()
    using(sqlite.createStatement(), inTransaction = true) { statement =>
      statement.execute("CREATE TABLE IF NOT EXISTS test (txid STRING NOT NULL)")
    }
    // column type is STRING
    assert(sqlite.getMetaData.getColumns(null, null, "test", null).getString("TYPE_NAME") == "STRING")

    // insert and read back random values
    val txids = for (i <- 0 until 1000) yield randomBytes32
    txids.foreach { txid =>
      using(sqlite.prepareStatement("INSERT OR IGNORE INTO test VALUES (?)")) { statement =>
        statement.setString(1, txid.toHex)
        statement.executeUpdate()
      }
    }

    val check = using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT txid FROM test")
      var q = new mutable.Queue[ByteVector32]()
      while (rs.next()) {
        val txId = ByteVector32.fromValidHex(rs.getString("txid"))
        q.enqueue(txId)
      }
      q
    }
    assert(txids.toSet == check.toSet)


     using(sqlite.createStatement(), inTransaction = true) { statement =>
      statement.execute("CREATE TABLE IF NOT EXISTS test (txid TEXT NOT NULL)")
    }

    // column type has not changed
    assert(sqlite.getMetaData.getColumns(null, null, "test", null).getString("TYPE_NAME") == "STRING")
  }

  test("remove many channels") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteNetworkDb(sqlite)
    val sig = Crypto.sign(randomBytes32, randomKey)
    val priv = randomKey
    val pub = priv.publicKey
    val capacity = 10000 sat

    val channels = shortChannelIds.map(id => Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, id, pub, pub, pub, pub, sig, sig, sig, sig))
    val template = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv, pub, ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)
    val updates = shortChannelIds.map(id => template.copy(shortChannelId = id))
    val txid = randomBytes32
    channels.foreach(ca => db.addChannel(ca, txid, capacity))
    updates.foreach(u => db.updateChannel(u))
    assert(db.listChannels().keySet === channels.map(_.shortChannelId).toSet)

    val toDelete = channels.map(_.shortChannelId).drop(500).take(2500)
    db.removeChannels(toDelete)
    assert(db.listChannels().keySet === (channels.map(_.shortChannelId).toSet -- toDelete))
  }

  test("prune many channels") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteNetworkDb(sqlite)

    db.addToPruned(shortChannelIds)
    shortChannelIds.foreach { id => assert(db.isPruned((id))) }
    db.removeFromPruned(ShortChannelId(5))
    assert(!db.isPruned(ShortChannelId(5)))
  }
}
