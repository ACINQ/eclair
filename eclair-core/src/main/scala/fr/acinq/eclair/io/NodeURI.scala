package fr.acinq.eclair.io

import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.PublicKey

case class NodeURI(nodeId: PublicKey, address: InetSocketAddress) {
  override def toString: String = s"$nodeId@${address.getHostString}:${address.getPort}"
}

object NodeURI {

  val regex = """([a-fA-F0-9]{66})@([a-zA-Z0-9:\.\-_]+):([0-9]+)""".r


  def parse(uri: String): NodeURI = {
    val regex(nodeid, host, port) = uri
    NodeURI(PublicKey(nodeid), new InetSocketAddress(host, port.toInt))
  }
}
