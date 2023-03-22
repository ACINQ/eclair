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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.db.{IncomingBlindedPayment, IncomingPaymentStatus, PaymentType}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.DummyBolt12Invoice
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceivingRoute}
import fr.acinq.eclair.wire.protocol.CommonCodecs.{bytes32, bytes64, millisatoshi, publicKey, timestampSecond, uint64overflow}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, InvoiceTlv, Offer}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.{GenericTlv, MessageOnion, OfferTypes, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{MilliSatoshi, NodeParams, TimestampMilli, TimestampSecond, randomBytes32}
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult}

import scala.concurrent.duration.DurationInt

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

  case class RequestInvoice(messagePayload: MessageOnion.FinalPayload, postman: ActorRef[Postman.SendMessage]) extends Command

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
   * @param replyTo    The handler must reply with either `PaymentActor.ApprovePayment` or `PaymentActor.RejectPayment`.
   * @param offerId    The id of the offer in case a single handler handles multiple offers.
   * @param pluginData Data provided by the handler when generating the invoice, for its own use.
   */
  case class HandlePayment(replyTo: ActorRef[PaymentActor.Command], offerId: ByteVector32, pluginData: ByteVector) extends HandlerCommand

  case class RegisteredOffer(offer: Offer, nodeKey: PrivateKey, pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand])

  case class PaymentMetadata(preimage: ByteVector32,
                             payerKey: PublicKey,
                             createdAt: TimestampSecond,
                             quantity: Long,
                             amount: MilliSatoshi,
                             pluginData: ByteVector)

  private val metadataCodec: Codec[PaymentMetadata] =
    (("preimage" | bytes32) ::
      ("payerKey" | publicKey) ::
      ("createdAt" | timestampSecond) ::
      ("quantity" | uint64overflow) ::
      ("amount" | millisatoshi) ::
      ("pluginData" | bytes)).as[PaymentMetadata]

  /**
   * Metadata that will be included in the blinded route so that we can recover it when the payment happens without
   * needing to store it in a database (which would be a DoS vector).
   * The data is signed so that it can't be forged by the payer, it is also encrypted as part of the blinding route so
   * the payer can't read it.
   *
   * It contains
   * - the offer id
   * - the preimage of the payment hash
   * - the payer key
   * - the creation time of the invoice
   * - the quantity of items bought
   * - the amount to pay
   * - optional data from the handler
   *
   * This data is what we need to recreate an invoice similar to the one that was actually sent to the payer. It will
   * not be exactly the same (notably the blinding route will be different) but it will contain what we need to fulfill
   * the payment HTLC.
   *
   * It takes 181 bytes plus what the handler adds.
   */
  case class SignedMetadata(signature: ByteVector64,
                            offerId: ByteVector32,
                            metadata: ByteVector) {
    def verify(publicKey: PublicKey): Option[PaymentMetadata] =
      if (Crypto.verifySignature(Crypto.sha256(offerId ++ metadata), signature, publicKey)) {
        metadataCodec.decode(metadata.bits) match {
          case Attempt.Successful(result) => Some(result.value)
          case Attempt.Failure(_) => None
        }
      } else {
        None
      }
  }

  object SignedMetadata {
    def apply(privateKey: PrivateKey, offerId: ByteVector32, metadata: PaymentMetadata): SignedMetadata = {
      val encodedMetadata = metadataCodec.encode(metadata).require.bytes
      val signature = Crypto.sign(Crypto.sha256(offerId ++ encodedMetadata), privateKey)
      SignedMetadata(signature, offerId, encodedMetadata)
    }
  }

  private val signedMetadataCodec: Codec[SignedMetadata] =
    (("signature" | bytes64) ::
      ("offerId" | bytes32) ::
      ("metadata" | bytes)).as[SignedMetadata]

  def apply(nodeParams: NodeParams, router: akka.actor.ActorRef): Behavior[Command] = normal(nodeParams, router, Map.empty[ByteVector32, RegisteredOffer])

  def normal(nodeParams: NodeParams, router: akka.actor.ActorRef, registeredOffers: Map[ByteVector32, RegisteredOffer]): Behavior[Command] = {
    Behaviors.setup(context => {
      Behaviors.receiveMessage {
        case RegisterOffer(offer, nodeKey, pathId_opt, handler) =>
          normal(nodeParams, router, registeredOffers + (offer.offerId -> RegisteredOffer(offer, nodeKey, pathId_opt, handler)))
        case DisableOffer(offer) =>
          normal(nodeParams, router, registeredOffers - offer.offerId)
        case RequestInvoice(messagePayload, postman) =>
          messagePayload.records.get[OnionMessagePayloadTlv.InvoiceRequest] match {
            case Some(request) => InvoiceRequest.validate(request.tlvs) match {
              case Left(_) => ()
              case Right(invoiceRequest) =>
                registeredOffers.get(invoiceRequest.offer.offerId) match {
                  case Some(registered) if registered.pathId_opt.map(_.bytes) == messagePayload.pathId_opt && invoiceRequest.isValid =>
                    messagePayload.replyPath_opt match {
                      case Some(replyPath) =>
                        val child = context.spawnAnonymous(InvoiceRequestActor(nodeParams, invoiceRequest, registered.handler, registered.nodeKey, router, OnionMessages.BlindedPath(replyPath), postman))
                        child ! InvoiceRequestActor.RequestInvoice
                      case None => ()
                    }
                  case _ => ()
                }
            }
            case None => ()
          }
          Behaviors.same
        case ReceivePayment(replyTo, paymentHash, payload) =>
          signedMetadataCodec.decode(payload.pathId.bits) match {
            case Attempt.Successful(DecodeResult(signed, _)) =>
              registeredOffers.get(signed.offerId) match {
                case Some(RegisteredOffer(offer, nodeKey, _, handler)) =>
                  signed.verify(nodeKey.publicKey) match {
                    case None => replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(s"Invalid signature for metadata for offer ${signed.offerId.toHex}")
                    case Some(metadata) if Crypto.sha256(metadata.preimage) == paymentHash =>
                      val child = context.spawnAnonymous(PaymentActor(nodeParams, replyTo, offer, metadata))
                      handler ! HandlePayment(child, signed.offerId, metadata.pluginData)
                    case _ => replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(s"Preimage does not match payment hash for offer ${signed.offerId.toHex}")
                  }
                case _ =>
                  replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(s"Unknown offer ${signed.offerId.toHex}")
              }
            case Attempt.Failure(_) =>
              replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment("Invalid metadata")
          }
          Behaviors.same
      }
    })
  }

  object InvoiceRequestActor {

    sealed trait Command

    object RequestInvoice extends Command

    /**
     * Sent by the offer handler. Causes an invoice to be created and sent to the requester.
     *
     * @param amount         Amount for the invoice (must be the same as the invoice request if it contained an amount).
     * @param routes         Routes to use for the payment.
     * @param pluginData     Some data for the handler by the handler. It will be sent to the handler when a payment is attempted.
     * @param additionalTlvs additional TLVs to add to the invoice.
     * @param customTlvs     custom TLVs to add to the invoice.
     */
    case class ApproveRequest(amount: MilliSatoshi,
                              routes: Seq[ReceivingRoute],
                              pluginData: ByteVector,
                              additionalTlvs: Set[InvoiceTlv] = Set.empty,
                              customTlvs: Set[GenericTlv] = Set.empty) extends Command

    /**
     * Sent by the offer handler to reject the request. For instance because stock has been exhausted.
     */
    object RejectRequest extends Command

    private case class WrappedInvoiceResponse(response: CreateInvoiceActor.Bolt12InvoiceResponse) extends Command

    private case class WrappedOnionMessageResponse(response: Postman.OnionMessageResponse) extends Command

    def apply(nodeParams: NodeParams,
              invoiceRequest: InvoiceRequest,
              offerHandler: ActorRef[HandleInvoiceRequest],
              nodeKey: PrivateKey,
              router: akka.actor.ActorRef,
              replyPath: OnionMessages.Destination,
              postman: ActorRef[Postman.SendMessage]): Behavior[Command] = {
      Behaviors.setup(context => {
        Behaviors.receiveMessagePartial {
          case RequestInvoice =>
            offerHandler ! HandleInvoiceRequest(context.self, invoiceRequest)
            waitForHandler(nodeParams, invoiceRequest, nodeKey, router, replyPath, postman)
        }
      })
    }

    private def waitForHandler(nodeParams: NodeParams,
                               invoiceRequest: InvoiceRequest,
                               nodeKey: PrivateKey,
                               router: akka.actor.ActorRef,
                               replyPath: OnionMessages.Destination,
                               postman: ActorRef[Postman.SendMessage]): Behavior[Command] = {
      Behaviors.setup(context => {
        Behaviors.receiveMessagePartial {
          case RejectRequest =>
            Behaviors.stopped
          case ApproveRequest(amount, routes, data, additionalTlvs, customTlvs) =>
            val preimage = randomBytes32()
            val metadata = PaymentMetadata(preimage, invoiceRequest.payerId, TimestampSecond.now(), invoiceRequest.quantity, amount, data)
            val signedMetadata = SignedMetadata(nodeKey, invoiceRequest.offer.offerId, metadata)
            val pathId = signedMetadataCodec.encode(signedMetadata).require.bytes
            val receivePayment = MultiPartHandler.ReceiveOfferPayment(context.messageAdapter[CreateInvoiceActor.Bolt12InvoiceResponse](WrappedInvoiceResponse), nodeKey, invoiceRequest, routes, router, preimage, pathId, additionalTlvs, customTlvs)
            val child = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
            child ! CreateInvoiceActor.CreateBolt12Invoice(receivePayment)
            waitForInvoice(replyPath, postman)
        }
      })
    }

    private def waitForInvoice(replyPath: OnionMessages.Destination,
                               postman: ActorRef[Postman.SendMessage]): Behavior[Command] = {
      Behaviors.setup(context => {
        Behaviors.receiveMessagePartial {
          case WrappedInvoiceResponse(CreateInvoiceActor.InvoiceCreated(invoice)) =>
            postman ! Postman.SendMessage(Nil, replyPath, None, TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), context.messageAdapter[Postman.OnionMessageResponse](WrappedOnionMessageResponse), 0 seconds)
            waitForSent()
          case WrappedInvoiceResponse(_) =>
            Behaviors.stopped
        }
      })
    }

    private def waitForSent(): Behavior[Command] = {
      Behaviors.receiveMessagePartial {
        case WrappedOnionMessageResponse(_) =>
          Behaviors.stopped
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
    case class AcceptPayment(additionalTlvs: Seq[InvoiceTlv] = Nil, customTlvs: Seq[GenericTlv] = Nil) extends Command

    /**
     * Sent by the offer handler to reject the payment. For instance because stock has been exhausted.
     */
    case class RejectPayment(reason: String) extends Command

    def apply(nodeParams: NodeParams, replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command], offer: Offer, metadata: PaymentMetadata): Behavior[Command] = {
      Behaviors.setup(context => {
        context.scheduleOnce(1 minute, context.self, RejectPayment("Timeout"))
        Behaviors.receiveMessage {
          case AcceptPayment(additionalTlvs, customTlvs) =>
            // This invoice is not the one we've sent (we don't store the real one as it would be a DoS vector) but it shares all the important bits with the real one.
            val dummyInvoice = DummyBolt12Invoice(TlvStream(offer.records.records ++ Seq[InvoiceTlv](
              OfferTypes.InvoiceRequestChain(nodeParams.chainHash),
              OfferTypes.InvoiceRequestQuantity(metadata.quantity),
              OfferTypes.InvoiceRequestPayerId(metadata.payerKey),
              OfferTypes.InvoiceCreatedAt(metadata.createdAt),
              OfferTypes.InvoiceRelativeExpiry(nodeParams.invoiceExpiry.toSeconds),
              OfferTypes.InvoicePaymentHash(Crypto.sha256(metadata.preimage)),
              OfferTypes.InvoiceAmount(metadata.amount),
              OfferTypes.InvoiceFeatures(nodeParams.features.bolt12Features().unscoped()),
              OfferTypes.InvoiceNodeId(offer.nodeId),
            ) ++ additionalTlvs, offer.records.unknown ++ customTlvs))
            val incomingPayment = IncomingBlindedPayment(dummyInvoice, metadata.preimage, PaymentType.Blinded, TimestampMilli.now(), IncomingPaymentStatus.Pending)
            replyTo ! MultiPartHandler.GetIncomingPaymentActor.ProcessPayment(incomingPayment)
            Behaviors.stopped
          case RejectPayment(reason) =>
            replyTo ! MultiPartHandler.GetIncomingPaymentActor.RejectPayment(reason)
            Behaviors.stopped
        }
      })
    }
  }
}
