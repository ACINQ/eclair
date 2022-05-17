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
import fr.acinq.eclair.{BlockHeight, Features, LocalAlias, RealShortChannelId, ShortChannelId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.ClosingType
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate}

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

/**
 * This event will be sent whenever a new scid is assigned to the channel, be it a real, local alias or remote alias.
 *
 * @param realShortChannelId_opt  the real scid, it can change in case of a reorg before the channel reaches 6 conf
 * @param localAlias              we must remember the alias that we sent to our peer because we use it to:
 *                                - identify incoming [[ChannelUpdate]]
 *                                - route outgoing payments to that channel
 * @param remoteAlias_opt         we only remember the last alias received from our peer, we use this to generate
 *                                routing hints in [[fr.acinq.eclair.payment.Bolt11Invoice]]
 */
case class ShortChannelIdAssigned(channel: ActorRef, channelId: ByteVector32, realShortChannelId_opt: Option[RealShortChannelId], localAlias: LocalAlias, remoteAlias_opt: Option[ShortChannelId], remoteNodeId: PublicKey) extends ChannelEvent

case class LocalChannelUpdate(channel: ActorRef, channelId: ByteVector32, realShortChannelId_opt: Option[RealShortChannelId], localAlias: LocalAlias, remoteNodeId: PublicKey, channelAnnouncement_opt: Option[ChannelAnnouncement], channelUpdate: ChannelUpdate, commitments: AbstractCommitments) extends ChannelEvent {
  /**
   * We always map the local alias because we must always be able to route based on it
   * However we only map the real scid if option_scid_alias (TODO: rename to option_scid_privacy) is disabled
   */
  def scidsForRouting: Seq[ShortChannelId] = {
    commitments match {
      case c: Commitments =>
        val realScid_opt = if (c.channelFeatures.hasFeature(Features.ScidAlias)) None else realShortChannelId_opt
        realScid_opt.toSeq :+ localAlias
      case _ => Seq(localAlias) // TODO: ugly
    }
  }
}

case class ChannelUpdateParametersChanged(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, channelUpdate: ChannelUpdate) extends ChannelEvent

case class LocalChannelDown(channel: ActorRef, channelId: ByteVector32, realShortChannelId_opt: Option[RealShortChannelId], localAlias: LocalAlias, remoteNodeId: PublicKey) extends ChannelEvent

case class ChannelStateChanged(channel: ActorRef, channelId: ByteVector32, peer: ActorRef, remoteNodeId: PublicKey, previousState: ChannelState, currentState: ChannelState, commitments_opt: Option[AbstractCommitments]) extends ChannelEvent

case class ChannelSignatureSent(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelSignatureReceived(channel: ActorRef, commitments: Commitments) extends ChannelEvent

case class ChannelErrorOccurred(channel: ActorRef, channelId: ByteVector32, remoteNodeId: PublicKey, error: ChannelOpenError, isFatal: Boolean) extends ChannelEvent

// NB: the fee should be set to 0 when we're not paying it.
case class TransactionPublished(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction, miningFee: Satoshi, desc: String) extends ChannelEvent

case class TransactionConfirmed(channelId: ByteVector32, remoteNodeId: PublicKey, tx: Transaction) extends ChannelEvent

// NB: this event is only sent when the channel is available.
case class AvailableBalanceChanged(channel: ActorRef, channelId: ByteVector32, realShortChannelId_opt: Option[RealShortChannelId], localAlias: LocalAlias, commitments: AbstractCommitments) extends ChannelEvent

case class ChannelPersisted(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, data: PersistentChannelData) extends ChannelEvent

case class LocalCommitConfirmed(channel: ActorRef, remoteNodeId: PublicKey, channelId: ByteVector32, refundAtBlock: BlockHeight) extends ChannelEvent

case class ChannelClosed(channel: ActorRef, channelId: ByteVector32, closingType: ClosingType, commitments: Commitments)
