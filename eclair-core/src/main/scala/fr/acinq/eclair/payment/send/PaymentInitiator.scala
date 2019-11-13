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
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.{LocalFailure, OutgoingPacket, PaymentFailed, PaymentRequest}
import fr.acinq.eclair.router.{NodeHop, RouteParams}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{Onion, OnionTlv}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, randomBytes32}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = {
    case r: SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.targetNodeId, r.paymentRequest, storeInDb = true, publishEvent = true)
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      if (r.paymentRequest.exists(!_.features.supported)) {
        sender ! paymentId
        sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException(s"can't send payment: unknown invoice features (${r.paymentRequest.get.features})")) :: Nil)
      } else {
        r.paymentRequest match {
          case Some(invoice) if invoice.features.allowMultiPart =>
            r.predefinedRoute match {
              case Nil => spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(r.paymentHash, invoice.paymentSecret.get, r.targetNodeId, r.amount, finalExpiry, r.maxAttempts, r.assistedRoutes, r.routeParams)
              case hops => spawnPaymentFsm(paymentCfg) forward SendPaymentToRoute(r.paymentHash, hops, Onion.createMultiPartPayload(r.amount, invoice.amount.getOrElse(r.amount), finalExpiry, invoice.paymentSecret.get))
            }
          case _ =>
            val payFsm = spawnPaymentFsm(paymentCfg)
            // NB: we only generate legacy payment onions for now for maximum compatibility.
            r.predefinedRoute match {
              case Nil => payFsm forward SendPayment(r.paymentHash, r.targetNodeId, FinalLegacyPayload(r.amount, finalExpiry), r.maxAttempts, r.assistedRoutes, r.routeParams)
              case hops => payFsm forward SendPaymentToRoute(r.paymentHash, hops, FinalLegacyPayload(r.amount, finalExpiry))
            }
        }
        sender ! paymentId
      }

    case r: SendTrampolinePaymentRequest =>
      val paymentId = UUID.randomUUID()
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentRequest.paymentHash, r.trampolineNodeId, Some(r.paymentRequest), storeInDb = true, publishEvent = true)
      val trampolineRoute = Seq(
        NodeHop(nodeParams.nodeId, r.trampolineNodeId, nodeParams.expiryDeltaBlocks, 0 msat),
        NodeHop(r.trampolineNodeId, r.paymentRequest.nodeId, r.trampolineExpiryDelta, r.trampolineFees)
      )
      // We generate a random secret for this payment to avoid leaking the invoice secret to the first trampoline node.
      val trampolineSecret = randomBytes32
      val finalPayload = if (r.paymentRequest.features.allowMultiPart) {
        Onion.createMultiPartPayload(r.finalAmount, r.finalAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret.get)
      } else {
        Onion.createSinglePartPayload(r.finalAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret)
      }
      // We assume that the trampoline node supports multi-part payments (it should).
      val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (r.paymentRequest.features.allowTrampoline) {
        OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(r.paymentRequest.paymentHash, trampolineRoute, finalPayload)
      } else {
        OutgoingPacket.buildTrampolineToLegacyPacket(r.paymentRequest, trampolineRoute, finalPayload)
      }
      spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(r.paymentRequest.paymentHash, trampolineSecret, r.trampolineNodeId, trampolineAmount, trampolineExpiry, 1, r.paymentRequest.routingInfo, r.routeParams, Seq(OnionTlv.TrampolineOnion(trampolineOnion.packet)))
      sender ! paymentId
  }

  def spawnPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(PaymentLifecycle.props(nodeParams, paymentCfg, router, register))

  def spawnMultiPartPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentCfg, relayer, router, register))

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, relayer, register)

  /**
   * We temporarily let the caller decide to use Trampoline (instead of a normal payment) and set the fees/cltv.
   * It's the caller's responsibility to retry with a higher fee/cltv on certain failures.
   * Once we have trampoline fee estimation built into the router, the decision to use Trampoline or not should be done
   * automatically by the router instead of the caller.
   * TODO: @t-bast: remove this message once full Trampoline is implemented.
   */
  case class SendTrampolinePaymentRequest(finalAmount: MilliSatoshi,
                                          trampolineFees: MilliSatoshi,
                                          paymentRequest: PaymentRequest,
                                          trampolineNodeId: PublicKey,
                                          finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                          trampolineExpiryDelta: CltvExpiryDelta,
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
                               paymentRequest: Option[PaymentRequest],
                               storeInDb: Boolean, // e.g. for trampoline we don't want to store in the DB when we're relaying payments
                               publishEvent: Boolean)

}
