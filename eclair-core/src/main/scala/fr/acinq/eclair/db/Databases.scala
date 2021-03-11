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

import akka.actor.ActorSystem
import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import fr.acinq.eclair.db.pg.PgUtils._
import fr.acinq.eclair.db.pg._
import fr.acinq.eclair.db.sqlite._
import grizzled.slf4j.Logging

import java.io.File
import java.nio.file._
import java.sql.{Connection, DriverManager}
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration._

trait Databases {
  val network: NetworkDb
  val audit: AuditDb
  val channels: ChannelsDb
  val peers: PeersDb
  val payments: PaymentsDb
  val pendingRelay: PendingRelayDb
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

  def init(dbConfig: Config, instanceId: UUID, datadir: File, chaindir: File, db: Option[Databases] = None)(implicit system: ActorSystem): Databases = {
    db match {
      case Some(d) => d
      case None =>
        dbConfig.getString("driver") match {
          case "sqlite" => Databases.sqliteJDBC(chaindir)
          case "postgres" => Databases.setupPgDatabases(dbConfig, instanceId, datadir)
          case driver => throw new RuntimeException(s"unknown database driver `$driver`")
        }
    }
  }

  /**
   * Given a parent folder it creates or loads all the databases from a JDBC connection
   *
   * @param dbdir
   * @return
   */
  def sqliteJDBC(dbdir: File): Databases = {
    dbdir.mkdir()
    var sqliteEclair: Connection = null
    var sqliteNetwork: Connection = null
    var sqliteAudit: Connection = null
    try {
      sqliteEclair = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "eclair.sqlite")}")
      sqliteNetwork = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "network.sqlite")}")
      sqliteAudit = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "audit.sqlite")}")
      SqliteUtils.obtainExclusiveLock(sqliteEclair) // there should only be one process writing to this file
      logger.info("successful lock on eclair.sqlite")
      sqliteDatabaseByConnections(sqliteAudit, sqliteNetwork, sqliteEclair)
    } catch {
      case t: Throwable => {
        logger.error("could not create connection to sqlite databases: ", t)
        if (sqliteEclair != null) sqliteEclair.close()
        if (sqliteNetwork != null) sqliteNetwork.close()
        if (sqliteAudit != null) sqliteAudit.close()
        throw t
      }
    }
  }

  /**
   * Utility method that can also be called directly in tests when using an in-memory database
   */
  def sqliteDatabaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection): Databases = new Databases with FileBackup {
    override val network = new SqliteNetworkDb(networkJdbc)
    override val audit = new SqliteAuditDb(auditJdbc)
    override val channels = new SqliteChannelsDb(eclairJdbc)
    override val peers = new SqlitePeersDb(eclairJdbc)
    override val payments = new SqlitePaymentsDb(eclairJdbc)
    override val pendingRelay = new SqlitePendingRelayDb(eclairJdbc)

    override def backup(backupFile: File): Unit = {

      SqliteUtils.using(eclairJdbc.createStatement()) {
        statement => {
          statement.executeUpdate(s"backup to ${backupFile.getAbsolutePath}")
        }
      }

    }
  }

  def setupPgDatabases(dbConfig: Config, instanceId: UUID, datadir: File)(implicit system: ActorSystem): Databases with ExclusiveLock = {
    val database = dbConfig.getString("postgres.database")
    val host = dbConfig.getString("postgres.host")
    val port = dbConfig.getInt("postgres.port")
    val username = if (dbConfig.getIsNull("postgres.username") || dbConfig.getString("postgres.username").isEmpty) None else Some(dbConfig.getString("postgres.username"))
    val password = if (dbConfig.getIsNull("postgres.password") || dbConfig.getString("postgres.password").isEmpty) None else Some(dbConfig.getString("postgres.password"))

    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(s"jdbc:postgresql://${host}:${port}/${database}")
    username.foreach(hikariConfig.setUsername)
    password.foreach(hikariConfig.setPassword)
    val poolConfig = dbConfig.getConfig("postgres.pool")
    hikariConfig.setMaximumPoolSize(poolConfig.getInt("max-size"))
    hikariConfig.setConnectionTimeout(poolConfig.getDuration("connection-timeout").toMillis)
    hikariConfig.setIdleTimeout(poolConfig.getDuration("idle-timeout").toMillis)
    hikariConfig.setMaxLifetime(poolConfig.getDuration("max-life-time").toMillis)

    val lock = dbConfig.getString("postgres.lock-type") match {
      case "none" => PgLock.NoLock
      case "lease" => {
        val leaseInterval = dbConfig.getDuration("postgres.lease.interval").toSeconds.seconds
        val leaseRenewInterval = dbConfig.getDuration("postgres.lease.renew-interval").toSeconds.seconds
        require(leaseInterval > leaseRenewInterval, "invalid configuration: `db.postgres.lease.interval` must be greater than `db.postgres.lease.renew-interval`")
        PgLock.LeaseLock(instanceId, leaseInterval, leaseRenewInterval, PgLock.logAndStopLockExceptionHandler)
      }
      case unknownLock => throw new RuntimeException(s"unknown postgres lock type: `$unknownLock`")
    }

    Databases.postgresJDBC(
      hikariConfig = hikariConfig,
      instanceId = instanceId,
      lock = lock,
      datadir = datadir
    )
  }

  def postgresJDBC(hikariConfig: HikariConfig,
                   instanceId: UUID,
                   lock: PgLock = PgLock.NoLock, datadir: File)(implicit system: ActorSystem): Databases with ExclusiveLock = {

    checkIfDatabaseUrlIsUnchanged(hikariConfig.getJdbcUrl, datadir)

    implicit val ds: DataSource = new HikariDataSource(hikariConfig)
    implicit val implicitLock: PgLock = lock

    val databases = new Databases with ExclusiveLock {
      override val network = new PgNetworkDb
      override val audit = new PgAuditDb
      override val channels = new PgChannelsDb
      override val peers = new PgPeersDb
      override val payments = new PgPaymentsDb
      override val pendingRelay = new PgPendingRelayDb

      override def obtainExclusiveLock(): Unit = lock.obtainExclusiveLock
    }

    lock match {
      case PgLock.NoLock => ()
      case l: PgLock.LeaseLock => {
        // we obtain a lock right now...
        databases.obtainExclusiveLock()
        // ...and renew the lease regularly
        import system.dispatcher
        system.scheduler.scheduleWithFixedDelay(l.leaseRenewInterval, l.leaseRenewInterval)(new Runnable {
          override def run(): Unit = {
            try {
              databases.obtainExclusiveLock()
            } catch {
              case e: Throwable =>
                logger.error("fatal error: Cannot obtain the database lease.\n", e)
                sys.exit(-1)
            }
          }
        })
      }
    }

    databases
  }

  private def checkIfDatabaseUrlIsUnchanged(url: String, datadir: File): Unit = {
    val urlFile = new File(datadir, "last_jdbcurl")

    def readString(path: Path): String = Files.readAllLines(path).get(0)

    def writeString(path: Path, string: String): Unit = Files.write(path, java.util.Arrays.asList(string))

    if (urlFile.exists()) {
      val oldUrl = readString(urlFile.toPath)
      if (oldUrl != url)
        throw new RuntimeException(s"The database URL has changed since the last start. It was `$oldUrl`, now it's `$url`")
    } else {
      writeString(urlFile.toPath, url)
    }
  }

}