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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.channel.{ChannelConfig, LocalParams, SupportedChannelType}
import fr.acinq.eclair.io.Peer.{ConnectedData, OutgoingMessage, SpawnChannelNonInitiator}
import fr.acinq.eclair.wire.protocol.{Error, OpenChannel, OpenDualFundedChannel}
import fr.acinq.eclair.{AcceptOpenChannel, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, InterceptOpenChannelResponse, RejectOpenChannel}

import scala.concurrent.duration.FiniteDuration

/**
 * Child actor of a Peer that handles the interactions with an InterceptOpenChannelPlugin:
 *  - sends OpenChannelReceived to the plugin
 *  - waits for its response and translates it to the Peer data format (either SpawnChannelNonInitiator or an outgoing Error)
 *  - reject the channel open after a timeout
 *
 * Note: we don't fully trust plugins to be correctly implemented, and we need to respond to our peer even if the plugin fails to tell us what to do
 */
object OpenChannelInterceptor {
  def apply(replyTo: ActorRef[Any], plugin: InterceptOpenChannelPlugin, timeout: FiniteDuration, connectedData: ConnectedData, temporaryChannelId: ByteVector32, localParams: LocalParams, open: Either[OpenChannel, OpenDualFundedChannel], channelType: SupportedChannelType): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startSingleTimer(PluginTimeout, timeout)
        new OpenChannelInterceptor(replyTo, plugin, connectedData, temporaryChannelId, localParams, open, channelType, context).start()
      }
    }
  }

  // @formatter:off
  sealed trait Command
  case object PluginTimeout extends Command
  case class WrappedOpenChannelResponse(pluginResponse: InterceptOpenChannelResponse) extends Command
  // @formatter:on
}

private class OpenChannelInterceptor(replyTo: ActorRef[Any], plugin: InterceptOpenChannelPlugin, connectedData: ConnectedData, temporaryChannelId: ByteVector32, localParams: LocalParams, open: Either[OpenChannel, OpenDualFundedChannel], channelType: SupportedChannelType, context: ActorContext[OpenChannelInterceptor.Command]) {

  import OpenChannelInterceptor._

  private def start(): Behavior[Command] = {
    val pluginResponseAdapter = context.messageAdapter[InterceptOpenChannelResponse](WrappedOpenChannelResponse)
    plugin.openChannelInterceptor ! InterceptOpenChannelReceived(pluginResponseAdapter, open, temporaryChannelId, localParams)

    Behaviors.receiveMessage {
      case PluginTimeout =>
        context.log.error(s"plugin ${plugin.name} timed out while intercepting open channel")
        replyTo ! OutgoingMessage(Error(temporaryChannelId, "plugin timeout"), connectedData.peerConnection)
        Behaviors.stopped
      case WrappedOpenChannelResponse(a: AcceptOpenChannel) =>
        replyTo ! SpawnChannelNonInitiator(open, ChannelConfig.standard, channelType, a.localParams)
        Behaviors.stopped
      case WrappedOpenChannelResponse(r: RejectOpenChannel) =>
        replyTo ! OutgoingMessage(r.error, connectedData.peerConnection)
        Behaviors.stopped
    }
  }

}
