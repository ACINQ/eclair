package fr.acinq.eclair.channel

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorContext, ActorLogging, ActorPath, ActorRef, ActorSystem, Props, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Register.Forward

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

  context.system.eventStream.subscribe(self, classOf[ChannelEvent])

  override def receive: Receive = main(Map())

  def main(channels: Map[String, ActorRef]): Receive = {
    case ChannelCreated(channel, _, _, _, temporaryChannelId) =>
      context.watch(channel)
      context become main(channels + (java.lang.Long.toHexString(temporaryChannelId) -> channel))

    case ChannelRestored(channel, _, _, _, channelId, _) =>
      context.watch(channel)
      context become main(channels + (java.lang.Long.toHexString(channelId) -> channel))

    case ChannelStateChanged(channel, _, _, _, _, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      import d.commitments.channelId
      context.watch(channel)
      // channel id switch
      context become main(channels + (java.lang.Long.toHexString(channelId) -> channel) - java.lang.Long.toHexString(d.lastSent.temporaryChannelId))

    case Terminated(actor) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      context become main(channels - channelId)

    case 'channels => sender ! channels

    case Forward(channelId, msg) if !channels.contains(java.lang.Long.toHexString(channelId)) =>
      sender ! Failure(new RuntimeException(s"channel $channelId not found"))

    case Forward(channelId, msg) =>
      channels(java.lang.Long.toHexString(channelId)) forward msg
  }
}

object Register {

  def actorPathToPeers()(implicit context: ActorContext): ActorPath =
    context.system / "switchboard" / "peer-*"

  def actorPathToPeer(system: ActorSystem, nodeId: PublicKey): ActorPath =
    system / "switchboard" / s"peer-${nodeId.toBin}"

  case class Forward(channelId: Long, message: Any)

}