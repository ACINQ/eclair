/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel.fsm

import akka.actor.typed.scaladsl.adapter.{TypedActorRefOps, actorRefAdapter}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction, TxId}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Helpers.getRelayFees
import fr.acinq.eclair.channel.LocalFundingStatus.{ConfirmedFundingTx, DualFundedUnconfirmedFundingTx, SingleFundedUnconfirmedFundingTx}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{BroadcastChannelUpdate, PeriodicRefresh, REFRESH_CHANNEL_UPDATE_INTERVAL}
import fr.acinq.eclair.crypto.NonceGenerator
import fr.acinq.eclair.db.RevokedHtlcInfoCleaner
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, DefaultCommitmentFormat, SimpleTaprootChannelCommitmentFormat}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{RealShortChannelId, ShortChannelId}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 18/08/2022.
 */

trait CommonFundingHandlers extends CommonHandlers {

  this: Channel =>

  /**
   * @param delay_opt optional delay to reduce herd effect at startup.
   */
  def watchFundingSpent(commitment: Commitment, additionalKnownSpendingTxs: Set[TxId], delay_opt: Option[FiniteDuration]): Unit = {
    val knownSpendingTxs = commitment.commitTxIds.txIds ++ additionalKnownSpendingTxs
    val watch = WatchFundingSpent(self, commitment.fundingInput.txid, commitment.fundingInput.index.toInt, knownSpendingTxs)
    delay_opt match {
      case Some(delay) => context.system.scheduler.scheduleOnce(delay, blockchain.toClassic, watch)
      case None => blockchain ! watch
    }
  }

  /**
   * @param delay_opt optional delay to reduce herd effect at startup.
   */
  def watchFundingConfirmed(fundingTxId: TxId, minDepth_opt: Option[Int], delay_opt: Option[FiniteDuration]): Unit = {
    val watch = minDepth_opt match {
      case Some(fundingMinDepth) => WatchFundingConfirmed(self, fundingTxId, fundingMinDepth)
      // When using 0-conf, we make sure that the transaction was successfully published, otherwise there is a risk
      // of accidentally double-spending it later (e.g. restarting bitcoind would remove the utxo locks).
      case None => WatchPublished(self, fundingTxId)
    }
    delay_opt match {
      case Some(delay) => context.system.scheduler.scheduleOnce(delay, blockchain.toClassic, watch)
      case None => blockchain ! watch
    }
  }

  def acceptFundingTxConfirmed(w: WatchFundingConfirmedTriggered, d: ChannelDataWithCommitments): Either[Commitments, (Commitments, Commitment)] = {
    log.info("funding txid={} was confirmed at blockHeight={} txIndex={}", w.tx.txid, w.blockHeight, w.txIndex)
    d.commitments.latest.localFundingStatus match {
      case _: SingleFundedUnconfirmedFundingTx =>
        // in the single-funding case, as fundee, it is the first time we see the full funding tx, we must verify that it is
        // valid (it pays the correct amount to the correct script). We also check as funder even if it's not really useful
        Try(Transaction.correctlySpends(d.commitments.latest.fullySignedLocalCommitTx(channelKeys), Seq(w.tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
          case Success(_) => ()
          case Failure(t) =>
            log.error(t, s"rejecting channel with invalid funding tx: ${w.tx.bin}")
            throw InvalidFundingTx(d.channelId)
        }
      case _ => () // in the dual-funding case, we have already verified the funding tx
    }
    context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, w.tx))
    d.commitments.all.find(_.fundingTxId == w.tx.txid) match {
      case Some(c) =>
        val scid = RealShortChannelId(w.blockHeight, w.txIndex, c.fundingInput.index.toInt)
        val fundingStatus = ConfirmedFundingTx(w.tx.txIn.map(_.outPoint), w.tx.txOut(c.fundingInput.index.toInt), scid, d.commitments.localFundingSigs(w.tx.txid), d.commitments.liquidityPurchase(w.tx.txid))
        // When a splice transaction confirms, it double-spends all the commitment transactions that only applied to the
        // previous funding transaction. Our peer cannot publish the corresponding revoked commitments anymore, so we can
        // clean-up the htlc data that we were storing for the matching penalty transactions.
        context.system.eventStream.publish(RevokedHtlcInfoCleaner.ForgetHtlcInfos(d.channelId, beforeCommitIndex = c.firstRemoteCommitIndex))
        val lastAnnouncedFundingTxId_opt = d match {
          case d: DATA_NORMAL => d.lastAnnouncedFundingTxId_opt
          case _ => None
        }
        d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus, lastAnnouncedFundingTxId_opt).map {
          case (commitments1, commitment) =>
            // First of all, we watch the funding tx that is now confirmed.
            // Children splice transactions may already spend that confirmed funding transaction.
            val spliceSpendingTxs = commitments1.all.collect { case c if c.fundingTxIndex == commitment.fundingTxIndex + 1 => c.fundingTxId }
            watchFundingSpent(commitment, additionalKnownSpendingTxs = spliceSpendingTxs.toSet, None)
            // In the dual-funding/splicing case we can forget all other transactions (RBF attempts), they have been
            // double-spent by the tx that just confirmed.
            val conflictingTxs = d.commitments.active // note how we use the unpruned original commitments
              .filter(c => c.fundingTxIndex == commitment.fundingTxIndex && c.fundingTxId != commitment.fundingTxId)
              .map(_.localFundingStatus).collect { case fundingTx: DualFundedUnconfirmedFundingTx => fundingTx.sharedTx }
            conflictingTxs.foreach(tx => blockchain ! UnwatchTxConfirmed(tx.txId))
            rollbackDualFundingTxs(conflictingTxs)
            (commitments1, commitment)
        }
      case None => Left(d.commitments)
    }
  }

  def createShortIdAliases(channelId: ByteVector32): ShortIdAliases = {
    // The alias will be used in our peer's channel_update message, the goal is to be able to use our channel as soon
    // as it reaches the NORMAL state, before it is announced on the network.
    val aliases = ShortIdAliases(ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
    context.system.eventStream.publish(ShortChannelIdAssigned(self, channelId, None, aliases, remoteNodeId))
    aliases
  }

  def createChannelReady(aliases: ShortIdAliases, commitments: Commitments): ChannelReady = {
    val params = commitments.channelParams
    val nextPerCommitmentPoint = channelKeys.commitmentPoint(1)
    // Note that we always send our local alias, even if it isn't explicitly supported, that's an optional TLV anyway.
    commitments.latest.commitmentFormat match {
      case _: SimpleTaprootChannelCommitmentFormat =>
        val localFundingKey = channelKeys.fundingKey(fundingTxIndex = 0)
        val nextLocalNonce = NonceGenerator.verificationNonce(commitments.latest.fundingTxId, localFundingKey, commitments.latest.remoteFundingPubKey, 1)
        ChannelReady(params.channelId, nextPerCommitmentPoint, aliases.localAlias, nextLocalNonce.publicNonce)
      case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat =>
        ChannelReady(params.channelId, nextPerCommitmentPoint, aliases.localAlias)
    }
  }

  def receiveChannelReady(aliases: ShortIdAliases, channelReady: ChannelReady, commitments: Commitments): DATA_NORMAL = {
    val aliases1 = aliases.copy(remoteAlias_opt = channelReady.alias_opt)
    aliases1.remoteAlias_opt.foreach(_ => context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, None, aliases1, remoteNodeId)))
    log.info("shortIds: real={} localAlias={} remoteAlias={}", commitments.latest.shortChannelId_opt.getOrElse("none"), aliases1.localAlias, aliases1.remoteAlias_opt.getOrElse("none"))
    // We notify that the channel is now ready to route payments.
    context.system.eventStream.publish(ChannelOpened(self, remoteNodeId, commitments.channelId))
    // We create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced.
    val scidForChannelUpdate = Helpers.scidForChannelUpdate(channelAnnouncement_opt = None, aliases1.localAlias)
    log.info("using shortChannelId={} for initial channel_update", scidForChannelUpdate)
    val (relayFees, inboundFees_opt) = getRelayFees(nodeParams, remoteNodeId, commitments.announceChannel)
    val initialChannelUpdate = Helpers.channelUpdate(nodeParams, scidForChannelUpdate, commitments, relayFees, enable = true, inboundFees_opt)
    // We need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network.
    context.system.scheduler.scheduleWithFixedDelay(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
    val commitments1 = commitments.copy(
      // Set the remote status for all initial funding commitments to Locked. If there are RBF attempts, only one can be confirmed locally.
      active = commitments.active.map {
        case c if c.fundingTxIndex == 0 => c.copy(remoteFundingStatus = RemoteFundingStatus.Locked)
        case c => c
      },
      remoteNextCommitInfo = Right(channelReady.nextPerCommitmentPoint)
    )
    channelReady.nextCommitNonce_opt.foreach(nonce => remoteNextCommitNonces = remoteNextCommitNonces + (commitments.latest.fundingTxId -> nonce))
    peer ! ChannelReadyForPayments(self, remoteNodeId, commitments.channelId, fundingTxIndex = 0)
    DATA_NORMAL(commitments1, aliases1, None, initialChannelUpdate, SpliceStatus.NoSplice, None, None, None)
  }

  def delayEarlyAnnouncementSigs(remoteAnnSigs: AnnouncementSignatures): Unit = {
    log.debug("received remote announcement signatures, delaying")
    // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
    // note: no need to persist their message, in case of disconnection they will resend it
    context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
  }

}
