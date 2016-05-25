package fr.acinq.eclair.channel

import com.trueaccord.scalapb.GeneratedMessage
import fr.acinq.bitcoin.BinaryData
import lightning._

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

sealed trait Direction
case object IN extends Direction
case object OUT extends Direction

final case class Change2(direction: Direction, ack: Long, msg: GeneratedMessage)

//case class Htlc2(id: Long, amountMsat: Int, rHash: sha256_hash, expiry: locktime, nextNodeIds: Seq[String] = Nil, val previousChannelId: Option[BinaryData])

case class Htlc(direction: Direction, id: Long, amountMsat: Int, rHash: sha256_hash, expiry: locktime, nextNodeIds: Seq[String] = Nil, val previousChannelId: Option[BinaryData])

// @formatter:on

case class ChannelOneSide(pay_msat: Long, fee_msat: Long) {
  val funds = pay_msat + fee_msat
}

case class ChannelState(us: ChannelOneSide, them: ChannelOneSide, feeRate: Long) {
  def reverse = this.copy(us = them, them = us)
}

object ChannelState {
  /**
    *
    * A node MUST use the formula 338 + 32 bytes for every non-dust HTLC as the bytecount for calculating commitment
    * transaction fees. Note that the fee requirement is unchanged, even if the elimination of dust HTLC outputs
    * has caused a non-zero fee already.
    * The fee for a transaction MUST be calculated by multiplying this bytecount by the fee rate, dividing by 1000
    * and truncating (rounding down) the result to an even number of satoshis.
    *
    * @param feeRate       fee rate in Satoshi/Kb
    * @param numberOfHtlcs number of (non-dust) HTLCs to be included in the commit tx
    * @return the fee in Satoshis for a commit tx with 'numberOfHtlcs' HTLCs
    */
  def computeFee(feeRate: Long, numberOfHtlcs: Int) : Long = {
    Math.floorDiv((338 + 32 * numberOfHtlcs) * feeRate, 2000) * 2
  }

  /**
    *
    * @param anchorAmount anchor amount in Satoshis
    * @param feeRate fee rate in Satoshis/Kb
    * @return a ChannelState instance where 'us' is funder (i.e. provided the anchor).
    */
  def initialFunding(anchorAmount: Long, feeRate: Long) : ChannelState = {
    val fee = computeFee(feeRate, 0)
    val pay_msat = (anchorAmount - fee) * 1000
    val fee_msat = fee * 1000
    val us = ChannelOneSide(pay_msat, fee_msat)
    val them = ChannelOneSide(0, 0)
    ChannelState(us, them, feeRate)
  }

  def adjust_fees(funder: ChannelOneSide, nonfunder: ChannelOneSide, fee: Long): (ChannelOneSide, ChannelOneSide) = {
    val nonfunder_fee = Math.min(fee - fee / 2, nonfunder.funds)
    val funder_fee = fee - nonfunder_fee
    (funder.copy(pay_msat = funder.funds - funder_fee, fee_msat = funder_fee), nonfunder.copy(pay_msat = nonfunder.funds - nonfunder_fee, fee_msat = nonfunder_fee))
  }
}