/*
 * Copyright 2019 ACINQ SAS
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
import fr.acinq.bitcoin.{ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.ClosingType
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate}

/**
 * Created by PM on 17/08/2016.
 */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: ByteVector32, initialFeeratePerKw: FeeratePerKw, fundingTxFeeratePerKw: Option[FeeratePerKw]) extends ChannelEvent

// This trait can be used by non-standard channels to inject themselves into Register actor and thus make them usable for routing
trait AbstractChannelRestored extends ChannelEvent {
  val channel: ActorRef
  val channelId: ByteVector32
  val peer: ActorRef
  val remoteNodeId: PublicKey
}

case class ChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, data: HasCommitments) extends AbstractChannelRestored

case class ChannelIdAssigned(channel: ActorRef, remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32) extends ChannelEvent

case class ShortChannelIdAssigned(channel: ActorRef, channelId: ByteVector32, shortChannelId: ShortChannelId, previousShortChannelId: Option[ShortChannelId]) extends ChannelEvent

case class LocalChannelUpdate(channel: ActorRef, channelId: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, channelAnnouncement_opt: Option[ChannelAnnouncement], channelUpdate: ChannelUpdate, commitments: AbstractCommitments) extends ChannelEvent

case class ChannelUpdateParametersChanged(channel: ActorRef, channelId: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, channelUpdate: ChannelUpdate) extends ChannelEvent

case class LocalChannelDown(channel: ActorRef, channelId: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, previousState: ChannelState, currentState: ChannelState, commitments_opt: Option[AbstractCommitments]) extends ChannelEvent

case class ChannelSignatureSent(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelErrorOccurred(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, data: ChannelData, error: ChannelOpenError, isFatal: Boolean) extends ChannelEvent

// NB: the fee should be set to 0 when we're not paying it.
case class TransactionPublished(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction, fee: Satoshi, desc: String) extends ChannelEvent

case class TransactionConfirmed(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction) extends ChannelEvent

// NB: this event is only sent when the channel is available.
case class AvailableBalanceChanged(channel: ActorRef, channelId: ByteVector32, shortChannelId: ShortChannelId, commitments: AbstractCommitments) extends ChannelEvent

case class ChannelPersisted(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, data: HasCommitments) extends ChannelEvent

case class LocalCommitConfirmed(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, refundAtBlock: Long) extends ChannelEvent

case class ChannelClosed(channel: ActorRef, channelId: ByteVector32, closingType: ClosingType, commitments: Commitments)
