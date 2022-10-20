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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, Satoshi, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.VariableLengthOnion
import fr.acinq.eclair.TestDatabases._
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgNetworkDb
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TestDatabases, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

import scala.collection.{SortedMap, mutable}
import scala.util.Random

class NetworkDbSpec extends AnyFunSuite {

  import NetworkDbSpec._
  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteNetworkDb(sqlite.connection)
        new SqliteNetworkDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgNetworkDb()(pg.datasource)
        new PgNetworkDb()(pg.datasource)
    }
  }

  test("add/remove/list nodes") {
    forAllDbs { dbs =>
      val db = dbs.network

      val node_1 = Announcements.makeNodeAnnouncement(randomKey(), "node-alice", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, Features.empty)
      val node_2 = Announcements.makeNodeAnnouncement(randomKey(), "node-bob", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, Features(VariableLengthOnion -> Optional))
      val node_3 = Announcements.makeNodeAnnouncement(randomKey(), "node-charlie", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, Features(VariableLengthOnion -> Optional))
      val node_4 = Announcements.makeNodeAnnouncement(randomKey(), "node-eve", Color(100.toByte, 200.toByte, 300.toByte), Tor3("of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad", 42000) :: Nil, Features.empty)
      val node_5 = Announcements.makeNodeAnnouncement(randomKey(), "node-frank", Color(100.toByte, 200.toByte, 300.toByte), DnsHostname("eclair.invalid", 42000) :: Nil, Features.empty)

      assert(db.listNodes().toSet == Set.empty)
      db.addNode(node_1)
      db.addNode(node_1) // duplicate is ignored
      assert(db.getNode(node_1.nodeId).contains(node_1))
      assert(db.listNodes().size == 1)
      db.addNode(node_2)
      db.addNode(node_3)
      db.addNode(node_4)
      db.addNode(node_5)
      assert(db.listNodes().toSet == Set(node_1, node_2, node_3, node_4, node_5))
      db.removeNode(node_2.nodeId)
      assert(db.listNodes().toSet == Set(node_1, node_3, node_4, node_5))
      db.updateNode(node_1)

      assert(node_4.addresses == List(Tor3("of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad", 42000)))
      assert(node_5.addresses == List(DnsHostname("eclair.invalid", 42000)))
    }
  }

  test("correctly handle txids that start with 0") {
    forAllDbs { dbs =>
      val db = dbs.network
      val sig = ByteVector64.Zeroes
      val c = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(42), randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, sig, sig, sig, sig)
      val txid = ByteVector32.fromValidHex("0001" * 16)
      db.addChannel(c, txid, Satoshi(42))
      assert(db.listChannels() == SortedMap(c.shortChannelId -> PublicChannel(c, txid, Satoshi(42), None, None, None)))
    }
  }

  def simpleTest(dbs: TestDatabases): Unit = {
    val db = dbs.network

    def sig: ByteVector64 = Crypto.sign(randomBytes32(), randomKey())

    def generatePubkeyHigherThan(priv: PrivateKey): PrivateKey = {
      var res = priv
      while (!Announcements.isNode1(priv.publicKey, res.publicKey)) res = randomKey()
      res
    }

    // in order to differentiate channel_updates 1/2 we order public keys
    val a = randomKey()
    val b = generatePubkeyHigherThan(a)
    val c = generatePubkeyHigherThan(b)

    val channel_1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(42), a.publicKey, b.publicKey, randomKey().publicKey, randomKey().publicKey, sig, sig, sig, sig)
    val channel_2 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(43), a.publicKey, c.publicKey, randomKey().publicKey, randomKey().publicKey, sig, sig, sig, sig)
    val channel_3 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(44), b.publicKey, c.publicKey, randomKey().publicKey, randomKey().publicKey, sig, sig, sig, sig)

    val txid_1 = randomBytes32()
    val txid_2 = randomBytes32()
    val txid_3 = randomBytes32()
    val capacity = 10000 sat

    assert(db.listChannels().toSet == Set.empty)
    assert(db.getChannel(channel_1.shortChannelId).isEmpty)
    db.addChannel(channel_1, txid_1, capacity)
    db.addChannel(channel_1, txid_1, capacity) // duplicate is ignored
    assert(db.listChannels().size == 1)
    assert(db.getChannel(channel_1.shortChannelId).contains(PublicChannel(channel_1, txid_1, capacity, None, None, None)))
    db.addChannel(channel_2, txid_2, capacity)
    db.addChannel(channel_3, txid_3, capacity)
    assert(db.listChannels() == SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, None, None, None),
      channel_2.shortChannelId -> PublicChannel(channel_2, txid_2, capacity, None, None, None),
      channel_3.shortChannelId -> PublicChannel(channel_3, txid_3, capacity, None, None, None))
    )
    db.removeChannel(channel_2.shortChannelId)
    assert(db.getChannel(channel_2.shortChannelId).isEmpty)
    assert(db.listChannels() == SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, None, None, None),
      channel_3.shortChannelId -> PublicChannel(channel_3, txid_3, capacity, None, None, None))
    )

    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)
    val channel_update_3 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, c.publicKey, ShortChannelId(44), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)

    db.updateChannel(channel_update_1)
    db.updateChannel(channel_update_1) // duplicate is ignored
    db.updateChannel(channel_update_2)
    db.updateChannel(channel_update_3)
    assert(db.listChannels() == SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, Some(channel_update_1), Some(channel_update_2), None),
      channel_3.shortChannelId -> PublicChannel(channel_3, txid_3, capacity, Some(channel_update_3), None, None)))
    db.removeChannel(channel_3.shortChannelId)
    assert(db.listChannels() == SortedMap(
      channel_1.shortChannelId -> PublicChannel(channel_1, txid_1, capacity, Some(channel_update_1), Some(channel_update_2), None)))
  }

  test("add/remove/list channels and channel_updates") {
    forAllDbs { dbs =>
      simpleTest(dbs)
    }
  }

  test("creating a table that already exists but with different column types is ignored") {
    forAllDbs { dbs =>

      using(dbs.connection.createStatement(), inTransaction = true) { statement =>
        statement.execute("CREATE TABLE IF NOT EXISTS test (txid VARCHAR NOT NULL)")
      }
      // column type is VARCHAR
      val rs = dbs.connection.getMetaData.getColumns(null, null, "test", null)
      assert(rs.next())
      assert(rs.getString("TYPE_NAME").toLowerCase == "varchar")


      // insert and read back random values
      val txids = for (_ <- 0 until 1000) yield randomBytes32()
      txids.foreach { txid =>
        using(dbs.connection.prepareStatement("INSERT INTO test VALUES (?)")) { statement =>
          statement.setString(1, txid.toHex)
          statement.executeUpdate()
        }
      }

      val check = using(dbs.connection.createStatement()) { statement =>
        val rs = statement.executeQuery("SELECT txid FROM test")
        val q = new mutable.Queue[ByteVector32]()
        while (rs.next()) {
          val txId = ByteVector32.fromValidHex(rs.getString("txid"))
          q.enqueue(txId)
        }
        q
      }
      assert(txids.toSet == check.toSet)


      using(dbs.connection.createStatement(), inTransaction = true) { statement =>
        statement.execute("CREATE TABLE IF NOT EXISTS test (txid TEXT NOT NULL)")
      }

      // column type has not changed
      val rs1 = dbs.connection.getMetaData.getColumns(null, null, "test", null)
      assert(rs1.next())
      assert(rs1.getString("TYPE_NAME").toLowerCase == "varchar")
    }
  }

  val shortChannelIds: Seq[RealShortChannelId] = (42 to (5000 + 42)).map(i => RealShortChannelId(i))

  test("remove many channels") {
    forAllDbs { dbs =>
      val db = dbs.network
      val sig = Crypto.sign(randomBytes32(), randomKey())
      val priv = randomKey()
      val pub = priv.publicKey
      val capacity = 10000 sat

      val channels = shortChannelIds.map(id => Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, id, pub, pub, pub, pub, sig, sig, sig, sig))
      val template = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv, pub, ShortChannelId(42), CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, true)
      val updates = shortChannelIds.map(id => template.copy(shortChannelId = id))
      val txid = randomBytes32()
      channels.foreach(ca => db.addChannel(ca, txid, capacity))
      updates.foreach(u => db.updateChannel(u))
      assert(db.listChannels().keySet == channels.map(_.shortChannelId).toSet)

      val toDelete = channels.map(_.shortChannelId).take(1 + Random.nextInt(2500))
      db.removeChannels(toDelete)
      assert(db.listChannels().keySet == (channels.map(_.shortChannelId).toSet -- toDelete))
    }
  }

  test("migration sqlite v1 -> current") {
    val dbs = TestSqliteDatabases()
    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.execute("PRAGMA foreign_keys = ON")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid STRING NOT NULL, data BLOB NOT NULL, capacity_sat INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(short_channel_id, node_flag), FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_updates_idx ON channel_updates(short_channel_id)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
          setVersion(statement, "network", 1)
        }
        nodeTestCases.foreach { testCase =>
          using(connection.prepareStatement("INSERT INTO nodes (node_id, data) VALUES (?, ?)")) { statement =>
            statement.setString(1, testCase.nodeId.toString())
            statement.setBytes(2, testCase.data)
            statement.executeUpdate()
          }
        }
        channelTestCases.foreach { testCase =>
          using(connection.prepareStatement("INSERT INTO channels (short_channel_id, txid, data, capacity_sat) VALUES (?, ?, ?, ?)")) { statement =>
            statement.setLong(1, testCase.shortChannelId.toLong)
            statement.setString(2, testCase.txid.toString())
            statement.setBytes(3, testCase.channel_data)
            statement.setLong(4, testCase.capacity.toLong)
            statement.executeUpdate()
          }
          testCase.update_1_data_opt.foreach { update =>
            using(connection.prepareStatement("INSERT INTO channel_updates (short_channel_id, node_flag, data) VALUES (?, ?, ?)")) { statement =>
              statement.setLong(1, testCase.shortChannelId.toLong)
              statement.setLong(2, 0)
              statement.setBytes(3, update)
              statement.executeUpdate()
            }
          }
          testCase.update_2_data_opt.foreach { update =>
            using(connection.prepareStatement("INSERT INTO channel_updates (short_channel_id, node_flag, data) VALUES (?, ?, ?)")) { statement =>
              statement.setLong(1, testCase.shortChannelId.toLong)
              statement.setLong(2, 1)
              statement.setBytes(3, update)
              statement.executeUpdate()
            }
          }
        }
      },
      dbName = SqliteNetworkDb.DB_NAME,
      targetVersion = SqliteNetworkDb.CURRENT_VERSION,
      postCheck = _ => {
        assert(dbs.network.listNodes().toSet == nodeTestCases.map(_.node).toSet)
        // NB: channel updates are not migrated
        assert(dbs.network.listChannels().values.toSet == channelTestCases.map(tc => PublicChannel(tc.channel, tc.txid, tc.capacity, None, None, None)).toSet)
      }
    )
  }

  test("migration postgres v2 -> current") {
    val dbs = TestPgDatabases()
    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE nodes (node_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL)")
          statement.executeUpdate("CREATE TABLE channels (short_channel_id BIGINT NOT NULL PRIMARY KEY, txid TEXT NOT NULL, channel_announcement BYTEA NOT NULL, capacity_sat BIGINT NOT NULL, channel_update_1 BYTEA NULL, channel_update_2 BYTEA NULL)")
          statement.executeUpdate("CREATE TABLE pruned (short_channel_id BIGINT NOT NULL PRIMARY KEY)")
          setVersion(statement, "network", 2)
        }
        nodeTestCases.foreach { testCase =>
          using(connection.prepareStatement("INSERT INTO nodes (node_id, data) VALUES (?, ?)")) { statement =>
            statement.setString(1, testCase.nodeId.toString())
            statement.setBytes(2, testCase.data)
            statement.executeUpdate()
          }
        }
        channelTestCases.foreach { testCase =>
          using(connection.prepareStatement("INSERT INTO channels (short_channel_id, txid, channel_announcement, capacity_sat, channel_update_1, channel_update_2) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setLong(1, testCase.shortChannelId.toLong)
            statement.setString(2, testCase.txid.toString())
            statement.setBytes(3, testCase.channel_data)
            statement.setLong(4, testCase.capacity.toLong)
            statement.setBytes(5, testCase.update_1_data_opt.orNull)
            statement.setBytes(6, testCase.update_2_data_opt.orNull)
            statement.executeUpdate()
          }
        }
      },
      dbName = PgNetworkDb.DB_NAME,
      targetVersion = PgNetworkDb.CURRENT_VERSION,
      postCheck = _ => {
        assert(dbs.network.listNodes().toSet == nodeTestCases.map(_.node).toSet)
        // NB: channel updates are not migrated
        assert(dbs.network.listChannels().values.toSet == channelTestCases.map(tc => PublicChannel(tc.channel, tc.txid, tc.capacity, tc.update_1_opt, tc.update_2_opt, None)).toSet)
      }
    )
  }

  test("remove channel updates without htlc_maximum_msat") {
    forAllDbs { dbs =>
      val t1 = channelTestCases(0)
      val t2 = channelTestCases(1)
      val db1 = dbs.network
      db1.addChannel(t1.channel, t1.txid, t2.capacity)
      db1.addChannel(t2.channel, t2.txid, t2.capacity)
      // The DB contains a channel update missing the `htlc_maximum_msat` field.
      val channelUpdateWithoutHtlcMax = hex"12540b6a236e21932622d61432f52913d9442cc09a1057c386119a286153f8681c66d2a0f17d32505ba71bb37c8edcfa9c11e151b2b38dae98b825eff1c040b36fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d619000000000008850f00058e00015e6a782e0000009000000000000003e8000003e800000002"
      dbs match {
        case sqlite: TestSqliteDatabases =>
          using(sqlite.connection.prepareStatement("UPDATE channels SET channel_update_1=? WHERE short_channel_id=?")) { statement =>
            statement.setBytes(1, channelUpdateWithoutHtlcMax.toArray)
            statement.setLong(2, t1.shortChannelId.toLong)
            statement.executeUpdate()
          }
        case pg: TestPgDatabases =>
          using(pg.connection.prepareStatement("UPDATE network.public_channels SET channel_update_1=? WHERE short_channel_id=?")) { statement =>
            statement.setBytes(1, channelUpdateWithoutHtlcMax.toArray)
            statement.setLong(2, t1.shortChannelId.toLong)
            statement.executeUpdate()
          }
      }
      assertThrows[IllegalArgumentException](db1.listChannels())
      // We restart eclair and automatically clean up invalid entries.
      val db2 = dbs match {
        case sqlite: TestSqliteDatabases => new SqliteNetworkDb(sqlite.connection)
        case pg: TestPgDatabases => new PgNetworkDb()(pg.datasource)
      }
      val channels = db2.listChannels()
      assert(channels.keySet == Set(t2.shortChannelId))
    }
  }

  test("json column reset (postgres)") {
    val dbs = TestPgDatabases()
    val db = dbs.network
    nodeTestCases.foreach(t => db.addNode(t.node))
    channelTestCases.foreach { t =>
      db.addChannel(t.channel, t.txid, t.capacity)
      t.update_1_opt.foreach(db.updateChannel)
      t.update_2_opt.foreach(db.updateChannel)
    }
    dbs.connection.execSQLUpdate("UPDATE network.nodes SET json='{}'")
    dbs.connection.execSQLUpdate("UPDATE network.public_channels SET channel_announcement_json='{}',channel_update_1_json=NULL,channel_update_2_json=NULL")
    db.asInstanceOf[PgNetworkDb].resetJsonColumns(dbs.connection)
    assert({
      val res = dbs.connection.execSQLQuery("SELECT * FROM network.nodes")
      res.next()
      res.getString("json").length > 100
    })
    assert({
      val res = dbs.connection.execSQLQuery("SELECT * FROM network.public_channels WHERE channel_update_1_json IS NOT NULL")
      res.next()
      res.getString("channel_announcement_json").length > 100
      res.getString("channel_update_1_json").length > 100
    })
  }
}

object NetworkDbSpec {

  case class NodeTestCase(nodeId: PublicKey,
                          node: NodeAnnouncement,
                          data: Array[Byte])

  case class ChannelTestCase(shortChannelId: ShortChannelId,
                             txid: ByteVector32,
                             channel: ChannelAnnouncement,
                             channel_data: Array[Byte],
                             capacity: Satoshi,
                             update_1_opt: Option[ChannelUpdate],
                             update_2_opt: Option[ChannelUpdate],
                             update_1_data_opt: Option[Array[Byte]],
                             update_2_data_opt: Option[Array[Byte]])

  val nodeTestCases: Seq[NodeTestCase] = for (_ <- 0 until 10) yield {
    val node = Announcements.makeNodeAnnouncement(randomKey(), "node-alice", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil, Features.empty)
    val data = nodeAnnouncementCodec.encode(node).require.toByteArray
    NodeTestCase(
      nodeId = node.nodeId,
      node = node,
      data = data
    )
  }

  val channelTestCases: Seq[ChannelTestCase] = for (_ <- 0 until 10) yield {
    val a = randomKey()
    val b = generatePubkeyHigherThan(a)
    val channel = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, RealShortChannelId(Random.nextInt(1_000_000)), a.publicKey, a.publicKey, randomKey().publicKey, randomKey().publicKey, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes, ByteVector64.Zeroes)
    val channel_update_1_opt = if (Random.nextBoolean()) {
      Some(Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, a, b.publicKey, channel.shortChannelId, CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, Random.nextBoolean()))
    } else None
    val channel_update_2_opt = if (Random.nextBoolean()) {
      Some(Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, b, a.publicKey, channel.shortChannelId, CltvExpiryDelta(5), 7000000 msat, 50000 msat, 100, 500000000L msat, Random.nextBoolean()))
    } else None
    val channel_data = channelAnnouncementCodec.encode(channel).require.toByteArray
    val channel_update_1_data = channel_update_1_opt.map(channelUpdateCodec.encode(_).require.toByteArray)
    val channel_update_2_data = channel_update_2_opt.map(channelUpdateCodec.encode(_).require.toByteArray)
    ChannelTestCase(
      shortChannelId = channel.shortChannelId,
      txid = randomBytes32(),
      channel = channel,
      channel_data = channel_data,
      capacity = Random.nextInt(100_000).sat,
      update_1_opt = channel_update_1_opt,
      update_2_opt = channel_update_2_opt,
      update_1_data_opt = channel_update_1_data,
      update_2_data_opt = channel_update_2_data
    )
  }

  val sig: ByteVector64 = Crypto.sign(randomBytes32(), randomKey())

  def generatePubkeyHigherThan(priv: PrivateKey): PrivateKey = {
    var res = priv
    while (!Announcements.isNode1(priv.publicKey, res.publicKey)) res = randomKey()
    res
  }

}
