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

package fr.acinq.eclair.db.psql

import java.sql.ResultSet
import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.{HopSummary, _}
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.wire.CommonCodecs
import grizzled.slf4j.Logging
import javax.sql.DataSource
import scodec.Attempt
import scodec.bits.BitVector
import scodec.codecs._

import scala.collection.immutable.Queue
import scala.compat.Platform
import scala.concurrent.duration._

class PsqlPaymentsDb(implicit ds: DataSource) extends PaymentsDb with Logging {

  import PsqlUtils.ExtendedResultSet._
  import PsqlUtils._

  val DB_NAME = "payments"
  val CURRENT_VERSION = 3

  private val hopSummaryCodec = (("node_id" | CommonCodecs.publicKey) :: ("next_node_id" | CommonCodecs.publicKey) :: ("short_channel_id" | optional(bool, CommonCodecs.shortchannelid))).as[HopSummary]
  private val paymentRouteCodec = discriminated[List[HopSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, hopSummaryCodec))
  private val failureSummaryCodec = (("type" | enumerated(uint8, FailureType)) :: ("message" | ascii32) :: paymentRouteCodec).as[FailureSummary]
  private val paymentFailuresCodec = discriminated[List[FailureSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, failureSummaryCodec))

  withConnection { psql =>
    using(psql.createStatement(), inTransaction = true) { statement =>
      getVersion(statement, DB_NAME, CURRENT_VERSION) match {
        case CURRENT_VERSION =>
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS received_payments (payment_hash TEXT NOT NULL PRIMARY KEY, payment_preimage TEXT NOT NULL, payment_request TEXT NOT NULL, received_msat BIGINT, created_at BIGINT NOT NULL, expire_at BIGINT NOT NULL, received_at BIGINT)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent_payments (id TEXT NOT NULL PRIMARY KEY, parent_id TEXT NOT NULL, external_id TEXT, payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, target_node_id TEXT NOT NULL, created_at BIGINT NOT NULL, payment_request TEXT, completed_at BIGINT, payment_preimage TEXT, fees_msat BIGINT, payment_route BYTEA, failures BYTEA)")

          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_parent_id_idx ON sent_payments(parent_id)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_payment_hash_idx ON sent_payments(payment_hash)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_created_idx ON sent_payments(created_at)")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_created_idx ON received_payments(created_at)")
        case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
    }
  }

  override def addOutgoingPayment(sent: OutgoingPayment): Unit = {
    require(sent.status == OutgoingPaymentStatus.Pending, s"outgoing payment isn't pending (${sent.status.getClass.getSimpleName})")
    withConnection { psql =>
      using(psql.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, payment_request) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, sent.id.toString)
        statement.setString(2, sent.parentId.toString)
        statement.setString(3, sent.externalId.orNull)
        statement.setString(4, sent.paymentHash.toHex)
        statement.setLong(5, sent.amount.toLong)
        statement.setString(6, sent.targetNodeId.value.toHex)
        statement.setLong(7, sent.createdAt)
        statement.setString(8, sent.paymentRequest.map(PaymentRequest.write).orNull)
        statement.executeUpdate()
      }
    }
  }

  def addOutgoingPayment(sent: (UUID, UUID,Option[String],ByteVector32,MilliSatoshi,PublicKey,Long,Option[PaymentRequest],Option[Long],Option[ByteVector32],Option[MilliSatoshi],Option[BitVector],Option[BitVector])): Unit = {
    val (id, parentId,externalId,paymentHash,amount,targetNodeId,createdAt,paymentRequest,completedAt,paymentPreimage, fees, route, failures) = sent
    withConnection { psql =>
      using(psql.prepareStatement("INSERT INTO sent_payments (id, parent_id, external_id, payment_hash, amount_msat, target_node_id, created_at, payment_request,completed_at, payment_preimage,fees_msat,payment_route,failures) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, id.toString)
        statement.setString(2, parentId.toString)
        statement.setString(3, externalId.orNull)
        statement.setString(4, paymentHash.toHex)
        statement.setLong(5, amount.toLong)
        statement.setString(6, targetNodeId.value.toHex)
        statement.setLong(7, createdAt)
        statement.setString(8, paymentRequest.map(PaymentRequest.write).orNull)
        completedAt.fold(statement.setNull(9, java.sql.Types.BIGINT))(statement.setLong(9, _))
        statement.setString(10, paymentPreimage.map(_.toHex).orNull)
        fees.fold(statement.setNull(11, java.sql.Types.BIGINT))(x => statement.setLong(11, x.toLong))
        statement.setBytes(12, route.map(_.toByteArray).orNull)
        statement.setBytes(13, failures.map(_.toByteArray).orNull)
        statement.executeUpdate()
      }
    }
  }

  override def updateOutgoingPayment(paymentResult: PaymentSent): Unit =
    withConnection { psql =>
      using(psql.prepareStatement("UPDATE sent_payments SET (completed_at, payment_preimage, fees_msat, payment_route) = (?, ?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
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

  override def updateOutgoingPayment(paymentResult: PaymentFailed): Unit =
    withConnection { psql =>
      using(psql.prepareStatement("UPDATE sent_payments SET (completed_at, failures) = (?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
        statement.setLong(1, paymentResult.timestamp)
        statement.setBytes(2, paymentFailuresCodec.encode(paymentResult.failures.map(f => FailureSummary(f)).toList).require.toByteArray)
        statement.setString(3, paymentResult.id.toString)
        if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to mark an outgoing payment as failed but already in final status (id=${paymentResult.id})")
      }
    }

  private def parseOutgoingPayment(rs: ResultSet): OutgoingPayment = {
    val result = OutgoingPayment(
      UUID.fromString(rs.getString("id")),
      UUID.fromString(rs.getString("parent_id")),
      rs.getStringNullable("external_id"),
      rs.getByteVector32FromHex("payment_hash"),
      MilliSatoshi(rs.getLong("amount_msat")),
      PublicKey(rs.getByteVectorFromHex("target_node_id")),
      rs.getLong("created_at"),
      rs.getStringNullable("payment_request").map(PaymentRequest.read),
      OutgoingPaymentStatus.Pending
    )
    // If we have a pre-image, the payment succeeded.
    rs.getByteVector32FromHexNullable("payment_preimage") match {
      case Some(paymentPreimage) => result.copy(status = OutgoingPaymentStatus.Succeeded(
        paymentPreimage,
        MilliSatoshi(rs.getLong("fees_msat")),
        rs.getBitVectorOpt("payment_route").map(b => paymentRouteCodec.decode(b) match {
          case Attempt.Successful(route) => route.value
          case Attempt.Failure(_) => Nil
        }).getOrElse(Nil),
        rs.getLong("completed_at")
      ))
      case None => rs.getLongNullable("completed_at") match {
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
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM sent_payments WHERE id = ?")) { statement =>
        statement.setString(1, id.toString)
        val rs = statement.executeQuery()
        if (rs.next()) {
          Some(parseOutgoingPayment(rs))
        } else {
          None
        }
      }
    }

  override def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM sent_payments WHERE parent_id = ? ORDER BY created_at")) { statement =>
        statement.setString(1, parentId.toString)
        val rs = statement.executeQuery()
        var q: Queue[OutgoingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseOutgoingPayment(rs)
        }
        q
      }
    }

  override def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM sent_payments WHERE payment_hash = ? ORDER BY created_at")) { statement =>
        statement.setString(1, paymentHash.toHex)
        val rs = statement.executeQuery()
        var q: Queue[OutgoingPayment] = Queue()
        while (rs.next()) {
          q = q :+ parseOutgoingPayment(rs)
        }
        q
      }
    }

  override def listOutgoingPayments(from: Long, to: Long): Seq[OutgoingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM sent_payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at")) { statement =>
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

  override def addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32): Unit =
    withConnection { psql =>
      using(psql.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_request, created_at, expire_at) VALUES (?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, pr.paymentHash.toHex)
        statement.setString(2, preimage.toHex)
        statement.setString(3, PaymentRequest.write(pr))
        statement.setLong(4, pr.timestamp.seconds.toMillis) // BOLT11 timestamp is in seconds
        statement.setLong(5, (pr.timestamp + pr.expiry.getOrElse(PaymentRequest.DEFAULT_EXPIRY_SECONDS.toLong)).seconds.toMillis)
        statement.executeUpdate()
      }
    }

  def addIncomingPayment(payment: (ByteVector32,ByteVector32,PaymentRequest,Option[MilliSatoshi],Long,Long,Option[Long])): Unit = {
    val (paymentHash,paymentPreimage,paymentRequest,received,createdAt,expireAt,receivedAt) = payment
    withConnection { psql =>
      using(psql.prepareStatement("INSERT INTO received_payments (payment_hash, payment_preimage, payment_request, received_msat, created_at, expire_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, paymentHash.toHex)
        statement.setString(2, paymentPreimage.toHex)
        statement.setString(3, PaymentRequest.write(paymentRequest))
        received.fold(statement.setNull(4, java.sql.Types.BIGINT))(x => statement.setLong(4, x.toLong))
        statement.setLong(5, createdAt)
        statement.setLong(6, expireAt)
        receivedAt.fold(statement.setNull(7, java.sql.Types.BIGINT))(statement.setLong(7, _))
        statement.executeUpdate()
      }
    }
  }

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long): Unit =
    withConnection { psql =>
      using(psql.prepareStatement("UPDATE received_payments SET (received_msat, received_at) = (?, ?) WHERE payment_hash = ?")) { statement =>
        statement.setLong(1, amount.toLong)
        statement.setLong(2, receivedAt)
        statement.setString(3, paymentHash.toHex)
        val res = statement.executeUpdate()
        if (res == 0) throw new IllegalArgumentException("Inserted a received payment without having an invoice")
      }
    }

  private def parseIncomingPayment(rs: ResultSet): IncomingPayment = {
    val paymentRequest = PaymentRequest.read(rs.getString("payment_request"))
    val paymentPreimage = rs.getByteVector32FromHex("payment_preimage")
    val createdAt = rs.getLong("created_at")
    val received = rs.getLongNullable("received_msat").map(MilliSatoshi(_))
    received match {
      case Some(amount) => IncomingPayment(paymentRequest, paymentPreimage, createdAt, IncomingPaymentStatus.Received(amount, rs.getLong("received_at")))
      case None if paymentRequest.isExpired => IncomingPayment(paymentRequest, paymentPreimage, createdAt, IncomingPaymentStatus.Expired)
      case None => IncomingPayment(paymentRequest, paymentPreimage, createdAt, IncomingPaymentStatus.Pending)
    }
  }

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM received_payments WHERE payment_hash = ?")) { statement =>
        statement.setString(1, paymentHash.toHex)
        val rs = statement.executeQuery()
        if (rs.next()) {
          Some(parseIncomingPayment(rs))
        } else {
          None
        }
      }
    }

  override def listIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM received_payments WHERE created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
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

  override def listReceivedIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM received_payments WHERE received_msat > 0 AND created_at > ? AND created_at < ? ORDER BY created_at")) { statement =>
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

  override def listPendingIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at > ? ORDER BY created_at")) { statement =>
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

  override def listExpiredIncomingPayments(from: Long, to: Long): Seq[IncomingPayment] =
    withConnection { psql =>
      using(psql.prepareStatement("SELECT * FROM received_payments WHERE received_msat IS NULL AND created_at > ? AND created_at < ? AND expire_at < ? ORDER BY created_at")) { statement =>
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

}