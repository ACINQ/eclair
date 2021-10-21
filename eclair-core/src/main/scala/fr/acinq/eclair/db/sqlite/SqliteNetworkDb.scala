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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.{ByteVector32, Crypto, PublicKey, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging

import java.sql.{Connection, Statement}
import scala.collection.immutable.SortedMap

object SqliteNetworkDb {
  val CURRENT_VERSION = 2
  val DB_NAME = "network"
}

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb with Logging {

  import SqliteNetworkDb._
  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      // channel_update are cheap to retrieve, so let's just wipe them out and they'll get resynced
      statement.execute("PRAGMA foreign_keys = ON")
      statement.executeUpdate("ALTER TABLE channels RENAME COLUMN data TO channel_announcement")
      statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_update_1 BLOB NULL")
      statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_update_2 BLOB NULL")
      statement.executeUpdate("DROP TABLE channel_updates")
      statement.execute("PRAGMA foreign_keys = OFF")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
        statement.executeUpdate("CREATE TABLE channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid TEXT NOT NULL, channel_announcement BLOB NOT NULL, capacity_sat INTEGER NOT NULL, channel_update_1 BLOB NULL, channel_update_2 BLOB NULL)")
        statement.executeUpdate("CREATE TABLE pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
      case Some(v@1) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        migration12(statement)
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addNode(n: NodeAnnouncement): Unit = withMetrics("network/add-node", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")) { statement =>
      statement.setBytes(1, n.nodeId.value.toByteArray)
      statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = withMetrics("network/update-node", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.setBytes(2, n.nodeId.value.toByteArray)
      statement.executeUpdate()
    }
  }

  override def getNode(nodeId: PublicKey): Option[NodeAnnouncement] = withMetrics("network/get-node", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT data FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toByteArray)
      statement.executeQuery()
        .mapCodec(nodeAnnouncementCodec)
        .headOption
    }
  }

  override def removeNode(nodeId: PublicKey): Unit = withMetrics("network/remove-node", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toByteArray)
      statement.executeUpdate()
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = withMetrics("network/list-nodes", DbBackends.Sqlite) {
    using(sqlite.createStatement()) { statement =>
      statement.executeQuery("SELECT data FROM nodes")
        .mapCodec(nodeAnnouncementCodec).toSeq
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = withMetrics("network/add-channel", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?, ?, NULL, NULL)")) { statement =>
      statement.setLong(1, c.shortChannelId.toLong)
      statement.setString(2, txid.toHex)
      statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
      statement.setLong(4, capacity.toLong)
      statement.executeUpdate()
    }
  }

  override def updateChannel(u: ChannelUpdate): Unit = withMetrics("network/update-channel", DbBackends.Sqlite) {
    val column = if (u.channelFlags.isNode1) "channel_update_1" else "channel_update_2"
    using(sqlite.prepareStatement(s"UPDATE channels SET $column=? WHERE short_channel_id=?")) { statement =>
      statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
      statement.setLong(2, u.shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def listChannels(): SortedMap[ShortChannelId, PublicChannel] = withMetrics("network/list-channels", DbBackends.Sqlite) {
    using(sqlite.createStatement()) { statement =>
      statement.executeQuery("SELECT channel_announcement, txid, capacity_sat, channel_update_1, channel_update_2 FROM channels")
        .foldLeft(SortedMap.empty[ShortChannelId, PublicChannel]) { (m, rs) =>
            val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
            val txId = ByteVector32.fromValidHex(rs.getString("txid"))
            val capacity = rs.getLong("capacity_sat")
            val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
            val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
            m + (ann.shortChannelId -> PublicChannel(ann, txId, new Satoshi(capacity), channel_update_1_opt, channel_update_2_opt, None))
        }
    }
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/remove-channels", DbBackends.Sqlite) {
    val batchSize = 100
    using(sqlite.prepareStatement(s"DELETE FROM channels WHERE short_channel_id IN (${List.fill(batchSize)("?").mkString(",")})")) { statement =>
      shortChannelIds
        .grouped(batchSize)
        .foreach { group =>
          val padded = group.toArray.padTo(batchSize, ShortChannelId(0L))
          for (i <- 0 until batchSize) {
            statement.setLong(1 + i, padded(i).toLong) // index for jdbc parameters starts at 1
          }
          statement.executeUpdate()
        }
    }
  }

  override def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/add-to-pruned", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pruned VALUES (?)"), inTransaction = true) { statement =>
      shortChannelIds.foreach(shortChannelId => {
        statement.setLong(1, shortChannelId.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = withMetrics("network/remove-from-pruned", DbBackends.Sqlite) {
    using(sqlite.prepareStatement(s"DELETE FROM pruned WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = withMetrics("network/is-pruned", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT short_channel_id from pruned WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      statement.executeQuery().nonEmpty
    }
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()
}
