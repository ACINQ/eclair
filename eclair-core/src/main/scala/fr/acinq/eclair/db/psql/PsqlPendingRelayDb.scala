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
import fr.acinq.eclair.channel.{Command, HasHtlcId}
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.db.psql.PsqlUtils._
import fr.acinq.eclair.wire.CommandCodecs.cmdCodec
import javax.sql.DataSource

import scala.collection.immutable.Queue

class PsqlPendingRelayDb(implicit ds: DataSource, lock: DatabaseLock) extends PendingRelayDb {

  import PsqlUtils.ExtendedResultSet._
  import PsqlUtils._
  import lock._

  val DB_NAME = "pending_relay"
  val CURRENT_VERSION = 1

  inTransaction { psql =>
    using(psql.createStatement()) { statement =>
      require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION, s"incompatible version of $DB_NAME DB found") // there is only one version currently deployed
      // note: should we use a foreign key to local_channels table here?
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS pending_relay (channel_id TEXT NOT NULL, htlc_id BIGINT NOT NULL, data BYTEA NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
    }
  }

  override def addPendingRelay(channelId: ByteVector32, cmd: Command with HasHtlcId): Unit = {
    withLock { psql =>
      using(psql.prepareStatement("INSERT INTO pending_relay VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, cmd.id)
        statement.setBytes(3, cmdCodec.encode(cmd).require.toByteArray)
        statement.executeUpdate()
      }
    }
  }

  override def removePendingRelay(channelId: ByteVector32, htlcId: Long): Unit = {
    withLock { psql =>
      using(psql.prepareStatement("DELETE FROM pending_relay WHERE channel_id=? AND htlc_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setLong(2, htlcId)
        statement.executeUpdate()
      }
    }
  }

  override def listPendingRelay(channelId: ByteVector32): Seq[Command with HasHtlcId] = {
    withLock { psql =>
      using(psql.prepareStatement("SELECT htlc_id, data FROM pending_relay WHERE channel_id=?")) { statement =>
        statement.setString(1, channelId.toHex)
        val rs = statement.executeQuery()
        codecSequence(rs, cmdCodec)
      }
    }
  }

  override def listPendingRelay(): Set[(ByteVector32, Long)] = {
    withLock { psql =>
      using(psql.prepareStatement("SELECT channel_id, htlc_id FROM pending_relay")) { statement =>
        val rs = statement.executeQuery()
        var q: Queue[(ByteVector32, Long)] = Queue()
        while (rs.next()) {
          q = q :+ (rs.getByteVector32FromHex("channel_id"), rs.getLong("htlc_id"))
        }
        q.toSet
      }
    }
  }

  override def close(): Unit = ()
}
