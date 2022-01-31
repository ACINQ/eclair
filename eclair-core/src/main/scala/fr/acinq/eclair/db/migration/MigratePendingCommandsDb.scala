package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.migration.MigrateDb.{checkVersions, migrateTable}

import java.sql.{Connection, PreparedStatement, ResultSet}

object MigratePendingCommandsDb {

  private def migratePendingSettlementCommandsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "pending_settlement_commands"
    val insertSql = "INSERT INTO local.pending_settlement_commands (channel_id, htlc_id, data) VALUES (?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector("channel_id").toHex)
      insertStatement.setLong(2, rs.getLong("htlc_id"))
      insertStatement.setBytes(3, rs.getBytes("data"))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  def migrateAllTables(source: Connection, destination: Connection): Unit = {
    checkVersions(source, destination, "pending_relay", 2, 3)
    migratePendingSettlementCommandsTable(source, destination)
  }

}
