package fr.acinq.eclair.io

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.wire.{Codecs, LightningMessage}
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

/**
  * Created by fabrice on 16/01/17.
  */
object LightningMessageSerializer extends TransportHandler.Serializer[LightningMessage] {

  override def serialize(t: LightningMessage): BinaryData =
    Codecs.lightningMessageCodec.encode(t) match {
      case Attempt.Successful(bitVector) => BinaryData(bitVector.toByteArray)
      case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
    }

  override def deserialize(bin: BinaryData): LightningMessage =
    Codecs.lightningMessageCodec.decode(BitVector(bin.data)) match {
      case Attempt.Successful(DecodeResult(msg, _)) => msg
      case Attempt.Failure(cause) => throw new RuntimeException(s"deserialization error: $cause")
    }
}
