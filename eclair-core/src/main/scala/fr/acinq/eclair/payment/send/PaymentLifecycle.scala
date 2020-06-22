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

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, ChannelCommandResponse, HtlcsTimedoutDownstream, Register}
import fr.acinq.eclair.crypto.{Sphinx, TransportHandler}
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.Onion._
import fr.acinq.eclair.wire._
import kamon.Kamon
import kamon.trace.Span

import scala.util.{Failure, Success}

/**
 * Created by PM on 26/08/2016.
 */

class PaymentLifecycle(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[PaymentLifecycle.State, PaymentLifecycle.Data] {

  val id = cfg.id
  val paymentHash = cfg.paymentHash
  val paymentsDb = nodeParams.db.payments
  val start = System.currentTimeMillis

  private val span = Kamon.runWithContextEntry(MultiPartPaymentLifecycle.parentPaymentIdKey, cfg.parentId) {
    val spanBuilder = if (Kamon.currentSpan().isEmpty) {
      Kamon.spanBuilder("single-payment")
    } else {
      Kamon.spanBuilder("payment-part").asChildOf(Kamon.currentSpan())
    }
    spanBuilder
      .tag(Tags.PaymentId, cfg.id.toString)
      .tag(Tags.PaymentHash, paymentHash.toHex)
      .tag(Tags.RecipientNodeId, cfg.recipientNodeId.toString())
      .tag(Tags.RecipientAmount, cfg.recipientAmount.toLong)
      .start()
  }

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(c: SendPaymentToRoute, WaitingForRequest) =>
      span.tag(Tags.TargetNodeId, c.targetNodeId.toString())
      span.tag(Tags.Amount, c.finalPayload.amount.toLong)
      span.tag(Tags.TotalAmount, c.finalPayload.totalAmount.toLong)
      span.tag(Tags.Expiry, c.finalPayload.expiry.toLong)
      log.debug("sending {} to route {}", c.finalPayload.amount, c.printRoute())
      val send = SendPayment(c.targetNodeId, c.finalPayload, maxAttempts = 1, assistedRoutes = c.assistedRoutes)
      c.route.fold(
        hops => router ! FinalizeRoute(c.finalPayload.amount, hops, c.assistedRoutes),
        route => self ! RouteResponse(route :: Nil)
      )
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, System.currentTimeMillis, cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, send, Nil, Ignore.empty)

    case Event(c: SendPayment, WaitingForRequest) =>
      span.tag(Tags.TargetNodeId, c.targetNodeId.toString())
      span.tag(Tags.Amount, c.finalPayload.amount.toLong)
      span.tag(Tags.TotalAmount, c.finalPayload.totalAmount.toLong)
      span.tag(Tags.Expiry, c.finalPayload.expiry.toLong)
      log.debug("sending {} to {}", c.finalPayload.amount, c.targetNodeId)
      router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.getMaxFee(nodeParams), c.assistedRoutes, routeParams = c.routeParams)
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, System.currentTimeMillis, cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, Nil, Ignore.empty)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(route +: _), WaitingForRoute(s, c, failures, ignore)) =>
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${route.printNodes()} channels=${route.printChannels()}")
      val (cmd, sharedSecrets) = OutgoingPacket.buildCommand(cfg.upstream, paymentHash, route.hops, c.finalPayload)
      register ! Register.ForwardShortId(route.hops.head.lastUpdate.shortChannelId, cmd)
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, c, cmd, failures, sharedSecrets, ignore, route)

    case Event(Status.Failure(t), WaitingForRoute(s, _, failures, _)) =>
      log.warning("router error: {}", t.getMessage)
      Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(Nil, t))).increment()
      onFailure(s, PaymentFailed(id, paymentHash, failures :+ LocalFailure(Nil, t)))
      myStop()
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event(ChannelCommandResponse.Ok, _) => stay

    case Event(fulfill: Relayer.ForwardFulfill, WaitingForComplete(s, c, cmd, failures, _, _, route)) =>
      Metrics.PaymentAttempt.withTag(Tags.MultiPart, value = false).record(failures.size + 1)
      val p = PartialPayment(id, c.finalPayload.amount, cmd.amount - c.finalPayload.amount, fulfill.htlc.channelId, Some(cfg.fullRoute(route)))
      onSuccess(s, cfg.createPaymentSent(fulfill.paymentPreimage, p :: Nil))
      myStop()

    case Event(forwardFail: Relayer.ForwardFail, _) =>
      forwardFail match {
        case Relayer.ForwardRemoteFail(fail, _, _) => self ! fail
        case Relayer.ForwardRemoteFailMalformed(fail, _, _) => self ! fail
        case Relayer.ForwardOnChainFail(cause, _, _) => self ! Status.Failure(cause)
      }
      stay

    case Event(fail: UpdateFailHtlc, data@WaitingForComplete(s, c, _, failures, sharedSecrets, ignore, route)) =>
      (Sphinx.FailurePacket.decrypt(fail.reason, sharedSecrets) match {
        case success@Success(e) =>
          Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(RemoteFailure(Nil, e))).increment()
          success
        case failure@Failure(_) =>
          Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(UnreadableRemoteFailure(Nil))).increment()
          failure
      }) match {
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) if nodeId == c.targetNodeId =>
          // if destination node returns an error, we fail the payment immediately
          log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
          onFailure(s, PaymentFailed(id, paymentHash, failures :+ RemoteFailure(cfg.fullRoute(route), e)))
          myStop()
        case res if failures.size + 1 >= c.maxAttempts =>
          // otherwise we never try more than maxAttempts, no matter the kind of error returned
          val failure = res match {
            case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
              log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
              RemoteFailure(cfg.fullRoute(route), e)
            case Failure(t) =>
              log.warning(s"cannot parse returned error: ${t.getMessage}")
              UnreadableRemoteFailure(cfg.fullRoute(route))
          }
          log.warning(s"too many failed attempts, failing the payment")
          onFailure(s, PaymentFailed(id, paymentHash, failures :+ failure))
          myStop()
        case Failure(t) =>
          log.warning(s"cannot parse returned error: ${t.getMessage}, route=${route.printNodes()}")
          val failure = UnreadableRemoteFailure(cfg.fullRoute(route))
          retry(failure, data)
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Node)) =>
          log.info(s"received 'Node' type error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
          val failure = RemoteFailure(cfg.fullRoute(route), e)
          retry(failure, data)
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update)) =>
          log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
          val ignore1 = if (Announcements.checkSig(failureMessage.update, nodeId)) {
            route.getChannelUpdateForNode(nodeId) match {
              case Some(u) if u.shortChannelId != failureMessage.update.shortChannelId =>
                // it is possible that nodes in the route prefer using a different channel (to the same N+1 node) than the one we requested, that's fine
                log.info(s"received an update for a different channel than the one we asked: requested=${u.shortChannelId} actual=${failureMessage.update.shortChannelId} update=${failureMessage.update}")
              case Some(u) if Announcements.areSame(u, failureMessage.update) =>
                // node returned the exact same update we used, this can happen e.g. if the channel is imbalanced
                // in that case, let's temporarily exclude the channel from future routes, giving it time to recover
                log.info(s"received exact same update from nodeId=$nodeId, excluding the channel from futures routes")
                val nextNodeId = route.hops.find(_.nodeId == nodeId).get.nextNodeId
                router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
              case Some(u) if PaymentFailure.hasAlreadyFailedOnce(nodeId, failures) =>
                // this node had already given us a new channel update and is still unhappy, it is probably messing with us, let's exclude it
                log.warning(s"it is the second time nodeId=$nodeId answers with a new update, excluding it: old=$u new=${failureMessage.update}")
                val nextNodeId = route.hops.find(_.nodeId == nodeId).get.nextNodeId
                router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
              case Some(u) =>
                log.info(s"got a new update for shortChannelId=${u.shortChannelId}: old=$u new=${failureMessage.update}")
              case None =>
                log.error(s"couldn't find a channel update for node=$nodeId, this should never happen")
            }
            // in any case, we forward the update to the router
            router ! failureMessage.update
            // we also update assisted routes, because they take precedence over the router's routing table
            val assistedRoutes1 = c.assistedRoutes.map(_.map {
              case extraHop: ExtraHop if extraHop.shortChannelId == failureMessage.update.shortChannelId => extraHop.copy(
                cltvExpiryDelta = failureMessage.update.cltvExpiryDelta,
                feeBase = failureMessage.update.feeBaseMsat,
                feeProportionalMillionths = failureMessage.update.feeProportionalMillionths
              )
              case extraHop => extraHop
            })
            // let's try again, router will have updated its state
            router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.getMaxFee(nodeParams), assistedRoutes1, ignore, c.routeParams)
            ignore
          } else {
            // this node is fishy, it gave us a bad sig!! let's filter it out
            log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update}")
            router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.getMaxFee(nodeParams), c.assistedRoutes, ignore + nodeId, c.routeParams)
            ignore + nodeId
          }
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(cfg.fullRoute(route), e), ignore1)
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
          log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
          val failure = RemoteFailure(cfg.fullRoute(route), e)
          retry(failure, data)
      }

    case Event(fail: UpdateFailMalformedHtlc, _) =>
      Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType.Malformed).increment()
      log.info(s"first node in the route couldn't parse our htlc: fail=$fail")
      // this is a corner case, that can only happen when the *first* node in the route cannot parse the onion
      // (if this happens higher up in the route, the error would be wrapped in an UpdateFailHtlc and handled above)
      // let's consider it a local error and treat is as such
      self ! Status.Failure(new RuntimeException("first hop returned an UpdateFailMalformedHtlc message"))
      stay

    case Event(Status.Failure(t), data@WaitingForComplete(s, c, _, failures, _, _, hops)) =>
      Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(cfg.fullRoute(hops), t))).increment()
      val isFatal = failures.size + 1 >= c.maxAttempts || // retries exhausted
        t.isInstanceOf[HtlcsTimedoutDownstream] // htlc timed out so retrying won't help, we need to re-compute cltvs
      if (isFatal) {
        onFailure(s, PaymentFailed(id, paymentHash, failures :+ LocalFailure(cfg.fullRoute(hops), t)))
        myStop()
      } else {
        log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
        val failure = LocalFailure(cfg.fullRoute(hops), t)
        retry(failure, data)
      }
  }

  private var stateSpan: Option[Span] = None

  onTransition {
    case _ -> state2 =>
      // whenever there is a transition we stop the current span and start a new one, this way we can track each state
      val stateSpanBuilder = Kamon.spanBuilder(state2.toString).asChildOf(span)
      nextStateData match {
        case d: WaitingForRoute =>
          // this means that previous state was WAITING_FOR_COMPLETE
          d.failures.lastOption.foreach(failure => stateSpan.foreach(span => KamonExt.failSpan(span, failure)))
        case d: WaitingForComplete =>
          stateSpanBuilder.tag("route", s"${cfg.fullRoute(d.route).map(_.nextNodeId).mkString("->")}")
        case _ => ()
      }
      stateSpan.foreach(_.finish())
      stateSpan = Some(stateSpanBuilder.start())
  }

  whenUnhandled {
    case Event(_: TransportHandler.ReadAck, _) => stay // ignored, router replies with this when we forward a channel_update
  }

  private def retry(failure: PaymentFailure, data: WaitingForComplete): FSM.State[PaymentLifecycle.State, PaymentLifecycle.Data] = {
    val ignore1 = PaymentFailure.updateIgnored(failure, data.ignore)
    router ! RouteRequest(nodeParams.nodeId, data.c.targetNodeId, data.c.finalPayload.amount, data.c.getMaxFee(nodeParams), data.c.assistedRoutes, ignore1, data.c.routeParams)
    goto(WAITING_FOR_ROUTE) using WaitingForRoute(data.sender, data.c, data.failures :+ failure, ignore1)
  }

  private def myStop(): State = {
    stateSpan.foreach(_.finish())
    span.finish()
    stop(FSM.Normal)
  }

  private def onSuccess(sender: ActorRef, result: PaymentSent): Unit = {
    if (cfg.storeInDb) paymentsDb.updateOutgoingPayment(result)
    sender ! result
    if (cfg.publishEvent) context.system.eventStream.publish(result)
    Metrics.SentPaymentDuration
      .withTag(Tags.MultiPart, if (cfg.id != cfg.parentId) Tags.MultiPartType.Child else Tags.MultiPartType.Disabled)
      .withTag(Tags.Success, value = true)
      .record(System.currentTimeMillis - start, TimeUnit.MILLISECONDS)
  }

  private def onFailure(sender: ActorRef, result: PaymentFailed): Unit = {
    span.fail("payment failed")
    if (cfg.storeInDb) paymentsDb.updateOutgoingPayment(result)
    sender ! result
    if (cfg.publishEvent) context.system.eventStream.publish(result)
    Metrics.SentPaymentDuration
      .withTag(Tags.MultiPart, if (cfg.id != cfg.parentId) Tags.MultiPartType.Child else Tags.MultiPartType.Disabled)
      .withTag(Tags.Success, value = false)
      .record(System.currentTimeMillis - start, TimeUnit.MILLISECONDS)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), parentPaymentId_opt = Some(cfg.parentId), paymentId_opt = Some(id), paymentHash_opt = Some(paymentHash))
  }

  initialize()
}

object PaymentLifecycle {

  def props(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) = Props(new PaymentLifecycle(nodeParams, cfg, router, register))

  /**
   * Send a payment to a pre-defined route without running the path-finding algorithm.
   *
   * @param route        payment route to use.
   * @param finalPayload onion payload for the target node.
   */
  case class SendPaymentToRoute(route: Either[Seq[PublicKey], Route], finalPayload: FinalPayload, assistedRoutes: Seq[Seq[ExtraHop]] = Nil) {
    require(route.fold(_.nonEmpty, _.hops.nonEmpty), "payment route must not be empty")

    val targetNodeId = route.fold(_.last, _.hops.last.nextNodeId)

    def printRoute(): String = route.fold(nodes => nodes, _.hops.map(_.nextNodeId)).mkString("->")
  }

  /**
   * Send a payment to a given node. A path-finding algorithm will run to find a suitable payment route.
   *
   * @param targetNodeId   target node (may be the final recipient when using source-routing, or the first trampoline
   *                       node when using trampoline).
   * @param finalPayload   onion payload for the target node.
   * @param maxAttempts    maximum number of retries.
   * @param assistedRoutes routing hints (usually from a Bolt 11 invoice).
   * @param routeParams    parameters to fine-tune the routing algorithm.
   */
  case class SendPayment(targetNodeId: PublicKey,
                         finalPayload: FinalPayload,
                         maxAttempts: Int,
                         assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                         routeParams: Option[RouteParams] = None) {
    require(finalPayload.amount > 0.msat, s"amount must be > 0")

    def getMaxFee(nodeParams: NodeParams): MilliSatoshi =
      routeParams.getOrElse(RouteCalculation.getDefaultRouteParams(nodeParams.routerConf)).getMaxFee(finalPayload.amount)

  }

  // @formatter:off
  sealed trait Data
  case object WaitingForRequest extends Data
  case class WaitingForRoute(sender: ActorRef, c: SendPayment, failures: Seq[PaymentFailure], ignore: Ignore) extends Data
  case class WaitingForComplete(sender: ActorRef, c: SendPayment, cmd: CMD_ADD_HTLC, failures: Seq[PaymentFailure], sharedSecrets: Seq[(ByteVector32, PublicKey)], ignore: Ignore, route: Route) extends Data

  sealed trait State
  case object WAITING_FOR_REQUEST extends State
  case object WAITING_FOR_ROUTE extends State
  case object WAITING_FOR_PAYMENT_COMPLETE extends State
  // @formatter:on

}
