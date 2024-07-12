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
import fr.acinq.eclair.io.Monitoring.{Metrics, Tags}
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
  case class UnknownChannel(messageId: ByteVector32, channelId: ShortChannelId) extends Failure {
    override def toString: String = s"Unknown channel: $channelId"
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
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case RelayMessage(messageId, prevNodeId, nextNode, msg, policy, replyTo_opt) =>
          val relay = new MessageRelay(nodeParams, messageId, prevNodeId, policy, switchboard, register, router, replyTo_opt, context)
          relay.queryNextNodeId(msg, nextNode)
      }
    }
  }
}

private class MessageRelay(nodeParams: NodeParams,
                           messageId: ByteVector32,
                           prevNodeId: PublicKey,
                           policy: MessageRelay.RelayPolicy,
                           switchboard: ActorRef,
                           register: ActorRef,
                           router: typed.ActorRef[Router.GetNodeId],
                           replyTo_opt: Option[typed.ActorRef[MessageRelay.Status]],
                           context: ActorContext[MessageRelay.Command]) {

  import MessageRelay._

  def queryNextNodeId(msg: OnionMessage, nextNode: Either[ShortChannelId, EncodedNodeId]): Behavior[Command] = {
    nextNode match {
      case Left(outgoingChannelId) if outgoingChannelId == ShortChannelId.toSelf =>
        withNextNodeId(msg, nodeParams.nodeId)
      case Left(outgoingChannelId) =>
        register ! Register.GetNextNodeId(context.messageAdapter(WrappedOptionalNodeId), outgoingChannelId)
        waitForNextNodeId(msg, outgoingChannelId)
      case Right(EncodedNodeId.ShortChannelIdDir(isNode1, scid)) =>
        router ! Router.GetNodeId(context.messageAdapter(WrappedOptionalNodeId), scid, isNode1)
        waitForNextNodeId(msg, scid)
      case Right(encodedNodeId: EncodedNodeId.WithPublicKey) =>
        withNextNodeId(msg, encodedNodeId.publicKey)
    }
  }

  private def waitForNextNodeId(msg: OnionMessage, channelId: ShortChannelId): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedOptionalNodeId(None) =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.UnknownNextNodeId).increment()
        context.log.info("could not find node associated with scid={} for messageId={}", channelId, messageId)
        replyTo_opt.foreach(_ ! UnknownChannel(messageId, channelId))
        Behaviors.stopped
      case WrappedOptionalNodeId(Some(nextNodeId)) =>
        withNextNodeId(msg, nextNodeId)
    }
  }

  private def withNextNodeId(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    if (nextNodeId == nodeParams.nodeId) {
      OnionMessages.process(nodeParams.privateKey, msg) match {
        case OnionMessages.DropMessage(reason) =>
          Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, reason.getClass.getSimpleName).increment()
          replyTo_opt.foreach(_ ! DroppedMessage(messageId, reason))
          Behaviors.stopped
        case OnionMessages.SendMessage(nextNode, nextMessage) =>
          // We need to repeat the process until we identify the (real) next node, or find out that we're the recipient.
          queryNextNodeId(nextMessage, nextNode)
        case received: OnionMessages.ReceiveMessage =>
          context.system.eventStream ! EventStream.Publish(received)
          replyTo_opt.foreach(_ ! Sent(messageId))
          Behaviors.stopped
      }
    } else {
      policy match {
        case RelayChannelsOnly =>
          switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), prevNodeId)
          waitForPreviousPeerForPolicyCheck(msg, nextNodeId)
        case RelayAll =>
          context.log.info("connecting to {} to relay messageId={}", nextNodeId, messageId)
          switchboard ! Peer.Connect(nextNodeId, None, context.messageAdapter(WrappedConnectionResult).toClassic, isPersistent = false)
          waitForConnection(msg, nextNodeId)
      }
    }
  }

  private def waitForPreviousPeerForPolicyCheck(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPeerInfo(PeerInfo(_, _, _, _, channels)) if channels.nonEmpty =>
        switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), nextNodeId)
        waitForNextPeerForPolicyCheck(msg, nextNodeId)
      case _ =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.NoChannelWithPreviousPeer).increment()
        context.log.info("dropping onion message from {} with messageId={}: relaying without channels is disabled by policy", prevNodeId, messageId)
        replyTo_opt.foreach(_ ! AgainstPolicy(messageId, RelayChannelsOnly))
        Behaviors.stopped
    }
  }

  private def waitForNextPeerForPolicyCheck(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPeerInfo(PeerInfo(peer, _, _, _, channels)) if channels.nonEmpty =>
        peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
        Behaviors.stopped
      case _ =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.NoChannelWithNextPeer).increment()
        context.log.info("dropping onion message from {} to {} with messageId={}: relaying without channels is disabled by policy", prevNodeId, nextNodeId, messageId)
        replyTo_opt.foreach(_ ! AgainstPolicy(messageId, RelayChannelsOnly))
        Behaviors.stopped
    }
  }

  private def waitForConnection(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedConnectionResult(r: PeerConnection.ConnectionResult.HasConnection) =>
        context.log.info("connected to {}: relaying messageId={}", nextNodeId, messageId)
        r.peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
        Behaviors.stopped
      case WrappedConnectionResult(f: PeerConnection.ConnectionResult.Failure) =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.ConnectionFailure).increment()
        context.log.info("could not connect to {} to relay messageId={}: {}", nextNodeId, messageId, f.toString)
        replyTo_opt.foreach(_ ! ConnectionFailure(messageId, f))
        Behaviors.stopped
    }
  }
}
