package fr.acinq.eclair.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount

/**
  * Created by PM on 29/08/2016.
  */
class PaymentSpawner(router: ActorRef, selector: ActorRef, initialBlockCount: Long) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  override def receive: Receive = main(initialBlockCount)

  def main(currentBlockCount: Long): Receive = {
    case CurrentBlockCount(count) => context.become(main(currentBlockCount))
    case c: CreatePayment =>
      val payFsm = context.actorOf(PaymentManager.props(router, selector, initialBlockCount))
      payFsm forward c
  }

}

object PaymentSpawner {
  def props(router: ActorRef, selector: ActorRef, initialBlockCount: Long) = Props(classOf[PaymentSpawner], router, selector, initialBlockCount)
}
