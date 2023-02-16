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
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.{OfferTypes, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{Features, InvoiceFeature, MilliSatoshi, NodeParams, TimestampSecond, randomKey}

object OfferPayment {
  sealed trait Failure

  case class UnsupportedFeatures(features: Features[InvoiceFeature]) extends Failure

  case class UnsupportedChains(chains: Seq[ByteVector32]) extends Failure

  case class ExpiredOffer(expiryDate: TimestampSecond) extends Failure

  case class QuantityTooHigh(quantityMax: Long) extends Failure

  case class AmountInsufficient(amountNeeded: MilliSatoshi) extends Failure

  case object NoInvoice extends Failure

  case class InvalidInvoice(tlvs: TlvStream[OfferTypes.InvoiceTlv], request: InvoiceRequest, message: String) extends Failure {
    override def toString: String = s"Invalid invoice: $message, invoice request: $request, received invoice: $tlvs"
  }

  sealed trait Command

  case class PayOffer(replyTo: ActorRef,
                      offer: Offer,
                      amount: MilliSatoshi,
                      quantity: Long,
                      sendPaymentConfig: SendPaymentConfig) extends Command

  case class WrappedMessageResponse(response: OnionMessageResponse) extends Command

  case class SendPaymentConfig(externalId_opt: Option[String],
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
    // TODO: random choice of intermediate nodes
    val intermediateNodes = Nil
    val messageContent = TlvStream[OnionMessagePayloadTlv](OnionMessagePayloadTlv.InvoiceRequest(request.records))
    postman ! SendMessage(intermediateNodes, destination, Some((nodeParams.nodeId +: intermediateNodes).reverse), messageContent, context.messageAdapter(WrappedMessageResponse), nodeParams.onionMessageConfig.timeout)
    waitForInvoice(nodeParams, postman, paymentInitiator, request, payerKey, replyTo, attemptNumber + 1, sendPaymentConfig)
  }

  def waitForInvoice(nodeParams: NodeParams,
                     postman: typed.ActorRef[Postman.Command],
                     paymentInitiator: ActorRef,
                     request: InvoiceRequest,
                     payerKey: PrivateKey,
                     replyTo: ActorRef,
                     attemptNumber: Int,
                     sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    Behaviors.setup(context =>
      Behaviors.receiveMessagePartial {
        case WrappedMessageResponse(Postman.Response(payload)) if payload.records.get[OnionMessagePayloadTlv.Invoice].nonEmpty =>
          val tlvs = payload.records.get[OnionMessagePayloadTlv.Invoice].get.tlvs
          Bolt12Invoice.validate(tlvs) match {
            case Right(invoice) =>
              invoice.validateFor(request) match {
                case Left(reason) =>
                  replyTo ! InvalidInvoice(tlvs, request, reason)
                  Behaviors.stopped
                case Right(()) =>
                  val recipientAmount = invoice.amount
                  paymentInitiator ! SendPaymentToNode(replyTo, recipientAmount, invoice, maxAttempts = sendPaymentConfig.maxAttempts, externalId = sendPaymentConfig.externalId_opt, routeParams = sendPaymentConfig.routeParams, payerKey_opt = Some(payerKey), blockUntilComplete = sendPaymentConfig.blocking)
                  Behaviors.stopped
              }
            case Left(invalidTlvs) =>
              replyTo ! InvalidInvoice(tlvs, request, invalidTlvs.toString)
              Behaviors.stopped
          }
        case WrappedMessageResponse(_) if attemptNumber < nodeParams.onionMessageConfig.maxAttempts =>
          // We didn't get an invoice, let's retry.
          sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, request, payerKey, replyTo, attemptNumber, sendPaymentConfig)
        case WrappedMessageResponse(_) =>
          replyTo ! NoInvoice
          Behaviors.stopped
      })
  }
}


