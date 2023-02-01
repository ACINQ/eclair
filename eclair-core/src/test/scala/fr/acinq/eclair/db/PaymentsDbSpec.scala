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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases, forAllDbs, migrationCheck}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.db.PaymentsDb._
import fr.acinq.eclair.db.jdbc.JdbcUtils.{setVersion, using}
import fr.acinq.eclair.db.pg.PgPaymentsDb
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.{ChannelHop, HopRelayParams, NodeHop}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, TlvStream, UnknownNextPeer}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, Paginated, ShortChannelId, TimestampMilli, TimestampMilliLong, TimestampSecond, TimestampSecondLong, randomBytes, randomBytes32, randomBytes64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class PaymentsDbSpec extends AnyFunSuite {

  import PaymentsDbSpec._

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqlitePaymentsDb(sqlite.connection)
        new SqlitePaymentsDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgPaymentsDb()(pg.datasource, pg.lock)
        new PgPaymentsDb()(pg.datasource, pg.lock)
    }
  }

  test("migrate sqlite payments db v1 -> current") {
    val dbs = TestSqliteDatabases()

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        // simulate existing previous version db
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS payments (payment_hash BLOB NOT NULL PRIMARY KEY, amount_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
          setVersion(statement, "payments", 1)
        }
        // Changes between version 1 and 2:
        //  - the monolithic payments table has been replaced by two tables, received_payments and sent_payments
        //  - old records from the payments table are ignored (not migrated to the new tables)
        using(connection.prepareStatement("INSERT INTO payments VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, paymentHash1.toArray)
          statement.setLong(2, (123 msat).toLong)
          statement.setLong(3, 1000) // received_at
          statement.executeUpdate()
        }
      },
      dbName = "payments",
      targetVersion = SqlitePaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments
        // the existing received payment can NOT be queried anymore
        assert(db.getIncomingPayment(paymentHash1).isEmpty)

        // add a few rows
        val ps1 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, paymentHash1, PaymentType.Standard, 12345 msat, 12345 msat, alice, 1000 unixms, None, None, OutgoingPaymentStatus.Pending)
        val i1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(500 msat), paymentHash1, davePriv, Left("Some invoice"), CltvExpiryDelta(18), expirySeconds = None, timestamp = 1 unixsec)
        val pr1 = IncomingStandardPayment(i1, preimage1, PaymentType.Standard, i1.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(550 msat, 1100 unixms))

        db.addOutgoingPayment(ps1)
        db.addIncomingPayment(i1, preimage1)
        db.receiveIncomingPayment(i1.paymentHash, 550 msat, 1100 unixms)

        assert(db.listIncomingPayments(1 unixms, 1500 unixms, None) == Seq(pr1))
        assert(db.listOutgoingPayments(1 unixms, 1500 unixms) == Seq(ps1))
      }
    )
  }

  test("migrate sqlite payments db v2 -> current") {
    val dbs = TestSqliteDatabases()

    // Test data
    val id1 = UUID.randomUUID()
    val id2 = UUID.randomUUID()
    val id3 = UUID.randomUUID()
    val ps1 = OutgoingPayment(id1, id1, None, randomBytes32(), PaymentType.Standard, 561 msat, 561 msat, PrivateKey(ByteVector32.One).publicKey, 1000 unixms, None, None, OutgoingPaymentStatus.Pending)
    val ps2 = OutgoingPayment(id2, id2, None, randomBytes32(), PaymentType.Standard, 1105 msat, 1105 msat, PrivateKey(ByteVector32.One).publicKey, 1010 unixms, None, None, OutgoingPaymentStatus.Failed(Nil, 1050 unixms))
    val ps3 = OutgoingPayment(id3, id3, None, paymentHash1, PaymentType.Standard, 1729 msat, 1729 msat, PrivateKey(ByteVector32.One).publicKey, 1040 unixms, None, None, OutgoingPaymentStatus.Succeeded(preimage1, 0 msat, Nil, 1060 unixms))
    val i1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash1, davePriv, Left("Some invoice"), CltvExpiryDelta(18), expirySeconds = None, timestamp = 1 unixsec)
    val pr1 = IncomingStandardPayment(i1, preimage1, PaymentType.Standard, i1.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(12345678 msat, 1090 unixms))
    val i2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash2, carolPriv, Left("Another invoice"), CltvExpiryDelta(18), expirySeconds = Some(30), timestamp = 1 unixsec)
    val pr2 = IncomingStandardPayment(i2, preimage2, PaymentType.Standard, i2.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER, received_at INTEGER)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, preimage BLOB, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, completed_at INTEGER, status VARCHAR NOT NULL)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON sent_payments(payment_hash)")
          setVersion(statement, "payments", 2)
        }
        // Insert a bunch of old version 2 rows.

        // Changes between version 2 and 3 to sent_payments:
        //  - removed the status column
        //  - added optional payment failures
        //  - added optional payment success details (fees paid and route)
        //  - added optional payment request
        //  - added target node ID
        //  - added externalID and parentID

        using(connection.prepareStatement("INSERT INTO sent_payments (id, payment_hash, amount_msat, created_at, status) VALUES (?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, ps1.id.toString)
          statement.setBytes(2, ps1.paymentHash.toArray)
          statement.setLong(3, ps1.amount.toLong)
          statement.setLong(4, ps1.createdAt.toLong)
          statement.setString(5, "PENDING")
          statement.executeUpdate()
        }

        using(connection.prepareStatement("INSERT INTO sent_payments (id, payment_hash, amount_msat, created_at, completed_at, status) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, ps2.id.toString)
          statement.setBytes(2, ps2.paymentHash.toArray)
          statement.setLong(3, ps2.amount.toLong)
          statement.setLong(4, ps2.createdAt.toLong)
          statement.setLong(5, ps2.status.asInstanceOf[OutgoingPaymentStatus.Failed].completedAt.toLong)
          statement.setString(6, "FAILED")
          statement.executeUpdate()
        }

        using(connection.prepareStatement("INSERT INTO sent_payments (id, payment_hash, preimage, amount_msat, created_at, completed_at, status) VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, ps3.id.toString)
          statement.setBytes(2, ps3.paymentHash.toArray)
          statement.setBytes(3, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].paymentPreimage.toArray)
          statement.setLong(4, ps3.amount.toLong)
          statement.setLong(5, ps3.createdAt.toLong)
          statement.setLong(6, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].completedAt.toLong)
          statement.setString(7, "SUCCEEDED")
          statement.executeUpdate()
        }

        // Changes between version 2 and 3 to received_payments:
        //  - renamed the preimage column
        //  - made expire_at not null

        using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, preimage, payment_request, received_msat, created_at, received_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, i1.paymentHash.toArray)
          statement.setBytes(2, pr1.paymentPreimage.toArray)
          statement.setString(3, i1.toString)
          statement.setLong(4, pr1.status.asInstanceOf[IncomingPaymentStatus.Received].amount.toLong)
          statement.setLong(5, pr1.createdAt.toLong)
          statement.setLong(6, pr1.status.asInstanceOf[IncomingPaymentStatus.Received].receivedAt.toLong)
          statement.executeUpdate()
        }

        using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, preimage, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, i2.paymentHash.toArray)
          statement.setBytes(2, pr2.paymentPreimage.toArray)
          statement.setString(3, i2.toString)
          statement.setLong(4, pr2.createdAt.toLong)
          statement.setLong(5, (i2.createdAt + i2.relativeExpiry).toLong)
          statement.executeUpdate()
        }
      },
      dbName = "payments",
      targetVersion = SqlitePaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments

        assert(db.getIncomingPayment(i1.paymentHash).contains(pr1))
        assert(db.getIncomingPayment(i2.paymentHash).contains(pr2))
        assert(db.listOutgoingPayments(1 unixms, 2000 unixms) == Seq(ps1, ps2, ps3))

        val i3 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), paymentHash3, alicePriv, Left("invoice #3"), CltvExpiryDelta(18), expirySeconds = Some(30))
        val pr3 = IncomingStandardPayment(i3, preimage3, PaymentType.Standard, i3.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
        db.addIncomingPayment(i3, pr3.paymentPreimage)

        val ps4 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("1"), randomBytes32(), PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(i3), None, OutgoingPaymentStatus.Pending)
        val ps5 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("2"), randomBytes32(), PaymentType.Standard, 456 msat, 456 msat, bob, 1150 unixms, Some(i2), None, OutgoingPaymentStatus.Succeeded(preimage1, 42 msat, Nil, 1180 unixms))
        val ps6 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("3"), randomBytes32(), PaymentType.Standard, 789 msat, 789 msat, bob, 1250 unixms, None, None, OutgoingPaymentStatus.Failed(Nil, 1300 unixms))
        db.addOutgoingPayment(ps4)
        db.addOutgoingPayment(ps5.copy(status = OutgoingPaymentStatus.Pending))
        db.updateOutgoingPayment(PaymentSent(ps5.parentId, ps5.paymentHash, preimage1, ps5.amount, ps5.recipientNodeId, Seq(PaymentSent.PartialPayment(ps5.id, ps5.amount, 42 msat, randomBytes32(), None, 1180 unixms))))
        db.addOutgoingPayment(ps6.copy(status = OutgoingPaymentStatus.Pending))
        db.updateOutgoingPayment(PaymentFailed(ps6.id, ps6.paymentHash, Nil, 1300 unixms))

        assert(db.listOutgoingPayments(1 unixms, 2000 unixms) == Seq(ps1, ps2, ps3, ps4, ps5, ps6))
        assert(db.listIncomingPayments(1 unixms, TimestampMilli.now(), None) == Seq(pr1, pr2, pr3))
        assert(db.listExpiredIncomingPayments(1 unixms, 2000 unixms) == Seq(pr2))
      })
  }

  test("migrate sqlite payments db v3 -> current") {
    val dbs = TestSqliteDatabases()

    // Test data
    val (id1, id2, id3) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    val parentId = UUID.randomUUID()
    val invoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(2834 msat), paymentHash1, bobPriv, Left("invoice #1"), CltvExpiryDelta(18), expirySeconds = Some(30))
    val ps1 = OutgoingPayment(id1, id1, Some("42"), randomBytes32(), PaymentType.Standard, 561 msat, 561 msat, alice, 1000 unixms, None, None, OutgoingPaymentStatus.Failed(Seq(FailureSummary(FailureType.REMOTE, "no candy for you", List(HopSummary(hop_ab), HopSummary(hop_bc)), Some(bob))), 1020 unixms))
    val ps2 = OutgoingPayment(id2, parentId, Some("42"), paymentHash1, PaymentType.Standard, 1105 msat, 1105 msat, bob, 1010 unixms, Some(invoice1), None, OutgoingPaymentStatus.Pending)
    val ps3 = OutgoingPayment(id3, parentId, None, paymentHash1, PaymentType.Standard, 1729 msat, 1729 msat, bob, 1040 unixms, None, None, OutgoingPaymentStatus.Succeeded(preimage1, 10 msat, Seq(HopSummary(hop_ab), HopSummary(hop_bc)), 1060 unixms))

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, target_node_id BLOB NOT NULL, created_at INTEGER NOT NULL, payment_request TEXT, completed_at INTEGER, payment_preimage BLOB, fees_msat INTEGER, payment_route BLOB, failures BLOB)")

          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_parent_id_idx ON sent_payments(parent_id)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_payment_hash_idx ON sent_payments(payment_hash)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_created_idx ON sent_payments(created_at)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_created_idx ON received_payments(created_at)")

          setVersion(statement, "payments", 3)
        }

        // Insert a bunch of old version 3 rows.

        using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, completed_at, failures) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, ps1.id.toString)
          statement.setString(2, ps1.parentId.toString)
          statement.setString(3, ps1.externalId.get)
          statement.setBytes(4, ps1.paymentHash.toArray)
          statement.setLong(5, ps1.amount.toLong)
          statement.setBytes(6, ps1.recipientNodeId.value.toArray)
          statement.setLong(7, ps1.createdAt.toLong)
          statement.setLong(8, ps1.status.asInstanceOf[OutgoingPaymentStatus.Failed].completedAt.toLong)
          statement.setBytes(9, PaymentsDb.encodeFailures(ps1.status.asInstanceOf[OutgoingPaymentStatus.Failed].failures.toList))
          statement.executeUpdate()
        }

        using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, ps2.id.toString)
          statement.setString(2, ps2.parentId.toString)
          statement.setString(3, ps2.externalId.get)
          statement.setBytes(4, ps2.paymentHash.toArray)
          statement.setLong(5, ps2.amount.toLong)
          statement.setBytes(6, ps2.recipientNodeId.value.toArray)
          statement.setLong(7, ps2.createdAt.toLong)
          statement.setString(8, invoice1.toString)
          statement.executeUpdate()
        }

        using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, payment_hash, amount_msat, target_node_id, created_at, completed_at, payment_preimage, fees_msat, payment_route) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, ps3.id.toString)
          statement.setString(2, ps3.parentId.toString)
          statement.setBytes(3, ps3.paymentHash.toArray)
          statement.setLong(4, ps3.amount.toLong)
          statement.setBytes(5, ps3.recipientNodeId.value.toArray)
          statement.setLong(6, ps3.createdAt.toLong)
          statement.setLong(7, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].completedAt.toLong)
          statement.setBytes(8, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].paymentPreimage.toArray)
          statement.setLong(9, ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid.toLong)
          statement.setBytes(10, PaymentsDb.encodeRoute(ps3.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].route.toList))
          statement.executeUpdate()
        }

        // Changes between version 3 and 4 to sent_payments:
        //  - added final amount column
        //  - added payment type column, with a default to "Standard"
        //  - renamed target_node_id -> recipient_node_id
        //  - re-ordered columns
      },
      dbName = "payments",
      targetVersion = SqlitePaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments
        assert(db.getOutgoingPayment(id1).contains(ps1))
        assert(db.listOutgoingPayments(parentId) == Seq(ps2, ps3))
      }
    )
  }

  test("migrate sqlite payments db v4 -> current") {
    val dbs = TestSqliteDatabases()
    val now = TimestampSecond.now()
    val pendingInvoice = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(2500 msat), paymentHash1, bobPriv, Left("invoice #1"), CltvExpiryDelta(18), timestamp = now, expirySeconds = Some(30))
    val pending = IncomingStandardPayment(pendingInvoice, preimage1, PaymentType.Standard, pendingInvoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
    val paidInvoice = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(10_000 msat), paymentHash2, bobPriv, Left("invoice #2"), CltvExpiryDelta(12), timestamp = 250 unixsec, expirySeconds = Some(60))
    val paid = IncomingStandardPayment(paidInvoice, preimage2, PaymentType.Standard, paidInvoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(11_000 msat, 300.unixsec.toTimestampMilli))

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
          statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, payment_preimage BLOB, payment_type TEXT NOT NULL, amount_msat INTEGER NOT NULL, fees_msat INTEGER, recipient_amount_msat INTEGER NOT NULL, recipient_node_id BLOB NOT NULL, payment_request TEXT, payment_route BLOB, failures BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)")
          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
          setVersion(statement, "payments", 4)
        }

        // Insert a bunch of version 4 rows.

        using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, pendingInvoice.paymentHash.toArray)
          statement.setBytes(2, pending.paymentPreimage.toArray)
          statement.setString(3, pending.paymentType)
          statement.setString(4, pendingInvoice.toString)
          statement.setLong(5, pendingInvoice.createdAt.toTimestampMilli.toLong)
          statement.setLong(6, (pendingInvoice.createdAt + pendingInvoice.relativeExpiry).toLong.seconds.toMillis)
          statement.executeUpdate()
        }

        using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at, received_msat, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, paidInvoice.paymentHash.toArray)
          statement.setBytes(2, paid.paymentPreimage.toArray)
          statement.setString(3, paid.paymentType)
          statement.setString(4, paidInvoice.toString)
          statement.setLong(5, paidInvoice.createdAt.toTimestampMilli.toLong)
          statement.setLong(6, (paidInvoice.createdAt + paidInvoice.relativeExpiry).toLong.seconds.toMillis)
          statement.setLong(7, 11_000)
          statement.setLong(8, 300_000)
          statement.executeUpdate()
        }
      },
      dbName = "payments",
      targetVersion = SqlitePaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments
        assert(db.getIncomingPayment(pendingInvoice.paymentHash).contains(pending))
        assert(db.getIncomingPayment(paidInvoice.paymentHash).contains(paid))
      }
    )
  }

  test("migrate sqlite payments db v5 -> current") {
    val dbs = TestSqliteDatabases()
    val pending = {
      val amount = 123456 msat
      val payerKey = randomKey()
      val recipientKey = randomKey()
      val preimage = randomBytes32()
      val invoice = createBolt12Invoice(amount, payerKey, recipientKey, preimage)
      OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, invoice.paymentHash, PaymentType.Blinded, amount, amount, recipientKey.publicKey, invoice.createdAt.toTimestampMilli, Some(invoice), None, OutgoingPaymentStatus.Pending)
    }
    val paid = {
      val amount = 789123456 msat
      val payerKey = randomKey()
      val recipientKey = randomKey()
      val preimage = randomBytes32()
      val invoice = createBolt12Invoice(amount, payerKey, recipientKey, preimage)
      OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, invoice.paymentHash, PaymentType.Blinded, amount, amount, recipientKey.publicKey, invoice.createdAt.toTimestampMilli, Some(invoice), None, OutgoingPaymentStatus.Succeeded(preimage, 123 msat, Nil, 300.unixsec.toTimestampMilli))
    }

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage BLOB NOT NULL, path_ids BLOB, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
          statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, payment_preimage BLOB, payment_type TEXT NOT NULL, amount_msat INTEGER NOT NULL, fees_msat INTEGER, recipient_amount_msat INTEGER NOT NULL, recipient_node_id BLOB NOT NULL, payment_request TEXT, payment_route BLOB, failures BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)")
          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
          setVersion(statement, "payments", 5)
        }
        using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, pending.id.toString)
          statement.setString(2, pending.parentId.toString)
          statement.setBytes(3, pending.paymentHash.toArray)
          statement.setString(4, pending.paymentType)
          statement.setLong(5, pending.amount.toLong)
          statement.setLong(6, pending.recipientAmount.toLong)
          statement.setBytes(7, pending.recipientNodeId.value.toArray)
          statement.setLong(8, pending.createdAt.toLong)
          statement.setString(9, pending.invoice.get.toString)
          statement.executeUpdate()
        }
        using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request, completed_at, payment_preimage, fees_msat, payment_route) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, paid.id.toString)
          statement.setString(2, paid.parentId.toString)
          statement.setBytes(3, paid.paymentHash.toArray)
          statement.setString(4, paid.paymentType)
          statement.setLong(5, paid.amount.toLong)
          statement.setLong(6, paid.recipientAmount.toLong)
          statement.setBytes(7, paid.recipientNodeId.value.toArray)
          statement.setLong(8, paid.createdAt.toLong)
          statement.setString(9, paid.invoice.get.toString)
          statement.setLong(10, paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].completedAt.toLong)
          statement.setBytes(11, paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].paymentPreimage.toArray)
          statement.setLong(12, paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid.toLong)
          statement.setBytes(13, encodeRoute(paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].route.toList))
          statement.executeUpdate()
        }
      },
      dbName = "payments",
      targetVersion = SqlitePaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments
        assert(db.listOutgoingPayments(pending.paymentHash) == Seq(pending))
        assert(db.listOutgoingPayments(paid.paymentHash) == Seq(paid))
      }
    )
  }

  test("migrate postgres payments db v4 -> current") {
    val dbs = TestPgDatabases()

    // Test data
    val id1 = UUID.randomUUID()
    val id2 = UUID.randomUUID()
    val id3 = UUID.randomUUID()
    val ps1 = OutgoingPayment(id1, id1, None, randomBytes32(), PaymentType.Standard, 561 msat, 561 msat, PrivateKey(ByteVector32.One).publicKey, TimestampMilli(Instant.parse("2021-01-01T10:15:30.00Z").toEpochMilli), None, None, OutgoingPaymentStatus.Pending)
    val ps2 = OutgoingPayment(id2, id2, None, randomBytes32(), PaymentType.Standard, 1105 msat, 1105 msat, PrivateKey(ByteVector32.One).publicKey, TimestampMilli(Instant.parse("2020-05-14T13:47:21.00Z").toEpochMilli), None, None, OutgoingPaymentStatus.Failed(Nil, TimestampMilli(Instant.parse("2021-05-15T04:12:40.00Z").toEpochMilli)))
    val ps3 = OutgoingPayment(id3, id3, None, paymentHash1, PaymentType.Standard, 1729 msat, 1729 msat, PrivateKey(ByteVector32.One).publicKey, TimestampMilli(Instant.parse("2021-01-28T09:12:05.00Z").toEpochMilli), None, None, OutgoingPaymentStatus.Succeeded(preimage1, 0 msat, Nil, TimestampMilli.now()))
    val i1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash1, davePriv, Left("Some invoice"), CltvExpiryDelta(18), expirySeconds = None, timestamp = TimestampSecond.now())
    val pr1 = IncomingStandardPayment(i1, preimage1, PaymentType.Standard, i1.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(12345678 msat, TimestampMilli.now()))
    val i2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(12345678 msat), paymentHash2, carolPriv, Left("Another invoice"), CltvExpiryDelta(18), expirySeconds = Some(24 * 3600), timestamp = TimestampSecond(Instant.parse("2020-12-30T10:00:55.00Z").getEpochSecond))
    val pr2 = IncomingStandardPayment(i2, preimage2, PaymentType.Standard, i2.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE received_payments (payment_hash TEXT NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage TEXT NOT NULL, payment_request TEXT NOT NULL, received_msat BIGINT, created_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, received_at BIGINT)")
          statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, payment_preimage TEXT, payment_type TEXT NOT NULL, amount_msat BIGINT NOT NULL, fees_msat BIGINT, recipient_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, payment_request TEXT, payment_route BYTEA, failures BYTEA, created_at BIGINT NOT NULL, completed_at BIGINT)")

          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")

          setVersion(statement, "payments", 4)
        }
        // insert test data
        Seq(ps1, ps2, ps3).foreach { sent =>
          using(connection.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request, completed_at, payment_preimage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setString(1, sent.id.toString)
            statement.setString(2, sent.parentId.toString)
            statement.setString(3, sent.externalId.orNull)
            statement.setString(4, sent.paymentHash.toHex)
            statement.setString(5, sent.paymentType)
            statement.setLong(6, sent.amount.toLong)
            statement.setLong(7, sent.recipientAmount.toLong)
            statement.setString(8, sent.recipientNodeId.value.toHex)
            statement.setLong(9, sent.createdAt.toLong)
            statement.setString(10, sent.invoice.map(_.toString).orNull)
            sent.status match {
              case s: OutgoingPaymentStatus.Succeeded =>
                statement.setLong(11, s.completedAt.toLong)
                statement.setString(12, s.paymentPreimage.toHex)
              case s: OutgoingPaymentStatus.Failed =>
                statement.setLong(11, s.completedAt.toLong)
                statement.setObject(12, null)
              case _ =>
                statement.setObject(11, null)
                statement.setObject(12, null)
            }
            statement.executeUpdate()
          }
        }

        Seq((i1, preimage1), (i2, preimage2)).foreach { case (invoice, preimage) =>
          using(connection.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setString(1, invoice.paymentHash.toHex)
            statement.setString(2, preimage.toHex)
            statement.setString(3, PaymentType.Standard)
            statement.setString(4, invoice.toString)
            statement.setLong(5, invoice.createdAt.toTimestampMilli.toLong) // BOLT11 timestamp is in seconds
            statement.setLong(6, (invoice.createdAt + invoice.relativeExpiry).toTimestampMilli.toLong)
            statement.executeUpdate()
          }
        }

        using(connection.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (? + COALESCE(received_msat, 0), ?) WHERE payment_hash = ?")) { update =>
          update.setLong(1, pr1.status.asInstanceOf[IncomingPaymentStatus.Received].amount.toLong)
          update.setLong(2, pr1.status.asInstanceOf[IncomingPaymentStatus.Received].receivedAt.toLong)
          update.setString(3, pr1.invoice.paymentHash.toHex)
          val updated = update.executeUpdate()
          if (updated == 0) {
            throw new IllegalArgumentException("Inserted a received payment without having an invoice")
          }
        }

        import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
        assert(connection.createStatement().executeQuery("SELECT * FROM received_payments").map(rs => rs.getString("payment_hash")).toSeq.nonEmpty)
      },
      dbName = PgPaymentsDb.DB_NAME,
      targetVersion = PgPaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments

        assert(db.getIncomingPayment(i1.paymentHash).contains(pr1))
        assert(db.getIncomingPayment(i2.paymentHash).contains(pr2))
        assert(db.listIncomingPayments(TimestampMilli(Instant.parse("2020-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2100-12-31T23:59:59.00Z").toEpochMilli), None) == Seq(pr2, pr1))
        assert(db.listIncomingPayments(TimestampMilli(Instant.parse("2020-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2020-12-31T23:59:59.00Z").toEpochMilli), None) == Seq(pr2))
        assert(db.listIncomingPayments(TimestampMilli(Instant.parse("2010-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2011-12-31T23:59:59.00Z").toEpochMilli), None) == Seq.empty)
        assert(db.listExpiredIncomingPayments(TimestampMilli(Instant.parse("2020-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2100-12-31T23:59:59.00Z").toEpochMilli)) == Seq(pr2))
        assert(db.listExpiredIncomingPayments(TimestampMilli(Instant.parse("2020-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2020-12-31T23:59:59.00Z").toEpochMilli)) == Seq(pr2))
        assert(db.listExpiredIncomingPayments(TimestampMilli(Instant.parse("2010-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2011-12-31T23:59:59.00Z").toEpochMilli)) == Seq.empty)

        assert(db.listOutgoingPayments(TimestampMilli(Instant.parse("2020-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2021-12-31T23:59:59.00Z").toEpochMilli)) == Seq(ps2, ps1, ps3))
        assert(db.listOutgoingPayments(TimestampMilli(Instant.parse("2010-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2021-01-15T23:59:59.00Z").toEpochMilli)) == Seq(ps2, ps1))
        assert(db.listOutgoingPayments(TimestampMilli(Instant.parse("2010-01-01T00:00:00.00Z").toEpochMilli), TimestampMilli(Instant.parse("2011-12-31T23:59:59.00Z").toEpochMilli)) == Seq.empty)
      }
    )
  }

  test("migrate postgres payments db v6 -> current") {
    val dbs = TestPgDatabases()
    val now = TimestampSecond.now()
    val pendingInvoice = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(2500 msat), paymentHash1, bobPriv, Left("invoice #1"), CltvExpiryDelta(18), timestamp = now, expirySeconds = Some(30))
    val pending = IncomingStandardPayment(pendingInvoice, preimage1, PaymentType.Standard, pendingInvoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
    val paidInvoice = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(10_000 msat), paymentHash2, bobPriv, Left("invoice #2"), CltvExpiryDelta(12), timestamp = 250 unixsec, expirySeconds = Some(60))
    val paid = IncomingStandardPayment(paidInvoice, preimage2, PaymentType.Standard, paidInvoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(11_000 msat, 300.unixsec.toTimestampMilli))

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE SCHEMA payments")
          statement.executeUpdate("CREATE TABLE payments.received (payment_hash TEXT NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage TEXT NOT NULL, payment_request TEXT NOT NULL, received_msat BIGINT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, expire_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE TABLE payments.sent (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, payment_preimage TEXT, payment_type TEXT NOT NULL, amount_msat BIGINT NOT NULL, fees_msat BIGINT, recipient_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, payment_request TEXT, payment_route BYTEA, failures BYTEA, created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON payments.sent(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON payments.sent(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON payments.sent(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON payments.received(created_at)")
          setVersion(statement, "payments", 6)
        }
        using(connection.prepareStatement("INSERT INTO payments.received (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, pendingInvoice.paymentHash.toHex)
          statement.setString(2, pending.paymentPreimage.toHex)
          statement.setString(3, pending.paymentType)
          statement.setString(4, pendingInvoice.toString)
          statement.setTimestamp(5, pendingInvoice.createdAt.toSqlTimestamp)
          statement.setTimestamp(6, (pendingInvoice.createdAt + pendingInvoice.relativeExpiry.toSeconds).toSqlTimestamp)
          statement.executeUpdate()
        }
        using(connection.prepareStatement("INSERT INTO payments.received (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at, received_msat, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, paidInvoice.paymentHash.toHex)
          statement.setString(2, paid.paymentPreimage.toHex)
          statement.setString(3, paid.paymentType)
          statement.setString(4, paidInvoice.toString)
          statement.setTimestamp(5, paidInvoice.createdAt.toSqlTimestamp)
          statement.setTimestamp(6, (paidInvoice.createdAt + paidInvoice.relativeExpiry.toSeconds).toSqlTimestamp)
          statement.setLong(7, 11_000)
          statement.setTimestamp(8, 300.unixsec.toSqlTimestamp)
          statement.executeUpdate()
        }
      },
      dbName = PgPaymentsDb.DB_NAME,
      targetVersion = PgPaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments
        assert(db.getIncomingPayment(pendingInvoice.paymentHash).contains(pending))
        assert(db.getIncomingPayment(paidInvoice.paymentHash).contains(paid))
      }
    )
  }

  test("migrate postgres payments db v7 -> current") {
    val dbs = TestPgDatabases()
    val pending = {
      val amount = 123456 msat
      val payerKey = randomKey()
      val recipientKey = randomKey()
      val preimage = randomBytes32()
      val invoice = createBolt12Invoice(amount, payerKey, recipientKey, preimage)
      OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, invoice.paymentHash, PaymentType.Blinded, amount, amount, recipientKey.publicKey, invoice.createdAt.toTimestampMilli, Some(invoice), None, OutgoingPaymentStatus.Pending)
    }
    val paid = {
      val amount = 789123456 msat
      val payerKey = randomKey()
      val recipientKey = randomKey()
      val preimage = randomBytes32()
      val invoice = createBolt12Invoice(amount, payerKey, recipientKey, preimage)
      OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, invoice.paymentHash, PaymentType.Blinded, amount, amount, recipientKey.publicKey, invoice.createdAt.toTimestampMilli, Some(invoice), None, OutgoingPaymentStatus.Succeeded(preimage, 123 msat, Nil, 300.unixsec.toTimestampMilli))
    }

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE SCHEMA payments")
          statement.executeUpdate("CREATE TABLE payments.received (payment_hash TEXT NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage TEXT NOT NULL, path_ids BYTEA, payment_request TEXT NOT NULL, received_msat BIGINT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, expire_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE TABLE payments.sent (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, payment_preimage TEXT, payment_type TEXT NOT NULL, amount_msat BIGINT NOT NULL, fees_msat BIGINT, recipient_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, payment_request TEXT, payment_route BYTEA, failures BYTEA, created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON payments.sent(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON payments.sent(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON payments.sent(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON payments.received(created_at)")
          setVersion(statement, "payments", 7)
        }
        using(connection.prepareStatement("INSERT INTO payments.sent (id, parent_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, pending.id.toString)
          statement.setString(2, pending.parentId.toString)
          statement.setString(3, pending.paymentHash.toHex)
          statement.setString(4, pending.paymentType)
          statement.setLong(5, pending.amount.toLong)
          statement.setLong(6, pending.recipientAmount.toLong)
          statement.setString(7, pending.recipientNodeId.value.toHex)
          statement.setTimestamp(8, pending.createdAt.toSqlTimestamp)
          statement.setString(9, pending.invoice.get.toString)
          statement.executeUpdate()
        }
        using(connection.prepareStatement("INSERT INTO payments.sent (id, parent_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request, completed_at, payment_preimage, fees_msat, payment_route) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, paid.id.toString)
          statement.setString(2, paid.parentId.toString)
          statement.setString(3, paid.paymentHash.toHex)
          statement.setString(4, paid.paymentType)
          statement.setLong(5, paid.amount.toLong)
          statement.setLong(6, paid.recipientAmount.toLong)
          statement.setString(7, paid.recipientNodeId.value.toHex)
          statement.setTimestamp(8, paid.createdAt.toSqlTimestamp)
          statement.setString(9, paid.invoice.get.toString)
          statement.setTimestamp(10, paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].completedAt.toSqlTimestamp)
          statement.setString(11, paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].paymentPreimage.toHex)
          statement.setLong(12, paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid.toLong)
          statement.setBytes(13, encodeRoute(paid.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].route.toList))

          statement.executeUpdate()
        }
      },
      dbName = PgPaymentsDb.DB_NAME,
      targetVersion = PgPaymentsDb.CURRENT_VERSION,
      postCheck = _ => {
        val db = dbs.db.payments
        assert(db.listOutgoingPayments(pending.paymentHash) == Seq(pending))
        assert(db.listOutgoingPayments(paid.paymentHash) == Seq(paid))
      }
    )
  }

  test("add/retrieve/update/remove incoming payments") {
    forAllDbs { dbs =>
      val db = dbs.payments

      // can't receive a payment without an invoice associated with it
      val unknownPaymentHash = randomBytes32()
      assert(!db.receiveIncomingPayment(unknownPaymentHash, 12345678 msat))
      assert(db.getIncomingPayment(unknownPaymentHash).isEmpty)

      val dummyBlindedPath = BlindedRoute(randomKey().publicKey, randomKey().publicKey, Seq(BlindedNode(randomKey().publicKey, hex"2a2b2c"), BlindedNode(randomKey().publicKey, hex"deadbeef")))
      val dummyPathInfo = Seq(PaymentInfo(1 msat, 15, CltvExpiryDelta(48), 1 msat, 15000 msat, Features.empty))

      val expiredInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32(), alicePriv, Left("invoice #1"), CltvExpiryDelta(18), timestamp = 1 unixsec)
      val expiredInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32(), bobPriv, Left("invoice #2"), CltvExpiryDelta(18), timestamp = 2 unixsec, expirySeconds = Some(30))
      val expiredInvoice3 = Bolt12Invoice(TlvStream(InvoiceRequestMetadata(randomBytes(5)), OfferDescription("invoice #3"), OfferNodeId(randomKey().publicKey), InvoiceRequestAmount(1729 msat), InvoiceRequestPayerId(randomKey().publicKey), InvoicePaths(Seq(dummyBlindedPath)), InvoiceBlindedPay(dummyPathInfo), InvoiceCreatedAt(3 unixsec), InvoicePaymentHash(randomBytes32()), InvoiceAmount(1729 msat), InvoiceNodeId(randomKey().publicKey), Signature(ByteVector64.Zeroes)))
      val expiredPayment1 = IncomingStandardPayment(expiredInvoice1, randomBytes32(), PaymentType.Standard, expiredInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
      val expiredPayment2 = IncomingStandardPayment(expiredInvoice2, randomBytes32(), PaymentType.Standard, expiredInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
      val expiredPayment3 = IncomingBlindedPayment(expiredInvoice3, randomBytes32(), PaymentType.Blinded, Map(randomKey().publicKey -> hex"2a2a2a2a"), expiredInvoice3.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)

      val pendingInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32(), alicePriv, Left("invoice #4"), CltvExpiryDelta(18), timestamp = TimestampSecond.now() - 10.seconds)
      val pendingInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32(), bobPriv, Left("invoice #5"), CltvExpiryDelta(18), expirySeconds = Some(30), timestamp = TimestampSecond.now() - 9.seconds)
      val pendingInvoice3 = Bolt12Invoice(TlvStream(InvoiceRequestMetadata(randomBytes(5)), OfferDescription("invoice #6"), OfferNodeId(randomKey().publicKey), InvoiceRequestAmount(1729 msat), InvoiceRequestPayerId(randomKey().publicKey), InvoicePaths(Seq(dummyBlindedPath)), InvoiceBlindedPay(dummyPathInfo), InvoiceCreatedAt(TimestampSecond.now() - 8.seconds), InvoicePaymentHash(randomBytes32()), InvoiceAmount(1729 msat), InvoiceNodeId(randomKey().publicKey), Signature(ByteVector64.Zeroes)))
      val pendingPayment1 = IncomingStandardPayment(pendingInvoice1, randomBytes32(), PaymentType.Standard, pendingInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
      val pendingPayment2 = IncomingStandardPayment(pendingInvoice2, randomBytes32(), PaymentType.SwapIn, pendingInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
      val pendingPayment3 = IncomingBlindedPayment(pendingInvoice3, randomBytes32(), PaymentType.Blinded, Map(randomKey().publicKey -> hex"deaddead"), pendingInvoice3.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)

      val paidInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32(), alicePriv, Left("invoice #7"), CltvExpiryDelta(18), timestamp = TimestampSecond.now() - 5.seconds)
      val paidInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32(), bobPriv, Left("invoice #8"), CltvExpiryDelta(18), expirySeconds = Some(60), timestamp = TimestampSecond.now() - 4.seconds)
      val paidInvoice3 = Bolt12Invoice(TlvStream(InvoiceRequestMetadata(randomBytes(5)), OfferDescription("invoice #9"), OfferNodeId(randomKey().publicKey), InvoiceRequestAmount(1729 msat), InvoiceRequestPayerId(randomKey().publicKey), InvoicePaths(Seq(dummyBlindedPath)), InvoiceBlindedPay(dummyPathInfo), InvoiceCreatedAt(TimestampSecond.now() - 3.seconds), InvoicePaymentHash(randomBytes32()), InvoiceAmount(1729 msat), InvoiceNodeId(randomKey().publicKey), Signature(ByteVector64.Zeroes)))
      val receivedAt1 = TimestampMilli.now() + 1.milli
      val receivedAt2 = TimestampMilli.now() + 2.milli
      val receivedAt3 = TimestampMilli.now() + 3.milli
      val payment1 = IncomingStandardPayment(paidInvoice1, randomBytes32(), PaymentType.Standard, paidInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(561 msat, receivedAt2))
      val payment2 = IncomingStandardPayment(paidInvoice2, randomBytes32(), PaymentType.Standard, paidInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(1111 msat, receivedAt2))
      val payment3 = IncomingBlindedPayment(paidInvoice3, randomBytes32(), PaymentType.Blinded, Map(randomKey().publicKey -> hex"beefbeef"), paidInvoice3.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(1730 msat, receivedAt3))

      db.addIncomingPayment(pendingInvoice1, pendingPayment1.paymentPreimage)
      db.addIncomingPayment(pendingInvoice2, pendingPayment2.paymentPreimage, PaymentType.SwapIn)
      db.addIncomingBlindedPayment(pendingInvoice3, pendingPayment3.paymentPreimage, pendingPayment3.pathIds, PaymentType.Blinded)
      db.addIncomingPayment(expiredInvoice1, expiredPayment1.paymentPreimage)
      db.addIncomingPayment(expiredInvoice2, expiredPayment2.paymentPreimage)
      db.addIncomingBlindedPayment(expiredInvoice3, expiredPayment3.paymentPreimage, expiredPayment3.pathIds, PaymentType.Blinded)
      db.addIncomingPayment(paidInvoice1, payment1.paymentPreimage)
      db.addIncomingPayment(paidInvoice2, payment2.paymentPreimage)
      db.addIncomingBlindedPayment(paidInvoice3, payment3.paymentPreimage, payment3.pathIds, PaymentType.Blinded)

      assert(db.getIncomingPayment(pendingInvoice1.paymentHash).contains(pendingPayment1))
      assert(db.getIncomingPayment(pendingInvoice3.paymentHash).contains(pendingPayment3))
      assert(db.getIncomingPayment(expiredInvoice2.paymentHash).contains(expiredPayment2))
      assert(db.getIncomingPayment(expiredInvoice3.paymentHash).contains(expiredPayment3))
      assert(db.getIncomingPayment(paidInvoice1.paymentHash).contains(payment1.copy(status = IncomingPaymentStatus.Pending)))
      assert(db.getIncomingPayment(paidInvoice3.paymentHash).contains(payment3.copy(status = IncomingPaymentStatus.Pending)))

      val now = TimestampMilli.now()
      assert(db.listIncomingPayments(0 unixms, now, None) == Seq(expiredPayment1, expiredPayment2, expiredPayment3, pendingPayment1, pendingPayment2, pendingPayment3, payment1.copy(status = IncomingPaymentStatus.Pending), payment2.copy(status = IncomingPaymentStatus.Pending), payment3.copy(status = IncomingPaymentStatus.Pending)))
      assert(db.listExpiredIncomingPayments(0 unixms, now) == Seq(expiredPayment1, expiredPayment2, expiredPayment3))
      assert(db.listReceivedIncomingPayments(0 unixms, now) == Nil)
      assert(db.listPendingIncomingPayments(0 unixms, now, None) == Seq(pendingPayment1, pendingPayment2, pendingPayment3, payment1.copy(status = IncomingPaymentStatus.Pending), payment2.copy(status = IncomingPaymentStatus.Pending), payment3.copy(status = IncomingPaymentStatus.Pending)))

      db.receiveIncomingPayment(paidInvoice1.paymentHash, 461 msat, receivedAt1)
      db.receiveIncomingPayment(paidInvoice1.paymentHash, 100 msat, receivedAt2) // adding another payment to this invoice should sum
      db.receiveIncomingPayment(paidInvoice2.paymentHash, 1111 msat, receivedAt2)
      db.receiveIncomingPayment(paidInvoice3.paymentHash, 1030 msat, receivedAt3)
      db.receiveIncomingPayment(paidInvoice3.paymentHash, 700 msat, receivedAt3)

      assert(db.getIncomingPayment(paidInvoice1.paymentHash).contains(payment1))
      assert(db.getIncomingPayment(paidInvoice3.paymentHash).contains(payment3))

      assert(db.listIncomingPayments(0 unixms, now, None) == Seq(expiredPayment1, expiredPayment2, expiredPayment3, pendingPayment1, pendingPayment2, pendingPayment3, payment1, payment2, payment3))
      assert(db.listIncomingPayments(now - 60.seconds, now, None) == Seq(pendingPayment1, pendingPayment2, pendingPayment3, payment1, payment2, payment3))
      assert(db.listIncomingPayments(0 unixms, now, Some(Paginated(0, 0))) == Seq())
      assert(db.listIncomingPayments(0 unixms, now, Some(Paginated(0, 3))) == Seq())
      assert(db.listIncomingPayments(0 unixms, now, Some(Paginated(3, 0))) == Seq(expiredPayment1, expiredPayment2, expiredPayment3))
      assert(db.listIncomingPayments(0 unixms, now, Some(Paginated(3, 3))) == Seq(pendingPayment1, pendingPayment2, pendingPayment3))
      assert(db.listPendingIncomingPayments(0 unixms, now, None) == Seq(pendingPayment1, pendingPayment2, pendingPayment3))
      assert(db.listPendingIncomingPayments(0 unixms, now, Some(Paginated(1, 1))) == Seq(pendingPayment2))
      assert(db.listReceivedIncomingPayments(0 unixms, now) == Seq(payment1, payment2, payment3))

      assert(db.removeIncomingPayment(paidInvoice1.paymentHash).isFailure)
      db.removeIncomingPayment(paidInvoice1.paymentHash).failed.foreach(e => assert(e.getMessage == "Cannot remove a received incoming payment"))
      assert(db.removeIncomingPayment(paidInvoice3.paymentHash).isFailure)
      db.removeIncomingPayment(paidInvoice3.paymentHash).failed.foreach(e => assert(e.getMessage == "Cannot remove a received incoming payment"))
      assert(db.removeIncomingPayment(pendingPayment1.invoice.paymentHash).isSuccess)
      assert(db.removeIncomingPayment(pendingPayment1.invoice.paymentHash).isSuccess) // idempotent
      assert(db.removeIncomingPayment(pendingPayment3.invoice.paymentHash).isSuccess)
      assert(db.removeIncomingPayment(expiredPayment1.invoice.paymentHash).isSuccess)
      assert(db.removeIncomingPayment(expiredPayment1.invoice.paymentHash).isSuccess) // idempotent
      assert(db.removeIncomingPayment(expiredPayment3.invoice.paymentHash).isSuccess)
    }
  }

  test("add/retrieve/update outgoing payments") {
    forAllDbs { dbs =>
      val db = dbs.payments

      val parentId = UUID.randomUUID()
      val i1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(123 msat), paymentHash1, davePriv, Left("Some invoice"), CltvExpiryDelta(18), expirySeconds = None, timestamp = 0 unixsec)
      val payerKey = randomKey()
      val i2 = createBolt12Invoice(789 msat, payerKey, carolPriv, randomBytes32())
      val s1 = OutgoingPayment(UUID.randomUUID(), parentId, None, paymentHash1, PaymentType.Standard, 123 msat, 600 msat, dave, 100 unixms, Some(i1), None, OutgoingPaymentStatus.Pending)
      val s2 = OutgoingPayment(UUID.randomUUID(), parentId, Some("1"), paymentHash1, PaymentType.SwapOut, 456 msat, 600 msat, dave, 200 unixms, None, None, OutgoingPaymentStatus.Pending)
      val b1 = OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), None, i2.paymentHash, PaymentType.Blinded, 789 msat, 789 msat, carol, 300 unixms, Some(i2), Some(payerKey), OutgoingPaymentStatus.Pending)

      assert(db.listOutgoingPayments(0 unixms, TimestampMilli.now()).isEmpty)
      db.addOutgoingPayment(s1)
      db.addOutgoingPayment(s2)
      db.addOutgoingPayment(b1)

      // can't add an outgoing payment in non-pending state
      assertThrows[IllegalArgumentException](db.addOutgoingPayment(s1.copy(status = OutgoingPaymentStatus.Succeeded(randomBytes32(), 0 msat, Nil, 110 unixms))))

      assert(db.listOutgoingPayments(1 unixms, 350 unixms).toList == Seq(s1, s2, b1))
      assert(db.listOutgoingPayments(1 unixms, 150 unixms).toList == Seq(s1))
      assert(db.listOutgoingPayments(150 unixms, 250 unixms).toList == Seq(s2))
      assert(db.getOutgoingPayment(s1.id).contains(s1))
      assert(db.getOutgoingPayment(UUID.randomUUID()).isEmpty)
      assert(db.listOutgoingPayments(s2.paymentHash) == Seq(s1, s2))
      assert(db.listOutgoingPayments(s1.id) == Nil)
      assert(db.listOutgoingPayments(parentId) == Seq(s1, s2))
      assert(db.listOutgoingPayments(ByteVector32.Zeroes) == Nil)
      assert(db.listOutgoingPayments(i2.paymentHash) == Seq(b1))
      assert(db.listOutgoingPaymentsToOffer(i2.invoiceRequest.offer.offerId) == Seq(b1))

      val s3 = s2.copy(id = UUID.randomUUID(), amount = 789 msat, createdAt = 300 unixms)
      val s4 = s2.copy(id = UUID.randomUUID(), paymentType = PaymentType.Standard, createdAt = 301 unixms)
      db.addOutgoingPayment(s3)
      db.addOutgoingPayment(s4)

      db.updateOutgoingPayment(PaymentFailed(s3.id, s3.paymentHash, Nil, 310 unixms))
      val ss3 = s3.copy(status = OutgoingPaymentStatus.Failed(Nil, 310 unixms))
      assert(db.getOutgoingPayment(s3.id).contains(ss3))
      db.updateOutgoingPayment(PaymentFailed(s4.id, s4.paymentHash, Seq(LocalFailure(s4.amount, Seq(hop_ab), new RuntimeException("woops")), RemoteFailure(s4.amount, Seq(hop_ab, hop_bc), Sphinx.DecryptedFailurePacket(carol, UnknownNextPeer()))), 320 unixms))
      val ss4 = s4.copy(status = OutgoingPaymentStatus.Failed(Seq(FailureSummary(FailureType.LOCAL, "woops", List(HopSummary(alice, bob, Some(ShortChannelId(42)))), Some(alice)), FailureSummary(FailureType.REMOTE, "processing node does not know the next peer in the route", List(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, None)), Some(carol))), 320 unixms))
      assert(db.getOutgoingPayment(s4.id).contains(ss4))

      // can't update again once it's in a final state
      assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentSent(parentId, s3.paymentHash, preimage1, s3.recipientAmount, s3.recipientNodeId, Seq(PaymentSent.PartialPayment(s3.id, s3.amount, 42 msat, randomBytes32(), None)))))

      val paymentSent = PaymentSent(parentId, paymentHash1, preimage1, 600 msat, carol, Seq(
        PaymentSent.PartialPayment(s1.id, s1.amount, 15 msat, randomBytes32(), None, 400 unixms),
        PaymentSent.PartialPayment(s2.id, s2.amount, 20 msat, randomBytes32(), Some(Seq(hop_ab, hop_bc)), 410 unixms)
      ))
      val ss1 = s1.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 15 msat, Nil, 400 unixms))
      val ss2 = s2.copy(status = OutgoingPaymentStatus.Succeeded(preimage1, 20 msat, Seq(HopSummary(alice, bob, Some(ShortChannelId(42))), HopSummary(bob, carol, None)), 410 unixms))
      db.updateOutgoingPayment(paymentSent)
      assert(db.getOutgoingPayment(s1.id).contains(ss1))
      assert(db.getOutgoingPayment(s2.id).contains(ss2))
      assert(db.listOutgoingPayments(parentId) == Seq(ss1, ss2, ss3, ss4))

      // can't update again once it's in a final state
      assertThrows[IllegalArgumentException](db.updateOutgoingPayment(PaymentFailed(s1.id, s1.paymentHash, Nil)))
    }
  }

}

object PaymentsDbSpec {
  val (alicePriv, bobPriv, carolPriv, davePriv) = (randomKey(), randomKey(), randomKey(), randomKey())
  val (alice, bob, carol, dave) = (alicePriv.publicKey, bobPriv.publicKey, carolPriv.publicKey, davePriv.publicKey)
  val hop_ab = ChannelHop(ShortChannelId(42), alice, bob, HopRelayParams.FromAnnouncement(ChannelUpdate(randomBytes64(), randomBytes32(), ShortChannelId(42), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(12), 1 msat, 1 msat, 1, 500_000_000 msat)))
  val hop_bc = NodeHop(bob, carol, CltvExpiryDelta(14), 1 msat)
  val (preimage1, preimage2, preimage3, preimage4) = (randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32())
  val (paymentHash1, paymentHash2, paymentHash3, paymentHash4) = (Crypto.sha256(preimage1), Crypto.sha256(preimage2), Crypto.sha256(preimage3), Crypto.sha256(preimage4))

  def createBolt12Invoice(amount: MilliSatoshi, payerKey: PrivateKey, recipientKey: PrivateKey, preimage: ByteVector32): Bolt12Invoice = {
    val offer = Offer(Some(amount), "some offer", recipientKey.publicKey, Features.empty, Block.TestnetGenesisBlock.hash)
    val invoiceRequest = InvoiceRequest(offer, 789 msat, 1, Features.empty, payerKey, Block.TestnetGenesisBlock.hash)
    val dummyRoute = PaymentBlindedRoute(RouteBlinding.create(randomKey(), Seq(randomKey().publicKey), Seq(randomBytes(100))).route, PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 0 msat, Features.empty))
    Bolt12Invoice(invoiceRequest, preimage, recipientKey, 1 hour, Features.empty, Seq(dummyRoute))
  }
}
