package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi}

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, params: LocalParams, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, channelId: BinaryData, amount: Satoshi) extends ChannelEvent

case class ChannelChangedState(channel: ActorRef, transport: ActorRef, remoteNodeId: PublicKey, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, Commitments: Commitments) extends ChannelEvent
