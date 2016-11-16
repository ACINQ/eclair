package fr.acinq.eclair.wire.bolt2

import fr.acinq.eclair.wire.bolt2.custom.OpenChannel
import fr.acinq.eclair.wire.bolt2.sdc.Codecs
import scodec.Attempt.Successful
import scodec.DecodeResult
import scodec.bits.BitVector



/**
  * Created by PM on 15/11/2016.
  */
object Test extends App {

  val open = OpenChannel(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, Array.fill[Byte](33)(1), Array.fill[Byte](33)(2), Array.fill[Byte](33)(3))
  val bin = OpenChannel.write(open)
  assert(open == OpenChannel.read(bin))

  Codecs.openChannelCodec.decode(BitVector(bin)) match {
    case Successful(DecodeResult(open2, _)) =>
      println(open)
      println(open2)
      assert(open == open2)
  }
}
