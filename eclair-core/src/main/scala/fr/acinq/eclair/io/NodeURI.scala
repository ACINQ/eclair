package fr.acinq.eclair.io

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey

import scala.util.{Failure, Success, Try}

case class NodeURI(nodeId: PublicKey, address: HostAndPort) {
  override def toString: String = s"$nodeId@$address"
}

object NodeURI {

  val DEFAULT_PORT = 9735

  /**
    * Extracts the PublicKey and InetAddress from a string URI (format pubkey@host:port). Port is optional, default is 9735.
    *
    * @param uri uri of a node, as a String
    * @throws IllegalArgumentException if the uri is not valid and can not be read
    * @return a NodeURI
    */
  @throws[IllegalArgumentException]
  def parse(uri: String): NodeURI = {
    uri.split("@") match {
      case Array(nodeId, address) => (Try(PublicKey(nodeId)), Try(HostAndPort.fromString(address).withDefaultPort(DEFAULT_PORT))) match {
        case (Success(pk), Success(hostAndPort)) => NodeURI(pk, hostAndPort)
        case (Failure(_), _) => throw new IllegalArgumentException("Invalid node id")
        case (_, Failure(_)) => throw new IllegalArgumentException("Invalid host:port")
      }
      case _ => throw new IllegalArgumentException("Invalid uri, should be nodeId@host:port")
    }
  }
}
