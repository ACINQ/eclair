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

package fr.acinq.eclair.payment.relay

import java.util.UUID

import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, Props, Status}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Logs, LongToBtcAmount, MilliSatoshi, NodeParams, ShortChannelId}
import grizzled.slf4j.Logging

import scala.collection.mutable

// @formatter:off
sealed trait Origin
object Origin {
  /** Our node is the origin of the payment. */
  case class Local(id: UUID, sender: Option[ActorRef]) extends Origin // we don't persist reference to local actors
  /** Our node forwarded a single incoming HTLC to an outgoing channel. */
  case class Relayed(originChannelId: ByteVector32, originHtlcId: Long, amountIn: MilliSatoshi, amountOut: MilliSatoshi) extends Origin
  // TODO: @t-bast: add TrampolineRelayed
}
// @formatter:on

/**
 * Created by PM on 01/02/2017.
 */

/**
 * The Relayer decrypts incoming HTLCs and relays accordingly:
 *  - to a payment handler if we are the final recipient
 *  - to a channel relayer if we are relaying from an upstream channel to a downstream channel
 *  - to a node relayer if we are relaying a trampoline payment
 *
 * It also receives channel HTLC events (fulfill / failed) and relays those to the appropriate handlers.
 * It also maintains an up-to-date view of local channel balances.
 */
class Relayer(nodeParams: NodeParams, register: ActorRef, commandBuffer: ActorRef, paymentHandler: ActorRef) extends Actor with DiagnosticActorLogging {

  import Relayer._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  private val channelRelayer = context.actorOf(ChannelRelayer.props(nodeParams, self, register, commandBuffer))

  override def receive: Receive = main(Map.empty, new mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId])

  def main(channelUpdates: ChannelUpdates, node2channels: NodeChannels): Receive = {
    case GetOutgoingChannels(enabledOnly) =>
      val channels = if (enabledOnly) {
        channelUpdates.values.filter(o => Announcements.isEnabled(o.channelUpdate.channelFlags))
      } else {
        channelUpdates.values
      }
      sender ! OutgoingChannels(channels.toSeq)

    case LocalChannelUpdate(_, channelId, shortChannelId, remoteNodeId, _, channelUpdate, commitments) =>
      log.debug(s"updating local channel info for channelId=$channelId shortChannelId=$shortChannelId remoteNodeId=$remoteNodeId channelUpdate={} commitments={}", channelUpdate, commitments)
      val channelUpdates1 = channelUpdates + (channelUpdate.shortChannelId -> OutgoingChannel(remoteNodeId, channelUpdate, commitments))
      context become main(channelUpdates1, node2channels.addBinding(remoteNodeId, channelUpdate.shortChannelId))

    case LocalChannelDown(_, channelId, shortChannelId, remoteNodeId) =>
      log.debug(s"removed local channel info for channelId=$channelId shortChannelId=$shortChannelId")
      context become main(channelUpdates - shortChannelId, node2channels.removeBinding(remoteNodeId, shortChannelId))

    case AvailableBalanceChanged(_, _, shortChannelId, _, commitments) =>
      val channelUpdates1 = channelUpdates.get(shortChannelId) match {
        case Some(c: OutgoingChannel) => channelUpdates + (shortChannelId -> c.copy(commitments = commitments))
        case None => channelUpdates // we only consider the balance if we have the channel_update
      }
      context become main(channelUpdates1, node2channels)

    case ShortChannelIdAssigned(_, channelId, shortChannelId, previousShortChannelId) =>
      previousShortChannelId.foreach(previousShortChannelId => {
        if (previousShortChannelId != shortChannelId) {
          log.debug(s"shortChannelId changed for channelId=$channelId ($previousShortChannelId->$shortChannelId, probably due to chain re-org)")
          // We simply remove the old entry: we should receive a LocalChannelUpdate with the new shortChannelId shortly.
          val node2channels1 = channelUpdates.get(previousShortChannelId).map(_.nextNodeId) match {
            case Some(remoteNodeId) => node2channels.removeBinding(remoteNodeId, previousShortChannelId)
            case None => node2channels
          }
          context become main(channelUpdates - previousShortChannelId, node2channels1)
        }
      })

    case ForwardAdd(add, previousFailures) =>
      log.debug(s"received forwarding request for htlc #${add.id} from channelId=${add.channelId}")
      IncomingPacket.decrypt(add, nodeParams.privateKey, nodeParams.globalFeatures) match {
        case Right(p: IncomingPacket.FinalPacket) =>
          log.debug(s"forwarding htlc #${add.id} to payment-handler")
          paymentHandler forward p
        case Right(r: IncomingPacket.ChannelRelayPacket) =>
          channelRelayer forward ChannelRelayer.RelayHtlc(r, previousFailures, channelUpdates, node2channels)
        case Right(r: IncomingPacket.NodeRelayPacket) =>
          if (!nodeParams.enableTrampolinePayment) {
            log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} to nodeId=${r.innerPayload.outgoingNodeId} reason=trampoline disabled")
            commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, CMD_FAIL_HTLC(add.id, Right(RequiredNodeFeatureMissing), commit = true))
          } else {
            // TODO: @t-bast: relay trampoline payload instead of rejecting.
            log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} to nodeId=${r.innerPayload.outgoingNodeId} reason=trampoline not implemented yet")
            commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, CMD_FAIL_HTLC(add.id, Right(RequiredNodeFeatureMissing), commit = true))
          }
        case Left(badOnion: BadOnion) =>
          log.warning(s"couldn't parse onion: reason=${badOnion.message}")
          val cmdFail = CMD_FAIL_MALFORMED_HTLC(add.id, badOnion.onionHash, badOnion.code, commit = true)
          log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} reason=malformed onionHash=${cmdFail.onionHash} failureCode=${cmdFail.failureCode}")
          commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
        case Left(failure) =>
          log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} reason=$failure")
          val cmdFail = CMD_FAIL_HTLC(add.id, Right(failure), commit = true)
          commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
      }

    case Status.Failure(addFailed: AddHtlcFailed) =>
      import addFailed.paymentHash
      addFailed.origin match {
        case Origin.Local(id, None) =>
          handleLocalPaymentAfterRestart(PaymentFailed(id, paymentHash, Nil))
        case Origin.Local(_, Some(sender)) =>
          sender ! Status.Failure(addFailed)
        case _: Origin.Relayed =>
          channelRelayer forward Status.Failure(addFailed)
      }

    case ForwardFulfill(fulfill, to, add) =>
      to match {
        case Origin.Local(id, None) =>
          val feesPaid = 0.msat // fees are unknown since we lost the reference to the payment
          handleLocalPaymentAfterRestart(PaymentSent(id, add.paymentHash, fulfill.paymentPreimage, Seq(PaymentSent.PartialPayment(id, add.amountMsat, feesPaid, add.channelId, None))))
        case Origin.Local(_, Some(sender)) =>
          sender ! fulfill
        case Origin.Relayed(originChannelId, originHtlcId, amountIn, amountOut) =>
          val cmd = CMD_FULFILL_HTLC(originHtlcId, fulfill.paymentPreimage, commit = true)
          commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)
          context.system.eventStream.publish(PaymentRelayed(amountIn, amountOut, add.paymentHash, fromChannelId = originChannelId, toChannelId = fulfill.channelId))
      }

    case ForwardFail(fail, to, add) =>
      to match {
        case Origin.Local(id, None) =>
          handleLocalPaymentAfterRestart(PaymentFailed(id, add.paymentHash, Nil))
        case Origin.Local(_, Some(sender)) =>
          sender ! fail
        case Origin.Relayed(originChannelId, originHtlcId, _, _) =>
          val cmd = CMD_FAIL_HTLC(originHtlcId, Left(fail.reason), commit = true)
          commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)
      }

    case ForwardFailMalformed(fail, to, add) =>
      to match {
        case Origin.Local(id, None) =>
          handleLocalPaymentAfterRestart(PaymentFailed(id, add.paymentHash, Nil))
        case Origin.Local(_, Some(sender)) =>
          sender ! fail
        case Origin.Relayed(originChannelId, originHtlcId, _, _) =>
          val cmd = CMD_FAIL_MALFORMED_HTLC(originHtlcId, fail.onionHash, fail.failureCode, commit = true)
          commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)
      }

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case "ok" => () // ignoring responses from channels
  }

  /**
   * It may happen that we sent a payment and then re-started before the payment completed.
   * When we receive the HTLC fulfill/fail associated to that payment, the payment FSM that generated them doesn't exist
   * anymore so we need to reconcile the database.
   */
  def handleLocalPaymentAfterRestart(paymentResult: PaymentEvent): Unit = paymentResult match {
    case e: PaymentFailed =>
      nodeParams.db.payments.updateOutgoingPayment(e)
      // Since payments can be multi-part, we only emit the payment failed event once all child payments have failed.
      nodeParams.db.payments.getOutgoingPayment(e.id).foreach(p => {
        val payments = nodeParams.db.payments.listOutgoingPayments(p.parentId)
        if (payments.forall(_.status.isInstanceOf[OutgoingPaymentStatus.Failed])) {
          context.system.eventStream.publish(PaymentFailed(p.parentId, e.paymentHash, Nil))
        }
      })
    case e: PaymentSent =>
      nodeParams.db.payments.updateOutgoingPayment(e)
      // Since payments can be multi-part, we only emit the payment sent event once all child payments have settled.
      nodeParams.db.payments.getOutgoingPayment(e.id).foreach(p => {
        val payments = nodeParams.db.payments.listOutgoingPayments(p.parentId)
        if (!payments.exists(p => p.status == OutgoingPaymentStatus.Pending)) {
          val succeeded = payments.collect {
            case OutgoingPayment(id, _, _, _, amount, _, _, _, OutgoingPaymentStatus.Succeeded(_, feesPaid, _, completedAt)) =>
              PaymentSent.PartialPayment(id, amount, feesPaid, ByteVector32.Zeroes, None, completedAt)
          }
          context.system.eventStream.publish(PaymentSent(p.parentId, e.paymentHash, e.paymentPreimage, succeeded))
        }
      })
    case _ =>
  }

  override def mdc(currentMessage: Any): MDC = {
    val paymentHash_opt = currentMessage match {
      case ForwardAdd(add, _) => Some(add.paymentHash)
      case Status.Failure(addFailed: AddHtlcFailed) => Some(addFailed.paymentHash)
      case ForwardFulfill(_, _, add) => Some(add.paymentHash)
      case ForwardFail(_, _, add) => Some(add.paymentHash)
      case ForwardFailMalformed(_, _, add) => Some(add.paymentHash)
      case _ => None
    }
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = paymentHash_opt)
  }

}

object Relayer extends Logging {

  def props(nodeParams: NodeParams, register: ActorRef, commandBuffer: ActorRef, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeParams, register, commandBuffer, paymentHandler)

  type ChannelUpdates = Map[ShortChannelId, OutgoingChannel]
  type NodeChannels = mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId]

  // @formatter:off
  sealed trait ForwardMessage
  case class ForwardAdd(add: UpdateAddHtlc, previousFailures: Seq[AddHtlcFailed] = Seq.empty) extends ForwardMessage
  case class ForwardFulfill(fulfill: UpdateFulfillHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardMessage
  case class ForwardFail(fail: UpdateFailHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardMessage
  case class ForwardFailMalformed(fail: UpdateFailMalformedHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardMessage

  case class UsableBalance(remoteNodeId: PublicKey, shortChannelId: ShortChannelId, canSend: MilliSatoshi, canReceive: MilliSatoshi, isPublic: Boolean)

  /**
   * Get the list of local outgoing channels.
   *
   * @param enabledOnly if true, filter out disabled channels.
   */
  case class GetOutgoingChannels(enabledOnly: Boolean = true)
  case class OutgoingChannel(nextNodeId: PublicKey, channelUpdate: ChannelUpdate, commitments: Commitments) {
    def toUsableBalance: UsableBalance = UsableBalance(
      remoteNodeId = nextNodeId,
      shortChannelId = channelUpdate.shortChannelId,
      canSend = commitments.availableBalanceForSend,
      canReceive = commitments.availableBalanceForReceive,
      isPublic = commitments.announceChannel)
  }
  case class OutgoingChannels(channels: Seq[OutgoingChannel])
  // @formatter:on

}
