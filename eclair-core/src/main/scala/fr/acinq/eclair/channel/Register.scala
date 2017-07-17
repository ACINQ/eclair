package fr.acinq.eclair.channel

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.Register.{Forward, ForwardShortId}

/**
  * Created by PM on 26/01/2016.
  */

class Register extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelCreated])
  context.system.eventStream.subscribe(self, classOf[ChannelRestored])
  context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  override def receive: Receive = main(Map.empty, Map.empty)

  def main(channels: Map[BinaryData, ActorRef], shortIds: Map[Long, BinaryData]): Receive = {
    case ChannelCreated(channel, _, _, _, temporaryChannelId) =>
      context.watch(channel)
      context become main(channels + (temporaryChannelId -> channel), shortIds)

    case ChannelRestored(channel, _, _, _, channelId, _) =>
      context.watch(channel)
      context become main(channels + (channelId -> channel), shortIds)

    case ChannelIdAssigned(channel, temporaryChannelId, channelId) =>
      context become main(channels + (channelId -> channel) - temporaryChannelId, shortIds)

    case ShortChannelIdAssigned(channel, channelId, shortChannelId) =>
      context become main(channels, shortIds + (shortChannelId -> channelId))

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      val shortChannelId = shortIds.find(_._2 == channelId).map(_._1).getOrElse(0L)
      context become main(channels - channelId, shortIds - shortChannelId)

    case 'channels => sender ! channels

    case 'shortIds => sender ! shortIds

    case Forward(channelId, msg) =>
      channels.get(channelId) match {
        case Some(channel) => channel forward msg
        case None => sender ! Failure(new RuntimeException(s"channel $channelId not found"))
      }

    case ForwardShortId(shortChannelId, msg) =>
      shortIds.get(shortChannelId).flatMap(channels.get(_)) match {
        case Some(channel) => channel forward msg
        case None => sender ! Failure(new RuntimeException(s"channel $shortChannelId not found"))
      }
  }
}

object Register {
  // @formatter:off
  case class Forward(channelId: BinaryData, message: Any)
  case class ForwardShortId(shortChannelId: Long, message: Any)
  // @formatter:on
}