package fr.acinq.eclair.db

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.db.Databases.JdbcUrlChanged
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.pg.PgUtils.ExtendedResultSet._
import fr.acinq.eclair.db.pg.PgUtils.PgLock.{LeaseLock, LockFailure, LockFailureHandler}
import fr.acinq.eclair.db.pg.PgUtils.{migrateTable, using}
import fr.acinq.eclair.payment.ChannelPaymentRelayed
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.Color
import fr.acinq.eclair.{Features, MilliSatoshiLong, TestKitBaseClass, TestUtils, TimestampMilli, TimestampSecond, randomBytes32, randomKey}
import grizzled.slf4j.{Logger, Logging}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.jdbc.PgConnection
import org.postgresql.util.PGInterval
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuiteLike

import java.io.File
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class PgUtilsSpec extends TestKitBaseClass with AnyFunSuiteLike with Eventually {

  test("database lock") {
    val pg = EmbeddedPostgres.start()
    val config = PgUtilsSpec.testConfig(pg.getPort)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    val instanceId1 = UUID.randomUUID()
    // this will lock the database for this instance id
    val db1 = Databases.postgres(config, instanceId1, datadir, None, LockFailureHandler.logAndThrow)

    assert(
      intercept[LockFailureHandler.LockException] {
        // this will fail because the database is already locked for a different instance id
        Databases.postgres(config, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
      }.lockFailure == LockFailure.AlreadyLocked(instanceId1))

    // we can renew the lease at will
    db1.obtainExclusiveLock()

    // we wait significantly longer than the lease interval, and make sure that the lock is still there
    Thread.sleep(10_000)
    assert(
      intercept[LockFailureHandler.LockException] {
        // this will fail because the database is already locked for a different instance id
        Databases.postgres(config, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
      }.lockFailure == LockFailure.AlreadyLocked(instanceId1))

    // we close the first connection
    db1.dataSource.close()
    eventually(assert(db1.dataSource.isClosed))
    // we wait just a bit longer than the lease interval
    Thread.sleep(6_000)

    // now we can put a lock with a different instance id
    val instanceId2 = UUID.randomUUID()
    val db2 = Databases.postgres(config, instanceId2, datadir, None, LockFailureHandler.logAndThrow)

    // we close the second connection
    db2.dataSource.close()
    eventually(assert(db2.dataSource.isClosed))

    // but we don't wait for the previous lease to expire, so we can't take over right now
    assert(intercept[LockFailureHandler.LockException] {
      // this will fail because even if we have acquired the table lock, the previous lease still hasn't expired
      Databases.postgres(config, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
    }.lockFailure == LockFailure.AlreadyLocked(instanceId2))

    pg.close()
  }

  test("withLock utility method") {
    val pg = EmbeddedPostgres.start()
    val config = PgUtilsSpec.testConfig(pg.getPort)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    val instanceId1 = UUID.randomUUID()
    // this will lock the database for this instance id
    val db = Databases.postgres(config, instanceId1, datadir, None, LockFailureHandler.logAndThrow)
    implicit val ds: DataSource = db.dataSource

    // dummy query works
    db.lock.withLock { conn =>
      conn.createStatement().executeQuery("SELECT 1")
    }

    intercept[LockFailureHandler.LockException] {
      db.lock.withLock { conn =>
        // we start with a dummy query
        conn.createStatement().executeQuery("SELECT 1")
        // but before we complete the query, a separate connection takes the lock
        using(pg.getPostgresDatabase.getConnection.prepareStatement(s"UPDATE lease SET expires_at = now() + ?, instance = ? WHERE id = 1")) {
          statement =>
            statement.setObject(1, new PGInterval("60 seconds"))
            statement.setString(2, UUID.randomUUID().toString)
            statement.executeUpdate()
        }
      }
    }

    pg.close()
  }

  test("lock release utility method") {
    val pg = EmbeddedPostgres.start()
    val ds: DataSource = pg.getPostgresDatabase

    val lock1 = LeaseLock(UUID.randomUUID(), 2 minutes, 1 minute, LockFailureHandler.logAndThrow, autoReleaseAtShutdown = false)
    val lock2 = LeaseLock(UUID.randomUUID(), 2 minutes, 1 minute, LockFailureHandler.logAndThrow, autoReleaseAtShutdown = false)

    lock1.obtainExclusiveLock(ds)
    intercept[LockFailureHandler.LockException] {
      lock2.obtainExclusiveLock(ds)
    }

    lock1.releaseExclusiveLock(ds)
    Thread.sleep(5)
    lock2.obtainExclusiveLock(ds)
    intercept[LockFailureHandler.LockException] {
      lock1.obtainExclusiveLock(ds)
    }

    pg.close()
  }

  test("jdbc url check") {
    val pg = EmbeddedPostgres.start()
    val config = PgUtilsSpec.testConfig(pg.getPort)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    val jdbcUrlPath = new File(datadir, "last_jdbcurl")
    // this will lock the database for this instance id
    val db = Databases.postgres(config, UUID.randomUUID(), datadir, Some(jdbcUrlPath), LockFailureHandler.logAndThrow)

    // we close the first connection
    db.dataSource.close()
    eventually(assert(db.dataSource.isClosed))

    // here we change the config to simulate an involuntary change in the server we connect to
    val config1 = ConfigFactory.parseString("postgres.port=1234").withFallback(config)
    intercept[JdbcUrlChanged] {
      Databases.postgres(config1, UUID.randomUUID(), datadir, Some(jdbcUrlPath), LockFailureHandler.logAndThrow)
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
    Databases.postgres(config, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
  }

  test("safety checks") {
    val pg = EmbeddedPostgres.start()
    val baseConfig = ConfigFactory.parseString("postgres.lock-type=none").withFallback(PgUtilsSpec.testConfig(pg.getPort))
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()

    {
      val db = Databases.postgres(baseConfig, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
      db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)
      db.channels.updateChannelMeta(ChannelCodecsSpec.normal.channelId, ChannelEvent.EventType.Created)
      db.network.addNode(Announcements.makeNodeAnnouncement(randomKey(), "node-A", Color(50, 99, -80), Nil, Features.empty, TimestampSecond.now() - 45.days))
      db.network.addNode(Announcements.makeNodeAnnouncement(randomKey(), "node-B", Color(50, 99, -80), Nil, Features.empty, TimestampSecond.now() - 3.days))
      db.network.addNode(Announcements.makeNodeAnnouncement(randomKey(), "node-C", Color(50, 99, -80), Nil, Features.empty, TimestampSecond.now() - 7.minutes))
      db.audit.add(ChannelPaymentRelayed(421 msat, 400 msat, randomBytes32(), randomBytes32(), randomBytes32(), TimestampMilli.now() - 3.seconds))
      db.dataSource.close()
    }

    {
      val safetyConfig = ConfigFactory.parseString(
        s"""
           |postgres {
           |  safety-checks {
           |    // a set of basic checks on data to make sure we use the correct database
           |    enabled = true
           |    max-age {
           |      local-channels = 3 minutes
           |      network-nodes = 30 minutes
           |      audit-relayed = 10 minutes
           |    }
           |    min-count {
           |      local-channels = 1
           |      network-nodes = 2
           |      network-channels = 0
           |    }
           |  }
           |}""".stripMargin)
      val config = safetyConfig.withFallback(baseConfig)
      val db = Databases.postgres(config, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
      db.dataSource.close()
    }

    {
      val safetyConfig = ConfigFactory.parseString(
        s"""
           |postgres {
           |  safety-checks {
           |    // a set of basic checks on data to make sure we use the correct database
           |    enabled = true
           |    max-age {
           |      local-channels = 3 minutes
           |      network-nodes = 30 minutes
           |      audit-relayed = 10 minutes
           |    }
           |    min-count {
           |      local-channels = 10
           |      network-nodes = 2
           |      network-channels = 0
           |    }
           |  }
           |}""".stripMargin)
      val config = safetyConfig.withFallback(baseConfig)
      intercept[IllegalArgumentException] {
        Databases.postgres(config, UUID.randomUUID(), datadir, None, LockFailureHandler.logAndThrow)
      }
    }

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
      assert(rs.map(_.getInt("bar")).toSet == Set(10, 20, 30))
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
       |    auto-release-at-shutdown = false // automatically release the lock when eclair is stopping
       |  }
       |  safety-checks {
       |    // a set of basic checks on data to make sure we use the correct database
       |    enabled = false
       |    max-age {
       |      local-channels = 3 minutes
       |      network-nodes = 30 minutes
       |      audit-relayed = 10 minutes
       |    }
       |    min-count {
       |      local-channels = 10
       |      network-nodes = 3000
       |      network-channels = 20000
       |    }
       |  }
       |}
       |""".stripMargin
  )

}
