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

import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, PoisonPill, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.send.{MultiPartPaymentLifecycle, PaymentLifecycle}
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.router.{BalanceTooLow, RouteCalculation, RouteNotFound}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, Features, Logs, MilliSatoshi, NodeParams, nodeFee, randomBytes32}

import scala.collection.immutable.Queue

/**
 * Created by t-bast on 10/10/2019.
 */

/**
 * The Node Relayer is used to relay an upstream payment to a downstream remote node (which is not necessarily a direct peer).
 * It aggregates incoming HTLCs (in case multi-part was used upstream) and then forwards the requested amount (using the
 * router to find a route to the remote node and potentially splitting the payment using multi-part).
 */
class NodeRelayer(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends Actor with DiagnosticActorLogging {

  import NodeRelayer._

  override def receive: Receive = main(Map.empty, Map.empty)

  def main(pendingIncoming: Map[ByteVector32, PendingRelay], pendingOutgoing: Map[ByteVector32, PendingResult]): Receive = {
    // We make sure we receive all payment parts before forwarding to the next trampoline node.
    case IncomingPacket.NodeRelayPacket(add, outer, inner, next) => outer.paymentSecret match {
      case None =>
        log.warning("rejecting htlcId={} channelId={}: missing payment secret", add.id, add.channelId)
        rejectHtlc(add.id, add.channelId, add.amountMsat)
      case Some(secret) =>
        pendingOutgoing.get(add.paymentHash) match {
          case Some(outgoing) =>
            log.warning("rejecting htlcId={} channelId={}: already relayed out with id={}", add.id, add.channelId, outgoing.paymentId)
            rejectHtlc(add.id, add.channelId, add.amountMsat)
          case None => pendingIncoming.get(add.paymentHash) match {
            case Some(relay) =>
              if (relay.secret != secret) {
                log.warning("rejecting htlcId={} channelId={}: payment secret doesn't match other HTLCs in the set", add.id, add.channelId)
                rejectHtlc(add.id, add.channelId, add.amountMsat)
              } else {
                relay.handler ! MultiPartPaymentFSM.HtlcPart(outer.totalAmount, add)
                context become main(pendingIncoming + (add.paymentHash -> relay.copy(htlcs = relay.htlcs :+ add)), pendingOutgoing)
              }
            case None =>
              val handler = context.actorOf(MultiPartPaymentFSM.props(nodeParams, add.paymentHash, outer.totalAmount, self))
              handler ! MultiPartPaymentFSM.HtlcPart(outer.totalAmount, add)
              context become main(pendingIncoming + (add.paymentHash -> PendingRelay(Queue(add), secret, inner, next, handler)), pendingOutgoing)
          }
        }
    }

    // We always fail extraneous HTLCs. They are a spec violation from the sender, but harmless in the relay case.
    // By failing them fast (before the payment has reached the final recipient) there's a good chance the sender won't lose any money.
    case MultiPartPaymentFSM.ExtraPaymentReceived(_, p: MultiPartPaymentFSM.HtlcPart, failure) => rejectHtlc(p.htlc.id, p.htlc.channelId, p.amount, failure)

    case MultiPartPaymentFSM.MultiPartPaymentFailed(paymentHash, failure, parts) =>
      log.warning("could not relay payment (paidAmount={} failure={})", parts.map(_.amount).sum, failure)
      Metrics.recordPaymentRelayFailed(failure.getClass.getSimpleName, Tags.RelayType.Trampoline)
      pendingIncoming.get(paymentHash).foreach(_.handler ! PoisonPill)
      parts.collect { case p: MultiPartPaymentFSM.HtlcPart => rejectHtlc(p.htlc.id, p.htlc.channelId, p.amount, Some(failure)) }
      context become main(pendingIncoming - paymentHash, pendingOutgoing)

    case MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts) => pendingIncoming.get(paymentHash) match {
      case Some(PendingRelay(htlcs, _, nextPayload, nextPacket, handler)) =>
        val upstream = Upstream.Trampoline(htlcs)
        handler ! PoisonPill
        validateRelay(nodeParams, upstream, nextPayload) match {
          case Some(failure) =>
            log.warning(s"rejecting trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv} htlcCount=${parts.length} reason=$failure)")
            rejectPayment(upstream, Some(failure))
            context become main(pendingIncoming - paymentHash, pendingOutgoing)
          case None =>
            log.info(s"relaying trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv} htlcCount=${parts.length})")
            val paymentId = relay(paymentHash, upstream, nextPayload, nextPacket)
            context become main(pendingIncoming - paymentHash, pendingOutgoing + (paymentHash -> PendingResult(upstream, nextPayload, paymentId, fulfilledUpstream = false)))
        }
      case None => log.error("could not find pending incoming payment: payment will not be relayed: please investigate")
    }

    case PreimageReceived(paymentHash, paymentPreimage) =>
      log.debug("trampoline payment successfully relayed")
      pendingOutgoing.get(paymentHash).foreach(p => if (!p.fulfilledUpstream) {
        // We want to fulfill upstream as soon as we receive the preimage (even if not all HTLCs have fulfilled downstream).
        fulfillPayment(p.upstream, paymentPreimage)
        context become main(pendingIncoming, pendingOutgoing + (paymentHash -> p.copy(fulfilledUpstream = true)))
      })

    case PaymentSent(id, paymentHash, paymentPreimage, _, _, parts) =>
      // We may have already fulfilled upstream, but we can now emit an accurate relayed event and clean-up resources.
      log.debug("trampoline payment fully resolved downstream (id={})", id)
      pendingOutgoing.get(paymentHash).foreach(p => {
        if (!p.fulfilledUpstream) {
          fulfillPayment(p.upstream, paymentPreimage)
        }
        val incoming = p.upstream.adds.map(add => PaymentRelayed.Part(add.amountMsat, add.channelId))
        val outgoing = parts.map(part => PaymentRelayed.Part(part.amountWithFees, part.toChannelId))
        context.system.eventStream.publish(TrampolinePaymentRelayed(paymentHash, incoming, outgoing))
      })
      context become main(pendingIncoming, pendingOutgoing - paymentHash)

    case PaymentFailed(id, paymentHash, failures, _) =>
      log.debug("trampoline payment failed downstream (id={})", id)
      pendingOutgoing.get(paymentHash).foreach(p => if (!p.fulfilledUpstream) {
        rejectPayment(p.upstream, translateError(nodeParams, failures, p))
      })
      context become main(pendingIncoming, pendingOutgoing - paymentHash)
  }

  def spawnOutgoingPayFSM(cfg: SendPaymentConfig, multiPart: Boolean): ActorRef = {
    if (multiPart) {
      context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, router, register))
    } else {
      context.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
    }
  }

  private def relay(paymentHash: ByteVector32, upstream: Upstream.Trampoline, payloadOut: Onion.NodeRelayPayload, packetOut: OnionRoutingPacket): UUID = {
    val paymentId = UUID.randomUUID()
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, paymentHash, payloadOut.amountToForward, payloadOut.outgoingNodeId, upstream, None, storeInDb = false, publishEvent = false, Nil)
    val routeParams = computeRouteParams(nodeParams, upstream.amountIn, upstream.expiryIn, payloadOut.amountToForward, payloadOut.outgoingCltv)
    // If invoice features are provided in the onion, the sender is asking us to relay to a non-trampoline recipient.
    payloadOut.invoiceFeatures match {
      case Some(features) =>
        val routingHints = payloadOut.invoiceRoutingInfo.map(_.map(_.toSeq).toSeq).getOrElse(Nil)
        payloadOut.paymentSecret match {
          case Some(paymentSecret) if Features(features).hasFeature(Features.BasicMultiPartPayment) =>
            log.debug("relaying trampoline payment to non-trampoline recipient using MPP")
            val payment = SendMultiPartPayment(self, paymentSecret, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
            spawnOutgoingPayFSM(paymentCfg, multiPart = true) ! payment
          case _ =>
            log.debug("relaying trampoline payment to non-trampoline recipient without MPP")
            val finalPayload = Onion.createSinglePartPayload(payloadOut.amountToForward, payloadOut.outgoingCltv, payloadOut.paymentSecret)
            val payment = SendPayment(self, payloadOut.outgoingNodeId, finalPayload, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
            spawnOutgoingPayFSM(paymentCfg, multiPart = false) ! payment
        }
      case None =>
        log.debug("relaying trampoline payment to next trampoline node")
        val payFSM = spawnOutgoingPayFSM(paymentCfg, multiPart = true)
        val paymentSecret = randomBytes32 // we generate a new secret to protect against probing attacks
        val payment = SendMultiPartPayment(self, paymentSecret, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routeParams = Some(routeParams), additionalTlvs = Seq(OnionTlv.TrampolineOnion(packetOut)))
        payFSM ! payment
    }
    paymentId
  }

  private def rejectHtlc(htlcId: Long, channelId: ByteVector32, amount: MilliSatoshi, failure: Option[FailureMessage] = None): Unit = {
    val failureMessage = failure.getOrElse(IncorrectOrUnknownPaymentDetails(amount, nodeParams.currentBlockHeight))
    PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, channelId, CMD_FAIL_HTLC(htlcId, Right(failureMessage), commit = true))
  }

  private def rejectPayment(upstream: Upstream.Trampoline, failure: Option[FailureMessage]): Unit = {
    Metrics.recordPaymentRelayFailed(failure.map(_.getClass.getSimpleName).getOrElse("Unknown"), Tags.RelayType.Trampoline)
    upstream.adds.foreach(add => rejectHtlc(add.id, add.channelId, upstream.amountIn, failure))
  }

  private def fulfillPayment(upstream: Upstream.Trampoline, paymentPreimage: ByteVector32): Unit = upstream.adds.foreach(add => {
    val cmdFulfill = CMD_FULFILL_HTLC(add.id, paymentPreimage, commit = true)
    PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, add.channelId, cmdFulfill)
  })

  override def mdc(currentMessage: Any): MDC = {
    val paymentHash_opt = currentMessage match {
      case m: IncomingPacket.NodeRelayPacket => Some(m.add.paymentHash)
      case m: MultiPartPaymentFSM.MultiPartPaymentFailed => Some(m.paymentHash)
      case m: MultiPartPaymentFSM.MultiPartPaymentSucceeded => Some(m.paymentHash)
      case m: MultiPartPaymentFSM.ExtraPaymentReceived => Some(m.paymentHash)
      case m: PaymentFailed => Some(m.paymentHash)
      case m: PaymentSent => Some(m.paymentHash)
      case _ => None
    }
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = paymentHash_opt)
  }

}

object NodeRelayer {

  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef) = Props(new NodeRelayer(nodeParams, router, register))

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
  case class PendingRelay(htlcs: Queue[UpdateAddHtlc], secret: ByteVector32, nextPayload: Onion.NodeRelayPayload, nextPacket: OnionRoutingPacket, handler: ActorRef)

  /**
   * Once the payment is forwarded, we're waiting for fail/fulfill responses from downstream nodes.
   *
   * @param upstream          complete HTLC set received.
   * @param nextPayload       relay instructions.
   * @param paymentId         id of the outgoing payment.
   * @param fulfilledUpstream true if we already fulfilled the payment upstream.
   */
  case class PendingResult(upstream: Upstream.Trampoline, nextPayload: Onion.NodeRelayPayload, paymentId: UUID, fulfilledUpstream: Boolean)

  private def validateRelay(nodeParams: NodeParams, upstream: Upstream.Trampoline, payloadOut: Onion.NodeRelayPayload): Option[FailureMessage] = {
    val fee = nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, payloadOut.amountToForward)
    if (upstream.amountIn - payloadOut.amountToForward < fee) {
      Some(TrampolineFeeInsufficient)
    } else if (upstream.expiryIn - payloadOut.outgoingCltv < nodeParams.expiryDelta) {
      Some(TrampolineExpiryTooSoon)
    } else {
      None
    }
  }

  /** Compute route params that honor our fee and cltv requirements. */
  private def computeRouteParams(nodeParams: NodeParams, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): RouteParams = {
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
  private def translateError(nodeParams: NodeParams, failures: Seq[PaymentFailure], p: PendingResult): Option[FailureMessage] = {
    val routeNotFound = failures.collectFirst { case f@LocalFailure(_, RouteNotFound) => f }.nonEmpty
    val routingFeeHigh = p.upstream.amountIn - p.nextPayload.amountToForward >= nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, p.nextPayload.amountToForward) * 5
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
        val outgoingNodeFailure = failures.collectFirst { case RemoteFailure(_, e) if e.originNode == p.nextPayload.outgoingNodeId => e.failureMessage }
        val otherNodeFailure = failures.collectFirst { case RemoteFailure(_, e) => e.failureMessage }
        val failure = outgoingNodeFailure.getOrElse(otherNodeFailure.getOrElse(TemporaryNodeFailure))
        Some(failure)
    }
  }

}
