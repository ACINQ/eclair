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
  context.system.eventStream.subscribe(self, classOf[ChannelSignatureSent])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  override def receive: Receive = main(Map.empty, Map.empty, Map.empty, Map.empty)

  def main(channels: Map[BinaryData, ActorRef], shortIds: Map[ShortChannelId, BinaryData], channelsTo: Map[BinaryData, PublicKey], localBalances: Map[BinaryData, ChannelBalance]): Receive = {
    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId) =>
      context.watch(channel)
      context become main(channels + (temporaryChannelId -> channel), shortIds, channelsTo + (temporaryChannelId -> remoteNodeId), localBalances)

    case ChannelRestored(channel, _, remoteNodeId, _, channelId, _) =>
      context.watch(channel)
      context become main(channels + (channelId -> channel), shortIds, channelsTo + (channelId -> remoteNodeId), localBalances)

    case ChannelIdAssigned(channel, remoteNodeId, temporaryChannelId, channelId) =>
      context become main(channels + (channelId -> channel) - temporaryChannelId, shortIds, channelsTo + (channelId -> remoteNodeId) - temporaryChannelId, localBalances)

    case ShortChannelIdAssigned(_, channelId, shortChannelId) =>
      context become main(channels, shortIds + (shortChannelId -> channelId), channelsTo, localBalances)

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.collectFirst { case (id, channelActor) if channelActor == actor => id }.get
      val shortChannelId = shortIds.collectFirst { case (shortId, id) if id == channelId => shortId } getOrElse ShortChannelId(0L)
      context become main(channels - channelId, shortIds - shortChannelId, channelsTo - channelId, localBalances - channelId)

    case ChannelSignatureSent(_, commitments) =>
      val updatedBalance = commitments.channelId -> getBalances(commitments)
      context become main(channels, shortIds, channelsTo, localBalances + updatedBalance)

    case ChannelStateChanged(_, _, _, previousState, NORMAL, d: DATA_NORMAL) if previousState != NORMAL =>
      val updatedBalance = d.commitments.channelId -> getBalances(d.commitments)
      context become main(channels, shortIds, channelsTo, localBalances + updatedBalance)

    case ChannelStateChanged(_, _, _, NORMAL, currentState, hasCommitments: HasCommitments) if currentState != NORMAL =>
      context become main(channels, shortIds, channelsTo, localBalances - hasCommitments.channelId)

    case 'channels => sender ! channels

    case 'shortIds => sender ! shortIds

    case 'channelsTo => sender ! channelsTo

    case 'localBalances => sender ! localBalances.values

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
  }

  private def getBalances(cs: Commitments) = {
    val latestRemoteCommit = cs.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit).getOrElse(cs.remoteCommit)
    val canReceiveWithReserve = cs.localCommit.spec.toRemoteMsat - cs.localParams.channelReserveSatoshis * 1000L
    val canSendWithReserve = latestRemoteCommit.spec.toRemoteMsat - cs.remoteParams.channelReserveSatoshis * 1000L
    ChannelBalance(cs.channelId, canSendWithReserve, canReceiveWithReserve)
  }
}

case class ChannelBalance(channelId: BinaryData, canSendMsat: Long, canReceiveMsat: Long)

object Register {

  // @formatter:off
  case class Forward[T](channelId: BinaryData, message: T)
  case class ForwardShortId[T](shortChannelId: ShortChannelId, message: T)

  case class ForwardFailure[T](fwd: Forward[T]) extends RuntimeException(s"channel ${fwd.channelId} not found")
  case class ForwardShortIdFailure[T](fwd: ForwardShortId[T]) extends RuntimeException(s"channel ${fwd.shortChannelId} not found")
  // @formatter:on
}