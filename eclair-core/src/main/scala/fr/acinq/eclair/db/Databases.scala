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

trait Databases {

  val network: NetworkDb

  val audit: AuditDb

  val channels: ChannelsDb

  val peers: PeersDb

  val payments: PaymentsDb

  val pendingRelay: PendingRelayDb

  val hostedChannels: HostedChannelsDb

  def backup(file: File) : Unit
}

object Databases {

  def assemble(sqliteAudit: Connection, sqliteNetwork: Connection, sqliteEclair: Connection, hosted: HostedChannelsDb): Databases =
    new Databases {
      override val network = new SqliteNetworkDb(sqliteNetwork)
      override val audit = new SqliteAuditDb(sqliteAudit)
      override val channels = new SqliteChannelsDb(sqliteEclair)
      override val peers = new SqlitePeersDb(sqliteEclair)
      override val payments = new SqlitePaymentsDb(sqliteEclair)
      override val pendingRelay = new SqlitePendingRelayDb(sqliteEclair)
      override val hostedChannels: HostedChannelsDb = hosted
      override def backup(file: File): Unit = {
        SqliteUtils.using(sqliteEclair.createStatement()) {
          _.executeUpdate(s"backup to ${file.getAbsolutePath}")
        }
      }
    }

  /**
    * Given a parent folder it creates or loads (channels, network, audit) databases from a JDBC connection
    * @param dbdir
    * @return
    */
  def sqliteJDBC(dbdir: File): (Connection, Connection, Connection) = {
    dbdir.mkdir()
    val sqliteEclair = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "eclair.sqlite")}")
    val sqliteNetwork = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "network.sqlite")}")
    val sqliteAudit = DriverManager.getConnection(s"jdbc:sqlite:${new File(dbdir, "audit.sqlite")}")
    SqliteUtils.obtainExclusiveLock(sqliteEclair) // there should only be one process writing to this file
    (sqliteAudit, sqliteNetwork, sqliteEclair)
  }
}