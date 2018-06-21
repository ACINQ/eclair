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

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi, Transaction}
import fr.acinq.eclair.channel.NetworkFeePaid
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.{randomBytes, randomKey}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SqliteAuditDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqliteAuditDb(sqlite)
    val db2 = new SqliteAuditDb(sqlite)
  }

  test("add/list events") {
    val sqlite = inmem
    val db = new SqliteAuditDb(sqlite)

    val e1 = PaymentSent(MilliSatoshi(42000), MilliSatoshi(1000), randomBytes(32), randomBytes(32), randomBytes(32))
    val e2 = PaymentReceived(MilliSatoshi(42000), randomBytes(32), randomBytes(32))
    val e3 = PaymentRelayed(MilliSatoshi(42000), MilliSatoshi(1000), randomBytes(32), randomBytes(32), randomBytes(32))
    val e4 = NetworkFeePaid(null, randomKey.publicKey, randomBytes(32), Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(42), "mutual")

    db.add(e1)
    db.add(e2)
    db.add(e3)
    db.add(e4)

    assert(db.listSent.toList === List(e1))
    assert(db.listReceived.toList === List(e2))
    assert(db.listRelayed.toList === List(e3))
    assert(db.listNetworkFees.size === 1)
    assert(db.listNetworkFees.head.txType === "mutual")
  }
}
