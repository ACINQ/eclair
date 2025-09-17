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

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorRef, FSM, Props, typed}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Upstream.Hot
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.{Sphinx, TransportHandler}
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.reputation.ReputationRecorder.GetConfidence
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.protocol._

import java.util.concurrent.TimeUnit

/**
 * Created by PM on 26/08/2016.
 */

class PaymentLifecycle(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef, reputationRecorder_opt: Option[typed.ActorRef[GetConfidence]]) extends FSMDiagnosticActorLogging[PaymentLifecycle.State, PaymentLifecycle.Data] {

  private val id = cfg.id
  private val paymentHash = cfg.paymentHash
  private val paymentsDb = nodeParams.db.payments
  private val start = TimestampMilli.now()

  startWith(WAITING_FOR_REQUEST, WaitingForRequest)

  when(WAITING_FOR_REQUEST) {
    case Event(request: SendPaymentToRoute, WaitingForRequest) =>
      log.debug("sending {} to route {}", request.amount, request.printRoute())
      request.route.fold(
        hops => router ! FinalizeRoute(self, hops, request.recipient.extraEdges, paymentContext = Some(cfg.paymentContext), nodeParams.routerConf.blip18InboundFees, nodeParams.routerConf.excludePositiveInboundFees),
        route => self ! RouteResponse(route :: Nil)
      )
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, cfg.paymentType, request.amount, request.recipient.totalAmount, request.recipient.nodeId, TimestampMilli.now(), cfg.invoice, cfg.payerKey_opt, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(request, Nil, Ignore.empty)

    case Event(request: SendPaymentToNode, WaitingForRequest) =>
      log.debug("sending {} to {}", request.amount, request.recipient.nodeId)
      router ! RouteRequest(self, nodeParams.nodeId, request.recipient, request.routeParams, paymentContext = Some(cfg.paymentContext), blip18InboundFees = nodeParams.routerConf.blip18InboundFees, excludePositiveInboundFees = nodeParams.routerConf.excludePositiveInboundFees)
      if (cfg.storeInDb) {
        paymentsDb.addOutgoingPayment(OutgoingPayment(id, cfg.parentId, cfg.externalId, paymentHash, cfg.paymentType, request.amount, request.recipient.totalAmount, request.recipient.nodeId, TimestampMilli.now(), cfg.invoice, cfg.payerKey_opt, OutgoingPaymentStatus.Pending))
      }
      goto(WAITING_FOR_ROUTE) using WaitingForRoute(request, Nil, Ignore.empty)
  }

  when(WAITING_FOR_ROUTE) {
    case Event(RouteResponse(route +: _), WaitingForRoute(request, failures, ignore)) =>
      log.info(s"route found: attempt=${failures.size + 1}/${request.maxAttempts} route=${route.printNodes()} channels=${route.printChannels()}")
      reputationRecorder_opt match {
        case Some(reputationRecorder) =>
          val cltvExpiry = route.fullRoute.map(_.cltvExpiryDelta).foldLeft(request.recipient.expiry)(_ + _)
          reputationRecorder ! GetConfidence(self, cfg.upstream, Some(route.hops.head.nextNodeId), route.hops.head.fee(request.amount), nodeParams.currentBlockHeight, cltvExpiry)
        case None =>
          val endorsement = cfg.upstream match {
            case Hot.Channel(add, _, _, _) => add.endorsement
            case Hot.Trampoline(received) => received.map(_.add.endorsement).min
            case Upstream.Local(_) => Reputation.maxEndorsement
          }
          self ! Reputation.Score.fromEndorsement(endorsement)
      }
      goto(WAITING_FOR_CONFIDENCE) using WaitingForConfidence(request, failures, ignore, route)

    case Event(PaymentRouteNotFound(t), WaitingForRoute(request, failures, _)) =>
      Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(request.amount, Nil, t))).increment()
      myStop(request, Left(PaymentFailed(id, paymentHash, failures :+ LocalFailure(request.amount, Nil, t))))
  }

  when(WAITING_FOR_CONFIDENCE) {
    case Event(score: Reputation.Score, WaitingForConfidence(request, failures, ignore, route)) =>
      OutgoingPaymentPacket.buildOutgoingPayment(Origin.Hot(self, cfg.upstream), paymentHash, route, request.recipient, score) match {
        case Right(payment) =>
          register ! Register.ForwardShortId(self.toTyped[Register.ForwardShortIdFailure[CMD_ADD_HTLC]], payment.outgoingChannel, payment.cmd)
          goto(WAITING_FOR_PAYMENT_COMPLETE) using WaitingForComplete(request, payment.cmd, failures, payment.sharedSecrets, ignore, route)
        case Left(error) =>
          log.warning("cannot send outgoing payment: {}", error.getMessage)
          Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(request.amount, route.fullRoute, error))).increment()
          myStop(request, Left(PaymentFailed(id, paymentHash, failures :+ LocalFailure(request.amount, route.fullRoute, error))))
      }
  }

  when(WAITING_FOR_PAYMENT_COMPLETE) {
    case Event(RES_SUCCESS(_: CMD_ADD_HTLC, _), _) => stay()

    case Event(RES_ADD_FAILED(_, t: ChannelException, _), d: WaitingForComplete) =>
      handleLocalFail(d, t, isFatal = false)

    case Event(_: Register.ForwardShortIdFailure[_], d: WaitingForComplete) =>
      handleLocalFail(d, DisconnectedException, isFatal = false)

    case Event(RES_ADD_SETTLED(_, htlc, fulfill: HtlcResult.Fulfill), d: WaitingForComplete) =>
      router ! Router.RouteDidRelay(d.route)
      Metrics.PaymentAttempt.withTag(Tags.MultiPart, value = false).record(d.failures.size + 1)
      val p = PartialPayment(id, d.request.amount, d.cmd.amount - d.request.amount, htlc.channelId, Some(d.route.fullRoute))
      val remainingAttribution_opt = fulfill match {
        case HtlcResult.RemoteFulfill(updateFulfill) =>
          updateFulfill.attribution_opt match {
            case Some(attribution) =>
              val unwrapped = Sphinx.Attribution.unwrap(attribution, d.sharedSecrets)
              if (unwrapped.holdTimes.nonEmpty) {
                context.system.eventStream.publish(Router.ReportedHoldTimes(unwrapped.holdTimes))
              }
              unwrapped.remaining_opt
            case None => None
          }
        case _: HtlcResult.OnChainFulfill => None
      }
      myStop(d.request, Right(cfg.createPaymentSent(d.recipient, fulfill.paymentPreimage, p :: Nil, remainingAttribution_opt)))

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
          // a disconnection occurred before the outgoing htlc got signed
          // again, we consider it a local error and treat is as such
          handleLocalFail(d, DisconnectedException, isFatal = false)
      }
  }

  whenUnhandled {
    case Event(_: TransportHandler.ReadAck, _) => stay() // ignored, router replies with this when we forward a channel_update
  }

  private def retry(failure: PaymentFailure, data: WaitingForComplete): FSM.State[PaymentLifecycle.State, PaymentLifecycle.Data] = {
    data.request match {
      case request: SendPaymentToNode =>
        val ignore1 = PaymentFailure.updateIgnored(failure, data.ignore)
        router ! RouteRequest(self, nodeParams.nodeId, data.recipient, request.routeParams, ignore1, paymentContext = Some(cfg.paymentContext), blip18InboundFees = nodeParams.routerConf.blip18InboundFees, excludePositiveInboundFees = nodeParams.routerConf.excludePositiveInboundFees)
        goto(WAITING_FOR_ROUTE) using WaitingForRoute(data.request, data.failures :+ failure, ignore1)
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
      case _ => Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(LocalFailure(d.request.amount, d.route.fullRoute, t))).increment()
    }
    // we only retry if the error isn't fatal, and we haven't exhausted the max number of retried
    val doRetry = !isFatal && (d.failures.size + 1 < d.request.maxAttempts)
    val localFailure = LocalFailure(d.request.amount, d.route.fullRoute, t)
    if (doRetry) {
      log.info(s"received an error message from local, trying to use a different channel (failure=${t.getMessage})")
      retry(localFailure, d)
    } else {
      myStop(d.request, Left(PaymentFailed(id, paymentHash, d.failures :+ localFailure)))
    }
  }

  private def handleRemoteFail(d: WaitingForComplete, fail: UpdateFailHtlc) = {
    import d._
    val htlcFailure = Sphinx.FailurePacket.decrypt(fail.reason, fail.attribution_opt, sharedSecrets)
    if (htlcFailure.holdTimes.nonEmpty) {
      context.system.eventStream.publish(Router.ReportedHoldTimes(htlcFailure.holdTimes))
    }
    ((htlcFailure.failure match {
      case success@Right(e) =>
        Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(RemoteFailure(request.amount, Nil, e))).increment()
        success
      case failure@Left(e) =>
        Metrics.PaymentError.withTag(Tags.Failure, Tags.FailureType(UnreadableRemoteFailure(request.amount, Nil, e, htlcFailure.holdTimes))).increment()
        failure
    }) match {
      case res@Right(Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
        // We have discovered some liquidity information with this payment: we update the router accordingly.
        val stoppedRoute = route.stopAt(nodeId)
        if (stoppedRoute.hops.length > 1) {
          router ! Router.RouteCouldRelay(stoppedRoute)
        }
        failureMessage match {
          case TemporaryChannelFailure(update_opt, _) =>
            route.hops.find(_.nodeId == nodeId) match {
              case Some(failingHop) =>
                val isLiquidityIssue = update_opt match {
                  // If the relay parameters have changed, it's not necessarily a liquidity issue.
                  case Some(update) => HopRelayParams.areSame(failingHop.params, HopRelayParams.FromAnnouncement(update), ignoreHtlcSize = true)
                  case None => true
                }
                if (isLiquidityIssue) {
                  router ! Router.ChannelCouldNotRelay(stoppedRoute.amount, failingHop)
                }
              case _ => ()
            }
          case _ => // other errors should not be used for liquidity issues
        }
        res
      case res => res
    }) match {
      case Right(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) if nodeId == recipient.nodeId =>
        // if destination node returns an error, we fail the payment immediately
        log.warning(s"received an error message from target nodeId=$nodeId, failing the payment (failure=$failureMessage)")
        myStop(request, Left(PaymentFailed(id, paymentHash, failures :+ RemoteFailure(request.amount, route.fullRoute, e))))
      case Right(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) if route.finalHop_opt.collect { case h: NodeHop if h.nodeId == nodeId => h }.nonEmpty =>
        // if trampoline node returns an error, we fail the payment immediately
        log.warning(s"received an error message from trampoline nodeId=$nodeId, failing the payment (failure=$failureMessage)")
        myStop(request, Left(PaymentFailed(id, paymentHash, failures :+ RemoteFailure(request.amount, route.fullRoute, e))))
      case res if failures.size + 1 >= request.maxAttempts =>
        // otherwise we never try more than maxAttempts, no matter the kind of error returned
        val failure = res match {
          case Right(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
            log.info(s"received an error message from nodeId=$nodeId (failure=$failureMessage)")
            failureMessage match {
              case failureMessage: Update => handleUpdate(nodeId, failureMessage, d)
              case _ =>
            }
            RemoteFailure(request.amount, route.fullRoute, e)
          case Left(e@Sphinx.CannotDecryptFailurePacket(unwrapped, _)) =>
            log.warning(s"cannot parse returned error ${fail.reason.toHex} with sharedSecrets=$sharedSecrets: unwrapped=$unwrapped")
            UnreadableRemoteFailure(request.amount, route.fullRoute, e, htlcFailure.holdTimes)
        }
        log.warning(s"too many failed attempts, failing the payment")
        myStop(request, Left(PaymentFailed(id, paymentHash, failures :+ failure)))
      case Left(e@Sphinx.CannotDecryptFailurePacket(unwrapped, _)) =>
        log.warning(s"cannot parse returned error: unwrapped=$unwrapped, route=${route.printNodes()}")
        val failure = UnreadableRemoteFailure(request.amount, route.fullRoute, e, htlcFailure.holdTimes)
        retry(failure, d)
      case Right(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Node)) =>
        log.info(s"received 'Node' type error message from nodeId=$nodeId, trying to route around it (failure=$failureMessage)")
        val failure = RemoteFailure(request.amount, route.fullRoute, e)
        retry(failure, d)
      case Right(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update)) =>
        log.info(s"received 'Update' type error message from nodeId=$nodeId, retrying payment (failure=$failureMessage)")
        val failure = RemoteFailure(request.amount, route.fullRoute, e)
        if (failureMessage.update_opt.forall(update => Announcements.checkSig(update, nodeId))) {
          val recipient1 = handleUpdate(nodeId, failureMessage, d)
          val ignore1 = PaymentFailure.updateIgnored(failure, ignore)
          // let's try again, router will have updated its state
          request match {
            case _: SendPaymentToRoute =>
              log.error("unexpected retry during SendPaymentToRoute")
              stop(FSM.Normal)
            case request: SendPaymentToNode =>
              router ! RouteRequest(self, nodeParams.nodeId, recipient1, request.routeParams, ignore1, paymentContext = Some(cfg.paymentContext), blip18InboundFees = nodeParams.routerConf.blip18InboundFees, excludePositiveInboundFees = nodeParams.routerConf.excludePositiveInboundFees)
              goto(WAITING_FOR_ROUTE) using WaitingForRoute(request.copy(recipient = recipient1), failures :+ failure, ignore1)
          }
        } else {
          // this node is fishy, it gave us a bad channel update signature: let's filter it out.
          log.warning(s"got bad signature from node=$nodeId update=${failureMessage.update_opt}")
          request match {
            case _: SendPaymentToRoute =>
              log.error("unexpected retry during SendPaymentToRoute")
              stop(FSM.Normal)
            case request: SendPaymentToNode =>
              router ! RouteRequest(self, nodeParams.nodeId, recipient, request.routeParams, ignore + nodeId, paymentContext = Some(cfg.paymentContext), blip18InboundFees = nodeParams.routerConf.blip18InboundFees, excludePositiveInboundFees = nodeParams.routerConf.excludePositiveInboundFees)
              goto(WAITING_FOR_ROUTE) using WaitingForRoute(request, failures :+ failure, ignore + nodeId)
          }
        }
      case Right(e@Sphinx.DecryptedFailurePacket(nodeId, _: InvalidOnionBlinding)) =>
        // there was a failure inside the blinded route we used: we cannot know why it failed, so let's ignore it.
        log.info(s"received an error coming from nodeId=$nodeId inside the blinded route, retrying with different blinded routes")
        val failure = RemoteFailure(request.amount, route.fullRoute, e)
        val ignore1 = PaymentFailure.updateIgnored(failure, ignore)
        request match {
          case _: SendPaymentToRoute =>
            log.error("unexpected retry during SendPaymentToRoute")
            stop(FSM.Normal)
          case request: SendPaymentToNode =>
            router ! RouteRequest(self, nodeParams.nodeId, recipient, request.routeParams, ignore1, paymentContext = Some(cfg.paymentContext), blip18InboundFees = nodeParams.routerConf.blip18InboundFees, excludePositiveInboundFees = nodeParams.routerConf.excludePositiveInboundFees)
            goto(WAITING_FOR_ROUTE) using WaitingForRoute(request, failures :+ failure, ignore1)
        }
      case Right(e@Sphinx.DecryptedFailurePacket(nodeId, failureMessage)) =>
        log.info(s"received an error message from nodeId=$nodeId, trying to use a different channel (failure=$failureMessage)")
        val failure = RemoteFailure(request.amount, route.fullRoute, e)
        retry(failure, d)
    }
  }

  /**
   * Analyze the channel_update we received and update our routing state accordingly.
   *
   * Note that we don't forward the channel update to the router, because it could leak that we were the payer:
   *  - a malicious intermediate node would fail the payment with a custom channel_update
   *  - they would *not* send this channel_update to the rest of the network
   *  - they would then directly connect to us and ask for our latest channel_update for that channel
   *  - if we sent them that channel_update, they'd know we were the payer
   *
   * We don't need to send that update to the router anyway: if the sending node is honest, we should receive it with
   * the standard gossip mechanism.
   *
   * @return updated routing hints if applicable.
   */
  private def handleUpdate(nodeId: PublicKey, failure: Update, data: WaitingForComplete): Recipient = {
    val extraEdges1 = data.route.hops.find(_.nodeId == nodeId) match {
      case Some(hop) => hop.params match {
        case ann: HopRelayParams.FromAnnouncement =>
          failure.update_opt match {
            case Some(update) if ann.channelUpdate.shortChannelId != update.shortChannelId =>
              // it is possible that nodes in the route prefer using a different channel (to the same N+1 node) than the one we requested, that's fine
              log.info("received an update for a different channel than the one we asked: requested={} actual={} update={}", ann.channelUpdate.shortChannelId, update.shortChannelId, update)
            case Some(update) if Announcements.areSame(ann.channelUpdate, update) =>
              // node returned the exact same update we used, this can happen e.g. if the channel is imbalanced
              // in that case, let's temporarily exclude the channel from future routes, giving it time to recover
              log.info("received exact same update from nodeId={}, excluding the channel from futures routes", nodeId)
              router ! ExcludeChannel(ChannelDesc(ann.channelUpdate.shortChannelId, nodeId, hop.nextNodeId), Some(nodeParams.routerConf.channelExcludeDuration))
            case Some(_) if PaymentFailure.hasAlreadyFailedOnce(nodeId, data.failures) =>
              // this node had already given us a new channel update and is still unhappy, it is probably messing with us, let's exclude it
              log.warning("it is the second time nodeId={} answers with a new update, excluding it: old={} new={}", nodeId, ann.channelUpdate, failure.update_opt)
              router ! ExcludeChannel(ChannelDesc(ann.channelUpdate.shortChannelId, nodeId, hop.nextNodeId), Some(nodeParams.routerConf.channelExcludeDuration))
            case Some(update) =>
              log.info("got a new update for shortChannelId={}: old={} new={}", ann.channelUpdate.shortChannelId, ann.channelUpdate, update)
            case None =>
              // this isn't a relay parameter issue, so it's probably a liquidity issue
              log.info("update not provided for shortChannelId={}", ann.channelUpdate.shortChannelId)
              router ! ExcludeChannel(ChannelDesc(ann.channelUpdate.shortChannelId, nodeId, hop.nextNodeId), Some(nodeParams.routerConf.channelExcludeDuration))
          }
          data.recipient.extraEdges
        case _: HopRelayParams.FromHint =>
          failure.update_opt match {
            case Some(update) =>
              log.info("received an update for a routing hint (shortChannelId={} nodeId={} enabled={} update={})", update.shortChannelId, nodeId, update.channelFlags.isEnabled, failure.update_opt)
              if (update.channelFlags.isEnabled) {
                data.recipient.extraEdges.map {
                  case edge: ExtraEdge if edge.sourceNodeId == nodeId && edge.targetNodeId == hop.nextNodeId => edge.update(update)
                  case edge: ExtraEdge => edge
                }
              } else {
                // if the channel is disabled, we temporarily exclude it: this is necessary because the routing hint doesn't
                // contain channel flags to indicate that it's disabled
                // we want the exclusion to be router-wide so that sister payments in the case of MPP are aware the channel is faulty
                data.recipient.extraEdges
                  .find(edge => edge.sourceNodeId == nodeId && edge.targetNodeId == hop.nextNodeId)
                  .foreach(edge => router ! ExcludeChannel(ChannelDesc(edge.shortChannelId, edge.sourceNodeId, edge.targetNodeId), Some(nodeParams.routerConf.channelExcludeDuration)))
                // we remove this edge for our next payment attempt
                data.recipient.extraEdges.filterNot(edge => edge.sourceNodeId == nodeId && edge.targetNodeId == hop.nextNodeId)
              }
            case None =>
              // this is most likely a liquidity issue, we remove this edge for our next payment attempt
              data.recipient.extraEdges.filterNot(edge => edge.sourceNodeId == nodeId && edge.targetNodeId == hop.nextNodeId)
          }
      }
      case None =>
        log.error(s"couldn't find node=$nodeId in the route, this should never happen")
        data.recipient.extraEdges
    }
    // we update the recipient's assisted routes: they take precedence over the router's routing table
    data.recipient match {
      case recipient: ClearRecipient => recipient.copy(extraEdges = extraEdges1)
      case recipient => recipient
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
        val isRecipientFailure = f.failures.exists {
          case r: RemoteFailure => r.e.originNode == request.recipient.nodeId
          case _ => false
        }
        if (isRecipientFailure) {
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
            case u: Upstream.Hot.Channel => u.amountIn - paymentSent.amountWithFees
            case _: Upstream.Hot.Trampoline =>
              // in case of a relayed payment, we need to take into account the fee of the first channels
              paymentSent.parts.collect {
                // NB: the route attribute will always be defined here
                case p@PartialPayment(_, _, _, _, Some(route), _) => route.head.fee(p.amountWithFees)
              }.sum
          }
          paymentSent.feesPaid + localFees
        case Left(paymentFailed) =>
          val (fees, pathFindingExperiment) = request match {
            case request: SendPaymentToNode => (request.routeParams.getMaxFee(request.amount), request.routeParams.experimentName)
            case _: SendPaymentToRoute => (0 msat, "n/a")
          }
          log.info(s"failed payment attempts details: ${PaymentFailure.jsonSummary(cfg, pathFindingExperiment, paymentFailed)}")
          fees
      }
      request match {
        case request: SendPaymentToNode =>
          context.system.eventStream.publish(PathFindingExperimentMetrics(cfg.paymentHash, request.amount, fees, status, duration, now, isMultiPart = false, request.routeParams.experimentName, request.recipient.nodeId, request.recipient.extraEdges))
        case _: SendPaymentToRoute => ()
      }
    }
    Metrics.SentPaymentDuration
      .withTag(Tags.MultiPart, if (cfg.id != cfg.parentId) Tags.MultiPartType.Child else Tags.MultiPartType.Disabled)
      .withTag(Tags.Success, value = status == "SUCCESS")
      .record(duration.toMillis, TimeUnit.MILLISECONDS)
    stop(FSM.Normal)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(
      category_opt = Some(Logs.LogCategory.PAYMENT),
      parentPaymentId_opt = Some(cfg.parentId),
      paymentId_opt = Some(id),
      paymentHash_opt = Some(paymentHash),
      remoteNodeId_opt = Some(cfg.recipientNodeId),
      nodeAlias_opt = Some(nodeParams.alias))
  }

  initialize()
}

object PaymentLifecycle {

  def props(nodeParams: NodeParams, cfg: SendPaymentConfig, router: ActorRef, register: ActorRef, reputationRecorder_opt: Option[typed.ActorRef[GetConfidence]]) = Props(new PaymentLifecycle(nodeParams, cfg, router, register, reputationRecorder_opt))

  sealed trait SendPayment {
    // @formatter:off
    def replyTo: ActorRef
    def amount: MilliSatoshi
    def recipient: Recipient
    def maxAttempts: Int
    // @formatter:on
  }

  /**
   * Send a payment to a given route.
   *
   * @param route     payment route to use.
   * @param recipient final recipient.
   */
  case class SendPaymentToRoute(replyTo: ActorRef, route: Either[PredefinedRoute, Route], recipient: Recipient) extends SendPayment {
    require(route.fold(r => !r.isEmpty, r => r.hops.nonEmpty || r.finalHop_opt.nonEmpty), "payment route must not be empty")

    override val maxAttempts: Int = 1
    override val amount = route.fold(_.amount, _.amount)

    def printRoute(): String = route match {
      case Left(PredefinedChannelRoute(_, _, channels, _)) => channels.mkString("->")
      case Left(PredefinedNodeRoute(_, nodes, _)) => nodes.mkString("->")
      case Right(route) => route.printNodes()
    }
  }

  /**
   * Send a payment to a given node. A path-finding algorithm will run to find a suitable payment route.
   *
   * @param recipient   final recipient.
   * @param maxAttempts maximum number of retries.
   * @param routeParams parameters to fine-tune the routing algorithm.
   */
  case class SendPaymentToNode(replyTo: ActorRef, recipient: Recipient, maxAttempts: Int, routeParams: RouteParams) extends SendPayment {
    require(recipient.totalAmount > 0.msat, "amount must be > 0")
    override val amount = recipient.totalAmount
  }

  // @formatter:off
  sealed trait Data
  case object WaitingForRequest extends Data
  case class WaitingForRoute(request: SendPayment, failures: Seq[PaymentFailure], ignore: Ignore) extends Data
  case class WaitingForConfidence(request: SendPayment, failures: Seq[PaymentFailure], ignore: Ignore, route: Route) extends Data
  case class WaitingForComplete(request: SendPayment, cmd: CMD_ADD_HTLC, failures: Seq[PaymentFailure], sharedSecrets: Seq[Sphinx.SharedSecret], ignore: Ignore, route: Route) extends Data {
    val recipient = request.recipient
  }

  sealed trait State
  case object WAITING_FOR_REQUEST extends State
  case object WAITING_FOR_ROUTE extends State
  case object WAITING_FOR_CONFIDENCE extends State
  case object WAITING_FOR_PAYMENT_COMPLETE extends State

  /** custom exceptions to handle corner cases */
  case object UpdateMalformedException extends RuntimeException("first hop returned an UpdateFailMalformedHtlc message")
  case object ChannelFailureException extends RuntimeException("a channel failure occurred with the first hop")
  case object DisconnectedException extends RuntimeException("a disconnection occurred with the first hop")
  // @formatter:on

}
