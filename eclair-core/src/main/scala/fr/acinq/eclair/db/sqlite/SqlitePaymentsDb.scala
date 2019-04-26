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

import java.sql.Connection
import java.util.UUID
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.db.{IncomingPayment, OutgoingPayment, OutgoingPaymentStatus, PaymentsDb}
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging
import scala.collection.immutable.Queue
import OutgoingPaymentStatus._
import concurrent.duration._
import scala.compat.Platform

class SqlitePaymentsDb(sqlite: Connection) extends PaymentsDb with Logging {

  import SqliteUtils.ExtendedResultSet._

  val DB_NAME = "payments"
  val CURRENT_VERSION = 2

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) <= CURRENT_VERSION, s"incompatible version of $DB_NAME DB found") // version 2 is "backward compatible" in the sense that it uses separate tables from version 1. There is no migration though
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, payment_request TEXT NOT NULL, received_msat INTEGER, created_at INTEGER NOT NULL, expire_at INTEGER, received_at INTEGER)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, preimage BLOB, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, completed_at INTEGER, status VARCHAR NOT NULL)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON sent_payments(payment_hash)")
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = {
    using(sqlite.prepareStatement("INSERT INTO sent_payments (id, payment_hash, amount_msat, created_at, status) VALUES (?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, sent.id.toString)
      statement.setBytes(2, sent.paymentHash.toArray)
      statement.setLong(3, sent.amountMsat)
      statement.setLong(4, sent.createdAt)
      statement.setString(5, sent.status.toString)
      val res = statement.executeUpdate()
      logger.debug(s"inserted $res payment=${sent.paymentHash} into payment DB")
    }
  }

  override def updateOutgoingPayment(id: UUID, newStatus: OutgoingPaymentStatus.Value, preimage: Option[ByteVector32] = None) = {
    require((newStatus == SUCCEEDED && preimage.isDefined) || (newStatus == FAILED && preimage.isEmpty), "Wrong combination of state/preimage")

    using(sqlite.prepareStatement("UPDATE sent_payments SET (completed_at, preimage, status) = (?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
      statement.setLong(1, Platform.currentTime.milliseconds.toSeconds)
      statement.setBytes(2, if (preimage.isEmpty) null else preimage.get.toArray)
      statement.setString(3, newStatus.toString)
      statement.setString(4, id.toString)
      if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to update an outgoing payment (id=$id) already in final status with=$newStatus")
    }
  }

  override def getOutgoingPayment(id: UUID): Option[OutgoingPayment] = {
    using(sqlite.prepareStatement("SELECT id, payment_hash, preimage, amount_msat, created_at, completed_at, status FROM sent_payments WHERE id = ?")) { statement =>
      statement.setString(1, id.toString)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(OutgoingPayment(
          UUID.fromString(rs.getString("id")),
          rs.getByteVector32("payment_hash"),
          rs.getByteVector32Nullable("preimage"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          getNullableLong(rs, "completed_at"),
          OutgoingPaymentStatus.withName(rs.getString("status"))
        ))
      } else {
        None
      }
    }
  }

  override def getOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] = {
    using(sqlite.prepareStatement("SELECT id, payment_hash, preimage, amount_msat, created_at, completed_at, status FROM sent_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      var q: Queue[OutgoingPayment] = Queue()
      while (rs.next()) {
        q = q :+ OutgoingPayment(
          UUID.fromString(rs.getString("id")),
          rs.getByteVector32("payment_hash"),
          rs.getByteVector32Nullable("preimage"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          getNullableLong(rs, "completed_at"),
          OutgoingPaymentStatus.withName(rs.getString("status"))
        )
      }
      q
    }
  }

  override def listOutgoingPayments(): Seq[OutgoingPayment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT id, payment_hash, preimage, amount_msat, created_at, completed_at, status FROM sent_payments")
      var q: Queue[OutgoingPayment] = Queue()
      while (rs.next()) {
        q = q :+ OutgoingPayment(
          UUID.fromString(rs.getString("id")),
          rs.getByteVector32("payment_hash"),
          rs.getByteVector32Nullable("preimage"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          getNullableLong(rs, "completed_at"),
          OutgoingPaymentStatus.withName(rs.getString("status"))
        )
      }
      q
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

  override def getPendingPaymentRequestAndPreimage(paymentHash: ByteVector32): Option[(ByteVector32, PaymentRequest)] = {
    using(sqlite.prepareStatement("SELECT payment_request, preimage FROM received_payments WHERE payment_hash = ? AND received_at IS NULL")) { statement =>
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

  override def listPaymentRequests(from: Long, to: Long): Seq[PaymentRequest] = listPaymentRequests(from, to, pendingOnly = false)

  override def listPendingPaymentRequests(from: Long, to: Long): Seq[PaymentRequest] = listPaymentRequests(from, to, pendingOnly = true)

  def listPaymentRequests(from: Long, to: Long, pendingOnly: Boolean): Seq[PaymentRequest] = {
    val queryStmt = pendingOnly match {
      case true => "SELECT payment_request FROM received_payments WHERE created_at > ? AND created_at < ? AND (expire_at > ? OR expire_at IS NULL) AND received_msat IS NULL ORDER BY created_at DESC"
      case false => "SELECT payment_request FROM received_payments WHERE created_at > ? AND created_at < ? ORDER BY created_at DESC"
    }

    using(sqlite.prepareStatement(queryStmt)) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      if (pendingOnly) statement.setLong(3, Platform.currentTime.milliseconds.toSeconds)

      val rs = statement.executeQuery()
      var q: Queue[PaymentRequest] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRequest.read(rs.getString("payment_request"))
      }
      q
    }
  }

  override def addIncomingPayment(payment: IncomingPayment): Unit = {
    using(sqlite.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (?, ?) WHERE payment_hash = ?")) { statement =>
      statement.setLong(1, payment.amountMsat)
      statement.setLong(2, payment.receivedAt)
      statement.setBytes(3, payment.paymentHash.toArray)
      val res = statement.executeUpdate()
      if (res == 0) throw new IllegalArgumentException("Inserted a received payment without having an invoice")
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = {
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

  override def listIncomingPayments(): Seq[IncomingPayment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT payment_hash, received_msat, received_at FROM received_payments WHERE received_msat > 0")
      var q: Queue[IncomingPayment] = Queue()
      while (rs.next()) {
        q = q :+ IncomingPayment(rs.getByteVector32("payment_hash"), rs.getLong("received_msat"), rs.getLong("received_at"))
      }
      q
    }
  }

}