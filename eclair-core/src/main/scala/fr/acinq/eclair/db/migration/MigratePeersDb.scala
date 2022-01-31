package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.migration.MigrateDb.{checkVersions, migrateTable}

import java.sql.{Connection, PreparedStatement, ResultSet}

object MigratePeersDb {

  private def migratePeersTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "peers"
    val insertSql = "INSERT INTO local.peers (node_id, data) VALUES (?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector("node_id").toHex)
      insertStatement.setBytes(2, rs.getBytes("data"))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateRelayFeesTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "relay_fees"
    val insertSql = "INSERT INTO local.relay_fees (node_id, fee_base_msat, fee_proportional_millionths) VALUES (?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector("node_id").toHex)
      insertStatement.setLong(2, rs.getLong("fee_base_msat"))
      insertStatement.setLong(3, rs.getLong("fee_proportional_millionths"))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  def migrateAllTables(source: Connection, destination: Connection): Unit = {
    checkVersions(source, destination, "peers", 2, 3)
    migratePeersTable(source, destination)
    migrateRelayFeesTable(source, destination)
  }

}
