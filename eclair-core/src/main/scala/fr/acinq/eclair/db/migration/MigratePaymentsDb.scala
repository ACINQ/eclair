package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.migration.MigrateDb.{checkVersions, migrateTable}

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.Instant

object MigratePaymentsDb {

  private def migrateReceivedPaymentsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "received_payments"
    val insertSql = "INSERT INTO payments.received (payment_hash, payment_type, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector("payment_hash").toHex)
      insertStatement.setString(2, rs.getString("payment_type"))
      insertStatement.setString(3, rs.getByteVector("payment_preimage").toHex)
      insertStatement.setString(4, rs.getString("payment_request"))
      insertStatement.setObject(5, rs.getLongNullable("received_msat").orNull)
      insertStatement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(rs.getLong("created_at"))))
      insertStatement.setTimestamp(7, Timestamp.from(Instant.ofEpochMilli(rs.getLong("expire_at"))))
      insertStatement.setObject(8, rs.getLongNullable("received_at").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateSentPaymentsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "sent_payments"
    val insertSql = "INSERT INTO payments.sent (id, parent_id, external_id, payment_hash, payment_preimage, payment_type, amount_msat, fees_msat, recipient_amount_msat, recipient_node_id, payment_request, payment_route, failures, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getString("id"))
      insertStatement.setString(2, rs.getString("parent_id"))
      insertStatement.setString(3, rs.getStringNullable("external_id").orNull)
      insertStatement.setString(4, rs.getByteVector("payment_hash").toHex)
      insertStatement.setString(5, rs.getByteVector32Nullable("payment_preimage").map(_.toHex).orNull)
      insertStatement.setString(6, rs.getString("payment_type"))
      insertStatement.setLong(7, rs.getLong("amount_msat"))
      insertStatement.setObject(8, rs.getLongNullable("fees_msat").orNull)
      insertStatement.setLong(9, rs.getLong("recipient_amount_msat"))
      insertStatement.setString(10, rs.getByteVector("recipient_node_id").toHex)
      insertStatement.setString(11, rs.getStringNullable("payment_request").orNull)
      insertStatement.setBytes(12, rs.getBytes("payment_route"))
      insertStatement.setBytes(13, rs.getBytes("failures"))
      insertStatement.setTimestamp(14, Timestamp.from(Instant.ofEpochMilli(rs.getLong("created_at"))))
      insertStatement.setObject(15, rs.getLongNullable("completed_at").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  def migrateAllTables(source: Connection, destination: Connection): Unit = {
    checkVersions(source, destination, "payments", 4, 6)
    migrateReceivedPaymentsTable(source, destination)
    migrateSentPaymentsTable(source, destination)
  }

}
