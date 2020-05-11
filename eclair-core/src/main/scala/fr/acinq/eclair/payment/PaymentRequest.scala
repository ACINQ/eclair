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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.payment.PaymentRequest._
import fr.acinq.eclair.{CltvExpiryDelta, FeatureSupport, Features, LongToBtcAmount, MilliSatoshi, ShortChannelId, randomBytes32}
import scodec.bits.{BitVector, ByteOrdering, ByteVector}
import scodec.codecs.{list, ubyte}
import scodec.{Codec, Err}

import scala.concurrent.duration._
import scala.util.Try

/**
 * Lightning Payment Request
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/11-payment-encoding.md
 *
 * @param prefix    currency prefix; lnbc for bitcoin, lntb for bitcoin testnet
 * @param amount    amount to pay (empty string means no amount is specified)
 * @param timestamp request timestamp (UNIX format)
 * @param nodeId    id of the node emitting the payment request
 * @param tags      payment tags; must include a single PaymentHash tag and a single PaymentSecret tag.
 * @param signature request signature that will be checked against node id
 */
case class PaymentRequest(prefix: String, amount: Option[MilliSatoshi], timestamp: Long, nodeId: PublicKey, tags: List[PaymentRequest.TaggedField], signature: ByteVector) {

  amount.foreach(a => require(a > 0.msat, s"amount is not valid"))
  require(tags.collect { case _: PaymentRequest.PaymentHash => }.size == 1, "there must be exactly one payment hash tag")
  require(tags.collect { case PaymentRequest.Description(_) | PaymentRequest.DescriptionHash(_) => }.size == 1, "there must be exactly one description tag or one description hash tag")
  private val featuresErr = Features.validateFeatureGraph(features.features)
  require(featuresErr.isEmpty, featuresErr.map(_.message))
  if (features.allowPaymentSecret) {
    require(tags.collect { case _: PaymentRequest.PaymentSecret => }.size == 1, "there must be exactly one payment secret tag when feature bit is set")
  }

  /**
   * @return the payment hash
   */
  lazy val paymentHash = tags.collectFirst { case p: PaymentRequest.PaymentHash => p.hash }.get

  /**
   * @return the payment secret
   */
  lazy val paymentSecret = tags.collectFirst { case p: PaymentRequest.PaymentSecret => p.secret }

  /**
   * @return the description of the payment, or its hash
   */
  lazy val description: Either[String, ByteVector32] = tags.collectFirst {
    case PaymentRequest.Description(d) => Left(d)
    case PaymentRequest.DescriptionHash(h) => Right(h)
  }.get

  /**
   * @return the fallback address if any. It could be a script address, pubkey address, ..
   */
  def fallbackAddress(): Option[String] = tags.collectFirst {
    case f: PaymentRequest.FallbackAddress => PaymentRequest.FallbackAddress.toAddress(f, prefix)
  }

  lazy val routingInfo: Seq[Seq[ExtraHop]] = tags.collect { case t: RoutingInfo => t.path }

  lazy val expiry: Option[Long] = tags.collectFirst {
    case expiry: PaymentRequest.Expiry => expiry.toLong
  }

  lazy val minFinalCltvExpiryDelta: Option[CltvExpiryDelta] = tags.collectFirst {
    case cltvExpiry: PaymentRequest.MinFinalCltvExpiry => cltvExpiry.toCltvExpiryDelta
  }

  lazy val features: PaymentRequestFeatures = tags.collectFirst { case f: PaymentRequestFeatures => f }.getOrElse(PaymentRequestFeatures(BitVector.empty))

  def isExpired: Boolean = expiry match {
    case Some(expiryTime) => timestamp + expiryTime <= System.currentTimeMillis.milliseconds.toSeconds
    case None => timestamp + DEFAULT_EXPIRY_SECONDS <= System.currentTimeMillis.milliseconds.toSeconds
  }

  /**
   * @return the hash of this payment request
   */
  def hash: ByteVector32 = {
    val hrp = s"$prefix${Amount.encode(amount)}".getBytes("UTF-8")
    val data = Bolt11Data(timestamp, tags, ByteVector.fill(65)(0)) // fake sig that we are going to strip next
    val bin = Codecs.bolt11DataCodec.encode(data).require
    val message = ByteVector.view(hrp) ++ bin.dropRight(520).toByteVector
    Crypto.sha256(message)
  }

  /**
   * @param priv private key
   * @return a signed payment request
   */
  def sign(priv: PrivateKey): PaymentRequest = {
    val sig64 = Crypto.sign(hash, priv)
    val (pub1, _) = Crypto.recoverPublicKey(sig64, hash)
    val recid = if (nodeId == pub1) 0.toByte else 1.toByte
    val signature = sig64 :+ recid
    this.copy(signature = signature)
  }
}

object PaymentRequest {

  val DEFAULT_EXPIRY_SECONDS = 3600

  val prefixes = Map(
    Block.RegtestGenesisBlock.hash -> "lnbcrt",
    Block.TestnetGenesisBlock.hash -> "lntb",
    Block.LivenetGenesisBlock.hash -> "lnbc")

  def apply(chainHash: ByteVector32, amount: Option[MilliSatoshi], paymentHash: ByteVector32, privateKey: PrivateKey,
            description: String, fallbackAddress: Option[String] = None, expirySeconds: Option[Long] = None,
            extraHops: List[List[ExtraHop]] = Nil, timestamp: Long = System.currentTimeMillis() / 1000L,
            features: Option[PaymentRequestFeatures] = Some(PaymentRequestFeatures(Features.VariableLengthOnion.optional, Features.PaymentSecret.optional))): PaymentRequest = {

    val prefix = prefixes(chainHash)
    val tags = {
      val defaultTags = List(
        Some(PaymentHash(paymentHash)),
        Some(Description(description)),
        fallbackAddress.map(FallbackAddress(_)),
        expirySeconds.map(Expiry(_)),
        features).flatten
      val paymentSecretTag = if (features.exists(_.allowPaymentSecret)) PaymentSecret(randomBytes32) :: Nil else Nil
      val routingInfoTags = extraHops.map(RoutingInfo)
      defaultTags ++ paymentSecretTag ++ routingInfoTags
    }

    PaymentRequest(
      prefix = prefix,
      amount = amount,
      timestamp = timestamp,
      nodeId = privateKey.publicKey,
      tags = tags,
      signature = ByteVector.empty)
      .sign(privateKey)
  }

  case class Bolt11Data(timestamp: Long, taggedFields: List[TaggedField], signature: ByteVector)

  sealed trait TaggedField

  sealed trait UnknownTaggedField extends TaggedField

  sealed trait InvalidTaggedField extends TaggedField

  // @formatter:off
  case class UnknownTag0(data: BitVector) extends UnknownTaggedField
  case class InvalidTag1(data: BitVector) extends InvalidTaggedField
  case class UnknownTag2(data: BitVector) extends UnknownTaggedField
  case class UnknownTag4(data: BitVector) extends UnknownTaggedField
  case class UnknownTag7(data: BitVector) extends UnknownTaggedField
  case class UnknownTag8(data: BitVector) extends UnknownTaggedField
  case class UnknownTag10(data: BitVector) extends UnknownTaggedField
  case class UnknownTag11(data: BitVector) extends UnknownTaggedField
  case class UnknownTag12(data: BitVector) extends UnknownTaggedField
  case class UnknownTag14(data: BitVector) extends UnknownTaggedField
  case class UnknownTag15(data: BitVector) extends UnknownTaggedField
  case class InvalidTag16(data: BitVector) extends InvalidTaggedField
  case class UnknownTag17(data: BitVector) extends UnknownTaggedField
  case class UnknownTag18(data: BitVector) extends UnknownTaggedField
  case class UnknownTag19(data: BitVector) extends UnknownTaggedField
  case class UnknownTag20(data: BitVector) extends UnknownTaggedField
  case class UnknownTag21(data: BitVector) extends UnknownTaggedField
  case class UnknownTag22(data: BitVector) extends UnknownTaggedField
  case class InvalidTag23(data: BitVector) extends InvalidTaggedField
  case class UnknownTag25(data: BitVector) extends UnknownTaggedField
  case class UnknownTag26(data: BitVector) extends UnknownTaggedField
  case class UnknownTag27(data: BitVector) extends UnknownTaggedField
  case class UnknownTag28(data: BitVector) extends UnknownTaggedField
  case class UnknownTag29(data: BitVector) extends UnknownTaggedField
  case class UnknownTag30(data: BitVector) extends UnknownTaggedField
  case class UnknownTag31(data: BitVector) extends UnknownTaggedField
  // @formatter:on

  /**
   * Payment Hash
   *
   * @param hash payment hash
   */
  case class PaymentHash(hash: ByteVector32) extends TaggedField

  /**
   * Payment secret. This is currently random bytes used to protect against probing from the next-to-last node.
   *
   * @param secret payment secret
   */
  case class PaymentSecret(secret: ByteVector32) extends TaggedField

  /**
   * Description
   *
   * @param description a free-format string that will be included in the payment request
   */
  case class Description(description: String) extends TaggedField

  /**
   * Hash
   *
   * @param hash hash that will be included in the payment request, and can be checked against the hash of a
   *             long description, an invoice, ...
   */
  case class DescriptionHash(hash: ByteVector32) extends TaggedField

  /**
   * Fallback Payment that specifies a fallback payment address to be used if LN payment cannot be processed
   */
  case class FallbackAddress(version: Byte, data: ByteVector) extends TaggedField

  object FallbackAddress {
    /**
     * @param address valid base58 or bech32 address
     * @return a FallbackAddressTag instance
     */
    def apply(address: String): FallbackAddress = {
      Try(fromBase58Address(address)).orElse(Try(fromBech32Address(address))).get
    }

    def fromBase58Address(address: String): FallbackAddress = {
      val (prefix, hash) = Base58Check.decode(address)
      prefix match {
        case Base58.Prefix.PubkeyAddress => FallbackAddress(17.toByte, hash)
        case Base58.Prefix.PubkeyAddressTestnet => FallbackAddress(17.toByte, hash)
        case Base58.Prefix.ScriptAddress => FallbackAddress(18.toByte, hash)
        case Base58.Prefix.ScriptAddressTestnet => FallbackAddress(18.toByte, hash)
      }
    }

    def fromBech32Address(address: String): FallbackAddress = {
      val (_, version, hash) = Bech32.decodeWitnessAddress(address)
      FallbackAddress(version, hash)
    }

    def toAddress(f: FallbackAddress, prefix: String): String = {
      import f.data
      f.version match {
        case 17 if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.PubkeyAddress, data)
        case 18 if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.ScriptAddress, data)
        case 17 if prefix == "lntb" || prefix == "lnbcrt" => Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, data)
        case 18 if prefix == "lntb" || prefix == "lnbcrt" => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, data)
        case version if prefix == "lnbc" => Bech32.encodeWitnessAddress("bc", version, data)
        case version if prefix == "lntb" => Bech32.encodeWitnessAddress("tb", version, data)
        case version if prefix == "lnbcrt" => Bech32.encodeWitnessAddress("bcrt", version, data)
      }
    }
  }

  /**
   * This returns a bitvector with the minimum size necessary to encode the long, left padded
   * to have a length (in bits) multiples of 5
   */
  def long2bits(l: Long) = {
    val bin = BitVector.fromLong(l)
    var highest = -1
    for (i <- 0 until bin.size.toInt) {
      if (highest == -1 && bin(i)) highest = i
    }
    val nonPadded = if (highest == -1) BitVector.empty else bin.drop(highest)
    nonPadded.size % 5 match {
      case 0 => nonPadded
      case remaining => BitVector.fill(5 - remaining)(high = false) ++ nonPadded
    }
  }

  /**
   * Extra hop contained in RoutingInfoTag
   *
   * @param nodeId                    start of the channel
   * @param shortChannelId            channel id
   * @param feeBase                   node fixed fee
   * @param feeProportionalMillionths node proportional fee
   * @param cltvExpiryDelta           node cltv expiry delta
   */
  case class ExtraHop(nodeId: PublicKey, shortChannelId: ShortChannelId, feeBase: MilliSatoshi, feeProportionalMillionths: Long, cltvExpiryDelta: CltvExpiryDelta)

  /**
   * Routing Info
   *
   * @param path one or more entries containing extra routing information for a private route
   */
  case class RoutingInfo(path: List[ExtraHop]) extends TaggedField

  /**
   * Expiry Date
   */
  case class Expiry(bin: BitVector) extends TaggedField {
    def toLong: Long = bin.toLong(signed = false)
  }

  object Expiry {
    /**
     * @param seconds expiry data for this payment request
     */
    def apply(seconds: Long): Expiry = Expiry(long2bits(seconds))
  }

  /**
   * Min final CLTV expiry
   */
  case class MinFinalCltvExpiry(bin: BitVector) extends TaggedField {
    def toCltvExpiryDelta = CltvExpiryDelta(bin.toInt(signed = false))
  }

  object MinFinalCltvExpiry {
    /**
     * Min final CLTV expiry
     *
     * @param blocks min final cltv expiry, in blocks
     */
    def apply(blocks: Long): MinFinalCltvExpiry = MinFinalCltvExpiry(long2bits(blocks))
  }

  /**
   * Features supported or required for receiving this payment.
   */
  case class PaymentRequestFeatures(bitmask: BitVector) extends TaggedField {
    lazy val features: Features = Features(bitmask)
    lazy val supported: Boolean = Features.areSupported(features)
    lazy val allowMultiPart: Boolean = features.hasFeature(Features.BasicMultiPartPayment)
    lazy val allowPaymentSecret: Boolean = features.hasFeature(Features.PaymentSecret)
    lazy val requirePaymentSecret: Boolean = features.hasFeature(Features.PaymentSecret, Some(FeatureSupport.Mandatory))
    lazy val allowTrampoline: Boolean = features.hasFeature(Features.TrampolinePayment)

    def toByteVector: ByteVector = features.toByteVector

    override def toString: String = s"Features(${bitmask.toBin})"
  }

  object PaymentRequestFeatures {
    def apply(features: Int*): PaymentRequestFeatures = PaymentRequestFeatures(long2bits(features.foldLeft(0L) {
      case (current, feature) => current + (1L << feature)
    }))
  }

  object Codecs {

    import fr.acinq.eclair.wire.CommonCodecs._
    import scodec.bits.BitVector
    import scodec.codecs._
    import scodec.{Attempt, Codec, DecodeResult}

    val extraHopCodec: Codec[ExtraHop] = (
      ("nodeId" | publicKey) ::
        ("shortChannelId" | shortchannelid) ::
        ("fee_base_msat" | millisatoshi32) ::
        ("fee_proportional_millionth" | uint32) ::
        ("cltv_expiry_delta" | cltvExpiryDelta)
      ).as[ExtraHop]

    val extraHopsLengthCodec = Codec[Int](
      (_: Int) => Attempt.successful(BitVector.empty), // we don't encode the length
      (wire: BitVector) => Attempt.successful(DecodeResult(wire.size.toInt / 408, wire)) // we infer the number of items by the size of the data
    )

    def alignedBytesCodec[A](valueCodec: Codec[A]): Codec[A] = Codec[A](
      (value: A) => valueCodec.encode(value),
      (wire: BitVector) => (limitedSizeBits(wire.size - wire.size % 8, valueCodec) ~ constant(BitVector.fill(wire.size % 8)(high = false))).map(_._1).decode(wire) // the 'constant' codec ensures that padding is zero
    )

    val dataLengthCodec: Codec[Long] = uint(10).xmap(_ * 5, s => (s / 5 + (if (s % 5 == 0) 0 else 1)).toInt)

    def dataCodec[A](valueCodec: Codec[A], expectedLength: Option[Long] = None): Codec[A] = paddedVarAlignedBits(
      dataLengthCodec.narrow(l => if (expectedLength.getOrElse(l) == l) Attempt.successful(l) else Attempt.failure(Err(s"invalid length $l")), l => l),
      valueCodec,
      multipleForPadding = 5)

    val taggedFieldCodec: Codec[TaggedField] = discriminated[TaggedField].by(ubyte(5))
      .typecase(0, dataCodec(bits).as[UnknownTag0])
      .\(1) {
        case a: PaymentHash => a: TaggedField
        case a: InvalidTag1 => a: TaggedField
      }(choice(dataCodec(bytes32, expectedLength = Some(52 * 5)).as[PaymentHash].upcast[TaggedField], dataCodec(bits).as[InvalidTag1].upcast[TaggedField]))
      .typecase(2, dataCodec(bits).as[UnknownTag2])
      .typecase(3, dataCodec(listOfN(extraHopsLengthCodec, extraHopCodec)).as[RoutingInfo])
      .typecase(4, dataCodec(bits).as[UnknownTag4])
      .typecase(5, dataCodec(bits).as[PaymentRequestFeatures])
      .typecase(6, dataCodec(bits).as[Expiry])
      .typecase(7, dataCodec(bits).as[UnknownTag7])
      .typecase(8, dataCodec(bits).as[UnknownTag8])
      .typecase(9, dataCodec(ubyte(5) :: alignedBytesCodec(bytes)).as[FallbackAddress])
      .typecase(10, dataCodec(bits).as[UnknownTag10])
      .typecase(11, dataCodec(bits).as[UnknownTag11])
      .typecase(12, dataCodec(bits).as[UnknownTag12])
      .typecase(13, dataCodec(alignedBytesCodec(utf8)).as[Description])
      .typecase(14, dataCodec(bits).as[UnknownTag14])
      .typecase(15, dataCodec(bits).as[UnknownTag15])
      .\(16) {
        case a: PaymentSecret => a: TaggedField
        case a: InvalidTag16 => a: TaggedField
      }(choice(dataCodec(bytes32, expectedLength = Some(52 * 5)).as[PaymentSecret].upcast[TaggedField], dataCodec(bits).as[InvalidTag16].upcast[TaggedField]))
      .typecase(17, dataCodec(bits).as[UnknownTag17])
      .typecase(18, dataCodec(bits).as[UnknownTag18])
      .typecase(19, dataCodec(bits).as[UnknownTag19])
      .typecase(20, dataCodec(bits).as[UnknownTag20])
      .typecase(21, dataCodec(bits).as[UnknownTag21])
      .typecase(22, dataCodec(bits).as[UnknownTag22])
      .\(23) {
        case a: DescriptionHash => a: TaggedField
        case a: InvalidTag23 => a: TaggedField
      }(choice(dataCodec(bytes32, expectedLength = Some(52 * 5)).as[DescriptionHash].upcast[TaggedField], dataCodec(bits).as[InvalidTag23].upcast[TaggedField]))
      .typecase(24, dataCodec(bits).as[MinFinalCltvExpiry])
      .typecase(25, dataCodec(bits).as[UnknownTag25])
      .typecase(26, dataCodec(bits).as[UnknownTag26])
      .typecase(27, dataCodec(bits).as[UnknownTag27])
      .typecase(28, dataCodec(bits).as[UnknownTag28])
      .typecase(29, dataCodec(bits).as[UnknownTag29])
      .typecase(30, dataCodec(bits).as[UnknownTag30])
      .typecase(31, dataCodec(bits).as[UnknownTag31])

    def fixedSizeTrailingCodec[A](codec: Codec[A], size: Int): Codec[A] = Codec[A](
      (data: A) => codec.encode(data),
      (wire: BitVector) => {
        val (head, tail) = wire.splitAt(wire.size - size)
        codec.decode(head).map(result => result.copy(remainder = tail))
      }
    )

    val bolt11DataCodec: Codec[Bolt11Data] = (
      ("timestamp" | ulong(35)) ::
        ("taggedFields" | fixedSizeTrailingCodec(list(taggedFieldCodec), 520)) ::
        ("signature" | bytes(65))
      ).as[Bolt11Data]
  }

  object Amount {

    /**
     * @return the unit allowing for the shortest representation possible
     */
    def unit(amount: MilliSatoshi): Char = amount.toLong * 10 match { // 1 milli-satoshis == 10 pico-bitcoin
      case pico if pico % 1000 > 0 => 'p'
      case pico if pico % 1000000 > 0 => 'n'
      case pico if pico % 1000000000 > 0 => 'u'
      case _ => 'm'
    }

    def decode(input: String): Option[MilliSatoshi] =
      input match {
        case "" => None
        case a if a.last == 'p' => Some(MilliSatoshi(a.dropRight(1).toLong / 10L)) // 1 pico-bitcoin == 10 milli-satoshis
        case a if a.last == 'n' => Some(MilliSatoshi(a.dropRight(1).toLong * 100L))
        case a if a.last == 'u' => Some(MilliSatoshi(a.dropRight(1).toLong * 100000L))
        case a if a.last == 'm' => Some(MilliSatoshi(a.dropRight(1).toLong * 100000000L))
        case a => Some(MilliSatoshi(a.toLong * 100000000000L))
      }

    def encode(amount: Option[MilliSatoshi]): String = {
      amount match {
        case None => ""
        case Some(amt) if unit(amt) == 'p' => s"${amt.toLong * 10L}p" // 1 pico-bitcoin == 10 milli-satoshis
        case Some(amt) if unit(amt) == 'n' => s"${amt.toLong / 100L}n"
        case Some(amt) if unit(amt) == 'u' => s"${amt.toLong / 100000L}u"
        case Some(amt) if unit(amt) == 'm' => s"${amt.toLong / 100000000L}m"
      }
    }
  }

  // char -> 5 bits value
  val charToint5: Map[Char, BitVector] = Bech32.alphabet.zipWithIndex.toMap.mapValues(BitVector.fromInt(_, size = 5, ordering = ByteOrdering.BigEndian)).toMap

  // TODO: could be optimized by preallocating the resulting buffer
  def string2Bits(data: String): BitVector = data.map(charToint5).foldLeft(BitVector.empty)(_ ++ _)

  val eight2fiveCodec: Codec[List[Byte]] = list(ubyte(5))

  /**
   * @param input bech32-encoded payment request
   * @return a payment request
   */
  def read(input: String): PaymentRequest = {
    // used only for data validation
    Bech32.decode(input)
    val lowercaseInput = input.toLowerCase
    val separatorIndex = lowercaseInput.lastIndexOf('1')
    val hrp = lowercaseInput.take(separatorIndex)
    val prefix: String = prefixes.values.find(prefix => hrp.startsWith(prefix)).getOrElse(throw new RuntimeException("unknown prefix"))
    val data = string2Bits(lowercaseInput.slice(separatorIndex + 1, lowercaseInput.length - 6)) // 6 == checksum size
    val bolt11Data = Codecs.bolt11DataCodec.decode(data).require.value
    val signature = ByteVector64(bolt11Data.signature.take(64))
    val message: ByteVector = ByteVector.view(hrp.getBytes) ++ data.dropRight(520).toByteVector // we drop the sig bytes
    val recid = bolt11Data.signature.last
    val pub = Crypto.recoverPublicKey(signature, Crypto.sha256(message), recid)
    // README: since we use pubkey recovery to compute the node id from the message and signature, we don't check the signature.
    // If instead we read the node id from the `n` field (which nobody uses afaict) then we would have to check the signature.
    val amount_opt = Amount.decode(hrp.drop(prefix.length))
    PaymentRequest(
      prefix = prefix,
      amount = amount_opt,
      timestamp = bolt11Data.timestamp,
      nodeId = pub,
      tags = bolt11Data.taggedFields,
      signature = bolt11Data.signature)
  }

  private def readBoltData(input: String): Bolt11Data = {
    val lowercaseInput = input.toLowerCase
    val separatorIndex = lowercaseInput.lastIndexOf('1')
    val hrp = lowercaseInput.take(separatorIndex)
    if (!prefixes.values.exists(prefix => hrp.startsWith(prefix))) throw new RuntimeException("unknown prefix")
    val data = string2Bits(lowercaseInput.slice(separatorIndex + 1, lowercaseInput.length - 6)) // 6 == checksum size
    Codecs.bolt11DataCodec.decode(data).require.value
  }

  /**
   * Extracts the description from a serialized payment request that is **expected to be valid**.
   * Throws an error if the payment request is not valid.
   *
   * @param input valid serialized payment request
   * @return description as a String. If the description is a hash, returns the hash value as a String.
   */
  def fastReadDescription(input: String): String = {
    readBoltData(input).taggedFields.collectFirst {
      case PaymentRequest.Description(d) => d
      case PaymentRequest.DescriptionHash(h) => h.toString()
    }.get
  }

  /**
   * Checks if a serialized payment request is expired. Timestamp is compared to the System's current time.
   *
   * @param input valid serialized payment request
   * @return true if the payment request has expired, false otherwise.
   */
  def fastHasExpired(input: String): Boolean = {
    val bolt11Data = readBoltData(input)
    val expiry_opt = bolt11Data.taggedFields.collectFirst {
      case p: PaymentRequest.Expiry => p
    }
    val timestamp = bolt11Data.timestamp
    expiry_opt match {
      case Some(expiry) => timestamp + expiry.toLong <= System.currentTimeMillis.milliseconds.toSeconds
      case None => timestamp + DEFAULT_EXPIRY_SECONDS <= System.currentTimeMillis.milliseconds.toSeconds
    }
  }

  /**
   * @param pr payment request
   * @return a bech32-encoded payment request
   */
  def write(pr: PaymentRequest): String = {
    // currency unit is Satoshi, but we compute amounts in Millisatoshis
    val hramount = Amount.encode(pr.amount)
    val hrp = s"${pr.prefix}$hramount"
    val data = Codecs.bolt11DataCodec.encode(Bolt11Data(pr.timestamp, pr.tags, pr.signature)).require
    val int5s = eight2fiveCodec.decode(data).require.value
    Bech32.encode(hrp, int5s.toArray)
  }
}

