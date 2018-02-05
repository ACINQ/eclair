package fr.acinq.eclair.crypto

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, Protocol}
import fr.acinq.eclair.wire.{FailureMessage, FailureMessageCodecs}
import grizzled.slf4j.Logging
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.macs.HMac
import org.spongycastle.crypto.params.KeyParameter
import scodec.bits.BitVector

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
  * Created by fabrice on 13/01/17.
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  */
object Sphinx extends Logging {
  val Version = 0.toByte

  // length of a MAC
  val MacLength = 32

  // length of a payload: 33 bytes (1 bytes for realm, 32 bytes for a realm-specific packet)
  val PayloadLength = 33

  // max number of hops
  val MaxHops = 20

  // onion packet length
  val PacketLength = 1 + 33 + MacLength + MaxHops * (PayloadLength + MacLength)

  // last packet (all zeroes except for the version byte)
  val LAST_PACKET = Packet(Version, zeroes(33), zeroes(MacLength), zeroes(MaxHops * (PayloadLength + MacLength)))

  def hmac256(key: Seq[Byte], message: Seq[Byte]): Seq[Byte] = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(message.toArray, 0, message.length)
    val output = new Array[Byte](32)
    mac.doFinal(output, 0)
    output
  }

  def mac(key: BinaryData, message: BinaryData): BinaryData = hmac256(key, message).take(MacLength)

  def xor(a: Seq[Byte], b: Seq[Byte]): Seq[Byte] = a.zip(b).map { case (x, y) => ((x ^ y) & 0xff).toByte }

  def generateKey(keyType: BinaryData, secret: BinaryData): BinaryData = {
    require(secret.length == 32, "secret must be 32 bytes")
    hmac256(keyType, secret)
  }

  def generateKey(keyType: String, secret: BinaryData): BinaryData = generateKey(keyType.getBytes("UTF-8"), secret)

  def zeroes(length: Int): BinaryData = Seq.fill[Byte](length)(0)

  def generateStream(key: BinaryData, length: Int): BinaryData = ChaCha20Legacy.encrypt(zeroes(length), key, zeroes(8))

  def computeSharedSecret(pub: PublicKey, secret: PrivateKey): BinaryData = Crypto.sha256(pub.multiply(secret).normalize().getEncoded(true))

  def computeblindingFactor(pub: PublicKey, secret: BinaryData): BinaryData = Crypto.sha256(pub.toBin ++ secret)

  def blind(pub: PublicKey, blindingFactor: BinaryData): PublicKey = PublicKey(pub.multiply(blindingFactor).normalize(), compressed = true)

  def blind(pub: PublicKey, blindingFactors: Seq[BinaryData]): PublicKey = blindingFactors.foldLeft(pub)(blind)

  /**
    * computes the ephemeral public keys and shared secrets for all nodes on the route.
    *
    * @param sessionKey this node's session key
    * @param publicKeys public keys of each node on the route
    * @return a tuple (ephemeral public keys, shared secrets)
    */
  def computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey]): (Seq[PublicKey], Seq[BinaryData]) = {
    val ephemeralPublicKey0 = blind(PublicKey(Crypto.curve.getG, compressed = true), sessionKey.value)
    val secret0 = computeSharedSecret(publicKeys(0), sessionKey)
    val blindingFactor0 = computeblindingFactor(ephemeralPublicKey0, secret0)
    computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, Seq(ephemeralPublicKey0), Seq(blindingFactor0), Seq(secret0))
  }

  @tailrec
  def computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], ephemeralPublicKeys: Seq[PublicKey], blindingFactors: Seq[BinaryData], sharedSecrets: Seq[BinaryData]): (Seq[PublicKey], Seq[BinaryData]) = {
    if (publicKeys.isEmpty)
      (ephemeralPublicKeys, sharedSecrets)
    else {
      val ephemeralPublicKey = blind(ephemeralPublicKeys.last, blindingFactors.last)
      val secret = computeSharedSecret(blind(publicKeys.head, blindingFactors), sessionKey)
      val blindingFactor = computeblindingFactor(ephemeralPublicKey, secret)
      computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, ephemeralPublicKeys :+ ephemeralPublicKey, blindingFactors :+ blindingFactor, sharedSecrets :+ secret)
    }
  }

  def generateFiller(keyType: String, sharedSecrets: Seq[BinaryData], hopSize: Int, maxNumberOfHops: Int = MaxHops): BinaryData = {
    sharedSecrets.foldLeft(Seq.empty[Byte])((padding, secret) => {
      val key = generateKey(keyType, secret)
      val padding1 = padding ++ zeroes(hopSize)
      val stream = generateStream(key, hopSize * (maxNumberOfHops + 1)).takeRight(padding1.length)
      xor(padding1, stream)
    })
  }

  case class Packet(version: Int, publicKey: BinaryData, hmac: BinaryData, routingInfo: BinaryData) {
    require(publicKey.length == 33, "onion packet public key length should be 33")
    require(hmac.length == MacLength, s"onion packet hmac length should be $MacLength")
    require(routingInfo.length == MaxHops * (PayloadLength + MacLength), s"onion packet routing info length should be ${MaxHops * (PayloadLength + MacLength)}")

    def isLastPacket: Boolean = hmac == zeroes(MacLength)

    def serialize: BinaryData = Packet.write(this)
  }

  object Packet {
    def read(in: InputStream): Packet = {
      val version = in.read
      val publicKey = new Array[Byte](33)
      in.read(publicKey)
      val routingInfo = new Array[Byte](MaxHops * (PayloadLength + MacLength))
      in.read(routingInfo)
      val hmac = new Array[Byte](MacLength)
      in.read(hmac)
      Packet(version, publicKey, hmac, routingInfo)
    }

    def read(in: BinaryData): Packet = read(new ByteArrayInputStream(in))

    def write(packet: Packet, out: OutputStream): OutputStream = {
      out.write(packet.version)
      out.write(packet.publicKey)
      out.write(packet.routingInfo)
      out.write(packet.hmac)
      out
    }

    def write(packet: Packet): BinaryData = {
      val out = new ByteArrayOutputStream(PacketLength)
      write(packet, out)
      out.toByteArray
    }

    def isLastPacket(packet: BinaryData): Boolean = Packet.read(packet).hmac == zeroes(MacLength)
  }

  /**
    *
    * @param payload      payload for this node
    * @param nextPacket   packet for the next node
    * @param sharedSecret shared secret for the sending node, which we will need to return error messages
    */
  case class ParsedPacket(payload: BinaryData, nextPacket: Packet, sharedSecret: BinaryData)

  /**
    *
    * @param privateKey     this node's private key
    * @param associatedData associated data
    * @param rawPacket      packet received by this node
    * @return a ParsedPacket(payload, packet, shared secret) object where:
    *         - payload is the per-hop payload for this node
    *         - packet is the next packet, to be forwarded using the info that is given in payload (channel id for now)
    *         - shared secret is the secret we share with the node that sent the packet. We need it to propagate failure
    *         messages upstream.
    */
  def parsePacket(privateKey: PrivateKey, associatedData: BinaryData, rawPacket: BinaryData): Try[ParsedPacket] = Try {
    require(rawPacket.length == PacketLength, s"onion packet length is ${rawPacket.length}, it should be ${PacketLength}")
    val packet = Packet.read(rawPacket)
    val sharedSecret = computeSharedSecret(PublicKey(packet.publicKey), privateKey)
    val mu = generateKey("mu", sharedSecret)
    val check: BinaryData = mac(mu, packet.routingInfo ++ associatedData)
    require(check == packet.hmac, "invalid header mac")

    val rho = generateKey("rho", sharedSecret)
    val bin = xor(packet.routingInfo ++ zeroes(PayloadLength + MacLength), generateStream(rho, PayloadLength + MacLength + MaxHops * (PayloadLength + MacLength)))
    val payload = bin.take(PayloadLength)
    val hmac = bin.slice(PayloadLength, PayloadLength + MacLength)
    val nextRouteInfo = bin.drop(PayloadLength + MacLength)

    val nextPubKey = blind(PublicKey(packet.publicKey), computeblindingFactor(PublicKey(packet.publicKey), sharedSecret))

    ParsedPacket(payload, Packet(Version, nextPubKey, hmac, nextRouteInfo), sharedSecret)
  }

  @tailrec
  private def extractSharedSecrets(packet: BinaryData, privateKey: PrivateKey, associatedData: BinaryData, acc: Seq[BinaryData] = Nil): Try[Seq[BinaryData]] = {
    parsePacket(privateKey, associatedData, packet) match {
      case Success(ParsedPacket(_, nextPacket, sharedSecret)) if nextPacket.isLastPacket => Success(acc :+ sharedSecret)
      case Success(ParsedPacket(_, nextPacket, sharedSecret)) => extractSharedSecrets(nextPacket.serialize, privateKey, associatedData, acc :+ sharedSecret)
      case Failure(t) => Failure(t)
    }
  }

  /**
    * Compute the next packet from the current packet and node parameters.
    * Packets are constructed in reverse order:
    * - you first build the last packet
    * - then you call makeNextPacket(...) until you've build the final onion packet that will be sent to the first node
    * in the route
    *
    * @param payload             payload for this packed
    * @param associatedData      associated data
    * @param ephemeralPublicKey ephemeral key for this packed
    * @param sharedSecret        shared secret
    * @param packet              current packet (1 + all zeroes if this is the last packet)
    * @param routingInfoFiller   optional routing info filler, needed only when you're constructing the last packet
    * @return the next packet
    */
  private def makeNextPacket(payload: BinaryData, associatedData: BinaryData, ephemeralPublicKey: BinaryData, sharedSecret: BinaryData, packet: Packet, routingInfoFiller: BinaryData = BinaryData.empty): Packet = {
    require(payload.length == PayloadLength)

    val nextRoutingInfo = {
      val routingInfo1 = payload ++ packet.hmac ++ packet.routingInfo.dropRight(PayloadLength + MacLength)
      val routingInfo2 = xor(routingInfo1, generateStream(generateKey("rho", sharedSecret), MaxHops * (PayloadLength + MacLength)))
      routingInfo2.dropRight(routingInfoFiller.length) ++ routingInfoFiller
    }

    val nextHmac: BinaryData = mac(generateKey("mu", sharedSecret), nextRoutingInfo ++ associatedData)
    val nextPacket = Packet(Version, ephemeralPublicKey, nextHmac, nextRoutingInfo)
    nextPacket
  }


  /**
    *
    * @param packet        onion packet
    * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
    *                      packet. Empty if you're just forwarding the packet to the next node
    */
  case class PacketAndSecrets(packet: Packet, sharedSecrets: Seq[(BinaryData, PublicKey)])

  /**
    * A properly decoded error from a node in the route
    *
    * @param originNode
    * @param failureMessage
    */
  case class ErrorPacket(originNode: PublicKey, failureMessage: FailureMessage)

  /**
    * Builds an encrypted onion packet that contains payloads and routing information for all nodes in the list
    *
    * @param sessionKey     session key
    * @param publicKeys     node public keys (one per node)
    * @param payloads       payloads (one per node)
    * @param associatedData associated data
    * @return an OnionPacket(onion packet, shared secrets). the onion packet can be sent to the first node in the list, and the
    *         shared secrets (one per node) can be used to parse returned error messages if needed
    */
  def makePacket(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[BinaryData], associatedData: BinaryData): PacketAndSecrets = {
    val (ephemeralPublicKeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", sharedsecrets.dropRight(1), PayloadLength + MacLength, MaxHops)

    val lastPacket = makeNextPacket(payloads.last, associatedData, ephemeralPublicKeys.last, sharedsecrets.last, LAST_PACKET, filler)

    @tailrec
    def loop(hoppayloads: Seq[BinaryData], ephkeys: Seq[PublicKey], sharedSecrets: Seq[BinaryData], packet: Packet): Packet = {
      if (hoppayloads.isEmpty) packet else {
        val nextPacket = makeNextPacket(hoppayloads.last, associatedData, ephkeys.last, sharedSecrets.last, packet)
        loop(hoppayloads.dropRight(1), ephkeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
      }
    }

    val packet = loop(payloads.dropRight(1), ephemeralPublicKeys.dropRight(1), sharedsecrets.dropRight(1), lastPacket)
    PacketAndSecrets(packet, sharedsecrets.zip(publicKeys))
  }

  /*
    error packet format:
    +----------------+----------------------------------+-----------------+----------------------+-----+
    | HMAC(32 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
    +----------------+----------------------------------+-----------------+----------------------+-----+
    with failure message length + pad length = 256
   */
  val MaxErrorPayloadLength = 256
  val ErrorPacketLength = MacLength + MaxErrorPayloadLength + 2 + 2

  /**
    *
    * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
    *                     was created or forwarded: see makePacket() and makeNextPacket()
    * @param failure      failure message
    * @return an error packet that can be sent to the destination node
    */
  def createErrorPacket(sharedSecret: BinaryData, failure: FailureMessage): BinaryData = {
    val message: BinaryData = FailureMessageCodecs.failureMessageCodec.encode(failure).require.toByteArray
    require(message.length <= MaxErrorPayloadLength, s"error message length is ${message.length}, it must be less than $MaxErrorPayloadLength")
    val um = Sphinx.generateKey("um", sharedSecret)
    val padlen = MaxErrorPayloadLength - message.length
    val payload = Protocol.writeUInt16(message.length, ByteOrder.BIG_ENDIAN) ++ message ++ Protocol.writeUInt16(padlen, ByteOrder.BIG_ENDIAN) ++ Sphinx.zeroes(padlen)
    logger.debug(s"um key: $um")
    logger.debug(s"error payload: ${BinaryData(payload)}")
    logger.debug(s"raw error packet: ${BinaryData(Sphinx.mac(um, payload) ++ payload)}")
    forwardErrorPacket(Sphinx.mac(um, payload) ++ payload, sharedSecret)
  }

  /**
    *
    * @param packet error packet
    * @return the failure message that is embedded in the error packet
    */
  private def extractFailureMessage(packet: BinaryData): FailureMessage = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    val (mac, payload) = packet.splitAt(Sphinx.MacLength)
    val len = Protocol.uint16(payload, ByteOrder.BIG_ENDIAN)
    require((len >= 0) && (len <= MaxErrorPayloadLength), s"message length must be less than $MaxErrorPayloadLength")
    FailureMessageCodecs.failureMessageCodec.decode(BitVector(payload.drop(2).take(len))).require.value
  }

  /**
    *
    * @param packet       error packet
    * @param sharedSecret destination node's shared secret
    * @return an obfuscated error packet that can be sent to the destination node
    */
  def forwardErrorPacket(packet: BinaryData, sharedSecret: BinaryData): BinaryData = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    val key = generateKey("ammag", sharedSecret)
    val stream = generateStream(key, ErrorPacketLength)
    logger.debug(s"ammag key: $key")
    logger.debug(s"error stream: $stream")
    Sphinx.xor(packet, stream)
  }

  /**
    *
    * @param sharedSecret this node's share secret
    * @param packet       error packet
    * @return true if the packet's mac is valid, which means that it has been properly de-obfuscated
    */
  private def checkMac(sharedSecret: BinaryData, packet: BinaryData): Boolean = {
    val (mac, payload) = packet.splitAt(Sphinx.MacLength)
    val um = Sphinx.generateKey("um", sharedSecret)
    BinaryData(mac) == Sphinx.mac(um, payload)
  }

  /**
    * Parse and de-obfuscate an error packet. Node shared secrets are applied until the packet's MAC becomes valid,
    * which means that it was sent by the corresponding node.
    *
    * @param packet        error packet
    * @param sharedSecrets nodes shared secrets
    * @return Success(secret, failure message) if the origin of the packet could be identified and the packet de-obfuscated, Failure otherwise
    */
  def parseErrorPacket(packet: BinaryData, sharedSecrets: Seq[(BinaryData, PublicKey)]): Try[ErrorPacket] = Try {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")

    @tailrec
    def loop(packet: BinaryData, sharedSecrets: Seq[(BinaryData, PublicKey)]): ErrorPacket = sharedSecrets match {
      case Nil => throw new RuntimeException(s"couldn't parse error packet=$packet with sharedSecrets=$sharedSecrets")
      case (secret, pubkey) :: tail =>
        val packet1 = forwardErrorPacket(packet, secret)
        if (checkMac(secret, packet1)) ErrorPacket(pubkey, extractFailureMessage(packet1)) else loop(packet1, tail)
    }

    loop(packet, sharedSecrets)
  }
}

