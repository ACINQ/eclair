/*
 * Copyright 2020 ACINQ SAS
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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.tor.Socks5ProxyParams

class ClientSpawner(keyPair: KeyPair, socks5ProxyParams_opt: Option[Socks5ProxyParams], peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ClientSpawner.ConnectionRequest])

  override def receive: Receive = {
    case req: ClientSpawner.ConnectionRequest =>
      log.info("initiating new connection to nodeId={} origin={}", req.remoteNodeId, sender)
      context.actorOf(Client.props(keyPair, socks5ProxyParams_opt, peerConnectionConf, switchboard, router, req.address, req.remoteNodeId, origin_opt = Some(req.origin)))
  }
}

object ClientSpawner {

  def props(keyPair: KeyPair, socks5ProxyParams_opt: Option[Socks5ProxyParams], peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef): Props = Props(new ClientSpawner(keyPair, socks5ProxyParams_opt, peerConnectionConf, switchboard, router))

  case class ConnectionRequest(address: InetSocketAddress,
                               remoteNodeId: PublicKey,
                               origin: ActorRef)

}
