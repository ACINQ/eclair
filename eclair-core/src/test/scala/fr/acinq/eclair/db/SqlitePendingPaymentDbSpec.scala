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

import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.sqlite.{SqlitePeersDb, SqlitePendingPaymentDb}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by anton on 12.09.18.
  */
@RunWith(classOf[JUnitRunner])
class SqlitePendingPaymentDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")
  val peerNodeId = PublicKey(BinaryData("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
  val targetNodeId1 = PublicKey(BinaryData("0x028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2"))
  val targetNodeId2 = PublicKey(BinaryData("0x03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4"))
  val paymentHash1 = BinaryData("0001020304050607080900010203040506070809000102030405060708090102")
  val paymentHash2 = BinaryData("0001020304050607080900010203040506070809000102030405060708090104")

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
    db.updateDelay(paymentHash1, delay = 1840)
    db.updateDelay(paymentHash2, delay = 1740)

    assert(db.listDelays(targetNodeId2, 1420) == Seq(300)) // delayed by 300 blocks
    assert(db.listDelays(targetNodeId1, 1420) == Nil) // Peer is to blame
    assert(db.listBadPeers(1420) == Seq(peerNodeId))
  }
}
