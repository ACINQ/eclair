/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.tor

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.tor.Socks5Connection.{Credentials, Socks5Connect}
import fr.acinq.eclair.wire.protocol._

import java.net._


/**
  * Simple socks 5 client. It should be given a new connection, and will
  *
  * Created by rorp
  *
  * @param connection      underlying TcpConnection
  * @param credentials_opt optional username/password for authentication
  */
class Socks5Connection(connection: ActorRef, credentials_opt: Option[Credentials], command: Socks5Connect) extends Actor with ActorLogging {

  import fr.acinq.eclair.tor.Socks5Connection._

  context watch connection

  val passwordAuth: Boolean = credentials_opt.isDefined

  var isConnected: Boolean = false

  connection ! Tcp.Register(self)
  connection ! Tcp.ResumeReading
  connection ! Tcp.Write(socks5Greeting(passwordAuth))

  override def receive: Receive = greetings

  def greetings: Receive = {
    case Tcp.Received(data) =>
        if (data(0) != 0x05) {
          throw Socks5Error("Invalid SOCKS5 proxy response")
        } else if ((!passwordAuth && data(1) != NoAuth) || (passwordAuth && data(1) != PasswordAuth)) {
          throw Socks5Error("Unrecognized SOCKS5 auth method")
        } else {
          if (data(1) == PasswordAuth) {
            context become authenticate
            val credentials = credentials_opt.getOrElse(throw Socks5Error("credentials are not defined"))
            connection ! Tcp.Write(socks5PasswordAuthenticationRequest(credentials.username, credentials.password))
            connection ! Tcp.ResumeReading
          } else {
            context become connectionRequest
            connection ! Tcp.Write(socks5ConnectionRequest(command.address))
            connection ! Tcp.ResumeReading
          }
        }
      }

  def authenticate: Receive = {
    case Tcp.Received(data) =>
        if (data(0) != 0x01) {
          throw Socks5Error("Invalid SOCKS5 proxy response")
        } else if (data(1) != 0) {
          throw Socks5Error("SOCKS5 authentication failed")
        }
        context become connectionRequest
        connection ! Tcp.Write(socks5ConnectionRequest(command.address))
        connection ! Tcp.ResumeReading
      }

  def connectionRequest: Receive = {
    case Tcp.Received(data) =>
        if (data(0) != 0x05) {
          throw Socks5Error("Invalid SOCKS5 proxy response")
        } else {
          val status = data(1)
          if (status != 0) {
            throw Socks5Error(connectErrors.getOrElse(status, s"Unknown SOCKS5 error $status"))
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
            case _ => throw Socks5Error(s"Unrecognized address type")
          }
          context become connected
          context.parent ! Socks5Connected(connectedAddress)
          isConnected = true
        }
  }

  def connected: Receive = {
    case Tcp.Register(handler, _, _) => context become registered(handler)
  }

  def registered(handler: ActorRef): Receive = {
    case c: Tcp.Command => connection ! c
    case e: Tcp.Event => handler ! e
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(actor) if actor == connection => context stop self
    case _: Tcp.ConnectionClosed => context stop self
    case _ => log.warning(s"unhandled message=$message")
  }

  override def postStop(): Unit = {
    super.postStop()
    connection ! Tcp.Close
    if (!isConnected) {
      context.parent ! command.failureMessage
    }
  }

}

object Socks5Connection {
  def props(tcpConnection: ActorRef, credentials_opt: Option[Credentials], command: Socks5Connect): Props = Props(new Socks5Connection(tcpConnection, credentials_opt, command))

  case class Socks5Connect(address: InetSocketAddress) extends Tcp.Command

  case class Socks5Connected(address: InetSocketAddress) extends Tcp.Event

  case class Socks5Error(message: String) extends RuntimeException(message)

  case class Credentials(username: String, password: String) {
    require(username.length < 256, "username is too long")
    require(password.length < 256, "password is too long")
  }

  val NoAuth: Byte = 0x00
  val PasswordAuth: Byte = 0x02

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

  def socks5Greeting(passwordAuth: Boolean) = ByteString(
    0x05, // SOCKS version
    0x01, // number of authentication methods supported
    if (passwordAuth) PasswordAuth else NoAuth) // auth method

  def socks5PasswordAuthenticationRequest(username: String, password: String): ByteString =
    ByteString(
      0x01, // version of username/password authentication
      username.length.toByte) ++
      ByteString(username) ++
      ByteString(password.length.toByte) ++
      ByteString(password)

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
    case _ => throw Socks5Error("Unknown InetAddress")
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

case class Socks5ProxyParams(address: InetSocketAddress, credentials_opt: Option[Credentials], randomizeCredentials: Boolean, useForIPv4: Boolean, useForIPv6: Boolean, useForTor: Boolean, useForWatchdogs: Boolean, useForDnsHostnames: Boolean)

object Socks5ProxyParams {

  val FakeFirefoxHeaders = Map(
    "User-Agent" -> "Mozilla/5.0 (Windows NT 10.0; rv:78.0) Gecko/20100101 Firefox/78.0",
    "Accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language" -> "en-US,en;q=0.5",
    "Connection" -> "keep-alive",
    "Upgrade-Insecure-Requests" -> "1",
    "Cache-Control" -> "max-age=0"
  )


  def proxyAddress(address: NodeAddress, proxyParams: Socks5ProxyParams): Option[InetSocketAddress] =
    address match {
      case _: IPv4 if proxyParams.useForIPv4 => Some(proxyParams.address)
      case _: IPv6 if proxyParams.useForIPv6 => Some(proxyParams.address)
      case _: Tor2 if proxyParams.useForTor => Some(proxyParams.address)
      case _: Tor3 if proxyParams.useForTor => Some(proxyParams.address)
      case _: DnsHostname if proxyParams.useForDnsHostnames => Some(proxyParams.address)
      case _ => None
    }

  def proxyCredentials(proxyParams: Socks5ProxyParams): Option[Socks5Connection.Credentials] =
    if (proxyParams.randomizeCredentials) {
      // randomize credentials for every proxy connection to enable Tor stream isolation
      Some(Socks5Connection.Credentials(randomBytes(16).toHex, randomBytes(16).toHex))
    } else {
      proxyParams.credentials_opt
    }

}
