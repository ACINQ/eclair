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

import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.{NetworkDb, Payment}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import scodec.bits.BitVector

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb {

  import SqliteUtils._

  val DB_NAME = "network"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid STRING NOT NULL, data BLOB NOT NULL, capacity_sat INTEGER NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(short_channel_id, node_flag), FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
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
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?, ?)")) { statement =>
      statement.setLong(1, c.shortChannelId.toLong)
      statement.setString(2, txid.toString())
      statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
      statement.setLong(4, capacity.amount)
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
      val rs = statement.executeQuery("SELECT data, txid, capacity_sat FROM channels")
      var m: Map[ChannelAnnouncement, (BinaryData, Satoshi)] = Map()
      while (rs.next()) {
        m += (channelAnnouncementCodec.decode(BitVector(rs.getBytes("data"))).require.value ->
          (BinaryData(rs.getString("txid")), Satoshi(rs.getLong("capacity_sat"))))
      }
      m
    }
  }

  override def addChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channel_updates VALUES (?, ?, ?)")) { statement =>
      statement.setLong(1, u.shortChannelId.toLong)
      statement.setBoolean(2, Announcements.isNode1(u.channelFlags))
      statement.setBytes(3, channelUpdateCodec.encode(u).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("UPDATE channel_updates SET data=? WHERE short_channel_id=? AND node_flag=?")) { statement =>
      statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
      statement.setLong(2, u.shortChannelId.toLong)
      statement.setBoolean(3, Announcements.isNode1(u.channelFlags))
      statement.executeUpdate()
    }
  }

  override def listChannelUpdates(): Seq[ChannelUpdate] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM channel_updates")
      codecSequence(rs, channelUpdateCodec)
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

  override def close(): Unit = sqlite.close
}
