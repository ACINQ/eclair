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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.send.PaymentError.RetryExhausted
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.{ClearRecipient, Recipient}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TimestampMilli}
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
 * Created by PM on 01/02/2017.
 */

sealed trait PaymentEvent {
  val paymentHash: ByteVector32
  val timestamp: TimestampMilli
}

/**
 * A payment was successfully sent and fulfilled.
 *
 * @param id              id of the whole payment attempt (if using multi-part, there will be multiple parts, each with
 *                        a different id).
 * @param paymentHash     payment hash.
 * @param paymentPreimage payment preimage (proof of payment).
 * @param recipientAmount amount that has been received by the final recipient.
 * @param recipientNodeId id of the final recipient.
 * @param parts           child payments (actual outgoing HTLCs).
 */
case class PaymentSent(id: UUID, paymentHash: ByteVector32, paymentPreimage: ByteVector32, recipientAmount: MilliSatoshi, recipientNodeId: PublicKey, parts: Seq[PaymentSent.PartialPayment]) extends PaymentEvent {
  require(parts.nonEmpty, "must have at least one payment part")
  val amountWithFees: MilliSatoshi = parts.map(_.amountWithFees).sum
  val feesPaid: MilliSatoshi = amountWithFees - recipientAmount // overall fees for this payment (routing + trampoline)
  val trampolineFees: MilliSatoshi = parts.map(_.amount).sum - recipientAmount
  val nonTrampolineFees: MilliSatoshi = feesPaid - trampolineFees // routing fees to reach the first trampoline node, or the recipient if not using trampoline
  val timestamp: TimestampMilli = parts.map(_.timestamp).min // we use min here because we receive the proof of payment as soon as the first partial payment is fulfilled
}

object PaymentSent {

  /**
   * A successfully sent partial payment (single outgoing HTLC).
   *
   * @param id          id of the outgoing payment.
   * @param amount      amount received by the target node.
   * @param feesPaid    fees paid to route to the target node.
   * @param toChannelId id of the channel used.
   * @param route       payment route used.
   * @param timestamp   absolute time in milli-seconds since UNIX epoch when the payment was fulfilled.
   */
  case class PartialPayment(id: UUID, amount: MilliSatoshi, feesPaid: MilliSatoshi, toChannelId: ByteVector32, route: Option[Seq[Hop]], timestamp: TimestampMilli = TimestampMilli.now()) {
    require(route.isEmpty || route.get.nonEmpty, "route must be None or contain at least one hop")
    val amountWithFees: MilliSatoshi = amount + feesPaid
  }

}

case class PaymentFailed(id: UUID, paymentHash: ByteVector32, failures: Seq[PaymentFailure], timestamp: TimestampMilli = TimestampMilli.now()) extends PaymentEvent

sealed trait PaymentRelayed extends PaymentEvent {
  val amountIn: MilliSatoshi
  val amountOut: MilliSatoshi
  val timestamp: TimestampMilli
}

case class ChannelPaymentRelayed(amountIn: MilliSatoshi, amountOut: MilliSatoshi, paymentHash: ByteVector32, fromChannelId: ByteVector32, toChannelId: ByteVector32, timestamp: TimestampMilli = TimestampMilli.now()) extends PaymentRelayed

case class TrampolinePaymentRelayed(paymentHash: ByteVector32, incoming: PaymentRelayed.Incoming, outgoing: PaymentRelayed.Outgoing, nextTrampolineNodeId: PublicKey, nextTrampolineAmount: MilliSatoshi, timestamp: TimestampMilli = TimestampMilli.now()) extends PaymentRelayed {
  override val amountIn: MilliSatoshi = incoming.map(_.amount).sum
  override val amountOut: MilliSatoshi = outgoing.map(_.amount).sum
}

object PaymentRelayed {

  case class Part(amount: MilliSatoshi, channelId: ByteVector32)

  type Incoming = Seq[Part]
  type Outgoing = Seq[Part]

}

case class PaymentReceived(paymentHash: ByteVector32, parts: Seq[PaymentReceived.PartialPayment]) extends PaymentEvent {
  require(parts.nonEmpty, "must have at least one payment part")
  val amount: MilliSatoshi = parts.map(_.amount).sum
  val timestamp: TimestampMilli = parts.map(_.timestamp).max // we use max here because we fulfill the payment only once we received all the parts
}

object PaymentReceived {

  case class PartialPayment(amount: MilliSatoshi, fromChannelId: ByteVector32, timestamp: TimestampMilli = TimestampMilli.now())

}

case class PaymentMetadataReceived(paymentHash: ByteVector32, paymentMetadata: ByteVector)

case class PaymentSettlingOnChain(id: UUID, amount: MilliSatoshi, paymentHash: ByteVector32, timestamp: TimestampMilli = TimestampMilli.now()) extends PaymentEvent

case class WaitingToRelayPayment(remoteNodeId: PublicKey, paymentHash: ByteVector32, timestamp: TimestampMilli = TimestampMilli.now()) extends PaymentEvent

sealed trait PaymentFailure {
  // @formatter:off
  def amount: MilliSatoshi
  def route: Seq[Hop]
  // @formatter:on
}

/** A failure happened locally, preventing the payment from being sent (e.g. no route found). */
case class LocalFailure(amount: MilliSatoshi, route: Seq[Hop], t: Throwable) extends PaymentFailure

/** A remote node failed the payment and we were able to decrypt the onion failure packet. */
case class RemoteFailure(amount: MilliSatoshi, route: Seq[Hop], e: Sphinx.DecryptedFailurePacket) extends PaymentFailure

/** A remote node failed the payment but we couldn't decrypt the failure (e.g. a malicious node tampered with the message). */
case class UnreadableRemoteFailure(amount: MilliSatoshi, route: Seq[Hop]) extends PaymentFailure

object PaymentFailure {

  import fr.acinq.eclair.router.RouteNotFound
  import fr.acinq.eclair.wire.protocol.Update

  /**
   * Rewrites a list of failures to retrieve the meaningful part.
   *
   * If a list of failures with many elements ends up with a LocalFailure RouteNotFound, this RouteNotFound failure
   * should be removed. This last failure is irrelevant information. In such a case only the n-1 attempts were rejected
   * with a **significant reason**; the final RouteNotFound error provides no meaningful insight.
   *
   * This method should be used by the user interface to provide a non-exhaustive but more useful feedback.
   *
   * @param failures a list of payment failures for a payment
   */
  def transformForUser(failures: Seq[PaymentFailure]): Seq[PaymentFailure] = {
    failures match {
      case previousFailures :+ LocalFailure(_, _, RouteNotFound) if previousFailures.nonEmpty => previousFailures
      case previousFailures :+ LocalFailure(_, _, RetryExhausted) if previousFailures.nonEmpty => previousFailures
      case other => other
    }
  }

  case class PaymentFailedSummary(cfg: SendPaymentConfig, pathFindingExperiment: String, paymentFailed: PaymentFailed)

  def jsonSummary(cfg: SendPaymentConfig, pathFindingExperiment: String, paymentFailed: PaymentFailed): String = {
    import fr.acinq.eclair.json.JsonSerializers
    Try(JsonSerializers.serialization.write(PaymentFailedSummary(cfg, pathFindingExperiment, paymentFailed))(JsonSerializers.formats)) match {
      case Failure(e) => s"json serialization failed: ${e.getMessage}"
      case Success(json) => json
    }
  }

  /**
   * This allows us to detect if a bad node always answers with a new update (e.g. with a slightly different expiry or fee)
   * in order to mess with us.
   */
  def hasAlreadyFailedOnce(nodeId: PublicKey, failures: Seq[PaymentFailure]): Boolean =
    failures
      .collectFirst { case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(origin, u: Update)) if origin == nodeId => u.update }
      .isDefined

  /** Ignore the channel outgoing from the given nodeId in the given route. */
  private def ignoreNodeOutgoingEdge(nodeId: PublicKey, hops: Seq[Hop], ignore: Ignore): Ignore = {
    hops.collectFirst {
      case hop: ChannelHop if hop.nodeId == nodeId => ChannelDesc(hop.shortChannelId, hop.nodeId, hop.nextNodeId)
      case hop: BlindedHop if hop.nodeId == nodeId => ChannelDesc(hop.dummyId, hop.nodeId, hop.nextNodeId)
      // The error comes from inside the blinded route: this is a spec violation, errors should always come from the
      // introduction node, so we definitely want to ignore this blinded route when this happens.
      case hop: BlindedHop if hop.route.blindedNodeIds.contains(nodeId) => ChannelDesc(hop.dummyId, hop.nodeId, hop.nextNodeId)
    } match {
      case Some(faultyEdge) => ignore + faultyEdge
      case None => ignore
    }
  }

  /** Update the set of nodes and channels to ignore in retries depending on the failure we received. */
  def updateIgnored(failure: PaymentFailure, ignore: Ignore): Ignore = failure match {
    case RemoteFailure(_, hops, Sphinx.DecryptedFailurePacket(nodeId, _)) if nodeId == hops.last.nextNodeId =>
      // The failure came from the final recipient: the payment should be aborted without penalizing anyone in the route.
      ignore
    case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(nodeId, _: Node)) =>
      ignore + nodeId
    case RemoteFailure(_, hops, Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update)) =>
      if (Announcements.checkSig(failureMessage.update, nodeId)) {
        val shouldIgnore = failureMessage match {
          case _: TemporaryChannelFailure => true
          case _: ChannelDisabled => true
          case _ => false
        }
        if (shouldIgnore) {
          ignoreNodeOutgoingEdge(nodeId, hops, ignore)
        } else {
          // We were using an outdated channel update, we should retry with the new one and nobody should be penalized.
          ignore
        }
      } else {
        // This node is fishy, it gave us a bad signature, so let's filter it out.
        ignore + nodeId
      }
    case RemoteFailure(_, hops, Sphinx.DecryptedFailurePacket(nodeId, _)) =>
      ignoreNodeOutgoingEdge(nodeId, hops, ignore)
    case UnreadableRemoteFailure(_, hops) =>
      // We don't know which node is sending garbage, let's blacklist all nodes except:
      //  - the one we are directly connected to: it would be too restrictive for retries
      //  - the final recipient: they have no incentive to send garbage since they want that payment
      //  - the introduction point of a blinded route: we don't want a node before the blinded path to force us to ignore that blinded path
      //  - the trampoline node: we don't want a node before the trampoline node to force us to ignore that trampoline node
      val blacklist = hops.collect { case hop: ChannelHop => hop }.map(_.nextNodeId).drop(1).dropRight(1).toSet
      ignore ++ blacklist
    case LocalFailure(_, hops, _) => hops.headOption match {
      case Some(hop: ChannelHop) =>
        val faultyChannel = ChannelDesc(hop.shortChannelId, hop.nodeId, hop.nextNodeId)
        ignore + faultyChannel
      case _ => ignore
    }
  }

  /** Update the set of nodes and channels to ignore in retries depending on the failures we received. */
  def updateIgnored(failures: Seq[PaymentFailure], ignore: Ignore): Ignore = {
    failures.foldLeft(ignore) { case (current, failure) => updateIgnored(failure, current) }
  }

  /** Update the recipient routing hints based on more recent data received. */
  def updateExtraEdges(failures: Seq[PaymentFailure], recipient: Recipient): Recipient = {
    recipient match {
      case r: ClearRecipient =>
        // We're only interested in the last channel update received per channel.
        val updates = failures.foldLeft(Map.empty[ShortChannelId, ChannelUpdate]) {
          case (current, failure) => failure match {
            case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(_, f: Update)) => current.updated(f.update.shortChannelId, f.update)
            case _ => current
          }
        }
        val extraEdges1 = r.extraEdges.map(edge => updates.get(edge.shortChannelId) match {
          case Some(u) => edge.update(u)
          case None => edge
        })
        r.copy(extraEdges = extraEdges1)
      case r => r
    }
  }

}

case class PathFindingExperimentMetrics(paymentHash: ByteVector32,
                                        amount: MilliSatoshi,
                                        fees: MilliSatoshi,
                                        status: String,
                                        duration: FiniteDuration,
                                        timestamp: TimestampMilli,
                                        isMultiPart: Boolean,
                                        experimentName: String,
                                        recipientNodeId: PublicKey,
                                        extraEdges: Seq[ExtraEdge])
