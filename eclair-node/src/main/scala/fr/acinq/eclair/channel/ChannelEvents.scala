package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi}

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: Long) extends ChannelEvent

case class ChannelRestored(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, channelId: Long, currentData: HasCommitments) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, channelId: Long) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, Commitments: Commitments) extends ChannelEvent
