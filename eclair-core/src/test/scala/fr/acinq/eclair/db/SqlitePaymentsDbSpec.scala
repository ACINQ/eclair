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

import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.bitcoin.{Block, ByteVector32, MilliSatoshi}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.{TestConstants, payment}
import fr.acinq.eclair.db.SentPayment.SentPaymentStatus
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import fr.acinq.eclair.payment.PaymentRequest
import org.scalatest.FunSuite
import scodec.bits._

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

    val oldReceivedPayment = ReceivedPayment(ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 123, 1233322)

    // insert old type record
    using(connection.prepareStatement("INSERT INTO payments VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, oldReceivedPayment.paymentHash.toArray)
      statement.setLong(2, oldReceivedPayment.amountMsat)
      statement.setLong(3, oldReceivedPayment.timestamp)
      statement.executeUpdate()
    }

    val preMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 1) == 2) // version has changed from 1 to 2!
    }

    // the existing received payment can still be queried
    assert(preMigrationDb.getReceived(oldReceivedPayment.paymentHash) == Some(oldReceivedPayment))

    // add a few rows
    val ps1 = SentPayment(UUID.randomUUID(), ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 12345, 1513871928274L, 1513871928275L, SentPaymentStatus.PENDING)
    val i1 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")
    val pr1 = ReceivedPayment(i1.paymentHash, 12345678, 1513871928275L)

    preMigrationDb.addPaymentRequest(i1, ByteVector32.Zeroes)
    preMigrationDb.addReceivedPayment(pr1)
    preMigrationDb.addSentPayment(ps1)

    assert(preMigrationDb.listReceived() == Seq(oldReceivedPayment, pr1))
    assert(preMigrationDb.listSent() == Seq(ps1))
    assert(preMigrationDb.listPaymentRequests() == Seq(i1))

    val postMigrationDb = new SqlitePaymentsDb(connection)

    using(connection.createStatement()) { statement =>
      assert(getVersion(statement, "payments", 2) == 2) // version still to 2
    }

    assert(postMigrationDb.listReceived() == Seq(oldReceivedPayment, pr1))
    assert(postMigrationDb.listSent() == Seq(ps1))
    assert(preMigrationDb.listPaymentRequests() == Seq(i1))
  }

  test("add/list received payments/find 1 payment that exists/find 1 payment that does not exist") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqlitePaymentsDb(sqlite)

    // can't receive a payment without an invoice associated with it
    assertThrows[IllegalArgumentException](db.addReceivedPayment(ReceivedPayment(ByteVector32(hex"6e7e8018f05e169cf1d99e77dc22cb372d09f10b6a81f1eae410718c56cad188"), 12345678, 1513871928275L)))

    val i1 = PaymentRequest.read("lnbc5450n1pw2t4qdpp5vcrf6ylgpettyng4ac3vujsk0zpc25cj0q3zp7l7w44zvxmpzh8qdzz2pshjmt9de6zqen0wgsr2dp4ypcxj7r9d3ejqct5ypekzar0wd5xjuewwpkxzcm99cxqzjccqp2rzjqtspxelp67qc5l56p6999wkatsexzhs826xmupyhk6j8lxl038t27z9tsqqqgpgqqqqqqqlgqqqqqzsqpcz8z8hmy8g3ecunle4n3edn3zg2rly8g4klsk5md736vaqqy3ktxs30ht34rkfkqaffzxmjphvd0637dk2lp6skah2hq09z6lrjna3xqp3d4vyd")
    val i2 = PaymentRequest.read("lnbc10u1pw2t4phpp5ezwm2gdccydhnphfyepklc0wjkxhz0r4tctg9paunh2lxgeqhcmsdqlxycrqvpqwdshgueqvfjhggr0dcsry7qcqzpgfa4ecv7447p9t5hkujy9qgrxvkkf396p9zar9p87rv2htmeuunkhydl40r64n5s2k0u7uelzc8twxmp37nkcch6m0wg5tvvx69yjz8qpk94qf3")

    db.addPaymentRequest(i1, ByteVector32.Zeroes)
    db.addPaymentRequest(i2, ByteVector32.Zeroes)

    val p1 = ReceivedPayment(i1.paymentHash, 12345678, 1513871928275L)
    val p2 = ReceivedPayment(i2.paymentHash, 12345678, 1513871928275L)
    assert(db.listReceived() === Nil)
    db.addReceivedPayment(p1)
    db.addReceivedPayment(p2)
    assert(db.listReceived().toList === List(p1, p2))
    assert(db.getReceived(p1.paymentHash) === Some(p1))
    assert(db.getReceived(ByteVector32(hex"6e7e8018f05e169cf1d99e77dc22cb372d09f10b6a81f1eae410718c56cad187")) === None)
  }

  test("add/retrieve/update sent payments") {

    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    val s1 = SentPayment(UUID.randomUUID(), ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 12345, 1513871928273L, 1513871928275L, SentPaymentStatus.PENDING)
    val s2 = SentPayment(UUID.randomUUID(), ByteVector32(hex"08d47d5f7164d4b696e8f6b62a03094d4f1c65f16e9d7b11c4a98854707e55cf"), 54321, 1513871928272L, 1513871928275L, SentPaymentStatus.PENDING)

    assert(db.listSent().isEmpty)
    db.addSentPayment(s1)
    db.addSentPayment(s2)

    assert(db.listSent().toList == Seq(s1, s2))
    assert(db.getSent(s1.id) === Some(s1))
    assert(db.getSent(UUID.randomUUID()) === None)
    assert(db.getSent(s2.paymentHash) === Some(s2))
    assert(db.getSent(ByteVector32.Zeroes) === None)

    val s3 = s2.copy(id = UUID.randomUUID(), amountMsat = 88776655, status = SentPaymentStatus.SUCCEEDED)
    db.addSentPayment(s3)
    assert(db.getSent(s3.id).exists(el => el.amountMsat == s3.amountMsat && el.status == SentPaymentStatus.SUCCEEDED))

    db.updateSentStatus(s3.id, SentPaymentStatus.FAILED)
    assert(db.getSent(s3.id).get.status == SentPaymentStatus.FAILED)

  }

  test("add/retrieve payment requests") {

    val db = new SqlitePaymentsDb(TestConstants.sqliteInMemory())

    val bob = Bob.keyManager

    val i1 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = None, paymentHash = ByteVector32.One, privateKey = bob.nodeKey.privateKey, description = "Some invoice", expirySeconds = Some(123456), timestamp = 12345)
    val i2 = PaymentRequest(chainHash = Block.TestnetGenesisBlock.hash, amount = Some(MilliSatoshi(123)), paymentHash = ByteVector32.Zeroes, privateKey = bob.nodeKey.privateKey, description = "Some invoice", expirySeconds = None, timestamp = 12345)

    val serialized = PaymentRequest.write(i1)
    val deserialized = PaymentRequest.read(serialized)

    assert(deserialized.expiry == i1.expiry)

    // i2 doesn't expire
    assert(i1.expiry.isDefined && i2.expiry.isEmpty)
    assert(i1.amount.isEmpty && i2.amount.isDefined)

    db.addPaymentRequest(i1, ByteVector32.Zeroes)
    db.addPaymentRequest(i2, ByteVector32.One)

    //assert(db.listPaymentRequests() == Seq(i1, i2))
    assert(db.getPaymentRequest(i1.paymentHash).get.expiry == i1.expiry)
    assert(db.getPaymentRequest(i1.paymentHash) == Some(i1))
    assert(db.getPaymentRequest(i2.paymentHash) == Some(i2))

    assert(db.listNonExpiredPaymentRequests() == Seq(i1, i2))
    assert(db.getActiveNonPaidPaymentRequest(i1.paymentHash) == Some((ByteVector32.Zeroes, i1)))
    assert(db.getActiveNonPaidPaymentRequest(i2.paymentHash) == Some((ByteVector32.One, i2)))
  }

}
