package fr.acinq.eclair.io

import java.net.{Inet6Address, InetSocketAddress}

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import grizzled.slf4j.Logging
import org.spongycastle.util.encoders.DecoderException

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case class NodeURI(nodeId: PublicKey, address: InetSocketAddress) {
  override def toString: String = s"$nodeId@$getHostAndPort"
  def getHostAndPort = NodeURI.getHostAndPort(address)
}

object NodeURI extends Logging {

  val DEFAULT_PORT = 9735
  val regex: Regex = """([a-fA-F0-9]{66})@([a-zA-Z0-9:\[\]%\/\.\-_]+):([0-9]+)""".r

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
      case Array(nodeId, address) => Try(PublicKey(nodeId), HostAndPort.fromString(address).withDefaultPort(DEFAULT_PORT)) match {
        case Success((pk, hostPort)) =>
          logger.debug(s"parsed uri=$uri to pk=$pk host=$hostPort")
          NodeURI(PublicKey(nodeId), new InetSocketAddress(hostPort.getHost, hostPort.getPort))
        case Failure(t) if t.isInstanceOf[scala.MatchError] || t.isInstanceOf[DecoderException] =>
          logger.debug(s"could not parse uri=$uri with cause=${t.getMessage}")
          throw new IllegalArgumentException("Public key is not valid")
        case Failure(t) =>
          logger.error(s"could not parse uri=$uri with cause = ${t.getMessage}")
          throw new IllegalArgumentException("URI is not valid. Should be pubkey@host:port")
      }
      case _ => throw new IllegalArgumentException("URI is not valid. Should be pubkey@host:port")
    }
  }

  /**
    * Returns host of address. If address is IPV6 and not bracketed, surrounds host with brackets.
    *
    * @param address Inet address
    * @return a formatted host preventing issue with ipv6 addresses when parsing host and port
    */
  def getHostAndPort(address: InetSocketAddress): String = {
    address.getAddress match {
      case a: Inet6Address if !a.getHostAddress.startsWith("[") && !a.getHostAddress.endsWith("]") => s"[${a.getHostAddress}]:${address.getPort}"
      case _ => s"${address.getHostString}:${address.getPort}"
    }
  }
}
