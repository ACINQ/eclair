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

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.{ChannelHop, NodeHop}
import fr.acinq.eclair.wire.{ChannelUpdate, UnknownNextPeer}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, ShortChannelId, TestConstants, randomBytes32, randomBytes64, randomKey}
import org.scalatest.FunSuite

import scala.compat.Platform
import scala.concurrent.duration._

class SqlitePaymentsDbSpec extends FunSuite {

  import SqlitePaymentsDbSpec._

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqlitePaymentsDb(sqlite)
    val db2 = new SqlitePaymentsDb(sqlite)
  }

  test("handle version migration 1->4") {
    val connection = TestConstants.sqliteInMemory()

    using(connection.createStatement()) { statement =>
      getVersion(statement, "payments", 1)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS payments (payment_hash BLOB NOT NULL PRIMARY KEY, amount_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 1) == 1) // version 1 is deployed now
    }

    // Changes between version 1 and 2:
    //  - the monolithic payments table has been replaced by two tables, received_payments and sent_payments
    //  - old records from the payments table are ignored (not migrated to the new tables)
    using(connection.prepareStatement("INSERT INTO payments VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, paymentHash1.toArray)
      statement.setLong(2, (123 msat).toLong)
      statement.setLong(3, 1000) // received_at
      statement.executeUpdate()
    }

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 1) == 4) // version changed from 1 -> 4
    }

    // the existing received payment can NOT be queried anymore
    assert(preMigrationDb.getIncomingPayment(paymentHash1).isEmpty)

    // add a few rows
    val ps1 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, paymentHash1, PaymentType.Standard, 12345 msat, 12345 msat, alice, 1000, None, OutgoingPaymentStatus.Pending)
    val i1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(500 msat), paymentHash1, davePriv, "Some invoice", expirySeconds = None, timestamp = 1)
    val pr1 = IncomingPayment(i1, preimage1, PaymentType.Standard, i1.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(550 msat, 1100))

    preMigrationDb.addOutgoingPayment(ps1)
    preMigrationDb.addIncomingPayment(i1, preimage1)
    preMigrationDb.receiveIncomingPayment(i1.paymentHash, 550 msat, 1100)

    assert(preMigrationDb.listIncomingPayments(1, 1500) === Seq(pr1))
    assert(preMigrationDb.listOutgoingPayments(1, 1500) === Seq(ps1))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 4) == 4) // version still to 4
    }

    assert(postMigrationDb.listIncomingPayments(1, 1500) === Seq(pr1))
    assert(postMigrationDb.listOutgoingPayments(1, 1500) === Seq(ps1))
  }

  test("handle version migration 2->4") {
    val connection = TestConstants.sqliteInMemory()

    using(connection.createStatement()) { statement =>
      getVersion(statement, "payments", 2)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER, received_at INTEGER)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, preimage BLOB, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, completed_at INTEGER, status VARCHAR NOT NULL)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON sent_payments(payment_hash)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 2) == 2) // version 2 is deployed now
    }

    // Insert a bunch of old version 2 rows.
    val id1 = UUID.randomUUID()
    val id2 = UUID.randomUUID()
    val id3 = UUID.randomUUID()
    val ps1 = OutgoingPayment(id1, id1, None, randomBytes32, PaymentType.Standard, 561 msat, 561 msat, PrivateKey(ByteVector32.One).publicKey, 1000, None, OutgoingPaymentStatus.Pending)
    val ps2 = OutgoingPayment(id2, id2, None, randomBytes32, PaymentType.Standard, 1105 msat, 1105 msat, PrivateKey(ByteVector32.One).publicKey, 1010, None, OutgoingPaymentStatus.Failed(Nil, 1050))
    val ps3 = OutgoingPayment(id3, id3, None, paymentHash1, PaymentType.Standard, 1729 msat, 1729 msat, PrivateKey(ByteVector32.One).publicKey, 1040, None, OutgoingPaymentStatus.Succeeded(preimage1, 0 msat, Nil, 1060))
    val i1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash1, davePriv, "Some invoice", expirySeconds = None, timestamp = 1)
    val pr1 = IncomingPayment(i1, preimage1, PaymentType.Standard, i1.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(12345678 msat, 1090))
    val i2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash2, carolPriv, "Another invoice", expirySeconds = Some(30), timestamp = 1)
    val pr2 = IncomingPayment(i2, preimage2, PaymentType.Standard, i2.timestamp.seconds.toMillis, IncomingPaymentStatus.Expired)

    // Changes between version 2 and 3 to sent_payments:
    //  - removed the status column
    //  - added optional payment failures
    //  - added optional payment success details (fees paid and route)
    //  - added optional payment request
    //  - added target node ID
    //  - added externalID and parentID

    using(connection.prepareStatement("INSERT INTO sent_payments (id, payment_hash, amount_msat, created_at, status) VALUES (?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, ps1.id.toString)
      statement.setBytes(2, ps1.paymentHash.toArray)
      statement.setLong(3, ps1.amount.toLong)
      statement.setLong(4, ps1.createdAt)
      statement.setString(5, "PENDING")
      statement.executeUpdate()
    }

    using(connection.prepareStatement("INSERT INTO sent_payments (id, payment_hash, amount_msat, created_at, completed_at, status) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, ps2.id.toString)
      statement.setBytes(2, ps2.paymentHash.toArray)
      statement.setLong(3, ps2.amount.toLong)
      statement.setLong(4, ps2.createdAt)
      statement.setLong(5, ps2.status.asInstanceOf[OutgoingPaymentStatus.Failed].completedAt)
      statement.setString(6, "FAILED")
      statement.executeUpdate()
    }

    using(connection.prepareStatement("INSERT INTO sent_payments (id, payment_hash, preimage, amount_msat, created_at, completed_at, status) VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, ps3.id.toString)
      statement.setBytes(2, ps3.paymentHash.toArray)
      statement.setBytes(3, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].paymentPreimage.toArray)
      statement.setLong(4, ps3.amount.toLong)
      statement.setLong(5, ps3.createdAt)
      statement.setLong(6, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].completedAt)
      statement.setString(7, "SUCCEEDED")
      statement.executeUpdate()
    }

    // Changes between version 2 and 3 to received_payments:
    //  - renamed the preimage column
    //  - made expire_at not null

    using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, preimage, payment_request, received_msat, created_at, received_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, i1.paymentHash.toArray)
      statement.setBytes(2, pr1.paymentPreimage.toArray)
      statement.setString(3, PaymentRequest.write(i1))
      statement.setLong(4, pr1.status.asInstanceOf[IncomingPaymentStatus.Received].amount.toLong)
      statement.setLong(5, pr1.createdAt)
      statement.setLong(6, pr1.status.asInstanceOf[IncomingPaymentStatus.Received].receivedAt)
      statement.executeUpdate()
    }

    using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, preimage, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, i2.paymentHash.toArray)
      statement.setBytes(2, pr2.paymentPreimage.toArray)
      statement.setString(3, PaymentRequest.write(i2))
      statement.setLong(4, pr2.createdAt)
      statement.setLong(5, (i2.timestamp + i2.expiry.get).seconds.toMillis)
      statement.executeUpdate()
    }

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 2) == 4) // version changed from 2 -> 4
    }

    assert(preMigrationDb.getIncomingPayment(i1.paymentHash) === Some(pr1))
    assert(preMigrationDb.getIncomingPayment(i2.paymentHash) === Some(pr2))
    assert(preMigrationDb.listOutgoingPayments(1, 2000) === Seq(ps1, ps2, ps3))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 4) == 4) // version still to 4
    }

    val i3 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), paymentHash3, alicePriv, "invoice #3", expirySeconds = Some(30))
    val pr3 = IncomingPayment(i3, preimage3, PaymentType.Standard, i3.timestamp.seconds.toMillis, IncomingPaymentStatus.Pending)
    postMigrationDb.addIncomingPayment(i3, pr3.paymentPreimage)

    val ps4 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("1"), randomBytes32, PaymentType.Standard, 123 msat, 123 msat, alice, 1100, Some(i3), OutgoingPaymentStatus.Pending)
    val ps5 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("2"), randomBytes32, PaymentType.Standard, 456 msat, 456 msat, bob, 1150, Some(i2), OutgoingPaymentStatus.Succeeded(preimage1, 42 msat, Nil, 1180))
    val ps6 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("3"), randomBytes32, PaymentType.Standard, 789 msat, 789 msat, bob, 1250, None, OutgoingPaymentStatus.Failed(Nil, 1300))
    postMigrationDb.addOutgoingPayment(ps4)
    postMigrationDb.addOutgoingPayment(ps5.copy(status = OutgoingPaymentStatus.Pending))
    postMigrationDb.updateOutgoingPayment(PaymentSent(ps5.parentId, ps5.paymentHash, preimage1, ps5.amount, ps5.recipientNodeId, Seq(PaymentSent.PartialPayment(ps5.id, ps5.amount, 42 msat, randomBytes32, None, 1180))))
    postMigrationDb.addOutgoingPayment(ps6.copy(status = OutgoingPaymentStatus.Pending))
    postMigrationDb.updateOutgoingPayment(PaymentFailed(ps6.id, ps6.paymentHash, Nil, 1300))

    assert(postMigrationDb.listOutgoingPayments(1, 2000) === Seq(ps1, ps2, ps3, ps4, ps5, ps6))
    assert(postMigrationDb.listIncomingPayments(1, Platform.currentTime) === Seq(pr1, pr2, pr3))
    assert(postMigrationDb.listExpiredIncomingPayments(1, 2000) === Seq(pr2))
  }

  test("handle version migration 3->4") {
    val connection = TestConstants.sqliteInMemory()

    using(connection.createStatement()) { statement =>
      getVersion(statement, "payments", 3)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, target_node_id BLOB NOT NULL, created_at INTEGER NOT NULL, payment_request TEXT, completed_at INTEGER, payment_preimage BLOB, fees_msat INTEGER, payment_route BLOB, failures BLOB)")

      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_parent_id_idx ON sent_payments(parent_id)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_payment_hash_idx ON sent_payments(payment_hash)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_created_idx ON sent_payments(created_at)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_created_idx ON received_payments(created_at)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 3) == 3) // version 3 is deployed now
    }

    // Insert a bunch of old version 3 rows.
    val (id1, id2, id3) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    val parentId = UUID.randomUUID()
    val invoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(2834 msat), paymentHash1, bobPriv, "invoice #1", expirySeconds = Some(30))
    val ps1 = OutgoingPayment(id1, id1, Some("42"), randomBytes32, PaymentType.Standard, 561 msat, 561 msat, alice, 1000, None, OutgoingPaymentStatus.Failed(Seq(FailureSummary(FailureType.REMOTE, "no candy for you", List(HopSummary(hop_ab), HopSummary(hop_bc)))), 1020))
    val ps2 = OutgoingPayment(id2, parentId, Some("42"), paymentHash1, PaymentType.Standard, 1105 msat, 1105 msat, bob, 1010, Some(invoice1), OutgoingPaymentStatus.Pending)
    val ps3 = OutgoingPayment(id3, parentId, None, paymentHash1, PaymentType.Standard, 1729 msat, 1729 msat, bob, 1040, None, OutgoingPaymentStatus.Succeeded(preimage1, 10 msat, Seq(HopSummary(hop_ab), HopSummary(hop_bc)), 1060))

    using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, completed_at, failures) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, ps1.id.toString)
      statement.setString(2, ps1.parentId.toString)
      statement.setString(3, ps1.externalId.get.toString)
      statement.setBytes(4, ps1.paymentHash.toArray)
      statement.setLong(5, ps1.amount.toLong)
      statement.setBytes(6, ps1.recipientNodeId.value.toArray)
      statement.setLong(7, ps1.createdAt)
      statement.setLong(8, ps1.status.asInstanceOf[OutgoingPaymentStatus.Failed].completedAt)
      statement.setBytes(9, SqlitePaymentsDb.paymentFailuresCodec.encode(ps1.status.asInstanceOf[OutgoingPaymentStatus.Failed].failures.toList).require.toByteArray)
      statement.executeUpdate()
    }

    using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, ps2.id.toString)
      statement.setString(2, ps2.parentId.toString)
      statement.setString(3, ps2.externalId.get.toString)
      statement.setBytes(4, ps2.paymentHash.toArray)
      statement.setLong(5, ps2.amount.toLong)
      statement.setBytes(6, ps2.recipientNodeId.value.toArray)
      statement.setLong(7, ps2.createdAt)
      statement.setString(8, PaymentRequest.write(invoice1))
      statement.executeUpdate()
    }

    using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, payment_hash, amount_msat, target_node_id, created_at, completed_at, payment_preimage, fees_msat, payment_route) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, ps3.id.toString)
      statement.setString(2, ps3.parentId.toString)
      statement.setBytes(3, ps3.paymentHash.toArray)
      statement.setLong(4, ps3.amount.toLong)
      statement.setBytes(5, ps3.recipientNodeId.value.toArray)
      statement.setLong(6, ps3.createdAt)
      statement.setLong(7, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].completedAt)
      statement.setBytes(8, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].paymentPreimage.toArray)
      statement.setLong(9, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid.toLong)
      statement.setBytes(10, SqlitePaymentsDb.paymentRouteCodec.encode(ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].route.toList).require.toByteArray)
      statement.executeUpdate()
    }

    // Changes between version 3 and 4 to sent_payments:
    //  - added final amount column
    //  - added payment type column, with a default to "Standard"
    //  - renamed target_node_id -> recipient_node_id
    //  - re-ordered columns

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 3) == 4) // version changed from 3 -> 4
    }

    assert(preMigrationDb.getOutgoingPayment(id1) === Some(ps1))
    assert(preMigrationDb.listOutgoingPayments(parentId) === Seq(ps2, ps3))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 4) == 4) // version still to 4
    }

    val ps4 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, randomBytes32, PaymentType.SwapOut, 50 msat, 100 msat, carol, 1100, Some(invoice1), OutgoingPaymentStatus.Pending)
    postMigrationDb.addOutgoingPayment(ps4)
    postMigrationDb.updateOutgoingPayment(PaymentSent(parentId, paymentHash1, preimage1, ps2.recipientAmount, ps2.recipientNodeId, Seq(PaymentSent.PartialPayment(id2, ps2.amount, 15 msat, randomBytes32, Some(Seq(hop_ab)), 1105))))

    assert(postMigrationDb.listOutgoingPayments(1, 2000) === Seq(ps1, ps2.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 15 msat, Seq(HopSummary(hop_ab)), 1105)), ps3, ps4))
  }

  test("add/retrieve/update incoming payments") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqlitePaymentsDb(sqlite)

    // can't receive a payment without an invoice associated with it
    assertThrows[IllegalArgumentException](db.receiveIncomingPayment(randomBytes32, 12345678 msat))

    val expiredInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32, alicePriv, "invoice #1", timestamp = 1)
    val expiredInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32, bobPriv, "invoice #2", timestamp = 2, expirySeconds = Some(30))
    val expiredPayment1 = IncomingPayment(expiredInvoice1, randomBytes32, PaymentType.Standard, expiredInvoice1.timestamp.seconds.toMillis, IncomingPaymentStatus.Expired)
    val expiredPayment2 = IncomingPayment(expiredInvoice2, randomBytes32, PaymentType.Standard, expiredInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Expired)

    val pendingInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32, alicePriv, "invoice #3")
    val pendingInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32, bobPriv, "invoice #4", expirySeconds = Some(30))
    val pendingPayment1 = IncomingPayment(pendingInvoice1, randomBytes32, PaymentType.Standard, pendingInvoice1.timestamp.seconds.toMillis, IncomingPaymentStatus.Pending)
    val pendingPayment2 = IncomingPayment(pendingInvoice2, randomBytes32, PaymentType.SwapIn, pendingInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Pending)

    val paidInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32, alicePriv, "invoice #5")
    val paidInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32, bobPriv, "invoice #6", expirySeconds = Some(60))
    val receivedAt1 = Platform.currentTime + 1
    val receivedAt2 = Platform.currentTime + 2
    val payment1 = IncomingPayment(paidInvoice1, randomBytes32, PaymentType.Standard, paidInvoice1.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(561 msat, receivedAt2))
    val payment2 = IncomingPayment(paidInvoice2, randomBytes32, PaymentType.Standard, paidInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(1111 msat, receivedAt2))

    db.addIncomingPayment(pendingInvoice1, pendingPayment1.paymentPreimage)
    db.addIncomingPayment(pendingInvoice2, pendingPayment2.paymentPreimage, PaymentType.SwapIn)
    db.addIncomingPayment(expiredInvoice1, expiredPayment1.paymentPreimage)
    db.addIncomingPayment(expiredInvoice2, expiredPayment2.paymentPreimage)
    db.addIncomingPayment(paidInvoice1, payment1.paymentPreimage)
    db.addIncomingPayment(paidInvoice2, payment2.paymentPreimage)

    assert(db.getIncomingPayment(pendingInvoice1.paymentHash) === Some(pendingPayment1))
    assert(db.getIncomingPayment(expiredInvoice2.paymentHash) === Some(expiredPayment2))
    assert(db.getIncomingPayment(paidInvoice1.paymentHash) === Some(payment1.copy(status = IncomingPaymentStatus.Pending)))

    val now = Platform.currentTime
    assert(db.listIncomingPayments(0, now) === Seq(expiredPayment1, expiredPayment2, pendingPayment1, pendingPayment2, payment1.copy(status = IncomingPaymentStatus.Pending), payment2.copy(status = IncomingPaymentStatus.Pending)))
    assert(db.listExpiredIncomingPayments(0, now) === Seq(expiredPayment1, expiredPayment2))
    assert(db.listReceivedIncomingPayments(0, now) === Nil)
    assert(db.listPendingIncomingPayments(0, now) === Seq(pendingPayment1, pendingPayment2, payment1.copy(status = IncomingPaymentStatus.Pending), payment2.copy(status = IncomingPaymentStatus.Pending)))

    db.receiveIncomingPayment(paidInvoice1.paymentHash, 461 msat, receivedAt1)
    db.receiveIncomingPayment(paidInvoice1.paymentHash, 100 msat, receivedAt2) // adding another payment to this invoice should sum
    db.receiveIncomingPayment(paidInvoice2.paymentHash, 1111 msat, receivedAt2)

    assert(db.getIncomingPayment(paidInvoice1.paymentHash) === Some(payment1))
    assert(db.listIncomingPayments(0, now) === Seq(expiredPayment1, expiredPayment2, pendingPayment1, pendingPayment2, payment1, payment2))
    assert(db.listIncomingPayments(now - 60.seconds.toMillis, now) === Seq(pendingPayment1, pendingPayment2, payment1, payment2))
    assert(db.listPendingIncomingPayments(0, now) === Seq(pendingPayment1, pendingPayment2))
    assert(db.listReceivedIncomingPayments(0, now) === Seq(payment1, payment2))
  }

  test("add/retrieve/update outgoing payments") {
    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    val parentId = UUID.randomUUID()
    val i1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(123 msat), paymentHash1, davePriv, "Some invoice", expirySeconds = None, timestamp = 0)
    val s1 = OutgoingPayment(UUID.randomUUID(), parentId, None, paymentHash1, PaymentType.Standard, 123 msat, 600 msat, dave, 100, Some(i1), OutgoingPaymentStatus.Pending)
    val s2 = OutgoingPayment(UUID.randomUUID(), parentId, Some("1"), paymentHash1, PaymentType.SwapOut, 456 msat, 600 msat, dave, 200, None, OutgoingPaymentStatus.Pending)

    assert(db.listOutgoingPayments(0, Platform.currentTime).isEmpty)
    db.addOutgoingPayment(s1)
    db.addOutgoingPayment(s2)

    // can't add an outgoing payment in non-pending state
    assertThrows[IllegalArgumentException](db.addOutgoingPayment(s1.copy(status = OutgoingPaymentStatus.Succeeded(randomBytes32, 0 msat, Nil, 110))))

    assert(db.listOutgoingPayments(1, 300).toList == Seq(s1, s2))
    assert(db.listOutgoingPayments(1, 150).toList == Seq(s1))
    assert(db.listOutgoingPayments(150, 250).toList == Seq(s2))
    assert(db.getOutgoingPayment(s1.id) === Some(s1))
    assert(db.getOutgoingPayment(UUID.randomUUID()) === None)
    assert(db.listOutgoingPayments(s2.paymentHash) === Seq(s1, s2))
    assert(db.listOutgoingPayments(s1.id) === Nil)
    assert(db.listOutgoingPayments(parentId) === Seq(s1, s2))
    assert(db.listOutgoingPayments(ByteVector32.Zeroes) === Nil)

    val s3 = s2.copy(id = UUID.randomUUID(), amount = 789 msat, createdAt = 300)
    val s4 = s2.copy(id = UUID.randomUUID(), paymentType = PaymentType.Standard, createdAt = 300)
    db.addOutgoingPayment(s3)
    db.addOutgoingPayment(s4)

    db.updateOutgoingPayment(PaymentFailed(s3.id, s3.paymentHash, Nil, 310))
    val ss3 = s3.copy(status = OutgoingPaymentStatus.Failed(Nil, 310))
    assert(db.getOutgoingPayment(s3.id) === Some(ss3))
    db.updateOutgoingPayment(PaymentFailed(s4.id, s4.paymentHash, Seq(LocalFailure(new RuntimeException("woops")), RemoteFailure(Seq(hop_ab, hop_bc), Sphinx.DecryptedFailurePacket(carol, UnknownNextPeer))), 320))
    val ss4 = s4.copy(status = OutgoingPaymentStatus.Failed(Seq(FailureSummary(FailureType.LOCAL, "woops", Nil), FailureSummary(FailureType.REMOTE, "processing node does not know the next peer in the route", List(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, None)))), 320))
    assert(db.getOutgoingPayment(s4.id) === Some(ss4))

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentSent(parentId, s3.paymentHash, preimage1, s3.recipientAmount, s3.recipientNodeId, Seq(PaymentSent.PartialPayment(s3.id, s3.amount, 42 msat, randomBytes32, None)))))

    val paymentSent = PaymentSent(parentId, paymentHash1, preimage1, 600 msat, carol, Seq(
      PaymentSent.PartialPayment(s1.id, s1.amount, 15 msat, randomBytes32, None, 400),
      PaymentSent.PartialPayment(s2.id, s2.amount, 20 msat, randomBytes32, Some(Seq(hop_ab, hop_bc)), 410)
    ))
    val ss1 = s1.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 15 msat, Nil, 400))
    val ss2 = s2.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 20 msat, Seq(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, None)), 410))
    db.updateOutgoingPayment(paymentSent)
    assert(db.getOutgoingPayment(s1.id) === Some(ss1))
    assert(db.getOutgoingPayment(s2.id) === Some(ss2))
    assert(db.listOutgoingPayments(parentId) === Seq(ss1, ss2, ss3, ss4))

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentFailed(s1.id, s1.paymentHash, Nil)))
  }

  test("high level payments overview") {
    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    // -- feed db with incoming payments
    val expiredInvoice = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(123 msat), randomBytes32, alicePriv, "incoming #1", timestamp = 1)
    val expiredPayment = IncomingPayment(expiredInvoice, randomBytes32, PaymentType.Standard, 100, IncomingPaymentStatus.Expired)
    val pendingInvoice = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(456 msat), randomBytes32, alicePriv, "incoming #2")
    val pendingPayment = IncomingPayment(pendingInvoice, randomBytes32, PaymentType.Standard, 120, IncomingPaymentStatus.Pending)
    val paidInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(789 msat), randomBytes32, alicePriv, "incoming #3")
    val receivedAt1 = 150
    val receivedPayment1 = IncomingPayment(paidInvoice1, randomBytes32, PaymentType.Standard, 130, IncomingPaymentStatus.Received(561 msat, receivedAt1))
    val paidInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(888 msat), randomBytes32, alicePriv, "incoming #4")
    val receivedAt2 = 160
    val receivedPayment2 = IncomingPayment(paidInvoice2, randomBytes32, PaymentType.Standard, paidInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(889 msat, receivedAt2))
    db.addIncomingPayment(pendingInvoice, pendingPayment.paymentPreimage)
    db.addIncomingPayment(expiredInvoice, expiredPayment.paymentPreimage)
    db.addIncomingPayment(paidInvoice1, receivedPayment1.paymentPreimage)
    db.addIncomingPayment(paidInvoice2, receivedPayment2.paymentPreimage)
    db.receiveIncomingPayment(paidInvoice1.paymentHash, 461 msat, receivedAt1)
    db.receiveIncomingPayment(paidInvoice2.paymentHash, 666 msat, receivedAt2)

    // -- feed db with outgoing payments
    val parentId1 = UUID.randomUUID()
    val parentId2 = UUID.randomUUID()
    val invoice = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1337 msat), paymentHash1, davePriv, "outgoing #1", expirySeconds = None, timestamp = 0)

    // 1st attempt, pending -> failed
    val outgoing1 = OutgoingPayment(UUID.randomUUID(), parentId1, None, paymentHash1, PaymentType.Standard, 123 msat, 123 msat, alice, 200, Some(invoice), OutgoingPaymentStatus.Pending)
    db.addOutgoingPayment(outgoing1)
    db.updateOutgoingPayment(PaymentFailed(outgoing1.id, outgoing1.paymentHash, Nil, 210))
    // 2nd attempt: pending
    val outgoing2 = OutgoingPayment(UUID.randomUUID(), parentId1, None, paymentHash1, PaymentType.Standard, 123 msat, 123 msat, alice, 211, Some(invoice), OutgoingPaymentStatus.Pending)
    db.addOutgoingPayment(outgoing2)

    // -- 1st check: result contains 2 incoming PAID, 1 outgoing PENDING. Outgoing1 must not be overridden by Outgoing2
    val check1 = db.listPaymentsOverview(10)
    assert(check1.size == 3)
    assert(check1.head.paymentHash == paymentHash1)
    assert(check1.head.isInstanceOf[PlainOutgoingPayment])
    assert(check1.head.asInstanceOf[PlainOutgoingPayment].status == OutgoingPaymentStatus.Pending)

    // failed #2 and add a successful payment (made of 2 partial payments)
    db.updateOutgoingPayment(PaymentFailed(outgoing2.id, outgoing2.paymentHash, Nil, 250))
    val outgoing3 = OutgoingPayment(UUID.randomUUID(), parentId2, None, paymentHash1, PaymentType.Standard, 200 msat, 500 msat, bob, 300, Some(invoice), OutgoingPaymentStatus.Pending)
    val outgoing4 = OutgoingPayment(UUID.randomUUID(), parentId2, None, paymentHash1, PaymentType.Standard, 300 msat, 500 msat, bob, 310, Some(invoice), OutgoingPaymentStatus.Pending)
    db.addOutgoingPayment(outgoing3)
    db.addOutgoingPayment(outgoing4)
    // complete #2 and #3 partial payments
    val sent = PaymentSent(parentId2, paymentHash1, preimage1, outgoing3.recipientAmount, outgoing3.recipientNodeId, Seq(
      PaymentSent.PartialPayment(outgoing3.id, outgoing3.amount, 15 msat, randomBytes32, None, 400),
      PaymentSent.PartialPayment(outgoing4.id, outgoing4.amount, 20 msat, randomBytes32, None, 410)
    ))
    db.updateOutgoingPayment(sent)

    // -- 2nd check: result contains 2 incoming PAID, 1 outgoing FAILED and 1 outgoing SUCCEEDED, in correct order
    val check2 = db.listPaymentsOverview(10)
    assert(check2.size == 4)
    assert(check2.head.paymentHash == paymentHash1)
    assert(check2.head.isInstanceOf[PlainOutgoingPayment])
    assert(check2.head.asInstanceOf[PlainOutgoingPayment].status.isInstanceOf[OutgoingPaymentStatus.Succeeded])

    assert(check2(1).paymentHash == paymentHash1)
    assert(check2(1).isInstanceOf[PlainOutgoingPayment])
    assert(check2(1).asInstanceOf[PlainOutgoingPayment].status.isInstanceOf[OutgoingPaymentStatus.Failed])

    assert(check2(2).paymentHash == paidInvoice2.paymentHash)
    assert(check2(2).isInstanceOf[PlainIncomingPayment])
    assert(check2(2).asInstanceOf[PlainIncomingPayment].status.isInstanceOf[IncomingPaymentStatus.Received])

    assert(check2(3).paymentHash == paidInvoice1.paymentHash)
    assert(check2(3).isInstanceOf[PlainIncomingPayment])
    assert(check2(3).asInstanceOf[PlainIncomingPayment].status.isInstanceOf[IncomingPaymentStatus.Received])
  }

}

object SqlitePaymentsDbSpec {
  val (alicePriv, bobPriv, carolPriv, davePriv) = (randomKey, randomKey, randomKey, randomKey)
  val (alice, bob, carol, dave) = (alicePriv.publicKey, bobPriv.publicKey, carolPriv.publicKey, davePriv.publicKey)
  val hop_ab = ChannelHop(alice, bob, ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(42), 1, 0, 0, CltvExpiryDelta(12), 1 msat, 1 msat, 1, None))
  val hop_bc = NodeHop(bob, carol, CltvExpiryDelta(14), 1 msat)
  val (preimage1, preimage2, preimage3, preimage4) = (randomBytes32, randomBytes32, randomBytes32, randomBytes32)
  val (paymentHash1, paymentHash2, paymentHash3, paymentHash4) = (Crypto.sha256(preimage1), Crypto.sha256(preimage2), Crypto.sha256(preimage3), Crypto.sha256(preimage4))
}
