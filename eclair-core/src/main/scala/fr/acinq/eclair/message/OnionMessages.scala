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
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.MessageRelay.RelayPolicy
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

object OnionMessages {

  case class OnionMessageConfig(relayPolicy: RelayPolicy,
                                timeout: FiniteDuration,
                                maxAttempts: Int)

  case class IntermediateNode(nodeId: PublicKey, padding: Option[ByteVector] = None)

  // @formatter:off
  sealed trait Destination
  case class BlindedPath(route: Sphinx.RouteBlinding.BlindedRoute) extends Destination
  case class Recipient(nodeId: PublicKey, pathId: Option[ByteVector], padding: Option[ByteVector] = None) extends Destination
  // @formatter:on

  private def buildIntermediatePayloads(intermediateNodes: Seq[IntermediateNode], nextTlvs: Set[RouteBlindingEncryptedDataTlv]): Seq[ByteVector] = {
    if (intermediateNodes.isEmpty) {
      Nil
    } else {
      (intermediateNodes.tail.map(node => Set(OutgoingNodeId(node.nodeId))) :+ nextTlvs)
        .zip(intermediateNodes).map { case (tlvs, hop) => hop.padding.map(Padding).toSet[RouteBlindingEncryptedDataTlv] ++ tlvs }
        .map(tlvs => RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(tlvs)).require.bytes)
    }
  }

  def buildRoute(blindingSecret: PrivateKey,
                 intermediateNodes: Seq[IntermediateNode],
                 recipient: Recipient): Sphinx.RouteBlinding.BlindedRoute = {
    val intermediatePayloads = buildIntermediatePayloads(intermediateNodes, Set(OutgoingNodeId(recipient.nodeId)))
    val tlvs: Set[RouteBlindingEncryptedDataTlv] = Set(recipient.padding.map(Padding), recipient.pathId.map(PathId)).flatten
    val lastPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(tlvs)).require.bytes
    Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.nodeId) :+ recipient.nodeId, intermediatePayloads :+ lastPayload).route
  }

  private def buildRouteFrom(originKey: PrivateKey,
                             blindingSecret: PrivateKey,
                             intermediateNodes: Seq[IntermediateNode],
                             destination: Destination): Option[Sphinx.RouteBlinding.BlindedRoute] = {
    destination match {
      case recipient: Recipient => Some(buildRoute(blindingSecret, intermediateNodes, recipient))
      case BlindedPath(route) if route.introductionNodeId == originKey.publicKey =>
        RouteBlindingEncryptedDataCodecs.decode(originKey, route.blindingKey, route.blindedNodes.head.encryptedPayload) match {
          case Left(_) => None
          case Right(decoded) =>
            decoded.tlvs.get[RouteBlindingEncryptedDataTlv.OutgoingNodeId] match {
              case None => None
              case Some(RouteBlindingEncryptedDataTlv.OutgoingNodeId(nextNodeId)) =>
                Some(Sphinx.RouteBlinding.BlindedRoute(nextNodeId, decoded.nextBlinding, route.blindedNodes.tail))
            }
        }
      case BlindedPath(route) if intermediateNodes.isEmpty => Some(route)
      case BlindedPath(route) =>
        val intermediatePayloads = buildIntermediatePayloads(intermediateNodes, Set(OutgoingNodeId(route.introductionNodeId), NextBlinding(route.blindingKey)))
        val routePrefix = Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.nodeId), intermediatePayloads).route
        Some(Sphinx.RouteBlinding.BlindedRoute(routePrefix.introductionNodeId, routePrefix.blindingKey, routePrefix.blindedNodes ++ route.blindedNodes))
    }
  }

  // @formatter:off
  sealed trait BuildMessageError
  case class MessageTooLarge(payloadSize: Long) extends BuildMessageError
  case class InvalidDestination(destination: Destination) extends BuildMessageError
  // @formatter:on

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
  def buildMessage(nodeKey: PrivateKey,
                   sessionKey: PrivateKey,
                   blindingSecret: PrivateKey,
                   intermediateNodes: Seq[IntermediateNode],
                   destination: Destination,
                   content: TlvStream[OnionMessagePayloadTlv]): Either[BuildMessageError, (PublicKey, OnionMessage)] = {
    buildRouteFrom(nodeKey, blindingSecret, intermediateNodes, destination) match {
      case None => Left(InvalidDestination(destination))
      case Some(route) =>
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
        Right((route.introductionNodeId, OnionMessage(route.blindingKey, packet)))
    }
  }

  // @formatter:off
  sealed trait Action
  case class DropMessage(reason: DropReason) extends Action
  case class SendMessage(nextNodeId: PublicKey, message: OnionMessage) extends Action
  case class ReceiveMessage(finalPayload: FinalPayload) extends Action

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

  @tailrec
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
                case Some(nextPacket) => validateRelayPayload(payload, blindedPayload, nextBlinding, nextPacket) match {
                  case SendMessage(nextNodeId, nextMsg) if nextNodeId == privateKey.publicKey => process(privateKey, nextMsg)
                  case action => action
                }
                case None => validateFinalPayload(payload, blindedPayload)
              }
            }
          case None => nextPacket_opt match {
            case Some(_) => DropMessage(CannotDecryptBlindedPayload("encrypted_data is missing"))
            case None => validateFinalPayload(payload, TlvStream.empty)
          }
        }
    }
  }

  private def validateRelayPayload(payload: TlvStream[OnionMessagePayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey, nextPacket: OnionRoutingPacket): Action = {
    IntermediatePayload.validate(payload, blindedPayload, nextBlinding) match {
      case Left(f) => DropMessage(CannotDecodeBlindedPayload(f.failureMessage.message))
      case Right(relayPayload) => SendMessage(relayPayload.nextNodeId, OnionMessage(nextBlinding, nextPacket))
    }
  }

  private def validateFinalPayload(payload: TlvStream[OnionMessagePayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv]): Action = {
    FinalPayload.validate(payload, blindedPayload) match {
      case Left(f) => DropMessage(CannotDecodeBlindedPayload(f.failureMessage.message))
      case Right(finalPayload) => ReceiveMessage(finalPayload)
    }
  }

}
