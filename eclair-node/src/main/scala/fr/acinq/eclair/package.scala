package fr.acinq

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, _}
import scodec.Attempt
import scodec.bits.BitVector

import scala.util.Random

package object eclair {

  def toLongId(fundingTxHash: BinaryData, fundingOutputIndex: Int): BinaryData = {
    require(fundingOutputIndex < 65536, "fundingOutputIndex must not be greater than FFFF")
    require(fundingTxHash.size == 32, "fundingTxHash must be of length 32B")
    val channelId = fundingTxHash.take(30) :+ (fundingTxHash.data(30) ^ (fundingOutputIndex >> 8)).toByte :+ (fundingTxHash.data(31) ^ fundingOutputIndex).toByte
    BinaryData(channelId)
  }

  /**
    * Creates a unique index assigned to a channel (== an unspent multisig 2-of-2 output)
    * @param blockHeight
    * @param txIndex
    * @param outputIndex
    * @return channelId
    */
  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)

  /**
    *
    * @param id
    * @return (blockHeight, txIndex, outputIndex)
    */
  def fromShortId(id: Long): (Int, Int, Int) =
    (((id >> 40) & 0xFFFFFF).toInt, ((id >> 16) & 0xFFFFFF).toInt, (id & 0xFFFF).toInt)

  def randomKey: PrivateKey = PrivateKey({
    val bin = Array.fill[Byte](32)(0)
    // TODO: use secure random
    Random.nextBytes(bin)
    bin
  }, compressed = true)

  def serializationResult(attempt: Attempt[BitVector]): BinaryData = attempt match {
    case Attempt.Successful(bin) => BinaryData(bin.toByteArray)
    case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
  }

}