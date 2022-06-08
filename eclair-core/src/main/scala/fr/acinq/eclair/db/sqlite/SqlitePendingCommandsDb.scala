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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel.HtlcSettlementCommand
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.wire.internal.CommandCodecs.cmdCodec
import grizzled.slf4j.Logging

import java.sql.{Connection, Statement}

object SqlitePendingCommandsDb {
  val DB_NAME = "pending_relay"
  val CURRENT_VERSION = 2
}

class SqlitePendingCommandsDb(val sqlite: Connection) extends PendingCommandsDb with Logging {

  import SqlitePendingCommandsDb._
  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      statement.executeUpdate("ALTER TABLE pending_relay RENAME TO pending_settlement_commands")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        // note: should we use a foreign key to local_channels table here?
        statement.executeUpdate("CREATE TABLE pending_settlement_commands (channel_id BLOB NOT NULL, htlc_id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
      case Some(v@1) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        migration12(statement)
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addSettlementCommand(channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = withMetrics("pending-relay/add", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pending_settlement_commands VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, cmd.id)
      statement.setBytes(3, cmdCodec.encode(cmd).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def removeSettlementCommand(channelId: ByteVector32, htlcId: Long): Unit = withMetrics("pending-relay/remove", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM pending_settlement_commands WHERE channel_id=? AND htlc_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, htlcId)
      statement.executeUpdate()
    }
  }

  override def listSettlementCommands(channelId: ByteVector32): Seq[HtlcSettlementCommand] = withMetrics("pending-relay/list-channel", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT data FROM pending_settlement_commands WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeQuery()
        .mapCodec(cmdCodec).toSeq
    }
  }

  override def listSettlementCommands(): Seq[(ByteVector32, HtlcSettlementCommand)] = withMetrics("pending-relay/list", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT channel_id, data FROM pending_settlement_commands")) { statement =>
      statement.executeQuery()
        .map(rs => (rs.getByteVector32("channel_id"), cmdCodec.decode(rs.getByteVector("data").bits).require.value))
        .toSeq
    }
  }
}