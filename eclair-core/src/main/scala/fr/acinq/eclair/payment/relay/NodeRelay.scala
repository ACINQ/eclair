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
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.HtlcPart
import fr.acinq.eclair.payment.relay.NodeRelay.FsmFactory
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.send.{MultiPartPaymentLifecycle, PaymentLifecycle}
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.router.{BalanceTooLow, RouteCalculation, RouteNotFound}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, Features, Logs, MilliSatoshi, NodeParams, nodeFee, randomBytes32}

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

  def apply(nodeParams: NodeParams, parent: akka.actor.typed.ActorRef[NodeRelayer.Command], router: ActorRef, register: ActorRef, relayId: UUID, paymentHash: ByteVector32, fsmFactory: FsmFactory = new FsmFactory): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a node relay, we use the same identifier for the whole relay itself, and the outgoing payment
        paymentHash_opt = Some(paymentHash))) {
        new NodeRelay(nodeParams, parent, router, register, relayId, paymentHash, context, fsmFactory)()
      }
    }

  /**
   * This is supposed to be overridden in tests
   */
  class FsmFactory {
    def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], nodeParams: NodeParams, router: ActorRef, register: ActorRef, cfg: SendPaymentConfig, multiPart: Boolean): ActorRef = {
      if (multiPart) {
        context.toClassic.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, router, register))
      } else {
        context.toClassic.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
      }
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
    } else {
      None
    }
  }

  /** Compute route params that honor our fee and cltv requirements. */
  def computeRouteParams(nodeParams: NodeParams, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): RouteParams = {
    val routeMaxCltv = expiryIn - expiryOut - nodeParams.expiryDelta
    val routeMaxFee = amountIn - amountOut - nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, amountOut)
    RouteCalculation.getDefaultRouteParams(nodeParams.routerConf).copy(
      maxFeeBase = routeMaxFee,
      routeMaxCltv = routeMaxCltv,
      maxFeePct = 0 // we disable percent-based max fee calculation, we're only interested in collecting our node fee
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
                        router: ActorRef,
                        register: ActorRef,
                        relayId: UUID,
                        paymentHash: ByteVector32,
                        context: ActorContext[NodeRelay.Command],
                        fsmFactory: FsmFactory) {

  import NodeRelay._

  private val mppFsmAdapters = {
    context.messageAdapter[MultiPartPaymentFSM.ExtraPaymentReceived[HtlcPart]](WrappedMultiPartExtraPaymentReceived)
    context.messageAdapter[MultiPartPaymentFSM.MultiPartPaymentFailed](WrappedMultiPartPaymentFailed)
    context.messageAdapter[MultiPartPaymentFSM.MultiPartPaymentSucceeded](WrappedMultiPartPaymentSucceeded)
  }.toClassic
  private val payFsmAdapters = {
    context.messageAdapter[PreimageReceived](WrappedPreimageReceived)
    context.messageAdapter[PaymentSent](WrappedPaymentSent)
    context.messageAdapter[PaymentFailed](WrappedPaymentFailed)
  }.toClassic

  def apply(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      // We make sure we receive all payment parts before forwarding to the next trampoline node.
      case Relay(IncomingPacket.NodeRelayPacket(add, outer, inner, next)) => outer.paymentSecret match {
        case None =>
          // TODO: @pm: maybe those checks should be done later in the flow (by the mpp FSM?)
          context.log.warn("rejecting htlcId={}: missing payment secret", add.id)
          rejectHtlc(add.id, add.channelId, add.amountMsat)
          stopping()
        case Some(secret) =>
          import akka.actor.typed.scaladsl.adapter._
          context.log.info("relaying payment relayId={}", relayId)
          val mppFsm = context.actorOf(MultiPartPaymentFSM.props(nodeParams, add.paymentHash, outer.totalAmount, mppFsmAdapters))
          context.log.debug("forwarding incoming htlc to the payment FSM")
          mppFsm ! MultiPartPaymentFSM.HtlcPart(outer.totalAmount, add)
          receiving(Queue(add), secret, inner, next, mppFsm)
      }
    }

  /**
   * We start by aggregating an incoming HTLC set. Once we received the whole set, we will compute a route to the next
   * trampoline node and forward the payment.
   *
   * @param htlcs       received incoming HTLCs for this set.
   * @param secret      all incoming HTLCs in this set must have the same secret to protect against probing / fee theft.
   * @param nextPayload relay instructions (should be identical across HTLCs in this set).
   * @param nextPacket  trampoline onion to relay to the next trampoline node.
   * @param handler     actor handling the aggregation of the incoming HTLC set.
   */
  private def receiving(htlcs: Queue[UpdateAddHtlc], secret: ByteVector32, nextPayload: Onion.NodeRelayPayload, nextPacket: OnionRoutingPacket, handler: ActorRef): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case Relay(IncomingPacket.NodeRelayPacket(add, outer, _, _)) => outer.paymentSecret match {
        case None =>
          context.log.warn("rejecting htlcId={}: missing payment secret", add.id)
          rejectHtlc(add.id, add.channelId, add.amountMsat)
          Behaviors.same
        case Some(incomingSecret) if incomingSecret != secret =>
          context.log.warn("rejecting htlcId={}: payment secret doesn't match other HTLCs in the set", add.id)
          rejectHtlc(add.id, add.channelId, add.amountMsat)
          Behaviors.same
        case Some(incomingSecret) if incomingSecret == secret =>
          context.log.debug("forwarding incoming htlc to the payment FSM")
          handler ! MultiPartPaymentFSM.HtlcPart(outer.totalAmount, add)
          receiving(htlcs :+ add, secret, nextPayload, nextPacket, handler)
      }
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
    parent ! NodeRelayer.RelayComplete(context.self, paymentHash)
    Behaviors.receiveMessagePartial {
      rejectExtraHtlcPartialFunction orElse {
        case Stop => Behaviors.stopped
      }
    }
  }

  private def relay(upstream: Upstream.Trampoline, payloadOut: Onion.NodeRelayPayload, packetOut: OnionRoutingPacket): ActorRef = {
    val paymentCfg = SendPaymentConfig(relayId, relayId, None, paymentHash, payloadOut.amountToForward, payloadOut.outgoingNodeId, upstream, None, storeInDb = false, publishEvent = false, Nil)
    val routeParams = computeRouteParams(nodeParams, upstream.amountIn, upstream.expiryIn, payloadOut.amountToForward, payloadOut.outgoingCltv)
    // If invoice features are provided in the onion, the sender is asking us to relay to a non-trampoline recipient.
    val payFSM = payloadOut.invoiceFeatures match {
      case Some(features) =>
        val routingHints = payloadOut.invoiceRoutingInfo.map(_.map(_.toSeq).toSeq).getOrElse(Nil)
        payloadOut.paymentSecret match {
          case Some(paymentSecret) if Features(features).hasFeature(Features.BasicMultiPartPayment) =>
            context.log.debug("sending the payment to non-trampoline recipient using MPP")
            val payment = SendMultiPartPayment(payFsmAdapters, paymentSecret, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
            val payFSM = fsmFactory.spawnOutgoingPayFSM(context, nodeParams, router, register, paymentCfg, multiPart = true)
            payFSM ! payment
            payFSM
          case _ =>
            context.log.debug("sending the payment to non-trampoline recipient without MPP")
            val finalPayload = Onion.createSinglePartPayload(payloadOut.amountToForward, payloadOut.outgoingCltv, payloadOut.paymentSecret)
            val payment = SendPayment(payFsmAdapters, payloadOut.outgoingNodeId, finalPayload, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
            val payFSM = fsmFactory.spawnOutgoingPayFSM(context, nodeParams, router, register, paymentCfg, multiPart = false)
            payFSM ! payment
            payFSM
        }
      case None =>
        context.log.debug("sending the payment to the next trampoline node")
        val payFSM = fsmFactory.spawnOutgoingPayFSM(context, nodeParams, router, register, paymentCfg, multiPart = true)
        val paymentSecret = randomBytes32 // we generate a new secret to protect against probing attacks
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
    context.log.warn("rejecting extra htlcId={}", add.id)
    rejectHtlc(add.id, add.channelId, add.amountMsat)
  }

  private def rejectHtlc(htlcId: Long, channelId: ByteVector32, amount: MilliSatoshi, failure: Option[FailureMessage] = None): Unit = {
    val failureMessage = failure.getOrElse(IncorrectOrUnknownPaymentDetails(amount, nodeParams.currentBlockHeight))
    val cmd = CMD_FAIL_HTLC(htlcId, Right(failureMessage), commit = true)
    PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, channelId, cmd)
  }

  private def rejectPayment(upstream: Upstream.Trampoline, failure: Option[FailureMessage]): Unit = {
    Metrics.recordPaymentRelayFailed(failure.map(_.getClass.getSimpleName).getOrElse("Unknown"), Tags.RelayType.Trampoline)
    upstream.adds.foreach(add => rejectHtlc(add.id, add.channelId, upstream.amountIn, failure))
  }

  private def fulfillPayment(upstream: Upstream.Trampoline, paymentPreimage: ByteVector32): Unit = upstream.adds.foreach(add => {
    val cmd = CMD_FULFILL_HTLC(add.id, paymentPreimage, commit = true)
    PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, add.channelId, cmd)
  })

  private def success(upstream: Upstream.Trampoline, fulfilledUpstream: Boolean, paymentSent: PaymentSent): Unit = {
    // We may have already fulfilled upstream, but we can now emit an accurate relayed event and clean-up resources.
    if (!fulfilledUpstream) {
      fulfillPayment(upstream, paymentSent.paymentPreimage)
    }
    val incoming = upstream.adds.map(add => PaymentRelayed.Part(add.amountMsat, add.channelId))
    val outgoing = paymentSent.parts.map(part => PaymentRelayed.Part(part.amountWithFees, part.toChannelId))
    context.system.eventStream ! EventStream.Publish(TrampolinePaymentRelayed(paymentHash, incoming, outgoing))
  }

}
