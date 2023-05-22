/*
 * Copyright 2023 ACINQ SAS
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

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.message.Postman.{OnionMessageResponse, SendMessage}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, InvoicePayload}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{Features, InvoiceFeature, MilliSatoshi, NodeParams, TimestampSecond, randomKey}

object OfferPayment {
  sealed trait Failure

  case class UnsupportedFeatures(features: Features[InvoiceFeature]) extends Failure

  case class UnsupportedChains(chains: Seq[ByteVector32]) extends Failure

  case class ExpiredOffer(expiryDate: TimestampSecond) extends Failure

  case class QuantityTooHigh(quantityMax: Long) extends Failure

  case class AmountInsufficient(amountNeeded: MilliSatoshi) extends Failure

  case object NoInvoiceResponse extends Failure

  case class InvalidInvoiceResponse(request: InvoiceRequest, response: FinalPayload) extends Failure {
    override def toString: String = s"Invalid invoice response: $response, invoice request: $request"
  }

  sealed trait Command

  case class PayOffer(replyTo: ActorRef,
                      offer: Offer,
                      amount: MilliSatoshi,
                      quantity: Long,
                      sendPaymentConfig: SendPaymentConfig) extends Command

  case class WrappedMessageResponse(response: OnionMessageResponse) extends Command

  case class SendPaymentConfig(externalId_opt: Option[String],
                               connectDirectly: Boolean,
                               maxAttempts: Int,
                               routeParams: RouteParams,
                               blocking: Boolean)

  def apply(nodeParams: NodeParams,
            postman: typed.ActorRef[Postman.Command],
            paymentInitiator: ActorRef): Behavior[Command] = {
    Behaviors.setup(context =>
      Behaviors.receiveMessagePartial {
        case PayOffer(replyTo, offer, amount, quantity, sendPaymentConfig) =>
          if (!nodeParams.features.bolt12Features().areSupported(offer.features)) {
            replyTo ! UnsupportedFeatures(offer.features.invoiceFeatures())
            Behaviors.stopped
          } else if (!offer.chains.contains(nodeParams.chainHash)) {
            replyTo ! UnsupportedChains(offer.chains)
            Behaviors.stopped
          } else if (offer.expiry.exists(_ < TimestampSecond.now())) {
            replyTo ! ExpiredOffer(offer.expiry.get)
            Behaviors.stopped
          } else if (offer.quantityMax.getOrElse(1L) < quantity) {
            replyTo ! QuantityTooHigh(offer.quantityMax.getOrElse(1))
            Behaviors.stopped
          } else if (offer.amount.exists(_ * quantity > amount)) {
            replyTo ! AmountInsufficient(offer.amount.get * quantity)
            Behaviors.stopped
          } else {
            val payerKey = randomKey()
            val request = InvoiceRequest(offer, amount, quantity, nodeParams.features.bolt12Features(), payerKey, nodeParams.chainHash)
            sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, request, payerKey, replyTo, 0, sendPaymentConfig)
          }
      })
  }

  def sendInvoiceRequest(nodeParams: NodeParams,
                         postman: typed.ActorRef[Postman.Command],
                         paymentInitiator: ActorRef,
                         context: ActorContext[Command],
                         request: InvoiceRequest,
                         payerKey: PrivateKey,
                         replyTo: ActorRef,
                         attemptNumber: Int,
                         sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    val destination = request.offer.contactInfo match {
      case Left(blindedRoutes) =>
        val blindedRoute = blindedRoutes(attemptNumber % blindedRoutes.length)
        OnionMessages.BlindedPath(blindedRoute)
      case Right(nodeId) =>
        OnionMessages.Recipient(nodeId, None, None)
    }
    val messageContent = TlvStream[OnionMessagePayloadTlv](OnionMessagePayloadTlv.InvoiceRequest(request.records))
    val routingStrategy = if (sendPaymentConfig.connectDirectly) OnionMessages.RoutingStrategy.connectDirectly else OnionMessages.RoutingStrategy.FindRoute
    postman ! SendMessage(destination, routingStrategy, messageContent, expectsReply = true, context.messageAdapter(WrappedMessageResponse))
    waitForInvoice(nodeParams, postman, paymentInitiator, context, request, payerKey, replyTo, attemptNumber + 1, sendPaymentConfig)
  }

  def waitForInvoice(nodeParams: NodeParams,
                     postman: typed.ActorRef[Postman.Command],
                     paymentInitiator: ActorRef,
                     context: ActorContext[Command],
                     request: InvoiceRequest,
                     payerKey: PrivateKey,
                     replyTo: ActorRef,
                     attemptNumber: Int,
                     sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedMessageResponse(Postman.Response(payload: InvoicePayload)) if payload.invoice.validateFor(request).isRight =>
        val recipientAmount = payload.invoice.amount
        paymentInitiator ! SendPaymentToNode(replyTo, recipientAmount, payload.invoice, maxAttempts = sendPaymentConfig.maxAttempts, externalId = sendPaymentConfig.externalId_opt, routeParams = sendPaymentConfig.routeParams, payerKey_opt = Some(payerKey), blockUntilComplete = sendPaymentConfig.blocking)
        Behaviors.stopped
      case WrappedMessageResponse(Postman.Response(payload)) =>
        // We've received a response but it is not an invoice as we expected or it is an invalid invoice.
        replyTo ! InvalidInvoiceResponse(request, payload)
        Behaviors.stopped
      case WrappedMessageResponse(Postman.NoReply) if attemptNumber < nodeParams.onionMessageConfig.maxAttempts =>
        // We didn't get a response, let's retry.
        sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, request, payerKey, replyTo, attemptNumber, sendPaymentConfig)
      case WrappedMessageResponse(_) =>
        // We can't reach the offer node or the offer node can't reach us.
        replyTo ! NoInvoiceResponse
        Behaviors.stopped
    }
  }
}