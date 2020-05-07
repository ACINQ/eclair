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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Features.BasicMultiPartPayment
import fr.acinq.eclair.channel.{Channel, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentError._
import fr.acinq.eclair.payment.send.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.router.Router.{ChannelHop, Hop, NodeHop, RouteParams}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, randomBytes32}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = main(Map.empty)

  def main(pending: Map[UUID, PendingPayment]): Receive = {
    case r: SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      sender ! paymentId
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), r.paymentRequest, storeInDb = true, publishEvent = true, Nil)
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      r.paymentRequest match {
        case Some(invoice) if !invoice.features.supported =>
          sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(UnsupportedFeatures(invoice.features.features)) :: Nil)
        case Some(invoice) if invoice.features.allowMultiPart && nodeParams.features.hasFeature(BasicMultiPartPayment) =>
          invoice.paymentSecret match {
            case Some(paymentSecret) =>
              spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(paymentSecret, r.recipientNodeId, r.recipientAmount, finalExpiry, r.maxAttempts, r.assistedRoutes, r.routeParams, userCustomTlvs = r.userCustomTlvs)
            case None =>
              sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(PaymentSecretMissing) :: Nil)
          }
        case _ =>
          val paymentSecret = r.paymentRequest.flatMap(_.paymentSecret)
          val finalPayload = Onion.createSinglePartPayload(r.recipientAmount, finalExpiry, paymentSecret, r.userCustomTlvs)
          spawnPaymentFsm(paymentCfg) forward SendPayment(r.recipientNodeId, finalPayload, r.maxAttempts, r.assistedRoutes, r.routeParams)
      }

    case r: SendTrampolinePaymentRequest =>
      val paymentId = UUID.randomUUID()
      sender ! paymentId
      r.trampolineAttempts match {
        case Nil =>
          sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(TrampolineFeesMissing) :: Nil)
        case _ if !r.paymentRequest.features.allowTrampoline && r.paymentRequest.amount.isEmpty =>
          sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(TrampolineLegacyAmountLessInvoice) :: Nil)
        case (trampolineFees, trampolineExpiryDelta) :: remainingAttempts =>
          log.info(s"sending trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
          sendTrampolinePayment(paymentId, r, trampolineFees, trampolineExpiryDelta)
          context become main(pending + (paymentId -> PendingPayment(sender, remainingAttempts, r)))
      }

    case pf: PaymentFailed => pending.get(pf.id).foreach(pp => {
      val decryptedFailures = pf.failures.collect { case RemoteFailure(_, Sphinx.DecryptedFailurePacket(_, f)) => f }
      val canRetry = decryptedFailures.contains(TrampolineFeeInsufficient) || decryptedFailures.contains(TrampolineExpiryTooSoon)
      pp.remainingAttempts match {
        case (trampolineFees, trampolineExpiryDelta) :: remainingAttempts if canRetry =>
          log.info(s"retrying trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
          sendTrampolinePayment(pf.id, pp.r, trampolineFees, trampolineExpiryDelta)
          context become main(pending + (pf.id -> pp.copy(remainingAttempts = remainingAttempts)))
        case _ =>
          pp.sender ! pf
          context.system.eventStream.publish(pf)
          context become main(pending - pf.id)
      }
    })

    case ps: PaymentSent => pending.get(ps.id).foreach(pp => {
      pp.sender ! ps
      context.system.eventStream.publish(ps)
      context become main(pending - ps.id)
    })

    case r: SendPaymentToRouteRequest =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      val additionalHops = r.trampolineNodes.sliding(2).map(hop => NodeHop(hop.head, hop(1), CltvExpiryDelta(0), 0 msat)).toSeq
      val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = true, additionalHops)
      val payFsm = spawnPaymentFsm(paymentCfg)
      r.trampolineNodes match {
        case trampoline :: recipient :: Nil =>
          log.info(s"sending trampoline payment to $recipient with trampoline=$trampoline, trampoline fees=${r.trampolineFees}, expiry delta=${r.trampolineExpiryDelta}")
          // We generate a random secret for the payment to the first trampoline node.
          val trampolineSecret = r.trampolineSecret.getOrElse(randomBytes32)
          sender ! SendPaymentToRouteResponse(paymentId, parentPaymentId, Some(trampolineSecret))
          val (trampolineAmount, trampolineExpiry, trampolineOnion) = buildTrampolinePayment(SendTrampolinePaymentRequest(r.recipientAmount, r.paymentRequest, trampoline, Seq((r.trampolineFees, r.trampolineExpiryDelta)), r.finalExpiryDelta), r.trampolineFees, r.trampolineExpiryDelta)
          payFsm forward SendPaymentToRoute(r.route, Onion.createMultiPartPayload(r.amount, trampolineAmount, trampolineExpiry, trampolineSecret, Seq(OnionTlv.TrampolineOnion(trampolineOnion))), r.paymentRequest.routingInfo)
        case Nil =>
          sender ! SendPaymentToRouteResponse(paymentId, parentPaymentId, None)
          r.paymentRequest.paymentSecret match {
            case Some(paymentSecret) => payFsm forward SendPaymentToRoute(r.route, Onion.createMultiPartPayload(r.amount, r.recipientAmount, finalExpiry, paymentSecret), r.paymentRequest.routingInfo)
            case None => payFsm forward SendPaymentToRoute(r.route, FinalLegacyPayload(r.recipientAmount, finalExpiry), r.paymentRequest.routingInfo)
          }
        case _ =>
          sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(TrampolineMultiNodeNotSupported) :: Nil)
      }
  }

  def spawnPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(PaymentLifecycle.props(nodeParams, paymentCfg, router, register))

  def spawnMultiPartPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentCfg, relayer, router, register))

  private def buildTrampolinePayment(r: SendTrampolinePaymentRequest, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): (MilliSatoshi, CltvExpiry, OnionRoutingPacket) = {
    val trampolineRoute = Seq(
      NodeHop(nodeParams.nodeId, r.trampolineNodeId, nodeParams.expiryDeltaBlocks, 0 msat),
      NodeHop(r.trampolineNodeId, r.recipientNodeId, trampolineExpiryDelta, trampolineFees) // for now we only use a single trampoline hop
    )
    val finalPayload = if (r.paymentRequest.features.allowMultiPart) {
      Onion.createMultiPartPayload(r.recipientAmount, r.recipientAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret.get)
    } else {
      Onion.createSinglePartPayload(r.recipientAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret)
    }
    // We assume that the trampoline node supports multi-part payments (it should).
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (r.paymentRequest.features.allowTrampoline) {
      OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(r.paymentHash, trampolineRoute, finalPayload)
    } else {
      OutgoingPacket.buildTrampolineToLegacyPacket(r.paymentRequest, trampolineRoute, finalPayload)
    }
    (trampolineAmount, trampolineExpiry, trampolineOnion.packet)
  }

  private def sendTrampolinePayment(paymentId: UUID, r: SendTrampolinePaymentRequest, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): Unit = {
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentHash, r.recipientAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = false, Seq(NodeHop(r.trampolineNodeId, r.recipientNodeId, trampolineExpiryDelta, trampolineFees)))
    // We generate a random secret for this payment to avoid leaking the invoice secret to the first trampoline node.
    val trampolineSecret = randomBytes32
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = buildTrampolinePayment(r, trampolineFees, trampolineExpiryDelta)
    spawnMultiPartPaymentFsm(paymentCfg) ! SendMultiPartPayment(trampolineSecret, r.trampolineNodeId, trampolineAmount, trampolineExpiry, 1, r.paymentRequest.routingInfo, r.routeParams, Seq(OnionTlv.TrampolineOnion(trampolineOnion)))
  }

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, relayer, register)

  case class PendingPayment(sender: ActorRef, remainingAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)], r: SendTrampolinePaymentRequest)

  /**
   * We temporarily let the caller decide to use Trampoline (instead of a normal payment) and set the fees/cltv.
   * Once we have trampoline fee estimation built into the router, the decision to use Trampoline or not should be done
   * automatically by the router instead of the caller.
   *
   * @param recipientAmount    amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param paymentRequest     Bolt 11 invoice.
   * @param trampolineNodeId   id of the trampoline node.
   * @param trampolineAttempts fees and expiry delta for the trampoline node. If this list contains multiple entries,
   *                           the payment will automatically be retried in case of TrampolineFeeInsufficient errors.
   *                           For example, [(10 msat, 144), (15 msat, 288)] will first send a payment with a fee of 10
   *                           msat and cltv of 144, and retry with 15 msat and 288 in case an error occurs.
   * @param finalExpiryDelta   expiry delta for the final recipient.
   * @param routeParams        (optional) parameters to fine-tune the routing algorithm.
   */
  case class SendTrampolinePaymentRequest(recipientAmount: MilliSatoshi,
                                          paymentRequest: PaymentRequest,
                                          trampolineNodeId: PublicKey,
                                          trampolineAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)],
                                          finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                          routeParams: Option[RouteParams] = None) {
    val recipientNodeId = paymentRequest.nodeId
    val paymentHash = paymentRequest.paymentHash

    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  /**
   * @param recipientAmount  amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param paymentHash      payment hash.
   * @param recipientNodeId  id of the final recipient.
   * @param maxAttempts      maximum number of retries.
   * @param finalExpiryDelta expiry delta for the final recipient.
   * @param paymentRequest   (optional) Bolt 11 invoice.
   * @param externalId       (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param assistedRoutes   (optional) routing hints (usually from a Bolt 11 invoice).
   * @param routeParams      (optional) parameters to fine-tune the routing algorithm.
   * @param userCustomTlvs   (optional) user-defined custom tlvs that will be added to the onion sent to the target node.
   */
  case class SendPaymentRequest(recipientAmount: MilliSatoshi,
                                paymentHash: ByteVector32,
                                recipientNodeId: PublicKey,
                                maxAttempts: Int,
                                finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                paymentRequest: Option[PaymentRequest] = None,
                                externalId: Option[String] = None,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None,
                                userCustomTlvs: Seq[GenericTlv] = Nil) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
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
   * @param amount                amount that should be received by the last node in the route (should take trampoline
   *                              fees into account).
   * @param recipientAmount       amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   *                              This amount may be split between multiple requests if using MPP.
   * @param externalId            (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId              id of the whole payment. When manually sending a multi-part payment, you need to make
   *                              sure all partial payments use the same parentId. If not provided, a random parentId will
   *                              be generated that can be used for the remaining partial payments.
   * @param paymentRequest        Bolt 11 invoice.
   * @param finalExpiryDelta      expiry delta for the final recipient.
   * @param route                 route to use to reach either the final recipient or the first trampoline node.
   * @param trampolineSecret      if trampoline is used, this is a secret to protect the payment to the first trampoline
   *                              node against probing. When manually sending a multi-part payment, you need to make sure
   *                              all partial payments use the same trampolineSecret.
   * @param trampolineFees        if trampoline is used, fees for the first trampoline node. This value must be the same
   *                              for all partial payments in the set.
   * @param trampolineExpiryDelta if trampoline is used, expiry delta for the first trampoline node. This value must be
   *                              the same for all partial payments in the set.
   * @param trampolineNodes       if trampoline is used, list of trampoline nodes to use (we currently support only a
   *                              single trampoline node).
   */
  case class SendPaymentToRouteRequest(amount: MilliSatoshi,
                                       recipientAmount: MilliSatoshi,
                                       externalId: Option[String],
                                       parentId: Option[UUID],
                                       paymentRequest: PaymentRequest,
                                       finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                       route: Seq[PublicKey],
                                       trampolineSecret: Option[ByteVector32],
                                       trampolineFees: MilliSatoshi,
                                       trampolineExpiryDelta: CltvExpiryDelta,
                                       trampolineNodes: Seq[PublicKey]) {
    val recipientNodeId = paymentRequest.nodeId
    val paymentHash = paymentRequest.paymentHash

    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

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
                               additionalHops: Seq[NodeHop]) {
    def fullRoute(hops: Seq[ChannelHop]): Seq[Hop] = hops ++ additionalHops

    def createPaymentSent(preimage: ByteVector32, parts: Seq[PaymentSent.PartialPayment]) = PaymentSent(parentId, paymentHash, preimage, recipientAmount, recipientNodeId, parts)
  }

}
