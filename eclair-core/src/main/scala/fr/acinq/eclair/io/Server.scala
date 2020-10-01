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

import java.net.InetSocketAddress

import akka.Done
import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, Props}
import akka.event.Logging.MDC
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.eclair.Logs
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.Noise.KeyPair

import scala.concurrent.Promise

/**
 * Created by PM on 27/10/2015.
 */
class Server(keyPair: KeyPair, peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef, address: InetSocketAddress, bound: Option[Promise[Done]] = None) extends Actor with DiagnosticActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, address, options = KeepAlive(true) :: Nil, pullMode = true)

  override def receive: Receive = {
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
      val peerConnection = context.actorOf(PeerConnection.props(
        keyPair = keyPair,
        conf = peerConnectionConf,
        switchboard = switchboard,
        router = router
      ))
      peerConnection ! PeerConnection.PendingAuth(connection, remoteNodeId_opt = None, address = remote, origin_opt = None)
      listener ! ResumeAccepting(batchSize = 1)
  }

  override def mdc(currentMessage: Any): MDC = Logs.mdc(Some(LogCategory.CONNECTION))
}

object Server {

  def props(keyPair: KeyPair, peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef, address: InetSocketAddress, bound: Option[Promise[Done]] = None): Props = Props(new Server(keyPair, peerConnectionConf, switchboard, router: ActorRef, address, bound))

}

