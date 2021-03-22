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

package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging
import javax.sql.DataSource

import scala.collection.immutable.SortedMap

class PgNetworkDb(implicit ds: DataSource) extends NetworkDb with Logging {

  import PgUtils.ExtendedResultSet._
  import PgUtils._

  val DB_NAME = "network"
  val CURRENT_VERSION = 2

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME, CURRENT_VERSION) match {
        case CURRENT_VERSION => () // nothing to do
        case unknown => throw new IllegalArgumentException(s"unknown version $unknown for network db")
      }
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id BIGINT NOT NULL PRIMARY KEY, txid TEXT NOT NULL, channel_announcement BYTEA NOT NULL, capacity_sat BIGINT NOT NULL, channel_update_1 BYTEA NULL, channel_update_2 BYTEA NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id BIGINT NOT NULL PRIMARY KEY)")
    }
  }

  override def addNode(n: NodeAnnouncement): Unit = withMetrics("network/add-node") {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO nodes VALUES (?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, n.nodeId.value.toHex)
        statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
        statement.executeUpdate()
      }
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = withMetrics("network/update-node") {
    inTransaction { pg =>
      using(pg.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
        statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
        statement.setString(2, n.nodeId.value.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def getNode(nodeId: Crypto.PublicKey): Option[NodeAnnouncement] = withMetrics("network/get-node") {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT data FROM nodes WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        val rs = statement.executeQuery()
        codecSequence(rs, nodeAnnouncementCodec).headOption
      }
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = withMetrics("network/remove-node") {
    inTransaction { pg =>
      using(pg.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = withMetrics("network/list-nodes") {
    inTransaction { pg =>
      using(pg.createStatement()) { statement =>
        val rs = statement.executeQuery("SELECT data FROM nodes")
        codecSequence(rs, nodeAnnouncementCodec)
      }
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = withMetrics("network/add-channel") {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO channels VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setLong(1, c.shortChannelId.toLong)
        statement.setString(2, txid.toHex)
        statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
        statement.setLong(4, capacity.toLong)
        statement.executeUpdate()
      }
    }
  }

  override def updateChannel(u: ChannelUpdate): Unit = withMetrics("network/update-channel") {
    val column = if (u.isNode1) "channel_update_1" else "channel_update_2"
    inTransaction { pg =>
      using(pg.prepareStatement(s"UPDATE channels SET $column=? WHERE short_channel_id=?")) { statement =>
        statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
        statement.setLong(2, u.shortChannelId.toLong)
        statement.executeUpdate()
      }
    }
  }

  override def listChannels(): SortedMap[ShortChannelId, PublicChannel] = withMetrics("network/list-channels") {
    inTransaction { pg =>
      using(pg.createStatement()) { statement =>
        val rs = statement.executeQuery("SELECT channel_announcement, txid, capacity_sat, channel_update_1, channel_update_2 FROM channels")
        var m = SortedMap.empty[ShortChannelId, PublicChannel]
        while (rs.next()) {
          val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
          val txId = ByteVector32.fromValidHex(rs.getString("txid"))
          val capacity = rs.getLong("capacity_sat")
          val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
          val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
          m = m + (ann.shortChannelId -> PublicChannel(ann, txId, Satoshi(capacity), channel_update_1_opt, channel_update_2_opt, None))
        }
        m
      }
    }
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/remove-channels") {
    inTransaction { pg =>
      using(pg.createStatement) { statement =>
        shortChannelIds
          .grouped(1000) // remove channels by batch of 1000
          .foreach { _ =>
            val ids = shortChannelIds.map(_.toLong).mkString(",")
            statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id IN ($ids)")
          }
      }
    }
  }

  override def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/add-to-pruned") {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO pruned VALUES (?) ON CONFLICT DO NOTHING")) { statement =>
        shortChannelIds.foreach(shortChannelId => {
          statement.setLong(1, shortChannelId.toLong)
          statement.addBatch()
        })
        statement.executeBatch()
      }
    }
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = withMetrics("network/remove-from-pruned") {
    inTransaction { pg =>
      using(pg.createStatement) { statement =>
        statement.executeUpdate(s"DELETE FROM pruned WHERE short_channel_id=${shortChannelId.toLong}")
      }
    }
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = withMetrics("network/is-pruned") {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT short_channel_id from pruned WHERE short_channel_id=?")) { statement =>
        statement.setLong(1, shortChannelId.toLong)
        val rs = statement.executeQuery()
        rs.next()
      }
    }
  }

  override def close(): Unit = ()
}
