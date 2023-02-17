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

import akka.Done
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorContextOps
import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, Props, typed}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Logs, MilliSatoshi, NodeParams}
import grizzled.slf4j.Logging

import scala.concurrent.Promise
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.Random

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
class Relayer(nodeParams: NodeParams, router: ActorRef, register: ActorRef, paymentHandler: ActorRef, triggerer: typed.ActorRef[AsyncPaymentTriggerer.Command], initialized: Option[Promise[Done]] = None) extends Actor with DiagnosticActorLogging {

  import Relayer._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  private val postRestartCleaner = context.actorOf(PostRestartHtlcCleaner.props(nodeParams, register, initialized), "post-restart-htlc-cleaner")
  private val channelRelayer = context.spawn(Behaviors.supervise(ChannelRelayer(nodeParams, register)).onFailure(SupervisorStrategy.resume), "channel-relayer")
  private val nodeRelayer = context.spawn(Behaviors.supervise(NodeRelayer(nodeParams, register, NodeRelay.SimpleOutgoingPaymentFactory(nodeParams, router, register), triggerer)).onFailure(SupervisorStrategy.resume), name = "node-relayer")

  def receive: Receive = {
    case init: PostRestartHtlcCleaner.Init => postRestartCleaner forward init
    case RelayForward(add) =>
      log.debug(s"received forwarding request for htlc #${add.id} from channelId=${add.channelId}")
      IncomingPaymentPacket.decrypt(add, nodeParams.privateKey, nodeParams.features) match {
        case Right(p: IncomingPaymentPacket.FinalPacket) =>
          log.debug(s"forwarding htlc #${add.id} to payment-handler")
          paymentHandler forward p
        case Right(r: IncomingPaymentPacket.ChannelRelayPacket) =>
          channelRelayer ! ChannelRelayer.Relay(r)
        case Right(r: IncomingPaymentPacket.NodeRelayPacket) =>
          if (!nodeParams.enableTrampolinePayment) {
            log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} to nodeId=${r.innerPayload.outgoingNodeId} reason=trampoline disabled")
            PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, CMD_FAIL_HTLC(add.id, Right(RequiredNodeFeatureMissing()), commit = true))
          } else {
            nodeRelayer ! NodeRelayer.Relay(r)
          }
        case Left(badOnion: BadOnion) =>
          log.warning(s"couldn't parse onion: reason=${badOnion.message}")
          val cmdFail = badOnion match {
            case _: InvalidOnionBlinding if add.blinding_opt.isEmpty =>
              // We are the introduction point of a blinded path: we add a non-negligible delay to make it look like it
              // could come from a downstream node.
              val delay = Some(500.millis + Random.nextLong(1500).millis)
              CMD_FAIL_HTLC(add.id, Right(InvalidOnionBlinding(badOnion.onionHash)), delay, commit = true)
            case _ =>
              CMD_FAIL_MALFORMED_HTLC(add.id, badOnion.onionHash, badOnion.code, commit = true)
          }
          log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} reason=malformed onionHash=${badOnion.onionHash} failureCode=${badOnion.code}")
          PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
        case Left(failure) =>
          log.warning(s"rejecting htlc #${add.id} from channelId=${add.channelId} reason=$failure")
          val cmdFail = CMD_FAIL_HTLC(add.id, Right(failure), commit = true)
          PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
      }

    case r: RES_ADD_SETTLED[_, _] => r.origin match {
      case _: Origin.Cold => postRestartCleaner ! r
      case o: Origin.Hot => o.replyTo ! r
    }

    case g: GetOutgoingChannels => channelRelayer ! ChannelRelayer.GetOutgoingChannels(sender(), g)

    case GetChildActors(replyTo) => replyTo ! ChildActors(postRestartCleaner, channelRelayer, nodeRelayer)
  }

  override def mdc(currentMessage: Any): MDC = {
    val paymentHash_opt = currentMessage match {
      case RelayForward(add) => Some(add.paymentHash)
      case addFailed: RES_ADD_FAILED[_] => Some(addFailed.c.paymentHash)
      case addCompleted: RES_ADD_SETTLED[_, _] => Some(addCompleted.htlc.paymentHash)
      case _ => None
    }
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = paymentHash_opt)
  }

}

object Relayer extends Logging {

  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef, paymentHandler: ActorRef, triggerer: typed.ActorRef[AsyncPaymentTriggerer.Command], initialized: Option[Promise[Done]] = None): Props =
    Props(new Relayer(nodeParams, router, register, paymentHandler, triggerer, initialized))

  // @formatter:off
  case class RelayFees(feeBase: MilliSatoshi, feeProportionalMillionths: Long) {
    require(feeBase.toLong >= 0.0, "feeBase must be nonnegative")
    require(feeProportionalMillionths >= 0.0, "feeProportionalMillionths must be nonnegative")
  }

  case class AsyncPaymentsParams(holdTimeoutBlocks: Int, cancelSafetyBeforeTimeout: CltvExpiryDelta)

  case class RelayParams(publicChannelFees: RelayFees,
                         privateChannelFees: RelayFees,
                         minTrampolineFees: RelayFees,
                         enforcementDelay: FiniteDuration,
                         asyncPaymentsParams: AsyncPaymentsParams) {
    def defaultFees(announceChannel: Boolean): RelayFees = {
      if (announceChannel) {
        publicChannelFees
      } else {
        privateChannelFees
      }
    }
  }

  case class RelayForward(add: UpdateAddHtlc)
  case class ChannelBalance(remoteNodeId: PublicKey, shortIds: ShortIds, canSend: MilliSatoshi, canReceive: MilliSatoshi, isPublic: Boolean, isEnabled: Boolean)

  sealed trait OutgoingChannelParams {
    def channelId: ByteVector32
    def channelUpdate: ChannelUpdate
    def prevChannelUpdate: Option[ChannelUpdate]
  }

  /**
   * Get the list of local outgoing channels.
   *
   * @param enabledOnly if true, filter out disabled channels.
   */
  case class GetOutgoingChannels(enabledOnly: Boolean = true)
  case class OutgoingChannel(shortIds: ShortIds, nextNodeId: PublicKey, channelUpdate: ChannelUpdate, prevChannelUpdate: Option[ChannelUpdate], commitments: Commitments) extends OutgoingChannelParams {
    override val channelId: ByteVector32 = commitments.channelId
    def toChannelBalance: ChannelBalance = ChannelBalance(
      remoteNodeId = nextNodeId,
      shortIds = shortIds,
      canSend = commitments.availableBalanceForSend,
      canReceive = commitments.availableBalanceForReceive,
      isPublic = commitments.announceChannel,
      isEnabled = channelUpdate.channelFlags.isEnabled)
  }
  case class OutgoingChannels(channels: Seq[OutgoingChannel])

  // internal classes, used for testing
  private[payment] case class GetChildActors(replyTo: ActorRef)
  private[payment] case class ChildActors(postRestartCleaner: ActorRef, channelRelayer: typed.ActorRef[ChannelRelayer.Command], nodeRelayer: typed.ActorRef[NodeRelayer.Command])
  // @formatter:on

}
