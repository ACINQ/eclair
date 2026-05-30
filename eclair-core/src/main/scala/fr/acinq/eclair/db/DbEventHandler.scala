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

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorContextOps
import akka.actor.{Actor, DiagnosticActorLogging, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, TxId}
import fr.acinq.eclair.channel.Helpers.Closing._
import fr.acinq.eclair.channel.Monitoring.{Metrics => ChannelMetrics, Tags => ChannelTags}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.payment.Monitoring.{Metrics => PaymentMetrics, Tags => PaymentTags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{Logs, NodeParams, TimestampMilli}
import kamon.tag.TagSet

/**
 * This actor sits at the interface between our event stream and the database.
 */
class DbEventHandler(nodeParams: NodeParams) extends Actor with DiagnosticActorLogging {

  private val auditDb: AuditDb = nodeParams.db.audit
  private val channelsDb: ChannelsDb = nodeParams.db.channels
  private val liquidityDb: LiquidityDb = nodeParams.db.liquidity

  context.spawn(Behaviors.supervise(RevokedHtlcInfoCleaner(channelsDb, nodeParams.revokedHtlcInfoCleanerConfig)).onFailure(SupervisorStrategy.restart), name = "revoked-htlc-info-cleaner")

  context.system.eventStream.subscribe(self, classOf[PaymentSent])
  context.system.eventStream.subscribe(self, classOf[PaymentFailed])
  context.system.eventStream.subscribe(self, classOf[PaymentReceived])
  context.system.eventStream.subscribe(self, classOf[PaymentRelayed])
  context.system.eventStream.subscribe(self, classOf[ChannelLiquidityPurchased])
  context.system.eventStream.subscribe(self, classOf[TransactionPublished])
  context.system.eventStream.subscribe(self, classOf[TransactionConfirmed])
  context.system.eventStream.subscribe(self, classOf[ChannelErrorOccurred])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelFundingConfirmed])
  context.system.eventStream.subscribe(self, classOf[ChannelClosed])
  context.system.eventStream.subscribe(self, classOf[ChannelUpdateParametersChanged])
  context.system.eventStream.subscribe(self, classOf[PathFindingExperimentMetrics])

  override def receive: Receive = {

    case e: PaymentSent =>
      PaymentMetrics.PaymentAmount.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.recipientAmount.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentFees.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.feesPaid.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(e.parts.length)
      auditDb.add(e)
      e.parts.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentSent))

    case _: PaymentFailed =>
      PaymentMetrics.PaymentFailed.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).increment()

    case e: PaymentReceived =>
      PaymentMetrics.PaymentAmount.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(e.amount.truncateToSatoshi.toLong)
      PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(e.parts.length)
      auditDb.add(e)
      e.parts.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentReceived))

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
        case TrampolinePaymentRelayed(_, incoming, outgoing, _, _) =>
          PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Received).record(incoming.length)
          PaymentMetrics.PaymentParts.withTag(PaymentTags.Direction, PaymentTags.Directions.Sent).record(outgoing.length)
          incoming.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentReceived))
          outgoing.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentSent))
        case ChannelPaymentRelayed(_, incoming, outgoing) =>
          incoming.foreach(i => channelsDb.updateChannelMeta(i.channelId, ChannelEvent.EventType.PaymentReceived))
          outgoing.foreach(o => channelsDb.updateChannelMeta(o.channelId, ChannelEvent.EventType.PaymentSent))
        case OnTheFlyFundingPaymentRelayed(_, incoming, outgoing) =>
          incoming.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentReceived))
          outgoing.foreach(p => channelsDb.updateChannelMeta(p.channelId, ChannelEvent.EventType.PaymentSent))
      }
      auditDb.add(e)

    case e: ChannelLiquidityPurchased => liquidityDb.addPurchase(e)

    case e: TransactionPublished =>
      log.info("paying mining fee={} for txid={} desc={}", e.miningFee, e.tx.txid, e.desc)
      auditDb.add(e)

    case e: TransactionConfirmed =>
      liquidityDb.setConfirmed(e.remoteNodeId, e.tx.txid)
      auditDb.add(e)

    case e: ChannelErrorOccurred =>
      // The first pattern matching level is to ignore some errors, the second level is to separate between different kind of errors.
      e.error match {
        case LocalError(_: CannotAffordFees) => () // will be thrown at each new block if our balance is too low to update the commitment fee
        case _ =>
          val tags = e.error match {
            case LocalError(t) => TagSet.Empty
              .withTag(ChannelTags.Origin, ChannelTags.Origins.Local)
              .withTag(ChannelTags.Fatal, value = e.isFatal)
              .withTag(ChannelTags.ErrorType, t.getClass.getSimpleName)
            case RemoteError(_) => TagSet.Empty
              .withTag(ChannelTags.Origin, ChannelTags.Origins.Remote)
              .withTag(ChannelTags.Fatal, value = true) // remote errors are always fatal
          }
          ChannelMetrics.ChannelErrors.withTags(tags).increment()
      }

    case e: ChannelStateChanged =>
      // NB: order matters!
      e match {
        case ChannelStateChanged(_, channelId, _, remoteNodeId, WAIT_FOR_CHANNEL_READY | WAIT_FOR_DUAL_FUNDING_READY, NORMAL, Some(commitments)) =>
          ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Created).increment()
          val event = ChannelEvent.EventType.Created
          auditDb.add(ChannelEvent(channelId, remoteNodeId, commitments.latest.fundingTxId, commitments.latest.commitmentFormat.toString, commitments.latest.capacity, commitments.localChannelParams.isChannelOpener, !commitments.announceChannel, event.label))
          channelsDb.updateChannelMeta(channelId, event)
        case ChannelStateChanged(_, channelId, _, _, OFFLINE, SYNCING, _) =>
          channelsDb.updateChannelMeta(channelId, ChannelEvent.EventType.Connected)
        case ChannelStateChanged(_, _, _, _, _, CLOSING, _) =>
          ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Closing).increment()
        case _ => ()
      }

    case e: ChannelFundingConfirmed =>
      if (e.fundingTxIndex > 0) {
        ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Spliced).increment()
      }
      val event = e.fundingTxIndex match {
        case 0 => ChannelEvent.EventType.Confirmed
        case _ => ChannelEvent.EventType.Spliced
      }
      auditDb.add(ChannelEvent(e.channelId, e.remoteNodeId, e.fundingTxId, e.commitments.latest.commitmentFormat.toString, e.commitments.latest.capacity, e.commitments.localChannelParams.isChannelOpener, !e.commitments.announceChannel, event.label))

    case e: ChannelClosed =>
      ChannelMetrics.ChannelLifecycleEvents.withTag(ChannelTags.Event, ChannelTags.Events.Closed).increment()
      val event = ChannelEvent.EventType.Closed(e.closingType)
      // We use the latest state of the channel (in case it has been spliced since it was opened), which is the state
      // spent by the closing transaction.
      val capacity = e.commitments.latest.capacity
      val fundingTxId = e.commitments.latest.fundingTxId
      auditDb.add(ChannelEvent(e.channelId, e.commitments.remoteNodeId, fundingTxId, e.commitments.latest.commitmentFormat.toString, capacity, e.commitments.localChannelParams.isChannelOpener, !e.commitments.announceChannel, event.label))
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
  case class ChannelEvent(channelId: ByteVector32, remoteNodeId: PublicKey, fundingTxId: TxId, channelType: String, capacity: Satoshi, isChannelOpener: Boolean, isPrivate: Boolean, event: String, timestamp: TimestampMilli = TimestampMilli.now())
  object ChannelEvent {
    sealed trait EventType { def label: String }
    object EventType {
      object Created extends EventType { override def label: String = "created" }
      object Confirmed extends EventType { override def label: String = "confirmed" }
      object Spliced extends EventType { override def label: String = "spliced" }
      object Connected extends EventType { override def label: String = "connected" }
      object PaymentSent extends EventType { override def label: String = "sent" }
      object PaymentReceived extends EventType { override def label: String = "received" }
      case class Closed(closingType: ClosingType) extends EventType {
        override def label: String = closingType match {
          case _: MutualClose => "mutual-close"
          case _: LocalClose => "local-close"
          case _: CurrentRemoteClose => "remote-close"
          case _: NextRemoteClose => "remote-close"
          case _: RecoveryClose => "recovery-close"
          case _: RevokedClose => "revoked-close"
        }
      }
    }
  }
  // @formatter:on
}
