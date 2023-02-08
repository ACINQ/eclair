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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.ClosingType
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{BlockHeight, Features, ShortChannelId}

/**
 * Created by PM on 17/08/2016.
 */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isInitiator: Boolean, temporaryChannelId: ByteVector32, commitTxFeerate: FeeratePerKw, fundingTxFeerate: Option[FeeratePerKw]) extends ChannelEvent

// This trait can be used by non-standard channels to inject themselves into Register actor and thus make them usable for routing
trait AbstractChannelRestored extends ChannelEvent {
  val channel: ActorRef
  val channelId: ByteVector32
  val peer: ActorRef
  val remoteNodeId: PublicKey
}

case class ChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, data: PersistentChannelData) extends AbstractChannelRestored

case class ChannelIdAssigned(channel: ActorRef, remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32) extends ChannelEvent

/** This event will be sent whenever a new scid is assigned to the channel, be it a real, local alias or remote alias. */
case class ShortChannelIdAssigned(channel: ActorRef, channelId: ByteVector32, shortIds: ShortIds, remoteNodeId: PublicKey) extends ChannelEvent

/** This event will be sent if a channel was aborted before completing the opening flow. */
case class ChannelAborted(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32) extends ChannelEvent

/** This event will be sent once a channel has been successfully opened and is ready to process payments. */
case class ChannelOpened(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32) extends ChannelEvent

case class LocalChannelUpdate(channel: ActorRef, channelId: ByteVector32, shortIds: ShortIds, remoteNodeId: PublicKey, channelAnnouncement_opt: Option[ChannelAnnouncement], channelUpdate: ChannelUpdate, commitments: Commitments) extends ChannelEvent {
  /**
   * We always include the local alias because we must always be able to route based on it.
   * However we only include the real scid if option_scid_alias is disabled, because we otherwise want to hide it.
   */
  def scidsForRouting: Seq[ShortChannelId] = {
    val canUseRealScid = !commitments.params.channelFeatures.hasFeature(Features.ScidAlias)
    if (canUseRealScid) {
      shortIds.real.toOption.toSeq :+ shortIds.localAlias
    } else {
      Seq(shortIds.localAlias)
    }
  }
}

case class ChannelUpdateParametersChanged(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, channelUpdate: ChannelUpdate) extends ChannelEvent

case class LocalChannelDown(channel: ActorRef, channelId: ByteVector32, shortIds: ShortIds, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, previousState: ChannelState, currentState: ChannelState, commitments_opt: Option[Commitments]) extends ChannelEvent

case class ChannelSignatureSent(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelErrorOccurred(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, error: ChannelOpenError, isFatal: Boolean) extends ChannelEvent

// NB: the fee should be set to 0 when we're not paying it.
case class TransactionPublished(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction, miningFee: Satoshi, desc: String) extends ChannelEvent

case class TransactionConfirmed(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction) extends ChannelEvent

// NB: this event is only sent when the channel is available.
case class AvailableBalanceChanged(channel: ActorRef, channelId: ByteVector32, shortIds: ShortIds, commitments: Commitments) extends ChannelEvent

case class ChannelPersisted(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, data: PersistentChannelData) extends ChannelEvent

case class LocalCommitConfirmed(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, refundAtBlock: BlockHeight) extends ChannelEvent

case class ChannelClosed(channel: ActorRef, channelId: ByteVector32, closingType: ClosingType, commitments: Commitments) extends ChannelEvent
