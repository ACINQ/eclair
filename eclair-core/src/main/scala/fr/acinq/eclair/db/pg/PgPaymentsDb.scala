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
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.wire.protocol.CommonCodecs
import grizzled.slf4j.Logging
import scodec.Attempt
import scodec.bits.BitVector
import scodec.codecs._

import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import scala.collection.immutable.Queue
import scala.concurrent.duration._

class PgPaymentsDb(implicit ds: DataSource, lock: PgLock) extends PaymentsDb with Logging {

  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  val DB_NAME = "payments"
  val CURRENT_VERSION = 4

  private val hopSummaryCodec = (("node_id" | CommonCodecs.publicKey) :: ("next_node_id" | CommonCodecs.publicKey) :: ("short_channel_id" | optional(bool, CommonCodecs.shortchannelid))).as[HopSummary]
  private val paymentRouteCodec = discriminated[List[HopSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, hopSummaryCodec))
  private val failureSummaryCodec = (("type" | enumerated(uint8, FailureType)) :: ("message" | ascii32) :: paymentRouteCodec).as[FailureSummary]
  private val paymentFailuresCodec = discriminated[List[FailureSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, failureSummaryCodec))

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>

      getVersion(statement, DB_NAME, CURRENT_VERSION) match {
        case CURRENT_VERSION =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash TEXT NOT NULL PRIMARY KEY, payment_type TEXT NOT NULL, payment_preimage TEXT NOT NULL, payment_request TEXT NOT NULL, received_msat BIGINT, created_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, received_at BIGINT)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, payment_preimage TEXT, payment_type TEXT NOT NULL, amount_msat BIGINT NOT NULL, fees_msat BIGINT, recipient_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, payment_request TEXT, payment_route BYTEA, failures BYTEA, created_at BIGINT NOT NULL, completed_at BIGINT)")

          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_parent_id_idx ON sent_payments(parent_id)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_payment_hash_idx ON sent_payments(payment_hash)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_created_idx ON sent_payments(created_at)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_created_idx ON received_payments(created_at)")
        case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
    }
  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = withMetrics("payments/add-outgoing", DbBackends.Postgres) {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, payment_type, amount_msat, recipient_amount_msat, recipient_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, sent.id.toString)
        statement.setString(2, sent.parentId.toString)
        statement.setString(3, sent.externalId.orNull)
        statement.setString(4, sent.paymentHash.toHex)
        statement.setString(5, sent.paymentType)
        statement.setLong(6, sent.amount.toLong)
        statement.setLong(7, sent.recipientAmount.toLong)
        statement.setString(8, sent.recipientNodeId.value.toHex)
        statement.setLong(9, sent.createdAt)
        statement.setString(10, sent.paymentRequest.map(PaymentRequest.write).orNull)
        statement.executeUpdate()
      }
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit = withMetrics("payments/update-outgoing-sent", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE sent_payments SET (completed_at, payment_preimage, fees_msat, payment_route) = (?, ?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
        paymentResult.parts.foreach(p => {
          statement.setLong(1, p.timestamp)
          statement.setString(2, paymentResult.paymentPreimage.toHex)
          statement.setLong(3, p.feesPaid.toLong)
          statement.setBytes(4, paymentRouteCodec.encode(p.route.getOrElse(Nil).map(h => HopSummary(h)).toList).require.toByteArray)
          statement.setString(5, p.id.toString)
          statement.addBatch()
        })
        if (statement.executeBatch().contains(0)) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as succeeded but already in final status (id=${paymentResult.id})")
      }
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit = withMetrics("payments/update-outgoing-failed", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE sent_payments SET (completed_at, failures) = (?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
        statement.setLong(1, paymentResult.timestamp)
        statement.setBytes(2, paymentFailuresCodec.encode(paymentResult.failures.map(f => FailureSummary(f)).toList).require.toByteArray)
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
      rs.getLongNullable("completed_at"),
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
      rs.getLong("created_at"),
      rs.getStringNullable("payment_request").map(PaymentRequest.read),
      status
    )
  }

  private def buildOutgoingPaymentStatus(preimage_opt: Option[ByteVector32], fees_opt: Option[MilliSatoshi], paymentRoute_opt: Option[BitVector], completedAt_opt: Option[Long], failures: Option[BitVector]): OutgoingPaymentStatus = {
    preimage_opt match {
      // If we have a pre-image, the payment succeeded.
      case Some(preimage) => OutgoingPaymentStatus.Succeeded(
        preimage, fees_opt.getOrElse(MilliSatoshi(0)), paymentRoute_opt.map(b => paymentRouteCodec.decode(b) match {
          case Attempt.Successful(route) => route.value
          case Attempt.Failure(_) => Nil
        }).getOrElse(Nil),
        completedAt_opt.getOrElse(0)
      )
      case None => completedAt_opt match {
        // Otherwise if the payment was marked completed, it's a failure.
        case Some(completedAt) => OutgoingPaymentStatus.Failed(
          failures.map(b => paymentFailuresCodec.decode(b) match {
            case Attempt.Successful(f) => f.value
            case Attempt.Failure(_) => Nil
          }).getOrElse(Nil),
          completedAt
        )
        // Else it's still pending.
        case _ => OutgoingPaymentStatus.Pending
      }
    }
  }

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] = withMetrics("payments/get-outgoing", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM sent_payments WHERE id = ?")) { statement =>
        statement.setString(1, id.toString)
        val rs = statement.executeQuery()
        if (rs.next()) {
          Some(parseOutgoingPayment(rs))
        } else {
          None
        }
      }
    }
  }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-parent-id", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM sent_payments WHERE parent_id = ? ORDER BY created_at")) { statement =>
        statement.setString(1, parentId.toString)
        val rs = statement.executeQuery()
        var q: Queue[OutgoingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseOutgoingPayment(rs)
        }
        q
      }
    }
  }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-payment-hash", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM sent_payments WHERE payment_hash = ? ORDER BY created_at")) { statement =>
        statement.setString(1, paymentHash.toHex)
        val rs = statement.executeQuery()
        var q: Queue[OutgoingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseOutgoingPayment(rs)
        }
        q
      }
    }
  }

  override def listOutgoingPayments(from: Long, to: Long): Seq[OutgoingPayment] = withMetrics("payments/list-outgoing-by-timestamp", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM sent_payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setLong(1, from)
        statement.setLong(2, to)
        val rs = statement.executeQuery()
        var q: Queue[OutgoingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseOutgoingPayment(rs)
        }
        q
      }
    }
  }

  override def addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32, paymentType: String): Unit = withMetrics("payments/add-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_type, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, pr.paymentHash.toHex)
        statement.setString(2, preimage.toHex)
        statement.setString(3, paymentType)
        statement.setString(4, PaymentRequest.write(pr))
        statement.setLong(5, pr.timestamp.seconds.toMillis) // BOLT11 timestamp is in seconds
        statement.setLong(6, (pr.timestamp + pr.expiry.getOrElse(PaymentRequest.DEFAULT_EXPIRY_SECONDS.toLong)).seconds.toMillis)
        statement.executeUpdate()
      }
    }
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long): Unit = withMetrics("payments/receive-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (? + COALESCE(received_msat, 0), ?) WHERE payment_hash = ?")) { update =>
        update.setLong(1, amount.toLong)
        update.setLong(2, receivedAt)
        update.setString(3, paymentHash.toHex)
        val updated = update.executeUpdate()
        if (updated == 0) {
          throw new IllegalArgumentException("Inserted a received payment without having an invoice")
        }
      }
    }
  }

  private def parseIncomingPayment(rs: ResultSet): IncomingPayment = {
    val paymentRequest = rs.getString("payment_request")
    IncomingPayment(
      PaymentRequest.read(paymentRequest),
      rs.getByteVector32FromHex("payment_preimage"),
      rs.getString("payment_type"),
      rs.getLong("created_at"),
      buildIncomingPaymentStatus(rs.getMilliSatoshiNullable("received_msat"), Some(paymentRequest), rs.getLongNullable("received_at")))
  }

  private def buildIncomingPaymentStatus(amount_opt: Option[MilliSatoshi], serializedPaymentRequest_opt: Option[String], receivedAt_opt: Option[Long]): IncomingPaymentStatus = {
    amount_opt match {
      case Some(amount) => IncomingPaymentStatus.Received(amount, receivedAt_opt.getOrElse(0))
      case None if serializedPaymentRequest_opt.exists(PaymentRequest.fastHasExpired) => IncomingPaymentStatus.Expired
      case None => IncomingPaymentStatus.Pending
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = withMetrics("payments/get-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM received_payments WHERE payment_hash = ?")) { statement =>
        statement.setString(1, paymentHash.toHex)
        val rs = statement.executeQuery()
        if (rs.next()) {
          Some(parseIncomingPayment(rs))
        } else {
          None
        }
      }
    }
  }

  override def listIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] = withMetrics("payments/list-incoming", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM received_payments WHERE created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setLong(1, from)
        statement.setLong(2, to)
        val rs = statement.executeQuery()
        var q: Queue[IncomingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseIncomingPayment(rs)
        }
        q
      }
    }
  }

  override def listReceivedIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] = withMetrics("payments/list-incoming-received", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM received_payments WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
        statement.setLong(1, from)
        statement.setLong(2, to)
        val rs = statement.executeQuery()
        var q: Queue[IncomingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseIncomingPayment(rs)
        }
        q
      }
    }
  }

  override def listPendingIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] = withMetrics("payments/list-incoming-pending", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at")) { statement =>
        statement.setLong(1, from)
        statement.setLong(2, to)
        statement.setLong(3, System.currentTimeMillis)
        val rs = statement.executeQuery()
        var q: Queue[IncomingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseIncomingPayment(rs)
        }
        q
      }
    }
  }

  override def listExpiredIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] = withMetrics("payments/list-incoming-expired", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
        statement.setLong(1, from)
        statement.setLong(2, to)
        statement.setLong(3, System.currentTimeMillis)
        val rs = statement.executeQuery()
        var q: Queue[IncomingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseIncomingPayment(rs)
        }
        q
      }
    }
  }

  override def listPaymentsOverview(limit: Int): Seq[PlainPayment] = withMetrics("payments/list-overview", DbBackends.Postgres) {
    // This query is an UNION of the ``sent_payments`` and ``received_payments`` table
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
          |  FROM received_payments
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
          |  FROM sent_payments
          |  GROUP BY parent_id,external_id,payment_hash,payment_preimage,payment_type,payment_request,created_at,completed_at
          |) q
          |ORDER BY coalesce(q.completed_at, q.created_at) DESC
          |LIMIT ?
      """.stripMargin
      )) { statement =>
        statement.setInt(1, limit)
        val rs = statement.executeQuery()
        var q: Queue[PlainPayment] = Queue()
        while (rs.next()) {
          val parentId = rs.getUUIDNullable("parent_id")
          val externalId_opt = rs.getStringNullable("external_id")
          val paymentHash = rs.getByteVector32FromHex("payment_hash")
          val paymentType = rs.getString("payment_type")
          val paymentRequest_opt = rs.getStringNullable("payment_request")
          val amount_opt = rs.getMilliSatoshiNullable("final_amount")
          val createdAt = rs.getLong("created_at")
          val completedAt_opt = rs.getLongNullable("completed_at")
          val expireAt_opt = rs.getLongNullable("expire_at")

          val p = if (rs.getString("type") == "received") {
            val status: IncomingPaymentStatus = buildIncomingPaymentStatus(amount_opt, paymentRequest_opt, completedAt_opt)
            PlainIncomingPayment(paymentHash, paymentType, amount_opt, paymentRequest_opt, status, createdAt, completedAt_opt, expireAt_opt)
          } else {
            val preimage_opt = rs.getByteVector32Nullable("payment_preimage")
            // note that the resulting status will not contain any details (routes, failures...)
            val status: OutgoingPaymentStatus = buildOutgoingPaymentStatus(preimage_opt, None, None, completedAt_opt, None)
            PlainOutgoingPayment(parentId, externalId_opt, paymentHash, paymentType, amount_opt, paymentRequest_opt, status, createdAt, completedAt_opt)
          }
          q = q :+ p
        }
        q
      }
    }
  }

  override def close(): Unit = ()
}