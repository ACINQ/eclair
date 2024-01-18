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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.PersistentChannelData
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import fr.acinq.eclair.{CltvExpiry, Paginated, TimestampMilli}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.{Connection, Statement}

object SqliteChannelsDb {
  val DB_NAME = "channels"
  val CURRENT_VERSION = 5
}

class SqliteChannelsDb(val sqlite: Connection) extends ChannelsDb with Logging {

  import SqliteChannelsDb._
  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  /**
   * The SQLite documentation states that "It is not possible to enable or disable foreign key constraints in the middle
   * of a multi-statement transaction (when SQLite is not in autocommit mode).".
   * So we need to set foreign keys before we initialize tables / migrations (which is done inside a transaction).
   */
  using(sqlite.createStatement()) { statement =>
    statement.execute("PRAGMA foreign_keys = ON")
  }

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN is_closed BOOLEAN NOT NULL DEFAULT 0")
    }

    def migration23(statement: Statement): Unit = {
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN created_timestamp INTEGER")
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_payment_sent_timestamp INTEGER")
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_payment_received_timestamp INTEGER")
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_connected_timestamp INTEGER")
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN closed_timestamp INTEGER")
    }

    def migration34(): Unit = {
      migrateTable(sqlite, sqlite,
        "local_channels",
        s"UPDATE local_channels SET data=? WHERE channel_id=?",
        (rs, statement) => {
          // This forces a re-serialization of the channel data with latest codecs, because as of codecs v3 we don't
          // store local commitment signatures anymore, and we want to clean up existing data
          val state = channelDataCodec.decode(BitVector(rs.getBytes("data"))).require.value
          val data = channelDataCodec.encode(state).require.toByteArray
          statement.setBytes(1, data)
          statement.setBytes(2, state.channelId.toArray)
        }
      )(logger)
    }

    def migration45(): Unit = {
      statement.executeUpdate("CREATE TABLE closed_channels_to_clean_up (channel_id BLOB NOT NULL PRIMARY KEY)")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT 0, created_timestamp INTEGER, last_payment_sent_timestamp INTEGER, last_payment_received_timestamp INTEGER, last_connected_timestamp INTEGER, closed_timestamp INTEGER)")
        statement.executeUpdate("CREATE TABLE htlc_infos (channel_id BLOB NOT NULL, commitment_number INTEGER NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
        statement.executeUpdate("CREATE TABLE closed_channels_to_clean_up (channel_id BLOB NOT NULL PRIMARY KEY)")
        statement.executeUpdate("CREATE INDEX htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
      case Some(v@(1 | 2 | 3)) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        if (v < 2) {
          migration12(statement)
        }
        if (v < 3) {
          migration23(statement)
        }
        if (v < 4) {
          migration34()
        }
        if (v < 5) {
          migration45()
        }
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOrUpdateChannel(data: PersistentChannelData): Unit = withMetrics("channels/add-or-update-channel", DbBackends.Sqlite) {
    val encoded = channelDataCodec.encode(data).require.toByteArray
    using(sqlite.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update =>
      update.setBytes(1, encoded)
      update.setBytes(2, data.channelId.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO local_channels (channel_id, data, created_timestamp, last_connected_timestamp, is_closed) VALUES (?, ?, ?, ?, 0)")) { statement =>
          statement.setBytes(1, data.channelId.toArray)
          statement.setBytes(2, encoded)
          statement.setLong(3, TimestampMilli.now().toLong)
          statement.setLong(4, TimestampMilli.now().toLong)
          statement.executeUpdate()
        }
      }
    }
  }

  override def getChannel(channelId: ByteVector32): Option[PersistentChannelData] = withMetrics("channels/get-channel", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT data FROM local_channels WHERE channel_id=? AND is_closed=0")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeQuery.mapCodec(channelDataCodec).lastOption
    }
  }

  /**
   * Helper method to factor updating timestamp columns
   */
  private def updateChannelMetaTimestampColumn(channelId: ByteVector32, columnName: String): Unit = {
    using(sqlite.prepareStatement(s"UPDATE local_channels SET $columnName=? WHERE channel_id=?")) { statement =>
      statement.setLong(1, TimestampMilli.now().toLong)
      statement.setBytes(2, channelId.toArray)
      statement.executeUpdate()
    }
  }

  override def updateChannelMeta(channelId: ByteVector32, event: ChannelEvent.EventType): Unit = {
    val timestampColumn_opt = event match {
      case ChannelEvent.EventType.Connected => Some("last_connected_timestamp")
      case ChannelEvent.EventType.PaymentReceived => Some("last_payment_received_timestamp")
      case ChannelEvent.EventType.PaymentSent => Some("last_payment_sent_timestamp")
      case _ => None
    }
    timestampColumn_opt.foreach(updateChannelMetaTimestampColumn(channelId, _))
  }

  override def removeChannel(channelId: ByteVector32): Unit = withMetrics("channels/remove-channel", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM pending_settlement_commands WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeUpdate()
    }

    // The htlc_infos may contain millions of rows, which is very expensive to delete synchronously.
    // We instead run an asynchronous job to clean up that data in small batches.
    using(sqlite.prepareStatement("INSERT INTO closed_channels_to_clean_up VALUES (?)")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeUpdate()
    }

    using(sqlite.prepareStatement("UPDATE local_channels SET is_closed=1, closed_timestamp=? WHERE channel_id=?")) { statement =>
      statement.setLong(1, TimestampMilli.now().toLong)
      statement.setBytes(2, channelId.toArray)
      statement.executeUpdate()
    }
  }

  override def removeHtlcInfos(batchSize: Int): Unit = withMetrics("channels/remove-htlc-infos", DbBackends.Sqlite) {
    // Check if there are channels that need to be cleaned up.
    val channelId_opt = using(sqlite.prepareStatement("SELECT channel_id FROM closed_channels_to_clean_up LIMIT 1")) { statement =>
      statement.executeQuery().map(rs => ByteVector32(rs.getByteVector32("channel_id"))).lastOption
    }
    // Remove a batch of HTLC information for that channel.
    channelId_opt.foreach(channelId => {
      val deletedCount = using(sqlite.prepareStatement(s"DELETE FROM htlc_infos WHERE channel_id=? AND commitment_number IN (SELECT commitment_number FROM htlc_infos WHERE channel_id=? LIMIT $batchSize)")) { statement =>
        statement.setBytes(1, channelId.toArray)
        statement.setBytes(2, channelId.toArray)
        statement.executeUpdate()
      }
      // If we've deleted all HTLC information for that channel, we can now remove it from the DB.
      if (deletedCount < batchSize) {
        using(sqlite.prepareStatement("DELETE FROM closed_channels_to_clean_up WHERE channel_id=?")) { statement =>
          statement.setBytes(1, channelId.toArray)
          statement.executeUpdate()
        }
      }
    })
  }

  override def listLocalChannels(): Seq[PersistentChannelData] = withMetrics("channels/list-local-channels", DbBackends.Sqlite) {
    using(sqlite.createStatement) { statement =>
      statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=0")
        .mapCodec(channelDataCodec).toSeq
    }
  }


  override def listClosedChannels(remoteNodeId_opt: Option[PublicKey], paginated_opt: Option[Paginated]): Seq[PersistentChannelData] = withMetrics("channels/list-closed-channels", DbBackends.Sqlite) {
    val sql = "SELECT data FROM local_channels WHERE is_closed=1 ORDER BY closed_timestamp DESC"
    remoteNodeId_opt match {
      case None =>
        using(sqlite.prepareStatement(limited(sql, paginated_opt))) { statement =>
          statement.executeQuery().mapCodec(channelDataCodec).toSeq
        }
      case Some(nodeId) =>
        using(sqlite.prepareStatement(sql)) { statement =>
          val filtered = statement.executeQuery()
            .mapCodec(channelDataCodec).filter(_.remoteNodeId == nodeId)
          val limited = paginated_opt match {
            case None => filtered
            case Some(p) => filtered.slice(p.skip, p.skip + p.count)
          }
          limited.toSeq
        }
    }
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = withMetrics("channels/add-htlc-info", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, commitmentNumber)
      statement.setBytes(3, paymentHash.toArray)
      statement.setLong(4, cltvExpiry.toLong)
      statement.executeUpdate()
    }
  }

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = withMetrics("channels/list-htlc-infos", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, commitmentNumber)
      statement.executeQuery
        .map(rs => (ByteVector32(rs.getByteVector32("payment_hash")), CltvExpiry(rs.getLong("cltv_expiry"))))
        .toSeq
    }
  }
}
