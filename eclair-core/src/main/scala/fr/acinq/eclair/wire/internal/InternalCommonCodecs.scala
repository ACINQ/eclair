package fr.acinq.eclair.wire.internal

import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.wire.CommonCodecs.varintoverflow
import scodec.Codec
import scodec.codecs.{bytes, variableSizeBytesLong}

object InternalCommonCodecs {

  /**
   * All LN protocol message must be stored as length-delimited, because they may have arbitrary trailing data
   */
  def lengthDelimited[T](codec: Codec[T]): Codec[T] = variableSizeBytesLong(varintoverflow, codec)

  val txCodec: Codec[Transaction] = lengthDelimited(bytes.xmap(d => Transaction.read(d.toArray), d => Transaction.write(d)))

}
