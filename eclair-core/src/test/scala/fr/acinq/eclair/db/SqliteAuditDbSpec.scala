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

import java.util.UUID

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi, Transaction}
import fr.acinq.eclair.channel.Channel.{LocalError, RemoteError}
import fr.acinq.eclair.channel.{AvailableBalanceChanged, ChannelErrorOccured, NetworkFeePaid}
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, using}
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.wire.ChannelCodecs
import fr.acinq.eclair._
import org.scalatest.FunSuite
import concurrent.duration._
import scala.compat.Platform


class SqliteAuditDbSpec extends FunSuite {

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqliteAuditDb(sqlite)
    val db2 = new SqliteAuditDb(sqlite)
  }

  test("add/list events") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteAuditDb(sqlite)

    val e1 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, MilliSatoshi(42000), MilliSatoshi(1000), randomBytes32, randomBytes32, randomBytes32)
    val e2 = PaymentReceived(MilliSatoshi(42000), randomBytes32, randomBytes32)
    val e3 = PaymentRelayed(MilliSatoshi(42000), MilliSatoshi(1000), randomBytes32, randomBytes32, randomBytes32)
    val e4 = NetworkFeePaid(null, randomKey.publicKey, randomBytes32, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(42), "mutual")
    val e5 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, MilliSatoshi(42000), MilliSatoshi(1000), randomBytes32, randomBytes32, randomBytes32, timestamp = 0)
    val e6 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, MilliSatoshi(42000), MilliSatoshi(1000), randomBytes32, randomBytes32, randomBytes32, timestamp = Platform.currentTime.milliseconds.toSeconds * 2)
    val e7 = AvailableBalanceChanged(null, randomBytes32, ShortChannelId(500000, 42, 1), 456123000, ChannelStateSpec.commitments)
    val e8 = ChannelLifecycleEvent(randomBytes32, randomKey.publicKey, 456123000, true, false, "mutual")
    val e9 = ChannelErrorOccured(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), true)
    val e10 = ChannelErrorOccured(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), true)

    db.add(e1)
    db.add(e2)
    db.add(e3)
    db.add(e4)
    db.add(e5)
    db.add(e6)
    db.add(e7)
    db.add(e8)
    db.add(e9)
    db.add(e10)

    assert(db.listSent(from = 0L, to = Long.MaxValue).toSet === Set(e1, e5, e6))
    assert(db.listSent(from = 100000L, to = Platform.currentTime + 1).toList === List(e1))
    assert(db.listReceived(from = 0L, to = Long.MaxValue).toList === List(e2))
    assert(db.listRelayed(from = 0L, to = Long.MaxValue).toList === List(e3))
    assert(db.listNetworkFees(from = 0L, to = Long.MaxValue).size === 1)
    assert(db.listNetworkFees(from = 0L, to = Long.MaxValue).head.txType === "mutual")
  }

  test("stats") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteAuditDb(sqlite)

    val n1 = randomKey.publicKey
    val n2 = randomKey.publicKey
    val n3 = randomKey.publicKey

    val c1 = randomBytes32
    val c2 = randomBytes32
    val c3 = randomBytes32

    db.add(PaymentRelayed(MilliSatoshi(46000), MilliSatoshi(44000), randomBytes32, randomBytes32, c1))
    db.add(PaymentRelayed(MilliSatoshi(41000), MilliSatoshi(40000), randomBytes32, randomBytes32, c1))
    db.add(PaymentRelayed(MilliSatoshi(43000), MilliSatoshi(42000), randomBytes32, randomBytes32, c1))
    db.add(PaymentRelayed(MilliSatoshi(42000), MilliSatoshi(40000), randomBytes32, randomBytes32, c2))

    db.add(NetworkFeePaid(null, n1, c1, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(100), "funding"))
    db.add(NetworkFeePaid(null, n2, c2, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(200), "funding"))
    db.add(NetworkFeePaid(null, n2, c2, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(300), "mutual"))
    db.add(NetworkFeePaid(null, n3, c3, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(400), "funding"))

    assert(db.stats.toSet === Set(
      Stats(channelId = c1, avgPaymentAmountSatoshi = 42, paymentCount = 3, relayFeeSatoshi = 4, networkFeeSatoshi = 100),
      Stats(channelId = c2, avgPaymentAmountSatoshi = 40, paymentCount = 1, relayFeeSatoshi = 2, networkFeeSatoshi = 500),
    Stats(channelId = c3, avgPaymentAmountSatoshi = 0, paymentCount = 0, relayFeeSatoshi = 0, networkFeeSatoshi = 400)
    ))
  }

  test("handle migration version 1 -> 3") {

    val connection = TestConstants.sqliteInMemory()

    // simulate existing previous version db
    using(connection.createStatement()) { statement =>
      getVersion(statement, "audit", 1)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event STRING NOT NULL, timestamp INTEGER NOT NULL)")

      statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 1) // we expect version 1
    }

    val ps = PaymentSent(UUID.randomUUID(), MilliSatoshi(42000), MilliSatoshi(1000), randomBytes32, randomBytes32, randomBytes32)
    val ps1 = PaymentSent(UUID.randomUUID(), MilliSatoshi(42001), MilliSatoshi(1001), randomBytes32, randomBytes32, randomBytes32)
    val ps2 = PaymentSent(UUID.randomUUID(), MilliSatoshi(42002), MilliSatoshi(1002), randomBytes32, randomBytes32, randomBytes32)
    val e1 = ChannelErrorOccured(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), true)
    val e2 = ChannelErrorOccured(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), true)

    // add a row (no ID on sent)
    using(connection.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, ps.amount.toLong)
      statement.setLong(2, ps.feesPaid.toLong)
      statement.setBytes(3, ps.paymentHash.toArray)
      statement.setBytes(4, ps.paymentPreimage.toArray)
      statement.setBytes(5, ps.toChannelId.toArray)
      statement.setLong(6, ps.timestamp.seconds.toMillis)
      statement.executeUpdate()
    }

    val migratedDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 3) // version changed from 1 -> 3
    }

    // existing rows will use 00000000-0000-0000-0000-000000000000 as default
    assert(migratedDb.listSent(0, Long.MaxValue) == Seq(ps.copy(id = ChannelCodecs.UNKNOWN_UUID)))

    val postMigrationDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 3) // version 3
    }

    postMigrationDb.add(ps1)
    postMigrationDb.add(ps2)
    postMigrationDb.add(e1)
    postMigrationDb.add(e2)

    // the old record will have the UNKNOWN_UUID but the new ones will have their actual id
    assert(postMigrationDb.listSent(0, Long.MaxValue) == Seq(ps.copy(id = ChannelCodecs.UNKNOWN_UUID), ps1, ps2))
  }

  test("handle migration version 2 -> 3") {

    val connection = TestConstants.sqliteInMemory()

    // simulate existing previous version db
    using(connection.createStatement()) { statement =>
      getVersion(statement, "audit", 2)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL, id BLOB NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event STRING NOT NULL, timestamp INTEGER NOT NULL)")

      statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 2) // version 2 is deployed now
    }

    val e1 = ChannelErrorOccured(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), true)
    val e2 = ChannelErrorOccured(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), true)

    val migratedDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 3) // version changed from 2 -> 3
    }

    migratedDb.add(e1)

    val postMigrationDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 3) // version 3
    }

    postMigrationDb.add(e2)
  }

}
