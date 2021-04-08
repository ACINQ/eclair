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
import fr.acinq.eclair.db.pg.PgUtils.PgLock.LockFailureHandler
import fr.acinq.eclair.db.pg.PgUtils._
import fr.acinq.eclair.db.pg._
import fr.acinq.eclair.db.sqlite._
import grizzled.slf4j.Logging

import java.io.File
import java.nio.file._
import java.sql.{Connection, DriverManager}
import java.util.UUID
import scala.concurrent.duration._

trait Databases {
  //@formatter:off
  def network: NetworkDb
  def audit: AuditDb
  def channels: ChannelsDb
  def peers: PeersDb
  def payments: PaymentsDb
  def pendingRelay: PendingRelayDb
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
                                     pendingRelay: SqlitePendingRelayDb,
                                     private val backupConnection: Connection) extends Databases with FileBackup {
    override def backup(backupFile: File): Unit = SqliteUtils.using(backupConnection.createStatement()) {
      statement => {
        statement.executeUpdate(s"backup to ${backupFile.getAbsolutePath}")
      }
    }
  }

  object SqliteDatabases {
    def apply(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection): SqliteDatabases = SqliteDatabases(
      network = new SqliteNetworkDb(networkJdbc),
      audit = new SqliteAuditDb(auditJdbc),
      channels = new SqliteChannelsDb(eclairJdbc),
      peers = new SqlitePeersDb(eclairJdbc),
      payments = new SqlitePaymentsDb(eclairJdbc),
      pendingRelay = new SqlitePendingRelayDb(eclairJdbc),
      backupConnection = eclairJdbc
    )
  }

  case class PostgresDatabases private(network: PgNetworkDb,
                                       audit: PgAuditDb,
                                       channels: PgChannelsDb,
                                       peers: PgPeersDb,
                                       payments: PgPaymentsDb,
                                       pendingRelay: PgPendingRelayDb,
                                       dataSource: HikariDataSource,
                                       lock: PgLock) extends Databases with ExclusiveLock {
    override def obtainExclusiveLock(): Unit = lock.obtainExclusiveLock(dataSource)
  }

  object PostgresDatabases {
    def apply(hikariConfig: HikariConfig,
              instanceId: UUID,
              lock: PgLock = PgLock.NoLock,
              jdbcUrlFile_opt: Option[File])(implicit system: ActorSystem): PostgresDatabases = {

      jdbcUrlFile_opt.foreach(jdbcUrlFile => checkIfDatabaseUrlIsUnchanged(hikariConfig.getJdbcUrl, jdbcUrlFile))

      implicit val ds: HikariDataSource = new HikariDataSource(hikariConfig)
      implicit val implicitLock: PgLock = lock

      val databases = PostgresDatabases(
        network = new PgNetworkDb,
        audit = new PgAuditDb,
        channels = new PgChannelsDb,
        peers = new PgPeersDb,
        payments = new PgPaymentsDb,
        pendingRelay = new PgPendingRelayDb,
        dataSource = ds,
        lock = lock)

      lock match {
        case PgLock.NoLock => ()
        case l: PgLock.LeaseLock =>
          // we obtain a lock right now...
          databases.obtainExclusiveLock()
          // ...and renew the lease regularly
          import system.dispatcher
          system.scheduler.scheduleWithFixedDelay(l.leaseRenewInterval, l.leaseRenewInterval)(() => databases.obtainExclusiveLock())
      }

      databases
    }

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
  }

  def init(dbConfig: Config, instanceId: UUID, chaindir: File, db: Option[Databases] = None)(implicit system: ActorSystem): Databases = {
    db match {
      case Some(d) => d
      case None =>
        dbConfig.getString("driver") match {
          case "sqlite" => Databases.sqlite(chaindir)
          case "postgres" => Databases.postgres(dbConfig, instanceId, chaindir)
          case driver => throw new RuntimeException(s"unknown database driver `$driver`")
        }
    }
  }

  /**
   * Given a parent folder it creates or loads all the databases from a JDBC connection
   */
  def sqlite(dbdir: File): SqliteDatabases = {
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
      SqliteDatabases(sqliteAudit, sqliteNetwork, sqliteEclair)
    } catch {
      case t: Throwable =>
        logger.error("could not create connection to sqlite databases: ", t)
        if (sqliteEclair != null) sqliteEclair.close()
        if (sqliteNetwork != null) sqliteNetwork.close()
        if (sqliteAudit != null) sqliteAudit.close()
        throw t
    }
  }

  def postgres(dbConfig: Config, instanceId: UUID, dbdir: File, lockExceptionHandler: LockFailureHandler = LockFailureHandler.logAndStop)(implicit system: ActorSystem): PostgresDatabases = {
    val database = dbConfig.getString("postgres.database")
    val host = dbConfig.getString("postgres.host")
    val port = dbConfig.getInt("postgres.port")
    val username = if (dbConfig.getIsNull("postgres.username") || dbConfig.getString("postgres.username").isEmpty) None else Some(dbConfig.getString("postgres.username"))
    val password = if (dbConfig.getIsNull("postgres.password") || dbConfig.getString("postgres.password").isEmpty) None else Some(dbConfig.getString("postgres.password"))

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
        PgLock.LeaseLock(instanceId, leaseInterval, leaseRenewInterval, lockExceptionHandler)
      case unknownLock => throw new RuntimeException(s"unknown postgres lock type: `$unknownLock`")
    }

    val jdbcUrlFile = new File(dbdir, "last_jdbcurl")

    Databases.PostgresDatabases(
      hikariConfig = hikariConfig,
      instanceId = instanceId,
      lock = lock,
      jdbcUrlFile_opt = Some(jdbcUrlFile)
    )
  }

}
