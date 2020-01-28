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

import java.util.UUID

import akka.actor.{ActorRef, FSM, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{Commitments, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer.{GetOutgoingChannels, OutgoingChannel, OutgoingChannels}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, FSMDiagnosticActorLogging, Logs, LongToBtcAmount, MilliSatoshi, NodeParams, ShortChannelId, ToMilliSatoshiConversion}
import kamon.Kamon
import kamon.context.Context
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.util.Random

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Sender for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * The payment will be split into multiple sub-payments that will be sent in parallel.
 */
class MultiPartPaymentLifecycle(nodeParams: NodeParams, cfg: SendPaymentConfig, relayer: ActorRef, router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data] {

  import MultiPartPaymentLifecycle._

  require(cfg.id == cfg.parentId, "multi-part payment cannot have a parent payment")

  val id = cfg.id
  val paymentHash = cfg.paymentHash

  private val span = Kamon.spanBuilder("multi-part-payment")
    .tag("parentPaymentId", cfg.parentId.toString)
    .tag("paymentHash", paymentHash.toHex)
    .tag("recipientNodeId", cfg.recipientNodeId.toString())
    .tag("recipientAmount", cfg.recipientAmount.toLong)
    .start()

  startWith(WAIT_FOR_PAYMENT_REQUEST, WaitingForRequest)

  when(WAIT_FOR_PAYMENT_REQUEST) {
    case Event(r: SendMultiPartPayment, _) =>
      router ! GetNetworkStats
      goto(WAIT_FOR_NETWORK_STATS) using WaitingForNetworkStats(sender, r)
  }

  when(WAIT_FOR_NETWORK_STATS) {
    case Event(s: GetNetworkStatsResponse, d: WaitingForNetworkStats) =>
      log.debug("network stats: {}", s.stats.map(_.capacity))
      // If we don't have network stats it's ok, we'll use data about our local channels instead.
      // We tell the router to compute those stats though: in case our payment attempt fails, they will be available for
      // another payment attempt.
      if (s.stats.isEmpty) {
        router ! TickComputeNetworkStats
      }
      relayer ! GetOutgoingChannels()
      goto(WAIT_FOR_CHANNEL_BALANCES) using WaitingForChannelBalances(d.sender, d.request, s.stats)
  }

  when(WAIT_FOR_CHANNEL_BALANCES) {
    case Event(OutgoingChannels(channels), d: WaitingForChannelBalances) =>
      log.debug("trying to send {} with local channels: {}", d.request.totalAmount, channels.map(_.toUsableBalance).mkString(","))
      val (remaining, payments) = splitPayment(nodeParams, d.request.totalAmount, channels, d.networkStats, d.request, randomize = false)
      if (remaining > 0.msat) {
        log.warning(s"cannot send ${d.request.totalAmount} with our current balance")
        goto(PAYMENT_ABORTED) using PaymentAborted(d.sender, d.request, LocalFailure(BalanceTooLow) :: Nil, Set.empty)
      } else {
        val pending = setFees(d.request.routeParams, payments, payments.size)
        Kamon.runWithContextEntry(parentPaymentIdKey, cfg.parentId) {
          Kamon.runWithSpan(span, finishSpan = true) {
            pending.foreach { case (childId, payment) => spawnChildPaymentFsm(childId) ! payment }
          }
        }
        goto(PAYMENT_IN_PROGRESS) using PaymentProgress(d.sender, d.request, d.networkStats, channels.length, 0 msat, d.request.maxAttempts - 1, pending, Set.empty, Nil)
      }
  }

  when(PAYMENT_IN_PROGRESS) {
    case Event(pf: PaymentFailed, d: PaymentProgress) => handleChildFailure(pf, d) match {
      case Some(paymentAborted) =>
        goto(PAYMENT_ABORTED) using paymentAborted
      case None =>
        // Get updated local channels (will take into account the child payments that are in-flight).
        relayer ! GetOutgoingChannels()
        val failedPayment = d.pending(pf.id)
        val shouldBlacklist = shouldBlacklistChannel(pf)
        if (shouldBlacklist) {
          log.debug(s"ignoring channel ${getFirstHopShortChannelId(failedPayment)} to ${failedPayment.routePrefix.head.nextNodeId}")
        }
        val ignoreChannels = if (shouldBlacklist) d.ignoreChannels + getFirstHopShortChannelId(failedPayment) else d.ignoreChannels
        val remainingAttempts = if (shouldBlacklist && Random.nextDouble() * math.log(d.channelsCount) > 2.0) {
          // When we have a lot of channels, many of them may end up being a bad route prefix for the destination we're
          // trying to reach. This is a cheap error that is detected quickly (RouteNotFound), so we don't want to count
          // it in our payment attempts to avoid failing too fast.
          // However we don't want to test all of our channels either which would be expensive, so we only probabilistically
          // count the failure in our payment attempts.
          // With the log-scale used, here are the probabilities and the corresponding number of retries:
          //  *   10 channels -> refund 13% of failures -> with 5 initial retries we will actually try 5/(1-0.13) = ~6 times
          //  *   20 channels -> refund 32% of failures -> with 5 initial retries we will actually try 5/(1-0.32) = ~7 times
          //  *   50 channels -> refund 50% of failures -> with 5 initial retries we will actually try 5/(1-0.50) = ~10 times
          //  *  100 channels -> refund 56% of failures -> with 5 initial retries we will actually try 5/(1-0.56) = ~11 times
          //  * 1000 channels -> refund 70% of failures -> with 5 initial retries we will actually try 5/(1-0.70) = ~17 times
          // NB: this hack won't be necessary once multi-part is directly handled by the router.
          d.remainingAttempts + 1
        } else {
          d.remainingAttempts
        }
        goto(RETRY_WITH_UPDATED_BALANCES) using d.copy(toSend = d.toSend + failedPayment.finalPayload.amount, pending = d.pending - pf.id, failures = d.failures ++ pf.failures, ignoreChannels = ignoreChannels, remainingAttempts = remainingAttempts)
    }

    case Event(ps: PaymentSent, d: PaymentProgress) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      // As soon as we get the preimage we can consider that the whole payment succeeded (we have a proof of payment).
      goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(d.sender, d.request, ps.paymentPreimage, ps.parts, d.pending.keySet - ps.parts.head.id)
  }

  when(RETRY_WITH_UPDATED_BALANCES) {
    case Event(OutgoingChannels(channels), d: PaymentProgress) =>
      log.debug("trying to send {} with local channels: {}", d.toSend, channels.map(_.toUsableBalance).mkString(","))
      val filteredChannels = channels.filter(c => !d.ignoreChannels.contains(c.channelUpdate.shortChannelId))
      val (remaining, payments) = splitPayment(nodeParams, d.toSend, filteredChannels, d.networkStats, d.request, randomize = true) // we randomize channel selection when we retry
      if (remaining > 0.msat) {
        log.warning(s"cannot send ${d.toSend} with our current balance")
        goto(PAYMENT_ABORTED) using PaymentAborted(d.sender, d.request, d.failures :+ LocalFailure(BalanceTooLow), d.pending.keySet)
      } else {
        val pending = setFees(d.request.routeParams, payments, payments.size + d.pending.size)
        pending.foreach { case (childId, payment) => spawnChildPaymentFsm(childId) ! payment }
        goto(PAYMENT_IN_PROGRESS) using d.copy(toSend = 0 msat, remainingAttempts = d.remainingAttempts - 1, pending = d.pending ++ pending, channelsCount = channels.length)
      }

    case Event(pf: PaymentFailed, d: PaymentProgress) => handleChildFailure(pf, d) match {
      case Some(paymentAborted) =>
        goto(PAYMENT_ABORTED) using paymentAborted
      case None =>
        val failedPayment = d.pending(pf.id)
        val ignoreChannels = if (shouldBlacklistChannel(pf)) d.ignoreChannels + getFirstHopShortChannelId(failedPayment) else d.ignoreChannels
        stay using d.copy(toSend = d.toSend + failedPayment.finalPayload.amount, pending = d.pending - pf.id, failures = d.failures ++ pf.failures, ignoreChannels = ignoreChannels)
    }

    case Event(ps: PaymentSent, d: PaymentProgress) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      // As soon as we get the preimage we can consider that the whole payment succeeded (we have a proof of payment).
      goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(d.sender, d.request, ps.paymentPreimage, ps.parts, d.pending.keySet - ps.parts.head.id)
  }

  when(PAYMENT_ABORTED) {
    case Event(pf: PaymentFailed, d: PaymentAborted) =>
      val failures = d.failures ++ pf.failures
      val pending = d.pending - pf.id
      if (pending.isEmpty) {
        myStop(d.sender, Left(PaymentFailed(id, paymentHash, failures)))
      } else {
        stay using d.copy(failures = failures, pending = pending)
      }

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(ps: PaymentSent, d: PaymentAborted) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      log.warning(s"payment recipient fulfilled incomplete multi-part payment (id=${ps.parts.head.id})")
      goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(d.sender, d.request, ps.paymentPreimage, ps.parts, d.pending - ps.parts.head.id)
  }

  when(PAYMENT_SUCCEEDED) {
    case Event(ps: PaymentSent, d: PaymentSucceeded) =>
      require(ps.parts.length == 1, "child payment must contain only one part")
      val parts = d.parts ++ ps.parts
      val pending = d.pending - ps.parts.head.id
      if (pending.isEmpty) {
        myStop(d.sender, Right(cfg.createPaymentSent(d.preimage, parts)))
      } else {
        stay using d.copy(parts = parts, pending = pending)
      }

    // The recipient released the preimage without receiving the full payment amount.
    // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
    case Event(pf: PaymentFailed, d: PaymentSucceeded) =>
      log.warning(s"payment succeeded but partial payment failed (id=${pf.id})")
      val pending = d.pending - pf.id
      if (pending.isEmpty) {
        myStop(d.sender, Right(cfg.createPaymentSent(d.preimage, d.parts)))
      } else {
        stay using d.copy(pending = pending)
      }
  }

  onTransition {
    case _ -> PAYMENT_ABORTED => nextStateData match {
      case d: PaymentAborted if d.pending.isEmpty =>
        myStop(d.sender, Left(PaymentFailed(id, paymentHash, d.failures)))
      case _ =>
    }

    case _ -> PAYMENT_SUCCEEDED => nextStateData match {
      case d: PaymentSucceeded if d.pending.isEmpty =>
        myStop(d.sender, Right(cfg.createPaymentSent(d.preimage, d.parts)))
      case _ =>
    }
  }

  def spawnChildPaymentFsm(childId: UUID): ActorRef = {
    val upstream = cfg.upstream match {
      case Upstream.Local(_) => Upstream.Local(childId)
      case _ => cfg.upstream
    }
    val childCfg = cfg.copy(id = childId, publishEvent = false, upstream = upstream)
    context.actorOf(PaymentLifecycle.props(nodeParams, childCfg, router, register))
  }

  def myStop(origin: ActorRef, event: Either[PaymentFailed, PaymentSent]): State = {
    event match {
      case Left(paymentFailed) =>
        log.warning("multi-part payment failed")
        reply(origin, paymentFailed)
        span.fail("payment failed")
      case Right(paymentSent) =>
        log.info("multi-part payment succeeded")
        reply(origin, paymentSent)
    }
    span.finish()
    stop(FSM.Normal)
  }

  def reply(to: ActorRef, e: PaymentEvent): Unit = {
    to ! e
    if (cfg.publishEvent) context.system.eventStream.publish(e)
  }

  def handleChildFailure(pf: PaymentFailed, d: PaymentProgress): Option[PaymentAborted] = {
    val isFromFinalRecipient = pf.failures.collectFirst { case f: RemoteFailure if f.e.originNode == d.request.targetNodeId => true }.isDefined
    if (isFromFinalRecipient) {
      Some(PaymentAborted(d.sender, d.request, d.failures ++ pf.failures, d.pending.keySet - pf.id))
    } else if (d.remainingAttempts == 0) {
      val failure = LocalFailure(RetryExhausted)
      Some(PaymentAborted(d.sender, d.request, d.failures ++ pf.failures :+ failure, d.pending.keySet - pf.id))
    } else {
      None
    }
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), parentPaymentId_opt = Some(cfg.parentId), paymentId_opt = Some(id), paymentHash_opt = Some(paymentHash))
  }

  initialize()

}

object MultiPartPaymentLifecycle {

  val parentPaymentIdKey = Context.key[UUID]("parentPaymentId", UUID.fromString("00000000-0000-0000-0000-000000000000"))

  def props(nodeParams: NodeParams, cfg: SendPaymentConfig, relayer: ActorRef, router: ActorRef, register: ActorRef) = Props(new MultiPartPaymentLifecycle(nodeParams, cfg, relayer, router, register))

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
   */
  case class SendMultiPartPayment(paymentSecret: ByteVector32,
                                  targetNodeId: PublicKey,
                                  totalAmount: MilliSatoshi,
                                  targetExpiry: CltvExpiry,
                                  maxAttempts: Int,
                                  assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                  routeParams: Option[RouteParams] = None,
                                  additionalTlvs: Seq[OnionTlv] = Nil) {
    require(totalAmount > 0.msat, s"total amount must be > 0")
  }

  // @formatter:off
  object BalanceTooLow extends RuntimeException("outbound capacity is too low")
  object RetryExhausted extends RuntimeException("payment attempts exhausted without success")
  // @formatter:on

  // @formatter:off
  sealed trait State
  case object WAIT_FOR_PAYMENT_REQUEST  extends State
  case object WAIT_FOR_NETWORK_STATS  extends State
  case object WAIT_FOR_CHANNEL_BALANCES  extends State
  case object PAYMENT_IN_PROGRESS extends State
  case object RETRY_WITH_UPDATED_BALANCES extends State
  case object PAYMENT_ABORTED extends State
  case object PAYMENT_SUCCEEDED extends State

  sealed trait Data
  /**
   * During initialization, we wait for a multi-part payment request containing the total amount to send.
   */
  case object WaitingForRequest extends Data
  /**
   * During initialization, we collect network statistics to help us decide how to best split a big payment.
   *
   * @param sender  the sender of the payment request.
   * @param request payment request containing the total amount to send.
   */
  case class WaitingForNetworkStats(sender: ActorRef, request: SendMultiPartPayment) extends Data
  /**
   * During initialization, we request our local channels balances.
   *
   * @param sender       the sender of the payment request.
   * @param request      payment request containing the total amount to send.
   * @param networkStats network statistics help us decide how to best split a big payment.
   */
  case class WaitingForChannelBalances(sender: ActorRef, request: SendMultiPartPayment, networkStats: Option[NetworkStats]) extends Data
  /**
   * While the payment is in progress, we listen to child payment failures. When we receive such failures, we request
   * our up-to-date local channels balances and retry the failed child payments with a potentially different route.
   *
   * @param sender            the sender of the payment request.
   * @param request           payment request containing the total amount to send.
   * @param networkStats      network statistics help us decide how to best split a big payment.
   * @param channelsCount     number of local channels.
   * @param toSend            remaining amount that should be split and sent.
   * @param remainingAttempts remaining attempts (after child payments fail).
   * @param pending           pending child payments (payment sent, we are waiting for a fulfill or a failure).
   * @param ignoreChannels    channels that should be ignored (previously returned a permanent error).
   * @param failures          previous child payment failures.
   */
  case class PaymentProgress(sender: ActorRef, request: SendMultiPartPayment, networkStats: Option[NetworkStats], channelsCount: Int, toSend: MilliSatoshi, remainingAttempts: Int, pending: Map[UUID, SendPayment], ignoreChannels: Set[ShortChannelId], failures: Seq[PaymentFailure]) extends Data
  /**
   * When we exhaust our retry attempts without success, we abort the payment.
   * Once we're in that state, we wait for all the pending child payments to settle.
   *
   * @param sender   the sender of the payment request.
   * @param request  payment request containing the total amount to send.
   * @param failures child payment failures.
   * @param pending  pending child payments (we are waiting for them to be failed downstream).
   */
  case class PaymentAborted(sender: ActorRef, request: SendMultiPartPayment, failures: Seq[PaymentFailure], pending: Set[UUID]) extends Data
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
  case class PaymentSucceeded(sender: ActorRef, request: SendMultiPartPayment, preimage: ByteVector32, parts: Seq[PartialPayment], pending: Set[UUID]) extends Data
  // @formatter:on

  /** If the payment failed immediately with a RouteNotFound, the channel we selected should be ignored in retries. */
  private def shouldBlacklistChannel(pf: PaymentFailed): Boolean = pf.failures match {
    case LocalFailure(RouteNotFound) :: Nil => true
    case _ => false
  }

  def getFirstHopShortChannelId(payment: SendPayment): ShortChannelId = {
    require(payment.routePrefix.nonEmpty, "multi-part payment must have a route prefix")
    payment.routePrefix.head.lastUpdate.shortChannelId
  }

  /**
   * If fee limits are provided, we need to divide them between all child payments. Otherwise we could end up paying
   * N * maxFee (where N is the number of child payments).
   * Note that payment retries may mess up this calculation and make us pay a bit more than our fee limit.
   *
   * TODO: @t-bast: the router should expose a GetMultiRouteRequest API; this is where fee calculations will be more
   * accurate and path-finding will be more efficient.
   */
  private def setFees(routeParams: Option[RouteParams], payments: Seq[SendPayment], paymentsCount: Int): Map[UUID, SendPayment] =
    payments.map(p => {
      val payment = routeParams match {
        case Some(routeParams) => p.copy(routeParams = Some(routeParams.copy(maxFeeBase = routeParams.maxFeeBase / paymentsCount)))
        case None => p
      }
      (UUID.randomUUID(), payment)
    }).toMap

  private def createChildPayment(nodeParams: NodeParams, request: SendMultiPartPayment, childAmount: MilliSatoshi, channel: OutgoingChannel): SendPayment = {
    SendPayment(
      request.targetNodeId,
      Onion.createMultiPartPayload(childAmount, request.totalAmount, request.targetExpiry, request.paymentSecret, request.additionalTlvs),
      request.maxAttempts,
      request.assistedRoutes,
      request.routeParams,
      ChannelHop(nodeParams.nodeId, channel.nextNodeId, channel.channelUpdate) :: Nil)
  }

  /** Compute the maximum amount we should send in a single child payment. */
  private def computeThreshold(networkStats: Option[NetworkStats], localChannels: Seq[OutgoingChannel]): MilliSatoshi = {
    import com.google.common.math.Quantiles.median

    import scala.collection.JavaConverters.asJavaCollectionConverter
    // We use network statistics with a random factor to decide on the maximum amount for child payments.
    // The current choice of parameters is completely arbitrary and could be made configurable.
    // We could also learn from previous payment failures to dynamically tweak that value.
    val maxAmount = networkStats.map(_.capacity.percentile75.toMilliSatoshi * ((75.0 + Random.nextInt(25)) / 100))
    // If network statistics aren't available, we'll use our local channels to choose a value.
    maxAmount.getOrElse({
      val localBalanceMedian = median().compute(localChannels.map(b => java.lang.Long.valueOf(b.commitments.availableBalanceForSend.toLong)).asJavaCollection)
      MilliSatoshi(localBalanceMedian.toLong)
    })
  }

  /**
   * Split a payment to a remote node inside a given channel.
   *
   * @param nodeParams         node params.
   * @param toSend             total amount to send (may exceed the channel capacity if we have other channels available).
   * @param request            parent payment request.
   * @param maxChildAmount     maximum amount of each child payment inside that channel.
   * @param maxFeeBase         maximum base fee (for the future payment route).
   * @param maxFeePct          maximum proportional fee (for the future payment route).
   * @param channel            channel to use.
   * @param channelCommitments channel commitments.
   * @param channelPayments    already-constructed child payments inside this channel.
   * @return child payments to send through this channel.
   */
  @tailrec
  private def splitInsideChannel(nodeParams: NodeParams,
                                 toSend: MilliSatoshi,
                                 request: SendMultiPartPayment,
                                 maxChildAmount: MilliSatoshi,
                                 maxFeeBase: MilliSatoshi,
                                 maxFeePct: Double,
                                 channel: OutgoingChannel,
                                 channelCommitments: Commitments,
                                 channelPayments: Seq[SendPayment]): Seq[SendPayment] = {
    // We can't use all the available balance because we need to take the fees for each child payment into account and
    // we don't know the exact fee before-hand because we don't know the rest of the route yet (so we assume the worst
    // case where the max fee is used).
    val previousFees = channelPayments.map(p => maxFeeBase.max(p.finalPayload.amount * maxFeePct))
    val totalPreviousFee = previousFees.sum
    val withFeeBase = channelCommitments.availableBalanceForSend - maxFeeBase - totalPreviousFee
    val withFeePct = channelCommitments.availableBalanceForSend * (1 - maxFeePct) - totalPreviousFee
    val childAmount = Seq(maxChildAmount, toSend - channelPayments.map(_.finalPayload.amount).sum, withFeeBase, withFeePct).min
    if (childAmount <= 0.msat) {
      channelPayments
    } else if (previousFees.nonEmpty && childAmount < previousFees.max) {
      // We avoid sending tiny HTLCs: that would be a waste of fees.
      channelPayments
    } else {
      val childPayment = createChildPayment(nodeParams, request, childAmount, channel)
      // Splitting into multiple HTLCs in the same channel will also increase the size of the CommitTx (and thus its
      // fee), which decreases the available balance.
      // We need to take that into account when trying to send multiple payments through the same channel, which is
      // why we simulate adding the HTLC to the commitments.
      val fakeOnion = OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(Sphinx.PaymentPacket.PayloadLength)(0), ByteVector32.Zeroes)
      val add = UpdateAddHtlc(channelCommitments.channelId, channelCommitments.localNextHtlcId + channelPayments.size, childAmount, ByteVector32.Zeroes, CltvExpiry(0), fakeOnion)
      val updatedCommitments = channelCommitments.addLocalProposal(add)
      splitInsideChannel(nodeParams, toSend, request, maxChildAmount, maxFeeBase, maxFeePct, channel, updatedCommitments, childPayment +: channelPayments)
    }
  }

  /**
   * Split a payment into many child payments.
   *
   * @param toSend        amount to split.
   * @param localChannels local channels balances.
   * @param request       payment request containing the total amount to send and routing hints and parameters.
   * @param randomize     randomize the channel selection.
   * @return the child payments that should be then sent to PaymentLifecycle actors.
   */
  def splitPayment(nodeParams: NodeParams, toSend: MilliSatoshi, localChannels: Seq[OutgoingChannel], networkStats: Option[NetworkStats], request: SendMultiPartPayment, randomize: Boolean): (MilliSatoshi, Seq[SendPayment]) = {
    require(toSend > 0.msat, "amount to send must be greater than 0")

    val maxFeePct = request.routeParams.map(_.maxFeePct).getOrElse(nodeParams.routerConf.searchMaxFeePct)
    val maxFeeBase = request.routeParams.map(_.maxFeeBase).getOrElse(nodeParams.routerConf.searchMaxFeeBase.toMilliSatoshi)

    @tailrec
    def split(remaining: MilliSatoshi, payments: Seq[SendPayment], channels: Seq[OutgoingChannel], splitInsideChannel: (MilliSatoshi, OutgoingChannel) => Seq[SendPayment]): Seq[SendPayment] = channels match {
      case Nil => payments
      case _ if remaining == 0.msat => payments
      case _ if remaining < 0.msat => throw new RuntimeException(s"payment splitting error: remaining amount must not be negative ($remaining): sending $toSend to ${request.targetNodeId} with local channels=${localChannels.map(_.toUsableBalance)}, current channels=${channels.map(_.toUsableBalance)}, network=${networkStats.map(_.capacity)}, fees=($maxFeeBase, $maxFeePct)")
      case channel :: rest if channel.commitments.availableBalanceForSend == 0.msat => split(remaining, payments, rest, splitInsideChannel)
      case channel :: rest =>
        val childPayments = splitInsideChannel(remaining, channel)
        split(remaining - childPayments.map(_.finalPayload.amount).sum, payments ++ childPayments, rest, splitInsideChannel)
    }

    // If we have direct channels to the target, we use them without splitting the payment inside each channel.
    val channelsToTarget = localChannels.filter(p => p.nextNodeId == request.targetNodeId).sortBy(_.commitments.availableBalanceForSend)
    val directPayments = split(toSend, Seq.empty, channelsToTarget, (remaining: MilliSatoshi, channel: OutgoingChannel) => {
      // When using direct channels to the destination, it doesn't make sense to use retries so we set maxAttempts to 1.
      createChildPayment(nodeParams, request.copy(maxAttempts = 1), remaining.min(channel.commitments.availableBalanceForSend), channel) :: Nil
    })

    // Otherwise we need to split the amount based on network statistics and pessimistic fees estimates.
    // Note that this will be handled more gracefully once this logic is migrated inside the router.
    val channels = if (randomize) {
      Random.shuffle(localChannels.filter(p => p.nextNodeId != request.targetNodeId))
    } else {
      localChannels.filter(p => p.nextNodeId != request.targetNodeId).sortBy(_.commitments.availableBalanceForSend)
    }
    val remotePayments = split(toSend - directPayments.map(_.finalPayload.amount).sum, Seq.empty, channels, (remaining: MilliSatoshi, channel: OutgoingChannel) => {
      // We re-generate a split threshold for each channel to randomize the amounts.
      val maxChildAmount = computeThreshold(networkStats, localChannels)
      splitInsideChannel(nodeParams, remaining, request, maxChildAmount, maxFeeBase, maxFeePct, channel, channel.commitments, Nil)
    })

    val childPayments = directPayments ++ remotePayments
    (toSend - childPayments.map(_.finalPayload.amount).sum, childPayments)
  }

}