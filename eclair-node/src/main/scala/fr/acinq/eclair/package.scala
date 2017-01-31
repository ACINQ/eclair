package fr.acinq

import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin._
import lightning.bitcoin_pubkey

import scala.util.Random

package object eclair {

  implicit def bin2pubkey(in: BinaryData) = bitcoin_pubkey(ByteString.copyFrom(in))

  implicit def array2pubkey(in: Array[Byte]) = bin2pubkey(in)

  implicit def pubkey2bin(in: bitcoin_pubkey): BinaryData = in.key.toByteArray

  implicit def bytestring2bin(in: ByteString): BinaryData = in.toByteArray

  implicit def bin2bytestring(in: BinaryData): ByteString = ByteString.copyFrom(in)

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