package fr.acinq.eclair.db

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.db.pg.PgUtils.{JdbcUrlChanged, migrateTable, using}
import fr.acinq.eclair.db.pg.PgUtils.PgLock.{LockFailure, LockFailureHandler}
import fr.acinq.eclair.{TestKitBaseClass, TestUtils}
import grizzled.slf4j.Logging
import org.postgresql.PGConnection
import org.postgresql.jdbc.PgConnection
import grizzled.slf4j.{Logger, Logging}
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuiteLike
import fr.acinq.eclair.db.pg.PgUtils.ExtendedResultSet._

import java.io.File
import java.util.UUID

class PgUtilsSpec extends TestKitBaseClass with AnyFunSuiteLike with Eventually {

  test("database lock") {
    val pg = EmbeddedPostgres.start()
    val config = PgUtilsSpec.testConfig(pg.getPort)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    val instanceId1 = UUID.randomUUID()
    // this will lock the database for this instance id
    val db = Databases.postgres(config, instanceId1, datadir, LockFailureHandler.logAndThrow)

    assert(
      intercept[LockFailureHandler.LockException] {
        // this will fail because the database is already locked for a different instance id
        Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
      }.lockFailure === LockFailure.AlreadyLocked(instanceId1))

    // we can renew the lease at will
    db.obtainExclusiveLock()

    // we wait significantly longer than the lease interval, and make sure that the lock is still there
    Thread.sleep(10_000)
    assert(
      intercept[LockFailureHandler.LockException] {
        // this will fail because the database is already locked for a different instance id
        Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
      }.lockFailure === LockFailure.AlreadyLocked(instanceId1))

    // we close the first connection
    db.dataSource.close()
    eventually(assert(db.dataSource.isClosed))
    // we wait just a bit longer than the lease interval
    Thread.sleep(6_000)

    // now we can put a lock with a different instance id
    val instanceId2 = UUID.randomUUID()
    Databases.postgres(config, instanceId2, datadir, LockFailureHandler.logAndThrow)

    // we close the second connection
    db.dataSource.close()
    eventually(assert(db.dataSource.isClosed))

    // but we don't wait for the previous lease to expire, so we can't take over right now
    assert(intercept[LockFailureHandler.LockException] {
      // this will fail because even if we have acquired the table lock, the previous lease still hasn't expired
      Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
    }.lockFailure === LockFailure.AlreadyLocked(instanceId2))

    pg.close()
  }

  test("jdbc url check") {
    val pg = EmbeddedPostgres.start()
    val config = PgUtilsSpec.testConfig(pg.getPort)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    // this will lock the database for this instance id
    val db = Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)

    // we close the first connection
    db.dataSource.close()
    eventually(assert(db.dataSource.isClosed))

    // here we change the config to simulate an involuntary change in the server we connect to
    val config1 = ConfigFactory.parseString("postgres.port=1234").withFallback(config)
    intercept[JdbcUrlChanged] {
      Databases.postgres(config1, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
    }

    pg.close()
  }

  test("grant rights to read-only user") {
    val pg = EmbeddedPostgres.start()
    pg.getPostgresDatabase.getConnection.asInstanceOf[PgConnection].execSQLUpdate("CREATE ROLE readonly NOLOGIN")
    val config = ConfigFactory.parseString("postgres.readonly-user = readonly")
      .withFallback(PgUtilsSpec.testConfig(pg.getPort))
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
  }

  test("migration test") {
    val pg = EmbeddedPostgres.start()
    using(pg.getPostgresDatabase.getConnection.createStatement()) { statement =>
      statement.executeUpdate("CREATE TABLE foo (bar INTEGER)")
    }

    def doMigrate() = {
      migrateTable(
        source = pg.getPostgresDatabase.getConnection,
        destination = pg.getPostgresDatabase.getConnection,
        sourceTable = "foo",
        migrateSql = "UPDATE foo SET bar=? WHERE bar=?",
        (rs, statement) => {
          statement.setInt(1, rs.getInt("bar") * 10)
          statement.setInt(2, rs.getInt("bar"))
        }
      )(Logger(classOf[PgUtilsSpec]))
    }

    // empty migration
    doMigrate()

    using(pg.getPostgresDatabase.getConnection.createStatement()) { statement =>
      statement.executeUpdate("INSERT INTO foo VALUES (1)")
      statement.executeUpdate("INSERT INTO foo VALUES (2)")
      statement.executeUpdate("INSERT INTO foo VALUES (3)")
    }

    // non-empty migration
    doMigrate()

    using(pg.getPostgresDatabase.getConnection.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT bar FROM foo")
      assert(rs.map(_.getInt("bar")).toSet === Set(10, 20, 30))
    }

  }

}

object PgUtilsSpec extends Logging {

  def testConfig(port: Int): Config = ConfigFactory.parseString(
    s"""
       |postgres {
       |  database = ""
       |  host = "localhost"
       |  port = $port
       |  username = "postgres"
       |  password = ""
       |  readonly-user = ""
       |  reset-json-columns = false
       |  pool {
       |    max-size = 10 // recommended value = number_of_cpu_cores * 2
       |    connection-timeout = 30 seconds
       |    idle-timeout = 10 minutes
       |    max-life-time = 30 minutes
       |  }
       |  lock-type = "lease" // lease or none (do not use none in production)
       |  lease {
       |    interval = 5 seconds // lease-interval must be greater than lease-renew-interval
       |    renew-interval = 2 seconds
       |    lock-timeout = 5 seconds // timeout for the lock statement on the lease table
       |  }
       |}
       |""".stripMargin
  )

}
