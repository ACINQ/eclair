package fr.acinq.eclair

import akka.actor.ActorSystem
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import com.zaxxer.hikari.HikariConfig
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.PgLock.LockFailureHandler
import fr.acinq.eclair.db.pg.PgUtils.{PgLock, getVersion, using}
import org.postgresql.jdbc.PgConnection
import org.scalatest.Assertions.convertToEqualizer
import org.sqlite.SQLiteConnection

import java.io.File
import java.sql.{Connection, DriverManager}
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration._


/**
 * Extends the regular [[fr.acinq.eclair.db.Databases]] trait with test-specific methods
 */
sealed trait TestDatabases extends Databases {
  // @formatter:off
  val connection: Connection
  val db: Databases
  override def network: NetworkDb = db.network
  override def audit: AuditDb = db.audit
  override def channels: ChannelsDb = db.channels
  override def peers: PeersDb = db.peers
  override def payments: PaymentsDb = db.payments
  override def pendingCommands: PendingCommandsDb = db.pendingCommands
  def close(): Unit
  // @formatter:on
}

object TestDatabases {

  def sqliteInMemory(): SQLiteConnection = DriverManager.getConnection("jdbc:sqlite::memory:").asInstanceOf[SQLiteConnection]

  def inMemoryDb(): Databases = {
    val connection = sqliteInMemory()
    Databases.SqliteDatabases(connection, connection, connection)
  }

  case class TestSqliteDatabases() extends TestDatabases {
    // @formatter:off
    override val connection: SQLiteConnection = sqliteInMemory()
    override lazy val db: Databases = Databases.SqliteDatabases(connection, connection, connection)
    override def close(): Unit = ()
    // @formatter:on
  }

  case class TestPgDatabases() extends TestDatabases {
    private val pg = EmbeddedPostgres.start()
    val datasource: DataSource = pg.getPostgresDatabase
    val hikariConfig = new HikariConfig
    hikariConfig.setDataSource(datasource)
    val lock: PgLock.LeaseLock = PgLock.LeaseLock(UUID.randomUUID(), 10 minutes, 8 minute, LockFailureHandler.logAndThrow)

    val jdbcUrlFile: File = new File(TestUtils.BUILD_DIRECTORY, s"jdbcUrlFile_${UUID.randomUUID()}.tmp")
    jdbcUrlFile.deleteOnExit()

    implicit val system: ActorSystem = ActorSystem()

    // @formatter:off
    override val connection: PgConnection = pg.getPostgresDatabase.getConnection.asInstanceOf[PgConnection]
    // NB: we use a lazy val here: databases won't be initialized until we reference that variable
    override lazy val db: Databases = Databases.PostgresDatabases(hikariConfig, UUID.randomUUID(), lock, jdbcUrlFile_opt = Some(jdbcUrlFile), readOnlyUser_opt = None, resetJsonColumns = false)
    override def close(): Unit = pg.close()
    // @formatter:on
  }

  def forAllDbs(f: TestDatabases => Unit): Unit = {
    def using(dbs: TestDatabases)(g: TestDatabases => Unit): Unit = try g(dbs) finally dbs.close()
    // @formatter:off
    using(TestSqliteDatabases())(f)
    using(TestPgDatabases())(f)
    // @formatter:on
  }

  def migrationCheck(dbs: TestDatabases,
                     initializeTables: Connection => Unit,
                     dbName: String,
                     targetVersion: Int,
                     postCheck: Connection => Unit
                    ): Unit = {
    val connection = dbs.connection
    // initialize the database to a previous version and populate data
    initializeTables(connection)
    // this will trigger the initialization of tables and the migration
    val _ = dbs.db
    // check that db version was updated
    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, dbName).contains(targetVersion), "unexpected version post-migration")
    }
    // post-migration checks
    postCheck(connection)
  }

}
