package fr.acinq.eclair.channel

import java.security.SecureRandom

import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import lightning.update_add_htlc

/**
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler extends Actor with ActorLogging {

  val random = SecureRandom.getInstanceStrong

  def generateR(): BinaryData = {
    val r = Array.fill[Byte](32)(0)
    random.nextBytes(r)
    r
  }

  context.become(run(Map()))

  override def receive: Receive = ???

  //TODO: store this map on file ?
  def run(h2r: Map[BinaryData, BinaryData]): Receive = {
    case 'genh =>
      val r = generateR()
      val h: BinaryData = Crypto.sha256(r)
      sender ! h
      context.become(run(h2r + (h -> r)))

    case htlc: update_add_htlc =>
      val r = h2r(htlc.rHash)
      sender ! CMD_FULFILL_HTLC(htlc.id, r, true)
      context.become(run(h2r - htlc.rHash))

  }

}
