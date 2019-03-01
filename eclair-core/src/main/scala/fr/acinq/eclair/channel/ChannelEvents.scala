/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.Channel.ChannelError
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}

/**
  * Created by PM on 17/08/2016.
  */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: BinaryData) extends ChannelEvent

case class ChannelRestored(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, channelId: BinaryData, currentData: HasCommitments) extends ChannelEvent

case class ChannelIdAssigned(channel: ActorRef, remoteNodeId: PublicKey, temporaryChannelId: BinaryData, channelId: BinaryData) extends ChannelEvent

case class ShortChannelIdAssigned(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId) extends ChannelEvent

case class LocalChannelUpdate(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, channelAnnouncement_opt: Option[ChannelAnnouncement], channelUpdate: ChannelUpdate, commitments: Commitments) extends ChannelEvent

case class LocalChannelDown(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, previousState: State, currentState: State, currentData: Data) extends ChannelEvent

case class ChannelSignatureSent(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelFailed(channel: ActorRef, channelId: BinaryData, remoteNodeId: PublicKey, data: Data, error: ChannelError) extends ChannelEvent

case class NetworkFeePaid(channel: ActorRef, remoteNodeId: PublicKey, channelId: BinaryData, tx: Transaction, fee: Satoshi, txType: String) extends ChannelEvent

// NB: this event is only sent when the channel is available
case class AvailableBalanceChanged(channel: ActorRef, channelId: BinaryData, shortChannelId: ShortChannelId, localBalanceMsat: Long, commitments: Commitments) extends ChannelEvent

case class ChannelPersisted(channel: ActorRef, remoteNodeId: PublicKey, channelId: BinaryData, data: Data) extends ChannelEvent

case class LocalCommitConfirmed(channel: ActorRef, remoteNodeId: PublicKey, channelId: BinaryData, refundAtBlock: Long) extends ChannelEvent

case class ChannelClosed(channel: ActorRef, channelId: BinaryData, closeType: String, commitments: Commitments)
