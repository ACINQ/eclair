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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.wire
import fr.acinq.eclair.wire.{FailureMessage, FailureMessageCodecs, OnionCodecs}
import grizzled.slf4j.Logging
import scodec.Attempt
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
 * Created by fabrice on 13/01/17.
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
 */
object Sphinx extends Logging {

  // We use HMAC-SHA256 which returns 32-bytes message authentication codes.
  val MacLength = 32

  def mac(key: ByteVector, message: ByteVector): ByteVector32 = Mac32.hmac256(key, message)

  def generateKey(keyType: ByteVector, secret: ByteVector32): ByteVector32 = Mac32.hmac256(keyType, secret)

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
   * @param sessionKey this node's session key.
   * @param publicKeys public keys of each node on the route.
   * @return a tuple (ephemeral public keys, shared secrets).
   */
  def computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey]): (Seq[PublicKey], Seq[ByteVector32]) = {
    val ephemeralPublicKey0 = blind(PublicKey(Crypto.curve.getG), sessionKey.value)
    val secret0 = computeSharedSecret(publicKeys.head, sessionKey)
    val blindingFactor0 = computeBlindingFactor(ephemeralPublicKey0, secret0)
    computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.tail, Seq(ephemeralPublicKey0), Seq(blindingFactor0), Seq(secret0))
  }

  @tailrec
  private def computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], ephemeralPublicKeys: Seq[PublicKey], blindingFactors: Seq[ByteVector32], sharedSecrets: Seq[ByteVector32]): (Seq[PublicKey], Seq[ByteVector32]) = {
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
   * Peek at the first bytes of the per-hop payload to extract its length.
   */
  def peekPayloadLength(payload: ByteVector): Int = {
    payload.head match {
      case 0 =>
        // The 1.0 BOLT spec used 65-bytes frames inside the onion payload.
        // The first byte of the frame (called `realm`) is set to 0x00, followed by 32 bytes of per-hop data, followed by a 32-bytes mac.
        65
      case _ =>
        // The 1.1 BOLT spec changed the frame format to use variable-length per-hop payloads.
        // The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
        // Since messages are always smaller than 65535 bytes, this varint will either be 1 or 3 bytes long.
        MacLength + OnionCodecs.payloadLengthDecoder.decode(payload.bits).require.value.toInt
    }
  }

  /**
   * Decrypting an onion packet yields a payload for the current node and the encrypted packet for the next node.
   *
   * @param payload      decrypted payload for this node.
   * @param nextPacket   packet for the next node.
   * @param sharedSecret shared secret for the sending node, which we will need to return failure messages.
   */
  case class DecryptedPacket(payload: ByteVector, nextPacket: wire.OnionRoutingPacket, sharedSecret: ByteVector32) {

    val isLastPacket: Boolean = nextPacket.hmac == ByteVector32.Zeroes

  }

  /**
   * A encrypted onion packet with all the associated shared secrets.
   *
   * @param packet        encrypted onion packet.
   * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
   *                      packet. Empty if you're just forwarding the packet to the next node.
   */
  case class PacketAndSecrets(packet: wire.OnionRoutingPacket, sharedSecrets: Seq[(ByteVector32, PublicKey)])

  sealed trait OnionRoutingPacket {

    /**
     * Supported packet version. Note that since this value is outside of the onion encrypted payload, intermediate
     * nodes may or may not use this value when forwarding the packet to the next node.
     */
    def Version = 0

    /**
     * Length of the encrypted onion payload.
     */
    def PayloadLength: Int

    /**
     * Generate a deterministic filler to prevent intermediate nodes from knowing their position in the route.
     * See https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#filler-generation
     *
     * @param keyType       type of key used (depends on the onion we're building).
     * @param sharedSecrets shared secrets for all the hops.
     * @param payloads      payloads for all the hops.
     * @return filler bytes.
     */
    def generateFiller(keyType: String, sharedSecrets: Seq[ByteVector32], payloads: Seq[ByteVector]): ByteVector = {
      require(sharedSecrets.length == payloads.length, "the number of secrets should equal the number of payloads")

      (sharedSecrets zip payloads).foldLeft(ByteVector.empty)((padding, secretAndPayload) => {
        val (secret, perHopPayload) = secretAndPayload
        val perHopPayloadLength = peekPayloadLength(perHopPayload)
        require(perHopPayloadLength == perHopPayload.length + MacLength, s"invalid payload: length isn't correctly encoded: $perHopPayload")
        val key = generateKey(keyType, secret)
        val padding1 = padding ++ ByteVector.fill(perHopPayloadLength)(0)
        val stream = generateStream(key, PayloadLength + perHopPayloadLength).takeRight(padding1.length)
        padding1.xor(stream)
      })
    }

    /**
     * Decrypt the incoming packet, extract the per-hop payload and build the packet for the next node.
     *
     * @param privateKey     this node's private key.
     * @param associatedData associated data.
     * @param packet         packet received by this node.
     * @return a DecryptedPacket(payload, packet, shared secret) object where:
     *         - payload is the per-hop payload for this node.
     *         - packet is the next packet, to be forwarded using the info that is given in the payload.
     *         - shared secret is the secret we share with the node that sent the packet. We need it to propagate
     *         failure messages upstream.
     *         or a BadOnion error containing the hash of the invalid onion.
     */
    def peel(privateKey: PrivateKey, associatedData: ByteVector, packet: wire.OnionRoutingPacket): Either[wire.BadOnion, DecryptedPacket] = packet.version match {
      case 0 => Try(PublicKey(packet.publicKey, checkValid = true)) match {
        case Success(packetEphKey) =>
          val sharedSecret = computeSharedSecret(packetEphKey, privateKey)
          val mu = generateKey("mu", sharedSecret)
          val check = mac(mu, packet.payload ++ associatedData)
          if (check == packet.hmac) {
            val rho = generateKey("rho", sharedSecret)
            // Since we don't know the length of the per-hop payload (we will learn it once we decode the first bytes),
            // we have to pessimistically generate a long cipher stream.
            val stream = generateStream(rho, 2 * PayloadLength)
            val bin = (packet.payload ++ ByteVector.fill(PayloadLength)(0)) xor stream

            val perHopPayloadLength = peekPayloadLength(bin)
            val perHopPayload = bin.take(perHopPayloadLength - MacLength)

            val hmac = ByteVector32(bin.slice(perHopPayloadLength - MacLength, perHopPayloadLength))
            val nextOnionPayload = bin.drop(perHopPayloadLength).take(PayloadLength)
            val nextPubKey = blind(packetEphKey, computeBlindingFactor(packetEphKey, sharedSecret))

            Right(DecryptedPacket(perHopPayload, wire.OnionRoutingPacket(Version, nextPubKey.value, nextOnionPayload, hmac), sharedSecret))
          } else {
            Left(wire.InvalidOnionHmac(hash(packet)))
          }
        case Failure(_) => Left(wire.InvalidOnionKey(hash(packet)))
      }
      case _ => Left(wire.InvalidOnionVersion(hash(packet)))
    }

    /**
     * Wrap the given packet in an additional layer of onion encryption, adding an encrypted payload for a specific
     * node.
     *
     * Packets are constructed in reverse order:
     * - you first create the packet for the final recipient
     * - then you call wrap(...) until you've built the final onion packet that will be sent to the first node in the
     * route
     *
     * @param payload            per-hop payload for the target node.
     * @param associatedData     associated data.
     * @param ephemeralPublicKey ephemeral key shared with the target node.
     * @param sharedSecret       shared secret with this hop.
     * @param packet             current packet (None if the packet hasn't been initialized).
     * @param onionPayloadFiller optional onion payload filler, needed only when you're constructing the last packet.
     * @return the next packet.
     */
    def wrap(payload: ByteVector, associatedData: ByteVector32, ephemeralPublicKey: PublicKey, sharedSecret: ByteVector32, packet: Option[wire.OnionRoutingPacket], onionPayloadFiller: ByteVector = ByteVector.empty): wire.OnionRoutingPacket = {
      require(payload.length <= PayloadLength - MacLength, s"packet payload cannot exceed ${PayloadLength - MacLength} bytes")

      val (currentMac, currentPayload): (ByteVector32, ByteVector) = packet match {
        // Packet construction starts with an empty mac and payload.
        case None => (ByteVector32.Zeroes, ByteVector.fill(PayloadLength)(0))
        case Some(p) => (p.hmac, p.payload)
      }

      val nextOnionPayload = {
        val onionPayload1 = payload ++ currentMac ++ currentPayload.dropRight(payload.length + MacLength)
        val onionPayload2 = onionPayload1 xor generateStream(generateKey("rho", sharedSecret), PayloadLength)
        onionPayload2.dropRight(onionPayloadFiller.length) ++ onionPayloadFiller
      }

      val nextHmac = mac(generateKey("mu", sharedSecret), nextOnionPayload ++ associatedData)
      val nextPacket = wire.OnionRoutingPacket(Version, ephemeralPublicKey.value, nextOnionPayload, nextHmac)
      nextPacket
    }

    /**
     * Create an encrypted onion packet that contains payloads for all nodes in the list.
     *
     * @param sessionKey     session key.
     * @param publicKeys     node public keys (one per node).
     * @param payloads       payloads (one per node).
     * @param associatedData associated data.
     * @return An onion packet with all shared secrets. The onion packet can be sent to the first node in the list, and
     *         the shared secrets (one per node) can be used to parse returned failure messages if needed.
     */
    def create(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[ByteVector], associatedData: ByteVector32): PacketAndSecrets = {
      val (ephemeralPublicKeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
      val filler = generateFiller("rho", sharedsecrets.dropRight(1), payloads.dropRight(1))

      val lastPacket = wrap(payloads.last, associatedData, ephemeralPublicKeys.last, sharedsecrets.last, None, filler)

      @tailrec
      def loop(hopPayloads: Seq[ByteVector], ephKeys: Seq[PublicKey], sharedSecrets: Seq[ByteVector32], packet: wire.OnionRoutingPacket): wire.OnionRoutingPacket = {
        if (hopPayloads.isEmpty) packet else {
          val nextPacket = wrap(hopPayloads.last, associatedData, ephKeys.last, sharedSecrets.last, Some(packet))
          loop(hopPayloads.dropRight(1), ephKeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
        }
      }

      val packet = loop(payloads.dropRight(1), ephemeralPublicKeys.dropRight(1), sharedsecrets.dropRight(1), lastPacket)
      PacketAndSecrets(packet, sharedsecrets.zip(publicKeys))
    }

    /**
     * When an invalid onion is received, its hash should be included in the failure message.
     */
    def hash(onion: wire.OnionRoutingPacket): ByteVector32 =
      Crypto.sha256(wire.OnionCodecs.onionRoutingPacketCodec(onion.payload.length.toInt).encode(onion).require.toByteVector)

  }

  /**
   * A payment onion packet is used when offering an HTLC to a remote node.
   */
  object PaymentPacket extends OnionRoutingPacket {
    override val PayloadLength = 1300
  }

  /**
   * A trampoline onion packet is used to defer route construction to trampoline nodes.
   * It is usually embedded inside a payment onion packet in the final node's payload.
   */
  object TrampolinePacket extends OnionRoutingPacket {
    override val PayloadLength = 400
  }

  /**
   * A properly decrypted failure from a node in the route.
   *
   * @param originNode     public key of the node that generated the failure.
   * @param failureMessage friendly failure message.
   */
  case class DecryptedFailurePacket(originNode: PublicKey, failureMessage: FailureMessage)

  object FailurePacket {

    val MaxPayloadLength = 256
    val PacketLength = MacLength + MaxPayloadLength + 2 + 2

    /**
     * Create a failure packet that will be returned to the sender.
     * Each intermediate hop will add a layer of encryption and forward to the previous hop.
     * Note that malicious intermediate hops may drop the packet or alter it (which breaks the mac).
     *
     * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
     *                     was created or forwarded: see OnionPacket.create() and OnionPacket.wrap().
     * @param failure      failure message.
     * @return a failure packet that can be sent to the destination node.
     */
    def create(sharedSecret: ByteVector32, failure: FailureMessage): ByteVector = {
      val um = generateKey("um", sharedSecret)
      val packet = FailureMessageCodecs.failureOnionCodec(Hmac256(um)).encode(failure).require.toByteVector
      logger.debug(s"um key: $um")
      logger.debug(s"raw error packet: ${packet.toHex}")
      wrap(packet, sharedSecret)
    }

    /**
     * Wrap the given packet in an additional layer of onion encryption for the previous hop.
     *
     * @param packet       failure packet.
     * @param sharedSecret destination node's shared secret.
     * @return an encrypted failure packet that can be sent to the destination node.
     */
    def wrap(packet: ByteVector, sharedSecret: ByteVector32): ByteVector = {
      if (packet.length != PacketLength) {
        logger.warn(s"invalid error packet length ${packet.length}, must be $PacketLength (malicious or buggy downstream node)")
      }
      val key = generateKey("ammag", sharedSecret)
      val stream = generateStream(key, PacketLength)
      logger.debug(s"ammag key: $key")
      logger.debug(s"error stream: $stream")
      // If we received a packet with an invalid length, we trim and pad to forward a packet with a normal length upstream.
      // This is a poor man's attempt at increasing the likelihood of the sender receiving the error.
      packet.take(PacketLength).padLeft(PacketLength) xor stream
    }

    /**
     * Decrypt a failure packet. Node shared secrets are applied until the packet's MAC becomes valid, which means that
     * it was sent by the corresponding node.
     * Note that malicious nodes in the route may have altered the packet, triggering a decryption failure.
     *
     * @param packet        failure packet.
     * @param sharedSecrets nodes shared secrets.
     * @return Success(secret, failure message) if the origin of the packet could be identified and the packet
     *         decrypted, Failure otherwise.
     */
    def decrypt(packet: ByteVector, sharedSecrets: Seq[(ByteVector32, PublicKey)]): Try[DecryptedFailurePacket] = Try {
      require(packet.length == PacketLength, s"invalid error packet length ${packet.length}, must be $PacketLength")

      @tailrec
      def loop(packet: ByteVector, sharedSecrets: Seq[(ByteVector32, PublicKey)]): DecryptedFailurePacket = sharedSecrets match {
        case Nil => throw new RuntimeException(s"couldn't parse error packet=$packet with sharedSecrets=$sharedSecrets")
        case (secret, pubkey) :: tail =>
          val packet1 = wrap(packet, secret)
          val um = generateKey("um", secret)
          FailureMessageCodecs.failureOnionCodec(Hmac256(um)).decode(packet1.toBitVector) match {
            case Attempt.Successful(value) => DecryptedFailurePacket(pubkey, value.value)
            case _ => loop(packet1, tail)
          }
      }

      loop(packet, sharedSecrets)
    }

  }

}

