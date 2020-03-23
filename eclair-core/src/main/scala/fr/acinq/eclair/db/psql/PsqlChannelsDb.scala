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

package fr.acinq.eclair.db.psql

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.db.psql.PsqlUtils.DatabaseLock
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec
import grizzled.slf4j.Logging
import javax.sql.DataSource

import scala.collection.immutable.Queue

class PsqlChannelsDb(implicit ds: DataSource, lock: DatabaseLock) extends ChannelsDb with Logging {

  import PsqlUtils.ExtendedResultSet._
  import PsqlUtils._
  import lock._

  val DB_NAME = "channels"
  val CURRENT_VERSION = 2

  inTransaction { psql =>
    using(psql.createStatement()) { statement =>
      getVersion(statement, DB_NAME, CURRENT_VERSION) match {
        case CURRENT_VERSION =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id TEXT NOT NULL, commitment_number TEXT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
    }
  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = {
    withLock { psql =>
      val data = stateDataCodec.encode(state).require.toByteArray
      using(psql.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update =>
        update.setBytes(1, data)
        update.setString(2, state.channelId.toHex)
        if (update.executeUpdate() == 0) {
          using(psql.prepareStatement("INSERT INTO local_channels VALUES (?, ?, FALSE)")) { statement =>
            statement.setString(1, state.channelId.toHex)
            statement.setBytes(2, data)
            statement.executeUpdate()
          }
        }
      }
    }
  }

  override def removeChannel(channelId: ByteVector32): Unit = {
    withLock { psql =>
      using(psql.prepareStatement("DELETE FROM pending_relay WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }

      using(psql.prepareStatement("DELETE FROM htlc_infos WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }

      using(psql.prepareStatement("UPDATE local_channels SET is_closed=TRUE WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def listLocalChannels(): Seq[HasCommitments] = {
    withLock { psql =>
      using(psql.createStatement) { statement =>
        val rs = statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=FALSE")
        codecSequence(rs, stateDataCodec)
      }
    }
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = {
    withLock { psql =>
      using(psql.prepareStatement("INSERT INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, commitmentNumber)
        statement.setString(3, paymentHash.toHex)
        statement.setLong(4, cltvExpiry.toLong)
        statement.executeUpdate()
      }
    }
  }

  def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = {
    withLock { psql =>
      using(psql.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setString(2, commitmentNumber.toString)
        val rs = statement.executeQuery
        var q: Queue[(ByteVector32, CltvExpiry)] = Queue()
        while (rs.next()) {
          q = q :+ (ByteVector32(rs.getByteVector32FromHex("payment_hash")), CltvExpiry(rs.getLong("cltv_expiry")))
        }
        q
      }
    }
  }

  override def close(): Unit = ()
}
