package fr.acinq.eclair

import akka.actor.ActorSystem
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import fr.acinq.eclair.db.pg.PgUtils
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.db.sqlite.SqliteUtils
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.PgLock.LockFailureHandler

import java.io.File
import java.sql.{Connection, DriverManager, Statement}
import java.util.UUID
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
  override def pendingRelay: PendingRelayDb = db.pendingRelay
  def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int
  def close(): Unit
  // @formatter:on
}

object TestDatabases {

  def sqliteInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

  def inMemoryDb(connection: Connection = sqliteInMemory()): Databases = Databases.SqliteDatabases(connection, connection, connection)

  case class TestSqliteDatabases() extends TestDatabases {
    // @formatter:off
    override val connection: Connection = sqliteInMemory()
    override lazy val db: Databases = Databases.SqliteDatabases(connection, connection, connection)
    override def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = SqliteUtils.getVersion(statement, db_name, currentVersion)
    override def close(): Unit = ()
    // @formatter:on
  }

  case class TestPgDatabases() extends TestDatabases {
    private val pg = EmbeddedPostgres.start()

    import com.zaxxer.hikari.HikariConfig

    val hikariConfig = new HikariConfig
    hikariConfig.setDataSource(pg.getPostgresDatabase)

    val lock: PgLock.LeaseLock = PgLock.LeaseLock(UUID.randomUUID(), 10 minutes, 8 minute, LockFailureHandler.logAndThrow)

    val jdbcUrlFile: File = new File(sys.props("tmp.dir"), s"jdbcUrlFile_${UUID.randomUUID()}.tmp")
    jdbcUrlFile.deleteOnExit()

    implicit val system: ActorSystem = ActorSystem()

    // @formatter:off
    override val connection: Connection = pg.getPostgresDatabase.getConnection
    override lazy val db: Databases = Databases.PostgresDatabases(hikariConfig, UUID.randomUUID(), lock, jdbcUrlFile_opt = Some(jdbcUrlFile))
    override def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = PgUtils.getVersion(statement, db_name, currentVersion)
    override def close(): Unit = pg.close()
    // @formatter:on
  }

  def forAllDbs(f: TestDatabases => Unit): Unit = {
    def using(dbs: TestDatabases)(g: TestDatabases => Unit): Unit = try g(dbs) finally dbs.close()
    // @formatter:off
    using(TestSqliteDatabases())(f)
    using(TestPgDatabases())(f)
    // @fodmatter:on
  }

}
