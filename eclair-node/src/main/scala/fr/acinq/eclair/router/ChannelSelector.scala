package fr.acinq.eclair.router

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.{ChannelChangedState, ChannelSignatureReceived, DATA_NORMAL, NORMAL}


/**
  * Created by PM on 26/08/2016.
  */
class ChannelSelector extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])
  context.system.eventStream.subscribe(self, classOf[ChannelSignatureReceived])

  override def receive: Receive = main(Map(), Map())

  def main(node2channels: Map[BinaryData, Set[ActorRef]], channel2balance: Map[ActorRef, Long]): Receive = {

    case ChannelChangedState(channel, _, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val bal = d.commitments.remoteCommit.spec.toRemoteMsat
      log.info(s"new channel to $theirNodeId with availableMsat=$bal")
      val channels = node2channels.get(theirNodeId).getOrElse(Set()) + channel
      context become main(node2channels + (theirNodeId -> channels), channel2balance + (channel -> bal))

    case ChannelSignatureReceived(channel, commitments) =>
      val bal = commitments.remoteCommit.spec.toRemoteMsat
      log.info(s"channel $channel now has availableMsat=$bal")
      context become main(node2channels, channel2balance + (channel -> bal))

    case SelectChannelRequest(targetNodeId) if node2channels.contains(targetNodeId) =>
      // getting a set of all channels pointing to targetNodeId
      val candidates = node2channels(targetNodeId)
      // selecting the channel with the highest available balance
      val selected = candidates.map(c => (c, channel2balance(c))).toList.sortBy(_._2).last._1
      sender ! SelectChannelResponse(Some(selected))

    case SelectChannelRequest(targetNodeId) =>
      sender ! SelectChannelResponse(None)

  }
}

case class SelectChannelRequest(targetNodeId: BinaryData)

case class SelectChannelResponse(channel_opt: Option[ActorRef])