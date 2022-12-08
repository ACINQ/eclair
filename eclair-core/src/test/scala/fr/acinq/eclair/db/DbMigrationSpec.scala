package fr.acinq.eclair.db

import akka.actor.ActorSystem
import com.zaxxer.hikari.HikariConfig
import fr.acinq.eclair.db.Databases.{PostgresDatabases, SqliteDatabases}
import fr.acinq.eclair.db.migration._
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.db.pg._
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.scalatest.Ignore
import org.scalatest.funsuite.AnyFunSuite
import org.sqlite.SQLiteConfig

import java.io.File
import java.sql.{Connection, DriverManager}
import java.util.UUID
import javax.sql.DataSource

/**
 * To run this test, create a `migration` directory in your project's `user.dir`
 * and copy your sqlite files to it (eclair.sqlite, network.sqlite, audit.sqlite).
 * Then remove the `Ignore` annotation and run the test.
 */
@Ignore
class DbMigrationSpec extends AnyFunSuite {

  import DbMigrationSpec._

  test("eclair migration test") {
    val sqlite = loadSqlite("migration\\eclair.sqlite")
    val postgresDatasource = EmbeddedPostgres.start().getPostgresDatabase

    new PgChannelsDb()(postgresDatasource, PgLock.NoLock)
    new PgPendingCommandsDb()(postgresDatasource, PgLock.NoLock)
    new PgPeersDb()(postgresDatasource, PgLock.NoLock)
    new PgPaymentsDb()(postgresDatasource, PgLock.NoLock)

    PgUtils.inTransaction { postgres =>
      MigrateChannelsDb.migrateAllTables(sqlite, postgres)
      MigratePendingCommandsDb.migrateAllTables(sqlite, postgres)
      MigratePeersDb.migrateAllTables(sqlite, postgres)
      MigratePaymentsDb.migrateAllTables(sqlite, postgres)
      assert(CompareChannelsDb.compareAllTables(sqlite, postgres))
      assert(ComparePendingCommandsDb.compareAllTables(sqlite, postgres))
      assert(ComparePeersDb.compareAllTables(sqlite, postgres))
      assert(ComparePaymentsDb.compareAllTables(sqlite, postgres))
    }(postgresDatasource)

    sqlite.close()
  }

  test("network migration test") {
    val sqlite = loadSqlite("migration\\network.sqlite")
    val postgresDatasource = EmbeddedPostgres.start().getPostgresDatabase

    new PgNetworkDb()(postgresDatasource)

    PgUtils.inTransaction { postgres =>
      MigrateNetworkDb.migrateAllTables(sqlite, postgres)
      assert(CompareNetworkDb.compareAllTables(sqlite, postgres))
    }(postgresDatasource)

    sqlite.close()
  }

  test("audit migration test") {
    val sqlite = loadSqlite("migration\\audit.sqlite")
    val postgresDatasource = EmbeddedPostgres.start().getPostgresDatabase

    new PgAuditDb()(postgresDatasource)

    PgUtils.inTransaction { postgres =>
      MigrateAuditDb.migrateAllTables(sqlite, postgres)
      assert(CompareAuditDb.compareAllTables(sqlite, postgres))
    }(postgresDatasource)

    sqlite.close()
  }

  test("full migration") {
    // we need to open in read/write because of the getVersion call
    val sqlite = SqliteDatabases(
      auditJdbc = loadSqlite("migration\\audit.sqlite", readOnly = false),
      eclairJdbc = loadSqlite("migration\\eclair.sqlite", readOnly = false),
      networkJdbc = loadSqlite("migration\\network.sqlite", readOnly = false),
      jdbcUrlFile_opt = None
    )
    val postgres = {
      val pg = EmbeddedPostgres.start()
      val datasource: DataSource = pg.getPostgresDatabase
      val hikariConfig = new HikariConfig
      hikariConfig.setDataSource(datasource)
      PostgresDatabases(
        hikariConfig = hikariConfig,
        instanceId = UUID.randomUUID(),
        lock = PgLock.NoLock,
        jdbcUrlFile_opt = None,
        readOnlyUser_opt = None,
        resetJsonColumns = false,
        safetyChecks_opt = None
      )(ActorSystem())
    }
    val dualDb = DualDatabases(sqlite, postgres)
    MigrateDb.migrateAll(dualDb)
    CompareDb.compareAll(dualDb)
  }

}

object DbMigrationSpec {
  def loadSqlite(path: String, readOnly: Boolean = true): Connection = {
    val sqliteConfig = new SQLiteConfig()
    sqliteConfig.setReadOnly(readOnly)
    val dbFile = new File(path)
    DriverManager.getConnection(s"jdbc:sqlite:$dbFile", sqliteConfig.toProperties)
  }
}
