package fr.acinq.eclair.tor

import java.net.InetSocketAddress

import org.apache.commons.codec.binary.Base32

/**
  * Created by rorp
  */
sealed trait OnionAddress {
  import OnionAddress._

  val onionService: String

  def getHostString: String = s"$onionService$OnionSuffix"

  val getPort: Int

  def toOnion: String = s"$getHostString:$getPort"

  def decodedOnionService: Array[Byte] = base32decode(onionService.toUpperCase)

  def toInetSocketAddress: InetSocketAddress = new InetSocketAddress(getHostString, getPort)
}

case class OnionAddressV2(onionService: String, getPort: Int) extends OnionAddress {
  require(onionService.length == OnionAddress.v2Len)
}

case class OnionAddressV3(onionService: String, getPort: Int) extends OnionAddress {
  require(onionService.length == OnionAddress.v3Len)
}

object OnionAddress {
  val OnionSuffix = ".onion"
  val v2Len = 16
  val v3Len = 56

  def hostString(host: Array[Byte]): String = s"${base32encode(host)}$OnionSuffix"

  def fromParts(host: Array[Byte], port: Int): OnionAddress = {
    val onionService = base32encode(host)
    onionService.length match {
      case `v2Len` => OnionAddressV2(onionService, port)
      case `v3Len` => OnionAddressV3(onionService, port)
      case _ => throw new RuntimeException(s"Invalid Tor address `$onionService`")
    }
  }

  def fromParts(hostname: String, port: Int): Option[OnionAddress] = if (isOnion(hostname)) {
    val onionService = hostname.stripSuffix(OnionSuffix)
    onionService.length match {
      case `v2Len` => Some(OnionAddressV2(onionService, port))
      case `v3Len` => Some(OnionAddressV3(onionService, port))
      case _ => None
    }
  } else {
    None
  }

  def isOnion(hostname: String): Boolean = hostname.endsWith(OnionSuffix)

  def decodeHostname(hostname: String): Array[Byte] = base32decode(hostname.stripSuffix(OnionSuffix))

  def base32decode(s: String): Array[Byte] = new Base32().decode(s.toUpperCase)

  def base32encode(a: Seq[Byte]): String = new Base32().encodeAsString(a.toArray).toLowerCase
}