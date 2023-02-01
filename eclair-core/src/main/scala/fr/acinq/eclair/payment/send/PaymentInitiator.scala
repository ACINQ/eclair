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

package fr.acinq.eclair.payment.send

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.PaymentType
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentError._
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, NodeParams, randomBytes32}

import java.util.UUID
import scala.util.{Failure, Success, Try}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, outgoingPaymentFactory: PaymentInitiator.MultiPartPaymentFactory) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = main(Map.empty)

  def main(pending: Map[UUID, PendingPayment]): Receive = {
    case r: SendPaymentToNode =>
      val replyTo = if (r.replyTo == ActorRef.noSender) sender() else r.replyTo
      val paymentId = UUID.randomUUID()
      if (!r.blockUntilComplete) {
        // Immediately return the paymentId
        replyTo ! paymentId
      }
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.invoice.nodeId, Upstream.Local(paymentId), Some(r.invoice), r.payerKey_opt, storeInDb = true, publishEvent = true, recordPathFindingMetrics = true)
      val finalExpiry = r.finalExpiry(nodeParams)
      val recipient = r.invoice match {
        case invoice: Bolt11Invoice => ClearRecipient(invoice, r.recipientAmount, finalExpiry, r.userCustomTlvs)
        case invoice: Bolt12Invoice => BlindedRecipient(invoice, r.recipientAmount, finalExpiry, r.userCustomTlvs)
      }
      if (!nodeParams.features.invoiceFeatures().areSupported(recipient.features)) {
        replyTo ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, UnsupportedFeatures(recipient.features)) :: Nil)
      } else if (Features.canUseFeature(nodeParams.features.invoiceFeatures(), recipient.features, Features.BasicMultiPartPayment)) {
        val fsm = outgoingPaymentFactory.spawnOutgoingMultiPartPayment(context, paymentCfg, publishPreimage = !r.blockUntilComplete)
        fsm ! MultiPartPaymentLifecycle.SendMultiPartPayment(self, recipient, r.maxAttempts, r.routeParams)
        context become main(pending + (paymentId -> PendingPaymentToNode(replyTo, r)))
      } else {
        val fsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
        fsm ! PaymentLifecycle.SendPaymentToNode(self, recipient, r.maxAttempts, r.routeParams)
        context become main(pending + (paymentId -> PendingPaymentToNode(replyTo, r)))
      }

    case r: SendSpontaneousPayment =>
      val paymentId = UUID.randomUUID()
      sender() ! paymentId
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.recipientNodeId, Upstream.Local(paymentId), None, None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = r.recordPathFindingMetrics)
      val finalExpiry = nodeParams.paymentFinalExpiry.computeFinalExpiry(nodeParams.currentBlockHeight, Channel.MIN_CLTV_EXPIRY_DELTA)
      val recipient = SpontaneousRecipient(r.recipientNodeId, r.recipientAmount, finalExpiry, r.paymentPreimage, r.userCustomTlvs)
      val fsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
      fsm ! PaymentLifecycle.SendPaymentToNode(self, recipient, r.maxAttempts, r.routeParams)
      context become main(pending + (paymentId -> PendingSpontaneousPayment(sender(), r)))

    case r: SendTrampolinePayment =>
      val paymentId = UUID.randomUUID()
      sender() ! paymentId
      r.trampolineAttempts match {
        case Nil =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, TrampolineFeesMissing) :: Nil)
        case _ if !r.invoice.features.hasFeature(Features.TrampolinePaymentPrototype) && r.invoice.amount_opt.isEmpty =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, TrampolineLegacyAmountLessInvoice) :: Nil)
        case (trampolineFees, trampolineExpiryDelta) :: remainingAttempts =>
          log.info(s"sending trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
          sendTrampolinePayment(paymentId, r, trampolineFees, trampolineExpiryDelta) match {
            case Success(_) =>
              context become main(pending + (paymentId -> PendingTrampolinePayment(sender(), remainingAttempts, r)))
            case Failure(t) =>
              log.warning("cannot send outgoing trampoline payment: {}", t.getMessage)
              sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, t) :: Nil)
          }
      }

    case r: SendPaymentToRoute =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      r.trampoline_opt match {
        case _ if !nodeParams.features.invoiceFeatures().areSupported(r.invoice.features) =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, UnsupportedFeatures(r.invoice.features)) :: Nil)
        case Some(trampolineAttempt) =>
          val trampolineNodeId = r.route.targetNodeId
          log.info(s"sending trampoline payment to ${r.recipientNodeId} with trampoline=$trampolineNodeId, trampoline fees=${trampolineAttempt.fees}, expiry delta=${trampolineAttempt.cltvExpiryDelta}")
          val trampolineHop = NodeHop(trampolineNodeId, r.recipientNodeId, trampolineAttempt.cltvExpiryDelta, trampolineAttempt.fees)
          buildTrampolineRecipient(r, trampolineHop) match {
            case Success(recipient) =>
              sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId, Some(recipient.trampolinePaymentSecret))
              val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientNodeId, Upstream.Local(paymentId), Some(r.invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = false)
              val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
              payFsm ! PaymentLifecycle.SendPaymentToRoute(self, Left(r.route), recipient)
              context become main(pending + (paymentId -> PendingPaymentToRoute(sender(), r)))
            case Failure(t) =>
              log.warning("cannot send outgoing trampoline payment: {}", t.getMessage)
              sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, t) :: Nil)
          }
        case None =>
          sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId, None)
          val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientNodeId, Upstream.Local(paymentId), Some(r.invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = false)
          val finalExpiry = r.finalExpiry(nodeParams)
          val recipient = r.invoice match {
            case invoice: Bolt11Invoice => ClearRecipient(invoice, r.recipientAmount, finalExpiry, Set.empty)
            case invoice: Bolt12Invoice => BlindedRecipient(invoice, r.recipientAmount, finalExpiry, Set.empty)
          }
          val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
          payFsm ! PaymentLifecycle.SendPaymentToRoute(self, Left(r.route), recipient)
          context become main(pending + (paymentId -> PendingPaymentToRoute(sender(), r)))
        case _ =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, TrampolineMultiNodeNotSupported) :: Nil)
      }

    case pf: PaymentFailed => pending.get(pf.id).foreach {
      case pp: PendingTrampolinePayment =>
        val trampolineHop = NodeHop(pp.r.trampolineNodeId, pp.r.recipientNodeId, pp.r.trampolineAttempts.last._2, pp.r.trampolineAttempts.last._1)
        val decryptedFailures = pf.failures.collect { case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(_, f)) => f }
        val shouldRetry = decryptedFailures.exists {
          case _: TrampolineFeeInsufficient => true
          case _: TrampolineExpiryTooSoon => true
          case _ => false
        }
        if (shouldRetry) {
          pp.remainingAttempts match {
            case (trampolineFees, trampolineExpiryDelta) :: remaining =>
              log.info(s"retrying trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
              sendTrampolinePayment(pf.id, pp.r, trampolineFees, trampolineExpiryDelta) match {
                case Success(_) =>
                  context become main(pending + (pf.id -> pp.copy(remainingAttempts = remaining)))
                case Failure(t) =>
                  log.warning("cannot send outgoing trampoline payment: {}", t.getMessage)
                  val localFailure = pf.copy(failures = Seq(LocalFailure(pp.r.recipientAmount, Seq(trampolineHop), t)))
                  pp.sender ! localFailure
                  context.system.eventStream.publish(localFailure)
                  context become main(pending - pf.id)
              }
            case Nil =>
              log.info("trampoline node couldn't find a route after all retries")
              val localFailure = pf.copy(failures = Seq(LocalFailure(pp.r.recipientAmount, Seq(trampolineHop), RouteNotFound)))
              pp.sender ! localFailure
              context.system.eventStream.publish(localFailure)
              context become main(pending - pf.id)
          }
        } else {
          pp.sender ! pf
          context.system.eventStream.publish(pf)
          context become main(pending - pf.id)
        }
      case pp =>
        pp.sender ! pf
        context become main(pending - pf.id)
    }

    case ps: PaymentSent => pending.get(ps.id).foreach(pp => {
      pp.sender ! ps
      pp match {
        case _: PendingTrampolinePayment => context.system.eventStream.publish(ps)
        case _ => // other types of payment internally handle publishing the event
      }
      context become main(pending - ps.id)
    })

    case GetPayment(id) =>
      val pending_opt = id match {
        case PaymentIdentifier.PaymentUUID(paymentId) => pending.get(paymentId).map(pp => (paymentId, pp))
        case PaymentIdentifier.PaymentHash(paymentHash) => pending.collectFirst { case (paymentId, pp) if pp.paymentHash == paymentHash => (paymentId, pp) }
        case PaymentIdentifier.OfferId(offerId) => pending.collectFirst {
          case (paymentId, pp@PendingPaymentToNode(_, SendPaymentToNode(_, _, invoice: Bolt12Invoice, _, _, _, _, _, _))) if invoice.invoiceRequest.offer.offerId == offerId =>
            (paymentId, pp)
        }
      }
      pending_opt match {
        case Some((paymentId, pp)) => sender() ! PaymentIsPending(paymentId, pp.paymentHash, pp)
        case None => sender() ! NoPendingPayment(id)
      }

  }

  private def buildTrampolineRecipient(r: SendRequestedPayment, trampolineHop: NodeHop): Try[ClearTrampolineRecipient] = {
    // We generate a random secret for the payment to the trampoline node.
    val trampolineSecret = r match {
      case r: SendPaymentToRoute => r.trampoline_opt.map(_.paymentSecret).getOrElse(randomBytes32())
      case _ => randomBytes32()
    }
    val finalExpiry = r.finalExpiry(nodeParams)
    r.invoice match {
      case invoice: Bolt11Invoice => Success(ClearTrampolineRecipient(invoice, r.recipientAmount, finalExpiry, trampolineHop, trampolineSecret))
      case _: Bolt12Invoice => Failure(new IllegalArgumentException("trampoline blinded payments are not supported yet"))
    }
  }

  private def sendTrampolinePayment(paymentId: UUID, r: SendTrampolinePayment, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): Try[Unit] = {
    val trampolineHop = NodeHop(r.trampolineNodeId, r.recipientNodeId, trampolineExpiryDelta, trampolineFees)
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentHash, r.recipientNodeId, Upstream.Local(paymentId), Some(r.invoice), None, storeInDb = true, publishEvent = false, recordPathFindingMetrics = true)
    buildTrampolineRecipient(r, trampolineHop).map { recipient =>
      val fsm = outgoingPaymentFactory.spawnOutgoingMultiPartPayment(context, paymentCfg, publishPreimage = false)
      fsm ! MultiPartPaymentLifecycle.SendMultiPartPayment(self, recipient, nodeParams.maxPaymentAttempts, r.routeParams)
    }
  }

}

// @formatter:off
sealed trait PaymentIdentifier
object PaymentIdentifier {
  case class PaymentUUID(uuid: UUID) extends PaymentIdentifier
  case class PaymentHash(hash: ByteVector32) extends PaymentIdentifier
  case class OfferId(id: ByteVector32) extends PaymentIdentifier
}
// @formatter:on

object PaymentInitiator {

  trait PaymentFactory {
    def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef
  }

  trait MultiPartPaymentFactory extends PaymentFactory {
    def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig, publishPreimage: Boolean): ActorRef
  }

  case class SimplePaymentFactory(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends MultiPartPaymentFactory {
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      context.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
    }

    override def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig, publishPreimage: Boolean): ActorRef = {
      context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, publishPreimage, router, this))
    }
  }

  def props(nodeParams: NodeParams, outgoingPaymentFactory: MultiPartPaymentFactory) = Props(new PaymentInitiator(nodeParams, outgoingPaymentFactory))

  // @formatter:off
  sealed trait PendingPayment {
    def sender: ActorRef
    def paymentHash: ByteVector32
  }
  case class PendingSpontaneousPayment(sender: ActorRef, request: SendSpontaneousPayment) extends PendingPayment { override def paymentHash: ByteVector32 = request.paymentHash }
  case class PendingPaymentToNode(sender: ActorRef, request: SendPaymentToNode) extends PendingPayment { override def paymentHash: ByteVector32 = request.paymentHash }
  case class PendingPaymentToRoute(sender: ActorRef, request: SendPaymentToRoute) extends PendingPayment { override def paymentHash: ByteVector32 = request.paymentHash }
  case class PendingTrampolinePayment(sender: ActorRef, remainingAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)], r: SendTrampolinePayment) extends PendingPayment { override def paymentHash: ByteVector32 = r.paymentHash }
  // @formatter:on

  // @formatter:off
  case class GetPayment(id: PaymentIdentifier)
  sealed trait GetPaymentResponse
  case class NoPendingPayment(id: PaymentIdentifier) extends GetPaymentResponse
  case class PaymentIsPending(paymentId: UUID, paymentHash: ByteVector32, pending: PendingPayment) extends GetPaymentResponse
  // @formatter:on

  sealed trait SendRequestedPayment {
    // @formatter:off
    def recipientAmount: MilliSatoshi
    def invoice: Invoice
    def recipientNodeId: PublicKey = invoice.nodeId
    def paymentHash: ByteVector32 = invoice.paymentHash
    // @formatter:on
    def finalExpiry(nodeParams: NodeParams): CltvExpiry = {
      val minFinalCltvExpiryDelta = invoice match {
        case invoice: Bolt11Invoice => invoice.minFinalCltvExpiryDelta
        case _: Bolt12Invoice => CltvExpiryDelta(0)
      }
      nodeParams.paymentFinalExpiry.computeFinalExpiry(nodeParams.currentBlockHeight, minFinalCltvExpiryDelta)
    }
  }

  /**
   * This command should be used to test the trampoline implementation until the feature is fully specified.
   *
   * @param recipientAmount    amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param invoice            Bolt 11 invoice.
   * @param trampolineNodeId   id of the trampoline node.
   * @param trampolineAttempts fees and expiry delta for the trampoline node. If this list contains multiple entries,
   *                           the payment will automatically be retried in case of TrampolineFeeInsufficient errors.
   *                           For example, [(10 msat, 144), (15 msat, 288)] will first send a payment with a fee of 10
   *                           msat and cltv of 144, and retry with 15 msat and 288 in case an error occurs.
   * @param routeParams        (optional) parameters to fine-tune the routing algorithm.
   */
  case class SendTrampolinePayment(recipientAmount: MilliSatoshi,
                                   invoice: Invoice,
                                   trampolineNodeId: PublicKey,
                                   trampolineAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)],
                                   routeParams: RouteParams) extends SendRequestedPayment

  /**
   * @param recipientAmount    amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param invoice            invoice.
   * @param maxAttempts        maximum number of retries.
   * @param externalId         (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param routeParams        (optional) parameters to fine-tune the routing algorithm.
   * @param payerKey_opt       (optional) private key associated with the invoice request when paying an offer.
   * @param userCustomTlvs     (optional) user-defined custom tlvs that will be added to the onion sent to the target node.
   * @param blockUntilComplete (optional) if true, wait until the payment completes before returning a result.
   */
  case class SendPaymentToNode(replyTo: ActorRef,
                               recipientAmount: MilliSatoshi,
                               invoice: Invoice,
                               maxAttempts: Int,
                               externalId: Option[String] = None,
                               routeParams: RouteParams,
                               payerKey_opt: Option[PrivateKey] = None,
                               userCustomTlvs: Set[GenericTlv] = Set.empty,
                               blockUntilComplete: Boolean = false) extends SendRequestedPayment

  /**
   * @param recipientAmount          amount that should be received by the final recipient.
   * @param recipientNodeId          id of the final recipient.
   * @param paymentPreimage          payment preimage.
   * @param maxAttempts              maximum number of retries.
   * @param externalId               (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param routeParams              (optional) parameters to fine-tune the routing algorithm.
   * @param userCustomTlvs           (optional) user-defined custom tlvs that will be added to the onion sent to the target node.
   * @param recordPathFindingMetrics will be used to build [[SendPaymentConfig]].
   */
  case class SendSpontaneousPayment(recipientAmount: MilliSatoshi,
                                    recipientNodeId: PublicKey,
                                    paymentPreimage: ByteVector32,
                                    maxAttempts: Int,
                                    externalId: Option[String] = None,
                                    routeParams: RouteParams,
                                    userCustomTlvs: Set[GenericTlv] = Set.empty,
                                    recordPathFindingMetrics: Boolean = false) {
    val paymentHash = Crypto.sha256(paymentPreimage)
  }

  /**
   * @param paymentSecret   this is a secret to protect the payment to the trampoline node against probing.
   * @param fees            fees for the trampoline node.
   * @param cltvExpiryDelta expiry delta for the trampoline node.
   */
  case class TrampolineAttempt(paymentSecret: ByteVector32, fees: MilliSatoshi, cltvExpiryDelta: CltvExpiryDelta)

  /**
   * The sender can skip the routing algorithm by specifying the route to use.
   *
   * When combining with MPP and Trampoline, extra-care must be taken to make sure payments are correctly grouped: only
   * amount, route and trampoline_opt should be changing. Splitting across multiple trampoline nodes isn't supported.
   *
   * Example 1: MPP containing two HTLCs for a 600 msat invoice:
   * SendPaymentToRoute(600 msat, invoice, Route(200 msat, Seq(alice, bob, dave)), None, Some(parentId), None)
   * SendPaymentToRoute(600 msat, invoice, Route(400 msat, Seq(alice, carol, dave)), None, Some(parentId), None)
   *
   * Example 2: Trampoline with MPP for a 600 msat invoice and 100 msat trampoline fees:
   * SendPaymentToRoute(600 msat, invoice, Route(250 msat, Seq(alice, bob, ted)), None, Some(parentId), Some(TrampolineAttempt(secret, 100 msat, CltvExpiryDelta(144))))
   * SendPaymentToRoute(600 msat, invoice, Route(450 msat, Seq(alice, carol, ted)), None, Some(parentId), Some(TrampolineAttempt(secret, 100 msat, CltvExpiryDelta(144))))
   *
   * @param recipientAmount amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   *                        This amount may be split between multiple requests if using MPP.
   * @param invoice         Bolt 11 invoice.
   * @param route           route to use to reach either the final recipient or the trampoline node.
   * @param externalId      (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId        id of the whole payment. When manually sending a multi-part payment, you need to make
   *                        sure all partial payments use the same parentId. If not provided, a random parentId will
   *                        be generated that can be used for the remaining partial payments.
   * @param trampoline_opt  if trampoline is used, this field must be provided. When manually sending a multi-part
   *                        payment, you need to make sure all partial payments share the same values.
   */
  case class SendPaymentToRoute(recipientAmount: MilliSatoshi,
                                invoice: Invoice,
                                route: PredefinedRoute,
                                externalId: Option[String],
                                parentId: Option[UUID],
                                trampoline_opt: Option[TrampolineAttempt]) extends SendRequestedPayment

  /**
   * @param paymentId        id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId         id of the whole payment. When manually sending a multi-part payment, you need to make sure
   *                         all partial payments use the same parentId.
   * @param trampolineSecret if trampoline is used, this is a secret to protect the payment to the first trampoline node
   *                         against probing. When manually sending a multi-part payment, you need to make sure all
   *                         partial payments use the same trampolineSecret.
   */
  case class SendPaymentToRouteResponse(paymentId: UUID, parentId: UUID, trampolineSecret: Option[ByteVector32])

  /**
   * Configuration for an instance of a payment state machine.
   *
   * @param id                       id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId                 id of the whole payment (if using multi-part, there will be N associated child payments,
   *                                 each with a different id).
   * @param externalId               externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param paymentHash              payment hash.
   * @param recipientNodeId          id of the final recipient.
   * @param upstream                 information about the payment origin (to link upstream to downstream when relaying a payment).
   * @param invoice                  Bolt 11 invoice.
   * @param storeInDb                whether to store data in the payments DB (e.g. when we're relaying a trampoline payment, we
   *                                 don't want to store in the DB).
   * @param publishEvent             whether to publish a [[fr.acinq.eclair.payment.PaymentEvent]] on success/failure (e.g. for
   *                                 multi-part child payments, we don't want to emit events for each child, only for the whole payment).
   * @param recordPathFindingMetrics We don't record metrics for payments that don't use path finding or that are a part of a bigger payment.
   */
  case class SendPaymentConfig(id: UUID,
                               parentId: UUID,
                               externalId: Option[String],
                               paymentHash: ByteVector32,
                               recipientNodeId: PublicKey,
                               upstream: Upstream,
                               invoice: Option[Invoice],
                               payerKey_opt: Option[PrivateKey],
                               storeInDb: Boolean, // e.g. for trampoline we don't want to store in the DB when we're relaying payments
                               publishEvent: Boolean,
                               recordPathFindingMetrics: Boolean) {
    val paymentContext: PaymentContext = PaymentContext(id, parentId, paymentHash)
    val paymentType = invoice match {
      case Some(_: Bolt12Invoice) => PaymentType.Blinded
      case _ => PaymentType.Standard
    }

    def createPaymentSent(recipient: Recipient, preimage: ByteVector32, parts: Seq[PaymentSent.PartialPayment]) = PaymentSent(parentId, paymentHash, preimage, recipient.totalAmount, recipient.nodeId, parts)
  }

}
