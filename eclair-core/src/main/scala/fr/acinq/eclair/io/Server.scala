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

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy}
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.wire.{LightningMessage, LightningMessageCodecs}

import scala.concurrent.Promise

/**
  * Created by PM on 27/10/2015.
  */
class Server(nodeParams: NodeParams, authenticator: ActorRef, address: InetSocketAddress, bound: Option[Promise[Done]] = None) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, address, options = KeepAlive(true) :: Nil, pullMode = true)

  def receive() = {
    case Bound(localAddress) =>
      bound.map(_.success(Done))
      log.info(s"bound on $localAddress")
      // Accept connections one by one
      sender() ! ResumeAccepting(batchSize = 1)
      context.become(listening(sender()))

    case CommandFailed(_: Bind) =>
      bound.map(_.failure(new RuntimeException("TCP bind failed")))
      context stop self
  }

  def listening(listener: ActorRef): Receive = {
    case Connected(remote, _) =>
      log.info(s"connected to $remote")
      val connection = sender
      authenticator ! Authenticator.PendingAuth(connection, remoteNodeId_opt = None, address = remote, origin_opt = None)
      listener ! ResumeAccepting(batchSize = 1)
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")
}

object Server {

  def props(nodeParams: NodeParams, switchboard: ActorRef, address: InetSocketAddress, bound: Option[Promise[Done]] = None): Props = Props(new Server(nodeParams, switchboard, address, bound))

}

