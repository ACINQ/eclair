package fr.acinq.eclair.message

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.MessageOnion.{finalBlindedTlvCodec, messageOnionPerHopPayloadCodec, messageRelayPayloadCodec, relayBlindedTlvCodec}
import fr.acinq.eclair.wire.protocol.MessageTlv._
import fr.acinq.eclair.wire.protocol.{BadOnion, EncryptedRecipientDataTlv, OnionMessage, OnionMessagePayloadTlv, TlvStream}
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import scala.util.{Failure, Success}

object OnionMessages {

  case class IntermediateNode(nodeId: PublicKey, padding: Option[ByteVector] = None)

  case class Recipient(nodeId: PublicKey, secret: Option[ByteVector], padding: Option[ByteVector] = None)

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
          .map(tlvs => RelayBlindedTlv(TlvStream(tlvs)))
          .map(relayBlindedTlvCodec.encode(_).require.bytes)
      }
    destination match {
      case Left(Recipient(nodeId, recipientSecret, padding)) =>
        val tlvs = padding.map(EncryptedRecipientDataTlv.Padding(_) :: Nil).getOrElse(Nil) ++ recipientSecret.map(EncryptedRecipientDataTlv.RecipientSecret(_) :: Nil).getOrElse(Nil)
        val lastPayload = finalBlindedTlvCodec.encode(FinalBlindedTlv(TlvStream(tlvs))).require.bytes
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
    val lastPayload = messageRelayPayloadCodec.encode(MessageRelayPayload(TlvStream(EncTlv(route.encryptedPayloads.last) :: content))).require.bytes
    val payloads = route.encryptedPayloads.dropRight(1).map(encTlv => messageRelayPayloadCodec.encode(MessageRelayPayload(TlvStream(EncTlv(encTlv)))).require.bytes) :+ lastPayload
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

  sealed trait Action

  case class DropMessage(reason: String) extends Action

  case class RelayMessage(nextNodeId: PublicKey, dataToRelay: OnionMessage) extends Action

  case class ReceiveMessage(finalPayload: MessageFinalPayload, pathId: Option[ByteVector]) extends Action

  def process(privateKey: PrivateKey, msg: OnionMessage): Action = {
    if (msg.onionRoutingPacket.payload.length > 32768) {
      DropMessage("Message too large")
    } else {
      val blindedPrivateKey = Sphinx.RouteBlinding.derivePrivateKey(privateKey, msg.blindingKey)
      Sphinx.peel(blindedPrivateKey, None, msg.onionRoutingPacket) match {
        case Left(_: BadOnion) => DropMessage("Can't decrypt onion")
        case Right(p@Sphinx.DecryptedPacket(payload, nextPacket, _)) => messageOnionPerHopPayloadCodec(p.isLastPacket).decode(payload.bits) match {
          case Attempt.Successful(DecodeResult(relayPayload: MessageRelayPayload, _)) =>
            Sphinx.RouteBlinding.decryptPayload(privateKey, msg.blindingKey, relayPayload.blindedTlv) match {
              case Success((decrypted, nextBlindingKey)) =>
                relayBlindedTlvCodec.decode(decrypted.bits) match {
                  case Attempt.Successful(DecodeResult(relayNext, _)) =>
                    val toRelay = OnionMessage(relayNext.nextBlinding.getOrElse(nextBlindingKey), nextPacket)
                    RelayMessage(relayNext.nextNodeId, toRelay)
                  case Attempt.Failure(_) => DropMessage("Can't decode blinded TLV")
                }
              case Failure(_) => DropMessage("Can't decrypt blinded TLV")
            }
          case Attempt.Successful(DecodeResult(finalPayload: MessageFinalPayload, _)) =>
            Sphinx.RouteBlinding.decryptPayload(privateKey, msg.blindingKey, finalPayload.blindedTlv) match {
              case Success((decrypted, _)) =>
                finalBlindedTlvCodec.decode(decrypted.bits) match {
                  case Attempt.Successful(DecodeResult(messageToSelf, _)) =>
                    ReceiveMessage(finalPayload, messageToSelf.recipientSecret)
                  case Attempt.Failure(_) => DropMessage("Can't decode blinded TLV")
                }
              case Failure(_) => DropMessage("Can't decrypt blinded TLV")
            }
          case Attempt.Failure(_) => DropMessage("Can't decode onion")
        }
      }
    }
  }
}
