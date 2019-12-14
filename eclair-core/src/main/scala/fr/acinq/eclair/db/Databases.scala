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
import java.nio.file.{Files, StandardCopyOption}
import java.sql.{Connection, DriverManager}

import com.typesafe.config.Config
import fr.acinq.eclair.db.psql._
import fr.acinq.eclair.db.sqlite._
import javax.sql.DataSource
import grizzled.slf4j.Logging
import org.sqlite.SQLiteException

trait Databases {

  val network: NetworkDb

  val audit: AuditDb

  val channels: ChannelsDb

  val peers: PeersDb

  val payments: PaymentsDb

  val pendingRelay: PendingRelayDb

  def backup(file: File): Unit

  val isBackupSupported: Boolean
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

  def postgresJDBC(database: String = "eclair", host: String = "localhost", port: Int = 5432,
                   username: Option[String] = None, password: Option[String] = None,
                   poolProperties: Map[String, Long] = Map()): Databases = {
    try {
      val url = s"jdbc:postgresql://${host}:${port}/${database}"

      import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

      val config = new HikariConfig()
      config.setJdbcUrl(url)
      username.foreach(config.setUsername)
      password.foreach(config.setPassword)
      poolProperties.get("max-size").foreach(x => config.setMaximumPoolSize(x.toInt))
      poolProperties.get("connection-timeout").foreach(config.setConnectionTimeout)
      poolProperties.get("idle-timeout").foreach(config.setIdleTimeout)
      poolProperties.get("max-life-time").foreach(config.setMaxLifetime)

      val ds = new HikariDataSource(config)

      psqlDatabaseByConnections(ds)
    } catch {
      case t: Throwable => {
        logger.error("could not create connection to psql database: ", t)
        throw t
      }
    }
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
  }

  def psqlDatabaseByConnections(implicit ds: DataSource): Databases = new Databases {
    override val network = new PsqlNetworkDb()
    override val audit = new PsqlAuditDb()
    override val channels = new PsqlChannelsDb()
    override val peers = new PsqlPeersDb()
    override val payments = new PsqlPaymentsDb()
    override val pendingRelay = new PsqlPendingRelayDb()
    override def backup(file: File): Unit = throw new RuntimeException("psql driver does not support channels backup")
    override val isBackupSupported: Boolean = false
  }

  def setupPsqlDatabases(dbConfig: Config): Databases = {
    val database = dbConfig.getString("psql.database")
    val host = dbConfig.getString("psql.host")
    val port = dbConfig.getInt("psql.port")
    val username = if (dbConfig.getIsNull("psql.username") || dbConfig.getString("psql.username").isBlank)
      None
    else
      Some(dbConfig.getString("psql.username"))
    val password = if (dbConfig.getIsNull("psql.password") || dbConfig.getString("psql.password").isBlank)
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

    Databases.postgresJDBC(
      database = database, host = host, port = port,
      username = username, password = password, poolProperties = properties
    )
  }

}