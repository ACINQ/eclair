/*
 * Copyright 2021 ACINQ SAS
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

import akka.actor.{Actor, DiagnosticActorLogging, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.channel.Helpers.Closing._
import fr.acinq.eclair.channel.Monitoring.{Metrics => ChannelMetrics, Tags => ChannelTags}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.payment.Monitoring.{Metrics => PaymentMetrics, Tags => PaymentTags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{Logs, NodeParams}

/**
 * This actor sits at the interface between our event stream and the database.
 */
class DbEventHandler(nodeParams: NodeParams) extends Actor with DiagnosticActorLogging {

  val auditDb: AuditDb = nodeParams.db.audit
  val channelsDb: ChannelsDb = nodeParams.db.channels

  context.system.eventStream.subscribe(self, classOf[PaymentSent])
  context.system.eventStream.subscribe(self, classOf[PaymentFailed])
  context.system.eventStream.subscribe(self, classOf[PaymentReceived])
  context.system.eventStream.subscribe(self, classOf[PaymentRelayed])
  context.system.eventStream.subscribe(self, classOf[TransactionPublished])
  context.system.eventStream.subscribe(self, classOf[TransactionConfirmed])
  context.system.eventStream.subscribe(self, classOf[ChannelErrorOccurred])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelClosed])
  context.system.eventStream.subscribe(self, classOf[ChannelUpdateParametersChanged])
  context.system.eventStream.subscribe(self, classOf[PathFindingExperimentMetrics])

  override def receive: Receive = {

    case e: PaymentSent =>
      PaymentMetrics.PaymentAmount.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.recipientAmount.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentFees.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.feesPaid.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.parts.length)
      auditDb.add(e)
      e.parts.foreach(p => channelsDb.updateChannelMeta(p.toChannelId, ChannelEvent.EventType.PaymentSent))

    case _: PaymentFailed =>
      PaymentMetrics.PaymentFailed.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).increment()

    case e: PaymentReceived =>
      PaymentMetrics.PaymentAmount.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(e.amount.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(e.parts.length)
      auditDb.add(e)
      e.parts.foreach(p => channelsDb.updateChannelMeta(p.fromChannelId, ChannelEvent.EventType.PaymentReceived))

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
        case TrampolinePaymentRelayed(_, incoming, outgoing, _, _, _) =>
          PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(incoming.length)
          PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(outgoing.length)
          incoming.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentReceived))
          outgoing.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentSent))
        case ChannelPaymentRelayed(_, _, _, fromChannelId, toChannelId, _) =>
          channelsDb.updateChannelMeta(fromChannelId, ChannelEvent.EventType.PaymentReceived)
          channelsDb.updateChannelMeta(toChannelId, ChannelEvent.EventType.PaymentSent)
      }
      auditDb.add(e)

    case e: TransactionPublished =>
      log.info(s"paying mining fee=${e.miningFee} for txid=${e.tx.txid} desc=${e.desc}")
      auditDb.add(e)

    case e: TransactionConfirmed => auditDb.add(e)

    case e: ChannelErrorOccurred =>
      // first pattern matching level is to ignore some errors, second level is to separate between different kind of errors
      e.error match {
        case LocalError(_: CannotAffordFees) => () // will be thrown at each new block if our balance is too low to update the commitment fee
        case _ =>
          e.error match {
            case LocalError(_) => ChannelMetrics.ChannelErrors.withTag(ChannelTags.Origin, ChannelTags.Origins.Local).withTag(ChannelTags.Fatal, value = e.isFatal).increment()
            case RemoteError(_) => ChannelMetrics.ChannelErrors.withTag(ChannelTags.Origin, ChannelTags.Origins.Remote).increment()
          }
          auditDb.add(e)
      }

    case e: ChannelStateChanged =>
      // NB: order matters!
      e match {
        case ChannelStateChanged(_, channelId, _, remoteNodeId, WAIT_FOR_CHANNEL_READY | WAIT_FOR_DUAL_FUNDING_READY, NORMAL, Some(commitments)) =>
          ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Created).increment()
          val event = ChannelEvent.EventType.Created
          auditDb.add(ChannelEvent(channelId, remoteNodeId, commitments.latest.capacity, commitments.params.localParams.isInitiator, !commitments.announceChannel, event))
          channelsDb.updateChannelMeta(channelId, event)
        case ChannelStateChanged(_, _, _, _, WAIT_FOR_INIT_INTERNAL, _, _) =>
        case ChannelStateChanged(_, channelId, _, _, OFFLINE, SYNCING, _) =>
          channelsDb.updateChannelMeta(channelId, ChannelEvent.EventType.Connected)
        case ChannelStateChanged(_, _, _, _, _, CLOSING, _) =>
          ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Closing).increment()
        case _ => ()
      }

    case e: ChannelClosed =>
      ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Closed).increment()
      val event = ChannelEvent.EventType.Closed(e.closingType)
      val capacity = e.commitments.latest.capacity
      auditDb.add(ChannelEvent(e.channelId, e.commitments.params.remoteParams.nodeId, capacity, e.commitments.params.localParams.isInitiator, !e.commitments.announceChannel, event))
      channelsDb.updateChannelMeta(e.channelId, event)

    case u: ChannelUpdateParametersChanged =>
      auditDb.addChannelUpdate(u)

    case m: PathFindingExperimentMetrics =>
      auditDb.addPathFindingExperimentMetrics(m)

  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled msg=$message")

  override def mdc(currentMessage: Any): MDC = {
    currentMessage match {
      case msg: TransactionPublished => Logs.mdc(remoteNodeId_opt = Some(msg.remoteNodeId), channelId_opt = Some(msg.channelId))
      case msg: TransactionConfirmed => Logs.mdc(remoteNodeId_opt = Some(msg.remoteNodeId), channelId_opt = Some(msg.channelId))
      case _ => Logs.mdc()
    }
  }

}

object DbEventHandler {

  def props(nodeParams: NodeParams): Props = Props(new DbEventHandler(nodeParams))

  // @formatter:off
  case class ChannelEvent(channelId: ByteVector32, remoteNodeId: PublicKey, capacity: Satoshi, isInitiator: Boolean, isPrivate: Boolean, event: ChannelEvent.EventType)
  object ChannelEvent {
    sealed trait EventType { def label: String }
    object EventType {
      object Created extends EventType { override def label: String = "created" }
      object Connected extends EventType { override def label: String = "connected" }
      object PaymentSent extends EventType { override def label: String = "sent" }
      object PaymentReceived extends EventType { override def label: String = "received" }
      case class Closed(closingType: ClosingType) extends EventType {
        override def label: String = closingType match {
          case _: MutualClose => "mutual"
          case _: LocalClose => "local"
          case _: CurrentRemoteClose => "remote"
          case _: NextRemoteClose => "remote"
          case _: RecoveryClose => "recovery"
          case _: RevokedClose => "revoked"
        }
      }
    }
  }
  // @formatter:on
}
