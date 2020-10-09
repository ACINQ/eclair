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
import fr.acinq.eclair.db.sqlite._
import grizzled.slf4j.Logging

trait Databases {

  val network: NetworkDb

  val audit: AuditDb

  val channels: ChannelsDb

  val peers: PeersDb

  val payments: PaymentsDb

  val pendingRelay: PendingRelayDb
}

object Databases extends Logging {

  trait FileBackup { this: Databases =>
    def backup(backupFile: File): Unit
  }

  trait ExclusiveLock { this: Databases =>
    def obtainExclusiveLock(): Unit
  }

  def init(dbConfig: Config, instanceId: UUID, datadir: File, chaindir: File, db: Option[Databases] = None)(implicit system: ActorSystem): Databases = {
    db match {
      case Some(d) => d
      case None =>
        dbConfig.getString("driver") match {
          case "sqlite" => Databases.sqliteJDBC(chaindir)
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