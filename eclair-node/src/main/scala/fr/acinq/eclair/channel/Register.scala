package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorContext, ActorLogging, ActorPath, ActorRef, ActorSystem, Props, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey

/**
  * Created by PM on 26/01/2016.
  */

/**
  * Actor hierarchy:
  * system
  * ├── blockchain
  * ├── register
  * │       ├── auth-handler-0
  * │       │         └── channel
  * │       │                 └── remote_node_id-anchor_id (alias to parent)
  * │      ...
  * │       └── auth-handler-n
  * │                 └── channel
  * │                         └── remote_node_id-anchor_id (alias to parent)
  * ├── server
  * ├── client (0..m, transient)
  * └── api
  */

class Register extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelCreated])
  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])

  override def receive: Receive = main(Map())

  def main(channels: Map[String, ActorRef]): Receive = {
    case ChannelCreated(temporaryChannelId, _, channel, _, _) =>
      context.watch(channel)
      context become main(channels + (java.lang.Long.toHexString(temporaryChannelId) -> channel))

    case ChannelChangedState(channel, _, _, _, _, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      import d.commitments.channelId
      context.watch(channel)
      // channel id switch
      context become main(channels + (java.lang.Long.toHexString(channelId) -> channel) - java.lang.Long.toHexString(d.lastSent.temporaryChannelId))

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      context become main(channels - channelId)

    case 'channels => sender ! channels
  }
}

object Register {

  /**
    * Once it reaches NORMAL state, channel creates a [[fr.acinq.eclair.channel.AliasActor]]
    * which name is counterparty_id-anchor_id
    */
  def createAlias(nodeId: PublicKey, channelId: Long)(implicit context: ActorContext) =
    context.actorOf(Props(new AliasActor(context.self)), name = s"${nodeId.toBin}-${java.lang.Long.toHexString(channelId)}")

  def actorPathToChannels(system: ActorSystem): ActorPath =
    system / "switchboard" / "peer-*" / "*"

  def actorPathToChannel(system: ActorSystem, channelId: Long): ActorPath =
    system / "switchboard" / "peer-*" / "*" / s"*-${java.lang.Long.toHexString(channelId)}"

  def actorPathToChannel(channelId: Long)(implicit context: ActorContext): ActorPath = actorPathToChannel(context.system, channelId)

  def actorPathToPeers()(implicit context: ActorContext): ActorPath =
    context.system / "switchboard" / "peer-*"

  def actorPathToPeer(system: ActorSystem, nodeId: PublicKey): ActorPath =
    system / "switchboard" / s"peer-${nodeId.toBin}"

}