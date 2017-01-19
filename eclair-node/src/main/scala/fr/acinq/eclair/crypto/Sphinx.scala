package fr.acinq.eclair.crypto

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

import scala.annotation.tailrec

/**
  * Created by fabrice on 13/01/17.
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  */
object Sphinx {
  def hmac256(key: Seq[Byte], message: Seq[Byte]): Seq[Byte] = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(message.toArray, 0, message.length)
    val output = new Array[Byte](32)
    mac.doFinal(output, 0)
    output
  }

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

  @tailrec
  def computeEphemerealPublicKeys(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], ephemerealPublicKeys: Seq[PublicKey], blindingFactors: Seq[BinaryData], sharedSecrets: Seq[BinaryData]): (Seq[PublicKey], Seq[BinaryData]) = {
    if (publicKeys.isEmpty)
      (ephemerealPublicKeys, sharedSecrets)
    else {
      val ephemerealPublicKey = blind(ephemerealPublicKeys.last, blindingFactors.last)
      val secret = computeSharedSecret(blind(publicKeys.head, blindingFactors), sessionKey)
      val blindingFactor = computeblindingFactor(ephemerealPublicKey, secret)
      computeEphemerealPublicKeys(sessionKey, publicKeys.tail, ephemerealPublicKeys :+ ephemerealPublicKey, blindingFactors :+ blindingFactor, sharedSecrets :+ secret)
    }
  }

  def generateFiller(keyType: String, sharedSecrets: Seq[BinaryData], hopSize: Int, maxNumberOfHops: Int = 20): BinaryData = {
    sharedSecrets.foldLeft(Seq.empty[Byte])((padding, secret) => {
      val key = generateKey(keyType, secret)
      val padding1 = padding ++ zeroes(hopSize)
      val stream = generateStream(key, hopSize * (maxNumberOfHops + 1)).takeRight(padding1.length)
      xor(padding1, stream)
    })
  }

  case class Header(version: Int, publicKey: BinaryData, hmac: BinaryData, routingInfo: BinaryData) {
    require(publicKey.length == 33, "onion header public key length should be 33")
    require(hmac.length == 20, "onion header hmac length should be 20")
    require(routingInfo.length == 800, "onion header routing info length should be 800")
  }

  object Header {
    def read(in: InputStream): Header = {
      val version = in.read
      val publicKey = new Array[Byte](33)
      in.read(publicKey)
      val hmac = new Array[Byte](20)
      in.read(hmac)
      val routingInfo = new Array[Byte](800)
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
      val out = new ByteArrayOutputStream(854)
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
    *         - payload is the teh per-hop payload for this node
    *         - address is the next destination. 0x0000000000000000000000000000000000000000 means this node was the final
    *         destination
    *         - packet is the next packet, to be forwarded to address
    */
  def parsePacket(privateKey: PrivateKey, associatedData: BinaryData, packet: BinaryData): (BinaryData, BinaryData, BinaryData) = {
    require(packet.length == 1254, "onion packet length should be 1854")
    val header = Header.read(packet)
    val perHopPayload = packet.drop(854)
    val sharedSecret = computeSharedSecret(PublicKey(header.publicKey), privateKey)
    val mu = generateKey("mu", sharedSecret)
    val check: BinaryData = hmac256(mu, header.routingInfo ++ perHopPayload ++ associatedData).take(20)
    assert(check == header.hmac)

    val rho = generateKey("rho", sharedSecret)
    val bin = xor(header.routingInfo ++ zeroes(40), generateStream(rho, 840))
    val address = bin.take(20)
    val hmac = bin.slice(20, 40)
    val nextRoutinfo = bin.drop(40)

    val nextPubKey = blind(PublicKey(header.publicKey), computeblindingFactor(PublicKey(header.publicKey), sharedSecret))
    println(s"next pubkey@: $nextPubKey")

    val gamma = generateKey("gamma", sharedSecret)
    val bin1 = xor(perHopPayload ++ zeroes(20), generateStream(gamma, 420))
    val payload = bin1.take(20)
    val nextPerHopPayloads = bin1.drop(20)

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
    val hopPayloads = packet.drop(854)

    val nextRoutingInfo = {
      val routingInfo1 = address ++ header.hmac ++ header.routingInfo.dropRight(40)
      val routingInfo2 = xor(routingInfo1, generateStream(generateKey("rho", sharedSecret), 800))
      routingInfo2.dropRight(routingInfoFiller.length) ++ routingInfoFiller
    }
    val nexHopPayloads = {
      val hopPayloads1 = payload ++ hopPayloads.dropRight(20)
      val hopPayloads2 = xor(hopPayloads1, generateStream(generateKey("gamma", sharedSecret), 400))
      hopPayloads2.dropRight(payloadsFiller.length) ++ payloadsFiller
    }

    val nextHmac: BinaryData = hmac256(generateKey("mu", sharedSecret), nextRoutingInfo ++ nexHopPayloads ++ associatedData).take(20)
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
    val ephemerealPublicKey0 = blind(PublicKey(Crypto.curve.getG, compressed = true), sessionKey.value)
    val secret0 = computeSharedSecret(publicKeys(0), sessionKey)
    val blindingFactor0 = computeblindingFactor(ephemerealPublicKey0, secret0)

    val (ephemerealPublicKeys, sharedsecrets) = computeEphemerealPublicKeys(sessionKey, publicKeys.tail, Seq(ephemerealPublicKey0), Seq(blindingFactor0), Seq(secret0))
    val filler = generateFiller("rho", sharedsecrets.dropRight(1), 40, 20)
    val hopFiller = generateFiller("gamma", sharedsecrets.dropRight(1), 20, 20)

    val lastPacket = makeNextPacket(zeroes(20), payloads.last, associatedData, ephemerealPublicKeys.last, sharedsecrets.last, 1.toByte +: zeroes(1253), filler, hopFiller)

    @tailrec
    def loop(pubKeys: Seq[PublicKey], hoppayloads: Seq[BinaryData], ephkeys: Seq[PublicKey], sharedSecrets: Seq[BinaryData], packet: BinaryData): BinaryData = {
      if (hoppayloads.isEmpty) packet else {
        val nextPacket = makeNextPacket(pubKeys.last.hash160, hoppayloads.last, associatedData, ephkeys.last, sharedSecrets.last, packet)
        loop(pubKeys.dropRight(1), hoppayloads.dropRight(1), ephkeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
      }
    }

    loop(publicKeys, payloads.dropRight(1), ephemerealPublicKeys.dropRight(1), sharedsecrets.dropRight(1), lastPacket)
  }
}

