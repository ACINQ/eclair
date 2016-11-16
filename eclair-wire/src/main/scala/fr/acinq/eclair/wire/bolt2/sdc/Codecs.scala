package fr.acinq.eclair.wire.bolt2.sdc

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire.bolt2.custom.OpenChannel
import scodec.Codec
import scodec.codecs._


/**
  * Created by PM on 15/11/2016.
  */
object Codecs {

  def binarydata(size: Int): Codec[BinaryData] = bytes(size).map(d => BinaryData(d.toSeq)).decodeOnly

  val openChannelCodec = (uint32 :: int64 :: int64 :: int64 :: int64 :: int64 :: int64 :: uint32 :: uint32 :: uint32 :: uint16 :: binarydata(33) :: binarydata(33) :: binarydata(33)).as[OpenChannel]

}
