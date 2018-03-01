package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.Payment
import fr.acinq.eclair.wire._

import scala.concurrent.duration._
import fr.acinq.eclair.{Globals, NodeParams, randomBytes}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler(nodeParams: NodeParams)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(10 minutes, 10 minutes)(self ! Platform.currentTime / 1000)

  override def receive: Receive = run(Map())

  def run(hash2preimage: Map[BinaryData, (BinaryData, PaymentRequest)]): Receive = {

    case currentSeconds: Long =>
      context.become(run(hash2preimage.collect {
        case e@(_, (_, pr)) if pr.expiry.isEmpty => e // requests that don't expire are kept forever
        case e@(_, (_, pr)) if pr.timestamp + pr.expiry.get > currentSeconds => e // clean up expired requests
      }))

    case ReceivePayment(amount_opt, desc) =>
      Try {
        if (hash2preimage.size > nodeParams.maxPendingPaymentRequests) {
          throw new RuntimeException(s"too many pending payment requests (max=${nodeParams.maxPendingPaymentRequests})")
        }
        val paymentPreimage = randomBytes(32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress = None, expirySeconds = Some(nodeParams.paymentRequestExpiry.toSeconds))
        log.debug(s"generated payment request=${PaymentRequest.write(paymentRequest)} from amount=$amount_opt")
        sender ! paymentRequest
        context.become(run(hash2preimage + (paymentHash -> (paymentPreimage, paymentRequest))))
      } recover { case t => sender ! Status.Failure(t) }

    case CheckPayment(paymentHash) =>
      nodeParams.paymentsDb.findByPaymentHash(paymentHash) match {
        case Some(_) => sender ! true
        case _ => sender ! false
      }

    case htlc: UpdateAddHtlc =>
      hash2preimage.get(htlc.paymentHash) match {
        case Some((paymentPreimage, paymentRequest)) =>
          val minFinalExpiry = Globals.blockCount.get() + paymentRequest.minFinalCltvExpiry.getOrElse(Channel.MIN_CLTV_EXPIRY)
          // The htlc amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
          // it must not be greater than two times the requested amount.
          // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
          paymentRequest.amount match {
            case _ if htlc.expiry < minFinalExpiry =>
              sender ! CMD_FAIL_HTLC(htlc.id, Right(FinalExpiryTooSoon), commit = true)
            case Some(amount) if MilliSatoshi(htlc.amountMsat) < amount =>
              log.warning(s"received payment with amount too small for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
            case Some(amount) if MilliSatoshi(htlc.amountMsat) > amount * 2 =>
              log.warning(s"received payment with amount too large for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
            case _ =>
              log.info(s"received payment for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              // amount is correct or was not specified in the payment request
              nodeParams.paymentsDb.addPayment(Payment(htlc.paymentHash, htlc.amountMsat, Platform.currentTime / 1000))
              sender ! CMD_FULFILL_HTLC(htlc.id, paymentPreimage, commit = true)
              context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash))
              context.become(run(hash2preimage - htlc.paymentHash))
          }
        case None =>
          sender ! CMD_FAIL_HTLC(htlc.id, Right(UnknownPaymentHash), commit = true)
      }
  }
}

object LocalPaymentHandler {
  def props(nodeParams: NodeParams) = Props(new LocalPaymentHandler(nodeParams))
}