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
import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import org.scalatest.FunSuite
import scodec.bits._

class SqlitePaymentsDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqlitePaymentsDb(sqlite)
    val db2 = new SqlitePaymentsDb(sqlite)
  }

  test("add/list received payments/find 1 payment that exists/find 1 payment that does not exist") {
    val sqlite = inmem
    val db = new SqlitePaymentsDb(sqlite)

    val p1 = ReceivedPayment(ByteVector32(hex"08d47d5f7164d4b696e8f6b62a03094d4f1c65f16e9d7b11c4a98854707e55cf"), 12345678, 1513871928275L)
    val p2 = ReceivedPayment(ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 12345678, 1513871928275L)
    assert(db.listReceived() === Nil)
    db.addReceivedPayment(p1)
    db.addReceivedPayment(p2)
    assert(db.listReceived().toList === List(p1, p2))
    assert(db.receivedByPaymentHash(p1.paymentHash) === Some(p1))
    assert(db.receivedByPaymentHash(ByteVector32(hex"6e7e8018f05e169cf1d99e77dc22cb372d09f10b6a81f1eae410718c56cad187")) === None)
  }

  test("add/retrieve/update sent payments") {

    val db = new SqlitePaymentsDb(inmem)

    val s1 = SentPayment(UUID.randomUUID(), ByteVector32(hex"0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 12345, 1513871928275L)
    val s2 = SentPayment(UUID.randomUUID(), ByteVector32(hex"08d47d5f7164d4b696e8f6b62a03094d4f1c65f16e9d7b11c4a98854707e55cf"), 54321, 1513871928275L)

    assert(db.listSent().isEmpty)
    db.addSentPayments(s1)
    db.addSentPayments(s2)

    assert(db.listSent().toList == Seq(s1, s2))
    assert(db.sentPaymentById(s1.id) === Some(s1))
    assert(db.sentPaymentById(UUID.randomUUID()) === None)
    assert(db.sentPaymentByHash(s2.paymentHash) === Some(s2))
    assert(db.sentPaymentByHash(ByteVector32.Zeroes) === None)

    val s3 = s2.copy(amountMsat = 88776655)
    db.addSentPayments(s3)
    assert(db.sentPaymentById(s2.id).exists(_.amountMsat == s3.amountMsat))
  }

}
