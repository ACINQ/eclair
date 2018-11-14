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

package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.db.sqlite.{SqliteChannelsDb, SqlitePendingRelayDb}
import org.scalatest.FunSuite
import org.sqlite.SQLiteException


class SqliteChannelsDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqliteChannelsDb(sqlite)
    val db2 = new SqliteChannelsDb(sqlite)
  }

  test("add/remove/list channels") {
    val sqlite = inmem
    val db = new SqliteChannelsDb(sqlite)
    new SqlitePendingRelayDb(sqlite) // needed by db.removeChannel

    val channel = ChannelStateSpec.normal

    val commitNumber = 42
    val paymentHash1 = BinaryData("42" * 300)
    val cltvExpiry1 = 123
    val paymentHash2 = BinaryData("43" * 300)
    val cltvExpiry2 = 656

    intercept[SQLiteException](db.addOrUpdateHtlcInfo(channel.channelId, commitNumber, paymentHash1, cltvExpiry1)) // no related channel

    assert(db.listChannels().toSet === Set.empty)
    db.addOrUpdateChannel(channel)
    db.addOrUpdateChannel(channel)
    assert(db.listChannels() === List(channel))

    assert(db.listHtlcInfos(channel.channelId, commitNumber).toList == Nil)
    db.addOrUpdateHtlcInfo(channel.channelId, commitNumber, paymentHash1, cltvExpiry1)
    db.addOrUpdateHtlcInfo(channel.channelId, commitNumber, paymentHash2, cltvExpiry2)
    assert(db.listHtlcInfos(channel.channelId, commitNumber).toList == List((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))
    assert(db.listHtlcInfos(channel.channelId, 43).toList == Nil)

    db.removeChannel(channel.channelId)
    assert(db.listChannels() === Nil)
    assert(db.listHtlcInfos(channel.channelId, commitNumber).toList == Nil)
  }

}
