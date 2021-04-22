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
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.stateDataCodec
import grizzled.slf4j.Logging

import java.sql.{Statement, Timestamp}
import java.time.Instant
import javax.sql.DataSource
import scala.collection.immutable.Queue

class PgChannelsDb(implicit ds: DataSource, lock: PgLock) extends ChannelsDb with Logging {

  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  val DB_NAME = "channels"
  val CURRENT_VERSION = 4

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>

      def migration23(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN created_timestamp BIGINT")
        statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_payment_sent_timestamp BIGINT")
        statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_payment_received_timestamp BIGINT")
        statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN last_connected_timestamp BIGINT")
        statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN closed_timestamp BIGINT")
      }

      def migration34(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE local_channels ALTER COLUMN created_timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + created_timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE local_channels ALTER COLUMN last_payment_sent_timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + last_payment_sent_timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE local_channels ALTER COLUMN last_payment_received_timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + last_payment_received_timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE local_channels ALTER COLUMN last_connected_timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + last_connected_timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE local_channels ALTER COLUMN closed_timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + closed_timestamp * interval '1 millisecond'")

        statement.executeUpdate("ALTER TABLE htlc_infos ALTER COLUMN commitment_number  SET DATA TYPE BIGINT USING commitment_number::BIGINT")
      }

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE TABLE local_channels (channel_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE, created_timestamp TIMESTAMP WITH TIME ZONE, last_payment_sent_timestamp TIMESTAMP WITH TIME ZONE, last_payment_received_timestamp TIMESTAMP WITH TIME ZONE, last_connected_timestamp TIMESTAMP WITH TIME ZONE, closed_timestamp TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE TABLE htlc_infos (channel_id TEXT NOT NULL, commitment_number BIGINT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        case Some(v@2) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
          migration23(statement)
          migration34(statement)
        case Some(v@3) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
          migration34(statement)
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = withMetrics("channels/add-or-update-channel", DbBackends.Postgres) {
    withLock { pg =>
      val data = stateDataCodec.encode(state).require.toByteArray
      using(pg.prepareStatement(
        """
          | INSERT INTO local_channels (channel_id, data, is_closed)
          | VALUES (?, ?, FALSE)
          | ON CONFLICT (channel_id)
          | DO UPDATE SET data = EXCLUDED.data ;
          | """.stripMargin)) { statement =>
        statement.setString(1, state.channelId.toHex)
        statement.setBytes(2, data)
        statement.executeUpdate()
      }
    }
  }

  /**
   * Helper method to factor updating timestamp columns
   */
  private def updateChannelMetaTimestampColumn(channelId: ByteVector32, columnName: String): Unit = {
    inTransaction { pg =>
      using(pg.prepareStatement(s"UPDATE local_channels SET $columnName=? WHERE channel_id=?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.now()))
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

  override def removeChannel(channelId: ByteVector32): Unit = withMetrics("channels/remove-channel", DbBackends.Postgres) {
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

  override def listLocalChannels(): Seq[HasCommitments] = withMetrics("channels/list-local-channels", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.createStatement) { statement =>
        val rs = statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=FALSE")
        codecSequence(rs, stateDataCodec)
      }
    }
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = withMetrics("channels/add-htlc-info", DbBackends.Postgres) {
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

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = withMetrics("channels/list-htlc-infos", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, commitmentNumber)
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
