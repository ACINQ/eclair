package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 29/08/2016.
  */
class PaymentInitiator(sourceNodeId: BinaryData, router: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = {
    case c: CreatePayment =>
      val payFsm = context.actorOf(PaymentLifecycle.props(sourceNodeId, router))
      payFsm forward c
  }

}

object PaymentInitiator {
  def props(sourceNodeId: BinaryData, router: ActorRef) = Props(classOf[PaymentInitiator], sourceNodeId, router)
}
