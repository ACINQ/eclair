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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.MessageOnion.{BlindedFinalPayload, BlindedRelayPayload, FinalPayload, RelayPayload}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import scala.util.{Failure, Success}

object OnionMessages {

  case class IntermediateNode(nodeId: PublicKey, padding: Option[ByteVector] = None)

  case class Recipient(nodeId: PublicKey, pathId: Option[ByteVector], padding: Option[ByteVector] = None)

  def buildRoute(blindingSecret: PrivateKey,
                 intermediateNodes: Seq[IntermediateNode],
                 destination: Either[Recipient, Sphinx.RouteBlinding.BlindedRoute]): Sphinx.RouteBlinding.BlindedRoute = {
    val last = destination match {
      case Left(Recipient(nodeId, _, _)) => EncryptedRecipientDataTlv.OutgoingNodeId(nodeId) :: Nil
      case Right(Sphinx.RouteBlinding.BlindedRoute(nodeId, blindingKey, _)) => EncryptedRecipientDataTlv.OutgoingNodeId(nodeId) :: EncryptedRecipientDataTlv.NextBlinding(blindingKey) :: Nil
    }
    val intermediatePayloads =
      if (intermediateNodes.isEmpty) {
        Nil
      } else {
        (intermediateNodes.tail.map(node => EncryptedRecipientDataTlv.OutgoingNodeId(node.nodeId) :: Nil) :+ last)
          .zip(intermediateNodes).map { case (tlvs, hop) => hop.padding.map(EncryptedRecipientDataTlv.Padding(_) :: Nil).getOrElse(Nil) ++ tlvs }
          .map(tlvs => BlindedRelayPayload(TlvStream(tlvs)))
          .map(MessageOnionCodecs.blindedRelayPayloadCodec.encode(_).require.bytes)
      }
    destination match {
      case Left(Recipient(nodeId, pathId, padding)) =>
        val tlvs = padding.map(EncryptedRecipientDataTlv.Padding(_) :: Nil).getOrElse(Nil) ++ pathId.map(EncryptedRecipientDataTlv.PathId(_) :: Nil).getOrElse(Nil)
        val lastPayload = MessageOnionCodecs.finalBlindedTlvCodec.encode(BlindedFinalPayload(TlvStream(tlvs))).require.bytes
        Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.nodeId) :+ nodeId, intermediatePayloads :+ lastPayload)
      case Right(route) =>
        val Sphinx.RouteBlinding.BlindedRoute(introductionNodeId, blindingKey, blindedNodes) = Sphinx.RouteBlinding.create(blindingSecret, intermediateNodes.map(_.nodeId), intermediatePayloads)
        Sphinx.RouteBlinding.BlindedRoute(introductionNodeId, blindingKey, blindedNodes ++ route.blindedNodes)
    }
  }

  def buildMessage(sessionKey: PrivateKey,
                   blindingSecret: PrivateKey,
                   intermediateNodes: Seq[IntermediateNode],
                   destination: Either[Recipient, Sphinx.RouteBlinding.BlindedRoute],
                   content: List[OnionMessagePayloadTlv]): (PublicKey, OnionMessage) = {
    val route = buildRoute(blindingSecret, intermediateNodes, destination)
    val lastPayload = MessageOnionCodecs.finalPerHopPayloadCodec.encode(FinalPayload(TlvStream(EncryptedData(route.encryptedPayloads.last) :: content))).require.bytes
    val payloads = route.encryptedPayloads.dropRight(1).map(encTlv => MessageOnionCodecs.relayPerHopPayloadCodec.encode(RelayPayload(TlvStream(EncryptedData(encTlv)))).require.bytes) :+ lastPayload
    val payloadSize = payloads.map(_.length + Sphinx.MacLength).sum
    val packetSize = if (payloadSize <= 1300) {
      1300
    } else if (payloadSize <= 32768) {
      32768
    } else {
      payloadSize.toInt
    }
    val Sphinx.PacketAndSecrets(packet, _) = Sphinx.create(sessionKey, packetSize, route.blindedNodes.map(_.blindedPublicKey), payloads, None)
    (route.introductionNodeId, OnionMessage(blindingSecret.publicKey, packet))
  }

  // @formatter:off
  sealed trait Action
  case class DropMessage(reason: DropReason) extends Action
  case class RelayMessage(nextNodeId: PublicKey, dataToRelay: OnionMessage) extends Action
  case class ReceiveMessage(finalPayload: FinalPayload, pathId: Option[ByteVector]) extends Action

  sealed trait DropReason
  case class MessageTooLarge(size: Long) extends DropReason { override def toString = s"message too large (size=$size, max=32768)" }
  case class CannotDecryptOnion(message: String) extends DropReason { override def toString = s"can't decrypt onion: $message" }
  case class CannotDecodeOnion(message: String) extends DropReason { override def toString = s"can't decode onion: $message" }
  case class CannotDecryptBlindedPayload(message: String) extends DropReason { override def toString = s"can't decrypt blinded payload: $message" }
  case class CannotDecodeBlindedPayload(message: String) extends DropReason { override def toString = s"can't decode blinded payload: $message" }
  // @formatter:on

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
                    RelayMessage(relayNext.nextNodeId, toRelay)
                  case Attempt.Failure(err) => DropMessage(CannotDecodeBlindedPayload(err.message))
                }
              case Failure(err) => DropMessage(CannotDecryptBlindedPayload(err.getMessage))
            }
          case Attempt.Successful(DecodeResult(finalPayload: FinalPayload, _)) =>
            Sphinx.RouteBlinding.decryptPayload(privateKey, msg.blindingKey, finalPayload.encryptedData) match {
              case Success((decrypted, _)) =>
                MessageOnionCodecs.finalBlindedTlvCodec.decode(decrypted.bits) match {
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
