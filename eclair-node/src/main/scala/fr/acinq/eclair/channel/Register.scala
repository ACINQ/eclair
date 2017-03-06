package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorContext, ActorLogging, ActorPath, ActorRef, ActorSystem, Props, Terminated}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey

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
      val shortChannelId = shortIds.find(_._2 == channelId).get._1
      context become main(channels - channelId, shortIds - shortChannelId)

    case 'channels => sender ! channels
  }
}

object Register {

  /**
    * Once it reaches NORMAL state, channel creates a [[fr.acinq.eclair.channel.AliasActor]]
    * which name is counterparty_id-anchor_id
    */
  def createAlias(nodeId: PublicKey, channelId: BinaryData)(implicit context: ActorContext) =
    context.actorOf(Props(new AliasActor(context.self)), name = s"${nodeId.toBin}-$channelId")

  def actorPathToChannels(system: ActorSystem): ActorPath =
    system / "switchboard" / "peer-*" / "*"

  def actorPathToChannel(system: ActorSystem, channelId: BinaryData): ActorPath =
    system / "switchboard" / "peer-*" / "*" / s"*-$channelId"

  def actorPathToChannel(channelId: BinaryData)(implicit context: ActorContext): ActorPath = actorPathToChannel(context.system, channelId)

  def actorPathToPeers()(implicit context: ActorContext): ActorPath =
    context.system / "switchboard" / "peer-*"

  def actorPathToPeer(system: ActorSystem, nodeId: PublicKey): ActorPath =
    system / "switchboard" / s"peer-${nodeId.toBin}"

}