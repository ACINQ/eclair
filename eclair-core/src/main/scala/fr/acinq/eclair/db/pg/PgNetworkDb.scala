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

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import fr.acinq.eclair.{RealShortChannelId, ShortChannelId}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.{Connection, ResultSet, Statement}
import javax.sql.DataSource
import scala.collection.immutable.SortedMap

object PgNetworkDb {
  val DB_NAME = "network"
  val CURRENT_VERSION = 4
}

class PgNetworkDb(implicit ds: DataSource) extends NetworkDb with Logging {

  import PgNetworkDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import fr.acinq.eclair.json.JsonSerializers.{formats, serialization}

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>

      def migration23(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE nodes ADD COLUMN json JSONB")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_announcement_json JSONB")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_update_1_json JSONB")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN channel_update_2_json JSONB")
        resetJsonColumns(pg, oldTableName = true)
        statement.executeUpdate("ALTER TABLE nodes ALTER COLUMN json SET NOT NULL")
        statement.executeUpdate("ALTER TABLE channels ALTER COLUMN channel_announcement_json SET NOT NULL")
      }

      def migration34(statement: Statement): Unit = {
        statement.executeUpdate("CREATE SCHEMA network")
        statement.executeUpdate("ALTER TABLE nodes SET SCHEMA network")
        statement.executeUpdate("ALTER TABLE channels RENAME TO public_channels")
        statement.executeUpdate("ALTER TABLE public_channels SET SCHEMA network")
        statement.executeUpdate("ALTER TABLE pruned RENAME TO pruned_channels")
        statement.executeUpdate("ALTER TABLE pruned_channels SET SCHEMA network")
      }

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA network")
          statement.executeUpdate("CREATE TABLE network.nodes (node_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, json JSONB NOT NULL)")
          statement.executeUpdate("CREATE TABLE network.public_channels (short_channel_id BIGINT NOT NULL PRIMARY KEY, txid TEXT NOT NULL, channel_announcement BYTEA NOT NULL, capacity_sat BIGINT NOT NULL, channel_update_1 BYTEA NULL, channel_update_2 BYTEA NULL, channel_announcement_json JSONB NOT NULL, channel_update_1_json JSONB NULL, channel_update_2_json JSONB NULL)")
        case Some(v@(2 | 3)) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
          if (v < 3) {
            migration23(statement)
          }
          if (v < 4) {
            migration34(statement)
          }
        case Some(CURRENT_VERSION) =>
          // We clean up channels that contain an invalid channel update (e.g. missing htlc_maximum_msat).
          statement.executeQuery("SELECT short_channel_id, channel_update_1, channel_update_2 FROM network.public_channels").map(rs => {
            val shortChannelId = rs.getLong("short_channel_id")
            val validChannelUpdate1 = rs.getBitVectorOpt("channel_update_1").forall(channelUpdateCodec.decode(_).isSuccessful)
            val validChannelUpdate2 = rs.getBitVectorOpt("channel_update_2").forall(channelUpdateCodec.decode(_).isSuccessful)
            (shortChannelId, validChannelUpdate1 && validChannelUpdate2)
          }).collect {
            case (scid, false) =>
              logger.warn(s"removing channel update with scid=$scid from the network DB (update cannot be decoded)")
              statement.executeUpdate(s"DELETE FROM network.public_channels WHERE short_channel_id=$scid")
          }
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  /** Sometimes we may want to do a full reset when we update the json format */
  def resetJsonColumns(connection: Connection, oldTableName: Boolean = false): Unit = {
    val nodesTable = if (oldTableName) "nodes" else "network.nodes"
    val channelsTable = if (oldTableName) "channels" else "network.public_channels"
    migrateTable(connection, connection,
      nodesTable,
      s"UPDATE $nodesTable SET json=?::JSONB WHERE node_id=?",
      (rs, statement) => {
        val node = nodeAnnouncementCodec.decode(BitVector(rs.getBytes("data"))).require.value
        val json = serialization.write(node)
        statement.setString(1, json)
        statement.setString(2, node.nodeId.toString())
      }
    )(logger)
    migrateTable(connection, connection,
      channelsTable,
      s"UPDATE $channelsTable SET channel_announcement_json=?::JSONB, channel_update_1_json=?::JSONB, channel_update_2_json=?::JSONB WHERE short_channel_id=?",
      (rs, statement) => {
        val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
        val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
        val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
        val json = serialization.write(ann)
        val u1_json = channel_update_1_opt.map(serialization.write(_)).orNull
        val u2_json = channel_update_2_opt.map(serialization.write(_)).orNull
        statement.setString(1, json)
        statement.setString(2, u1_json)
        statement.setString(3, u2_json)
        statement.setLong(4, ann.shortChannelId.toLong)
      }
    )(logger)
  }

  override def addNode(n: NodeAnnouncement): Unit = withMetrics("network/add-node", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO network.nodes (node_id, data, json) VALUES (?, ?, ?::JSONB) ON CONFLICT DO NOTHING")) {
        statement =>
          statement.setString(1, n.nodeId.value.toHex)
          statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
          statement.setString(3, serialization.write(n))
          statement.executeUpdate()
      }
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = withMetrics("network/update-node", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("UPDATE network.nodes SET data=?, json=?::JSONB WHERE node_id=?")) {
        statement =>
          statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
          statement.setString(2, serialization.write(n))
          statement.setString(3, n.nodeId.value.toHex)
          statement.executeUpdate()
      }
    }
  }

  override def getNode(nodeId: Crypto.PublicKey): Option[NodeAnnouncement] = withMetrics("network/get-node", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT data FROM network.nodes WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeQuery()
          .mapCodec(nodeAnnouncementCodec)
          .headOption
      }
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = withMetrics("network/remove-node", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("DELETE FROM network.nodes WHERE node_id=?")) {
        statement =>
          statement.setString(1, nodeId.value.toHex)
          statement.executeUpdate()
      }
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = withMetrics("network/list-nodes", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.createStatement()) { statement =>
        statement.executeQuery("SELECT data FROM network.nodes")
          .mapCodec(nodeAnnouncementCodec).toSeq
      }
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = withMetrics("network/add-channel", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO network.public_channels (short_channel_id, txid, channel_announcement, capacity_sat, channel_announcement_json) VALUES (?, ?, ?, ?, ?::JSONB) ON CONFLICT DO NOTHING")) {
        statement =>
          statement.setLong(1, c.shortChannelId.toLong)
          statement.setString(2, txid.toHex)
          statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
          statement.setLong(4, capacity.toLong)
          statement.setString(5, serialization.write(c))
          statement.executeUpdate()
      }
    }
  }

  override def updateChannel(u: ChannelUpdate): Unit = withMetrics("network/update-channel", DbBackends.Postgres) {
    val column = if (u.channelFlags.isNode1) "channel_update_1" else "channel_update_2"
    inTransaction { pg =>
      using(pg.prepareStatement(s"UPDATE network.public_channels SET $column=?, ${column}_json=?::JSONB WHERE short_channel_id=?")) {
        statement =>
          statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
          statement.setString(2, serialization.write(u))
          statement.setLong(3, u.shortChannelId.toLong)
          statement.executeUpdate()
      }
    }
  }

  private def parseChannel(rs: ResultSet): PublicChannel = {
    val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
    val txId = ByteVector32.fromValidHex(rs.getString("txid"))
    val capacity = rs.getLong("capacity_sat")
    val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
    val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
    PublicChannel(ann, txId, Satoshi(capacity), channel_update_1_opt, channel_update_2_opt, None)
  }

  override def getChannel(shortChannelId: RealShortChannelId): Option[PublicChannel] = withMetrics("network/get-channel", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT channel_announcement, txid, capacity_sat, channel_update_1, channel_update_2 FROM network.public_channels WHERE short_channel_id=?")) { statement =>
        statement.setLong(1, shortChannelId.toLong)
        statement.executeQuery().map(parseChannel).headOption
      }
    }
  }

  override def listChannels(): SortedMap[RealShortChannelId, PublicChannel] = withMetrics("network/list-channels", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.createStatement()) { statement =>
        statement.executeQuery("SELECT channel_announcement, txid, capacity_sat, channel_update_1, channel_update_2 FROM network.public_channels")
          .foldLeft(SortedMap.empty[RealShortChannelId, PublicChannel]) { (m, rs) =>
            val channel = parseChannel(rs)
            m + (channel.shortChannelId -> channel)
          }
      }
    }
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = withMetrics("network/remove-channels", DbBackends.Postgres) {
    val batchSize = 100
    inTransaction { pg =>
      using(pg.prepareStatement(s"DELETE FROM network.public_channels WHERE short_channel_id IN (${
        List.fill(batchSize)("?").mkString(",")
      })")) {
        statement =>
          shortChannelIds
            .map(_.toLong)
            .grouped(batchSize)
            .foreach {
              group =>
                val padded = group.toArray.padTo(batchSize, 0L)
                for (i <- 0 until batchSize) {
                  statement.setLong(1 + i, padded(i)) // index for jdbc parameters starts at 1
                }
                statement.executeUpdate()
            }
      }
    }
  }

}
