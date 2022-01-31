/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.db

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.db.migration.{CompareDb, MigrateDb}
import fr.acinq.eclair.db.pg.PgUtils.PgLock.LockFailureHandler
import fr.acinq.eclair.db.pg.PgUtils._
import fr.acinq.eclair.db.pg._
import fr.acinq.eclair.db.sqlite._
import grizzled.slf4j.Logging

import java.io.File
import java.nio.file._
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration._

trait Databases {
  //@formatter:off
  def network: NetworkDb
  def audit: AuditDb
  def channels: ChannelsDb
  def peers: PeersDb
  def payments: PaymentsDb
  def pendingCommands: PendingCommandsDb
  //@formatter:on
}

object Databases extends Logging {

  trait FileBackup {
    this: Databases =>
    def backup(backupFile: File): Unit
  }

  trait ExclusiveLock {
    this: Databases =>
    def obtainExclusiveLock(): Unit
  }

  case class SqliteDatabases private(network: SqliteNetworkDb,
                                     audit: SqliteAuditDb,
                                     channels: SqliteChannelsDb,
                                     peers: SqlitePeersDb,
                                     payments: SqlitePaymentsDb,
                                     pendingCommands: SqlitePendingCommandsDb,
                                     private val backupConnection: Connection) extends Databases with FileBackup {
    override def backup(backupFile: File): Unit = SqliteUtils.using(backupConnection.createStatement()) {
      statement => {
        statement.executeUpdate(s"backup to ${backupFile.getAbsolutePath}")
      }
    }
  }

  object SqliteDatabases {
    def apply(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection, jdbcUrlFile_opt: Option[File]): SqliteDatabases = {
      jdbcUrlFile_opt.foreach(checkIfDatabaseUrlIsUnchanged("sqlite", _))
      SqliteDatabases(
        network = new SqliteNetworkDb(networkJdbc),
        audit = new SqliteAuditDb(auditJdbc),
        channels = new SqliteChannelsDb(eclairJdbc),
        peers = new SqlitePeersDb(eclairJdbc),
        payments = new SqlitePaymentsDb(eclairJdbc),
        pendingCommands = new SqlitePendingCommandsDb(eclairJdbc),
        backupConnection = eclairJdbc
      )
    }
  }

  case class PostgresDatabases private(network: PgNetworkDb,
                                       audit: PgAuditDb,
                                       channels: PgChannelsDb,
                                       peers: PgPeersDb,
                                       payments: PgPaymentsDb,
                                       pendingCommands: PgPendingCommandsDb,
                                       dataSource: HikariDataSource,
                                       lock: PgLock) extends Databases with ExclusiveLock {
    override def obtainExclusiveLock(): Unit = lock.obtainExclusiveLock(dataSource)
  }

  object PostgresDatabases {

    case class SafetyChecks(localChannelsMaxAge: FiniteDuration,
                            networkNodesMaxAge: FiniteDuration,
                            auditRelayedMaxAge: FiniteDuration,
                            localChannelsMinCount: Int,
                            networkNodesMinCount: Int,
                            networkChannelsMinCount: Int
                           )

    def apply(hikariConfig: HikariConfig,
              instanceId: UUID,
              lock: PgLock = PgLock.NoLock,
              jdbcUrlFile_opt: Option[File],
              readOnlyUser_opt: Option[String],
              resetJsonColumns: Boolean,
              safetyChecks_opt: Option[SafetyChecks])(implicit system: ActorSystem): PostgresDatabases = {

      jdbcUrlFile_opt.foreach(jdbcUrlFile => checkIfDatabaseUrlIsUnchanged(hikariConfig.getJdbcUrl, jdbcUrlFile))

      implicit val ds: HikariDataSource = new HikariDataSource(hikariConfig)
      implicit val implicitLock: PgLock = lock

      lock match {
        case PgLock.NoLock => ()
        case l: PgLock.LeaseLock =>
          // we obtain a lock right now...
          l.obtainExclusiveLock(ds)
          // ...and renew the lease regularly
          import system.dispatcher
          val leaseLockTask = system.scheduler.scheduleWithFixedDelay(l.leaseRenewInterval, l.leaseRenewInterval)(() => l.obtainExclusiveLock(ds))

          if (l.autoReleaseAtShutdown) {
            CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "release-postgres-lock") { () =>
              Future {
                logger.info("cancelling the pg lock renew task...")
                leaseLockTask.cancel()
                logger.info("releasing the curent pg lock...")
                l.releaseExclusiveLock(ds)
                Thread.sleep(3000)
                logger.info("closing the connection pool...")
                ds.close()
                Done
              }
            }
          }
      }

      val databases = PostgresDatabases(
        network = new PgNetworkDb,
        audit = new PgAuditDb,
        channels = new PgChannelsDb,
        peers = new PgPeersDb,
        payments = new PgPaymentsDb,
        pendingCommands = new PgPendingCommandsDb,
        dataSource = ds,
        lock = lock)

      readOnlyUser_opt.foreach { readOnlyUser =>
        PgUtils.inTransaction { connection =>
          using(connection.createStatement()) { statement =>
            val schemas = "public" :: "audit" :: "local" :: "network" :: "payments" :: Nil
            schemas.foreach { schema =>
              logger.info(s"granting read-only access to user=$readOnlyUser schema=$schema")
              statement.executeUpdate(s"GRANT USAGE ON SCHEMA $schema TO $readOnlyUser")
              statement.executeUpdate(s"GRANT SELECT ON ALL TABLES IN SCHEMA $schema TO $readOnlyUser")
            }
          }
        }
      }

      if (resetJsonColumns) {
        logger.warn("resetting json columns...")
        PgUtils.inTransaction { connection =>
          databases.channels.resetJsonColumns(connection)
          databases.network.resetJsonColumns(connection)
        }
      }

      safetyChecks_opt foreach { initChecks =>

        PgUtils.inTransaction { connection =>
          using(connection.createStatement()) { statement =>

            def checkMaxAge(name: String, maxAge: FiniteDuration, sqlQuery: String): Unit = {
              import ExtendedResultSet._
              val smallestAge_opt = statement
                .executeQuery(sqlQuery)
                .headOption // sql max() will always return a result, with perhaps a null value if there was no records
                .flatMap(_.getTimestampNullable("max"))
                .map(ts => TimestampMilli.now() - TimestampMilli.fromSqlTimestamp(ts))
              require(smallestAge_opt.isDefined, s"db check failed: no $name found")
              require(smallestAge_opt.get <= maxAge, s"db check failed: most recent $name is too old (${smallestAge_opt.get.toMinutes} minutes > ${maxAge.toMinutes} minutes)")
              logger.info(s"db check ok: max age ${smallestAge_opt.get.toMinutes} minutes <= ${maxAge.toMinutes} minutes for $name")
            }

            checkMaxAge(name = "local channel",
              maxAge = initChecks.localChannelsMaxAge,
              sqlQuery =
                """
                  |SELECT MAX(GREATEST(created_timestamp, last_payment_sent_timestamp, last_payment_received_timestamp, last_connected_timestamp, closed_timestamp))
                  |FROM local.channels
                  |WHERE NOT is_closed""".stripMargin)

            checkMaxAge(name = "network node",
              maxAge = initChecks.networkNodesMaxAge,
              sqlQuery =
                """
                  |SELECT MAX((json->'timestamp'->>'iso')::timestamptz)
                  |FROM network.nodes""".stripMargin)

            checkMaxAge(name = "audit relayed",
              maxAge = initChecks.auditRelayedMaxAge,
              sqlQuery =
                """
                  |SELECT MAX(timestamp)
                  |FROM audit.relayed""".stripMargin)

            def checkMinCount(name: String, minCount: Int, sqlQuery: String): Unit = {
              import ExtendedResultSet._
              val count = statement
                .executeQuery(sqlQuery)
                .map(_.getInt("count"))
                .head // NB: COUNT(*) always returns exactly one row
              require(count >= minCount, s"db check failed: min count not reached for $name ($count < $minCount)")
              logger.info(s"db check ok: min count $count > $minCount for $name")
            }

            checkMinCount(name = "local channels",
              minCount = initChecks.localChannelsMinCount,
              sqlQuery = "SELECT COUNT(*) FROM local.channels")

            checkMinCount(name = "network node",
              minCount = initChecks.networkNodesMinCount,
              sqlQuery = "SELECT COUNT(*) FROM network.nodes")

            checkMinCount(name = "network channels",
              minCount = initChecks.networkChannelsMinCount,
              sqlQuery = "SELECT COUNT(*) FROM network.public_channels")

          }
        }
      }

      databases
    }
  }

  /** We raise this exception when the jdbc url changes, to prevent using a different server involuntarily. */
  case class JdbcUrlChanged(before: String, after: String) extends RuntimeException(s"The database URL has changed since the last start. It was `$before`, now it's `$after`. If this was intended, make sure you have migrated your data, otherwise your channels will be force-closed and you may lose funds.")

  private def checkIfDatabaseUrlIsUnchanged(url: String, urlFile: File): Unit = {
    def readString(path: Path): String = Files.readAllLines(path).get(0)

    def writeString(path: Path, string: String): Unit = Files.write(path, java.util.Arrays.asList(string))

    if (urlFile.exists()) {
      val oldUrl = readString(urlFile.toPath)
      if (oldUrl != url)
        throw JdbcUrlChanged(oldUrl, url)
    } else {
      writeString(urlFile.toPath, url)
    }
  }

  def init(dbConfig: Config, instanceId: UUID, chaindir: File, db: Option[Databases] = None)(implicit system: ActorSystem): Databases = {
    db match {
      case Some(d) => d
      case None =>
        val jdbcUrlFile = new File(chaindir, "last_jdbcurl")
        dbConfig.getString("driver") match {
          case "sqlite" => Databases.sqlite(chaindir, jdbcUrlFile_opt = Some(jdbcUrlFile))
          case "postgres" => Databases.postgres(dbConfig, instanceId, chaindir, jdbcUrlFile_opt = Some(jdbcUrlFile))
          case dual@("dual-sqlite-primary" | "dual-postgres-primary") =>
            logger.info(s"using $dual database mode")
            val sqlite = Databases.sqlite(chaindir, jdbcUrlFile_opt = None)
            val postgres = Databases.postgres(dbConfig, instanceId, chaindir, jdbcUrlFile_opt = None)
            val (primary, secondary) = if (dual == "dual-sqlite-primary") (sqlite, postgres) else (postgres, sqlite)
            val dualDb = DualDatabases(primary, secondary)
            if (primary == sqlite) {
              if (dbConfig.getBoolean("dual.migrate-on-restart")) {
                MigrateDb.migrateAll(dualDb)
              }
              if (dbConfig.getBoolean("dual.compare-on-restart")) {
                CompareDb.compareAll(dualDb)
              }
            }
            dualDb
          case driver => throw new RuntimeException(s"unknown database driver `$driver`")
        }
    }
  }

  /**
   * Given a parent folder it creates or loads all the databases from a JDBC connection
   */
  def sqlite(dbdir: File, jdbcUrlFile_opt: Option[File]): SqliteDatabases = {
    dbdir.mkdirs()
    SqliteDatabases(
      eclairJdbc = SqliteUtils.openSqliteFile(dbdir, "eclair.sqlite", exclusiveLock = true, journalMode = "wal", syncFlag = "full"), // there should only be one process writing to this file
      networkJdbc = SqliteUtils.openSqliteFile(dbdir, "network.sqlite", exclusiveLock = false, journalMode = "wal", syncFlag = "normal"), // we don't need strong durability guarantees on the network db
      auditJdbc = SqliteUtils.openSqliteFile(dbdir, "audit.sqlite", exclusiveLock = false, journalMode = "wal", syncFlag = "full"),
      jdbcUrlFile_opt = jdbcUrlFile_opt
    )
  }

  def postgres(dbConfig: Config, instanceId: UUID, dbdir: File, jdbcUrlFile_opt: Option[File], lockExceptionHandler: LockFailureHandler = LockFailureHandler.logAndStop)(implicit system: ActorSystem): PostgresDatabases = {
    dbdir.mkdirs()
    val database = dbConfig.getString("postgres.database")
    val host = dbConfig.getString("postgres.host")
    val port = dbConfig.getInt("postgres.port")
    val username = if (dbConfig.getIsNull("postgres.username") || dbConfig.getString("postgres.username").isEmpty) None else Some(dbConfig.getString("postgres.username"))
    val password = if (dbConfig.getIsNull("postgres.password") || dbConfig.getString("postgres.password").isEmpty) None else Some(dbConfig.getString("postgres.password"))
    val readOnlyUser_opt = if (dbConfig.getIsNull("postgres.readonly-user") || dbConfig.getString("postgres.readonly-user").isEmpty) None else Some(dbConfig.getString("postgres.readonly-user"))
    val resetJsonColumns = dbConfig.getBoolean("postgres.reset-json-columns")

    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(s"jdbc:postgresql://$host:$port/$database")
    username.foreach(hikariConfig.setUsername)
    password.foreach(hikariConfig.setPassword)
    val poolConfig = dbConfig.getConfig("postgres.pool")
    hikariConfig.setMaximumPoolSize(poolConfig.getInt("max-size"))
    hikariConfig.setConnectionTimeout(poolConfig.getDuration("connection-timeout").toMillis)
    hikariConfig.setIdleTimeout(poolConfig.getDuration("idle-timeout").toMillis)
    hikariConfig.setMaxLifetime(poolConfig.getDuration("max-life-time").toMillis)

    val lock = dbConfig.getString("postgres.lock-type") match {
      case "none" => PgLock.NoLock
      case "lease" =>
        val leaseInterval = dbConfig.getDuration("postgres.lease.interval").toSeconds.seconds
        val leaseRenewInterval = dbConfig.getDuration("postgres.lease.renew-interval").toSeconds.seconds
        require(leaseInterval > leaseRenewInterval, "invalid configuration: `db.postgres.lease.interval` must be greater than `db.postgres.lease.renew-interval`")
        // We use a timeout for locks, because we might not be able to get the lock right away due to concurrent access
        // by other threads. That timeout gives time for other transactions to complete, then ours can take the lock
        val lockTimeout = dbConfig.getDuration("postgres.lease.lock-timeout").toSeconds.seconds
        hikariConfig.setConnectionInitSql(s"SET lock_timeout TO '${lockTimeout.toSeconds}s'")
        val autoReleaseAtShutdown = dbConfig.getBoolean("postgres.lease.auto-release-at-shutdown")
        PgLock.LeaseLock(instanceId, leaseInterval, leaseRenewInterval, lockExceptionHandler, autoReleaseAtShutdown)
      case unknownLock => throw new RuntimeException(s"unknown postgres lock type: `$unknownLock`")
    }

    val safetyChecks_opt = if (dbConfig.getBoolean("postgres.safety-checks.enabled")) {
      Some(PostgresDatabases.SafetyChecks(
        localChannelsMaxAge = FiniteDuration(dbConfig.getDuration("postgres.safety-checks.max-age.local-channels").getSeconds, TimeUnit.SECONDS),
        networkNodesMaxAge = FiniteDuration(dbConfig.getDuration("postgres.safety-checks.max-age.network-nodes").getSeconds, TimeUnit.SECONDS),
        auditRelayedMaxAge = FiniteDuration(dbConfig.getDuration("postgres.safety-checks.max-age.audit-relayed").getSeconds, TimeUnit.SECONDS),
        localChannelsMinCount = dbConfig.getInt("postgres.safety-checks.min-count.local-channels"),
        networkNodesMinCount = dbConfig.getInt("postgres.safety-checks.min-count.network-nodes"),
        networkChannelsMinCount = dbConfig.getInt("postgres.safety-checks.min-count.network-channels"),
      ))
    } else None

    Databases.PostgresDatabases(
      hikariConfig = hikariConfig,
      instanceId = instanceId,
      lock = lock,
      jdbcUrlFile_opt = jdbcUrlFile_opt,
      readOnlyUser_opt = readOnlyUser_opt,
      resetJsonColumns = resetJsonColumns,
      safetyChecks_opt = safetyChecks_opt
    )
  }

}
