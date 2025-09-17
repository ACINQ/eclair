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

package fr.acinq.eclair.payment.receive

import akka.actor.Actor.Receive
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorContextOps
import akka.actor.{ActorContext, ActorRef, PoisonPill, typed}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.EncodedNodeId.ShortChannelIdDir
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.offer.OfferManager
import fr.acinq.eclair.router.BlindedRouteCreation.createBlindedRouteFromHops
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, InvoiceTlv}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Bolt11Feature, CltvExpiryDelta, FeatureSupport, Features, Logs, MilliSatoshi, MilliSatoshiLong, NodeParams, TimestampMilli, randomBytes32}
import scodec.bits.{ByteVector, HexStringSyntax}

/**
 * Simple payment handler that generates invoices and fulfills incoming htlcs.
 *
 * Created by PM on 17/06/2016.
 */
class MultiPartHandler(nodeParams: NodeParams, register: ActorRef, db: IncomingPaymentsDb, offerManager: typed.ActorRef[OfferManager.ReceivePayment]) extends ReceiveHandler {

  import MultiPartHandler._

  // NB: this is safe because this handler will be called from within an actor
  private var pendingPayments: Map[ByteVector32, (IncomingPayment, ActorRef)] = Map.empty

  private def addHtlcPart(ctx: ActorContext, add: UpdateAddHtlc, payload: FinalPayload, payment: IncomingPayment, receivedAt: TimestampMilli): Unit = {
    pendingPayments.get(add.paymentHash) match {
      case Some((_, handler)) =>
        handler ! MultiPartPaymentFSM.HtlcPart(payload.totalAmount, add, receivedAt)
      case None =>
        val handler = ctx.actorOf(MultiPartPaymentFSM.props(nodeParams, add.paymentHash, payload.totalAmount, ctx.self))
        handler ! MultiPartPaymentFSM.HtlcPart(payload.totalAmount, add, receivedAt)
        pendingPayments = pendingPayments + (add.paymentHash -> (payment, handler))
    }
  }

  /**
   * Can be overridden for a more fine-grained control of whether or not to handle this payment hash.
   * If the call returns false, then the pattern matching will fail and the payload will be passed to other handlers.
   */
  def doHandle(paymentHash: ByteVector32): Boolean = true

  /** Can be overridden to do custom post-processing on successfully received payments. */
  def postFulfill(paymentReceived: PaymentReceived)(implicit log: LoggingAdapter): Unit = ()

  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case receivePayment: ReceiveStandardPayment =>
      val child = ctx.spawnAnonymous(CreateInvoiceActor(nodeParams))
      child ! CreateInvoiceActor.CreateBolt11Invoice(receivePayment)

    case receivePayment: ReceiveOfferPayment =>
      val child = ctx.spawnAnonymous(CreateInvoiceActor(nodeParams))
      child ! CreateInvoiceActor.CreateBolt12Invoice(receivePayment)

    case p: IncomingPaymentPacket.FinalPacket if doHandle(p.add.paymentHash) =>
      val child = ctx.spawnAnonymous(GetIncomingPaymentActor(nodeParams, p, offerManager))
      child ! GetIncomingPaymentActor.GetIncomingPayment(ctx.self)

    case ProcessPacket(add, payload, payment_opt, receivedAt) if doHandle(add.paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(add.paymentHash))) {
        payment_opt match {
          case Some(payment) => validateStandardPayment(nodeParams, add, payload, payment, receivedAt) match {
            case Some(cmdFail) =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, Tags.FailureType(cmdFail)).increment()
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
            case None =>
              // We log whether the sender included the payment metadata field.
              // We always set it in our invoices to test whether senders support it.
              // Once all incoming payments correctly set that field, we can make it mandatory.
              log.debug("received payment for amount={} totalAmount={} paymentMetadata={}", add.amountMsat, payload.totalAmount, payload.paymentMetadata.map(_.toHex).getOrElse("none"))
              Metrics.PaymentHtlcReceived.withTag(Tags.PaymentMetadataIncluded, payload.paymentMetadata.nonEmpty).increment()
              payload.paymentMetadata.foreach(metadata => ctx.system.eventStream.publish(PaymentMetadataReceived(add.paymentHash, metadata)))
              addHtlcPart(ctx, add, payload, payment, receivedAt)
          }
          case None => payload.paymentPreimage match {
            case Some(paymentPreimage) if nodeParams.features.hasFeature(Features.KeySend) =>
              val amount = Some(payload.totalAmount)
              val paymentHash = Crypto.sha256(paymentPreimage)
              val desc = Left("Donation")
              val features = if (nodeParams.features.hasFeature(Features.BasicMultiPartPayment)) {
                Features[Bolt11Feature](Features.BasicMultiPartPayment -> FeatureSupport.Optional, Features.PaymentSecret -> FeatureSupport.Mandatory, Features.VariableLengthOnion -> FeatureSupport.Mandatory)
              } else {
                Features[Bolt11Feature](Features.PaymentSecret -> FeatureSupport.Mandatory, Features.VariableLengthOnion -> FeatureSupport.Mandatory)
              }
              // Insert a fake invoice and then restart the incoming payment handler
              val invoice = Bolt11Invoice(nodeParams.chainHash, amount, paymentHash, nodeParams.privateKey, desc, nodeParams.channelConf.minFinalExpiryDelta, paymentSecret = payload.paymentSecret, features = features)
              log.debug("generated fake invoice={} from amount={} (KeySend)", invoice.toString, amount)
              db.addIncomingPayment(invoice, paymentPreimage, PaymentType.KeySend)
              ctx.self ! ProcessPacket(add, payload, Some(IncomingStandardPayment(invoice, paymentPreimage, PaymentType.KeySend, TimestampMilli.now(), IncomingPaymentStatus.Pending)), receivedAt)
            case _ =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, "InvoiceNotFound").increment()
              val attribution = FailureAttributionData(htlcReceivedAt = receivedAt, trampolineReceivedAt_opt = None)
              val cmdFail = CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), Some(attribution), commit = true)
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
          }
        }
      }

    case ProcessBlindedPacket(add, payload, payment, maxRecipientPathFees, receivedAt) if doHandle(add.paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(add.paymentHash))) {
        validateBlindedPayment(nodeParams, add, payload, payment, maxRecipientPathFees, receivedAt) match {
          case Some(cmdFail) =>
            Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, Tags.FailureType(cmdFail)).increment()
            PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
          case None =>
            val recipientPathFees = payload.amount - add.amountMsat
            log.debug("received payment for amount={} recipientPathFees={} totalAmount={}", add.amountMsat, recipientPathFees, payload.totalAmount)
            addHtlcPart(ctx, add, payload, payment, receivedAt)
            if (recipientPathFees > 0.msat) {
              // We've opted into deducing the blinded paths fees from the amount we receive for this payment.
              // We add an artificial payment part for those fees, otherwise we will never reach the total amount.
              pendingPayments.get(add.paymentHash).foreach(_._2 ! MultiPartPaymentFSM.RecipientBlindedPathFeePart(add.paymentHash, recipientPathFees, payload.totalAmount))
            }
        }
      }

    case RejectPacket(add, failure, receivedAt) if doHandle(add.paymentHash) =>
      Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, failure.getClass.getSimpleName).increment()
      val attribution = FailureAttributionData(htlcReceivedAt = receivedAt, trampolineReceivedAt_opt = None)
      val cmdFail = CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(failure), Some(attribution), commit = true)
      PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)

    case MultiPartPaymentFSM.MultiPartPaymentFailed(paymentHash, failure, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, failure.getClass.getSimpleName).increment()
        log.warning("payment with paidAmount={} failed ({})", parts.map(_.amount).sum, failure)
        pendingPayments.get(paymentHash).foreach { case (_, handler: ActorRef) => handler ! PoisonPill }
        parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart =>
            val attribution = FailureAttributionData(htlcReceivedAt = p.receivedAt, trampolineReceivedAt_opt = None)
            PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, FailureReason.LocalFailure(failure), Some(attribution), commit = true))
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case s@MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.info("received complete payment for amount={}", parts.map(_.amount).sum)
        pendingPayments.get(paymentHash).foreach {
          case (payment: IncomingPayment, handler: ActorRef) =>
            handler ! PoisonPill
            ctx.self ! DoFulfill(payment, s)
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case MultiPartPaymentFSM.ExtraPaymentReceived(paymentHash, p, failure) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        failure match {
          case Some(failure) => p match {
            case p: MultiPartPaymentFSM.HtlcPart =>
              val attribution = FailureAttributionData(htlcReceivedAt = p.receivedAt, trampolineReceivedAt_opt = None)
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, FailureReason.LocalFailure(failure), Some(attribution), commit = true))
            case _: MultiPartPaymentFSM.RecipientBlindedPathFeePart => ()
          }
          case None => p match {
            // NB: this case shouldn't happen unless the sender violated the spec, so it's ok that we take a slightly more
            // expensive code path by fetching the preimage from DB.
            case p: MultiPartPaymentFSM.HtlcPart => db.getIncomingPayment(paymentHash).foreach(record => {
              val received = PaymentReceived(paymentHash, PaymentReceived.PartialPayment(p.amount, p.htlc.channelId) :: Nil)
              if (db.receiveIncomingPayment(paymentHash, p.amount, received.timestamp)) {
                val attribution = FulfillAttributionData(htlcReceivedAt = p.receivedAt, trampolineReceivedAt_opt = None, downstreamAttribution_opt = None)
                PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, record.paymentPreimage, Some(attribution), commit = true))
                ctx.system.eventStream.publish(received)
              } else {
                val attribution = FailureAttributionData(htlcReceivedAt = p.receivedAt, trampolineReceivedAt_opt = None)
                val cmdFail = CMD_FAIL_HTLC(p.htlc.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(received.amount, nodeParams.currentBlockHeight)), Some(attribution), commit = true)
                PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, cmdFail)
              }
            })
            case _: MultiPartPaymentFSM.RecipientBlindedPathFeePart => ()
          }
        }
      }

    case DoFulfill(payment, MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts)) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.debug("fulfilling payment for amount={}", parts.map(_.amount).sum)
        val received = PaymentReceived(paymentHash, parts.flatMap {
          case p: MultiPartPaymentFSM.HtlcPart => Some(PaymentReceived.PartialPayment(p.amount, p.htlc.channelId))
          case _: MultiPartPaymentFSM.RecipientBlindedPathFeePart => None
        })
        val recordedInDb = payment match {
          // Incoming offer payments are not stored in the database until they have been paid.
          case IncomingBlindedPayment(invoice, preimage, paymentType, _, _) =>
            db.receiveIncomingOfferPayment(invoice, preimage, received.amount, received.timestamp, paymentType)
            true
          // Incoming standard payments are already stored and need to be marked as received.
          case _: IncomingStandardPayment =>
            db.receiveIncomingPayment(paymentHash, received.amount, received.timestamp)
        }
        if (recordedInDb) {
          parts.collect {
            case p: MultiPartPaymentFSM.HtlcPart =>
              val attribution = FulfillAttributionData(htlcReceivedAt = p.receivedAt, trampolineReceivedAt_opt = None, downstreamAttribution_opt = None)
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, payment.paymentPreimage, Some(attribution), commit = true))
          }
          postFulfill(received)
          ctx.system.eventStream.publish(received)
        } else {
          parts.collect {
            case p: MultiPartPaymentFSM.HtlcPart =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, "InvoiceNotFound").increment()
              val attribution = FailureAttributionData(htlcReceivedAt = p.receivedAt, trampolineReceivedAt_opt = None)
              val cmdFail = CMD_FAIL_HTLC(p.htlc.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(received.amount, nodeParams.currentBlockHeight)), Some(attribution), commit = true)
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, cmdFail)
          }
        }
      }

    case GetPendingPayments => ctx.sender() ! PendingPayments(pendingPayments.keySet)

    case _: RES_SUCCESS[_] => // ignoring responses from channels
  }

}

object MultiPartHandler {

  // @formatter:off
  private case class ProcessPacket(add: UpdateAddHtlc, payload: FinalPayload.Standard, payment_opt: Option[IncomingStandardPayment], receivedAt: TimestampMilli)
  private case class ProcessBlindedPacket(add: UpdateAddHtlc, payload: FinalPayload.Blinded, payment: IncomingBlindedPayment, maxRecipientPathFees: MilliSatoshi, receivedAt: TimestampMilli)
  private case class RejectPacket(add: UpdateAddHtlc, failure: FailureMessage, receivedAt: TimestampMilli)
  case class DoFulfill(payment: IncomingPayment, success: MultiPartPaymentFSM.MultiPartPaymentSucceeded)

  case object GetPendingPayments
  case class PendingPayments(paymentHashes: Set[ByteVector32])
  // @formatter:on

  sealed trait ReceivePayment

  /**
   * Use this message to create a Bolt 11 invoice to receive a payment.
   *
   * @param amount_opt          amount to receive in milli-satoshis.
   * @param description         payment description as string or SHA256 hash.
   * @param expirySeconds_opt   number of seconds before the invoice expires (relative to the invoice creation time).
   * @param extraHops           routing hints to help the payer.
   * @param fallbackAddress_opt fallback Bitcoin address.
   * @param paymentPreimage_opt payment preimage.
   */
  case class ReceiveStandardPayment(replyTo: typed.ActorRef[Bolt11Invoice],
                                    amount_opt: Option[MilliSatoshi],
                                    description: Either[String, ByteVector32],
                                    expirySeconds_opt: Option[Long] = None,
                                    extraHops: List[List[ExtraHop]] = Nil,
                                    fallbackAddress_opt: Option[String] = None,
                                    paymentPreimage_opt: Option[ByteVector32] = None,
                                    paymentType: String = PaymentType.Standard) extends ReceivePayment

  /**
   * A route that will be blinded and included in a Bolt 12 invoice.
   *
   * @param hops                hops to reach our node, or the empty sequence if we do not want to hide our node id.
   * @param pathId              path id for this route.
   * @param maxFinalExpiryDelta maximum expiry delta that senders can use: the route expiry will be computed based on this value.
   */
  case class ReceivingRoute(hops: Seq[Router.ChannelHop], pathId: ByteVector, maxFinalExpiryDelta: CltvExpiryDelta, paymentInfo: OfferTypes.PaymentInfo, shortChannelIdDir_opt: Option[ShortChannelIdDir] = None)

  /**
   * Use this message to create a Bolt 12 invoice to receive a payment for a given offer.
   *
   * @param nodeKey         the private key corresponding to the offer node id. It will be used to sign the invoice
   *                        and may be different from our public nodeId.
   * @param invoiceRequest  the request this invoice responds to.
   * @param routes          routes that must be blinded and provided in the invoice.
   * @param paymentPreimage payment preimage.
   */
  case class ReceiveOfferPayment(replyTo: typed.ActorRef[Bolt12Invoice],
                                 nodeKey: PrivateKey,
                                 invoiceRequest: InvoiceRequest,
                                 routes: Seq[ReceivingRoute],
                                 paymentPreimage: ByteVector32,
                                 additionalTlvs: Set[InvoiceTlv] = Set.empty,
                                 customTlvs: Set[GenericTlv] = Set.empty) extends ReceivePayment {
    val amount: MilliSatoshi = invoiceRequest.amount
  }

  object CreateInvoiceActor {

    // @formatter:off
    sealed trait Command
    case class CreateBolt11Invoice(receivePayment: ReceiveStandardPayment) extends Command
    case class CreateBolt12Invoice(receivePayment: ReceiveOfferPayment) extends Command
    // @formatter:on

    def apply(nodeParams: NodeParams): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.receiveMessagePartial {
          case CreateBolt11Invoice(r) =>
            val paymentPreimage = r.paymentPreimage_opt.getOrElse(randomBytes32())
            val paymentHash = Crypto.sha256(paymentPreimage)
            val expirySeconds = r.expirySeconds_opt.getOrElse(nodeParams.invoiceExpiry.toSeconds)
            val paymentMetadata = hex"2a"
            val featuresTrampolineOpt = if (nodeParams.enableTrampolinePayment) {
              nodeParams.features.bolt11Features().add(Features.TrampolinePaymentPrototype, FeatureSupport.Optional)
            } else {
              nodeParams.features.bolt11Features()
            }
            val invoice = Bolt11Invoice(
              nodeParams.chainHash,
              r.amount_opt,
              paymentHash,
              nodeParams.privateKey,
              r.description,
              nodeParams.channelConf.minFinalExpiryDelta,
              r.fallbackAddress_opt,
              expirySeconds = Some(expirySeconds),
              extraHops = r.extraHops,
              paymentMetadata = Some(paymentMetadata),
              features = featuresTrampolineOpt
            )
            context.log.debug("generated invoice={} from amount={}", invoice.toString, r.amount_opt)
            nodeParams.db.payments.addIncomingPayment(invoice, paymentPreimage, r.paymentType)
            r.replyTo ! invoice
            Behaviors.stopped
          case CreateBolt12Invoice(r) =>
            val paths = r.routes.map(route => {
              val blindedRoute = createBlindedRouteFromHops(route.hops, nodeParams.nodeId, route.pathId, nodeParams.channelConf.htlcMinimum, route.maxFinalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight))
              val contactInfo = route.shortChannelIdDir_opt match {
                case Some(shortChannelIdDir) => BlindedRoute(shortChannelIdDir, blindedRoute.route.firstPathKey, blindedRoute.route.blindedHops)
                case None => blindedRoute.route
              }
              PaymentBlindedRoute(contactInfo, route.paymentInfo)
            })
            val invoiceFeatures = nodeParams.features.bolt12Features()
            val invoice = Bolt12Invoice(r.invoiceRequest, r.paymentPreimage, r.nodeKey, nodeParams.invoiceExpiry, invoiceFeatures, paths, r.additionalTlvs, r.customTlvs)
            context.log.debug("generated invoice={} for offer={}", invoice.toString, r.invoiceRequest.offer.toString)
            r.replyTo ! invoice
            Behaviors.stopped
        }
      }
    }
  }

  object GetIncomingPaymentActor {

    // @formatter:off
    sealed trait Command
    case class GetIncomingPayment(replyTo: ActorRef) extends Command
    case class ProcessPayment(payment: IncomingBlindedPayment, maxRecipientPathFees: MilliSatoshi) extends Command
    case class RejectPayment(reason: String) extends Command
    // @formatter:on

    def apply(nodeParams: NodeParams, packet: IncomingPaymentPacket.FinalPacket, offerManager: typed.ActorRef[OfferManager.ReceivePayment]): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.withMdc(Logs.mdc(category_opt = Some(LogCategory.PAYMENT), paymentHash_opt = Some(packet.add.paymentHash))) {
          Behaviors.receiveMessagePartial {
            case GetIncomingPayment(replyTo) =>
              packet.payload match {
                case payload: FinalPayload.Standard =>
                  nodeParams.db.payments.getIncomingPayment(packet.add.paymentHash) match {
                    case Some(_: IncomingBlindedPayment) =>
                      context.log.info("rejecting non-blinded htlc #{} from channel {}: expected a blinded payment", packet.add.id, packet.add.channelId)
                      replyTo ! RejectPacket(packet.add, IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight), packet.receivedAt)
                    case Some(payment: IncomingStandardPayment) => replyTo ! ProcessPacket(packet.add, payload, Some(payment), packet.receivedAt)
                    case None => replyTo ! ProcessPacket(packet.add, payload, None, packet.receivedAt)
                  }
                  Behaviors.stopped
                case payload: FinalPayload.Blinded =>
                  offerManager ! OfferManager.ReceivePayment(context.self, packet.add.paymentHash, payload, packet.add.amountMsat)
                  waitForPayment(context, nodeParams, replyTo, packet.add, payload, packet.receivedAt)
              }
          }
        }
      }
    }

    private def waitForPayment(context: typed.scaladsl.ActorContext[Command], nodeParams: NodeParams, replyTo: ActorRef, add: UpdateAddHtlc, payload: FinalPayload.Blinded, packetReceivedAt: TimestampMilli): Behavior[Command] = {
      Behaviors.receiveMessagePartial {
        case ProcessPayment(payment, maxRecipientPathFees) =>
          replyTo ! ProcessBlindedPacket(add, payload, payment, maxRecipientPathFees, packetReceivedAt)
          Behaviors.stopped
        case RejectPayment(reason) =>
          context.log.info("rejecting blinded htlc #{} from channel {}: {}", add.id, add.channelId, reason)
          replyTo ! RejectPacket(add, IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight), packetReceivedAt)
          Behaviors.stopped
      }
    }
  }

  private def validatePaymentStatus(add: UpdateAddHtlc, payload: FinalPayload, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    if (record.status.isInstanceOf[IncomingPaymentStatus.Received]) {
      log.warning("ignoring incoming payment for which has already been paid")
      false
    } else if (record.invoice.isExpired()) {
      log.warning("received payment for expired amount={} totalAmount={}", add.amountMsat, payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePaymentAmount(add: UpdateAddHtlc, payload: FinalPayload, expectedAmount: MilliSatoshi)(implicit log: LoggingAdapter): Boolean = {
    // The total amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
    // it must not be greater than two times the requested amount.
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
    if (payload.totalAmount < expectedAmount) {
      log.warning("received payment with amount too small for amount={} totalAmount={}", add.amountMsat, payload.totalAmount)
      false
    } else if (payload.totalAmount > expectedAmount * 2) {
      log.warning("received payment with amount too large for amount={} totalAmount={}", add.amountMsat, payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePaymentCltv(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload)(implicit log: LoggingAdapter): Boolean = {
    val minExpiry = nodeParams.channelConf.minFinalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)
    if (add.cltvExpiry < minExpiry) {
      log.warning("received payment with expiry too small for amount={} totalAmount={}", add.amountMsat, payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validateInvoiceFeatures(add: UpdateAddHtlc, payload: FinalPayload, invoice: Invoice)(implicit log: LoggingAdapter): Boolean = {
    if (payload.amount < payload.totalAmount && !invoice.features.hasFeature(Features.BasicMultiPartPayment)) {
      log.warning("received multi-part payment but invoice doesn't support it for amount={} totalAmount={}", add.amountMsat, payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePaymentSecret(add: UpdateAddHtlc, payload: FinalPayload.Standard, invoice: Bolt11Invoice)(implicit log: LoggingAdapter): Boolean = {
    if (payload.amount < payload.totalAmount && invoice.paymentSecret != payload.paymentSecret) {
      log.warning("received multi-part payment with invalid secret={} for amount={} totalAmount={}", payload.paymentSecret, add.amountMsat, payload.totalAmount)
      false
    } else if (invoice.paymentSecret != payload.paymentSecret) {
      log.warning("received payment with invalid secret={} for amount={} totalAmount={}", payload.paymentSecret, add.amountMsat, payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validateCommon(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    val paymentAmountOk = record.invoice.amount_opt.forall(a => validatePaymentAmount(add, payload, a))
    val paymentCltvOk = validatePaymentCltv(nodeParams, add, payload)
    val paymentStatusOk = validatePaymentStatus(add, payload, record)
    val paymentFeaturesOk = validateInvoiceFeatures(add, payload, record.invoice)
    paymentAmountOk && paymentCltvOk && paymentStatusOk && paymentFeaturesOk
  }

  private def validateStandardPayment(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload.Standard, record: IncomingStandardPayment, receivedAt: TimestampMilli)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val attribution = FailureAttributionData(htlcReceivedAt = receivedAt, trampolineReceivedAt_opt = None)
    val cmdFail = CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), Some(attribution), commit = true)
    val commonOk = validateCommon(nodeParams, add, payload, record)
    val secretOk = validatePaymentSecret(add, payload, record.invoice)
    if (commonOk && secretOk) None else Some(cmdFail)
  }

  private def validateBlindedPayment(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload.Blinded, record: IncomingBlindedPayment, maxRecipientPathFees: MilliSatoshi, receivedAt: TimestampMilli)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val attribution = FailureAttributionData(htlcReceivedAt = receivedAt, trampolineReceivedAt_opt = None)
    val cmdFail = CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), Some(attribution), commit = true)
    val commonOk = validateCommon(nodeParams, add, payload, record)
    // The payer isn't aware of the blinded path fees if we decided to hide them. The HTLC amount will thus be smaller
    // than the onion amount, but should match when re-adding the blinded path fees.
    val pathFeesOk = payload.amount - add.amountMsat <= maxRecipientPathFees
    if (commonOk && pathFeesOk) None else Some(cmdFail)
  }

}
