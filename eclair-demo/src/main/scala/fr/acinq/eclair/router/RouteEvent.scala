package fr.acinq.eclair.router

import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 18/08/2016.
  */
trait RouteEvent

case class ChannelDiscovered(id: BinaryData, a: BinaryData, b: BinaryData) extends RouteEvent