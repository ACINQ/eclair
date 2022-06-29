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

package fr.acinq.eclair.channel

import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.Register._
import fr.acinq.eclair.{SubscriptionsComplete, ShortChannelId}

/**
 * Created by PM on 26/01/2016.
 */

class Register() extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelCreated])
  context.system.eventStream.subscribe(self, classOf[AbstractChannelRestored])
  context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])
  context.system.eventStream.publish(SubscriptionsComplete(this.getClass))

  // @formatter:off
  private case class ChannelTerminated(channel: ActorRef, channelId: ByteVector32)
  // @formatter:on

  override def receive: Receive = main(Map.empty, Map.empty, Map.empty)

  def main(channels: Map[ByteVector32, ActorRef], shortIds: Map[ShortChannelId, ByteVector32], channelsTo: Map[ByteVector32, PublicKey]): Receive = {
    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId, _, _) =>
      context.watchWith(channel, ChannelTerminated(channel, temporaryChannelId))
      context become main(channels + (temporaryChannelId -> channel), shortIds, channelsTo + (temporaryChannelId -> remoteNodeId))

    case event: AbstractChannelRestored =>
      context.watchWith(event.channel, ChannelTerminated(event.channel, event.channelId))
      context become main(channels + (event.channelId -> event.channel), shortIds, channelsTo + (event.channelId -> event.remoteNodeId))

    case ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId) =>
      context.unwatch(channel)
      context.watchWith(channel, ChannelTerminated(channel, channelId))
      context become main(channels + (channelId -> channel) - temporaryChannelId, shortIds, channelsTo + (channelId -> remoteNodeId) - temporaryChannelId)

    case scidAssigned: ShortChannelIdAssigned =>
      // We map all known scids (real or alias) to the channel_id. The relayer is in charge of deciding whether a real
      // scid can be used or not for routing (see option_scid_alias), but the register is neutral.
      val m = (scidAssigned.shortIds.real.toOption.toSeq :+ scidAssigned.shortIds.localAlias).map(_ -> scidAssigned.channelId).toMap
      // duplicate check for aliases (we use a random value in a large enough space that there should never be collisions)
      shortIds.get(scidAssigned.shortIds.localAlias) match {
        case Some(channelId) if channelId != scidAssigned.channelId =>
          log.error("duplicate alias={} for channelIds={},{} this should never happen!", scidAssigned.shortIds.localAlias, channelId, scidAssigned.channelId)
        case _ => ()
      }
      context become main(channels, shortIds ++ m, channelsTo)

    case ChannelTerminated(_, channelId) =>
      val shortChannelIds = shortIds.collect { case (key, value) if value == channelId => key }
      context become main(channels - channelId, shortIds -- shortChannelIds, channelsTo - channelId)

    case Symbol("channels") => sender() ! channels

    case Symbol("shortIds") => sender() ! shortIds

    case Symbol("channelsTo") => sender() ! channelsTo

    case fwd@Forward(replyTo, channelId, msg) =>
      // for backward compatibility with legacy ask, we use the replyTo as sender
      val compatReplyTo = if (replyTo == null) sender() else replyTo.toClassic
      channels.get(channelId) match {
        case Some(channel) => channel.tell(msg, compatReplyTo)
        case None => compatReplyTo ! ForwardFailure(fwd)
      }

    case fwd@ForwardShortId(replyTo, shortChannelId, msg) =>
      // for backward compatibility with legacy ask, we use the replyTo as sender
      val compatReplyTo = if (replyTo == null) sender() else replyTo.toClassic
      shortIds.get(shortChannelId).flatMap(channels.get) match {
        case Some(channel) => channel.tell(msg, compatReplyTo)
        case None => compatReplyTo ! ForwardShortIdFailure(fwd)
      }
  }
}

object Register {

  def props(): Props = Props(new Register())

  // @formatter:off
  case class Forward[T](replyTo: akka.actor.typed.ActorRef[ForwardFailure[T]], channelId: ByteVector32, message: T)
  case class ForwardShortId[T](replyTo: akka.actor.typed.ActorRef[ForwardShortIdFailure[T]], shortChannelId: ShortChannelId, message: T)

  case class ForwardFailure[T](fwd: Forward[T])
  case class ForwardShortIdFailure[T](fwd: ForwardShortId[T])
  // @formatter:on
}
