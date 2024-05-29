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
import fr.acinq.bitcoin.scalacompat.BlockHash
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.message.Postman.{OnionMessageResponse, SendMessage}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.send.BlindedPathsResolver.{Resolve, ResolvedPath}
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentToNode, SendTrampolinePayment}
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentBlindedRoute}
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, InvoicePayload}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, EncodedNodeId, Features, InvoiceFeature, MilliSatoshi, NodeParams, RealShortChannelId, TimestampSecond, randomKey}

object OfferPayment {
  sealed trait Failure

  case class UnsupportedFeatures(features: Features[InvoiceFeature]) extends Failure

  case class UnsupportedChains(chains: Seq[BlockHash]) extends Failure

  case class ExpiredOffer(expiryDate: TimestampSecond) extends Failure

  case class QuantityTooHigh(quantityMax: Long) extends Failure

  case class AmountInsufficient(amountNeeded: MilliSatoshi) extends Failure

  case object NoInvoiceResponse extends Failure

  case class InvalidInvoiceResponse(request: InvoiceRequest, response: FinalPayload) extends Failure {
    override def toString: String = s"Invalid invoice response: $response, invoice request: $request"
  }

  case class UnknownShortChannelIds(scids: Seq[RealShortChannelId]) extends Failure {
    override def toString: String = s"Unknown short channel ids: ${scids.mkString(",")}"
  }

  sealed trait Command

  case class PayOffer(replyTo: ActorRef,
                      offer: Offer,
                      amount: MilliSatoshi,
                      quantity: Long,
                      sendPaymentConfig: SendPaymentConfig) extends Command

  case class WrappedMessageResponse(response: OnionMessageResponse) extends Command

  private case class WrappedResolvedPaths(resolved: Seq[ResolvedPath]) extends Command

  case class SendPaymentConfig(externalId_opt: Option[String],
                               connectDirectly: Boolean,
                               maxAttempts: Int,
                               routeParams: RouteParams,
                               blocking: Boolean,
                               trampoline: Option[TrampolineConfig] = None)

  case class TrampolineConfig(nodeId: PublicKey, attempts: Seq[(MilliSatoshi, CltvExpiryDelta)])

  def apply(nodeParams: NodeParams,
            postman: typed.ActorRef[Postman.Command],
            router: ActorRef,
            register: ActorRef,
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
            val offerPayment = new OfferPayment(replyTo, nodeParams, postman, router, register, paymentInitiator, payerKey, request, sendPaymentConfig, context)
            offerPayment.sendInvoiceRequest(attemptNumber = 0)
          }
      })
  }
}

private class OfferPayment(replyTo: ActorRef,
                           nodeParams: NodeParams,
                           postman: typed.ActorRef[Postman.Command],
                           router: ActorRef,
                           register: ActorRef,
                           paymentInitiator: ActorRef,
                           payerKey: PrivateKey,
                           invoiceRequest: InvoiceRequest,
                           sendPaymentConfig: OfferPayment.SendPaymentConfig,
                           context: ActorContext[OfferPayment.Command]) {

  import OfferPayment._

  def sendInvoiceRequest(attemptNumber: Int): Behavior[Command] = {
    val contactInfo = invoiceRequest.offer.contactInfos(attemptNumber % invoiceRequest.offer.contactInfos.length)
    val messageContent = TlvStream[OnionMessagePayloadTlv](OnionMessagePayloadTlv.InvoiceRequest(invoiceRequest.records))
    val routingStrategy = if (sendPaymentConfig.connectDirectly) OnionMessages.RoutingStrategy.connectDirectly else OnionMessages.RoutingStrategy.FindRoute
    postman ! SendMessage(contactInfo, routingStrategy, messageContent, expectsReply = true, context.messageAdapter(WrappedMessageResponse))
    waitForInvoice(attemptNumber + 1, contactInfo.nodeId)
  }

  private def waitForInvoice(attemptNumber: Int, pathNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedMessageResponse(Postman.Response(payload: InvoicePayload)) if payload.invoice.validateFor(invoiceRequest, pathNodeId).isRight =>
        sendPaymentConfig.trampoline match {
          case Some(trampoline) =>
            paymentInitiator ! SendTrampolinePayment(replyTo, payload.invoice.amount, payload.invoice, trampoline.nodeId, trampoline.attempts, sendPaymentConfig.routeParams)
            Behaviors.stopped
          case None =>
            context.spawnAnonymous(BlindedPathsResolver(nodeParams, router, register)) ! Resolve(context.messageAdapter[Seq[ResolvedPath]](WrappedResolvedPaths), payload.invoice.blindedPaths)
            waitForResolvedPaths(payload.invoice)
        }
      case WrappedMessageResponse(Postman.Response(payload)) =>
        // We've received a response but it is not an invoice as we expected or it is an invalid invoice.
        replyTo ! InvalidInvoiceResponse(invoiceRequest, payload)
        Behaviors.stopped
      case WrappedMessageResponse(Postman.NoReply) if attemptNumber < nodeParams.onionMessageConfig.maxAttempts =>
        // We didn't get a response, let's retry.
        sendInvoiceRequest(attemptNumber)
      case WrappedMessageResponse(_) =>
        // We can't reach the offer node or the offer node can't reach us.
        replyTo ! NoInvoiceResponse
        Behaviors.stopped
    }
  }

  /**
   * Blinded paths in Bolt 12 invoices may encode the introduction node with an scid and a direction: we need to resolve
   * that to a nodeId in order to reach that introduction node and use the blinded path.
   */
  private def waitForResolvedPaths(invoice: Bolt12Invoice): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedResolvedPaths(resolved) if resolved.isEmpty =>
        // We couldn't identify any of the blinded paths' introduction nodes because the scids are unknown.
        val scids = invoice.blindedPaths.collect { case PaymentBlindedRoute(BlindedRoute(EncodedNodeId.ShortChannelIdDir(_, scid), _, _), _) => scid }
        replyTo ! UnknownShortChannelIds(scids)
        Behaviors.stopped
      case WrappedResolvedPaths(resolved) =>
        paymentInitiator ! SendPaymentToNode(replyTo, invoice.amount, invoice, resolved, maxAttempts = sendPaymentConfig.maxAttempts, externalId = sendPaymentConfig.externalId_opt, routeParams = sendPaymentConfig.routeParams, payerKey_opt = Some(payerKey), blockUntilComplete = sendPaymentConfig.blocking)
        Behaviors.stopped
    }
}
