package fr.acinq.eclair.payment

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}

/**
  * Created by PM on 01/02/2017.
  */
class PaymentEvent

case class PaymentSent(amount: MilliSatoshi, feesPaid: MilliSatoshi, paymentHash: BinaryData) extends PaymentEvent

case class PaymentRelayed(amount: MilliSatoshi, feesEarned: MilliSatoshi, paymentHash: BinaryData) extends PaymentEvent

case class PaymentReceived(amount: MilliSatoshi, paymentHash: BinaryData) extends PaymentEvent
