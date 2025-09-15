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
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.io.Peer.{PeerInfo, PeerInfoResponse}
import fr.acinq.eclair.io.Switchboard.GetPeerInfo
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.DropReason
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OnionMessage
import fr.acinq.eclair.{EncodedNodeId, Logs, NodeParams, ShortChannelId}

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
  private case class WrappedConnectionResult(result: PeerConnection.ConnectionResult) extends Command
  private case class WrappedOptionalNodeId(nodeId_opt: Option[PublicKey]) extends Command
  private case class WrappedPeerReadyResult(result: PeerReadyNotifier.Result) extends Command

  sealed trait Status { val messageId: ByteVector32 }
  case class Sent(messageId: ByteVector32) extends Status
  sealed trait Failure extends Status
  case class AgainstPolicy(messageId: ByteVector32, policy: RelayPolicy) extends Failure { override def toString: String = s"Relay prevented by policy $policy" }
  case class ConnectionFailure(messageId: ByteVector32, failure: PeerConnection.ConnectionResult.Failure) extends Failure { override def toString: String = s"Can't connect to peer: ${failure.toString}" }
  case class Disconnected(messageId: ByteVector32) extends Failure { override def toString: String = "Peer is not connected" }
  case class UnknownChannel(messageId: ByteVector32, channelId: ShortChannelId) extends Failure { override def toString: String = s"Unknown channel: $channelId" }
  case class DroppedMessage(messageId: ByteVector32, reason: DropReason) extends Failure { override def toString: String = s"Message dropped: $reason" }

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
          Behaviors.withMdc(Logs.mdc(category_opt = Some(LogCategory.MESSAGE), messageId_opt = Some(messageId), remoteNodeId_opt = Some(prevNodeId))) {
            val relay = new MessageRelay(nodeParams, messageId, prevNodeId, policy, switchboard, register, router, replyTo_opt, context)
            relay.queryNextNodeId(msg, nextNode)
          }
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

  private val log = context.log

  def queryNextNodeId(msg: OnionMessage, nextNode: Either[ShortChannelId, EncodedNodeId]): Behavior[Command] = {
    nextNode match {
      case Left(outgoingChannelId) if outgoingChannelId == ShortChannelId.toSelf =>
        withNextNodeId(msg, EncodedNodeId.WithPublicKey.Plain(nodeParams.nodeId))
      case Left(outgoingChannelId) =>
        register ! Register.GetNextNodeId(context.messageAdapter(WrappedOptionalNodeId), outgoingChannelId)
        waitForNextNodeId(msg, outgoingChannelId)
      case Right(EncodedNodeId.ShortChannelIdDir(isNode1, scid)) =>
        router ! Router.GetNodeId(context.messageAdapter(WrappedOptionalNodeId), scid, isNode1)
        waitForNextNodeId(msg, scid)
      case Right(encodedNodeId: EncodedNodeId.WithPublicKey) =>
        withNextNodeId(msg, encodedNodeId)
    }
  }

  private def waitForNextNodeId(msg: OnionMessage, channelId: ShortChannelId): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedOptionalNodeId(None) =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.UnknownNextNodeId).increment()
        log.info("could not find outgoing node for scid={}", channelId)
        replyTo_opt.foreach(_ ! UnknownChannel(messageId, channelId))
        Behaviors.stopped
      case WrappedOptionalNodeId(Some(nextNodeId)) =>
        log.info("found outgoing node {} for channel {}", nextNodeId, channelId)
        withNextNodeId(msg, EncodedNodeId.WithPublicKey.Plain(nextNodeId))
    }
  }

  private def withNextNodeId(msg: OnionMessage, nextNodeId: EncodedNodeId.WithPublicKey): Behavior[Command] = {
    nextNodeId match {
      case EncodedNodeId.WithPublicKey.Plain(nodeId) if nodeId == nodeParams.nodeId =>
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
      case EncodedNodeId.WithPublicKey.Plain(nodeId) =>
        println(policy)
        policy match {
          case RelayChannelsOnly =>
            switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), prevNodeId)
            waitForPreviousPeerForPolicyCheck(msg, nodeId)
          case RelayAll =>
            switchboard ! Peer.Connect(nodeId, None, context.messageAdapter(WrappedConnectionResult).toClassic, isPersistent = false)
            waitForConnection(msg, nodeId)
        }
      case EncodedNodeId.WithPublicKey.Wallet(nodeId) =>
        val notifier = context.spawnAnonymous(PeerReadyNotifier(nodeId, timeout_opt = Some(Left(nodeParams.peerWakeUpConfig.timeout))))
        notifier ! PeerReadyNotifier.NotifyWhenPeerReady(context.messageAdapter(WrappedPeerReadyResult))
        waitForWalletNodeUp(msg, nodeId)
    }
  }

  private def waitForPreviousPeerForPolicyCheck(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPeerInfo(info: PeerInfo) if info.channels.nonEmpty =>
        switchboard ! GetPeerInfo(context.messageAdapter(WrappedPeerInfo), nextNodeId)
        waitForNextPeerForPolicyCheck(msg, nextNodeId)
      case _ =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.NoChannelWithPreviousPeer).increment()
        log.info("dropping onion message to {}: relaying without channels with the previous node is disabled by policy", nextNodeId)
        replyTo_opt.foreach(_ ! AgainstPolicy(messageId, RelayChannelsOnly))
        Behaviors.stopped
    }
  }

  private def waitForNextPeerForPolicyCheck(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPeerInfo(info: PeerInfo) if info.channels.nonEmpty =>
        info.peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
        Behaviors.stopped
      case _ =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.NoChannelWithNextPeer).increment()
        log.info("dropping onion message to {}: relaying without channels with the next node is disabled by policy", nextNodeId)
        replyTo_opt.foreach(_ ! AgainstPolicy(messageId, RelayChannelsOnly))
        Behaviors.stopped
    }
  }

  private def waitForConnection(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedConnectionResult(r: PeerConnection.ConnectionResult.HasConnection) =>
        log.info("connected to {}: relaying onion message", nextNodeId)
        r.peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
        Behaviors.stopped
      case WrappedConnectionResult(f: PeerConnection.ConnectionResult.Failure) =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.ConnectionFailure).increment()
        log.info("could not connect to {}: {}", nextNodeId, f.toString)
        replyTo_opt.foreach(_ ! ConnectionFailure(messageId, f))
        Behaviors.stopped
    }
  }

  private def waitForWalletNodeUp(msg: OnionMessage, nextNodeId: PublicKey): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedPeerReadyResult(r: PeerReadyNotifier.PeerReady) =>
        log.info("successfully woke up {}: relaying onion message", nextNodeId)
        r.peer ! Peer.RelayOnionMessage(messageId, msg, replyTo_opt)
        Behaviors.stopped
      case WrappedPeerReadyResult(_: PeerReadyNotifier.PeerUnavailable) =>
        Metrics.OnionMessagesNotRelayed.withTag(Tags.Reason, Tags.Reasons.ConnectionFailure).increment()
        log.info("could not wake up {}: onion message cannot be relayed", nextNodeId)
        replyTo_opt.foreach(_ ! Disconnected(messageId))
        Behaviors.stopped
    }
  }
}
