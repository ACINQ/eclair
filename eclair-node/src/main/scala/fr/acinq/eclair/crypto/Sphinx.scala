package fr.acinq.eclair.crypto

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, Protocol}
import fr.acinq.eclair.wire.{ChannelUpdate, Codecs}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

import scala.annotation.tailrec

/**
  * Created by fabrice on 13/01/17.
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  */
object Sphinx {
  // length of a MAC
  val MacLength = 20

  // length of an address (hash160(publicKey))
  val AddressLength = 20

  // max number of hops
  val MaxHops = 20

  // per hop payload length
  val PerHopPayloadLength = 20

  // header length
  val HeaderLength = 1 + 33 + MacLength + MaxHops * (AddressLength + MacLength)

  // onion packet length
  val PacketLength = HeaderLength + MaxHops * PerHopPayloadLength

  // last address; means that we are the final destination for an onion packet
  val LAST_ADDRESS = zeroes(AddressLength)

  // last packet (all zeroes except for the version byte)
  val LAST_PACKET: BinaryData = 1.toByte +: zeroes(PacketLength - 1)

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
    * computes the ephemereal public keys and shared secrets for all nodes on the route.
    *
    * @param sessionKey this node's session key
    * @param publicKeys public keys of each node on the route
    * @return a tuple (ephemereal public keys, shared secrets)
    */
  def computeEphemerealPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey]): (Seq[PublicKey], Seq[BinaryData]) = {
    val ephemerealPublicKey0 = blind(PublicKey(Crypto.curve.getG, compressed = true), sessionKey.value)
    val secret0 = computeSharedSecret(publicKeys(0), sessionKey)
    val blindingFactor0 = computeblindingFactor(ephemerealPublicKey0, secret0)
    computeEphemerealPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, Seq(ephemerealPublicKey0), Seq(blindingFactor0), Seq(secret0))
  }

  @tailrec
  def computeEphemerealPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], ephemerealPublicKeys: Seq[PublicKey], blindingFactors: Seq[BinaryData], sharedSecrets: Seq[BinaryData]): (Seq[PublicKey], Seq[BinaryData]) = {
    if (publicKeys.isEmpty)
      (ephemerealPublicKeys, sharedSecrets)
    else {
      val ephemerealPublicKey = blind(ephemerealPublicKeys.last, blindingFactors.last)
      val secret = computeSharedSecret(blind(publicKeys.head, blindingFactors), sessionKey)
      val blindingFactor = computeblindingFactor(ephemerealPublicKey, secret)
      computeEphemerealPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, ephemerealPublicKeys :+ ephemerealPublicKey, blindingFactors :+ blindingFactor, sharedSecrets :+ secret)
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

  case class Header(version: Int, publicKey: BinaryData, hmac: BinaryData, routingInfo: BinaryData) {
    require(publicKey.length == 33, "onion header public key length should be 33")
    require(hmac.length == MacLength, s"onion header hmac length should be $MacLength")
    require(routingInfo.length == MaxHops * (AddressLength + MacLength), s"onion header routing info length should be ${MaxHops * (AddressLength + MacLength)}")
  }

  object Header {
    def read(in: InputStream): Header = {
      val version = in.read
      val publicKey = new Array[Byte](33)
      in.read(publicKey)
      val hmac = new Array[Byte](MacLength)
      in.read(hmac)
      val routingInfo = new Array[Byte](MaxHops * (AddressLength + MacLength))
      in.read(routingInfo)
      Header(version, publicKey, hmac, routingInfo)
    }

    def read(in: BinaryData): Header = read(new ByteArrayInputStream(in))

    def write(header: Header, out: OutputStream): OutputStream = {
      out.write(header.version)
      out.write(header.publicKey)
      out.write(header.hmac)
      out.write(header.routingInfo)
      out
    }

    def write(header: Header): BinaryData = {
      val out = new ByteArrayOutputStream(HeaderLength)
      write(header, out)
      out.toByteArray
    }
  }

  /**
    *
    * @param payload      paylod for this node
    * @param nextAddress  next address in the route (all 0s if we're the final destination)
    * @param nextPacket   packet for the next node
    * @param sharedSecret shared secret for the sending node, which we will need to return error messages
    */
  case class ParsedPacket(payload: BinaryData, nextAddress: BinaryData, nextPacket: BinaryData, sharedSecret: BinaryData)

  /**
    *
    * @param privateKey     this node's private key
    * @param associatedData associated data
    * @param packet         packet received by this node
    * @return a (payload, address, packet, shared secret) tuple where:
    *         - payload is the per-hop payload for this node
    *         - address is the next destination. 0x0000000000000000000000000000000000000000 means this node was the final
    *         destination
    *         - packet is the next packet, to be forwarded to address
    *         - shared secret is the secret we share with the node that send the packet. We need it to propagate failure
    *         messages upstream.
    */
  def parsePacket(privateKey: PrivateKey, associatedData: BinaryData, packet: BinaryData): ParsedPacket = {
    require(packet.length == PacketLength, "onion packet length should be 1254")
    val header = Header.read(packet)
    val perHopPayload = packet.drop(HeaderLength)
    val sharedSecret = computeSharedSecret(PublicKey(header.publicKey), privateKey)
    val mu = generateKey("mu", sharedSecret)
    val check: BinaryData = mac(mu, header.routingInfo ++ perHopPayload ++ associatedData)
    require(check == header.hmac, "invalid header mac")

    val rho = generateKey("rho", sharedSecret)
    val bin = xor(header.routingInfo ++ zeroes(AddressLength + MacLength), generateStream(rho, AddressLength + MacLength + MaxHops * (AddressLength + MacLength)))
    val address = bin.take(AddressLength)
    val hmac = bin.slice(AddressLength, AddressLength + MacLength)
    val nextRoutinfo = bin.drop(AddressLength + MacLength)

    val nextPubKey = blind(PublicKey(header.publicKey), computeblindingFactor(PublicKey(header.publicKey), sharedSecret))

    val gamma = generateKey("gamma", sharedSecret)
    val bin1 = xor(perHopPayload ++ zeroes(PerHopPayloadLength), generateStream(gamma, PerHopPayloadLength + MaxHops * PerHopPayloadLength))
    val payload = bin1.take(PerHopPayloadLength)
    val nextPerHopPayloads = bin1.drop(PerHopPayloadLength)

    ParsedPacket(payload, address, Header.write(Header(1, nextPubKey, hmac, nextRoutinfo)) ++ nextPerHopPayloads, sharedSecret)
  }

  @tailrec
  def extractSharedSecrets(packet: BinaryData, privateKey: PrivateKey, associatedData: BinaryData, acc: Seq[BinaryData] = Nil): Seq[BinaryData] = {
    parsePacket(privateKey, associatedData, packet) match {
      case ParsedPacket(_, nextAddress, _, sharedSecret) if nextAddress == LAST_ADDRESS => acc :+ sharedSecret
      case ParsedPacket(_, _, nextPacket, sharedSecret) => extractSharedSecrets(nextPacket, privateKey, associatedData, acc :+ sharedSecret)
    }
  }

  /**
    * Compute the next packet from the current packet and node parameters.
    * Packets are constructed in reverse order:
    * - you first build the last packet
    * - then you call makeNextPacket(...) until you've build the final onion packet that will be sent to the first node
    * in the route
    *
    * @param address             next destination; all zeroes if this is the last packet
    * @param payload             payload for this packed
    * @param associatedData      associated data
    * @param ephemerealPublicKey ephemereal key for this packed
    * @param sharedSecret        shared secret
    * @param packet              current packet (1 + all zeroes if this is the last packet)
    * @param routingInfoFiller   optional routing info filler, needed only when you're constructing the last packet
    * @param payloadsFiller      optional payload filler, needed only when you're constructing the last packet
    * @return the next packet
    */
  def makeNextPacket(address: BinaryData, payload: BinaryData, associatedData: BinaryData, ephemerealPublicKey: BinaryData, sharedSecret: BinaryData, packet: BinaryData, routingInfoFiller: BinaryData = BinaryData.empty, payloadsFiller: BinaryData = BinaryData.empty): BinaryData = {
    val header = Header.read(packet)
    val hopPayloads = packet.drop(HeaderLength)

    val nextRoutingInfo = {
      val routingInfo1 = address ++ header.hmac ++ header.routingInfo.dropRight(AddressLength + MacLength)
      val routingInfo2 = xor(routingInfo1, generateStream(generateKey("rho", sharedSecret), MaxHops * (AddressLength + MacLength)))
      routingInfo2.dropRight(routingInfoFiller.length) ++ routingInfoFiller
    }
    val nexHopPayloads = {
      val hopPayloads1 = payload ++ hopPayloads.dropRight(PerHopPayloadLength)
      val hopPayloads2 = xor(hopPayloads1, generateStream(generateKey("gamma", sharedSecret), MaxHops * PerHopPayloadLength))
      hopPayloads2.dropRight(payloadsFiller.length) ++ payloadsFiller
    }

    val nextHmac: BinaryData = mac(generateKey("mu", sharedSecret), nextRoutingInfo ++ nexHopPayloads ++ associatedData)
    val nextHeader = Header(1, ephemerealPublicKey, nextHmac, nextRoutingInfo)
    Header.write(nextHeader) ++ nexHopPayloads
  }


  /**
    *
    * @param onionPacket   onion packet
    * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
    *                      packet. Empty if you're just forwarding the packet to the next node
    */
  case class OnionPacket(onionPacket: BinaryData, sharedSecrets: Seq[(BinaryData, PublicKey)] = Nil)

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
  def makePacket(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[BinaryData], associatedData: BinaryData): OnionPacket = {
    val (ephemerealPublicKeys, sharedsecrets) = computeEphemerealPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", sharedsecrets.dropRight(1), AddressLength + MacLength, MaxHops)
    val hopFiller = generateFiller("gamma", sharedsecrets.dropRight(1), PerHopPayloadLength, MaxHops)

    val lastPacket = makeNextPacket(LAST_ADDRESS, payloads.last, associatedData, ephemerealPublicKeys.last, sharedsecrets.last, LAST_PACKET, filler, hopFiller)

    @tailrec
    def loop(pubKeys: Seq[PublicKey], hoppayloads: Seq[BinaryData], ephkeys: Seq[PublicKey], sharedSecrets: Seq[BinaryData], packet: BinaryData): BinaryData = {
      if (hoppayloads.isEmpty) packet else {
        val nextPacket = makeNextPacket(pubKeys.last.hash160, hoppayloads.last, associatedData, ephkeys.last, sharedSecrets.last, packet)
        loop(pubKeys.dropRight(1), hoppayloads.dropRight(1), ephkeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
      }
    }

    val packet = loop(publicKeys, payloads.dropRight(1), ephemerealPublicKeys.dropRight(1), sharedsecrets.dropRight(1), lastPacket)
    OnionPacket(packet, sharedsecrets.zip(publicKeys))
  }

  /*
    error packet format:
    +----------------+----------------------------------+-----------------+----------------------+-----+
    | HMAC(20 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
    +----------------+----------------------------------+-----------------+----------------------+-----+
    with failure message length + pad length = 128
   */
  val ErrorPacketLength = MacLength + 128 + 2 + 2


  /**
    *
    * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
    *                     was created or forwarded: see makePacket() and makeNextPacket()
    * @param message      failure message
    * @return an error packet that can be sent to the destination node
    */
  def createErrorPacket(sharedSecret: BinaryData, message: BinaryData): BinaryData = {
    require(message.length <= 128, s"error message length is ${message.length}, it must be less than 128")
    val um = Sphinx.generateKey("um", sharedSecret)
    val padlen = 128 - message.length
    val payload = Protocol.writeUInt16(message.length, ByteOrder.BIG_ENDIAN) ++ message ++ Protocol.writeUInt16(padlen, ByteOrder.BIG_ENDIAN) ++ Sphinx.zeroes(padlen)
    forwardErrorPacket(Sphinx.mac(um, payload) ++ payload, sharedSecret)
  }

  /**
    *
    * @param packet error packet
    * @return the failure message that is embedded in the error packet
    */
  def extractFailureMessage(packet: BinaryData): BinaryData = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    val (mac, payload) = packet.splitAt(Sphinx.MacLength)
    val len = Protocol.uint16(payload, ByteOrder.BIG_ENDIAN)
    require((len >= 0) && (len <= 128), "message length must be less than 128")
    payload.drop(2).take(len)
  }

  /**
    *
    * @param packet       error packet
    * @param sharedSecret destination node's shared secret
    * @return an obfuscated error packet that can be sent to the destination node
    */
  def forwardErrorPacket(packet: BinaryData, sharedSecret: BinaryData): BinaryData = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    val filler = Sphinx.generateFiller("ammag", Seq(sharedSecret), ErrorPacketLength, 1)
    Sphinx.xor(packet, filler)
  }

  /**
    *
    * @param sharedSecret this node's share secret
    * @param packet       error packet
    * @return true if the packet's mac is valid, which means that it has been properly de-obfuscated
    */
  def checkMac(sharedSecret: BinaryData, packet: BinaryData): Boolean = {
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
    * @return Some(secret, failure message) if the origin of the packet could be identified and the packet de-obfuscated, none otherwise
    */
  @tailrec
  def parseErrorPacket(packet: BinaryData, sharedSecrets: Seq[(BinaryData, PublicKey)]): Option[(PublicKey, BinaryData)] = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    sharedSecrets match {
      case Nil => None
      case (secret, pubkey) :: tail =>
        val packet1 = forwardErrorPacket(packet, secret)
        if (checkMac(secret, packet1)) Some(pubkey, extractFailureMessage(packet1)) else parseErrorPacket(packet1, tail)
    }
  }
}

