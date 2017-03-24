package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, CMD_SIGN}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.{FailureMessageCodecs, UnknownPaymentHash, UpdateAddHtlc}

import scala.util.Random

/**
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler extends Actor with ActorLogging {

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

    case (htlc: UpdateAddHtlc, _) if h2r.contains(htlc.paymentHash) =>
      val r = h2r(htlc.paymentHash)
      sender ! CMD_FULFILL_HTLC(htlc.id, r, commit = true)
      context.system.eventStream.publish(PaymentReceived(self, htlc.paymentHash))
      context.become(run(h2r - htlc.paymentHash))

    case (htlc: UpdateAddHtlc, sharedSecret: BinaryData) =>
      val reason = Sphinx.createErrorPacket(sharedSecret, UnknownPaymentHash)
      sender ! CMD_FAIL_HTLC(htlc.id, reason, commit = true)

  }

}
