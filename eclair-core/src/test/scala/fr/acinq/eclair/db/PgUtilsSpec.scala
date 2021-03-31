package fr.acinq.eclair.db

import akka.actor.ActorSystem
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.TestUtils
import fr.acinq.eclair.db.pg.PgUtils.JdbcUrlChanged
import fr.acinq.eclair.db.pg.PgUtils.PgLock.{LockFailure, LockFailureHandler}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.util.UUID

class PgUtilsSpec extends AnyFunSuite {

  test("database lock") {
    implicit val system: ActorSystem = ActorSystem()
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
    while (!db.dataSource.isClosed) {
      Thread.sleep(1000)
    }
    // we wait just a bit longer than the lease interval
    Thread.sleep(6_000)

    // now we can put a lock with a different instance id
    val instanceId2 = UUID.randomUUID()
    Databases.postgres(config, instanceId2, datadir, LockFailureHandler.logAndThrow)

    // we close the second connection
    db.dataSource.close()
    while (!db.dataSource.isClosed) {
      Thread.sleep(1000)
    }

    // but we don't wait for the previous lease to expire, so we can't take over right now
    assert(intercept[LockFailureHandler.LockException] {
      // this will fail because even if we have acquired the table lock, the previous lease still hasn't expired
      Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
    }.lockFailure === LockFailure.AlreadyLocked(instanceId2))

    pg.close()
  }

  test("jdbc url check") {
    implicit val system: ActorSystem = ActorSystem()
    val pg = EmbeddedPostgres.start()
    val config = PgUtilsSpec.testConfig(pg.getPort)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    // this will lock the database for this instance id
    val db = Databases.postgres(config, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)

    // we close the first connection
    db.dataSource.close()
    while (!db.dataSource.isClosed) {
      Thread.sleep(1000)
    }

    // here we change the config to simulate an involuntary change in the server we connect to
    val config1 = ConfigFactory.parseString("postgres.port=1234").withFallback(config)
    intercept[JdbcUrlChanged] {
      Databases.postgres(config1, UUID.randomUUID(), datadir, LockFailureHandler.logAndThrow)
    }

    pg.close()
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
       |  pool {
       |    max-size = 10 // recommended value = number_of_cpu_cores * 2
       |    connection-timeout = 30 seconds
       |    idle-timeout = 10 minutes
       |    max-life-time = 30 minutes
       |  }
       |  lease {
       |    interval = 5 seconds // lease-interval must be greater than lease-renew-interval
       |    renew-interval = 2 seconds
       |  }
       |  lock-type = "lease" // lease or none
       |}
       |""".stripMargin
  )

}
