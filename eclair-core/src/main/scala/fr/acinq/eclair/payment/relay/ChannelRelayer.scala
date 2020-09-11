/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.payment.relay

import java.util.UUID

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.IncomingPacket
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.{Logs, NodeParams, ShortChannelId}

import scala.collection.mutable

/**
 * Created by t-bast on 09/10/2019.
 */

/**
 * The [[ChannelRelayer]] relays a single upstream HTLC to a downstream channel.
 * It selects the best channel to use to relay and retries using other channels in case a local failure happens.
 */
object ChannelRelayer {

  type ChannelUpdates = Map[ShortChannelId, OutgoingChannel]
  type NodeChannels = mutable.MultiDict[PublicKey, ShortChannelId]

  // @formatter:off
  sealed trait Command
  case class WrappedGetOutgoingChannels(replyTo: ActorRef, getOutgoingChannels: GetOutgoingChannels) extends Command
  case class WrappedChannelRelayPacket(channelRelayPacket: IncomingPacket.ChannelRelayPacket) extends Command
  case class WrappedLocalChannelUpdate(localChannelUpdate: LocalChannelUpdate) extends Command
  case class WrappedLocalChannelDown(localChannelDown: LocalChannelDown) extends Command
  case class WrappedAvailableBalanceChanged(availableBalanceChanged: AvailableBalanceChanged) extends Command
  case class WrappedShortChannelIdAssigned(shortChannelIdAssigned: ShortChannelIdAssigned) extends Command
  // @formatter:on

  def mdc: Command => Map[String, String] = {
    case c: WrappedChannelRelayPacket => Logs.mdc(
      paymentHash_opt = Some(c.channelRelayPacket.add.paymentHash))
    case _ => Map.empty
  }

  def apply(nodeParams: NodeParams,
            register: ActorRef,
            channelUpdates: ChannelUpdates = Map.empty,
            node2channels: NodeChannels = mutable.MultiDict.empty[PublicKey, ShortChannelId]
           ): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[LocalChannelUpdate](WrappedLocalChannelUpdate))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[LocalChannelDown](WrappedLocalChannelDown))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[AvailableBalanceChanged](WrappedAvailableBalanceChanged))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ShortChannelIdAssigned](WrappedShortChannelIdAssigned))
      context.messageAdapter[IncomingPacket.ChannelRelayPacket](WrappedChannelRelayPacket)
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT)), mdc) {
        Behaviors.receiveMessage {
          case WrappedChannelRelayPacket(channelRelayPacket) =>
            val relayId = UUID.randomUUID()
            context.log.debug(s"spawning a new handler with relayId=$relayId")
            context.spawn(ChannelRelay.apply(nodeParams, register, channelUpdates, node2channels, relayId, channelRelayPacket), name = relayId.toString)
            Behaviors.same

          case WrappedGetOutgoingChannels(replyTo, GetOutgoingChannels(enabledOnly)) =>
            val channels = if (enabledOnly) {
              channelUpdates.values.filter(o => Announcements.isEnabled(o.channelUpdate.channelFlags))
            } else {
              channelUpdates.values
            }
            replyTo ! OutgoingChannels(channels.toSeq)
            Behaviors.same

          case WrappedLocalChannelUpdate(LocalChannelUpdate(_, channelId, shortChannelId, remoteNodeId, _, channelUpdate, commitments)) =>
            context.log.debug(s"updating local channel info for channelId=$channelId shortChannelId=$shortChannelId remoteNodeId=$remoteNodeId channelUpdate={} commitments={}", channelUpdate, commitments)
            val channelUpdates1 = channelUpdates + (channelUpdate.shortChannelId -> OutgoingChannel(remoteNodeId, channelUpdate, commitments))
            apply(nodeParams, register, channelUpdates1, node2channels.addOne(remoteNodeId, channelUpdate.shortChannelId))

          case WrappedLocalChannelDown(LocalChannelDown(_, channelId, shortChannelId, remoteNodeId)) =>
            context.log.debug(s"removed local channel info for channelId=$channelId shortChannelId=$shortChannelId")
            apply(nodeParams, register, channelUpdates - shortChannelId, node2channels.subtractOne(remoteNodeId, shortChannelId))

          case WrappedAvailableBalanceChanged(AvailableBalanceChanged(_, _, shortChannelId, commitments)) =>
            val channelUpdates1 = channelUpdates.get(shortChannelId) match {
              case Some(c: OutgoingChannel) => channelUpdates + (shortChannelId -> c.copy(commitments = commitments))
              case None => channelUpdates // we only consider the balance if we have the channel_update
            }
            apply(nodeParams, register, channelUpdates1, node2channels)

          case WrappedShortChannelIdAssigned(ShortChannelIdAssigned(_, channelId, shortChannelId, previousShortChannelId_opt)) =>
            val (channelUpdates1, node2channels1) = previousShortChannelId_opt match {
              case Some(previousShortChannelId) if previousShortChannelId != shortChannelId =>
                context.log.debug(s"shortChannelId changed for channelId=$channelId ($previousShortChannelId->$shortChannelId, probably due to chain re-org)")
                // We simply remove the old entry: we should receive a LocalChannelUpdate with the new shortChannelId shortly.
                val node2channels1 = channelUpdates.get(previousShortChannelId).map(_.nextNodeId) match {
                  case Some(remoteNodeId) => node2channels.subtractOne(remoteNodeId, previousShortChannelId)
                  case None => node2channels
                }
                (channelUpdates - previousShortChannelId, node2channels1)
              case _ => (channelUpdates, node2channels)
            }
            apply(nodeParams, register, channelUpdates1, node2channels1)
        }
      }
    }
}
