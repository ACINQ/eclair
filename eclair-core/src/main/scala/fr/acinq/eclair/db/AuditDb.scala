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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{NetworkFee, PublishedTransaction, Stats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{MilliSatoshi, Paginated, TimestampMilli}

trait AuditDb {

  def add(channelLifecycle: ChannelEvent): Unit

  def add(paymentSent: PaymentSent): Unit

  def add(paymentReceived: PaymentReceived): Unit

  def add(paymentRelayed: PaymentRelayed): Unit

  def add(txPublished: TransactionPublished): Unit

  def add(txConfirmed: TransactionConfirmed): Unit

  def addChannelUpdate(channelUpdateParametersChanged: ChannelUpdateParametersChanged): Unit

  def addPathFindingExperimentMetrics(metrics: PathFindingExperimentMetrics): Unit

  def listPublished(channelId: ByteVector32): Seq[PublishedTransaction]

  def listPublished(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[PublishedTransaction]

  def listChannelEvents(channelId: ByteVector32, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent]

  def listChannelEvents(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent]

  def listSent(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentSent]

  def listReceived(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentReceived]

  def listRelayed(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentRelayed]

  def listNetworkFees(from: TimestampMilli, to: TimestampMilli): Seq[NetworkFee]

  def stats(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[Stats]

}

object AuditDb {

  case class PublishedTransaction(txId: TxId, desc: String, localMiningFee: Satoshi, remoteMiningFee: Satoshi, feerate: FeeratePerKw, timestamp: TimestampMilli)

  object PublishedTransaction {
    def apply(tx: TransactionPublished): PublishedTransaction = PublishedTransaction(tx.tx.txid, tx.desc, tx.localMiningFee, tx.remoteMiningFee, tx.feerate, tx.timestamp)
  }

  case class NetworkFee(remoteNodeId: PublicKey, channelId: ByteVector32, txId: ByteVector32, fee: Satoshi, txType: String, timestamp: TimestampMilli)

  case class Stats(channelId: ByteVector32, direction: String, avgPaymentAmount: Satoshi, paymentCount: Int, relayFee: Satoshi, networkFee: Satoshi)

  case class RelayedPart(channelId: ByteVector32, remoteNodeId: PublicKey, amount: MilliSatoshi, direction: String, relayType: String, timestamp: TimestampMilli)

  def relayType(e: PaymentRelayed): String = e match {
    case _: ChannelPaymentRelayed => "channel"
    case _: TrampolinePaymentRelayed => "trampoline"
    case _: OnTheFlyFundingPaymentRelayed => "on-the-fly-funding"
  }

  private def incomingParts(parts: Seq[RelayedPart]): Seq[PaymentEvent.IncomingPayment] = {
    parts.filter(_.direction == "IN").map(p => PaymentEvent.IncomingPayment(p.channelId, p.remoteNodeId, p.amount, p.timestamp)).sortBy(_.receivedAt)
  }

  private def outgoingParts(parts: Seq[RelayedPart]): Seq[PaymentEvent.OutgoingPayment] = {
    parts.filter(_.direction == "OUT").map(p => PaymentEvent.OutgoingPayment(p.channelId, p.remoteNodeId, p.amount, p.timestamp)).sortBy(_.settledAt)
  }

  private def verifyInAndOut(parts: Seq[RelayedPart]): Boolean = {
    parts.exists(_.direction == "IN") && parts.exists(_.direction == "OUT")
  }

  def listRelayedInternal(relayedByHash: Map[ByteVector32, Seq[RelayedPart]], trampolineDetails: Map[ByteVector32, (PublicKey, MilliSatoshi)], paginated_opt: Option[Paginated]): Seq[PaymentRelayed] = {
    val result = relayedByHash.flatMap {
      case (paymentHash, parts) =>
        // We may have been routing multiple payments for the same payment_hash with different relay types.
        // That's fine, we simply separate each part into the correct event.
        val channelParts = parts.filter(_.relayType == "channel")
        val trampolineParts = parts.filter(_.relayType == "trampoline")
        val onTheFlyParts = parts.filter(_.relayType == "on-the-fly-funding")
        val channelRelayed_opt = if (verifyInAndOut(channelParts)) {
          Some(ChannelPaymentRelayed(paymentHash, incomingParts(channelParts), outgoingParts(channelParts)))
        } else {
          None
        }
        val trampolineRelayed_opt = trampolineDetails.get(paymentHash) match {
          case Some((nextTrampolineNode, nextTrampolineAmount)) if verifyInAndOut(trampolineParts) => Some(TrampolinePaymentRelayed(paymentHash, incomingParts(trampolineParts), outgoingParts(trampolineParts), nextTrampolineNode, nextTrampolineAmount))
          case _ => None
        }
        val onTheFlyRelayed_opt = if (verifyInAndOut(onTheFlyParts)) {
          Some(OnTheFlyFundingPaymentRelayed(paymentHash, incomingParts(onTheFlyParts), outgoingParts(onTheFlyParts)))
        } else {
          None
        }
        channelRelayed_opt.toSeq ++ trampolineRelayed_opt.toSeq ++ onTheFlyRelayed_opt.toSeq
    }.toSeq.sortBy(_.settledAt)
    paginated_opt match {
      case Some(paginated) => result.slice(paginated.skip, paginated.skip + paginated.count)
      case None => result
    }
  }

}