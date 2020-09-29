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

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.Register._

/**
 * Created by PM on 26/01/2016.
 */

class Register extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelCreated])
  context.system.eventStream.subscribe(self, classOf[ChannelRestored])
  context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  override def receive: Receive = main(Map.empty, Map.empty, Map.empty)

  def main(channels: Map[ByteVector32, ActorRef], shortIds: Map[ShortChannelId, ByteVector32], channelsTo: Map[ByteVector32, PublicKey]): Receive = {
    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId, _, _) =>
      context.watch(channel)
      context become main(channels + (temporaryChannelId -> channel), shortIds, channelsTo + (temporaryChannelId -> remoteNodeId))

    case ChannelRestored(channel, channelId, _, remoteNodeId, _, _) =>
      context.watch(channel)
      context become main(channels + (channelId -> channel), shortIds, channelsTo + (channelId -> remoteNodeId))

    case ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId) =>
      context become main(channels + (channelId -> channel) - temporaryChannelId, shortIds, channelsTo + (channelId -> remoteNodeId) - temporaryChannelId)

    case ShortChannelIdAssigned(_, channelId, shortChannelId, _) =>
      context become main(channels, shortIds + (shortChannelId -> channelId), channelsTo)

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      val shortChannelId = shortIds.find(_._2 == channelId).map(_._1).getOrElse(ShortChannelId(0L))
      context become main(channels - channelId, shortIds - shortChannelId, channelsTo - channelId)

    case Symbol("channels") => sender ! channels

    case Symbol("shortIds") => sender ! shortIds

    case Symbol("channelsTo") => sender ! channelsTo

    case fwd@Forward(replyTo, channelId, msg) =>
      // for backward compatibility with legacy ask, we use the replyTo as sender
      val compatReplyTo = if (replyTo == ActorRef.noSender) sender else replyTo
      channels.get(channelId) match {
        case Some(channel) => channel.tell(msg, compatReplyTo)
        case None => compatReplyTo ! ForwardFailure(fwd)
      }

    case fwd@ForwardShortId(replyTo, shortChannelId, msg) =>
      // for backward compatibility with legacy ask, we use the replyTo as sender
      val compatReplyTo = if (replyTo == ActorRef.noSender) sender else replyTo
      shortIds.get(shortChannelId).flatMap(channels.get) match {
        case Some(channel) => channel.tell(msg, compatReplyTo)
        case None => compatReplyTo ! ForwardShortIdFailure(fwd)
      }
  }
}

object Register {

  // @formatter:off
  case class Forward[T](replyTo: ActorRef, channelId: ByteVector32, message: T)
  case class ForwardShortId[T](replyTo: ActorRef, shortChannelId: ShortChannelId, message: T)

  case class ForwardFailure[T](fwd: Forward[T])
  case class ForwardShortIdFailure[T](fwd: ForwardShortId[T])
  // @formatter:on
}
