/*
 * Copyright 2022 ACINQ SAS
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

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.{BlockHeight, Logs, channel}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * This actor waits for a given peer to be online and ready to process payments.
 * It automatically stops after the timeout provided.
 */
object PeerReadyNotifier {

  // @formatter:off
  sealed trait Command
  case class NotifyWhenPeerReady(replyTo: ActorRef[Result]) extends Command
  private case object PeerNotConnected extends Command
  private case class SomePeerConnected(nodeId: PublicKey) extends Command
  private case class SomePeerDisconnected(nodeId: PublicKey) extends Command
  private case class PeerChannels(channels: Set[akka.actor.ActorRef]) extends Command
  private case class NewBlockNotTimedOut(currentBlockHeight: BlockHeight) extends Command
  private case object CheckChannelsReady extends Command
  private case class ChannelStates(states: Seq[channel.ChannelState]) extends Command
  private case object Timeout extends Command

  sealed trait Result
  case class PeerReady(remoteNodeId: PublicKey, channelsCount: Int) extends Result
  case class PeerUnavailable(remoteNodeId: PublicKey) extends Result

  private case object ChannelsReadyTimerKey
  // @formatter:on

  def apply(remoteNodeId: PublicKey, switchboard: ActorRef[Switchboard.GetPeerInfo], timeout_opt: Option[Either[FiniteDuration, BlockHeight]]): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
          Behaviors.receiveMessagePartial {
            case NotifyWhenPeerReady(replyTo) =>
              timeout_opt.foreach {
                case Left(d) => timers.startSingleTimer(Timeout, d)
                case Right(h) => context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight] {
                  case cbc if h <= cbc.blockHeight => Timeout
                  case cbc => NewBlockNotTimedOut(cbc.blockHeight)
                })
              }
              // In case the peer is not currently connected, we will wait for them to connect instead of regularly
              // polling the switchboard. This makes more sense for long timeouts such as the ones used for async payments.
              context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerConnected](e => SomePeerConnected(e.nodeId)))
              context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerDisconnected](e => SomePeerDisconnected(e.nodeId)))
              waitForPeerConnected(replyTo, remoteNodeId, switchboard, context, timers)
          }
        }
      }
    }
  }

  private def waitForPeerConnected(replyTo: ActorRef[Result], remoteNodeId: PublicKey, switchboard: ActorRef[Switchboard.GetPeerInfo], context: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] = {
    val peerInfoAdapter = context.messageAdapter[Peer.PeerInfoResponse] {
      // We receive this when we don't have any channel to the given peer and are not currently connected to them.
      // In that case we still want to wait for a connection, because we may want to open a channel to them.
      case _: Peer.PeerNotFound => PeerNotConnected
      case info: Peer.PeerInfo if info.state != Peer.CONNECTED => PeerNotConnected
      case info: Peer.PeerInfo => PeerChannels(info.channels)
    }
    // We check whether the peer is already connected.
    switchboard ! Switchboard.GetPeerInfo(peerInfoAdapter, remoteNodeId)
    Behaviors.receiveMessagePartial {
      case PeerNotConnected =>
        context.log.debug("peer is not connected yet")
        Behaviors.same
      case SomePeerConnected(nodeId) =>
        if (nodeId == remoteNodeId) {
          switchboard ! Switchboard.GetPeerInfo(peerInfoAdapter, remoteNodeId)
        }
        Behaviors.same
      case SomePeerDisconnected(_) =>
        Behaviors.same
      case PeerChannels(channels) =>
        if (channels.isEmpty) {
          context.log.info("peer is ready with no channels")
          replyTo ! PeerReady(remoteNodeId, 0)
          Behaviors.stopped
        } else {
          context.log.debug("peer is connected with {} channels", channels.size)
          waitForChannelsReady(replyTo, remoteNodeId, channels, switchboard, context, timers)
        }
      case NewBlockNotTimedOut(currentBlockHeight) =>
        context.log.debug("waiting for peer to connect at block {}", currentBlockHeight)
        Behaviors.same
      case Timeout =>
        context.log.info("timed out waiting for peer to be ready")
        replyTo ! PeerUnavailable(remoteNodeId)
        Behaviors.stopped
    }
  }

  private def waitForChannelsReady(replyTo: ActorRef[Result], remoteNodeId: PublicKey, channels: Set[akka.actor.ActorRef], switchboard: ActorRef[Switchboard.GetPeerInfo], context: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] = {
    var channelCollector_opt = Option.empty[ActorRef[ChannelStatesCollector.Command]]
    timers.startTimerWithFixedDelay(ChannelsReadyTimerKey, CheckChannelsReady, initialDelay = 50 millis, delay = 1 second)
    Behaviors.receiveMessagePartial {
      case CheckChannelsReady =>
        channelCollector_opt.foreach(ref => context.stop(ref))
        channelCollector_opt = Some(context.spawnAnonymous(ChannelStatesCollector(context.self, channels)))
        Behaviors.same
      case ChannelStates(states) =>
        if (states.forall(isChannelReady)) {
          replyTo ! PeerReady(remoteNodeId, channels.size)
          Behaviors.stopped
        } else {
          context.log.debug("peer has {} channels that are not ready", states.count(s => !isChannelReady(s)))
          Behaviors.same
        }
      case NewBlockNotTimedOut(currentBlockHeight) =>
        context.log.debug("waiting for channels to be ready at block {}", currentBlockHeight)
        Behaviors.same
      case SomePeerConnected(_) =>
        Behaviors.same
      case SomePeerDisconnected(nodeId) =>
        if (nodeId == remoteNodeId) {
          context.log.debug("peer disconnected, waiting for them to reconnect")
          timers.cancel(ChannelsReadyTimerKey)
          waitForPeerConnected(replyTo, remoteNodeId, switchboard, context, timers)
        } else {
          Behaviors.same
        }
      case Timeout =>
        context.log.info("timed out waiting for channels to be ready")
        replyTo ! PeerUnavailable(remoteNodeId)
        Behaviors.stopped
    }
  }

  // We use an exhaustive pattern matching here to ensure we explicitly handle future new channel states.
  // We only want to test that channels are not in an uninitialized state, we don't need them to be available to relay
  // payments (channels closing or waiting to confirm are "ready" for our purposes).
  private def isChannelReady(state: channel.ChannelState): Boolean = state match {
    case channel.WAIT_FOR_INIT_INTERNAL => false
    case channel.WAIT_FOR_INIT_SINGLE_FUNDED_CHANNEL => false
    case channel.WAIT_FOR_INIT_DUAL_FUNDED_CHANNEL => false
    case channel.OFFLINE => false
    case channel.SYNCING => false
    case channel.WAIT_FOR_OPEN_CHANNEL => true
    case channel.WAIT_FOR_ACCEPT_CHANNEL => true
    case channel.WAIT_FOR_FUNDING_INTERNAL => true
    case channel.WAIT_FOR_FUNDING_CREATED => true
    case channel.WAIT_FOR_FUNDING_SIGNED => true
    case channel.WAIT_FOR_FUNDING_CONFIRMED => true
    case channel.WAIT_FOR_CHANNEL_READY => true
    case channel.WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL => true
    case channel.WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL => true
    case channel.WAIT_FOR_DUAL_FUNDING_CREATED => true
    case channel.WAIT_FOR_DUAL_FUNDING_CONFIRMED => true
    case channel.WAIT_FOR_DUAL_FUNDING_READY => true
    case channel.NORMAL => true
    case channel.SHUTDOWN => true
    case channel.NEGOTIATING => true
    case channel.CLOSING => true
    case channel.CLOSED => true
    case channel.WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => true
    case channel.ERR_INFORMATION_LEAK => true
  }

  private object ChannelStatesCollector {

    // @formatter:off
    sealed trait Command
    private final case class WrappedChannelState(wrapped: channel.RES_GET_CHANNEL_STATE) extends Command
    // @formatter:on

    def apply(replyTo: ActorRef[ChannelStates], channels: Set[akka.actor.ActorRef]): Behavior[Command] = {
      Behaviors.setup { context =>
        val channelStateAdapter = context.messageAdapter[channel.RES_GET_CHANNEL_STATE](WrappedChannelState)
        channels.foreach(c => c ! channel.CMD_GET_CHANNEL_STATE(channelStateAdapter.toClassic))
        collect(replyTo, Nil, channels.size)
      }
    }

    private def collect(replyTo: ActorRef[ChannelStates], received: Seq[channel.ChannelState], remaining: Int): Behavior[Command] = {
      Behaviors.receiveMessage {
        case WrappedChannelState(wrapped) => remaining match {
          case 1 =>
            replyTo ! ChannelStates(received :+ wrapped.state)
            Behaviors.stopped
          case _ =>
            collect(replyTo, received :+ wrapped.state, remaining - 1)
        }
      }
    }

  }

}
