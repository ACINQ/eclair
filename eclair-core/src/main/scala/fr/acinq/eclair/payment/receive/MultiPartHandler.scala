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
import akka.actor.{ActorContext, ActorRef, PoisonPill, Status}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import akka.pattern.ask
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, Crypto}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, RES_SUCCESS}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, FeatureSupport, Features, InvoiceFeature, Logs, MilliSatoshi, MilliSatoshiLong, NodeParams, ShortChannelId, TimestampMilli, randomBytes32, randomKey}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/**
 * Simple payment handler that generates invoices and fulfills incoming htlcs.
 *
 * Created by PM on 17/06/2016.
 */
class MultiPartHandler(nodeParams: NodeParams, register: ActorRef, db: IncomingPaymentsDb) extends ReceiveHandler {

  import MultiPartHandler._

  // NB: this is safe because this handler will be called from within an actor
  private var pendingPayments: Map[ByteVector32, (ByteVector32, ActorRef)] = Map.empty

  private def addHtlcPart(ctx: ActorContext, add: UpdateAddHtlc, payload: FinalPayload, preimage: ByteVector32): Unit = {
    pendingPayments.get(add.paymentHash) match {
      case Some((_, handler)) =>
        handler ! MultiPartPaymentFSM.HtlcPart(payload.totalAmount, add)
      case None =>
        val handler = ctx.actorOf(MultiPartPaymentFSM.props(nodeParams, add.paymentHash, payload.totalAmount, ctx.self))
        handler ! MultiPartPaymentFSM.HtlcPart(payload.totalAmount, add)
        pendingPayments = pendingPayments + (add.paymentHash -> (preimage, handler))
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
    case receivePayment: ReceivePayment =>
      val child = ctx.spawnAnonymous(CreateInvoiceActor(nodeParams))
      child ! CreateInvoiceActor.CreateInvoice(ctx.sender(), receivePayment)

    case p: IncomingPaymentPacket.FinalPacket if doHandle(p.add.paymentHash) =>
      val child = ctx.spawnAnonymous(GetIncomingPaymentActor(nodeParams, p))
      child ! GetIncomingPaymentActor.GetIncomingPayment(ctx.self)

    case ProcessPacket(add, payload, payment_opt) if doHandle(add.paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(add.paymentHash))) {
        payment_opt match {
          case Some(payment) => validateStandardPayment(nodeParams, add, payload, payment) match {
            case Some(cmdFail) =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, Tags.FailureType(cmdFail)).increment()
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
            case None =>
              // We log whether the sender included the payment metadata field.
              // We always set it in our invoices to test whether senders support it.
              // Once all incoming payments correctly set that field, we can make it mandatory.
              log.info("received payment for amount={} totalAmount={} paymentMetadata={}", add.amountMsat, payload.totalAmount, payload.paymentMetadata.map(_.toHex).getOrElse("none"))
              Metrics.PaymentHtlcReceived.withTag(Tags.PaymentMetadataIncluded, payload.paymentMetadata.nonEmpty).increment()
              payload.paymentMetadata.foreach(metadata => ctx.system.eventStream.publish(PaymentMetadataReceived(add.paymentHash, metadata)))
              addHtlcPart(ctx, add, payload, payment.paymentPreimage)
          }
          case None => payload.paymentPreimage match {
            case Some(paymentPreimage) if nodeParams.features.hasFeature(Features.KeySend) =>
              val amount = Some(payload.totalAmount)
              val paymentHash = Crypto.sha256(paymentPreimage)
              val desc = Left("Donation")
              val features = if (nodeParams.features.hasFeature(Features.BasicMultiPartPayment)) {
                Features[InvoiceFeature](Features.BasicMultiPartPayment -> FeatureSupport.Optional, Features.PaymentSecret -> FeatureSupport.Mandatory, Features.VariableLengthOnion -> FeatureSupport.Mandatory)
              } else {
                Features[InvoiceFeature](Features.PaymentSecret -> FeatureSupport.Mandatory, Features.VariableLengthOnion -> FeatureSupport.Mandatory)
              }
              // Insert a fake invoice and then restart the incoming payment handler
              val invoice = Bolt11Invoice(nodeParams.chainHash, amount, paymentHash, nodeParams.privateKey, desc, nodeParams.channelConf.minFinalExpiryDelta, paymentSecret = payload.paymentSecret, features = features)
              log.debug("generated fake invoice={} from amount={} (KeySend)", invoice.toString, amount)
              db.addIncomingPayment(invoice, paymentPreimage, PaymentType.KeySend)
              ctx.self ! ProcessPacket(add, payload, Some(IncomingStandardPayment(invoice, paymentPreimage, PaymentType.KeySend, TimestampMilli.now(), IncomingPaymentStatus.Pending)))
            case _ =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, "InvoiceNotFound").increment()
              val cmdFail = CMD_FAIL_HTLC(add.id, Right(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
              PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
          }
        }
      }

    case ProcessBlindedPacket(add, payload, payment) if doHandle(add.paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(add.paymentHash))) {
        validateBlindedPayment(nodeParams, add, payload, payment) match {
          case Some(cmdFail) =>
            Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, Tags.FailureType(cmdFail)).increment()
            PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)
          case None =>
            log.info("received payment for amount={} totalAmount={}", add.amountMsat, payload.totalAmount)
            addHtlcPart(ctx, add, payload, payment.paymentPreimage)
        }
      }

    case RejectPacket(add, failure) if doHandle(add.paymentHash) =>
      Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, failure.getClass.getSimpleName).increment()
      val cmdFail = CMD_FAIL_HTLC(add.id, Right(failure), commit = true)
      PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, add.channelId, cmdFail)

    case MultiPartPaymentFSM.MultiPartPaymentFailed(paymentHash, failure, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, failure.getClass.getSimpleName).increment()
        log.warning("payment with paidAmount={} failed ({})", parts.map(_.amount).sum, failure)
        pendingPayments.get(paymentHash).foreach { case (_, handler: ActorRef) => handler ! PoisonPill }
        parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(failure), commit = true))
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case s@MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.info("received complete payment for amount={}", parts.map(_.amount).sum)
        pendingPayments.get(paymentHash).foreach {
          case (preimage: ByteVector32, handler: ActorRef) =>
            handler ! PoisonPill
            ctx.self ! DoFulfill(preimage, s)
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case MultiPartPaymentFSM.ExtraPaymentReceived(paymentHash, p, failure) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        failure match {
          case Some(failure) => p match {
            case p: MultiPartPaymentFSM.HtlcPart => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(failure), commit = true))
          }
          case None => p match {
            // NB: this case shouldn't happen unless the sender violated the spec, so it's ok that we take a slightly more
            // expensive code path by fetching the preimage from DB.
            case p: MultiPartPaymentFSM.HtlcPart => db.getIncomingPayment(paymentHash).foreach(record => {
              val received = PaymentReceived(paymentHash, PaymentReceived.PartialPayment(p.amount, p.htlc.channelId) :: Nil)
              if (db.receiveIncomingPayment(paymentHash, p.amount, received.timestamp)) {
                PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, record.paymentPreimage, commit = true))
                ctx.system.eventStream.publish(received)
              } else {
                val cmdFail = CMD_FAIL_HTLC(p.htlc.id, Right(IncorrectOrUnknownPaymentDetails(received.amount, nodeParams.currentBlockHeight)), commit = true)
                PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, cmdFail)
              }
            })
          }
        }
      }

    case DoFulfill(preimage, MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts)) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.info("fulfilling payment for amount={}", parts.map(_.amount).sum)
        val received = PaymentReceived(paymentHash, parts.map {
          case p: MultiPartPaymentFSM.HtlcPart => PaymentReceived.PartialPayment(p.amount, p.htlc.channelId)
        })
        if (db.receiveIncomingPayment(paymentHash, received.amount, received.timestamp)) {
          parts.collect {
            case p: MultiPartPaymentFSM.HtlcPart => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, preimage, commit = true))
          }
          postFulfill(received)
          ctx.system.eventStream.publish(received)
        } else {
          parts.collect {
            case p: MultiPartPaymentFSM.HtlcPart =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, "InvoiceNotFound").increment()
              val cmdFail = CMD_FAIL_HTLC(p.htlc.id, Right(IncorrectOrUnknownPaymentDetails(received.amount, nodeParams.currentBlockHeight)), commit = true)
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
  case class ProcessPacket(add: UpdateAddHtlc, payload: FinalPayload.Standard, payment_opt: Option[IncomingStandardPayment])
  case class ProcessBlindedPacket(add: UpdateAddHtlc, payload: FinalPayload.Blinded, payment: IncomingBlindedPayment)
  case class RejectPacket(add: UpdateAddHtlc, failure: FailureMessage)
  case class DoFulfill(preimage: ByteVector32, success: MultiPartPaymentFSM.MultiPartPaymentSucceeded)

  case object GetPendingPayments
  case class PendingPayments(paymentHashes: Set[ByteVector32])
  // @formatter:on

  sealed trait ReceivePayment {
    def paymentPreimage_opt: Option[ByteVector32]
  }

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
  case class ReceiveStandardPayment(amount_opt: Option[MilliSatoshi],
                                    description: Either[String, ByteVector32],
                                    expirySeconds_opt: Option[Long] = None,
                                    extraHops: List[List[ExtraHop]] = Nil,
                                    fallbackAddress_opt: Option[String] = None,
                                    paymentPreimage_opt: Option[ByteVector32] = None,
                                    paymentType: String = PaymentType.Standard) extends ReceivePayment

  /**
   * Use this message to create a Bolt 12 invoice to receive a payment for a given offer.
   *
   * @param nodeKey             the key that will be used to sign the invoice, which may be different from our public nodeId.
   * @param offer               the offer this invoice corresponds to.
   * @param invoiceRequest      the request this invoice responds to.
   * @param paymentPreimage_opt payment preimage.
   */
  case class ReceiveOfferPayment(nodeKey: PrivateKey,
                                 offer: Offer,
                                 invoiceRequest: InvoiceRequest,
                                 routes: Seq[Seq[PublicKey]],
                                 router: ActorRef,
                                 paymentPreimage_opt: Option[ByteVector32] = None,
                                 paymentType: String = PaymentType.Blinded) extends ReceivePayment

  object CreateInvoiceActor {

    // @formatter:off
    sealed trait Command
    case class CreateInvoice(replyTo: ActorRef, receivePayment: ReceivePayment) extends Command
    // @formatter:on

    def apply(nodeParams: NodeParams): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.receiveMessage {
          case CreateInvoice(replyTo, receivePayment) =>
            Try {
              val paymentPreimage = receivePayment.paymentPreimage_opt.getOrElse(randomBytes32())
              val paymentHash = Crypto.sha256(paymentPreimage)
              val featuresTrampolineOpt = if (nodeParams.enableTrampolinePayment) {
                nodeParams.features.invoiceFeatures().add(Features.TrampolinePaymentPrototype, FeatureSupport.Optional)
              } else {
                nodeParams.features.invoiceFeatures()
              }
              receivePayment match {
                case r: ReceiveStandardPayment =>
                  val expirySeconds = r.expirySeconds_opt.getOrElse(nodeParams.invoiceExpiry.toSeconds)
                  val paymentMetadata = hex"2a"
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
                    features = featuresTrampolineOpt.remove(Features.RouteBlinding)
                  )
                  context.log.debug("generated invoice={} from amount={}", invoice.toString, r.amount_opt)
                  nodeParams.db.payments.addIncomingPayment(invoice, paymentPreimage, r.paymentType)
                  invoice
                case r: ReceiveOfferPayment =>
                  val amount = r.invoiceRequest.amount.orElse(r.offer.amount.map(_ * r.invoiceRequest.quantity)).get
                  val paths = r.routes.map(nodeIds => {
                    require(nodeIds.nonEmpty, "route can't be empty")
                    val pathId = randomBytes32()
                    val finalExpiryDelta = nodeParams.channelConf.minFinalExpiryDelta + 3
                    val finalConstraints = RouteBlindingEncryptedDataTlv.PaymentConstraints(finalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight), nodeParams.channelConf.htlcMinimum)
                    val zeroPaymentInfo = PaymentInfo(0 msat, 0, CltvExpiryDelta(0), nodeParams.channelConf.htlcMinimum, amount, Features.empty)
                    val finalPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
                      finalConstraints,
                      RouteBlindingEncryptedDataTlv.PathId(pathId)
                    )).require.bytes
                    val (paymentInfo, payloads) = if (nodeIds.length > 1) {
                      val timeout = 30 second
                      val dummyRecipient = BlindRecipient(RouteBlinding.BlindedRoute(nodeIds.last, randomKey().publicKey, Seq(RouteBlinding.BlindedNode(randomKey().publicKey, ByteVector.empty))), PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 0 msat, Features.empty), None)
                      val routeResponse = Await.result(r.router.ask(Router.FinalizeRoute(0 msat, Router.PredefinedNodeRoute(nodeIds), dummyRecipient))(timeout).mapTo[Router.RouteResponse], timeout)
                      val routeToBlind = routeResponse.routes.head
                      val totalCltvDelta = routeToBlind.clearHops.map(_.cltvExpiryDelta).fold(finalExpiryDelta)(_ + _)
                      routeToBlind.clearHops.foldRight((zeroPaymentInfo, Seq(finalPayload))) {
                        case (channel: ChannelHop, (payInfo, nextPayloads)) =>
                          val newFeeBase = MilliSatoshi((channel.params.relayFees.feeBase.toLong * 1_000_000 + payInfo.feeBase.toLong * (1_000_000 + channel.params.relayFees.feeProportionalMillionths) + 1_000_000 - 1) / 1_000_000)
                          val newFeeProp = ((payInfo.feeProportionalMillionths + channel.params.relayFees.feeProportionalMillionths) * 1_000_000 + payInfo.feeProportionalMillionths * channel.params.relayFees.feeProportionalMillionths + 1_000_000 - 1) / 1_000_000
                          // Because eclair (and others) lies about max HTLC, we remove 10% as a safety margin.
                          val channelMaxHtlc = channel.params.htlcMaximum_opt.map(_ * 0.9).getOrElse(amount)
                          val newPayInfo = PaymentInfo(newFeeBase, newFeeProp, payInfo.cltvExpiryDelta + channel.cltvExpiryDelta, payInfo.minHtlc.max(channel.params.htlcMinimum), payInfo.maxHtlc.min(channelMaxHtlc), payInfo.allowedFeatures)
                          val payload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
                            RouteBlindingEncryptedDataTlv.OutgoingChannelId(channel.shortChannelId),
                            RouteBlindingEncryptedDataTlv.PaymentRelay(channel.cltvExpiryDelta, channel.params.relayFees.feeProportionalMillionths, channel.params.relayFees.feeBase),
                            RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(nodeParams.currentBlockHeight) + totalCltvDelta, channel.params.htlcMinimum)
                          )).require.bytes
                          (newPayInfo, payload +: nextPayloads)
                      }
                    } else {
                      (zeroPaymentInfo, Seq(finalPayload))
                    }
                    val blindedRoute = Sphinx.RouteBlinding.create(randomKey(), nodeIds, payloads)
                    (blindedRoute, paymentInfo, pathId)
                  })
                  val invoiceFeatures = featuresTrampolineOpt.remove(Features.RouteBlinding).add(Features.RouteBlinding, FeatureSupport.Mandatory)
                  val invoice = Bolt12Invoice(r.offer, r.invoiceRequest, paymentPreimage, r.nodeKey, nodeParams.channelConf.minFinalExpiryDelta, invoiceFeatures, paths.map { case (blindedRoute, paymentInfo, _) => (blindedRoute.route, paymentInfo) })
                  context.log.debug("generated invoice={} for offerId={}", invoice.toString, r.offer.offerId)
                  nodeParams.db.payments.addIncomingBlindedPayment(invoice, paymentPreimage, paths.map { case (blindedRoute, _, pathId) => (blindedRoute.lastBlinding -> pathId.bytes) }.toMap, r.paymentType)
                  invoice
              }
            } match {
              case Success(invoice) => replyTo ! invoice
              case Failure(exception) => replyTo ! Status.Failure(exception)
            }
            Behaviors.stopped
        }
      }
    }
  }

  object GetIncomingPaymentActor {

    // @formatter:off
    sealed trait Command
    case class GetIncomingPayment(replyTo: ActorRef) extends Command
    // @formatter:on

    def apply(nodeParams: NodeParams, packet: IncomingPaymentPacket.FinalPacket): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.withMdc(Logs.mdc(category_opt = Some(LogCategory.PAYMENT), paymentHash_opt = Some(packet.add.paymentHash))) {
          Behaviors.receiveMessage {
            case GetIncomingPayment(replyTo) =>
              packet.payload match {
                case payload: FinalPayload.Standard =>
                  nodeParams.db.payments.getIncomingPayment(packet.add.paymentHash) match {
                    case Some(_: IncomingBlindedPayment) =>
                      context.log.info("rejecting non-blinded htlc #{} from channel {}: expected a blinded payment", packet.add.id, packet.add.channelId)
                      replyTo ! RejectPacket(packet.add, IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight))
                    case Some(payment: IncomingStandardPayment) => replyTo ! ProcessPacket(packet.add, payload, Some(payment))
                    case None => replyTo ! ProcessPacket(packet.add, payload, None)
                  }
                case payload: FinalPayload.Blinded =>
                  nodeParams.db.payments.getIncomingPayment(packet.add.paymentHash) match {
                    case Some(_: IncomingStandardPayment) =>
                      context.log.info("rejecting blinded htlc #{} from channel {}: expected a non-blinded payment", packet.add.id, packet.add.channelId)
                      replyTo ! RejectPacket(packet.add, IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight))
                    case Some(payment: IncomingBlindedPayment) => replyTo ! ProcessBlindedPacket(packet.add, payload, payment)
                    case None =>
                      context.log.info("rejecting blinded htlc #{} from channel {}: invoice not found", packet.add.id, packet.add.channelId)
                      replyTo ! RejectPacket(packet.add, IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight))
                  }
              }
              Behaviors.stopped
          }
        }
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

  private def validatePaymentCltv(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    val minExpiry = record.invoice.minFinalCltvExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)
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

  private def validatePathId(blinding_opt: Option[PublicKey], payload: FinalPayload.Blinded, record: IncomingBlindedPayment)(implicit log: LoggingAdapter): Boolean = {
    blinding_opt.flatMap(record.pathIds.get) match {
      case Some(pathId) if pathId == payload.pathId => true
      case Some(pathId) =>
        log.warning("received blinded payment with invalid pathId={} (expected {})", payload.pathId, pathId)
        false
      case None =>
        log.warning("received blinded payment with an invalid blinding={}", blinding_opt.map(_.toHex).getOrElse("none"))
        false
    }
  }

  private def validateCommon(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    val paymentAmountOk = record.invoice.amount_opt.forall(a => validatePaymentAmount(add, payload, a))
    val paymentCltvOk = validatePaymentCltv(nodeParams, add, payload, record)
    val paymentStatusOk = validatePaymentStatus(add, payload, record)
    val paymentFeaturesOk = validateInvoiceFeatures(add, payload, record.invoice)
    paymentAmountOk && paymentCltvOk && paymentStatusOk && paymentFeaturesOk
  }

  private def validateStandardPayment(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload.Standard, record: IncomingStandardPayment)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val cmdFail = CMD_FAIL_HTLC(add.id, Right(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
    val commonOk = validateCommon(nodeParams, add, payload, record)
    val secretOk = validatePaymentSecret(add, payload, record.invoice)
    if (commonOk && secretOk) None else Some(cmdFail)
  }

  private def validateBlindedPayment(nodeParams: NodeParams, add: UpdateAddHtlc, payload: FinalPayload.Blinded, record: IncomingBlindedPayment)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val cmdFail = CMD_FAIL_HTLC(add.id, Right(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
    val commonOk = validateCommon(nodeParams, add, payload, record)
    val secretOk = validatePathId(add.blinding_opt.orElse(payload.blinding_opt), payload, record)
    if (commonOk && secretOk) None else Some(cmdFail)
  }

}
