package fr.acinq.eclair.payment

import java.time.ZonedDateTime

import akka.actor.{Actor, ActorLogging, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.Payment
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{NodeParams, randomBytes}

import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  override def receive: Receive = run(Map())

  def run(h2r: Map[BinaryData, (BinaryData, PaymentRequest)]): Receive = {

    case ReceivePayment(amount_opt, desc) =>
      Try {
        val paymentPreimage = randomBytes(32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        (paymentPreimage, paymentHash, PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc))
      } match {
        case Success((r, h, pr)) =>
          log.debug(s"generated payment request=${PaymentRequest.write(pr)} from amount=$amount_opt")
          sender ! pr
          context.become(run(h2r + (h -> (r, pr))))
        case Failure(t) =>
          sender ! Status.Failure(t)
      }

    case htlc: UpdateAddHtlc =>
      if (h2r.contains(htlc.paymentHash)) {
        val r = h2r(htlc.paymentHash)._1
        val pr = h2r(htlc.paymentHash)._2
        // The htlc amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
        // it must not be greater than two times the requested amount.
        // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
        pr.amount match {
          case Some(amount) if MilliSatoshi(htlc.amountMsat) < amount => sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
          case Some(amount) if MilliSatoshi(htlc.amountMsat) > amount * 2 => sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
          case _ =>
            log.info(s"received payment for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
            // amount is correct or was not specified in the payment request
            nodeParams.paymentsDb.addPayment(Payment(htlc.paymentHash, htlc.amountMsat, ZonedDateTime.now().toEpochSecond))
            sender ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
            context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash))
            context.become(run(h2r - htlc.paymentHash))
        }
      } else {
        sender ! CMD_FAIL_HTLC(htlc.id, Right(UnknownPaymentHash), commit = true)
      }
  }
}

object LocalPaymentHandler {
  def props(nodeParams: NodeParams) = Props(new LocalPaymentHandler(nodeParams))
}