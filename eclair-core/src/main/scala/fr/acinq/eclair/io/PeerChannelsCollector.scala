/*
 * Copyright 2023 ACINQ SAS
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

import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_INFO, ChannelData, ChannelState, RES_GET_CHANNEL_INFO}

import scala.concurrent.duration.FiniteDuration

object PeerChannelsCollector {

  // @formatter:off
  sealed trait Command
  case class GetChannels(replyTo: ActorRef[Peer.PeerChannels], channels: Set[akka.actor.ActorRef]) extends Command
  private case class WrappedChannelInfo(state: ChannelState, data: ChannelData) extends Command
  private object Timeout extends Command
  // @formatter:on

  /**
   * @param timeout channel actors should respond immediately, so this actor should be very short-lived. However, if one
   *                of the channel actors dies (because the channel is closed), we won't get a response: we will wait
   *                until the given timeout, and then return the subset of channels we were able to collect.
   */
  def apply(remoteNodeId: PublicKey, timeout: FiniteDuration): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
          Behaviors.receiveMessagePartial {
            case GetChannels(replyTo, channels) =>
              timers.startSingleTimer(Timeout, timeout)
              val adapter = context.messageAdapter[RES_GET_CHANNEL_INFO](r => WrappedChannelInfo(r.state, r.data))
              channels.foreach(c => c ! CMD_GET_CHANNEL_INFO(adapter.toClassic))
              new PeerChannelsCollector(replyTo, remoteNodeId, context).collect(Nil, channels.size)
          }
        }
      }
    }
  }

}

private class PeerChannelsCollector(replyTo: ActorRef[Peer.PeerChannels], remoteNodeId: PublicKey, context: ActorContext[PeerChannelsCollector.Command]) {

  import PeerChannelsCollector._

  private val log = context.log

  def collect(received: Seq[Peer.ChannelInfo], remaining: Int): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedChannelInfo(state, data) if remaining == 1 =>
        replyTo ! Peer.PeerChannels(remoteNodeId, received :+ Peer.ChannelInfo(state, data))
        Behaviors.stopped
      case WrappedChannelInfo(state, data) =>
        collect(received :+ Peer.ChannelInfo(state, data), remaining - 1)
      case Timeout =>
        log.warn("timed out fetching peer channel information (remaining={})", remaining)
        replyTo ! Peer.PeerChannels(remoteNodeId, received)
        Behaviors.stopped
    }
  }

}
