/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq

import java.security.SecureRandom
import fr.acinq.bitcoin.scala.Crypto.PrivateKey
import fr.acinq.bitcoin.scala._
import scodec.Attempt
import scodec.bits.{BitVector, ByteVector}
import scala.util.{Failure, Success, Try}

package object eclair {

  /**
   * We are using 'new SecureRandom()' instead of 'SecureRandom.getInstanceStrong()' because the latter can hang on Linux
   * See http://bugs.java.com/view_bug.do?bug_id=6521844 and https://tersesystems.com/2015/12/17/the-right-way-to-use-securerandom/
   */
  val secureRandom = new SecureRandom()

  def randomBytes(length: Int): ByteVector = {
    val buffer = new Array[Byte](length)
    secureRandom.nextBytes(buffer)
    ByteVector.view(buffer)
  }

  def randomBytes32: ByteVector32 = ByteVector32(randomBytes(32))

  def randomBytes64: ByteVector64 = ByteVector64(randomBytes(64))

  def randomKey: PrivateKey = PrivateKey(randomBytes32)

  def toLongId(fundingTxHash: ByteVector32, fundingOutputIndex: Int): ByteVector32 = {
    require(fundingOutputIndex < 65536, "fundingOutputIndex must not be greater than FFFF")
    require(fundingTxHash.size == 32, "fundingTxHash must be of length 32B")
    val channelId = ByteVector32(fundingTxHash.take(30) :+ (fundingTxHash(30) ^ (fundingOutputIndex >> 8)).toByte :+ (fundingTxHash(31) ^ fundingOutputIndex).toByte)
    channelId
  }

  def serializationResult(attempt: Attempt[BitVector]): ByteVector = attempt match {
    case Attempt.Successful(bin) => bin.toByteVector
    case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
  }

  /**
   * Converts fee rate in satoshi-per-bytes to fee rate in satoshi-per-kw
   *
   * @param feeratePerByte fee rate in satoshi-per-bytes
   * @return fee rate in satoshi-per-kw
   */
  def feerateByte2Kw(feeratePerByte: Long): Long = feerateKB2Kw(feeratePerByte * 1000)

  /**
   * Converts fee rate in satoshi-per-kw to fee rate in satoshi-per-byte
   *
   * @param feeratePerKw fee rate in satoshi-per-kw
   * @return fee rate in satoshi-per-byte
   */
  def feerateKw2Byte(feeratePerKw: Long): Long = feeratePerKw / 250

  /**
   * why 253 and not 250 since feerate-per-kw is feerate-per-kb / 250 and the minimum relay fee rate is 1000 satoshi/Kb ?
   *
   * because bitcoin core uses neither the actual tx size in bytes or the tx weight to check fees, but a "virtual size"
   * which is (3 * weight) / 4 ...
   * so we want :
   * fee > 1000 * virtual size
   * feerate-per-kw * weight > 1000 * (3 * weight / 4)
   * feerate_per-kw > 250 + 3000 / (4 * weight)
   * with a conservative minimum weight of 400, we get a minimum feerate_per-kw of 253
   *
   * see https://github.com/ElementsProject/lightning/pull/1251
   * */
  val MinimumFeeratePerKw = 253

  /**
   * minimum relay fee rate, in satoshi per kilo
   * bitcoin core uses virtual size and not the actual size in bytes, see above
   * */
  val MinimumRelayFeeRate = 1000

  /**
   * Converts fee rate in satoshi-per-kilobytes to fee rate in satoshi-per-kw
   *
   * @param feeratePerKB fee rate in satoshi-per-kilobytes
   * @return fee rate in satoshi-per-kw
   */
  def feerateKB2Kw(feeratePerKB: Long): Long = Math.max(feeratePerKB / 4, MinimumFeeratePerKw)

  /**
   * Converts fee rate in satoshi-per-kw to fee rate in satoshi-per-kilobyte
   *
   * @param feeratePerKw fee rate in satoshi-per-kw
   * @return fee rate in satoshi-per-kilobyte
   */
  def feerateKw2KB(feeratePerKw: Long): Long = feeratePerKw * 4

  def isPay2PubkeyHash(address: String): Boolean = address.startsWith("1") || address.startsWith("m") || address.startsWith("n")

  /**
   * Tests whether the binary data is composed solely of printable ASCII characters (see BOLT 1)
   *
   * @param data to check
   */
  def isAsciiPrintable(data: ByteVector): Boolean = data.toArray.forall(ch => ch >= 32 && ch < 127)

  /**
   * @param baseFee         fixed fee
   * @param proportionalFee proportional fee (millionths)
   * @param paymentAmount   payment amount in millisatoshi
   * @return the fee that a node should be paid to forward an HTLC of 'paymentAmount' millisatoshis
   */
  def nodeFee(baseFee: MilliSatoshi, proportionalFee: Long, paymentAmount: MilliSatoshi): MilliSatoshi = baseFee + (paymentAmount * proportionalFee) / 1000000

  /**
   * @param address   base58 of bech32 address
   * @param chainHash hash of the chain we're on, which will be checked against the input address
   * @return the public key script that matches the input address.
   */
  def addressToPublicKeyScript(address: String, chainHash: ByteVector32): Seq[ScriptElt] = {
    Try(Base58Check.decode(address)) match {
      case Success((Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)) if chainHash == Block.TestnetGenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash => Script.pay2pkh(pubKeyHash)
      case Success((Base58.Prefix.PubkeyAddress, pubKeyHash)) if chainHash == Block.LivenetGenesisBlock.hash => Script.pay2pkh(pubKeyHash)
      case Success((Base58.Prefix.ScriptAddressTestnet, scriptHash)) if chainHash == Block.TestnetGenesisBlock.hash || chainHash == Block.RegtestGenesisBlock.hash => OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil
      case Success((Base58.Prefix.ScriptAddress, scriptHash)) if chainHash == Block.LivenetGenesisBlock.hash => OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil
      case Success(_) => throw new IllegalArgumentException("base58 address does not match our blockchain")
      case Failure(base58error) =>
        Try(Bech32.decodeWitnessAddress(address)) match {
          case Success((_, version, _)) if version != 0.toByte => throw new IllegalArgumentException(s"invalid version $version in bech32 address")
          case Success((_, _, bin)) if bin.length != 20 && bin.length != 32 => throw new IllegalArgumentException("hash length in bech32 address must be either 20 or 32 bytes")
          case Success(("bc", _, bin)) if chainHash == Block.LivenetGenesisBlock.hash => OP_0 :: OP_PUSHDATA(bin) :: Nil
          case Success(("tb", _, bin)) if chainHash == Block.TestnetGenesisBlock.hash => OP_0 :: OP_PUSHDATA(bin) :: Nil
          case Success(("bcrt", _, bin)) if chainHash == Block.RegtestGenesisBlock.hash => OP_0 :: OP_PUSHDATA(bin) :: Nil
          case Success(_) => throw new IllegalArgumentException("bech32 address does not match our blockchain")
          case Failure(bech32error) => throw new IllegalArgumentException(s"$address is neither a valid Base58 address ($base58error) nor a valid Bech32 address ($bech32error)")
        }
    }
  }

  def addressFromPublicKeyScript(chainHash: ByteVector32, pubkeyScript: List[ScriptElt]): String = {
    val p2pkhPrefix = chainHash match {
      case Block.LivenetGenesisBlock.hash => Base58.Prefix.PubkeyAddress
      case Block.TestnetGenesisBlock.hash | Block.RegtestGenesisBlock.hash => Base58.Prefix.PubkeyAddressTestnet
    }
    val p2shPrefix = chainHash match {
      case Block.LivenetGenesisBlock.hash => Base58.Prefix.ScriptAddress
      case Block.TestnetGenesisBlock.hash | Block.RegtestGenesisBlock.hash => Base58.Prefix.ScriptAddressTestnet
    }
    val hrp = chainHash match {
      case Block.TestnetGenesisBlock.hash => "tb"
      case Block.RegtestGenesisBlock.hash => "bcrt"
      case Block.LivenetGenesisBlock.hash => "bc"
    }
    pubkeyScript match {
      case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil if pubkeyHash.size == 20 =>
        Base58Check.encode(p2pkhPrefix, pubkeyHash)
      case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil if scriptHash.size == 20 =>
        Base58Check.encode(p2shPrefix, scriptHash)
      case op :: OP_PUSHDATA(witnessProgram, _) :: Nil if witnessProgram.length >= 2 && witnessProgram.length <= 40 =>
        op match {
          case OP_0 =>
            require(witnessProgram.length == 20 || witnessProgram.length == 32, "witness v0 program length must be 20 or 32")
            Bech32.encodeWitnessAddress(hrp, 0, witnessProgram)
          case OP_1 => Bech32.encodeWitnessAddress(hrp, 1, witnessProgram)
          case OP_2 => Bech32.encodeWitnessAddress(hrp, 2, witnessProgram)
          case OP_3 => Bech32.encodeWitnessAddress(hrp, 3, witnessProgram)
          case OP_4 => Bech32.encodeWitnessAddress(hrp, 4, witnessProgram)
          case OP_5 => Bech32.encodeWitnessAddress(hrp, 5, witnessProgram)
          case OP_6 => Bech32.encodeWitnessAddress(hrp, 6, witnessProgram)
          case OP_7 => Bech32.encodeWitnessAddress(hrp, 7, witnessProgram)
          case OP_8 => Bech32.encodeWitnessAddress(hrp, 8, witnessProgram)
          case OP_9 => Bech32.encodeWitnessAddress(hrp, 9, witnessProgram)
          case OP_10 => Bech32.encodeWitnessAddress(hrp, 10, witnessProgram)
          case OP_11 => Bech32.encodeWitnessAddress(hrp, 11, witnessProgram)
          case OP_12 => Bech32.encodeWitnessAddress(hrp, 12, witnessProgram)
          case OP_13 => Bech32.encodeWitnessAddress(hrp, 13, witnessProgram)
          case OP_14 => Bech32.encodeWitnessAddress(hrp, 14, witnessProgram)
          case OP_15 => Bech32.encodeWitnessAddress(hrp, 15, witnessProgram)
          case OP_16 => Bech32.encodeWitnessAddress(hrp, 16, witnessProgram)
        }
    }
  }

  def addressFromPublicKeyScript(chainHash: ByteVector32, pubkeyScript: ByteVector): String = addressFromPublicKeyScript(chainHash, Script.parse(pubkeyScript))

  implicit class LongToBtcAmount(l: Long) {
    // @formatter:off
    def msat: MilliSatoshi = MilliSatoshi(l)
    def sat: Satoshi = Satoshi(l)
    def mbtc: MilliBtc = MilliBtc(l)
    def btc: Btc = Btc(l)
    // @formatter:on
  }

  // We implement Numeric to take advantage of operations such as sum, sort or min/max on iterables.
  implicit object NumericMilliSatoshi extends Numeric[MilliSatoshi] {
    // @formatter:off
    override def plus(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = x + y
    override def minus(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = x - y
    override def times(x: MilliSatoshi, y: MilliSatoshi): MilliSatoshi = MilliSatoshi(x.toLong * y.toLong)
    override def negate(x: MilliSatoshi): MilliSatoshi = -x
    override def fromInt(x: Int): MilliSatoshi = MilliSatoshi(x)
    override def toInt(x: MilliSatoshi): Int = x.toLong.toInt
    override def toLong(x: MilliSatoshi): Long = x.toLong
    override def toFloat(x: MilliSatoshi): Float = x.toLong
    override def toDouble(x: MilliSatoshi): Double = x.toLong
    override def compare(x: MilliSatoshi, y: MilliSatoshi): Int = x.compare(y)
    //override def parseString(str: String): Option[MilliSatoshi] = ???
    // @formatter:on
  }

  implicit class ToMilliSatoshiConversion(amount: BtcAmount) {
    // @formatter:off
    def toMilliSatoshi: MilliSatoshi = MilliSatoshi.toMilliSatoshi(amount)
    def +(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi + other
    def -(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi - other
    // @formatter:on
  }

  /**
   * Apparently .getClass.getSimpleName can crash java 8 with a "Malformed class name" error
   */
  def getSimpleClassName(o: Any): String = o.getClass.getName.split("\\$").last

}