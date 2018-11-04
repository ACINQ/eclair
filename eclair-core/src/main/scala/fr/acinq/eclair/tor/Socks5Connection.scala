package fr.acinq.eclair.tor

import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString

class Socks5Connection(underlying: ActorRef) extends Actor with ActorLogging {

  import fr.acinq.eclair.tor.Socks5Connection._

  private var handler: ActorRef = _

  context watch underlying

  override def receive: Receive = {
    case c@Socks5Connect(address) =>
      context become greetings(sender(), c)
      underlying ! Tcp.Register(self)
      underlying ! Tcp.ResumeReading
      underlying ! Tcp.Write(socks5Greeting)
  }

  def greetings(commander: ActorRef, connectCommand: Socks5Connect): Receive = {
    case Tcp.Received(data) =>
      try {
        if (data(0) != 0x05) {
          throw new RuntimeException("Invalid SOCKS5 proxy response")
        } else if (data(1) != 0x00) {
          throw new RuntimeException("Unrecognized SOCKS5 auth method")
        } else {
          context become connectionRequest(commander, connectCommand)
          underlying ! Tcp.Write(socks5ConnectionRequest(connectCommand.address))
          underlying ! Tcp.ResumeReading
        }
      } catch {
        case e: Throwable => handleErrors("Error connecting to SOCKS5 proxy", commander, connectCommand, e)
      }
    case c: Tcp.ConnectionClosed =>
      commander ! c
      context stop self
  }

  def connectionRequest(commander: ActorRef, connectCommand: Socks5Connect): Receive = {
    case c@Tcp.Received(data) =>
      try {
      if (data(0) != 0x05) {
        throw new RuntimeException("Invalid SOCKS5 proxy response")
      } else {
          val status = data(1)
          if (status != 0) {
            throw new RuntimeException(connectErrors.getOrElse(status, s"Unknown SOCKS5 error $status"))
          }
          val connectedAddress = data(3) match {
            case 0x01 =>
              val ip = Array(data(4), data(5), data(6), data(7))
              val port = data(8).toInt << 8 | data(9)
              new InetSocketAddress(InetAddress.getByAddress(ip), port)
            case 0x03 =>
              val len = data(4)
              val start = 5
              val end = start + len
              val domain = data.slice(start, end).utf8String
              val port = data(end).toInt << 8 | data(end + 1)
              new InetSocketAddress(domain, port)
            case 0x04 =>
              val ip = Array.ofDim[Byte](16)
              data.copyToArray(ip, 4, 4 + ip.length)
              val port = data(4 + ip.length).toInt << 8 | data(4 + ip.length + 1)
              new InetSocketAddress(InetAddress.getByAddress(ip), port)
            case _ => throw new RuntimeException(s"Unrecognized address type")
          }
          context become connected
          log.info(s"connected $connectedAddress")
          commander ! Socks5Connected(connectedAddress)
      }
      } catch {
        case e: Throwable => handleErrors("Cannot establish SOCKS5 connection", commander, connectCommand, e)
      }
    case c: Tcp.ConnectionClosed =>
      commander ! c
      context stop self
  }

  def connected: Receive = {
    case Tcp.Register(actor, keepOpenOnPeerClosed, useResumeWriting) =>
      handler = actor
      context become registered
  }

  def registered: Receive = {
    case c: Tcp.Command => underlying ! c
    case e: Tcp.Event => handler ! e
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(actor) if actor == underlying => context stop self
    case _ => log.warning(s"unhandled message=$message")
  }

  private def handleErrors(message: String, commander: ActorRef, connectCommand: Socks5Connect, e: Throwable): Unit = {
    log.error(e, message + " ")
    underlying ! Tcp.Close
    commander ! connectCommand.failureMessage
  }
}

object Socks5Connection {
  def props(tcpConnection: ActorRef): Props = Props(new Socks5Connection(tcpConnection))

  case class Socks5Connected(address: InetSocketAddress) extends Tcp.Event

  case class Socks5Connect(address: InetSocketAddress) extends Tcp.Command

  val connectErrors: Map[Byte, String] = Map[Byte, String](
    (0x00, "Request granted"),
    (0x01, "General failure"),
    (0x02, "Connection not allowed by ruleset"),
    (0x03, "Network unreachable"),
    (0x04, "Host unreachable"),
    (0x05, "Connection refused by destination host"),
    (0x06, "TTL expired"),
    (0x07, "Command not supported / protocol error"),
    (0x08, "Address type not supported")
  )

  val socks5Greeting = ByteString(
    0x05, // SOCKS version
    0x01, // number of authentication methods supported
    0x00) // reserved

  def socks5ConnectionRequest(address: InetSocketAddress): ByteString = {
    ByteString(
      0x05, // SOCKS version
      0x01, // establish a TCP/IP stream connection
      0x00) ++ // reserved
      addressToByteString(address) ++
      portToByteString(address.getPort)
  }

  def inetAddressToByteString(inet: InetAddress): ByteString = inet match {
    case a: Inet4Address => ByteString(
      0x01 // IPv4 address
    ) ++ ByteString(a.getAddress)
    case a: Inet6Address => ByteString(
      0x04 // IPv6 address
    ) ++ ByteString(a.getAddress)
    case _ => throw new RuntimeException("Unknown InetAddress")
  }

  def addressToByteString(address: InetSocketAddress): ByteString = Option(address.getAddress) match {
    case None =>
      // unresolved address, use SOCKS5 resolver
      val host = address.getHostString
      ByteString(
        0x03, // Domain name
        host.length.toByte) ++
        ByteString(host)
    case Some(inetAddress) =>
      inetAddressToByteString(inetAddress)
  }

  def portToByteString(port: Int): ByteString = ByteString((port & 0x0000ff00) >> 8, port & 0x000000ff)
}
