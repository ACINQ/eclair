/*
 * Copyright 2022 ACINQ SAS
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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64}
import fr.acinq.eclair.message.OnionMessages.Recipient
import fr.acinq.eclair.message.Postman.{OnionMessageResponse, SendMessage, SendMessageToBoth}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentFailed, PaymentSent}
import fr.acinq.eclair.wire.protocol.Offers.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.ReplyPath
import fr.acinq.eclair.wire.protocol.{OnionMessage, OnionMessagePayloadTlv}
import fr.acinq.eclair.{Features, InvoiceFeature, MilliSatoshi, NodeParams, TimestampSecond, randomBytes32, randomKey}
import fr.acinq.eclair.router.Router.RouteParams

import java.util.UUID
import scala.util.Random

object OfferPayment {
  sealed trait Result
  case class Success(invoice: Bolt12Invoice, payerKey: PrivateKey, paymentPreimage: ByteVector32) extends Result
  case class UnsupportedFeatures(features: Features[InvoiceFeature]) extends Result
  case class UnsupportedChains(chains: Seq[ByteVector32]) extends Result
  case class UnsupportedCurrency(iso4217: String) extends Result
  case class ExpiredOffer(expiryDate: TimestampSecond) extends Result
  case class QuantityTooLow(quantityMin: Long) extends Result
  case class QuantityTooHigh(quantityMax: Long) extends Result
  case object IsSendInvoice extends Result
  case class AmountInsufficient(amountNeeded: MilliSatoshi) extends Result
  case class InvalidSignature(signature: ByteVector64) extends Result
  case class InvalidInvoice(invoice: Bolt12Invoice, request: InvoiceRequest, payerKey: PrivateKey) extends Result
  case object NoInvoice extends Result
  case class FailedPayment(invoice: Bolt12Invoice, payerKey: PrivateKey, paymentFailed: PaymentFailed) extends Result

  sealed trait Command
  case class PayOffer(replyTo: typed.ActorRef[Result]) extends Command
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

  def buildRequest(nodeParams: NodeParams, request: InvoiceRequest, destination: OnionMessages.Destination): (ByteVector32, PublicKey, OnionMessage) = {
    val pathId = randomBytes32()
    // TODO: randomize intermediate nodes
    val intermediateNodes = Seq.empty
    val replyPath = ReplyPath(OnionMessages.buildRoute(randomKey(), intermediateNodes.reverse, Recipient(nodeParams.nodeId, Some(pathId.bytes))))
    val (nextNodeId, message) = OnionMessages.buildMessage(randomKey(), randomKey(), intermediateNodes, destination, Seq(replyPath, OnionMessagePayloadTlv.InvoiceRequest(request)))
    (pathId, nextNodeId, message)
  }

  def apply(nodeParams: NodeParams,
            postman: typed.ActorRef[Postman.Command],
            paymentInitiator: ActorRef,
            offer: Offer,
            amount: MilliSatoshi,
            quantity: Long,
            sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, PayOffer(replyTo)) =>
        if (!nodeParams.features.invoiceFeatures().areSupported(offer.features)) {
          replyTo ! UnsupportedFeatures(offer.features)
          Behaviors.stopped
        } else if (!offer.chains.contains(nodeParams.chainHash)) {
          replyTo ! UnsupportedChains(offer.chains)
          Behaviors.stopped
        } else if (offer.currency.nonEmpty) {
          replyTo ! UnsupportedCurrency(offer.currency.get)
          Behaviors.stopped
        } else if (offer.expiry.exists(_ < TimestampSecond.now())) {
          replyTo ! ExpiredOffer(offer.expiry.get)
          Behaviors.stopped
        } else if (offer.quantityMin.exists(_ > quantity)) {
          replyTo ! QuantityTooLow(offer.quantityMin.get)
          Behaviors.stopped
        } else if (offer.quantityMax.getOrElse(1L) < quantity) {
          replyTo ! QuantityTooHigh(offer.quantityMax.getOrElse(1))
          Behaviors.stopped
        } else if (offer.sendInvoice) {
          replyTo ! IsSendInvoice
          Behaviors.stopped
        } else if (offer.amount.exists(_ * quantity > amount)) {
          replyTo ! AmountInsufficient(offer.amount.get * quantity)
          Behaviors.stopped
        } else if (offer.signature.nonEmpty && !offer.checkSignature()) {
          replyTo ! InvalidSignature(offer.signature.get)
          Behaviors.stopped
        } else {
          val payerKey = randomKey()
          val request = InvoiceRequest(offer, amount, quantity, nodeParams.features.invoiceFeatures(), payerKey, nodeParams.chainHash)
          sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, offer, request, payerKey, replyTo, nodeParams.onionMessageConfig.maxAttempts, sendPaymentConfig)
        }
    }
  }

  val rand = new Random

  def sendInvoiceRequest(nodeParams: NodeParams,
                         postman: typed.ActorRef[Postman.Command],
                         paymentInitiator: ActorRef,
                         context: ActorContext[Command],
                         offer: Offer,
                         request: InvoiceRequest,
                         payerKey: PrivateKey,
                         replyTo: typed.ActorRef[Result],
                         remainingAttempts: Int,
                         sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    offer.contact match {
            case Left(blindedRoutes) =>
              val blindedRoute = blindedRoutes(rand.nextInt(blindedRoutes.length))
              val (pathId, nextNodeId, message) = buildRequest(nodeParams, request, OnionMessages.BlindedPath(blindedRoute))
              postman ! SendMessage(nextNodeId, message, Some(pathId), context.messageAdapter(WrappedMessageResponse), nodeParams.onionMessageConfig.timeout)
              waitForInvoice(nodeParams, postman, paymentInitiator, offer, Map(pathId -> offer.nodeIdXOnly.nodeId1), request, payerKey, replyTo, remainingAttempts - 1, sendPaymentConfig)
            case Right(nodeIdXOnly) =>
              // The node id from the offer is missing the first byte so we try both the odd and even versions, we'll
              // pay the first one that answers (the other one should not exist but if it does, it is controlled by the
              // same person).
              val (pathId1, nextNodeId1, message1) = buildRequest(nodeParams, request, Recipient(nodeIdXOnly.nodeId1, None, None))
              val (pathId2, nextNodeId2, message2) = buildRequest(nodeParams, request, Recipient(nodeIdXOnly.nodeId2, None, None))
              postman ! SendMessageToBoth(nextNodeId1, message1, pathId1, nextNodeId2, message2, pathId2, context.messageAdapter(WrappedMessageResponse), nodeParams.onionMessageConfig.timeout)
              waitForInvoice(nodeParams, postman, paymentInitiator, offer, Map(pathId1 -> nodeIdXOnly.nodeId1, pathId2 -> nodeIdXOnly.nodeId2),request, payerKey, replyTo, remainingAttempts - 1, sendPaymentConfig)

    }
  }

  def waitForInvoice(nodeParams: NodeParams,
                     postman: typed.ActorRef[Postman.Command],
                     paymentInitiator: ActorRef,
                     offer: Offer,
                     nodeId: Map[ByteVector32, PublicKey],
                     request: InvoiceRequest,
                     payerKey: PrivateKey,
                     replyTo: typed.ActorRef[Result],
                     remainingAttempts: Int,
                     sendPaymentConfig: SendPaymentConfig): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, WrappedMessageResponse(Postman.Response(payload, pathId))) if payload.invoice.nonEmpty =>
        val invoice = payload.invoice.get.invoice.withNodeId(nodeId(pathId))
        if (invoice.isValidFor(offer, request)) {
          val recipientAmount = invoice.amount
          paymentInitiator ! SendPaymentToNode(context.messageAdapter(paymentResultWrapper).toClassic, recipientAmount, invoice, maxAttempts = sendPaymentConfig.maxAttempts, externalId = sendPaymentConfig.externalId_opt, routeParams = sendPaymentConfig.routeParams)
          waitForPayment(invoice, payerKey, replyTo)
        } else {
          replyTo ! InvalidInvoice(invoice, request, payerKey)
          Behaviors.stopped
        }
      case (context, WrappedMessageResponse(_)) if remainingAttempts > 0 =>
        // We didn't get an invoice, let's retry.
        sendInvoiceRequest(nodeParams, postman, paymentInitiator, context, offer, request, payerKey, replyTo, remainingAttempts, sendPaymentConfig)
      case (_, WrappedMessageResponse(_)) =>
        replyTo ! NoInvoice
        Behaviors.stopped
    }
  }

  def waitForPayment(invoice: Bolt12Invoice, payerKey: PrivateKey, replyTo: typed.ActorRef[Result]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPaymentId(_) =>
        Behaviors.same
      case WrappedPreimageReceived(preimageReceived) =>
        replyTo ! Success(invoice, payerKey, preimageReceived.paymentPreimage)
        Behaviors.stopped
      case WrappedPaymentSent(paymentSent) =>
        replyTo ! Success(invoice, payerKey, paymentSent.paymentPreimage)
        Behaviors.stopped
      case WrappedPaymentFailed(failed) =>
        replyTo ! FailedPayment(invoice, payerKey, failed)
        Behaviors.stopped
    }
  }
}


