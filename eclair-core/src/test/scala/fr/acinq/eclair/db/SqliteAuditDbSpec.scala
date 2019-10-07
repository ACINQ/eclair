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

import java.util.UUID

import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Channel.{LocalError, RemoteError}
import fr.acinq.eclair.channel.{AvailableBalanceChanged, ChannelErrorOccurred, NetworkFeePaid}
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, using}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.{ChannelCodecs, ChannelCodecsSpec}
import org.scalatest.FunSuite

import scala.compat.Platform
import scala.concurrent.duration._


class SqliteAuditDbSpec extends FunSuite {

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqliteAuditDb(sqlite)
    val db2 = new SqliteAuditDb(sqlite)
  }

  test("add/list events") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteAuditDb(sqlite)

    val e1 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, randomBytes32, randomBytes32, PaymentSent.PartialPayment(ChannelCodecs.UNKNOWN_UUID, 42000 msat, 1000 msat, randomBytes32, None) :: Nil)
    val pp2a = PaymentReceived.PartialPayment(42000 msat, randomBytes32)
    val pp2b = PaymentReceived.PartialPayment(42100 msat, randomBytes32)
    val e2 = PaymentReceived(randomBytes32, pp2a :: pp2b :: Nil)
    val e3 = PaymentRelayed(42000 msat, 1000 msat, randomBytes32, randomBytes32, randomBytes32)
    val e4 = NetworkFeePaid(null, randomKey.publicKey, randomBytes32, Transaction(0, Seq.empty, Seq.empty, 0), 42 sat, "mutual")
    val pp5a = PaymentSent.PartialPayment(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32, None, timestamp = 0)
    val pp5b = PaymentSent.PartialPayment(UUID.randomUUID(), 42100 msat, 900 msat, randomBytes32, None, timestamp = 1)
    val e5 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, randomBytes32, randomBytes32, pp5a :: pp5b :: Nil)
    val pp6 = PaymentSent.PartialPayment(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32, None, timestamp = (Platform.currentTime.milliseconds + 10.minutes).toMillis)
    val e6 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, randomBytes32, randomBytes32, pp6 :: Nil)
    val e7 = AvailableBalanceChanged(null, randomBytes32, ShortChannelId(500000, 42, 1), 456123000 msat, ChannelCodecsSpec.commitments)
    val e8 = ChannelLifecycleEvent(randomBytes32, randomKey.publicKey, 456123000 sat, isFunder = true, isPrivate = false, "mutual")
    val e9 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), isFatal = true)
    val e10 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), isFatal = true)

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

    assert(db.listSent(from = 0L, to = (Platform.currentTime.milliseconds + 15.minute).toMillis).toSet === Set(e1, e5.copy(id = pp5a.id, parts = pp5a :: Nil), e5.copy(id = pp5b.id, parts = pp5b :: Nil), e6.copy(id = pp6.id)))
    assert(db.listSent(from = 100000L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).toList === List(e1))
    assert(db.listReceived(from = 0L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).toList === List(e2.copy(parts = pp2a :: Nil), e2.copy(parts = pp2b :: Nil)))
    assert(db.listRelayed(from = 0L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).toList === List(e3))
    assert(db.listNetworkFees(from = 0L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).size === 1)
    assert(db.listNetworkFees(from = 0L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).head.txType === "mutual")
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

    db.add(PaymentRelayed(46000 msat, 44000 msat, randomBytes32, randomBytes32, c1))
    db.add(PaymentRelayed(41000 msat, 40000 msat, randomBytes32, randomBytes32, c1))
    db.add(PaymentRelayed(43000 msat, 42000 msat, randomBytes32, randomBytes32, c1))
    db.add(PaymentRelayed(42000 msat, 40000 msat, randomBytes32, randomBytes32, c2))

    db.add(NetworkFeePaid(null, n1, c1, Transaction(0, Seq.empty, Seq.empty, 0), 100 sat, "funding"))
    db.add(NetworkFeePaid(null, n2, c2, Transaction(0, Seq.empty, Seq.empty, 0), 200 sat, "funding"))
    db.add(NetworkFeePaid(null, n2, c2, Transaction(0, Seq.empty, Seq.empty, 0), 300 sat, "mutual"))
    db.add(NetworkFeePaid(null, n3, c3, Transaction(0, Seq.empty, Seq.empty, 0), 400 sat, "funding"))

    assert(db.stats.toSet === Set(
      Stats(channelId = c1, avgPaymentAmount = 42 sat, paymentCount = 3, relayFee = 4 sat, networkFee = 100 sat),
      Stats(channelId = c2, avgPaymentAmount = 40 sat, paymentCount = 1, relayFee = 2 sat, networkFee = 500 sat),
      Stats(channelId = c3, avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 400 sat)
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

    val ps = PaymentSent(UUID.randomUUID(), randomBytes32, randomBytes32, PaymentSent.PartialPayment(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32, None) :: Nil)
    val pp1 = PaymentSent.PartialPayment(UUID.randomUUID(), 42001 msat, 1001 msat, randomBytes32, None)
    val pp2 = PaymentSent.PartialPayment(UUID.randomUUID(), 42002 msat, 1002 msat, randomBytes32, None)
    val ps1 = PaymentSent(UUID.randomUUID(), randomBytes32, randomBytes32, pp1 :: pp2 :: Nil)
    val e1 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), isFatal = true)
    val e2 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), isFatal = true)

    // add a row (no ID on sent)
    using(connection.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, ps.amount.toLong)
      statement.setLong(2, ps.feesPaid.toLong)
      statement.setBytes(3, ps.paymentHash.toArray)
      statement.setBytes(4, ps.paymentPreimage.toArray)
      statement.setBytes(5, ps.parts.head.toChannelId.toArray)
      statement.setLong(6, ps.timestamp)
      statement.executeUpdate()
    }

    val migratedDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 3) // version changed from 1 -> 3
    }

    // existing rows in the 'sent' table will use id=00000000-0000-0000-0000-000000000000 as default
    assert(migratedDb.listSent(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(ps.copy(id = ChannelCodecs.UNKNOWN_UUID, parts = Seq(ps.parts.head.copy(id = ChannelCodecs.UNKNOWN_UUID)))))

    val postMigrationDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 3) == 3) // version 3
    }

    postMigrationDb.add(ps1)
    postMigrationDb.add(e1)
    postMigrationDb.add(e2)

    // the old record will have the UNKNOWN_UUID but the new ones will have their actual id
    assert(postMigrationDb.listSent(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(
      ps.copy(id = ChannelCodecs.UNKNOWN_UUID, parts = Seq(ps.parts.head.copy(id = ChannelCodecs.UNKNOWN_UUID))),
      ps1.copy(id = pp1.id, parts = pp1 :: Nil),
      ps1.copy(id = pp2.id, parts = pp2 :: Nil)))
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

    val e1 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), isFatal = true)
    val e2 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), isFatal = true)

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
