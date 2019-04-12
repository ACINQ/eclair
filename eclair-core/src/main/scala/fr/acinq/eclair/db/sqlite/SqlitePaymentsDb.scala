/*
 * Copyright 2018 ACINQ SAS
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

import java.sql.{Connection, ResultSet}
import java.time.Instant
import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.db.{IncomingPayment, OutgoingPayment, OutgoingPaymentStatus, PaymentsDb}
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.collection.immutable.Queue
import scala.compat.Platform
import scala.util.{Failure, Success, Try}

class SqlitePaymentsDb(sqlite: Connection) extends PaymentsDb with Logging {

  import SqliteUtils.ExtendedResultSet._

  val DB_NAME = "payments"
  val PREVIOUS_VERSION = 1
  val CURRENT_VERSION = 2

  using(sqlite.createStatement()) { statement =>
    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case PREVIOUS_VERSION =>
        logger.warn(s"Performing db migration for DB $DB_NAME, found version=$PREVIOUS_VERSION current=$CURRENT_VERSION")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS payments (payment_hash BLOB NOT NULL PRIMARY KEY, amount_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request VARCHAR NOT NULL, received_msat INTEGER, received_at INTEGER, expire_at INTEGER, created_at INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id BLOB NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, succeeded_at INTEGER, failed_at INTEGER)")
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case CURRENT_VERSION =>
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request VARCHAR NOT NULL, received_msat INTEGER, received_at INTEGER, expire_at INTEGER, created_at INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id BLOB NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, succeeded_at INTEGER, failed_at INTEGER)")
      case unknownVersion =>
        throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
  }

  override def addPaymentRequest(pr: PaymentRequest, preimage: ByteVector32): Unit = {
    val insertStmt = pr.expiry match {
      case Some(_) => "INSERT INTO received_payments (payment_hash, preimage, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?)"
      case None => "INSERT INTO received_payments (payment_hash, preimage, payment_request, created_at) VALUES (?, ?, ?, ?)"
    }

    using(sqlite.prepareStatement(insertStmt)) { statement =>
      statement.setBytes(1, pr.paymentHash.toArray)
      statement.setBytes(2, preimage.toArray)
      statement.setString(3, PaymentRequest.write(pr))
      statement.setLong(4, pr.timestamp)
      pr.expiry.foreach { ex => statement.setLong(5, pr.timestamp + ex) } // we store "when" the invoice will expire
      statement.executeUpdate()
    }
  }

  override def addIncomingPayment(payment: IncomingPayment): Unit = {
    using(sqlite.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (?, ?) WHERE payment_hash = ?")) { statement =>
      statement.setLong(1, payment.amountMsat)
      statement.setLong(2, payment.timestamp)
      statement.setBytes(3, payment.paymentHash.toArray)
      val res = statement.executeUpdate()
      if (res == 0) throw new IllegalArgumentException("Inserted a received payment without having an invoice")
    }
  }

  override def updateOutgoingStatus(id: UUID, newStatus: OutgoingPaymentStatus.Value) = {
    val updateStmt = newStatus match {
      case OutgoingPaymentStatus.SUCCEEDED => "UPDATE sent_payments SET succeeded_at = ? WHERE id = ? AND failed_at IS NULL"
      case OutgoingPaymentStatus.FAILED => "UPDATE sent_payments SET failed_at = ? WHERE id = ? AND succeeded_at IS NULL"
    }

    using(sqlite.prepareStatement(updateStmt)) { statement =>
      statement.setLong(1, Instant.now().getEpochSecond)
      statement.setBytes(2, id.toString.getBytes)
      if(statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to update an outgoing payment (id=$id) already in final status with=$newStatus")
    }
  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = {
    using(sqlite.prepareStatement("INSERT INTO sent_payments (id, payment_hash, amount_msat, created_at) VALUES (?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, sent.id.toString.getBytes)
      statement.setBytes(2, sent.paymentHash.toArray)
      statement.setLong(3, sent.amountMsat)
      statement.setLong(4, sent.createdAt)
      val res = statement.executeUpdate()
      logger.debug(s"inserted $res payment=${sent.paymentHash} into payment DB")
    }
  }

  override def getIncoming(paymentHash: ByteVector32): Option[IncomingPayment] = {
    using(sqlite.prepareStatement("SELECT payment_hash, received_msat, received_at FROM received_payments WHERE payment_hash = ? AND received_msat > 0")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(IncomingPayment(rs.getByteVector32("payment_hash"), rs.getLong("received_msat"), rs.getLong("received_at")))
      } else {
        None
      }
    }
  }

  override def getOutgoing(id: UUID): Option[OutgoingPayment] = {
    using(sqlite.prepareStatement("SELECT id, payment_hash, amount_msat, created_at, succeeded_at, failed_at FROM sent_payments WHERE id = ?")) { statement =>
      statement.setBytes(1, id.toString.getBytes)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(OutgoingPayment(
          UUID.fromString(new String(rs.getBytes("id"))),
          rs.getByteVector32("payment_hash"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          getNullableTimestamp(rs, "succeeded_at"),
          getNullableTimestamp(rs, "failed_at")))
      } else {
        None
      }
    }
  }

  override def getOutgoing(paymentHash: ByteVector32): Option[OutgoingPayment] = {
    using(sqlite.prepareStatement("SELECT id, payment_hash, amount_msat, created_at, succeeded_at, failed_at FROM sent_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(OutgoingPayment(
          UUID.fromString(new String(rs.getBytes("id"))),
          rs.getByteVector32("payment_hash"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          getNullableTimestamp(rs, "succeeded_at"),
          getNullableTimestamp(rs, "failed_at")))
      } else {
        None
      }
    }
  }


  override def getPaymentRequest(paymentHash: ByteVector32): Option[PaymentRequest] = {
    using(sqlite.prepareStatement("SELECT payment_request FROM received_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(PaymentRequest.read(rs.getString("payment_request")))
      } else {
        None
      }
    }
  }

  override def getRequestAndPreimage(paymentHash: ByteVector32): Option[(ByteVector32, PaymentRequest)] = {
    using(sqlite.prepareStatement("SELECT payment_request, preimage FROM received_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        val preimage = rs.getByteVector32("preimage")
        val pr = PaymentRequest.read(rs.getString("payment_request"))
        Some(preimage, pr)
      } else {
        None
      }
    }
  }

  override def listIncoming(): Seq[IncomingPayment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT payment_hash, received_msat, received_at FROM received_payments WHERE received_msat > 0")
      var q: Queue[IncomingPayment] = Queue()
      while (rs.next()) {
        q = q :+ IncomingPayment(rs.getByteVector32("payment_hash"), rs.getLong("received_msat"), rs.getLong("received_at"))
      }
      q
    }
  }

  override def listOutgoing(): Seq[OutgoingPayment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT id, payment_hash, amount_msat, created_at, succeeded_at, failed_at FROM sent_payments")
      var q: Queue[OutgoingPayment] = Queue()
      while (rs.next()) {
        q = q :+ OutgoingPayment(
          UUID.fromString(new String(rs.getBytes("id"))),
          rs.getByteVector32("payment_hash"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          getNullableTimestamp(rs, "succeeded_at"),
          getNullableTimestamp(rs, "failed_at"))
      }
      q
    }
  }

  def getNullableTimestamp(rs: ResultSet, column: String): Option[Long] = Try(rs.getLong(column)) match {
    case Success(0) => None
    case Success(timestamp) => Some(timestamp)
    case Failure(exception) => None
  }


  override def listPaymentRequests(from: Long, to: Long): Seq[PaymentRequest] = {
    using(sqlite.prepareStatement("SELECT payment_request FROM received_payments WHERE created_at > ? AND created_at < ?")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var q: Queue[PaymentRequest] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRequest.read(rs.getString("payment_request"))
      }
      q
    }
  }

  override def listPendingPaymentRequests(): Seq[PaymentRequest] = {
    using(sqlite.prepareStatement("SELECT payment_request FROM received_payments WHERE (expire_at > ? OR expire_at IS NULL) AND received_msat IS NULL")) { statement =>
      statement.setLong(1, Platform.currentTime / 1000)
      val rs = statement.executeQuery()
      var q: Queue[PaymentRequest] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRequest.read(rs.getString("payment_request"))
      }
      q
    }
  }

}