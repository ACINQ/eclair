package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.DualDatabases
import fr.acinq.eclair.db.jdbc.JdbcUtils
import fr.acinq.eclair.db.pg.PgUtils
import grizzled.slf4j.Logging

import java.sql.{Connection, PreparedStatement, ResultSet}

object MigrateDb extends Logging {

  private def getVersion(conn: Connection,
                         dbName: String): Int = {
    val statement = conn.prepareStatement(s"SELECT version FROM versions WHERE db_name='$dbName'")
    val res = statement.executeQuery()
    res.next()
    res.getInt("version")
  }

  def checkVersions(source: Connection,
                    destination: Connection,
                    dbName: String,
                    expectedSourceVersion: Int,
                    expectedDestinationVersion: Int): Unit = {
    val actualSourceVersion = getVersion(source, dbName)
    val actualDestinationVersion = getVersion(destination, dbName)
    require(actualSourceVersion == expectedSourceVersion, s"unexpected version for source db=$dbName expected=$expectedSourceVersion actual=$actualSourceVersion")
    require(actualDestinationVersion == expectedDestinationVersion, s"unexpected version for destination db=$dbName expected=$expectedDestinationVersion actual=$actualDestinationVersion")
  }

  def migrateTable(source: Connection,
                   destination: Connection,
                   sourceTable: String,
                   insertSql: String,
                   migrate: (ResultSet, PreparedStatement) => Unit): Int =
    JdbcUtils.migrateTable(source, destination, sourceTable, insertSql, migrate)(logger)

  def migrateAll(dualDatabases: DualDatabases): Unit = {
    logger.info("migrating all tables...")
    val (sqliteDb, postgresDb) = DualDatabases.getDatabases(dualDatabases)
    PgUtils.inTransaction { postgres =>
      // eclair.sqlite
      val eclair_sqlite = sqliteDb.channels.sqlite
      MigrateChannelsDb.migrateAllTables(eclair_sqlite, postgres)
      MigratePendingCommandsDb.migrateAllTables(eclair_sqlite, postgres)
      MigratePeersDb.migrateAllTables(eclair_sqlite, postgres)
      MigratePaymentsDb.migrateAllTables(eclair_sqlite, postgres)
      // network.sqlite
      val network_sqlite = sqliteDb.network.sqlite
      MigrateNetworkDb.migrateAllTables(network_sqlite, postgres)
      // audit.sqlite
      val audit_sqlite = sqliteDb.audit.sqlite
      MigrateAuditDb.migrateAllTables(audit_sqlite, postgres)
      logger.info("migration complete")
    }(postgresDb.dataSource)
  }

}
