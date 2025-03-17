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
import fr.acinq.eclair.EncodedNodeId.ShortChannelIdDir
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.db.{IncomingBlindedPayment, IncomingPaymentStatus, PaymentType}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.offer.OfferPaymentMetadata.MinimalInvoiceData
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceivingRoute}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.{Bolt12Invoice, MinimalBolt12Invoice}
import fr.acinq.eclair.router.BlindedRouteCreation.aggregatePaymentInfo
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, InvoiceTlv, Offer}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Logs, MilliSatoshi, NodeParams, TimestampMilli, TimestampSecond, nodeFee, randomBytes32}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

/**
 * Created by thomash-acinq on 13/01/2023.
 */

object OfferManager {
  sealed trait Command

  /**
   * Register an offer so that we can respond to invoice requests for it using the handler provided.
   *
   * @param offer       The offer to register.
   * @param nodeKey_opt If the offer has a node id, this must be the associated private key.
   * @param pathId_opt  If the offer uses a blinded path, the path id of this blinded path.
   * @param handler     An actor that will be in charge of accepting or rejecting invoice requests and payments for this offer.
   */
  case class RegisterOffer(offer: Offer, nodeKey_opt: Option[PrivateKey], pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand]) extends Command {
    require(offer.nodeId.isEmpty || nodeKey_opt.nonEmpty, "offers including the node_id field must be registered with the corresponding private key")
    require(!offer.contactInfos.exists(_.isInstanceOf[OfferTypes.BlindedPath]) || pathId_opt.nonEmpty, "offers including a blinded path must be registered with the corresponding path_id")
  }

  /**
   * Forget about an offer. Invoice requests and payment attempts for this offer will be ignored.
   *
   * @param offer The offer to forget.
   */
  case class DisableOffer(offer: Offer) extends Command

  case class RequestInvoice(messagePayload: MessageOnion.InvoiceRequestPayload, blindedKey: PrivateKey, postman: ActorRef[Postman.SendMessage]) extends Command

  case class ReceivePayment(replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command], paymentHash: ByteVector32, payload: FinalPayload.Blinded, amountReceived: MilliSatoshi) extends Command

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
   * @param replyTo     The handler must reply with either `PaymentActor.ApprovePayment` or `PaymentActor.RejectPayment`.
   * @param offer       The offer in case a single handler handles multiple offers.
   * @param invoiceData Data from the invoice this payment is for (quantity, amount, creation time, etc.).
   */
  case class HandlePayment(replyTo: ActorRef[PaymentActor.Command], offer: Offer, invoiceData: MinimalInvoiceData) extends HandlerCommand

  /**
   * An active offer, for which we handle invoice requests.
   * See [[RegisterOffer]] for more details about the fields.
   */
  private case class RegisteredOffer(offer: Offer, nodeKey_opt: Option[PrivateKey], pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand])

  def apply(nodeParams: NodeParams, paymentTimeout: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT))) {
        new OfferManager(nodeParams, paymentTimeout, context).normal(Map.empty)
      }
    }
  }

  private class OfferManager(nodeParams: NodeParams, paymentTimeout: FiniteDuration, context: ActorContext[Command]) {
    def normal(registeredOffers: Map[ByteVector32, RegisteredOffer]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case RegisterOffer(offer, nodeKey, pathId_opt, handler) =>
          normal(registeredOffers + (offer.offerId -> RegisteredOffer(offer, nodeKey, pathId_opt, handler)))
        case DisableOffer(offer) =>
          nodeParams.db.offers.disableOffer(offer)
          normal(registeredOffers - offer.offerId)
        case RequestInvoice(messagePayload, blindedKey, postman) =>
          registeredOffers.get(messagePayload.invoiceRequest.offer.offerId) match {
            case Some(registered) if registered.pathId_opt.map(_.bytes) == messagePayload.pathId_opt && messagePayload.invoiceRequest.isValid =>
              context.log.debug("received valid invoice request for offerId={}", messagePayload.invoiceRequest.offer.offerId)
              val child = context.spawnAnonymous(InvoiceRequestActor(nodeParams, messagePayload.invoiceRequest, registered.handler, registered.nodeKey_opt.getOrElse(blindedKey), messagePayload.replyPath, postman))
              child ! InvoiceRequestActor.RequestInvoice
            case _ => context.log.debug("offer {} is not registered or invoice request is invalid", messagePayload.invoiceRequest.offer.offerId)
          }
          Behaviors.same
        case ReceivePayment(replyTo, paymentHash, payload, amountReceived) =>
          MinimalInvoiceData.decode(payload.pathId) match {
            case Some(signed) =>
              registeredOffers.get(signed.offerId) match {
                case Some(RegisteredOffer(offer, _, _, handler)) =>
                  MinimalInvoiceData.verify(nodeParams.nodeId, signed) match {
                    case Some(metadata) if Crypto.sha256(metadata.preimage) == paymentHash =>
                      val child = context.spawnAnonymous(PaymentActor(nodeParams, replyTo, offer, metadata, amountReceived, paymentTimeout))
                      handler ! HandlePayment(child, offer, metadata)
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
                              routes: Seq[Route],
                              pluginData_opt: Option[ByteVector] = None,
                              additionalTlvs: Set[InvoiceTlv] = Set.empty,
                              customTlvs: Set[GenericTlv] = Set.empty) extends Command

    /**
     * Route used in payment blinded paths: [[feeOverride_opt]] and [[cltvOverride_opt]] allow hiding the routing
     * parameters of the route's intermediate hops, which provides better privacy.
     *
     * @param feeOverride_opt       fees that will be published for this route, the difference between these and the
     *                              actual fees of the route will be paid by the recipient.
     * @param cltvOverride_opt      cltv_expiry_delta to publish for the route, which must be greater than the route's
     *                              real cltv_expiry_delta.
     * @param shortChannelIdDir_opt short channel id and direction to use for the first node instead of its node id.
     */
    case class Route(hops: Seq[Router.ChannelHop], maxFinalExpiryDelta: CltvExpiryDelta, feeOverride_opt: Option[RelayFees] = None, cltvOverride_opt: Option[CltvExpiryDelta] = None, shortChannelIdDir_opt: Option[ShortChannelIdDir] = None) {
      def finalize(nodePriv: PrivateKey, preimage: ByteVector32, amount: MilliSatoshi, invoiceRequest: InvoiceRequest, minFinalExpiryDelta: CltvExpiryDelta, pluginData_opt: Option[ByteVector]): ReceivingRoute = {
        val aggregatedPaymentInfo = aggregatePaymentInfo(amount, hops, minFinalExpiryDelta)
        val fees = feeOverride_opt.getOrElse(RelayFees(aggregatedPaymentInfo.feeBase, aggregatedPaymentInfo.feeProportionalMillionths))
        val cltvExpiryDelta = cltvOverride_opt.getOrElse(aggregatedPaymentInfo.cltvExpiryDelta)
        val paymentInfo = aggregatedPaymentInfo.copy(feeBase = fees.feeBase, feeProportionalMillionths = fees.feeProportionalMillionths, cltvExpiryDelta = cltvExpiryDelta)
        val recipientFees = RelayFees(aggregatedPaymentInfo.feeBase - paymentInfo.feeBase, aggregatedPaymentInfo.feeProportionalMillionths - paymentInfo.feeProportionalMillionths)
        val metadata = MinimalInvoiceData(preimage, invoiceRequest.payerId, TimestampSecond.now(), invoiceRequest.quantity, amount, recipientFees, pluginData_opt)
        val pathId = MinimalInvoiceData.encode(nodePriv, invoiceRequest.offer.offerId, metadata)
        ReceivingRoute(hops, pathId, maxFinalExpiryDelta, paymentInfo, shortChannelIdDir_opt)
      }
    }

    /**
     * Sent by the offer handler to reject the request. For instance because stock has been exhausted.
     */
    case class RejectRequest(message: String) extends Command

    private case class WrappedInvoiceResponse(invoice: Bolt12Invoice) extends Command

    private case class WrappedOnionMessageResponse(response: Postman.OnionMessageResponse) extends Command

    def apply(nodeParams: NodeParams,
              invoiceRequest: InvoiceRequest,
              offerHandler: ActorRef[HandleInvoiceRequest],
              nodeKey: PrivateKey,
              pathToSender: RouteBlinding.BlindedRoute,
              postman: ActorRef[Postman.SendMessage]): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT))) {
          Behaviors.receiveMessagePartial {
            case RequestInvoice =>
              offerHandler ! HandleInvoiceRequest(context.self, invoiceRequest)
              new InvoiceRequestActor(nodeParams, invoiceRequest, nodeKey, pathToSender, postman, context).waitForHandler()
          }
        }
      }
    }

    private class InvoiceRequestActor(nodeParams: NodeParams,
                                      invoiceRequest: InvoiceRequest,
                                      nodeKey: PrivateKey,
                                      pathToSender: RouteBlinding.BlindedRoute,
                                      postman: ActorRef[Postman.SendMessage],
                                      context: ActorContext[Command]) {
      def waitForHandler(): Behavior[Command] = {
        Behaviors.receiveMessagePartial {
          case RejectRequest(error) =>
            context.log.debug("offer handler rejected invoice request: {}", error)
            postman ! Postman.SendMessage(OfferTypes.BlindedPath(pathToSender), OnionMessages.RoutingStrategy.FindRoute, TlvStream(OnionMessagePayloadTlv.InvoiceError(TlvStream(OfferTypes.Error(error)))), expectsReply = false, context.messageAdapter[Postman.OnionMessageResponse](WrappedOnionMessageResponse))
            waitForSent()
          case ApproveRequest(amount, routes, pluginData_opt, additionalTlvs, customTlvs) =>
            val preimage = randomBytes32()
            val receivingRoutes = routes.map(_.finalize(nodeParams.privateKey, preimage, amount, invoiceRequest, nodeParams.channelConf.minFinalExpiryDelta, pluginData_opt))
            val receivePayment = MultiPartHandler.ReceiveOfferPayment(context.messageAdapter[Bolt12Invoice](WrappedInvoiceResponse), nodeKey, invoiceRequest, receivingRoutes, preimage, additionalTlvs, customTlvs)
            val child = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
            child ! CreateInvoiceActor.CreateBolt12Invoice(receivePayment)
            waitForInvoice()
        }
      }

      private def waitForInvoice(): Behavior[Command] = {
        Behaviors.receiveMessagePartial {
          case WrappedInvoiceResponse(invoice) =>
            context.log.debug("invoice created for offerId={} invoice={}", invoice.invoiceRequest.offer.offerId, invoice.toString)
            postman ! Postman.SendMessage(OfferTypes.BlindedPath(pathToSender), OnionMessages.RoutingStrategy.FindRoute, TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), expectsReply = false, context.messageAdapter[Postman.OnionMessageResponse](WrappedOnionMessageResponse))
            waitForSent()
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
     * Sent by the offer handler. Causes the creation of a dummy invoice that matches as best as possible the actual
     * invoice for this payment (since the actual invoice is not stored) and will be used in the payment handler.
     *
     * @param additionalTlvs additional TLVs to add to the dummy invoice. Should be the same as what was used for the actual invoice.
     * @param customTlvs     custom TLVs to add to the dummy invoice. Should be the same as what was used for the actual invoice.
     */
    case class AcceptPayment(additionalTlvs: Set[InvoiceTlv] = Set.empty, customTlvs: Set[GenericTlv] = Set.empty) extends Command

    /**
     * Sent by the offer handler to reject the payment. For instance because stock has been exhausted.
     */
    case class RejectPayment(reason: String) extends Command

    def apply(nodeParams: NodeParams,
              replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command],
              offer: Offer,
              metadata: MinimalInvoiceData,
              amount: MilliSatoshi,
              timeout: FiniteDuration): Behavior[Command] = {
      Behaviors.setup { context =>
        context.scheduleOnce(timeout, context.self, RejectPayment("plugin timeout"))
        Behaviors.receiveMessage {
          case AcceptPayment(additionalTlvs, customTlvs) =>
            val minimalInvoice = MinimalBolt12Invoice(offer, nodeParams.chainHash, metadata.amount, metadata.quantity, Crypto.sha256(metadata.preimage), metadata.payerKey, metadata.createdAt, additionalTlvs, customTlvs)
            val incomingPayment = IncomingBlindedPayment(minimalInvoice, metadata.preimage, PaymentType.Blinded, TimestampMilli.now(), IncomingPaymentStatus.Pending)
            // We may be deducing some of the blinded path fees from the received amount.
            val maxRecipientPathFees = nodeFee(metadata.recipientPathFees, amount)
            replyTo ! MultiPartHandler.GetIncomingPaymentActor.ProcessPayment(incomingPayment, maxRecipientPathFees)
            Behaviors.stopped
          case RejectPayment(reason) =>
            replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(reason)
            Behaviors.stopped
        }
      }
    }
  }
}
