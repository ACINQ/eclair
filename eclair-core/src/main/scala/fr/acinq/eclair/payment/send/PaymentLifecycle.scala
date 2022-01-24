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
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.protocol.PaymentOnion._
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
  private val start = TimestampMilli.now()

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(c: SendPaymentToRoute, WaitingForRequest) =>
      log.debug("sending {} to route {}", c.finalPayload.amount, c.printRoute())
      c.route.fold(
        hops => router ! FinalizeRoute(c.finalPayload.amount, hops, c.assistedRoutes, paymentContext = Some(cfg.paymentContext)),
        route => self ! RouteResponse(route :: Nil)
      )
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, TimestampMilli.now(), cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(c, Nil, Ignore.empty)

    case Event(c: SendPaymentToNode, WaitingForRequest) =>
      log.debug("sending {} to {}", c.finalPayload.amount, c.targetNodeId)
      router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.maxFee, c.assistedRoutes, routeParams = c.routeParams, paymentContext = Some(cfg.paymentContext))
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, TimestampMilli.now(), cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(c, Nil, Ignore.empty)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(route +: _), WaitingForRoute(c, failures, ignore)) =>
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${route.printNodes()} channels=${route.printChannels()}")
      OutgoingPaymentPacket.buildCommand(self, cfg.upstream, paymentHash, route.hops, c.finalPayload) match {
        case Success((cmd, sharedSecrets)) =>
          register ! Register.ForwardShortId(self, route.hops.head.lastUpdate.shortChannelId, cmd)
          goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(c, cmd, failures, sharedSecrets, ignore, route)
        case Failure(t) =>
          log.warning("cannot send outgoing payment: {}", t.getMessage)
          Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(c.finalPayload.amount, Nil, t))).increment()
          myStop(c, Left(PaymentFailed(id, paymentHash, failures :+ LocalFailure(c.finalPayload.amount, Nil, t))))
      }

    case Event(Status.Failure(t), WaitingForRoute(c, failures, _)) =>
      log.warning("router error: {}", t.getMessage)
      Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(c.finalPayload.amount, Nil, t))).increment()
      myStop(c, Left(PaymentFailed(id, paymentHash, failures :+ LocalFailure(c.finalPayload.amount, Nil, t))))
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event(RES_SUCCESS(_: CMD_ADD_HTLC, _), _) => stay()

    case Event(RES_ADD_FAILED(_, t: ChannelException, _), d: WaitingForComplete) =>
      handleLocalFail(d, t, isFatal = false)

    case Event(_: Register.ForwardShortIdFailure[_], d: WaitingForComplete) =>
      handleLocalFail(d, DisconnectedException, isFatal = false)

    case Event(RES_ADD_SETTLED(_, htlc, fulfill: HtlcResult.Fulfill), d: WaitingForComplete) =>
      Metrics.PaymentAttempt.withTag(Tags.MultiPart, value = false).record(d.failures.size + 1)
      val p = PartialPayment(id, d.c.finalPayload.amount, d.cmd.amount - d.c.finalPayload.amount, htlc.channelId, Some(cfg.fullRoute(d.route)))
      myStop(d.c, Right(cfg.createPaymentSent(fulfill.paymentPreimage, p :: Nil)))

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
          // the channel that we wanted to use for the outgoing htlc has failed before that htlc was signed, we may
          // retry with another channel
          handleLocalFail(d, ChannelFailureException, isFatal = false)
        case HtlcResult.DisconnectedBeforeSigned(_) =>
          // a disconnection occured before the outgoing htlc got signed
          // again, we consider it a local error and treat is as such
          handleLocalFail(d, DisconnectedException, isFatal = false)
      }
  }

  whenUnhandled {
    case Event(_: TransportHandler.ReadAck, _) => stay() // ignored, router replies with this when we forward a channel_update
  }

  private def retry(failure: PaymentFailure, data: WaitingForComplete): FSM.State[PaymentLifecycle.State, PaymentLifecycle.Data] = {
    data.c match {
      case sendPaymentToNode: SendPaymentToNode =>
        val ignore1 = PaymentFailure.updateIgnored(failure, data.ignore)
        router ! RouteRequest(nodeParams.nodeId, data.c.targetNodeId, data.c.finalPayload.amount, sendPaymentToNode.maxFee, data.c.assistedRoutes, ignore1, sendPaymentToNode.routeParams, paymentContext = Some(cfg.paymentContext))
        goto(WAITING_FOR_ROUTE) using WaitingForRoute(data.c, data.failures :+ failure, ignore1)
      case _: SendPaymentToRoute =>
        log.error("unexpected retry during SendPaymentToRoute")
        stop(FSM.Normal)
    }
  }

  /**
   * Handle a local error, and stop or retry
   */
  private def handleLocalFail(d: WaitingForComplete, t: Throwable, isFatal: Boolean) = {
    t match {
      case UpdateMalformedException => Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType.Malformed).increment()
      case _ => Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(d.c.finalPayload.amount, cfg.fullRoute(d.route), t))).increment()
    }
    // we only retry if the error isn't fatal, and we haven't exhausted the max number of retried
    val doRetry = !isFatal && (d.failures.size + 1 < d.c.maxAttempts)
    val localFailure = LocalFailure(d.c.finalPayload.amount, cfg.fullRoute(d.route), t)
    if (doRetry) {
      log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
      retry(localFailure, d)
    } else {
      myStop(d.c, Left(PaymentFailed(id, paymentHash, d.failures :+ localFailure)))
    }
  }

  private def handleRemoteFail(d: WaitingForComplete, fail: UpdateFailHtlc) = {
    import d._
    (Sphinx.FailurePacket.decrypt(fail.reason, sharedSecrets) match {
      case success@Success(e) =>
        Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(RemoteFailure(d.c.finalPayload.amount, Nil, e))).increment()
        success
      case failure@Failure(_) =>
        Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(UnreadableRemoteFailure(d.c.finalPayload.amount, Nil))).increment()
        failure
    }) match {
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) if nodeId == c.targetNodeId =>
        // if destination node returns an error, we fail the payment immediately
        log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
        myStop(c, Left(PaymentFailed(id, paymentHash, failures :+ RemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route), e))))
      case res if failures.size + 1 >= c.maxAttempts =>
        // otherwise we never try more than maxAttempts, no matter the kind of error returned
        val failure = res match {
          case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
            log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
            failureMessage match {
              case failureMessage: Update => handleUpdate(nodeId, failureMessage, d)
              case _ =>
            }
            RemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route), e)
          case Failure(t) =>
            log.warning(s"cannot parse returned error ${fail.reason.toHex} with sharedSecrets=$sharedSecrets: ${t.getMessage}")
            UnreadableRemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route))
        }
        log.warning(s"too many failed attempts, failing the payment")
        myStop(c, Left(PaymentFailed(id, paymentHash, failures :+ failure)))
      case Failure(t) =>
        log.warning(s"cannot parse returned error: ${t.getMessage}, route=${route.printNodes()}")
        val failure = UnreadableRemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route))
        retry(failure, d)
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Node)) =>
        log.info(s"received 'Node' type error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
        val failure = RemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route), e)
        retry(failure, d)
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update)) =>
        log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
        val failure = RemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route), e)
        if (Announcements.checkSig(failureMessage.update, nodeId)) {
          val assistedRoutes1 = handleUpdate(nodeId, failureMessage, d)
          val ignore1 = PaymentFailure.updateIgnored(failure, ignore)
          // let's try again, router will have updated its state
          c match {
            case _: SendPaymentToRoute =>
              log.error("unexpected retry during SendPaymentToRoute")
              stop(FSM.Normal)
            case c: SendPaymentToNode =>
              router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.maxFee, assistedRoutes1, ignore1, c.routeParams, paymentContext = Some(cfg.paymentContext))
              goto(WAITING_FOR_ROUTE) using WaitingForRoute(c, failures :+ failure, ignore1)
          }
        } else {
          // this node is fishy, it gave us a bad sig!! let's filter it out
          log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update}")
          c match {
            case _: SendPaymentToRoute =>
              log.error("unexpected retry during SendPaymentToRoute")
              stop(FSM.Normal)
            case c: SendPaymentToNode =>
              router ! RouteRequest(nodeParams.nodeId, c.targetNodeId, c.finalPayload.amount, c.maxFee, c.assistedRoutes, ignore + nodeId, c.routeParams, paymentContext = Some(cfg.paymentContext))
              goto(WAITING_FOR_ROUTE) using WaitingForRoute(c, failures :+ failure, ignore + nodeId)
          }
        }
      case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
        log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
        val failure = RemoteFailure(d.c.finalPayload.amount, cfg.fullRoute(route), e)
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
    if (failure.update.channelFlags.isEnabled) {
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

  def myStop(request: SendPayment, result: Either[PaymentFailed, PaymentSent]): State = {
    if (cfg.storeInDb) {
      result match {
        case Left(paymentFailed: PaymentFailed) =>
          paymentsDb.updateOutgoingPayment(paymentFailed)
        case Right(paymentSent: PaymentSent) =>
          paymentsDb.updateOutgoingPayment(paymentSent)
      }
    }
    val event = result match {
      case Left(event) => event
      case Right(event) => event
    }
    request.replyTo ! event
    if (cfg.publishEvent) context.system.eventStream.publish(event)
    val status = result match {
      case Right(_: PaymentSent) => "SUCCESS"
      case Left(f: PaymentFailed) =>
        if (f.failures.exists({ case r: RemoteFailure => r.e.originNode == cfg.recipientNodeId case _ => false })) {
          "RECIPIENT_FAILURE"
        } else {
          "FAILURE"
        }
    }
    val now = TimestampMilli.now()
    val duration = now - start
    if (cfg.recordPathFindingMetrics) {
      val fees = result match {
        case Right(paymentSent) =>
          val localFees = cfg.upstream match {
            case _: Upstream.Local => 0.msat // no local fees when we are the origin of the payment
            case _: Upstream.Trampoline =>
              // in case of a relayed payment, we need to take into account the fee of the first channels
              paymentSent.parts.collect {
                // NB: the route attribute will always be defined here
                case p@PartialPayment(_, _, _, _, Some(route), _) => route.head.fee(p.amountWithFees)
              }.sum
          }
          paymentSent.feesPaid + localFees
        case Left(paymentFailed) =>
          val (fees, pathFindingExperiment) = request match {
            case s: SendPaymentToNode => (s.routeParams.getMaxFee(cfg.recipientAmount), s.routeParams.experimentName)
            case _: SendPaymentToRoute => (0 msat, "n/a")
          }
          log.info(s"failed payment attempts details: ${PaymentFailure.jsonSummary(cfg, pathFindingExperiment, paymentFailed)}")
          fees
      }
      request match {
        case SendPaymentToNode(_, _, _, _, _, routeParams) =>
          context.system.eventStream.publish(PathFindingExperimentMetrics(request.finalPayload.amount, fees, status, duration, now, isMultiPart = false, routeParams.experimentName, cfg.recipientNodeId))
        case SendPaymentToRoute(_, _, _, _) => ()
      }
    }
    Metrics.SentPaymentDuration
      .withTag(Tags.MultiPart, if (cfg.id != cfg.parentId) Tags.MultiPartType.Child else Tags.MultiPartType.Disabled)
      .withTag(Tags.Success, value = status == "SUCCESS")
      .record(duration.toMillis, TimeUnit.MILLISECONDS)
    stop(FSM.Normal)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), parentPaymentId_opt = Some(cfg.parentId), paymentId_opt = Some(id), paymentHash_opt = Some(paymentHash))
  }

  initialize()
}

object PaymentLifecycle {

  def props(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) = Props(new PaymentLifecycle(nodeParams, cfg, router, register))

  sealed trait SendPayment {
    // @formatter:off
    def replyTo: ActorRef
    def finalPayload: FinalPayload
    def assistedRoutes: Seq[Seq[ExtraHop]]
    def targetNodeId: PublicKey
    def maxAttempts: Int
    // @formatter:on
  }

  /**
   * Send a payment to a given route.
   *
   * @param route        payment route to use.
   * @param finalPayload onion payload for the target node.
   */
  case class SendPaymentToRoute(replyTo: ActorRef,
                                route: Either[PredefinedRoute, Route],
                                finalPayload: FinalPayload,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil) extends SendPayment {
    require(route.fold(!_.isEmpty, _.hops.nonEmpty), "payment route must not be empty")

    val targetNodeId: PublicKey = route.fold(_.targetNodeId, _.hops.last.nextNodeId)

    override def maxAttempts: Int = 1

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
  case class SendPaymentToNode(replyTo: ActorRef,
                               targetNodeId: PublicKey,
                               finalPayload: FinalPayload,
                               maxAttempts: Int,
                               assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                               routeParams: RouteParams) extends SendPayment {
    require(finalPayload.amount > 0.msat, s"amount must be > 0")

    val maxFee: MilliSatoshi = routeParams.getMaxFee(finalPayload.amount)

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
  case object ChannelFailureException extends RuntimeException("a channel failure occurred with the first hop")
  case object DisconnectedException extends RuntimeException("a disconnection occurred with the first hop")
  // @formatter:on

}
