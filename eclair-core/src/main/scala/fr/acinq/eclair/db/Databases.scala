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

import java.io.File
import java.lang.management.ManagementFactory
import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicLong
import java.nio.file._

import com.typesafe.config.Config
import fr.acinq.eclair.db.psql.PsqlUtils.LockType.LockType
import fr.acinq.eclair.db.psql.PsqlUtils._
import fr.acinq.eclair.db.psql._
import fr.acinq.eclair.db.sqlite._
import grizzled.slf4j.Logging
import javax.sql.DataSource

import scala.concurrent.duration._

trait Databases {

  val network: NetworkDb

  val audit: AuditDb

  val channels: ChannelsDb

  val peers: PeersDb

  val payments: PaymentsDb

  val pendingRelay: PendingRelayDb

  def backup(file: File): Unit

  val isBackupSupported: Boolean

  def obtainExclusiveLock(): Unit
}

object Databases extends Logging {

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

  def postgresJDBC(database: String = "eclair", host: String = "localhost", port: Int = 5432,
                   username: Option[String] = None, password: Option[String] = None,
                   poolProperties: Map[String, Long] = Map(),
                   instanceId: String = ManagementFactory.getRuntimeMXBean().getName(),
                   databaseLeaseInterval: FiniteDuration = 5.minutes,
                   lockTimeout: FiniteDuration = 5.seconds,
                   lockExceptionHandler: LockExceptionHandler = { _ => () },
                   lockType: LockType = LockType.NONE, datadir: File = new File(File.separator + "tmp")): Databases = {
    val url = s"jdbc:postgresql://${host}:${port}/${database}"

    checkIfDatabaseUrlIsUnchanged(url, datadir)

    implicit val lock: DatabaseLock = lockType match {
      case LockType.NONE => NoLock
      case LockType.OPTIMISTIC => OptimisticLock(new AtomicLong(0L), lockExceptionHandler)
      case LockType.OWNERSHIP_LEASE => OwnershipLeaseLock(instanceId, databaseLeaseInterval, lockTimeout, lockExceptionHandler)
      case x@_ => throw new RuntimeException(s"Unknown psql lock type: `$lockType`")
    }

    import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

    val config = new HikariConfig()
    config.setJdbcUrl(url)
    username.foreach(config.setUsername)
    password.foreach(config.setPassword)
    poolProperties.get("max-size").foreach(x => config.setMaximumPoolSize(x.toInt))
    poolProperties.get("connection-timeout").foreach(config.setConnectionTimeout)
    poolProperties.get("idle-timeout").foreach(config.setIdleTimeout)
    poolProperties.get("max-life-time").foreach(config.setMaxLifetime)

    implicit val ds: DataSource = new HikariDataSource(config)

    val databases: Databases = new Databases {
      override val network = new PsqlNetworkDb
      override val audit = new PsqlAuditDb
      override val channels = new PsqlChannelsDb
      override val peers = new PsqlPeersDb
      override val payments = new PsqlPaymentsDb
      override val pendingRelay = new PsqlPendingRelayDb

      override def backup(file: File): Unit = throw new RuntimeException("psql driver does not support channels backup")

      override val isBackupSupported: Boolean = false

      override def obtainExclusiveLock(): Unit = lock.obtainExclusiveLock
    }
    databases.obtainExclusiveLock()
    databases
  }

  def sqliteDatabaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection): Databases = new Databases {
    override val network = new SqliteNetworkDb(networkJdbc)
    override val audit = new SqliteAuditDb(auditJdbc)
    override val channels = new SqliteChannelsDb(eclairJdbc)
    override val peers = new SqlitePeersDb(eclairJdbc)
    override val payments = new SqlitePaymentsDb(eclairJdbc)
    override val pendingRelay = new SqlitePendingRelayDb(eclairJdbc)
    override def backup(backupFile: File): Unit = {
      val tmpFile = new File(backupFile.getAbsolutePath.concat(".tmp"))

      SqliteUtils.using(eclairJdbc.createStatement()) {
        statement =>  {
          statement.executeUpdate(s"backup to ${tmpFile.getAbsolutePath}")
        }
      }

      // this will throw an exception if it fails, which is possible if the backup file is not on the same filesystem
      // as the temporary file
      Files.move(tmpFile.toPath, backupFile.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    override val isBackupSupported: Boolean = true

    override def obtainExclusiveLock(): Unit = ()
  }

  def setupPsqlDatabases(dbConfig: Config, datadir: File, lockExceptionHandler: LockExceptionHandler): Databases = {
    val database = dbConfig.getString("psql.database")
    val host = dbConfig.getString("psql.host")
    val port = dbConfig.getInt("psql.port")
    val username = if (dbConfig.getIsNull("psql.username") || dbConfig.getString("psql.username").isEmpty)
      None
    else
      Some(dbConfig.getString("psql.username"))
    val password = if (dbConfig.getIsNull("psql.password") || dbConfig.getString("psql.password").isEmpty)
      None
    else
      Some(dbConfig.getString("psql.password"))
    val properties = {
      val poolConfig = dbConfig.getConfig("psql.pool")
      Map.empty
        .updated("max-size", poolConfig.getInt("max-size").toLong)
        .updated("connection-timeout", poolConfig.getDuration("connection-timeout").toMillis)
        .updated("idle-timeout", poolConfig.getDuration("idle-timeout").toMillis)
        .updated("max-life-time", poolConfig.getDuration("max-life-time").toMillis)

    }
    val lockType = LockType(dbConfig.getString("psql.lock-type"))
    val leaseInterval = dbConfig.getDuration("psql.ownership-lease.lease-interval").toSeconds.seconds
    val lockTimeout = dbConfig.getDuration("psql.ownership-lease.lock-timeout").toSeconds.seconds

    Databases.postgresJDBC(
      database = database, host = host, port = port,
      username = username, password = password,
      poolProperties = properties,
      databaseLeaseInterval = leaseInterval, lockTimeout = lockTimeout,
      lockExceptionHandler = lockExceptionHandler, lockType = lockType, datadir = datadir
    )
  }

  private def checkIfDatabaseUrlIsUnchanged(url: String, datadir: File ): Unit = {
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