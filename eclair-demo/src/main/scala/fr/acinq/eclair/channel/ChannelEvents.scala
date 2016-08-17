package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.BinaryData

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, params: OurChannelParams, theirNodeId: String) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, channelId: BinaryData) extends ChannelEvent

case class ChannelChangedState(channel: ActorRef, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, Commitments: Commitments) extends ChannelEvent
