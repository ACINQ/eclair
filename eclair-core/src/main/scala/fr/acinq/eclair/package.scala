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

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.{ImportMultiItem, WatchAddressItem}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.{DATA_CLOSING, DATA_NEGOTIATING, DATA_NORMAL, DATA_SHUTDOWN, DATA_WAIT_FOR_FUNDING_CONFIRMED, DATA_WAIT_FOR_FUNDING_LOCKED, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT, HasCommitments}
import fr.acinq.eclair.db.Databases
import grizzled.slf4j.Logger
import scodec.Attempt
import scodec.bits.{BitVector, ByteVector}

import scala.concurrent.{ExecutionContext, Future}
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
   **/
  val MinimumFeeratePerKw = 253

  /**
   * minimum relay fee rate, in satoshi per kilo
   * bitcoin core uses virtual size and not the actual size in bytes, see above
   **/
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

  /**
    * Converts a bitcoin script from a transaction output into its address representation,
    * supports P2PKH, P2PSH, P2WPKH and P2WSH
    *
    * @param scriptPubKey a bitcoin script
    * @return the bitcoin address of this scriptPubKey
    */
  def scriptPubKeyToAddress(scriptPubKey: ByteVector) = Script.parse(scriptPubKey) match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
      Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)
    case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, scriptHash)
    case OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil if pubKeyHash.length == 20 => Bech32.encodeWitnessAddress("bcrt", 0, pubKeyHash)
    case OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil if scriptHash.length == 32 => Bech32.encodeWitnessAddress("bcrt", 0, scriptHash)
    case _ => throw new IllegalArgumentException(s"unknown script type script=$scriptPubKey")
  }


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
    // @formatter:on
  }

  implicit class ToMilliSatoshiConversion(amount: BtcAmount) {
    // @formatter:off
    def toMilliSatoshi: MilliSatoshi = MilliSatoshi.toMilliSatoshi(amount)
    def +(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi + other
    def -(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi - other
    // @formatter:on
  }

  // returns all watch addresses with label IMPORTED
  def listImported(bitcoinClient: ExtendedBitcoinClient)(implicit ec: ExecutionContext): Future[List[WatchAddressItem]] = {
    bitcoinClient.listReceivedByAddress(minConf = 1, includeEmpty = true, includeWatchOnly = true).map(_.filter(_.label == "IMPORTED"))
  }

  // extracts rescan info from each channel data
  def getRescanInfo(channel: HasCommitments) = channel match {
    case DATA_NORMAL(_, shortChannelId, _, _, _, _, _)             => Some(Left(shortChannelId))
    case DATA_WAIT_FOR_FUNDING_CONFIRMED(_, _, waitingSince, _, _) => Some(Right(waitingSince))
    case DATA_WAIT_FOR_FUNDING_LOCKED(_, shortChannelId, _)        => Some(Left(shortChannelId))
    case DATA_SHUTDOWN(_, _, _)                                    => None
    case DATA_NEGOTIATING(_, _, _, _, _)                           => None
    case DATA_CLOSING(_, _, _, _, _, _, _, _, _, _)                => None
    case DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(_, _)      => None
  }

  // converts the optional short channel id into timestamp, returns elements ready to be imported with 'importMulti'
  def addressToImportMultiItem(elem: (Option[Either[ShortChannelId, Long]], String), bitcoinClient: ExtendedBitcoinClient)(implicit ec: ExecutionContext): Future[ImportMultiItem] = {
    elem match {
      case (Some(Left(shortChannelId)), address) =>
        bitcoinClient.getBlock(ShortChannelId.coordinates(shortChannelId).blockHeight)
          .map(_.header.time) // compute block time from block height
          .map { timestamp =>
          ImportMultiItem(address, "PENDING", Some(timestamp))
        }
      case (optWaitingSince, address) =>
        Future.successful(ImportMultiItem(address, "PENDING", optWaitingSince.map(_.right.get)))

    }
  }

  /**
    * Reconciliation function to import in the bitcoind wallet any missing address from our channels.
    */
  def reconcileWatchAddresses(bitcoinClient: ExtendedBitcoinClient, databases: Databases)(implicit ec: ExecutionContext, logger: Logger): Future[Unit] = {
    // lookup existing imported addresses
    listImported(bitcoinClient).flatMap { importedAddresses =>
        logger.info(s"found ${importedAddresses.size} addresses already IMPORTED")
        val addressesWithInfo = databases.channels.listLocalChannels()
          .map( data => (data, eclair.scriptPubKeyToAddress(data.commitments.commitInput.txOut.publicKeyScript))) // compute the address for each channel
          .filterNot( el => importedAddresses.exists(_.address == el._2))                                         // discard already imported addresses
          .map { case (channel, address) => (getRescanInfo(channel), address) }                                   // get rescan info for each address

        // convert rescan info into timestamp and each element in ImportMultiItem
        val importMultiInputF = Future.sequence(addressesWithInfo.map(addressToImportMultiItem(_, bitcoinClient)))

        for {
          imInput <- importMultiInputF
          _ = logger.info(s"importing ${imInput.size} addresses")
          imported <- bitcoinClient.importMulti(imInput, rescan = true)
          _ = if(!imported) throw new IllegalStateException("could not import all addresses")
          _ <- Future.sequence(imInput.map(el => bitcoinClient.setLabel(el.address, "IMPORTED")))
          _ = logger.info(s"done importing")
        } yield ()
    }
  }
}