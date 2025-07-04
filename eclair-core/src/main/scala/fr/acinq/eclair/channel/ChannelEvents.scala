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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, Transaction, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.ClosingType
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, HtlcFailureMessage, LiquidityAds, UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{BlockHeight, Features, MilliSatoshi, RealShortChannelId, ShortChannelId}

/**
 * Created by PM on 17/08/2016.
 */

trait ChannelEvent

case class ChannelCreated(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isOpener: Boolean, temporaryChannelId: ByteVector32, commitTxFeerate: FeeratePerKw, fundingTxFeerate: Option[FeeratePerKw]) extends ChannelEvent

// This trait can be used by non-standard channels to inject themselves into Register actor and thus make them usable for routing
trait AbstractChannelRestored extends ChannelEvent {
  val channel: ActorRef
  val channelId: ByteVector32
  val peer: ActorRef
  val remoteNodeId: PublicKey
}

case class ChannelRestored(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, data: PersistentChannelData) extends AbstractChannelRestored

case class ChannelIdAssigned(channel: ActorRef, remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32) extends ChannelEvent

/** This event will be sent whenever a new scid is assigned to the channel: local alias, remote alias or announcement. */
case class ShortChannelIdAssigned(channel: ActorRef, channelId: ByteVector32, announcement_opt: Option[ChannelAnnouncement], aliases: ShortIdAliases, remoteNodeId: PublicKey) extends ChannelEvent

/** This event will be sent if a channel was aborted before completing the opening flow. */
case class ChannelAborted(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32) extends ChannelEvent

/** This event will be sent once a channel has been successfully opened and is ready to process payments. */
case class ChannelOpened(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32) extends ChannelEvent

/** This event is sent once channel_ready or splice_locked have been exchanged. */
case class ChannelReadyForPayments(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, fundingTxIndex: Long) extends ChannelEvent

case class LocalChannelUpdate(channel: ActorRef, channelId: ByteVector32, aliases: ShortIdAliases, remoteNodeId: PublicKey, announcement_opt: Option[AnnouncedCommitment], channelUpdate: ChannelUpdate, commitments: Commitments) extends ChannelEvent {
  /**
   * We always include the local alias because we must always be able to route based on it.
   * However we only include the real scid if option_scid_alias is disabled, because we otherwise want to hide it.
   */
  def scidsForRouting: Seq[ShortChannelId] = {
    val canUseRealScid = !commitments.channelParams.channelFeatures.hasFeature(Features.ScidAlias)
    if (canUseRealScid) {
      announcement_opt.map(_.shortChannelId).toSeq :+ aliases.localAlias
    } else {
      Seq(aliases.localAlias)
    }
  }
}

case class ChannelUpdateParametersChanged(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, channelUpdate: ChannelUpdate) extends ChannelEvent

case class LocalChannelDown(channel: ActorRef, channelId: ByteVector32, realScids: Seq[RealShortChannelId], aliases: ShortIdAliases, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, previousState: ChannelState, currentState: ChannelState, commitments_opt: Option[Commitments]) extends ChannelEvent

case class ChannelSignatureSent(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class LiquidityPurchase(fundingTxId: TxId, fundingTxIndex: Long, isBuyer: Boolean, amount: Satoshi, fees: LiquidityAds.Fees, capacity: Satoshi, localContribution: Satoshi, remoteContribution: Satoshi, localBalance: MilliSatoshi, remoteBalance: MilliSatoshi, outgoingHtlcCount: Long, incomingHtlcCount: Long) {
  val previousCapacity: Satoshi = capacity - localContribution - remoteContribution
  val previousLocalBalance: MilliSatoshi = if (isBuyer) localBalance - localContribution + fees.total else localBalance - localContribution - fees.total
  val previousRemoteBalance: MilliSatoshi = if (isBuyer) remoteBalance - remoteContribution - fees.total else remoteBalance - remoteContribution + fees.total
}

case class ChannelLiquidityPurchased(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, purchase: LiquidityPurchase) extends ChannelEvent

case class ChannelErrorOccurred(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, error: ChannelError, isFatal: Boolean) extends ChannelEvent

// NB: the fee should be set to 0 when we're not paying it.
case class TransactionPublished(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction, miningFee: Satoshi, desc: String) extends ChannelEvent

case class TransactionConfirmed(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction) extends ChannelEvent

// NB: this event is only sent when the channel is available.
case class AvailableBalanceChanged(channel: ActorRef, channelId: ByteVector32, aliases: ShortIdAliases, commitments: Commitments, lastAnnouncement_opt: Option[ChannelAnnouncement]) extends ChannelEvent

case class ChannelPersisted(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, data: PersistentChannelData) extends ChannelEvent

case class LocalCommitConfirmed(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, refundAtBlock: BlockHeight) extends ChannelEvent

case class ChannelClosed(channel: ActorRef, channelId: ByteVector32, closingType: ClosingType, commitments: Commitments) extends ChannelEvent

case class OutgoingHtlcAdded(add: UpdateAddHtlc, remoteNodeId: PublicKey, upstream: Upstream.Hot, fee: MilliSatoshi)

sealed trait OutgoingHtlcSettled

case class OutgoingHtlcFailed(fail: HtlcFailureMessage) extends OutgoingHtlcSettled

case class OutgoingHtlcFulfilled(fulfill: UpdateFulfillHtlc) extends OutgoingHtlcSettled
