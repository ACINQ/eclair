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

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.Features.BasicMultiPartPayment
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentError._
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, NodeParams, randomBytes32}

import java.util.UUID

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, outgoingPaymentFactory: PaymentInitiator.MultiPartPaymentFactory) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = main(Map.empty)

  def main(pending: Map[UUID, PendingPayment]): Receive = {
    case r: SendPaymentToNode =>
      val paymentId = UUID.randomUUID()
      if (!r.blockUntilComplete) {
        // Immediately return the paymentId
        sender() ! paymentId
      }
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = true, recordMetrics = true, Nil)
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      r.paymentRequest.paymentSecret match {
        case _ if !r.paymentRequest.features.areSupported(nodeParams) =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(Nil, UnsupportedFeatures(r.paymentRequest.features.features)) :: Nil)
        case None =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(Nil, PaymentSecretMissing) :: Nil)
        case Some(paymentSecret) if r.paymentRequest.features.allowMultiPart && nodeParams.features.hasFeature(BasicMultiPartPayment) =>
          val fsm = outgoingPaymentFactory.spawnOutgoingMultiPartPayment(context, paymentCfg)
          fsm ! SendMultiPartPayment(sender(), paymentSecret, r.recipientNodeId, r.recipientAmount, finalExpiry, r.maxAttempts, r.assistedRoutes, r.routeParams, userCustomTlvs = r.userCustomTlvs)
        case Some(paymentSecret) =>
          val finalPayload = Onion.createSinglePartPayload(r.recipientAmount, finalExpiry, paymentSecret, r.userCustomTlvs)
          val fsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
          fsm ! PaymentLifecycle.SendPaymentToNode(sender(), r.recipientNodeId, finalPayload, r.maxAttempts, r.assistedRoutes, r.routeParams)
      }

    case r: SendSpontaneousPayment =>
      val paymentId = UUID.randomUUID()
      sender() ! paymentId
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), None, storeInDb = true, publishEvent = true, recordMetrics = true, Nil)
      val finalExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1)
      val finalPayload = Onion.FinalTlvPayload(TlvStream(Seq(OnionTlv.AmountToForward(r.recipientAmount), OnionTlv.OutgoingCltv(finalExpiry), OnionTlv.PaymentData(randomBytes32(), r.recipientAmount), OnionTlv.KeySend(r.paymentPreimage)), r.userCustomTlvs))
      val fsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
      fsm ! PaymentLifecycle.SendPaymentToNode(sender(), r.recipientNodeId, finalPayload, r.maxAttempts, routeParams = r.routeParams)

    case r: SendTrampolinePayment =>
      val paymentId = UUID.randomUUID()
      sender() ! paymentId
      r.trampolineAttempts match {
        case Nil =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(Nil, TrampolineFeesMissing) :: Nil)
        case _ if !r.paymentRequest.features.allowTrampoline && r.paymentRequest.amount.isEmpty =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(Nil, TrampolineLegacyAmountLessInvoice) :: Nil)
        case (trampolineFees, trampolineExpiryDelta) :: remainingAttempts =>
          log.info(s"sending trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
          sendTrampolinePayment(paymentId, r, trampolineFees, trampolineExpiryDelta)
          context become main(pending + (paymentId -> PendingPayment(sender(), remainingAttempts, r)))
      }

    case pf: PaymentFailed => pending.get(pf.id).foreach(pp => {
      val decryptedFailures = pf.failures.collect { case RemoteFailure(_, Sphinx.DecryptedFailurePacket(_, f)) => f }
      val shouldRetry = decryptedFailures.contains(TrampolineFeeInsufficient) || decryptedFailures.contains(TrampolineExpiryTooSoon)
      if (shouldRetry) {
        pp.remainingAttempts match {
          case (trampolineFees, trampolineExpiryDelta) :: remaining =>
            log.info(s"retrying trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
            sendTrampolinePayment(pf.id, pp.r, trampolineFees, trampolineExpiryDelta)
            context become main(pending + (pf.id -> pp.copy(remainingAttempts = remaining)))
          case Nil =>
            log.info("trampoline node couldn't find a route after all retries")
            val trampolineRoute = Seq(
              NodeHop(nodeParams.nodeId, pp.r.trampolineNodeId, nodeParams.expiryDelta, 0 msat),
              NodeHop(pp.r.trampolineNodeId, pp.r.recipientNodeId, pp.r.trampolineAttempts.last._2, pp.r.trampolineAttempts.last._1)
            )
            val localFailure = pf.copy(failures = Seq(LocalFailure(trampolineRoute, RouteNotFound)))
            pp.sender ! localFailure
            context.system.eventStream.publish(localFailure)
            context become main(pending - pf.id)
        }
      } else {
        pp.sender ! pf
        context.system.eventStream.publish(pf)
        context become main(pending - pf.id)
      }
    })

    case _: PreimageReceived => // we received the preimage, but we wait for the PaymentSent event that will contain more data

    case ps: PaymentSent => pending.get(ps.id).foreach(pp => {
      pp.sender ! ps
      context.system.eventStream.publish(ps)
      context become main(pending - ps.id)
    })

    case r: SendPaymentToRoute =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      val additionalHops = r.trampolineNodes.sliding(2).map(hop => NodeHop(hop.head, hop(1), CltvExpiryDelta(0), 0 msat)).toSeq
      val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = true, recordMetrics = false, additionalHops)
      r.trampolineNodes match {
        case _ if r.paymentRequest.paymentSecret.isEmpty =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(Nil, PaymentSecretMissing) :: Nil)
        case trampoline :: recipient :: Nil =>
          log.info(s"sending trampoline payment to $recipient with trampoline=$trampoline, trampoline fees=${r.trampolineFees}, expiry delta=${r.trampolineExpiryDelta}")
          // We generate a random secret for the payment to the first trampoline node.
          val trampolineSecret = r.trampolineSecret.getOrElse(randomBytes32())
          sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId, Some(trampolineSecret))
          val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
          val (trampolineAmount, trampolineExpiry, trampolineOnion) = buildTrampolinePayment(r, trampoline, r.trampolineFees, r.trampolineExpiryDelta)
          payFsm ! PaymentLifecycle.SendPaymentToRoute(sender(), Left(r.route), Onion.createMultiPartPayload(r.amount, trampolineAmount, trampolineExpiry, trampolineSecret, Seq(OnionTlv.TrampolineOnion(trampolineOnion))), r.paymentRequest.routingInfo)
        case Nil =>
          sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId, None)
          val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
          payFsm ! PaymentLifecycle.SendPaymentToRoute(sender(), Left(r.route), Onion.createMultiPartPayload(r.amount, r.recipientAmount, finalExpiry, r.paymentRequest.paymentSecret.get), r.paymentRequest.routingInfo)
        case _ =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(Nil, TrampolineMultiNodeNotSupported) :: Nil)
      }
  }

  private def buildTrampolinePayment(r: SendRequestedPayment, trampolineNodeId : PublicKey, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): (MilliSatoshi, CltvExpiry, OnionRoutingPacket) = {
    val trampolineRoute = Seq(
      NodeHop(nodeParams.nodeId, trampolineNodeId, nodeParams.expiryDelta, 0 msat),
      NodeHop(trampolineNodeId, r.recipientNodeId, trampolineExpiryDelta, trampolineFees) // for now we only use a single trampoline hop
    )
    val finalPayload = if (r.paymentRequest.features.allowMultiPart) {
      Onion.createMultiPartPayload(r.recipientAmount, r.recipientAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret.get)
    } else {
      Onion.createSinglePartPayload(r.recipientAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret.get)
    }
    // We assume that the trampoline node supports multi-part payments (it should).
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (r.paymentRequest.features.allowTrampoline) {
      OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(r.paymentHash, trampolineRoute, finalPayload)
    } else {
      OutgoingPacket.buildTrampolineToLegacyPacket(r.paymentRequest, trampolineRoute, finalPayload)
    }
    (trampolineAmount, trampolineExpiry, trampolineOnion.packet)
  }

  private def sendTrampolinePayment(paymentId: UUID, r: SendTrampolinePayment, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): Unit = {
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = false, recordMetrics = true, Seq(NodeHop(r.trampolineNodeId, r.recipientNodeId, trampolineExpiryDelta, trampolineFees)))
    // We generate a random secret for this payment to avoid leaking the invoice secret to the first trampoline node.
    val trampolineSecret = randomBytes32()
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = buildTrampolinePayment(r, r.trampolineNodeId, trampolineFees, trampolineExpiryDelta)
    val fsm = outgoingPaymentFactory.spawnOutgoingMultiPartPayment(context, paymentCfg)
    fsm ! SendMultiPartPayment(self, trampolineSecret, r.trampolineNodeId, trampolineAmount, trampolineExpiry, nodeParams.maxPaymentAttempts, r.paymentRequest.routingInfo, r.routeParams, Seq(OnionTlv.TrampolineOnion(trampolineOnion)))
  }

}

object PaymentInitiator {

  trait PaymentFactory {
    def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef
  }

  trait MultiPartPaymentFactory extends PaymentFactory {
    def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef
  }

  case class SimplePaymentFactory(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends MultiPartPaymentFactory {
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      context.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
    }

    override def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, router, this))
    }
  }

  def props(nodeParams: NodeParams, outgoingPaymentFactory: MultiPartPaymentFactory) = Props(new PaymentInitiator(nodeParams, outgoingPaymentFactory))

  case class PendingPayment(sender: ActorRef, remainingAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)], r: SendTrampolinePayment)

  sealed trait SendRequestedPayment{
    def recipientAmount: MilliSatoshi
    def paymentRequest: PaymentRequest
    def recipientNodeId: PublicKey = paymentRequest.nodeId
    def paymentHash: ByteVector32 = paymentRequest.paymentHash
    def fallbackFinalExpiryDelta: CltvExpiryDelta
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long): CltvExpiry =
      paymentRequest.minFinalCltvExpiryDelta.getOrElse(fallbackFinalExpiryDelta).toCltvExpiry(currentBlockHeight + 1)
  }

  /**
   * We temporarily let the caller decide to use Trampoline (instead of a normal payment) and set the fees/cltv.
   * Once we have trampoline fee estimation built into the router, the decision to use Trampoline or not should be done
   * automatically by the router instead of the caller.
   *
   * @param recipientAmount          amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param paymentRequest           Bolt 11 invoice.
   * @param trampolineNodeId         id of the trampoline node.
   * @param trampolineAttempts       fees and expiry delta for the trampoline node. If this list contains multiple entries,
   *                                 the payment will automatically be retried in case of TrampolineFeeInsufficient errors.
   *                                 For example, [(10 msat, 144), (15 msat, 288)] will first send a payment with a fee of 10
   *                                 msat and cltv of 144, and retry with 15 msat and 288 in case an error occurs.
   * @param fallbackFinalExpiryDelta expiry delta for the final recipient when the [[paymentRequest]] doesn't specify it.
   * @param routeParams              (optional) parameters to fine-tune the routing algorithm.
   */
  case class SendTrampolinePayment(recipientAmount: MilliSatoshi,
                                   paymentRequest: PaymentRequest,
                                   trampolineNodeId: PublicKey,
                                   trampolineAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)],
                                   fallbackFinalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                   routeParams: RouteParams) extends SendRequestedPayment

  /**
   * @param recipientAmount          amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param paymentRequest           Bolt 11 invoice.
   * @param maxAttempts              maximum number of retries.
   * @param fallbackFinalExpiryDelta expiry delta for the final recipient when the [[paymentRequest]] doesn't specify it.
   * @param externalId               (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param assistedRoutes           (optional) routing hints (usually from a Bolt 11 invoice).
   * @param routeParams              (optional) parameters to fine-tune the routing algorithm.
   * @param userCustomTlvs           (optional) user-defined custom tlvs that will be added to the onion sent to the target node.
   * @param blockUntilComplete       (optional) if true, wait until the payment completes before returning a result.
   */
  case class SendPaymentToNode(recipientAmount: MilliSatoshi,
                               paymentRequest: PaymentRequest,
                               maxAttempts: Int,
                               fallbackFinalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                               externalId: Option[String] = None,
                               assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                               routeParams: RouteParams,
                               userCustomTlvs: Seq[GenericTlv] = Nil,
                               blockUntilComplete: Boolean = false) extends SendRequestedPayment

  /**
   * @param recipientAmount amount that should be received by the final recipient.
   * @param recipientNodeId id of the final recipient.
   * @param paymentPreimage payment preimage.
   * @param maxAttempts     maximum number of retries.
   * @param externalId      (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param routeParams     (optional) parameters to fine-tune the routing algorithm.
   * @param userCustomTlvs  (optional) user-defined custom tlvs that will be added to the onion sent to the target node.
   */
  case class SendSpontaneousPayment(recipientAmount: MilliSatoshi,
                                    recipientNodeId: PublicKey,
                                    paymentPreimage: ByteVector32,
                                    maxAttempts: Int,
                                    externalId: Option[String] = None,
                                    routeParams: RouteParams,
                                    userCustomTlvs: Seq[GenericTlv] = Nil) {
    val paymentHash = Crypto.sha256(paymentPreimage)
  }

  /**
   * The sender can skip the routing algorithm by specifying the route to use.
   * When combining with MPP and Trampoline, extra-care must be taken to make sure payments are correctly grouped: only
   * amount, route and trampolineNodes should be changing.
   *
   * Example 1: MPP containing two HTLCs for a 600 msat invoice:
   * SendPaymentToRouteRequest(200 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, bob, dave), None, 0 msat, CltvExpiryDelta(0), Nil)
   * SendPaymentToRouteRequest(400 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, carol, dave), None, 0 msat, CltvExpiryDelta(0), Nil)
   *
   * Example 2: Trampoline with MPP for a 600 msat invoice and 100 msat trampoline fees:
   * SendPaymentToRouteRequest(250 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, bob, dave), secret, 100 msat, CltvExpiryDelta(144), Seq(dave, peter))
   * SendPaymentToRouteRequest(450 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, carol, dave), secret, 100 msat, CltvExpiryDelta(144), Seq(dave, peter))
   *
   * @param amount                   amount that should be received by the last node in the route (should take trampoline
   *                                 fees into account).
   * @param recipientAmount          amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   *                                 This amount may be split between multiple requests if using MPP.
   * @param paymentRequest           Bolt 11 invoice.
   * @param fallbackFinalExpiryDelta expiry delta for the final recipient when the [[paymentRequest]] doesn't specify it.
   * @param route                    route to use to reach either the final recipient or the first trampoline node.
   * @param externalId               (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId                 id of the whole payment. When manually sending a multi-part payment, you need to make
   *                                 sure all partial payments use the same parentId. If not provided, a random parentId will
   *                                 be generated that can be used for the remaining partial payments.
   * @param trampolineSecret         if trampoline is used, this is a secret to protect the payment to the first trampoline
   *                                 node against probing. When manually sending a multi-part payment, you need to make sure
   *                                 all partial payments use the same trampolineSecret.
   * @param trampolineFees           if trampoline is used, fees for the first trampoline node. This value must be the same
   *                                 for all partial payments in the set.
   * @param trampolineExpiryDelta    if trampoline is used, expiry delta for the first trampoline node. This value must be
   *                                 the same for all partial payments in the set.
   * @param trampolineNodes          if trampoline is used, list of trampoline nodes to use (we currently support only a
   *                                 single trampoline node).
   */
  case class SendPaymentToRoute(amount: MilliSatoshi,
                                recipientAmount: MilliSatoshi,
                                paymentRequest: PaymentRequest,
                                fallbackFinalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                route: PredefinedRoute,
                                externalId: Option[String],
                                parentId: Option[UUID],
                                trampolineSecret: Option[ByteVector32],
                                trampolineFees: MilliSatoshi,
                                trampolineExpiryDelta: CltvExpiryDelta,
                                trampolineNodes: Seq[PublicKey]) extends SendRequestedPayment

  /**
   * @param paymentId        id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId         id of the whole payment. When manually sending a multi-part payment, you need to make sure
   *                         all partial payments use the same parentId.
   * @param trampolineSecret if trampoline is used, this is a secret to protect the payment to the first trampoline node
   *                         against probing. When manually sending a multi-part payment, you need to make sure all
   *                         partial payments use the same trampolineSecret.
   */
  case class SendPaymentToRouteResponse(paymentId: UUID, parentId: UUID, trampolineSecret: Option[ByteVector32])

  /**
   * Configuration for an instance of a payment state machine.
   *
   * @param id              id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId        id of the whole payment (if using multi-part, there will be N associated child payments,
   *                        each with a different id).
   * @param externalId      externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param paymentHash     payment hash.
   * @param recipientAmount amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param recipientNodeId id of the final recipient.
   * @param upstream        information about the payment origin (to link upstream to downstream when relaying a payment).
   * @param paymentRequest  Bolt 11 invoice.
   * @param storeInDb       whether to store data in the payments DB (e.g. when we're relaying a trampoline payment, we
   *                        don't want to store in the DB).
   * @param publishEvent    whether to publish a [[fr.acinq.eclair.payment.PaymentEvent]] on success/failure (e.g. for
   *                        multi-part child payments, we don't want to emit events for each child, only for the whole payment).
   * @param recordMetrics   We don't record metrics for payments that don't use path finding or that are a part of a bigger payment.
   * @param additionalHops  additional hops that the payment state machine isn't aware of (e.g. when using trampoline, hops
   *                        that occur after the first trampoline node).
   */
  case class SendPaymentConfig(id: UUID,
                               parentId: UUID,
                               externalId: Option[String],
                               paymentHash: ByteVector32,
                               recipientAmount: MilliSatoshi,
                               recipientNodeId: PublicKey,
                               upstream: Upstream,
                               paymentRequest: Option[PaymentRequest],
                               storeInDb: Boolean, // e.g. for trampoline we don't want to store in the DB when we're relaying payments
                               publishEvent: Boolean,
                               recordMetrics: Boolean,
                               additionalHops: Seq[NodeHop]) {
    def fullRoute(route: Route): Seq[Hop] = route.hops ++ additionalHops

    def createPaymentSent(preimage: ByteVector32, parts: Seq[PaymentSent.PartialPayment]) = PaymentSent(parentId, paymentHash, preimage, recipientAmount, recipientNodeId, parts)

    def paymentContext: PaymentContext = PaymentContext(id, parentId, paymentHash)
  }

}
