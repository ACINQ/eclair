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
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.protocol.OnionMessage

object MessageRelay {
  // @formatter:off
  sealed trait Command
  case class RelayMessage(switchboard: ActorRef, nextNodeId: PublicKey, msg: OnionMessage, replyTo: typed.ActorRef[Status]) extends Command
  case class WrappedConnectionResult(result: PeerConnection.ConnectionResult) extends Command

  sealed trait Status
  case object Success extends Status
  case class Failure(failure: PeerConnection.ConnectionResult.Failure) extends Status
  // @formatter:on

  def apply(): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, RelayMessage(switchboard, nextNodeId, msg, replyTo)) =>
        switchboard ! Peer.Connect(nextNodeId, None, context.messageAdapter(WrappedConnectionResult))
        waitForConnection(msg, replyTo)
    }
  }

  def waitForConnection(msg: OnionMessage, replyTo: typed.ActorRef[Status]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedConnectionResult(r: PeerConnection.ConnectionResult.HasConnection) =>
        r.peerConnection ! msg
        replyTo ! Success
        Behaviors.stopped
      case WrappedConnectionResult(f: PeerConnection.ConnectionResult.Failure) =>
        replyTo ! Failure(f)
        Behaviors.stopped
    }
  }
}
