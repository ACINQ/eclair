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
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelLifecycleEvent
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import kamon.Kamon

class Auditor(nodeParams: NodeParams) extends Actor with ActorLogging {

  val db = nodeParams.db.audit

  context.system.eventStream.subscribe(self, classOf[PaymentEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkFeePaid])
  context.system.eventStream.subscribe(self, classOf[ChannelErrorOccurred])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelClosed])

  override def receive: Receive = {

    case e: PaymentSent =>
      Metrics.PaymentAmount.withTag(Tags.Direction, Tags.Directions.Sent).record(e.recipientAmount.truncateToSatoshi.toLong)
      Metrics.PaymentFees.withTag(Tags.Direction, Tags.Directions.Sent).record(e.feesPaid.truncateToSatoshi.toLong)
      Metrics.PaymentParts.withTag(Tags.Direction, Tags.Directions.Sent).record(e.parts.length)
      db.add(e)

    case _: PaymentFailed =>
      Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Sent).increment()

    case e: PaymentReceived =>
      Metrics.PaymentAmount.withTag(Tags.Direction, Tags.Directions.Received).record(e.amount.truncateToSatoshi.toLong)
      Metrics.PaymentParts.withTag(Tags.Direction, Tags.Directions.Received).record(e.parts.length)
      db.add(e)

    case e: PaymentRelayed =>
      Metrics.PaymentAmount
        .withTag(Tags.Direction, Tags.Directions.Relayed)
        .withTag(Tags.Relay, Tags.RelayType.get(e))
        .record(e.amountIn.truncateToSatoshi.toLong)
      Metrics.PaymentFees
        .withTag(Tags.Direction, Tags.Directions.Relayed)
        .withTag(Tags.Relay, Tags.RelayType.get(e))
        .record((e.amountIn - e.amountOut).truncateToSatoshi.toLong)
      e match {
        case TrampolinePaymentRelayed(_, incoming, outgoing, _) =>
          Metrics.PaymentParts.withTag(Tags.Direction, Tags.Directions.Received).record(incoming.length)
          Metrics.PaymentParts.withTag(Tags.Direction, Tags.Directions.Sent).record(outgoing.length)
        case _: ChannelPaymentRelayed =>
      }
      db.add(e)

    case e: NetworkFeePaid => db.add(e)

    case e: ChannelErrorOccurred =>
      val metric = Kamon.counter("channels.errors")
      e.error match {
        case LocalError(_) if e.isFatal => metric.withTag("origin", "local").withTag("fatal", "yes").increment()
        case LocalError(_) if !e.isFatal => metric.withTag("origin", "local").withTag("fatal", "no").increment()
        case RemoteError(_) => metric.withTag("origin", "remote").increment()
      }
      db.add(e)

    case e: ChannelStateChanged =>
      val metric = Kamon.counter("channels.lifecycle")
      // NB: order matters!
      e match {
        case ChannelStateChanged(_, _, remoteNodeId, WAIT_FOR_FUNDING_LOCKED, NORMAL, d: DATA_NORMAL) =>
          metric.withTag("event", "created").increment()
          db.add(ChannelLifecycleEvent(d.channelId, remoteNodeId, d.commitments.commitInput.txOut.amount, d.commitments.localParams.isFunder, !d.commitments.announceChannel, "created"))
        case ChannelStateChanged(_, _, _, WAIT_FOR_INIT_INTERNAL, _, _) =>
        case ChannelStateChanged(_, _, _, _, CLOSING, _) =>
          metric.withTag("event", "closing").increment()
        case _ => ()
      }

    case e: ChannelClosed =>
      Kamon.counter("channels.lifecycle").withTag("event", "closed").increment()
      val event = e.closingType match {
        case MutualClose => "mutual"
        case LocalClose => "local"
        case _: RemoteClose => "remote" // can be current or next
        case RecoveryClose => "recovery"
        case RevokedClose => "revoked"
      }
      db.add(ChannelLifecycleEvent(e.channelId, e.commitments.remoteParams.nodeId, e.commitments.commitInput.txOut.amount, e.commitments.localParams.isFunder, !e.commitments.announceChannel, event))

  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled msg=$message")

}

object Auditor {

  def props(nodeParams: NodeParams) = Props(classOf[Auditor], nodeParams)

}
