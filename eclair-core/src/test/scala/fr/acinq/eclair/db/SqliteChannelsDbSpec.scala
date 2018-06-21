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
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.db.sqlite.{SqliteChannelsDb, SqlitePendingRelayDb}
import grizzled.slf4j.Logging
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import org.scalatest.junit.JUnitRunner
import org.sqlite.SQLiteException

@RunWith(classOf[JUnitRunner])
class SqliteChannelsDbSpec extends FunSuite with BeforeAndAfterAll with Logging {

  val dbConfig = TestConstants.eclairDb
  val db = new SqliteChannelsDb(dbConfig)
  val relayDb = new SqlitePendingRelayDb(TestConstants.networkDb) // needed by db.removeChannel

  override def beforeAll(): Unit = {
    db.createTables
  }

  test("init sqlite 2 times in a row") {
    val db1 = new SqliteChannelsDb(dbConfig)
    val db2 = new SqliteChannelsDb(dbConfig)
    db1.createTables
    db2.createTables
  }

  test("add/remove/list channels") {
    val channel = ChannelStateSpec.normal

    val commitNumber = 42
    val paymentHash1 = BinaryData("42" * 300)
    val cltvExpiry1 = 123
    val paymentHash2 = BinaryData("43" * 300)
    val cltvExpiry2 = 656

    intercept[SQLiteException](db.addOrUpdateHtlcInfo(channel.channelId, commitNumber,
      paymentHash1, cltvExpiry1)) // no related channel

    assert(db.listChannels().toSet === Set.empty)
    db.addOrUpdateChannel(channel)
    db.addOrUpdateChannel(channel)
    assert(db.listChannels() === List(channel))

    assert(db.listHtlcHtlcInfos(channel.channelId, commitNumber).toList == Nil)
    db.addOrUpdateHtlcInfo(channel.channelId, commitNumber, paymentHash1, cltvExpiry1)
    db.addOrUpdateHtlcInfo(channel.channelId, commitNumber, paymentHash2, cltvExpiry2)
    assert(db.listHtlcHtlcInfos(channel.channelId, commitNumber).toList == List((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))
    assert(db.listHtlcHtlcInfos(channel.channelId, 43).toList == Nil)

    db.removeChannel(channel.channelId)
    assert(db.listChannels() === Nil)
    assert(db.listHtlcHtlcInfos(channel.channelId, commitNumber).toList == Nil)
  }

  override def afterAll(): Unit = {
    db.dropTables
    relayDb.dropTables
  }
}
