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
import fr.acinq.eclair.db.PaymentsDb.{decodeFailures, decodeRoute, encodeFailures, encodeRoute}
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment.{Bolt11Invoice, Invoice, PaymentFailed, PaymentSent}
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli, TimestampMilliLong}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

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

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
        statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, payment_preimage BLOB, payment_type TEXT NOT NULL, amount_msat INTEGER NOT NULL, fees_msat INTEGER, recipient_amount_msat INTEGER NOT NULL, recipient_node_id BLOB NOT NULL, payment_request TEXT, payment_route BLOB, failures BLOB, created_at INTEGER NOT NULL, completed_at INTEGER)")

        statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON sent_payments(parent_id)")
        statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON sent_payments(payment_hash)")
        statement.executeUpdate("CREATE INDEX sent_created_idx ON sent_payments(created_at)")
        statement.executeUpdate("CREATE INDEX received_created_idx ON received_payments(created_at)")
      case Some(v@(1 | 2 | 3)) =>
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
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)

  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = withMetrics("payments/add-outgoing", DbBackends.Sqlite) {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    using(sqlite.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
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

  override def addIncomingPayment(invoice: Invoice, preimage: ByteVector32, paymentType: String): Unit = withMetrics("payments/add-incoming", DbBackends.Sqlite) {
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

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli): Boolean = withMetrics("payments/receive-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (? + COALESCE(received_msat, 0), ?) WHERE payment_hash = ?")) { update =>
      update.setLong(1, amount.toLong)
      update.setLong(2, receivedAt.toLong)
      update.setBytes(3, paymentHash.toArray)
      val updated = update.executeUpdate()
      updated > 0
    }
  }

  private def parseIncomingPayment(rs: ResultSet): IncomingPayment = {
    val invoice = Invoice.fromString(rs.getString("payment_request")).get
    IncomingPayment(
      invoice,
      rs.getByteVector32("payment_preimage"),
      rs.getString("payment_type"),
      TimestampMilli(rs.getLong("created_at")),
      buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), Some(invoice), rs.getLongNullable("received_at").map(TimestampMilli(_))))
  }

  private def buildIncomingPaymentStatus(amount_opt: Option[MilliSatoshi], invoice_opt: Option[Invoice], receivedAt_opt: Option[TimestampMilli]): IncomingPaymentStatus = {
    amount_opt match {
      case Some(amount) => IncomingPaymentStatus.Received(amount, receivedAt_opt.getOrElse(0 unixms))
      case None if invoice_opt.exists(_.isExpired()) => IncomingPaymentStatus.Expired
      case None => IncomingPaymentStatus.Pending
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = withMetrics("payments/get-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      statement.executeQuery().map(parseIncomingPayment).headOption
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

  override def listIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery().map(parseIncomingPayment).toSeq
    }
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-received", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery().map(parseIncomingPayment).toSeq
    }
  }

  override def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-pending", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.setLong(3, TimestampMilli.now().toLong)
      statement.executeQuery().map(parseIncomingPayment).toSeq
    }
  }

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-expired", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.setLong(3, TimestampMilli.now().toLong)
      statement.executeQuery().map(parseIncomingPayment).toSeq
    }
  }

  override def listPaymentsOverview(limit: Int): Seq[PlainPayment] = withMetrics("payments/list-overview", DbBackends.Sqlite) {
    // This query is an UNION of the ``sent_payments`` and ``received_payments`` table
    // - missing fields set to NULL when needed.
    // - only retrieve incoming payments that did receive funds.
    // - outgoing payments are grouped by parent_id.
    // - order by completion date (or creation date if nothing else).
    using(sqlite.prepareStatement(
      """
        |SELECT * FROM (
        |	 SELECT 'received' as type,
        |	   NULL as parent_id,
        |    NULL as external_id,
        |    payment_hash,
        |    payment_preimage,
        |    payment_type,
        |    received_msat as final_amount,
        |    payment_request,
        |    created_at,
        |    received_at as completed_at,
        |    expire_at,
        |    NULL as order_trick
        |  FROM received_payments
        |  WHERE final_amount > 0
        |UNION ALL
        |  SELECT 'sent' as type,
        |	   parent_id,
        |    external_id,
        |    payment_hash,
        |    payment_preimage,
        |    payment_type,
        |    sum(amount_msat + fees_msat) as final_amount,
        |    payment_request,
        |    created_at,
        |    completed_at,
        |    NULL as expire_at,
        |    MAX(coalesce(completed_at, created_at)) as order_trick
        |  FROM sent_payments
        |  GROUP BY parent_id
        |)
        |ORDER BY coalesce(completed_at, created_at) DESC
        |LIMIT ?
      """.stripMargin
    )) { statement =>
      statement.setInt(1, limit)
      statement.executeQuery()
        .map { rs =>
          val parentId = rs.getUUIDNullable("parent_id")
          val externalId_opt = rs.getStringNullable("external_id")
          val paymentHash = rs.getByteVector32("payment_hash")
          val paymentType = rs.getString("payment_type")
          val invoice_opt = rs.getStringNullable("payment_request")
          val amount_opt = rs.getMilliSatoshiNullable("final_amount")
          val createdAt = TimestampMilli(rs.getLong("created_at"))
          val completedAt_opt = rs.getLongNullable("completed_at").map(TimestampMilli(_))
          val expireAt_opt = rs.getLongNullable("expire_at").map(TimestampMilli(_))

          if (rs.getString("type") == "received") {
            val status: IncomingPaymentStatus = buildIncomingPaymentStatus(amount_opt, invoice_opt.map(Invoice.fromString(_).get), completedAt_opt)
            PlainIncomingPayment(paymentHash, paymentType, amount_opt, invoice_opt, status, createdAt, completedAt_opt, expireAt_opt)
          } else {
            val preimage_opt = rs.getByteVector32Nullable("payment_preimage")
            // note that the resulting status will not contain any details (routes, failures...)
            val status: OutgoingPaymentStatus = buildOutgoingPaymentStatus(preimage_opt, None, None, completedAt_opt, None)
            PlainOutgoingPayment(parentId, externalId_opt, paymentHash, paymentType, amount_opt, invoice_opt, status, createdAt, completedAt_opt)
          }
        }.toSeq
    }
  }
}

object SqlitePaymentsDb {
  val DB_NAME = "payments"
  val CURRENT_VERSION = 4
}