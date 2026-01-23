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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Helpers.Closing.MutualClose
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.Stats
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgAuditDb
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

  test("add/list events") {
    forAllDbs { dbs =>
      val db = dbs.audit
      // We don't yet store the remote node_id in our DB: we use this placeholder instead.
      // TODO: update this test once we store the remote node_id for incoming/outgoing payments.
      val dummyRemoteNodeId = PrivateKey(ByteVector32.One).publicKey

      val now = TimestampMilli.now()
      val e1 = PaymentSent(ZERO_UUID, randomBytes32(), 40000 msat, randomKey().publicKey, PaymentSent.PaymentPart(ZERO_UUID, PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 42000 msat, now), 1000 msat, None, now) :: Nil, None, now)
      val pp2a = PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 42000 msat, now)
      val pp2b = PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 42100 msat, now)
      val e2 = PaymentReceived(randomBytes32(), pp2a :: pp2b :: Nil)
      val e3 = ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 42000 msat, now - 3.seconds), PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 1000 msat, now))
      val e4a = TransactionPublished(randomBytes32(), randomKey().publicKey, Transaction(0, Seq.empty, Seq.empty, 0), 42 sat, "mutual")
      val e4b = TransactionConfirmed(e4a.channelId, e4a.remoteNodeId, e4a.tx)
      val e4c = TransactionConfirmed(randomBytes32(), randomKey().publicKey, Transaction(2, Nil, TxOut(500 sat, hex"1234") :: Nil, 0))
      val pp5a = PaymentSent.PaymentPart(UUID.randomUUID(), PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 42000 msat, 0 unixms), 1000 msat, None, startedAt = 0 unixms)
      val pp5b = PaymentSent.PaymentPart(UUID.randomUUID(), PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 42100 msat, 1 unixms), 900 msat, None, startedAt = 1 unixms)
      val e5 = PaymentSent(UUID.randomUUID(), randomBytes32(), 84100 msat, randomKey().publicKey, pp5a :: pp5b :: Nil, None, startedAt = 0 unixms)
      val pp6 = PaymentSent.PaymentPart(UUID.randomUUID(), PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 42000 msat, settledAt = now + 10.minutes), 1000 msat, None, startedAt = now + 10.minutes)
      val e6 = PaymentSent(UUID.randomUUID(), randomBytes32(), 42000 msat, randomKey().publicKey, pp6 :: Nil, None, startedAt = now + 10.minutes)
      val e7 = ChannelEvent(randomBytes32(), randomKey().publicKey, randomTxId(), 456123000 sat, isChannelOpener = true, isPrivate = false, ChannelEvent.EventType.Closed(MutualClose(null)))
      val e10 = TrampolinePaymentRelayed(randomBytes32(),
        Seq(
          PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 20000 msat, now - 7.seconds),
          PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 22000 msat, now - 5.seconds)
        ),
        Seq(
          PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 10000 msat, now + 1.milli),
          PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 12000 msat, now + 2.milli),
          PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 15000 msat, now + 3.milli)
        ),
        randomKey().publicKey, 30000 msat)
      val multiPartPaymentHash = randomBytes32()
      val e11 = ChannelPaymentRelayed(multiPartPaymentHash, PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 13000 msat, now - 5.seconds), PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 11000 msat, now + 4.milli))
      val e12 = ChannelPaymentRelayed(multiPartPaymentHash, PaymentEvent.IncomingPayment(randomBytes32(), dummyRemoteNodeId, 15000 msat, now - 4.seconds), PaymentEvent.OutgoingPayment(randomBytes32(), dummyRemoteNodeId, 12500 msat, now + 5.milli))

      db.add(e1)
      db.add(e2)
      db.add(e3)
      db.add(e4a)
      db.add(e4b)
      db.add(e4c)
      db.add(e5)
      db.add(e6)
      db.add(e7)
      db.add(e10)
      db.add(e11)
      db.add(e12)

      assert(db.listSent(from = TimestampMilli(0L), to = now + 15.minute).toList == List(e5, e1, e6))
      assert(db.listSent(from = TimestampMilli(100000L), to = now + 1.minute).toList == List(e1))
      assert(db.listSent(from = TimestampMilli(0L), to = now + 15.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listSent(from = TimestampMilli(0L), to = now + 15.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e5, e1))
      assert(db.listSent(from = TimestampMilli(0L), to = now + 15.minute, Some(Paginated(count = 2, skip = 1))).toList == List(e1, e6))
      assert(db.listSent(from = TimestampMilli(0L), to = now + 15.minute, Some(Paginated(count = 2, skip = 2))).toList == List(e6))
      assert(db.listSent(from = TimestampMilli(0L), to = now + 15.minute, Some(Paginated(count = 2, skip = 3))).toList == List())
      assert(db.listReceived(from = TimestampMilli(0L), to = now + 1.minute).toList == List(e2))
      assert(db.listReceived(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listReceived(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e2))
      assert(db.listReceived(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 2, skip = 1))).toList == List())
      assert(db.listRelayed(from = TimestampMilli(0L), to = now + 1.minute).toList == List(e3, e10, e11, e12))
      assert(db.listRelayed(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listRelayed(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e3, e10))
      assert(db.listRelayed(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 2, skip = 1))).toList == List(e10, e11))
      assert(db.listRelayed(from = TimestampMilli(0L), to = now + 1.minute, Some(Paginated(count = 2, skip = 4))).toList == List())
      assert(db.listNetworkFees(from = TimestampMilli(0L), to = now + 1.minute).size == 1)
      assert(db.listNetworkFees(from = TimestampMilli(0L), to = now + 1.minute).head.txType == "mutual")
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

      db.add(ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 46000 msat, 1000 unixms), PaymentEvent.OutgoingPayment(c1, randomKey().publicKey, 44000 msat, 1001 unixms)))
      db.add(ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 41000 msat, 1002 unixms), PaymentEvent.OutgoingPayment(c1, randomKey().publicKey, 40000 msat, 1003 unixms)))
      db.add(ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(c5, randomKey().publicKey, 43000 msat, 1004 unixms), PaymentEvent.OutgoingPayment(c1, randomKey().publicKey, 42000 msat, 1005 unixms)))
      db.add(ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(c5, randomKey().publicKey, 42000 msat, 1006 unixms), PaymentEvent.OutgoingPayment(c2, randomKey().publicKey, 40000 msat, 1007 unixms)))
      db.add(ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(c5, randomKey().publicKey, 45000 msat, 1008 unixms), PaymentEvent.OutgoingPayment(c6, randomKey().publicKey, 40000 msat, 1009 unixms)))
      db.add(TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 25000 msat, 1010 unixms)), Seq(PaymentEvent.OutgoingPayment(c4, randomKey().publicKey, 20000 msat, 1011 unixms)), randomKey().publicKey, 15000 msat))
      db.add(TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentEvent.IncomingPayment(c6, randomKey().publicKey, 46000 msat, 1012 unixms)), Seq(PaymentEvent.OutgoingPayment(c2, randomKey().publicKey, 16000 msat, 1013 unixms), PaymentEvent.OutgoingPayment(c4, randomKey().publicKey, 10000 msat, 1014 unixms), PaymentEvent.OutgoingPayment(c4, randomKey().publicKey, 14000 msat, 1015 unixms)), randomKey().publicKey, 37000 msat))

      // The following confirmed txs will be taken into account.
      db.add(TransactionPublished(c2, n2, Transaction(0, Seq.empty, Seq(TxOut(5000 sat, hex"12345")), 0), 200 sat, "funding"))
      db.add(TransactionConfirmed(c2, n2, Transaction(0, Seq.empty, Seq(TxOut(5000 sat, hex"12345")), 0)))
      db.add(TransactionPublished(c2, n2, Transaction(0, Seq.empty, Seq(TxOut(4000 sat, hex"00112233")), 0), 300 sat, "mutual"))
      db.add(TransactionConfirmed(c2, n2, Transaction(0, Seq.empty, Seq(TxOut(4000 sat, hex"00112233")), 0)))
      db.add(TransactionPublished(c3, n3, Transaction(0, Seq.empty, Seq(TxOut(8000 sat, hex"deadbeef")), 0), 400 sat, "funding"))
      db.add(TransactionConfirmed(c3, n3, Transaction(0, Seq.empty, Seq(TxOut(8000 sat, hex"deadbeef")), 0)))
      db.add(TransactionPublished(c4, n4, Transaction(0, Seq.empty, Seq(TxOut(6000 sat, hex"0000000000")), 0), 500 sat, "funding"))
      db.add(TransactionConfirmed(c4, n4, Transaction(0, Seq.empty, Seq(TxOut(6000 sat, hex"0000000000")), 0)))
      // The following txs will not be taken into account.
      db.add(TransactionPublished(c2, n2, Transaction(0, Seq.empty, Seq(TxOut(5000 sat, hex"12345")), 0), 1000 sat, "funding")) // duplicate
      db.add(TransactionPublished(c4, n4, Transaction(0, Seq.empty, Seq(TxOut(4500 sat, hex"1111222233")), 0), 500 sat, "funding")) // unconfirmed
      db.add(TransactionConfirmed(c4, n4, Transaction(0, Seq.empty, Seq(TxOut(2500 sat, hex"ffffff")), 0))) // doesn't match a published tx

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
        db.add(TransactionPublished(channelId, nodeId, fundingTx, 100 sat, "funding"))
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
          db.add(ChannelPaymentRelayed(randomBytes32(), PaymentEvent.IncomingPayment(randomBytes32(), randomKey().publicKey, 10000 msat, TimestampMilli.now() - 2.seconds), PaymentEvent.OutgoingPayment(toChannelId, randomKey().publicKey, Random.nextInt(10000).msat, TimestampMilli.now())))
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

}
