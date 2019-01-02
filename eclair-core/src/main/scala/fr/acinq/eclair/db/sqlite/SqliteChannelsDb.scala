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

import scala.collection.immutable.Queue

class SqliteChannelsDb(sqlite: Connection) extends ChannelsDb {

  import SqliteUtils._

  val DB_NAME = "channels"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
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

    using(sqlite.prepareStatement("DELETE FROM htlc_infos WHERE channel_id=?")) { statement =>
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

  def addOrUpdateHtlcInfo(channelId: BinaryData, commitmentNumber: Long, paymentHash: BinaryData, cltvExpiry: Long): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId)
      statement.setLong(2, commitmentNumber)
      statement.setBytes(3, paymentHash)
      statement.setLong(4, cltvExpiry)
      statement.executeUpdate()
    }
  }

  def listHtlcInfos(channelId: BinaryData, commitmentNumber: Long): Seq[(BinaryData, Long)] = {
    using(sqlite.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.setLong(2, commitmentNumber)
      val rs = statement.executeQuery
      var q: Queue[(BinaryData, Long)] = Queue()
      while (rs.next()) {
        q = q :+ (BinaryData(rs.getBytes("payment_hash")), rs.getLong("cltv_expiry"))
      }
      q
    }
  }

  override def close(): Unit = sqlite.close
}
