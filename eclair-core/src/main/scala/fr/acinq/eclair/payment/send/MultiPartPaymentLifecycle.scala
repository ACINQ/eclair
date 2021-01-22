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

package fr.acinq.eclair.payment.send

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPaymentToRoute
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, FSMDiagnosticActorLogging, Logs, MilliSatoshi, MilliSatoshiLong, NodeParams}

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Sender for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * The payment will be split into multiple sub-payments that will be sent in parallel.
 */
class MultiPartPaymentLifecycle(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data] {

  import MultiPartPaymentLifecycle._

  require(cfg.id == cfg.parentId, "multi-part payment cannot have a parent payment")

  val id = cfg.id
  val paymentHash = cfg.paymentHash
  val start = System.currentTimeMillis
  private var retriedFailedChannels = false

  startWith(WAIT_FOR_PAYMENT_REQUEST, WaitingForRequest)

  when(WAIT_FOR_PAYMENT_REQUEST) {
    case Event(r: SendMultiPartPayment, _) =>
      val routeParams = r.getRouteParams(nodeParams, randomize = false) // we don't randomize the first attempt, regardless of configuration choices
      val maxFee = routeParams.getMaxFee(r.totalAmount)
      log.debug("sending {} with maximum fee {}", r.totalAmount, maxFee)
      val d = PaymentProgress(r, r.maxAttempts, Map.empty, Ignore.empty, Nil)
      router ! createRouteRequest(nodeParams, r.totalAmount, maxFee, routeParams, d, cfg)
      goto(WAIT_FOR_ROUTES) using d
  }

  when(WAIT_FOR_ROUTES) {
    case Event(RouteResponse(routes), d: PaymentProgress) =>
      log.info("{} routes found (attempt={}/{})", routes.length, d.request.maxAttempts - d.remainingAttempts + 1, d.request.maxAttempts)
      // We may have already succeeded sending parts of the payment and only need to take care of the rest.
      val (toSend, maxFee) = remainingToSend(nodeParams, d.request, d.pending.values)
      if (routes.map(_.amount).sum == toSend) {
        val childPayments = routes.map(route => (UUID.randomUUID(), route)).toMap
        childPayments.foreach { case (childId, route) => spawnChildPaymentFsm(childId) ! createChildPayment(self, route, d.request) }
        goto(PAYMENT_IN_PROGRESS) using d.copy(remainingAttempts = (d.remainingAttempts - 1).max(0), pending = d.pending ++ childPayments)
      } else {
        // If a child payment failed while we were waiting for routes, the routes we received don't cover the whole
        // remaining amount. In that case we discard these routes and send a new request to the router.
        log.info("discarding routes, another child payment failed so we need to recompute them (amount = {}, maximum fee = {})", toSend, maxFee)
        val routeParams = d.request.getRouteParams(nodeParams, randomize = true) // we randomize route selection when we retry
        router ! createRouteRequest(nodeParams, toSend, maxFee, routeParams, d, cfg)
        stay
      }

    case Event(Status.Failure(t), d: PaymentProgress) =>
      log.warning("router error: {}", t.getMessage)
      if (d.ignore.channels.nonEmpty) {
        // If no route can be found, we will retry once with the channels that we previously ignored.
        // Channels are mostly ignored for temporary reasons, likely because they didn't have enough balance to forward
        // the payment. When we're retrying an MPP split, it may make sense to retry those ignored channels because with
        // a different split, they may have enough balance to forward the payment.
        val (toSend, maxFee) = remainingToSend(nodeParams, d.request, d.pending.values)
        log.debug("retry sending {} with maximum fee {} without ignoring channels ({})", toSend, maxFee, d.ignore.channels.map(_.shortChannelId).mkString(","))
        val routeParams = d.request.getRouteParams(nodeParams, randomize = true) // we randomize route selection when we retry
        router ! createRouteRequest(nodeParams, toSend, maxFee, routeParams, d, cfg).copy(ignore = d.ignore.emptyChannels())
        retriedFailedChannels = true
        stay using d.copy(remainingAttempts = (d.remainingAttempts - 1).max(0), ignore = d.ignore.emptyChannels())
      } else {
        val failure = LocalFailure(Nil, t)
        Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(failure)).increment()
        if (cfg.storeInDb && d.pending.isEmpty && d.failures.isEmpty) {
          // In cases where we fail early (router error during the first attempt), the DB won't have an entry for that
          // payment, which may be confusing for users.
          val dummyPayment = OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, cfg.recipientAmount, cfg.recipientAmount, cfg.recipientNodeId, System.currentTimeMillis, cfg.paymentRequest, OutgoingPaymentStatus.Pending)
          nodeParams.db.payments.addOutgoingPayment(dummyPayment)
          nodeParams.db.payments.updateOutgoingPayment(PaymentFailed(id, paymentHash, failure :: Nil))
        }
        gotoAbortedOrStop(PaymentAborted(d.request, d.failures :+ failure, d.pending.keySet))
      }

    case Event(pf: PaymentFailed, d: PaymentProgress) =>
      if (isFinalRecipientFailure(pf, d)) {
        gotoAbortedOrStop(PaymentAborted(d.request, d.failures ++ pf.failures, d.pending.keySet - pf.id))
      } else {
        val ignore1 = PaymentFailure.updateIgnored(pf.failures, d.ignore)
        stay using d.copy(pending = d.pending - pf.id, ignore = ignore1, failures = d.failures ++ pf.failures)
      }

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(ps: PaymentSent, d: PaymentProgress) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      // As soon as we get the preimage we can consider that the whole payment succeeded (we have a proof of payment).
      gotoSucceededOrStop(PaymentSucceeded(d.request, ps.paymentPreimage, ps.parts, d.pending.keySet - ps.parts.head.id))
  }

  when(PAYMENT_IN_PROGRESS) {
    case Event(pf: PaymentFailed, d: PaymentProgress) =>
      if (isFinalRecipientFailure(pf, d)) {
        gotoAbortedOrStop(PaymentAborted(d.request, d.failures ++ pf.failures, d.pending.keySet - pf.id))
      } else if (d.remainingAttempts == 0) {
        val failure = LocalFailure(Nil, PaymentError.RetryExhausted)
        Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(failure)).increment()
        gotoAbortedOrStop(PaymentAborted(d.request, d.failures ++ pf.failures :+ failure, d.pending.keySet - pf.id))
      } else {
        val ignore1 = PaymentFailure.updateIgnored(pf.failures, d.ignore)
        val stillPending = d.pending - pf.id
        val (toSend, maxFee) = remainingToSend(nodeParams, d.request, stillPending.values)
        log.debug("child payment failed, retry sending {} with maximum fee {}", toSend, maxFee)
        val routeParams = d.request.getRouteParams(nodeParams, randomize = true) // we randomize route selection when we retry
        val d1 = d.copy(pending = stillPending, ignore = ignore1, failures = d.failures ++ pf.failures)
        router ! createRouteRequest(nodeParams, toSend, maxFee, routeParams, d1, cfg)
        goto(WAIT_FOR_ROUTES) using d1
      }

    case Event(ps: PaymentSent, d: PaymentProgress) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      // As soon as we get the preimage we can consider that the whole payment succeeded (we have a proof of payment).
      Metrics.PaymentAttempt.withTag(Tags.MultiPart, value = true).record(d.request.maxAttempts - d.remainingAttempts)
      gotoSucceededOrStop(PaymentSucceeded(d.request, ps.paymentPreimage, ps.parts, d.pending.keySet - ps.parts.head.id))
  }

  when(PAYMENT_ABORTED) {
    case Event(pf: PaymentFailed, d: PaymentAborted) =>
      val failures = d.failures ++ pf.failures
      val pending = d.pending - pf.id
      if (pending.isEmpty) {
        myStop(d.request.replyTo, Left(PaymentFailed(id, paymentHash, failures)))
      } else {
        stay using d.copy(failures = failures, pending = pending)
      }

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(ps: PaymentSent, d: PaymentAborted) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      log.warning(s"payment recipient fulfilled incomplete multi-part payment (id=${ps.parts.head.id})")
      gotoSucceededOrStop(PaymentSucceeded(d.request, ps.paymentPreimage, ps.parts, d.pending - ps.parts.head.id))

    case Event(_: RouteResponse, _) => stay
    case Event(_: Status.Failure, _) => stay
  }

  when(PAYMENT_SUCCEEDED) {
    case Event(ps: PaymentSent, d: PaymentSucceeded) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      val parts = d.parts ++ ps.parts
      val pending = d.pending - ps.parts.head.id
      if (pending.isEmpty) {
        myStop(d.request.replyTo, Right(cfg.createPaymentSent(d.preimage, parts)))
      } else {
        stay using d.copy(parts = parts, pending = pending)
      }

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(pf: PaymentFailed, d: PaymentSucceeded) =>
      log.warning(s"payment succeeded but partial payment failed (id=${pf.id})")
      val pending = d.pending - pf.id
      if (pending.isEmpty) {
        myStop(d.request.replyTo, Right(cfg.createPaymentSent(d.preimage, d.parts)))
      } else {
        stay using d.copy(pending = pending)
      }

    case Event(_: RouteResponse, _) => stay
    case Event(_: Status.Failure, _) => stay
  }

  def spawnChildPaymentFsm(childId: UUID): ActorRef = {
    val upstream = cfg.upstream match {
      case Upstream.Local(_) => Upstream.Local(childId)
      case _ => cfg.upstream
    }
    val childCfg = cfg.copy(id = childId, publishEvent = false, upstream = upstream)
    context.actorOf(PaymentLifecycle.props(nodeParams, childCfg, router, register))
  }

  private def gotoAbortedOrStop(d: PaymentAborted): State = {
    if (d.pending.isEmpty) {
      myStop(d.request.replyTo, Left(PaymentFailed(id, paymentHash, d.failures)))
    } else
      goto(PAYMENT_ABORTED) using d
  }

  private def gotoSucceededOrStop(d: PaymentSucceeded): State = {
    d.request.replyTo ! PreimageReceived(paymentHash, d.preimage)
    if (d.pending.isEmpty) {
      myStop(d.request.replyTo, Right(cfg.createPaymentSent(d.preimage, d.parts)))
    } else
      goto(PAYMENT_SUCCEEDED) using d
  }

  def myStop(origin: ActorRef, event: Either[PaymentFailed, PaymentSent]): State = {
    event match {
      case Left(paymentFailed) =>
        log.warning("multi-part payment failed")
        reply(origin, paymentFailed)
      case Right(paymentSent) =>
        log.info("multi-part payment succeeded")
        reply(origin, paymentSent)
    }
    Metrics.SentPaymentDuration
      .withTag(Tags.MultiPart, Tags.MultiPartType.Parent)
      .withTag(Tags.Success, value = event.isRight)
      .record(System.currentTimeMillis - start, TimeUnit.MILLISECONDS)
    if (retriedFailedChannels) {
      Metrics.RetryFailedChannelsResult.withTag(Tags.Success, event.isRight).increment()
    }
    stop(FSM.Normal)
  }

  def reply(to: ActorRef, e: PaymentEvent): Unit = {
    to ! e
    if (cfg.publishEvent) context.system.eventStream.publish(e)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(
      category_opt = Some(Logs.LogCategory.PAYMENT),
      parentPaymentId_opt = Some(cfg.parentId),
      paymentId_opt = Some(id),
      paymentHash_opt = Some(paymentHash),
      remoteNodeId_opt = Some(cfg.recipientNodeId))
  }

  initialize()

}

object MultiPartPaymentLifecycle {

  def props(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) = Props(new MultiPartPaymentLifecycle(nodeParams, cfg, router, register))

  /**
   * Send a payment to a given node. The payment may be split into multiple child payments, for which a path-finding
   * algorithm will run to find suitable payment routes.
   *
   * @param paymentSecret  payment secret to protect against probing (usually from a Bolt 11 invoice).
   * @param targetNodeId   target node (may be the final recipient when using source-routing, or the first trampoline
   *                       node when using trampoline).
   * @param totalAmount    total amount to send to the target node.
   * @param targetExpiry   expiry at the target node (CLTV for the target node's received HTLCs).
   * @param maxAttempts    maximum number of retries.
   * @param assistedRoutes routing hints (usually from a Bolt 11 invoice).
   * @param routeParams    parameters to fine-tune the routing algorithm.
   * @param additionalTlvs when provided, additional tlvs that will be added to the onion sent to the target node.
   * @param userCustomTlvs when provided, additional user-defined custom tlvs that will be added to the onion sent to the target node.
   */
  case class SendMultiPartPayment(replyTo: ActorRef,
                                  paymentSecret: ByteVector32,
                                  targetNodeId: PublicKey,
                                  totalAmount: MilliSatoshi,
                                  targetExpiry: CltvExpiry,
                                  maxAttempts: Int,
                                  assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                  routeParams: Option[RouteParams] = None,
                                  additionalTlvs: Seq[OnionTlv] = Nil,
                                  userCustomTlvs: Seq[GenericTlv] = Nil) {
    require(totalAmount > 0.msat, s"total amount must be > 0")

    def getRouteParams(nodeParams: NodeParams, randomize: Boolean): RouteParams =
      routeParams.getOrElse(RouteCalculation.getDefaultRouteParams(nodeParams.routerConf)).copy(randomize = randomize)
  }

  /**
   * The payment FSM will wait for all child payments to settle before emitting payment events, but the preimage will be
   * shared as soon as it's received to unblock other actors that may need it.
   */
  case class PreimageReceived(paymentHash: ByteVector32, paymentPreimage: ByteVector32)

  // @formatter:off
  sealed trait State
  case object WAIT_FOR_PAYMENT_REQUEST extends State
  case object WAIT_FOR_ROUTES extends State
  case object PAYMENT_IN_PROGRESS extends State
  case object PAYMENT_ABORTED extends State
  case object PAYMENT_SUCCEEDED extends State
  // @formatter:on

  sealed trait Data

  /**
   * During initialization, we wait for a multi-part payment request containing the total amount to send and the maximum
   * fee budget.
   */
  case object WaitingForRequest extends Data

  /**
   * While the payment is in progress, we listen to child payment failures. When we receive such failures, we retry the
   * failed amount with different routes.
   *
   * @param request           payment request containing the total amount to send.
   * @param remainingAttempts remaining attempts (after child payments fail).
   * @param pending           pending child payments (payment sent, we are waiting for a fulfill or a failure).
   * @param ignore            channels and nodes that should be ignored (previously returned a permanent error).
   * @param failures          previous child payment failures.
   */
  case class PaymentProgress(request: SendMultiPartPayment,
                             remainingAttempts: Int,
                             pending: Map[UUID, Route],
                             ignore: Ignore,
                             failures: Seq[PaymentFailure]) extends Data

  /**
   * When we exhaust our retry attempts without success, we abort the payment.
   * Once we're in that state, we wait for all the pending child payments to settle.
   *
   * @param request  payment request containing the total amount to send.
   * @param failures child payment failures.
   * @param pending  pending child payments (we are waiting for them to be failed downstream).
   */
  case class PaymentAborted(request: SendMultiPartPayment, failures: Seq[PaymentFailure], pending: Set[UUID]) extends Data

  /**
   * Once we receive a first fulfill for a child payment, we can consider that the whole payment succeeded (because we
   * received the payment preimage that we can use as a proof of payment).
   * Once we're in that state, we wait for all the pending child payments to fulfill.
   *
   * @param request  payment request containing the total amount to send.
   * @param preimage payment preimage.
   * @param parts    fulfilled child payments.
   * @param pending  pending child payments (we are waiting for them to be fulfilled downstream).
   */
  case class PaymentSucceeded(request: SendMultiPartPayment, preimage: ByteVector32, parts: Seq[PartialPayment], pending: Set[UUID]) extends Data

  private def createRouteRequest(nodeParams: NodeParams, toSend: MilliSatoshi, maxFee: MilliSatoshi, routeParams: RouteParams, d: PaymentProgress, cfg: SendPaymentConfig): RouteRequest =
    RouteRequest(
      nodeParams.nodeId,
      d.request.targetNodeId,
      toSend,
      maxFee,
      d.request.assistedRoutes,
      d.ignore,
      Some(routeParams),
      allowMultiPart = true,
      d.pending.values.toSeq,
      Some(cfg.paymentContext))

  private def createChildPayment(replyTo: ActorRef, route: Route, request: SendMultiPartPayment): SendPaymentToRoute = {
    val finalPayload = Onion.createMultiPartPayload(route.amount, request.totalAmount, request.targetExpiry, request.paymentSecret, request.additionalTlvs, request.userCustomTlvs)
    SendPaymentToRoute(replyTo, Right(route), finalPayload)
  }

  /** When we receive an error from the final recipient, we should fail the whole payment, it's useless to retry. */
  private def isFinalRecipientFailure(pf: PaymentFailed, d: PaymentProgress): Boolean = pf.failures.collectFirst {
    case f: RemoteFailure if f.e.originNode == d.request.targetNodeId => true
  }.getOrElse(false)

  private def remainingToSend(nodeParams: NodeParams, request: SendMultiPartPayment, pending: Iterable[Route]): (MilliSatoshi, MilliSatoshi) = {
    val sentAmount = pending.map(_.amount).sum
    val sentFees = pending.map(_.fee).sum
    (request.totalAmount - sentAmount, request.getRouteParams(nodeParams, randomize = false).getMaxFee(request.totalAmount) - sentFees)
  }

}