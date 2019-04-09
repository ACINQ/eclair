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
import fr.acinq.eclair.db.SentPayment.SentPaymentStatus
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.db.{PaymentsDb, ReceivedPayment, SentPayment}
import fr.acinq.eclair.payment.PaymentRequest
import grizzled.slf4j.Logging

import scala.collection.immutable.Queue
import scala.compat.Platform

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

        // create the new table and copy data over it
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, received_msat INTEGER, received_at INTEGER)")
        statement.executeUpdate("INSERT INTO received_payments (payment_hash, received_msat, received_at) SELECT payment_hash, amount_msat as received_msat, timestamp as received_at FROM payments")

        // drop old table
        statement.executeUpdate("DROP TABLE payments")

        // now add columns for invoices
        statement.executeUpdate("ALTER TABLE received_payments ADD COLUMN preimage BLOB")
        statement.executeUpdate("ALTER TABLE received_payments ADD COLUMN expire_at INTEGER")
        statement.executeUpdate("ALTER TABLE received_payments ADD COLUMN payment_request BLOB")

        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id BLOB NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, status VARCHAR NOT NULL)")
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case CURRENT_VERSION =>
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash BLOB NOT NULL PRIMARY KEY, received_msat INTEGER, received_at INTEGER, preimage BLOB, expire_at INTEGER, payment_request BLOB)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id BLOB NOT NULL PRIMARY KEY, payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, status VARCHAR NOT NULL)")
      case unknownVersion =>
        throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
  }

  override def addPaymentRequest(pr: PaymentRequest, preimage: ByteVector32): Unit = {
    val insertStmt = pr.expiry match {
      case Some(_) => "INSERT INTO received_payments (payment_hash, preimage, expire_at, payment_request) VALUES (?, ?, ?, ?)"
      case None => "INSERT INTO received_payments (payment_hash, preimage, payment_request) VALUES (?, ?, ?)"
    }

    using(sqlite.prepareStatement(insertStmt)) { statement =>
      statement.setBytes(1, pr.paymentHash.toArray)
      // 2 received_msat
      // 3 received_at
      statement.setBytes(2, preimage.toArray)
      pr.expiry.foreach { ex => statement.setLong(3, pr.timestamp + ex) } // we store "when" the invoice will expire
      statement.setBytes(if (pr.expiry.isDefined) 4 else 3, PaymentRequest.write(pr).getBytes)
      statement.executeUpdate()
    }
  }

  override def addReceivedPayment(payment: ReceivedPayment): Unit = {
    using(sqlite.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (?, ?) WHERE payment_hash = ?")) { statement =>
      statement.setLong(1, payment.amountMsat)
      statement.setLong(2, payment.timestamp)
      statement.setBytes(3, payment.paymentHash.toArray)
      val res = statement.executeUpdate()
      if (res == 0) throw new IllegalArgumentException("Inserted a received payment without having an invoice")
    }
  }

  override def updateSentStatus(id: UUID, newStatus: SentPaymentStatus.Value) = {
    using(sqlite.prepareStatement(s"UPDATE sent_payments SET (status, updated_at) = (?, ?) WHERE id = ?")) { statement =>
      statement.setString(1, newStatus.toString)
      statement.setLong(2, Platform.currentTime)
      statement.setBytes(3, id.toString.getBytes)
      statement.executeUpdate()
    }
  }

  override def addSentPayment(sent: SentPayment): Unit = {
    using(sqlite.prepareStatement("INSERT INTO sent_payments VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, sent.id.toString.getBytes)
      statement.setBytes(2, sent.paymentHash.toArray)
      statement.setLong(3, sent.amountMsat)
      statement.setLong(4, sent.createdAt)
      statement.setLong(5, sent.updatedAt)
      statement.setString(6, sent.status.toString)
      val res = statement.executeUpdate()
      logger.debug(s"inserted $res payment=${sent.paymentHash} into payment DB")
    }
  }

  override def getReceived(paymentHash: ByteVector32): Option[ReceivedPayment] = {
    using(sqlite.prepareStatement("SELECT payment_hash, received_msat, received_at FROM received_payments WHERE payment_hash = ? AND received_msat > 0")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(ReceivedPayment(rs.getByteVector32("payment_hash"), rs.getLong("received_msat"), rs.getLong("received_at")))
      } else {
        None
      }
    }
  }

  override def getSent(id: UUID): Option[SentPayment] = {
    using(sqlite.prepareStatement("SELECT id, payment_hash, amount_msat, created_at, updated_at, status FROM sent_payments WHERE id = ?")) { statement =>
      statement.setBytes(1, id.toString.getBytes)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(SentPayment(
          UUID.fromString(new String(rs.getBytes("id"))),
          rs.getByteVector32("payment_hash"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          rs.getLong("updated_at"),
          SentPaymentStatus.withName(rs.getString("status"))))
      } else {
        None
      }
    }
  }

  override def getSent(paymentHash: ByteVector32): Option[SentPayment] = {
    using(sqlite.prepareStatement("SELECT id, payment_hash, amount_msat, created_at, updated_at, status FROM sent_payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(SentPayment(
          UUID.fromString(new String(rs.getBytes("id"))),
          rs.getByteVector32("payment_hash"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          rs.getLong("updated_at"),
          SentPaymentStatus.withName(rs.getString("status"))))
      } else {
        None
      }
    }
  }


  override def getPaymentRequest(paymentHash: ByteVector32): Option[PaymentRequest] = {
    using(sqlite.prepareStatement("SELECT payment_request FROM received_payments WHERE payment_hash = ? AND payment_request IS NOT NULL")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        val bytes = rs.getAsciiStream("payment_request").readAllBytes()
        println(s"READ: ${new String(bytes)}")
        Some(PaymentRequest.read(new String(bytes)))
      } else {
        None
      }
    }
  }

  override def getPendingRequestAndPreimage(paymentHash: ByteVector32): Option[(ByteVector32, PaymentRequest)] = {
    using(sqlite.prepareStatement("SELECT payment_request, preimage FROM received_payments WHERE payment_request IS NOT NULL AND (expire_at < ? OR expire_at IS NULL) AND received_msat IS NULL AND payment_hash = ?")) { statement =>
      statement.setLong(1, Platform.currentTime / 1000)
      statement.setBytes(2, paymentHash.toArray)
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

  override def listReceived(): Seq[ReceivedPayment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT payment_hash, received_msat, received_at FROM received_payments WHERE received_msat > 0")
      var q: Queue[ReceivedPayment] = Queue()
      while (rs.next()) {
        q = q :+ ReceivedPayment(rs.getByteVector32("payment_hash"), rs.getLong("received_msat"), rs.getLong("received_at"))
      }
      q
    }
  }

  override def listSent(): Seq[SentPayment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT id, payment_hash, amount_msat, created_at, updated_at, status FROM sent_payments")
      var q: Queue[SentPayment] = Queue()
      while (rs.next()) {
        q = q :+ SentPayment(
          UUID.fromString(new String(rs.getBytes("id"))),
          rs.getByteVector32("payment_hash"),
          rs.getLong("amount_msat"),
          rs.getLong("created_at"),
          rs.getLong("updated_at"),
          SentPaymentStatus.withName(rs.getString("status")))
      }
      q
    }
  }

  override def listPaymentRequests(): Seq[PaymentRequest] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT payment_request FROM received_payments WHERE payment_request IS NOT NULL")
      var q: Queue[PaymentRequest] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRequest.read(rs.getString("payment_request"))
      }
      q
    }
  }

  override def listNonExpiredPaymentRequests(): Seq[PaymentRequest] = {
    using(sqlite.prepareStatement("SELECT payment_request FROM received_payments WHERE payment_request IS NOT NULL AND (expire_at < ? OR expire_at IS NULL)")) { statement =>
      statement.setLong(1, Platform.currentTime / 1000)
      val rs = statement.executeQuery()
      var q: Queue[PaymentRequest] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRequest.read(rs.getString("payment_request"))
      }
      q
    }
  }

  override def listPendingPaymentRequests(): Seq[PaymentRequest] = {
    using(sqlite.prepareStatement("SELECT payment_request FROM received_payments WHERE payment_request IS NOT NULL AND (expire_at < ? OR expire_at IS NULL) AND received_msat IS NULL")) { statement =>
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