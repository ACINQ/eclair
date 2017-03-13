package fr.acinq.eclair.crypto

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, Protocol}
import fr.acinq.eclair.wire.{ChannelUpdate, Codecs}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import sun.corba.SharedSecrets

import scala.annotation.tailrec

/**
  * Created by fabrice on 13/01/17.
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  */
object Sphinx {
  val MacLength = 20
  val AddressLength = 20
  val MaxHops = 20
  val PerHopPayloadLength = 20
  val HeaderLength = 1 + 33 + MacLength + MaxHops * (AddressLength + MacLength)
  val PacketLength = HeaderLength + MaxHops * PerHopPayloadLength

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
    require(hmac.length == MacLength, "onion header hmac length should be 20")
    require(routingInfo.length == MaxHops * (AddressLength + MacLength), "onion header routing info length should be 800")
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
    * @param privateKey     this node's private key
    * @param associatedData associated data
    * @param packet         packet received by this node
    * @return a (payload, address, packet) tuple where:
    *         - payload is the per-hop payload for this node
    *         - address is the next destination. 0x0000000000000000000000000000000000000000 means this node was the final
    *         destination
    *         - packet is the next packet, to be forwarded to address
    */
  def parsePacket(privateKey: PrivateKey, associatedData: BinaryData, packet: BinaryData): (BinaryData, BinaryData, BinaryData) = {
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

    (payload, address, Header.write(Header(1, nextPubKey, hmac, nextRoutinfo)) ++ nextPerHopPayloads)
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
    * @param packet              packet that
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
    * Builds an encrypted onion packet that contains payloads and routing information for all nodes in the list
    *
    * @param sessionKey     session key
    * @param publicKeys     node public keys (one per node)
    * @param payloads       payloads (one per node)
    * @param associatedData associated data
    * @return an onion packet that can be sent to the first node in the list
    */
  def makePacket(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[BinaryData], associatedData: BinaryData): BinaryData = {
    val (ephemerealPublicKeys, sharedsecrets) = computeEphemerealPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", sharedsecrets.dropRight(1), AddressLength + MacLength, MaxHops)
    val hopFiller = generateFiller("gamma", sharedsecrets.dropRight(1), PerHopPayloadLength, MaxHops)

    val lastPacket = makeNextPacket(zeroes(AddressLength), payloads.last, associatedData, ephemerealPublicKeys.last, sharedsecrets.last, 1.toByte +: zeroes(PacketLength - 1), filler, hopFiller)

    @tailrec
    def loop(pubKeys: Seq[PublicKey], hoppayloads: Seq[BinaryData], ephkeys: Seq[PublicKey], sharedSecrets: Seq[BinaryData], packet: BinaryData): BinaryData = {
      if (hoppayloads.isEmpty) packet else {
        val nextPacket = makeNextPacket(pubKeys.last.hash160, hoppayloads.last, associatedData, ephkeys.last, sharedSecrets.last, packet)
        loop(pubKeys.dropRight(1), hoppayloads.dropRight(1), ephkeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
      }
    }

    loop(publicKeys, payloads.dropRight(1), ephemerealPublicKeys.dropRight(1), sharedsecrets.dropRight(1), lastPacket)
  }

  object FailureMessage {
    val BADONION = 0x8000
    val PERM = 0x4000
    val NODE = 0x2000
    val UPDATE = 0x1000

    def encode(`type`: Int, data: BinaryData): BinaryData = Protocol.writeUInt16(`type`, ByteOrder.BIG_ENDIAN) ++ data

    val invalid_realm = encode(PERM | 1, BinaryData.empty)

    val temporary_node_failure = encode(NODE | 2, BinaryData.empty)

    val permanent_node_failure = encode(PERM | 2, BinaryData.empty)

    val required_node_feature_missing = encode(PERM | NODE | 3, BinaryData.empty)

    def invalid_onion_version(onion: BinaryData) = encode(BADONION | PERM | 4, Crypto.sha256(onion))

    def invalid_onion_hmac(onion: BinaryData) = encode(BADONION | PERM | 5, Crypto.sha256(onion))

    def invalid_onion_key(onion: BinaryData) = encode(BADONION | PERM | 6, Crypto.sha256(onion))

    val temporary_channel_failure = encode(7, BinaryData.empty)

    val permanent_channel_failure = encode(PERM | 8, BinaryData.empty)

    val unknown_next_peer = encode(PERM | 10, BinaryData.empty)

    def amount_below_minimum(amount_msat: Long, update: ChannelUpdate) = {
      val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
      encode(UPDATE | 11, Protocol.writeUInt32(amount_msat, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
    }

    def insufficient_fee(amount_msat: Long, update: ChannelUpdate) = {
      val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
      encode(UPDATE | 12, Protocol.writeUInt32(amount_msat, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
    }

    def incorrect_cltv_expiry(expiry: Long, update: ChannelUpdate) = {
      val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
      encode(UPDATE | 13, Protocol.writeUInt32(expiry, ByteOrder.BIG_ENDIAN) ++ Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
    }

    def expiry_too_soon(update: ChannelUpdate) = {
      val payload = Codecs.channelUpdateCodec.encode(update).toOption.get.toByteArray
      encode(UPDATE | 14, Protocol.writeUInt16(payload.length, ByteOrder.BIG_ENDIAN) ++ payload)
    }

    val unknown_payment_hash = encode(PERM | 15, BinaryData.empty)

    val incorrect_payment_amount = encode(PERM | 16, BinaryData.empty)

    val final_expiry_too_soon = encode(PERM | 17, BinaryData.empty)


    /**
      *
      * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
      *                     was created or forwarded: see makePacket() and makeNextPacket()
      * @param message      failure message
      * @return an error packet that can be sent to the destination node
      */
    def createPacket(sharedSecret: BinaryData, message: BinaryData): BinaryData = {
      val um = generateKey("um", sharedSecret)
      val padlen = 128 - message.length
      val payload = Protocol.writeUInt16(message.length, ByteOrder.BIG_ENDIAN) ++ message ++ Protocol.writeUInt16(padlen, ByteOrder.BIG_ENDIAN) ++ zeroes(padlen)
      forwardPacket(sharedSecret, Sphinx.mac(um, payload) ++ payload)
    }

    def extractFailureMessage(packet: BinaryData): BinaryData = {
      val (mac, payload) = packet.splitAt(MacLength)
      val len = Protocol.uint16(payload, ByteOrder.BIG_ENDIAN)
      require((len >= 0) && (len <= 128), "message length must be less than 128")
      payload.drop(2).take(len)
    }

    /**
      *
      * @param sharedSecret destination node's shared secret
      * @param packet       error packet
      * @return an obfuscated error packet that can be sent to the destination node
      */
    def forwardPacket(sharedSecret: BinaryData, packet: BinaryData): BinaryData = {
      val filler = generateFiller("ammag", Seq(sharedSecret), MacLength + 132, 1)
      xor(packet, filler)
    }

    def checkMac(sharedSecret: BinaryData, packet: BinaryData): Boolean = {
      val (mac, payload) = packet.splitAt(MacLength)
      val um = generateKey("um", sharedSecret)
      BinaryData(mac) == Sphinx.mac(um, payload)
    }

    /**
      *
      * @param packet        error packet
      * @param sharedSecrets nodes shared secrets
      * @return Some(secret, failure message) if the origin of the packet could be identified and the packet deobfuscated, none otherwise
      */
    @tailrec
    def parsePacket(packet: BinaryData, sharedSecrets: Seq[BinaryData]): Option[(BinaryData, BinaryData)] = {
      if (sharedSecrets.isEmpty) None
      else {
        val packet1 = forwardPacket(sharedSecrets.head, packet)
        if (checkMac(sharedSecrets.head, packet1)) Some(sharedSecrets.head, extractFailureMessage(packet1)) else parsePacket(packet1, sharedSecrets.tail)
      }
    }

  }

}

