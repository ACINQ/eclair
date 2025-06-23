/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.balance

import fr.acinq.bitcoin.scalacompat.{BlockId, Btc, ByteVector32, KotlinUtils, OutPoint, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.Utxo
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.Helpers.Closing._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.DirectedHtlc.incoming
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

object CheckBalance {

  /**
   * Helper to avoid accidental deduplication caused by the [[Set]].
   * Amounts are truncated to [[Satoshi]] because that is what would happen on-chain.
   */
  implicit class HtlcsWithSum(htlcs: Set[UpdateAddHtlc]) {
    def sumAmount: Satoshi = htlcs.toList.map(_.amountMsat.truncateToSatoshi).sum
  }

  private def mainBalance(commit: LocalCommit): Satoshi = commit.spec.toLocal.truncateToSatoshi

  /**
   * For more fine-grained analysis, we count the in-flight HTLC amounts separately from the main amounts.
   *
   * We assume that pending htlcs will all be fulfilled and thus count incoming HTLCs in our balance.
   * When HTLCs are relayed, the upstream and downstream channels will cancel each other, because the HTLC is added to
   * our balance in the upstream channel and deduced from our balance in the downstream channel (minus fees).
   *
   * If an HTLC is failed downstream, the failure is immediately propagated to the upstream channel (even if it is in
   * the middle of a force-close): the HTLC amount is thus added back to our balance in the downstream channel and
   * removed from the upstream channel, so it correctly cancels itself in the global balance. If the downstream channel
   * is force-closing, the HTLC will be considered failed only when the HTLC-timeout transaction is confirmed, at which
   * point we relay the failure upstream: the HTLC amount is removed from the upstream channel and is added to our
   * on-chain balance in the closing downstream channel.
   */
  case class MainAndHtlcBalance(toLocal: Btc = 0 sat, htlcs: Btc = 0 sat) {
    val total: Btc = toLocal + htlcs

    def addChannelBalance(commitments: Commitments): MainAndHtlcBalance = {
      // We take the last commitment into account: it's the most likely to (eventually) confirm.
      MainAndHtlcBalance(
        toLocal = this.toLocal + mainBalance(commitments.latest.localCommit),
        htlcs = this.htlcs + commitments.latest.localCommit.spec.htlcs.collect(incoming).sumAmount
      )
    }

    /** Add our balance for a confirmed local close. */
    def addLocalClose(lcp: LocalCommitPublished, recentlySpentInputs: Set[OutPoint]): MainAndHtlcBalance = {
      // If our main transaction isn't confirmed or in the mempool yet, we count it in our off-chain balance.
      // Once it confirms or appears in the mempool, it will be included in our on-chain balance, so we ignore it in our off-chain balance.
      val additionalToLocal = lcp.localOutput_opt match {
        case Some(outpoint) if !lcp.irrevocablySpent.contains(outpoint) && !recentlySpentInputs.contains(outpoint) => lcp.commitTx.txOut(outpoint.index.toInt).amount
        case _ => 0 sat
      }
      val additionalHtlcs = lcp.htlcOutputs.map { outpoint =>
        val htlcAmount = lcp.commitTx.txOut(outpoint.index.toInt).amount
        lcp.irrevocablySpent.get(outpoint) match {
          case Some(spendingTx) =>
            // If the HTLC was spent by us, there will be an entry in our 3rd-stage transactions.
            // Otherwise it was spent by the remote and we don't have anything to add to our balance.
            val delayedHtlcOutpoint = OutPoint(spendingTx.txid, 0)
            val htlcSpentByUs = lcp.htlcDelayedOutputs.contains(delayedHtlcOutpoint)
            // If our 3rd-stage transaction isn't confirmed yet, we should count it in our off-chain balance.
            // Once confirmed or seen in the mempool, we should ignore it since it will appear in our on-chain balance.
            val htlcDelayedPending = !lcp.irrevocablySpent.contains(delayedHtlcOutpoint)
            if (htlcSpentByUs && htlcDelayedPending && !recentlySpentInputs.contains(delayedHtlcOutpoint)) htlcAmount else 0 sat
          case None =>
            // We assume that HTLCs will be fulfilled, so we only count incoming HTLCs in our off-chain balance.
            if (lcp.incomingHtlcs.contains(outpoint)) htlcAmount else 0 sat
        }
      }.sum
      MainAndHtlcBalance(toLocal = toLocal + additionalToLocal, htlcs = htlcs + additionalHtlcs)
    }

    /** Add our balance for a confirmed remote close. */
    def addRemoteClose(rcp: RemoteCommitPublished, recentlySpentInputs: Set[OutPoint]): MainAndHtlcBalance = {
      // If our main transaction isn't confirmed or in the mempool yet, we count it in our off-chain balance.
      // Once it confirms or appears in the mempool, it will be included in our on-chain balance, so we ignore it in our off-chain balance.
      val additionalToLocal = rcp.localOutput_opt match {
        case Some(outpoint) if !rcp.irrevocablySpent.contains(outpoint) && !recentlySpentInputs.contains(outpoint) => rcp.commitTx.txOut(outpoint.index.toInt).amount
        case _ => 0 sat
      }
      // If HTLC transactions are confirmed, they will appear in our on-chain balance if we were the one to claim them.
      // We only need to include incoming HTLCs that haven't been claimed yet (since we assume that they will be fulfilled).
      // Note that it is their commitment, so incoming/outgoing are inverted.
      val additionalHtlcs = rcp.incomingHtlcs.keys.map {
        case outpoint if !rcp.irrevocablySpent.contains(outpoint) && !recentlySpentInputs.contains(outpoint) => rcp.commitTx.txOut(outpoint.index.toInt).amount
        case _ => 0 sat
      }.sum
      MainAndHtlcBalance(toLocal = toLocal + additionalToLocal, htlcs = htlcs + additionalHtlcs)
    }

    /** Add our balance for a confirmed revoked close. */
    def addRevokedClose(rvk: RevokedCommitPublished, recentlySpentInputs: Set[OutPoint]): MainAndHtlcBalance = {
      // If our main transaction isn't confirmed or in the mempool yet, we count it in our off-chain balance.
      // Once it confirms or appears in the mempool, it will be included in our on-chain balance, so we ignore it in our off-chain balance.
      // We do the same thing for our main penalty transaction claiming their main output.
      val additionalToLocal = rvk.localOutput_opt match {
        case Some(outpoint) if !rvk.irrevocablySpent.contains(outpoint) && !recentlySpentInputs.contains(outpoint) => rvk.commitTx.txOut(outpoint.index.toInt).amount
        case _ => 0 sat
      }
      val additionalToRemote = rvk.remoteOutput_opt match {
        case Some(outpoint) if !rvk.irrevocablySpent.contains(outpoint) && !recentlySpentInputs.contains(outpoint) => rvk.commitTx.txOut(outpoint.index.toInt).amount
        case _ => 0 sat
      }
      val additionalHtlcs = rvk.htlcOutputs.map(htlcOutpoint => {
        val htlcAmount = rvk.commitTx.txOut(htlcOutpoint.index.toInt).amount
        rvk.irrevocablySpent.get(htlcOutpoint) match {
          case Some(spendingTx) =>
            // The spending transaction may claim a batch of HTLCs at once, we only look at the current one.
            spendingTx.txIn.zipWithIndex.collectFirst { case (txIn, i) if txIn.outPoint == htlcOutpoint => i } match {
              case Some(outputIndex) =>
                // If they managed to get their HTLC transaction confirmed, we published an HTLC-delayed penalty transaction.
                val delayedHtlcOutpoint = OutPoint(spendingTx.txid, outputIndex)
                val htlcSpentByThem = rvk.htlcDelayedOutputs.contains(delayedHtlcOutpoint)
                // If our 3rd-stage transaction isn't confirmed yet, we should count it in our off-chain balance.
                // Once confirmed, we should ignore it since it will appear in our on-chain balance.
                val htlcDelayedPending = !rvk.irrevocablySpent.contains(delayedHtlcOutpoint)
                // Note that if the HTLC output was spent by us, it should appear in our on-chain balance, so we don't
                // count it here.
                if (htlcSpentByThem && htlcDelayedPending && !recentlySpentInputs.contains(delayedHtlcOutpoint)) htlcAmount else 0 sat
              case None =>
                // This should never happen unless our data is corrupted.
                0 sat
            }
          // We ignore this HTLC if it's already included in our on-chain balance.
          case None if recentlySpentInputs.contains(htlcOutpoint) => 0 sat
          // We assume that our penalty transaction will confirm before their HTLC transaction.
          case None => htlcAmount
        }
      }).sum
      MainAndHtlcBalance(toLocal = toLocal + additionalToLocal + additionalToRemote, htlcs = htlcs + additionalHtlcs)
    }
  }

  /**
   * The overall balance among all channels in all states.
   */
  case class OffChainBalance(waitForFundingConfirmed: Btc = 0.sat,
                             waitForChannelReady: Btc = 0.sat,
                             normal: MainAndHtlcBalance = MainAndHtlcBalance(),
                             shutdown: MainAndHtlcBalance = MainAndHtlcBalance(),
                             negotiating: MainAndHtlcBalance = MainAndHtlcBalance(),
                             closing: MainAndHtlcBalance = MainAndHtlcBalance(),
                             waitForPublishFutureCommitment: Btc = 0.sat) {
    val total: Btc = waitForFundingConfirmed + waitForChannelReady + normal.total + shutdown.total + negotiating.total + closing.total + waitForPublishFutureCommitment

    def addChannelBalance(channel: PersistentChannelData, recentlySpentInputs: Set[OutPoint]): OffChainBalance = channel match {
      case d: DATA_WAIT_FOR_FUNDING_CONFIRMED => this.copy(waitForFundingConfirmed = this.waitForFundingConfirmed + mainBalance(d.commitments.latest.localCommit))
      case d: DATA_WAIT_FOR_CHANNEL_READY => this.copy(waitForChannelReady = this.waitForChannelReady + mainBalance(d.commitments.latest.localCommit))
      case _: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED => this // we ignore our balance from unsigned commitments
      case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => this.copy(waitForFundingConfirmed = this.waitForFundingConfirmed + mainBalance(d.commitments.latest.localCommit))
      case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => this.copy(waitForChannelReady = this.waitForChannelReady + mainBalance(d.commitments.latest.localCommit))
      case d: DATA_NORMAL => this.copy(normal = this.normal.addChannelBalance(d.commitments))
      case d: DATA_SHUTDOWN => this.copy(shutdown = this.shutdown.addChannelBalance(d.commitments))
      // If one of our closing transactions is in the mempool or recently confirmed, and thus included in our on-chain
      // balance, we ignore this channel in our off-chain balance to avoid counting it twice.
      case d: DATA_NEGOTIATING if recentlySpentInputs.contains(d.commitments.latest.fundingInput) => this
      case d: DATA_NEGOTIATING_SIMPLE if recentlySpentInputs.contains(d.commitments.latest.fundingInput) => this
      // Otherwise, that means the closing transactions aren't in the mempool yet, so we include our off-chain balance.
      case d: DATA_NEGOTIATING => this.copy(negotiating = this.negotiating.addChannelBalance(d.commitments))
      case d: DATA_NEGOTIATING_SIMPLE => this.copy(negotiating = this.negotiating.addChannelBalance(d.commitments))
      case d: DATA_CLOSING =>
        Closing.isClosingTypeAlreadyKnown(d) match {
          // A mutual close transaction is confirmed: the channel should transition to the CLOSED state.
          // We can ignore it as our channel balance should appear in our on-chain balance.
          case Some(_: MutualClose) => this
          // A commitment transaction is confirmed: we compute the channel balance that we expect to get back on-chain.
          case Some(c: LocalClose) => this.copy(closing = this.closing.addLocalClose(c.localCommitPublished, recentlySpentInputs))
          case Some(c: CurrentRemoteClose) => this.copy(closing = this.closing.addRemoteClose(c.remoteCommitPublished, recentlySpentInputs))
          case Some(c: NextRemoteClose) => this.copy(closing = this.closing.addRemoteClose(c.remoteCommitPublished, recentlySpentInputs))
          case Some(c: RevokedClose) => this.copy(closing = this.closing.addRevokedClose(c.revokedCommitPublished, recentlySpentInputs))
          // In the recovery case, we can only claim our main output, HTLC outputs are lost.
          // Once our main transaction confirms, the channel will transition to the CLOSED state and our channel funds
          // will appear in our on-chain balance (minus on-chain fees).
          case Some(c: RecoveryClose) => c.remoteCommitPublished.localOutput_opt match {
            case Some(localOutput) =>
              val localBalance = c.remoteCommitPublished.commitTx.txOut(localOutput.index.toInt).amount
              this.copy(closing = this.closing.copy(toLocal = this.closing.toLocal + localBalance))
            case None => this
          }
          // We don't know yet which type of closing will confirm on-chain, so we use our default off-chain balance.
          case None => this.copy(closing = this.closing.addChannelBalance(d.commitments))
        }
      case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => this.copy(waitForPublishFutureCommitment = this.waitForPublishFutureCommitment + mainBalance(d.commitments.latest.localCommit))
    }
  }

  /**
   * Compute the overall balance for a list of channels.
   *
   * Assumptions:
   * - If the funding transaction isn't confirmed yet, we simply take our (future) local amount into account.
   * - If the funding transaction is confirmed, we take our main balance and pending HTLCs into account.
   * - In the [[CLOSING]] state: while closing transactions are unconfirmed, we use the channel amounts, which don't
   * take on-chain fees into account. Once closing transactions confirm, we ignore the corresponding channel amounts,
   * the final amounts are included in our on-chain balance, which takes into account the on-chain fees paid.
   */
  def computeOffChainBalance(channels: Iterable[PersistentChannelData], recentlySpentInputs: Set[OutPoint]): OffChainBalance = {
    channels.foldLeft(OffChainBalance()) { case (balance, channel) => balance.addChannelBalance(channel, recentlySpentInputs) }
  }

  case class DetailedOnChainBalance(deeplyConfirmed: Map[OutPoint, Btc] = Map.empty, recentlyConfirmed: Map[OutPoint, Btc] = Map.empty, unconfirmed: Map[OutPoint, Btc] = Map.empty, utxos: Seq[Utxo], recentlySpentInputs: Set[OutPoint]) {
    val totalDeeplyConfirmed: Btc = deeplyConfirmed.values.map(_.toSatoshi).sum
    val totalRecentlyConfirmed: Btc = recentlyConfirmed.values.map(_.toSatoshi).sum
    val totalUnconfirmed: Btc = unconfirmed.values.map(_.toSatoshi).sum
    val total: Btc = totalDeeplyConfirmed + totalRecentlyConfirmed + totalUnconfirmed
  }

  /**
   * Compute our on-chain balance: we distinguish unconfirmed transactions (which may be RBF-ed or even double-spent),
   * recently confirmed transactions (which aren't yet settled in our off-chain balance until they've reached min-depth)
   * and deeply confirmed transactions (which are settled in our off-chain balance).
   *
   * Note that this may create temporary glitches when doing 0-conf splices, which will appear in the off-chain balance
   * immediately but will only be correctly accounted for in our on-chain balance after being deeply confirmed. Those
   * cases can be detected by looking at the unconfirmed and recently confirmed on-chain balance.
   */
  def computeOnChainBalance(bitcoinClient: BitcoinCoreClient, minDepth: Int)(implicit ec: ExecutionContext): Future[DetailedOnChainBalance] = for {
    utxos <- bitcoinClient.listUnspent()
    unconfirmedRecentlySpentInputs <- getUnconfirmedRecentlySpentInputs(bitcoinClient, utxos)
    confirmedRecentlySpentInputs <- getConfirmedRecentlySpentInputs(bitcoinClient, minDepth)
    detailed = utxos.foldLeft(DetailedOnChainBalance(utxos = utxos, recentlySpentInputs = unconfirmedRecentlySpentInputs ++ confirmedRecentlySpentInputs)) {
      case (total, utxo) if utxo.confirmations == 0 => total.copy(unconfirmed = total.unconfirmed + (utxo.outPoint -> utxo.amount))
      case (total, utxo) if utxo.confirmations < minDepth => total.copy(recentlyConfirmed = total.recentlyConfirmed + (utxo.outPoint -> utxo.amount))
      case (total, utxo) => total.copy(deeplyConfirmed = total.deeplyConfirmed + (utxo.outPoint -> utxo.amount))
    }
  } yield detailed

  /**
   * We list utxos that were spent by our unconfirmed transactions: they will be included in our on-chain balance, and
   * thus need to be ignored from our off-chain balance.
   */
  private def getUnconfirmedRecentlySpentInputs(bitcoinClient: BitcoinCoreClient, utxos: Seq[Utxo])(implicit ec: ExecutionContext): Future[Set[OutPoint]] = {
    val unconfirmedTxs = utxos.filter(_.confirmations == 0).map(_.txid).toSet
    Future.sequence(unconfirmedTxs.map(txId => bitcoinClient.getTransaction(txId).map(Some(_)).recover { case _ => None })).map(_.flatten.flatMap(_.txIn.map(_.outPoint)))
  }

  /**
   * We list utxos that were spent in recent blocks, up to min-depth: those utxos will be included in our on-chain
   * balance if they belong to us, and thus need to be ignored from our off-chain balance.
   *
   * Note that since we may spend our inputs before they reach min-depth (e.g. to fund unrelated channels), some of
   * those utxos don't appear in our on-chain balance, which is fine since we already spent them! In that case, they
   * must not be counted in our off-chain balance either, since we've used them already. This is why we cannot rely
   * only on listUnspent to deduplicate utxos between on-chain and off-chain balances.
   */
  private def getConfirmedRecentlySpentInputs(bitcoinClient: BitcoinCoreClient, minDepth: Int)(implicit ec: ExecutionContext): Future[Set[OutPoint]] = for {
    currentBlockHeight <- bitcoinClient.getBlockHeight()
    currentBlockId <- bitcoinClient.getBlockId(currentBlockHeight.toInt)
    // We look one block past our min-depth in case there's a race with a new block.
    spentInputs <- scanPastBlocks(bitcoinClient, currentBlockId, Set.empty, remaining = minDepth + 1)
  } yield spentInputs

  private def scanPastBlocks(bitcoinClient: BitcoinCoreClient, blockId: BlockId, spentInputs: Set[OutPoint], remaining: Int)(implicit ec: ExecutionContext): Future[Set[OutPoint]] = {
    bitcoinClient.getBlock(blockId).flatMap(block => {
      val spentInputs1 = spentInputs ++ block.tx.asScala.flatMap(_.txIn.asScala.map(_.outPoint)).map(KotlinUtils.kmp2scala).toSet
      if (remaining > 0) {
        scanPastBlocks(bitcoinClient, BlockId(KotlinUtils.kmp2scala(block.header.hashPreviousBlock)), spentInputs1, remaining - 1)
      } else {
        Future.successful(spentInputs1)
      }
    })
  }

  case class GlobalBalance(onChain: DetailedOnChainBalance, offChain: OffChainBalance, channels: Map[ByteVector32, PersistentChannelData]) {
    val total: Btc = onChain.total + offChain.total
  }

  def computeGlobalBalance(channels: Map[ByteVector32, PersistentChannelData], bitcoinClient: BitcoinCoreClient, minDepth: Int)(implicit ec: ExecutionContext): Future[GlobalBalance] = for {
    onChain <- CheckBalance.computeOnChainBalance(bitcoinClient, minDepth)
    offChain = CheckBalance.computeOffChainBalance(channels.values, onChain.recentlySpentInputs)
  } yield GlobalBalance(onChain, offChain, channels)

}
