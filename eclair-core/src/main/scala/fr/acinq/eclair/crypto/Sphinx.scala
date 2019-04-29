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

package fr.acinq.eclair.crypto

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Crypto, Protocol}
import fr.acinq.eclair.wire.{FailureMessage, FailureMessageCodecs}
import grizzled.slf4j.Logging
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.macs.HMac
import org.spongycastle.crypto.params.KeyParameter
import scodec.bits.{BitVector, ByteVector}

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

  // max number of hops
  val MaxHops = 20

  // A frame is the smallest unit of memory that can be used by a hop to store its payload.
  // Each hop may use multiple frames.
  // Parts of the frame are fixed:
  //  - The first byte of the first frame contains the number of frames used by the payload and the realm, which indicates how the payload should be parsed.
  //  - The last 32 bytes of the last frame contain the HMAC that should be passed to the next hop, or 0 for the last hop.
  //  - All other bytes can be used to store the hop's payload.
  val FrameSize = 65

  // The maximum size a payload for a single hop can be. This is the worst case scenario of a single hop, consuming all 20 frames.
  // We need to know this in order to generate a sufficiently long stream of pseudo-random bytes when encrypting/decrypting the payload.
  val MaxPayloadLength = MaxHops * FrameSize

  // length of the obfuscated onion data
  val RoutingInfoLength = MaxHops * FrameSize

  // onion packet length
  val PacketLength = 1 + 33 + MacLength + RoutingInfoLength

  // last packet (all zeroes except for the version byte)
  val LAST_PACKET = Packet(Version, ByteVector.fill(33)(0), ByteVector32.Zeroes, ByteVector.fill(RoutingInfoLength)(0))

  // The 4 MSB of the first frame of the payload contains the number of frames used.
  def payloadFrameCount(payload: ByteVector): Int = {
    (payload.head >> 4) + 1
  }

  def hmac256(key: ByteVector, message: ByteVector): ByteVector32 = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(message.toArray, 0, message.length.toInt)
    val output = new Array[Byte](32)
    mac.doFinal(output, 0)
    ByteVector32(ByteVector.view(output))
  }

  def mac(key: ByteVector, message: ByteVector): ByteVector32 = hmac256(key, message)

  def generateKey(keyType: ByteVector, secret: ByteVector32): ByteVector32 = hmac256(keyType, secret)

  def generateKey(keyType: String, secret: ByteVector32): ByteVector32 = generateKey(ByteVector.view(keyType.getBytes("UTF-8")), secret)

  def zeroes(length: Int): ByteVector = ByteVector.fill(length)(0)

  def generateStream(key: ByteVector, length: Int): ByteVector = ChaCha20.encrypt(zeroes(length), key, zeroes(12))

  def computeSharedSecret(pub: PublicKey, secret: PrivateKey): ByteVector32 = Crypto.sha256(pub.multiply(secret).value)

  def computeBlindingFactor(pub: PublicKey, secret: ByteVector): ByteVector32 = Crypto.sha256(pub.value ++ secret)

  def blind(pub: PublicKey, blindingFactor: ByteVector32): PublicKey = pub.multiply(PrivateKey(blindingFactor))

  def blind(pub: PublicKey, blindingFactors: Seq[ByteVector32]): PublicKey = blindingFactors.foldLeft(pub)(blind)

  /**
    * computes the ephemeral public keys and shared secrets for all nodes on the route.
    *
    * @param sessionKey this node's session key
    * @param publicKeys public keys of each node on the route
    * @return a tuple (ephemeral public keys, shared secrets)
    */
  def computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey]): (Seq[PublicKey], Seq[ByteVector32]) = {
    val ephemeralPublicKey0 = blind(PublicKey(Crypto.curve.getG), sessionKey.value)
    val secret0 = computeSharedSecret(publicKeys.head, sessionKey)
    val blindingFactor0 = computeBlindingFactor(ephemeralPublicKey0, secret0)
    computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, Seq(ephemeralPublicKey0), Seq(blindingFactor0), Seq(secret0))
  }

  @tailrec
  def computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], ephemeralPublicKeys: Seq[PublicKey], blindingFactors: Seq[ByteVector32], sharedSecrets: Seq[ByteVector32]): (Seq[PublicKey], Seq[ByteVector32]) = {
    if (publicKeys.isEmpty)
      (ephemeralPublicKeys, sharedSecrets)
    else {
      val ephemeralPublicKey = blind(ephemeralPublicKeys.last, blindingFactors.last)
      val secret = computeSharedSecret(blind(publicKeys.head, blindingFactors), sessionKey)
      val blindingFactor = computeBlindingFactor(ephemeralPublicKey, secret)
      computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, ephemeralPublicKeys :+ ephemeralPublicKey, blindingFactors :+ blindingFactor, sharedSecrets :+ secret)
    }
  }

  def generateFiller(keyType: String, sharedSecrets: Seq[ByteVector32], payloads: Seq[ByteVector]): ByteVector = {
    require(sharedSecrets.length == payloads.length, "the number of secrets should equal the number of payloads")

    (sharedSecrets zip payloads).foldLeft(ByteVector.empty)((padding, secretAndPayload) => {
      val (secret, payload) = secretAndPayload
      val payloadLength = FrameSize*payloadFrameCount(payload)
      val key = generateKey(keyType, secret)
      val padding1 = padding ++ ByteVector.fill(payloadLength)(0)
      val stream = generateStream(key, RoutingInfoLength + payloadLength).takeRight(padding1.length)
      padding1.xor(stream)
    })
  }

  case class Packet(version: Int, publicKey: ByteVector, hmac: ByteVector32, routingInfo: ByteVector) {
    require(publicKey.length == 33, "onion packet public key length should be 33")
    require(hmac.length == MacLength, s"onion packet hmac length should be $MacLength")
    require(routingInfo.length == RoutingInfoLength, s"onion packet routing info length should be $RoutingInfoLength")

    def isLastPacket: Boolean = hmac == ByteVector32.Zeroes

    def serialize: ByteVector = Packet.write(this)
  }

  object Packet {
    def read(in: InputStream): Packet = {
      val version = in.read
      val publicKey = new Array[Byte](33)
      in.read(publicKey)
      val routingInfo = new Array[Byte](RoutingInfoLength)
      in.read(routingInfo)
      val hmac = new Array[Byte](MacLength)
      in.read(hmac)
      Packet(version, ByteVector.view(publicKey), ByteVector32(ByteVector.view(hmac)), ByteVector.view(routingInfo))
    }

    def read(in: ByteVector): Packet = read(new ByteArrayInputStream(in.toArray))

    def write(packet: Packet, out: OutputStream): OutputStream = {
      out.write(packet.version)
      out.write(packet.publicKey.toArray)
      out.write(packet.routingInfo.toArray)
      out.write(packet.hmac.toArray)
      out
    }

    def write(packet: Packet): ByteVector = {
      val out = new ByteArrayOutputStream(PacketLength)
      write(packet, out)
      ByteVector.view(out.toByteArray)
    }

    def isLastPacket(packet: ByteVector): Boolean = Packet.read(packet).hmac == ByteVector32.Zeroes
  }

  /**
    *
    * @param payload      payload for this node
    * @param nextPacket   packet for the next node
    * @param sharedSecret shared secret for the sending node, which we will need to return error messages
    */
  case class ParsedPacket(payload: ByteVector, nextPacket: Packet, sharedSecret: ByteVector32)

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
  def parsePacket(privateKey: PrivateKey, associatedData: ByteVector, rawPacket: ByteVector): Try[ParsedPacket] = Try {
    require(rawPacket.length == PacketLength, s"onion packet length is ${rawPacket.length}, it should be $PacketLength")
    val packet = Packet.read(rawPacket)
    val sharedSecret = computeSharedSecret(PublicKey(packet.publicKey), privateKey)
    val mu = generateKey("mu", sharedSecret)
    val check = mac(mu, packet.routingInfo ++ associatedData)
    require(check == packet.hmac, "invalid header mac")

    val rho = generateKey("rho", sharedSecret)
    // Since we don't know the length of the hop payload (we will learn it once we decode the first byte),
    // we have to pessimistically generate a long cipher stream.
    val stream = generateStream(rho, RoutingInfoLength + MaxPayloadLength)
    val bin = (packet.routingInfo ++ ByteVector.fill(MaxPayloadLength)(0)) xor stream

    val payloadLength = payloadFrameCount(bin)*FrameSize
    val payload = bin.take(payloadLength-MacLength)

    val hmac = ByteVector32(bin.slice(payloadLength-MacLength, payloadLength))
    val nextRouteInfo = bin.drop(payloadLength).take(RoutingInfoLength)
    val nextPubKey = blind(PublicKey(packet.publicKey), computeBlindingFactor(PublicKey(packet.publicKey), sharedSecret))

    ParsedPacket(payload, Packet(Version, nextPubKey.value, hmac, nextRouteInfo), sharedSecret)
  }

  @tailrec
  private def extractSharedSecrets(packet: ByteVector, privateKey: PrivateKey, associatedData: ByteVector32, acc: Seq[ByteVector32] = Nil): Try[Seq[ByteVector32]] = {
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
    * - then you call makeNextPacket(...) until you've built the final onion packet that will be sent to the first node
    * in the route
    *
    * @param payload             payload for this packet
    * @param associatedData      associated data
    * @param ephemeralPublicKey ephemeral key for this packet
    * @param sharedSecret        shared secret
    * @param packet              current packet (1 + all zeroes if this is the last packet)
    * @param routingInfoFiller   optional routing info filler, needed only when you're constructing the last packet
    * @return the next packet
    */
  private def makeNextPacket(payload: ByteVector, associatedData: ByteVector32, ephemeralPublicKey: ByteVector, sharedSecret: ByteVector32, packet: Packet, routingInfoFiller: ByteVector = ByteVector.empty): Packet = {
    require(payload.length <= MaxPayloadLength-MacLength, s"packet payload cannot exceed ${MaxPayloadLength-MacLength} bytes")
    require((payload.length+MacLength) % FrameSize == 0, "the payload and mac should use a discrete number of frames")

    val nextRoutingInfo = {
      val routingInfo1 = payload ++ packet.hmac ++ packet.routingInfo.dropRight(payload.length + MacLength)
      val routingInfo2 = routingInfo1 xor generateStream(generateKey("rho", sharedSecret), RoutingInfoLength)
      routingInfo2.dropRight(routingInfoFiller.length) ++ routingInfoFiller
    }

    val nextHmac = mac(generateKey("mu", sharedSecret), nextRoutingInfo ++ associatedData)
    val nextPacket = Packet(Version, ephemeralPublicKey, nextHmac, nextRoutingInfo)
    nextPacket
  }


  /**
    *
    * @param packet        onion packet
    * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
    *                      packet. Empty if you're just forwarding the packet to the next node
    */
  case class PacketAndSecrets(packet: Packet, sharedSecrets: Seq[(ByteVector32, PublicKey)])

  /**
    * A properly decoded error from a node in the route
    *
    * @param originNode     public key of the node that generated the failure.
    * @param failureMessage friendly error message.
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
  def makePacket(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[ByteVector], associatedData: ByteVector32): PacketAndSecrets = {
    require(payloadFrameCount(payloads.last) == 1, "last packet should use a single frame")

    val (ephemeralPublicKeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", sharedsecrets.dropRight(1), payloads.dropRight(1))

    val lastPacket = makeNextPacket(payloads.last, associatedData, ephemeralPublicKeys.last.value, sharedsecrets.last, LAST_PACKET, filler)

    @tailrec
    def loop(hoppayloads: Seq[ByteVector], ephkeys: Seq[PublicKey], sharedSecrets: Seq[ByteVector32], packet: Packet): Packet = {
      if (hoppayloads.isEmpty) packet else {
        val nextPacket = makeNextPacket(hoppayloads.last, associatedData, ephkeys.last.value, sharedSecrets.last, packet)
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
  def createErrorPacket(sharedSecret: ByteVector32, failure: FailureMessage): ByteVector = {
    val message: ByteVector = FailureMessageCodecs.failureMessageCodec.encode(failure).require.toByteVector
    require(message.length <= MaxErrorPayloadLength, s"error message length is ${message.length}, it must be less than $MaxErrorPayloadLength")
    val um = Sphinx.generateKey("um", sharedSecret)
    val padlen = MaxErrorPayloadLength - message.length
    val payload = Protocol.writeUInt16(message.length.toInt, ByteOrder.BIG_ENDIAN) ++ message ++ Protocol.writeUInt16(padlen.toInt, ByteOrder.BIG_ENDIAN) ++ ByteVector.fill(padlen.toInt)(0)
    logger.debug(s"um key: $um")
    logger.debug(s"error payload: ${payload.toHex}")
    logger.debug(s"raw error packet: ${(Sphinx.mac(um, payload) ++ payload).toHex}")
    forwardErrorPacket(Sphinx.mac(um, payload) ++ payload, sharedSecret)
  }

  /**
    *
    * @param packet error packet
    * @return the failure message that is embedded in the error packet
    */
  private def extractFailureMessage(packet: ByteVector): FailureMessage = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    val (mac, payload) = packet.splitAt(Sphinx.MacLength)
    val len = Protocol.uint16(payload.toArray, ByteOrder.BIG_ENDIAN)
    require((len >= 0) && (len <= MaxErrorPayloadLength), s"message length must be less than $MaxErrorPayloadLength")
    FailureMessageCodecs.failureMessageCodec.decode(BitVector(payload.drop(2).take(len))).require.value
  }

  /**
    *
    * @param packet       error packet
    * @param sharedSecret destination node's shared secret
    * @return an obfuscated error packet that can be sent to the destination node
    */
  def forwardErrorPacket(packet: ByteVector, sharedSecret: ByteVector32): ByteVector = {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")
    val key = generateKey("ammag", sharedSecret)
    val stream = generateStream(key, ErrorPacketLength)
    logger.debug(s"ammag key: $key")
    logger.debug(s"error stream: $stream")
    packet xor stream
  }

  /**
    *
    * @param sharedSecret this node's shared secret
    * @param packet       error packet
    * @return true if the packet's mac is valid, which means that it has been properly de-obfuscated
    */
  private def checkMac(sharedSecret: ByteVector32, packet: ByteVector): Boolean = {
    val (mac, payload) = packet.splitAt(Sphinx.MacLength)
    val um = Sphinx.generateKey("um", sharedSecret)
    ByteVector32(mac) == Sphinx.mac(um, payload)
  }

  /**
    * Parse and de-obfuscate an error packet. Node shared secrets are applied until the packet's MAC becomes valid,
    * which means that it was sent by the corresponding node.
    *
    * @param packet        error packet
    * @param sharedSecrets nodes shared secrets
    * @return Success(secret, failure message) if the origin of the packet could be identified and the packet de-obfuscated, Failure otherwise
    */
  def parseErrorPacket(packet: ByteVector, sharedSecrets: Seq[(ByteVector32, PublicKey)]): Try[ErrorPacket] = Try {
    require(packet.length == ErrorPacketLength, s"invalid error packet length ${packet.length}, must be $ErrorPacketLength")

    @tailrec
    def loop(packet: ByteVector, sharedSecrets: Seq[(ByteVector32, PublicKey)]): ErrorPacket = sharedSecrets match {
      case Nil => throw new RuntimeException(s"couldn't parse error packet=$packet with sharedSecrets=$sharedSecrets")
      case (secret, pubkey) :: tail =>
        val packet1 = forwardErrorPacket(packet, secret)
        if (checkMac(secret, packet1)) ErrorPacket(pubkey, extractFailureMessage(packet1)) else loop(packet1, tail)
    }

    loop(packet, sharedSecrets)
  }
}

