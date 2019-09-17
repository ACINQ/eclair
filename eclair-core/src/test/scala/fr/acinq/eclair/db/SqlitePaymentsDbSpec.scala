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
import fr.acinq.eclair.db.OutgoingPaymentStatus._
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Hop
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

    val oldReceivedPayment = IncomingPayment(randomBytes32, 123 msat, 1233322)

    // Changes between version 1 and 2:
    //  - the monolithic payments table has been replaced by two tables, received_payments and sent_payments
    //  - old records from the payments table are ignored (not migrated to the new tables)
    using(connection.prepareStatement("INSERT INTO payments VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, oldReceivedPayment.paymentHash.toArray)
      statement.setLong(2, oldReceivedPayment.amount.toLong)
      statement.setLong(3, oldReceivedPayment.receivedAt)
      statement.executeUpdate()
    }

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 1) == 3) // version changed from 1 -> 3
    }

    // the existing received payment can NOT be queried anymore
    assert(preMigrationDb.getIncomingPayment(oldReceivedPayment.paymentHash).isEmpty)

    // add a few rows
    val ps1 = OutgoingPayment(UUID.randomUUID(), None, None, oldReceivedPayment.paymentHash, 12345 msat, alice, 12345, PENDING, None)
    val i1 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")
    val pr1 = IncomingPayment(i1.paymentHash, 12345678 msat, 1513871928275L)

    preMigrationDb.addPaymentRequest(i1, ByteVector32.Zeroes)
    preMigrationDb.addIncomingPayment(pr1)
    preMigrationDb.addOutgoingPayment(ps1)

    val now = Platform.currentTime.milliseconds.toSeconds
    assert(preMigrationDb.listIncomingPayments(0, now) == Seq(pr1))
    assert(preMigrationDb.listOutgoingPayments(0, now) == Seq(ps1))
    assert(preMigrationDb.listPaymentRequests(0, now) == Seq(i1))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 3) == 3) // version still to 3
    }

    assert(postMigrationDb.listIncomingPayments(0, now) == Seq(pr1))
    assert(postMigrationDb.listOutgoingPayments(0, now) == Seq(ps1))
    assert(preMigrationDb.listPaymentRequests(0, now) == Seq(i1))
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
    val ps1 = OutgoingPayment(UUID.randomUUID(), None, None, randomBytes32, 561 msat, PrivateKey(ByteVector32.One).publicKey, 0, PENDING, None)
    val ps2 = OutgoingPayment(UUID.randomUUID(), None, None, randomBytes32, 1105 msat, PrivateKey(ByteVector32.One).publicKey, 1, FAILED, None, Some(2), None, Some(PaymentFailureSummary(Nil)))
    val ps3 = OutgoingPayment(UUID.randomUUID(), None, None, defaultPaymentHash, 1729 msat, PrivateKey(ByteVector32.One).publicKey, 4, SUCCEEDED, None, Some(5), Some(PaymentSuccessSummary(defaultPreimage, 0 msat, Nil)))
    val i1 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")
    val pr1 = IncomingPayment(i1.paymentHash, 12345678 msat, 9)
    val i2 = PaymentRequest.read("lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq8rkx3yf5tcsyz3d73gafnh3cax9rn449d9p5uxz9ezhhypd0elx87sjle52x86fux2ypatgddc6k63n7erqz25le42c4u4ecky03ylcqca784w")

    // Changes between version 2 and 3 to sent_payments:
    //  - added optional payment failures
    //  - added optional payment success details (fees paid and route)
    //  - added optional payment request
    //  - added target node ID
    //  - added externalID and parentID

    using(connection.prepareStatement("INSERT INTO sent_payments VALUES (?, ?, NULL, ?, ?, NULL, ?)")) { statement =>
      statement.setString(1, ps1.id.toString)
      statement.setBytes(2, ps1.paymentHash.toArray)
      statement.setLong(3, ps1.amount.toLong)
      statement.setLong(4, ps1.createdAt)
      statement.setString(5, ps1.status.toString)
      statement.executeUpdate()
    }

    for (ps <- Seq(ps2, ps3)) {
      using(connection.prepareStatement("INSERT INTO sent_payments VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, ps.id.toString)
        statement.setBytes(2, ps.paymentHash.toArray)
        statement.setBytes(3, ps.successSummary.map(_.paymentPreimage.toArray).orNull)
        statement.setLong(4, ps.amount.toLong)
        statement.setLong(5, ps.createdAt)
        statement.setLong(6, ps.completedAt.get)
        statement.setString(7, ps.status.toString)
        statement.executeUpdate()
      }
    }

    using(connection.prepareStatement("INSERT INTO received_payments VALUES (?, ?, ?, ?, ?, NULL, ?)")) { statement =>
      statement.setBytes(1, i1.paymentHash.toArray)
      statement.setBytes(2, defaultPreimage.toArray)
      statement.setString(3, PaymentRequest.write(i1))
      statement.setLong(4, pr1.amount.toLong)
      statement.setLong(5, 0) // created_at
      statement.setLong(6, pr1.receivedAt)
      statement.executeUpdate()
    }

    using(connection.prepareStatement("INSERT INTO received_payments VALUES (?, ?, ?, NULL, ?, NULL, NULL)")) { statement =>
      statement.setBytes(1, i2.paymentHash.toArray)
      statement.setBytes(2, defaultPreimage.toArray)
      statement.setString(3, PaymentRequest.write(i2))
      statement.setLong(4, 0) // created_at
      statement.executeUpdate()
    }

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 2) == 3) // version changed from 2 -> 3
    }

    assert(preMigrationDb.getPaymentRequest(i1.paymentHash) === Some(i1))
    assert(preMigrationDb.getPaymentRequest(i2.paymentHash) === Some(i2))
    assert(preMigrationDb.getIncomingPayment(i1.paymentHash) === Some(pr1))
    assert(preMigrationDb.getIncomingPayment(i2.paymentHash) === None)
    assert(preMigrationDb.listOutgoingPayments(0, 5).toSet === Set(ps1, ps2, ps3))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 3) == 3) // version still to 3
    }

    val i3 = PaymentRequest.read("lnbc1500n1pdl686hpp5y7mz3lgvrfccqnk9es6trumjgqdpjwcecycpkdggnx7h6cuup90sdpa2fjkzep6ypqkymm4wssycnjzf9rjqurjda4x2cm5ypskuepqv93x7at5ypek7cqzysxqr23s5e864m06fcfp3axsefy276d77tzp0xzzzdfl6p46wvstkeqhu50khm9yxea2d9efp7lvthrta0ktmhsv52hf3tvxm0unsauhmfmp27cqqx4xxe")
    postMigrationDb.addPaymentRequest(i3, randomBytes32)

    val ps4 = OutgoingPayment(UUID.randomUUID(), Some(UUID.randomUUID()), Some(UUID.randomUUID()), randomBytes32, 123 msat, alice, 6, PENDING, Some(i3))
    val ps5 = OutgoingPayment(UUID.randomUUID(), Some(UUID.randomUUID()), Some(UUID.randomUUID()), randomBytes32, 456 msat, bob, 7, SUCCEEDED, Some(i2), Some(8), Some(PaymentSuccessSummary(randomBytes32, 42 msat, Nil)))
    val ps6 = OutgoingPayment(UUID.randomUUID(), Some(UUID.randomUUID()), Some(UUID.randomUUID()), randomBytes32, 789 msat, bob, 8, FAILED, None, Some(9), None, Some(PaymentFailureSummary(Nil)))
    postMigrationDb.addOutgoingPayment(ps4)
    postMigrationDb.addOutgoingPayment(ps5)
    postMigrationDb.updateOutgoingPayment(PaymentSent(ps5.id, ps5.amount, ps5.successSummary.get.feesPaid, ps5.paymentHash, ps5.successSummary.get.paymentPreimage, Nil, ps5.completedAt.get))
    postMigrationDb.addOutgoingPayment(ps6)
    postMigrationDb.updateOutgoingPayment(PaymentFailed(ps6.id, ps6.paymentHash, Nil, ps6.completedAt.get))

    assert(postMigrationDb.listOutgoingPayments(0, 10).toSet === Set(ps1, ps2, ps3, ps4, ps5, ps6))
    assert(postMigrationDb.listIncomingPayments(0, 10).toSet === Set(pr1))
    assert(postMigrationDb.getPaymentRequest(i1.paymentHash) === Some(i1))
    assert(postMigrationDb.getPaymentRequest(i2.paymentHash) === Some(i2))
    assert(postMigrationDb.getPaymentRequest(i3.paymentHash) === Some(i3))
  }

  test("add/list received payments/find 1 payment that exists/find 1 payment that does not exist") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqlitePaymentsDb(sqlite)

    // can't receive a payment without an invoice associated with it
    assertThrows[IllegalArgumentException](db.addIncomingPayment(IncomingPayment(randomBytes32, 12345678 msat, 1513871928275L)))

    val i1 = PaymentRequest.read("lnbc5450n1pw2t4qdpp5vcrf6ylgpettyng4ac3vujsk0zpc25cj0q3zp7l7w44zvxmpzh8qdzz2pshjmt9de6zqen0wgsr2dp4ypcxj7r9d3ejqct5ypekzar0wd5xjuewwpkxzcm99cxqzjccqp2rzjqtspxelp67qc5l56p6999wkatsexzhs826xmupyhk6j8lxl038t27z9tsqqqgpgqqqqqqqlgqqqqqzsqpcz8z8hmy8g3ecunle4n3edn3zg2rly8g4klsk5md736vaqqy3ktxs30ht34rkfkqaffzxmjphvd0637dk2lp6skah2hq09z6lrjna3xqp3d4vyd")
    val i2 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")

    db.addPaymentRequest(i1, ByteVector32.Zeroes)
    db.addPaymentRequest(i2, ByteVector32.One)

    val p1 = IncomingPayment(i1.paymentHash, 12345678 msat, 1513871928275L)
    val p2 = IncomingPayment(i2.paymentHash, 12345678 msat, 1513871928275L)
    assert(db.listIncomingPayments(0, Platform.currentTime.milliseconds.toSeconds) === Nil)
    db.addIncomingPayment(p1)
    db.addIncomingPayment(p2)
    assert(db.listIncomingPayments(0, Platform.currentTime.milliseconds.toSeconds).toList === List(p1, p2))
    assert(db.getIncomingPayment(p1.paymentHash) === Some(p1))
    assert(db.getIncomingPayment(randomBytes32) === None)
  }

  test("add/retrieve/update sent payments") {
    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    val i1 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = Some(123 msat), paymentHash = randomBytes32, privateKey = davePriv, description = "Some invoice", expirySeconds = None, timestamp = 1)
    val s1 = OutgoingPayment(UUID.randomUUID(), Some(UUID.randomUUID()), None, i1.paymentHash, 123 msat, alice, 1, PENDING, Some(i1))
    val s2 = OutgoingPayment(UUID.randomUUID(), None, Some(UUID.randomUUID()), randomBytes32, 456 msat, bob, 2, PENDING, None)

    assert(db.listOutgoingPayments(0, Platform.currentTime.milliseconds.toSeconds).isEmpty)
    db.addOutgoingPayment(s1)
    db.addOutgoingPayment(s2)

    assert(db.listOutgoingPayments(0, Platform.currentTime.milliseconds.toSeconds).toList == Seq(s1, s2))
    assert(db.getOutgoingPayment(s1.id) === Some(s1))
    assert(db.getOutgoingPayment(UUID.randomUUID()) === None)
    assert(db.getOutgoingPayments(s2.paymentHash) === Seq(s2))
    assert(db.getOutgoingPayments(ByteVector32.Zeroes) === Nil)

    val s3 = s2.copy(id = UUID.randomUUID(), amount = 789 msat)
    val s4 = s2.copy(id = UUID.randomUUID())
    db.addOutgoingPayment(s3)
    db.addOutgoingPayment(s4)

    db.updateOutgoingPayment(PaymentFailed(s3.id, s3.paymentHash, Nil, 10))
    assert(db.getOutgoingPayment(s3.id) === Some(s3.copy(status = FAILED, completedAt = Some(10), failureSummary = Some(PaymentFailureSummary(Nil)))))
    db.updateOutgoingPayment(PaymentFailed(s4.id, s4.paymentHash, Seq(LocalFailure(new RuntimeException("woops")), RemoteFailure(Seq(hop_ab, hop_bc), Sphinx.DecryptedFailurePacket(carol, UnknownNextPeer))), 11))
    assert(db.getOutgoingPayment(s4.id) === Some(s4.copy(status = FAILED, completedAt = Some(11), failureSummary = Some(PaymentFailureSummary(Seq(FailureSummary(FailureType.LOCAL, "woops", Nil), FailureSummary(FailureType.REMOTE, "processing node does not know the next peer in the route", List(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, Some(ShortChannelId(43)))))))))))

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentSent(s3.id, s3.amount, 42 msat, s3.paymentHash, defaultPreimage, Nil)))

    db.updateOutgoingPayment(PaymentSent(s1.id, s1.amount, 15 msat, s1.paymentHash, defaultPreimage, Nil, 10))
    assert(db.getOutgoingPayment(s1.id) === Some(s1.copy(status = SUCCEEDED, completedAt = Some(10), successSummary = Some(PaymentSuccessSummary(defaultPreimage, 15 msat, Nil)))))
    db.updateOutgoingPayment(PaymentSent(s2.id, s2.amount, 15 msat, s2.paymentHash, defaultPreimage, Seq(hop_ab, hop_bc), 11))
    assert(db.getOutgoingPayment(s2.id) === Some(s2.copy(status = SUCCEEDED, completedAt = Some(11), successSummary = Some(PaymentSuccessSummary(defaultPreimage, 15 msat, Seq(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, Some(ShortChannelId(43)))))))))

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentFailed(s1.id, s1.paymentHash, Nil)))
  }

  test("add/retrieve payment requests") {
    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())
    val someTimestamp = 12345
    val (paymentHash1, paymentHash2) = (randomBytes32, randomBytes32)
    val i1 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = Some(123 msat), paymentHash = paymentHash1, privateKey = bobPriv, description = "Some invoice", expirySeconds = None, timestamp = someTimestamp)
    val i2 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = None, paymentHash = paymentHash2, privateKey = bobPriv, description = "Some invoice", expirySeconds = Some(123456), timestamp = Platform.currentTime.milliseconds.toSeconds)

    // i2 doesn't expire
    assert(i1.expiry.isEmpty && i2.expiry.isDefined)
    assert(i1.amount.isDefined && i2.amount.isEmpty)

    db.addPaymentRequest(i1, ByteVector32.Zeroes)
    db.addPaymentRequest(i2, ByteVector32.One)

    // order matters, i2 has a more recent timestamp than i1
    assert(db.listPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i2, i1))
    assert(db.getPaymentRequest(i1.paymentHash) === Some(i1))
    assert(db.getPaymentRequest(i2.paymentHash) === Some(i2))

    assert(db.listPendingPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i2, i1))
    assert(db.getPendingPaymentRequestAndPreimage(paymentHash1) === Some((ByteVector32.Zeroes, i1)))
    assert(db.getPendingPaymentRequestAndPreimage(paymentHash2) === Some((ByteVector32.One, i2)))

    val from = (someTimestamp - 100).seconds.toSeconds
    val to = (someTimestamp + 100).seconds.toSeconds
    assert(db.listPaymentRequests(from, to) == Seq(i1))

    db.addIncomingPayment(IncomingPayment(i2.paymentHash, 42 msat, someTimestamp))
    assert(db.listPendingPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i1))
  }

}

object SqlitePaymentsDbSpec {
  val (alicePriv, bobPriv, carolPriv, davePriv) = (randomKey, randomKey, randomKey, randomKey)
  val (alice, bob, carol, dave) = (alicePriv.publicKey, bobPriv.publicKey, carolPriv.publicKey, davePriv.publicKey)
  val hop_ab = Hop(alice, bob, ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(42), 1, 0, 0, CltvExpiryDelta(12), 1 msat, 1 msat, 1, None))
  val hop_bc = Hop(bob, carol, ChannelUpdate(randomBytes64, randomBytes32, ShortChannelId(43), 1, 0, 0, CltvExpiryDelta(12), 1 msat, 1 msat, 1, None))
  val defaultPreimage = randomBytes32
  val defaultPaymentHash = Crypto.sha256(defaultPreimage)
}
