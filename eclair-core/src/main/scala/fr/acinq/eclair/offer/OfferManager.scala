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

package fr.acinq.eclair.offer

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.db.{IncomingBlindedPayment, IncomingPaymentStatus, PaymentType}
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceivingRoute}
import fr.acinq.eclair.payment.{Bolt12Invoice, Invoice}
import fr.acinq.eclair.wire.protocol.CommonCodecs.{bytes32, bytes64, lengthPrefixedFeaturesCodec, millisatoshi, publicKey, timestampSecond, uint64overflow}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, InvoiceTlv, Offer}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.{GenericTlv, MessageOnion, OfferTypes, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{Feature, Features, MilliSatoshi, NodeParams, TimestampMilli, TimestampSecond, randomBytes32}
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, DecodeResult}

import scala.concurrent.duration.DurationInt
import scala.util.{Success, Try}

object OfferManager {
  // @formatter:off
  sealed trait Command
  case class RegisterOffer(offer: Offer, nodeKey: PrivateKey, pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand]) extends Command
  case class DisableOffer(offer: Offer) extends Command
  case class RequestInvoice(messagePayload: MessageOnion.FinalPayload, postman: ActorRef[Postman.SendMessage]) extends Command
  case class Payment(replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command], paymentHash: ByteVector32, payload: FinalPayload.Blinded) extends Command

  sealed trait HandlerCommand
  case class HandleInvoiceRequest(replyTo: ActorRef[InvoiceRequestActor.Command], invoiceRequest: InvoiceRequest) extends HandlerCommand
  case class HandlePayment(replyTo: ActorRef[PaymentActor.Command], offerId: ByteVector32, data: ByteVector) extends HandlerCommand
  // @formatter:on

  case class RegisteredOffer(offer: Offer, nodeKey: PrivateKey, pathId_opt: Option[ByteVector32], handler: ActorRef[HandlerCommand])

  case class PaymentMetadata(preimage: ByteVector32,
                             payerKey: PublicKey,
                             createdAt: TimestampSecond,
                             quantity: Long,
                             amount: MilliSatoshi,
                             features: Features[Feature],
                             extraData: ByteVector)

  private val metadataCodec: Codec[PaymentMetadata] =
    (("preimage" | bytes32) ::
      ("payerKey" | publicKey) ::
      ("createdAt" | timestampSecond) ::
      ("quantity" | uint64overflow) ::
      ("amount" | millisatoshi) ::
      ("features" | lengthPrefixedFeaturesCodec) ::
      ("extraData" | bytes)).as[PaymentMetadata]

  case class SignedMetadata(signature: ByteVector64,
                            offerId: ByteVector32,
                            metadata: ByteVector) {
    def verify(publicKey: PublicKey): Try[PaymentMetadata] = Try {
      require(Crypto.verifySignature(Crypto.sha256(offerId ++ metadata), signature, publicKey))
      metadataCodec.decode(metadata.bits).require.value
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
          Try {
            val Right(invoiceRequest) = InvoiceRequest.validate(messagePayload.records.get[OnionMessagePayloadTlv.InvoiceRequest].get.tlvs)
            val registered = registeredOffers(invoiceRequest.offer.offerId)
            if (registered.pathId_opt.map(_.bytes) == messagePayload.pathId_opt && invoiceRequest.isValid) {
              val replyPath = OnionMessages.BlindedPath(messagePayload.replyPath_opt.get)
              val child = context.spawnAnonymous(InvoiceRequestActor(nodeParams, invoiceRequest, registered.handler, registered.nodeKey, router, replyPath, postman))
              child ! InvoiceRequestActor.RequestInvoice
            }
          }
          Behaviors.same
        case Payment(replyTo, paymentHash, payload) =>
          signedMetadataCodec.decode(payload.pathId.bits) match {
            case Attempt.Successful(DecodeResult(signed, _)) =>
              registeredOffers.get(signed.offerId) match {
                case Some(RegisteredOffer(offer, nodeKey, _, handler)) =>
                  signed.verify(nodeKey.publicKey) match {
                    case Success(metadata) if Crypto.sha256(metadata.preimage) == paymentHash =>
                      val child = context.spawnAnonymous(PaymentActor(nodeParams, replyTo, offer, metadata))
                      handler ! HandlePayment(child, signed.offerId, metadata.extraData)
                    case _ => replyTo ! MultiPartHandler.GetIncomingPaymentActor.NoPayment
                  }
                case _ =>
                  replyTo ! MultiPartHandler.GetIncomingPaymentActor.NoPayment
              }
            case Attempt.Failure(_) =>
              replyTo ! MultiPartHandler.GetIncomingPaymentActor.NoPayment
          }
          Behaviors.same
      }
    })
  }

  object InvoiceRequestActor {

    sealed trait Command

    object RequestInvoice extends Command

    case class ApproveRequest(amount: MilliSatoshi,
                              routes: Seq[ReceivingRoute],
                              features: Features[Feature],
                              data: ByteVector,
                              additionalTlvs: Set[InvoiceTlv] = Set.empty,
                              customTlvs: Set[GenericTlv] = Set.empty) extends Command

    object RejectRequest extends Command

    private case class WrappedInvoice(invoice: Try[Invoice]) extends Command

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
          case ApproveRequest(amount, routes, features, data, additionalTlvs, customTlvs) =>
            val preimage = randomBytes32()
            val metadata = PaymentMetadata(preimage, invoiceRequest.payerId, TimestampSecond.now(), invoiceRequest.quantity, amount, features, data)
            val signedMetadata = SignedMetadata(nodeKey, invoiceRequest.offer.offerId, metadata)
            val pathId = signedMetadataCodec.encode(signedMetadata).require.bytes
            val receivePayment = MultiPartHandler.ReceiveOfferPayment(nodeKey, invoiceRequest, routes, router, Some(preimage), Some(pathId), additionalTlvs, customTlvs, storeInDb = false)
            val child = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
            child ! CreateInvoiceActor.CreateInvoice(context.messageAdapter[Try[Invoice]](WrappedInvoice), receivePayment)
            waitForInvoice(replyPath, postman)
        }
      })
    }

    private def waitForInvoice(replyPath: OnionMessages.Destination,
                               postman: ActorRef[Postman.SendMessage]): Behavior[Command] = {
      Behaviors.setup(context => {
        Behaviors.receiveMessagePartial {
          case WrappedInvoice(Success(invoice: Bolt12Invoice)) =>
            postman ! Postman.SendMessage(Nil, replyPath, None, TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), context.messageAdapter[Postman.OnionMessageResponse](WrappedOnionMessageResponse), 0 seconds)
            waitForSent()
          case WrappedInvoice(_) =>
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

    case class AcceptPayment(additionalTlvs: Seq[InvoiceTlv] = Nil, customTlvs: Seq[GenericTlv] = Nil) extends Command

    object RejectPayment extends Command

    def apply(nodeParams: NodeParams, replyTo: ActorRef[MultiPartHandler.GetIncomingPaymentActor.Command], offer: Offer, metadata: PaymentMetadata): Behavior[Command] = {
      Behaviors.receiveMessage {
        case AcceptPayment(additionalTlvs, customTlvs) =>
          // This invoice is not the one we've sent (we don't store the real one as it would be a DoS vector) but it shares all the important bit with the real one.
          val dummyInvoice = Bolt12Invoice(TlvStream(offer.records.records ++ Seq[InvoiceTlv](
            OfferTypes.InvoiceRequestMetadata(ByteVector.empty),
            OfferTypes.InvoiceRequestChain(nodeParams.chainHash),
            OfferTypes.InvoiceRequestQuantity(metadata.quantity),
            OfferTypes.InvoiceRequestPayerId(metadata.payerKey),
            OfferTypes.InvoicePaths(Nil),
            OfferTypes.InvoiceBlindedPay(Nil),
            OfferTypes.InvoiceCreatedAt(metadata.createdAt),
            OfferTypes.InvoiceRelativeExpiry(nodeParams.invoiceExpiry.toSeconds),
            OfferTypes.InvoicePaymentHash(Crypto.sha256(metadata.preimage)),
            OfferTypes.InvoiceAmount(metadata.amount),
            OfferTypes.InvoiceFeatures(metadata.features),
            OfferTypes.InvoiceNodeId(offer.nodeId),
            OfferTypes.Signature(ByteVector64.Zeroes)
          ) ++ additionalTlvs, offer.records.unknown ++ customTlvs))
          val incomingPayment = IncomingBlindedPayment(dummyInvoice, metadata.preimage, PaymentType.Blinded, None, TimestampMilli.now(), IncomingPaymentStatus.Pending)
          replyTo ! MultiPartHandler.GetIncomingPaymentActor.PaymentFound(incomingPayment)
          Behaviors.stopped
        case RejectPayment =>
          replyTo ! MultiPartHandler.GetIncomingPaymentActor.NoPayment
          Behaviors.stopped
      }
    }
  }
}
