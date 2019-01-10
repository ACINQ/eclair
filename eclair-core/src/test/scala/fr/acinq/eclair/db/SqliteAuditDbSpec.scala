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
import fr.acinq.eclair.channel.{AvailableBalanceChanged, NetworkFeePaid}
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.{ShortChannelId, randomBytes, randomKey}
import org.scalatest.FunSuite

import scala.compat.Platform


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
    val e5 = PaymentSent(MilliSatoshi(42000), MilliSatoshi(1000), randomBytes(32), randomBytes(32), randomBytes(32), timestamp = 0)
    val e6 = PaymentSent(MilliSatoshi(42000), MilliSatoshi(1000), randomBytes(32), randomBytes(32), randomBytes(32), timestamp = Platform.currentTime * 2)
    val e7 = AvailableBalanceChanged(null, randomBytes(32), ShortChannelId(500000, 42, 1), 456123000, ChannelStateSpec.commitments)
    val e8 = ChannelLifecycleEvent(randomBytes(32), randomKey.publicKey, 456123000, true, false, "mutual")

    db.add(e1)
    db.add(e2)
    db.add(e3)
    db.add(e4)
    db.add(e5)
    db.add(e6)
    db.add(e7)
    db.add(e8)

    assert(db.listSent(from = 0L, to = Long.MaxValue).toSet === Set(e1, e5, e6))
    assert(db.listSent(from = 100000L, to = Platform.currentTime + 1).toList === List(e1))
    assert(db.listReceived(from = 0L, to = Long.MaxValue).toList === List(e2))
    assert(db.listRelayed(from = 0L, to = Long.MaxValue).toList === List(e3))
    assert(db.listNetworkFees(from = 0L, to = Long.MaxValue).size === 1)
    assert(db.listNetworkFees(from = 0L, to = Long.MaxValue).head.txType === "mutual")
  }

  test("stats") {
    val sqlite = inmem
    val db = new SqliteAuditDb(sqlite)

    val n1 = randomKey.publicKey
    val n2 = randomKey.publicKey
    val n3 = randomKey.publicKey

    val c1 = randomBytes(32)
    val c2 = randomBytes(32)
    val c3 = randomBytes(32)

    db.add(PaymentRelayed(MilliSatoshi(46000), MilliSatoshi(44000), randomBytes(32), randomBytes(32), c1))
    db.add(PaymentRelayed(MilliSatoshi(41000), MilliSatoshi(40000), randomBytes(32), randomBytes(32), c1))
    db.add(PaymentRelayed(MilliSatoshi(43000), MilliSatoshi(42000), randomBytes(32), randomBytes(32), c1))
    db.add(PaymentRelayed(MilliSatoshi(42000), MilliSatoshi(40000), randomBytes(32), randomBytes(32), c2))

    db.add(NetworkFeePaid(null, n1, c1, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(100), "funding"))
    db.add(NetworkFeePaid(null, n2, c2, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(200), "funding"))
    db.add(NetworkFeePaid(null, n2, c2, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(300), "mutual"))
    db.add(NetworkFeePaid(null, n3, c3, Transaction(0, Seq.empty, Seq.empty, 0), Satoshi(400), "funding"))

    assert(db.stats.toSet === Set(
      Stats(channelId = c1, avgPaymentAmountSatoshi = 42, paymentCount = 3, relayFeeSatoshi = 4, networkFeeSatoshi = 100),
      Stats(channelId = c2, avgPaymentAmountSatoshi = 40, paymentCount = 1, relayFeeSatoshi = 2, networkFeeSatoshi = 500),
      Stats(channelId = c3, avgPaymentAmountSatoshi = 0, paymentCount = 0, relayFeeSatoshi = 0, networkFeeSatoshi = 400)
    ))
  }
}
