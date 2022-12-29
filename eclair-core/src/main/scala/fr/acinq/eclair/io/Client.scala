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

package fr.acinq.eclair.io

import akka.actor.{Props, _}
import akka.event.Logging.MDC
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.tor.Socks5Connection.{Socks5Connect, Socks5Connected, Socks5Error}
import fr.acinq.eclair.tor.{Socks5Connection, Socks5ProxyParams}
import fr.acinq.eclair.wire.protocol._

import java.net.InetSocketAddress
import scala.concurrent.duration._

/**
 * Created by PM on 27/10/2015.
 *
 */
class Client(keyPair: KeyPair, socks5ProxyParams_opt: Option[Socks5ProxyParams], peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef, remoteNodeAddress: NodeAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef], isPersistent: Boolean) extends Actor with DiagnosticActorLogging {

  import context.system

  // we could connect directly here but this allows to take advantage of the automated mdc configuration on message reception
  self ! Symbol("connect")

  def receive: Receive = {
    case Symbol("connect") =>
      // note that only DNS host names are resolved here; plain ip addresses and tor hostnames are not resolved
      val remoteAddress = remoteNodeAddress match {
        case addr: IPv4 => new InetSocketAddress(addr.ipv4, addr.port)
        case addr: IPv6 => new InetSocketAddress(addr.ipv6, addr.port)
        case addr: Tor2 => InetSocketAddress.createUnresolved(addr.host, addr.port)
        case addr: Tor3 => InetSocketAddress.createUnresolved(addr.host, addr.port)
        case addr: DnsHostname => new InetSocketAddress(addr.host, addr.port)
      }
      val (peerOrProxyAddress, proxyParams_opt) = socks5ProxyParams_opt.map(proxyParams => (proxyParams, Socks5ProxyParams.proxyAddress(remoteNodeAddress, proxyParams))) match {
        case Some((proxyParams, Some(proxyAddress))) => (proxyAddress, Some(proxyParams))
        case _ => (remoteAddress, None)
      }
      IO(Tcp) ! Tcp.Connect(peerOrProxyAddress, timeout = Some(20 seconds), options = KeepAlive(true) :: Nil, pullMode = true)
      context become connecting(proxyParams_opt, remoteAddress)
  }

  def connecting(proxyParams: Option[Socks5ProxyParams], remoteAddress: InetSocketAddress): Receive = {
    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val peerOrProxyAddress = c.remoteAddress
      log.info(s"connection failed to ${str(peerOrProxyAddress)}")
      origin_opt.foreach(_ ! PeerConnection.ConnectionResult.ConnectionFailed(remoteNodeAddress))
      context stop self

    case Tcp.Connected(peerOrProxyAddress, _) =>
      val connection = sender()
      proxyParams match {
        case Some(proxyParams) =>
          val proxyAddress = peerOrProxyAddress
          log.info(s"connecting to ${str(remoteAddress)} via SOCKS5 ${str(proxyAddress)}")
          val proxy = context.actorOf(Socks5Connection.props(sender(), Socks5ProxyParams.proxyCredentials(proxyParams), Socks5Connect(remoteAddress)))
          context watch proxy
          context become {
            case Tcp.CommandFailed(_: Socks5Connect) =>
              log.info(s"connection failed to ${str(remoteAddress)} via SOCKS5 ${str(proxyAddress)}")
              origin_opt.foreach(_ ! PeerConnection.ConnectionResult.ConnectionFailed(remoteNodeAddress))
              context stop self
            case Socks5Connected(_) =>
              log.info(s"connected to ${str(remoteAddress)} via SOCKS5 proxy ${str(proxyAddress)}")
              context unwatch proxy
              val peerConnection = auth(proxy)
              context watch peerConnection
              context become connected(peerConnection)
            case Terminated(actor) if actor == proxy =>
              context stop self
          }
        case None =>
          val peerAddress = peerOrProxyAddress
          log.info(s"connected to ${str(peerAddress)}")
          val peerConnection = auth(connection)
          context watch peerConnection
          context become connected(peerConnection)
      }
  }

  def connected(peerConnection: ActorRef): Receive = {
    case Terminated(actor) if actor == peerConnection =>
      context stop self
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"unhandled message=$message")
  }

  // we should not restart a failing socks client or transport handler
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case t =>
      Logs.withMdc(log)(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
        t match {
          case Socks5Error(msg) => log.info(s"SOCKS5 error: $msg")
          case _ => log.error(t, "")
        }
      }
      SupervisorStrategy.Stop
  }

  override def mdc(currentMessage: Any): MDC = Logs.mdc(Some(LogCategory.CONNECTION), remoteNodeId_opt = Some(remoteNodeId))

  private def str(address: InetSocketAddress): String = s"${address.getHostString}:${address.getPort}"

  def auth(connection: ActorRef): ActorRef = {
    val peerConnection = context.actorOf(PeerConnection.props(
      keyPair = keyPair,
      conf = peerConnectionConf,
      switchboard = switchboard,
      router = router
    ))
    peerConnection ! PeerConnection.PendingAuth(connection, remoteNodeId_opt = Some(remoteNodeId), address = remoteNodeAddress, origin_opt = origin_opt, isPersistent = isPersistent)
    peerConnection
  }
}

object Client {

  def props(keyPair: KeyPair, socks5ProxyParams_opt: Option[Socks5ProxyParams], peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef, address: NodeAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef], isPersistent: Boolean): Props = Props(new Client(keyPair, socks5ProxyParams_opt, peerConnectionConf, switchboard, router, address, remoteNodeId, origin_opt, isPersistent))

}
