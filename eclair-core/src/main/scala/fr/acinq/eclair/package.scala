/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, _}
import scodec.Attempt
import scodec.bits.BitVector

import scala.util.{Failure, Success, Try}

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
    * @param feeratePerByte fee rate in satoshi-per-bytes
    * @return feerate in satoshi-per-kw
    */
  def feerateByte2Kw(feeratePerByte: Long): Long = feerateKB2Kw(feeratePerByte * 1000)

  /**
    *
    * @param feeratesPerKw fee rate in satoshi-per-kw
    * @return fee rate in satoshi-per-byte
    */
  def feerateKw2Byte(feeratesPerKw: Long): Long = feeratesPerKw / 250

  /*
    why 253 and not 250 since feerate-per-kw is feerate-per-kb / 250 and the minimum relay fee rate is 1000 satoshi/Kb ?

    because bitcoin core uses neither the actual tx size in bytes or the tx weight to check fees, but a "virtual size"
    which is (3 * weight) / 4 ...
    so we want :
    fee > 1000 * virtual size
    feerate-per-kw * weight > 1000 * (3 * weight / 4)
    feerate_per-kw > 250 + 3000 / (4 * weight)
    with a conservative minimum weight of 400, we get a minimum feerate_per-kw of 253

    see https://github.com/ElementsProject/lightning/pull/1251
   */
  val MinimumFeeratePerKw = 253

  /*
    minimum relay fee rate, in satoshi per kilo
    bitcoin core uses virtual size and not the actual size in bytes, see above
   */
  val MinimumRelayFeeRate = 1000

  /**
    * Converts feerate in satoshi-per-kilobytes to feerate in satoshi-per-kw
    *
    * @param feeratePerKB fee rate in satoshi-per-kilobytes
    * @return feerate in satoshi-per-kw
    */
  def feerateKB2Kw(feeratePerKB: Long): Long = Math.max(feeratePerKB / 4, MinimumFeeratePerKw)

  /**
    *
    * @param feeratesPerKw fee rate in satoshi-per-kw
    * @return fee rate in satoshi-per-kilobyte
    */
  def feerateKw2KB(feeratesPerKw: Long): Long = feeratesPerKw * 4


  def isPay2PubkeyHash(address: String): Boolean = address.startsWith("1") || address.startsWith("m") || address.startsWith("n")

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

  /**
    *
    * @param address   base58 of bech32 address
    * @param chainHash hash of the chain we're on, which will be checked against the input address
    * @return the public key script that matches the input address.
    */

  def addressToPublicKeyScript(address: String, chainHash: BinaryData): Seq[ScriptElt] = {
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
}