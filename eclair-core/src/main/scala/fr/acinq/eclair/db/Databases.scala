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
import java.nio.file._
import java.sql.{Connection, DriverManager}
import java.util.UUID

import akka.actor.ActorSystem
import com.typesafe.config.Config
import fr.acinq.eclair.db.pg.PgUtils.LockType.LockType
import fr.acinq.eclair.db.pg.PgUtils._
import fr.acinq.eclair.db.pg._
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

  def obtainExclusiveLock(): Unit
}

object Databases extends Logging {

  trait CanBackup { this: Databases =>
    def backup(file: File): Unit
  }

  def init(dbConfig: Config, instanceId: UUID, datadir: File, chaindir: File, db: Option[Databases] = None)(implicit system: ActorSystem): Databases = {
    db match {
      case Some(d) => d
      case None =>
        dbConfig.getString("driver") match {
          case "sqlite" => Databases.sqliteJDBC(chaindir)
          case "postgres" =>
            val pg = Databases.setupPgDatabases(dbConfig, instanceId, datadir, { ex =>
              logger.error("fatal error: Cannot obtain lock on the database.\n", ex)
              sys.exit(-2)
            })
            if (LockType(dbConfig.getString("postgres.lock-type")) == LockType.LEASE) {
              val dbLockLeaseRenewInterval = dbConfig.getDuration("postgres.lease.renew-interval").toSeconds.seconds
              val dbLockLeaseInterval = dbConfig.getDuration("postgres.lease.interval").toSeconds.seconds
              if (dbLockLeaseInterval <= dbLockLeaseRenewInterval)
                throw new RuntimeException("Invalid configuration: `db.postgres.lease.interval` must be greater than `db.postgres.lease.renew-interval`")
              import system.dispatcher
              system.scheduler.scheduleWithFixedDelay(dbLockLeaseRenewInterval, dbLockLeaseRenewInterval)(new Runnable {
                override def run(): Unit = {
                  try {
                    pg.obtainExclusiveLock()
                  } catch {
                    case e: Throwable =>
                      logger.error("fatal error: Cannot obtain the database lease.\n", e)
                      sys.exit(-1)
                  }
                }
              })
            }
            pg
          case driver => throw new RuntimeException(s"Unknown database driver `$driver`")
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

  def postgresJDBC(database: String, host: String, port: Int,
                   username: Option[String], password: Option[String],
                   poolProperties: Map[String, Long],
                   instanceId: UUID,
                   databaseLeaseInterval: FiniteDuration,
                   lockExceptionHandler: LockExceptionHandler = { _ => () },
                   lockType: LockType = LockType.NONE, datadir: File): Databases = {
    val url = s"jdbc:postgresql://${host}:${port}/${database}"

    checkIfDatabaseUrlIsUnchanged(url, datadir)

    implicit val lock: DatabaseLock = lockType match {
      case LockType.NONE => NoLock
      case LockType.LEASE => LeaseLock(instanceId, databaseLeaseInterval, lockExceptionHandler)
      case _ => throw new RuntimeException(s"Unknown postgres lock type: `$lockType`")
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
      override val network = new PgNetworkDb
      override val audit = new PgAuditDb
      override val channels = new PgChannelsDb
      override val peers = new PgPeersDb
      override val payments = new PgPaymentsDb
      override val pendingRelay = new PgPendingRelayDb
      override def obtainExclusiveLock(): Unit = lock.obtainExclusiveLock
    }
    databases.obtainExclusiveLock()
    databases
  }

  def sqliteDatabaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection): Databases = new Databases with CanBackup {
    override val network = new SqliteNetworkDb(networkJdbc)
    override val audit = new SqliteAuditDb(auditJdbc)
    override val channels = new SqliteChannelsDb(eclairJdbc)
    override val peers = new SqlitePeersDb(eclairJdbc)
    override val payments = new SqlitePaymentsDb(eclairJdbc)
    override val pendingRelay = new SqlitePendingRelayDb(eclairJdbc)
    override def backup(backupFile: File): Unit = {
      val tmpFile = new File(backupFile.getAbsolutePath.concat(".tmp"))

      SqliteUtils.using(eclairJdbc.createStatement()) {
        statement => {
          statement.executeUpdate(s"backup to ${tmpFile.getAbsolutePath}")
        }
      }

      // this will throw an exception if it fails, which is possible if the backup file is not on the same filesystem
      // as the temporary file
      Files.move(tmpFile.toPath, backupFile.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override def obtainExclusiveLock(): Unit = ()
  }

  def setupPgDatabases(dbConfig: Config, instanceId: UUID, datadir: File, lockExceptionHandler: LockExceptionHandler): Databases = {
    val database = dbConfig.getString("postgres.database")
    val host = dbConfig.getString("postgres.host")
    val port = dbConfig.getInt("postgres.port")
    val username = if (dbConfig.getIsNull("postgres.username") || dbConfig.getString("postgres.username").isEmpty)
      None
    else
      Some(dbConfig.getString("postgres.username"))
    val password = if (dbConfig.getIsNull("postgres.password") || dbConfig.getString("postgres.password").isEmpty)
      None
    else
      Some(dbConfig.getString("postgres.password"))
    val properties = {
      val poolConfig = dbConfig.getConfig("postgres.pool")
      Map.empty
        .updated("max-size", poolConfig.getInt("max-size").toLong)
        .updated("connection-timeout", poolConfig.getDuration("connection-timeout").toMillis)
        .updated("idle-timeout", poolConfig.getDuration("idle-timeout").toMillis)
        .updated("max-life-time", poolConfig.getDuration("max-life-time").toMillis)

    }
    val lockType = LockType(dbConfig.getString("postgres.lock-type"))
    val leaseInterval = dbConfig.getDuration("postgres.lease.interval").toSeconds.seconds

    Databases.postgresJDBC(
      database = database, host = host, port = port,
      username = username, password = password,
      poolProperties = properties,
      instanceId = instanceId,
      databaseLeaseInterval = leaseInterval,
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