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

import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.db.OutgoingPaymentStatus._
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.{LongToBtcAmount, TestConstants, randomBytes32}
import org.scalatest.FunSuite
import scodec.bits._

import scala.compat.Platform
import scala.concurrent.duration._

class SqlitePaymentsDbSpec extends FunSuite {

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqlitePaymentsDb(sqlite)
    val db2 = new SqlitePaymentsDb(sqlite)
  }

  test("handle version migration 1->2") {

    val connection = TestConstants.sqliteInMemory()

    using(connection.createStatement()) { statement =>
      getVersion(statement, "payments", 1)
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS payments (payment_hash BLOB NOT NULL PRIMARY KEY, amount_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
    }

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 1) == 1) // version 1 is deployed now
    }

    val oldReceivedPayment = IncomingPayment(ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 123 msat, 1233322)

    // insert old type record
    using(connection.prepareStatement("INSERT INTO payments VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, oldReceivedPayment.paymentHash.toArray)
      statement.setLong(2, oldReceivedPayment.amount.toLong)
      statement.setLong(3, oldReceivedPayment.receivedAt)
      statement.executeUpdate()
    }

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 1) == 2) // version has changed from 1 to 2!
    }

    // the existing received payment can NOT be queried anymore
    assert(preMigrationDb.getIncomingPayment(oldReceivedPayment.paymentHash).isEmpty)

    // add a few rows
    val ps1 = OutgoingPayment(id = UUID.randomUUID(), paymentHash = ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), None, amount = 12345 msat, createdAt = 12345, None, PENDING)
    val i1 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")
    val pr1 = IncomingPayment(i1.paymentHash, 12345678 msat, 1513871928275L)

    preMigrationDb.addPaymentRequest(i1, ByteVector32.Zeroes)
    preMigrationDb.addIncomingPayment(pr1)
    preMigrationDb.addOutgoingPayment(ps1)

    assert(preMigrationDb.listIncomingPayments() == Seq(pr1))
    assert(preMigrationDb.listOutgoingPayments() == Seq(ps1))
    assert(preMigrationDb.listPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i1))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 2) == 2) // version still to 2
    }

    assert(postMigrationDb.listIncomingPayments() == Seq(pr1))
    assert(postMigrationDb.listOutgoingPayments() == Seq(ps1))
    assert(preMigrationDb.listPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i1))
  }

  test("add/list received payments/find 1 payment that exists/find 1 payment that does not exist") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqlitePaymentsDb(sqlite)

    // can't receive a payment without an invoice associated with it
    assertThrows[IllegalArgumentException](db.addIncomingPayment(IncomingPayment(ByteVector32(hex"6e7e8018f05e169cf1d99e77dc22cb372d09f10b6a81f1eae410718c56cad188"), 12345678 msat, 1513871928275L)))

    val i1 = PaymentRequest.read("lnbc5450n1pw2t4qdpp5vcrf6ylgpettyng4ac3vujsk0zpc25cj0q3zp7l7w44zvxmpzh8qdzz2pshjmt9de6zqen0wgsr2dp4ypcxj7r9d3ejqct5ypekzar0wd5xjuewwpkxzcm99cxqzjccqp2rzjqtspxelp67qc5l56p6999wkatsexzhs826xmupyhk6j8lxl038t27z9tsqqqgpgqqqqqqqlgqqqqqzsqpcz8z8hmy8g3ecunle4n3edn3zg2rly8g4klsk5md736vaqqy3ktxs30ht34rkfkqaffzxmjphvd0637dk2lp6skah2hq09z6lrjna3xqp3d4vyd")
    val i2 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")

    db.addPaymentRequest(i1, ByteVector32.Zeroes)
    db.addPaymentRequest(i2, ByteVector32.Zeroes)

    val p1 = IncomingPayment(i1.paymentHash, 12345678 msat, 1513871928275L)
    val p2 = IncomingPayment(i2.paymentHash, 12345678 msat, 1513871928275L)
    assert(db.listIncomingPayments() === Nil)
    db.addIncomingPayment(p1)
    db.addIncomingPayment(p2)
    assert(db.listIncomingPayments().toList === List(p1, p2))
    assert(db.getIncomingPayment(p1.paymentHash) === Some(p1))
    assert(db.getIncomingPayment(ByteVector32(hex"6e7e8018f05e169cf1d99e77dc22cb372d09f10b6a81f1eae410718c56cad187")) === None)
  }

  test("add/retrieve/update sent payments") {

    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    val s1 = OutgoingPayment(id = UUID.randomUUID(), paymentHash = ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), None, amount = 12345 msat, createdAt = 12345, None, PENDING)
    val s2 = OutgoingPayment(id = UUID.randomUUID(), paymentHash = ByteVector32(hex"08d47d5f7164d4b696e8f6b62a03094d4f1c65f16e9d7b11c4a98854707e55cf"), None, amount = 12345 msat, createdAt = 12345, None, PENDING)

    assert(db.listOutgoingPayments().isEmpty)
    db.addOutgoingPayment(s1)
    db.addOutgoingPayment(s2)

    assert(db.listOutgoingPayments().toList == Seq(s1, s2))
    assert(db.getOutgoingPayment(s1.id) === Some(s1))
    assert(db.getOutgoingPayment(s1.id).get.completedAt.isEmpty)
    assert(db.getOutgoingPayment(UUID.randomUUID()) === None)
    assert(db.getOutgoingPayments(s2.paymentHash) === Seq(s2))
    assert(db.getOutgoingPayments(ByteVector32.Zeroes) === Seq.empty)

    val s3 = s2.copy(id = UUID.randomUUID(), amount = 88776655 msat)
    db.addOutgoingPayment(s3)

    db.updateOutgoingPayment(s3.id, FAILED)
    assert(db.getOutgoingPayment(s3.id).get.status == FAILED)
    assert(db.getOutgoingPayment(s3.id).get.preimage.isEmpty) // failed sent payments don't have a preimage
    assert(db.getOutgoingPayment(s3.id).get.completedAt.isDefined)

    // can't update again once it's in a final state
    assertThrows[IllegalArgumentException](db.updateOutgoingPayment(s3.id, SUCCEEDED))

    db.updateOutgoingPayment(s1.id, SUCCEEDED, Some(ByteVector32.One))
    assert(db.getOutgoingPayment(s1.id).get.preimage.isDefined)
    assert(db.getOutgoingPayment(s1.id).get.completedAt.isDefined)
  }

  test("add/retrieve payment requests") {

    val someTimestamp = 12345
    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    val bob = Bob.keyManager

    val (paymentHash1, paymentHash2) = (randomBytes32, randomBytes32)

    val i1 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = Some(123 msat), paymentHash = paymentHash1, privateKey = bob.nodeKey.privateKey, description = "Some invoice", expirySeconds = None, timestamp = someTimestamp)
    val i2 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = None, paymentHash = paymentHash2, privateKey = bob.nodeKey.privateKey, description = "Some invoice", expirySeconds = Some(123456), timestamp = Platform.currentTime.milliseconds.toSeconds)

    // i2 doesn't expire
    assert(i1.expiry.isEmpty && i2.expiry.isDefined)
    assert(i1.amount.isDefined && i2.amount.isEmpty)

    db.addPaymentRequest(i1, ByteVector32.Zeroes)
    db.addPaymentRequest(i2, ByteVector32.One)

    // order matters, i2 has a more recent timestamp than i1
    assert(db.listPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i2, i1))
    assert(db.getPaymentRequest(i1.paymentHash) == Some(i1))
    assert(db.getPaymentRequest(i2.paymentHash) == Some(i2))

    assert(db.listPendingPaymentRequests(0, (Platform.currentTime.milliseconds + 1.minute).toSeconds) == Seq(i2, i1))
    assert(db.getPendingPaymentRequestAndPreimage(paymentHash1) == Some((ByteVector32.Zeroes, i1)))
    assert(db.getPendingPaymentRequestAndPreimage(paymentHash2) == Some((ByteVector32.One, i2)))

    val from = (someTimestamp - 100).seconds.toSeconds
    val to = (someTimestamp + 100).seconds.toSeconds
    assert(db.listPaymentRequests(from, to) == Seq(i1))
  }

}
