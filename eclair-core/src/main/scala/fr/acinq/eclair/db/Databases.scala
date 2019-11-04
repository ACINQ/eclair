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

import fr.acinq.eclair.db.sqlite._
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
      databaseByConnections(sqliteAudit, sqliteNetwork, sqliteEclair)
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

  def databaseByConnections(auditJdbc: Connection, networkJdbc: Connection, eclairJdbc: Connection) = new Databases {
    override val network = new SqliteNetworkDb(networkJdbc)
    override val audit = new SqliteAuditDb(auditJdbc)
    override val channels = new SqliteChannelsDb(eclairJdbc)
    override val peers = new SqlitePeersDb(eclairJdbc)
    override val payments = new SqlitePaymentsDb(eclairJdbc)
    override val pendingRelay = new SqlitePendingRelayDb(eclairJdbc)

    override def backup(file: File): Unit = {
      SqliteUtils.using(eclairJdbc.createStatement()) {
        statement => {
          statement.executeUpdate(s"backup to ${file.getAbsolutePath}")
        }
      }
    }
  }
}