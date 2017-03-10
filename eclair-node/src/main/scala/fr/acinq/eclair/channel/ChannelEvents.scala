package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: BinaryData) extends ChannelEvent

case class ChannelRestored(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, channelId: BinaryData, currentData: HasCommitments) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, temporaryChannelId: BinaryData, channelId: BinaryData) extends ChannelEvent

case class ShortChannelIdAssigned(channel: ActorRef, channelId: BinaryData, shortChannelId: Long) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, Commitments: Commitments) extends ChannelEvent
