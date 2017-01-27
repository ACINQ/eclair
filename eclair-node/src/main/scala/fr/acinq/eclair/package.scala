package fr.acinq

import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import lightning.bitcoin_pubkey

package object eclair {

  implicit def bin2pubkey(in: BinaryData) = bitcoin_pubkey(ByteString.copyFrom(in))

  implicit def array2pubkey(in: Array[Byte]) = bin2pubkey(in)

  implicit def pubkey2bin(in: bitcoin_pubkey): BinaryData = in.key.toByteArray

  implicit def bytestring2bin(in: ByteString): BinaryData = in.toByteArray

  implicit def bin2bytestring(in: BinaryData): ByteString = ByteString.copyFrom(in)

  /**
    *
    * @param base         fixed fee
    * @param proportional proportional fee
    * @param msat         amount in millisatoshi
    * @return the fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
    */
  def nodeFee(base: Long, proportional: Long, msat: Long): Long = base + (proportional * msat) / 1000000

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)

  def fromShortId(id: Long): (Int, Int, Int) =
    (((id >> 40) & 0xFFFFFF).toInt, ((id >> 16) & 0xFFFFFF).toInt, (id & 0xFFFF).toInt)

}