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

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetter, Props}
import akka.cluster.Cluster
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Put
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.SubscriptionsComplete
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.wire.protocol.NodeAddress

class ClientSpawner(keyPair: KeyPair, socks5ProxyParams_opt: Option[Socks5ProxyParams], peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ClientSpawner.ConnectionRequest])
  context.system.eventStream.publish(SubscriptionsComplete(this.getClass))

  if (context.system.hasExtension(Cluster)) {
    val roles = context.system.extension(Cluster).selfRoles
    if (roles.contains("frontend")) {
      val mediator = DistributedPubSub(context.system).mediator
      mediator ! Put(self)
    } else if (roles.contains("backend")) {
      // When the cluster is enabled, the backend will handle outgoing connections when there are no front available, by
      // registering to the dead letters.
      // Another option would have been to register the backend along with the front to the regular distributed pubsub,
      // but, even with affinity=false at sending, it would have resulted in outgoing connections being randomly assigned
      // to all listeners equally (front and back). What we really want is to always spawn connections on the frontend
      // when possible
      context.system.eventStream.subscribe(self, classOf[DeadLetter])
    }
  }

  override def receive: Receive = {
    case req: ClientSpawner.ConnectionRequest =>
      log.info("initiating new connection to nodeId={} origin={}", req.remoteNodeId, sender())
      context.actorOf(Client.props(keyPair, socks5ProxyParams_opt, peerConnectionConf, switchboard, router, req.address, req.remoteNodeId, origin_opt = Some(req.origin), req.isPersistent))
    case DeadLetter(req: ClientSpawner.ConnectionRequest, _, _) =>
      // we only subscribe to the deadletters event stream when in cluster mode
      // in that case we want to be warned when connections are spawned by the backend
      log.warning("handling outgoing connection request locally")
      self forward req
    case _: DeadLetter =>
    // we don't care about other dead letters
  }
}

object ClientSpawner {

  def props(keyPair: KeyPair, socks5ProxyParams_opt: Option[Socks5ProxyParams], peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef): Props = Props(new ClientSpawner(keyPair, socks5ProxyParams_opt, peerConnectionConf, switchboard, router))

  case class ConnectionRequest(remoteNodeId: PublicKey,
                               address: NodeAddress,
                               origin: ActorRef,
                               isPersistent: Boolean) extends RemoteTypes
}
