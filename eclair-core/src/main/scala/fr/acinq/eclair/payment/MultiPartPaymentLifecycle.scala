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

import java.util.UUID

import akka.actor.{ActorContext, ActorRef, FSM, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentsDb}
import fr.acinq.eclair.payment.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.payment.PaymentLifecycle.{PaymentProgressHandler, SendPayment}
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.Onion
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, LongToBtcAmount, MilliSatoshi, NodeParams, ToMilliSatoshiConversion}

import scala.compat.Platform
import scala.util.Random

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Sender for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * The payment will be split into multiple sub-payments that will be sent in parallel.
 */
class MultiPartPaymentLifecycle(nodeParams: NodeParams, id: UUID, relayer: ActorRef, router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data] {

  import MultiPartPaymentLifecycle._

  val paymentsDb = nodeParams.db.payments

  startWith(PAYMENT_INIT, PaymentInit(None, None, None))

  when(PAYMENT_INIT) {
    case Event(r: SendPaymentRequest, d: PaymentInit) =>
      require(d.request.isEmpty, "multi-part payment lifecycle must receive only one payment request")
      router ! GetNetworkStats
      stay using d.copy(sender = Some(sender()), request = Some(r))

    case Event(s: GetNetworkStatsResponse, d: PaymentInit) =>
      require(d.request.nonEmpty && d.sender.nonEmpty, "multi-part payment request must be set")
      val r = d.request.get
      s.stats match {
        case Some(networkStats) =>
          log.debug(s"network stats available: ${networkStats.capacity}")
          relayer ! GetUsableBalances
          goto(PAYMENT_IN_PROGRESS) using PaymentProgress(d.sender.get, r, networkStats, r.amount, r.maxAttempts, Map.empty, Nil)
        case None =>
          log.debug("network stats not available: retrying...")
          router ! TickComputeNetworkStats
          router ! GetNetworkStats
          stay
      }
  }

  when(PAYMENT_IN_PROGRESS) {
    case Event(UsableBalances(balances), d: PaymentProgress) if d.toSend > 0.msat =>
      log.debug(s"trying to send ${d.toSend} with local channels balances: ${balances.mkString(",")}")
      val randomize = d.remainingAttempts != d.request.maxAttempts // we randomize channel selection when we retry
      val (remaining, payments) = splitPayment(nodeParams, d.toSend, balances, d.networkStats, d.request, randomize)
      if (remaining > 0.msat) {
        log.warning(s"cannot send ${d.toSend} with our current balance")
        goto(PAYMENT_ABORTED) using PaymentAborted(d.sender, d.request, d.failures :+ LocalFailure(new RuntimeException("balance is too low")), d.pending.keySet)
      } else {
        val pending = payments.map(p => (UUID.randomUUID(), p)).toMap
        pending.foreach { case (childId, payment) => spawnChildPaymentFsm(ChildPaymentProgressHandler(childId, id, d.request, paymentsDb)) ! payment }
        stay using d.copy(toSend = 0 msat, remainingAttempts = d.remainingAttempts - 1, pending = d.pending ++ pending)
      }

    case Event(pf: PaymentFailed, d: PaymentProgress) =>
      if (d.remainingAttempts == 0) {
        val failure = LocalFailure(new RuntimeException("payments attempts exhausted without success"))
        goto(PAYMENT_ABORTED) using PaymentAborted(d.sender, d.request, d.failures ++ pf.failures :+ failure, d.pending.keySet - pf.id)
      } else {
        // Get updated balances (will take into account the child payments that are in-flight).
        if (d.toSend == 0.msat) relayer ! GetUsableBalances
        val failedPayment = d.pending(pf.id)
        stay using d.copy(toSend = d.toSend + failedPayment.finalPayload.amount, pending = d.pending - pf.id, failures = d.failures ++ pf.failures)
      }

    case Event(ps: PaymentSent, d: PaymentProgress) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      // As soon as we get the preimage we can consider that the whole payment succeeded (we have a proof of payment).
      goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(d.sender, d.request, ps.paymentPreimage, ps.parts, d.pending.keySet - ps.id)
  }

  when(PAYMENT_ABORTED) {
    case Event(pf: PaymentFailed, d: PaymentAborted) =>
      val failures = d.failures ++ pf.failures
      val pending = d.pending - pf.id
      if (pending.isEmpty) {
        log.warning("multi-part payment failed")
        reply(d.sender, PaymentFailed(id, d.request.paymentHash, failures))
        stop(FSM.Normal)
      }
      stay using d.copy(failures = failures, pending = pending)

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(ps: PaymentSent, d: PaymentAborted) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      log.warning(s"payment recipient fulfilled incomplete multi-part payment (id=${ps.id})")
      goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(d.sender, d.request, ps.paymentPreimage, ps.parts, d.pending - ps.id)
  }

  when(PAYMENT_SUCCEEDED) {
    case Event(ps: PaymentSent, d: PaymentSucceeded) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      val parts = d.parts ++ ps.parts
      val pending = d.pending - ps.id
      if (pending.isEmpty) {
        log.info("multi-part payment succeeded")
        reply(d.sender, PaymentSent(id, d.request.paymentHash, d.preimage, parts))
        stop(FSM.Normal)
      }
      stay using d.copy(parts = parts, pending = pending)

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(pf: PaymentFailed, d: PaymentSucceeded) =>
      log.warning(s"payment succeeded but partial payment failed (id=${pf.id})")
      val pending = d.pending - pf.id
      if (pending.isEmpty) {
        log.info("multi-part payment succeeded")
        reply(d.sender, PaymentSent(id, d.request.paymentHash, d.preimage, d.parts))
        stop(FSM.Normal)
      }
      stay using d.copy(pending = pending)
  }

  onTransition {
    case _ -> PAYMENT_ABORTED => nextStateData match {
      case d: PaymentAborted if d.pending.isEmpty =>
        log.warning("multi-part payment failed")
        reply(d.sender, PaymentFailed(id, d.request.paymentHash, d.failures))
        stop(FSM.Normal)
      case _ =>
    }

    case _ -> PAYMENT_SUCCEEDED => nextStateData match {
      case d: PaymentSucceeded if d.pending.isEmpty =>
        log.info("multi-part payment succeeded")
        reply(d.sender, PaymentSent(id, d.request.paymentHash, d.preimage, d.parts))
        stop(FSM.Normal)
      case _ =>
    }
  }

  def spawnChildPaymentFsm(progressHandler: PaymentProgressHandler): ActorRef = context.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, router, register))

  def reply(to: ActorRef, e: PaymentEvent): Unit = {
    to ! e
    context.system.eventStream.publish(e)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(paymentId_opt = Some(id))
  }

  initialize()

}

object MultiPartPaymentLifecycle {

  def props(nodeParams: NodeParams, id: UUID, relayer: ActorRef, router: ActorRef, register: ActorRef) = Props(new MultiPartPaymentLifecycle(nodeParams, id, relayer, router, register))

  // @formatter:off
  sealed trait State
  case object PAYMENT_INIT extends State
  case object PAYMENT_IN_PROGRESS extends State
  case object PAYMENT_ABORTED extends State
  case object PAYMENT_SUCCEEDED extends State

  sealed trait Data
  /**
   * During initialization, we collect the data we need to correctly split payments.
   *
   * @param sender       the sender of the payment request.
   * @param networkStats network statistics help us decide how to best split a big payment.
   * @param request      payment request containing the total amount to send.
   */
  case class PaymentInit(sender: Option[ActorRef], networkStats: Option[NetworkStats], request: Option[SendPaymentRequest]) extends Data
  /**
   * While the payment is in progress, we listen to child payment failures. When we receive such failures, we request
   * our up-to-date local channels balances and retry the failed child payments with a potentially different route.
   *
   * @param sender            the sender of the payment request.
   * @param request           payment request containing the total amount to send.
   * @param networkStats      network statistics help us decide how to best split a big payment.
   * @param toSend            remaining amount that should be split and sent.
   * @param remainingAttempts remaining attempts (after child payments fail).
   * @param pending           pending child payments (payment sent, we are waiting for a fulfill or a failure).
   * @param failures          previous child payment failures.
   */
  case class PaymentProgress(sender: ActorRef, request: SendPaymentRequest, networkStats: NetworkStats, toSend: MilliSatoshi, remainingAttempts: Int, pending: Map[UUID, SendPayment], failures: Seq[PaymentFailure]) extends Data
  /**
   * When we exhaust our retry attempts without success, we abort the payment.
   * Once we're in that state, we wait for all the pending child payments to settle.
   *
   * @param sender   the sender of the payment request.
   * @param request  payment request containing the total amount to send.
   * @param failures child payment failures.
   * @param pending  pending child payments (we are waiting for them to be failed downstream).
   */
  case class PaymentAborted(sender: ActorRef, request: SendPaymentRequest, failures: Seq[PaymentFailure], pending: Set[UUID]) extends Data
  /**
   * Once we receive a first fulfill for a child payment, we can consider that the whole payment succeeded (because we
   * received the payment preimage that we can use as a proof of payment).
   * Once we're in that state, we wait for all the pending child payments to fulfill.
   *
   * @param sender   the sender of the payment request.
   * @param request  payment request containing the total amount to send.
   * @param preimage payment preimage.
   * @param parts    fulfilled child payments.
   * @param pending  pending child payments (we are waiting for them to be fulfilled downstream).
   */
  case class PaymentSucceeded(sender: ActorRef, request: SendPaymentRequest, preimage: ByteVector32, parts: Seq[PartialPayment], pending: Set[UUID]) extends Data
  // @formatter:on

  /** Child payments are stored in the payments DB but don't emit payment events. */
  case class ChildPaymentProgressHandler(id: UUID, parentId: UUID, r: SendPaymentRequest, db: PaymentsDb) extends PaymentProgressHandler {
    override def onSend(amount: MilliSatoshi): Unit = {
      db.addOutgoingPayment(OutgoingPayment(id, parentId, r.externalId, r.paymentHash, amount, r.targetNodeId, Platform.currentTime, r.paymentRequest, OutgoingPaymentStatus.Pending))
    }

    override def onSuccess(sender: ActorRef, result: PaymentSent)(ctx: ActorContext): Unit = {
      db.updateOutgoingPayment(result)
      sender ! result
    }

    override def onFailure(sender: ActorRef, result: PaymentFailed)(ctx: ActorContext): Unit = {
      db.updateOutgoingPayment(result)
      sender ! result
    }
  }

  private def createChildPayment(nodeParams: NodeParams, request: SendPaymentRequest, childAmount: MilliSatoshi, channel: UsableBalance): SendPayment = {
    SendPayment(
      request.paymentHash,
      request.targetNodeId,
      Onion.createMultiPartPayload(childAmount, request.amount, request.finalExpiry(nodeParams.currentBlockHeight), request.paymentRequest.get.paymentSecret.get),
      request.maxAttempts,
      request.assistedRoutes,
      request.routeParams,
      Hop(nodeParams.nodeId, channel.remoteNodeId, channel.lastUpdate) :: Nil)
  }

  /**
   * Split a payment into many child payments.
   *
   * @param toSend    amount to split.
   * @param balances  local channels balances.
   * @param request   payment request containing the total amount to send and routing hints and parameters.
   * @param randomize randomize the channel selection.
   * @return the child payments that should be then sent to PaymentLifecycle actors.
   */
  def splitPayment(nodeParams: NodeParams, toSend: MilliSatoshi, balances: Seq[UsableBalance], networkStats: NetworkStats, request: SendPaymentRequest, randomize: Boolean): (MilliSatoshi, Seq[SendPayment]) = {
    require(toSend > 0.msat, "amount to send must be greater than 0")
    require(request.paymentRequest.isDefined && request.paymentRequest.get.features.allowMultiPart, "payment request must allow multi-part payment")
    // If we have channels to the target, we directly use them.
    val channelsToTarget = balances.filter(p => p.remoteNodeId == request.targetNodeId).sortBy(_.canSend)
    val (remaining, localPayments) = channelsToTarget.foldLeft((toSend, Seq.empty[SendPayment])) {
      case ((MilliSatoshi(0), payments), _) => (0 msat, payments)
      case ((remaining, payments), channel) =>
        val childPayment = createChildPayment(nodeParams, request, remaining.min(channel.canSend), channel)
        (remaining - childPayment.finalPayload.amount, childPayment +: payments)
    }
    // For other channels, we need to split the amount based on network statistics and pessimistic fees estimates.
    val maxFeePct = request.routeParams.map(_.maxFeePct).getOrElse(nodeParams.routerConf.searchMaxFeePct)
    val maxFeeBase = request.routeParams.map(_.maxFeeBase).getOrElse(nodeParams.routerConf.searchMaxFeeBase.toMilliSatoshi)
    val channels = if (randomize) {
      Random.shuffle(balances.filter(p => p.remoteNodeId != request.targetNodeId))
    } else {
      balances.filter(p => p.remoteNodeId != request.targetNodeId).sortBy(_.canSend)
    }
    channels.foldLeft((remaining, localPayments)) {
      case ((MilliSatoshi(0), payments), _) => (0 msat, payments)
      case ((remaining, payments), channel) =>
        require(remaining > 0.msat, s"payment splitting error: remaining amount must not be negative ($remaining): sending $toSend to ${request.targetNodeId} with balances=$balances, channels=$channels network=${networkStats.capacity}, fees=($maxFeeBase, $maxFeePct)")
        // We use network statistics with a random factor to decide on the maximum amount for child payments.
        // The current choice of parameters is completely arbitrary and could be made configurable.
        // We could also learn from previous payment failures to dynamically tweak that value.
        val maxAmount = networkStats.capacity.percentile75.toMilliSatoshi * ((75.0 + Random.nextInt(25)) / 100)
        if (remaining <= maxAmount) {
          val childAmount = Seq(remaining, channel.canSend * (1 - maxFeePct), channel.canSend - maxFeeBase).min
          if (childAmount > 0.msat) {
            val childPayment = createChildPayment(nodeParams, request, childAmount, channel)
            (remaining - childPayment.finalPayload.amount, childPayment +: payments)
          } else {
            (remaining, payments)
          }
        } else {
          val childCount = math.ceil(channel.canSend.min(remaining).toLong.toDouble / maxAmount.toLong).toInt
          // We can't use all the available balance because we need to take fees into account and we don't know the
          // exact fee before-hand because we don't know the rest of the route yet.
          // Splitting into multiple HTLCs in the same channel will also increase the size of the CommitTx (and thus its
          // fee), which decreases the available balance.
          // We need to take that into account when trying to send multiple payments through the same channel.
          val htlcsFees = Transactions.htlcOutputFee(nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget)) * childCount
          val canSend = remaining.min(channel.canSend - (maxFeeBase * childCount)).min(channel.canSend * (1 - (childCount * maxFeePct))) - htlcsFees
          if (canSend > 0.msat) {
            val childPayments = Seq.fill(childCount)(createChildPayment(nodeParams, request, canSend / childCount, channel))
            (remaining - childPayments.map(_.finalPayload.amount).sum, childPayments ++ payments)
          } else {
            (remaining, payments)
          }
        }
    }
  }

}