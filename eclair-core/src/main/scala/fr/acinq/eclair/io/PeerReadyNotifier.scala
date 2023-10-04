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
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, TypedActorRefOps}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.{BlockHeight, Features, InitFeature, Logs, channel}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * This actor tracks the set of pending [[PeerReadyNotifier]].
 * It can be used to ensure that notifications are only sent once, even if there are multiple parallel operations
 * waiting for that peer to come online.
 */
object PeerReadyManager {

  val PeerReadyManagerServiceKey: ServiceKey[Register] = ServiceKey[Register]("peer-ready-manager")

  // @formatter:off
  sealed trait Command
  case class Register(replyTo: ActorRef[Registered], remoteNodeId: PublicKey) extends Command
  case class List(replyTo: ActorRef[Set[PublicKey]]) extends Command
  private case class Completed(remoteNodeId: PublicKey, actor: ActorRef[Registered]) extends Command
  // @formatter:on

  /**
   * @param otherAttempts number of already pending [[PeerReadyNotifier]] instances for that peer.
   */
  case class Registered(remoteNodeId: PublicKey, otherAttempts: Int)

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      context.system.receptionist ! Receptionist.Register(PeerReadyManagerServiceKey, context.self)
      watch(Map.empty, context)
    }
  }

  private def watch(pending: Map[PublicKey, Set[ActorRef[Registered]]], context: ActorContext[Command]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case Register(replyTo, remoteNodeId) =>
        context.watchWith(replyTo, Completed(remoteNodeId, replyTo))
        pending.get(remoteNodeId) match {
          case Some(attempts) =>
            replyTo ! Registered(remoteNodeId, otherAttempts = attempts.size)
            val attempts1 = attempts + replyTo
            watch(pending + (remoteNodeId -> attempts1), context)
          case None =>
            replyTo ! Registered(remoteNodeId, otherAttempts = 0)
            watch(pending + (remoteNodeId -> Set(replyTo)), context)
        }
      case Completed(remoteNodeId, actor) =>
        pending.get(remoteNodeId) match {
          case Some(attempts) =>
            val attempts1 = attempts - actor
            if (attempts1.isEmpty) {
              watch(pending - remoteNodeId, context)
            } else {
              watch(pending + (remoteNodeId -> attempts1), context)
            }
          case None =>
            Behaviors.same
        }
      case List(replyTo) =>
        replyTo ! pending.keySet
        Behaviors.same
    }
  }

}

/**
 * This actor waits for a given peer to be online and ready to process payments.
 * It automatically stops after the timeout provided if the peer doesn't connect.
 * There may be multiple instances of this actor running in parallel for the same peer, which is fine because they
 * may use different timeouts.
 * Having separate actor instances for each caller guarantees that the caller will always receive a response.
 */
object PeerReadyNotifier {

  case class WakeUpConfig(enabled: Boolean, timeout: FiniteDuration)

  // @formatter:off
  sealed trait Command
  case class NotifyWhenPeerReady(replyTo: ActorRef[Result]) extends Command
  private final case class WrappedListing(wrapped: Receptionist.Listing) extends Command
  private final case class WrappedRegistered(registered: PeerReadyManager.Registered) extends Command
  private case object PeerNotConnected extends Command
  private case object PeerConnected extends Command
  private case object PeerDisconnected extends Command
  private case class WrappedPeerInfo(peer: ActorRef[Peer.GetPeerChannels], remoteFeatures: Features[InitFeature], channelCount: Int) extends Command
  private case class NewBlockNotTimedOut(currentBlockHeight: BlockHeight) extends Command
  private case object CheckChannelsReady extends Command
  private case class WrappedPeerChannels(wrapped: Peer.PeerChannels) extends Command
  private case object Timeout extends Command
  private case object ToBeIgnored extends Command

  sealed trait Result { def remoteNodeId: PublicKey }
  case class PeerReady(remoteNodeId: PublicKey, peer: akka.actor.ActorRef, remoteFeatures: Features[InitFeature], channelInfos: Seq[Peer.ChannelInfo]) extends Result { val channelsCount: Int = channelInfos.size }
  case class PeerUnavailable(remoteNodeId: PublicKey) extends Result

  private case object ChannelsReadyTimerKey
  // @formatter:on

  def apply(remoteNodeId: PublicKey, timeout_opt: Option[Either[FiniteDuration, BlockHeight]]): Behavior[Command] = {
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
              // The actor should never throw, but for extra safety we wrap it with a supervisor.
              Behaviors.supervise {
                start(replyTo, remoteNodeId, context, timers)
              }.onFailure(SupervisorStrategy.stop)
          }
        }
      }
    }
  }

  private def start(replyTo: ActorRef[Result], remoteNodeId: PublicKey, context: ActorContext[Command], timers: TimerScheduler[Command]): Behavior[Command] = {
    // We start by registering ourself to see if other instances are running.
    context.system.receptionist ! Receptionist.Find(PeerReadyManager.PeerReadyManagerServiceKey, context.messageAdapter[Receptionist.Listing](WrappedListing))
    Behaviors.receiveMessagePartial {
      case WrappedListing(PeerReadyManager.PeerReadyManagerServiceKey.Listing(listings)) =>
        listings.headOption match {
          case Some(peerReadyManager) =>
            peerReadyManager ! PeerReadyManager.Register(context.messageAdapter[PeerReadyManager.Registered](WrappedRegistered), remoteNodeId)
            Behaviors.same
          case None =>
            context.log.error("no peer-ready-manager found")
            replyTo ! PeerUnavailable(remoteNodeId)
            Behaviors.stopped
        }
      case WrappedRegistered(registered) =>
        context.log.info("checking if peer is ready ({} other attempts)", registered.otherAttempts)
        val isFirstAttempt = registered.otherAttempts == 0
        // In case the peer is not currently connected, we will wait for them to connect instead of regularly
        // polling the switchboard. This makes more sense for long timeouts such as the ones used for async payments.
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerConnected](e => if (e.nodeId == remoteNodeId) PeerConnected else ToBeIgnored))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerDisconnected](e => if (e.nodeId == remoteNodeId) PeerDisconnected else ToBeIgnored))
        new PeerReadyNotifier(replyTo, remoteNodeId, isFirstAttempt, context, timers).findSwitchboard()
      case Timeout =>
        context.log.info("timed out finding peer-ready-manager actor")
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
    case channel.WAIT_FOR_DUAL_FUNDING_SIGNED => true
    case channel.WAIT_FOR_DUAL_FUNDING_CONFIRMED => true
    case channel.WAIT_FOR_DUAL_FUNDING_READY => true
    case channel.NORMAL => true
    case channel.SHUTDOWN => true
    case channel.NEGOTIATING => true
    case channel.NEGOTIATING_SIMPLE => true
    case channel.CLOSING => true
    case channel.CLOSED => true
    case channel.WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => true
    case channel.ERR_INFORMATION_LEAK => true
  }

}

private class PeerReadyNotifier(replyTo: ActorRef[PeerReadyNotifier.Result],
                                remoteNodeId: PublicKey,
                                isFirstAttempt: Boolean,
                                context: ActorContext[PeerReadyNotifier.Command],
                                timers: TimerScheduler[PeerReadyNotifier.Command]) {

  import PeerReadyNotifier._

  private val log = context.log

  private def findSwitchboard(): Behavior[Command] = {
    context.system.receptionist ! Receptionist.Find(Switchboard.SwitchboardServiceKey, context.messageAdapter[Receptionist.Listing](WrappedListing))
    Behaviors.receiveMessagePartial {
      case WrappedListing(Switchboard.SwitchboardServiceKey.Listing(listings)) =>
        listings.headOption match {
          case Some(switchboard) =>
            waitForPeerConnected(switchboard)
          case None =>
            log.error("no switchboard found")
            replyTo ! PeerUnavailable(remoteNodeId)
            Behaviors.stopped
        }
      case Timeout =>
        log.info("timed out finding switchboard actor")
        replyTo ! PeerUnavailable(remoteNodeId)
        Behaviors.stopped
      case ToBeIgnored =>
        Behaviors.same
    }
  }

  private def waitForPeerConnected(switchboard: ActorRef[Switchboard.GetPeerInfo]): Behavior[Command] = {
    val peerInfoAdapter = context.messageAdapter[Peer.PeerInfoResponse] {
      // We receive this when we don't have any channel to the given peer and are not currently connected to them.
      // In that case we still want to wait for a connection, because we may want to open a channel to them.
      case _: Peer.PeerNotFound => PeerNotConnected
      case info: Peer.PeerInfo if info.state != Peer.CONNECTED => PeerNotConnected
      case info: Peer.PeerInfo => WrappedPeerInfo(info.peer.toTyped, info.features.getOrElse(Features.empty), info.channels.size)
    }
    // We check whether the peer is already connected.
    switchboard ! Switchboard.GetPeerInfo(peerInfoAdapter, remoteNodeId)
    Behaviors.receiveMessagePartial {
      case PeerNotConnected =>
        log.debug("peer is not connected yet")
        Behaviors.same
      case PeerConnected =>
        switchboard ! Switchboard.GetPeerInfo(peerInfoAdapter, remoteNodeId)
        Behaviors.same
      case PeerDisconnected =>
        Behaviors.same
      case WrappedPeerInfo(peer, remoteFeatures, channelCount) =>
        if (channelCount == 0) {
          log.info("peer is ready with no channels")
          replyTo ! PeerReady(remoteNodeId, peer.toClassic, remoteFeatures, Seq.empty)
          Behaviors.stopped
        } else {
          log.debug("peer is connected with {} channels", channelCount)
          waitForChannelsReady(peer, switchboard, remoteFeatures)
        }
      case NewBlockNotTimedOut(currentBlockHeight) =>
        log.debug("waiting for peer to connect at block {}", currentBlockHeight)
        Behaviors.same
      case Timeout =>
        log.info("timed out waiting for peer to connect")
        replyTo ! PeerUnavailable(remoteNodeId)
        Behaviors.stopped
      case ToBeIgnored =>
        Behaviors.same
    }
  }

  private def waitForChannelsReady(peer: ActorRef[Peer.GetPeerChannels], switchboard: ActorRef[Switchboard.GetPeerInfo], remoteFeatures: Features[InitFeature]): Behavior[Command] = {
    timers.startTimerWithFixedDelay(ChannelsReadyTimerKey, CheckChannelsReady, initialDelay = 50 millis, delay = 1 second)
    Behaviors.receiveMessagePartial {
      case CheckChannelsReady =>
        log.debug("checking channel states")
        peer ! Peer.GetPeerChannels(context.messageAdapter[Peer.PeerChannels](WrappedPeerChannels))
        Behaviors.same
      case WrappedPeerChannels(peerChannels) =>
        if (peerChannels.channels.map(_.state).forall(isChannelReady)) {
          replyTo ! PeerReady(remoteNodeId, peer.toClassic, remoteFeatures, peerChannels.channels)
          Behaviors.stopped
        } else {
          log.debug("peer has {} channels that are not ready", peerChannels.channels.count(s => !isChannelReady(s.state)))
          Behaviors.same
        }
      case NewBlockNotTimedOut(currentBlockHeight) =>
        log.debug("waiting for channels to be ready at block {}", currentBlockHeight)
        Behaviors.same
      case PeerConnected =>
        Behaviors.same
      case PeerDisconnected =>
        log.debug("peer disconnected, waiting for them to reconnect")
        timers.cancel(ChannelsReadyTimerKey)
        waitForPeerConnected(switchboard)
      case Timeout =>
        log.info("timed out waiting for channels to be ready")
        replyTo ! PeerUnavailable(remoteNodeId)
        Behaviors.stopped
      case ToBeIgnored =>
        Behaviors.same
    }
  }

}