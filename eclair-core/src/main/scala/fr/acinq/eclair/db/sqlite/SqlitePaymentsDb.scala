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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PaymentsDb._
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{MilliSatoshi, Paginated, TimestampMilli, TimestampMilliLong}
import grizzled.slf4j.Logging
import scodec.bits.{BitVector, ByteVector}

import java.sql.{Connection, ResultSet, Statement}
import java.util.UUID
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class SqlitePaymentsDb(val sqlite: Connection) extends PaymentsDb with Logging {

  import SqlitePaymentsDb._
  import SqliteUtils.ExtendedResultSet._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      // Version 2 is "backwards compatible" in the sense that it uses separate tables from version 1 (which used a single "payments" table).
      statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER, received_at INTEGER)")
      statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, preimage BLOB, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, completed_at INTEGER, status VARCHAR NOT NULL)")
      statement.executeUpdate("CREATE INDEX payment_hash_idx ON sent_payments(payment_hash)")
    }

    def migration23(statement: Statement): Unit = {
      // We add many more columns to the sent_payments table.
      statement.executeUpdate("DROP index payment_hash_idx")
      statement.executeUpdate("ALTER TABLE sent_payments RENAME TO _sent_payments_old")
      statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, target_node_id BLOB NOT NULL, created_at INTEGER NOT NULL, payment_request TEXT, completed_at INTEGER, payment_preimage BLOB, fees_msat INTEGER, payment_route BLOB, failures BLOB)")
      // Old rows will be missing a target node id, so we use an easy-to-spot default value.
      val defaultTargetNodeId = PrivateKey(ByteVector32.One).publicKey
      statement.executeUpdate(s"INSERT INTO sent_payments (id, parent_id, payment_hash, amount_msat, target_node_id, created_at, completed_at, payment_preimage) SELECT id, id, payment_hash, amount_msat, X'${defaultTargetNodeId.toString}', created_at, completed_at, preimage FROM _sent_payments_old")
      statement.executeUpdate("DROP table _sent_payments_old")

      statement.executeUpdate("ALTER TABLE received_payments RENAME TO _received_payments_old")
      // We make invoice expiration not null in the received_payments table.
      // When it was previously set to NULL the default expiry should apply.
      statement.executeUpdate(s"UPDATE _received_payments_old SET expire_at = created_at + ${Bolt11Invoice.DEFAULT_EXPIRY_SECONDS} WHERE expire_at IS NULL")
      statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
      statement.executeUpdate("INSERT INTO received_payments (payment_hash, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at) SELECT payment_hash, preimage, payment_request, received_msat, created_at, expire_at, received_at FROM _received_payments_old")
      statement.executeUpdate("DROP table _received_payments_old")

      statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
      statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
      statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")
      statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
    }

    def migration34(statement: Statement): Unit = {
      // We add a recipient_amount_msat and payment_type columns, rename some columns and change column order.
      statement.executeUpdate("DROP index sent_parent_id_idx")
      statement.executeUpdate("DROP index sent_payment_hash_idx")
      statement.executeUpdate("DROP index sent_created_idx")
      statement.executeUpdate("ALTER TABLE sent_payments RENAME TO _sent_payments_old")
      statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, payment_preimage BLOB, payment_type TEXT NOT NULL, amount_msat INTEGER NOT NULL, fees_msat INTEGER, recipient_amount_msat INTEGER NOT NULL, recipient_node_id BLOB NOT NULL, payment_request TEXT, payment_route BLOB, failures BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)")
      statement.executeUpdate("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, payment_preimage, payment_type, amount_msat, fees_msat, recipient_amount_msat, recipient_node_id, payment_request, payment_route, failures, created_at, completed_at) SELECT id, parent_id, external_id, payment_hash, payment_preimage, 'Standard', amount_msat, fees_msat, amount_msat, target_node_id, payment_request, payment_route, failures, created_at, completed_at FROM _sent_payments_old")
      statement.executeUpdate("DROP table _sent_payments_old")
      statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
      statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
      statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")

      // We add payment_type column.
      statement.executeUpdate("DROP index received_created_idx")
      statement.executeUpdate("ALTER TABLE received_payments RENAME TO _received_payments_old")
      statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
      statement.executeUpdate("INSERT INTO received_payments (payment_hash, payment_type, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at) SELECT payment_hash, 'Standard', payment_preimage, payment_request, received_msat, created_at, expire_at, received_at FROM _received_payments_old")
      statement.executeUpdate("DROP table _received_payments_old")
      statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
    }

    def migration45(statement: Statement): Unit = {
      // We add a path_ids column for blinded payments.
      statement.executeUpdate("ALTER TABLE received_payments RENAME to _received_payments_old")
      statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage BLOB NOT NULL, path_ids BLOB, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
      statement.executeUpdate("INSERT INTO received_payments (payment_hash, payment_type, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at) SELECT payment_hash, payment_type, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at FROM _received_payments_old")
      statement.executeUpdate("DROP table _received_payments_old")
      statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
    }

    def migration56(statement: Statement): Unit = {
      statement.executeUpdate("ALTER TABLE sent_payments ADD COLUMN offer_id BLOB")
      statement.executeUpdate("ALTER TABLE sent_payments ADD COLUMN payer_key BLOB")
      statement.executeUpdate("CREATE INDEX sent_payment_offer_idx ON sent_payments(offer_id)")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage BLOB NOT NULL, path_ids BLOB, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
        statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, payment_preimage BLOB, payment_type TEXT NOT NULL, amount_msat INTEGER NOT NULL, fees_msat INTEGER, recipient_amount_msat INTEGER NOT NULL, recipient_node_id BLOB NOT NULL, payment_request TEXT, offer_id BLOB, payer_key BLOB, payment_route BLOB, failures BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)")

        statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
        statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
        statement.executeUpdate("CREATE INDEX sent_payment_offer_idx ON sent_payments(offer_id)")
        statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")
        statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
      case Some(v@(1 | 2 | 3 | 4 | 5)) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        if (v < 2) {
          migration12(statement)
        }
        if (v < 3) {
          migration23(statement)
        }
        if (v < 4) {
          migration34(statement)
        }
        if (v < 5) {
          migration45(statement)
        }
        if (v < 6) {
          migration56(statement)
        }
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)

  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = withMetrics("payments/add-outgoing", DbBackends.Sqlite) {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    using(sqlite.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request, offer_id, payer_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, sent.id.toString)
      statement.setString(2, sent.parentId.toString)
      statement.setString(3, sent.externalId.orNull)
      statement.setBytes(4, sent.paymentHash.toArray)
      statement.setString(5, sent.paymentType)
      statement.setLong(6, sent.amount.toLong)
      statement.setLong(7, sent.recipientAmount.toLong)
      statement.setBytes(8, sent.recipientNodeId.value.toArray)
      statement.setLong(9, sent.createdAt.toLong)
      statement.setString(10, sent.invoice.map(_.toString).orNull)
      val offerId = sent.invoice match {
        case Some(invoice: Bolt12Invoice) => Some(invoice.invoiceRequest.offer.offerId)
        case _ => None
      }
      statement.setBytes(11, offerId.map(_.toArray).orNull)
      statement.setBytes(12, sent.payerKey_opt.map(_.value.toArray).orNull)
      statement.executeUpdate()
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit = withMetrics("payments/update-outgoing-sent", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE sent_payments SET (completed_at, payment_preimage, fees_msat, payment_route) = (?, ?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
      paymentResult.parts.foreach(p => {
        statement.setLong(1, p.timestamp.toLong)
        statement.setBytes(2, paymentResult.paymentPreimage.toArray)
        statement.setLong(3, p.feesPaid.toLong)
        statement.setBytes(4, encodeRoute(p.route.getOrElse(Nil).map(h => HopSummary(h)).toList))
        statement.setString(5, p.id.toString)
        statement.addBatch()
      })
      if (statement.executeBatch().contains(0)) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as succeeded but already in final status (id=${paymentResult.id})")
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit = withMetrics("payments/update-outgoing-failed", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE sent_payments SET (completed_at, failures) = (?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
      statement.setLong(1, paymentResult.timestamp.toLong)
      statement.setBytes(2, encodeFailures(paymentResult.failures.map(f => FailureSummary(f)).toList))
      statement.setString(3, paymentResult.id.toString)
      if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as failed but already in final status (id=${paymentResult.id})")
    }
  }

  private def parseOutgoingPayment(rs: ResultSet): OutgoingPayment = {
    val status = buildOutgoingPaymentStatus(
      rs.getByteVector32Nullable("payment_preimage"),
      rs.getMilliSatoshiNullable("fees_msat"),
      rs.getBitVectorOpt("payment_route"),
      rs.getLongNullable("completed_at").map(TimestampMilli(_)),
      rs.getBitVectorOpt("failures"))

    OutgoingPayment(
      UUID.fromString(rs.getString("id")),
      UUID.fromString(rs.getString("parent_id")),
      rs.getStringNullable("external_id"),
      rs.getByteVector32("payment_hash"),
      rs.getString("payment_type"),
      MilliSatoshi(rs.getLong("amount_msat")),
      MilliSatoshi(rs.getLong("recipient_amount_msat")),
      PublicKey(rs.getByteVector("recipient_node_id")),
      TimestampMilli(rs.getLong("created_at")),
      rs.getStringNullable("payment_request").map(Invoice.fromString(_).get),
      rs.getByteVectorNullable("payer_key").map(PrivateKey(_)),
      status
    )
  }

  private def buildOutgoingPaymentStatus(preimage_opt: Option[ByteVector32], fees_opt: Option[MilliSatoshi], paymentRoute_opt: Option[BitVector], completedAt_opt: Option[TimestampMilli], failures: Option[BitVector]): OutgoingPaymentStatus = {
    preimage_opt match {
      // If we have a pre-image, the payment succeeded.
      case Some(preimage) => OutgoingPaymentStatus.Succeeded(
        preimage, fees_opt.getOrElse(MilliSatoshi(0)), paymentRoute_opt.map(decodeRoute).getOrElse(Nil),
        completedAt_opt.getOrElse(0 unixms)
      )
      case None => completedAt_opt match {
        // Otherwise if the payment was marked completed, it's a failure.
        case Some(completedAt) => OutgoingPaymentStatus.Failed(
          failures.map(decodeFailures).getOrElse(Nil),
          completedAt
        )
        // Else it's still pending.
        case _ => OutgoingPaymentStatus.Pending
      }
    }
  }

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] = withMetrics("payments/get-outgoing", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE id = ?")) { statement =>
      statement.setString(1, id.toString)
      statement.executeQuery().map(parseOutgoingPayment).headOption
    }
  }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-parent-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE parent_id = ? ORDER BY created_at")) { statement =>
      statement.setString(1, parentId.toString)
      statement.executeQuery().map(parseOutgoingPayment).toSeq
    }
  }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-payment-hash", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE payment_hash = ? ORDER BY created_at")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      statement.executeQuery().map(parseOutgoingPayment).toSeq
    }
  }

  override def listOutgoingPayments(from: TimestampMilli, to: TimestampMilli): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-timestamp", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery().map(parseOutgoingPayment).toSeq
    }
  }

  override def listOutgoingPaymentsToOffer(offerId: ByteVector32): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-offer-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE offer_id = ? ORDER BY created_at")) { statement =>
      statement.setBytes(1, offerId.toArray)
      statement.executeQuery().map(parseOutgoingPayment).toSeq
    }
  }

  override def addIncomingPayment(invoice: Bolt11Invoice, preimage: ByteVector32, paymentType: String): Unit = withMetrics("payments/add-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, invoice.paymentHash.toArray)
      statement.setBytes(2, preimage.toArray)
      statement.setString(3, paymentType)
      statement.setString(4, invoice.toString)
      statement.setLong(5, invoice.createdAt.toTimestampMilli.toLong)
      statement.setLong(6, (invoice.createdAt + invoice.relativeExpiry).toLong.seconds.toMillis)
      statement.executeUpdate()
    }
  }

  override def addIncomingBlindedPayment(invoice: Bolt12Invoice, preimage: ByteVector32, pathIds: Map[PublicKey, ByteVector], paymentType: String): Unit = withMetrics("payments/add-incoming-blinded", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, path_ids, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, invoice.paymentHash.toArray)
      statement.setBytes(2, preimage.toArray)
      statement.setBytes(3, encodePathIds(pathIds))
      statement.setString(4, paymentType)
      statement.setString(5, invoice.toString)
      statement.setLong(6, invoice.createdAt.toTimestampMilli.toLong)
      statement.setLong(7, (invoice.createdAt + invoice.relativeExpiry).toLong.seconds.toMillis)
      statement.executeUpdate()
    }
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli): Boolean = withMetrics("payments/receive-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (? + COALESCE(received_msat, 0), ?) WHERE payment_hash = ?")) { update =>
      update.setLong(1, amount.toLong)
      update.setLong(2, receivedAt.toLong)
      update.setBytes(3, paymentHash.toArray)
      val updated = update.executeUpdate()
      updated > 0
    }
  }

  private def parseIncomingPayment(rs: ResultSet): Option[IncomingPayment] = {
    val invoice = rs.getString("payment_request")
    val preimage = rs.getByteVector32("payment_preimage")
    val paymentType = rs.getString("payment_type")
    val createdAt = TimestampMilli(rs.getLong("created_at"))
    Invoice.fromString(invoice) match {
      case Success(invoice: Bolt11Invoice) =>
        val status = buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), invoice, rs.getLongNullable("received_at").map(TimestampMilli(_)))
        Some(IncomingStandardPayment(invoice, preimage, paymentType, createdAt, status))
      case Success(invoice: Bolt12Invoice) =>
        val status = buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), invoice, rs.getLongNullable("received_at").map(TimestampMilli(_)))
        val pathIds = decodePathIds(BitVector(rs.getBytes("path_ids")))
        Some(IncomingBlindedPayment(invoice, preimage, paymentType, pathIds, createdAt, status))
      case _ =>
        logger.error(s"could not parse DB invoice=$invoice, this should not happen")
        None
    }
  }

  private def buildIncomingPaymentStatus(amount_opt: Option[MilliSatoshi], invoice: Invoice, receivedAt_opt: Option[TimestampMilli]): IncomingPaymentStatus = {
    amount_opt match {
      case Some(amount) => IncomingPaymentStatus.Received(amount, receivedAt_opt.getOrElse(0 unixms))
      case None if invoice.isExpired() => IncomingPaymentStatus.Expired
      case None => IncomingPaymentStatus.Pending
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = withMetrics("payments/get-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      statement.executeQuery().flatMap(parseIncomingPayment).headOption
    }
  }

  override def removeIncomingPayment(paymentHash: ByteVector32): Try[Unit] = withMetrics("payments/remove-incoming", DbBackends.Sqlite) {
    getIncomingPayment(paymentHash) match {
      case Some(incomingPayment) =>
        incomingPayment.status match {
          case _: IncomingPaymentStatus.Received => Failure(new IllegalArgumentException("Cannot remove a received incoming payment"))
          case _: IncomingPaymentStatus =>
            using(sqlite.prepareStatement("DELETE FROM received_payments WHERE payment_hash = ?")) { delete =>
              delete.setBytes(1, paymentHash.toArray)
              delete.executeUpdate()
              Success(())
            }
        }
      case None => Success(())
    }
  }

  override def listIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = withMetrics("payments/list-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement(limited("SELECT * FROM received_payments WHERE created_at > ? AND created_at < ? ORDER BY created_at", paginated_opt))) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery().flatMap(parseIncomingPayment).toSeq
    }
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-received", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery().flatMap(parseIncomingPayment).toSeq
    }
  }

  override def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = withMetrics("payments/list-incoming-pending", DbBackends.Sqlite) {
    using(sqlite.prepareStatement(limited("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at", paginated_opt))) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.setLong(3, TimestampMilli.now().toLong)
      statement.executeQuery().flatMap(parseIncomingPayment).toSeq
    }
  }

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-expired", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.setLong(3, TimestampMilli.now().toLong)
      statement.executeQuery().flatMap(parseIncomingPayment).toSeq
    }
  }

}

object SqlitePaymentsDb {
  val DB_NAME = "payments"
  val CURRENT_VERSION = 6
}