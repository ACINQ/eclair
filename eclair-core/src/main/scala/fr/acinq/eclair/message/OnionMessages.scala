/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.message

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.MessageRelay.RelayPolicy
import fr.acinq.eclair.wire.protocol.MessageOnion.{BlindedFinalPayload, BlindedRelayPayload, FinalPayload, RelayPayload}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object OnionMessages {

  case class OnionMessageConfig(relayPolicy: RelayPolicy,
                                timeout: FiniteDuration)

  case class IntermediateNode(nodeId: PublicKey, padding: Option[ByteVector] = None)

  sealed trait Destination
  case class BlindedPath(route: Sphinx.RouteBlinding.BlindedRoute) extends Destination
  case class Recipient(nodeId: PublicKey, pathId: Option[ByteVector], padding: Option[ByteVector] = None) extends Destination

  def buildRoute(blindingSecret: PrivateKey,
                 intermediateNodes: Seq[IntermediateNode],
                 destination: Destination): Sphinx.RouteBlinding.BlindedRoute = {
    val last = destination match {
      case Recipient(nodeId, _, _) => OutgoingNodeId(nodeId) :: Nil
      case BlindedPath(Sphinx.RouteBlinding.BlindedRoute(nodeId, blindingKey, _)) => OutgoingNodeId(nodeId) :: NextBlinding(blindingKey) :: Nil
    }
    val intermediatePayloads =
      if (intermediateNodes.isEmpty) {
        Nil
      } else {
        (intermediateNodes.tail.map(node => OutgoingNodeId(node.nodeId) :: Nil) :+ last)
          .zip(intermediateNodes).map { case (tlvs, hop) => hop.padding.map(Padding(_) :: Nil).getOrElse(Nil) ++ tlvs }
          .map(tlvs => BlindedRelayPayload(TlvStream(tlvs)))
          .map(MessageOnionCodecs.blindedRelayPayloadCodec.encode(_).require.bytes)
      }
    destination match {
      case Recipient(nodeId, pathId, padding) =>
        val tlvs = padding.map(Padding(_) :: Nil).getOrElse(Nil) ++ pathId.map(PathId(_) :: Nil).getOrElse(Nil)
        val lastPayload = MessageOnionCodecs.blindedFinalPayloadCodec.encode(BlindedFinalPayload(TlvStream(tlvs))).require.bytes
        Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.nodeId) :+ nodeId, intermediatePayloads :+ lastPayload)
      case BlindedPath(route) =>
        if (intermediateNodes.isEmpty) {
          route
        } else {
          val Sphinx.RouteBlinding.BlindedRoute(introductionNodeId, blindingKey, blindedNodes) = Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.nodeId), intermediatePayloads)
          Sphinx.RouteBlinding.BlindedRoute(introductionNodeId, blindingKey, blindedNodes ++ route.blindedNodes)
        }
    }
  }

  /**
   * Builds an encrypted onion containing a message that should be relayed to the destination.
   *
   * @param sessionKey        A random key to encrypt the onion
   * @param blindingSecret    A random key to encrypt the onion
   * @param intermediateNodes List of intermediate nodes between us and the destination, can be empty if we want to contact the destination directly
   * @param destination       The destination of this message, can be a node id or a blinded route
   * @param content           List of TLVs to send to the recipient of the message
   * @return The node id to send the onion to and the onion containing the message
   */
  def buildMessage(sessionKey: PrivateKey,
                   blindingSecret: PrivateKey,
                   intermediateNodes: Seq[IntermediateNode],
                   destination: Destination,
                   content: Seq[OnionMessagePayloadTlv],
                   userCustomTlvs: Seq[GenericTlv] = Nil): (PublicKey, OnionMessage) = {
    val route = buildRoute(blindingSecret, intermediateNodes, destination)
    val lastPayload = MessageOnionCodecs.finalPerHopPayloadCodec.encode(FinalPayload(TlvStream(EncryptedData(route.encryptedPayloads.last) +: content, userCustomTlvs))).require.bytes
    val payloads = route.encryptedPayloads.dropRight(1).map(encTlv => MessageOnionCodecs.relayPerHopPayloadCodec.encode(RelayPayload(TlvStream(EncryptedData(encTlv)))).require.bytes) :+ lastPayload
    val payloadSize = payloads.map(_.length + Sphinx.MacLength).sum
    val packetSize = if (payloadSize <= 1300) {
      1300
    } else if (payloadSize <= 32768) {
      32768
    } else {
      payloadSize.toInt
    }
    // Since we are setting the packet size based on the payload, the onion creation should never fail (hence the `.get`).
    val Sphinx.PacketAndSecrets(packet, _) = Sphinx.create(sessionKey, packetSize, route.blindedNodes.map(_.blindedPublicKey), payloads, None).get
    (route.introductionNodeId, OnionMessage(route.blindingKey, packet))
  }

  // @formatter:off
  sealed trait Action
  case class DropMessage(reason: DropReason) extends Action
  case class SendMessage(nextNodeId: PublicKey, message: OnionMessage) extends Action
  case class ReceiveMessage(finalPayload: FinalPayload, pathId: Option[ByteVector]) extends Action

  sealed trait DropReason
  case class MessageTooLarge(size: Long) extends DropReason { override def toString = s"message too large (size=$size, max=32768)" }
  case class CannotDecryptOnion(message: String) extends DropReason { override def toString = s"can't decrypt onion: $message" }
  case class CannotDecodeOnion(message: String) extends DropReason { override def toString = s"can't decode onion: $message" }
  case class CannotDecryptBlindedPayload(message: String) extends DropReason { override def toString = s"can't decrypt blinded payload: $message" }
  case class CannotDecodeBlindedPayload(message: String) extends DropReason { override def toString = s"can't decode blinded payload: $message" }
  // @formatter:on

  @tailrec
  def process(privateKey: PrivateKey, msg: OnionMessage): Action = {
    if (msg.onionRoutingPacket.payload.length > 32768) {
      DropMessage(MessageTooLarge(msg.onionRoutingPacket.payload.length))
    } else {
      val blindedPrivateKey = Sphinx.RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
      Sphinx.peel(blindedPrivateKey, None, msg.onionRoutingPacket) match {
        case Left(err: BadOnion) => DropMessage(CannotDecryptOnion(err.message))
        case Right(p@Sphinx.DecryptedPacket(payload, nextPacket, _)) => MessageOnionCodecs.messageOnionPerHopPayloadCodec(p.isLastPacket).decode(payload.bits) match {
          case Attempt.Successful(DecodeResult(relayPayload: RelayPayload, _)) =>
            Sphinx.RouteBlinding.decryptPayload(privateKey, msg.blindingKey, relayPayload.encryptedData) match {
              case Success((decrypted, nextBlindingKey)) =>
                MessageOnionCodecs.blindedRelayPayloadCodec.decode(decrypted.bits) match {
                  case Attempt.Successful(DecodeResult(relayNext, _)) =>
                    val toRelay = OnionMessage(relayNext.nextBlindingOverride.getOrElse(nextBlindingKey), nextPacket)
                    if (relayNext.nextNodeId == privateKey.publicKey) { // we may add ourselves to the route several times to hide the real length of the route
                      process(privateKey, toRelay)
                    } else {
                      SendMessage(relayNext.nextNodeId, toRelay)
                    }
                  case Attempt.Failure(err) => DropMessage(CannotDecodeBlindedPayload(err.message))
                }
              case Failure(err) => DropMessage(CannotDecryptBlindedPayload(err.getMessage))
            }
          case Attempt.Successful(DecodeResult(finalPayload: FinalPayload, _)) =>
            Sphinx.RouteBlinding.decryptPayload(privateKey, msg.blindingKey, finalPayload.encryptedData) match {
              case Success((decrypted, _)) =>
                MessageOnionCodecs.blindedFinalPayloadCodec.decode(decrypted.bits) match {
                  case Attempt.Successful(DecodeResult(messageToSelf, _)) => ReceiveMessage(finalPayload, messageToSelf.pathId)
                  case Attempt.Failure(err) => DropMessage(CannotDecodeBlindedPayload(err.message))
                }
              case Failure(err) => DropMessage(CannotDecryptBlindedPayload(err.getMessage))
            }
          case Attempt.Failure(err) => DropMessage(CannotDecodeOnion(err.message))
        }
      }
    }
  }
}
