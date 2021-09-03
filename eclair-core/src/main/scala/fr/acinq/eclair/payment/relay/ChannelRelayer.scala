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

  // @formatter:off
  sealed trait Command
  case class GetOutgoingChannels(replyTo: ActorRef, getOutgoingChannels: Relayer.GetOutgoingChannels) extends Command
  case class Relay(channelRelayPacket: IncomingPacket.ChannelRelayPacket) extends Command
  private[payment] case class WrappedLocalChannelUpdate(localChannelUpdate: LocalChannelUpdate) extends Command
  private[payment] case class WrappedLocalChannelDown(localChannelDown: LocalChannelDown) extends Command
  private[payment] case class WrappedAvailableBalanceChanged(availableBalanceChanged: AvailableBalanceChanged) extends Command
  private[payment] case class WrappedShortChannelIdAssigned(shortChannelIdAssigned: ShortChannelIdAssigned) extends Command
  // @formatter:on

  def mdc: Command => Map[String, String] = {
    case c: Relay => Logs.mdc(paymentHash_opt = Some(c.channelRelayPacket.add.paymentHash))
    case _ => Map.empty
  }

  private type ChannelUpdates = Map[ShortChannelId, Relayer.OutgoingChannel]
  private type NodeChannels = mutable.MultiDict[PublicKey, ShortChannelId]

  def apply(nodeParams: NodeParams,
            register: ActorRef,
            channelUpdates: ChannelUpdates = Map.empty,
            node2channels: NodeChannels = mutable.MultiDict.empty[PublicKey, ShortChannelId]): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[LocalChannelUpdate](WrappedLocalChannelUpdate))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[LocalChannelDown](WrappedLocalChannelDown))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[AvailableBalanceChanged](WrappedAvailableBalanceChanged))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ShortChannelIdAssigned](WrappedShortChannelIdAssigned))
      context.messageAdapter[IncomingPacket.ChannelRelayPacket](Relay)
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT)), mdc) {
        Behaviors.receiveMessage {
          case Relay(channelRelayPacket) =>
            val relayId = UUID.randomUUID()
            val nextNodeId_opt: Option[PublicKey] = channelUpdates.get(channelRelayPacket.payload.outgoingChannelId) match {
              case Some(channel) => Some(channel.nextNodeId)
              case None => None
            }
            val channels: Map[ShortChannelId, Relayer.OutgoingChannel] = nextNodeId_opt match {
              case Some(nextNodeId) => node2channels.get(nextNodeId).map(channelUpdates).map(c => c.channelUpdate.shortChannelId -> c).toMap
              case None => Map.empty
            }
            context.log.debug(s"spawning a new handler with relayId=$relayId to nextNodeId={} with channels={}", nextNodeId_opt.getOrElse(""), channels.keys.mkString(","))
            context.spawn(ChannelRelay.apply(nodeParams, register, channels, relayId, channelRelayPacket), name = relayId.toString)
            Behaviors.same

          case GetOutgoingChannels(replyTo, Relayer.GetOutgoingChannels(enabledOnly)) =>
            val channels = if (enabledOnly) {
              channelUpdates.values.filter(o => Announcements.isEnabled(o.channelUpdate.channelFlags))
            } else {
              channelUpdates.values
            }
            replyTo ! Relayer.OutgoingChannels(channels.toSeq)
            Behaviors.same

          case WrappedLocalChannelUpdate(LocalChannelUpdate(_, channelId, shortChannelId, remoteNodeId, _, channelUpdate, commitments)) =>
            context.log.debug(s"updating local channel info for channelId=$channelId shortChannelId=$shortChannelId remoteNodeId=$remoteNodeId channelUpdate={} commitments={}", channelUpdate, commitments)
            val channelUpdates1 = channelUpdates + (channelUpdate.shortChannelId -> Relayer.OutgoingChannel(remoteNodeId, channelUpdate, commitments))
            val node2channels1 = node2channels.addOne(remoteNodeId, channelUpdate.shortChannelId)
            apply(nodeParams, register, channelUpdates1, node2channels1)

          case WrappedLocalChannelDown(LocalChannelDown(_, channelId, shortChannelId, remoteNodeId)) =>
            context.log.debug(s"removed local channel info for channelId=$channelId shortChannelId=$shortChannelId")
            val node2channels1 = node2channels.subtractOne(remoteNodeId, shortChannelId)
            apply(nodeParams, register, channelUpdates - shortChannelId, node2channels1)

          case WrappedAvailableBalanceChanged(AvailableBalanceChanged(_, channelId, shortChannelId, commitments)) =>
            val channelUpdates1 = channelUpdates.get(shortChannelId) match {
              case Some(c: Relayer.OutgoingChannel) =>
                context.log.debug(s"available balance changed for channelId=$channelId shortChannelId=$shortChannelId availableForSend={} availableForReceive={}", commitments.availableBalanceForSend, commitments.availableBalanceForReceive)
                channelUpdates + (shortChannelId -> c.copy(commitments = commitments))
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
