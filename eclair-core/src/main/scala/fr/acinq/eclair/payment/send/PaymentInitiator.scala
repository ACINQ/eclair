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
import fr.acinq.eclair.channel.{Channel, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.{NodeHop, RouteParams}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{Onion, OnionTlv, TrampolineExpiryTooSoon, TrampolineFeeInsufficient}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, randomBytes32}

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
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.targetNodeId, Upstream.Local(paymentId), r.paymentRequest, storeInDb = true, publishEvent = true)
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      r.paymentRequest match {
        case Some(invoice) if !invoice.features.supported =>
          sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException(s"can't send payment: unknown invoice features (${invoice.features})")) :: Nil)
        case Some(invoice) if invoice.features.allowMultiPart => invoice.paymentSecret match {
          case Some(paymentSecret) => r.predefinedRoute match {
            case Nil => spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(r.paymentHash, paymentSecret, r.targetNodeId, r.amount, finalExpiry, r.maxAttempts, r.assistedRoutes, r.routeParams)
            case hops => spawnPaymentFsm(paymentCfg) forward SendPaymentToRoute(r.paymentHash, hops, Onion.createMultiPartPayload(r.amount, invoice.amount.getOrElse(r.amount), finalExpiry, paymentSecret))
          }
          case None => sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException("can't send payment: multi-part invoice is missing a payment secret")) :: Nil)
        }
        case _ =>
          val payFsm = spawnPaymentFsm(paymentCfg)
          // NB: we only generate legacy payment onions for now for maximum compatibility.
          r.predefinedRoute match {
            case Nil => payFsm forward SendPayment(r.paymentHash, r.targetNodeId, FinalLegacyPayload(r.amount, finalExpiry), r.maxAttempts, r.assistedRoutes, r.routeParams)
            case hops => payFsm forward SendPaymentToRoute(r.paymentHash, hops, FinalLegacyPayload(r.amount, finalExpiry))
          }
      }

    case r: SendTrampolinePaymentRequest =>
      val paymentId = UUID.randomUUID()
      sender ! paymentId
      if (r.trampolineAttempts.isEmpty) {
        sender ! PaymentFailed(paymentId, r.paymentRequest.paymentHash, LocalFailure(new IllegalArgumentException("trampoline fees and cltv expiry delta are missing")) :: Nil)
      } else if (!r.paymentRequest.features.allowTrampoline && r.paymentRequest.amount.isEmpty) {
        sender ! PaymentFailed(paymentId, r.paymentRequest.paymentHash, LocalFailure(new IllegalArgumentException("cannot pay a 0-value invoice via trampoline-to-legacy (trampoline may steal funds)")) :: Nil)
      } else {
        val (trampolineFees, trampolineExpiryDelta) =  r.trampolineAttempts.head
        log.info(s"sending trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
        sendTrampolinePayment(paymentId, r, r.trampolineAttempts.head._1, r.trampolineAttempts.head._2)
        context become main(pending + (paymentId -> PendingPayment(sender, r.trampolineAttempts.tail, r)))
      }

    case pf: PaymentFailed => pending.get(pf.id).foreach(pp => {
      val decryptedFailures = pf.failures.collect { case RemoteFailure(_, Sphinx.DecryptedFailurePacket(_, f)) => f }
      val shouldRetry = pp.remainingAttempts.nonEmpty && (decryptedFailures.contains(TrampolineFeeInsufficient) || decryptedFailures.contains(TrampolineExpiryTooSoon))
      if (shouldRetry) {
        val (trampolineFees, trampolineExpiryDelta) =  pp.remainingAttempts.head
        log.info(s"retrying trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
        sendTrampolinePayment(pf.id, pp.r, trampolineFees, trampolineExpiryDelta)
        context become main(pending + (pf.id -> pp.copy(remainingAttempts = pp.remainingAttempts.tail)))
      } else {
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
  }

  def spawnPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(PaymentLifecycle.props(nodeParams, paymentCfg, router, register))

  def spawnMultiPartPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentCfg, relayer, router, register))

  private def sendTrampolinePayment(paymentId: UUID, r: SendTrampolinePaymentRequest, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): Unit = {
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentRequest.paymentHash, r.trampolineNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = false, Some(r.copy(trampolineAttempts = Seq((trampolineFees, trampolineExpiryDelta)))))
    val finalPayload = if (r.paymentRequest.features.allowMultiPart) {
      Onion.createMultiPartPayload(r.finalAmount, r.finalAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret.get)
    } else {
      Onion.createSinglePartPayload(r.finalAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret)
    }
    val trampolineRoute = Seq(
      NodeHop(nodeParams.nodeId, r.trampolineNodeId, nodeParams.expiryDeltaBlocks, 0 msat),
      NodeHop(r.trampolineNodeId, r.paymentRequest.nodeId, trampolineExpiryDelta, trampolineFees) // for now we only use a single trampoline hop
    )
    // We assume that the trampoline node supports multi-part payments (it should).
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (r.paymentRequest.features.allowTrampoline) {
      OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(r.paymentRequest.paymentHash, trampolineRoute, finalPayload)
    } else {
      OutgoingPacket.buildTrampolineToLegacyPacket(r.paymentRequest, trampolineRoute, finalPayload)
    }
    // We generate a random secret for this payment to avoid leaking the invoice secret to the first trampoline node.
    val trampolineSecret = randomBytes32
    spawnMultiPartPaymentFsm(paymentCfg) ! SendMultiPartPayment(r.paymentRequest.paymentHash, trampolineSecret, r.trampolineNodeId, trampolineAmount, trampolineExpiry, 1, r.paymentRequest.routingInfo, r.routeParams, Seq(OnionTlv.TrampolineOnion(trampolineOnion.packet)))
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
   * @param finalAmount        amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param paymentRequest     Bolt 11 invoice.
   * @param trampolineNodeId   id of the trampoline node.
   * @param trampolineAttempts fees and expiry delta for the trampoline node. If this list contains multiple entries,
   *                           the payment will automatically be retried in case of TrampolineFeeInsufficient errors.
   *                           For example, [(10 msat, 144), (15 msat, 288)] will first send a payment with a fee of 10
   *                           msat and cltv of 144, and retry with 15 msat and 288 in case an error occurs.
   * @param finalExpiryDelta   expiry delta for the final recipient.
   * @param routeParams        (optional) parameters to fine-tune the routing algorithm.
   */
  case class SendTrampolinePaymentRequest(finalAmount: MilliSatoshi,
                                          paymentRequest: PaymentRequest,
                                          trampolineNodeId: PublicKey,
                                          trampolineAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)],
                                          finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                          routeParams: Option[RouteParams] = None) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  case class SendPaymentRequest(amount: MilliSatoshi,
                                paymentHash: ByteVector32,
                                targetNodeId: PublicKey,
                                maxAttempts: Int,
                                finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                paymentRequest: Option[PaymentRequest] = None,
                                externalId: Option[String] = None,
                                predefinedRoute: Seq[PublicKey] = Nil,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  case class SendPaymentConfig(id: UUID,
                               parentId: UUID,
                               externalId: Option[String],
                               paymentHash: ByteVector32,
                               targetNodeId: PublicKey,
                               upstream: Upstream,
                               paymentRequest: Option[PaymentRequest],
                               storeInDb: Boolean, // e.g. for trampoline we don't want to store in the DB when we're relaying payments
                               publishEvent: Boolean,
                               // TODO: @t-bast: this is a very awkward work-around to get accurate data in the DB: fix this once we update the DB schema
                               trampolineData: Option[SendTrampolinePaymentRequest] = None)

}
