package fr.acinq.eclair.wire

import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import scodec.codecs._

/**
  * Created by PM on 17/10/2016.
  */
object PacketType extends Enumeration {
  type PacketType = Value
  val ADD = Value(0)
  val FAIL = Value(1)
  val FULFILL = Value(2)

  implicit val commandTypeCodec: Codec[PacketType] =
    mappedEnum(uint(2), PacketType.values.map(v => (v, v.id)).toMap)
}

import fr.acinq.eclair.wire.PacketType._

class PacketTypeCodec() extends Codec[PacketType] {
  override def sizeBound = SizeBound.exact(1)

  override def encode(a: PacketType) = Attempt.successful(BitVector.empty)

  override def decode(buffer: BitVector) =
    buffer.acquire(1) match {
      case Left(e) => Attempt.failure(Err.insufficientBits(1, buffer.size))
      case Right(b) if b(0) == 0 => Attempt.successful(DecodeResult(ADD, buffer))
      case Right(b) if b(0) == 1 => Attempt.successful(DecodeResult(FAIL, buffer))
      case Right(b) if b(0) == 2 => Attempt.successful(DecodeResult(FULFILL, buffer))
    }

  override def toString = s"PacketTypeCodec"
}
