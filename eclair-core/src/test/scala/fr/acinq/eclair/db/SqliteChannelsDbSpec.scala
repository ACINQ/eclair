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

import java.sql.SQLException

import fr.acinq.bitcoin.scala.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.TestConstants.{TestSqliteDatabases, forAllDbs}
import fr.acinq.eclair.db.sqlite.SqliteChannelsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, using}
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec
import fr.acinq.eclair.wire.ChannelCodecsSpec
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

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

  test("migrate channel database v1 -> v2") {
    forAllDbs {
      case dbs: TestSqliteDatabases =>
        val sqlite = dbs.connection

        // create a v1 channels database
        using(sqlite.createStatement()) { statement =>
          getVersion(statement, "channels", 1)
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
          assert(getVersion(statement, "channels", 1) == 2) // version changed from 1 -> 2
        }
        assert(db.listLocalChannels() === List(channel))
    }
  }
}