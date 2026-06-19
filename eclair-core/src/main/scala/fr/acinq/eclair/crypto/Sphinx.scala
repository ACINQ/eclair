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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.EncodedNodeId
import fr.acinq.eclair.wire.protocol._
import grizzled.slf4j.Logging
import scodec.Attempt
import scodec.bits.ByteVector
import scodec.codecs.uint32

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * Created by fabrice on 13/01/17.
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
 */
object Sphinx extends Logging {

  /**
   * Supported packet version. Note that since this value is outside of the onion encrypted payload, intermediate
   * nodes may or may not use this value when forwarding the packet to the next node.
   */
  val Version = 0

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
    val ephemeralPublicKey0 = blind(PublicKey(fr.acinq.bitcoin.PublicKey.Generator), sessionKey.value)
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
    require(payload.head != 0, "legacy onion format is not supported anymore")
    // Each onion frame contains a variable-length per-hop payload.
    // The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
    // Since messages are always smaller than 65535 bytes, this varint will either be 1 or 3 bytes long.
    MacLength + PaymentOnionCodecs.payloadLengthDecoder.decode(payload.bits).require.value.toInt
  }

  /**
   * Decrypting an onion packet yields a payload for the current node and the encrypted packet for the next node.
   *
   * @param payload      decrypted payload for this node.
   * @param nextPacket   packet for the next node.
   * @param sharedSecret shared secret for the sending node, which we will need to return failure messages.
   */
  case class DecryptedPacket(payload: ByteVector, nextPacket: OnionRoutingPacket, sharedSecret: ByteVector32) {
    val isLastPacket: Boolean = nextPacket.hmac == ByteVector32.Zeroes
  }

  /** Shared secret used to encrypt the payload for a given node. */
  case class SharedSecret(secret: ByteVector32, remoteNodeId: PublicKey)

  /**
   * A encrypted onion packet with all the associated shared secrets.
   *
   * @param packet        encrypted onion packet.
   * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
   *                      packet. Empty if you're just forwarding the packet to the next node.
   */
  case class PacketAndSecrets(packet: OnionRoutingPacket, sharedSecrets: Seq[SharedSecret])

  /**
   * Generate a deterministic filler to prevent intermediate nodes from knowing their position in the route.
   * See https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#filler-generation
   *
   * @param keyType             type of key used (depends on the onion we're building).
   * @param packetPayloadLength length of the packet's encrypted onion payload (e.g. 1300 for standard payment onions).
   * @param sharedSecrets       shared secrets for all the hops.
   * @param payloads            payloads for all the hops.
   * @return filler bytes.
   */
  def generateFiller(keyType: String, packetPayloadLength: Int, sharedSecrets: Seq[ByteVector32], payloads: Seq[ByteVector]): ByteVector = {
    require(sharedSecrets.length == payloads.length, "the number of secrets should equal the number of payloads")

    (sharedSecrets zip payloads).foldLeft(ByteVector.empty)((padding, secretAndPayload) => {
      val (secret, perHopPayload) = secretAndPayload
      val perHopPayloadLength = peekPayloadLength(perHopPayload)
      require(perHopPayloadLength == perHopPayload.length + MacLength, s"invalid payload: length isn't correctly encoded: $perHopPayload")
      val key = generateKey(keyType, secret)
      val padding1 = padding ++ ByteVector.fill(perHopPayloadLength)(0)
      val stream = generateStream(key, packetPayloadLength + perHopPayloadLength).takeRight(padding1.length)
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
   *           failure messages upstream.
   *           or a BadOnion error containing the hash of the invalid onion.
   */
  def peel(privateKey: PrivateKey, associatedData: Option[ByteVector32], packet: OnionRoutingPacket): Either[BadOnion, DecryptedPacket] = packet.version match {
    case 0 => Try(PublicKey(packet.publicKey, checkValid = true)) match {
      case Success(packetEphKey) =>
        val sharedSecret = computeSharedSecret(packetEphKey, privateKey)
        val mu = generateKey("mu", sharedSecret)
        val check = mac(mu, associatedData.map(packet.payload ++ _).getOrElse(packet.payload))
        if (check == packet.hmac) {
          val rho = generateKey("rho", sharedSecret)
          // Since we don't know the length of the per-hop payload (we will learn it once we decode the first bytes),
          // we have to pessimistically generate a long cipher stream.
          val stream = generateStream(rho, 2 * packet.payload.length.toInt)
          val bin = (packet.payload ++ ByteVector.fill(packet.payload.length)(0)) xor stream
          Try(peekPayloadLength(bin)) match {
            case Success(perHopPayloadLength) =>
              val perHopPayload = bin.take(perHopPayloadLength - MacLength)
              val hmac = ByteVector32(bin.slice(perHopPayloadLength - MacLength, perHopPayloadLength))
              val nextOnionPayload = bin.drop(perHopPayloadLength).take(packet.payload.length)
              val nextPubKey = blind(packetEphKey, computeBlindingFactor(packetEphKey, sharedSecret))
              Right(DecryptedPacket(perHopPayload, OnionRoutingPacket(Version, nextPubKey.value, nextOnionPayload, hmac), sharedSecret))
            case Failure(_) =>
              Left(InvalidOnionVersion(hash(packet)))
          }
        } else {
          Left(InvalidOnionHmac(hash(packet)))
        }
      case Failure(_) => Left(InvalidOnionKey(hash(packet)))
    }
    case _ => Left(InvalidOnionVersion(hash(packet)))
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
   * @param packet             current packet or random bytes if the packet hasn't been initialized.
   * @param onionPayloadFiller optional onion payload filler, needed only when you're constructing the last packet.
   * @return the next packet.
   */
  def wrap(payload: ByteVector, associatedData: Option[ByteVector32], ephemeralPublicKey: PublicKey, sharedSecret: ByteVector32, packet: Either[ByteVector, OnionRoutingPacket], onionPayloadFiller: ByteVector = ByteVector.empty): OnionRoutingPacket = {
    val packetPayloadLength = packet match {
      case Left(startingBytes) => startingBytes.length.toInt
      case Right(p) => p.payload.length.toInt
    }
    require(payload.length <= packetPayloadLength - MacLength, s"packet per-hop payload cannot exceed ${packetPayloadLength - MacLength} bytes")

    val (currentMac, currentPayload): (ByteVector32, ByteVector) = packet match {
      // Packet construction starts with an empty mac and random payload.
      case Left(startingBytes) => (ByteVector32.Zeroes, startingBytes)
      case Right(p) => (p.hmac, p.payload)
    }
    val nextOnionPayload = {
      val onionPayload1 = payload ++ currentMac ++ currentPayload.dropRight(payload.length + MacLength)
      val onionPayload2 = onionPayload1 xor generateStream(generateKey("rho", sharedSecret), packetPayloadLength)
      onionPayload2.dropRight(onionPayloadFiller.length) ++ onionPayloadFiller
    }

    val nextHmac = mac(generateKey("mu", sharedSecret), associatedData.map(nextOnionPayload ++ _).getOrElse(nextOnionPayload))
    val nextPacket = OnionRoutingPacket(Version, ephemeralPublicKey.value, nextOnionPayload, nextHmac)
    nextPacket
  }

  def payloadsTotalSize(payloads: Seq[ByteVector]): Int = payloads.map(_.length + MacLength).sum.toInt

  /**
   * Create an encrypted onion packet that contains payloads for all nodes in the list.
   *
   * @param sessionKey          session key.
   * @param packetPayloadLength length of the packet's encrypted onion payload (e.g. 1300 for standard payment onions).
   * @param publicKeys          node public keys (one per node).
   * @param payloads            payloads (one per node).
   * @param associatedData      associated data.
   * @return An onion packet with all shared secrets. The onion packet can be sent to the first node in the list, and
   *         the shared secrets (one per node) can be used to parse returned failure messages if needed.
   */
  def create(sessionKey: PrivateKey, packetPayloadLength: Int, publicKeys: Seq[PublicKey], payloads: Seq[ByteVector], associatedData: Option[ByteVector32]): Try[PacketAndSecrets] = Try {
    require(payloadsTotalSize(payloads) <= packetPayloadLength, s"packet per-hop payloads cannot exceed $packetPayloadLength bytes")
    val (ephemeralPublicKeys, sharedSecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", packetPayloadLength, sharedSecrets.dropRight(1), payloads.dropRight(1))

    // We deterministically-derive the initial payload bytes: see https://github.com/lightningnetwork/lightning-rfc/pull/697
    val startingBytes = generateStream(generateKey("pad", sessionKey.value), packetPayloadLength)
    val lastPacket = wrap(payloads.last, associatedData, ephemeralPublicKeys.last, sharedSecrets.last, Left(startingBytes), filler)

    @tailrec
    def loop(hopPayloads: Seq[ByteVector], ephKeys: Seq[PublicKey], sharedSecrets: Seq[ByteVector32], packet: OnionRoutingPacket): OnionRoutingPacket = {
      if (hopPayloads.isEmpty) packet else {
        val nextPacket = wrap(hopPayloads.last, associatedData, ephKeys.last, sharedSecrets.last, Right(packet))
        loop(hopPayloads.dropRight(1), ephKeys.dropRight(1), sharedSecrets.dropRight(1), nextPacket)
      }
    }

    val packet = loop(payloads.dropRight(1), ephemeralPublicKeys.dropRight(1), sharedSecrets.dropRight(1), lastPacket)
    PacketAndSecrets(packet, sharedSecrets.zip(publicKeys).map { case (secret, remoteNodeId) => SharedSecret(secret, remoteNodeId) })
  }

  /**
   * When an invalid onion is received, its hash should be included in the failure message.
   */
  def hash(onion: OnionRoutingPacket): ByteVector32 =
    Crypto.sha256(OnionRoutingCodecs.onionRoutingPacketCodec(onion.payload.length.toInt).encode(onion).require.toByteVector)

  /**
   * A properly decrypted failure from a node in the route.
   *
   * @param originNode     public key of the node that generated the failure.
   * @param index          position in the route of the node that generated the failure.
   * @param failureMessage friendly failure message.
   */
  case class DecryptedFailurePacket(originNode: PublicKey, index: Int, failureMessage: FailureMessage)

  /**
   * The downstream failure could not be decrypted.
   *
   * @param unwrapped       encrypted failure packet after unwrapping using our shared secrets.
   * @param attribution_opt attribution data after unwrapping using our shared secrets
   */
  case class CannotDecryptFailurePacket(unwrapped: ByteVector, attribution_opt: Option[ByteVector])

  case class HoldTime(duration: FiniteDuration, remoteNodeId: PublicKey)

  case class HtlcFailure(holdTimes: Seq[HoldTime], failure: Either[CannotDecryptFailurePacket, DecryptedFailurePacket])

  case class HtlcSuccess(holdTimes: Seq[HoldTime], fulfillmentPayload_opt: Option[ByteVector], remainingAttribution_opt: Option[ByteVector])

  object FailurePacket {
    /**
     * Create a failure packet that needs to be wrapped before being returned to the sender.
     * Each intermediate hop will add a layer of encryption and forward to the previous hop.
     * Note that malicious intermediate hops may drop the packet or alter it (which breaks the mac).
     *
     * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
     *                     was created or forwarded: see OnionPacket.create() and OnionPacket.wrap().
     * @param failure      failure message.
     * @return a failure packet that still needs to be wrapped before being sent to the destination node.
     */
    def create(sharedSecret: ByteVector32, failure: FailureMessage): ByteVector = {
      val um = generateKey("um", sharedSecret)
      val packet = FailureMessageCodecs.failureOnionCodec(Hmac256(um)).encode(failure).require.toByteVector
      logger.debug(s"um key: $um")
      logger.debug(s"raw error packet: ${packet.toHex}")
      packet
    }

    /**
     * Wrap the given packet in an additional layer of onion encryption for the previous hop.
     *
     * @param packet       failure packet.
     * @param sharedSecret destination node's shared secret.
     * @return an encrypted failure packet that can be sent to the destination node.
     */
    def wrap(packet: ByteVector, sharedSecret: ByteVector32): ByteVector = {
      val key = generateKey("ammag", sharedSecret)
      val stream = generateStream(key, packet.length.toInt)
      logger.debug(s"ammag key: $key")
      logger.debug(s"error stream: $stream")
      packet xor stream
    }

    /**
     * Decrypt a failure packet. Node shared secrets are applied until the packet's MAC becomes valid, which means that
     * it was sent by the corresponding node.
     * Note that malicious nodes in the route may have altered the packet, triggering a decryption failure.
     *
     * @param packet          failure packet.
     * @param attribution_opt attribution data for this failure packet.
     * @param sharedSecrets   nodes shared secrets.
     * @return failure message if the origin of the packet could be identified and the packet decrypted, the unwrapped
     *         failure packet otherwise.
     */
    def decrypt(packet: ByteVector, attribution_opt: Option[ByteVector], sharedSecrets: Seq[SharedSecret], index: Int = 1): HtlcFailure = {
      sharedSecrets match {
        case Nil => HtlcFailure(Nil, Left(CannotDecryptFailurePacket(packet, attribution_opt)))
        case ss :: tail =>
          val packet1 = wrap(packet, ss.secret)
          val attribution1_opt = attribution_opt.flatMap(attribution => Attribution.decrypt(attribution, Some(packet1), ss, sharedSecrets.length))
          val um = generateKey("um", ss.secret)
          val downstream = FailureMessageCodecs.failureOnionCodec(Hmac256(um)).decode(packet1.toBitVector) match {
            // We've identified the failing node: no need to continue decrypting.
            case Attempt.Successful(value) => HtlcFailure(Nil, Right(DecryptedFailurePacket(ss.remoteNodeId, index, value.value)))
              // The failing node may be downstream: we keep decrypting.
            case _ => decrypt(packet1, attribution1_opt.map(_.downstreamAttribution), tail, index + 1)
          }
          HtlcFailure(attribution1_opt.map(_.holdTime).toSeq ++ downstream.holdTimes, downstream.failure)
      }
    }
  }

  object SuccessPacket {
    /**
     * Create an encrypted fulfillment payload, that will be wrapped by each intermediate node before being returned to
     * the sender. Note that the final node (which creates this payload) does *not* apply additional wrapping since this
     * is encrypted (unlike what happens for failure messages).
     *
     * Note that malicious intermediate hops may drop the packet or alter it, but since each intermediate node includes
     * the payload they received in their attribution data, the sender will be able to infer who dropped or altered the
     * payload.
     */
    def create(sharedSecret: ByteVector32, payload: ByteVector): ByteVector = {
      val key = generateKey("fulfillment", sharedSecret)
      val (encryptedPayload, mac) = ChaCha20Poly1305.encrypt(key, zeroes(12), payload, ByteVector.empty)
      encryptedPayload ++ mac
    }

    /**
     * Wrap the fulfillment payload received from the downstream node in an additional layer of onion encryption.
     * Each intermediate node wraps the fulfillment payload until it reaches the original sender.
     * Each intermediate node also includes the received fulfillment payload in its attribution data.
     */
    def wrap(payload: ByteVector, sharedSecret: ByteVector32): ByteVector = {
      val key = generateKey("ammag", sharedSecret)
      val stream = generateStream(key, payload.length.toInt)
      payload xor stream
    }

    /**
     * Decrypt the fulfillment payload and attribution data provided in the HTLC-success case.
     * Node shared secrets are applied until we reach the recipient's shared secret, where the decryption step differs.
     * Note that malicious nodes in the route may have altered the packet, triggering a decryption failure.
     *
     * @param payload_opt     fulfillment payload.
     * @param attribution_opt attribution data for this success packet.
     * @param sharedSecrets   nodes shared secrets.
     * @param fullRoute       must be set to false when decrypting a partial route (e.g. as an intermediate trampoline).
     */
    def decrypt(payload_opt: Option[ByteVector], attribution_opt: Option[ByteVector], sharedSecrets: Seq[SharedSecret], fullRoute: Boolean = true): HtlcSuccess = {
      sharedSecrets match {
        case Nil => HtlcSuccess(Nil, payload_opt, attribution_opt)
        case ss :: tail =>
          // We start by unwrapping the fulfillment payload, if provided.
          val isFinalNode = tail.isEmpty && fullRoute
          val unwrappedPayload_opt = payload_opt match {
            case Some(payload) if isFinalNode =>
              // We decrypt the payload provided by the final node.
              val key = generateKey("fulfillment", ss.secret)
              Try(ChaCha20Poly1305.decrypt(key, zeroes(12), payload.dropRight(16), ByteVector.empty, payload.takeRight(16))).toOption
            case Some(payload) =>
              // We peel the wrapping added by the intermediate node.
              Some(wrap(payload, ss.secret))
            case None => None
          }
          // We decrypt the attribution data provided by this node: its HMACs must cover the unwrapped fulfillment payload.
          // The code below is quite subtle, because nodes inside a blinded path don't include attribution data, so when
          // we reach that point in the recursion, attribution decryption will fail, which is expected.
          // After that, attribution_opt will be set to None for recursive calls, because there is no point trying to
          // decrypt attribution data that wasn't actually provided or that was tampered with.
          // Note that if an intermediate node tampered with the attribution data, it will have the same effect: we will
          // stop processing attribution after that node.
          // The caller can look at the reported hold times to know which nodes provided valid attribution data: this
          // allows identifying which nodes are acting maliciously, if any.
          // We keep processing the fulfillment payload recursively though, because we need to use all shared secrets
          // to decrypt it.
          val attribution1_opt = attribution_opt.flatMap(attribution => Attribution.decrypt(attribution, if (!isFinalNode) unwrappedPayload_opt else None, ss, sharedSecrets.length))
          val downstream = decrypt(unwrappedPayload_opt, attribution1_opt.map(_.downstreamAttribution), tail, fullRoute)
          HtlcSuccess(attribution1_opt.map(_.holdTime).toSeq ++ downstream.holdTimes, downstream.fulfillmentPayload_opt, downstream.remainingAttribution_opt)
      }
    }
  }

  /**
   * Attribution data is included when resolving an HTLC, whether it's a failure or a success.
   *
   * In the HTLC failure case, nodes that relay attribution data can prove that they are not the origin of the failure.
   * In case the failing node tries to hide (by returning garbage or tampering with a downstream failure), there will
   * be at most two nodes that can be responsible: the last one to send attribution data and the one after it.
   *
   * Attribution data also contains timing information for each node on the payment path, which lets the sending node
   * identify slow nodes and potentially prioritize faster nodes for future payments.
   */
  object Attribution {
    private val maxNumHops = 20
    private val holdTimeLength = 4
    private val hmacLength = 4 // HMACs are truncated to 4 bytes to save space
    val totalLength: Int = maxNumHops * holdTimeLength + maxNumHops * (maxNumHops + 1) / 2 * hmacLength // = 920

    /** Valid attribution data from one hop in the payment path. */
    case class PerHopAttribution(holdTime: HoldTime, downstreamAttribution: ByteVector) {
      val nodeId: PublicKey = holdTime.remoteNodeId
    }

    private def wrap(attributionData: ByteVector, sharedSecret: ByteVector32): ByteVector = {
      val key = generateKey("ammagext", sharedSecret)
      val stream = generateStream(key, totalLength)
      attributionData xor stream
    }

    /**
     * Get the HMACs from the attribution data.
     * The layout of the attribution data is as follows (using maxNumHops = 3 for conciseness):
     * holdTime(0) ++ holdTime(1) ++ holdTime(2) ++
     * hmacs(0)(0) ++ hmacs(0)(1) ++ hmacs(0)(2) ++
     * hmacs(1)(0) ++ hmacs(1)(1) ++
     * hmacs(2)(0)
     *
     * Where `hmac(i)(j)` is the hmac added by node `i` (counted from the node that built the attribution data),
     * assuming it is `maxNumHops - 1 - i - j` hops away from the erring node.
     */
    private def getHmacs(bytes: ByteVector): Seq[Seq[ByteVector]] =
      (0 until maxNumHops).map(i => (0 until (maxNumHops - i)).map(j => {
        val start = maxNumHops * holdTimeLength + (maxNumHops * i - (i * (i - 1)) / 2 + j) * hmacLength
        bytes.slice(start, start + hmacLength)
      }))

    /**
     * Computes the HMACs for the node that is `maxNumHops - remainingHops` hops away from us: we only need to compute
     * `remainingHops` HMACs.
     * HMACs are truncated to 4 bytes to save space: this should be enough since an attacker has only one shot at
     * "forging" an HMAC, so they're unlikely to succeed.
     */
    private def computeHmacs(mac: Mac32, payload: ByteVector, holdTimes: ByteVector, hmacs: Seq[Seq[ByteVector]], remainingHops: Int): Seq[ByteVector] = {
      ((maxNumHops - remainingHops) until maxNumHops).map(i => {
        val y = maxNumHops - i
        // We include the HMACs of downstream nodes in our own HMACs.
        val downstreamMacs = ByteVector.concat((0 until y - 1).map(j => hmacs(j)(i)))
        // We include the hold times of downstream nodes as well.
        val downstreamHoldTimes = holdTimes.take(y * holdTimeLength)
        mac.mac(payload ++ downstreamHoldTimes ++ downstreamMacs).bytes.take(hmacLength)
      })
    }

    /**
     * Create attribution data to send when settling an HTLC (in both failure and success cases).
     *
     * @param downstreamAttribution_opt attribution data received from downstream.
     * @param payload_opt               payload that should be covered by the attribution HMACs.
     */
    def create(downstreamAttribution_opt: Option[ByteVector], payload_opt: Option[ByteVector], holdTime: FiniteDuration, sharedSecret: ByteVector32): ByteVector = {
      val downstreamAttribution = downstreamAttribution_opt.getOrElse(zeroes(totalLength))
      val downstreamHmacs = getHmacs(downstreamAttribution).dropRight(1).map(_.drop(1))
      val downstreamHoldTimes = downstreamAttribution.take((maxNumHops - 1) * holdTimeLength)
      val holdTimes = uint32.encode(holdTime.toMillis / 100).require.bytes ++ downstreamHoldTimes
      val macKey = generateKey("um", sharedSecret)
      val hmacs = computeHmacs(Hmac256(macKey), payload_opt.getOrElse(ByteVector.empty), holdTimes, downstreamHmacs, maxNumHops) +: downstreamHmacs
      wrap(holdTimes ++ ByteVector.concat(hmacs.map(ByteVector.concat(_))), sharedSecret)
    }

    /**
     * Decrypt one hop of attribution data, or return [[None]] if we cannot extract attribution data (which happens if
     * this node or the previous one is malicious, or if the node is inside a blinded path).
     *
     * @param attribution   attribution data from this node.
     * @param payload_opt   (optional) payload that is also covered by this node's HMACs.
     * @param sharedSecret  shared secret with the node.
     * @param remainingHops number of remaining downstream nodes.
     */
    def decrypt(attribution: ByteVector, payload_opt: Option[ByteVector], sharedSecret: SharedSecret, remainingHops: Int): Option[PerHopAttribution] = {
      val decrypted = wrap(attribution, sharedSecret.secret)
      val holdTime = (uint32.decode(decrypted.take(holdTimeLength).bits).require.value * 100).milliseconds
      val holdTimes = decrypted.take(maxNumHops * holdTimeLength)
      val hmacs = getHmacs(decrypted)
      val macKey = generateKey("um", sharedSecret.secret)
      val expectedHmacs = computeHmacs(Hmac256(macKey), payload_opt.getOrElse(ByteVector.empty), holdTimes, hmacs.drop(1), remainingHops)
      if (expectedHmacs == hmacs.head.drop(maxNumHops - remainingHops)) {
        // The attribution data from this node is valid: we shift it to access attribution data from the downstream nodes.
        val downstreamHoldTimes = decrypted.slice(holdTimeLength, maxNumHops * holdTimeLength) ++ zeroes(holdTimeLength)
        val downstreamHmacs = ByteVector.concat((hmacs.drop(1) :+ Seq()).map(s => zeroes(hmacLength) ++ ByteVector.concat(s)))
        Some(PerHopAttribution(HoldTime(holdTime, sharedSecret.remoteNodeId), downstreamHoldTimes ++ downstreamHmacs))
      } else {
        // The attribution data from this node is invalid or missing. This doesn't necessarily mean that this node was
        // malicious: they could be inside a blinded path, in which case they don't return any attribution data, which
        // is fine (we only care about attribution outside the blinded path).
        None
      }
    }
  }

  /**
   * Route blinding is a lightweight technique to provide recipient anonymity by blinding an arbitrary amount of hops at
   * the end of an onion path. It can be used for payments or onion messages.
   */
  object RouteBlinding {

    /**
     * @param nodeId           first node's id (which cannot be blinded since the sender need to find a route to it).
     * @param blindedPublicKey blinded public key, which hides the real public key.
     * @param pathKey          blinding tweak that can be used by the receiving node to derive the private key that
     *                         matches the blinded public key.
     * @param encryptedPayload encrypted payload that can be decrypted with the introduction node's private key and the
     *                         path key.
     */
    case class FirstNode(nodeId: EncodedNodeId, blindedPublicKey: PublicKey, pathKey: PublicKey, encryptedPayload: ByteVector)

    /**
     * @param blindedPublicKey blinded public key, which hides the real public key.
     * @param encryptedPayload encrypted payload that can be decrypted with the receiving node's private key and the
     *                         path key.
     */
    case class BlindedHop(blindedPublicKey: PublicKey, encryptedPayload: ByteVector)

    /**
     * @param firstNodeId  the first node, not blinded so that the sender can locate it.
     * @param firstPathKey blinding tweak that can be used by the introduction node to derive the private key that
     *                     matches the blinded public key.
     * @param blindedHops  blinded nodes (including the introduction node).
     */
    case class BlindedRoute(firstNodeId: EncodedNodeId, firstPathKey: PublicKey, blindedHops: Seq[BlindedHop]) {
      require(blindedHops.nonEmpty, "blinded route must not be empty")
      val firstNode: FirstNode = FirstNode(firstNodeId, blindedHops.head.blindedPublicKey, firstPathKey, blindedHops.head.encryptedPayload)
      val subsequentNodes: Seq[BlindedHop] = blindedHops.tail
      val blindedNodeIds: Seq[PublicKey] = blindedHops.map(_.blindedPublicKey)
      val encryptedPayloads: Seq[ByteVector] = blindedHops.map(_.encryptedPayload)
      val length: Int = blindedHops.length - 1
    }

    /**
     * @param route       blinded route.
     * @param lastPathKey path key for the last node, which can be used to derive the blinded private key.
     */
    case class BlindedRouteDetails(route: BlindedRoute, lastPathKey: PublicKey)

    /**
     * Blind the provided route and encrypt intermediate nodes' payloads.
     *
     * @param sessionKey this node's session key.
     * @param publicKeys public keys of each node on the route, starting from the introduction point.
     * @param payloads   payloads that should be encrypted for each node on the route.
     * @return a blinded route and the path key for the last node.
     */
    def create(sessionKey: PrivateKey, publicKeys: Seq[PublicKey], payloads: Seq[ByteVector]): BlindedRouteDetails = {
      require(publicKeys.length == payloads.length, "a payload must be provided for each node in the blinded path")
      var e = sessionKey
      val (blindedHops, pathKeys) = publicKeys.zip(payloads).map { case (publicKey, payload) =>
        val pathKey = e.publicKey
        val sharedSecret = computeSharedSecret(publicKey, e)
        val blindedPublicKey = blind(publicKey, generateKey("blinded_node_id", sharedSecret))
        val rho = generateKey("rho", sharedSecret)
        val (encryptedPayload, mac) = ChaCha20Poly1305.encrypt(rho, zeroes(12), payload, ByteVector.empty)
        e = e.multiply(PrivateKey(Crypto.sha256(pathKey.value ++ sharedSecret.bytes)))
        (BlindedHop(blindedPublicKey, encryptedPayload ++ mac), pathKey)
      }.unzip
      BlindedRouteDetails(BlindedRoute(EncodedNodeId.WithPublicKey.Plain(publicKeys.head), pathKeys.head, blindedHops), pathKeys.last)
    }

    /**
     * Compute the blinded private key that must be used to decrypt an incoming blinded onion.
     *
     * @param privateKey this node's private key.
     * @param pathKey    unblinding ephemeral key.
     * @return this node's blinded private key.
     */
    def derivePrivateKey(privateKey: PrivateKey, pathKey: PublicKey): PrivateKey = {
      val sharedSecret = computeSharedSecret(pathKey, privateKey)
      privateKey.multiply(PrivateKey(generateKey("blinded_node_id", sharedSecret)))
    }

    /**
     * Decrypt the encrypted payload (usually found in the onion) that contains instructions to locate the next node.
     *
     * @param privateKey       this node's private key.
     * @param pathKey          unblinding ephemeral key.
     * @param encryptedPayload encrypted payload for this node.
     * @return a tuple (decrypted payload, path key for the next node)
     */
    def decryptPayload(privateKey: PrivateKey, pathKey: PublicKey, encryptedPayload: ByteVector): Try[(ByteVector, PublicKey)] = Try {
      val sharedSecret = computeSharedSecret(pathKey, privateKey)
      val rho = generateKey("rho", sharedSecret)
      val decrypted = ChaCha20Poly1305.decrypt(rho, zeroes(12), encryptedPayload.dropRight(16), ByteVector.empty, encryptedPayload.takeRight(16))
      val nextPathKey = blind(pathKey, computeBlindingFactor(pathKey, sharedSecret))
      (decrypted, nextPathKey)
    }

  }

}

