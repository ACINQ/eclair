package fr.acinq.eclair.wire.bolt2

import fr.acinq.eclair.wire.bolt2.custom._
import fr.acinq.eclair.wire.bolt2.sdc.Codecs.lightningMessageCodec


/**
  * Created by PM on 15/11/2016.
  */
object Test extends App {

  val open = OpenChannel(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, Array.fill[Byte](33)(1), Array.fill[Byte](33)(2), Array.fill[Byte](33)(3))
  val accept = AcceptChannel(2, 3, 4, 5, 6, 7, 8, Array.fill[Byte](32)(0), 9, Array.fill[Byte](33)(1), Array.fill[Byte](33)(2), Array.fill[Byte](33)(3))
  val funding_created = FundingCreated(2, Array.fill[Byte](32)(0), 3, Array.fill[Byte](64)(1))
  val funding_signed = FundingSigned(2, Array.fill[Byte](64)(1))
  val funding_locked = FundingLocked(1, 2, Array.fill[Byte](32)(1), Array.fill[Byte](33)(2))

  val msgs: List[LightningMessage] = open :: accept :: funding_created :: funding_signed :: funding_locked :: Nil

  msgs.foreach {
    case msg =>
      val bin = lightningMessageCodec.encode(msg)
      println(bin)
      println(bin.flatMap(lightningMessageCodec.decode(_)))
  }

}
