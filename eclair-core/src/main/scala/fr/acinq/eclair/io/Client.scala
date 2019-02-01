/*
 * Copyright 2018 ACINQ SAS
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

import java.net.{Inet4Address, Inet6Address, InetSocketAddress}

import akka.actor.{Props, _}
import akka.event.Logging.MDC
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.toHexString
import fr.acinq.eclair.io.Client.ConnectionFailed
import fr.acinq.eclair.tor.Socks5Connection
import fr.acinq.eclair.tor.Socks5Connection.{Socks5Connect, Socks5Connected}
import fr.acinq.eclair.{Logs, NodeParams, randomBytes}

import scala.concurrent.duration._

/**
  * Created by PM on 27/10/2015.
  *
  */
class Client(nodeParams: NodeParams, authenticator: ActorRef, remoteAddress: InetSocketAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]) extends Actor with DiagnosticActorLogging {

  import Tcp._
  import context.system

  // we could connect directly here but this allows to take advantage of the automated mdc configuration on message reception
  self ! 'connect

  private var connection: ActorRef = _

  def receive = {

    case 'connect =>
      val addressToConnect = proxyAddress match {
        case None =>
          log.info(s"connecting to pubkey=$remoteNodeId host=${remoteAddress.getHostString} port=${remoteAddress.getPort}")
          remoteAddress
        case Some(socks5Address) =>
          log.info(s"connecting to SOCKS5 proxy ${str(socks5Address)}")
          socks5Address
      }
      IO(Tcp) ! Connect(addressToConnect, timeout = Some(50 seconds), options = KeepAlive(true) :: Nil, pullMode = true)

    case CommandFailed(_: Connect) =>
      proxyAddress match {
        case None =>
          log.info(s"connection failed to $remoteNodeId@${str(remoteAddress)}")
        case Some(socks5Address) =>
          log.info(s"connection failed to SOCKS5 proxy ${str(socks5Address)}")
      }
      origin_opt.map(_ ! Status.Failure(ConnectionFailed(remoteAddress)))
      context stop self

    case CommandFailed(_: Socks5Connect) =>
      log.info(s"connection failed to $remoteNodeId@${str(remoteAddress)} via SOCKS5 ${proxyAddress.map(str).getOrElse("")}")
      origin_opt.map(_ ! Status.Failure(ConnectionFailed(remoteAddress)))
      context stop self

    case Connected(remote, _) =>
      proxyAddress match {
        case None =>
          connection = sender()
          context watch connection
          log.info(s"connected to pubkey=$remoteNodeId host=${remote.getHostString} port=${remote.getPort}")
          authenticator ! Authenticator.PendingAuth(connection, remoteNodeId_opt = Some(remoteNodeId), address = remoteAddress, origin_opt = origin_opt)
          context become connected(connection)
        case Some(_) =>
          val (username, password) = nodeParams.socksProxy_opt match {
            case Some(_) =>
              // randomize credentials for every proxy connection to enable Tor stream isolation
              (Some(toHexString(randomBytes(16))), Some(toHexString(randomBytes(16))))
            case None =>
              (None, None)
          }
          connection = context.actorOf(Socks5Connection.props(sender(), username, password))
          context watch connection
          connection ! Socks5Connect(remoteAddress)
      }

    case Socks5Connected(_) =>
      proxyAddress match {
        case Some(socks5Address) =>
          log.info(s"connected to pubkey=$remoteNodeId host=${remoteAddress.getHostString} port=${remoteAddress.getPort} via SOCKS5 proxy ${str(socks5Address)}")
          authenticator ! Authenticator.PendingAuth(connection, remoteNodeId_opt = Some(remoteNodeId), address = remoteAddress, origin_opt = origin_opt)
          context become connected(connection)
        case _ =>
          log.error("Hmm.")
      }
  }

  def connected(connection: ActorRef): Receive = {
    case Terminated(actor) if actor == connection =>
      context stop self
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")

  override def mdc(currentMessage: Any): MDC = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))

  private def proxyAddress: Option[InetSocketAddress] = nodeParams.socksProxy_opt.flatMap { proxyParams =>
    remoteAddress.getAddress match {
      case _ if remoteAddress.getHostString.endsWith(".onion") => if (proxyParams.useForTor) Some(proxyParams.address) else None
      case _: Inet4Address => if (proxyParams.useForIPv4) Some(proxyParams.address) else None
      case _: Inet6Address =>if (proxyParams.useForIPv6) Some(proxyParams.address) else None
      case _ => None
    }
  }

  private def str(address: InetSocketAddress): String = s"${address.getHostString}:${address.getPort}"
}

object Client extends App {

  def props(nodeParams: NodeParams, authenticator: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): Props = Props(new Client(nodeParams, authenticator, address, remoteNodeId, origin_opt))

  case class ConnectionFailed(address: InetSocketAddress) extends RuntimeException(s"connection failed to $address")

  case class Socks5ProxyParams(address: InetSocketAddress, randomizeCredentials: Boolean, useForIPv4: Boolean, useForIPv6: Boolean, useForTor: Boolean)

}
