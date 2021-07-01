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

package fr.acinq.eclair.payment.relay

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.{TypedActorContextOps, TypedActorRefOps}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.payment.IncomingPacket.NodeRelayPacket
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.HtlcPart
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.send.{MultiPartPaymentLifecycle, PaymentInitiator, PaymentLifecycle}
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.router.{BalanceTooLow, RouteCalculation, RouteNotFound}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, Features, Logs, MilliSatoshi, NodeParams, UInt64, nodeFee, randomBytes32}

import java.util.UUID
import scala.collection.immutable.Queue

/**
 * It [[NodeRelay]] aggregates incoming HTLCs (in case multi-part was used upstream) and then forwards the requested amount (using the
 * router to find a route to the remote node and potentially splitting the payment using multi-part).
 */
object NodeRelay {

  // @formatter:off
  sealed trait Command
  case class Relay(nodeRelayPacket: IncomingPacket.NodeRelayPacket) extends Command
  case object Stop extends Command
  private case class WrappedMultiPartExtraPaymentReceived(mppExtraReceived: MultiPartPaymentFSM.ExtraPaymentReceived[HtlcPart]) extends Command
  private case class WrappedMultiPartPaymentFailed(mppFailed: MultiPartPaymentFSM.MultiPartPaymentFailed) extends Command
  private case class WrappedMultiPartPaymentSucceeded(mppSucceeded: MultiPartPaymentFSM.MultiPartPaymentSucceeded) extends Command
  private case class WrappedPreimageReceived(preimageReceived: PreimageReceived) extends Command
  private case class WrappedPaymentSent(paymentSent: PaymentSent) extends Command
  private case class WrappedPaymentFailed(paymentFailed: PaymentFailed) extends Command
  // @formatter:on

  trait OutgoingPaymentFactory {
    def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], cfg: SendPaymentConfig, multiPart: Boolean): ActorRef
  }

  case class SimpleOutgoingPaymentFactory(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends OutgoingPaymentFactory {
    val paymentFactory = PaymentInitiator.SimplePaymentFactory(nodeParams, router, register)

    override def spawnOutgoingPayFSM(context: ActorContext[Command], cfg: SendPaymentConfig, multiPart: Boolean): ActorRef = {
      if (multiPart) {
        context.toClassic.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, router, paymentFactory))
      } else {
        context.toClassic.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
      }
    }
  }

  def apply(nodeParams: NodeParams,
            parent: akka.actor.typed.ActorRef[NodeRelayer.Command],
            register: ActorRef,
            relayId: UUID,
            nodeRelayPacket: NodeRelayPacket,
            outgoingPaymentFactory: OutgoingPaymentFactory): Behavior[Command] =
    Behaviors.setup { context =>
      val paymentHash = nodeRelayPacket.add.paymentHash
      val totalAmountIn = nodeRelayPacket.outerPayload.totalAmount
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a node relay, we use the same identifier for the whole relay itself, and the outgoing payment
        paymentHash_opt = Some(paymentHash))) {
        context.log.info("relaying payment relayId={}", relayId)
        val mppFsmAdapters = {
          context.messageAdapter[MultiPartPaymentFSM.ExtraPaymentReceived[HtlcPart]](WrappedMultiPartExtraPaymentReceived)
          context.messageAdapter[MultiPartPaymentFSM.MultiPartPaymentFailed](WrappedMultiPartPaymentFailed)
          context.messageAdapter[MultiPartPaymentFSM.MultiPartPaymentSucceeded](WrappedMultiPartPaymentSucceeded)
        }.toClassic
        val incomingPaymentHandler = context.actorOf(MultiPartPaymentFSM.props(nodeParams, paymentHash, totalAmountIn, mppFsmAdapters))
        new NodeRelay(nodeParams, parent, register, relayId, paymentHash, nodeRelayPacket.outerPayload.paymentSecret, context, outgoingPaymentFactory)
          .receiving(Queue.empty, nodeRelayPacket.innerPayload, nodeRelayPacket.nextPacket, incomingPaymentHandler)
      }
    }

  def validateRelay(nodeParams: NodeParams, upstream: Upstream.Trampoline, payloadOut: Onion.NodeRelayPayload): Option[FailureMessage] = {
    val fee = nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, payloadOut.amountToForward)
    if (upstream.amountIn - payloadOut.amountToForward < fee) {
      Some(TrampolineFeeInsufficient)
    } else if (upstream.expiryIn - payloadOut.outgoingCltv < nodeParams.expiryDelta) {
      Some(TrampolineExpiryTooSoon)
    } else if (payloadOut.outgoingCltv <= CltvExpiry(nodeParams.currentBlockHeight)) {
      Some(TrampolineExpiryTooSoon)
    } else if (payloadOut.invoiceFeatures.isDefined && payloadOut.paymentSecret.isEmpty) {
      Some(InvalidOnionPayload(UInt64(8), 0)) // payment secret field is missing
    } else if (payloadOut.amountToForward <= MilliSatoshi(0)) {
      Some(InvalidOnionPayload(UInt64(2), 0))
    } else {
      None
    }
  }

  /** Compute route params that honor our fee and cltv requirements. */
  def computeRouteParams(nodeParams: NodeParams, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): RouteParams = {
    val routeMaxCltv = expiryIn - expiryOut
    val routeMaxFee = amountIn - amountOut
    RouteCalculation.getDefaultRouteParams(nodeParams.routerConf).copy(
      maxFeeBase = routeMaxFee,
      routeMaxCltv = routeMaxCltv,
      maxFeePct = 0, // we disable percent-based max fee calculation, we're only interested in collecting our node fee
      includeLocalChannelCost = true,
    )
  }

  /**
   * This helper method translates relaying errors (returned by the downstream nodes) to a BOLT 4 standard error that we
   * should return upstream.
   */
  def translateError(nodeParams: NodeParams, failures: Seq[PaymentFailure], upstream: Upstream.Trampoline, nextPayload: Onion.NodeRelayPayload): Option[FailureMessage] = {
    val routeNotFound = failures.collectFirst { case f@LocalFailure(_, RouteNotFound) => f }.nonEmpty
    val routingFeeHigh = upstream.amountIn - nextPayload.amountToForward >= nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, nextPayload.amountToForward) * 5
    failures match {
      case Nil => None
      case LocalFailure(_, BalanceTooLow) :: Nil if routingFeeHigh =>
        // We have direct channels to the target node, but not enough outgoing liquidity to use those channels.
        // The routing fee proposed by the sender was high enough to find alternative, indirect routes, but didn't yield
        // any result so we tell them that we don't have enough outgoing liquidity at the moment.
        Some(TemporaryNodeFailure)
      case LocalFailure(_, BalanceTooLow) :: Nil => Some(TrampolineFeeInsufficient) // a higher fee/cltv may find alternative, indirect routes
      case _ if routeNotFound => Some(TrampolineFeeInsufficient) // if we couldn't find routes, it's likely that the fee/cltv was insufficient
      case _ =>
        // Otherwise, we try to find a downstream error that we could decrypt.
        val outgoingNodeFailure = failures.collectFirst { case RemoteFailure(_, e) if e.originNode == nextPayload.outgoingNodeId => e.failureMessage }
        val otherNodeFailure = failures.collectFirst { case RemoteFailure(_, e) => e.failureMessage }
        val failure = outgoingNodeFailure.getOrElse(otherNodeFailure.getOrElse(TemporaryNodeFailure))
        Some(failure)
    }
  }

}

/**
 * see https://doc.akka.io/docs/akka/current/typed/style-guide.html#passing-around-too-many-parameters
 */
class NodeRelay private(nodeParams: NodeParams,
                        parent: akka.actor.typed.ActorRef[NodeRelayer.Command],
                        register: ActorRef,
                        relayId: UUID,
                        paymentHash: ByteVector32,
                        paymentSecret: ByteVector32,
                        context: ActorContext[NodeRelay.Command],
                        outgoingPaymentFactory: NodeRelay.OutgoingPaymentFactory) {

  import NodeRelay._

  /**
   * We start by aggregating an incoming HTLC set. Once we received the whole set, we will compute a route to the next
   * trampoline node and forward the payment.
   *
   * @param htlcs       received incoming HTLCs for this set.
   * @param nextPayload relay instructions (should be identical across HTLCs in this set).
   * @param nextPacket  trampoline onion to relay to the next trampoline node.
   * @param handler     actor handling the aggregation of the incoming HTLC set.
   */
  private def receiving(htlcs: Queue[UpdateAddHtlc], nextPayload: Onion.NodeRelayPayload, nextPacket: OnionRoutingPacket, handler: ActorRef): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Relay(IncomingPacket.NodeRelayPacket(add, outer, _, _)) =>
        require(outer.paymentSecret == paymentSecret, "payment secret mismatch")
        context.log.debug("forwarding incoming htlc #{} from channel {} to the payment FSM", add.id, add.channelId)
        handler ! MultiPartPaymentFSM.HtlcPart(outer.totalAmount, add)
        receiving(htlcs :+ add, nextPayload, nextPacket, handler)
      case WrappedMultiPartPaymentFailed(MultiPartPaymentFSM.MultiPartPaymentFailed(_, failure, parts)) =>
        context.log.warn("could not complete incoming multi-part payment (parts={} paidAmount={} failure={})", parts.size, parts.map(_.amount).sum, failure)
        Metrics.recordPaymentRelayFailed(failure.getClass.getSimpleName, Tags.RelayType.Trampoline)
        parts.collect { case p: MultiPartPaymentFSM.HtlcPart => rejectHtlc(p.htlc.id, p.htlc.channelId, p.amount, Some(failure)) }
        stopping()
      case WrappedMultiPartPaymentSucceeded(MultiPartPaymentFSM.MultiPartPaymentSucceeded(_, parts)) =>
        context.log.info("completed incoming multi-part payment with parts={} paidAmount={}", parts.size, parts.map(_.amount).sum)
        val upstream = Upstream.Trampoline(htlcs)
        validateRelay(nodeParams, upstream, nextPayload) match {
          case Some(failure) =>
            context.log.warn(s"rejecting trampoline payment reason=$failure")
            rejectPayment(upstream, Some(failure))
            stopping()
          case None =>
            doSend(upstream, nextPayload, nextPacket)
        }
    }

  private def doSend(upstream: Upstream.Trampoline, nextPayload: Onion.NodeRelayPayload, nextPacket: OnionRoutingPacket): Behavior[Command] = {
    context.log.info(s"relaying trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv})")
    relay(upstream, nextPayload, nextPacket)
    sending(upstream, nextPayload, fulfilledUpstream = false)
  }

  /**
   * Once the payment is forwarded, we're waiting for fail/fulfill responses from downstream nodes.
   *
   * @param upstream          complete HTLC set received.
   * @param nextPayload       relay instructions.
   * @param fulfilledUpstream true if we already fulfilled the payment upstream.
   */
  private def sending(upstream: Upstream.Trampoline, nextPayload: Onion.NodeRelayPayload, fulfilledUpstream: Boolean): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      rejectExtraHtlcPartialFunction orElse {
        // this is the fulfill that arrives from downstream channels
        case WrappedPreimageReceived(PreimageReceived(_, paymentPreimage)) =>
          if (!fulfilledUpstream) {
            // We want to fulfill upstream as soon as we receive the preimage (even if not all HTLCs have fulfilled downstream).
            context.log.debug("got preimage from downstream")
            fulfillPayment(upstream, paymentPreimage)
            sending(upstream, nextPayload, fulfilledUpstream = true)
          } else {
            // we don't want to fulfill multiple times
            Behaviors.same
          }
        case WrappedPaymentSent(paymentSent) =>
          context.log.debug("trampoline payment fully resolved downstream")
          success(upstream, fulfilledUpstream, paymentSent)
          stopping()
        case WrappedPaymentFailed(PaymentFailed(_, _, failures, _)) =>
          context.log.debug(s"trampoline payment failed downstream")
          if (!fulfilledUpstream) {
            rejectPayment(upstream, translateError(nodeParams, failures, upstream, nextPayload))
          }
          stopping()
      }
    }

  /**
   * Once the downstream payment is settled (fulfilled or failed), we reject new upstream payments while we wait for our parent to stop us.
   */
  private def stopping(): Behavior[Command] = {
    parent ! NodeRelayer.RelayComplete(context.self, paymentHash, paymentSecret)
    Behaviors.receiveMessagePartial {
      rejectExtraHtlcPartialFunction orElse {
        case Stop => Behaviors.stopped
      }
    }
  }

  private val payFsmAdapters = {
    context.messageAdapter[PreimageReceived](WrappedPreimageReceived)
    context.messageAdapter[PaymentSent](WrappedPaymentSent)
    context.messageAdapter[PaymentFailed](WrappedPaymentFailed)
  }.toClassic

  private def relay(upstream: Upstream.Trampoline, payloadOut: Onion.NodeRelayPayload, packetOut: OnionRoutingPacket): ActorRef = {
    val paymentCfg = SendPaymentConfig(relayId, relayId, None, paymentHash, payloadOut.amountToForward, payloadOut.outgoingNodeId, upstream, None, storeInDb = false, publishEvent = false, Nil)
    val routeParams = computeRouteParams(nodeParams, upstream.amountIn, upstream.expiryIn, payloadOut.amountToForward, payloadOut.outgoingCltv)
    // If invoice features are provided in the onion, the sender is asking us to relay to a non-trampoline recipient.
    val payFSM = payloadOut.invoiceFeatures match {
      case Some(features) =>
        val routingHints = payloadOut.invoiceRoutingInfo.map(_.map(_.toSeq).toSeq).getOrElse(Nil)
        val paymentSecret = payloadOut.paymentSecret.get // NB: we've verified that there was a payment secret in validateRelay
        if (Features(features).hasFeature(Features.BasicMultiPartPayment)) {
          context.log.debug("sending the payment to non-trampoline recipient using MPP")
          val payment = SendMultiPartPayment(payFsmAdapters, paymentSecret, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
          val payFSM = outgoingPaymentFactory.spawnOutgoingPayFSM(context, paymentCfg, multiPart = true)
          payFSM ! payment
          payFSM
        } else {
          context.log.debug("sending the payment to non-trampoline recipient without MPP")
          val finalPayload = Onion.createSinglePartPayload(payloadOut.amountToForward, payloadOut.outgoingCltv, paymentSecret)
          val payment = SendPayment(payFsmAdapters, payloadOut.outgoingNodeId, finalPayload, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
          val payFSM = outgoingPaymentFactory.spawnOutgoingPayFSM(context, paymentCfg, multiPart = false)
          payFSM ! payment
          payFSM
        }
      case None =>
        context.log.debug("sending the payment to the next trampoline node")
        val payFSM = outgoingPaymentFactory.spawnOutgoingPayFSM(context, paymentCfg, multiPart = true)
        val paymentSecret = randomBytes32() // we generate a new secret to protect against probing attacks
        val payment = SendMultiPartPayment(payFsmAdapters, paymentSecret, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routeParams = Some(routeParams), additionalTlvs = Seq(OnionTlv.TrampolineOnion(packetOut)))
        payFSM ! payment
        payFSM
    }
    payFSM
  }

  private def rejectExtraHtlcPartialFunction: PartialFunction[Command, Behavior[Command]] = {
    case Relay(nodeRelayPacket) =>
      rejectExtraHtlc(nodeRelayPacket.add)
      Behaviors.same
    // NB: this message would be sent from the payment FSM which we stopped before going to this state, but all this is asynchronous.
    // We always fail extraneous HTLCs. They are a spec violation from the sender, but harmless in the relay case.
    // By failing them fast (before the payment has reached the final recipient) there's a good chance the sender won't lose any money.
    // We don't expect to relay pay-to-open payments.
    case WrappedMultiPartExtraPaymentReceived(extraPaymentReceived) =>
      rejectExtraHtlc(extraPaymentReceived.payment.htlc)
      Behaviors.same
  }

  private def rejectExtraHtlc(add: UpdateAddHtlc): Unit = {
    context.log.warn("rejecting extra htlc #{} from channel {}", add.id, add.channelId)
    rejectHtlc(add.id, add.channelId, add.amountMsat)
  }

  private def rejectHtlc(htlcId: Long, channelId: ByteVector32, amount: MilliSatoshi, failure: Option[FailureMessage] = None): Unit = {
    val failureMessage = failure.getOrElse(IncorrectOrUnknownPaymentDetails(amount, nodeParams.currentBlockHeight))
    val cmd = CMD_FAIL_HTLC(htlcId, Right(failureMessage), commit = true)
    PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd)
  }

  private def rejectPayment(upstream: Upstream.Trampoline, failure: Option[FailureMessage]): Unit = {
    Metrics.recordPaymentRelayFailed(failure.map(_.getClass.getSimpleName).getOrElse("Unknown"), Tags.RelayType.Trampoline)
    upstream.adds.foreach(add => rejectHtlc(add.id, add.channelId, upstream.amountIn, failure))
  }

  private def fulfillPayment(upstream: Upstream.Trampoline, paymentPreimage: ByteVector32): Unit = upstream.adds.foreach(add => {
    val cmd = CMD_FULFILL_HTLC(add.id, paymentPreimage, commit = true)
    PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmd)
  })

  private def success(upstream: Upstream.Trampoline, fulfilledUpstream: Boolean, paymentSent: PaymentSent): Unit = {
    // We may have already fulfilled upstream, but we can now emit an accurate relayed event and clean-up resources.
    if (!fulfilledUpstream) {
      fulfillPayment(upstream, paymentSent.paymentPreimage)
    }
    val incoming = upstream.adds.map(add => PaymentRelayed.Part(add.amountMsat, add.channelId))
    val outgoing = paymentSent.parts.map(part => PaymentRelayed.Part(part.amountWithFees, part.toChannelId))
    context.system.eventStream ! EventStream.Publish(TrampolinePaymentRelayed(paymentHash, incoming, outgoing, paymentSent.recipientNodeId, paymentSent.recipientAmount))
  }

}
