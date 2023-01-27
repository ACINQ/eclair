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

import akka.actor.ActorRef
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.{NodeAddress, UnknownMessage}

import scala.concurrent.duration._

sealed trait PeerEvent

case class ConnectionInfo(address: NodeAddress, peerConnection: ActorRef, localInit: protocol.Init, remoteInit: protocol.Init)

case class PeerConnected(peer: ActorRef, nodeId: PublicKey, connectionInfo: ConnectionInfo) extends PeerEvent

case class PeerDisconnected(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

case class LastChannelClosed(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

case class PongReceived(nodeId: PublicKey, latency: FiniteDuration) extends PeerEvent

case class UnknownMessageReceived(peer: ActorRef, nodeId: PublicKey, message: UnknownMessage, connectionInfo: ConnectionInfo) extends PeerEvent
