package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.wire.{IncorrectPaymentAmount, UnknownPaymentHash, UpdateAddHtlc}

import scala.util.{Failure, Random, Success, Try}

/**
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  // see http://bugs.java.com/view_bug.do?bug_id=6521844
  // val random = SecureRandom.getInstanceStrong
  val random = new Random()

  def generateR(): BinaryData = {
    val r = Array.fill[Byte](32)(0)
    random.nextBytes(r)
    r
  }

  override def receive: Receive = run(Map())

  // TODO: store this map on file ?
  // TODO: add payment amount to the map: we need to be able to check that the amount matches what we expected
  def run(h2r: Map[BinaryData, (BinaryData, PaymentRequest)]): Receive = {

    case ReceivePayment(amount) =>
      Try {
        val r = generateR
        val h = Crypto.sha256(r)
        (r, h, PaymentRequest(nodeParams.privateKey.publicKey, amount, h))
      } match {
        case Success((r, h, pr)) =>
          log.debug(s"generated payment request=${PaymentRequest.write(pr)} from amount=$amount")
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
        // see https://github.com/lightningnetwork/lightning-rfc/pull/139
        if (pr.amount.amount <= htlc.amountMsat && htlc.amountMsat <= (2 * pr.amount.amount)) {
          sender ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
          context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash))
          context.become(run(h2r - htlc.paymentHash))
        } else {
          sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
        }
      } else {
        sender ! CMD_FAIL_HTLC(htlc.id, Right(UnknownPaymentHash), commit = true)
      }
  }
}

object LocalPaymentHandler {
  def props(nodeParams: NodeParams) = Props(new LocalPaymentHandler(nodeParams))
}