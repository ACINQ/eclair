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
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Upstream}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.payment.send.{MultiPartPaymentLifecycle, PaymentLifecycle}
import fr.acinq.eclair.payment.{IncomingPacket, PaymentFailed, PaymentSent, TrampolinePaymentRelayed}
import fr.acinq.eclair.router.{RouteParams, Router}
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
class NodeRelayer(nodeParams: NodeParams, relayer: ActorRef, router: ActorRef, commandBuffer: ActorRef, register: ActorRef) extends Actor with DiagnosticActorLogging {

  // TODO: @t-bast: if fees/cltv insufficient (could not find route) send special error (sender should retry with higher fees/cltv)?
  // TODO: @t-bast: add Kamon counters to monitor the size of pendingIncoming/Outgoing?

  import NodeRelayer._

  override def receive: Receive = main(Map.empty, Map.empty)

  def main(pendingIncoming: Map[ByteVector32, PendingRelay], pendingOutgoing: Map[UUID, PendingResult]): Receive = {
    // We make sure we receive all payment parts before forwarding to the next trampoline node.
    case IncomingPacket.NodeRelayPacket(add, outer, inner, next) => outer.paymentSecret match {
      case None =>
        log.warning(s"rejecting htlcId=${add.id} channelId=${add.channelId}: missing payment secret")
        rejectHtlc(add.id, add.channelId, add.amountMsat)
      case Some(secret) => pendingIncoming.get(add.paymentHash) match {
        case Some(relay) =>
          if (relay.secret != secret) {
            log.warning(s"rejecting htlcId=${add.id} channelId=${add.channelId}: payment secret doesn't match other HTLCs in the set")
            rejectHtlc(add.id, add.channelId, add.amountMsat)
          } else {
            relay.handler ! MultiPartPaymentFSM.MultiPartHtlc(outer.totalAmount, add)
            context become main(pendingIncoming + (add.paymentHash -> relay.copy(htlcs = relay.htlcs :+ add)), pendingOutgoing)
          }
        case None =>
          val handler = context.actorOf(MultiPartPaymentFSM.props(nodeParams, add.paymentHash, outer.totalAmount, self))
          handler ! MultiPartPaymentFSM.MultiPartHtlc(outer.totalAmount, add)
          context become main(pendingIncoming + (add.paymentHash -> PendingRelay(Queue(add), secret, inner, next, handler)), pendingOutgoing)
      }
    }

    // We always fail extraneous HTLCs. They are a spec violation from the sender, but harmless in the relay case.
    // By failing them fast (before the payment has reached the final recipient) there's a good chance the sender
    // won't lose any money.
    case MultiPartPaymentFSM.ExtraHtlcReceived(_, p, failure) => rejectHtlc(p.htlcId, p.payment.fromChannelId, p.payment.amount, failure)

    case MultiPartPaymentFSM.MultiPartHtlcFailed(paymentHash, failure, parts) =>
      log.warning(s"could not relay payment (paidAmount=${parts.map(_.payment.amount).sum} failure=$failure)")
      pendingIncoming.get(paymentHash).foreach(_.handler ! PoisonPill)
      parts.foreach(p => rejectHtlc(p.htlcId, p.payment.fromChannelId, p.payment.amount, Some(failure)))
      context become main(pendingIncoming - paymentHash, pendingOutgoing)

    case MultiPartPaymentFSM.MultiPartHtlcSucceeded(paymentHash, parts) => pendingIncoming.get(paymentHash) match {
      case Some(PendingRelay(htlcs, _, nextPayload, nextPacket, handler)) =>
        val upstream = Upstream.TrampolineRelayed(htlcs)
        handler ! PoisonPill
        validateRelay(nodeParams, upstream, nextPayload) match {
          case Some(failure) =>
            log.warning(s"rejecting trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv} htlcCount=${parts.length} reason=$failure)")
            rejectPayment(upstream, Some(failure))
          case None =>
            log.info(s"relaying trampoline payment (amountIn=${upstream.amountIn} expiryIn=${upstream.expiryIn} amountOut=${nextPayload.amountToForward} expiryOut=${nextPayload.outgoingCltv} htlcCount=${parts.length})")
            val paymentId = relay(paymentHash, upstream, nextPayload, nextPacket)
            context become main(pendingIncoming - paymentHash, pendingOutgoing + (paymentId -> PendingResult(upstream, nextPayload)))
        }
      case None => throw new RuntimeException(s"could not find pending incoming payment (paymentHash=$paymentHash)")
    }

    case PaymentSent(id, paymentHash, paymentPreimage, parts) =>
      log.debug("trampoline payment successfully relayed")
      pendingOutgoing.get(id).foreach {
        case PendingResult(upstream, nextPayload) =>
          fulfillPayment(upstream, paymentPreimage)
          val fromChannelIds = upstream.adds.map(_.channelId)
          val toChannelIds = parts.map(_.toChannelId)
          context.system.eventStream.publish(TrampolinePaymentRelayed(upstream.amountIn, nextPayload.amountToForward, paymentHash, nextPayload.outgoingNodeId, fromChannelIds, toChannelIds))
      }
      context become main(pendingIncoming, pendingOutgoing - id)

    case PaymentFailed(id, _, _, _) =>
      // TODO: @t-bast: try to extract the most meaningful error to return upstream (from the downstream failures)
      //  - if local failure because balance too low: we should send a TEMPORARY failure upstream (they should retry when we have more balance available)
      //  - if local failure because route not found: sender probably need to raise fees/cltv?
      log.debug("trampoline payment failed")
      pendingOutgoing.get(id).foreach { case PendingResult(upstream, _) => rejectPayment(upstream) }
      context become main(pendingIncoming, pendingOutgoing - id)

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

  }

  def spawnOutgoingPayFSM(cfg: SendPaymentConfig, multiPart: Boolean): ActorRef = {
    if (multiPart) {
      context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, relayer, router, register))
    } else {
      context.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
    }
  }

  private def relay(paymentHash: ByteVector32, upstream: Upstream.TrampolineRelayed, payloadOut: Onion.NodeRelayPayload, packetOut: OnionRoutingPacket): UUID = {
    val paymentId = UUID.randomUUID()
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, paymentHash, payloadOut.outgoingNodeId, upstream, None, storeInDb = false, publishEvent = false)
    val routeParams = computeRouteParams(nodeParams, upstream.amountIn, upstream.expiryIn, payloadOut.amountToForward, payloadOut.outgoingCltv)
    payloadOut.invoiceFeatures match {
      case Some(invoiceFeatures) =>
        log.debug("relaying trampoline payment to non-trampoline recipient")
        val routingHints = payloadOut.invoiceRoutingInfo.map(_.map(_.toSeq).toSeq).getOrElse(Nil)
        val allowMultiPart = Features.hasFeature(invoiceFeatures, Features.BasicMultiPartPayment, None)
        val payFSM = spawnOutgoingPayFSM(paymentCfg, allowMultiPart)
        if (allowMultiPart) {
          if (payloadOut.paymentSecret.isEmpty) {
            log.warning("payment relay to non-trampoline node will likely fail: sender didn't include the invoice payment secret")
          }
          val payment = SendMultiPartPayment(paymentHash, payloadOut.paymentSecret.getOrElse(randomBytes32), payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
          payFSM ! payment
        } else {
          val finalPayload = Onion.createSinglePartPayload(payloadOut.amountToForward, payloadOut.outgoingCltv, payloadOut.paymentSecret)
          val payment = SendPayment(paymentHash, payloadOut.outgoingNodeId, finalPayload, nodeParams.maxPaymentAttempts, routingHints, Some(routeParams))
          payFSM ! payment
        }
      case None =>
        log.debug("relaying trampoline payment to next trampoline node")
        val payFSM = spawnOutgoingPayFSM(paymentCfg, multiPart = true)
        val paymentSecret = randomBytes32 // we generate a new secret to protect against probing attacks
        val payment = SendMultiPartPayment(paymentHash, paymentSecret, payloadOut.outgoingNodeId, payloadOut.amountToForward, payloadOut.outgoingCltv, nodeParams.maxPaymentAttempts, routeParams = Some(routeParams), additionalTlvs = Seq(OnionTlv.TrampolineOnion(packetOut)))
        payFSM ! payment
    }
    paymentId
  }

  private def rejectHtlc(htlcId: Long, channelId: ByteVector32, amount: MilliSatoshi, failure: Option[FailureMessage] = None): Unit = {
    val failureMessage = failure.getOrElse(IncorrectOrUnknownPaymentDetails(amount, nodeParams.currentBlockHeight))
    commandBuffer ! CommandBuffer.CommandSend(channelId, CMD_FAIL_HTLC(htlcId, Right(failureMessage), commit = true))
  }

  private def rejectPayment(upstream: Upstream.TrampolineRelayed, failure: Option[FailureMessage] = None): Unit =
    upstream.adds.foreach(add => rejectHtlc(add.id, add.channelId, upstream.amountIn, failure))

  private def fulfillPayment(upstream: Upstream.TrampolineRelayed, paymentPreimage: ByteVector32): Unit = upstream.adds.foreach(add => {
    val cmdFulfill = CMD_FULFILL_HTLC(add.id, paymentPreimage, commit = true)
    commandBuffer ! CommandBuffer.CommandSend(add.channelId, cmdFulfill)
  })

  override def mdc(currentMessage: Any): MDC = {
    val paymentHash_opt = currentMessage match {
      case IncomingPacket.NodeRelayPacket(add, _, _, _) => Some(add.paymentHash)
      case MultiPartPaymentFSM.MultiPartHtlcFailed(paymentHash, _, _) => Some(paymentHash)
      case MultiPartPaymentFSM.MultiPartHtlcSucceeded(paymentHash, _) => Some(paymentHash)
      case MultiPartPaymentFSM.ExtraHtlcReceived(paymentHash, _, _) => Some(paymentHash)
      case PaymentFailed(_, paymentHash, _, _) => Some(paymentHash)
      case PaymentSent(_, paymentHash, _, _) => Some(paymentHash)
      case _ => None
    }
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = paymentHash_opt)
  }

}

object NodeRelayer {

  def props(nodeParams: NodeParams, relayer: ActorRef, router: ActorRef, commandBuffer: ActorRef, register: ActorRef) = Props(classOf[NodeRelayer], nodeParams, relayer, router, commandBuffer, register)

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
   * @param upstream    complete HTLC set received.
   * @param nextPayload relay instructions.
   */
  case class PendingResult(upstream: Upstream.TrampolineRelayed, nextPayload: Onion.NodeRelayPayload)

  def validateRelay(nodeParams: NodeParams, upstream: Upstream.TrampolineRelayed, payloadOut: Onion.NodeRelayPayload): Option[FailureMessage] = {
    val fee = nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, payloadOut.amountToForward)
    if (upstream.amountIn - payloadOut.amountToForward < fee) {
      // TODO: @t-bast: should be a TrampolineFeeInsufficient(upstream.amountIn, myLatestNodeUpdate)
      Some(IncorrectOrUnknownPaymentDetails(upstream.amountIn, nodeParams.currentBlockHeight))
    } else if (upstream.expiryIn - payloadOut.outgoingCltv < nodeParams.expiryDeltaBlocks) {
      // TODO: @t-bast: should be a TrampolineExpiryTooSoon(myLatestNodeUpdate)
      Some(IncorrectOrUnknownPaymentDetails(upstream.amountIn, nodeParams.currentBlockHeight))
    } else {
      None
    }
  }

  /** Compute route params that honor our fee and cltv requirements. */
  def computeRouteParams(nodeParams: NodeParams, amountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): RouteParams = {
    val routeMaxCltv = expiryIn - expiryOut - nodeParams.expiryDeltaBlocks
    val routeMaxFee = amountIn - amountOut - nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, amountOut)
    Router.getDefaultRouteParams(nodeParams.routerConf).copy(
      maxFeeBase = routeMaxFee,
      routeMaxCltv = routeMaxCltv,
      maxFeePct = 0 // we disable percent-based max fee calculation, we're only interested in collecting our node fee
    )
  }

}
