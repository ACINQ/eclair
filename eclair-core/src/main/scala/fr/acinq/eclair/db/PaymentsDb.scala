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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.{BlindedHop, ChannelHop, Hop, NodeHop}
import fr.acinq.eclair.{MilliSatoshi, Paginated, ShortChannelId, TimestampMilli}
import scodec.bits.ByteVector

import java.util.UUID
import scala.util.Try

trait PaymentsDb extends IncomingPaymentsDb with OutgoingPaymentsDb

trait IncomingPaymentsDb {

  /** Add a new expected standard incoming payment (not yet received). */
  def addIncomingPayment(pr: Bolt11Invoice, preimage: ByteVector32, paymentType: String = PaymentType.Standard): Unit

  /** Add a new expected blinded incoming payment (not yet received). */
  def addIncomingBlindedPayment(pr: Bolt12Invoice, preimage: ByteVector32, pathIds: Map[PublicKey, ByteVector], paymentType: String = PaymentType.Blinded): Unit

  /**
   * Mark an incoming payment as received (paid). The received amount may exceed the invoice amount.
   * If there was no matching invoice in the DB, this will return false.
   */
  def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: TimestampMilli = TimestampMilli.now()): Boolean

  /** Get information about the incoming payment (paid or not) for the given payment hash, if any. */
  def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment]

  /**
   * Remove an unpaid incoming payment from the DB.
   * Returns a failure if the payment has already been paid.
   */
  def removeIncomingPayment(paymentHash: ByteVector32): Try[Unit]

  /** List all incoming payments (pending, expired and succeeded) in the given time range (milli-seconds). */
  def listIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment]

  /** List all pending (not paid, not expired) incoming payments in the given time range (milli-seconds). */
  def listPendingIncomingPayments(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[IncomingPayment]

  /** List all expired (not paid) incoming payments in the given time range (milli-seconds). */
  def listExpiredIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment]

  /** List all received (paid) incoming payments in the given time range (milli-seconds). */
  def listReceivedIncomingPayments(from: TimestampMilli, to: TimestampMilli): Seq[IncomingPayment]

}

trait OutgoingPaymentsDb {

  /** Create a record for a non yet finalized outgoing payment. */
  def addOutgoingPayment(outgoingPayment: OutgoingPayment): Unit

  /** Update the status of the payment in case of success. */
  def updateOutgoingPayment(paymentResult: PaymentSent): Unit

  /** Update the status of the payment in case of failure. */
  def updateOutgoingPayment(paymentResult: PaymentFailed): Unit

  /** Get an outgoing payment attempt. */
  def getOutgoingPayment(id: UUID): Option[OutgoingPayment]

  /** List all the outgoing payment attempts that are children of the given id. */
  def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment]

  /** List all the outgoing payment attempts that tried to pay the given payment hash. */
  def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment]

  /** List all the outgoing payment attempts in the given time range (milli-seconds). */
  def listOutgoingPayments(from: TimestampMilli, to: TimestampMilli): Seq[OutgoingPayment]

  /** List all the outgoing payment attempts that tried to pay the given offer. */
  def listOutgoingPaymentsToOffer(offerId: ByteVector32): Seq[OutgoingPayment]

}

case object PaymentType {
  val Standard = "Standard"
  val Blinded = "Blinded"
  val SwapIn = "SwapIn"
  val SwapOut = "SwapOut"
  val KeySend = "KeySend"
}

/**
 * An incoming payment received by this node.
 * At first it is in a pending state once the invoice has been generated, then will become either a success (if we
 * receive a valid HTLC) or a failure (if the invoice expires).
 */
sealed trait IncomingPayment {
  // @formatter:off
  /** Bolt invoice. */
  def invoice: Invoice
  /** Pre-image associated with the invoice's payment_hash. */
  def paymentPreimage: ByteVector32
  /** Distinguish different payment types (standard, swaps, etc). */
  def paymentType: String
  /** Absolute time in milli-seconds since UNIX epoch when the invoice was generated. */
  def createdAt: TimestampMilli
  /** Current status of the payment. */
  def status: IncomingPaymentStatus
  // @formatter:on
}

/** A standard incoming payment received by this node. */
case class IncomingStandardPayment(invoice: Bolt11Invoice,
                                   paymentPreimage: ByteVector32,
                                   paymentType: String,
                                   createdAt: TimestampMilli,
                                   status: IncomingPaymentStatus) extends IncomingPayment

/**
 * A blinded incoming payment received by this node.
 *
 * @param pathIds map the last blinding point of a blinded path to the corresponding pathId.
 */
case class IncomingBlindedPayment(invoice: Bolt12Invoice,
                                  paymentPreimage: ByteVector32,
                                  paymentType: String,
                                  pathIds: Map[PublicKey, ByteVector],
                                  createdAt: TimestampMilli,
                                  status: IncomingPaymentStatus) extends IncomingPayment

sealed trait IncomingPaymentStatus

object IncomingPaymentStatus {

  /** Payment is pending (waiting to receive). */
  case object Pending extends IncomingPaymentStatus

  /** Payment has expired. */
  case object Expired extends IncomingPaymentStatus

  /**
   * Payment has been successfully received.
   *
   * @param amount     amount of the payment received, in milli-satoshis (may exceed the invoice amount).
   * @param receivedAt absolute time in milli-seconds since UNIX epoch when the payment was received.
   */
  case class Received(amount: MilliSatoshi, receivedAt: TimestampMilli) extends IncomingPaymentStatus

}

/**
 * An outgoing payment sent by this node.
 * At first it is in a pending state, then will become either a success or a failure.
 *
 * @param id              internal payment identifier.
 * @param parentId        internal identifier of a parent payment, or [[id]] if single-part payment.
 * @param externalId      external payment identifier: lets lightning applications reconcile payments with their own db.
 * @param paymentHash     payment_hash.
 * @param paymentType     distinguish different payment types (standard, swaps, etc).
 * @param amount          amount that will be received by the target node, will be different from recipientAmount for trampoline payments.
 * @param recipientAmount amount that will be received by the final recipient.
 * @param recipientNodeId id of the final recipient.
 * @param createdAt       absolute time in milli-seconds since UNIX epoch when the payment was created.
 * @param invoice         invoice (if paying from an invoice).
 * @param status          current status of the payment.
 */
case class OutgoingPayment(id: UUID,
                           parentId: UUID,
                           externalId: Option[String],
                           paymentHash: ByteVector32,
                           paymentType: String,
                           amount: MilliSatoshi,
                           recipientAmount: MilliSatoshi,
                           recipientNodeId: PublicKey,
                           createdAt: TimestampMilli,
                           invoice: Option[Invoice],
                           payerKey_opt: Option[PrivateKey],
                           status: OutgoingPaymentStatus)

sealed trait OutgoingPaymentStatus

object OutgoingPaymentStatus {

  /** Payment is pending (waiting for the recipient to release the pre-image). */
  case object Pending extends OutgoingPaymentStatus

  /**
   * Payment has been successfully sent and the recipient released the pre-image.
   * We now have a valid proof-of-payment.
   *
   * @param paymentPreimage the preimage of the payment_hash.
   * @param feesPaid        fees paid to route to the target node (which not necessarily the final recipient, e.g. when
   *                        trampoline is used).
   * @param route           payment route used.
   * @param completedAt     absolute time in milli-seconds since UNIX epoch when the payment was completed.
   */
  case class Succeeded(paymentPreimage: ByteVector32, feesPaid: MilliSatoshi, route: Seq[HopSummary], completedAt: TimestampMilli) extends OutgoingPaymentStatus

  /**
   * Payment has failed and may be retried.
   *
   * @param failures    failed payment attempts.
   * @param completedAt absolute time in milli-seconds since UNIX epoch when the payment was completed.
   */
  case class Failed(failures: Seq[FailureSummary], completedAt: TimestampMilli) extends OutgoingPaymentStatus

}

/** A minimal representation of a hop in a payment route (suitable to store in a database). */
case class HopSummary(nodeId: PublicKey, nextNodeId: PublicKey, shortChannelId: Option[ShortChannelId] = None) {
  override def toString = shortChannelId match {
    case Some(shortChannelId) => s"$nodeId->$nextNodeId ($shortChannelId)"
    case None => s"$nodeId->$nextNodeId"
  }
}

object HopSummary {
  def apply(h: Hop): HopSummary = {
    val shortChannelId = h match {
      case ch: ChannelHop => Some(ch.shortChannelId)
      case _: BlindedHop => None
      case _: NodeHop => None
    }
    HopSummary(h.nodeId, h.nextNodeId, shortChannelId)
  }
}

/** A minimal representation of a payment failure (suitable to store in a database). */
trait GenericFailureSummary

case class FailureSummary(failureType: FailureType.Value, failureMessage: String, failedRoute: List[HopSummary], failedNode: Option[PublicKey]) extends GenericFailureSummary

object FailureType extends Enumeration {
  type FailureType = Value
  val LOCAL = Value(1, "Local")
  val REMOTE = Value(2, "Remote")
  val UNREADABLE_REMOTE = Value(3, "UnreadableRemote")
}

object FailureSummary {
  def apply(f: PaymentFailure): FailureSummary = f match {
    case LocalFailure(_, route, t) => FailureSummary(FailureType.LOCAL, t.getMessage, route.map(h => HopSummary(h)).toList, route.headOption.map(_.nodeId))
    case RemoteFailure(_, route, e) => FailureSummary(FailureType.REMOTE, e.failureMessage.message, route.map(h => HopSummary(h)).toList, Some(e.originNode))
    case UnreadableRemoteFailure(_, route) => FailureSummary(FailureType.UNREADABLE_REMOTE, "could not decrypt failure onion", route.map(h => HopSummary(h)).toList, None)
  }
}

object PaymentsDb {

  import fr.acinq.eclair.wire.protocol.CommonCodecs
  import scodec.Attempt
  import scodec.bits.BitVector
  import scodec.codecs._

  private case class LegacyFailureSummary(failureType: FailureType.Value, failureMessage: String, failedRoute: List[HopSummary]) extends GenericFailureSummary {
    def toFailureSummary: FailureSummary = FailureSummary(failureType, failureMessage, failedRoute, None)
  }

  private val hopSummaryCodec = (("node_id" | CommonCodecs.publicKey) :: ("next_node_id" | CommonCodecs.publicKey) :: ("short_channel_id" | optional(bool, CommonCodecs.shortchannelid))).as[HopSummary]
  val paymentRouteCodec = discriminated[List[HopSummary]].by(byte)
    .typecase(0x01, listOfN(uint8, hopSummaryCodec))

  def encodeRoute(route: List[HopSummary]): Array[Byte] = {
    paymentRouteCodec.encode(route).require.toByteArray
  }

  def decodeRoute(b: BitVector): List[HopSummary] = {
    paymentRouteCodec.decode(b) match {
      case Attempt.Successful(route) => route.value
      case Attempt.Failure(_) => Nil
    }
  }

  private val legacyFailureSummaryCodec = (("type" | enumerated(uint8, FailureType)) :: ("message" | ascii32) :: paymentRouteCodec).as[LegacyFailureSummary]
  private val failureSummaryCodec = (("type" | enumerated(uint8, FailureType)) :: ("message" | ascii32) :: paymentRouteCodec :: ("node_id" | optional(bool, CommonCodecs.publicKey))).as[FailureSummary]
  private val paymentFailuresCodec = discriminated[List[GenericFailureSummary]].by(byte)
    .typecase(0x02, listOfN(uint8, failureSummaryCodec))
    .typecase(0x01, listOfN(uint8, legacyFailureSummaryCodec).decodeOnly)

  def encodeFailures(failures: List[FailureSummary]): Array[Byte] = {
    paymentFailuresCodec.encode(failures).require.toByteArray
  }

  def decodeFailures(b: BitVector): List[FailureSummary] = {
    paymentFailuresCodec.decode(b) match {
      case Attempt.Successful(f) => f.value.collect {
        case failure: FailureSummary => failure
        case legacy: LegacyFailureSummary => legacy.toFailureSummary
      }
      case Attempt.Failure(_) => Nil
    }
  }

  private val pathIdCodec = (("blinding_key" | CommonCodecs.publicKey) :: ("path_id" | variableSizeBytes(uint16, bytes))).as[(PublicKey, ByteVector)]
  private val pathIdsCodec = "path_ids" | listOfN(uint16, pathIdCodec)

  def encodePathIds(pathIds: Map[PublicKey, ByteVector]): Array[Byte] = {
    pathIdsCodec.encode(pathIds.toList).require.toByteArray
  }

  def decodePathIds(b: BitVector): Map[PublicKey, ByteVector] = {
    pathIdsCodec.decode(b) match {
      case Attempt.Successful(pathIds) => pathIds.value.toMap
      case Attempt.Failure(_) => Map.empty
    }
  }

}