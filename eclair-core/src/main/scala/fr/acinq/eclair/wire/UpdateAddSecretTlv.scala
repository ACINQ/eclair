package fr.acinq.eclair.wire

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec}

sealed trait UpdateAddSecretTlv extends Tlv

object UpdateAddSecretTlv {
  case class Secret(data: ByteVector) extends UpdateAddSecretTlv

  val codec: Codec[TlvStream[Tlv]] = {
    val secretCodec: Codec[Secret] = Codec(varsizebinarydata withContext "data").as[Secret]

    val discriminatorCodec: DiscriminatorCodec[Tlv, UInt64] = discriminated.by(varint).typecase(UInt64(1), secretCodec)

    val prefixedTlvCodec = variableSizeBytesLong(value = TlvCodecs.tlvStream(discriminatorCodec), size = varintoverflow)

    fallback(provide(TlvStream.empty[Tlv]), prefixedTlvCodec).narrow(f = {
      case Left(emptyFallback) => Attempt.successful(emptyFallback)
      case Right(realStream) => Attempt.successful(realStream)
    }, g = Right.apply)
  }
}