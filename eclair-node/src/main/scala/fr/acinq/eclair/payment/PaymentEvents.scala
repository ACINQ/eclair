package fr.acinq.eclair.payment

import akka.actor.ActorRef
import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 01/02/2017.
  */
class PaymentEvent

case class PaymentSent(channel: ActorRef, h: BinaryData) extends PaymentEvent

case class PaymentFailed(channel: ActorRef, h: BinaryData, reason: String) extends PaymentEvent

case class PaymentReceived(channel: ActorRef, h: BinaryData) extends PaymentEvent
