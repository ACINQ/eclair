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
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{Sphinx, TransportHandler}
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.protocol.Onion._
import fr.acinq.eclair.wire.protocol._

import java.util.concurrent.TimeUnit
import scala.util.{Failure, Success}

/**
 * Created by PM on 26/08/2016.
 */

class PaymentLifecycle(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[PaymentLifecycle.State, PaymentLifecycle.Data] {

  private val id = cfg.id
  private val paymentHash = cfg.paymentHash
  private val paymentsDb = nodeParams.db.payments
  private val start = System.currentTimeMillis

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(c: SendPaymentToRoute, WaitingForRequest) =>
      log.debug("sending {} to route {}", c.finalPayload.amount, c.printRoute())
      val send = SendPayment(c.replyTo, c.targetNodeId, c.finalPayload, maxAttempts = 1, assistedRoutes = c.assistedRoutes)
      c.route.fold(
        hops => router ! FinalizeRoute(c.finalPayload.amount, hops, c.assistedRoutes, paymentContext = Some(cfg.paymentContext)),
        route => self ! RouteResponse(route :: Nil)
      )
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, System.currentTimeMillis, cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(send, Nil, Ignore.empty)

    case Event(c: SendPayment, WaitingForRequest) =>
      log.debug("sending {} to {}", c.finalPayload.amount, c.targetNodeId)
      router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.getMaxFee(nodeParams), c.assistedRoutes, routeParams = c.routeParams, paymentContext = Some(cfg.paymentContext))
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, System.currentTimeMillis, cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(c, Nil, Ignore.empty)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(route +: _), WaitingForRoute(c, failures, ignore)) =>
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${route.printNodes()} channels=${route.printChannels()}")
      val (cmd, sharedSecrets) = OutgoingPacket.buildCommand(self, cfg.upstream, paymentHash, route.hops, c.finalPayload)
      register ! Register.ForwardShortId(self, route.hops.head.lastUpdate.shortChannelId, cmd)
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(c, cmd, failures, sharedSecrets, ignore, route)

    case Event(Status.Failure(t), WaitingForRoute(c, failures, _)) =>
      log.warning("router error: {}", t.getMessage)
      Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(Nil, t))).increment()
      onFailure(c.replyTo, PaymentFailed(id, paymentHash, failures :+ LocalFailure(Nil, t)))
      stop(FSM.Normal)
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event(RES_SUCCESS(_: CMD_ADD_HTLC, _), _) => stay

    case Event(RES_ADD_FAILED(_, t: ChannelException, _), d: WaitingForComplete) =>
      handleLocalFail(d, t, isFatal = false)

    case Event(_: Register.ForwardShortIdFailure[_], d: WaitingForComplete) =>
      handleLocalFail(d, DisconnectedException, isFatal = false)

    case Event(RES_ADD_SETTLED(_, htlc, fulfill: HtlcResult.Fulfill), d: WaitingForComplete) =>
      Metrics.PaymentAttempt.withTag(Tags.MultiPart, value = false).record(d.failures.size + 1)
      val p = PartialPayment(id, d.c.finalPayload.amount, d.cmd.amount - d.c.finalPayload.amount, htlc.channelId, Some(cfg.fullRoute(d.route)))
      onSuccess(d.c.replyTo, cfg.createPaymentSent(fulfill.paymentPreimage, p :: Nil))
      stop(FSM.Normal)

    case Event(RES_ADD_SETTLED(_, _, fail: HtlcResult.Fail), d: WaitingForComplete) =>
      fail match {
        case HtlcResult.RemoteFail(fail) => handleRemoteFail(d, fail)
        case HtlcResult.RemoteFailMalformed(fail) =>
          log.info(s"first node in the route couldn't parse our htlc: fail=$fail")
          // this is a corner case, that can only happen when the *first* node in the route cannot parse the onion
          // (if this happens higher up in the route, the error would be wrapped in an UpdateFailHtlc and handled above)
          // let's consider it a local error and treat is as such
          handleLocalFail(d, UpdateMalformedException, isFatal = false)
        case HtlcResult.OnChainFail(cause) =>
          // if the outgoing htlc is being resolved on chain, we treat it like a local error but we cannot retry
          handleLocalFail(d, cause, isFatal = true)
        case HtlcResult.ChannelFailureBeforeSigned =>
          // if the outgoing htlc is being resolved on chain, we treat it like a local error but we cannot retry
          handleLocalFail(d, ChannelFailureException, isFatal = true)
        case HtlcResult.DisconnectedBeforeSigned(_) =>
          // a disconnection occured before the outgoing htlc got signed
          // again, we consider it a local error and treat is as such
          handleLocalFail(d, DisconnectedException, isFatal = false)
      }
  }

  whenUnhandled {
    case Event(_: TransportHandler.ReadAck, _) => stay // ignored, router replies with this when we forward a channel_update
  }

  private def retry(failure: PaymentFailure, data: WaitingForComplete): FSM.State[PaymentLifecycle.State, PaymentLifecycle.Data] = {
    val ignore1 = PaymentFailure.updateIgnored(failure, data.ignore)
    router ! RouteRequest(nodeParams.nodeId, data.c.targetNodeId, data.c.finalPayload.amount, data.c.getMaxFee(nodeParams), data.c.assistedRoutes, ignore1, data.c.routeParams, paymentContext = Some(cfg.paymentContext))
    goto(WAITING_FOR_ROUTE) using WaitingForRoute(data.c, data.failures :+ failure, ignore1)
  }

  /**
   * Handle a local error, and stop or retry
   */
  private def handleLocalFail(d: WaitingForComplete, t: Throwable, isFatal: Boolean) = {
    t match {
      case UpdateMalformedException => Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType.Malformed).increment()
      case _ => Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(cfg.fullRoute(d.route), t))).increment()
    }
    // we only retry if the error isn't fatal, and we haven't exhausted the max number of retried
    val doRetry = !isFatal && (d.failures.size + 1 < d.c.maxAttempts)
    val localFailure = LocalFailure(cfg.fullRoute(d.route), t)
    if (doRetry) {
      log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
      retry(localFailure, d)
    } else {
      onFailure(d.c.replyTo, PaymentFailed(id, paymentHash, d.failures :+ localFailure))
      stop(FSM.Normal)
    }
  }

  private def handleRemoteFail(d: WaitingForComplete, fail: UpdateFailHtlc) = {
    import d._
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
        onFailure(c.replyTo, PaymentFailed(id, paymentHash, failures :+ RemoteFailure(cfg.fullRoute(route), e)))
        stop(FSM.Normal)
      case res if failures.size + 1 >= c.maxAttempts =>
        // otherwise we never try more than maxAttempts, no matter the kind of error returned
        val failure = res match {
          case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
            log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
            failureMessage match {
              case failureMessage: Update => handleUpdate(nodeId, failureMessage, d)
              case _ =>
            }
            RemoteFailure(cfg.fullRoute(route), e)
          case Failure(t) =>
            log.warning(s"cannot parse returned error ${fail.reason.toHex} with sharedSecrets=$sharedSecrets: ${t.getMessage}")
            UnreadableRemoteFailure(cfg.fullRoute(route))
        }
        log.warning(s"too many failed attempts, failing the payment")
        onFailure(c.replyTo, PaymentFailed(id, paymentHash, failures :+ failure))
        stop(FSM.Normal)
      case Failure(t) =>
        log.warning(s"cannot parse returned error: ${t.getMessage}, route=${route.printNodes()}")
        val failure = UnreadableRemoteFailure(cfg.fullRoute(route))
        retry(failure, d)
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Node)) =>
        log.info(s"received 'Node' type error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
        val failure = RemoteFailure(cfg.fullRoute(route), e)
        retry(failure, d)
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update)) =>
        log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
        val failure = RemoteFailure(cfg.fullRoute(route), e)
        val ignore1 = if (Announcements.checkSig(failureMessage.update, nodeId)) {
          val assistedRoutes1 = handleUpdate(nodeId, failureMessage, d)
          val ignore1 = PaymentFailure.updateIgnored(failure, ignore)
          // let's try again, router will have updated its state
          router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.getMaxFee(nodeParams), assistedRoutes1, ignore1, c.routeParams, paymentContext = Some(cfg.paymentContext))
          ignore1
        } else {
          // this node is fishy, it gave us a bad sig!! let's filter it out
          log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update}")
          router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.getMaxFee(nodeParams), c.assistedRoutes, ignore + nodeId, c.routeParams, paymentContext = Some(cfg.paymentContext))
          ignore + nodeId
        }
        goto(WAITING_FOR_ROUTE) using WaitingForRoute(c, failures :+ failure, ignore1)
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
        log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
        val failure = RemoteFailure(cfg.fullRoute(route), e)
        retry(failure, d)
    }
  }

  /**
   * Apply the channel update to our routing table.
   *
   * @return updated routing hints if applicable.
   */
  private def handleUpdate(nodeId: PublicKey, failure: Update, data: WaitingForComplete): Seq[Seq[ExtraHop]] = {
    data.route.getChannelUpdateForNode(nodeId) match {
      case Some(u) if u.shortChannelId != failure.update.shortChannelId =>
        // it is possible that nodes in the route prefer using a different channel (to the same N+1 node) than the one we requested, that's fine
        log.info(s"received an update for a different channel than the one we asked: requested=${u.shortChannelId} actual=${failure.update.shortChannelId} update=${failure.update}")
      case Some(u) if Announcements.areSame(u, failure.update) =>
        // node returned the exact same update we used, this can happen e.g. if the channel is imbalanced
        // in that case, let's temporarily exclude the channel from future routes, giving it time to recover
        log.info(s"received exact same update from nodeId=$nodeId, excluding the channel from futures routes")
        val nextNodeId = data.route.hops.find(_.nodeId == nodeId).get.nextNodeId
        router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
      case Some(u) if PaymentFailure.hasAlreadyFailedOnce(nodeId, data.failures) =>
        // this node had already given us a new channel update and is still unhappy, it is probably messing with us, let's exclude it
        log.warning(s"it is the second time nodeId=$nodeId answers with a new update, excluding it: old=$u new=${failure.update}")
        val nextNodeId = data.route.hops.find(_.nodeId == nodeId).get.nextNodeId
        router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
      case Some(u) =>
        log.info(s"got a new update for shortChannelId=${u.shortChannelId}: old=$u new=${failure.update}")
      case None =>
        log.error(s"couldn't find a channel update for node=$nodeId, this should never happen")
    }
    // in any case, we forward the update to the router: if the channel is disabled, the router will remove it from its routing table
    router ! failure.update
    // we return updated assisted routes: they take precedence over the router's routing table
    if (Announcements.isEnabled(failure.update.channelFlags)) {
      data.c.assistedRoutes.map(_.map {
        case extraHop: ExtraHop if extraHop.shortChannelId == failure.update.shortChannelId => extraHop.copy(
          cltvExpiryDelta = failure.update.cltvExpiryDelta,
          feeBase = failure.update.feeBaseMsat,
          feeProportionalMillionths = failure.update.feeProportionalMillionths
        )
        case extraHop => extraHop
      })
    } else {
      // if the channel is disabled, we temporarily exclude it: this is necessary because the routing hint doesn't contain
      // channel flags to indicate that it's disabled
      data.c.assistedRoutes.flatMap(r => RouteCalculation.toChannelDescs(r, data.c.targetNodeId))
        .find(_.shortChannelId == failure.update.shortChannelId)
        .foreach(desc => router ! ExcludeChannel(desc)) // we want the exclusion to be router-wide so that sister payments in the case of MPP are aware the channel is faulty
      data.c.assistedRoutes
    }
  }

  private def onSuccess(replyTo: ActorRef, result: PaymentSent): Unit = {
    if (cfg.storeInDb) paymentsDb.updateOutgoingPayment(result)
    replyTo ! result
    if (cfg.publishEvent) context.system.eventStream.publish(result)
    Metrics.SentPaymentDuration
      .withTag(Tags.MultiPart, if (cfg.id != cfg.parentId) Tags.MultiPartType.Child else Tags.MultiPartType.Disabled)
      .withTag(Tags.Success, value = true)
      .record(System.currentTimeMillis - start, TimeUnit.MILLISECONDS)
  }

  private def onFailure(replyTo: ActorRef, result: PaymentFailed): Unit = {
    if (cfg.storeInDb) paymentsDb.updateOutgoingPayment(result)
    replyTo ! result
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
   * Send a payment to a given route.
   *
   * @param route        payment route to use.
   * @param finalPayload onion payload for the target node.
   */
  case class SendPaymentToRoute(replyTo: ActorRef, route: Either[PredefinedRoute, Route], finalPayload: FinalPayload, assistedRoutes: Seq[Seq[ExtraHop]] = Nil) {
    require(route.fold(!_.isEmpty, _.hops.nonEmpty), "payment route must not be empty")

    val targetNodeId = route.fold(_.targetNodeId, _.hops.last.nextNodeId)

    def printRoute(): String = route match {
      case Left(PredefinedChannelRoute(_, channels)) => channels.mkString("->")
      case Left(PredefinedNodeRoute(nodes)) => nodes.mkString("->")
      case Right(route) => route.hops.map(_.nextNodeId).mkString("->")
    }
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
  case class SendPayment(replyTo: ActorRef,
                         targetNodeId: PublicKey,
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
  case class WaitingForRoute(c: SendPayment, failures: Seq[PaymentFailure], ignore: Ignore) extends Data
  case class WaitingForComplete(c: SendPayment, cmd: CMD_ADD_HTLC, failures: Seq[PaymentFailure], sharedSecrets: Seq[(ByteVector32, PublicKey)], ignore: Ignore, route: Route) extends Data

  sealed trait State
  case object WAITING_FOR_REQUEST extends State
  case object WAITING_FOR_ROUTE extends State
  case object WAITING_FOR_PAYMENT_COMPLETE extends State

  /** custom exceptions to handle corner cases */
  case object UpdateMalformedException extends RuntimeException("first hop returned an UpdateFailMalformedHtlc message")
  case object ChannelFailureException extends RuntimeException("a channel failure occured with the first hop")
  case object DisconnectedException extends RuntimeException("a disconnection occurred with the first hop")
  // @formatter:on

}
