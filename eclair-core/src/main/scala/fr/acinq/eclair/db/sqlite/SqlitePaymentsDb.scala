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

import java.sql.{Connection, ResultSet, Statement}
import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.wire.CommonCodecs
import grizzled.slf4j.Logging
import scodec.Attempt
import scodec.codecs._

import scala.collection.immutable.Queue
import scala.compat.Platform
import scala.concurrent.duration._

class SqlitePaymentsDb(sqlite: Connection) extends PaymentsDb with Logging {

  import SqliteUtils.ExtendedResultSet._

  val DB_NAME = "payments"
  val CURRENT_VERSION = 3

  private val hopSummaryCodec = (("node_id" | CommonCodecs.publicKey) :: ("next_node_id" | CommonCodecs.publicKey) :: ("short_channel_id" | optional(bool, CommonCodecs.shortchannelid))).as[HopSummary]
  private val paymentRouteCodec = discriminated[List[HopSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, hopSummaryCodec))
  private val failureSummaryCodec = (("type" | enumerated(uint8, FailureType)) :: ("message" | ascii32) :: paymentRouteCodec).as[FailureSummary]
  private val paymentFailuresCodec = discriminated[List[FailureSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, failureSummaryCodec))

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement) = {
      // Version 2 is "backwards compatible" in the sense that it uses separate tables from version 1 (which used a single "payments" table).
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER, received_at INTEGER)")
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, preimage BLOB, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, completed_at INTEGER, status VARCHAR NOT NULL)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON sent_payments(payment_hash)")
    }

    def migration23(statement: Statement) = {
      // We add many more columns to the sent_payments table.
      statement.executeUpdate("DROP index payment_hash_idx")
      statement.executeUpdate("ALTER TABLE sent_payments RENAME TO _sent_payments_old")
      statement.executeUpdate("CREATE TABLE sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, target_node_id BLOB NOT NULL, created_at INTEGER NOT NULL, payment_request TEXT, completed_at INTEGER, payment_preimage BLOB, fees_msat INTEGER, payment_route BLOB, failures BLOB)")
      // Old rows will be missing a target node id, so we use an easy-to-spot default value.
      val defaultTargetNodeId = PrivateKey(ByteVector32.One).publicKey
      statement.executeUpdate(s"INSERT INTO sent_payments (id, parent_id, payment_hash, amount_msat, target_node_id, created_at, completed_at, payment_preimage) SELECT id, id, payment_hash, amount_msat, X'${defaultTargetNodeId.toString}', created_at, completed_at, preimage FROM _sent_payments_old")
      statement.executeUpdate("DROP table _sent_payments_old")

      statement.executeUpdate("ALTER TABLE received_payments RENAME TO _received_payments_old")
      // We make payment request expiration not null in the received_payments table.
      // When it was previously set to NULL the default expiry should apply.
      statement.executeUpdate(s"UPDATE _received_payments_old SET expire_at = created_at + ${PaymentRequest.DEFAULT_EXPIRY_SECONDS} WHERE expire_at IS NULL")
      statement.executeUpdate("CREATE TABLE received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
      statement.executeUpdate("INSERT INTO received_payments (payment_hash, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at) SELECT payment_hash, preimage, payment_request, received_msat, created_at, expire_at, received_at FROM _received_payments_old")
      statement.executeUpdate("DROP table _received_payments_old")

      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_parent_id_idx ON sent_payments(parent_id)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_payment_hash_idx ON sent_payments(payment_hash)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_created_idx ON sent_payments(created_at)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_created_idx ON received_payments(created_at)")
    }

    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 =>
        logger.warn(s"migrating db $DB_NAME, found version=1 current=$CURRENT_VERSION")
        migration12(statement)
        migration23(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case 2 =>
        logger.warn(s"migrating db $DB_NAME, found version=2 current=$CURRENT_VERSION")
        migration23(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case CURRENT_VERSION =>
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, payment_preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER NOT NULL, received_at INTEGER)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, target_node_id BLOB NOT NULL, created_at INTEGER NOT NULL, payment_request TEXT, completed_at INTEGER, payment_preimage BLOB, fees_msat INTEGER, payment_route BLOB, failures BLOB)")

        statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_parent_id_idx ON sent_payments(parent_id)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_payment_hash_idx ON sent_payments(payment_hash)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_created_idx ON sent_payments(created_at)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_created_idx ON received_payments(created_at)")
      case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }

  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    using(sqlite.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, sent.id.toString)
      statement.setString(2, sent.parentId.toString)
      statement.setString(3, sent.externalId.orNull)
      statement.setBytes(4, sent.paymentHash.toArray)
      statement.setLong(5, sent.amount.toLong)
      statement.setBytes(6, sent.targetNodeId.value.toArray)
      statement.setLong(7, sent.createdAt)
      statement.setString(8, sent.paymentRequest.map(PaymentRequest.write).orNull)
      statement.executeUpdate()
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit =
    using(sqlite.prepareStatement("UPDATE sent_payments SET (completed_at, payment_preimage, fees_msat, payment_route) = (?, ?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
      paymentResult.parts.foreach(p => {
        statement.setLong(1, p.timestamp)
        statement.setBytes(2, paymentResult.paymentPreimage.toArray)
        statement.setLong(3, p.feesPaid.toLong)
        statement.setBytes(4, paymentRouteCodec.encode(p.route.getOrElse(Nil).map(h => HopSummary(h)).toList).require.toByteArray)
        statement.setString(5, p.id.toString)
        statement.addBatch()
      })
      if (statement.executeBatch().contains(0)) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as succeeded but already in final status (id=${paymentResult.id})")
    }

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit =
    using(sqlite.prepareStatement("UPDATE sent_payments SET (completed_at, failures) = (?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
      statement.setLong(1, paymentResult.timestamp)
      statement.setBytes(2, paymentFailuresCodec.encode(paymentResult.failures.map(f => FailureSummary(f)).toList).require.toByteArray)
      statement.setString(3, paymentResult.id.toString)
      if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as failed but already in final status (id=${paymentResult.id})")
    }

  private def parseOutgoingPayment(rs: ResultSet): OutgoingPayment = {
    val result = OutgoingPayment(
      UUID.fromString(rs.getString("id")),
      UUID.fromString(rs.getString("parent_id")),
      rs.getStringNullable("external_id"),
      rs.getByteVector32("payment_hash"),
      MilliSatoshi(rs.getLong("amount_msat")),
      PublicKey(rs.getByteVector("target_node_id")),
      rs.getLong("created_at"),
      rs.getStringNullable("payment_request").map(PaymentRequest.read),
      OutgoingPaymentStatus.Pending
    )
    // If we have a pre-image, the payment succeeded.
    rs.getByteVector32Nullable("payment_preimage") match {
      case Some(paymentPreimage) => result.copy(status = OutgoingPaymentStatus.Succeeded(
        paymentPreimage,
        MilliSatoshi(rs.getLong("fees_msat")),
        rs.getBitVectorOpt("payment_route").map(b => paymentRouteCodec.decode(b) match {
          case Attempt.Successful(route) => route.value
          case Attempt.Failure(_) => Nil
        }).getOrElse(Nil),
        rs.getLong("completed_at")
      ))
      case None => getNullableLong(rs, "completed_at") match {
        // Otherwise if the payment was marked completed, it's a failure.
        case Some(completedAt) => result.copy(status = OutgoingPaymentStatus.Failed(
          rs.getBitVectorOpt("failures").map(b => paymentFailuresCodec.decode(b) match {
            case Attempt.Successful(failures) => failures.value
            case Attempt.Failure(_) => Nil
          }).getOrElse(Nil),
          completedAt
        ))
        // Else it's still pending.
        case _ => result
      }
    }
  }

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE id = ?")) { statement =>
      statement.setString(1, id.toString)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(parseOutgoingPayment(rs))
      } else {
        None
      }
    }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE parent_id = ? ORDER BY created_at")) { statement =>
      statement.setString(1, parentId.toString)
      val rs = statement.executeQuery()
      var q: Queue[OutgoingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseOutgoingPayment(rs)
      }
      q
    }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE payment_hash = ? ORDER BY created_at")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      var q: Queue[OutgoingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseOutgoingPayment(rs)
      }
      q
    }

  override def listOutgoingPayments(from: Long, to: Long): Seq[OutgoingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM sent_payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var q: Queue[OutgoingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseOutgoingPayment(rs)
      }
      q
    }

  override def addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32): Unit =
    using(sqlite.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, pr.paymentHash.toArray)
      statement.setBytes(2, preimage.toArray)
      statement.setString(3, PaymentRequest.write(pr))
      statement.setLong(4, pr.timestamp.seconds.toMillis) // BOLT11 timestamp is in seconds
      statement.setLong(5, (pr.timestamp + pr.expiry.getOrElse(PaymentRequest.DEFAULT_EXPIRY_SECONDS.toLong)).seconds.toMillis)
      statement.executeUpdate()
    }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long): Unit =
    using(sqlite.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (?, ?) WHERE payment_hash = ?")) { statement =>
      statement.setLong(1, amount.toLong)
      statement.setLong(2, receivedAt)
      statement.setBytes(3, paymentHash.toArray)
      val res = statement.executeUpdate()
      if (res == 0) throw new IllegalArgumentException("Inserted a received payment without having an invoice")
    }

  private def parseIncomingPayment(rs: ResultSet): IncomingPayment = {
    val paymentRequest = PaymentRequest.read(rs.getString("payment_request"))
    val paymentPreimage = rs.getByteVector32("payment_preimage")
    val createdAt = rs.getLong("created_at")
    val received = getNullableLong(rs, "received_msat").map(MilliSatoshi(_))
    received match {
      case Some(amount) => IncomingPayment(paymentRequest, paymentPreimage, createdAt, IncomingPaymentStatus.Received(amount, rs.getLong("received_at")))
      case None if paymentRequest.isExpired => IncomingPayment(paymentRequest, paymentPreimage, createdAt, IncomingPaymentStatus.Expired)
      case None => IncomingPayment(paymentRequest, paymentPreimage, createdAt, IncomingPaymentStatus.Pending)
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(parseIncomingPayment(rs))
      } else {
        None
      }
    }

  override def listIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var q: Queue[IncomingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseIncomingPayment(rs)
      }
      q
    }

  override def listReceivedIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var q: Queue[IncomingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseIncomingPayment(rs)
      }
      q
    }

  override def listPendingIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      statement.setLong(3, Platform.currentTime)
      val rs = statement.executeQuery()
      var q: Queue[IncomingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseIncomingPayment(rs)
      }
      q
    }

  override def listExpiredIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    using(sqlite.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      statement.setLong(3, Platform.currentTime)
      val rs = statement.executeQuery()
      var q: Queue[IncomingPayment] = Queue()
      while (rs.next()) {
        q = q :+ parseIncomingPayment(rs)
      }
      q
    }

}