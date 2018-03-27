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
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec

class SqliteChannelsDb(sqlite: Connection) extends ChannelsDb {

  import SqliteUtils._

  val DB_NAME = "channels"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_scripts (channel_id BLOB NOT NULL, script_hash BLOB NOT NULL, script BLOB NOT NULL, PRIMARY KEY(channel_id, script_hash), FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = {
    val data = stateDataCodec.encode(state).require.toByteArray
    using (sqlite.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, state.channelId)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?)")) { statement =>
          statement.setBytes(1, state.channelId)
          statement.setBytes(2, data)
          statement.executeUpdate()
        }
      }
    }
  }

  override def removeChannel(channelId: BinaryData): Unit = {
    using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.executeUpdate()
    }

    using(sqlite.prepareStatement("DELETE FROM htlc_scripts WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.executeUpdate()
    }

    using(sqlite.prepareStatement("DELETE FROM local_channels WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.executeUpdate()
    }
  }

  override def listChannels(): Seq[HasCommitments] = {
    using(sqlite.createStatement) { statement =>
      val rs = statement.executeQuery("SELECT data FROM local_channels")
      codecSequence(rs, stateDataCodec)
    }
  }

  override def addOrUpdateHtlcScript(channelId: BinaryData, scriptHash: BinaryData, script: BinaryData): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO htlc_scripts VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId)
      statement.setBytes(2, scriptHash)
      statement.setBytes(3, script)
      statement.executeUpdate()
    }
  }

  override def getHtlcScript(channelId: BinaryData, scriptHash: BinaryData): Option[BinaryData] = {
    using(sqlite.prepareStatement("SELECT script FROM htlc_scripts WHERE channel_id=? AND script_hash=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.setBytes(2, scriptHash)
      val rs = statement.executeQuery
      if (rs.next()) {
        Option(rs.getBytes("script"))
      } else {
        None
      }

    }
  }
}
