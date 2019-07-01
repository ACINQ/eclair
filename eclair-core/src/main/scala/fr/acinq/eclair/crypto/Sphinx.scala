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
import fr.acinq.eclair.wire.{FailureMessage, FailureMessageCodecs, CommonCodecs}
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

  val PubKeyLength = 33

  // We use hmac which returns 32-bytes message authentication codes.
  val MacLength = 32

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
    * Compute the ephemeral public keys and shared secrets for all nodes on the route.
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

  /**
    * Return the number of bytes that should be read to extract the per-hop payload (data and mac).
    */
  def currentHopLength(payload: ByteVector): Int = {
    payload.head match {
      case 0 =>
        // The 1.0 BOLT spec used 20 fixed-size 65-bytes frames inside the onion payload.
        // The first byte of the frame (called `realm`) was set to 0x00, followed by 32 bytes of hop data and a 32-bytes mac.
        // The 1.1 BOLT spec changed that format to use variable-length per-hop payloads.
        65
      case _ =>
        // For non-legacy packets the first bytes are a varint encoding the length of the payload data (not including mac).
        // Since messages are always smaller than 65535 bytes, the varint will either be 1 or 3 bytes long.
        val dataLength = CommonCodecs.varintoverflow.decode(BitVector(payload.take(3))).require.value.toInt
        val varintLength = dataLength match {
          case i if i < 253 => 1
          case _ => 3
        }
        varintLength + dataLength + MacLength
    }
  }

  /**
    * Our Sphinx onion packets have the following format:
    *   - version (1 byte)
    *   - ephemeral public key (33 bytes)
    *   - encrypted onion payload (variable size)
    *   - hmac of the whole packet (32 bytes)
    */
  case class Packet(version: Int, publicKey: ByteVector, hmac: ByteVector32, onionPayload: ByteVector) {
    require(publicKey.length == PubKeyLength, s"onion packet public key length should be $PubKeyLength")
    require(hmac.length == MacLength, s"onion packet hmac length should be $MacLength")

    val length = 1 + PubKeyLength + onionPayload.length.toInt + MacLength

    def isLastPacket: Boolean = hmac == ByteVector32.Zeroes

    def write(out: OutputStream): OutputStream = {
      out.write(version)
      out.write(publicKey.toArray)
      out.write(onionPayload.toArray)
      out.write(hmac.toArray)
      out
    }

    def serialize: ByteVector = {
      val out = new ByteArrayOutputStream(length)
      write(out)
      ByteVector.view(out.toByteArray)
    }
  }

  /**
    * Decrypting the received onion packet yields a ParsedPacket.
    *
    * @param payload      payload for this node
    * @param nextPacket   packet for the next node
    * @param sharedSecret shared secret for the sending node, which we will need to return error messages
    */
  case class ParsedPacket(payload: ByteVector, nextPacket: Packet, sharedSecret: ByteVector32)

  /**
    * A Packet with all the associated shared secrets.
    *
    * @param packet        onion packet
    * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
    *                      packet. Empty if you're just forwarding the packet to the next node
    */
  case class PacketAndSecrets(packet: Packet, sharedSecrets: Seq[(ByteVector32, PublicKey)])

  sealed trait OnionPacket {

    // Packet version. Note that since this value is outside of the onion encrypted payload, intermediate hops may or
    // may not use this value when forwarding the packet to the next node.
    val Version = 0.toByte

    // Length of the obfuscated onion payload.
    val PayloadLength: Int

    // Length of the whole packet.
    def PacketLength = 1 + PubKeyLength + PayloadLength + MacLength

    // Packet construction starts with an empty packet (all zeroes except for the version byte).
    def EMPTY_PACKET = Packet(Version, ByteVector.fill(PubKeyLength)(0), ByteVector32.Zeroes, ByteVector.fill(PayloadLength)(0))

    def read(in: InputStream): Packet = {
      val version = in.read
      val publicKey = new Array[Byte](PubKeyLength)
      in.read(publicKey)
      val onionPayload = new Array[Byte](PayloadLength)
      in.read(onionPayload)
      val hmac = new Array[Byte](MacLength)
      in.read(hmac)
      Packet(version, ByteVector.view(publicKey), ByteVector32(ByteVector.view(hmac)), ByteVector.view(onionPayload))
    }

    def read(in: ByteVector): Packet = read(new ByteArrayInputStream(in.toArray))

    /**
      * Generate a deterministic filler to prevent intermediate nodes from knowing their position in the route.
      * See https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#filler-generation
      *
      * @param keyType       type of key used (depends on the onion we're building)
      * @param sharedSecrets shared secrets for all the hops
      * @param payloads      payloads for all the hops
      * @return filler bytes
      */
    def generateFiller(keyType: String, sharedSecrets: Seq[ByteVector32], payloads: Seq[ByteVector]): ByteVector = {
      require(sharedSecrets.length == payloads.length, "the number of secrets should equal the number of payloads")

      (sharedSecrets zip payloads).foldLeft(ByteVector.empty)((padding, secretAndPayload) => {
        val (secret, perHopPayload) = secretAndPayload
        val perHopPayloadLength = currentHopLength(perHopPayload)
        require(perHopPayloadLength == perHopPayload.length + MacLength, s"invalid payload: length isn't correctly encoded: $perHopPayload")
        val key = generateKey(keyType, secret)
        val padding1 = padding ++ ByteVector.fill(perHopPayloadLength)(0)
        val stream = generateStream(key, PayloadLength + perHopPayloadLength).takeRight(padding1.length)
        padding1.xor(stream)
      })
    }

    /**
      * Parse the incoming packet, decrypts the payload and builds the packet for the next node.
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
      val packet = read(rawPacket)
      val sharedSecret = computeSharedSecret(PublicKey(packet.publicKey), privateKey)
      val mu = generateKey("mu", sharedSecret)
      val check = mac(mu, packet.onionPayload ++ associatedData)
      require(check == packet.hmac, "invalid header mac")

      val rho = generateKey("rho", sharedSecret)
      // Since we don't know the length of the hop payload (we will learn it once we decode the first byte),
      // we have to pessimistically generate a long cipher stream.
      val stream = generateStream(rho, 2 * PayloadLength)
      val bin = (packet.onionPayload ++ ByteVector.fill(PayloadLength)(0)) xor stream

      val perHopPayloadLength = currentHopLength(bin)
      val perHopPayload = bin.take(perHopPayloadLength - MacLength)

      val hmac = ByteVector32(bin.slice(perHopPayloadLength - MacLength, perHopPayloadLength))
      val nextOnionPayload = bin.drop(perHopPayloadLength).take(PayloadLength)
      val nextPubKey = blind(PublicKey(packet.publicKey), computeBlindingFactor(PublicKey(packet.publicKey), sharedSecret))

      ParsedPacket(perHopPayload, Packet(Version, nextPubKey.value, hmac, nextOnionPayload), sharedSecret)
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
      * @param payload            payload for this packet
      * @param associatedData     associated data
      * @param ephemeralPublicKey ephemeral key for this packet
      * @param sharedSecret       shared secret
      * @param packet             current packet (1 + all zeroes if this is the last packet)
      * @param onionPayloadFiller optional onion payload filler, needed only when you're constructing the last packet
      * @return the next packet
      */
    private def makeNextPacket(payload: ByteVector, associatedData: ByteVector32, ephemeralPublicKey: ByteVector, sharedSecret: ByteVector32, packet: Packet, onionPayloadFiller: ByteVector = ByteVector.empty): Packet = {
      require(payload.length <= PayloadLength - MacLength, s"packet payload cannot exceed ${PayloadLength - MacLength} bytes")

      val nextOnionPayload = {
        val onionPayload1 = payload ++ packet.hmac ++ packet.onionPayload.dropRight(payload.length + MacLength)
        val onionPayload2 = onionPayload1 xor generateStream(generateKey("rho", sharedSecret), PayloadLength)
        onionPayload2.dropRight(onionPayloadFiller.length) ++ onionPayloadFiller
      }

      val nextHmac = mac(generateKey("mu", sharedSecret), nextOnionPayload ++ associatedData)
      val nextPacket = Packet(Version, ephemeralPublicKey, nextHmac, nextOnionPayload)
      nextPacket
    }

    /**
      * Build an encrypted onion packet that contains payloads for all nodes in the list
      *
      * @param sessionKey     session key
      * @param publicKeys     node public keys (one per node)
      * @param payloads       payloads (one per node)
      * @param associatedData associated data
      * @return an OnionPacket(onion packet, shared secrets). the onion packet can be sent to the first node in the list, and the
      *         shared secrets (one per node) can be used to parse returned error messages if needed
      */
    def makePacket(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[ByteVector], associatedData: ByteVector32): PacketAndSecrets = {
      val (ephemeralPublicKeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
      val filler = generateFiller("rho", sharedsecrets.dropRight(1), payloads.dropRight(1))

      val lastPacket = makeNextPacket(payloads.last, associatedData, ephemeralPublicKeys.last.value, sharedsecrets.last, EMPTY_PACKET, filler)

      @tailrec
      def loop(hopPayloads: Seq[ByteVector], ephKeys: Seq[PublicKey], sharedSecrets: Seq[ByteVector32], packet: Packet): Packet = {
        if (hopPayloads.isEmpty) packet else {
          val nextPacket = makeNextPacket(hopPayloads.last, associatedData, ephKeys.last.value, sharedSecrets.last, packet)
          loop(hopPayloads.dropRight(1), ephKeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
        }
      }

      val packet = loop(payloads.dropRight(1), ephemeralPublicKeys.dropRight(1), sharedsecrets.dropRight(1), lastPacket)
      PacketAndSecrets(packet, sharedsecrets.zip(publicKeys))
    }

  }

  /**
    * A payment onion packet is used when offering an HTLC to a remote node.
    */
  object PaymentPacket extends OnionPacket {

    override val PayloadLength = 1300

  }

  /**
    * A properly decoded error from a node in the route.
    * It has the following format:
    * +----------------+----------------------------------+-----------------+----------------------+-----+
    * | HMAC(32 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
    * +----------------+----------------------------------+-----------------+----------------------+-----+
    * with failure message length + pad length = 256
    *
    * @param originNode     public key of the node that generated the failure.
    * @param failureMessage friendly error message.
    */
  case class ErrorPacket(originNode: PublicKey, failureMessage: FailureMessage)

  object ErrorPacket {

    val MaxPayloadLength = 256
    val PacketLength = MacLength + MaxPayloadLength + 2 + 2

    /**
      * Create an error packet that will be returned to the sender.
      * Each intermediate hop will add a layer of encryption and forward to the previous hop.
      * Note that malicious intermediate hops may drop the packet or alter it (which breaks the mac).
      *
      * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
      *                     was created or forwarded: see makePacket() and makeNextPacket()
      * @param failure      failure message
      * @return an error packet that can be sent to the destination node
      */
    def createPacket(sharedSecret: ByteVector32, failure: FailureMessage): ByteVector = {
      val message: ByteVector = FailureMessageCodecs.failureMessageCodec.encode(failure).require.toByteVector
      require(message.length <= MaxPayloadLength, s"error message length is ${message.length}, it must be less than $MaxPayloadLength")
      val um = generateKey("um", sharedSecret)
      val padLength = MaxPayloadLength - message.length
      val payload = Protocol.writeUInt16(message.length.toInt, ByteOrder.BIG_ENDIAN) ++ message ++ Protocol.writeUInt16(padLength.toInt, ByteOrder.BIG_ENDIAN) ++ ByteVector.fill(padLength.toInt)(0)
      logger.debug(s"um key: $um")
      logger.debug(s"error payload: ${payload.toHex}")
      logger.debug(s"raw error packet: ${(mac(um, payload) ++ payload).toHex}")
      forwardPacket(mac(um, payload) ++ payload, sharedSecret)
    }

    /**
      * Extract the failure message from an error packet.
      *
      * @param packet error packet
      * @return the failure message that is embedded in the error packet
      */
    private def extractFailureMessage(packet: ByteVector): FailureMessage = {
      require(packet.length == PacketLength, s"invalid error packet length ${packet.length}, must be $PacketLength")
      val (_, payload) = packet.splitAt(MacLength)
      val len = Protocol.uint16(payload.toArray, ByteOrder.BIG_ENDIAN)
      require((len >= 0) && (len <= MaxPayloadLength), s"message length must be less than $MaxPayloadLength")
      FailureMessageCodecs.failureMessageCodec.decode(BitVector(payload.drop(2).take(len))).require.value
    }

    /**
      * Forward an error packet to the previous hop.
      *
      * @param packet       error packet
      * @param sharedSecret destination node's shared secret
      * @return an obfuscated error packet that can be sent to the destination node
      */
    def forwardPacket(packet: ByteVector, sharedSecret: ByteVector32): ByteVector = {
      require(packet.length == PacketLength, s"invalid error packet length ${packet.length}, must be $PacketLength")
      val key = generateKey("ammag", sharedSecret)
      val stream = generateStream(key, PacketLength)
      logger.debug(s"ammag key: $key")
      logger.debug(s"error stream: $stream")
      packet xor stream
    }

    /**
      * Check the mac of an error packet.
      * Note that malicious nodes in the route may have altered the packet, thus breaking the mac.
      *
      * @param sharedSecret this node's shared secret
      * @param packet       error packet
      * @return true if the packet's mac is valid, which means that it has been properly de-obfuscated
      */
    private def checkMac(sharedSecret: ByteVector32, packet: ByteVector): Boolean = {
      val (packetMac, payload) = packet.splitAt(MacLength)
      val um = generateKey("um", sharedSecret)
      ByteVector32(packetMac) == mac(um, payload)
    }

    /**
      * Parse and de-obfuscate an error packet. Node shared secrets are applied until the packet's MAC becomes valid,
      * which means that it was sent by the corresponding node.
      *
      * @param packet        error packet
      * @param sharedSecrets nodes shared secrets
      * @return Success(secret, failure message) if the origin of the packet could be identified and the packet de-obfuscated, Failure otherwise
      */
    def parsePacket(packet: ByteVector, sharedSecrets: Seq[(ByteVector32, PublicKey)]): Try[ErrorPacket] = Try {
      require(packet.length == PacketLength, s"invalid error packet length ${packet.length}, must be $PacketLength")

      @tailrec
      def loop(packet: ByteVector, sharedSecrets: Seq[(ByteVector32, PublicKey)]): ErrorPacket = sharedSecrets match {
        case Nil => throw new RuntimeException(s"couldn't parse error packet=$packet with sharedSecrets=$sharedSecrets")
        case (secret, pubkey) :: tail =>
          val packet1 = forwardPacket(packet, secret)
          if (checkMac(secret, packet1)) ErrorPacket(pubkey, extractFailureMessage(packet1)) else loop(packet1, tail)
      }

      loop(packet, sharedSecrets)
    }

  }

}

