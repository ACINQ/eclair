package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.bitcoin.Protocol
import java.nio.ByteOrder


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
    * @param msat an amount to send to a payment recipient
    * @return a sequence of extra hops with a pre-calculated fee for a given msat amount
    */
  def buildExtra(reversePath: Seq[Hop], msat: Long): Seq[ExtraHop] = (List.empty[ExtraHop] /: reversePath) {
    case (Nil, hop) => ExtraHop(hop.nextNodeId, hop.shortChannelId, hop.nextFee(msat), hop.cltvExpiryDelta) :: Nil
    case (head :: rest, hop) => ExtraHop(hop.nextNodeId, hop.shortChannelId, hop.nextFee(msat + head.fee), hop.cltvExpiryDelta) :: head :: rest
  }
}

trait PaymentHop {
  def nextFee(msat: Long): Long
  def shortChannelId: Long
  def cltvExpiryDelta: Int
  def nodeId: PublicKey
}

/**
  * Extra hop contained in RoutingInfoTag
  *
  * @param nodeId          node id
  * @param shortChannelId  channel id
  * @param fee             node fee
  * @param cltvExpiryDelta node cltv expiry delta
  */
case class ExtraHop(nodeId: PublicKey, shortChannelId: Long, fee: Long, cltvExpiryDelta: Int) extends PaymentHop {
  def pack: Seq[Byte] = nodeId.toBin ++ Protocol.writeUInt64(shortChannelId, ByteOrder.BIG_ENDIAN) ++
    Protocol.writeUInt64(fee, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(cltvExpiryDelta, ByteOrder.BIG_ENDIAN)

  // Fee is already pre-calculated for extra hops
  def nextFee(msat: Long): Long = fee
}

case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate) extends PaymentHop {
  def nextFee(msat: Long): Long = PaymentHop.nodeFee(lastUpdate.feeBaseMsat, lastUpdate.feeProportionalMillionths, msat)
  def cltvExpiryDelta: Int = lastUpdate.cltvExpiryDelta
  def shortChannelId: Long = lastUpdate.shortChannelId
}