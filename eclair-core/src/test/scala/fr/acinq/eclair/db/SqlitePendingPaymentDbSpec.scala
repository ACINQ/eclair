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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.sqlite.SqlitePendingPaymentDb
import fr.acinq.eclair.payment.{PaymentLostOnChain, PaymentSettlingOnChain}
import org.scalatest.FunSuite

/**
  * Created by anton on 12.09.18.
  */
class SqlitePendingPaymentDbSpec extends FunSuite {
  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")
  val peerNodeId = PublicKey(BinaryData("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
  val targetNodeId1 = PublicKey(BinaryData("0x028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2"))
  val targetNodeId2 = PublicKey(BinaryData("0x03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4"))
  val paymentHash1 = BinaryData("0001020304050607080900010203040506070809000102030405060708090102")
  val paymentHash2 = BinaryData("0001020304050607080900010203040506070809000102030405060708090104")
  val paymentHash3 = BinaryData("0001020304050607080900010203040506070809000102030405060708090106")
  val paymentHash4 = BinaryData("0001020304050607080900010203040506070809000102030405060708090108")
  val paymentHash5 = BinaryData("0001020304050607080900010203040506070809000102030405060708090110")
  val paymentHash6 = BinaryData("0001020304050607080900010203040506070809000102030405060708090112")
  val txid1 = BinaryData("0001020304050607080900010203040506070809000102030405060708090114")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqlitePendingPaymentDb(sqlite)
    val db2 = new SqlitePendingPaymentDb(sqlite)
  }

  test("list delays for target node id") {
    val sqlite = inmem
    val db = new SqlitePendingPaymentDb(sqlite)

    db.add(paymentHash1, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash2, peerNodeId, targetNodeId2, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.updateDelay(paymentHash1, peerNodeId, delay = 1840)
    db.updateDelay(paymentHash2, peerNodeId, delay = 1740)

    assert(db.listDelays(targetNodeId2, 1420) == Seq(300)) // delayed by 300 blocks
    assert(db.listDelays(targetNodeId1, 1420) == Nil) // Peer is to blame
    assert(db.listBadPeers(1420) == Seq(peerNodeId))
  }

  test("ignore same payment for same peer node") {
    val sqlite = inmem
    val db = new SqlitePendingPaymentDb(sqlite)

    db.add(paymentHash1, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1540, expiry = 1900)
    db.add(paymentHash1, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1540, delay = 1740, expiry = 2000)
    assert(db.listDelays(targetNodeId1, 1420) == Seq(100))

    db.updateDelay(paymentHash2, peerNodeId, delay = 1440)
    db.updateDelay(paymentHash1, peerNodeId, delay = 1740)
    assert(db.listDelays(targetNodeId1, 1420) == Seq(300))
  }

  test("list bad peers") {
    val sqlite = inmem
    val db = new SqlitePendingPaymentDb(sqlite)

    db.add(paymentHash1, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash2, peerNodeId, targetNodeId2, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.updateDelay(paymentHash1, peerNodeId, delay = 1840)
    db.updateDelay(paymentHash2, peerNodeId, delay = 1840)

    assert(db.listBadPeers(1420) == Seq(peerNodeId, peerNodeId)) // This peer has delayed our payment twice since block 1420
  }

  test("risk evaluation") {
    val sqlite = inmem
    val db = new SqlitePendingPaymentDb(sqlite)

    db.add(paymentHash1, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash2, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash3, peerNodeId, targetNodeId2, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash4, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash5, peerNodeId, targetNodeId1, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.add(paymentHash6, peerNodeId, targetNodeId2, peerCltvDelta = 144, added = 1440, delay = 1440, expiry = 1900)
    db.updateDelay(paymentHash1, peerNodeId, delay = 1452) // an outlier
    db.updateDelay(paymentHash2, peerNodeId, delay = 1443)
    db.updateDelay(paymentHash3, peerNodeId, delay = 1444)
    db.updateDelay(paymentHash4, peerNodeId, delay = 1445)
    db.updateDelay(paymentHash5, peerNodeId, delay = 1446)
    db.updateDelay(paymentHash6, peerNodeId, delay = 1447)

    val expected = RiskInfo(targetNodeId1, 1420, 6, 6.166666666666667, 5.82141639885766, Seq(12, 3, 5, 6), Seq(12))
    assert(db.riskInfo(targetNodeId1, sinceBlockHeight = 1420, sdTimes = 2).contains(expected))
  }

  test("on-chain settling") {
    val sqlite = inmem
    val db = new SqlitePendingPaymentDb(sqlite)

    val msg = PaymentSettlingOnChain(MilliSatoshi(100000000L), MilliSatoshi(90000000L), paymentHash1, txid1, "claim-htlc-delayed", isDone = false)
    // Refunding transaction is unconfirmed yet
    db.addSettlingOnChain(msg)
    assert(db.getSettlingOnChain(paymentHash1).contains(msg))

    // Refunding transaction is confirmed, we simply insert another record with `isDone = true` instead of updating an old one
    db.addSettlingOnChain(msg.copy(isDone = true))
    assert(db.getSettlingOnChain(paymentHash1).contains(msg.copy(isDone = true)))
    assert(db.getSettlingOnChain(txid1).contains(msg.copy(isDone = true)))

    val lost = PaymentLostOnChain(MilliSatoshi(2000000L), paymentHash1)
    db.addLostOnChain(lost)
    assert(db.getLostOnChain(paymentHash1).contains(lost))
  }
}
