package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey

/**
  * Created by PM on 29/08/2016.
  */
class PaymentInitiator(sourceNodeId: PublicKey, router: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = {
    case c: CreatePayment =>
      val payFsm = context.actorOf(PaymentLifecycle.props(sourceNodeId, router))
      payFsm forward c
  }

}

object PaymentInitiator {
  def props(sourceNodeId: PublicKey, router: ActorRef) = Props(classOf[PaymentInitiator], sourceNodeId, router)
}
