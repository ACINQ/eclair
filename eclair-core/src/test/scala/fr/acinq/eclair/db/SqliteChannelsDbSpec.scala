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

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.TestConstants.{TestPgDatabases, TestSqliteDatabases, forAllDbs}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgUtils
import fr.acinq.eclair.db.sqlite.{SqliteChannelsDb, SqliteUtils}
import fr.acinq.eclair.db.sqlite.SqliteUtils.ExtendedResultSet._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.stateDataCodec
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{CltvExpiry, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import java.sql.SQLException

class SqliteChannelsDbSpec extends AnyFunSuite {

  test("init sqlite 2 times in a row") {
    forAllDbs { dbs =>
      val db1 = dbs.channels()
      val db2 = dbs.channels()
    }
  }

  test("add/remove/list channels") {
    forAllDbs { dbs =>
      val db = dbs.channels()
      dbs.pendingRelay() // needed by db.removeChannel

      val channel = ChannelCodecsSpec.normal

      val commitNumber = 42
      val paymentHash1 = ByteVector32.Zeroes
      val cltvExpiry1 = CltvExpiry(123)
      val paymentHash2 = ByteVector32(ByteVector.fill(32)(1))
      val cltvExpiry2 = CltvExpiry(656)

      intercept[SQLException](db.addHtlcInfo(channel.channelId, commitNumber, paymentHash1, cltvExpiry1)) // no related channel

      assert(db.listLocalChannels().toSet === Set.empty)
      db.addOrUpdateChannel(channel)
      db.addOrUpdateChannel(channel)
      assert(db.listLocalChannels() === List(channel))

      assert(db.listHtlcInfos(channel.channelId, commitNumber).toList == Nil)
      db.addHtlcInfo(channel.channelId, commitNumber, paymentHash1, cltvExpiry1)
      db.addHtlcInfo(channel.channelId, commitNumber, paymentHash2, cltvExpiry2)
      assert(db.listHtlcInfos(channel.channelId, commitNumber).toList.toSet == Set((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))
      assert(db.listHtlcInfos(channel.channelId, 43).toList == Nil)

      db.removeChannel(channel.channelId)
      assert(db.listLocalChannels() === Nil)
      assert(db.listHtlcInfos(channel.channelId, commitNumber).toList == Nil)
    }
  }

  test("channel metadata") {
    forAllDbs { dbs =>
      val db = dbs.channels()
      val connection = dbs.connection

      val channel1 = ChannelCodecsSpec.normal
      val channel2 = channel1.modify(_.commitments.channelId).setTo(randomBytes32)

      def getTimestamp(channelId: ByteVector32, columnName: String): Option[Long] = {
        using(connection.prepareStatement(s"SELECT $columnName FROM local_channels WHERE channel_id=?")) { statement =>
          // data type differs depending on underlying database system
          dbs match {
            case _: TestPgDatabases => statement.setString(1, channelId.toHex)
            case _: TestSqliteDatabases => statement.setBytes(1, channelId.toArray)
          }
          val rs = statement.executeQuery()
          rs.next()
          rs.getLongNullable(columnName)
        }
      }

      // first we add channels
      db.addOrUpdateChannel(channel1)
      db.addOrUpdateChannel(channel2)

      // make sure initially all metadata are empty
      assert(getTimestamp(channel1.channelId, "created_timestamp").isEmpty)
      assert(getTimestamp(channel1.channelId, "last_payment_sent_timestamp").isEmpty)
      assert(getTimestamp(channel1.channelId, "last_payment_received_timestamp").isEmpty)
      assert(getTimestamp(channel1.channelId, "last_connected_timestamp").isEmpty)
      assert(getTimestamp(channel1.channelId, "closed_timestamp").isEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Created)
      assert(getTimestamp(channel1.channelId, "created_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.PaymentSent)
      assert(getTimestamp(channel1.channelId, "last_payment_sent_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.PaymentReceived)
      assert(getTimestamp(channel1.channelId, "last_payment_received_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Connected)
      assert(getTimestamp(channel1.channelId, "last_connected_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Closed(null))
      assert(getTimestamp(channel1.channelId, "closed_timestamp").nonEmpty)

      // make sure all metadata are still empty for channel 2
      assert(getTimestamp(channel2.channelId, "created_timestamp").isEmpty)
      assert(getTimestamp(channel2.channelId, "last_payment_sent_timestamp").isEmpty)
      assert(getTimestamp(channel2.channelId, "last_payment_received_timestamp").isEmpty)
      assert(getTimestamp(channel2.channelId, "last_connected_timestamp").isEmpty)
      assert(getTimestamp(channel2.channelId, "closed_timestamp").isEmpty)
    }
  }

  test("migrate channel database v1 -> v3") {
    forAllDbs {
      case _: TestPgDatabases => // no migration
      case dbs: TestSqliteDatabases =>
        val sqlite = dbs.connection

        // create a v1 channels database
        using(sqlite.createStatement()) { statement =>
          SqliteUtils.getVersion(statement, "channels", 1)
          statement.execute("PRAGMA foreign_keys = ON")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        }

        // insert 1 row
        val channel = ChannelCodecsSpec.normal
        val data = stateDataCodec.encode(channel).require.toByteArray
        using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?)")) { statement =>
          statement.setBytes(1, channel.channelId.toArray)
          statement.setBytes(2, data)
          statement.executeUpdate()
        }

        // check that db migration works
        val db = new SqliteChannelsDb(sqlite)
        using(sqlite.createStatement()) { statement =>
          assert(SqliteUtils.getVersion(statement, "channels", 1) == 3) // version changed from 1 -> 3
        }
        assert(db.listLocalChannels() === List(channel))
        db.updateChannelMeta(channel.channelId, ChannelEvent.EventType.Created) // this call must not fail
    }
  }

  test("migrate channel database v2 -> v3") {
    forAllDbs {
      case dbs: TestPgDatabases =>
        val pg = dbs.connection

        // create a v2 channels database
        using(pg.createStatement()) { statement =>
          PgUtils.getVersion(statement, "channels", 2)
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id TEXT NOT NULL, commitment_number TEXT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        }

        // insert 1 row
        val channel = ChannelCodecsSpec.normal
        val data = stateDataCodec.encode(channel).require.toByteArray
        using(pg.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed) VALUES (?, ?, ?)")) { statement =>
          statement.setString(1, channel.channelId.toHex)
          statement.setBytes(2, data)
          statement.setBoolean(3, false)
          statement.executeUpdate()
        }

        // check that db migration works
        val db = dbs.channels()
        using(pg.createStatement()) { statement =>
          assert(PgUtils.getVersion(statement, "channels", 2) == 3) // version changed from 2 -> 3
        }
        assert(db.listLocalChannels() === List(channel))
        db.updateChannelMeta(channel.channelId, ChannelEvent.EventType.Created) // this call must not fail

      case dbs: TestSqliteDatabases =>
        val sqlite = dbs.connection

        // create a v2 channels database
        using(sqlite.createStatement()) { statement =>
          SqliteUtils.getVersion(statement, "channels", 2)
          statement.execute("PRAGMA foreign_keys = ON")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT 0)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        }

        // insert 1 row
        val channel = ChannelCodecsSpec.normal
        val data = stateDataCodec.encode(channel).require.toByteArray
        using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, channel.channelId.toArray)
          statement.setBytes(2, data)
          statement.setBoolean(3, false)
          statement.executeUpdate()
        }

        // check that db migration works
        val db = dbs.channels()
        using(sqlite.createStatement()) { statement =>
          assert(SqliteUtils.getVersion(statement, "channels", 2) == 3) // version changed from 2 -> 3
        }
        assert(db.listLocalChannels() === List(channel))
        db.updateChannelMeta(channel.channelId, ChannelEvent.EventType.Created) // this call must not fail
    }
  }
}