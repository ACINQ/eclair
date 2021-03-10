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

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.pg.PgUtils.DatabaseLock
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec
import grizzled.slf4j.Logging

import java.sql.Statement
import javax.sql.DataSource
import scala.collection.immutable.Queue

class PgChannelsDb(implicit ds: DataSource, lock: DatabaseLock) extends ChannelsDb with Logging {

  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  val DB_NAME = "channels"
  val CURRENT_VERSION = 3

  def migration23(statement: Statement): Unit = {
    statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN created_timestamp BIGINT")
    statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_payment_sent_timestamp BIGINT")
    statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_payment_received_timestamp BIGINT")
    statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_connected_timestamp BIGINT")
    statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN closed_timestamp BIGINT")
  }

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME, CURRENT_VERSION) match {
        case 2 =>
          logger.warn(s"migrating db $DB_NAME, found version=2 current=$CURRENT_VERSION")
          migration23(statement)
          setVersion(statement, DB_NAME, CURRENT_VERSION)
        case CURRENT_VERSION =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE, created_timestamp BIGINT, last_payment_sent_timestamp BIGINT, last_payment_received_timestamp BIGINT, last_connected_timestamp BIGINT, closed_timestamp BIGINT)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id TEXT NOT NULL, commitment_number TEXT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
    }
  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = withMetrics("channels/add-or-update-channel") {
    withLock { pg =>
      val data = stateDataCodec.encode(state).require.toByteArray
      using(pg.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update =>
        update.setBytes(1, data)
        update.setString(2, state.channelId.toHex)
        if (update.executeUpdate() == 0) {
          using(pg.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed) VALUES (?, ?, FALSE)")) { statement =>
            statement.setString(1, state.channelId.toHex)
            statement.setBytes(2, data)
            statement.executeUpdate()
          }
        }
      }
    }
  }

  /**
   * Helper method to factor updating timestamp columns
   */
  private def updateChannelMetaTimestampColumn(channelId: ByteVector32, columnName: String): Unit = {
    inTransaction { pg =>
      using(pg.prepareStatement(s"UPDATE local_channels SET $columnName=? WHERE channel_id=?")) { statement =>
        statement.setLong(1, System.currentTimeMillis)
        statement.setString(2, channelId.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def updateChannelMeta(channelId: ByteVector32, event: ChannelEvent.EventType): Unit = {
    val timestampColumn_opt = event match {
      case ChannelEvent.EventType.Created => Some("created_timestamp")
      case ChannelEvent.EventType.Connected => Some("last_connected_timestamp")
      case ChannelEvent.EventType.PaymentReceived => Some("last_payment_received_timestamp")
      case ChannelEvent.EventType.PaymentSent => Some("last_payment_sent_timestamp")
      case _: ChannelEvent.EventType.Closed => Some("closed_timestamp")
      case _ => None
    }
    timestampColumn_opt.foreach(updateChannelMetaTimestampColumn(channelId, _))
  }

  override def removeChannel(channelId: ByteVector32): Unit = withMetrics("channels/remove-channel") {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM pending_relay WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }

      using(pg.prepareStatement("DELETE FROM htlc_infos WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }

      using(pg.prepareStatement("UPDATE local_channels SET is_closed=TRUE WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def listLocalChannels(): Seq[HasCommitments] = withMetrics("channels/list-local-channels") {
    withLock { pg =>
      using(pg.createStatement) { statement =>
        val rs = statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=FALSE")
        codecSequence(rs, stateDataCodec)
      }
    }
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = withMetrics("channels/add-htlc-info") {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, commitmentNumber)
        statement.setString(3, paymentHash.toHex)
        statement.setLong(4, cltvExpiry.toLong)
        statement.executeUpdate()
      }
    }
  }

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = withMetrics("channels/list-htlc-infos") {
    withLock { pg =>
      using(pg.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setString(2, commitmentNumber.toString)
        val rs = statement.executeQuery
        var q: Queue[(ByteVector32, CltvExpiry)] = Queue()
        while (rs.next()) {
          q = q :+ (ByteVector32(rs.getByteVector32FromHex("payment_hash")), CltvExpiry(rs.getLong("cltv_expiry")))
        }
        q
      }
    }
  }

  override def close(): Unit = ()
}
