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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{EncodedNodeId, ShortChannelId}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.MessageRelay.RelayPolicy
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import scala.concurrent.duration.FiniteDuration

object OnionMessages {

  /**
   * @param relayPolicy         When to relay onion messages (always, never, only along existing channels).
   * @param minIntermediateHops For routes we build to us, minimum number of hops before our node. Dummy hops are added
   *                            if needed to hide our position in the network.
   * @param timeout             Time after which we consider that the message has been lost and stop waiting for a reply.
   * @param maxAttempts         Maximum number of attempts for sending a message.
   */
  case class OnionMessageConfig(relayPolicy: RelayPolicy,
                                minIntermediateHops: Int,
                                timeout: FiniteDuration,
                                maxAttempts: Int)

  case class IntermediateNode(publicKey: PublicKey, encodedNodeId: EncodedNodeId, outgoingChannel_opt: Option[ShortChannelId] = None, padding: Option[ByteVector] = None, customTlvs: Set[GenericTlv] = Set.empty) {
    def toTlvStream(nextNodeId: EncodedNodeId, nextBlinding_opt: Option[PublicKey] = None): TlvStream[RouteBlindingEncryptedDataTlv] =
      TlvStream(Set[Option[RouteBlindingEncryptedDataTlv]](
        padding.map(Padding),
        outgoingChannel_opt.map(OutgoingChannelId).orElse(Some(OutgoingNodeId(nextNodeId))),
        nextBlinding_opt.map(NextBlinding)
      ).flatten, customTlvs)
  }

  object IntermediateNode {
    def apply(publicKey: PublicKey): IntermediateNode = IntermediateNode(publicKey, EncodedNodeId.WithPublicKey.Plain(publicKey))
  }

  // @formatter:off
  sealed trait Destination {
    def introductionNodeId: EncodedNodeId
  }
  case class BlindedPath(route: Sphinx.RouteBlinding.BlindedRoute) extends Destination {
    override def introductionNodeId: EncodedNodeId = route.introductionNodeId
  }
  case class Recipient(nodeId: PublicKey, pathId: Option[ByteVector], padding: Option[ByteVector] = None, customTlvs: Set[GenericTlv] = Set.empty) extends Destination {
    override def introductionNodeId: EncodedNodeId = EncodedNodeId.WithPublicKey.Plain(nodeId)
  }
  // @formatter:on

  // @formatter:off
  sealed trait RoutingStrategy
  object RoutingStrategy {
    /** Use the provided route to reach the recipient or the blinded path's introduction node. */
    case class UseRoute(intermediateNodes: Seq[PublicKey]) extends RoutingStrategy
    /** Directly connect to the recipient or the blinded path's introduction node. */
    val connectDirectly: UseRoute = UseRoute(Nil)
    /** Use path-finding to find a route to reach the recipient or the blinded path's introduction node. */
    case object FindRoute extends RoutingStrategy
  }
  // @formatter:on

  private def buildIntermediatePayloads(intermediateNodes: Seq[IntermediateNode], lastNodeId: EncodedNodeId, lastBlinding_opt: Option[PublicKey] = None): Seq[ByteVector] = {
    if (intermediateNodes.isEmpty) {
      Nil
    } else {
      val intermediatePayloads = intermediateNodes.dropRight(1).zip(intermediateNodes.tail).map { case (hop, nextNode) => hop.toTlvStream(nextNode.encodedNodeId) }
      val lastPayload = intermediateNodes.last.toTlvStream(lastNodeId, lastBlinding_opt)
      (intermediatePayloads :+ lastPayload).map(tlvs => RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(tlvs).require.bytes)
    }
  }

  def buildRoute(blindingSecret: PrivateKey,
                 intermediateNodes: Seq[IntermediateNode],
                 recipient: Recipient): Sphinx.RouteBlinding.BlindedRouteDetails = {
    val intermediatePayloads = buildIntermediatePayloads(intermediateNodes, EncodedNodeId.WithPublicKey.Plain(recipient.nodeId))
    val tlvs: Set[RouteBlindingEncryptedDataTlv] = Set(recipient.padding.map(Padding), recipient.pathId.map(PathId)).flatten
    val lastPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(tlvs, recipient.customTlvs)).require.bytes
    Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.publicKey) :+ recipient.nodeId, intermediatePayloads :+ lastPayload)
  }

  private[message] def buildRouteFrom(blindingSecret: PrivateKey,
                                      intermediateNodes: Seq[IntermediateNode],
                                      destination: Destination): Sphinx.RouteBlinding.BlindedRoute = {
    destination match {
      case recipient: Recipient => buildRoute(blindingSecret, intermediateNodes, recipient).route
      case BlindedPath(route) if intermediateNodes.isEmpty => route
      case BlindedPath(route) =>
        val intermediatePayloads = buildIntermediatePayloads(intermediateNodes, route.introductionNodeId, Some(route.blindingKey))
        val routePrefix = Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.publicKey), intermediatePayloads).route
        Sphinx.RouteBlinding.BlindedRoute(routePrefix.introductionNodeId, routePrefix.blindingKey, routePrefix.blindedNodes ++ route.blindedNodes)
    }
  }

  case class MessageTooLarge(payloadSize: Long)

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
                   content: TlvStream[OnionMessagePayloadTlv]): Either[MessageTooLarge, OnionMessage] = {
    val route = buildRouteFrom(blindingSecret, intermediateNodes, destination)
    val lastPayload = MessageOnionCodecs.perHopPayloadCodec.encode(TlvStream(content.records + EncryptedData(route.encryptedPayloads.last), content.unknown)).require.bytes
    val payloads = route.encryptedPayloads.dropRight(1).map(encTlv => MessageOnionCodecs.perHopPayloadCodec.encode(TlvStream(EncryptedData(encTlv))).require.bytes) :+ lastPayload
    val payloadSize = payloads.map(_.length + Sphinx.MacLength).sum
    val packetSize = if (payloadSize <= 1300) {
      1300
    } else if (payloadSize <= 32768) {
      32768
    } else if (payloadSize > 65432) {
      // A payload of size 65432 corresponds to a total lightning message size of 65535.
      return Left(MessageTooLarge(payloadSize))
    } else {
      payloadSize.toInt
    }
    // Since we are setting the packet size based on the payload, the onion creation should never fail (hence the `.get`).
    val Sphinx.PacketAndSecrets(packet, _) = Sphinx.create(sessionKey, packetSize, route.blindedNodes.map(_.blindedPublicKey), payloads, None).get
    Right(OnionMessage(route.blindingKey, packet))
  }

  // @formatter:off
  sealed trait Action
  case class DropMessage(reason: DropReason) extends Action
  case class SendMessage(nextNode: Either[ShortChannelId, EncodedNodeId], message: OnionMessage) extends Action
  case class ReceiveMessage(finalPayload: FinalPayload, blindedKey: PrivateKey) extends Action

  sealed trait DropReason
  case class CannotDecryptOnion(message: String) extends DropReason { override def toString = s"can't decrypt onion: $message" }
  case class CannotDecodeOnion(message: String) extends DropReason { override def toString = s"can't decode onion: $message" }
  case class CannotDecryptBlindedPayload(message: String) extends DropReason { override def toString = s"can't decrypt blinded payload: $message" }
  case class CannotDecodeBlindedPayload(message: String) extends DropReason { override def toString = s"can't decode blinded payload: $message" }
  // @formatter:on

  case class DecodedOnionPacket(payload: TlvStream[OnionMessagePayloadTlv], next_opt: Option[OnionRoutingPacket])

  private def decryptOnion(privateKey: PrivateKey, packet: OnionRoutingPacket): Either[DropReason, DecodedOnionPacket] = {
    Sphinx.peel(privateKey, None, packet) match {
      case Right(p: Sphinx.DecryptedPacket) =>
        MessageOnionCodecs.perHopPayloadCodec.decode(p.payload.bits) match {
          case Attempt.Successful(DecodeResult(perHopPayload, _)) if p.isLastPacket => Right(DecodedOnionPacket(perHopPayload, None))
          case Attempt.Successful(DecodeResult(perHopPayload, _)) => Right(DecodedOnionPacket(perHopPayload, Some(p.nextPacket)))
          case Attempt.Failure(f) => Left(CannotDecodeOnion(f.message))
        }
      case Left(badOnion) => Left(CannotDecryptOnion(badOnion.message))
    }
  }

  case class DecodedEncryptedData(payload: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey)

  private def decryptEncryptedData(privateKey: PrivateKey, blinding: PublicKey, encryptedData: ByteVector): Either[DropReason, DecodedEncryptedData] = {
    RouteBlindingEncryptedDataCodecs.decode(privateKey, blinding, encryptedData) match {
      case Left(RouteBlindingEncryptedDataCodecs.CannotDecryptData(f)) => Left(CannotDecryptBlindedPayload(f))
      case Left(RouteBlindingEncryptedDataCodecs.CannotDecodeData(f)) => Left(CannotDecodeBlindedPayload(f))
      case Right(decoded) => Right(DecodedEncryptedData(decoded.tlvs, decoded.nextBlinding))
    }
  }

  def process(privateKey: PrivateKey, msg: OnionMessage): Action = {
    val blindedPrivateKey = Sphinx.RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
    decryptOnion(blindedPrivateKey, msg.onionRoutingPacket) match {
      case Left(f) => DropMessage(f)
      case Right(DecodedOnionPacket(payload, nextPacket_opt)) =>
        payload.get[OnionMessagePayloadTlv.EncryptedData] match {
          case Some(OnionMessagePayloadTlv.EncryptedData(encryptedData)) =>
            decryptEncryptedData(privateKey, msg.blindingKey, encryptedData) match {
              case Left(f) => DropMessage(f)
              case Right(DecodedEncryptedData(blindedPayload, nextBlinding)) => nextPacket_opt match {
                case Some(nextPacket) => validateRelayPayload(payload, blindedPayload, nextBlinding, nextPacket)
                case None => validateFinalPayload(payload, blindedPayload, blindedPrivateKey)
              }
            }
          case None => nextPacket_opt match {
            case Some(_) => DropMessage(CannotDecryptBlindedPayload("encrypted_data is missing"))
            case None => validateFinalPayload(payload, TlvStream.empty, blindedPrivateKey)
          }
        }
    }
  }

  private def validateRelayPayload(payload: TlvStream[OnionMessagePayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey, nextPacket: OnionRoutingPacket): Action = {
    IntermediatePayload.validate(payload, blindedPayload, nextBlinding) match {
      case Left(f) => DropMessage(CannotDecodeBlindedPayload(f.failureMessage.message))
      case Right(relayPayload) => SendMessage(relayPayload.nextNode, OnionMessage(nextBlinding, nextPacket))
    }
  }

  private def validateFinalPayload(payload: TlvStream[OnionMessagePayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv], blindedKey: PrivateKey): Action = {
    FinalPayload.validate(payload, blindedPayload) match {
      case Left(f) => DropMessage(CannotDecodeBlindedPayload(f.failureMessage.message))
      case Right(finalPayload) => ReceiveMessage(finalPayload, blindedKey)
    }
  }

}
