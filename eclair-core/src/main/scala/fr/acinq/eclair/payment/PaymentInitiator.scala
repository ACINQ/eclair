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

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.payment.PaymentLifecycle.{DefaultPaymentProgressHandler, SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.RouteParams
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, NodeParams}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = {
    case p: PaymentInitiator.SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      // We add one block in order to not have our htlc fail when a new block has just been found.
      val finalExpiry = p.finalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight + 1)
      val payFsm = context.actorOf(PaymentLifecycle.props(nodeParams, DefaultPaymentProgressHandler(paymentId, p, nodeParams.db.payments), router, register))
      // NB: we only generate legacy payment onions for now for maximum compatibility.
      p.predefinedRoute match {
        case Nil => payFsm forward SendPayment(p.paymentHash, p.targetNodeId, FinalLegacyPayload(p.amount, finalExpiry), p.maxAttempts, p.assistedRoutes, p.routeParams)
        case hops => payFsm forward SendPaymentToRoute(p.paymentHash, hops, FinalLegacyPayload(p.amount, finalExpiry))
      }
      sender ! paymentId
  }

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, register)

  case class SendPaymentRequest(amount: MilliSatoshi,
                                paymentHash: ByteVector32,
                                targetNodeId: PublicKey,
                                maxAttempts: Int,
                                finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                paymentRequest: Option[PaymentRequest] = None,
                                externalId: Option[String] = None,
                                predefinedRoute: Seq[PublicKey] = Nil,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None)

}
