package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedNode, BlindedRoute}
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{ForbiddenTlv, MissingRequiredTlv, onionRoutingPacketCodec}
import scodec.bits.ByteVector

sealed trait OnionMessagePayloadTlv extends Tlv

sealed trait BlindedTlv extends Tlv

object MessageTlv {

  /** Blinding ephemeral public key that should be used to derive shared secrets when using route blinding. */
  case class BlindingPoint(publicKey: PublicKey) extends BlindedTlv

  case class ReplyPath(blindedRoute: BlindedRoute) extends OnionMessagePayloadTlv

  case class EncTlv(bytes: ByteVector) extends OnionMessagePayloadTlv

  case class NextNodeId(nodeId: PublicKey) extends BlindedTlv

  case class Padding(bytes: ByteVector) extends BlindedTlv

  case class PathId(bytes: ByteVector) extends BlindedTlv

  sealed trait MessagePacket

  case class MessageRelayPayload(records: TlvStream[OnionMessagePayloadTlv]) extends MessagePacket {
    val blindedTlv: ByteVector = records.get[MessageTlv.EncTlv].get.bytes
  }

  case class MessageFinalPayload(records: TlvStream[OnionMessagePayloadTlv]) extends MessagePacket {
    val blindedTlv: ByteVector = records.get[MessageTlv.EncTlv].get.bytes
    val replyPath: Option[MessageTlv.ReplyPath] = records.get[MessageTlv.ReplyPath]
  }

  case class RelayBlindedTlv(records: TlvStream[BlindedTlv]) {
    val nextNodeId: PublicKey = records.get[MessageTlv.NextNodeId].get.nodeId
    val nextBlinding: Option[PublicKey] = records.get[MessageTlv.BlindingPoint].map(_.publicKey)
  }

  case class FinalBlindedTlv(records: TlvStream[BlindedTlv]) {
    val pathId: Option[ByteVector] = records.get[MessageTlv.PathId].map(_.bytes)
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

  private val padding: Codec[Padding] = variableSizeBytesLong(varintoverflow, "padding" | bytes).as[Padding]

  private val nextNodeId: Codec[NextNodeId] = variableSizeBytesLong(varintoverflow, "node_id" | publicKey).as[NextNodeId]

  private val blindingKey: Codec[BlindingPoint] = variableSizeBytesLong(varintoverflow, "blinding" | publicKey).as[BlindingPoint]

  private val pathId: Codec[PathId] = variableSizeBytesLong(varintoverflow, "path_id" | bytes).as[PathId]

  private val blindedTlvCodec: Codec[TlvStream[BlindedTlv]] = TlvCodecs.tlvStream[BlindedTlv](
    discriminated[BlindedTlv].by(varint)
      .typecase(UInt64(1), padding)
      .typecase(UInt64(4), nextNodeId)
      .typecase(UInt64(12), blindingKey)
      .typecase(UInt64(14), pathId)).complete

  val relayBlindedTlvCodec: Codec[RelayBlindedTlv] = blindedTlvCodec.narrow({
    case tlvs if tlvs.get[NextNodeId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case tlvs if tlvs.get[PathId].nonEmpty => Attempt.failure(ForbiddenTlv(UInt64(14)))
    case tlvs => Attempt.successful(RelayBlindedTlv(tlvs))
  }, {
    case RelayBlindedTlv(tlvs) => tlvs
  })

  val finalBlindedTlvCodec: Codec[FinalBlindedTlv] = blindedTlvCodec.narrow(
    tlvs => Attempt.successful(FinalBlindedTlv(tlvs))
    , {
      case FinalBlindedTlv(tlvs) => tlvs
    })

  def messageOnionPerHopPayloadCodec(isLastPacket: Boolean): Codec[MessagePacket] = if (isLastPacket) messageFinalPayloadCodec.upcast[MessagePacket] else messageRelayPayloadCodec.upcast[MessagePacket]

  val messageOnionPacketCodec: Codec[OnionRoutingPacket] = onionRoutingPacketCodec(uint16.xmap(_ - 66, _ + 66))

}
