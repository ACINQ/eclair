package fr.acinq.eclair.payment

import java.math.BigInteger
import java.nio.ByteOrder

import fr.acinq.bitcoin.Bech32.Int5
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, _}
import fr.acinq.eclair.payment.PaymentRequest.{Amount, Timestamp}

import scala.annotation.tailrec
import scala.util.Try

/**
  * Lightning Payment Request
  * see https://github.com/lightningnetwork/lightning-rfc/pull/183
  *
  * @param prefix    currency prefix; lnbc for bitcoin, lntb for bitcoin testnet
  * @param amount    amount to pay (empty string means no amount is specified)
  * @param timestamp request timestamp (UNIX format)
  * @param nodeId    id of the node emitting the payment request
  * @param tags      payment tags; must include a single PaymentHash tag
  * @param signature request signature that will be checked against node id
  */
case class PaymentRequest(prefix: String, amount: Option[MilliSatoshi], timestamp: Long, nodeId: PublicKey, tags: List[PaymentRequest.Tag], signature: BinaryData) {

  amount.map(a => require(a > MilliSatoshi(0) && a <= PaymentRequest.maxAmount, s"amount is not valid"))
  require(tags.collect { case _: PaymentRequest.PaymentHashTag => {} }.size == 1, "there must be exactly one payment hash tag")
  require(tags.collect { case PaymentRequest.DescriptionTag(_) | PaymentRequest.DescriptionHashTag(_) => {} }.size == 1, "there must be exactly one description tag or one description hash tag")

  /**
    *
    * @return the payment hash
    */
  def paymentHash = tags.collectFirst { case p: PaymentRequest.PaymentHashTag => p }.get.hash

  /**
    *
    * @return the description of the payment, or its hash
    */
  def description: Either[String, BinaryData] = tags.collectFirst {
    case PaymentRequest.DescriptionTag(d) => Left(d)
    case PaymentRequest.DescriptionHashTag(h) => Right(h)
  }.get

  /**
    *
    * @return the fallback address if any. It could be a script address, pubkey address, ..
    */
  def fallbackAddress(): Option[String] = tags.collectFirst {
    case PaymentRequest.FallbackAddressTag(17, hash) if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.PubkeyAddress, hash)
    case PaymentRequest.FallbackAddressTag(18, hash) if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.ScriptAddress, hash)
    case PaymentRequest.FallbackAddressTag(17, hash) if prefix == "lntb" => Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, hash)
    case PaymentRequest.FallbackAddressTag(18, hash) if prefix == "lntb" => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
    case PaymentRequest.FallbackAddressTag(version, hash) if prefix == "lnbc" => Bech32.encodeWitnessAddress("bc", version, hash)
    case PaymentRequest.FallbackAddressTag(version, hash) if prefix == "lntb" => Bech32.encodeWitnessAddress("tb", version, hash)
  }

  /**
    *
    * @return a representation of this payment request as a sequence of 32 bits integers
    */
  def data: Seq[Bech32.Int5] = Timestamp.encode(timestamp) ++ (tags.map(_.toInt5s).flatten)

  /**
    *
    * @return the hash of this payment request
    */
  def hash: BinaryData = Crypto.sha256(s"${prefix}${Amount.encode(amount)}".getBytes("UTF-8") ++ data)

  /**
    *
    * @param priv private key
    * @return a signed payment request
    */
  def sign(priv: PrivateKey): PaymentRequest = {
    val (r, s) = Crypto.sign(hash, priv)
    val (pub1, pub2) = Crypto.recoverPublicKey((r, s), hash)
    val recid = if (nodeId == pub1) 0.toByte else 1.toByte
    val signature = PaymentRequest.Signature.encode(r, s, recid)
    this.copy(signature = signature)
  }
}

object PaymentRequest {

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#adding-an-htlc-update_add_htlc
  val maxAmount = MilliSatoshi(4294967296L)

  def apply(prefix: String, amount: Option[MilliSatoshi], paymentHash: BinaryData, privateKey: PrivateKey, description: String, fallbackAddress: Option[String] = None, expirySeconds: Option[Long] = None, timestamp: Long = System.currentTimeMillis() / 1000L, unit: Char = 'm'): PaymentRequest =
    PaymentRequest(
      prefix = prefix,
      amount = amount,
      timestamp = timestamp,
      nodeId = privateKey.publicKey,
      tags = List(
        Some(PaymentHashTag(paymentHash)),
        Some(DescriptionTag(description)),
        expirySeconds.map(ExpiryTag(_))).flatten,
      signature = BinaryData.empty)
      .sign(privateKey)

  sealed trait Tag {
    def toInt5s: Seq[Int5]
  }

  /**
    * Payment Hash Tag
    *
    * @param hash payment hash
    */
  case class PaymentHashTag(hash: BinaryData) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(hash)
      Seq(Bech32.map('p'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  /**
    * Description Tag
    *
    * @param description a free-format string that will be included in the payment request
    */
  case class DescriptionTag(description: String) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(description.getBytes("UTF-8"))
      Seq(Bech32.map('d'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  /**
    * Hash Tag
    *
    * @param hash hash that will be included in the payment request, and can be checked against the hash of a
    *             long description, an invoice, ...
    */
  case class DescriptionHashTag(hash: BinaryData) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(hash)
      Seq(Bech32.map('h'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }


  /**
    * Fallback Payment Tag that specifies a fallback payment address to be used if LN payment cannot be processed
    *
    * @param version address version; valid values are
    *                - 17 (pubkey hash)
    *                - 18 (script hash)
    *                - 0 (segwit hash: p2wpkh (20 bytes) or p2wsh (32 bytes))
    * @param hash    address hash
    */
  case class FallbackAddressTag(version: Byte, hash: BinaryData) extends Tag {
    override def toInt5s = {
      val ints = version +: Bech32.eight2five(hash)
      Seq(Bech32.map('f'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  object FallbackAddressTag {
    /**
      *
      * @param address valid base58 or bech32 address
      * @return a FallbackAddressTag instance
      */
    def apply(address: String): FallbackAddressTag = {
      Try(fromBase58Address(address)).orElse(Try(fromBech32Address(address))).get
    }

    def fromBase58Address(address: String): FallbackAddressTag = {
      val (prefix, hash) = Base58Check.decode(address)
      prefix match {
        case Base58.Prefix.PubkeyAddress => FallbackAddressTag(17, hash)
        case Base58.Prefix.PubkeyAddressTestnet => FallbackAddressTag(17, hash)
        case Base58.Prefix.ScriptAddress => FallbackAddressTag(18, hash)
        case Base58.Prefix.ScriptAddressTestnet => FallbackAddressTag(18, hash)
      }
    }

    def fromBech32Address(address: String): FallbackAddressTag = {
      val (prefix, hash) = Bech32.decodeWitnessAddress(address)
      FallbackAddressTag(prefix, hash)
    }
  }

  /**
    * Routing Info Tag
    *
    * @param pubkey          node id
    * @param channelId       channel id
    * @param fee             node fee
    * @param cltvExpiryDelta node cltv expiry delta
    */
  case class RoutingInfoTag(pubkey: PublicKey, channelId: BinaryData, fee: Long, cltvExpiryDelta: Int) extends Tag {
    override def toInt5s = {
      val ints = Bech32.eight2five(pubkey.toBin ++ channelId ++ Protocol.writeUInt64(fee, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(cltvExpiryDelta, ByteOrder.BIG_ENDIAN))
      Seq(Bech32.map('r'), (ints.length / 32).toByte, (ints.length % 32).toByte) ++ ints
    }
  }

  /**
    * Expiry Date
    *
    * @param seconds expriry data for this payment request
    */
  case class ExpiryTag(seconds: Long) extends Tag {
    override def toInt5s = {
      val ints = Seq((seconds / 32).toByte, (seconds % 32).toByte)
      Seq(Bech32.map('x'), 0.toByte, 2.toByte) ++ ints
    }
  }

  object Amount {

    /**
      * @param amount
      * @return the unit allowing for the shortest representation possible
      */
    def unit(amount: MilliSatoshi): Char = amount.amount * 10 match { // 1 milli-satoshis == 10 pico-bitcoin
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
      }

    def encode(amount: Option[MilliSatoshi]): String = {
      amount match {
        case None => ""
        case Some(amt) if unit(amt) == 'p' => s"${amt.amount * 10L}p" // 1 pico-bitcoin == 10 milli-satoshis
        case Some(amt) if unit(amt) == 'n' => s"${amt.amount / 100L}n"
        case Some(amt) if unit(amt) == 'u' => s"${amt.amount / 100000L}u"
        case Some(amt) if unit(amt) == 'm' => s"${amt.amount / 100000000L}m"
      }
    }
  }

  object Tag {
    def parse(input: Seq[Byte]): Tag = {
      val tag = input(0)
      val len = input(1) * 32 + input(2)
      tag match {
        case p if p == Bech32.map('p') =>
          val hash = Bech32.five2eight(input.drop(3).take(52))
          PaymentHashTag(hash)
        case d if d == Bech32.map('d') =>
          val description = new String(Bech32.five2eight(input.drop(3).take(len)).toArray, "UTF-8")
          DescriptionTag(description)
        case h if h == Bech32.map('h') =>
          val hash: BinaryData = Bech32.five2eight(input.drop(3).take(len))
          DescriptionHashTag(hash)
        case f if f == Bech32.map('f') =>
          val version = input(3)
          val prog = Bech32.five2eight(input.drop(4).take(len - 1))
          version match {
            case v if v >= 0 && v <= 16 =>
              FallbackAddressTag(version, prog)
            case 17 | 18 =>
              FallbackAddressTag(version, prog)
          }
        case r if r == Bech32.map('r') =>
          val data = Bech32.five2eight(input.drop(3).take(len))
          val pubkey = PublicKey(data.take(33))
          val channelId = data.drop(33).take(8)
          val fee = Protocol.uint64(data.drop(33 + 8), ByteOrder.BIG_ENDIAN)
          val cltv = Protocol.uint16(data.drop(33 + 8 + 8), ByteOrder.BIG_ENDIAN)
          RoutingInfoTag(pubkey, channelId, fee, cltv)
        case x if x == Bech32.map('x') =>
          require(len == 2, s"invalid length for expiry tag, should be 2 instead of $len")
          val expiry = 32 * input(3) + input(4)
          ExpiryTag(expiry)
      }
    }
  }

  object Timestamp {
    def decode(data: Seq[Int5]): Long = data.take(7).foldLeft(0L)((a, b) => a * 32 + b)

    def encode(timestamp: Long, acc: Seq[Int5] = Nil): Seq[Int5] = if (acc.length == 7) acc else {
      encode(timestamp / 32, (timestamp % 32).toByte +: acc)
    }
  }

  object Signature {
    /**
      *
      * @param signature 65-bytes signatyre: r (32 bytes) | s (32 bytes) | recid (1 bytes)
      * @return a (r, s, recoveryId)
      */
    def decode(signature: BinaryData): (BigInteger, BigInteger, Byte) = {
      require(signature.length == 65)
      val r = new BigInteger(1, signature.take(32).toArray)
      val s = new BigInteger(1, signature.drop(32).take(32).toArray)
      val recid = signature.last
      (r, s, recid)
    }

    /**
      *
      * @return a 65 bytes representation of (r, s, recid)
      */
    def encode(r: BigInteger, s: BigInteger, recid: Byte): BinaryData = {
      Crypto.fixSize(r.toByteArray.dropWhile(_ == 0.toByte)) ++ Crypto.fixSize(s.toByteArray.dropWhile(_ == 0.toByte)) :+ recid
    }
  }

  /**
    *
    * @param input bech32-encoded payment request
    * @return a payment request
    */
  def read(input: String): PaymentRequest = {
    val (hrp, data) = Bech32.decode(input)
    val timestamp = Timestamp.decode(data)
    val data1 = data.drop(7)

    @tailrec
    def loop(data: Seq[Int5], tags: Seq[Seq[Int5]] = Nil): (BinaryData, Seq[Seq[Int5]]) = {
      // 104 is the size of a signature
      if (data.length > 104) {
        val len = 1 + 2 + 32 * data(1) + data(2)
        loop(data.drop(len), tags :+ data.take(len))
      } else (Bech32.five2eight(data), tags)
    }

    val (signature, rawtags) = loop(data1)
    val tags = rawtags.map(Tag.parse)
    val r = new BigInteger(1, signature.take(32).toArray)
    val s = new BigInteger(1, signature.drop(32).take(32).toArray)
    val recid = signature.last
    val message: BinaryData = hrp.getBytes ++ data.dropRight(104)
    val (pub1, pub2) = Crypto.recoverPublicKey((r, s), Crypto.sha256(message))
    val pub = if (recid % 2 != 0) pub2 else pub1
    val prefix = hrp.take(4)
    val amount_opt = Amount.decode(hrp.drop(4))
    val pr = PaymentRequest(prefix, amount_opt, timestamp, pub, tags.toList, signature)
    val validSig = Crypto.verifySignature(pr.hash, (r, s), pub)
    require(validSig, "invalid signature")
    pr
  }

  /**
    *
    * @param pr payment request
    * @return a bech32-encoded payment request
    */
  def write(pr: PaymentRequest): String = {
    // currency unit is Satoshi, but we compute amounts in Millisatoshis
    val hramount = Amount.encode(pr.amount)
    val hrp = s"${pr.prefix}$hramount"
    val data1 = pr.data ++ Bech32.eight2five(pr.signature)
    val checksum = Bech32.checksum(hrp, data1)
    hrp + "1" + new String((data1 ++ checksum).map(i => Bech32.pam(i)).toArray)
  }
}

