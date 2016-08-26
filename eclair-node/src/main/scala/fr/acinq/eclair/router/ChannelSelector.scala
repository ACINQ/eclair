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

  def main(node2channel: Map[BinaryData, ActorRef], channel2balance: Map[ActorRef, Long]): Receive = {

    case ChannelChangedState(channel, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val bal = d.commitments.theirCommit.spec.amount_them_msat
      log.info(s"new channel to $theirNodeId with availableMsat=$bal")
      context become main(node2channel + (theirNodeId -> channel), channel2balance + (channel -> bal))

    case ChannelSignatureReceived(channel, commitments) =>
      val bal = commitments.theirCommit.spec.amount_them_msat
      log.info(s"channel $channel now has availableMsat=$bal")
      context become main(node2channel, channel2balance + (channel -> bal))
  }
}
