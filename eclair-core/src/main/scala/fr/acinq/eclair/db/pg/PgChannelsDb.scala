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

import com.zaxxer.hikari.util.IsolationLevel
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, TxId}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, Paginated}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.{Connection, Statement, Timestamp}
import java.time.Instant
import javax.sql.DataSource

object PgChannelsDb {
  val DB_NAME = "channels"
  val CURRENT_VERSION = 12
}

class PgChannelsDb(implicit ds: DataSource, lock: PgLock) extends ChannelsDb with Logging {

  import PgChannelsDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import fr.acinq.eclair.json.JsonSerializers.{formats, serialization}
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      /**
       * Before version 12, closed channels were directly kept in the local_channels table with an is_closed flag set to true.
       * We move them to a dedicated table, where we keep minimal channel information.
       */
      def migration1112(statement: Statement): Unit = {
        // We start by dropping for foreign key constraint on htlc_infos, otherwise we won't be able to move recently
        // closed channels to a different table.
        statement.executeQuery("SELECT conname FROM pg_catalog.pg_constraint WHERE contype = 'f'").map(rs => rs.getString("conname")).headOption match {
          case Some(foreignKeyConstraint) => statement.executeUpdate(s"ALTER TABLE local.htlc_infos DROP CONSTRAINT $foreignKeyConstraint")
          case None => logger.warn("couldn't find foreign key constraint for htlc_infos table: DB migration may fail")
        }
        // We can now move closed channels to a dedicated table.
        statement.executeUpdate("CREATE TABLE local.channels_closed (channel_id TEXT NOT NULL PRIMARY KEY, remote_node_id TEXT NOT NULL, funding_txid TEXT NOT NULL, funding_output_index BIGINT NOT NULL, funding_tx_index BIGINT NOT NULL, funding_key_path TEXT NOT NULL, channel_features TEXT NOT NULL, is_channel_opener BOOLEAN NOT NULL, commitment_format TEXT NOT NULL, announced BOOLEAN NOT NULL, capacity_satoshis BIGINT NOT NULL, closing_txid TEXT NOT NULL, closing_type TEXT NOT NULL, closing_script TEXT NOT NULL, local_balance_msat BIGINT NOT NULL, remote_balance_msat BIGINT NOT NULL, closing_amount_satoshis BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, closed_at TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE INDEX channels_closed_remote_node_id_idx ON local.channels_closed(remote_node_id)")
        // We migrate closed channels from the local_channels table to the new channels_closed table, whenever possible.
        val insertStatement = pg.prepareStatement("INSERT INTO local.channels_closed VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        val batchSize = 50
        using(pg.prepareStatement("SELECT channel_id, data, is_closed, created_timestamp, closed_timestamp FROM local.channels WHERE is_closed=TRUE")) { queryStatement =>
          val rs = queryStatement.executeQuery()
          var inserted = 0
          var batchCount = 0
          while (rs.next()) {
            val channelId = rs.getByteVector32FromHex("channel_id")
            val data_opt = channelDataCodec.decode(BitVector(rs.getBytes("data"))).require.value match {
              case d: DATA_NEGOTIATING_SIMPLE =>
                // We didn't store which closing transaction actually confirmed, so we select the most likely one.
                // The simple_close feature wasn't widely supported before this migration, so this shouldn't affect a lot of channels.
                val closingTx = d.publishedClosingTxs.lastOption.getOrElse(d.proposedClosingTxs.last.preferred_opt.get)
                Some(DATA_CLOSED(d, closingTx))
              case d: DATA_CLOSING =>
                Helpers.Closing.isClosingTypeAlreadyKnown(d) match {
                  case Some(closingType) => Some(DATA_CLOSED(d, closingType))
                  // If the closing type cannot be inferred from the stored data, it must be a mutual close.
                  // In that case, we didn't store which closing transaction actually confirmed, so we select the most likely one.
                  case None if d.mutualClosePublished.nonEmpty => Some(DATA_CLOSED(d, Helpers.Closing.MutualClose(d.mutualClosePublished.last)))
                  case None =>
                    logger.warn(s"cannot move channel_id=$channelId to the channels_closed table, unknown closing_type")
                    None
                }
              case d =>
                logger.warn(s"cannot move channel_id=$channelId to the channels_closed table (state=${d.getClass.getSimpleName})")
                None
            }
            data_opt match {
              case Some(data) =>
                insertStatement.setString(1, channelId.toHex)
                insertStatement.setString(2, data.remoteNodeId.toHex)
                insertStatement.setString(3, data.fundingTxId.value.toHex)
                insertStatement.setLong(4, data.fundingOutputIndex)
                insertStatement.setLong(5, data.fundingTxIndex)
                insertStatement.setString(6, data.fundingKeyPath)
                insertStatement.setString(7, data.channelFeatures)
                insertStatement.setBoolean(8, data.isChannelOpener)
                insertStatement.setString(9, data.commitmentFormat)
                insertStatement.setBoolean(10, data.announced)
                insertStatement.setLong(11, data.capacity.toLong)
                insertStatement.setString(12, data.closingTxId.value.toHex)
                insertStatement.setString(13, data.closingType)
                insertStatement.setString(14, data.closingScript.toHex)
                insertStatement.setLong(15, data.localBalance.toLong)
                insertStatement.setLong(16, data.remoteBalance.toLong)
                insertStatement.setLong(17, data.closingAmount.toLong)
                insertStatement.setTimestamp(18, rs.getTimestampNullable("created_timestamp").getOrElse(Timestamp.from(Instant.ofEpochMilli(0))))
                insertStatement.setTimestamp(19, rs.getTimestampNullable("closed_timestamp").getOrElse(Timestamp.from(Instant.ofEpochMilli(0))))
                insertStatement.addBatch()
                batchCount = batchCount + 1
                if (batchCount % batchSize == 0) {
                  inserted = inserted + insertStatement.executeBatch().sum
                  batchCount = 0
                }
              case None => ()
            }
          }
          inserted = inserted + insertStatement.executeBatch().sum
          logger.info(s"moved $inserted channels to the channels_closed table")
        }
        // We can now clean-up the active channels table.
        statement.executeUpdate("DELETE FROM local.channels WHERE is_closed=TRUE")
        statement.executeUpdate("ALTER TABLE local.channels DROP COLUMN is_closed")
        statement.executeUpdate("ALTER TABLE local.channels DROP COLUMN closed_timestamp")
      }

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS local")

          statement.executeUpdate("CREATE TABLE local.channels (channel_id TEXT NOT NULL PRIMARY KEY, remote_node_id TEXT NOT NULL, data BYTEA NOT NULL, json JSONB NOT NULL, created_timestamp TIMESTAMP WITH TIME ZONE, last_payment_sent_timestamp TIMESTAMP WITH TIME ZONE, last_payment_received_timestamp TIMESTAMP WITH TIME ZONE, last_connected_timestamp TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE TABLE local.channels_closed (channel_id TEXT NOT NULL PRIMARY KEY, remote_node_id TEXT NOT NULL, funding_txid TEXT NOT NULL, funding_output_index BIGINT NOT NULL, funding_tx_index BIGINT NOT NULL, funding_key_path TEXT NOT NULL, channel_features TEXT NOT NULL, is_channel_opener BOOLEAN NOT NULL, commitment_format TEXT NOT NULL, announced BOOLEAN NOT NULL, capacity_satoshis BIGINT NOT NULL, closing_txid TEXT NOT NULL, closing_type TEXT NOT NULL, closing_script TEXT NOT NULL, local_balance_msat BIGINT NOT NULL, remote_balance_msat BIGINT NOT NULL, closing_amount_satoshis BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, closed_at TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE local.htlc_infos (channel_id TEXT NOT NULL, commitment_number BIGINT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL)")
          statement.executeUpdate("CREATE TABLE local.htlc_infos_to_remove (channel_id TEXT NOT NULL PRIMARY KEY, before_commitment_number BIGINT NOT NULL)")

          statement.executeUpdate("CREATE INDEX local_channels_type_idx ON local.channels ((json->>'type'))")
          statement.executeUpdate("CREATE INDEX local_channels_remote_node_id_idx ON local.channels(remote_node_id)")
          // Note that we use two distinct indices instead of a composite index on (channel_id, commitment_number).
          // This is more efficient because we're writing a lot to this table but only reading when a channel is force-closed.
          statement.executeUpdate("CREATE INDEX htlc_infos_channel_id_idx ON local.htlc_infos(channel_id)")
          statement.executeUpdate("CREATE INDEX htlc_infos_commitment_number_idx ON local.htlc_infos(commitment_number)")
          statement.executeUpdate("CREATE INDEX channels_closed_remote_node_id_idx ON local.channels_closed(remote_node_id)")
        case Some(v) if v < 11 => throw new RuntimeException("You are updating from a version of eclair older than v0.13.0: please update to the v0.13.0 release first to migrate your channel data, and afterwards you'll be able to update to the latest version.")
        case Some(v@11) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
          if (v < 12) migration1112(statement)
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  /** Sometimes we may want to do a full reset when we update the json format */
  def resetJsonColumns(connection: Connection, oldTableName: Boolean = false): Unit = {
    val table = if (oldTableName) "local_channels" else "local.channels"
    migrateTable(connection, connection,
      table,
      s"UPDATE $table SET json=?::JSONB WHERE channel_id=?",
      (rs, statement) => {
        val state = channelDataCodec.decode(BitVector(rs.getBytes("data"))).require.value
        val json = serialization.write(state)
        statement.setString(1, json)
        statement.setString(2, state.channelId.toHex)
      }
    )(logger)
  }

  override def addOrUpdateChannel(data: PersistentChannelData): Unit = withMetrics("channels/add-or-update-channel", DbBackends.Postgres) {
    withLock { pg =>
      val encoded = channelDataCodec.encode(data).require.toByteArray
      using(pg.prepareStatement(
        """
          | INSERT INTO local.channels (channel_id, remote_node_id, data, json, created_timestamp, last_connected_timestamp)
          | VALUES (?, ?, ?, ?::JSONB, ?, ?)
          | ON CONFLICT (channel_id)
          | DO UPDATE SET data = EXCLUDED.data, json = EXCLUDED.json ;
          | """.stripMargin)) { statement =>
        statement.setString(1, data.channelId.toHex)
        statement.setString(2, data.remoteNodeId.toHex)
        statement.setBytes(3, encoded)
        statement.setString(4, serialization.write(data))
        statement.setTimestamp(5, Timestamp.from(Instant.now()))
        statement.setTimestamp(6, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def getChannel(channelId: ByteVector32): Option[PersistentChannelData] = withMetrics("channels/get-channel", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT data FROM local.channels WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeQuery.mapCodec(channelDataCodec).lastOption
      }
    }
  }

  /** Helper method to factor updating timestamp columns */
  private def updateChannelMetaTimestampColumn(channelId: ByteVector32, columnName: String): Unit = {
    inTransaction(IsolationLevel.TRANSACTION_READ_UNCOMMITTED) { pg =>
      using(pg.prepareStatement(s"UPDATE local.channels SET $columnName=? WHERE channel_id=?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.now()))
        statement.setString(2, channelId.toHex)
        statement.executeUpdate()
      }
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

  override def removeChannel(channelId: ByteVector32, data_opt: Option[DATA_CLOSED]): Unit = withMetrics("channels/remove-channel", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM local.pending_settlement_commands WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }

      // The htlc_infos may contain millions of rows, which is very expensive to delete synchronously.
      // We instead run an asynchronous job to clean up that data in small batches.
      markHtlcInfosForRemoval(channelId, Long.MaxValue)

      // If we have useful closing data for this channel, we keep it in a dedicated table.
      data_opt.foreach(data => {
        val createdAt_opt = using(pg.prepareStatement("SELECT created_timestamp FROM local.channels WHERE channel_id=?")) { statement =>
          statement.setString(1, channelId.toHex)
          statement.executeQuery().flatMap(rs => rs.getTimestampNullable("created_timestamp")).headOption
        }
        using(pg.prepareStatement("INSERT INTO local.channels_closed VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
          statement.setString(1, channelId.toHex)
          statement.setString(2, data.remoteNodeId.toHex)
          statement.setString(3, data.fundingTxId.value.toHex)
          statement.setLong(4, data.fundingOutputIndex)
          statement.setLong(5, data.fundingTxIndex)
          statement.setString(6, data.fundingKeyPath)
          statement.setString(7, data.channelFeatures)
          statement.setBoolean(8, data.isChannelOpener)
          statement.setString(9, data.commitmentFormat)
          statement.setBoolean(10, data.announced)
          statement.setLong(11, data.capacity.toLong)
          statement.setString(12, data.closingTxId.value.toHex)
          statement.setString(13, data.closingType)
          statement.setString(14, data.closingScript.toHex)
          statement.setLong(15, data.localBalance.toLong)
          statement.setLong(16, data.remoteBalance.toLong)
          statement.setLong(17, data.closingAmount.toLong)
          statement.setTimestamp(18, createdAt_opt.getOrElse(Timestamp.from(Instant.ofEpochMilli(0))))
          statement.setTimestamp(19, Timestamp.from(Instant.now()))
          statement.executeUpdate()
        }
      })

      // We can now remove this channel from the active channels table.
      using(pg.prepareStatement("DELETE FROM local.channels WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def markHtlcInfosForRemoval(channelId: ByteVector32, beforeCommitIndex: Long): Unit = withMetrics("channels/forget-htlc-infos", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO local.htlc_infos_to_remove (channel_id, before_commitment_number) VALUES(?, ?) ON CONFLICT (channel_id) DO UPDATE SET before_commitment_number = EXCLUDED.before_commitment_number")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, beforeCommitIndex)
        statement.executeUpdate()
      }
    }
  }

  override def removeHtlcInfos(batchSize: Int): Unit = withMetrics("channels/remove-htlc-infos", DbBackends.Postgres) {
    withLock { pg =>
      // Check if there are channels that need to be cleaned up.
      val channelToCleanUp_opt = using(pg.prepareStatement("SELECT channel_id, before_commitment_number FROM local.htlc_infos_to_remove LIMIT 1")) { statement =>
        statement.executeQuery().map(rs => {
          val channelId = ByteVector32(rs.getByteVector32FromHex("channel_id"))
          val beforeCommitmentNumber = rs.getLong("before_commitment_number")
          (channelId, beforeCommitmentNumber)
        }).lastOption
      }
      // Remove a batch of HTLC information for that channel.
      channelToCleanUp_opt.foreach { case (channelId, beforeCommitmentNumber) =>
        val deletedCount = using(pg.prepareStatement(s"DELETE FROM local.htlc_infos WHERE channel_id=? AND commitment_number IN (SELECT commitment_number FROM local.htlc_infos WHERE channel_id=? AND commitment_number<? LIMIT $batchSize)")) { statement =>
          statement.setString(1, channelId.toHex)
          statement.setString(2, channelId.toHex)
          statement.setLong(3, beforeCommitmentNumber)
          statement.executeUpdate()
        }
        logger.info(s"deleted $deletedCount rows from htlc_infos for channelId=$channelId beforeCommitmentNumber=$beforeCommitmentNumber")
        // If we've deleted all HTLC information for that channel, we can now remove it from the DB.
        if (deletedCount < batchSize) {
          using(pg.prepareStatement("DELETE FROM local.htlc_infos_to_remove WHERE channel_id=?")) { statement =>
            statement.setString(1, channelId.toHex)
            statement.executeUpdate()
          }
        }
      }
    }
  }

  override def listLocalChannels(): Seq[PersistentChannelData] = withMetrics("channels/list-local-channels", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.createStatement) { statement =>
        statement.executeQuery("SELECT data FROM local.channels")
          .mapCodec(channelDataCodec).toSeq
      }
    }
  }

  override def listClosedChannels(remoteNodeId_opt: Option[PublicKey], paginated_opt: Option[Paginated]): Seq[DATA_CLOSED] = withMetrics("channels/list-closed-channels", DbBackends.Postgres) {
    val sql = remoteNodeId_opt match {
      case Some(remoteNodeId) => s"SELECT * FROM local.channels_closed WHERE remote_node_id = '${remoteNodeId.toHex}' ORDER BY closed_at DESC"
      case None => "SELECT * FROM local.channels_closed ORDER BY closed_at DESC"
    }
    withLock { pg =>
      using(pg.prepareStatement(limited(sql, paginated_opt))) { statement =>
        statement.executeQuery().map { rs =>
          DATA_CLOSED(
            channelId = rs.getByteVector32FromHex("channel_id"),
            remoteNodeId = PublicKey(rs.getByteVectorFromHex("remote_node_id")),
            fundingTxId = TxId(rs.getByteVector32FromHex("funding_txid")),
            fundingOutputIndex = rs.getLong("funding_output_index"),
            fundingTxIndex = rs.getLong("funding_tx_index"),
            fundingKeyPath = rs.getString("funding_key_path"),
            channelFeatures = rs.getString("channel_features"),
            isChannelOpener = rs.getBoolean("is_channel_opener"),
            commitmentFormat = rs.getString("commitment_format"),
            announced = rs.getBoolean("announced"),
            capacity = Satoshi(rs.getLong("capacity_satoshis")),
            closingTxId = TxId(rs.getByteVector32FromHex("closing_txid")),
            closingType = rs.getString("closing_type"),
            closingScript = rs.getByteVectorFromHex("closing_script"),
            localBalance = MilliSatoshi(rs.getLong("local_balance_msat")),
            remoteBalance = MilliSatoshi(rs.getLong("remote_balance_msat")),
            closingAmount = Satoshi(rs.getLong("closing_amount_satoshis"))
          )
        }.toSeq
      }
    }
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = withMetrics("channels/add-htlc-info", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO local.htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
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
      using(pg.prepareStatement("SELECT payment_hash, cltv_expiry FROM local.htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, commitmentNumber)
        statement.executeQuery
          .map { rs =>
            (ByteVector32(rs.getByteVector32FromHex("payment_hash")), CltvExpiry(rs.getLong("cltv_expiry")))
          }.toSeq
      }
    }
  }
}
