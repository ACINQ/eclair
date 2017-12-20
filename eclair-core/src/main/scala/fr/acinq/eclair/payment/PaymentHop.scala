package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.wire.ChannelUpdate


object PaymentHop {
  /**
    *
    * @param baseMsat     fixed fee
    * @param proportional proportional fee
    * @param msat         amount in millisatoshi
    * @return the fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
    */
  def nodeFee(baseMsat: Long, proportional: Long, msat: Long): Long = baseMsat + (proportional * msat) / 1000000

  /**
    *
    * @param reversePath sequence of Hops from recipient to a start of assisted path
    * @param msat        an amount to send to a payment recipient
    * @return a sequence of extra hops with a pre-calculated fee for a given msat amount
    */
  def buildExtra(reversePath: Seq[Hop], msat: Long): Seq[ExtraHop] = reversePath.foldLeft(List.empty[ExtraHop]) {
    case (Nil, hop) => ExtraHop(hop.nodeId, hop.shortChannelId, hop.feeBaseMsat, hop.feeProportionalMillionths, hop.cltvExpiryDelta) :: Nil
    case (head :: rest, hop) => ExtraHop(hop.nodeId, hop.shortChannelId, hop.feeBaseMsat, hop.feeProportionalMillionths, hop.cltvExpiryDelta) :: head :: rest
  }
}

trait PaymentHop {
  def nextFee(msat: Long): Long

  def shortChannelId: Long

  def cltvExpiryDelta: Int

  def nodeId: PublicKey
}

case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate) extends PaymentHop {
  def nextFee(msat: Long): Long = PaymentHop.nodeFee(lastUpdate.feeBaseMsat, lastUpdate.feeProportionalMillionths, msat)

  def feeBaseMsat: Long = lastUpdate.feeBaseMsat

  def feeProportionalMillionths: Long = lastUpdate.feeProportionalMillionths

  def cltvExpiryDelta: Int = lastUpdate.cltvExpiryDelta

  def shortChannelId: Long = lastUpdate.shortChannelId
}