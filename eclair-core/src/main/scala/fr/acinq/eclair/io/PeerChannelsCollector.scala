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
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_INFO, ChannelData, ChannelState, RES_GET_CHANNEL_INFO}
import fr.acinq.eclair.{Logs, randomBytes32}

/**
 * Collect the current states of a peer's channels.
 * If one of the channel actors dies (e.g. because it has been closed), it will be ignored: callers may thus receive
 * fewer responses than expected.
 * Since channels are constantly being updated concurrently, the channel data is just a recent snapshot: callers should
 * never expect this data to be fully up-to-date.
 */
object PeerChannelsCollector {

  // @formatter:off
  sealed trait Command
  case class GetChannels(replyTo: ActorRef[Peer.PeerChannels], channels: Set[ActorRef[CMD_GET_CHANNEL_INFO]]) extends Command
  private case class WrappedChannelInfo(requestId: ByteVector32, state: ChannelState, data: ChannelData) extends Command
  private case class IgnoreRequest(requestId: ByteVector32) extends Command
  // @formatter:on

  def apply(remoteNodeId: PublicKey): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
        Behaviors.receiveMessagePartial {
          case GetChannels(replyTo, channels) =>
            val adapter = context.messageAdapter[RES_GET_CHANNEL_INFO](r => WrappedChannelInfo(r.requestId, r.state, r.data))
            val pending = channels.map { c =>
              val requestId = randomBytes32()
              context.watchWith(c, IgnoreRequest(requestId))
              c ! CMD_GET_CHANNEL_INFO(adapter.toClassic, requestId)
              requestId
            }
            new PeerChannelsCollector(replyTo, remoteNodeId, context).collect(pending, Nil)
        }
      }
    }
  }

}

private class PeerChannelsCollector(replyTo: ActorRef[Peer.PeerChannels], remoteNodeId: PublicKey, context: ActorContext[PeerChannelsCollector.Command]) {

  import PeerChannelsCollector._

  private val log = context.log

  def collect(pending: Set[ByteVector32], received: Seq[Peer.ChannelInfo]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedChannelInfo(requestId, state, data) =>
        val pending1 = pending - requestId
        val received1 = received :+ Peer.ChannelInfo(state, data)
        if (pending1.isEmpty) {
          replyTo ! Peer.PeerChannels(remoteNodeId, received1)
          Behaviors.stopped
        } else {
          collect(pending1, received1)
        }
      case IgnoreRequest(requestId) =>
        log.debug("could not fetch peer channel information, channel actor died")
        val pending1 = pending - requestId
        if (pending1.isEmpty) {
          replyTo ! Peer.PeerChannels(remoteNodeId, received)
          Behaviors.stopped
        } else {
          collect(pending1, received)
        }
    }
  }

}
