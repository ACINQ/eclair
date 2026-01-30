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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{ConfirmedTransaction, PublishedTransaction, RelayStats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, Paginated, TimestampMilli}

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

  def listConfirmed(channelId: ByteVector32): Seq[ConfirmedTransaction]

  def listConfirmed(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[ConfirmedTransaction]

  def listConfirmed(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[ConfirmedTransaction]

  def listChannelEvents(channelId: ByteVector32, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent]

  def listChannelEvents(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent]

  def listSent(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentSent]

  def listReceived(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentReceived]

  def listRelayed(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentRelayed]

  def relayStats(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): RelayStats = {
    val relayed = listRelayed(from, to).filter(e => e.incoming.exists(_.remoteNodeId == remoteNodeId) || e.outgoing.exists(_.remoteNodeId == remoteNodeId))
    val relayFeeEarned = relayed.map(e => {
      // When using MPP and trampoline, payments can be relayed through multiple nodes at once.
      // We split the fee according to the proportional amount relayed through the requested node.
      e.relayFee * (e.outgoing.filter(_.remoteNodeId == remoteNodeId).map(_.amount).sum.toLong.toDouble / e.amountOut.toLong)
    }).sum
    val incomingPayments = relayed.flatMap(_.incoming).filter(_.remoteNodeId == remoteNodeId)
    val outgoingPayments = relayed.flatMap(_.outgoing).filter(_.remoteNodeId == remoteNodeId)
    val onChainFeePaid = listConfirmed(remoteNodeId, from, to, None).map(_.onChainFeePaid).sum
    RelayStats(remoteNodeId, incomingPayments.size, incomingPayments.map(_.amount).sum, outgoingPayments.size, outgoingPayments.map(_.amount).sum, relayFeeEarned, onChainFeePaid, from, to)
  }

  def relayStats(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[RelayStats] = {
    // We fill payment data from all relayed payments.
    val perNodeStats = listRelayed(from, to).foldLeft(Map.empty[PublicKey, RelayStats]) {
      case (perNodeStats, e) =>
        val withIncoming = e.incoming.foldLeft(perNodeStats) {
          case (perNodeStats, i) =>
            val current = perNodeStats.getOrElse(i.remoteNodeId, RelayStats(i.remoteNodeId, from, to))
            val updated = current.copy(incomingPaymentCount = current.incomingPaymentCount + 1, totalAmountIn = current.totalAmountIn + i.amount)
            perNodeStats + (i.remoteNodeId -> updated)
        }
        val withOutgoing = e.outgoing.foldLeft(withIncoming) {
          case (perNodeStats, o) =>
            val current = perNodeStats.getOrElse(o.remoteNodeId, RelayStats(o.remoteNodeId, from, to))
            val updated = current.copy(outgoingPaymentCount = current.outgoingPaymentCount + 1, totalAmountOut = current.totalAmountOut + o.amount)
            perNodeStats + (o.remoteNodeId -> updated)
        }
        val withRelayFee = e.outgoing.map(_.remoteNodeId).toSet.foldLeft(withOutgoing) {
          case (perNodeStats, remoteNodeId) =>
            val current = perNodeStats.getOrElse(remoteNodeId, RelayStats(remoteNodeId, from, to))
            val updated = current.copy(relayFeeEarned = current.relayFeeEarned + e.relayFee * (e.outgoing.filter(_.remoteNodeId == remoteNodeId).map(_.amount).sum.toLong.toDouble / e.amountOut.toLong))
            perNodeStats + (remoteNodeId -> updated)
        }
        withRelayFee
    }.values.toSeq.sortBy(_.relayFeeEarned)(Ordering[MilliSatoshi].reverse)
    // We add on-chain fees paid for each node.
    val confirmedTransactions = listConfirmed(from, to)
    Paginated.paginate(perNodeStats.map(stats => {
      val onChainFeePaid = confirmedTransactions.filter(_.remoteNodeId == stats.remoteNodeId).map(_.onChainFeePaid).sum
      stats.copy(onChainFeePaid = onChainFeePaid)
    }), paginated_opt)
  }

}

object AuditDb {

  case class PublishedTransaction(txId: TxId, desc: String, localMiningFee: Satoshi, remoteMiningFee: Satoshi, feerate: FeeratePerKw, timestamp: TimestampMilli)

  object PublishedTransaction {
    def apply(tx: TransactionPublished): PublishedTransaction = PublishedTransaction(tx.tx.txid, tx.desc, tx.localMiningFee, tx.remoteMiningFee, tx.feerate, tx.timestamp)
  }

  case class ConfirmedTransaction(remoteNodeId: PublicKey, channelId: ByteVector32, txId: TxId, onChainFeePaid: Satoshi, txType: String, timestamp: TimestampMilli)

  case class RelayStats(remoteNodeId: PublicKey, incomingPaymentCount: Int, totalAmountIn: MilliSatoshi, outgoingPaymentCount: Int, totalAmountOut: MilliSatoshi, relayFeeEarned: MilliSatoshi, onChainFeePaid: Satoshi, from: TimestampMilli, to: TimestampMilli)

  object RelayStats {
    def apply(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): RelayStats = RelayStats(remoteNodeId, 0, 0 msat, 0, 0 msat, 0 msat, 0 sat, from, to)
  }

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
    Paginated.paginate(relayedByHash.flatMap {
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
    }.toSeq.sortBy(_.settledAt), paginated_opt)
  }

}