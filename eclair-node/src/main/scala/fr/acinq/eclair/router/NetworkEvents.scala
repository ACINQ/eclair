package fr.acinq.eclair.router

/**
  * Created by PM on 26/08/2016.
  */
trait NetworkEvent

case class ChannelDiscovered(c: ChannelDesc) extends NetworkEvent

case class ChannelLost(c: ChannelDesc) extends NetworkEvent

