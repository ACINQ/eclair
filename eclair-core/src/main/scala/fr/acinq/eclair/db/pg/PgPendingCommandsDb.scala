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

package fr.acinq.eclair.db.pg


import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel.HtlcSettlementCommand
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.db.pg.PgUtils._
import fr.acinq.eclair.wire.internal.CommandCodecs.cmdCodec
import grizzled.slf4j.Logging

import java.sql.Statement
import javax.sql.DataSource

object PgPendingCommandsDb {
  val DB_NAME = "pending_relay"
  val CURRENT_VERSION = 3
}

class PgPendingCommandsDb(implicit ds: DataSource, lock: PgLock) extends PendingCommandsDb with Logging {

  import PgPendingCommandsDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>

      def migration12(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE pending_relay RENAME TO pending_settlement_commands")
      }

      def migration23(statement: Statement): Unit = {
        statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS local")
        statement.executeUpdate("ALTER TABLE pending_settlement_commands SET SCHEMA local")
      }

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS local")
          // note: should we use a foreign key to local_channels table here?
          statement.executeUpdate("CREATE TABLE local.pending_settlement_commands (channel_id TEXT NOT NULL, htlc_id BIGINT NOT NULL, data BYTEA NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
        case Some(v@(1 | 2)) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
          if (v < 2) {
            migration12(statement)
          }
          if (v < 3) {
            migration23(statement)
          }
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addSettlementCommand(channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = withMetrics("pending-relay/add", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO local.pending_settlement_commands VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, cmd.id)
        statement.setBytes(3, cmdCodec.encode(cmd).require.toByteArray)
        statement.executeUpdate()
      }
    }
  }

  override def removeSettlementCommand(channelId: ByteVector32, htlcId: Long): Unit = withMetrics("pending-relay/remove", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM local.pending_settlement_commands WHERE channel_id=? AND htlc_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, htlcId)
        statement.executeUpdate()
      }
    }
  }

  override def listSettlementCommands(channelId: ByteVector32): Seq[HtlcSettlementCommand] = withMetrics("pending-relay/list-channel", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT htlc_id, data FROM local.pending_settlement_commands WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeQuery()
          .mapCodec(cmdCodec).toSeq
      }
    }
  }

  override def listSettlementCommands(): Seq[(ByteVector32, HtlcSettlementCommand)] = withMetrics("pending-relay/list", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT channel_id, data FROM local.pending_settlement_commands")) { statement =>
        statement.executeQuery()
          .map(rs => (rs.getByteVector32FromHex("channel_id"), cmdCodec.decode(rs.getByteVector("data").bits).require.value))
          .toSeq
      }
    }
  }
}
