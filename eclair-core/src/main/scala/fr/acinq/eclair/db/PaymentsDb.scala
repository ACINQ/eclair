package fr.acinq.eclair.db

import fr.acinq.bitcoin.BinaryData

/**
  * Store the Lightning payments received by the node. Sent and relayed payments are not persisted.
  * <p>
  * A payment is a [[Payment]] object. In the local context of a LN node, it is safe to consider that
  * a payment is uniquely identified by its payment hash. As such, implementations of this database can use the payment
  * hash as a unique key and index.
  * <p>
  * Basic operations on this DB are:
  * <ul>
  * <li>insertion
  * <li>find by payment hash
  * <li>list all
  * </ul>
  * Payments should not be updated nor deleted.
  */
trait PaymentsDb {

  def addPayment(payment: Payment)

  @throws(classOf[NoSuchElementException])
  def findByPaymentHash(paymentHash: BinaryData): Payment

  def listPayments(): List[Payment]

}
