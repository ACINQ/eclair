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

import akka.Done
import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, Props, Status}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Logs, MilliSatoshi, NodeParams, ShortChannelId}
import grizzled.slf4j.Logging

import scala.collection.mutable
import scala.concurrent.Promise

// @formatter:off
sealed trait Origin
object Origin {
  /** Our node is the origin of the payment. */
  case class Local(id: UUID, sender: Option[ActorRef]) extends Origin // we don't persist reference to local actors
  /** Our node forwarded a single incoming HTLC to an outgoing channel. */
  case class Relayed(originChannelId: ByteVector32, originHtlcId: Long, amountIn: MilliSatoshi, amountOut: MilliSatoshi) extends Origin
  /**
   * Our node forwarded an incoming HTLC set to a remote outgoing node (potentially producing multiple downstream HTLCs).
   *
   * @param origins       origin channelIds and htlcIds.
   * @param paymentSender actor sending the outgoing HTLC (if we haven't restarted and lost the reference).
   */
  case class TrampolineRelayed(origins: List[(ByteVector32, Long)], paymentSender: Option[ActorRef]) extends Origin
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
class Relayer(nodeParams: NodeParams, router: ActorRef, register: ActorRef, commandBuffer: ActorRef, paymentHandler: ActorRef, initialized: Option[Promise[Done]] = None) extends Actor with DiagnosticActorLogging {

  import Relayer._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  private val postRestartCleaner = context.actorOf(PostRestartHtlcCleaner.props(nodeParams, commandBuffer, initialized))
  private val channelRelayer = context.actorOf(ChannelRelayer.props(nodeParams, self, register, commandBuffer))
  private val nodeRelayer = context.actorOf(NodeRelayer.props(nodeParams, router, commandBuffer, register))

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

    case AvailableBalanceChanged(_, _, shortChannelId, commitments) =>
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
      IncomingPacket.decrypt(add, nodeParams.privateKey, nodeParams.features) match {
        case Right(p: IncomingPacket.FinalPacket) =>
          log.debug(s"forwarding htlc #${add.id} to payment-handler")
          paymentHandler forward p
        case Right(r: IncomingPacket.ChannelRelayPacket) =>
          channelRelayer forward ChannelRelayer.RelayHtlc(r, previousFailures, channelUpdates, node2channels)
        case Right(r: IncomingPacket.NodeRelayPacket) =>
          if (!nodeParams.enableTrampolinePayment) {
            log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} to nodeId=${r.innerPayload.outgoingNodeId} reason=trampoline disabled")
            commandBuffer ! CommandBuffer.CommandSend(add.channelId, CMD_FAIL_HTLC(add.id, Right(RequiredNodeFeatureMissing), commit = true))
          } else {
            nodeRelayer forward r
          }
        case Left(badOnion: BadOnion) =>
          log.warning(s"couldn't parse onion: reason=${badOnion.message}")
          val cmdFail = CMD_FAIL_MALFORMED_HTLC(add.id, badOnion.onionHash, badOnion.code, commit = true)
          log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} reason=malformed onionHash=${cmdFail.onionHash} failureCode=${cmdFail.failureCode}")
          commandBuffer ! CommandBuffer.CommandSend(add.channelId, cmdFail)
        case Left(failure) =>
          log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} reason=$failure")
          val cmdFail = CMD_FAIL_HTLC(add.id, Right(failure), commit = true)
          commandBuffer ! CommandBuffer.CommandSend(add.channelId, cmdFail)
      }

    case Status.Failure(addFailed: AddHtlcFailed) => addFailed.origin match {
      case Origin.Local(id, None) => log.error(s"received unexpected add failed with no sender (paymentId=$id)")
      case Origin.Local(_, Some(sender)) => sender ! Status.Failure(addFailed)
      case _: Origin.Relayed => channelRelayer forward Status.Failure(addFailed)
      case Origin.TrampolineRelayed(htlcs, None) => log.error(s"received unexpected add failed with no sender (upstream=${htlcs.mkString(", ")}")
      case Origin.TrampolineRelayed(_, Some(paymentSender)) => paymentSender ! Status.Failure(addFailed)
    }

    case ff: ForwardFulfill => ff.to match {
      case Origin.Local(_, None) => postRestartCleaner forward ff
      case Origin.Local(_, Some(sender)) => sender ! ff
      case Origin.Relayed(originChannelId, originHtlcId, amountIn, amountOut) =>
        val cmd = CMD_FULFILL_HTLC(originHtlcId, ff.paymentPreimage, commit = true)
        commandBuffer ! CommandBuffer.CommandSend(originChannelId, cmd)
        context.system.eventStream.publish(ChannelPaymentRelayed(amountIn, amountOut, ff.htlc.paymentHash, originChannelId, ff.htlc.channelId))
      case Origin.TrampolineRelayed(_, None) => postRestartCleaner forward ff
      case Origin.TrampolineRelayed(_, Some(_)) => nodeRelayer forward ff
    }

    case ff: ForwardFail => ff.to match {
      case Origin.Local(_, None) => postRestartCleaner forward ff
      case Origin.Local(_, Some(sender)) => sender ! ff
      case Origin.Relayed(originChannelId, originHtlcId, _, _) =>
        Metrics.recordPaymentRelayFailed(Tags.FailureType.Remote, Tags.RelayType.Channel)
        val cmd = ff match {
          case ForwardRemoteFail(fail, _, _) => CMD_FAIL_HTLC(originHtlcId, Left(fail.reason), commit = true)
          case ForwardRemoteFailMalformed(fail, _, _) => CMD_FAIL_MALFORMED_HTLC(originHtlcId, fail.onionHash, fail.failureCode, commit = true)
          case _: ForwardOnChainFail => CMD_FAIL_HTLC(originHtlcId, Right(PermanentChannelFailure), commit = true)
        }
        commandBuffer ! CommandBuffer.CommandSend(originChannelId, cmd)
      case Origin.TrampolineRelayed(_, None) => postRestartCleaner forward ff
      case Origin.TrampolineRelayed(_, Some(paymentSender)) => paymentSender ! ff
    }

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case ChannelCommandResponse.Ok => () // ignoring responses from channels
  }

  override def mdc(currentMessage: Any): MDC = {
    val paymentHash_opt = currentMessage match {
      case ForwardAdd(add, _) => Some(add.paymentHash)
      case Status.Failure(addFailed: AddHtlcFailed) => Some(addFailed.paymentHash)
      case ff: ForwardFulfill => Some(ff.htlc.paymentHash)
      case ff: ForwardFail => Some(ff.htlc.paymentHash)
      case _ => None
    }
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = paymentHash_opt)
  }

}

object Relayer extends Logging {

  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef, commandBuffer: ActorRef, paymentHandler: ActorRef, initialized: Option[Promise[Done]] = None) =
    Props(new Relayer(nodeParams, router, register, commandBuffer, paymentHandler, initialized))

  type ChannelUpdates = Map[ShortChannelId, OutgoingChannel]
  type NodeChannels =  mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId]

  // @formatter:off
  sealed trait ForwardMessage
  case class ForwardAdd(add: UpdateAddHtlc, previousFailures: Seq[AddHtlcFailed] = Seq.empty) extends ForwardMessage
  sealed trait ForwardFulfill extends ForwardMessage { val paymentPreimage: ByteVector32; val to: Origin; val htlc: UpdateAddHtlc }
  case class ForwardRemoteFulfill(fulfill: UpdateFulfillHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardFulfill { override val paymentPreimage = fulfill.paymentPreimage }
  case class ForwardOnChainFulfill(paymentPreimage: ByteVector32, to: Origin, htlc: UpdateAddHtlc) extends ForwardFulfill
  sealed trait ForwardFail extends ForwardMessage { val to: Origin; val htlc: UpdateAddHtlc }
  case class ForwardRemoteFail(fail: UpdateFailHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardFail
  case class ForwardRemoteFailMalformed(fail: UpdateFailMalformedHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardFail
  case class ForwardOnChainFail(cause: ChannelException, to: Origin, htlc: UpdateAddHtlc) extends ForwardFail

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
