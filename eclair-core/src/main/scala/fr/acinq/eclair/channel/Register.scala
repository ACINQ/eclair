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

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.scala.ByteVector32
import fr.acinq.bitcoin.scala.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.Register._
import fr.acinq.eclair.io.{PeerConnected, PeerDisconnected}

/**
 * Created by PM on 26/01/2016.
 */

class Register extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[PeerConnected])
  context.system.eventStream.subscribe(self, classOf[PeerDisconnected])
  context.system.eventStream.subscribe(self, classOf[ChannelCreated])
  context.system.eventStream.subscribe(self, classOf[ChannelRestored])
  context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  override def receive: Receive = main(Map.empty, Map.empty, Map.empty, Map.empty)

  def main(channels: Map[ByteVector32, ActorRef], shortIds: Map[ShortChannelId, ByteVector32], channelsTo: Map[ByteVector32, PublicKey], peers: Map[ShortChannelId, ActorRef]): Receive = {

    case PeerConnected(peer, nodeId) =>
      context become main(channels, shortIds, channelsTo, peers + (ShortChannelId.peerId(nodeId) -> peer))

    case PeerDisconnected(_, nodeId) =>
      context become main(channels, shortIds, channelsTo, peers - ShortChannelId.peerId(nodeId))

    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId, _, _) =>
      context.watch(channel)
      context become main(channels + (temporaryChannelId -> channel), shortIds, channelsTo + (temporaryChannelId -> remoteNodeId), peers)

    case ChannelRestored(channel, _, remoteNodeId, _, channelId, _) =>
      context.watch(channel)
      context become main(channels + (channelId -> channel), shortIds, channelsTo + (channelId -> remoteNodeId), peers)

    case ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId) =>
      context become main(channels + (channelId -> channel) - temporaryChannelId, shortIds, channelsTo + (channelId -> remoteNodeId) - temporaryChannelId, peers)

    case ShortChannelIdAssigned(_, channelId, shortChannelId, _) =>
      context become main(channels, shortIds + (shortChannelId -> channelId), channelsTo, peers)

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      val shortChannelId = shortIds.find(_._2 == channelId).map(_._1).getOrElse(ShortChannelId(0L))
      context become main(channels - channelId, shortIds - shortChannelId, channelsTo - channelId, peers)

    case Symbol("channels") => sender ! channels

    case Symbol("shortIds") => sender ! shortIds

    case Symbol("channelsTo") => sender ! channelsTo

    case fwd@Forward(channelId, msg) =>
      channels.get(channelId) match {
        case Some(channel) => channel forward msg
        case None => sender ! Failure(ForwardFailure(fwd))
      }

    case fwd@ForwardShortId(shortChannelId, msg) =>
      shortIds.get(shortChannelId).flatMap(channels.get) match {
        case Some(channel) => channel forward msg
        case None => sender ! Failure(ForwardShortIdFailure(fwd))
      }

    case fwd@ForwardPeerId(peerShortChannelId, msg) =>
      peers.get(peerShortChannelId) match {
        case Some(peer) => peer forward msg
        case None => sender ! Failure(ForwardPeerIdFailure(fwd))
      }
  }
}

object Register {

  // @formatter:off
  case class Forward[T](channelId: ByteVector32, message: T)
  case class ForwardShortId[T](shortChannelId: ShortChannelId, message: T)
  case class ForwardPeerId[T](peerId: ShortChannelId, message: T)

  case class ForwardFailure[T](fwd: Forward[T]) extends RuntimeException(s"channel ${fwd.channelId} not found")
  case class ForwardShortIdFailure[T](fwd: ForwardShortId[T]) extends RuntimeException(s"channel ${fwd.shortChannelId} not found")
  case class ForwardPeerIdFailure[T](fwd: ForwardPeerId[T]) extends RuntimeException(s"peer ${fwd.peerId.toLong.toHexString} not found")
  // @formatter:on
}