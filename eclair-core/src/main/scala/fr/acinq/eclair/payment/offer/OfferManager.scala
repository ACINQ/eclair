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

package fr.acinq.eclair.payment.offer

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.db.{IncomingBlindedPayment, IncomingPaymentStatus, PaymentType}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.MinimalBolt12Invoice
import fr.acinq.eclair.payment.offer.OfferPaymentMetadata.MinimalInvoiceData
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceivingRoute}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, InvoiceTlv, Offer}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshi, NodeParams, TimestampMilli, TimestampSecond, randomBytes32}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

/**
 * Created by thomash-acinq on 13/01/2023.
 */

object OfferManager {
  sealed trait Command

  /**
   * Register an offer and its handler.
   *
   * @param offer      The offer.
   * @param nodeKey    The private key corresponding to the node id used in the offer.
   * @param pathId_opt If the offer uses a blinded path, the path id of this blinded path.
   * @param handler    An actor that will be in charge of accepting or rejecting invoice requests and payments for this offer.
   */
  case class RegisterOffer(offer: Offer, nodeKey: PrivateKey, pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand]) extends Command

  /**
   * Forget about an offer. Invoice requests and payment attempts for this offer will be ignored.
   *
   * @param offer The offer to forget.
   */
  case class DisableOffer(offer: Offer) extends Command

  case class RequestInvoice(messagePayload: MessageOnion.InvoiceRequestPayload, postman: ActorRef[Postman.SendMessage]) extends Command

  case class ReceivePayment(replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command], paymentHash: ByteVector32, payload: FinalPayload.Blinded) extends Command

  /**
   * Offer handlers must be implemented in separate plugins and respond to these two `HandlerCommand`.
   */
  sealed trait HandlerCommand

  /**
   * When an invoice request is received, a `HandleInvoiceRequest` is sent to the handler registered for this offer.
   *
   * @param replyTo        The handler must reply with either `InvoiceRequestActor.ApproveRequest` or `InvoiceRequestActor.RejectRequest`.
   * @param invoiceRequest The invoice request to accept or reject. It is guaranteed to be valid for the offer.
   */
  case class HandleInvoiceRequest(replyTo: ActorRef[InvoiceRequestActor.Command], invoiceRequest: InvoiceRequest) extends HandlerCommand

  /**
   * When a payment is received for an offer invoice, a `HandlePayment` is sent to the handler registered for this offer.
   * The handler may receive several `HandlePayment` for the same payment, usually because of multi-part payments.
   *
   * @param replyTo        The handler must reply with either `PaymentActor.ApprovePayment` or `PaymentActor.RejectPayment`.
   * @param offerId        The id of the offer in case a single handler handles multiple offers.
   * @param pluginData_opt If the plugin handler needs to associate data with a payment, it shouldn't store it to avoid
   *                       DoS and should instead use that field to include that data in the blinded path.
   */
  case class HandlePayment(replyTo: ActorRef[PaymentActor.Command], offerId: ByteVector32, pluginData_opt: Option[ByteVector] = None) extends HandlerCommand

  private case class RegisteredOffer(offer: Offer, nodeKey: PrivateKey, pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand])

  def apply(nodeParams: NodeParams, router: akka.actor.ActorRef, paymentTimeout: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT))) {
        new OfferManager(nodeParams, router, paymentTimeout, context).normal(Map.empty)
      }
    }
  }

  private class OfferManager(nodeParams: NodeParams, router: akka.actor.ActorRef, paymentTimeout: FiniteDuration, context: ActorContext[Command]) {
    def normal(registeredOffers: Map[ByteVector32, RegisteredOffer]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case RegisterOffer(offer, nodeKey, pathId_opt, handler) =>
          normal(registeredOffers + (offer.offerId -> RegisteredOffer(offer, nodeKey, pathId_opt, handler)))
        case DisableOffer(offer) =>
          normal(registeredOffers - offer.offerId)
        case RequestInvoice(messagePayload, postman) =>
          registeredOffers.get(messagePayload.invoiceRequest.offer.offerId) match {
            case Some(registered) if registered.pathId_opt.map(_.bytes) == messagePayload.pathId_opt && messagePayload.invoiceRequest.isValid =>
              val child = context.spawnAnonymous(InvoiceRequestActor(nodeParams, messagePayload.invoiceRequest, registered.handler, registered.nodeKey, router, OnionMessages.BlindedPath(messagePayload.replyPath), postman))
              child ! InvoiceRequestActor.RequestInvoice
            case _ => context.log.debug("offer {} is not registered or invoice request is invalid", messagePayload.invoiceRequest.offer.offerId)
          }
          Behaviors.same
        case ReceivePayment(replyTo, paymentHash, payload) =>
          MinimalInvoiceData.decode(payload.pathId) match {
            case Some(signed) =>
              registeredOffers.get(signed.offerId) match {
                case Some(RegisteredOffer(offer, nodeKey, _, handler)) =>
                  MinimalInvoiceData.verify(nodeKey.publicKey, signed) match {
                    case Some(metadata) if Crypto.sha256(metadata.preimage) == paymentHash =>
                      val child = context.spawnAnonymous(PaymentActor(nodeParams, replyTo, offer, metadata, paymentTimeout))
                      handler ! HandlePayment(child, signed.offerId, metadata.pluginData_opt)
                    case Some(_) => replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(s"preimage does not match payment hash for offer ${signed.offerId.toHex}")
                    case None => replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(s"invalid signature for metadata for offer ${signed.offerId.toHex}")
                  }
                case None => replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(s"unknown offer ${signed.offerId.toHex}")
              }
            case None => replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment("payment metadata could not be decoded")
          }
          Behaviors.same
      }
    }
  }

  object InvoiceRequestActor {

    sealed trait Command

    object RequestInvoice extends Command

    /**
     * Sent by the offer handler. Causes an invoice to be created and sent to the requester.
     *
     * @param amount         Amount for the invoice (must be the same as the invoice request if it contained an amount).
     * @param routes         Routes to use for the payment.
     * @param pluginData_opt Some data for the handler by the handler. It will be sent to the handler when a payment is attempted.
     * @param additionalTlvs additional TLVs to add to the invoice.
     * @param customTlvs     custom TLVs to add to the invoice.
     */
    case class ApproveRequest(amount: MilliSatoshi,
                              routes: Seq[ReceivingRoute],
                              pluginData_opt: Option[ByteVector] = None,
                              additionalTlvs: Set[InvoiceTlv] = Set.empty,
                              customTlvs: Set[GenericTlv] = Set.empty) extends Command

    /**
     * Sent by the offer handler to reject the request. For instance because stock has been exhausted.
     */
    case class RejectRequest(message: String) extends Command

    private case class WrappedInvoiceResponse(response: CreateInvoiceActor.Bolt12InvoiceResponse) extends Command

    private case class WrappedOnionMessageResponse(response: Postman.OnionMessageResponse) extends Command

    def apply(nodeParams: NodeParams,
              invoiceRequest: InvoiceRequest,
              offerHandler: ActorRef[HandleInvoiceRequest],
              nodeKey: PrivateKey,
              router: akka.actor.ActorRef,
              pathToSender: OnionMessages.Destination,
              postman: ActorRef[Postman.SendMessage]): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT))) {
          Behaviors.receiveMessagePartial {
            case RequestInvoice =>
              offerHandler ! HandleInvoiceRequest(context.self, invoiceRequest)
              new InvoiceRequestActor(nodeParams, invoiceRequest, nodeKey, router, pathToSender, postman, context).waitForHandler()
          }
        }
      }
    }

    private class InvoiceRequestActor(nodeParams: NodeParams,
                                      invoiceRequest: InvoiceRequest,
                                      nodeKey: PrivateKey,
                                      router: akka.actor.ActorRef,
                                      pathToSender: OnionMessages.Destination,
                                      postman: ActorRef[Postman.SendMessage],
                                      context: ActorContext[Command]) {
      def waitForHandler(): Behavior[Command] = {
        Behaviors.receiveMessagePartial {
          case RejectRequest(error) =>
            postman ! Postman.SendMessage(pathToSender, OnionMessages.RoutingStrategy.FindRoute, TlvStream(OnionMessagePayloadTlv.InvoiceError(TlvStream(OfferTypes.Error(error)))), expectsReply = false, context.messageAdapter[Postman.OnionMessageResponse](WrappedOnionMessageResponse))
            waitForSent()
          case ApproveRequest(amount, routes, pluginData_opt, additionalTlvs, customTlvs) =>
            val preimage = randomBytes32()
            val metadata = MinimalInvoiceData(preimage, invoiceRequest.payerId, TimestampSecond.now(), invoiceRequest.quantity, amount, pluginData_opt)
            val pathId = MinimalInvoiceData.encode(nodeKey, invoiceRequest.offer.offerId, metadata)
            val receivePayment = MultiPartHandler.ReceiveOfferPayment(context.messageAdapter[CreateInvoiceActor.Bolt12InvoiceResponse](WrappedInvoiceResponse), nodeKey, invoiceRequest, routes, router, preimage, pathId, additionalTlvs, customTlvs)
            val child = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
            child ! CreateInvoiceActor.CreateBolt12Invoice(receivePayment)
            waitForInvoice()
        }
      }

      private def waitForInvoice(): Behavior[Command] = {
        Behaviors.receiveMessagePartial {
          case WrappedInvoiceResponse(invoiceResponse) =>
            invoiceResponse match {
              case CreateInvoiceActor.InvoiceCreated(invoice) =>
                postman ! Postman.SendMessage(pathToSender, OnionMessages.RoutingStrategy.FindRoute, TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), expectsReply = false, context.messageAdapter[Postman.OnionMessageResponse](WrappedOnionMessageResponse))
                waitForSent()
              case f: CreateInvoiceActor.InvoiceCreationFailed =>
                context.log.debug("invoice creation failed: {}", f.message)
                Behaviors.stopped
            }
        }
      }

      private def waitForSent(): Behavior[Command] = {
        Behaviors.receiveMessagePartial {
          case WrappedOnionMessageResponse(_) =>
            Behaviors.stopped
        }
      }
    }
  }

  object PaymentActor {
    sealed trait Command

    /**
     * Sent by the offer handler. Causes the creation of a dummy invoice that matches as best as possible the actual invoice for this payment (since the actual invoice is not stored) and will be used in the payment handler.
     *
     * @param additionalTlvs additional TLVs to add to the dummy invoice. Should be the same as what was used for the actual invoice.
     * @param customTlvs     custom TLVs to add to the dummy invoice. Should be the same as what was used for the actual invoice.
     */
    case class AcceptPayment(additionalTlvs: Set[InvoiceTlv] = Set.empty, customTlvs: Set[GenericTlv] = Set.empty) extends Command

    /**
     * Sent by the offer handler to reject the payment. For instance because stock has been exhausted.
     */
    case class RejectPayment(reason: String) extends Command

    def apply(nodeParams: NodeParams, replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command], offer: Offer, metadata: MinimalInvoiceData, timeout: FiniteDuration): Behavior[Command] = {
      Behaviors.setup { context =>
        context.scheduleOnce(timeout, context.self, RejectPayment("plugin timeout"))
        Behaviors.receiveMessage {
          case AcceptPayment(additionalTlvs, customTlvs) =>
            val minimalInvoice = MinimalBolt12Invoice(offer, nodeParams.chainHash, metadata.amount, metadata.quantity, Crypto.sha256(metadata.preimage), metadata.payerKey, metadata.createdAt, additionalTlvs, customTlvs)
            val incomingPayment = IncomingBlindedPayment(minimalInvoice, metadata.preimage, PaymentType.Blinded, TimestampMilli.now(), IncomingPaymentStatus.Pending)
            replyTo ! MultiPartHandler.GetIncomingPaymentActor.ProcessPayment(incomingPayment)
            Behaviors.stopped
          case RejectPayment(reason) =>
            replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(reason)
            Behaviors.stopped
        }
      }
    }
  }
}
