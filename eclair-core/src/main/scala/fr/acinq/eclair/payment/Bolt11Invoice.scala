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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32}
import fr.acinq.eclair.{Bolt11Feature, CltvExpiryDelta, Feature, FeatureSupport, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TimestampSecond, randomBytes32}
import scodec.bits.{BitVector, ByteOrdering, ByteVector}
import scodec.codecs.{list, ubyte}
import scodec.{Codec, Err}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
 * Lightning Bolt 11 invoice
 * see https://github.com/lightning/bolts/blob/master/11-payment-encoding.md
 *
 * @param prefix     currency prefix; lnbc for bitcoin, lntb for bitcoin testnet, lntbs for bitcoin signet
 * @param amount_opt amount to pay (empty string means no amount is specified)
 * @param createdAt  invoice timestamp (UNIX format)
 * @param nodeId     id of the node emitting the invoice
 * @param tags       payment tags; must include a single PaymentHash tag and a single PaymentSecret tag.
 * @param signature  invoice signature that will be checked against node id
 */
case class Bolt11Invoice(prefix: String, amount_opt: Option[MilliSatoshi], createdAt: TimestampSecond, nodeId: PublicKey, tags: List[Bolt11Invoice.TaggedField], signature: ByteVector) extends Invoice {

  import fr.acinq.eclair.payment.Bolt11Invoice._

  amount_opt.foreach(a => require(a > 0.msat, s"amount is not valid"))
  require(tags.collect { case _: Bolt11Invoice.PaymentHash => }.size == 1, "there must be exactly one payment hash tag")
  require(tags.collect { case Bolt11Invoice.Description(_) | Bolt11Invoice.DescriptionHash(_) => }.size == 1, "there must be exactly one description tag or one description hash tag")
  require(tags.collect { case _: Bolt11Invoice.PaymentSecret => }.size == 1, "there must be exactly one payment secret tag")

  {
    val featuresErr = Features.validateFeatureGraph(features)
    require(featuresErr.isEmpty, featuresErr.map(_.message))
  }
  if (features.hasFeature(Features.PaymentSecret)) {
    require(tags.collect { case _: Bolt11Invoice.PaymentSecret => }.size == 1, "there must be exactly one payment secret tag when feature bit is set")
  }

  /**
   * @return the payment hash
   */
  lazy val paymentHash = tags.collectFirst { case p: Bolt11Invoice.PaymentHash => p.hash }.get

  /**
   * @return the payment secret
   */
  lazy val paymentSecret = tags.collectFirst { case p: Bolt11Invoice.PaymentSecret => p.secret }.get

  /**
   * @return the description of the payment, or its hash
   */
  lazy val description: Either[String, ByteVector32] = tags.collectFirst {
    case Bolt11Invoice.Description(d) => Left(d)
    case Bolt11Invoice.DescriptionHash(h) => Right(h)
  }.get

  /**
   * @return metadata about the payment (see option_payment_metadata).
   */
  lazy val paymentMetadata: Option[ByteVector] = tags.collectFirst { case m: Bolt11Invoice.PaymentMetadata => m.data }

  /**
   * @return the fallback address if any. It could be a script address, pubkey address, ..
   */
  def fallbackAddress(): Option[String] = tags.collectFirst { case f: Bolt11Invoice.FallbackAddress => Bolt11Invoice.FallbackAddress.toAddress(f, prefix) }

  lazy val routingInfo: Seq[Seq[ExtraHop]] = tags.collect { case t: RoutingInfo => t.path }

  lazy val extraEdges: Seq[Invoice.ExtraEdge] = routingInfo.flatMap(path => toExtraEdges(path, nodeId))

  lazy val relativeExpiry: FiniteDuration = FiniteDuration(tags.collectFirst { case expiry: Bolt11Invoice.Expiry => expiry.toLong }.getOrElse(DEFAULT_EXPIRY_SECONDS), TimeUnit.SECONDS)

  lazy val minFinalCltvExpiryDelta: CltvExpiryDelta = tags.collectFirst { case cltvExpiry: Bolt11Invoice.MinFinalCltvExpiry => cltvExpiry.toCltvExpiryDelta }.getOrElse(DEFAULT_MIN_CLTV_EXPIRY_DELTA)

  override lazy val features: Features[InvoiceFeature] = tags.collectFirst { case f: InvoiceFeatures => f.features.invoiceFeatures() }.getOrElse(Features.empty)

  /**
   * @return the hash of this payment invoice
   */
  def hash: ByteVector32 = {
    val hrp = s"$prefix${Amount.encode(amount_opt)}".getBytes("UTF-8")
    val data = Bolt11Data(createdAt, tags, ByteVector.fill(65)(0)) // fake sig that we are going to strip next
    val bin = Codecs.bolt11DataCodec.encode(data).require
    val message = ByteVector.view(hrp) ++ bin.dropRight(520).toByteVector
    Crypto.sha256(message)
  }

  /**
   * @param priv private key
   * @return a signed payment invoice
   */
  def sign(priv: PrivateKey): Bolt11Invoice = {
    val sig64 = Crypto.sign(hash, priv)
    // in order to tell what the recovery id is, we actually recover the pubkey ourselves and compare it to the real one
    val pub0 = Crypto.recoverPublicKey(sig64, hash, 0.toByte)
    val recid = if (nodeId == pub0) 0.toByte else 1.toByte
    val signature = sig64 :+ recid
    this.copy(signature = signature)
  }

  /**
   * @return a bech32-encoded payment invoice
   */
  override def toString: String = {
    // currency unit is Satoshi, but we compute amounts in Millisatoshis
    val hramount = Amount.encode(amount_opt)
    val hrp = s"${prefix}$hramount"
    val data = Codecs.bolt11DataCodec.encode(Bolt11Data(createdAt, tags, signature)).require
    val int5s = eight2fiveCodec.decode(data).require.value
    Bech32.encode(hrp, int5s.toArray, Bech32.Encoding.Bech32)
  }
}

object Bolt11Invoice {
  val DEFAULT_EXPIRY_SECONDS: Long = 3600
  val DEFAULT_MIN_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(18)

  val prefixes = Map(
    Block.RegtestGenesisBlock.hash -> "lnbcrt",
    Block.SignetGenesisBlock.hash -> "lntbs",
    Block.TestnetGenesisBlock.hash -> "lntb",
    Block.LivenetGenesisBlock.hash -> "lnbc"
  )

  val defaultFeatures: Features[Bolt11Feature] = Features((Features.VariableLengthOnion, FeatureSupport.Mandatory), (Features.PaymentSecret, FeatureSupport.Mandatory))

  def apply(chainHash: ByteVector32,
            amount: Option[MilliSatoshi],
            paymentHash: ByteVector32,
            privateKey: PrivateKey,
            description: Either[String, ByteVector32],
            minFinalCltvExpiryDelta: CltvExpiryDelta,
            fallbackAddress: Option[String] = None,
            expirySeconds: Option[Long] = None,
            extraHops: List[List[ExtraHop]] = Nil,
            timestamp: TimestampSecond = TimestampSecond.now(),
            paymentSecret: ByteVector32 = randomBytes32(),
            paymentMetadata: Option[ByteVector] = None,
            features: Features[Bolt11Feature] = defaultFeatures): Bolt11Invoice = {
    require(features.hasFeature(Features.PaymentSecret, Some(FeatureSupport.Mandatory)), "invoices must require a payment secret")
    require(!features.hasFeature(Features.RouteBlinding), "bolt11 invoices cannot use route blinding")
    val prefix = prefixes(chainHash)
    val tags = {
      val defaultTags = List(
        Some(PaymentHash(paymentHash)),
        Some(description.fold(Description, DescriptionHash)),
        Some(PaymentSecret(paymentSecret)),
        paymentMetadata.map(PaymentMetadata),
        fallbackAddress.map(FallbackAddress(_)),
        expirySeconds.map(Expiry(_)),
        Some(MinFinalCltvExpiry(minFinalCltvExpiryDelta.toInt)),
        // We want to keep invoices as small as possible, so we explicitly remove unknown features.
        Some(InvoiceFeatures(features.copy(unknown = Set.empty).unscoped()))
      ).flatten
      val routingInfoTags = extraHops.map(RoutingInfo)
      defaultTags ++ routingInfoTags
    }
    Bolt11Invoice(
      prefix = prefix,
      amount_opt = amount,
      createdAt = timestamp,
      nodeId = privateKey.publicKey,
      tags = tags,
      signature = ByteVector.empty
    ).sign(privateKey)
  }

  case class Bolt11Data(timestamp: TimestampSecond, taggedFields: List[TaggedField], signature: ByteVector)

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
   * @param description a free-format string that will be included in the invoice
   */
  case class Description(description: String) extends TaggedField

  /**
   * Hash
   *
   * @param hash hash that will be included in the invoice, and can be checked against the hash of a
   *             long description, an SKU, ...
   */
  case class DescriptionHash(hash: ByteVector32) extends TaggedField

  /**
   * Additional metadata to attach to the payment.
   */
  case class PaymentMetadata(data: ByteVector) extends TaggedField

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
      val (prefix, hash) = {
        val decoded = Base58Check.decode(address)
        (decoded.getFirst.byteValue(), ByteVector.view(decoded.getSecond))
      }
      prefix match {
        case Base58.Prefix.PubkeyAddress => FallbackAddress(17.toByte, hash)
        case Base58.Prefix.PubkeyAddressTestnet => FallbackAddress(17.toByte, hash)
        case Base58.Prefix.ScriptAddress => FallbackAddress(18.toByte, hash)
        case Base58.Prefix.ScriptAddressTestnet => FallbackAddress(18.toByte, hash)
      }
    }

    def fromBech32Address(address: String): FallbackAddress = {
      val (_, version, hash) = {
        val decoded = Bech32.decodeWitnessAddress(address)
        (decoded.getFirst, decoded.getSecond, ByteVector.view(decoded.getThird))
      }
      FallbackAddress(version, hash)
    }

    def toAddress(f: FallbackAddress, prefix: String): String = {
      import f.data
      f.version match {
        case 17 if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.PubkeyAddress, data.toArray)
        case 18 if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.ScriptAddress, data.toArray)
        case 17 if prefix == "lntb" || prefix == "lnbcrt" || prefix == "lntbs" => Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, data.toArray)
        case 18 if prefix == "lntb" || prefix == "lnbcrt" || prefix == "lntbs" => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, data.toArray)
        case version if prefix == "lnbc" => Bech32.encodeWitnessAddress("bc", version, data.toArray)
        case version if prefix == "lntb" => Bech32.encodeWitnessAddress("tb", version, data.toArray)
        case version if prefix == "lnbcrt" => Bech32.encodeWitnessAddress("bcrt", version, data.toArray)
        case version if prefix == "lntbs" => Bech32.encodeWitnessAddress("tb", version, data.toArray)
      }
    }
  }

  /**
   * This returns a bitvector with the minimum size necessary to encode the long, left padded to have a length (in bits)
   * that is a multiple of 5.
   */
  def long2bits(l: Long): BitVector = leftPaddedBits(BitVector.fromLong(l))

  /**
   * This returns a bitvector with the minimum size necessary to encode the features, left padded to have a length (in
   * bits) that is a multiple of 5.
   */
  def features2bits[T <: Feature](features: Features[T]): BitVector = leftPaddedBits(features.toByteVector.bits)

  private def leftPaddedBits(bits: BitVector): BitVector = {
    var highest = -1
    for (i <- 0 until bits.size.toInt) {
      if (highest == -1 && bits(i)) highest = i
    }
    val nonPadded = if (highest == -1) BitVector.empty else bits.drop(highest)
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
     * @param seconds expiry data for this invoice
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
  case class InvoiceFeatures(features: Features[Feature]) extends TaggedField

  object Codecs {

    import fr.acinq.eclair.wire.protocol.CommonCodecs._
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
      .typecase(5, dataCodec(bits).xmap[Features[Feature]](Features(_), features2bits).as[InvoiceFeatures])
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
      .typecase(27, dataCodec(alignedBytesCodec(bytes)).as[PaymentMetadata])
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
      ("timestamp" | ulong(35).xmapc(TimestampSecond(_))(_.toLong)) ::
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

    def decode(input: String): Try[Option[MilliSatoshi]] =
      (input match {
        case "" => Success(None)
        case a if a.last == 'p' && a.dropRight(1).last != '0' => Failure(new IllegalArgumentException("invalid sub-millisatoshi precision"))
        case a if a.last == 'p' => Success(Some(MilliSatoshi(a.dropRight(1).toLong / 10L))) // 1 pico-bitcoin == 0.1 milli-satoshis
        case a if a.last == 'n' => Success(Some(MilliSatoshi(a.dropRight(1).toLong * 100L)))
        case a if a.last == 'u' => Success(Some(MilliSatoshi(a.dropRight(1).toLong * 100000L)))
        case a if a.last == 'm' => Success(Some(MilliSatoshi(a.dropRight(1).toLong * 100000000L)))
        case a => Success(Some(MilliSatoshi(a.toLong * 100000000000L)))
      }).map {
        case None => None
        case Some(MilliSatoshi(0)) => None
        case Some(amount) => Some(amount)
      }

    def encode(amount: Option[MilliSatoshi]): String = {
      (amount: @unchecked) match {
        case None => ""
        case Some(amt) if unit(amt) == 'p' => s"${amt.toLong * 10L}p" // 1 pico-bitcoin == 0.1 milli-satoshis
        case Some(amt) if unit(amt) == 'n' => s"${amt.toLong / 100L}n"
        case Some(amt) if unit(amt) == 'u' => s"${amt.toLong / 100000L}u"
        case Some(amt) if unit(amt) == 'm' => s"${amt.toLong / 100000000L}m"
      }
    }
  }

  // char -> 5 bits value
  val charToint5: Map[Char, BitVector] = Bech32.alphabet.zipWithIndex.toMap.view.mapValues(BitVector.fromInt(_, size = 5, ordering = ByteOrdering.BigEndian)).toMap

  // TODO: could be optimized by preallocating the resulting buffer
  def string2Bits(data: String): BitVector = data.map(charToint5).foldLeft(BitVector.empty)(_ ++ _)

  val eight2fiveCodec: Codec[List[java.lang.Byte]] = list(ubyte(5).xmap[java.lang.Byte](b => b, b => b))

  /**
   * @param input bech32-encoded invoice
   * @return a Bolt11 invoice
   */
  def fromString(input: String): Try[Bolt11Invoice] = Try {
    // used only for data validation
    Bech32.decode(input, false)
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
    val amount_opt = Amount.decode(hrp.drop(prefix.length)) match {
      case Success(value) => value
      case Failure(e) => throw e
    }
    Bolt11Invoice(
      prefix = prefix,
      amount_opt = amount_opt,
      createdAt = bolt11Data.timestamp,
      nodeId = pub,
      tags = bolt11Data.taggedFields,
      signature = bolt11Data.signature)
  }

  def toExtraEdges(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Seq[Invoice.ExtraEdge] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map {
      case (extraHop, nextNodeId) =>
        Invoice.ExtraEdge(extraHop.nodeId, nextNodeId, extraHop.shortChannelId, extraHop.feeBase, extraHop.feeProportionalMillionths, extraHop.cltvExpiryDelta, 1 msat, None)
    }
  }
}
