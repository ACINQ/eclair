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
import fr.acinq.eclair.io.Switchboard.GetPeer
import fr.acinq.eclair.wire.protocol.OnionMessage

object MessageRelay {
  // @formatter:off
  sealed trait Command
  case class RelayMessage(switchboard: ActorRef, nextNodeId: PublicKey, msg: OnionMessage, policy: RelayPolicy, replyTo: typed.ActorRef[Status]) extends Command
  case class WrappedPeerOption(peer: Option[ActorRef]) extends Command
  case class WrappedConnectionResult(result: PeerConnection.ConnectionResult) extends Command

  sealed trait Status
  case object Success extends Status
  case class AgainstPolicy(policy: RelayPolicy) extends Status
  case class ConnectionFailure(failure: PeerConnection.ConnectionResult.Failure) extends Status

  sealed trait RelayPolicy
  case object NoRelay extends RelayPolicy
  case object RelayChannelsOnly extends RelayPolicy
  case object RelayAll extends RelayPolicy
  // @formatter:on

  def apply(): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, RelayMessage(switchboard, nextNodeId, msg, policy, replyTo)) =>
        switchboard ! GetPeer(nextNodeId, context.messageAdapter(WrappedPeerOption))
        waitForPeer(switchboard, nextNodeId, msg, policy, replyTo)
    }
  }

  def waitForPeer(switchboard: ActorRef, nextNodeId: PublicKey, msg: OnionMessage, policy: RelayPolicy, replyTo: typed.ActorRef[Status]): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, WrappedPeerOption(None)) =>
        policy match {
          case NoRelay | RelayChannelsOnly =>
            replyTo ! AgainstPolicy(policy)
            Behaviors.stopped
          case RelayAll =>
            switchboard ! Peer.Connect(nextNodeId, None, context.messageAdapter(WrappedConnectionResult).toClassic)
            waitForConnection(msg, policy, replyTo)
        }
      case (_, WrappedPeerOption(Some(peer))) =>
        peer ! Peer.RelayOnionMessage(msg, policy, replyTo)
        Behaviors.stopped
    }
  }

  def waitForConnection(msg: OnionMessage, policy: RelayPolicy, replyTo: typed.ActorRef[Status]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedConnectionResult(r: PeerConnection.ConnectionResult.HasConnection) =>
        r.peer ! Peer.RelayOnionMessage(msg, policy, replyTo)
        Behaviors.stopped
      case WrappedConnectionResult(f: PeerConnection.ConnectionResult.Failure) =>
        replyTo ! ConnectionFailure(f)
        Behaviors.stopped
    }
  }
}
