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

import java.sql.Connection

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{Command, HtlcSettlementCommand}
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.wire.internal.CommandCodecs.cmdCodec

import scala.collection.immutable.Queue

class SqlitePendingRelayDb(sqlite: Connection) extends PendingRelayDb {

  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  val DB_NAME = "pending_relay"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION, s"incompatible version of $DB_NAME DB found") // there is only one version currently deployed
    // note: should we use a foreign key to local_channels table here?
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pending_relay (channel_id BLOB NOT NULL, htlc_id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
  }

  override def addPendingRelay(channelId: ByteVector32, cmd: HtlcSettlementCommand): Unit = withMetrics("pending-relay/add") {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pending_relay VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, cmd.id)
      statement.setBytes(3, cmdCodec.encode(cmd).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def removePendingRelay(channelId: ByteVector32, htlcId: Long): Unit = withMetrics("pending-relay/remove") {
    using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=? AND htlc_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, htlcId)
      statement.executeUpdate()
    }
  }

  override def listPendingRelay(channelId: ByteVector32): Seq[HtlcSettlementCommand] = withMetrics("pending-relay/list-channel") {
    using(sqlite.prepareStatement("SELECT data FROM pending_relay WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      val rs = statement.executeQuery()
      codecSequence(rs, cmdCodec)
    }
  }

  override def listPendingRelay(): Set[(ByteVector32, Long)] = withMetrics("pending-relay/list") {
    using(sqlite.prepareStatement("SELECT channel_id, htlc_id FROM pending_relay")) { statement =>
      val rs = statement.executeQuery()
      var q: Queue[(ByteVector32, Long)] = Queue()
      while (rs.next()) {
        q = q :+ (rs.getByteVector32("channel_id"), rs.getLong("htlc_id"))
      }
      q.toSet
    }
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()
}