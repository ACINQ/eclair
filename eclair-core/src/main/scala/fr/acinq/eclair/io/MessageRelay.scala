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
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.Peer.{PeerInfo, PeerInfoResponse}
import fr.acinq.eclair.io.Switchboard.GetPeerInfo
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.DropReason
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OnionMessage
import fr.acinq.eclair.{EncodedNodeId, NodeParams, ShortChannelId}

object MessageRelay {
  // @formatter:off
  sealed trait Command
  case class RelayMessage(messageId: ByteVector32,
                          prevNodeId: PublicKey,
                          nextNode: Either[ShortChannelId, EncodedNodeId],
                          msg: OnionMessage,
                          policy: RelayPolicy,
                          replyTo_opt: Option[typed.ActorRef[Status]]) extends Command
  case class WrappedPeerInfo(peerInfo: PeerInfoResponse) extends Command
  case class WrappedConnectionResult(result: PeerConnection.ConnectionResult) extends Command
  case class WrappedOptionalNodeId(nodeId_opt: Option[PublicKey]) extends Command

  sealed trait Status {
    val messageId: ByteVector32
  }
  case class Sent(messageId: ByteVector32) extends Status
  sealed trait Failure extends Status
  case class AgainstPolicy(messageId: ByteVector32, policy: RelayPolicy) extends Failure {
    override def toString: String = s"Relay prevented by policy $policy"
  }
  case class ConnectionFailure(messageId: ByteVector32, failure: PeerConnection.ConnectionResult.Failure) extends Failure {
    override def toString: String = s"Can't connect to peer: ${failure.toString}"
  }
  case class Disconnected(messageId: ByteVector32) extends Failure {
    override def toString: String = "Peer is not connected"
  }
  case class UnknownOutgoingChannel(messageId: ByteVector32, outgoingChannelId: ShortChannelId) extends Failure {
    override def toString: String = s"Unknown outgoing channel: $outgoingChannelId"
  }
  case class DroppedMessage(messageId: ByteVector32, reason: DropReason) extends Failure {
    override def toString: String = s"Message dropped: $reason"
  }

  sealed trait RelayPolicy
  case object RelayChannelsOnly extends RelayPolicy
  case object RelayAll extends RelayPolicy
  // @formatter:on

  def apply(nodeParams: NodeParams,
            switchboard: ActorRef,
            register: ActorRef,
            router: typed.ActorRef[Router.GetNodeId]): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, RelayMessage(messageId, prevNodeId, nextNode, msg, policy, replyTo_opt)) =>
        val relay = new Relay(context, nodeParams, messageId, switchboard, register, router, msg, policy, replyTo_opt)
        nextNode match {
          case Left(outgoingChannelId) =>
            register ! Register.GetNextNodeId(context.messageAdapter(WrappedOptionalNodeId), outgoingChannelId)
            relay.waitForNextNodeId(prevNodeId, outgoingChannelId)
          case Right(EncodedNodeId.ShortChannelIdDir(isNode1, scid)) =>
            router ! Router.GetNodeId(context.messageAdapter(WrappedOptionalNodeId), scid, isNode1)
            relay.waitForNextNodeId(prevNodeId, scid)
          case Right(EncodedNodeId.Plain(nextNodeId)) =>
            relay.withNextNodeId(prevNodeId, nextNodeId)
        }
    }
  }

  private class Relay(context: ActorContext[Command],
                      nodeParams: NodeParams,
                      messageId: ByteVector32,
                      switchboard: ActorRef,
                      register: ActorRef,
                      router: typed.ActorRef[Router.GetNodeId],
                      msg: OnionMessage,
                      policy: RelayPolicy,
                      replyTo_opt: Option[typed.ActorRef[Status]]) {

    def waitForNextNodeId(prevNodeId: PublicKey,
                          outgoingChannelId: ShortChannelId): Behavior[Command] =
      Behaviors.receiveMessagePartial {
        case WrappedOptionalNodeId(None) =>
          replyTo_opt.foreach(_ ! UnknownOutgoingChannel(messageId, outgoingChannelId))
          Behaviors.stopped
        case WrappedOptionalNodeId(Some(nextNodeId)) =>
          withNextNodeId(prevNodeId, nextNodeId)
      }

    def withNextNodeId(prevNodeId: PublicKey,
                       nextNodeId: PublicKey): Behavior[Command] = {
      if (nextNodeId == nodeParams.nodeId) {
        OnionMessages.process(nodeParams.privateKey, msg) match {
          case OnionMessages.DropMessage(reason) =>
            replyTo_opt.foreach(_ ! DroppedMessage(messageId, reason))
            Behaviors.stopped
          case OnionMessages.SendMessage(nextNode, nextMessage) =>
            context.self ! RelayMessage(messageId, prevNodeId, nextNode, nextMessage, policy, replyTo_opt)
            apply(nodeParams: NodeParams, switchboard, register, router)
          case received: OnionMessages.ReceiveMessage =>
            context.system.eventStream ! EventStream.Publish(received)
            replyTo_opt.foreach(_ ! Sent(messageId))
            Behaviors.stopped
        }
      } else {
        policy match {
          case RelayChannelsOnly =>
            switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), prevNodeId)
            waitForPreviousPeerForPoliciyCheck(nextNodeId)
          case RelayAll =>
            switchboard ! Peer.Connect(nextNodeId, None, context.messageAdapter(WrappedConnectionResult).toClassic, isPersistent = false)
            waitForConnection()
        }
      }
    }

    def waitForPreviousPeerForPoliciyCheck(nextNodeId: PublicKey): Behavior[Command] = {
      Behaviors.receivePartial {
        case (context, WrappedPeerInfo(PeerInfo(_, _, _, _, channels))) if channels.nonEmpty =>
          switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), nextNodeId)
          waitForNextPeerForPoliciyCheck()
        case _ =>
          replyTo_opt.foreach(_ ! AgainstPolicy(messageId, RelayChannelsOnly))
          Behaviors.stopped
      }
    }

    def waitForNextPeerForPoliciyCheck(): Behavior[Command] = {
      Behaviors.receiveMessagePartial {
        case WrappedPeerInfo(PeerInfo(peer, _, _, _, channels)) if channels.nonEmpty =>
          peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
          Behaviors.stopped
        case _ =>
          replyTo_opt.foreach(_ ! AgainstPolicy(messageId, RelayChannelsOnly))
          Behaviors.stopped
      }
    }

    def waitForConnection(): Behavior[Command] = {
      Behaviors.receiveMessagePartial {
        case WrappedConnectionResult(r: PeerConnection.ConnectionResult.HasConnection) =>
          r.peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
          Behaviors.stopped
        case WrappedConnectionResult(f: PeerConnection.ConnectionResult.Failure) =>
          replyTo_opt.foreach(_ ! ConnectionFailure(messageId, f))
          Behaviors.stopped
      }
    }
  }
}
