package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.BinaryData

/**
  * Created by anton on 30.04.17.
  */

class ReceivedPayments extends Actor with ActorLogging  {

  context.system.eventStream.subscribe(self, classOf[PaymentEvent])

  override def receive: Receive = run(Map.empty)

  def run(history: Map[BinaryData, Long]): Receive = {
    case p: PaymentReceived =>
      val history1 = history.updated(p.paymentHash, System.currentTimeMillis())
      context.become(run(history1))

    case paymentHash: BinaryData =>
      sender ! history.get(paymentHash)
  }
}
