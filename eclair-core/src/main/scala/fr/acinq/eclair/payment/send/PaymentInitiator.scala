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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.Features.BasicMultiPartPayment
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.PaymentError._
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, NodeParams, randomBytes32}

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
      val paymentId = UUID.randomUUID()
      if (!r.blockUntilComplete) {
        // Immediately return the paymentId
        sender() ! paymentId
      }
      val recipients = r.recipients.map(_.withCustomTlvs(r.userCustomTlvs))
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.recipientAmount, recipients.flatMap(_.nodeIds), Upstream.Local(paymentId), Some(r.invoice), storeInDb = true, publishEvent = true, recordPathFindingMetrics = true, Nil)
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      if (!nodeParams.features.invoiceFeatures().areSupported(r.invoice.features)) {
        sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, UnsupportedFeatures(r.invoice.features)) :: Nil)
      } else if (r.invoice.features.hasFeature(Features.BasicMultiPartPayment) && nodeParams.features.hasFeature(BasicMultiPartPayment)) {
        val fsm = outgoingPaymentFactory.spawnOutgoingMultiPartPayment(context, paymentCfg)
        fsm ! MultiPartPaymentLifecycle.SendMultiPartPayment(self, recipients, r.recipientAmount, finalExpiry, r.maxAttempts, r.invoice.extraEdges, r.routeParams)
        context become main(pending + (paymentId -> PendingPaymentToNode(sender(), r)))
      } else {
        val fsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
        fsm ! PaymentLifecycle.SendPaymentToNode(self, recipients, r.recipientAmount, r.recipientAmount, finalExpiry, r.maxAttempts, r.invoice.extraEdges, r.routeParams)
        context become main(pending + (paymentId -> PendingPaymentToNode(sender(), r)))
      }

    case r: SendSpontaneousPayment =>
      val paymentId = UUID.randomUUID()
      sender() ! paymentId
      val recipients = Seq(KeySendRecipient(r.recipientNodeId, r.paymentPreimage, r.userCustomTlvs))
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.recipientAmount, Seq(r.recipientNodeId), Upstream.Local(paymentId), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = r.recordPathFindingMetrics, Nil)
      val finalExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1)
      val fsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
      fsm ! PaymentLifecycle.SendPaymentToNode(self, recipients, r.recipientAmount, r.recipientAmount, finalExpiry, r.maxAttempts, routeParams = r.routeParams)
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

    case r: SendTrampolinePaymentToRoute =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      val additionalHops = r.trampolineNodes.sliding(2).map(hop => NodeHop(hop.head, hop(1), CltvExpiryDelta(0), 0 msat)).toSeq
      val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientAmount, Seq(r.invoice.nodeId), Upstream.Local(paymentId), Some(r.invoice), storeInDb = true, publishEvent = true, recordPathFindingMetrics = false, additionalHops)
      r.trampolineNodes match {
        case trampoline :: recipient :: Nil =>
          log.info(s"sending trampoline payment to $recipient with trampoline=$trampoline, trampoline fees=${r.trampolineFees}, expiry delta=${r.trampolineExpiryDelta}")
          buildTrampolinePayment(r, trampoline, r.trampolineFees, r.trampolineExpiryDelta) match {
            case Success((trampolineAmount, trampolineExpiry, trampolineOnion)) =>
              // We generate a random secret for the payment to the first trampoline node.
              val trampolineSecret = r.trampolineSecret.getOrElse(randomBytes32())
              sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId, Some(trampolineSecret))
              val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
              payFsm ! PaymentLifecycle.SendPaymentToRoute(self, Left(r.route), TrampolineRecipient(trampoline, trampolineOnion, r.invoice.paymentMetadata, trampolineSecret), r.amount, trampolineAmount, trampolineExpiry, r.invoice.extraEdges)
              context become main(pending + (paymentId -> PendingTrampolinePaymentToRoute(sender(), r)))
            case Failure(t) =>
              log.warning("cannot send outgoing trampoline payment: {}", t.getMessage)
              sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, t) :: Nil)
          }
        case _ =>
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, TrampolineMultiNodeNotSupported) :: Nil)
      }

    case r: SendPaymentToRoute =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.recipientAmount, r.recipients.flatMap(_.nodeIds), Upstream.Local(paymentId), Some(r.invoice), storeInDb = true, publishEvent = true, recordPathFindingMetrics = false, Nil)
      sender() ! SendPaymentToRouteResponse(paymentId, parentPaymentId, None)
      val payFsm = outgoingPaymentFactory.spawnOutgoingPayment(context, paymentCfg)
      r.recipients.find(_.introductionNodeId == r.route.targetNodeId) match {
        case Some(recipient) =>
          payFsm ! PaymentLifecycle.SendPaymentToRoute(self, Left(r.route), recipient, r.amount, r.recipientAmount, finalExpiry, r.invoice.extraEdges)
          context become main(pending + (paymentId -> PendingPaymentToRoute(sender(), r)))
        case None =>
          log.warning("the provided route does not reach the correct recipient")
          sender() ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(r.recipientAmount, Nil, InvalidRecipientForRoute(r.route, r.recipients)) :: Nil)
      }

    case pf: PaymentFailed => pending.get(pf.id).foreach {
      case pp: PendingTrampolinePayment =>
        val trampolineRoute = Seq(
          NodeHop(nodeParams.nodeId, pp.r.trampolineNodeId, nodeParams.channelConf.expiryDelta, 0 msat),
          NodeHop(pp.r.trampolineNodeId, pp.r.invoice.nodeId, pp.r.trampolineAttempts.last._2, pp.r.trampolineAttempts.last._1)
        )
        val decryptedFailures = pf.failures.collect { case RemoteFailure(_, _, Sphinx.DecryptedFailurePacket(_, f)) => f }
        val shouldRetry = decryptedFailures.contains(TrampolineFeeInsufficient) || decryptedFailures.contains(TrampolineExpiryTooSoon)
        if (shouldRetry) {
          pp.remainingAttempts match {
            case (trampolineFees, trampolineExpiryDelta) :: remaining =>
              log.info(s"retrying trampoline payment with trampoline fees=$trampolineFees and expiry delta=$trampolineExpiryDelta")
              sendTrampolinePayment(pf.id, pp.r, trampolineFees, trampolineExpiryDelta) match {
                case Success(_) =>
                  context become main(pending + (pf.id -> pp.copy(remainingAttempts = remaining)))
                case Failure(t) =>
                  log.warning("cannot send outgoing trampoline payment: {}", t.getMessage)
                  val localFailure = pf.copy(failures = Seq(LocalFailure(pp.r.recipientAmount, trampolineRoute, t)))
                  pp.sender ! localFailure
                  context.system.eventStream.publish(localFailure)
                  context become main(pending - pf.id)
              }
            case Nil =>
              log.info("trampoline node couldn't find a route after all retries")
              val localFailure = pf.copy(failures = Seq(LocalFailure(pp.r.recipientAmount, trampolineRoute, RouteNotFound)))
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

    case _: MultiPartPaymentLifecycle.PreimageReceived => // we received the preimage, but we wait for the PaymentSent event that will contain more data

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
        case Left(paymentId) => pending.get(paymentId).map(pp => (paymentId, pp))
        case Right(paymentHash) => pending.collectFirst { case (paymentId, pp) if pp.paymentHash == paymentHash => (paymentId, pp) }
      }
      pending_opt match {
        case Some((paymentId, pp)) => sender() ! PaymentIsPending(paymentId, pp.paymentHash, pp)
        case None => sender() ! NoPendingPayment(id)
      }

  }

  private def buildTrampolinePayment(r: SendRequestedPayment, trampolineNodeId: PublicKey, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): Try[(MilliSatoshi, CltvExpiry, OnionRoutingPacket)] = {
    r.invoice match {
      case invoice: Bolt11Invoice =>
        val trampolineRoute = Seq(
          NodeHop(nodeParams.nodeId, trampolineNodeId, nodeParams.channelConf.expiryDelta, 0 msat),
          NodeHop(trampolineNodeId, invoice.nodeId, trampolineExpiryDelta, trampolineFees) // for now we only use a single trampoline hop
        )
        // We assume that the trampoline node supports multi-part payments (it should).
        val trampolinePacket_opt = if (r.invoice.features.hasFeature(Features.TrampolinePaymentPrototype)) {
          OutgoingPaymentPacket.buildTrampolinePacket(r.paymentHash, trampolineRoute, invoice.recipient, r.recipientAmount, r.recipientAmount, r.finalExpiry(nodeParams.currentBlockHeight))
        } else {
          OutgoingPaymentPacket.buildTrampolineToLegacyPacket(invoice, trampolineRoute, r.recipientAmount, r.finalExpiry(nodeParams.currentBlockHeight))
        }
        trampolinePacket_opt.map {
          case (trampolineAmount, trampolineExpiry, trampolineOnion) => (trampolineAmount, trampolineExpiry, trampolineOnion.packet)
        }
      case _ => Failure(new Exception("Trampoline is only supported for Bolt11 invoices."))
    }
  }

  private def sendTrampolinePayment(paymentId: UUID, r: SendTrampolinePayment, trampolineFees: MilliSatoshi, trampolineExpiryDelta: CltvExpiryDelta): Try[Unit] = {
    val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentHash, r.recipientAmount, Seq(r.invoice.nodeId), Upstream.Local(paymentId), Some(r.invoice), storeInDb = true, publishEvent = false, recordPathFindingMetrics = true, Seq(NodeHop(r.trampolineNodeId, r.invoice.nodeId, trampolineExpiryDelta, trampolineFees)))
    buildTrampolinePayment(r, r.trampolineNodeId, trampolineFees, trampolineExpiryDelta).map {
      case (trampolineAmount, trampolineExpiry, trampolineOnion) =>
        val fsm = outgoingPaymentFactory.spawnOutgoingMultiPartPayment(context, paymentCfg)
        val trampolineRecipient = TrampolineRecipient(r.trampolineNodeId, trampolineOnion, r.invoice.paymentMetadata)
        fsm ! MultiPartPaymentLifecycle.SendMultiPartPayment(self, Seq(trampolineRecipient), trampolineAmount, trampolineExpiry, nodeParams.maxPaymentAttempts, r.invoice.extraEdges, r.routeParams)
    }
  }

}

object PaymentInitiator {

  trait PaymentFactory {
    def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef
  }

  trait MultiPartPaymentFactory extends PaymentFactory {
    def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef
  }

  case class SimplePaymentFactory(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends MultiPartPaymentFactory {
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      context.actorOf(PaymentLifecycle.props(nodeParams, cfg, router, register))
    }

    override def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, cfg, router, this))
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
  case class PendingTrampolinePaymentToRoute(sender: ActorRef, request: SendTrampolinePaymentToRoute) extends PendingPayment { override def paymentHash: ByteVector32 = request.paymentHash }
  case class PendingTrampolinePayment(sender: ActorRef, remainingAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)], r: SendTrampolinePayment) extends PendingPayment { override def paymentHash: ByteVector32 = r.paymentHash }
  // @formatter:on

  // @formatter:off
  case class GetPayment(id: Either[UUID, ByteVector32])
  sealed trait GetPaymentResponse
  case class NoPendingPayment(id: Either[UUID, ByteVector32]) extends GetPaymentResponse
  case class PaymentIsPending(paymentId: UUID, paymentHash: ByteVector32, pending: PendingPayment) extends GetPaymentResponse
  // @formatter:on

  sealed trait SendRequestedPayment {
    // @formatter:off
    def recipientAmount: MilliSatoshi
    def invoice: Invoice
    def recipients: Seq[Recipient] = invoice.recipients
    def recipientNodeId: PublicKey = recipients.head.nodeId
    def paymentHash: ByteVector32 = invoice.paymentHash
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: BlockHeight): CltvExpiry = invoice.minFinalCltvExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
    // @formatter:on
  }

  /**
   * We temporarily let the caller decide to use Trampoline (instead of a normal payment) and set the fees/cltv.
   * Once we have trampoline fee estimation built into the router, the decision to use Trampoline or not should be done
   * automatically by the router instead of the caller.
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
                                   invoice: Bolt11Invoice,
                                   trampolineNodeId: PublicKey,
                                   trampolineAttempts: Seq[(MilliSatoshi, CltvExpiryDelta)],
                                   routeParams: RouteParams) extends SendRequestedPayment

  /**
   * @param recipientAmount    amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param invoice            Bolt 11 invoice.
   * @param maxAttempts        maximum number of retries.
   * @param externalId         (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param routeParams        (optional) parameters to fine-tune the routing algorithm.
   * @param userCustomTlvs     (optional) user-defined custom tlvs that will be added to the onion sent to the target node.
   * @param blockUntilComplete (optional) if true, wait until the payment completes before returning a result.
   */
  case class SendPaymentToNode(recipientAmount: MilliSatoshi,
                               invoice: Invoice,
                               maxAttempts: Int,
                               externalId: Option[String] = None,
                               routeParams: RouteParams,
                               userCustomTlvs: Seq[GenericTlv] = Nil,
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
                                    userCustomTlvs: Seq[GenericTlv] = Nil,
                                    recordPathFindingMetrics: Boolean = false) {
    val paymentHash = Crypto.sha256(paymentPreimage)
  }

  /**
   * The sender can skip the routing algorithm by specifying the route to use.
   * When combining with MPP, extra-care must be taken to make sure payments are correctly grouped: only amount and
   * route should be changing.
   *
   * Example 1: MPP containing two HTLCs for a 600 msat invoice:
   * SendPaymentToRoute(200 msat, 600 msat, invoice, Seq(alice, bob, dave), None, Some(parentId))
   * SendPaymentToRoute(400 msat, 600 msat, invoice, Seq(alice, carol, dave), None, Some(parentId))
   *
   * @param amount          amount to send through this route
   * @param recipientAmount amount that should be received by the final recipient.
   *                        This amount may be split between multiple requests if using MPP.
   * @param invoice         invoice.
   * @param route           route to use to reach the recipient.
   * @param externalId      (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId        id of the whole payment. When manually sending a multi-part payment, you need to make
   *                        sure all partial payments use the same parentId. If not provided, a random parentId will
   *                        be generated that can be used for the remaining partial payments.
   */
  case class SendPaymentToRoute(amount: MilliSatoshi,
                                recipientAmount: MilliSatoshi,
                                invoice: Invoice,
                                route: PredefinedRoute,
                                externalId: Option[String],
                                parentId: Option[UUID]) extends SendRequestedPayment

  /**
   * The sender can skip the routing algorithm by specifying the route to use.
   * When combining with MPP and Trampoline, extra-care must be taken to make sure payments are correctly grouped: only
   * amount, route and trampolineNodes should be changing.
   *
   * Example 2: Trampoline with MPP for a 600 msat invoice and 100 msat trampoline fees:
   * SendPaymentToRouteRequest(250 msat, 600 msat, invoice, Seq(alice, bob, dave), None, Some(parentId), secret, 100 msat, CltvExpiryDelta(144), Seq(dave, peter))
   * SendPaymentToRouteRequest(450 msat, 600 msat, invoice, Seq(alice, carol, dave), None, Some(parentId), secret, 100 msat, CltvExpiryDelta(144), Seq(dave, peter))
   *
   * @param amount                amount that should be received by the last node in the route (should take trampoline
   *                              fees into account).
   * @param recipientAmount       amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   *                              This amount may be split between multiple requests if using MPP.
   * @param invoice               Bolt 11 invoice.
   * @param route                 route to use to reach either the final recipient or the first trampoline node.
   * @param externalId            (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId              id of the whole payment. When manually sending a multi-part payment, you need to make
   *                              sure all partial payments use the same parentId. If not provided, a random parentId will
   *                              be generated that can be used for the remaining partial payments.
   * @param trampolineSecret      this is a secret to protect the payment to the first trampoline node against probing.
   *                              When manually sending a multi-part payment, you need to make sure all partial payments
   *                              use the same trampolineSecret.
   * @param trampolineFees        fees for the first trampoline node. This value must be the same for all partial
   *                              payments in the set.
   * @param trampolineExpiryDelta expiry delta for the first trampoline node. This value must be the same for all
   *                              partial payments in the set.
   * @param trampolineNodes       list of trampoline nodes to use (we currently support only a single trampoline node).
   *                              The last one must be the recipient.
   */
  case class SendTrampolinePaymentToRoute(amount: MilliSatoshi,
                                          recipientAmount: MilliSatoshi,
                                          invoice: Bolt11Invoice,
                                          route: PredefinedRoute,
                                          externalId: Option[String],
                                          parentId: Option[UUID],
                                          trampolineSecret: Option[ByteVector32],
                                          trampolineFees: MilliSatoshi,
                                          trampolineExpiryDelta: CltvExpiryDelta,
                                          trampolineNodes: Seq[PublicKey]) extends SendRequestedPayment

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
   * @param recipientAmount          amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param recipientNodeIds         ids of the final recipients. Used to check if an error was returned by a recipient.
   * @param upstream                 information about the payment origin (to link upstream to downstream when relaying a payment).
   * @param invoice                  Bolt 11 invoice.
   * @param storeInDb                whether to store data in the payments DB (e.g. when we're relaying a trampoline payment, we
   *                                 don't want to store in the DB).
   * @param publishEvent             whether to publish a [[fr.acinq.eclair.payment.PaymentEvent]] on success/failure (e.g. for
   *                                 multi-part child payments, we don't want to emit events for each child, only for the whole payment).
   * @param recordPathFindingMetrics We don't record metrics for payments that don't use path finding or that are a part of a bigger payment.
   * @param additionalHops           additional hops that the payment state machine isn't aware of (e.g. when using trampoline, hops
   *                                 that occur after the first trampoline node).
   */
  case class SendPaymentConfig(id: UUID,
                               parentId: UUID,
                               externalId: Option[String],
                               paymentHash: ByteVector32,
                               recipientAmount: MilliSatoshi,
                               recipientNodeIds: Seq[PublicKey],
                               upstream: Upstream,
                               invoice: Option[Invoice],
                               storeInDb: Boolean, // e.g. for trampoline we don't want to store in the DB when we're relaying payments
                               publishEvent: Boolean,
                               recordPathFindingMetrics: Boolean,
                               additionalHops: Seq[NodeHop]) {
    def fullRoute(route: Route): Seq[Hop] = route.clearHops ++ additionalHops

    def createPaymentSent(preimage: ByteVector32, parts: Seq[PaymentSent.PartialPayment]) = PaymentSent(parentId, paymentHash, preimage, recipientAmount, recipientNodeIds.head, parts)

    def paymentContext: PaymentContext = PaymentContext(id, parentId, paymentHash)

    def isRecipient(nodeId: PublicKey): Boolean = recipientNodeIds.contains(nodeId)
  }

}
