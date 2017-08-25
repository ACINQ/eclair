package fr.acinq.eclair

import akka.actor.Actor
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.PaymentEvent
import fr.acinq.eclair.router.NetworkEvent

/**
  * Created by PM on 31/05/2017.
  */
class TextuiUpdater(textui: Textui) extends Actor {
  context.system.eventStream.subscribe(self, classOf[ChannelEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkEvent])
  context.system.eventStream.subscribe(self, classOf[PaymentEvent])

  override def receive: Receive = {
    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId) =>
      textui.addChannel(channel, temporaryChannelId, remoteNodeId, WAIT_FOR_INIT_INTERNAL, Satoshi(0), Satoshi(1))

    case ChannelRestored(channel, _, remoteNodeId, _, channelId, data) =>
      textui.addChannel(channel, channelId, remoteNodeId, OFFLINE, Satoshi(33), Satoshi(100))

    case ChannelStateChanged(channel, _, _, _, state, _) =>
      textui.updateState(channel, state)
  }
}
