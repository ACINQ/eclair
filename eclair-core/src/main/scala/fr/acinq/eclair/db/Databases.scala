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
import java.sql.{Connection, DriverManager}
import java.util.Properties

import fr.acinq.eclair.db.psql._
import fr.acinq.eclair.db.sqlite._

trait Databases {

  val network: NetworkDb

  val audit: AuditDb

  val channels: ChannelsDb

  val peers: PeersDb

  val payments: PaymentsDb

  val pendingRelay: PendingRelayDb

  def backup(file: File) : Unit
}

object Databases {

  /**
    * Given a parent folder it creates or loads all the databases from a JDBC connection
    * @param dbdir
    * @return
    */
  def sqliteJDBC(dbdir: File): Databases = {
    dbdir.mkdir()
    val sqliteEclair = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "eclair.sqlite")}")
    val sqliteNetwork = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "network.sqlite")}")
    val sqliteAudit = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "audit.sqlite")}")
    SqliteUtils.obtainExclusiveLock(sqliteEclair) // there should only be one process writing to this file

    sqliteDatabaseByConnections(sqliteAudit, sqliteNetwork, sqliteEclair)
  }

  def postgresJDBC(database: String = "eclair", host: String = "localhost", port: Int = 5432,
                   username: Option[String] = None, password: Option[String] = None, ssl: Boolean = false): Databases = {
    val url = s"jdbc:postgresql://${host}:${port}/${database}"
    val connectionProperties = new Properties()
    username.foreach(connectionProperties.setProperty("user", _))
    password.foreach(connectionProperties.setProperty("password", _))
    connectionProperties.setProperty("ssl", ssl.toString)

    val psqlEclair = DriverManager.getConnection(url, connectionProperties)
    val psqlNetwork = DriverManager.getConnection(url, connectionProperties)
    val psqlAudit = DriverManager.getConnection(url, connectionProperties)

    psqlDatabaseByConnections(psqlAudit, psqlNetwork, psqlEclair)
  }

  def sqliteDatabaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection) = new Databases {
    override val network = new SqliteNetworkDb(networkJdbc)
    override val audit = new SqliteAuditDb(auditJdbc)
    override val channels = new SqliteChannelsDb(eclairJdbc)
    override val peers = new SqlitePeersDb(eclairJdbc)
    override val payments = new SqlitePaymentsDb(eclairJdbc)
    override val pendingRelay = new SqlitePendingRelayDb(eclairJdbc)
    override def backup(file: File): Unit = {
      SqliteUtils.using(eclairJdbc.createStatement()) {
        statement =>  {
          statement.executeUpdate(s"backup to ${file.getAbsolutePath}")
        }
      }
    }
  }

  def psqlDatabaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection) = new Databases {
    override val network = new PsqlNetworkDb(networkJdbc)
    override val audit = new PsqlAuditDb(auditJdbc)
    override val channels = new PsqlChannelsDb(eclairJdbc)
    override val peers = new PsqlPeersDb(eclairJdbc)
    override val payments = new PsqlPaymentsDb(eclairJdbc)
    override val pendingRelay = new PsqlPendingRelayDb(eclairJdbc)
    override def backup(file: File): Unit = {}
  }
}