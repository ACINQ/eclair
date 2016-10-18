package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import lightning.sha256_hash

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, params: OurChannelParams, theirNodeId: String) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, channelId: BinaryData, amount: Satoshi) extends ChannelEvent

case class ChannelChangedState(channel: ActorRef, theirNodeId: BinaryData, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, Commitments: Commitments) extends ChannelEvent

case class PaymentSent(channel: ActorRef, h: sha256_hash) extends ChannelEvent

case class PaymentFailed(channel: ActorRef, h: sha256_hash, reason: String) extends ChannelEvent

case class PaymentReceived(channel: ActorRef, h: sha256_hash) extends ChannelEvent
