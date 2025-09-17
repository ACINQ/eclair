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

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.Upstream
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.db.PaymentType
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.BlindedPathsResolver.ResolvedPath
import fr.acinq.eclair.payment.send.PaymentError._
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, NodeParams}
import scodec.bits.ByteVector

import java.util.UUID

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
        case invoice: Bolt12Invoice => BlindedRecipient(invoice, r.resolvedPaths, r.recipientAmount, finalExpiry, r.userCustomTlvs)
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
      if (!r.blockUntilComplete) {
        r.replyTo ! paymentId
      }
      if (r.invoice.amount_opt.isEmpty) {
        r.replyTo ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, new IllegalArgumentException("test trampoline payments must not use amount-less invoices")) :: Nil)
      } else {
        log.info(s"sending trampoline payment with trampolineNodeId=${r.trampolineNodeId} and invoice=${r.invoice.toString}")
        val fsm = outgoingPaymentFactory.spawnOutgoingTrampolinePayment(context)
        fsm ! TrampolinePaymentLifecycle.SendPayment(self, paymentId, r.trampolineNodeId, r.invoice, r.routeParams)
        context become main(pending + (paymentId -> PendingTrampolinePayment(r.replyTo, r)))
      }

    case r: SendPaymentToRoute =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      if (!nodeParams.features.invoiceFeatures().areSupported(r.invoice.features)) {
        sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, UnsupportedFeatures(r.invoice.features)) :: Nil)
      } else {
        sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId)
        val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientNodeId, Upstream.Local(paymentId), Some(r.invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = false)
        val finalExpiry = r.finalExpiry(nodeParams)
        val recipient = r.invoice match {
          case invoice: Bolt11Invoice => ClearRecipient(invoice, r.recipientAmount, finalExpiry, Set.empty)
          case invoice: Bolt12Invoice => BlindedRecipient(invoice, r.resolvedPaths, r.recipientAmount, finalExpiry, Set.empty)
        }
        val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
        payFsm ! PaymentLifecycle.SendPaymentToRoute(self, Left(r.route), recipient)
        context become main(pending + (paymentId -> PendingPaymentToRoute(sender(), r)))
      }

    case pf: PaymentFailed => pending.get(pf.id).foreach { pp =>
      pp.sender ! pf
      context become main(pending - pf.id)
    }

    case ps: PaymentSent => pending.get(ps.id).foreach { pp =>
      pp.sender ! ps
      context become main(pending - ps.id)
    }

    case GetPayment(id) =>
      val pending_opt = id match {
        case PaymentIdentifier.PaymentUUID(paymentId) => pending.get(paymentId).map(pp => (paymentId, pp))
        case PaymentIdentifier.PaymentHash(paymentHash) => pending.collectFirst { case (paymentId, pp) if pp.paymentHash == paymentHash => (paymentId, pp) }
        case PaymentIdentifier.OfferId(offerId) => pending.collectFirst {
          case (paymentId, pp@PendingPaymentToNode(_, SendPaymentToNode(_, _, invoice: Bolt12Invoice, _, _, _, _, _, _, _))) if invoice.invoiceRequest.offer.offerId == offerId =>
            (paymentId, pp)
        }
      }
      pending_opt match {
        case Some((paymentId, pp)) => sender() ! PaymentIsPending(paymentId, pp.paymentHash, pp)
        case None => sender() ! NoPendingPayment(id)
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

  trait TrampolinePaymentFactory {
    def spawnOutgoingTrampolinePayment(context: ActorContext): typed.ActorRef[TrampolinePaymentLifecycle.Command]
  }

  trait MultiPartPaymentFactory extends PaymentFactory with TrampolinePaymentFactory {
    def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig, publishPreimage: Boolean): ActorRef
  }

  case class SimplePaymentFactory(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends MultiPartPaymentFactory {
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      context.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register, None))
    }

    override def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig, publishPreimage: Boolean): ActorRef = {
      context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, publishPreimage, router, this))
    }

    override def spawnOutgoingTrampolinePayment(context: ActorContext): typed.ActorRef[TrampolinePaymentLifecycle.Command] = {
      context.spawnAnonymous(TrampolinePaymentLifecycle(nodeParams, register.toTyped))
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
  case class PendingTrampolinePayment(sender: ActorRef, request: SendTrampolinePayment) extends PendingPayment { override def paymentHash: ByteVector32 = request.paymentHash }
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
        // For blinded payments, the min-final-expiry-delta is included in the blinded path instead of being added explicitly by the sender.
        case _: Bolt12Invoice => CltvExpiryDelta(0)
      }
      nodeParams.paymentFinalExpiry.computeFinalExpiry(nodeParams.currentBlockHeight, minFinalCltvExpiryDelta)
    }
  }

  /**
   * Eclair nodes never need to send trampoline payments, but they need to be able to relay them or receive them.
   * This command is only used in e2e tests, to simulate the behavior of a trampoline sender and verify that relaying
   * and receiving payments work.
   *
   * @param invoice            Bolt 11 invoice.
   * @param trampolineNodeId   id of the trampoline node (which must be a direct peer for simplicity).
   * @param routeParams        (optional) parameters to fine-tune the maximum fee allowed.
   * @param blockUntilComplete (optional) if true, wait until the payment completes before returning a result.
   */
  case class SendTrampolinePayment(replyTo: ActorRef,
                                   invoice: Invoice,
                                   trampolineNodeId: PublicKey,
                                   routeParams: RouteParams,
                                   blockUntilComplete: Boolean = false) extends SendRequestedPayment {
    override val recipientAmount = invoice.amount_opt.getOrElse(0 msat)
  }

  /**
   * @param recipientAmount    amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param invoice            invoice.
   * @param resolvedPaths      when using a Bolt 12 invoice, list of payment paths to reach the recipient.
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
                               resolvedPaths: Seq[ResolvedPath],
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
   * The sender can skip the routing algorithm by specifying the route to use.
   *
   * @param recipientAmount amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   *                        This amount may be split between multiple requests if using MPP.
   * @param invoice         Bolt 11 invoice.
   * @param resolvedPaths   when using a Bolt 12 invoice, list of payment paths to reach the recipient.
   * @param route           route to use to reach the final recipient.
   * @param externalId      (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId        id of the whole payment. When manually sending a multi-part payment, you need to make
   *                        sure all partial payments use the same parentId. If not provided, a random parentId will
   *                        be generated that can be used for the remaining partial payments.
   */
  case class SendPaymentToRoute(recipientAmount: MilliSatoshi,
                                invoice: Invoice,
                                resolvedPaths: Seq[ResolvedPath],
                                route: PredefinedRoute,
                                externalId: Option[String],
                                parentId: Option[UUID]) extends SendRequestedPayment

  /**
   * @param paymentId id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId  id of the whole payment. When manually sending a multi-part payment, you need to make sure
   *                  all partial payments use the same parentId.
   */
  case class SendPaymentToRouteResponse(paymentId: UUID, parentId: UUID)

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
                               upstream: Upstream.Hot,
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

    def createPaymentSent(recipient: Recipient, preimage: ByteVector32, parts: Seq[PaymentSent.PartialPayment], remainingAttribution_opt: Option[ByteVector]) = PaymentSent(parentId, paymentHash, preimage, recipient.totalAmount, recipient.nodeId, parts, remainingAttribution_opt)
  }

}
