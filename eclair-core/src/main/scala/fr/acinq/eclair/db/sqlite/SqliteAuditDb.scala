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

import java.sql.{Connection, Statement}
import java.util.UUID
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.channel.{AvailableBalanceChanged, Channel, ChannelErrorOccured, NetworkFeePaid}
import fr.acinq.eclair.db.{AuditDb, ChannelLifecycleEvent, NetworkFee, Stats}
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.wire.ChannelCodecs
import grizzled.slf4j.Logging
import scala.collection.immutable.Queue
import scala.compat.Platform
import concurrent.duration._

class SqliteAuditDb(sqlite: Connection) extends AuditDb with Logging {

  import SqliteUtils._
  import ExtendedResultSet._

  val DB_NAME = "audit"
  val CURRENT_VERSION = 3

  using(sqlite.createStatement()) { statement =>

    def migration12(statement: Statement) = {
      statement.executeUpdate(s"ALTER TABLE sent ADD id BLOB DEFAULT '${ChannelCodecs.UNKNOWN_UUID.toString}' NOT NULL")
    }

    def migration23(statement: Statement) = {
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name STRING NOT NULL, error_message STRING NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")
    }

    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 => // previous version let's migrate
        logger.warn(s"migrating db $DB_NAME, found version=1 current=$CURRENT_VERSION")
        migration12(statement)
        migration23(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case 2 =>
        logger.warn(s"migrating db $DB_NAME, found version=2 current=$CURRENT_VERSION")
        migration23(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case CURRENT_VERSION =>
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL, id BLOB NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event STRING NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name STRING NOT NULL, error_message STRING NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

        statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")

      case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
  }

  override def add(e: AvailableBalanceChanged): Unit =
    using(sqlite.prepareStatement("INSERT INTO balance_updated VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.commitments.remoteParams.nodeId.value.toArray)
      statement.setLong(3, e.localBalanceMsat)
      statement.setLong(4, e.commitments.commitInput.txOut.amount.toLong)
      statement.setLong(5, e.commitments.remoteParams.channelReserveSatoshis) // remote decides what our reserve should be
      statement.setLong(6, Platform.currentTime)
      statement.executeUpdate()
    }

  override def add(e: ChannelLifecycleEvent): Unit =
    using(sqlite.prepareStatement("INSERT INTO channel_events VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setLong(3, e.capacitySat)
      statement.setBoolean(4, e.isFunder)
      statement.setBoolean(5, e.isPrivate)
      statement.setString(6, e.event)
      statement.setLong(7, Platform.currentTime)
      statement.executeUpdate()
    }

  override def add(e: PaymentSent): Unit =
    using(sqlite.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, e.amount.toLong)
      statement.setLong(2, e.feesPaid.toLong)
      statement.setBytes(3, e.paymentHash.toArray)
      statement.setBytes(4, e.paymentPreimage.toArray)
      statement.setBytes(5, e.toChannelId.toArray)
      statement.setLong(6, e.timestamp)
      statement.setBytes(7, e.id.toString.getBytes)

      statement.executeUpdate()
    }

  override def add(e: PaymentReceived): Unit =
    using(sqlite.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
      statement.setLong(1, e.amount.toLong)
      statement.setBytes(2, e.paymentHash.toArray)
      statement.setBytes(3, e.fromChannelId.toArray)
      statement.setLong(4, e.timestamp)
      statement.executeUpdate()
    }

  override def add(e: PaymentRelayed): Unit =
    using(sqlite.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, e.amountIn.toLong)
      statement.setLong(2, e.amountOut.toLong)
      statement.setBytes(3, e.paymentHash.toArray)
      statement.setBytes(4, e.fromChannelId.toArray)
      statement.setBytes(5, e.toChannelId.toArray)
      statement.setLong(6, e.timestamp)
      statement.executeUpdate()
    }

  override def add(e: NetworkFeePaid): Unit =
    using(sqlite.prepareStatement("INSERT INTO network_fees VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setBytes(3, e.tx.txid.toArray)
      statement.setLong(4, e.fee.toLong)
      statement.setString(5, e.txType)
      statement.setLong(6, Platform.currentTime)
      statement.executeUpdate()
    }

  override def add(e: ChannelErrorOccured): Unit =
    using(sqlite.prepareStatement("INSERT INTO channel_errors VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      val (errorName, errorMessage) = e.error match {
        case Channel.LocalError(t) => (t.getClass.getSimpleName, t.getMessage)
        case Channel.RemoteError(error) => ("remote", error.toAscii)
      }
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setString(3, errorName)
      statement.setString(4, errorMessage)
      statement.setBoolean(5, e.isFatal)
      statement.setLong(6, Platform.currentTime)
      statement.executeUpdate()
    }

  override def listSent(from: Long, to: Long): Seq[PaymentSent] =
    using(sqlite.prepareStatement("SELECT * FROM sent WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.seconds.toMillis)
      statement.setLong(2, to.seconds.toMillis)
      val rs = statement.executeQuery()
      var q: Queue[PaymentSent] = Queue()
      while (rs.next()) {
        q = q :+ PaymentSent(
          id = UUID.fromString(rs.getString("id")),
          amount = MilliSatoshi(rs.getLong("amount_msat")),
          feesPaid = MilliSatoshi(rs.getLong("fees_msat")),
          paymentHash = rs.getByteVector32("payment_hash"),
          paymentPreimage = rs.getByteVector32("payment_preimage"),
          toChannelId = rs.getByteVector32("to_channel_id"),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def listReceived(from: Long, to: Long): Seq[PaymentReceived] =
    using(sqlite.prepareStatement("SELECT * FROM received WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.seconds.toMillis)
      statement.setLong(2, to.seconds.toMillis)
      val rs = statement.executeQuery()
      var q: Queue[PaymentReceived] = Queue()
      while (rs.next()) {
        q = q :+ PaymentReceived(
          amount = MilliSatoshi(rs.getLong("amount_msat")),
          paymentHash = rs.getByteVector32("payment_hash"),
          fromChannelId = rs.getByteVector32("from_channel_id"),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def listRelayed(from: Long, to: Long): Seq[PaymentRelayed] =
    using(sqlite.prepareStatement("SELECT * FROM relayed WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.seconds.toMillis)
      statement.setLong(2, to.seconds.toMillis)
      val rs = statement.executeQuery()
      var q: Queue[PaymentRelayed] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRelayed(
          amountIn = MilliSatoshi(rs.getLong("amount_in_msat")),
          amountOut = MilliSatoshi(rs.getLong("amount_out_msat")),
          paymentHash = rs.getByteVector32("payment_hash"),
          fromChannelId = rs.getByteVector32("from_channel_id"),
          toChannelId = rs.getByteVector32("to_channel_id"),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def listNetworkFees(from: Long, to: Long): Seq[NetworkFee] =
    using(sqlite.prepareStatement("SELECT * FROM network_fees WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.seconds.toMillis)
      statement.setLong(2, to.seconds.toMillis)
      val rs = statement.executeQuery()
      var q: Queue[NetworkFee] = Queue()
      while (rs.next()) {
        q = q :+ NetworkFee(
          remoteNodeId = PublicKey(rs.getByteVector("node_id")),
          channelId = rs.getByteVector32("channel_id"),
          txId = rs.getByteVector32("tx_id"),
          feeSat = rs.getLong("fee_sat"),
          txType = rs.getString("tx_type"),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def stats: Seq[Stats] =
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery(
        """
          |SELECT
          |     channel_id,
          |     sum(avg_payment_amount_sat) AS avg_payment_amount_sat,
          |     sum(payment_count) AS payment_count,
          |     sum(relay_fee_sat) AS relay_fee_sat,
          |     sum(network_fee_sat) AS network_fee_sat
          |FROM (
          |       SELECT
          |           to_channel_id AS channel_id,
          |           avg(amount_out_msat) / 1000 AS avg_payment_amount_sat,
          |           count(*) AS payment_count,
          |           sum(amount_in_msat - amount_out_msat) / 1000 AS relay_fee_sat,
          |           0 AS network_fee_sat
          |       FROM relayed
          |       GROUP BY 1
          |     UNION
          |       SELECT
          |           channel_id,
          |           0 AS avg_payment_amount_sat,
          |           0 AS payment_count,
          |           0 AS relay_fee_sat,
          |           sum(fee_sat) AS network_fee_sat
          |       FROM network_fees
          |       GROUP BY 1
          |)
          |GROUP BY 1
        """.stripMargin)
      var q: Queue[Stats] = Queue()
      while (rs.next()) {
        q = q :+ Stats(
          channelId = rs.getByteVector32("channel_id"),
          avgPaymentAmountSatoshi = rs.getLong("avg_payment_amount_sat"),
          paymentCount = rs.getInt("payment_count"),
          relayFeeSatoshi = rs.getLong("relay_fee_sat"),
          networkFeeSatoshi = rs.getLong("network_fee_sat"))
      }
      q
    }

  override def close(): Unit = sqlite.close()

}
