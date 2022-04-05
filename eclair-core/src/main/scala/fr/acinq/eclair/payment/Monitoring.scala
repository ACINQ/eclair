/*
 * Copyright 2020 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.CMD_FAIL_HTLC
import kamon.Kamon

object Monitoring {

  object Metrics {
    val PaymentAmount = Kamon.histogram("payment.amount", "Payment amount (satoshi)")
    val PaymentFees = Kamon.histogram("payment.fees", "Payment fees (satoshi)")
    val PaymentParts = Kamon.histogram("payment.parts", "Number of HTLCs per payment (MPP)")
    val PaymentHtlcReceived = Kamon.counter("payment.received", "Number of valid htlcs received")
    val PaymentFailed = Kamon.counter("payment.failed", "Number of failed payment")
    val PaymentError = Kamon.counter("payment.error", "Non-fatal errors encountered during payment attempts")
    val PaymentAttempt = Kamon.histogram("payment.attempt", "Number of attempts before a payment succeeds")
    val SentPaymentDuration = Kamon.timer("payment.duration.sent", "Outgoing payment duration")
    val ReceivedPaymentDuration = Kamon.timer("payment.duration.received", "Incoming payment duration")

    // The goal of this metric is to measure whether retrying MPP payments on failing channels yields useful results.
    // Once enough data has been collected, we will update the MultiPartPaymentLifecycle logic accordingly.
    val RetryFailedChannelsResult = Kamon.counter("payment.mpp.retry-failed-channels-result")

    private val PaymentNodeInAmount = Kamon.histogram("payment.node.in.amount", "Distribution of incoming payments across nodes (satoshi)")
    private val PaymentNodeIn = Kamon.histogram("payment.node.in", "Distribution of incoming payments across nodes (count)")
    private val PaymentNodeOutAmount = Kamon.histogram("payment.node.out.amount", "Distribution of outgoing payments across nodes (satoshi)")
    private val PaymentNodeOut = Kamon.histogram("payment.node.out", "Distribution of outgoing payments across nodes (count)")

    def recordPaymentRelayFailed(failureType: String, relayType: String): Unit =
      Metrics.PaymentFailed
        .withTag(Tags.Direction, Tags.Directions.Relayed)
        .withTag(Tags.Failure, failureType)
        .withTag(Tags.Relay, relayType)
        .increment()

    /**
     * Assign a bucket to a node id. There are 256 buckets.
     */
    def nodeIdBucket(nodeId: PublicKey): Short = nodeId.value.takeRight(1).toShort(signed = false) // we use short to not have negative values

    def recordIncomingPaymentDistribution(nodeId: PublicKey, amount: MilliSatoshi): Unit = {
      val bucket = nodeIdBucket(nodeId)
      PaymentNodeInAmount.withoutTags().record(bucket, amount.truncateToSatoshi.toLong)
      PaymentNodeIn.withoutTags().record(bucket)
    }

    def recordOutgoingPaymentDistribution(nodeId: PublicKey, amount: MilliSatoshi): Unit = {
      val bucket = nodeIdBucket(nodeId)
      PaymentNodeOutAmount.withoutTags().record(bucket, amount.truncateToSatoshi.toLong)
      PaymentNodeOut.withoutTags().record(bucket)
    }
  }

  object Tags {
    val PaymentId = "paymentId"
    val ParentId = "parentPaymentId"
    val PaymentHash = "paymentHash"
    val PaymentMetadataIncluded = "paymentMetadataIncluded"

    val Amount = "amount"
    val TotalAmount = "totalAmount"
    val RecipientAmount = "recipientAmount"
    val Expiry = "expiry"

    val RecipientNodeId = "recipientNodeId"
    val TargetNodeId = "targetNodeId"

    val Direction = "direction"

    object Directions {
      val Sent = "sent"
      val Received = "received"
      val Relayed = "relayed"
    }

    val Relay = "relay"

    object RelayType {
      val Channel = "channel"
      val Trampoline = "trampoline"

      def apply(e: PaymentRelayed): String = e match {
        case _: ChannelPaymentRelayed => Channel
        case _: TrampolinePaymentRelayed => Trampoline
      }
    }

    val MultiPart = "multiPart"

    object MultiPartType {
      val Parent = "parent"
      val Child = "child"
      val Disabled = "disabled"
    }

    val Success = "success"
    val Failure = "failure"

    object FailureType {
      val Remote = "Remote"
      val Malformed = "MalformedHtlc"

      def apply(cmdFail: CMD_FAIL_HTLC): String = cmdFail.reason match {
        case Left(_) => Remote
        case Right(f) => f.getClass.getSimpleName
      }

      def apply(pf: PaymentFailure): String = pf match {
        case LocalFailure(_, _, t) => t.getClass.getSimpleName
        case RemoteFailure(_, _, e) => e.failureMessage.getClass.getSimpleName
        case UnreadableRemoteFailure(_, _) => "UnreadableRemoteFailure"
      }
    }

  }

}
