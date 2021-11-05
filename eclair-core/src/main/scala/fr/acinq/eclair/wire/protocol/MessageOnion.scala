package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.wire.protocol.EncryptedRecipientDataCodecs.encryptedRecipientDataCodec
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, MissingRequiredTlv, onionRoutingPacketCodec}
import scodec.bits.ByteVector

sealed trait OnionMessagePayloadTlv extends Tlv

object MessageTlv {

  case class ReplyPath(blindedRoute: BlindedRoute) extends OnionMessagePayloadTlv

  case class EncTlv(bytes: ByteVector) extends OnionMessagePayloadTlv

  sealed trait MessagePacket

  case class MessageRelayPayload(records: TlvStream[OnionMessagePayloadTlv]) extends MessagePacket {
    val blindedTlv: ByteVector = records.get[MessageTlv.EncTlv].get.bytes
  }

  case class MessageFinalPayload(records: TlvStream[OnionMessagePayloadTlv]) extends MessagePacket {
    val blindedTlv: ByteVector = records.get[MessageTlv.EncTlv].get.bytes
    val replyPath: Option[MessageTlv.ReplyPath] = records.get[MessageTlv.ReplyPath]
  }

  case class RelayBlindedTlv(records: TlvStream[EncryptedRecipientDataTlv]) {
    val nextNodeId: PublicKey = records.get[EncryptedRecipientDataTlv.OutgoingNodeId].get.nodeId
    val nextBlinding: Option[PublicKey] = records.get[EncryptedRecipientDataTlv.NextBlinding].map(_.blinding)
  }

  case class FinalBlindedTlv(records: TlvStream[EncryptedRecipientDataTlv]) {
    val recipientSecret: Option[ByteVector] = records.get[EncryptedRecipientDataTlv.RecipientSecret].map(_.data)
  }
}

object MessageOnion {

  import MessageTlv._
  import fr.acinq.eclair.wire.protocol.CommonCodecs._
  import scodec.codecs._
  import scodec.{Attempt, Codec}

  private val replyHopCodec: Codec[BlindedNode] = (("nodeId" | publicKey) :: ("encTlv" | variableSizeBytes(uint16, bytes))).as[BlindedNode]

  private val replyPathCodec: Codec[ReplyPath] = variableSizeBytesLong(varintoverflow, ("firstNodeId" | publicKey) :: ("blinding" | publicKey) :: ("path" | list(replyHopCodec).xmap[Seq[BlindedNode]](_.toSeq, _.toList))).as[BlindedRoute].as[ReplyPath]

  private val encTlvCodec: Codec[EncTlv] = variableSizeBytesLong(varintoverflow, bytes).as[EncTlv]

  private val messageTlvCodec = discriminated[OnionMessagePayloadTlv].by(varint)
    .typecase(UInt64(2), replyPathCodec)
    .typecase(UInt64(10), encTlvCodec)

  val messagePerHopPayloadCodec: Codec[TlvStream[OnionMessagePayloadTlv]] = TlvCodecs.lengthPrefixedTlvStream[OnionMessagePayloadTlv](messageTlvCodec).complete

  val messageRelayPayloadCodec: Codec[MessageRelayPayload] = messagePerHopPayloadCodec.narrow({
    case tlvs if tlvs.get[EncTlv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(10)))
    case tlvs if tlvs.get[ReplyPath].nonEmpty => Attempt.failure(ForbiddenTlv(UInt64(2)))
    case tlvs => Attempt.successful(MessageRelayPayload(tlvs))
  }, {
    case MessageRelayPayload(tlvs) => tlvs
  })

  val messageFinalPayloadCodec: Codec[MessageFinalPayload] = messagePerHopPayloadCodec.narrow({
    case tlvs if tlvs.get[EncTlv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(10)))
    case tlvs => Attempt.successful(MessageFinalPayload(tlvs))
  }, {
    case MessageFinalPayload(tlvs) => tlvs
  })

  val relayBlindedTlvCodec: Codec[RelayBlindedTlv] = encryptedRecipientDataCodec.narrow({
    case tlvs if tlvs.get[EncryptedRecipientDataTlv.OutgoingNodeId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case tlvs if tlvs.get[EncryptedRecipientDataTlv.RecipientSecret].nonEmpty => Attempt.failure(ForbiddenTlv(UInt64(6)))
    case tlvs => Attempt.successful(RelayBlindedTlv(tlvs))
  }, {
    case RelayBlindedTlv(tlvs) => tlvs
  })

  val finalBlindedTlvCodec: Codec[FinalBlindedTlv] = encryptedRecipientDataCodec.narrow(
    tlvs => Attempt.successful(FinalBlindedTlv(tlvs))
    , {
      case FinalBlindedTlv(tlvs) => tlvs
    })

  def messageOnionPerHopPayloadCodec(isLastPacket: Boolean): Codec[MessagePacket] = if (isLastPacket) messageFinalPayloadCodec.upcast[MessagePacket] else messageRelayPayloadCodec.upcast[MessagePacket]

  val messageOnionPacketCodec: Codec[OnionRoutingPacket] = onionRoutingPacketCodec(uint16.xmap(_ - 66, _ + 66))

}
