package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: BinaryData) extends ChannelEvent

case class ChannelRestored(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, channelId: BinaryData, currentData: HasCommitments) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, remoteNodeId: PublicKey, temporaryChannelId: BinaryData, channelId: BinaryData) extends ChannelEvent

case class ShortChannelIdAssigned(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId) extends ChannelEvent

case class LocalChannelUpdate(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, channelAnnouncement_opt: Option[ChannelAnnouncement], channelUpdate: ChannelUpdate) extends ChannelEvent

case class LocalChannelDown(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureSent(channel: ActorRef, Commitments: Commitments) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, Commitments: Commitments) extends ChannelEvent
