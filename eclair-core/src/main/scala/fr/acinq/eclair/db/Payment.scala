package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData

/**
  * Payment object stored in DB.
  *
  * @param payment_hash identifier of the payment
  * @param amount_msat amount of the payment, in milli-satoshis
  * @param timestamp absolute time in seconds since UNIX epoch when the payment was created.
  */
case class Payment(payment_hash: BinaryData, amount_msat: Long, timestamp: Long)