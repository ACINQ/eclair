package fr.acinq.eclair.wire

import fr.acinq.eclair.wire.PacketType._
import scodec.Codec
import scodec.codecs._

/**
  * Created by PM on 17/10/2016.
  */
object Packet {
  implicit val codec: Codec[Packet] = {
    ("packet_type" | Codec[PacketType]) ::
      (("data_length" | uint16) >>:~ { length =>
        ("data" | vectorOfN(provide(length), uint8)).hlist
      })
  }.as[Packet]
}

case class Packet(packetType: PacketType, dataLength: Int, data: Vector[Int]) {
  require(dataLength == data.length)
}