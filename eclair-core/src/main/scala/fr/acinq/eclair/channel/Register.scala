/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.BinaryData
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
  context.system.eventStream.subscribe(self, classOf[ChannelSignatureReceived])

  override def receive: Receive = main(Map.empty, Map.empty, Map.empty, Map.empty)

  def main(channels: Map[BinaryData, ActorRef], shortIds: Map[ShortChannelId, BinaryData], channelsTo: Map[BinaryData, PublicKey], preferredChannels: Map[PublicKey, Map[BinaryData, Commitments]]): Receive = {
    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId) =>
      context.watch(channel)
      context become main(channels + (temporaryChannelId -> channel), shortIds, channelsTo + (temporaryChannelId -> remoteNodeId), preferredChannels)

    case ChannelRestored(channel, _, remoteNodeId, _, channelId, _) =>
      context.watch(channel)
      context become main(channels + (channelId -> channel), shortIds, channelsTo + (channelId -> remoteNodeId), preferredChannels)

    case ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId) =>
      context become main(channels + (channelId -> channel) - temporaryChannelId, shortIds, channelsTo + (channelId -> remoteNodeId) - temporaryChannelId, preferredChannels)

    case ShortChannelIdAssigned(_, channelId, shortChannelId) =>
      context become main(channels, shortIds + (shortChannelId -> channelId), channelsTo, preferredChannels)

    case ChannelSignatureReceived(_, commitments) =>
      val preferredChannels1 = preferredChannels.get(commitments.remoteParams.nodeId) match {
        case Some(channels) => preferredChannels + (commitments.remoteParams.nodeId -> (channels + (commitments.channelId -> commitments)))
        case None => preferredChannels + (commitments.remoteParams.nodeId -> Map(commitments.channelId -> commitments))
      }
      context become main(channels, shortIds, channelsTo, preferredChannels1)

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      val shortChannelId = shortIds.find(_._2 == channelId).map(_._1).getOrElse(ShortChannelId(0L))
      val preferredChannels1 = channelsTo.get(channelId).flatMap(preferredChannels.get(_)) match {
        case Some(channels) => preferredChannels + (channelsTo(channelId) -> (channels - channelId)) // NB: channelsTo map does contain channelId because of the above
        case _ => preferredChannels
      }
      context become main(channels - channelId, shortIds - shortChannelId, channelsTo - channelId, preferredChannels1)

    case 'channels => sender ! channels

    case 'shortIds => sender ! shortIds

    case 'channelsTo => sender ! channelsTo

    case fwd@Forward(channelId, msg) =>
      channels.get(channelId) match {
        case Some(channel) => channel forward msg
        case None => sender ! Failure(ForwardFailure(fwd))
      }

    case fwd@ForwardShortId(shortChannelId, msg, toPreferred) =>
      shortIds
        .get(shortChannelId) // get the channelId
        .map {
        case channelId if toPreferred =>
          channelsTo.get(channelId).flatMap(preferredChannels.get(_)) match {
            case Some(channels) =>
              // Note: remoteCommit.toRemote == our funds
              val preferred = channels.values.toList.maxBy(_.remoteCommit.spec.toRemoteMsat)
              if (preferred.channelId != channelId) {
                log.info("replacing by preferred channelId={}->{}", channelId, preferred.channelId)
                preferred.channelId
              } else {
                channelId
              }
            case _ => channelId
          }
        case channelId => channelId
      } // replace by the preferred channelId if available
        .flatMap(channels.get(_)) match {
        case Some(channel) => channel forward msg
        case None => sender ! Failure(ForwardShortIdFailure(fwd))
      }
  }
}

object Register {

  // @formatter:off
  case class Forward[T](channelId: BinaryData, message: T)
  case class ForwardShortId[T](shortChannelId: ShortChannelId, message: T, toPreferred: Boolean = false)

  case class ForwardFailure[T](fwd: Forward[T]) extends RuntimeException(s"channel ${fwd.channelId} not found")
  case class ForwardShortIdFailure[T](fwd: ForwardShortId[T]) extends RuntimeException(s"channel ${fwd.shortChannelId} not found")
  // @formatter:on
}