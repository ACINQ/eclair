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
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64}
import fr.acinq.eclair.message.Postman.{OnionMessageResponse, SendMessage}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentFailed, PaymentSent}
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{Features, InvoiceFeature, MilliSatoshi, NodeParams, TimestampSecond, randomKey}

import java.util.UUID
import scala.util.Random

object OfferPayment {
  sealed trait Result

  sealed trait Failure extends Exception with Result

  case class UnsupportedFeatures(features: Features[InvoiceFeature]) extends Exception(s"Unsupported features: $features") with Failure

  case class UnsupportedChains(chains: Seq[ByteVector32]) extends Exception(s"Unsupported chainsf: ${chains.mkString(",")}") with Failure

  case class ExpiredOffer(expiryDate: TimestampSecond) extends Exception(s"expired since $expiryDate") with Failure

  case class QuantityTooHigh(quantityMax: Long) extends Exception(s"Maximum quantity is $quantityMax") with Failure

  case class AmountInsufficient(amountNeeded: MilliSatoshi) extends Exception(s"Paying this offer requires at least $amountNeeded") with Failure

  case class InvalidSignature(signature: ByteVector64) extends Exception(s"Invalid signature: ${signature.toHex}") with Failure

  case class Processing(uuid: UUID) extends Result

  sealed trait Command

  case class PayOffer(replyTo: typed.ActorRef[Result],
                      offer: Offer,
                      amount: MilliSatoshi,
                      quantity: Long,
                      sendPaymentConfig: SendPaymentConfig) extends Command

  case class WrappedMessageResponse(response: OnionMessageResponse) extends Command

  case class WrappedPaymentId(paymentId: UUID) extends Command

  case class WrappedPreimageReceived(preimageReceived: PreimageReceived) extends Command

  case class WrappedPaymentSent(paymentSent: PaymentSent) extends Command

  case class WrappedPaymentFailed(paymentFailed: PaymentFailed) extends Command

  case class SendPaymentConfig(externalId_opt: Option[String],
                               maxAttempts: Int,
                               routeParams: RouteParams)

  def paymentResultWrapper(x: Any): Command = {
    x match {
      case paymentId: UUID => WrappedPaymentId(paymentId)
      case preimageReceived: PreimageReceived => WrappedPreimageReceived(preimageReceived)
      case paymentSent: PaymentSent => WrappedPaymentSent(paymentSent)
      case paymentFailed: PaymentFailed => WrappedPaymentFailed(paymentFailed)
    }
  }

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
            val uuid = UUID.randomUUID()
            val payerKey = randomKey()
            val request = InvoiceRequest(offer, amount, quantity, nodeParams.features.bolt12Features(), payerKey, nodeParams.chainHash)
            nodeParams.db.offers.addAttemptToPayOffer(offer, request, payerKey, uuid)
            replyTo ! Processing(uuid)
            sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, request, payerKey, uuid, nodeParams.onionMessageConfig.maxAttempts, sendPaymentConfig)
          }
      })
  }

  val rand = new Random

  def sendInvoiceRequest(nodeParams: NodeParams,
                         postman: typed.ActorRef[Postman.Command],
                         paymentInitiator: ActorRef,
                         context: ActorContext[Command],
                         request: InvoiceRequest,
                         payerKey: PrivateKey,
                         uuid: UUID,
                         remainingAttempts: Int,
                         sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    val destination = request.offer.contactInfo match {
      case Left(blindedRoutes) =>
        val blindedRoute = blindedRoutes(rand.nextInt(blindedRoutes.length))
        OnionMessages.BlindedPath(blindedRoute)
      case Right(nodeId) =>
        OnionMessages.Recipient(nodeId, None, None)
    }
    // TODO: random choice of intermediate nodes
    val intermediateNodes = Nil
    val messageContent = TlvStream[OnionMessagePayloadTlv](OnionMessagePayloadTlv.InvoiceRequest(request.records))
    postman ! SendMessage(intermediateNodes, destination, Some((nodeParams.nodeId +: intermediateNodes).reverse), messageContent, context.messageAdapter(WrappedMessageResponse), nodeParams.onionMessageConfig.timeout)
    waitForInvoice(nodeParams, postman, paymentInitiator, request, payerKey, uuid, remainingAttempts - 1, sendPaymentConfig)
  }

  def waitForInvoice(nodeParams: NodeParams,
                     postman: typed.ActorRef[Postman.Command],
                     paymentInitiator: ActorRef,
                     request: InvoiceRequest,
                     payerKey: PrivateKey,
                     uuid: UUID,
                     remainingAttempts: Int,
                     sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    Behaviors.setup(context =>
      Behaviors.receiveMessagePartial {
        case WrappedMessageResponse(Postman.Response(payload)) if payload.records.get[OnionMessagePayloadTlv.Invoice].nonEmpty =>
          Bolt12Invoice.validate(payload.records.get[OnionMessagePayloadTlv.Invoice].get.tlvs) match {
            case Right(invoice) if invoice.isValidFor(request) =>
              val recipientAmount = invoice.amount
              paymentInitiator ! SendPaymentToNode(context.messageAdapter(paymentResultWrapper).toClassic, recipientAmount, invoice, maxAttempts = sendPaymentConfig.maxAttempts, externalId = sendPaymentConfig.externalId_opt, routeParams = sendPaymentConfig.routeParams)
              waitForPaymentId(nodeParams, uuid, invoice)
            case Right(invoice) =>
              nodeParams.db.offers.addOfferInvoice(uuid, Some(invoice), None)
              Behaviors.stopped
            case Left(_) =>
              nodeParams.db.offers.addOfferInvoice(uuid, None, None)
              Behaviors.stopped
          }
        case WrappedMessageResponse(x) if remainingAttempts > 0 =>
          // We didn't get an invoice, let's retry.
          sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, request, payerKey, uuid, remainingAttempts, sendPaymentConfig)
        case WrappedMessageResponse(x) =>
          nodeParams.db.offers.addOfferInvoice(uuid, None, None)
          Behaviors.stopped
      })
  }

  def waitForPaymentId(nodeParams: NodeParams,
                       uuid: UUID,
                       invoice: Bolt12Invoice): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPaymentId(paymentId) =>
        nodeParams.db.offers.addOfferInvoice(uuid, Some(invoice), Some(paymentId))
        Behaviors.stopped
    }
  }
}


