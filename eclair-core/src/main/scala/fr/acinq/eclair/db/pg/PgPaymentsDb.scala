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

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PaymentsDb.{decodeFailures, decodeRoute, encodeFailures, encodeRoute}
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli, TimestampMilliLong}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.{Connection, ResultSet, Statement, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Success, Try}

object PgPaymentsDb {
  val DB_NAME = "payments"
  val CURRENT_VERSION = 6
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

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA payments")

          statement.executeUpdate("CREATE TABLE payments.received (payment_hash TEXT NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage TEXT NOT NULL, payment_request TEXT NOT NULL, received_msat BIGINT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, expire_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE TABLE payments.sent (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, payment_preimage TEXT, payment_type TEXT NOT NULL, amount_msat BIGINT NOT NULL, fees_msat BIGINT, recipient_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, payment_request TEXT, payment_route BYTEA, failures BYTEA, created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE)")

          statement.executeUpdate("CREATE INDEX sent_parent_id_idx ON payments.sent(parent_id)")
          statement.executeUpdate("CREATE INDEX sent_payment_hash_idx ON payments.sent(payment_hash)")
          statement.executeUpdate("CREATE INDEX sent_created_idx ON payments.sent(created_at)")
          statement.executeUpdate("CREATE INDEX received_created_idx ON payments.received(created_at)")
        case Some(v@(4 | 5)) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
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
  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = withMetrics("payments/add-outgoing", DbBackends.Postgres) {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO payments.sent (id, parent_id, external_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, sent.id.toString)
        statement.setString(2, sent.parentId.toString)
        statement.setString(3, sent.externalId.orNull)
        statement.setString(4, sent.paymentHash.toHex)
        statement.setString(5, sent.paymentType)
        statement.setLong(6, sent.amount.toLong)
        statement.setLong(7, sent.recipientAmount.toLong)
        statement.setString(8, sent.recipientNodeId.value.toHex)
        statement.setTimestamp(9, sent.createdAt.toSqlTimestamp)
        statement.setString(10, sent.paymentRequest.map(_.write).orNull)
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
      rs.getStringNullable("payment_request").map(PaymentRequest.read),
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

  override def addIncomingPayment(pr: Bolt11Invoice, preimage: ByteVector32, paymentType: String): Unit = withMetrics("payments/add-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO payments.received (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, pr.paymentHash.toHex)
        statement.setString(2, preimage.toHex)
        statement.setString(3, paymentType)
        statement.setString(4, pr.write)
        statement.setTimestamp(5, pr.timestamp.toSqlTimestamp)
        statement.setTimestamp(6, (pr.timestamp + pr.relativeExpiry.seconds).toSqlTimestamp)
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

  private def parseIncomingPayment(rs: ResultSet): IncomingPayment = {
    val paymentRequest = rs.getString("payment_request")
    IncomingPayment(
      Bolt11Invoice.read(paymentRequest),
      rs.getByteVector32FromHex("payment_preimage"),
      rs.getString("payment_type"),
      TimestampMilli.fromSqlTimestamp(rs.getTimestamp("created_at")),
      buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), Some(paymentRequest), rs.getTimestampNullable("received_at").map(TimestampMilli.fromSqlTimestamp)))
  }

  private def buildIncomingPaymentStatus(amount_opt: Option[MilliSatoshi], serializedPaymentRequest_opt: Option[String], receivedAt_opt: Option[TimestampMilli]): IncomingPaymentStatus = {
    amount_opt match {
      case Some(amount) => IncomingPaymentStatus.Received(amount, receivedAt_opt.getOrElse(0 unixms))
      case None if serializedPaymentRequest_opt.exists(Bolt11Invoice.fastHasExpired) => IncomingPaymentStatus.Expired
      case None => IncomingPaymentStatus.Pending
    }
  }

  private def getIncomingPaymentInternal(pg: Connection, paymentHash: ByteVector32): Option[IncomingPayment] = {
    using(pg.prepareStatement("SELECT * FROM payments.received WHERE payment_hash = ?")) { statement =>
      statement.setString(1, paymentHash.toHex)
      statement.executeQuery().map(parseIncomingPayment).headOption
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

  override def listIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.received WHERE created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().map(parseIncomingPayment).toSeq
      }
    }
  }

  override def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-received", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.received WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().map(parseIncomingPayment).toSeq
      }
    }
  }

  override def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-pending", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.received WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeQuery().map(parseIncomingPayment).toSeq
      }
    }
  }

  override def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment] = withMetrics("payments/list-incoming-expired", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM payments.received WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeQuery().map(parseIncomingPayment).toSeq
      }
    }
  }

  override def listPaymentsOverview(limit: Int): Seq[PlainPayment] = withMetrics("payments/list-overview", DbBackends.Postgres) {
    // This query is an UNION of the ``payments.sent`` and ``payments.received`` table
    // - missing fields set to NULL when needed.
    // - only retrieve incoming payments that did receive funds.
    // - outgoing payments are grouped by parent_id.
    // - order by completion date (or creation date if nothing else).
    withLock { pg =>
      using(pg.prepareStatement(
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
          |  FROM payments.received
          |  WHERE received_msat > 0
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
          |  FROM payments.sent
          |  GROUP BY parent_id,external_id,payment_hash,payment_preimage,payment_type,payment_request,created_at,completed_at
          |) q
          |ORDER BY coalesce(q.completed_at, q.created_at) DESC
          |LIMIT ?
      """.stripMargin
      )) { statement =>
        statement.setInt(1, limit)
        statement.executeQuery()
          .map { rs =>
            val parentId = rs.getUUIDNullable("parent_id")
            val externalId_opt = rs.getStringNullable("external_id")
            val paymentHash = rs.getByteVector32FromHex("payment_hash")
            val paymentType = rs.getString("payment_type")
            val paymentRequest_opt = rs.getStringNullable("payment_request")
            val amount_opt = rs.getMilliSatoshiNullable("final_amount")
            val createdAt = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("created_at"))
            val completedAt_opt = rs.getTimestampNullable("completed_at").map(TimestampMilli.fromSqlTimestamp)
            val expireAt_opt = rs.getTimestampNullable("expire_at").map(TimestampMilli.fromSqlTimestamp)

            if (rs.getString("type") == "received") {
              val status: IncomingPaymentStatus = buildIncomingPaymentStatus(amount_opt, paymentRequest_opt, completedAt_opt)
              PlainIncomingPayment(paymentHash, paymentType, amount_opt, paymentRequest_opt, status, createdAt, completedAt_opt, expireAt_opt)
            } else {
              val preimage_opt = rs.getByteVector32Nullable("payment_preimage")
              // note that the resulting status will not contain any details (routes, failures...)
              val status: OutgoingPaymentStatus = buildOutgoingPaymentStatus(preimage_opt, None, None, completedAt_opt, None)
              PlainOutgoingPayment(parentId, externalId_opt, paymentHash, paymentType, amount_opt, paymentRequest_opt, status, createdAt, completedAt_opt)
            }
          }.toSeq
      }
    }
  }

  override def close(): Unit = ()

}