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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases, migrationCheck}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Helpers.Closing.MutualClose
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{NetworkFee, Stats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgAuditDb
import fr.acinq.eclair.db.pg.PgUtils.{getVersion, setVersion}
import fr.acinq.eclair.db.sqlite.SqliteAuditDb
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.PlaceHolderPubKey
import fr.acinq.eclair.wire.protocol.Error
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

      val e1 = PaymentSent(ZERO_UUID, randomBytes32(), randomBytes32(), 40000 msat, randomKey().publicKey, PaymentSent.PartialPayment(ZERO_UUID, 42000 msat, 1000 msat, randomBytes32(), None) :: Nil)
      val pp2a = PaymentReceived.PartialPayment(42000 msat, randomBytes32())
      val pp2b = PaymentReceived.PartialPayment(42100 msat, randomBytes32())
      val e2 = PaymentReceived(randomBytes32(), pp2a :: pp2b :: Nil)
      val e3 = ChannelPaymentRelayed(42000 msat, 1000 msat, randomBytes32(), randomBytes32(), randomBytes32())
      val e4a = TransactionPublished(randomBytes32(), randomKey().publicKey, Transaction(0, Seq.empty, Seq.empty, 0), 42 sat, "mutual")
      val e4b = TransactionConfirmed(e4a.channelId, e4a.remoteNodeId, e4a.tx)
      val e4c = TransactionConfirmed(randomBytes32(), randomKey().publicKey, Transaction(2, Nil, TxOut(500 sat, hex"1234") :: Nil, 0))
      val pp5a = PaymentSent.PartialPayment(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32(), None, timestamp = 0 unixms)
      val pp5b = PaymentSent.PartialPayment(UUID.randomUUID(), 42100 msat, 900 msat, randomBytes32(), None, timestamp = 1 unixms)
      val e5 = PaymentSent(UUID.randomUUID(), randomBytes32(), randomBytes32(), 84100 msat, randomKey().publicKey, pp5a :: pp5b :: Nil)
      val pp6 = PaymentSent.PartialPayment(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32(), None, timestamp = TimestampMilli.now() + 10.minutes)
      val e6 = PaymentSent(UUID.randomUUID(), randomBytes32(), randomBytes32(), 42000 msat, randomKey().publicKey, pp6 :: Nil)
      val e7 = ChannelEvent(randomBytes32(), randomKey().publicKey, 456123000 sat, isInitiator = true, isPrivate = false, ChannelEvent.EventType.Closed(MutualClose(null)))
      val e8 = ChannelErrorOccurred(null, randomBytes32(), randomKey().publicKey, LocalError(new RuntimeException("oops")), isFatal = true)
      val e9 = ChannelErrorOccurred(null, randomBytes32(), randomKey().publicKey, RemoteError(Error(randomBytes32(), "remote oops")), isFatal = true)
      val e10 = TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(20000 msat, randomBytes32()), PaymentRelayed.Part(22000 msat, randomBytes32())), Seq(PaymentRelayed.Part(10000 msat, randomBytes32()), PaymentRelayed.Part(12000 msat, randomBytes32()), PaymentRelayed.Part(15000 msat, randomBytes32())), randomKey().publicKey, 30000 msat)
      val multiPartPaymentHash = randomBytes32()
      val now = TimestampMilli.now()
      val e11 = ChannelPaymentRelayed(13000 msat, 11000 msat, multiPartPaymentHash, randomBytes32(), randomBytes32(), now)
      val e12 = ChannelPaymentRelayed(15000 msat, 12500 msat, multiPartPaymentHash, randomBytes32(), randomBytes32(), now)

      db.add(e1)
      db.add(e2)
      db.add(e3)
      db.add(e4a)
      db.add(e4b)
      db.add(e4c)
      db.add(e5)
      db.add(e6)
      db.add(e7)
      db.add(e8)
      db.add(e9)
      db.add(e10)
      db.add(e11)
      db.add(e12)

      assert(db.listSent(from = TimestampMilli(0L), to = TimestampMilli.now() + 15.minute).toList == List(e5, e1, e6))
      assert(db.listSent(from = TimestampMilli(100000L), to = TimestampMilli.now() + 1.minute).toList == List(e1))
      assert(db.listSent(from = TimestampMilli(0L), to = TimestampMilli.now() + 15.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listSent(from = TimestampMilli(0L), to = TimestampMilli.now() + 15.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e5, e1))
      assert(db.listSent(from = TimestampMilli(0L), to = TimestampMilli.now() + 15.minute, Some(Paginated(count = 2, skip = 1))).toList == List(e1, e6))
      assert(db.listSent(from = TimestampMilli(0L), to = TimestampMilli.now() + 15.minute, Some(Paginated(count = 2, skip = 2))).toList == List(e6))
      assert(db.listSent(from = TimestampMilli(0L), to = TimestampMilli.now() + 15.minute, Some(Paginated(count = 2, skip = 3))).toList == List())
      assert(db.listReceived(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute).toList == List(e2))
      assert(db.listReceived(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listReceived(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e2))
      assert(db.listReceived(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 2, skip = 1))).toList == List())
      assert(db.listRelayed(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute).toList == List(e3, e10, e11, e12))
      assert(db.listRelayed(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 0, skip = 0))).toList == List())
      assert(db.listRelayed(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 2, skip = 0))).toList == List(e3, e10))
      assert(db.listRelayed(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 2, skip = 1))).toList == List(e10, e11))
      assert(db.listRelayed(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute, Some(Paginated(count = 2, skip = 4))).toList == List())
      assert(db.listNetworkFees(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute).size == 1)
      assert(db.listNetworkFees(from = TimestampMilli(0L), to = TimestampMilli.now() + 1.minute).head.txType == "mutual")
    }
  }

  test("stats") {
    forAllDbs { dbs =>
      val db = dbs.audit

      val n2 = randomKey().publicKey
      val n3 = randomKey().publicKey
      val n4 = randomKey().publicKey

      val c1 = randomBytes32()
      val c2 = randomBytes32()
      val c3 = randomBytes32()
      val c4 = randomBytes32()
      val c5 = randomBytes32()
      val c6 = randomBytes32()

      db.add(ChannelPaymentRelayed(46000 msat, 44000 msat, randomBytes32(), c6, c1))
      db.add(ChannelPaymentRelayed(41000 msat, 40000 msat, randomBytes32(), c6, c1))
      db.add(ChannelPaymentRelayed(43000 msat, 42000 msat, randomBytes32(), c5, c1))
      db.add(ChannelPaymentRelayed(42000 msat, 40000 msat, randomBytes32(), c5, c2))
      db.add(ChannelPaymentRelayed(45000 msat, 40000 msat, randomBytes32(), c5, c6))
      db.add(TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(25000 msat, c6)), Seq(PaymentRelayed.Part(20000 msat, c4)), randomKey().publicKey, 15000 msat))
      db.add(TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(46000 msat, c6)), Seq(PaymentRelayed.Part(16000 msat, c2), PaymentRelayed.Part(10000 msat, c4), PaymentRelayed.Part(14000 msat, c4)), randomKey().publicKey, 37000 msat))

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

      // NB: we only count a relay fee for the outgoing channel, no the incoming one.
      assert(db.stats(0 unixms, TimestampMilli.now() + 1.milli).toSet == Set(
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
          val incoming = Seq(PaymentRelayed.Part(10000 msat, randomBytes32()))
          val outgoing = (1 to outgoingCount).map(_ => PaymentRelayed.Part(Random.nextInt(2000).msat, channelIds(Random.nextInt(channelCount))))
          db.add(TrampolinePaymentRelayed(randomBytes32(), incoming, outgoing, randomKey().publicKey, 5000 msat))
        } else {
          val toChannelId = channelIds(Random.nextInt(channelCount))
          db.add(ChannelPaymentRelayed(10000 msat, Random.nextInt(10000).msat, randomBytes32(), randomBytes32(), toChannelId))
        }
      })
      // Test starts here.
      val start = TimestampMilli.now()
      assert(db.stats(0 unixms, start + 1.milli).nonEmpty)
      val end = TimestampMilli.now()
      fail(s"took ${end - start}ms")
    }
  }

  test("migrate sqlite audit database v1 -> current") {

    val dbs = TestSqliteDatabases()

    val ps = PaymentSent(UUID.randomUUID(), randomBytes32(), randomBytes32(), 42000 msat, PrivateKey(ByteVector32.One).publicKey, PaymentSent.PartialPayment(UUID.randomUUID(), 42000 msat, 1000 msat, randomBytes32(), None) :: Nil)
    val pp1 = PaymentSent.PartialPayment(UUID.randomUUID(), 42001 msat, 1001 msat, randomBytes32(), None)
    val pp2 = PaymentSent.PartialPayment(UUID.randomUUID(), 42002 msat, 1002 msat, randomBytes32(), None)
    val ps1 = PaymentSent(UUID.randomUUID(), randomBytes32(), randomBytes32(), 84003 msat, PrivateKey(ByteVector32.One).publicKey, pp1 :: pp2 :: Nil)
    val e1 = ChannelErrorOccurred(null, randomBytes32(), randomKey().publicKey, LocalError(new RuntimeException("oops")), isFatal = true)
    val e2 = ChannelErrorOccurred(null, randomBytes32(), randomKey().publicKey, RemoteError(Error(randomBytes32(), "remote oops")), isFatal = true)

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        // simulate existing previous version db
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event STRING NOT NULL, timestamp INTEGER NOT NULL)")

          statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")

          setVersion(statement, "audit", 1)
        }

        // add a row (no ID on sent)
        using(connection.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setLong(1, ps.recipientAmount.toLong)
          statement.setLong(2, ps.feesPaid.toLong)
          statement.setBytes(3, ps.paymentHash.toArray)
          statement.setBytes(4, ps.paymentPreimage.toArray)
          statement.setBytes(5, ps.parts.head.toChannelId.toArray)
          statement.setLong(6, ps.timestamp.toLong)
          statement.executeUpdate()
        }
      },
      dbName = SqliteAuditDb.DB_NAME,
      targetVersion = SqliteAuditDb.CURRENT_VERSION,
      postCheck = connection => {
        // existing rows in the 'sent' table will use id=00000000-0000-0000-0000-000000000000 as default
        assert(dbs.audit.listSent(0 unixms, TimestampMilli.now() + 1.minute) == Seq(ps.copy(id = ZERO_UUID, parts = Seq(ps.parts.head.copy(id = ZERO_UUID)))))

        val postMigrationDb = new SqliteAuditDb(connection)

        using(connection.createStatement()) { statement =>
          assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
        }

        postMigrationDb.add(ps1)
        postMigrationDb.add(e1)
        postMigrationDb.add(e2)

        // the old record will have the UNKNOWN_UUID but the new ones will have their actual id
        val expected = Seq(ps.copy(id = ZERO_UUID, parts = Seq(ps.parts.head.copy(id = ZERO_UUID))), ps1)
        assert(postMigrationDb.listSent(0 unixms, TimestampMilli.now() + 1.minute) == expected)
      }
    )
  }

  test("migrate sqlite audit database v2 -> current") {
    val dbs = TestSqliteDatabases()

    val e1 = ChannelErrorOccurred(null, randomBytes32(), randomKey().publicKey, LocalError(new RuntimeException("oops")), isFatal = true)
    val e2 = ChannelErrorOccurred(null, randomBytes32(), randomKey().publicKey, RemoteError(Error(randomBytes32(), "remote oops")), isFatal = true)

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        // simulate existing previous version db
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL, id BLOB NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event STRING NOT NULL, timestamp INTEGER NOT NULL)")

          statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")

          setVersion(statement, "audit", 2)
        }
      },
      dbName = SqliteAuditDb.DB_NAME,
      targetVersion = SqliteAuditDb.CURRENT_VERSION,
      postCheck = connection => {
        val migratedDb = dbs.audit
        using(connection.createStatement()) { statement =>
          assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
        }
        migratedDb.add(e1)

        val postMigrationDb = new SqliteAuditDb(connection)
        using(connection.createStatement()) { statement =>
          assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
        }
        postMigrationDb.add(e2)
      }
    )
  }

  test("migrate sqlite audit database v3 -> current") {

    val dbs = TestSqliteDatabases()

    val pp1 = PaymentSent.PartialPayment(UUID.randomUUID(), 500 msat, 10 msat, randomBytes32(), None, 100 unixms)
    val pp2 = PaymentSent.PartialPayment(UUID.randomUUID(), 600 msat, 5 msat, randomBytes32(), None, 110 unixms)
    val ps1 = PaymentSent(UUID.randomUUID(), randomBytes32(), randomBytes32(), 1100 msat, PrivateKey(ByteVector32.One).publicKey, pp1 :: pp2 :: Nil)

    val relayed1 = ChannelPaymentRelayed(600 msat, 500 msat, randomBytes32(), randomBytes32(), randomBytes32(), 105 unixms)
    val relayed2 = ChannelPaymentRelayed(650 msat, 500 msat, randomBytes32(), randomBytes32(), randomBytes32(), 115 unixms)

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        // simulate existing previous version db
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS balance_updated (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, amount_msat INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, reserve_sat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL, id BLOB NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

          statement.executeUpdate("CREATE INDEX IF NOT EXISTS balance_updated_idx ON balance_updated(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")

          setVersion(statement, "audit", 3)
        }

        for (pp <- Seq(pp1, pp2)) {
          using(connection.prepareStatement("INSERT INTO sent (amount_msat, fees_msat, payment_hash, payment_preimage, to_channel_id, timestamp, id) VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setLong(1, pp.amount.toLong)
            statement.setLong(2, pp.feesPaid.toLong)
            statement.setBytes(3, ps1.paymentHash.toArray)
            statement.setBytes(4, ps1.paymentPreimage.toArray)
            statement.setBytes(5, pp.toChannelId.toArray)
            statement.setLong(6, pp.timestamp.toLong)
            statement.setBytes(7, pp.id.toString.getBytes)
            statement.executeUpdate()
          }
        }

        for (relayed <- Seq(relayed1, relayed2)) {
          using(connection.prepareStatement("INSERT INTO relayed (amount_in_msat, amount_out_msat, payment_hash, from_channel_id, to_channel_id, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setLong(1, relayed.amountIn.toLong)
            statement.setLong(2, relayed.amountOut.toLong)
            statement.setBytes(3, relayed.paymentHash.toArray)
            statement.setBytes(4, relayed.fromChannelId.toArray)
            statement.setBytes(5, relayed.toChannelId.toArray)
            statement.setLong(6, relayed.timestamp.toLong)
            statement.executeUpdate()
          }
        }
      },
      dbName = SqliteAuditDb.DB_NAME,
      targetVersion = SqliteAuditDb.CURRENT_VERSION,
      postCheck = connection => {
        val migratedDb = dbs.audit
        using(connection.createStatement()) { statement =>
          assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
        }
        assert(migratedDb.listSent(50 unixms, 150 unixms).toSet == Set(
          ps1.copy(id = pp1.id, recipientAmount = pp1.amount, parts = pp1 :: Nil),
          ps1.copy(id = pp2.id, recipientAmount = pp2.amount, parts = pp2 :: Nil)
        ))
        assert(migratedDb.listRelayed(100 unixms, 120 unixms) == Seq(relayed1, relayed2))

        val postMigrationDb = new SqliteAuditDb(connection)
        using(connection.createStatement()) { statement =>
          assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
        }
        val ps2 = PaymentSent(UUID.randomUUID(), randomBytes32(), randomBytes32(), 1100 msat, randomKey().publicKey, Seq(
          PaymentSent.PartialPayment(UUID.randomUUID(), 500 msat, 10 msat, randomBytes32(), None, 160 unixms),
          PaymentSent.PartialPayment(UUID.randomUUID(), 600 msat, 5 msat, randomBytes32(), None, 165 unixms)
        ))
        val relayed3 = TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(450 msat, randomBytes32()), PaymentRelayed.Part(500 msat, randomBytes32())), Seq(PaymentRelayed.Part(800 msat, randomBytes32())), randomKey().publicKey, 700 msat, 150 unixms)
        postMigrationDb.add(ps2)
        assert(postMigrationDb.listSent(155 unixms, 200 unixms) == Seq(ps2))
        postMigrationDb.add(relayed3)
        assert(postMigrationDb.listRelayed(100 unixms, 160 unixms) == Seq(relayed1, relayed2, relayed3))
      }
    )
  }

  test("migrate audit database v4 -> current") {

    val relayed1 = ChannelPaymentRelayed(600 msat, 500 msat, randomBytes32(), randomBytes32(), randomBytes32(), 105 unixms)
    val relayed2 = TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(300 msat, randomBytes32()), PaymentRelayed.Part(350 msat, randomBytes32())), Seq(PaymentRelayed.Part(600 msat, randomBytes32())), PlaceHolderPubKey, 0 msat, 110 unixms)

    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // simulate existing previous version db
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_amount_msat BIGINT NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, recipient_node_id TEXT NOT NULL, to_channel_id TEXT NOT NULL, timestamp BIGINT NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat BIGINT NOT NULL, payment_hash TEXT NOT NULL, from_channel_id TEXT NOT NULL, timestamp BIGINT NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, channel_id TEXT NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp BIGINT NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, tx_id TEXT NOT NULL, fee_sat BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp BIGINT NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp BIGINT NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal BOOLEAN NOT NULL, timestamp BIGINT NOT NULL)")

              statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_payment_hash_idx ON relayed(payment_hash)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")

              setVersion(statement, "audit", 4)
            }

            using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setString(1, relayed1.paymentHash.toHex)
              statement.setLong(2, relayed1.amountIn.toLong)
              statement.setString(3, relayed1.fromChannelId.toHex)
              statement.setString(4, "IN")
              statement.setString(5, "channel")
              statement.setLong(6, relayed1.timestamp.toLong)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setString(1, relayed1.paymentHash.toHex)
              statement.setLong(2, relayed1.amountOut.toLong)
              statement.setString(3, relayed1.toChannelId.toHex)
              statement.setString(4, "OUT")
              statement.setString(5, "channel")
              statement.setLong(6, relayed1.timestamp.toLong)
              statement.executeUpdate()
            }
            for (incoming <- relayed2.incoming) {
              using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
                statement.setString(1, relayed2.paymentHash.toHex)
                statement.setLong(2, incoming.amount.toLong)
                statement.setString(3, incoming.channelId.toHex)
                statement.setString(4, "IN")
                statement.setString(5, "trampoline")
                statement.setLong(6, relayed2.timestamp.toLong)
                statement.executeUpdate()
              }
            }
            for (outgoing <- relayed2.outgoing) {
              using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
                statement.setString(1, relayed2.paymentHash.toHex)
                statement.setLong(2, outgoing.amount.toLong)
                statement.setString(3, outgoing.channelId.toHex)
                statement.setString(4, "OUT")
                statement.setString(5, "trampoline")
                statement.setLong(6, relayed2.timestamp.toLong)
                statement.executeUpdate()
              }
            }
          },
          dbName = PgAuditDb.DB_NAME,
          targetVersion = PgAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            val migratedDb = dbs.audit

            assert(migratedDb.listRelayed(100 unixms, 120 unixms) == Seq(relayed1, relayed2))

            val postMigrationDb = new PgAuditDb()(dbs.datasource)
            using(connection.createStatement()) { statement =>
              assert(getVersion(statement, "audit").contains(PgAuditDb.CURRENT_VERSION))
            }
            val relayed3 = TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(450 msat, randomBytes32()), PaymentRelayed.Part(500 msat, randomBytes32())), Seq(PaymentRelayed.Part(800 msat, randomBytes32())), randomKey().publicKey, 700 msat, 150 unixms)
            postMigrationDb.add(relayed3)
            assert(postMigrationDb.listRelayed(100 unixms, 160 unixms) == Seq(relayed1, relayed2, relayed3))
          }
        )
      case dbs: TestSqliteDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // simulate existing previous version db
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_amount_msat INTEGER NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, recipient_node_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, channel_id BLOB NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

              statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_payment_hash_idx ON relayed(payment_hash)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")

              setVersion(statement, "audit", 4)
            }

            using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setBytes(1, relayed1.paymentHash.toArray)
              statement.setLong(2, relayed1.amountIn.toLong)
              statement.setBytes(3, relayed1.fromChannelId.toArray)
              statement.setString(4, "IN")
              statement.setString(5, "channel")
              statement.setLong(6, relayed1.timestamp.toLong)
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setBytes(1, relayed1.paymentHash.toArray)
              statement.setLong(2, relayed1.amountOut.toLong)
              statement.setBytes(3, relayed1.toChannelId.toArray)
              statement.setString(4, "OUT")
              statement.setString(5, "channel")
              statement.setLong(6, relayed1.timestamp.toLong)
              statement.executeUpdate()
            }
            for (incoming <- relayed2.incoming) {
              using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
                statement.setBytes(1, relayed2.paymentHash.toArray)
                statement.setLong(2, incoming.amount.toLong)
                statement.setBytes(3, incoming.channelId.toArray)
                statement.setString(4, "IN")
                statement.setString(5, "trampoline")
                statement.setLong(6, relayed2.timestamp.toLong)
                statement.executeUpdate()
              }
            }
            for (outgoing <- relayed2.outgoing) {
              using(connection.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
                statement.setBytes(1, relayed2.paymentHash.toArray)
                statement.setLong(2, outgoing.amount.toLong)
                statement.setBytes(3, outgoing.channelId.toArray)
                statement.setString(4, "OUT")
                statement.setString(5, "trampoline")
                statement.setLong(6, relayed2.timestamp.toLong)
                statement.executeUpdate()
              }
            }
          },
          dbName = SqliteAuditDb.DB_NAME,
          targetVersion = SqliteAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            val migratedDb = dbs.audit
            using(connection.createStatement()) { statement =>
              assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
            }
            assert(migratedDb.listRelayed(100 unixms, 120 unixms) == Seq(relayed1, relayed2))

            val postMigrationDb = new SqliteAuditDb(connection)
            using(connection.createStatement()) { statement =>
              assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION))
            }
            val relayed3 = TrampolinePaymentRelayed(randomBytes32(), Seq(PaymentRelayed.Part(450 msat, randomBytes32()), PaymentRelayed.Part(500 msat, randomBytes32())), Seq(PaymentRelayed.Part(800 msat, randomBytes32())), randomKey().publicKey, 700 msat, 150 unixms)
            postMigrationDb.add(relayed3)
            assert(postMigrationDb.listRelayed(100 unixms, 160 unixms) == Seq(relayed1, relayed2, relayed3))
          }
        )
    }
  }

  test("migrate audit database v7 -> current") {
    val networkFees = Seq(
      NetworkFee(randomKey().publicKey, randomBytes32(), randomBytes32(), 50 sat, "test-tx-1", 500 unixms),
      NetworkFee(randomKey().publicKey, randomBytes32(), randomBytes32(), 0 sat, "test-tx-2", 600 unixms),
    )

    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // simulate existing previous version db
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE SCHEMA audit")

              statement.executeUpdate("CREATE TABLE audit.sent (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_amount_msat BIGINT NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, recipient_node_id TEXT NOT NULL, to_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.received (amount_msat BIGINT NOT NULL, payment_hash TEXT NOT NULL, from_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.relayed (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, channel_id TEXT NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.relayed_trampoline (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, next_node_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.network_fees (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, tx_id TEXT NOT NULL, fee_sat BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL, cltv_expiry_delta BIGINT NOT NULL, htlc_minimum_msat BIGINT NOT NULL, htlc_maximum_msat BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.path_finding_metrics (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, status TEXT NOT NULL, duration_ms BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL, is_mpp BOOLEAN NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id TEXT NOT NULL)")

              statement.executeUpdate("CREATE TABLE audit.channel_errors (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal BOOLEAN NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON audit.sent(timestamp)")
              statement.executeUpdate("CREATE INDEX received_timestamp_idx ON audit.received(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON audit.relayed(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON audit.relayed(payment_hash)")
              statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON audit.relayed_trampoline(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON audit.relayed_trampoline(payment_hash)")
              statement.executeUpdate("CREATE INDEX network_fees_timestamp_idx ON audit.network_fees(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON audit.channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_errors_timestamp_idx ON audit.channel_errors(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON audit.channel_updates(channel_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON audit.channel_updates(node_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON audit.channel_updates(timestamp)")
              statement.executeUpdate("CREATE INDEX metrics_status_idx ON audit.path_finding_metrics(status)")
              statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON audit.path_finding_metrics(timestamp)")
              statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON audit.path_finding_metrics(is_mpp)")
              statement.executeUpdate("CREATE INDEX metrics_name_idx ON audit.path_finding_metrics(experiment_name)")

              setVersion(statement, "audit", 9)
            }

            // We insert some transactions in the table.
            // NB: the first transaction is explicitly duplicated to test the primary key addition.
            for (tx <- networkFees.head +: networkFees) {
              using(connection.prepareStatement("INSERT INTO audit.network_fees VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
                statement.setString(1, tx.channelId.toHex)
                statement.setString(2, tx.remoteNodeId.value.toHex)
                statement.setString(3, tx.txId.toHex)
                statement.setLong(4, tx.fee.toLong)
                statement.setString(5, tx.txType)
                statement.setTimestamp(6, tx.timestamp.toSqlTimestamp)
                statement.executeUpdate()
              }
            }
          },
          dbName = PgAuditDb.DB_NAME,
          targetVersion = PgAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            val migratedDb = dbs.audit
            using(connection.createStatement()) { statement => assert(getVersion(statement, "audit").contains(PgAuditDb.CURRENT_VERSION)) }
            assert(migratedDb.listNetworkFees(0 unixms, 700 unixms) == networkFees)
          }
        )
      case dbs: TestSqliteDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // simulate existing previous version db
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_amount_msat INTEGER NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, recipient_node_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE relayed (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, channel_id BLOB NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE relayed_trampoline (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, next_node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE channel_updates (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, htlc_maximum_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
              statement.executeUpdate("CREATE TABLE path_finding_metrics (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, status TEXT NOT NULL, duration_ms INTEGER NOT NULL, timestamp INTEGER NOT NULL, is_mpp INTEGER NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id BLOB NOT NULL)")

              statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON sent(timestamp)")
              statement.executeUpdate("CREATE INDEX received_timestamp_idx ON received(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON relayed(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON relayed(payment_hash)")
              statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON relayed_trampoline(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON relayed_trampoline(payment_hash)")
              statement.executeUpdate("CREATE INDEX network_fees_timestamp_idx ON network_fees(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_errors_timestamp_idx ON channel_errors(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON channel_updates(channel_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON channel_updates(node_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON channel_updates(timestamp)")
              statement.executeUpdate("CREATE INDEX metrics_status_idx ON path_finding_metrics(status)")
              statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON path_finding_metrics(timestamp)")
              statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON path_finding_metrics(is_mpp)")
              statement.executeUpdate("CREATE INDEX metrics_name_idx ON path_finding_metrics(experiment_name)")

              setVersion(statement, "audit", 7)
            }

            // We insert some transactions in the table.
            // NB: the first transaction is explicitly duplicated to test the primary key addition.
            for (tx <- networkFees.head +: networkFees) {
              using(connection.prepareStatement("INSERT INTO network_fees VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
                statement.setBytes(1, tx.channelId.toArray)
                statement.setBytes(2, tx.remoteNodeId.value.toArray)
                statement.setBytes(3, tx.txId.toArray)
                statement.setLong(4, tx.fee.toLong)
                statement.setString(5, tx.txType)
                statement.setLong(6, tx.timestamp.toLong)
                statement.executeUpdate()
              }
            }
          },
          dbName = SqliteAuditDb.DB_NAME,
          targetVersion = SqliteAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            val migratedDb = dbs.audit
            using(connection.createStatement()) { statement => assert(getVersion(statement, "audit").contains(SqliteAuditDb.CURRENT_VERSION)) }
            assert(migratedDb.listNetworkFees(0 unixms, 700 unixms) == networkFees)
          }
        )
    }
  }

  test("migrate postgres audit database v10 -> current") {
    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // simulate existing v10 db
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE SCHEMA audit")

              statement.executeUpdate("CREATE TABLE audit.sent (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_amount_msat BIGINT NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, recipient_node_id TEXT NOT NULL, to_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.received (amount_msat BIGINT NOT NULL, payment_hash TEXT NOT NULL, from_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.relayed (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, channel_id TEXT NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.relayed_trampoline (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, next_node_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL, cltv_expiry_delta BIGINT NOT NULL, htlc_minimum_msat BIGINT NOT NULL, htlc_maximum_msat BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.path_finding_metrics (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, status TEXT NOT NULL, duration_ms BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL, is_mpp BOOLEAN NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id TEXT NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, mining_fee_sat BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE TABLE audit.transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")

              statement.executeUpdate("CREATE TABLE audit.channel_errors (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal BOOLEAN NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
              statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON audit.sent(timestamp)")
              statement.executeUpdate("CREATE INDEX received_timestamp_idx ON audit.received(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON audit.relayed(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON audit.relayed(payment_hash)")
              statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON audit.relayed_trampoline(timestamp)")
              statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON audit.relayed_trampoline(payment_hash)")
              statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON audit.channel_events(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_errors_timestamp_idx ON audit.channel_errors(timestamp)")
              statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON audit.channel_updates(channel_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON audit.channel_updates(node_id)")
              statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON audit.channel_updates(timestamp)")
              statement.executeUpdate("CREATE INDEX metrics_status_idx ON audit.path_finding_metrics(status)")
              statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON audit.path_finding_metrics(timestamp)")
              statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON audit.path_finding_metrics(is_mpp)")
              statement.executeUpdate("CREATE INDEX metrics_name_idx ON audit.path_finding_metrics(experiment_name)")
              statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON audit.transactions_published(timestamp)")
              statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON audit.transactions_confirmed(timestamp)")

              setVersion(statement, "audit", 10)
            }

            using(connection.prepareStatement("INSERT INTO audit.path_finding_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setLong(1, 214000)
              statement.setLong(2, 345)
              statement.setString(3, "FAILURE")
              statement.setLong(4, 520)
              statement.setTimestamp(5, TimestampSecond(1651053434L).toSqlTimestamp)
              statement.setBoolean(6, true)
              statement.setString(7, "experiment-a")
              statement.setString(8, "03271338633d2d37b285dae4df40b413d8c6c791fbee7797bc5dc70812196d7d5c")
              statement.executeUpdate()
            }
            using(connection.prepareStatement("INSERT INTO audit.path_finding_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
              statement.setLong(1, 35000)
              statement.setLong(2, 43)
              statement.setString(3, "SUCCESS")
              statement.setLong(4, 2043)
              statement.setTimestamp(5, TimestampSecond(1651054567L).toSqlTimestamp)
              statement.setBoolean(6, false)
              statement.setString(7, "experiment-b")
              statement.setString(8, "030c3f19d742ca294a55c00376b3b355c3c90d61c6b6b39554dbc7ac19b141c14f")
              statement.executeUpdate()
            }
          },
          dbName = PgAuditDb.DB_NAME,
          targetVersion = PgAuditDb.CURRENT_VERSION,
          postCheck = connection => {
            using(connection.createStatement()) { statement => assert(getVersion(statement, "audit").contains(PgAuditDb.CURRENT_VERSION)) }
            using(connection.prepareStatement(s"SELECT amount_msat, status, experiment_name, recipient_node_id FROM audit.path_finding_metrics ORDER BY timestamp")) { statement =>
              val result = statement.executeQuery()
              assert(result.next())
              assert(result.getLong(1) == 214000)
              assert(result.getString(2) == "FAILURE")
              assert(result.getString(3) == "experiment-a")
              assert(result.getString(4) == "03271338633d2d37b285dae4df40b413d8c6c791fbee7797bc5dc70812196d7d5c")
              assert(result.next())
              assert(result.getLong(1) == 35000)
              assert(result.getString(2) == "SUCCESS")
              assert(result.getString(3) == "experiment-b")
              assert(result.getString(4) == "030c3f19d742ca294a55c00376b3b355c3c90d61c6b6b39554dbc7ac19b141c14f")
              assert(!result.next())
            }
          }
        )
      case _: TestSqliteDatabases => ()
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
      val u = Announcements.makeChannelUpdate(randomBytes32(), randomKey(), remoteNodeId, scid, CltvExpiryDelta(56), 2000 msat, 1000 msat, 999, 1000000000 msat)
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
