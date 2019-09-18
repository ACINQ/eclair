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
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.{ChannelCodecs, ChannelCodecsSpec, ChannelUpdate}
import org.scalatest.FunSuite

import scala.compat.Platform
import scala.concurrent.duration._


class SqliteAuditDbSpec extends FunSuite {

  import SqliteAuditDbSpec._

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqliteAuditDb(sqlite)
    val db2 = new SqliteAuditDb(sqlite)
  }

  test("add/list events") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteAuditDb(sqlite)

    val e1 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, 42000 msat, 1000 msat, randomBytes32, randomBytes32, Seq(Hop(carol, dave, channelUpdate2)))
    val e2 = PaymentReceived(42000 msat, randomBytes32)
    val e3 = PaymentRelayed(42000 msat, 1000 msat, randomBytes32, randomBytes32, randomBytes32)
    val e4 = NetworkFeePaid(null, randomKey.publicKey, randomBytes32, Transaction(0, Seq.empty, Seq.empty, 0), 42 sat, "mutual")
    val e5 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, 42000 msat, 1000 msat, randomBytes32, randomBytes32, Seq(Hop(alice, bob, channelUpdate1), Hop(bob, carol, channelUpdate2)), timestamp = 0)
    val e6 = PaymentSent(ChannelCodecs.UNKNOWN_UUID, 42000 msat, 1000 msat, randomBytes32, randomBytes32, Nil, timestamp = (Platform.currentTime.milliseconds + 10.minutes).toMillis)
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

    assert(db.listSent(from = 0L, to = (Platform.currentTime.milliseconds + 15.minute).toMillis).toSet === Set(e1.copy(route = Nil), e5.copy(route = Nil), e6))
    assert(db.listSent(from = 100000L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).toList === List(e1.copy(route = Nil)))
    assert(db.listReceived(from = 0L, to = (Platform.currentTime.milliseconds + 1.minute).toMillis).toList === List(e2))
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

  test("handle migration version 1 -> 4") {

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
      assert(getVersion(statement, "audit", 4) == 1) // we expect version 1
    }

    val ps = PaymentSent(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32, randomBytes32, Seq(Hop(alice, bob, channelUpdate1)))
    val ps1 = PaymentSent(UUID.randomUUID(), 42001 msat, 1001 msat, randomBytes32, randomBytes32, Seq(Hop(alice, bob, channelUpdate1), Hop(bob, carol, channelUpdate2)))
    val ps2 = PaymentSent(UUID.randomUUID(), 42002 msat, 1002 msat, randomBytes32, randomBytes32, Nil)
    val pr = PaymentReceived(561 msat, randomBytes32)
    val pr1 = PaymentReceived(1105 msat, randomBytes32)
    val e1 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), isFatal = true)
    val e2 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), isFatal = true)

    // Changes to the 'sent' table between versions 1 and 4:
    //  - the 'id' column was added
    //  - the 'toChannelId' column was removed
    using(connection.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, ps.amount.toLong)
      statement.setLong(2, ps.feesPaid.toLong)
      statement.setBytes(3, ps.paymentHash.toArray)
      statement.setBytes(4, ps.paymentPreimage.toArray)
      statement.setBytes(5, randomBytes32.toArray) // toChannelId
      statement.setLong(6, ps.timestamp)
      statement.executeUpdate()
    }

    // Changes to the 'received' table between versions 1 and 4:
    //  - the 'fromChannelId' column was removed
    using(connection.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
      statement.setLong(1, pr.amount.toLong)
      statement.setBytes(2, pr.paymentHash.toArray)
      statement.setBytes(3, randomBytes32.toArray) // fromChannelId
      statement.setLong(4, pr.timestamp)
      statement.executeUpdate()
    }

    val migratedDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 4) // version changed from 1 -> 4
    }

    // existing rows in the 'sent' table will use id=00000000-0000-0000-0000-000000000000 as default
    assert(migratedDb.listSent(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(ps.copy(id = ChannelCodecs.UNKNOWN_UUID, route = Nil)))
    // existing rows in the 'received' table will not contain a fromChannelId anymore
    assert(migratedDb.listReceived(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(pr))

    val postMigrationDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 4) // version 4
    }

    postMigrationDb.add(ps1)
    postMigrationDb.add(ps2)
    postMigrationDb.add(e1)
    postMigrationDb.add(e2)
    postMigrationDb.add(pr1)

    // the old 'sent' record will have the UNKNOWN_UUID and an empty route but the new ones will have their actual id
    assert(postMigrationDb.listSent(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(ps.copy(id = ChannelCodecs.UNKNOWN_UUID, route = Nil), ps1.copy(route = Nil), ps2.copy(route = Nil)))
    assert(postMigrationDb.listReceived(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(pr, pr1))
  }

  test("handle migration version 2 -> 4") {

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
      assert(getVersion(statement, "audit", 4) == 2) // version 2 is deployed now
    }

    val e1 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, LocalError(new RuntimeException("oops")), isFatal = true)
    val e2 = ChannelErrorOccurred(null, randomBytes32, randomKey.publicKey, null, RemoteError(wire.Error(randomBytes32, "remote oops")), isFatal = true)

    val migratedDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 4) // version changed from 2 -> 4
    }

    migratedDb.add(e1)

    val postMigrationDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 4) // version 4
    }

    postMigrationDb.add(e2)
  }

  test("handle migration version 3 -> 4") {
    val connection = TestConstants.sqliteInMemory()

    // simulate existing previous version db
    using(connection.createStatement()) { statement =>
      getVersion(statement, "audit", 3)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL, id BLOB NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event STRING NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name STRING NOT NULL, error_message STRING NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

      statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 3) // version 3 is deployed now
    }

    val ps = PaymentSent(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32, randomBytes32, Seq(Hop(alice, bob, channelUpdate1)))
    val ps1 = PaymentSent(UUID.randomUUID(), 42001 msat, 1001 msat, randomBytes32, randomBytes32, Seq(Hop(alice, bob, channelUpdate1), Hop(bob, carol, channelUpdate2)))
    val ps2 = PaymentSent(UUID.randomUUID(), 42002 msat, 1002 msat, randomBytes32, randomBytes32, Nil)
    val pr = PaymentReceived(561 msat, randomBytes32)
    val pr1 = PaymentReceived(1105 msat, randomBytes32)

    // Changes to the 'sent' table between versions 3 and 4:
    //  - the 'toChannelId' column was removed
    using(connection.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, ps.amount.toLong)
      statement.setLong(2, ps.feesPaid.toLong)
      statement.setBytes(3, ps.paymentHash.toArray)
      statement.setBytes(4, ps.paymentPreimage.toArray)
      statement.setBytes(5, randomBytes32.toArray) // toChannelId
      statement.setLong(6, ps.timestamp)
      statement.setBytes(7, ps.id.toString.getBytes)
      statement.executeUpdate()
    }

    // Changes to the 'received' table between versions 3 and 4:
    //  - the 'fromChannelId' column was removed
    using(connection.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
      statement.setLong(1, pr.amount.toLong)
      statement.setBytes(2, pr.paymentHash.toArray)
      statement.setBytes(3, randomBytes32.toArray) // fromChannelId
      statement.setLong(4, pr.timestamp)
      statement.executeUpdate()
    }

    val migratedDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 4) // version changed from 3 -> 4
    }

    // existing rows in the 'sent' table will use route=NULL as default
    assert(migratedDb.listSent(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(ps.copy(route = Nil)))
    // existing rows in the 'received' table will not contain a fromChannelId anymore
    assert(migratedDb.listReceived(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(pr))

    val postMigrationDb = new SqliteAuditDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "audit", 4) == 4) // version 4
    }

    postMigrationDb.add(ps1)
    postMigrationDb.add(ps2)
    postMigrationDb.add(pr1)

    // the old 'sent' record will have the UNKNOWN_UUID and an empty route but the new ones will have their actual id
    assert(postMigrationDb.listSent(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(ps.copy(route = Nil), ps1.copy(route = Nil), ps2.copy(route = Nil)))
    assert(postMigrationDb.listReceived(0, (Platform.currentTime.milliseconds + 1.minute).toMillis) === Seq(pr, pr1))
  }

}

object SqliteAuditDbSpec {

  val (alice, bob, carol, dave) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
  val channelUpdate1 = ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(561), 0, 0, 0, CltvExpiryDelta(144), 100 msat, 10 msat, 1000, None)
  val channelUpdate2 = ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(1105), 0, 0, 0, CltvExpiryDelta(9), 1000 msat, 15 msat, 100, None)

}