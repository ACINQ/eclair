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
import fr.acinq.eclair.router.ChannelHop
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

  test("handle version migration 1->3") {
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
      assert(getVersion(statement, "payments", 1) == 3) // version changed from 1 -> 3
    }

    // the existing received payment can NOT be queried anymore
    assert(preMigrationDb.getIncomingPayment(paymentHash1).isEmpty)

    // add a few rows
    val ps1 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, paymentHash1, 12345 msat, alice, 1000, None, OutgoingPaymentStatus.Pending)
    val i1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(500 msat), paymentHash1, davePriv, "Some invoice", expirySeconds = None, timestamp = 1)
    val pr1 = IncomingPayment(i1, preimage1, i1.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(550 msat, 1100))

    preMigrationDb.addOutgoingPayment(ps1)
    preMigrationDb.addIncomingPayment(i1, preimage1)
    preMigrationDb.receiveIncomingPayment(i1.paymentHash, 550 msat, 1100)

    assert(preMigrationDb.listIncomingPayments(1, 1500) === Seq(pr1))
    assert(preMigrationDb.listOutgoingPayments(1, 1500) === Seq(ps1))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 3) == 3) // version still to 3
    }

    assert(postMigrationDb.listIncomingPayments(1, 1500) === Seq(pr1))
    assert(postMigrationDb.listOutgoingPayments(1, 1500) === Seq(ps1))
  }

  test("handle version migration 2->3") {
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
    val ps1 = OutgoingPayment(id1, id1, None, randomBytes32, 561 msat, PrivateKey(ByteVector32.One).publicKey, 1000, None, OutgoingPaymentStatus.Pending)
    val ps2 = OutgoingPayment(id2, id2, None, randomBytes32, 1105 msat, PrivateKey(ByteVector32.One).publicKey, 1010, None, OutgoingPaymentStatus.Failed(Nil, 1050))
    val ps3 = OutgoingPayment(id3, id3, None, paymentHash1, 1729 msat, PrivateKey(ByteVector32.One).publicKey, 1040, None, OutgoingPaymentStatus.Succeeded(preimage1, 0 msat, Nil, 1060))
    val i1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash1, davePriv, "Some invoice", expirySeconds = None, timestamp = 1)
    val pr1 = IncomingPayment(i1, preimage1, i1.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(12345678 msat, 1090))
    val i2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash2, carolPriv, "Another invoice", expirySeconds = Some(30), timestamp = 1)
    val pr2 = IncomingPayment(i2, preimage2, i2.timestamp.seconds.toMillis, IncomingPaymentStatus.Expired)

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
      assert(getVersion(statement, "payments", 2) == 3) // version changed from 2 -> 3
    }

    assert(preMigrationDb.getIncomingPayment(i1.paymentHash) === Some(pr1))
    assert(preMigrationDb.getIncomingPayment(i2.paymentHash) === Some(pr2))
    assert(preMigrationDb.listOutgoingPayments(1, 2000) === Seq(ps1, ps2, ps3))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 3) == 3) // version still to 3
    }

    val i3 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), paymentHash3, alicePriv, "invoice #3", expirySeconds = Some(30))
    val pr3 = IncomingPayment(i3, preimage3, i3.timestamp.seconds.toMillis, IncomingPaymentStatus.Pending)
    postMigrationDb.addIncomingPayment(i3, pr3.paymentPreimage)

    val ps4 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("1"), randomBytes32, 123 msat, alice, 1100, Some(i3), OutgoingPaymentStatus.Pending)
    val ps5 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("2"), randomBytes32, 456 msat, bob, 1150, Some(i2), OutgoingPaymentStatus.Succeeded(preimage1, 42 msat, Nil, 1180))
    val ps6 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("3"), randomBytes32, 789 msat, bob, 1250, None, OutgoingPaymentStatus.Failed(Nil, 1300))
    postMigrationDb.addOutgoingPayment(ps4)
    postMigrationDb.addOutgoingPayment(ps5.copy(status = OutgoingPaymentStatus.Pending))
    postMigrationDb.updateOutgoingPayment(PaymentSent(ps5.parentId, ps5.paymentHash, preimage1, Seq(PaymentSent.PartialPayment(ps5.id, ps5.amount, 42 msat, randomBytes32, None, 1180))))
    postMigrationDb.addOutgoingPayment(ps6.copy(status = OutgoingPaymentStatus.Pending))
    postMigrationDb.updateOutgoingPayment(PaymentFailed(ps6.id, ps6.paymentHash, Nil, 1300))

    assert(postMigrationDb.listOutgoingPayments(1, 2000) === Seq(ps1, ps2, ps3, ps4, ps5, ps6))
    assert(postMigrationDb.listIncomingPayments(1, Platform.currentTime) === Seq(pr1, pr2, pr3))
    assert(postMigrationDb.listExpiredIncomingPayments(1, 2000) === Seq(pr2))
  }

  test("add/retrieve/update incoming payments") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqlitePaymentsDb(sqlite)

    // can't receive a payment without an invoice associated with it
    assertThrows[IllegalArgumentException](db.receiveIncomingPayment(randomBytes32, 12345678 msat))

    val expiredInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32, alicePriv, "invoice #1", timestamp = 1)
    val expiredInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32, bobPriv, "invoice #2", timestamp = 2, expirySeconds = Some(30))
    val expiredPayment1 = IncomingPayment(expiredInvoice1, randomBytes32, expiredInvoice1.timestamp.seconds.toMillis, IncomingPaymentStatus.Expired)
    val expiredPayment2 = IncomingPayment(expiredInvoice2, randomBytes32, expiredInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Expired)

    val pendingInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32, alicePriv, "invoice #3")
    val pendingInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32, bobPriv, "invoice #4", expirySeconds = Some(30))
    val pendingPayment1 = IncomingPayment(pendingInvoice1, randomBytes32, pendingInvoice1.timestamp.seconds.toMillis, IncomingPaymentStatus.Pending)
    val pendingPayment2 = IncomingPayment(pendingInvoice2, randomBytes32, pendingInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Pending)

    val paidInvoice1 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32, alicePriv, "invoice #5")
    val paidInvoice2 = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32, bobPriv, "invoice #6", expirySeconds = Some(60))
    val receivedAt1 = Platform.currentTime + 1
    val receivedAt2 = Platform.currentTime + 2
    val payment1 = IncomingPayment(paidInvoice1, randomBytes32, paidInvoice1.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(561 msat, receivedAt2))
    val payment2 = IncomingPayment(paidInvoice2, randomBytes32, paidInvoice2.timestamp.seconds.toMillis, IncomingPaymentStatus.Received(1111 msat, receivedAt2))

    db.addIncomingPayment(pendingInvoice1, pendingPayment1.paymentPreimage)
    db.addIncomingPayment(pendingInvoice2, pendingPayment2.paymentPreimage)
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
    val s1 = OutgoingPayment(UUID.randomUUID(), parentId, None, paymentHash1, 123 msat, alice, 100, Some(i1), OutgoingPaymentStatus.Pending)
    val s2 = OutgoingPayment(UUID.randomUUID(), parentId, Some("1"), paymentHash1, 456 msat, bob, 200, None, OutgoingPaymentStatus.Pending)

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
    val s4 = s2.copy(id = UUID.randomUUID(), createdAt = 300)
    db.addOutgoingPayment(s3)
    db.addOutgoingPayment(s4)

    db.updateOutgoingPayment(PaymentFailed(s3.id, s3.paymentHash, Nil, 310))
    val ss3 = s3.copy(status = OutgoingPaymentStatus.Failed(Nil, 310))
    assert(db.getOutgoingPayment(s3.id) === Some(ss3))
    db.updateOutgoingPayment(PaymentFailed(s4.id, s4.paymentHash, Seq(LocalFailure(new RuntimeException("woops")), RemoteFailure(Seq(hop_ab, hop_bc), Sphinx.DecryptedFailurePacket(carol, UnknownNextPeer))), 320))
    val ss4 = s4.copy(status = OutgoingPaymentStatus.Failed(Seq(FailureSummary(FailureType.LOCAL, "woops", Nil), FailureSummary(FailureType.REMOTE, "processing node does not know the next peer in the route", List(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, Some(ShortChannelId(43)))))), 320))
    assert(db.getOutgoingPayment(s4.id) === Some(ss4))

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentSent(parentId, s3.paymentHash, preimage1, Seq(PaymentSent.PartialPayment(s3.id, s3.amount, 42 msat, randomBytes32, None)))))

    val paymentSent = PaymentSent(parentId, paymentHash1, preimage1, Seq(
      PaymentSent.PartialPayment(s1.id, s1.amount, 15 msat, randomBytes32, None, 400),
      PaymentSent.PartialPayment(s2.id, s2.amount, 20 msat, randomBytes32, Some(Seq(hop_ab, hop_bc)), 410)
    ))
    val ss1 = s1.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 15 msat, Nil, 400))
    val ss2 = s2.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 20 msat, Seq(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, Some(ShortChannelId(43)))), 410))
    db.updateOutgoingPayment(paymentSent)
    assert(db.getOutgoingPayment(s1.id) === Some(ss1))
    assert(db.getOutgoingPayment(s2.id) === Some(ss2))
    assert(db.listOutgoingPayments(parentId) === Seq(ss1, ss2, ss3, ss4))

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentFailed(s1.id, s1.paymentHash, Nil)))
  }

}

object SqlitePaymentsDbSpec {
  val (alicePriv, bobPriv, carolPriv, davePriv) = (randomKey, randomKey, randomKey, randomKey)
  val (alice, bob, carol, dave) = (alicePriv.publicKey, bobPriv.publicKey, carolPriv.publicKey, davePriv.publicKey)
  val hop_ab = ChannelHop(alice, bob, ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(42), 1, 0, 0, CltvExpiryDelta(12), 1 msat, 1 msat, 1, None))
  val hop_bc = ChannelHop(bob, carol, ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(43), 1, 0, 0, CltvExpiryDelta(12), 1 msat, 1 msat, 1, None))
  val (preimage1, preimage2, preimage3, preimage4) = (randomBytes32, randomBytes32, randomBytes32, randomBytes32)
  val (paymentHash1, paymentHash2, paymentHash3, paymentHash4) = (Crypto.sha256(preimage1), Crypto.sha256(preimage2), Crypto.sha256(preimage3), Crypto.sha256(preimage4))
}
