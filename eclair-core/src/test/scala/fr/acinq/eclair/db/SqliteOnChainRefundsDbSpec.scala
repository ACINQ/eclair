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

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.db.sqlite.SqliteOnChainRefundsDb
import fr.acinq.eclair.payment.{PaymentLostOnChain, PaymentSettlingOnChain}
import org.scalatest.FunSuite

class SqliteOnChainRefundsDbSpec extends FunSuite {
  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")
  val paymentHash1 = BinaryData("0001020304050607080900010203040506070809000102030405060708090102")
  val txid1 = BinaryData("0001020304050607080900010203040506070809000102030405060708090114")
  val txid2 = BinaryData("0001020304050607080900010203040506070809000102030405060708090116")

  test("track on-chain settling and failed refunds") {
    val sqlite = inmem
    val db = new SqliteOnChainRefundsDb(sqlite)

    val msg1 = PaymentSettlingOnChain(MilliSatoshi(100000000L), MilliSatoshi(90000000L), paymentHash1, txid1, "claim-success", isDone = false)
    val msg2 = PaymentSettlingOnChain(MilliSatoshi(100000000L), MilliSatoshi(80000000L), paymentHash1, txid2, "claim-timeout-delayed", isDone = false)
    // a double-spend race happens
    db.addSettlingOnChain(msg1)
    db.addSettlingOnChain(msg2)
    // no refunding transaction is confirmed yet
    assert(db.getSettlingOnChain(txid1).contains(msg1))
    assert(db.getSettlingOnChain(txid2).contains(msg2))

    // One of two competing transactions is confirmed, we simply insert another record with `isDone = true` instead of updating an old one
    db.getSettlingOnChain(txid1).map(_.copy(isDone = true)).foreach(db.addSettlingOnChain)
    assert(db.getSettlingOnChain(paymentHash1).contains(msg1.copy(isDone = true)))
    // transaction which has lost is not updated and has `isDone = false`
    assert(db.getSettlingOnChain(txid2).contains(msg2))
    // Works both for off-chain paymentHash and for on-chain txid
    assert(db.getSettlingOnChain(txid1).contains(msg1.copy(isDone = true)))

    val lost = PaymentLostOnChain(MilliSatoshi(2000000L), paymentHash1)
    db.addLostOnChain(lost)
    assert(db.getLostOnChain(paymentHash1).contains(lost))
  }
}