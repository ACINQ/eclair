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

import akka.actor.{Actor, ActorLogging, Props}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.Helpers.Closing._
import fr.acinq.eclair.channel.Monitoring.{Metrics => ChannelMetrics, Tags => ChannelTags}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelLifecycleEvent
import fr.acinq.eclair.payment.Monitoring.{Metrics => PaymentMetrics, Tags => PaymentTags}

class Auditor(nodeParams: NodeParams) extends Actor with ActorLogging {

  val db = nodeParams.db.audit

  context.system.eventStream.subscribe(self, classOf[PaymentEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkFeePaid])
  context.system.eventStream.subscribe(self, classOf[ChannelErrorOccurred])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelClosed])

  override def receive: Receive = {

    case e: PaymentSent =>
      PaymentMetrics.PaymentAmount.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.recipientAmount.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentFees.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.feesPaid.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.parts.length)
      db.add(e)

    case _: PaymentFailed =>
      PaymentMetrics.PaymentFailed.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).increment()

    case e: PaymentReceived =>
      PaymentMetrics.PaymentAmount.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(e.amount.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(e.parts.length)
      db.add(e)

    case e: PaymentRelayed =>
      PaymentMetrics.PaymentAmount
        .withTag(PaymentTags.Direction, PaymentTags.Directions.Relayed)
        .withTag(PaymentTags.Relay, PaymentTags.RelayType(e))
        .record(e.amountIn.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentFees
        .withTag(PaymentTags.Direction, PaymentTags.Directions.Relayed)
        .withTag(PaymentTags.Relay, PaymentTags.RelayType(e))
        .record((e.amountIn - e.amountOut).truncateToSatoshi.toLong)
      e match {
        case TrampolinePaymentRelayed(_, incoming, outgoing, _) =>
          PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(incoming.length)
          PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(outgoing.length)
        case _: ChannelPaymentRelayed =>
      }
      db.add(e)

    case e: NetworkFeePaid => db.add(e)

    case e: ChannelErrorOccurred =>
      e.error match {
        case LocalError(_) if e.isFatal => ChannelMetrics.ChannelErrors.withTag(ChannelTags.Origin, ChannelTags.Origins.Local).withTag(ChannelTags.Fatal, value = true).increment()
        case LocalError(_) if !e.isFatal => ChannelMetrics.ChannelErrors.withTag(ChannelTags.Origin, ChannelTags.Origins.Local).withTag(ChannelTags.Fatal, value = false).increment()
        case RemoteError(_) => ChannelMetrics.ChannelErrors.withTag(ChannelTags.Origin, ChannelTags.Origins.Remote).increment()
      }
      db.add(e)

    case e: ChannelStateChanged =>
      // NB: order matters!
      e match {
        case ChannelStateChanged(_, channelId, _, remoteNodeId, WAIT_FOR_FUNDING_LOCKED, NORMAL, Some(commitments: Commitments)) =>
          ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Created).increment()
          db.add(ChannelLifecycleEvent(channelId, remoteNodeId, commitments.capacity, commitments.localParams.isFunder, !commitments.announceChannel, "created"))
        case ChannelStateChanged(_, _, _, _, WAIT_FOR_INIT_INTERNAL, _, _) =>
        case ChannelStateChanged(_, _, _, _, _, CLOSING, _) =>
          ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Closing).increment()
        case _ => ()
      }

    case e: ChannelClosed =>
      ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Closed).increment()
      val event = e.closingType match {
        case _: MutualClose => "mutual"
        case _: LocalClose => "local"
        case _: RemoteClose => "remote" // can be current or next
        case _: RecoveryClose => "recovery"
        case _: RevokedClose => "revoked"
      }
      db.add(ChannelLifecycleEvent(e.channelId, e.commitments.remoteParams.nodeId, e.commitments.commitInput.txOut.amount, e.commitments.localParams.isFunder, !e.commitments.announceChannel, event))

  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled msg=$message")

}

object Auditor {

  def props(nodeParams: NodeParams) = Props(classOf[Auditor], nodeParams)

}
