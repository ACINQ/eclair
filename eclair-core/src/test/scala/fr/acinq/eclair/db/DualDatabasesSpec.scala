package fr.acinq.eclair.db

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{TestKitBaseClass, TestUtils}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.scalatest.funsuite.AnyFunSuiteLike

import java.io.File
import java.util.UUID

class DualDatabasesSpec extends TestKitBaseClass with AnyFunSuiteLike {

  def fixture(driver: String): DualDatabases = {
    val pg = EmbeddedPostgres.start()
    val config = DualDatabasesSpec.testConfig(pg.getPort, driver)
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"pg_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    val instanceId = UUID.randomUUID()
    Databases.init(config, instanceId, datadir).asInstanceOf[DualDatabases]
  }

  test("sqlite primary") {
    val db = fixture("dual-sqlite-primary")

    db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)
    assert(db.primary.channels.listLocalChannels().nonEmpty)
    awaitCond(db.primary.channels.listLocalChannels() == db.secondary.channels.listLocalChannels())
  }

  test("postgres primary") {
    val db = fixture("dual-postgres-primary")

    db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)
    assert(db.primary.channels.listLocalChannels().nonEmpty)
    awaitCond(db.primary.channels.listLocalChannels() == db.secondary.channels.listLocalChannels())
  }
}

object DualDatabasesSpec {
  def testConfig(port: Int, driver: String): Config =
    ConfigFactory.parseString(
    s"""
       |driver = $driver
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
       |dual {
       |  migrate-on-restart = false // migrate sqlite -> postgres on restart (only applies if sqlite is primary)
       |  compare-on-restart = false // compare sqlite and postgres dbs on restart
       |}
       |""".stripMargin)
}
