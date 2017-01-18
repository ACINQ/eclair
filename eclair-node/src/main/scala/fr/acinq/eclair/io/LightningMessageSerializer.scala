package fr.acinq.eclair.io

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.wire.{Codecs, LightningMessage}
import scodec.bits.BitVector

/**
  * Created by fabrice on 16/01/17.
  */
object LightningMessageSerializer extends TransportHandler.Serializer[LightningMessage] {
  override def serialize(t: LightningMessage): BinaryData = {
    val encoded = Codecs.lightningMessageCodec.encode(t)
    encoded.toOption.map(_.toByteArray).getOrElse(throw new RuntimeException(s"cannot encode $t"))
  }

  override def deserialize(bin: BinaryData): LightningMessage = {
    Codecs.lightningMessageCodec.decode(BitVector(bin.toArray)).toOption.get.value
  }
}
