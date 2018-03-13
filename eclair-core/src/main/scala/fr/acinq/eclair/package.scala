package fr.acinq

import java.security.SecureRandom

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, _}
import scodec.Attempt
import scodec.bits.BitVector

package object eclair {

  /**
    * We are using 'new SecureRandom()' instead of 'SecureRandom.getInstanceStrong()' because the latter can hang on Linux
    * See http://bugs.java.com/view_bug.do?bug_id=6521844 and https://tersesystems.com/2015/12/17/the-right-way-to-use-securerandom/
    */
  val secureRandom = new SecureRandom()

  def randomBytes(length: Int): BinaryData = {
    val buffer = new Array[Byte](length)
    secureRandom.nextBytes(buffer)
    buffer
  }

  def randomKey: PrivateKey = PrivateKey(randomBytes(32), compressed = true)

  def toLongId(fundingTxHash: BinaryData, fundingOutputIndex: Int): BinaryData = {
    require(fundingOutputIndex < 65536, "fundingOutputIndex must not be greater than FFFF")
    require(fundingTxHash.size == 32, "fundingTxHash must be of length 32B")
    val channelId = fundingTxHash.take(30) :+ (fundingTxHash.data(30) ^ (fundingOutputIndex >> 8)).toByte :+ (fundingTxHash.data(31) ^ fundingOutputIndex).toByte
    BinaryData(channelId)
  }

  def serializationResult(attempt: Attempt[BitVector]): BinaryData = attempt match {
    case Attempt.Successful(bin) => BinaryData(bin.toByteArray)
    case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
  }

  /**
    * Converts feerate in satoshi-per-bytes to feerate in satoshi-per-kw
    *
    * @param feeratePerByte feerate in satoshi-per-bytes
    * @return feerate in satoshi-per-kw
    */
  def feerateByte2Kw(feeratePerByte: Long): Long = feeratePerByte * 1024 / 4


  /**
    *
    * @param address bitcoin Base58 address
    * @return true if the address is a segwit address i.e. a p2sh-of-p2wpkh address.
    *         We approximate this be returning true if the address is a p2sh address, there is no
    *         way to tell what the script is.
    */
  def isSegwitAddress(address: String) : Boolean = address.startsWith("2") || address.startsWith("3")

  /**
    * Tests whether the binary data is composed solely of printable ASCII characters (see BOLT 1)
    *
    * @param data to check
    */
  def isAsciiPrintable(data: BinaryData): Boolean = data.data.forall(ch => ch >= 32 && ch < 127)

  /**
    *
    * @param baseMsat     fixed fee
    * @param proportional proportional fee
    * @param msat         amount in millisatoshi
    * @return the fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
    */
  def nodeFee(baseMsat: Long, proportional: Long, msat: Long): Long = baseMsat + (proportional * msat) / 1000000
}