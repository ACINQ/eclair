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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases, migrationCheck}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{PublishedTransaction, Stats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgAuditDb
import fr.acinq.eclair.db.pg.PgUtils.{getVersion, setVersion}
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.util.Random

class AuditDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  val ZERO_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteAuditDb(sqlite.connection)
        new SqliteAuditDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgAuditDb()(pg.datasource)
        new PgAuditDb()(pg.datasource)
    }
  }

  test("add/list channel events") {
    forAllDbs { dbs =>
      val db = dbs.audit
      val now = TimestampMilli.now()
      val channelId1 = randomBytes32()
      val channelId2 = randomBytes32()
      val remoteNodeId = randomKey().publicKey
      val e1 = ChannelEvent(channelId1, remoteNodeId, randomTxId(), "anchor_outputs", 100_000 sat, isChannelOpener = true, isPrivate = false, "mutual-close", now - 1.minute)
      val e2 = ChannelEvent(channelId2, remoteNodeId, randomTxId(), "taproot", 150_000 sat, isChannelOpener = false, isPrivate = true, "funding", now)

      db.add(e1)
      db.add(e2)

      assert(db.listChannelEvents(randomBytes32(), from = TimestampMilli(0L), to = now + 1.minute).isEmpty)
      assert(db.listChannelEvents(channelId1, from = TimestampMilli(0L), to = now + 1.minute) == Seq(e1))
      assert(db.listChannelEvents(channelId1, from = TimestampMilli(0L), to = now - 10.minute).isEmpty)
      assert(db.listChannelEvents(randomKey().publicKey, from = TimestampMilli(0L), to = now + 1.minute).isEmpty)
      assert(db.listChannelEvents(remoteNodeId, from = TimestampMilli(0L), to = now + 1.minute) == Seq(e1, e2))
      assert(db.listChannelEvents(remoteNodeId, from = TimestampMilli(0L), to = now - 30.seconds) == Seq(e1))
    }
  }

  test("add/list transaction events") {
    forAllDbs { dbs =>
      val db = dbs.audit
      val now = TimestampMilli.now()
      val channelId1 = randomBytes32()
      val channelId2 = randomBytes32()
      val remoteNodeId = randomKey().publicKey
      val p1a = TransactionPublished(channelId1, remoteNodeId, Transaction(2, Nil, Seq(TxOut(50_000 sat, Script.pay2wpkh(remoteNodeId))), 0), 50 sat, 0 sat, "funding", None, now - 10.seconds)
      val p1b = TransactionPublished(channelId1, remoteNodeId, Transaction(2, Nil, Seq(TxOut(100_000 sat, Script.pay2wpkh(remoteNodeId))), 0), 75 sat, 25 sat, "splice", None, now - 5.seconds)
      val p2 = TransactionPublished(channelId2, remoteNodeId, Transaction(2, Nil, Seq(TxOut(200_000 sat, Script.pay2wpkh(remoteNodeId))), 0), 0 sat, 0 sat, "local-close", None, now - 1.seconds)
      val c1 = TransactionConfirmed(channelId1, remoteNodeId, p1a.tx, now)
      val c2 = TransactionConfirmed(channelId2, remoteNodeId, Transaction(2, Nil, Seq(TxOut(150_000 sat, hex"1234")), 0), now)

      db.add(p1a)
      db.add(p1b)
      db.add(p2)
      db.add(c1)
      db.add(c2)

      assert(db.listPublished(randomBytes32()).isEmpty)
      assert(db.listPublished(randomKey().publicKey, from = TimestampMilli(0L), to = now + 1.seconds).isEmpty)
      assert(db.listPublished(channelId1) == Seq(PublishedTransaction(p1a), PublishedTransaction(p1b)))
      assert(db.listPublished(channelId2) == Seq(PublishedTransaction(p2)))
      assert(db.listPublished(remoteNodeId, from = now - 1.minute, to = now) == Seq(PublishedTransaction(p1a), PublishedTransaction(p1b), PublishedTransaction(p2)))
      assert(db.listPublished(remoteNodeId, from = now - 6.seconds, to = now) == Seq(PublishedTransaction(p1b), PublishedTransaction(p2)))
    }
  }

  test("add/list payment events") {
    forAllDbs { dbs =>
      val db = dbs.audit

      val now = TimestampMilli.now()
      val uuid1 = UUID.randomUUID()
      val uuid2 = UUID.randomUUID()
      val uuid3 = UUID.randomUUID()
      val remoteNodeId1 = randomKey().publicKey
      val remoteNodeId2 = randomKey().publicKey
      val channelId1 = randomBytes32()
      val channelId2 = randomBytes32()
      val preimage1 = randomBytes32()
      val paymentHash1 = Crypto.sha256(preimage1)
      val preimage2 = randomBytes32()
      val paymentHash2 = Crypto.sha256(preimage2)

      val e1 = PaymentSent(ZERO_UUID, preimage1, 40000 msat, remoteNodeId2, PaymentSent.PaymentPart(ZERO_UUID, PaymentEvent.OutgoingPayment(channelId1, remoteNodeId1, 42000 msat, now - 75.seconds), 1000 msat, None, now - 100.seconds) :: Nil, None, now - 100.seconds)
      val pp2a = PaymentEvent.IncomingPayment(channelId1, remoteNodeId1, 42000 msat, now - 1.seconds)
      val pp2b = PaymentEvent.IncomingPayment(channelId2, remoteNodeId2, 42100 msat, now)
      val e2 = PaymentReceived(paymentHash1, pp2a :: pp2b :: Nil)
      val e3 = ChannelPaymentRelayed(paymentHash1, Seq(PaymentEvent.IncomingPayment(channelId1, remoteNodeId1, 42000 msat, now - 3.seconds)), Seq(PaymentEvent.OutgoingPayment(channelId2, remoteNodeId2, 1000 msat, now)))
      val pp4a = PaymentSent.PaymentPart(uuid1, PaymentEvent.OutgoingPayment(channelId1, remoteNodeId1, 42000 msat, now - 15.seconds), 1000 msat, None, startedAt = now - 30.seconds)
      val pp4b = PaymentSent.PaymentPart(uuid2, PaymentEvent.OutgoingPayment(channelId2, remoteNodeId2, 42100 msat, now - 10.seconds), 900 msat, None, startedAt = now - 25.seconds)
      val e4 = PaymentSent(uuid3, preimage1, 84100 msat, remoteNodeId2, pp4a :: pp4b :: Nil, None, startedAt = now - 30.seconds)
      val pp5 = PaymentSent.PaymentPart(uuid2, PaymentEvent.OutgoingPayment(channelId1, remoteNodeId1, 42000 msat, settledAt = now + 10.minutes), 1000 msat, None, startedAt = now + 9.minutes)
      val e5 = PaymentSent(uuid2, preimage1, 42000 msat, remoteNodeId1, pp5 :: Nil, None, startedAt = now + 9.minutes)
      val e6 = TrampolinePaymentRelayed(randomBytes32(),
        Seq(
          PaymentEvent.IncomingPayment(channelId1, remoteNodeId1, 20000 msat, now - 7.seconds),
          PaymentEvent.IncomingPayment(channelId1, remoteNodeId1, 22000 msat, now - 5.seconds)
        ),
        Seq(
          PaymentEvent.OutgoingPayment(channelId2, remoteNodeId2, 10000 msat, now + 1.milli),
          PaymentEvent.OutgoingPayment(channelId2, remoteNodeId2, 12000 msat, now + 2.milli),
          PaymentEvent.OutgoingPayment(channelId1, remoteNodeId1, 15000 msat, now + 3.milli)
        ),
        randomKey().publicKey, 30000 msat)
      val e7 = ChannelPaymentRelayed(paymentHash2, Seq(PaymentEvent.IncomingPayment(channelId1, remoteNodeId1, 13000 msat, now - 5.seconds)), Seq(PaymentEvent.OutgoingPayment(channelId2, remoteNodeId2, 11000 msat, now + 4.milli)))
      val e8 = ChannelPaymentRelayed(paymentHash2, Seq(PaymentEvent.IncomingPayment(channelId2, remoteNodeId2, 15000 msat, now - 4.seconds)), Seq(PaymentEvent.OutgoingPayment(channelId1, remoteNodeId1, 12500 msat, now + 5.milli)))

      db.add(e1)
      db.add(e2)
      db.add(e3)
      db.add(e4)
      db.add(e5)
      db.add(e6)
      db.add(e7)
      db.add(e8)

      assert(db.listSent(from = now - 15.minutes, to = now + 15.minute).toList == List(e1, e4, e5))
      assert(db.listSent(from = now - 80.seconds, to = now - 70.seconds).toList == List(e1))
      assert(db.listSent(from = now - 15.minutes, to = now + 15.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listSent(from = now - 15.minutes, to = now + 15.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e1, e4))
      assert(db.listSent(from = now - 15.minutes, to = now + 15.minute, Some(Paginated(count = 2, skip = 1))).toList == List(e4, e5))
      assert(db.listSent(from = now - 15.minutes, to = now + 15.minute, Some(Paginated(count = 2, skip = 2))).toList == List(e5))
      assert(db.listSent(from = now - 15.minutes, to = now + 15.minute, Some(Paginated(count = 2, skip = 3))).toList == List())
      assert(db.listReceived(from = now - 5.seconds, to = now + 5.seconds).toList == List(e2))
      assert(db.listReceived(from = now - 5.seconds, to = now + 5.seconds, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listReceived(from = now - 5.seconds, to = now + 5.seconds, Some(Paginated(count = 2, skip = 0))).toList == List(e2))
      assert(db.listReceived(from = now - 5.seconds, to = now + 5.seconds, Some(Paginated(count = 2, skip = 1))).toList == List())
      assert(db.listRelayed(from = now - 10.seconds, to = now + 1.minute).toList == List(e3, e6, e7, e8))
      assert(db.listRelayed(from = now - 10.seconds, to = now + 1.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listRelayed(from = now - 10.seconds, to = now + 1.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e3, e6))
      assert(db.listRelayed(from = now - 10.seconds, to = now + 1.minute, Some(Paginated(count = 2, skip = 1))).toList == List(e6, e7))
      assert(db.listRelayed(from = now - 10.seconds, to = now + 1.minute, Some(Paginated(count = 2, skip = 4))).toList == List())
    }
  }

  test("stats") {
    forAllDbs { dbs =>
      val db = dbs.audit

      val n2 = randomKey().publicKey
      val n3 = randomKey().publicKey
      val n4 = randomKey().publicKey

      val c1 = ByteVector32.One
      val c2 = c1.copy(bytes = 0x02b +: c1.tail)
      val c3 = c1.copy(bytes = 0x03b +: c1.tail)
      val c4 = c1.copy(bytes = 0x04b +: c1.tail)
      val c5 = c1.copy(bytes = 0x05b +: c1.tail)
      val c6 = c1.copy(bytes = 0x06b +: c1.tail)

      db.add(ChannelPaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 46000 msat, 1000 unixms)), Seq(PaymentEvent.OutgoingPayment(c1, randomKey().publicKey, 44000 msat, 1001 unixms))))
      db.add(ChannelPaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 41000 msat, 1002 unixms)), Seq(PaymentEvent.OutgoingPayment(c1, randomKey().publicKey, 40000 msat, 1003 unixms))))
      db.add(ChannelPaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c5, randomKey().publicKey, 43000 msat, 1004 unixms)), Seq(PaymentEvent.OutgoingPayment(c1, randomKey().publicKey, 42000 msat, 1005 unixms))))
      db.add(ChannelPaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c5, randomKey().publicKey, 42000 msat, 1006 unixms)), Seq(PaymentEvent.OutgoingPayment(c2, randomKey().publicKey, 40000 msat, 1007 unixms))))
      db.add(ChannelPaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c5, randomKey().publicKey, 45000 msat, 1008 unixms)), Seq(PaymentEvent.OutgoingPayment(c6, randomKey().publicKey, 40000 msat, 1009 unixms))))
      db.add(TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 25000 msat, 1010 unixms)), Seq(PaymentEvent.OutgoingPayment(c4, randomKey().publicKey, 20000 msat, 1011 unixms)), randomKey().publicKey, 15000 msat))
      db.add(TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 46000 msat, 1012 unixms)), Seq(PaymentEvent.OutgoingPayment(c2, randomKey().publicKey, 16000 msat, 1013 unixms), PaymentEvent.OutgoingPayment(c4, randomKey().publicKey, 10000 msat, 1014 unixms), PaymentEvent.OutgoingPayment(c4, randomKey().publicKey, 14000 msat, 1015 unixms)), randomKey().publicKey, 37000 msat))

      // The following confirmed txs will be taken into account.
      db.add(TransactionPublished(c2, n2, Transaction(2, Nil, Seq(TxOut(5000 sat, hex"12345")), 0), 200 sat, 100 sat, "funding", None))
      db.add(TransactionConfirmed(c2, n2, Transaction(2, Nil, Seq(TxOut(5000 sat, hex"12345")), 0)))
      db.add(TransactionPublished(c2, n2, Transaction(2, Nil, Seq(TxOut(4000 sat, hex"00112233")), 0), 300 sat, 200 sat, "mutual", None))
      db.add(TransactionConfirmed(c2, n2, Transaction(2, Nil, Seq(TxOut(4000 sat, hex"00112233")), 0)))
      db.add(TransactionPublished(c3, n3, Transaction(2, Nil, Seq(TxOut(8000 sat, hex"deadbeef")), 0), 400 sat, 50 sat, "funding", None))
      db.add(TransactionConfirmed(c3, n3, Transaction(2, Nil, Seq(TxOut(8000 sat, hex"deadbeef")), 0)))
      db.add(TransactionPublished(c4, n4, Transaction(2, Nil, Seq(TxOut(6000 sat, hex"0000000000")), 0), 500 sat, 0 sat, "funding", None))
      db.add(TransactionConfirmed(c4, n4, Transaction(2, Nil, Seq(TxOut(6000 sat, hex"0000000000")), 0)))
      // The following txs will not be taken into account.
      db.add(TransactionPublished(c2, n2, Transaction(2, Nil, Seq(TxOut(5000 sat, hex"12345")), 0), 1000 sat, 0 sat, "funding", None)) // duplicate
      db.add(TransactionPublished(c4, n4, Transaction(2, Nil, Seq(TxOut(4500 sat, hex"1111222233")), 0), 500 sat, 150 sat, "funding", None)) // unconfirmed
      db.add(TransactionConfirmed(c4, n4, Transaction(2, Nil, Seq(TxOut(2500 sat, hex"ffffff")), 0))) // doesn't match a published tx

      assert(db.listPublished(randomBytes32()).isEmpty)
      assert(db.listPublished(c4).map(_.txId).toSet.size == 2)
      assert(db.listPublished(c4).map(_.desc) == Seq("funding", "funding"))

      // NB: we only count a relay fee for the outgoing channel, no the incoming one.
      assert(db.stats(0 unixms, TimestampMilli.now() + 1.milli) == Seq(
        Stats(channelId = c1, direction = "IN", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 0 sat),
        Stats(channelId = c1, direction = "OUT", avgPaymentAmount = 42 sat, paymentCount = 3, relayFee = 4 sat, networkFee = 0 sat),
        Stats(channelId = c2, direction = "IN", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 500 sat),
        Stats(channelId = c2, direction = "OUT", avgPaymentAmount = 28 sat, paymentCount = 2, relayFee = 4 sat, networkFee = 500 sat),
        Stats(channelId = c3, direction = "IN", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 400 sat),
        Stats(channelId = c3, direction = "OUT", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 400 sat),
        Stats(channelId = c4, direction = "IN", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 500 sat),
        Stats(channelId = c4, direction = "OUT", avgPaymentAmount = 22 sat, paymentCount = 2, relayFee = 9 sat, networkFee = 500 sat),
        Stats(channelId = c5, direction = "IN", avgPaymentAmount = 43 sat, paymentCount = 3, relayFee = 0 sat, networkFee = 0 sat),
        Stats(channelId = c5, direction = "OUT", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 0 sat),
        Stats(channelId = c6, direction = "IN", avgPaymentAmount = 39 sat, paymentCount = 4, relayFee = 0 sat, networkFee = 0 sat),
        Stats(channelId = c6, direction = "OUT", avgPaymentAmount = 40 sat, paymentCount = 1, relayFee = 5 sat, networkFee = 0 sat),
      ))
      assert(db.stats(0 unixms, TimestampMilli.now() + 1.milli, Some(Paginated(2, 3))) == Seq(
        Stats(channelId = c2, direction = "OUT", avgPaymentAmount = 28 sat, paymentCount = 2, relayFee = 4 sat, networkFee = 500 sat),
        Stats(channelId = c3, direction = "IN", avgPaymentAmount = 0 sat, paymentCount = 0, relayFee = 0 sat, networkFee = 400 sat),
      ))
    }
  }

  ignore("relay stats performance", Tag("perf")) {
    forAllDbs { dbs =>
      val db = dbs.audit
      val nodeCount = 100
      val channelCount = 1000
      val eventCount = 100000
      val nodeIds = (1 to nodeCount).map(_ => randomKey().publicKey)
      val channelIds = (1 to channelCount).map(_ => randomBytes32())
      // Fund channels.
      channelIds.foreach(channelId => {
        val nodeId = nodeIds(Random.nextInt(nodeCount))
        val fundingTx = Transaction(0, Seq.empty, Seq(TxOut(5000 sat, Script.pay2wpkh(nodeId))), 0)
        db.add(TransactionPublished(channelId, nodeId, fundingTx, 100 sat, 0 sat, "funding", None))
        db.add(TransactionConfirmed(channelId, nodeId, fundingTx))
      })
      // Add relay events.
      (1 to eventCount).foreach(_ => {
        // 25% trampoline relays.
        if (Random.nextInt(4) == 0) {
          val outgoingCount = 1 + Random.nextInt(4)
          val incoming = Seq(PaymentEvent.IncomingPayment(randomBytes32(), randomKey().publicKey, 10000 msat, TimestampMilli.now() - 3.seconds))
          val outgoing = (1 to outgoingCount).map(_ => PaymentEvent.OutgoingPayment(channelIds(Random.nextInt(channelCount)), randomKey().publicKey, Random.nextInt(2000).msat, TimestampMilli.now()))
          db.add(TrampolinePaymentRelayed(randomBytes32(), incoming, outgoing, randomKey().publicKey, 5000 msat))
        } else {
          val toChannelId = channelIds(Random.nextInt(channelCount))
          db.add(ChannelPaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(randomBytes32(), randomKey().publicKey, 10000 msat, TimestampMilli.now() - 2.seconds)), Seq(PaymentEvent.OutgoingPayment(toChannelId, randomKey().publicKey, Random.nextInt(10000).msat, TimestampMilli.now()))))
        }
      })
      // Test starts here.
      val start = TimestampMilli.now()
      assert(db.stats(0 unixms, start + 1.milli).nonEmpty)
      val end = TimestampMilli.now()
      fail(s"took ${end - start}ms")
    }
  }

  test("ignore invalid values in the DB") {
    forAllDbs { dbs =>
      val db = dbs.audit
      val sqlite = dbs.connection
      val isPg = dbs.isInstanceOf[TestPgDatabases]
      val table = if (isPg) "audit.relayed" else "relayed"

      using(sqlite.prepareStatement(s"INSERT INTO $table (payment_hash, amount_msat, channel_id, direction, relay_type, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        if (isPg) statement.setString(1, randomBytes32().toHex) else statement.setBytes(1, randomBytes32().toArray)
        statement.setLong(2, 42)
        if (isPg) statement.setString(3, randomBytes32().toHex) else statement.setBytes(3, randomBytes32().toArray)
        statement.setString(4, "IN")
        statement.setString(5, "unknown") // invalid relay type
        if (isPg) statement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(10))) else statement.setLong(6, 10)
        statement.executeUpdate()
      }

      using(sqlite.prepareStatement(s"INSERT INTO $table (payment_hash, amount_msat, channel_id, direction, relay_type, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        if (isPg) statement.setString(1, randomBytes32().toHex) else statement.setBytes(1, randomBytes32().toArray)
        statement.setLong(2, 51)
        if (isPg) statement.setString(3, randomBytes32().toHex) else statement.setBytes(3, randomBytes32().toArray)
        statement.setString(4, "UP") // invalid direction
        statement.setString(5, "channel")
        if (isPg) statement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(20))) else statement.setLong(6, 20)
        statement.executeUpdate()
      }

      val paymentHash = randomBytes32()
      val channelId = randomBytes32()

      using(sqlite.prepareStatement(s"INSERT INTO $table (payment_hash, amount_msat, channel_id, direction, relay_type, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        if (isPg) statement.setString(1, paymentHash.toHex) else statement.setBytes(1, paymentHash.toArray)
        statement.setLong(2, 65)
        if (isPg) statement.setString(3, channelId.toHex) else statement.setBytes(3, channelId.toArray)
        statement.setString(4, "IN") // missing a corresponding OUT
        statement.setString(5, "channel")
        if (isPg) statement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(30))) else statement.setLong(6, 30)
        statement.executeUpdate()
      }

      assert(db.listRelayed(0 unixms, 40 unixms, None) == Nil)
    }
  }

  test("add channel update") {
    forAllDbs { dbs =>
      val channelId = randomBytes32()
      val scid = ShortChannelId(123)
      val remoteNodeId = randomKey().publicKey
      val u = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey(), remoteNodeId, scid, CltvExpiryDelta(56), 2000 msat, 1000 msat, 999, 1000000000 msat)
      dbs.audit.addChannelUpdate(ChannelUpdateParametersChanged(null, channelId, remoteNodeId, u))
    }
  }

  test("add experiment metrics") {
    forAllDbs { dbs =>
      val isPg = dbs.isInstanceOf[TestPgDatabases]
      val recipientNodeId = PublicKey(hex"03f5b1f2768140178e1daac0fec11fce2eec6beec3ed64862bfb1114f7bc535b48")
      val hints = Seq(Seq(ExtraHop(
        PublicKey(hex"033f2d90d6ba1f771e4b3586b35cc9f825cfcb7cdd7edaa2bfd63f0cb81b17580e"),
        ShortChannelId(1),
        1000 msat,
        100,
        CltvExpiryDelta(144)
      ), ExtraHop(
        PublicKey(hex"02c15a88ff263cec5bf79c315b17b7f2e083f71d62a880e30281faaac0898cb2b7"),
        ShortChannelId(2),
        900 msat,
        200,
        CltvExpiryDelta(12)
      )), Seq(ExtraHop(
        PublicKey(hex"026ec3e3438308519a75ca4496822a6c1e229174fbcaadeeb174704c377112c331"),
        ShortChannelId(3),
        800 msat,
        300,
        CltvExpiryDelta(78)
      )))
      val extraEdges = hints.flatMap(Bolt11Invoice.toExtraEdges(_, recipientNodeId))
      dbs.audit.addPathFindingExperimentMetrics(PathFindingExperimentMetrics(randomBytes32(), 100000000 msat, 3000 msat, status = "SUCCESS", 37 millis, TimestampMilli.now(), isMultiPart = false, "my-test-experiment", recipientNodeId, extraEdges))

      val table = if (isPg) "audit.path_finding_metrics" else "path_finding_metrics"
      val hint_column = if (isPg) ", routing_hints" else ""
      using(dbs.connection.prepareStatement(s"SELECT amount_msat, status, fees_msat, duration_ms, experiment_name, recipient_node_id $hint_column FROM $table")) { statement =>
        val result = statement.executeQuery()
        assert(result.next())
        assert(result.getLong(1) == 100000000)
        assert(result.getString(2) == "SUCCESS")
        assert(result.getLong(3) == 3000)
        assert(result.getLong(4) == 37)
        assert(result.getString(5) == "my-test-experiment")
        if (isPg) {
          assert(result.getString(6) == recipientNodeId.toHex)
          assert(result.getString(7) == """[{"feeBase": 1000, "htlcMinimum": 1, "sourceNodeId": "033f2d90d6ba1f771e4b3586b35cc9f825cfcb7cdd7edaa2bfd63f0cb81b17580e", "targetNodeId": "02c15a88ff263cec5bf79c315b17b7f2e083f71d62a880e30281faaac0898cb2b7", "shortChannelId": "0x0x1", "cltvExpiryDelta": 144, "feeProportionalMillionths": 100}, {"feeBase": 900, "htlcMinimum": 1, "sourceNodeId": "02c15a88ff263cec5bf79c315b17b7f2e083f71d62a880e30281faaac0898cb2b7", "targetNodeId": "03f5b1f2768140178e1daac0fec11fce2eec6beec3ed64862bfb1114f7bc535b48", "shortChannelId": "0x0x2", "cltvExpiryDelta": 12, "feeProportionalMillionths": 200}, {"feeBase": 800, "htlcMinimum": 1, "sourceNodeId": "026ec3e3438308519a75ca4496822a6c1e229174fbcaadeeb174704c377112c331", "targetNodeId": "03f5b1f2768140178e1daac0fec11fce2eec6beec3ed64862bfb1114f7bc535b48", "shortChannelId": "0x0x3", "cltvExpiryDelta": 78, "feeProportionalMillionths": 300}]""")
        }
        assert(!result.next())
      }
    }
  }

  test("migrate audit db to v14") {
    val channelId1 = randomBytes32()
    val channelId2 = randomBytes32()
    val remoteNodeId1 = randomKey().publicKey
    val remoteNodeId2 = randomKey().publicKey
    val fundingTx = Transaction(2, Seq(TxIn(OutPoint(randomTxId(), 2), Nil, 0)), Seq(TxOut(150_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    val now = TimestampMilli.now()
    val channelCreated = ChannelEvent(channelId1, remoteNodeId1, fundingTx.txid, "anchor_outputs", 100_000 sat, isChannelOpener = true, isPrivate = false, "created", now)
    val txPublished = TransactionPublished(channelId1, remoteNodeId1, fundingTx, 200 sat, 100 sat, "funding", None)
    val txConfirmed = TransactionConfirmed(channelId1, remoteNodeId1, fundingTx)
    val paymentSent = PaymentSent(UUID.randomUUID(), randomBytes32(), 25_000_000 msat, remoteNodeId2, Seq(PaymentSent.PaymentPart(UUID.randomUUID(), PaymentEvent.OutgoingPayment(channelId1, remoteNodeId1, 24_999_999 msat, now), 561 msat, None, now - 10.seconds)), None, now - 10.seconds)
    val paymentReceived = PaymentReceived(randomBytes32(), Seq(PaymentEvent.IncomingPayment(channelId1, remoteNodeId1, 15_350 msat, now - 1.seconds)))
    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // We simulate the DB as it was before eclair v14.
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE SCHEMA audit")
              statement.executeUpdate("CREATE TABLE audit.sent (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_amount_msat BIGINT NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, recipient_node_id TEXT NOT NULL, to_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.received (amount_msat BIGINT NOT NULL, payment_hash TEXT NOT NULL, from_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, mining_fee_sat BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON audit.sent(timestamp)")
              statement.executeUpdate("CREATE INDEX received_timestamp_idx ON audit.received(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON audit.channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON audit.transactions_published(channel_id)")
              statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON audit.transactions_published(timestamp)")
              statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON audit.transactions_confirmed(timestamp)")
              setVersion(statement, "audit", 13)
            }
            // We insert some data into the tables we'll modify.
            using(connection.prepareStatement("INSERT INTO audit.sent VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setLong(1, paymentSent.parts.head.amountWithFees.toLong)
              statement.setLong(2, paymentSent.parts.head.feesPaid.toLong)
              statement.setLong(3, paymentSent.recipientAmount.toLong)
              statement.setString(4, paymentSent.parts.head.id.toString)
              statement.setString(5, paymentSent.id.toString)
              statement.setString(6, paymentSent.paymentHash.toHex)
              statement.setString(7, paymentSent.paymentPreimage.toHex)
              statement.setString(8, paymentSent.recipientNodeId.value.toHex)
              statement.setString(9, paymentSent.parts.head.channelId.toHex)
              statement.setTimestamp(10, paymentSent.parts.head.settledAt.toSqlTimestamp)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO audit.received VALUES (?, ?, ?, ?)")) { statement =>
              statement.setLong(1, paymentReceived.parts.head.amount.toLong)
              statement.setString(2, paymentReceived.paymentHash.toHex)
              statement.setString(3, paymentReceived.parts.head.channelId.toHex)
              statement.setTimestamp(4, paymentReceived.parts.head.receivedAt.toSqlTimestamp)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO audit.channel_events VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setString(1, channelId1.toHex)
              statement.setString(2, remoteNodeId1.toHex)
              statement.setLong(3, 100_000)
              statement.setBoolean(4, true)
              statement.setBoolean(5, false)
              statement.setString(6, "mutual")
              statement.setTimestamp(7, now.toSqlTimestamp)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO audit.transactions_published VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setString(1, fundingTx.txid.value.toHex)
              statement.setString(2, channelId1.toHex)
              statement.setString(3, remoteNodeId1.toHex)
              statement.setLong(4, txPublished.localMiningFee.toLong)
              statement.setString(5, txPublished.desc)
              statement.setTimestamp(6, txPublished.timestamp.toSqlTimestamp)
              statement.executeUpdate()
            }
          },
          dbName = PgAuditDb.DB_NAME,
          targetVersion = PgAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            val migratedDb = dbs.audit
            using(connection.createStatement()) { statement => assert(getVersion(statement, "audit").contains(PgAuditDb.CURRENT_VERSION)) }
            // We've created new tables: previous data from the existing tables isn't available anymore through the API.
            assert(migratedDb.listChannelEvents(channelId1, 0 unixms, now + 1.minute).isEmpty)
            assert(migratedDb.listChannelEvents(remoteNodeId1, 0 unixms, now + 1.minute).isEmpty)
            assert(migratedDb.listPublished(channelId1).isEmpty)
            // But the data is still available in the database.
            Seq("audit.sent_before_v14", "audit.received_before_v14", "audit.channel_events_before_v14", "audit.transactions_published_before_v14").foreach(table => {
              using(connection.prepareStatement(s"SELECT * FROM $table")) { statement =>
                val result = statement.executeQuery()
                assert(result.next())
              }
            })
            // We can use the new tables immediately.
            migratedDb.add(paymentSent)
            assert(migratedDb.listSent(0 unixms, now + 1.minute) == Seq(paymentSent))
            migratedDb.add(paymentReceived)
            assert(migratedDb.listReceived(0 unixms, now + 1.minute) == Seq(paymentReceived))
            migratedDb.add(channelCreated)
            assert(migratedDb.listChannelEvents(channelId1, 0 unixms, now + 1.minute) == Seq(channelCreated))
            migratedDb.add(txPublished)
            migratedDb.add(txConfirmed)
            assert(migratedDb.listPublished(channelId1) == Seq(PublishedTransaction(txPublished)))
          }
        )
      case dbs: TestSqliteDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // We simulate the DB as it was before eclair v14.
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_amount_msat INTEGER NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, recipient_node_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE channel_updates (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, htlc_maximum_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE transactions_published (tx_id BLOB NOT NULL PRIMARY KEY, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, mining_fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE transactions_confirmed (tx_id BLOB NOT NULL PRIMARY KEY, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON sent(timestamp)")
              statement.executeUpdate("CREATE INDEX received_timestamp_idx ON received(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON channel_updates(channel_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON channel_updates(node_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON channel_updates(timestamp)")
              statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON transactions_published(channel_id)")
              statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON transactions_published(timestamp)")
              statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON transactions_confirmed(timestamp)")
              setVersion(statement, "audit", 10)
            }
            // We insert some data into the tables we'll modify.
            using(connection.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setLong(1, paymentSent.parts.head.amountWithFees.toLong)
              statement.setLong(2, paymentSent.parts.head.feesPaid.toLong)
              statement.setLong(3, paymentSent.recipientAmount.toLong)
              statement.setString(4, paymentSent.parts.head.id.toString)
              statement.setString(5, paymentSent.id.toString)
              statement.setBytes(6, paymentSent.paymentHash.toArray)
              statement.setBytes(7, paymentSent.paymentPreimage.toArray)
              statement.setBytes(8, paymentSent.recipientNodeId.value.toArray)
              statement.setBytes(9, paymentSent.parts.head.channelId.toArray)
              statement.setLong(10, paymentSent.parts.head.settledAt.toLong)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
              statement.setLong(1, paymentReceived.parts.head.amount.toLong)
              statement.setBytes(2, paymentReceived.paymentHash.toArray)
              statement.setBytes(3, paymentReceived.parts.head.channelId.toArray)
              statement.setLong(4, paymentReceived.parts.head.receivedAt.toLong)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO channel_events VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setBytes(1, channelId1.toArray)
              statement.setBytes(2, remoteNodeId1.value.toArray)
              statement.setLong(3, 100_000)
              statement.setBoolean(4, true)
              statement.setBoolean(5, false)
              statement.setString(6, "mutual")
              statement.setLong(7, now.toLong)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO transactions_published VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setBytes(1, fundingTx.txid.value.toArray)
              statement.setBytes(2, channelId1.toArray)
              statement.setBytes(3, remoteNodeId1.value.toArray)
              statement.setLong(4, txPublished.localMiningFee.toLong)
              statement.setString(5, txPublished.desc)
              statement.setLong(6, txPublished.timestamp.toLong)
              statement.executeUpdate()
            }
          },
          dbName = SqliteAuditDb.DB_NAME,
          targetVersion = SqliteAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            val migratedDb = dbs.audit
            using(connection.createStatement()) { statement => assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION)) }
            // We've created new tables: previous data from the existing tables isn't available anymore through the API.
            assert(migratedDb.listChannelEvents(channelId1, 0 unixms, now + 1.minute).isEmpty)
            assert(migratedDb.listChannelEvents(remoteNodeId1, 0 unixms, now + 1.minute).isEmpty)
            assert(migratedDb.listPublished(channelId1).isEmpty)
            // But the data is still available in the database.
            Seq("sent_before_v14", "received_before_v14", "channel_events_before_v14", "transactions_published_before_v14").foreach(table => {
              using(connection.prepareStatement(s"SELECT * FROM $table")) { statement =>
                val result = statement.executeQuery()
                assert(result.next())
              }
            })
            // We can use the new tables immediately.
            migratedDb.add(paymentSent)
            assert(migratedDb.listSent(0 unixms, now + 1.minute) == Seq(paymentSent))
            migratedDb.add(paymentReceived)
            assert(migratedDb.listReceived(0 unixms, now + 1.minute) == Seq(paymentReceived))
            migratedDb.add(channelCreated)
            assert(migratedDb.listChannelEvents(channelId1, 0 unixms, now + 1.minute) == Seq(channelCreated))
            migratedDb.add(txPublished)
            migratedDb.add(txConfirmed)
            assert(migratedDb.listPublished(channelId1) == Seq(PublishedTransaction(txPublished)))
          }
        )
    }
  }

}
