package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.migration.MigrateDb.{checkVersions, migrateTable}

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.Instant

object MigrateAuditDb {

  private def migrateSentTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "sent"
    val insertSql = "INSERT INTO audit.sent (amount_msat, fees_msat, recipient_amount_msat, payment_id, parent_payment_id, payment_hash, payment_preimage, recipient_node_id, to_channel_id, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setLong(1, rs.getLong("amount_msat"))
      insertStatement.setLong(2, rs.getLong("fees_msat"))
      insertStatement.setLong(3, rs.getLong("recipient_amount_msat"))
      insertStatement.setString(4, rs.getString("payment_id"))
      insertStatement.setString(5, rs.getString("parent_payment_id"))
      insertStatement.setString(6, rs.getByteVector32("payment_hash").toHex)
      insertStatement.setString(7, rs.getByteVector32("payment_preimage").toHex)
      insertStatement.setString(8, rs.getByteVector("recipient_node_id").toHex)
      insertStatement.setString(9, rs.getByteVector32("to_channel_id").toHex)
      insertStatement.setTimestamp(10, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateReceivedTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "received"
    val insertSql = "INSERT INTO audit.received (amount_msat, payment_hash, from_channel_id, timestamp) VALUES (?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setLong(1, rs.getLong("amount_msat"))
      insertStatement.setString(2, rs.getByteVector32("payment_hash").toHex)
      insertStatement.setString(3, rs.getByteVector32("from_channel_id").toHex)
      insertStatement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateRelayedTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "relayed"
    val insertSql = "INSERT INTO audit.relayed (payment_hash, amount_msat, channel_id, direction, relay_type, timestamp) VALUES (?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("payment_hash").toHex)
      insertStatement.setLong(2, rs.getLong("amount_msat"))
      insertStatement.setString(3, rs.getByteVector32("channel_id").toHex)
      insertStatement.setString(4, rs.getString("direction"))
      insertStatement.setString(5, rs.getString("relay_type"))
      insertStatement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateRelayedTrampolineTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "relayed_trampoline"
    val insertSql = "INSERT INTO audit.relayed_trampoline (payment_hash, amount_msat, next_node_id, timestamp) VALUES (?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("payment_hash").toHex)
      insertStatement.setLong(2, rs.getLong("amount_msat"))
      insertStatement.setString(3, rs.getByteVector("next_node_id").toHex)
      insertStatement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateTransactionsPublishedTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "transactions_published"
    val insertSql = "INSERT INTO audit.transactions_published (tx_id, channel_id, node_id, mining_fee_sat, tx_type, timestamp) VALUES (?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("tx_id").toHex)
      insertStatement.setString(2, rs.getByteVector32("channel_id").toHex)
      insertStatement.setString(3, rs.getByteVector("node_id").toHex)
      insertStatement.setLong(4, rs.getLong("mining_fee_sat"))
      insertStatement.setString(5, rs.getString("tx_type"))
      insertStatement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateTransactionsConfirmedTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "transactions_confirmed"
    val insertSql = "INSERT INTO audit.transactions_confirmed (tx_id, channel_id, node_id, timestamp) VALUES (?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("tx_id").toHex)
      insertStatement.setString(2, rs.getByteVector32("channel_id").toHex)
      insertStatement.setString(3, rs.getByteVector("node_id").toHex)
      insertStatement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateChannelEventsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "channel_events"
    val insertSql = "INSERT INTO audit.channel_events (channel_id, node_id, capacity_sat, is_funder, is_private, event, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("channel_id").toHex)
      insertStatement.setString(2, rs.getByteVector("node_id").toHex)
      insertStatement.setLong(3, rs.getLong("capacity_sat"))
      insertStatement.setBoolean(4, rs.getBoolean("is_funder"))
      insertStatement.setBoolean(5, rs.getBoolean("is_private"))
      insertStatement.setString(6, rs.getString("event"))
      insertStatement.setTimestamp(7, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateChannelErrorsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "channel_errors WHERE error_name <> 'CannotAffordFees'"
    val insertSql = "INSERT INTO audit.channel_errors (channel_id, node_id, error_name, error_message, is_fatal, timestamp) VALUES (?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("channel_id").toHex)
      insertStatement.setString(2, rs.getByteVector("node_id").toHex)
      insertStatement.setString(3, rs.getString("error_name"))
      insertStatement.setString(4, rs.getString("error_message"))
      insertStatement.setBoolean(5, rs.getBoolean("is_fatal"))
      insertStatement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateChannelUpdatesTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "channel_updates"
    val insertSql = "INSERT INTO audit.channel_updates (channel_id, node_id, fee_base_msat, fee_proportional_millionths, cltv_expiry_delta, htlc_minimum_msat, htlc_maximum_msat, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("channel_id").toHex)
      insertStatement.setString(2, rs.getByteVector("node_id").toHex)
      insertStatement.setLong(3, rs.getLong("fee_base_msat"))
      insertStatement.setLong(4, rs.getLong("fee_proportional_millionths"))
      insertStatement.setLong(5, rs.getLong("cltv_expiry_delta"))
      insertStatement.setLong(6, rs.getLong("htlc_minimum_msat"))
      insertStatement.setLong(7, rs.getLong("htlc_maximum_msat"))
      insertStatement.setTimestamp(8, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migratePathFindingMetricsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "path_finding_metrics"
    val insertSql = "INSERT INTO audit.path_finding_metrics (amount_msat, fees_msat, status, duration_ms, timestamp, is_mpp, experiment_name, recipient_node_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setLong(1, rs.getLong("amount_msat"))
      insertStatement.setLong(2, rs.getLong("fees_msat"))
      insertStatement.setString(3, rs.getString("status"))
      insertStatement.setLong(4, rs.getLong("duration_ms"))
      insertStatement.setTimestamp(5, Timestamp.from(Instant.ofEpochMilli(rs.getLong("timestamp"))))
      insertStatement.setBoolean(6, rs.getBoolean("is_mpp"))
      insertStatement.setString(7, rs.getString("experiment_name"))
      insertStatement.setString(8, rs.getByteVector("recipient_node_id").toHex)
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  def migrateAllTables(source: Connection, destination: Connection): Unit = {
    checkVersions(source, destination, "audit", 8, 10)
    migrateSentTable(source, destination)
    migrateReceivedTable(source, destination)
    migrateRelayedTable(source, destination)
    migrateRelayedTrampolineTable(source, destination)
    migrateTransactionsPublishedTable(source, destination)
    migrateTransactionsConfirmedTable(source, destination)
    migrateChannelEventsTable(source, destination)
    migrateChannelErrorsTable(source, destination)
    migrateChannelUpdatesTable(source, destination)
    migratePathFindingMetricsTable(source, destination)
  }

}
