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
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Register}
import fr.acinq.eclair.crypto.{Sphinx, TransportHandler}
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.Onion._
import fr.acinq.eclair.wire._
import kamon.Kamon
import kamon.trace.Span

import scala.compat.Platform
import scala.util.{Failure, Success}

/**
 * Created by PM on 26/08/2016.
 */

class PaymentLifecycle(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) extends FSMDiagnosticActorLogging[PaymentLifecycle.State, PaymentLifecycle.Data] {

  val id = cfg.id
  val paymentHash = cfg.paymentHash
  val paymentsDb = nodeParams.db.payments

  private val span = Kamon.runWithContextEntry(MultiPartPaymentLifecycle.parentPaymentIdKey, cfg.parentId) {
    val spanBuilder = if (Kamon.currentSpan().isEmpty) {
      Kamon.spanBuilder("single-payment")
    } else {
      Kamon.spanBuilder("payment-part").asChildOf(Kamon.currentSpan())
    }
    spanBuilder
      .tag("paymentId", cfg.id.toString)
      .tag("paymentHash", paymentHash.toHex)
      .tag("recipientNodeId", cfg.recipientNodeId.toString())
      .tag("recipientAmount", cfg.recipientAmount.toLong)
      .start()
  }

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(c: SendPaymentToRoute, WaitingForRequest) =>
      span.tag("targetNodeId", c.targetNodeId.toString())
      span.tag("amount", c.finalPayload.amount.toLong)
      span.tag("totalAmount", c.finalPayload.totalAmount.toLong)
      span.tag("expiry", c.finalPayload.expiry.toLong)
      log.debug("sending {} to route {}", c.finalPayload.amount, c.hops.mkString("->"))
      val send = SendPayment(c.hops.last, c.finalPayload, maxAttempts = 1)
      router ! FinalizeRoute(c.hops)
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, Platform.currentTime, cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, send, failures = Nil)

    case Event(c: SendPayment, WaitingForRequest) =>
      span.tag("targetNodeId", c.targetNodeId.toString())
      span.tag("amount", c.finalPayload.amount.toLong)
      span.tag("totalAmount", c.finalPayload.totalAmount.toLong)
      span.tag("expiry", c.finalPayload.expiry.toLong)
      log.debug("sending {} to {}{}", c.finalPayload.amount, c.targetNodeId, c.routePrefix.mkString(" with route prefix ", "->", ""))
      // We don't want the router to try cycling back to nodes that are at the beginning of the route.
      val ignoredNodes = c.routePrefix.map(_.nodeId).toSet
      if (c.routePrefix.lastOption.exists(_.nextNodeId == c.targetNodeId)) {
        // If the sender already provided a route to the target, no need to involve the router.
        self ! RouteResponse(Nil, ignoredNodes, Set.empty, allowEmpty = true)
      } else {
        router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, c.assistedRoutes, routeParams = c.routeParams, ignoreNodes = ignoredNodes)
      }
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, PaymentType.Standard, c.finalPayload.amount, cfg.recipientAmount, cfg.recipientNodeId, Platform.currentTime, cfg.paymentRequest, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(sender, c, failures = Nil)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(routeHops, ignoreNodes, ignoreChannels, _), WaitingForRoute(s, c, failures)) =>
      val hops = c.routePrefix ++ routeHops
      log.info(s"route found: attempt=${failures.size + 1}/${c.maxAttempts} route=${hops.map(_.nextNodeId).mkString("->")} channels=${hops.map(_.lastUpdate.shortChannelId).mkString("->")}")
      val firstHop = hops.head
      val (cmd, sharedSecrets) = OutgoingPacket.buildCommand(cfg.upstream, paymentHash, hops, c.finalPayload)
      register ! Register.ForwardShortId(firstHop.lastUpdate.shortChannelId, cmd)
      goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(s, c, cmd, failures, sharedSecrets, ignoreNodes, ignoreChannels, hops)

    case Event(Status.Failure(t), WaitingForRoute(s, _, failures)) =>
      onFailure(s, PaymentFailed(id, paymentHash, failures :+ LocalFailure(t)))
      myStop()
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event("ok", _) => stay

    case Event(fulfill: UpdateFulfillHtlc, WaitingForComplete(s, c, cmd, _, _, _, _, route)) =>
      val p = PartialPayment(id, c.finalPayload.amount, cmd.amount - c.finalPayload.amount, fulfill.channelId, Some(cfg.fullRoute(route)))
      onSuccess(s, cfg.createPaymentSent(fulfill.paymentPreimage, p :: Nil))
      myStop()

    case Event(fail: UpdateFailHtlc, WaitingForComplete(s, c, _, failures, sharedSecrets, ignoreNodes, ignoreChannels, hops)) =>
      Sphinx.FailurePacket.decrypt(fail.reason, sharedSecrets) match {
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) if nodeId == c.targetNodeId =>
          // if destination node returns an error, we fail the payment immediately
          log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
          onFailure(s, PaymentFailed(id, paymentHash, failures :+ RemoteFailure(cfg.fullRoute(hops), e)))
          myStop()
        case res if failures.size + 1 >= c.maxAttempts =>
          // otherwise we never try more than maxAttempts, no matter the kind of error returned
          val failure = res match {
            case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
              log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
              RemoteFailure(cfg.fullRoute(hops), e)
            case Failure(t) =>
              log.warning(s"cannot parse returned error: ${t.getMessage}")
              UnreadableRemoteFailure(cfg.fullRoute(hops))
          }
          log.warning(s"too many failed attempts, failing the payment")
          onFailure(s, PaymentFailed(id, paymentHash, failures :+ failure))
          myStop()
        case Failure(t) =>
          log.warning(s"cannot parse returned error: ${t.getMessage}")
          // in that case we don't know which node is sending garbage, let's try to blacklist all nodes except the one we are directly connected to and the destination node
          val blacklist = hops.map(_.nextNodeId).drop(1).dropRight(1)
          log.warning(s"blacklisting intermediate nodes=${blacklist.mkString(",")}")
          router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, c.assistedRoutes, ignoreNodes ++ blacklist, ignoreChannels, c.routeParams)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ UnreadableRemoteFailure(cfg.fullRoute(hops)))
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Node)) =>
          log.info(s"received 'Node' type error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
          // let's try to route around this node
          router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, c.assistedRoutes, ignoreNodes + nodeId, ignoreChannels, c.routeParams)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(cfg.fullRoute(hops), e))
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update)) =>
          log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
          if (Announcements.checkSig(failureMessage.update, nodeId)) {
            getChannelUpdateForNode(nodeId, hops) match {
              case Some(u) if u.shortChannelId != failureMessage.update.shortChannelId =>
                // it is possible that nodes in the route prefer using a different channel (to the same N+1 node) than the one we requested, that's fine
                log.info(s"received an update for a different channel than the one we asked: requested=${u.shortChannelId} actual=${failureMessage.update.shortChannelId} update=${failureMessage.update}")
              case Some(u) if Announcements.areSame(u, failureMessage.update) =>
                // node returned the exact same update we used, this can happen e.g. if the channel is imbalanced
                // in that case, let's temporarily exclude the channel from future routes, giving it time to recover
                log.info(s"received exact same update from nodeId=$nodeId, excluding the channel from futures routes")
                val nextNodeId = hops.find(_.nodeId == nodeId).get.nextNodeId
                router ! ExcludeChannel(ChannelDesc(u.shortChannelId, nodeId, nextNodeId))
              case Some(u) if PaymentFailure.hasAlreadyFailedOnce(nodeId, failures) =>
                // this node had already given us a new channel update and is still unhappy, it is probably messing with us, let's exclude it
                log.warning(s"it is the second time nodeId=$nodeId answers with a new update, excluding it: old=$u new=${failureMessage.update}")
                val nextNodeId = hops.find(_.nodeId == nodeId).get.nextNodeId
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
            router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, assistedRoutes1, ignoreNodes, ignoreChannels, c.routeParams)
          } else {
            // this node is fishy, it gave us a bad sig!! let's filter it out
            log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update}")
            router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, c.assistedRoutes, ignoreNodes + nodeId, ignoreChannels, c.routeParams)
          }
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(cfg.fullRoute(hops), e))
        case Success(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
          log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
          // let's try again without the channel outgoing from nodeId
          val faultyChannel = hops.find(_.nodeId == nodeId).map(hop => ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId))
          router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, c.assistedRoutes, ignoreNodes, ignoreChannels ++ faultyChannel.toSet, c.routeParams)
          goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ RemoteFailure(cfg.fullRoute(hops), e))
      }

    case Event(fail: UpdateFailMalformedHtlc, _) =>
      log.info(s"first node in the route couldn't parse our htlc: fail=$fail")
      // this is a corner case, that can only happen when the *first* node in the route cannot parse the onion
      // (if this happens higher up in the route, the error would be wrapped in an UpdateFailHtlc and handled above)
      // let's consider it a local error and treat is as such
      self ! Status.Failure(new RuntimeException("first hop returned an UpdateFailMalformedHtlc message"))
      stay

    case Event(Status.Failure(t), WaitingForComplete(s, c, _, failures, _, ignoreNodes, ignoreChannels, hops)) =>
      // If the first hop was selected by the sender (in routePrefix) and it failed, it doesn't make sense to retry (we
      // will end up retrying over that same faulty channel).
      if (failures.size + 1 >= c.maxAttempts || c.routePrefix.nonEmpty) {
        onFailure(s, PaymentFailed(id, paymentHash, failures :+ LocalFailure(t)))
        myStop()
      } else {
        log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
        val faultyChannel = ChannelDesc(hops.head.lastUpdate.shortChannelId, hops.head.nodeId, hops.head.nextNodeId)
        router ! RouteRequest(c.getRouteRequestStart(nodeParams), c.targetNodeId, c.finalPayload.amount, c.assistedRoutes, ignoreNodes, ignoreChannels + faultyChannel, c.routeParams)
        goto(WAITING_FOR_ROUTE) using WaitingForRoute(s, c, failures :+ LocalFailure(t))
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
          stateSpanBuilder.tag("route", s"${cfg.fullRoute(d.hops).map(_.nextNodeId).mkString("->")}")
        case _ => ()
      }
      stateSpan.foreach(_.finish())
      stateSpan = Some(stateSpanBuilder.start())
  }

  whenUnhandled {
    case Event(_: TransportHandler.ReadAck, _) => stay // ignored, router replies with this when we forward a channel_update
  }

  def myStop(): State = {
    stateSpan.foreach(_.finish())
    span.finish()
    stop(FSM.Normal)
  }

  def onSuccess(sender: ActorRef, result: PaymentSent): Unit = {
    if (cfg.storeInDb) paymentsDb.updateOutgoingPayment(result)
    sender ! result
    if (cfg.publishEvent) context.system.eventStream.publish(result)
  }

  def onFailure(sender: ActorRef, result: PaymentFailed): Unit = {
    span.fail("payment failed")
    if (cfg.storeInDb) paymentsDb.updateOutgoingPayment(result)
    sender ! result
    if (cfg.publishEvent) context.system.eventStream.publish(result)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), parentPaymentId_opt = Some(cfg.parentId), paymentId_opt = Some(id), paymentHash_opt = Some(paymentHash))
  }

  initialize()
}

object PaymentLifecycle {

  def props(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef) = Props(classOf[PaymentLifecycle], nodeParams, cfg, router, register)

  /**
   * Send a payment to a pre-defined route without running the path-finding algorithm.
   *
   * @param hops         payment route to use.
   * @param finalPayload onion payload for the target node.
   */
  case class SendPaymentToRoute(hops: Seq[PublicKey], finalPayload: FinalPayload) {
    require(hops.nonEmpty, s"payment route must not be empty")
    val targetNodeId = hops.last
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
   * @param routePrefix    when provided, the payment route will start with these hops. Path-finding will run only to
   *                       find how to route from the last node of the route prefix to the target node.
   */
  case class SendPayment(targetNodeId: PublicKey,
                         finalPayload: FinalPayload,
                         maxAttempts: Int,
                         assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                         routeParams: Option[RouteParams] = None,
                         routePrefix: Seq[ChannelHop] = Nil) {
    require(finalPayload.amount > 0.msat, s"amount must be > 0")

    /** Returns the node from which the path-finding algorithm should start. */
    def getRouteRequestStart(nodeParams: NodeParams): PublicKey = routePrefix match {
      case Nil => nodeParams.nodeId
      case prefix => prefix.last.nextNodeId
    }
  }

  // @formatter:off
  sealed trait Data
  case object WaitingForRequest extends Data
  case class WaitingForRoute(sender: ActorRef, c: SendPayment, failures: Seq[PaymentFailure]) extends Data
  case class WaitingForComplete(sender: ActorRef, c: SendPayment, cmd: CMD_ADD_HTLC, failures: Seq[PaymentFailure], sharedSecrets: Seq[(ByteVector32, PublicKey)], ignoreNodes: Set[PublicKey], ignoreChannels: Set[ChannelDesc], hops: Seq[ChannelHop]) extends Data

  sealed trait State
  case object WAITING_FOR_REQUEST extends State
  case object WAITING_FOR_ROUTE extends State
  case object WAITING_FOR_PAYMENT_COMPLETE extends State
  // @formatter:on

  /**
   * This method retrieves the channel update that we used when we built a route.
   * It just iterates over the hops, but there are at most 20 of them.
   *
   * @return the channel update if found
   */
  def getChannelUpdateForNode(nodeId: PublicKey, hops: Seq[ChannelHop]): Option[ChannelUpdate] = hops.find(_.nodeId == nodeId).map(_.lastUpdate)

}
