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

package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PaymentsDb._
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{MilliSatoshi, Paginated, TimestampMilli, TimestampMilliLong}
import grizzled.slf4j.Logging
import scodec.bits.{BitVector, ByteVector}

import java.sql.{Connection, ResultSet, Statement, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.{Failure, Success, Try}

object PgPaymentsDb {
  val DB_NAME = "payments"
  val CURRENT_VERSION = 8
}

class PgPaymentsDb(implicit ds: DataSource, lock: PgLock) extends PaymentsDb with Logging {

  import PgPaymentsDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>

      def migration45(statement: Statement): Unit = {
        statement.executeUpdate("CREATE SCHEMA payments")
        statement.executeUpdate("ALTER TABLE received_payments RENAME TO received")
        statement.executeUpdate("ALTER TABLE received SET SCHEMA payments")
        statement.executeUpdate("ALTER TABLE sent_payments RENAME TO sent")
        statement.executeUpdate("ALTER TABLE sent SET SCHEMA payments")
      }

      def migration56(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE payments.received ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + created_at * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE payments.received ALTER COLUMN expire_at SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + expire_at * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE payments.received ALTER COLUMN received_at SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + received_at * interval '1 millisecond'")

        statement.executeUpdate("ALTER TABLE payments.sent ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + created_at * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE payments.sent ALTER COLUMN completed_at SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + completed_at * interval '1 millisecond'")
      }

      def migration67(statement: Statement): Unit = {
        // We add a path_ids column for blinded payments.
        statement.executeUpdate("ALTER TABLE payments.received ADD COLUMN path_ids BYTEA")
      }

      def migration78(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE payments.sent ADD COLUMN offer_id TEXT")
        statement.executeUpdate("ALTER TABLE payments.sent ADD COLUMN payer_key TEXT")
        statement.executeUpdate("CREATE INDEX sent_payment_offer_idx ON payments.sent(offer_id)")
      }

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA payments")

          statement.executeUpdate("CREATE TABLE payments.received (payment_hash TEXT NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage TEXT NOT NULL, path_ids BYTEA, payment_request TEXT NOT NULL, received_msat BIGINT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, expire_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE TABLE payments.sent (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, payment_preimage TEXT, payment_type TEXT NOT NULL, amount_msat BIGINT NOT NULL, fees_msat BIGINT, recipient_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, payment_request TEXT, offer_id TEXT, payer_key TEXT, payment_route BYTEA, failures BYTEA, created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE)")

          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON payments.sent(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON payments.sent(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_payment_offer_idx ON payments.sent(offer_id)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON payments.sent(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON payments.received(created_at)")
        case Some(v@(4 | 5 | 6 | 7)) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
          if (v < 5) {
            migration45(statement)
          }
          if (v < 6) {
            migration56(statement)
          }
          if (v < 7) {
            migration67(statement)
          }
          if (v < 8) {
            migration78(statement)
          }
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = withMetrics("payments/add-outgoing", DbBackends.Postgres) {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO payments.sent (id, parent_id, external_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request, offer_id, payer_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, sent.id.toString)
        statement.setString(2, sent.parentId.toString)
        statement.setString(3, sent.externalId.orNull)
        statement.setString(4, sent.paymentHash.toHex)
        statement.setString(5, sent.paymentType)
        statement.setLong(6, sent.amount.toLong)
        statement.setLong(7, sent.recipientAmount.toLong)
        statement.setString(8, sent.recipientNodeId.value.toHex)
        statement.setTimestamp(9, sent.createdAt.toSqlTimestamp)
        statement.setString(10, sent.invoice.map(_.toString).orNull)
        val offerId = sent.invoice match {
          case Some(invoice: Bolt12Invoice) => Some(invoice.invoiceRequest.offer.offerId)
          case _ => None
        }
        statement.setString(11, offerId.map(_.toHex).orNull)
        statement.setString(12, sent.payerKey_opt.map(_.toHex).orNull)

        statement.executeUpdate()
      }
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit = withMetrics("payments/update-outgoing-sent", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE payments.sent SET (completed_at, payment_preimage, fees_msat, payment_route) = (?, ?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
        paymentResult.parts.foreach(p => {
          statement.setTimestamp(1, p.timestamp.toSqlTimestamp)
          statement.setString(2, paymentResult.paymentPreimage.toHex)
          statement.setLong(3, p.feesPaid.toLong)
          statement.setBytes(4, encodeRoute(p.route.getOrElse(Nil).map(h => HopSummary(h)).toList))
          statement.setString(5, p.id.toString)
          statement.addBatch()
        })
        if (statement.executeBatch().contains(0)) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as succeeded but already in final status (id=${paymentResult.id})")
      }
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit = withMetrics("payments/update-outgoing-failed", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE payments.sent SET (completed_at, failures) = (?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
        statement.setTimestamp(1, paymentResult.timestamp.toSqlTimestamp)
        statement.setBytes(2, encodeFailures(paymentResult.failures.map(f => FailureSummary(f)).toList))
        statement.setString(3, paymentResult.id.toString)
        if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as failed but already in final status (id=${paymentResult.id})")
      }
    }
  }

  private def parseOutgoingPayment(rs: ResultSet): OutgoingPayment = {
    val status = buildOutgoingPaymentStatus(
      rs.getByteVector32FromHexNullable("payment_preimage"),
      rs.getMilliSatoshiNullable("fees_msat"),
      rs.getBitVectorOpt("payment_route"),
      rs.getTimestampNullable("completed_at").map(TimestampMilli.fromSqlTimestamp),
      rs.getBitVectorOpt("failures"))

    OutgoingPayment(
      UUID.fromString(rs.getString("id")),
      UUID.fromString(rs.getString("parent_id")),
      rs.getStringNullable("external_id"),
      rs.getByteVector32FromHex("payment_hash"),
      rs.getString("payment_type"),
      MilliSatoshi(rs.getLong("amount_msat")),
      MilliSatoshi(rs.getLong("recipient_amount_msat")),
      PublicKey(rs.getByteVectorFromHex("recipient_node_id")),
      TimestampMilli(rs.getTimestamp("created_at").getTime),
      rs.getStringNullable("payment_request").map(Invoice.fromString(_).get),
      rs.getByteVectorFromHexNullable("payer_key").map(PrivateKey(_)),
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

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] = withMetrics("payments/get-outgoing", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.sent WHERE id = ?")) { statement =>
        statement.setString(1, id.toString)
        statement.executeQuery().map(parseOutgoingPayment).headOption
      }
    }
  }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-parent-id", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.sent WHERE parent_id = ? ORDER BY created_at")) { statement =>
        statement.setString(1, parentId.toString)
        statement.executeQuery().map(parseOutgoingPayment).toSeq
      }
    }
  }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-payment-hash", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.sent WHERE payment_hash = ? ORDER BY created_at")) { statement =>
        statement.setString(1, paymentHash.toHex)
        statement.executeQuery().map(parseOutgoingPayment).toSeq
      }
    }
  }

  override def listOutgoingPayments(from: TimestampMilli, to: TimestampMilli): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-timestamp", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.sent WHERE created_at >= ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().map { rs =>
          parseOutgoingPayment(rs)
        }.toSeq
      }
    }
  }

  override def listOutgoingPaymentsToOffer(offerId: ByteVector32): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-offer-id", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.sent WHERE offer_id = ? ORDER BY created_at")) { statement =>
        statement.setString(1, offerId.toHex)
        statement.executeQuery().map(parseOutgoingPayment).toSeq
      }
    }
  }

  override def addIncomingPayment(invoice: Bolt11Invoice, preimage: ByteVector32, paymentType: String): Unit = withMetrics("payments/add-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO payments.received (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, invoice.paymentHash.toHex)
        statement.setString(2, preimage.toHex)
        statement.setString(3, paymentType)
        statement.setString(4, invoice.toString)
        statement.setTimestamp(5, invoice.createdAt.toSqlTimestamp)
        statement.setTimestamp(6, (invoice.createdAt + invoice.relativeExpiry.toSeconds).toSqlTimestamp)
        statement.executeUpdate()
      }
    }
  }

  override def addIncomingBlindedPayment(invoice: Bolt12Invoice, preimage: ByteVector32, pathIds: Map[PublicKey, ByteVector], paymentType: String): Unit = withMetrics("payments/add-incoming-blinded", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO payments.received (payment_hash, payment_preimage, path_ids, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, invoice.paymentHash.toHex)
        statement.setString(2, preimage.toHex)
        statement.setBytes(3, encodePathIds(pathIds))
        statement.setString(4, paymentType)
        statement.setString(5, invoice.toString)
        statement.setTimestamp(6, invoice.createdAt.toSqlTimestamp)
        statement.setTimestamp(7, (invoice.createdAt + invoice.relativeExpiry.toSeconds).toSqlTimestamp)
        statement.executeUpdate()
      }
    }
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli): Boolean = withMetrics("payments/receive-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE payments.received SET (received_msat, received_at) = (? + COALESCE(received_msat, 0), ?) WHERE payment_hash = ?")) { update =>
        update.setLong(1, amount.toLong)
        update.setTimestamp(2, receivedAt.toSqlTimestamp)
        update.setString(3, paymentHash.toHex)
        val updated = update.executeUpdate()
        updated > 0
      }
    }
  }

  private def parseIncomingPayment(rs: ResultSet): Option[IncomingPayment] = {
    val invoice = rs.getString("payment_request")
    val preimage = rs.getByteVector32FromHex("payment_preimage")
    val paymentType = rs.getString("payment_type")
    val createdAt = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("created_at"))
    Invoice.fromString(invoice) match {
      case Success(invoice: Bolt11Invoice) =>
        val status = buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), invoice, rs.getTimestampNullable("received_at").map(TimestampMilli.fromSqlTimestamp))
        Some(IncomingStandardPayment(invoice, preimage, paymentType, createdAt, status))
      case Success(invoice: Bolt12Invoice) =>
        val status = buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), invoice, rs.getTimestampNullable("received_at").map(TimestampMilli.fromSqlTimestamp))
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

  private def getIncomingPaymentInternal(pg: Connection, paymentHash: ByteVector32): Option[IncomingPayment] = {
    using(pg.prepareStatement("SELECT * FROM payments.received WHERE payment_hash = ?")) { statement =>
      statement.setString(1, paymentHash.toHex)
      statement.executeQuery().flatMap(parseIncomingPayment).headOption
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = withMetrics("payments/get-incoming", DbBackends.Postgres) {
    withLock { pg =>
      getIncomingPaymentInternal(pg, paymentHash)
    }
  }

  override def removeIncomingPayment(paymentHash: ByteVector32): Try[Unit] = withMetrics("payments/remove-incoming", DbBackends.Postgres) {
    withLock { pg =>
      getIncomingPaymentInternal(pg, paymentHash) match {
        case Some(incomingPayment) =>
          incomingPayment.status match {
            case _: IncomingPaymentStatus.Received => Failure(new IllegalArgumentException("Cannot remove a received incoming payment"))
            case _: IncomingPaymentStatus =>
              using(pg.prepareStatement("DELETE FROM payments.received WHERE payment_hash = ?")) { delete =>
                delete.setString(1, paymentHash.toHex)
                delete.executeUpdate()
                Success(())
              }
          }
        case None => Success(())
      }
    }
  }

  override def listIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = withMetrics("payments/list-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement(limited("SELECT * FROM payments.received WHERE created_at > ? AND created_at < ? ORDER BY created_at", paginated_opt))) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().flatMap(parseIncomingPayment).toSeq
      }
    }
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-received", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.received WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().flatMap(parseIncomingPayment).toSeq
      }
    }
  }

  override def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment] = withMetrics("payments/list-incoming-pending", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement(limited("SELECT * FROM payments.received WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at", paginated_opt))) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeQuery().flatMap(parseIncomingPayment).toSeq
      }
    }
  }

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-expired", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.received WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeQuery().flatMap(parseIncomingPayment).toSeq
      }
    }
  }

}