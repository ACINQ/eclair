package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.payment.LocalPaymentHandler.NewPaymentRequest
import fr.acinq.eclair.wire.{UnknownPaymentHash, UpdateAddHtlc}

import scala.util.Random

/**
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  // see http://bugs.java.com/view_bug.do?bug_id=6521844
  //val random = SecureRandom.getInstanceStrong
  val random = new Random()

  def generateR(): BinaryData = {
    val r = Array.fill[Byte](32)(0)
    random.nextBytes(r)
    r
  }

  override def receive: Receive = run(Map())

  // TODO: store this map on file ?
  // TODO: add payment amount to the map: we need to be able to check that the amount matches what we expected
  def run(h2r: Map[BinaryData, BinaryData]): Receive = {
    case 'genh =>
      val r = generateR()
      val h: BinaryData = Crypto.sha256(r)
      sender ! h
      context.become(run(h2r + (h -> r)))

    case NewPaymentRequest(amount) if amount.amount > 0 && amount.amount < 4294967295L =>
      val r = generateR
      val h: BinaryData = Crypto.sha256(r)
      val pr = s"${nodeParams.privateKey.publicKey}:${amount.amount}:${h.toString}"
      log.debug(s"generated payment request=$pr from amount=$amount")
      sender ! pr
      context.become(run(h2r + (h -> r)))

    case NewPaymentRequest(amount) =>
      sender ! Status.Failure(new RuntimeException("amount is not valid: must be > 0 and < 42.95 mBTC"))

    case htlc: UpdateAddHtlc if h2r.contains(htlc.paymentHash) =>
      val r = h2r(htlc.paymentHash)
      sender ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
      context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash))
      context.become(run(h2r - htlc.paymentHash))

    case htlc: UpdateAddHtlc =>
      sender ! CMD_FAIL_HTLC(htlc.id, Right(UnknownPaymentHash), commit = true)

  }
}

object LocalPaymentHandler {
  def props(nodeParams: NodeParams) = Props(new LocalPaymentHandler(nodeParams))
  case class NewPaymentRequest(amountMsat: MilliSatoshi)
}