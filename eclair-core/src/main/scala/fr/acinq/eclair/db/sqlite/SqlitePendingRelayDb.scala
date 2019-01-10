/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.Command
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{codecSequence, getVersion, using}
import fr.acinq.eclair.wire.CommandCodecs.cmdCodec

class SqlitePendingRelayDb(sqlite: Connection) extends PendingRelayDb {

  val DB_NAME = "pending_relay"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    // note: should we use a foreign key to local_channels table here?
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pending_relay (channel_id BLOB NOT NULL, htlc_id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
  }

  override def addPendingRelay(channelId: BinaryData, htlcId: Long, cmd: Command): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pending_relay VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId)
      statement.setLong(2, htlcId)
      statement.setBytes(3, cmdCodec.encode(cmd).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def removePendingRelay(channelId: BinaryData, htlcId: Long): Unit = {
    using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=? AND htlc_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.setLong(2, htlcId)
      statement.executeUpdate()
    }
  }

  override def listPendingRelay(channelId: BinaryData): Seq[Command] = {
    using(sqlite.prepareStatement("SELECT htlc_id, data FROM pending_relay WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      val rs = statement.executeQuery()
      codecSequence(rs, cmdCodec)
    }
  }

  override def close(): Unit = sqlite.close()
}
