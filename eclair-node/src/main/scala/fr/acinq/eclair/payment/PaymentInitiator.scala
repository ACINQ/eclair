package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount

/**
  * Created by PM on 29/08/2016.
  */
class PaymentInitiator(sourceNodeId: BinaryData, router: ActorRef, initialBlockCount: Long) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  override def receive: Receive = main(initialBlockCount)

  def main(currentBlockCount: Long): Receive = {
    case CurrentBlockCount(count) => context.become(main(count))
    case c: CreatePayment =>
      val payFsm = context.actorOf(PaymentLifecycle.props(sourceNodeId, router, initialBlockCount))
      payFsm forward c
  }

}

object PaymentInitiator {
  def props(sourceNodeId: BinaryData, router: ActorRef, initialBlockCount: Long) = Props(classOf[PaymentInitiator], sourceNodeId, router, initialBlockCount)
}
