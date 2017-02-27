package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.db.ChannelState
import fr.acinq.eclair.wire.LightningMessage

/**
  * Created by fabrice on 27/02/17.
  */

case class StoreAndForward(messages: Seq[LightningMessage], destination: ActorRef, channelId: Long, channelState: ChannelState)

object StoreAndForward {
  def apply(message: LightningMessage, destination: ActorRef, channelId: Long, channelState: ChannelState) = new StoreAndForward(Seq(message), destination, channelId, channelState)
}

class Forwarder(nodeParams: NodeParams) extends Actor with ActorLogging {
  val db = nodeParams.db
  val channelDb = Channel.makeChannelDb(db)

  def receive = {
    case StoreAndForward(messages, destination, channelId, channelState) =>
      channelDb.put(channelId, ChannelRecord(channelId, channelState))
      messages.foreach(message => destination forward message)
      context become main(channelId)
  }

  def main(currentChannelId: Long): Receive = {
    case StoreAndForward(messages, destination, channelId, channelState) if channelId != currentChannelId =>
      log.info(s"channel changed id: $currentChannelId -> $channelId")
      channelDb.put(channelId, ChannelRecord(channelId, channelState))
      db.delete(currentChannelId.toString)
      messages.foreach(message => destination forward message)
      context become main(channelId)
    case StoreAndForward(messages, destination, channelId, channelState) =>
      channelDb.put(channelId, ChannelRecord(channelId, channelState))
      messages.foreach(message => destination forward message)
  }
}
