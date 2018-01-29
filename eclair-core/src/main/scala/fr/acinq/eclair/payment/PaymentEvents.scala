package fr.acinq.eclair.payment

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}

/**
  * Created by PM on 01/02/2017.
  */
sealed trait PaymentEvent {
  val paymentHash: BinaryData
}

case class PaymentSent(amount: MilliSatoshi, feesPaid: MilliSatoshi, paymentHash: BinaryData, paymentPreimage: BinaryData) extends PaymentEvent

case class PaymentRelayed(amountIn: MilliSatoshi, amountOut: MilliSatoshi, paymentHash: BinaryData) extends PaymentEvent

case class PaymentReceived(amount: MilliSatoshi, paymentHash: BinaryData) extends PaymentEvent
