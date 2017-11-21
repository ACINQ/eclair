package fr.acinq.eclair.blockchain.fee

import fr.acinq.eclair.feerateByte2Kw

import scala.concurrent.Future

/**
  * Created by PM on 09/07/2017.
  */
trait FeeProvider {

  def getFeerates: Future[FeeratesPerByte]

}

case class FeeratesPerByte(block_1: Long, blocks_2: Long, blocks_6: Long, blocks_12: Long, blocks_36: Long, blocks_72: Long)

case class FeeratesPerKw(block_1: Long, blocks_2: Long, blocks_6: Long, blocks_12: Long, blocks_36: Long, blocks_72: Long)

object FeeratesPerKw {
  def apply(feerates: FeeratesPerByte): FeeratesPerKw = FeeratesPerKw(
    block_1 = feerateByte2Kw(feerates.block_1),
    blocks_2 = feerateByte2Kw(feerates.blocks_2),
    blocks_6 = feerateByte2Kw(feerates.blocks_6),
    blocks_12 = feerateByte2Kw(feerates.blocks_12),
    blocks_36 = feerateByte2Kw(feerates.blocks_36),
    blocks_72 = feerateByte2Kw(feerates.blocks_72))

  /**
    * Used in tests
    *
    * @param feeratePerKw
    * @return
    */
  def single(feeratePerKw: Long): FeeratesPerKw = FeeratesPerKw(
    block_1 = feeratePerKw,
    blocks_2 = feeratePerKw,
    blocks_6 = feeratePerKw,
    blocks_12 = feeratePerKw,
    blocks_36 = feeratePerKw,
    blocks_72 = feeratePerKw)
}
