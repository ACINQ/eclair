package fr.acinq.eclair.tor

import org.apache.commons.codec.binary.Base32

sealed trait OnionAddress {
  val onionService: String

  val port: Int

  def toOnion: String = s"$onionService.onion:$port"

  def decodedOnionService: Array[Byte] = new Base32().decode(onionService.toUpperCase)
}
case class OnionAddressV2(onionService: String, port: Int) extends OnionAddress { require(onionService.length == 16) }
case class OnionAddressV3(onionService: String, port: Int) extends OnionAddress { require(onionService.length == 56) }

