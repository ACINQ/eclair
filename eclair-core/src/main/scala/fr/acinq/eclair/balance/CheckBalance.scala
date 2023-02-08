package fr.acinq.eclair.balance

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.Helpers.Closing.{CurrentRemoteClose, LocalClose, NextRemoteClose, RemoteClose}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.Databases
import fr.acinq.eclair.transactions.DirectedHtlc.{incoming, outgoing}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{ClaimHtlcSuccessTx, ClaimHtlcTimeoutTx, HtlcSuccessTx, HtlcTimeoutTx}
import fr.acinq.eclair.wire.protocol.{UpdateAddHtlc, UpdateFulfillHtlc}

import scala.concurrent.{ExecutionContext, Future}

object CheckBalance {

  /**
   * Helper to avoid accidental deduplication caused by the [[Set]]
   * Amounts are truncated to the [[Satoshi]] because that is what would happen on-chain.
   */
  implicit class HtlcsWithSum(htlcs: Set[UpdateAddHtlc]) {
    def sumAmount: Satoshi = htlcs.toList.map(_.amountMsat.truncateToSatoshi).sum
  }

  /** if local has preimage of an incoming htlc, then we know it will get the funds */
  def localHasPreimage(c: CommitmentChanges, htlcId: Long): Boolean = {
    c.localChanges.all.collectFirst { case u: UpdateFulfillHtlc if u.id == htlcId => true }.isDefined
  }

  /** if remote proved it had the preimage of an outgoing htlc, then we know it won't timeout */
  def remoteHasPreimage(c: CommitmentChanges, htlcId: Long): Boolean = {
    c.remoteChanges.all.collectFirst { case u: UpdateFulfillHtlc if u.id == htlcId => true }.isDefined
  }

  /**
   * For more fine-grained analysis, we count the in-flight amounts separately from the main amounts.
   *
   * The base assumption regarding htlcs is that they will all timeout. That means that we ignore incoming htlcs (except
   * if we know the preimage), and we count outgoing htlcs in our balance.
   */
  case class MainAndHtlcBalance(toLocal: Btc = 0.sat, htlcs: Btc = 0.sat) {
    val total: Btc = toLocal + htlcs
  }

  /**
   * In the closing state some transactions may be published or even confirmed. They will be taken into account if we
   * do a `bitcoin-cli getbalance` and we don't want to count them twice.
   *
   * That's why we keep track of the id of each transaction that pays us any amount. It allows us to double check from
   * bitcoin core and remove any published transaction.
   */
  case class PossiblyPublishedMainBalance(toLocal: Map[ByteVector32, Btc] = Map.empty) {
    val total: Btc = toLocal.values.map(_.toSatoshi).sum
  }

  case class PossiblyPublishedMainAndHtlcBalance(toLocal: Map[ByteVector32, Btc] = Map.empty, htlcs: Map[ByteVector32, Btc] = Map.empty, htlcsUnpublished: Btc = 0.sat) {
    val totalToLocal: Btc = toLocal.values.map(_.toSatoshi).sum
    val totalHtlcs: Btc = htlcs.values.map(_.toSatoshi).sum
    val total: Btc = totalToLocal + totalHtlcs + htlcsUnpublished
  }

  /**
   * Unless they got evicted, mutual close transactions will also appear in the on-chain balance and will disappear
   * from here after on pruning.
   */
  case class ClosingBalance(localCloseBalance: PossiblyPublishedMainAndHtlcBalance = PossiblyPublishedMainAndHtlcBalance(),
                            remoteCloseBalance: PossiblyPublishedMainAndHtlcBalance = PossiblyPublishedMainAndHtlcBalance(),
                            mutualCloseBalance: PossiblyPublishedMainBalance = PossiblyPublishedMainBalance(),
                            unknownCloseBalance: MainAndHtlcBalance = MainAndHtlcBalance()) {

    val total: Btc = localCloseBalance.total + remoteCloseBalance.total + mutualCloseBalance.total + unknownCloseBalance.total
  }

  /**
   * The overall balance among all channels in all states.
   */
  case class OffChainBalance(waitForFundingConfirmed: Btc = 0.sat,
                             waitForChannelReady: Btc = 0.sat,
                             normal: MainAndHtlcBalance = MainAndHtlcBalance(),
                             shutdown: MainAndHtlcBalance = MainAndHtlcBalance(),
                             negotiating: Btc = 0.sat,
                             closing: ClosingBalance = ClosingBalance(),
                             waitForPublishFutureCommitment: Btc = 0.sat) {
    val total: Btc = waitForFundingConfirmed + waitForChannelReady + normal.total + shutdown.total + negotiating + closing.total + waitForPublishFutureCommitment
  }

  def updateMainBalance(localCommit: LocalCommit): Btc => Btc = { v: Btc =>
    val toLocal = localCommit.spec.toLocal.truncateToSatoshi
    v + toLocal
  }

  def updateMainAndHtlcBalance(c: Commitments, knownPreimages: Set[(ByteVector32, Long)]): MainAndHtlcBalance => MainAndHtlcBalance = { b: MainAndHtlcBalance =>
    // We take the last commitment into account: it's the most likely to (eventually) confirm.
    val commitment = c.latest
    val toLocal = commitment.localCommit.spec.toLocal.truncateToSatoshi
    // we only count htlcs in if we know the preimage
    val htlcIn = commitment.localCommit.spec.htlcs.collect(incoming)
      .filter(add => knownPreimages.contains((add.channelId, add.id)) || localHasPreimage(c.changes, add.id))
      .sumAmount
    val htlcOut = commitment.localCommit.spec.htlcs.collect(outgoing).sumAmount
    b.modify(_.toLocal).using(_ + toLocal)
      .modify(_.htlcs).using(_ + htlcIn + htlcOut)
  }

  def updatePossiblyPublishedBalance(b1: PossiblyPublishedMainAndHtlcBalance): PossiblyPublishedMainAndHtlcBalance => PossiblyPublishedMainAndHtlcBalance = { b: PossiblyPublishedMainAndHtlcBalance =>
    b.modify(_.toLocal).using(_ ++ b1.toLocal)
      .modify(_.htlcs).using(_ ++ b1.htlcs)
      .modify(_.htlcsUnpublished).using(_ + b1.htlcsUnpublished)
  }

  def computeLocalCloseBalance(changes: CommitmentChanges, l: LocalClose, originChannels: Map[Long, Origin], knownPreimages: Set[(ByteVector32, Long)]): PossiblyPublishedMainAndHtlcBalance = {
    import l._
    val toLocal = localCommitPublished.claimMainDelayedOutputTx.toSeq.map(c => c.tx.txid -> c.tx.txOut.head.amount.toBtc).toMap
    // incoming htlcs for which we have a preimage and the to-local delay has expired: we have published a claim tx that pays directly to our wallet
    val htlcsInOnChain = localCommitPublished.htlcTxs.values.flatten.collect { case htlcTx: HtlcSuccessTx => htlcTx }
      .filter(htlcTx => localCommitPublished.claimHtlcDelayedTxs.exists(_.input.outPoint.txid == htlcTx.tx.txid))
      .map(_.htlcId)
      .toSet
    // outgoing htlcs that have timed out and the to-local delay has expired: we have published a claim tx that pays directly to our wallet
    val htlcsOutOnChain = localCommitPublished.htlcTxs.values.flatten.collect { case htlcTx: HtlcTimeoutTx => htlcTx }
      .filter(htlcTx => localCommitPublished.claimHtlcDelayedTxs.exists(_.input.outPoint.txid == htlcTx.tx.txid))
      .map(_.htlcId)
      .toSet
    // incoming htlcs for which we have a preimage but we are still waiting for the to-local delay
    val htlcIn = localCommit.spec.htlcs.collect(incoming)
      .filterNot(htlc => htlcsInOnChain.contains(htlc.id)) // we filter the htlc that already pay us on-chain
      .filter(add => knownPreimages.contains((add.channelId, add.id)) || localHasPreimage(changes, add.id))
      .sumAmount
    // outgoing htlcs for which remote didn't prove it had the preimage are expected to time out if they were relayed,
    // and succeed if they were sent from this node
    val htlcOut = localCommit.spec.htlcs.collect(outgoing)
      .filterNot(htlc => htlcsOutOnChain.contains(htlc.id)) // we filter the htlc that already pay us on-chain
      .filterNot(htlc => originChannels.get(htlc.id).exists(_.isInstanceOf[Origin.Local]))
      .filterNot(htlc => remoteHasPreimage(changes, htlc.id))
      .sumAmount
    // all claim txs have possibly been published
    val htlcs = localCommitPublished.claimHtlcDelayedTxs
      .map(c => c.tx.txid -> c.tx.txOut.head.amount.toBtc).toMap
    PossiblyPublishedMainAndHtlcBalance(
      toLocal = toLocal,
      htlcs = htlcs,
      htlcsUnpublished = htlcIn + htlcOut
    )
  }

  def computeRemoteCloseBalance(c: Commitments, r: RemoteClose, knownPreimages: Set[(ByteVector32, Long)]): PossiblyPublishedMainAndHtlcBalance = {
    import r._
    val toLocal = if (c.params.channelFeatures.paysDirectlyToWallet) {
      // If static remote key is enabled, the commit tx directly pays to our wallet
      // We use the pubkeyscript to retrieve our output
      val finalScriptPubKey = Script.write(Script.pay2wpkh(c.params.localParams.walletStaticPaymentBasepoint.get))
      Transactions.findPubKeyScriptIndex(remoteCommitPublished.commitTx, finalScriptPubKey) match {
        case Right(outputIndex) => Map(remoteCommitPublished.commitTx.txid -> remoteCommitPublished.commitTx.txOut(outputIndex).amount.toBtc)
        case _ => Map.empty[ByteVector32, Btc] // either we don't have an output (below dust), or we have used a non-default pubkey script
      }
    } else {
      remoteCommitPublished.claimMainOutputTx.toSeq.map(c => c.tx.txid -> c.tx.txOut.head.amount.toBtc).toMap
    }
    // incoming htlcs for which we have a preimage: we have published a claim tx that pays directly to our wallet
    val htlcsInOnChain = remoteCommitPublished.claimHtlcTxs.values.flatten.collect { case htlcTx: ClaimHtlcSuccessTx => htlcTx }
      .map(_.htlcId)
      .toSet
    // outgoing htlcs that have timed out: we have published a claim tx that pays directly to our wallet
    val htlcsOutOnChain = remoteCommitPublished.claimHtlcTxs.values.flatten.collect { case htlcTx: ClaimHtlcTimeoutTx => htlcTx }
      .map(_.htlcId)
      .toSet
    // incoming htlcs for which we have a preimage
    val htlcIn = remoteCommit.spec.htlcs.collect(outgoing)
      .filter(add => knownPreimages.contains((add.channelId, add.id)) || localHasPreimage(c.changes, add.id))
      .filterNot(htlc => htlcsInOnChain.contains(htlc.id)) // we filter the htlc that already pay us on-chain
      .sumAmount
    // all outgoing htlcs for which remote didn't prove it had the preimage are expected to time out
    val htlcOut = remoteCommit.spec.htlcs.collect(incoming)
      .filterNot(htlc => htlcsOutOnChain.contains(htlc.id)) // we filter the htlc that already pay us on-chain
      .filterNot(htlc => remoteHasPreimage(c.changes, htlc.id))
      .sumAmount
    // all claim txs have possibly been published
    val htlcs = remoteCommitPublished.claimHtlcTxs.values.flatten
      .map(c => c.tx.txid -> c.tx.txOut.head.amount.toBtc).toMap
    PossiblyPublishedMainAndHtlcBalance(
      toLocal = toLocal,
      htlcs = htlcs,
      htlcsUnpublished = htlcIn + htlcOut
    )
  }

  /**
   * Compute the overall balance a list of channels.
   *
   * Assumptions:
   * - If the commitment transaction hasn't been published, we simply take our local amount (and htlc amount in states
   * where they may exist, namely [[NORMAL]] and [[SHUTDOWN]]).
   * - In [[CLOSING]] state:
   *   - If we know for sure we are in a mutual close scenario, then we don't count the amount, because the tx will
   *     already have been published.
   *   - If we know for sure we are in a local, then we take the amounts based on the outputs of
   *     the transactions, whether delayed or not. This ensures that mining fees are taken into account.
   *   - If we have detected that a remote commit was published, then we assume the closing type will be remote, even
   *     it is not yet confirmed. Like for local commits, we take amounts based on outputs of transactions.
   *   - In the other cases, we simply take our local amount
   *   - TODO?: we disregard anchor outputs
   */
  def computeOffChainBalance(channels: Iterable[PersistentChannelData], knownPreimages: Set[(ByteVector32, Long)]): OffChainBalance = {
    channels
      .foldLeft(OffChainBalance()) {
        case (r, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => r.modify(_.waitForFundingConfirmed).using(updateMainBalance(d.commitments.latest.localCommit))
        case (r, d: DATA_WAIT_FOR_CHANNEL_READY) => r.modify(_.waitForChannelReady).using(updateMainBalance(d.commitments.latest.localCommit))
        case (r, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => r.modify(_.waitForFundingConfirmed).using(updateMainBalance(d.commitments.latest.localCommit))
        case (r, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) => r.modify(_.waitForChannelReady).using(updateMainBalance(d.commitments.latest.localCommit))
        case (r, d: DATA_NORMAL) => r.modify(_.normal).using(updateMainAndHtlcBalance(d.commitments, knownPreimages))
        case (r, d: DATA_SHUTDOWN) => r.modify(_.shutdown).using(updateMainAndHtlcBalance(d.commitments, knownPreimages))
        case (r, d: DATA_NEGOTIATING) => r.modify(_.negotiating).using(updateMainBalance(d.commitments.latest.localCommit))
        case (r, d: DATA_CLOSING) =>
          Closing.isClosingTypeAlreadyKnown(d) match {
            case None if d.mutualClosePublished.nonEmpty && d.localCommitPublished.isEmpty && d.remoteCommitPublished.isEmpty && d.nextRemoteCommitPublished.isEmpty && d.revokedCommitPublished.isEmpty =>
              // There can be multiple mutual close transactions for the same channel, but most of the time there will
              // only be one. We use the last one in the list, which should be the one we have seen last in our local
              // mempool. In the worst case scenario, there are several mutual closes and the one that made it to the
              // mempool or the chain isn't the one we are keeping track of here. As a consequence the transaction won't
              // be pruned and we will count twice the amount in the global (onChain + offChain) balance, until the
              // mutual close tx gets deeply confirmed and the channel is removed.
              val mutualClose = d.mutualClosePublished.last
              val amount = mutualClose.toLocalOutput match {
                case Some(outputInfo) => outputInfo.amount
                case None =>
                  // Normally this would mean that we don't actually have an output, but due to a migration
                  // the data might not be accurate, see [[ChannelTypes0.migrateClosingTx]]
                  // As a (hackish) workaround, we use the pubkeyscript to retrieve our output
                  Transactions.findPubKeyScriptIndex(mutualClose.tx, d.finalScriptPubKey) match {
                    case Right(outputIndex) => mutualClose.tx.txOut(outputIndex).amount
                    case _ => 0.sat // either we don't have an output (below dust), or we have used a non-default pubkey script
                  }
              }
              r.modify(_.closing.mutualCloseBalance.toLocal).using(_ + (mutualClose.tx.txid -> amount))
            case Some(localClose: LocalClose) => r.modify(_.closing.localCloseBalance).using(updatePossiblyPublishedBalance(computeLocalCloseBalance(d.commitments.changes, localClose, d.commitments.originChannels, knownPreimages)))
            case _ if d.remoteCommitPublished.nonEmpty || d.nextRemoteCommitPublished.nonEmpty =>
              // We have seen the remote commit, it may or may not have been confirmed. We may have published our own
              // local commit too, which may take precedence. But if we are aware of the remote commit, it means that
              // our bitcoin core has already seen it (since it's the one who told us about it) and we make
              // the assumption that the remote commit won't be replaced by our local commit.
              val remoteClose = if (d.remoteCommitPublished.isDefined) {
                CurrentRemoteClose(d.commitments.latest.remoteCommit, d.remoteCommitPublished.get)
              } else {
                NextRemoteClose(d.commitments.latest.nextRemoteCommit_opt.get.commit, d.nextRemoteCommitPublished.get)
              }
              r.modify(_.closing.remoteCloseBalance).using(updatePossiblyPublishedBalance(computeRemoteCloseBalance(d.commitments, remoteClose, knownPreimages)))
            case _ => r.modify(_.closing.unknownCloseBalance).using(updateMainAndHtlcBalance(d.commitments, knownPreimages))
          }
        case (r, d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => r.modify(_.waitForPublishFutureCommitment).using(updateMainBalance(d.commitments.latest.localCommit))
      }
  }

  /**
   * Query bitcoin core to prune all amounts related to transactions that have already been published
   */
  def prunePublishedTransactions(br: OffChainBalance, bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): Future[OffChainBalance] = {
    for {
      txs: Iterable[Option[(ByteVector32, Int)]] <- Future.sequence((br.closing.localCloseBalance.toLocal.keys ++
        br.closing.localCloseBalance.htlcs.keys ++
        br.closing.remoteCloseBalance.toLocal.keys ++
        br.closing.remoteCloseBalance.htlcs.keys ++
        br.closing.mutualCloseBalance.toLocal.keys)
        .map(txid => bitcoinClient.getTxConfirmations(txid).map(_ map { confirmations => txid -> confirmations })))
      txMap: Map[ByteVector32, Int] = txs.flatten.toMap
    } yield {
      br
        .modifyAll(
          _.closing.localCloseBalance.toLocal,
          _.closing.localCloseBalance.htlcs,
          _.closing.remoteCloseBalance.toLocal,
          _.closing.remoteCloseBalance.htlcs,
          _.closing.mutualCloseBalance.toLocal)
        .using(map => map.filterNot { case (txid, _) => txMap.contains(txid) })
    }
  }

  case class CorrectedOnChainBalance(confirmed: Btc, unconfirmed: Btc) {
    val total: Btc = confirmed + unconfirmed
  }

  private case class DetailedBalance(confirmed: Btc = 0.sat, unconfirmed: Btc = 0.sat)

  /**
   * Returns the on-chain balance, but discards the unconfirmed incoming swap-in transactions, because they may be RBF-ed.
   * Confirmed swap-in transactions are counted, because we can spend them, but we keep track of what we still owe to our
   * users.
   */
  def computeOnChainBalance(bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): Future[CorrectedOnChainBalance] = for {
    utxos <- bitcoinClient.listUnspent()
    detailed = utxos.foldLeft(DetailedBalance()) {
      case (total, utxo) if utxo.confirmations == 0 => total.modify(_.unconfirmed).using(_ + utxo.amount)
      case (total, utxo) => total.modify(_.confirmed).using(_ + utxo.amount)
    }
  } yield CorrectedOnChainBalance(detailed.confirmed, detailed.unconfirmed)

  case class GlobalBalance(onChain: CorrectedOnChainBalance, offChain: OffChainBalance) {
    val total: Btc = onChain.total + offChain.total
  }

  def computeGlobalBalance(channels: Map[ByteVector32, PersistentChannelData], db: Databases, bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): Future[GlobalBalance] = for {
    onChain <- CheckBalance.computeOnChainBalance(bitcoinClient)
    knownPreimages = db.pendingCommands.listSettlementCommands().collect { case (channelId, cmd: CMD_FULFILL_HTLC) => (channelId, cmd.id) }.toSet
    offChainRaw = CheckBalance.computeOffChainBalance(channels.values, knownPreimages)
    offChainPruned <- CheckBalance.prunePublishedTransactions(offChainRaw, bitcoinClient)
  } yield GlobalBalance(onChain, offChainPruned)

}
