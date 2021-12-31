/*
 * Copyright 2021 ACINQ SAS
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

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.io.Peer.{PeerInfo, PeerInfoResponse, PeerNotFound}
import fr.acinq.eclair.io.Switchboard.GetPeerInfo
import fr.acinq.eclair.wire.protocol.OnionMessage

object MessageRelay {
  // @formatter:off
  sealed trait Command
  case class RelayMessage(switchboard: ActorRef, prevNodeId: PublicKey, nextNodeId: PublicKey, msg: OnionMessage, policy: RelayPolicy, replyTo: typed.ActorRef[Status]) extends Command
  case class WrappedPeerInfo(peerInfo: PeerInfoResponse) extends Command
  case class WrappedConnectionResult(result: PeerConnection.ConnectionResult) extends Command

  sealed trait Status
  case object Sent extends Status
  sealed trait Failure extends Status
  case class AgainstPolicy(policy: RelayPolicy) extends Failure {
    override def toString: String = s"Relay prevented by policy $policy"
  }
  case class ConnectionFailure(failure: PeerConnection.ConnectionResult.Failure) extends Failure{
    override def toString: String = s"Can't connect to peer: ${failure.toString}"
  }
  case object Disconnected extends Failure{
    override def toString: String = "Peer is not connected"
  }

  sealed trait RelayPolicy
  case object NoRelay extends RelayPolicy
  case object RelayChannelsOnly extends RelayPolicy
  case object RelayAll extends RelayPolicy
  // @formatter:on

  def apply(): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, RelayMessage(switchboard, prevNodeId, nextNodeId, msg, policy, replyTo)) =>
        policy match {
          case NoRelay =>
            replyTo ! AgainstPolicy(policy)
            Behaviors.stopped
          case RelayChannelsOnly =>
            switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), prevNodeId)
            waitForPreviousPeer(switchboard, nextNodeId, msg, replyTo)
          case RelayAll =>
            switchboard ! Peer.Connect(nextNodeId, None, context.messageAdapter(WrappedConnectionResult).toClassic, isPersistent = false)
            waitForConnection(msg, replyTo)
        }
    }
  }

  def waitForPreviousPeer(switchboard: ActorRef, nextNodeId: PublicKey, msg: OnionMessage, replyTo: typed.ActorRef[Status]): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, WrappedPeerInfo(PeerInfo(_, _, _, _, channels))) if channels > 0 =>
        switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), nextNodeId)
        waitForNextPeer(msg, replyTo)
      case _ =>
        replyTo ! AgainstPolicy(RelayChannelsOnly)
        Behaviors.stopped
    }
  }

  def waitForNextPeer(msg: OnionMessage, replyTo: typed.ActorRef[Status]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPeerInfo(PeerInfo(peer, _, _, _, channels)) if channels > 0 =>
        peer ! Peer.RelayOnionMessage(msg, replyTo)
        Behaviors.stopped
      case _ =>
        replyTo ! AgainstPolicy(RelayChannelsOnly)
        Behaviors.stopped
    }
  }

  def waitForConnection(msg: OnionMessage, replyTo: typed.ActorRef[Status]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedConnectionResult(r: PeerConnection.ConnectionResult.HasConnection) =>
        r.peer ! Peer.RelayOnionMessage(msg, replyTo)
        Behaviors.stopped
      case WrappedConnectionResult(f: PeerConnection.ConnectionResult.Failure) =>
        replyTo ! ConnectionFailure(f)
        Behaviors.stopped
    }
  }
}
