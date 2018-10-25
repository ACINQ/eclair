/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs.nodeAnnouncementCodec
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

import scala.collection.immutable.Queue

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb {

  import SqliteUtils._

  val DB_NAME = "network"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, node_id_1 BLOB NOT NULL, node_id_2 BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, timestamp INTEGER NOT NULL, flags BLOB NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, htlc_maximum_msat INTEGER, PRIMARY KEY(short_channel_id, node_flag), FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_updates_idx ON channel_updates(short_channel_id)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
  }

  override def addNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")) { statement =>
      statement.setBytes(1, n.nodeId.toBin)
      statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.setBytes(2, n.nodeId.toBin)
      statement.executeUpdate()
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    using(sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.toBin)
      statement.executeUpdate()
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM nodes")
      codecSequence(rs, nodeAnnouncementCodec)
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: BinaryData, capacity: Satoshi): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?)")) { statement =>
      statement.setLong(1, c.shortChannelId.toLong)
      statement.setBytes(2, c.nodeId1.value.toBin(false).toArray) // we store uncompressed public keys
      statement.setBytes(3, c.nodeId2.value.toBin(false).toArray)
      statement.executeUpdate()
    }
  }

  override def removeChannel(shortChannelId: ShortChannelId): Unit = {
    using(sqlite.createStatement) { statement =>
      statement.execute("BEGIN TRANSACTION")
      statement.executeUpdate(s"DELETE FROM channel_updates WHERE short_channel_id=${shortChannelId.toLong}")
      statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id=${shortChannelId.toLong}")
      statement.execute("COMMIT TRANSACTION")
    }
  }

  override def listChannels(): Map[ChannelAnnouncement, (BinaryData, Satoshi)] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM channels")
      var m: Map[ChannelAnnouncement, (BinaryData, Satoshi)] = Map()
      val emptyTxid = BinaryData("")
      val zeroCapacity = Satoshi(0)
      while (rs.next()) {
        m = m + (ChannelAnnouncement(
          nodeSignature1 = null,
          nodeSignature2 = null,
          bitcoinSignature1 = null,
          bitcoinSignature2 = null,
          features = null,
          chainHash = null,
          shortChannelId = ShortChannelId(rs.getLong("short_channel_id")),
          nodeId1 = PublicKey(PublicKey(rs.getBytes("node_id_1")).value, compressed = true), // we read as uncompressed, and convert to compressed
          nodeId2 = PublicKey(PublicKey(rs.getBytes("node_id_2")).value, compressed = true),
          bitcoinKey1 = null,
          bitcoinKey2 = null) -> (emptyTxid, zeroCapacity))
      }
      m
    }
  }

  override def addChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channel_updates VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, u.shortChannelId.toLong)
      statement.setBoolean(2, Announcements.isNode1(u.channelFlags))
      statement.setLong(3, u.timestamp)
      statement.setBytes(4, Array(u.messageFlags, u.channelFlags))
      statement.setInt(5, u.cltvExpiryDelta)
      statement.setLong(6, u.htlcMinimumMsat)
      statement.setLong(7, u.feeBaseMsat)
      statement.setLong(8, u.feeProportionalMillionths)
      setNullableLong(statement, 9, u.htlcMaximumMsat)
      statement.executeUpdate()
    }
  }

  override def updateChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("UPDATE channel_updates SET timestamp=?, flags=?, cltv_expiry_delta=?, htlc_minimum_msat=?, fee_base_msat=?, fee_proportional_millionths=?, htlc_maximum_msat=? WHERE short_channel_id=? AND node_flag=?")) { statement =>
      statement.setLong(1, u.timestamp)
      statement.setBytes(2, Array(u.messageFlags, u.channelFlags))
      statement.setInt(3, u.cltvExpiryDelta)
      statement.setLong(4, u.htlcMinimumMsat)
      statement.setLong(5, u.feeBaseMsat)
      statement.setLong(6, u.feeProportionalMillionths)
      setNullableLong(statement, 7, u.htlcMaximumMsat)
      statement.setLong(8, u.shortChannelId.toLong)
      statement.setBoolean(9, Announcements.isNode1(u.channelFlags))
      statement.executeUpdate()
    }
  }

  override def listChannelUpdates(): Seq[ChannelUpdate] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM channel_updates")
      var q: Queue[ChannelUpdate] = Queue()
      while (rs.next()) {
        q = q :+ ChannelUpdate(
          signature = null,
          chainHash = null,
          shortChannelId = ShortChannelId(rs.getLong("short_channel_id")),
          timestamp = rs.getLong("timestamp"),
          messageFlags = rs.getBytes("flags")(0),
          channelFlags = rs.getBytes("flags")(1),
          cltvExpiryDelta = rs.getInt("cltv_expiry_delta"),
          htlcMinimumMsat = rs.getLong("htlc_minimum_msat"),
          feeBaseMsat = rs.getLong("fee_base_msat"),
          feeProportionalMillionths = rs.getLong("fee_proportional_millionths"),
          htlcMaximumMsat = getNullableLong(rs, "htlc_maximum_msat"))
      }
      q
    }
  }

  override def addToPruned(shortChannelId: ShortChannelId): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pruned VALUES (?)")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = {
    using(sqlite.createStatement) { statement =>
      statement.executeUpdate(s"DELETE FROM pruned WHERE short_channel_id=${shortChannelId.toLong}")
    }
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = {
    using(sqlite.prepareStatement("SELECT short_channel_id from pruned WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      val rs = statement.executeQuery()
      rs.next()
    }
  }
}
