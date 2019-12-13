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

import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.ChannelHop

import scala.compat.Platform

/**
 * Created by PM on 01/02/2017.
 */

sealed trait PaymentEvent {
  val paymentHash: ByteVector32
  val timestamp: Long
}

case class PaymentSent(id: UUID, paymentHash: ByteVector32, paymentPreimage: ByteVector32, parts: Seq[PaymentSent.PartialPayment]) extends PaymentEvent {
  require(parts.nonEmpty, "must have at least one subpayment")
  val amount: MilliSatoshi = parts.map(_.amount).sum
  val feesPaid: MilliSatoshi = parts.map(_.feesPaid).sum
  val timestamp: Long = parts.map(_.timestamp).min // we use min here because we receive the proof of payment as soon as the first partial payment is fulfilled
}

// TODO: @t-bast: the route fields should be a Seq[Hop], not Seq[ChannelHop]

object PaymentSent {

  case class PartialPayment(id: UUID, amount: MilliSatoshi, feesPaid: MilliSatoshi, toChannelId: ByteVector32, route: Option[Seq[ChannelHop]], timestamp: Long = Platform.currentTime) {
    require(route.isEmpty || route.get.nonEmpty, "route must be None or contain at least one hop")
  }

}

case class PaymentFailed(id: UUID, paymentHash: ByteVector32, failures: Seq[PaymentFailure], timestamp: Long = Platform.currentTime) extends PaymentEvent

case class PaymentRelayed(amountIn: MilliSatoshi, amountOut: MilliSatoshi, paymentHash: ByteVector32, fromChannelId: ByteVector32, toChannelId: ByteVector32, timestamp: Long = Platform.currentTime) extends PaymentEvent

case class PaymentReceived(paymentHash: ByteVector32, parts: Seq[PaymentReceived.PartialPayment]) extends PaymentEvent {
  require(parts.nonEmpty, "must have at least one subpayment")
  val amount: MilliSatoshi = parts.map(_.amount).sum
  val timestamp: Long = parts.map(_.timestamp).max // we use max here because we fulfill the payment only once we received all the parts
}

object PaymentReceived {

  case class PartialPayment(amount: MilliSatoshi, fromChannelId: ByteVector32, timestamp: Long = Platform.currentTime)

}

case class PaymentSettlingOnChain(id: UUID, amount: MilliSatoshi, paymentHash: ByteVector32, timestamp: Long = Platform.currentTime) extends PaymentEvent

sealed trait PaymentFailure

/** A failure happened locally, preventing the payment from being sent (e.g. no route found). */
case class LocalFailure(t: Throwable) extends PaymentFailure

/** A remote node failed the payment and we were able to decrypt the onion failure packet. */
case class RemoteFailure(route: Seq[ChannelHop], e: Sphinx.DecryptedFailurePacket) extends PaymentFailure

/** A remote node failed the payment but we couldn't decrypt the failure (e.g. a malicious node tampered with the message). */
case class UnreadableRemoteFailure(route: Seq[ChannelHop]) extends PaymentFailure

object PaymentFailure {

  import fr.acinq.bitcoin.Crypto.PublicKey
  import fr.acinq.eclair.channel.AddHtlcFailed
  import fr.acinq.eclair.router.RouteNotFound
  import fr.acinq.eclair.wire.Update

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
    failures.map {
      case LocalFailure(AddHtlcFailed(_, _, t, _, _, _)) => LocalFailure(t) // we're interested in the error which caused the add-htlc to fail
      case other => other
    } match {
      case previousFailures :+ LocalFailure(RouteNotFound) if previousFailures.nonEmpty => previousFailures
      case other => other
    }
  }

  /**
   * This allows us to detect if a bad node always answers with a new update (e.g. with a slightly different expiry or fee)
   * in order to mess with us.
   */
  def hasAlreadyFailedOnce(nodeId: PublicKey, failures: Seq[PaymentFailure]): Boolean =
    failures
      .collectFirst { case RemoteFailure(_, Sphinx.DecryptedFailurePacket(origin, u: Update)) if origin == nodeId => u.update }
      .isDefined

}