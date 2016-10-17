package fr.acinq.eclair.wire

import scodec.Codec
import scodec.bits._

/**
  * Created by PM on 17/10/2016.
  */
object Test extends App {
  val bin = hex"0x00010000000000".bits
  println(Codec[Packet].decode(bin))
}


