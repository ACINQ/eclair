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

import java.net.InetSocketAddress

import akka.actor.{Props, _}
import akka.event.Logging.MDC
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.{Logs, NodeParams}
import fr.acinq.eclair.io.Client.ConnectionFailed

import scala.concurrent.duration._

/**
  * Created by PM on 27/10/2015.
  *
  */
class Client(nodeParams: NodeParams, authenticator: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]) extends Actor with DiagnosticActorLogging {

  import Tcp._
  import context.system

  // we could connect directly here but this allows to take advantage of the automated mdc configuration on message reception
  self ! 'connect

  def receive = {
    case 'connect =>
      log.info(s"connecting to pubkey=$remoteNodeId host=${address.getHostString} port=${address.getPort}")
      IO(Tcp) ! Connect(address, timeout = Some(5 seconds), options = KeepAlive(true) :: Nil, pullMode = true)

    case CommandFailed(_: Connect) =>
      log.info(s"connection failed to $remoteNodeId@${address.getHostString}:${address.getPort}")
      origin_opt.map(_ ! Status.Failure(ConnectionFailed(address)))
      context stop self

    case Connected(remote, _) =>
      log.info(s"connected to pubkey=$remoteNodeId host=${remote.getHostString} port=${remote.getPort}")
      val connection = sender
      authenticator ! Authenticator.PendingAuth(connection, remoteNodeId_opt = Some(remoteNodeId), address = address, origin_opt = origin_opt)
      context watch connection
      context become connected(connection)
  }

  def connected(connection: ActorRef): Receive = {
    case Terminated(actor) if actor == connection =>
      context stop self
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")

  override def mdc(currentMessage: Any): MDC = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
}

object Client extends App {

  def props(nodeParams: NodeParams, authenticator: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): Props = Props(new Client(nodeParams, authenticator, address, remoteNodeId, origin_opt))

  case class ConnectionFailed(address: InetSocketAddress) extends RuntimeException(s"connection failed to $address")

}
