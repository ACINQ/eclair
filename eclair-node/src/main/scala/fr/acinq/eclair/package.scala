package fr.acinq

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin._

import scala.util.Random

package object eclair {

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)

  def fromShortId(id: Long): (Int, Int, Int) =
    (((id >> 40) & 0xFFFFFF).toInt, ((id >> 16) & 0xFFFFFF).toInt, (id & 0xFFFF).toInt)

  def randomKey: PrivateKey = PrivateKey({
    val bin = Array.fill[Byte](32)(0)
    // TODO: use secure random
    Random.nextBytes(bin)
    bin
  }, compressed = true)

}