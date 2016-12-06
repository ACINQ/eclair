package fr.acinq

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.math.BigInteger

import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import lightning.{bitcoin_pubkey, signature}

import scala.annotation.tailrec

package object eclair {

  implicit def bin2pubkey(in: BinaryData) = bitcoin_pubkey(ByteString.copyFrom(in))

  implicit def array2pubkey(in: Array[Byte]) = bin2pubkey(in)

  implicit def pubkey2bin(in: bitcoin_pubkey): BinaryData = in.key.toByteArray

  private def fixSize(in: Array[Byte]): Array[Byte] = in.size match {
    case 32 => in
    case s if s < 32 => Array.fill(32 - s)(0: Byte) ++ in
    case s if s > 32 => in.takeRight(32)
  }

  implicit def bin2signature(in: BinaryData): signature = {
    val (r, s) = Crypto.decodeSignature(in)
    val (ar, as) = (r.toByteArray, s.toByteArray)
    val (ar1, as1) = (fixSize(ar), fixSize(as))
    val (rbis, sbis) = (new ByteArrayInputStream(ar1), new ByteArrayInputStream(as1))
    signature(Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(rbis), Protocol.uint64(sbis), Protocol.uint64(sbis), Protocol.uint64(sbis), Protocol.uint64(sbis))
  }

  implicit def array2signature(in: Array[Byte]): signature = bin2signature(in)

  implicit def signature2bin(in: signature): BinaryData = {
    val rbos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.r1, rbos)
    Protocol.writeUInt64(in.r2, rbos)
    Protocol.writeUInt64(in.r3, rbos)
    Protocol.writeUInt64(in.r4, rbos)
    val r = new BigInteger(1, rbos.toByteArray)
    val sbos = new ByteArrayOutputStream()
    Protocol.writeUInt64(in.s1, sbos)
    Protocol.writeUInt64(in.s2, sbos)
    Protocol.writeUInt64(in.s3, sbos)
    Protocol.writeUInt64(in.s4, sbos)
    val s = new BigInteger(1, sbos.toByteArray)
    Crypto.encodeSignature(r, s) :+ SIGHASH_ALL.toByte
  }

  implicit def bytestring2bin(in: ByteString): BinaryData = in.toByteArray

  implicit def bin2bytestring(in: BinaryData): ByteString = ByteString.copyFrom(in)

  @tailrec
  def memcmp(a: List[Byte], b: List[Byte]): Int = (a, b) match {
    case (x, y) if (x.length != y.length) => x.length - y.length
    case (Nil, Nil) => 0
    case (ha :: ta, hb :: tb) if ha == hb => memcmp(ta, tb)
    case (ha :: ta, hb :: tb) => (ha & 0xff) - (hb & 0xff)
  }

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
  def computeFee(feeRate: Long, numberOfHtlcs: Int): Long = {
    Math.floorDiv((338 + 32 * numberOfHtlcs) * feeRate, 2000) * 2
  }

  /**
    *
    * @param base         fixed fee
    * @param proportional proportional fee
    * @param msat         amount in millisatoshi
    * @return the fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
    */
  def nodeFee(base: Long, proportional: Long, msat: Long): Long = base + (proportional * msat) / 1000000
}