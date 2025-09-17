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

package fr.acinq.eclair.channel.fsm

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, actorRefAdapter}
import akka.actor.{Actor, ActorContext, ActorRef, FSM, OneForOneStrategy, PossiblyHarmful, Props, SupervisorStrategy, typed}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Transaction, TxId}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.Commitments.PostRevocationAction
import fr.acinq.eclair.channel.Helpers.Closing.MutualClose
import fr.acinq.eclair.channel.Helpers.Syncing.SyncResult
import fr.acinq.eclair.channel.Helpers._
import fr.acinq.eclair.channel.Monitoring.Metrics.ProcessMessage
import fr.acinq.eclair.channel.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxFunder, InteractiveTxSigningSession}
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishReplaceableTx, SetChannelId}
import fr.acinq.eclair.channel.publish._
import fr.acinq.eclair.crypto.NonceGenerator
import fr.acinq.eclair.crypto.keymanager.ChannelKeys
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent.EventType
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.io.Peer.LiquidityPurchaseSigned
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.relay.Relayer.InboundFees
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentSettlingOnChain}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by PM on 20/08/2015.
 */

object Channel {

  case class RemoteRbfLimits(maxAttempts: Int, attemptDeltaBlocks: Int)

  /**
   * @param available     When the amount available to send goes below this,
   * @param maxHtlcAmount set the maximum HTLC amount to this value.
   *                      But never lower than the minimum HTLC amount, in case maxHtlcAmount is lower than the minimum
   *                      HTLC amount, the minimum HTLC amount will be used instead.
   */
  case class BalanceThreshold(available: Satoshi, maxHtlcAmount: Satoshi)

  case class ChannelConf(channelFlags: ChannelFlags,
                         dustLimit: Satoshi,
                         maxRemoteDustLimit: Satoshi,
                         htlcMinimum: MilliSatoshi,
                         maxHtlcValueInFlightMsat: MilliSatoshi,
                         maxHtlcValueInFlightPercent: Int,
                         maxAcceptedHtlcs: Int,
                         reserveToFundingRatio: Double,
                         maxReserveToFundingRatio: Double,
                         minFundingPublicSatoshis: Satoshi,
                         minFundingPrivateSatoshis: Satoshi,
                         toRemoteDelay: CltvExpiryDelta,
                         maxToLocalDelay: CltvExpiryDelta,
                         minDepth: Int,
                         expiryDelta: CltvExpiryDelta,
                         maxExpiryDelta: CltvExpiryDelta,
                         fulfillSafetyBeforeTimeout: CltvExpiryDelta,
                         minFinalExpiryDelta: CltvExpiryDelta,
                         maxRestartWatchDelay: FiniteDuration,
                         maxBlockProcessingDelay: FiniteDuration,
                         maxTxPublishRetryDelay: FiniteDuration,
                         scanPreviousBlocksDepth: Int,
                         maxChannelSpentRescanBlocks: Int,
                         unhandledExceptionStrategy: UnhandledExceptionStrategy,
                         revocationTimeout: FiniteDuration,
                         requireConfirmedInputsForDualFunding: Boolean,
                         channelOpenerWhitelist: Set[PublicKey],
                         maxPendingChannelsPerPeer: Int,
                         maxTotalPendingChannelsPrivateNodes: Int,
                         channelFundingTimeout: FiniteDuration,
                         remoteRbfLimits: RemoteRbfLimits,
                         quiescenceTimeout: FiniteDuration,
                         balanceThresholds: Seq[BalanceThreshold],
                         minTimeBetweenUpdates: FiniteDuration,
                         acceptIncomingStaticRemoteKeyChannels: Boolean) {
    require(0 <= maxHtlcValueInFlightPercent && maxHtlcValueInFlightPercent <= 100, "max-htlc-value-in-flight-percent must be between 0 and 100")
    require(balanceThresholds.sortBy(_.available) == balanceThresholds, "channel-update.balance-thresholds must be sorted by available-sat")

    def minFundingSatoshis(flags: ChannelFlags): Satoshi = if (flags.announceChannel) minFundingPublicSatoshis else minFundingPrivateSatoshis

    def maxHtlcValueInFlight(fundingAmount: Satoshi, unlimited: Boolean): UInt64 = {
      if (unlimited) {
        // We don't want to impose limits on the amount in flight, typically to allow fully emptying the channel.
        UInt64.MaxValue
      } else {
        // NB: when we're the initiator, we don't know yet if the remote peer will contribute to the funding amount, so
        // the percentage-based value may be underestimated. That's ok, this is a security parameter so it makes sense to
        // base it on the amount that we're contributing instead of the total funding amount.
        UInt64(maxHtlcValueInFlightMsat.min(fundingAmount * maxHtlcValueInFlightPercent / 100).toLong)
      }
    }

    def commitParams(fundingAmount: Satoshi, unlimitedMaxHtlcValueInFlight: Boolean): ProposedCommitParams = ProposedCommitParams(
      localDustLimit = dustLimit,
      localHtlcMinimum = htlcMinimum,
      localMaxHtlcValueInFlight = maxHtlcValueInFlight(fundingAmount, unlimitedMaxHtlcValueInFlight),
      localMaxAcceptedHtlcs = maxAcceptedHtlcs,
      toRemoteDelay = toRemoteDelay,
    )
  }

  trait TxPublisherFactory {
    def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): typed.ActorRef[TxPublisher.Command]
  }

  case class SimpleTxPublisherFactory(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient) extends TxPublisherFactory {
    override def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): typed.ActorRef[TxPublisher.Command] = {
      context.spawn(Behaviors.supervise(TxPublisher(nodeParams, remoteNodeId, TxPublisher.SimpleChildFactory(nodeParams, bitcoinClient))).onFailure(typed.SupervisorStrategy.restart), "tx-publisher")
    }
  }

  def props(nodeParams: NodeParams, channelKeys: ChannelKeys, wallet: OnChainChannelFunder with OnChainPubkeyCache, remoteNodeId: PublicKey, blockchain: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, txPublisherFactory: TxPublisherFactory): Props =
    Props(new Channel(nodeParams, channelKeys, wallet, remoteNodeId, blockchain, relayer, txPublisherFactory))

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
  val MAX_FUNDING_WITHOUT_WUMBO: Satoshi = 16777216 sat // = 2^24
  val MAX_ACCEPTED_HTLCS = 483

  // We may need to rely on our peer's commit tx in certain cases (backup/restore) so we must ensure their transactions
  // can propagate through the bitcoin network (assuming bitcoin core nodes with default policies).
  // The various dust limits enforced by the bitcoin network are summarized here:
  // https://github.com/lightningnetwork/lightning-rfc/blob/master/03-transactions.md#dust-limits
  // A dust limit of 354 sat ensures all segwit outputs will relay with default relay policies.
  val MIN_DUST_LIMIT: Satoshi = 354 sat

  // we won't exchange more than this many signatures when negotiating the closing fee
  val MAX_NEGOTIATION_ITERATIONS = 20

  val MIN_CLTV_EXPIRY_DELTA: CltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_CLTV_EXPIRY_DELTA

  // as a non-initiator, we will wait that many blocks for the funding tx to confirm (initiator will rely on the funding tx being double-spent)
  val FUNDING_TIMEOUT_FUNDEE = 2016
  // when using dual-funding, the initiator has the ability to RBF, so we can use a shorter timeout
  val DUAL_FUNDING_TIMEOUT_NON_INITIATOR = 720

  // pruning occurs if no new update has been received in two weeks (BOLT 7)
  val REFRESH_CHANNEL_UPDATE_INTERVAL: FiniteDuration = 10 days

  case class BroadcastChannelUpdate(reason: BroadcastReason)

  // @formatter:off
  sealed trait BroadcastReason
  case object PeriodicRefresh extends BroadcastReason
  case object Reconnected extends BroadcastReason
  private case object BalanceThresholdCrossed extends BroadcastReason

  private[channel] sealed trait BitcoinEvent extends PossiblyHarmful
  private[channel] case object BITCOIN_FUNDING_PUBLISH_FAILED extends BitcoinEvent
  private[channel] case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
  private[channel] case class BITCOIN_FUNDING_DOUBLE_SPENT(fundingTxIds: Set[TxId]) extends BitcoinEvent
  // @formatter:on

  case object TickChannelOpenTimeout

  // we will receive this message when we waited too long for a revocation for that commit number (NB: we explicitly specify the peer to allow for testing)
  case class RevocationTimeout(remoteCommitNumber: Long, peer: ActorRef)

  // we will receive this message if we waited too long to reach quiescence, or stayed quiescent for too long (NB: we explicitly specify the peer to allow for testing)
  case class QuiescenceTimeout(peer: ActorRef)

  /** We don't immediately process [[CurrentBlockHeight]] to avoid herd effects */
  case class ProcessCurrentBlockHeight(c: CurrentBlockHeight)

  // @formatter:off
  /** What do we do if we have a local unhandled exception. */
  sealed trait UnhandledExceptionStrategy
  object UnhandledExceptionStrategy {
    /** Ask our counterparty to close the channel. This may be the best choice for smaller loosely administered nodes.*/
    case object LocalClose extends UnhandledExceptionStrategy
    /** Just log an error and stop the node. May be better for larger nodes, to prevent unwanted mass force-close.*/
    case object Stop extends UnhandledExceptionStrategy
  }
  // @formatter:on

}

class Channel(val nodeParams: NodeParams, val channelKeys: ChannelKeys, val wallet: OnChainChannelFunder with OnChainPubkeyCache, val remoteNodeId: PublicKey, val blockchain: typed.ActorRef[ZmqWatcher.Command], val relayer: ActorRef, val txPublisherFactory: Channel.TxPublisherFactory)(implicit val ec: ExecutionContext = ExecutionContext.Implicits.global)
  extends FSM[ChannelState, ChannelData]
    with FSMDiagnosticActorLogging[ChannelState, ChannelData]
    with ChannelOpenSingleFunded
    with ChannelOpenDualFunded
    with CommonHandlers
    with ErrorHandlers {

  import Channel._

  // Remote nonces that must be used when signing the next remote commitment transaction (one per active commitment).
  var remoteNextCommitNonces: Map[TxId, IndividualNonce] = Map.empty

  // Closee nonces are first exchanged in shutdown messages, and replaced by a new nonce after each closing_sig.
  var localCloseeNonce_opt: Option[LocalNonce] = None
  var remoteCloseeNonce_opt: Option[IndividualNonce] = None
  // Closer nonces are randomly generated when sending our closing_complete.
  var localCloserNonces_opt: Option[CloserNonces] = None

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: akka.event.DiagnosticLoggingAdapter = diagLog

  // we assume that the peer is the channel's parent
  val peer = context.parent
  // noinspection ActorMutableStateInspection
  // the last active connection we are aware of; note that the peer manages connections and asynchronously notifies
  // the channel, which means that if we get disconnected, the previous active connection will die and some messages will
  // be sent to dead letters, before the channel gets notified of the disconnection; knowing that this will happen, we
  // choose to not make this an Option (that would be None before the first connection), and instead embrace the fact
  // that the active connection may point to dead letters at all time
  var activeConnection = context.system.deadLetters
  // we stash announcement_signatures if we receive them earlier than expected
  var announcementSigsStash = Map.empty[RealShortChannelId, AnnouncementSignatures]
  // we record the announcement_signatures messages we already sent to avoid unnecessary retransmission
  var announcementSigsSent = Set.empty[RealShortChannelId]
  // we keep track of the splice_locked we sent after channel_reestablish and it's funding tx index to avoid sending it again
  private var spliceLockedSent = Map.empty[TxId, Long]

  private def trimAnnouncementSigsStashIfNeeded(): Unit = {
    if (announcementSigsStash.size >= 10) {
      // We shouldn't store an unbounded number of announcement_signatures for scids that we don't have in our
      // commitments, otherwise it can be used as a DoS vector.
      val oldestScid = announcementSigsStash.keys.minBy(ShortChannelId.blockHeight(_))
      log.warning("too many pending announcement_signatures: dropping scid={}", oldestScid)
      announcementSigsStash -= oldestScid
    }
  }

  private def trimSpliceLockedSentIfNeeded(): Unit = {
    if (spliceLockedSent.size >= 10) {
      // We shouldn't store an unbounded number of splice_locked: on long-lived connections where we do a lot of splice
      // transactions, we only need to keep track of the most recent ones.
      val oldestFundingTxId = spliceLockedSent.toSeq
        .sortBy { case (_, fundingTxIndex) => fundingTxIndex }
        .map { case (fundingTxId, _) => fundingTxId }.head
      spliceLockedSent -= oldestFundingTxId
    }
  }

  val txPublisher = txPublisherFactory.spawnTxPublisher(context, remoteNodeId)

  // this will be used to detect htlc timeouts
  context.system.eventStream.subscribe(self, classOf[CurrentBlockHeight])
  // the constant delay by which we delay processing of blocks (it will be smoothened among all channels)
  private val blockProcessingDelay = Random.nextLong(nodeParams.channelConf.maxBlockProcessingDelay.toMillis + 1).millis
  // this will be used to make sure the current commitment fee is up-to-date
  context.system.eventStream.subscribe(self, classOf[CurrentFeerates.BitcoinCore])

  /*
          8888888 888b    888 8888888 88888888888
            888   8888b   888   888       888
            888   88888b  888   888       888
            888   888Y88b 888   888       888
            888   888 Y88b888   888       888
            888   888  Y88888   888       888
            888   888   Y8888   888       888
          8888888 888    Y888 8888888     888
   */

  startWith(WAIT_FOR_INIT_INTERNAL, Nothing)

  when(WAIT_FOR_INIT_INTERNAL)(handleExceptions {
    case Event(input: INPUT_INIT_CHANNEL_INITIATOR, Nothing) =>
      context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isOpener = true, input.temporaryChannelId, input.commitTxFeerate, Some(input.fundingTxFeerate)))
      activeConnection = input.remote
      txPublisher ! SetChannelId(remoteNodeId, input.temporaryChannelId)
      // We will process the input in the next state differently depending on whether we use dual-funding or not.
      self ! input
      if (input.dualFunded) {
        goto(WAIT_FOR_INIT_DUAL_FUNDED_CHANNEL)
      } else {
        goto(WAIT_FOR_INIT_SINGLE_FUNDED_CHANNEL)
      }

    case Event(input: INPUT_INIT_CHANNEL_NON_INITIATOR, Nothing) if !input.localChannelParams.isChannelOpener =>
      activeConnection = input.remote
      txPublisher ! SetChannelId(remoteNodeId, input.temporaryChannelId)
      if (input.dualFunded) {
        goto(WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) using DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL(input)
      } else {
        goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(input)
      }

    case Event(INPUT_RESTORED(data), _) =>
      log.debug("restoring channel")
      data match {
        case data: ChannelDataWithCommitments if data.commitments.active.exists(_.commitmentFormat == DefaultCommitmentFormat) =>
          log.warning("channel is not using anchor outputs: please close it and re-open an anchor output channel before updating to the next version of eclair")
        case _ => ()
      }
      context.system.eventStream.publish(ChannelRestored(self, data.channelId, peer, remoteNodeId, data))
      txPublisher ! SetChannelId(remoteNodeId, data.channelId)
      // We watch all unconfirmed funding txs, whatever our state is.
      // There can be multiple funding txs due to rbf, and they can be unconfirmed in any state due to zero-conf.
      // To avoid a herd effect on restart, we add a delay before watching funding txs.
      val herdDelay_opt = nodeParams.channelConf.maxRestartWatchDelay.toSeconds match {
        case maxRestartWatchDelay if maxRestartWatchDelay > 0 => Some((1 + Random.nextLong(maxRestartWatchDelay)).seconds)
        case _ => None
      }
      data match {
        case _: ChannelDataWithoutCommitments => ()
        case data: ChannelDataWithCommitments => data.commitments.all.foreach { commitment =>
          commitment.localFundingStatus match {
            case _: LocalFundingStatus.SingleFundedUnconfirmedFundingTx =>
              data match {
                case _: DATA_WAIT_FOR_FUNDING_CONFIRMED =>
                  // The funding tx is unconfirmed, we will watch for confirmations, but we also query the mempool
                  // to decide if we should republish (when funder) or abandon the channel (when fundee)
                  blockchain ! GetTxWithMeta(self, commitment.fundingTxId)
                case _ =>
                  // In the case of legacy single-funded channels, the funding tx may actually be confirmed already (and
                  // the channel fully operational). We could have set a specific Unknown status, but it would have forced
                  // us to keep it forever. Instead, we just put a watch which, if the funding tx was indeed confirmed, will
                  // trigger instantly, and the state will be updated and a watch-spent will be set. This will only happen
                  // once, because at the next restore, the status of the funding tx will be "confirmed".
                  ()
              }
              watchFundingConfirmed(commitment.fundingTxId, Some(nodeParams.channelConf.minDepth), herdDelay_opt)
            case fundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx =>
              publishFundingTx(fundingTx)
              val minDepth_opt = data.commitments.channelParams.minDepth(nodeParams.channelConf.minDepth)
              watchFundingConfirmed(fundingTx.sharedTx.txId, minDepth_opt, herdDelay_opt)
            case fundingTx: LocalFundingStatus.ZeroconfPublishedFundingTx =>
              watchFundingConfirmed(fundingTx.tx.txid, Some(nodeParams.channelConf.minDepth), herdDelay_opt)
            case _: LocalFundingStatus.ConfirmedFundingTx =>
              data match {
                case closing: DATA_CLOSING if Closing.nothingAtStake(closing) => ()
                // No need to watch the funding tx, it has already been spent and the spending tx has already reached mindepth.
                case closing: DATA_CLOSING if Closing.isClosingTypeAlreadyKnown(closing).isDefined => ()
                // In all other cases we need to be ready for any type of closing.
                case closing: DATA_CLOSING =>
                  watchFundingSpent(commitment, closing.spendingTxs.map(_.txid).toSet, herdDelay_opt)
                case negotiating: DATA_NEGOTIATING =>
                  val closingTxs = negotiating.closingTxProposed.flatten.map(_.unsignedTx.tx.txid).toSet
                  watchFundingSpent(commitment, additionalKnownSpendingTxs = closingTxs, herdDelay_opt)
                case negotiating: DATA_NEGOTIATING_SIMPLE =>
                  val closingTxs = negotiating.proposedClosingTxs.flatMap(_.all).map(_.tx.txid).toSet ++ negotiating.publishedClosingTxs.map(_.tx.txid).toSet
                  watchFundingSpent(commitment, additionalKnownSpendingTxs = closingTxs, herdDelay_opt)
                case _ =>
                  // Children splice transactions may already spend that confirmed funding transaction.
                  val spliceSpendingTxs = data.commitments.all.collect { case c if c.fundingTxIndex == commitment.fundingTxIndex + 1 => c.fundingTxId }
                  watchFundingSpent(commitment, additionalKnownSpendingTxs = spliceSpendingTxs.toSet, herdDelay_opt)
              }
          }
        }
      }
      // We resume the channel and re-publish closing transactions if it was closing.
      data match {
        // NB: order matters!
        case closing: DATA_CLOSING if Closing.nothingAtStake(closing) =>
          log.info("we have nothing at stake, going straight to CLOSED")
          context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, closing.channelId))
          goto(CLOSED) using closing
        case closing: DATA_CLOSING =>
          val localPaysClosingFees = closing.commitments.localChannelParams.paysClosingFees
          val closingType_opt = Closing.isClosingTypeAlreadyKnown(closing)
          log.info(s"channel is closing (closingType=${closingType_opt.map(c => EventType.Closed(c).label).getOrElse("UnknownYet")})")
          // If the closing type is known:
          //  - there is no need to attempt to publish transactions for other type of closes
          //  - there may be 3rd-stage transactions to publish
          //  - there is a single commitment, the others have all been invalidated
          val commitment = closing.commitments.latest
          val closingFeerate = nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, closing.maxClosingFeerate_opt)
          closingType_opt match {
            case Some(c: Closing.MutualClose) =>
              doPublish(c.tx, localPaysClosingFees)
            case Some(c: Closing.LocalClose) =>
              val (_, secondStageTransactions) = Closing.LocalClose.claimCommitTxOutputs(channelKeys, commitment, c.localCommitPublished.commitTx, closingFeerate, closing.finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
              doPublish(c.localCommitPublished, secondStageTransactions, commitment)
              val thirdStageTransactions = Closing.LocalClose.claimHtlcDelayedOutputs(c.localCommitPublished, channelKeys, commitment, closingFeerate, closing.finalScriptPubKey)
              doPublish(c.localCommitPublished, thirdStageTransactions)
            case Some(c: Closing.RemoteClose) =>
              val (_, secondStageTransactions) = Closing.RemoteClose.claimCommitTxOutputs(channelKeys, commitment, c.remoteCommit, c.remoteCommitPublished.commitTx, closingFeerate, closing.finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
              doPublish(c.remoteCommitPublished, secondStageTransactions, commitment)
            case Some(c: Closing.RecoveryClose) =>
              // We cannot do anything in that case: we've already published our recovery transaction before restarting,
              // and must wait for it to confirm.
              doPublish(c.remoteCommitPublished, Closing.RemoteClose.SecondStageTransactions(None, None, Nil), commitment)
            case Some(c: Closing.RevokedClose) =>
              Closing.RevokedClose.getRemotePerCommitmentSecret(closing.commitments.channelParams, channelKeys, closing.commitments.remotePerCommitmentSecrets, c.revokedCommitPublished.commitTx).foreach {
                case (commitmentNumber, remotePerCommitmentSecret) =>
                  // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
                  val toSelfDelay = closing.commitments.latest.remoteCommitParams.toSelfDelay
                  val commitmentFormat = closing.commitments.latest.commitmentFormat
                  val dustLimit = closing.commitments.latest.localCommitParams.dustLimit
                  val (_, secondStageTransactions) = Closing.RevokedClose.claimCommitTxOutputs(closing.commitments.channelParams, channelKeys, c.revokedCommitPublished.commitTx, commitmentNumber, remotePerCommitmentSecret, toSelfDelay, commitmentFormat, nodeParams.db.channels, dustLimit, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf, closing.finalScriptPubKey)
                  doPublish(c.revokedCommitPublished, secondStageTransactions)
                  val thirdStageTransactions = Closing.RevokedClose.claimHtlcTxsOutputs(closing.commitments.channelParams, channelKeys, remotePerCommitmentSecret, toSelfDelay, commitmentFormat, c.revokedCommitPublished, dustLimit, nodeParams.currentBitcoinCoreFeerates, closing.finalScriptPubKey)
                  doPublish(c.revokedCommitPublished, thirdStageTransactions)
              }
            case None =>
              // The closing type isn't known yet:
              //  - we publish transactions for all types of closes that we detected
              //  - there may be other commitments, but we'll adapt if we receive WatchAlternativeCommitTxConfirmedTriggered
              //  - there cannot be 3rd-stage transactions yet, no need to re-compute them
              closing.mutualClosePublished.foreach(mcp => doPublish(mcp, localPaysClosingFees))
              closing.localCommitPublished.foreach(lcp => {
                val (_, secondStageTransactions) = Closing.LocalClose.claimCommitTxOutputs(channelKeys, commitment, lcp.commitTx, closingFeerate, closing.finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
                doPublish(lcp, secondStageTransactions, commitment)
              })
              closing.remoteCommitPublished.foreach(rcp => {
                val (_, secondStageTransactions) = Closing.RemoteClose.claimCommitTxOutputs(channelKeys, commitment, commitment.remoteCommit, rcp.commitTx, closingFeerate, closing.finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
                doPublish(rcp, secondStageTransactions, commitment)
              })
              closing.nextRemoteCommitPublished.foreach(rcp => {
                val remoteCommit = commitment.nextRemoteCommit_opt.get.commit
                val (_, secondStageTransactions) = Closing.RemoteClose.claimCommitTxOutputs(channelKeys, commitment, remoteCommit, rcp.commitTx, closingFeerate, closing.finalScriptPubKey, nodeParams.onChainFeeConf.spendAnchorWithoutHtlcs)
                doPublish(rcp, secondStageTransactions, commitment)
              })
              closing.revokedCommitPublished.foreach(rvk => {
                Closing.RevokedClose.getRemotePerCommitmentSecret(closing.commitments.channelParams, channelKeys, closing.commitments.remotePerCommitmentSecrets, rvk.commitTx).foreach {
                  case (commitmentNumber, remotePerCommitmentSecret) =>
                    // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
                    val toSelfDelay = closing.commitments.latest.remoteCommitParams.toSelfDelay
                    val commitmentFormat = closing.commitments.latest.commitmentFormat
                    val dustLimit = closing.commitments.latest.localCommitParams.dustLimit
                    val (_, secondStageTransactions) = Closing.RevokedClose.claimCommitTxOutputs(closing.commitments.channelParams, channelKeys, rvk.commitTx, commitmentNumber, remotePerCommitmentSecret, toSelfDelay, commitmentFormat, nodeParams.db.channels, dustLimit, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf, closing.finalScriptPubKey)
                    doPublish(rvk, secondStageTransactions)
                }
              })
              closing.futureRemoteCommitPublished.foreach(rcp => doPublish(rcp, Closing.RemoteClose.SecondStageTransactions(None, None, Nil), commitment))
          }
          // no need to go OFFLINE, we can directly switch to CLOSING
          goto(CLOSING) using closing
        case normal: DATA_NORMAL =>
          context.system.eventStream.publish(ShortChannelIdAssigned(self, normal.channelId, normal.lastAnnouncement_opt, normal.aliases, remoteNodeId))
          // we check the configuration because the values for channel_update may have changed while eclair was down
          val (fees, inboundFees_opt) = getRelayFees(nodeParams, remoteNodeId, normal.commitments.announceChannel)
          if (fees.feeBase != normal.channelUpdate.feeBaseMsat ||
            fees.feeProportionalMillionths != normal.channelUpdate.feeProportionalMillionths ||
            inboundFees_opt != normal.channelUpdate.blip18InboundFees_opt ||
            nodeParams.channelConf.expiryDelta != normal.channelUpdate.cltvExpiryDelta) {
            log.debug("refreshing channel_update due to configuration changes")
            self ! CMD_UPDATE_RELAY_FEE(ActorRef.noSender, fees.feeBase, fees.feeProportionalMillionths, inboundFees_opt.map(_.feeBase), inboundFees_opt.map(_.feeProportionalMillionths))
          }
          // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
          // we take into account the date of the last update so that we don't send superfluous updates when we restart the app
          val periodicRefreshInitialDelay = Helpers.nextChannelUpdateRefresh(normal.channelUpdate.timestamp)
          context.system.scheduler.scheduleWithFixedDelay(initialDelay = periodicRefreshInitialDelay, delay = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
          goto(OFFLINE) using normal
        case _ =>
          goto(OFFLINE) using data
      }
  })

  /*
          888b     d888        d8888 8888888 888b    888      888      .d88888b.   .d88888b.  8888888b.
          8888b   d8888       d88888   888   8888b   888      888     d88P" "Y88b d88P" "Y88b 888   Y88b
          88888b.d88888      d88P888   888   88888b  888      888     888     888 888     888 888    888
          888Y88888P888     d88P 888   888   888Y88b 888      888     888     888 888     888 888   d88P
          888 Y888P 888    d88P  888   888   888 Y88b888      888     888     888 888     888 8888888P"
          888  Y8P  888   d88P   888   888   888  Y88888      888     888     888 888     888 888
          888   "   888  d8888888888   888   888   Y8888      888     Y88b. .d88P Y88b. .d88P 888
          888       888 d88P     888 8888888 888    Y888      88888888 "Y88888P"   "Y88888P"  888
   */

  when(NORMAL)(handleExceptions {
    case Event(c: ForbiddenCommandDuringQuiescenceNegotiation, d: DATA_NORMAL) if d.isNegotiatingQuiescence =>
      val error = ForbiddenDuringQuiescence(d.channelId, c.getClass.getSimpleName)
      c match {
        case c: CMD_ADD_HTLC => handleAddHtlcCommandError(c, error, Some(d.channelUpdate))
        // Htlc settlement commands are ignored and will be replayed when not quiescent.
        // This could create issues if we're keeping htlcs that should be settled pending for too long, as they could timeout.
        // That's why we have a timer to actively disconnect if we stay in quiescence for too long.
        case _: HtlcSettlementCommand => stay()
        case _ => handleCommandError(error, c)
      }

    case Event(c: ForbiddenCommandWhenQuiescent, d: DATA_NORMAL) if d.isQuiescent =>
      val error = ForbiddenDuringSplice(d.channelId, c.getClass.getSimpleName)
      c match {
        case c: CMD_ADD_HTLC => handleAddHtlcCommandError(c, error, Some(d.channelUpdate))
        // Htlc settlement commands are ignored and will be replayed when not quiescent.
        // This could create issues if we're keeping htlcs that should be settled pending for too long, as they could timeout.
        // That's why we have a timer to actively disconnect if we're splicing for too long.
        case _: HtlcSettlementCommand => stay()
        case _ => handleCommandError(error, c)
      }

    case Event(msg: ForbiddenMessageWhenQuiescent, d: DATA_NORMAL) if d.isQuiescent =>
      log.warning("received forbidden message {} during splicing with status {}", msg.getClass.getSimpleName, d.spliceStatus.getClass.getSimpleName)
      val error = ForbiddenDuringSplice(d.channelId, msg.getClass.getSimpleName)
      // We forward preimages as soon as possible to the upstream channel because it allows us to pull funds.
      msg match {
        case fulfill: UpdateFulfillHtlc => d.commitments.receiveFulfill(fulfill) match {
          case Right((_, origin, htlc)) => relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.RemoteFulfill(fulfill))
          case _ => ()
        }
        case _ => ()
      }
      // Instead of force-closing (which would cost us on-chain fees), we try to resolve this issue by disconnecting.
      // This will abort the splice attempt if it hasn't been signed yet, and restore the channel to a clean state.
      // If the splice attempt was signed, it gives us an opportunity to re-exchange signatures on reconnection before
      // the forbidden message. It also provides the opportunity for our peer to update their node to get rid of that
      // bug and resume normal execution.
      context.system.scheduler.scheduleOnce(1 second, peer, Peer.Disconnect(remoteNodeId))
      stay() sending Warning(d.channelId, error.getMessage)

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.localShutdown.isDefined || d.remoteShutdown.isDefined =>
      // note: spec would allow us to keep sending new htlcs after having received their shutdown (and not sent ours)
      // but we want to converge as fast as possible and they would probably not route them anyway
      val error = NoMoreHtlcsClosingInProgress(d.channelId)
      handleAddHtlcCommandError(c, error, Some(d.channelUpdate))

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) =>
      d.commitments.sendAdd(c, nodeParams.currentBlockHeight, nodeParams.channelConf, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf) match {
        case Right((commitments1, add)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.aliases, commitments1, d.lastAnnouncement_opt))
          val relayFee = nodeFee(d.channelUpdate.relayFees, add.amountMsat)
          context.system.eventStream.publish(OutgoingHtlcAdded(add, remoteNodeId, c.origin.upstream, relayFee))
          log.info("OutgoingHtlcAdded: channelId={}, id={}, endorsement={}, remoteNodeId={}, upstream={}, fee={}, now={}, blockHeight={}, expiry={}", Array(add.channelId.toHex, add.id, add.endorsement, remoteNodeId.toHex, c.origin.upstream.toString, relayFee, TimestampMilli.now().toLong, nodeParams.currentBlockHeight.toLong, add.cltvExpiry))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending add
        case Left(cause) => handleAddHtlcCommandError(c, cause, Some(d.channelUpdate))
      }

    case Event(add: UpdateAddHtlc, d: DATA_NORMAL) =>
      d.commitments.receiveAdd(add, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(add))
      }

    case Event(c: CMD_FULFILL_HTLC, d: DATA_NORMAL) =>
      d.commitments.sendFulfill(c, nodeParams.privateKey, nodeParams.features.hasFeature(Features.AttributionData)) match {
        case Right((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.aliases, commitments1, d.lastAnnouncement_opt))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fulfill
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_NORMAL) =>
      d.commitments.receiveFulfill(fulfill) match {
        case Right((commitments1, origin, htlc)) =>
          // we forward preimages as soon as possible to the upstream channel because it allows us to pull funds
          relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.RemoteFulfill(fulfill))
          context.system.eventStream.publish(OutgoingHtlcFulfilled(fulfill))
          log.info("OutgoingHtlcFulfilled: channelId={}, id={}", fulfill.channelId.toHex, fulfill.id)
          stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_NORMAL) =>
      c.delay_opt match {
        case Some(delay) =>
          log.debug("delaying CMD_FAIL_HTLC with id={} for {}", c.id, delay)
          context.system.scheduler.scheduleOnce(delay, self, c.copy(delay_opt = None))
          stay()
        case None => d.commitments.sendFail(c, nodeParams.privateKey, nodeParams.features.hasFeature(Features.AttributionData)) match {
          case Right((commitments1, fail)) =>
            if (c.commit) self ! CMD_SIGN()
            context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.aliases, commitments1, d.lastAnnouncement_opt))
            handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
          case Left(cause) =>
            // we acknowledge the command right away in case of failure
            handleCommandError(cause, c).acking(d.channelId, c)
        }
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_NORMAL) =>
      d.commitments.sendFailMalformed(c) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.aliases, commitments1, d.lastAnnouncement_opt))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_NORMAL) =>
      context.system.eventStream.publish(OutgoingHtlcFailed(fail))
      log.info("OutgoingHtlcFailed: channelId={}, id={}", fail.channelId.toHex, fail.id)
      d.commitments.receiveFail(fail) match {
        case Right((commitments1, _, _)) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_NORMAL) =>
      context.system.eventStream.publish(OutgoingHtlcFailed(fail))
      log.info("OutgoingHtlcFailed: channelId={}, id={}", fail.channelId.toHex, fail.id)
      d.commitments.receiveFailMalformed(fail) match {
        case Right((commitments1, _, _)) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_NORMAL) =>
      d.commitments.sendFee(c, nodeParams.onChainFeeConf) match {
        case Right((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.aliases, commitments1, d.lastAnnouncement_opt))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fee
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_NORMAL) =>
      d.commitments.receiveFee(fee, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c: CMD_SIGN, d: DATA_NORMAL) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !d.commitments.changes.localHasChanges =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay()
        case Right(_) =>
          d.commitments.sendCommit(channelKeys, remoteNextCommitNonces) match {
            case Right((commitments1, commit)) =>
              log.debug("sending a new sig, spec:\n{}", commitments1.latest.specs2String)
              val nextRemoteCommit = commitments1.latest.nextRemoteCommit_opt.get.commit
              val nextCommitNumber = nextRemoteCommit.index
              // We persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
              // counterparty, so only htlcs above remote's dust_limit matter.
              val trimmedOfferedHtlcs = d.commitments.active.flatMap(c => Transactions.trimOfferedHtlcs(c.remoteCommitParams.dustLimit, nextRemoteCommit.spec, c.commitmentFormat)).map(_.add).toSet
              val trimmedReceivedHtlcs = d.commitments.active.flatMap(c => Transactions.trimReceivedHtlcs(c.remoteCommitParams.dustLimit, nextRemoteCommit.spec, c.commitmentFormat)).map(_.add).toSet
              (trimmedOfferedHtlcs ++ trimmedReceivedHtlcs).foreach { htlc =>
                log.debug(s"adding paymentHash=${htlc.paymentHash} cltvExpiry=${htlc.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber")
                nodeParams.db.channels.addHtlcInfo(d.channelId, nextCommitNumber, htlc.paymentHash, htlc.cltvExpiry)
              }
              maybeUpdateMaxHtlcAmount(d.channelUpdate.htlcMaximumMsat, commitments1)
              context.system.eventStream.publish(ChannelSignatureSent(self, commitments1))
              // we expect a quick response from our peer
              startSingleTimer(RevocationTimeout.toString, RevocationTimeout(commitments1.latest.remoteCommit.index, peer), nodeParams.channelConf.revocationTimeout)
              handleCommandSuccess(c, d.copy(commitments = commitments1)).storing().sending(commit).acking(commitments1.changes.localChanges.signed)
            case Left(cause) => handleCommandError(cause, c)
          }
        case Left(_) =>
          log.debug("already in the process of signing, will sign again as soon as possible")
          stay()
      }

    case Event(commit: CommitSigs, d: DATA_NORMAL) =>
      (d.spliceStatus, commit) match {
        case (s: SpliceStatus.SpliceInProgress, sig: CommitSig) =>
          log.debug("received their commit_sig, deferring message")
          stay() using d.copy(spliceStatus = s.copy(remoteCommitSig = Some(sig)))
        case (SpliceStatus.SpliceAborted, sig: CommitSig) =>
          log.warning("received commit_sig after sending tx_abort, they probably sent it before receiving our tx_abort, ignoring...")
          stay()
        case (SpliceStatus.SpliceWaitingForSigs(signingSession), sig: CommitSig) =>
          signingSession.receiveCommitSig(d.commitments.channelParams, channelKeys, sig, nodeParams.currentBlockHeight) match {
            case Left(f) =>
              rollbackFundingAttempt(signingSession.fundingTx.tx, Nil)
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, f.getMessage)
            case Right(signingSession1) => signingSession1 match {
              case signingSession1: InteractiveTxSigningSession.WaitingForSigs =>
                // In theory we don't have to store their commit_sig here, as they would re-send it if we disconnect, but
                // it is more consistent with the case where we send our tx_signatures first.
                val d1 = d.copy(spliceStatus = SpliceStatus.SpliceWaitingForSigs(signingSession1))
                stay() using d1 storing()
              case signingSession1: InteractiveTxSigningSession.SendingSigs =>
                // We don't have their tx_sigs, but they have ours, and could publish the funding tx without telling us.
                // That's why we move on immediately to the next step, and will update our unsigned funding tx when we
                // receive their tx_sigs.
                val minDepth_opt = d.commitments.channelParams.minDepth(nodeParams.channelConf.minDepth)
                watchFundingConfirmed(signingSession.fundingTx.txId, minDepth_opt, delay_opt = None)
                val commitments1 = d.commitments.add(signingSession1.commitment)
                val d1 = d.copy(commitments = commitments1, spliceStatus = SpliceStatus.NoSplice)
                stay() using d1 storing() sending signingSession1.localSigs calling endQuiescence(d1)
            }
          }
        case _ =>
          // NB: in all other cases we process the commit_sigs normally. We could do a full pattern matching on all
          // splice statuses, but it would force us to handle every corner case where our peer doesn't behave correctly
          // whereas they will all simply lead to a force-close.
          d.commitments.receiveCommit(commit, channelKeys) match {
            case Right((commitments1, revocation)) =>
              log.debug("received a new sig, spec:\n{}", commitments1.latest.specs2String)
              if (commitments1.changes.localHasChanges) {
                // if we have newly acknowledged changes let's sign them
                self ! CMD_SIGN()
              }
              if (d.commitments.availableBalanceForSend != commitments1.availableBalanceForSend) {
                // we send this event only when our balance changes
                context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.aliases, commitments1, d.lastAnnouncement_opt))
              }
              context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
              // If we're now quiescent, we may send our stfu message.
              val (d1, toSend) = d.spliceStatus match {
                case SpliceStatus.NegotiatingQuiescence(cmd_opt, QuiescenceNegotiation.Initiator.QuiescenceRequested) if commitments1.localIsQuiescent =>
                  val stfu = Stfu(d.channelId, initiator = true)
                  val spliceStatus1 = SpliceStatus.NegotiatingQuiescence(cmd_opt, QuiescenceNegotiation.Initiator.SentStfu(stfu))
                  (d.copy(commitments = commitments1, spliceStatus = spliceStatus1), Seq(revocation, stfu))
                case SpliceStatus.NegotiatingQuiescence(_, _: QuiescenceNegotiation.NonInitiator.ReceivedStfu) if commitments1.localIsQuiescent =>
                  val stfu = Stfu(d.channelId, initiator = false)
                  (d.copy(commitments = commitments1, spliceStatus = SpliceStatus.NonInitiatorQuiescent), Seq(revocation, stfu))
                case _ =>
                  (d.copy(commitments = commitments1), Seq(revocation))
              }
              stay() using d1 storing() sending toSend
            case Left(cause) => handleLocalError(cause, d, Some(commit))
          }
      }

    case Event(revocation: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      d.commitments.receiveRevocation(revocation, nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure) match {
        case Right((commitments1, actions)) =>
          cancelTimer(RevocationTimeout.toString)
          remoteNextCommitNonces = revocation.nextCommitNonces
          log.debug("received a new rev, spec:\n{}", commitments1.latest.specs2String)
          actions.foreach {
            case PostRevocationAction.RelayHtlc(add) =>
              log.debug("forwarding incoming htlc {} to relayer", add)
              relayer ! Relayer.RelayForward(add, remoteNodeId, Reputation.incomingOccupancy(commitments1))
            case PostRevocationAction.RejectHtlc(add) =>
              log.debug("rejecting incoming htlc {}", add)
              // NB: we don't set commit = true, we will sign all updates at once afterwards.
              val attribution = FailureAttributionData(htlcReceivedAt = TimestampMilli.now(), trampolineReceivedAt_opt = None)
              self ! CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(TemporaryChannelFailure(Some(d.channelUpdate))), Some(attribution), commit = true)
            case PostRevocationAction.RelayFailure(result) =>
              log.debug("forwarding {} to relayer", result)
              relayer ! result
          }
          if (commitments1.changes.localHasChanges) {
            self ! CMD_SIGN()
          }
          if (d.remoteShutdown.isDefined && !commitments1.changes.localHasUnsignedOutgoingHtlcs) {
            // we were waiting for our pending htlcs to be signed before replying with our local shutdown
            val finalScriptPubKey = getOrGenerateFinalScriptPubKey(d)
            val localShutdown = createShutdown(d.commitments, finalScriptPubKey)
            // this should always be defined, we provide a fallback for backward compat with older channels
            val closeStatus = d.closeStatus_opt.getOrElse(CloseStatus.NonInitiator(None))
            // note: it means that we had pending htlcs to sign, therefore we go to SHUTDOWN, not to NEGOTIATING
            require(commitments1.latest.remoteCommit.spec.htlcs.nonEmpty, "we must have just signed new htlcs, otherwise we would have sent our Shutdown earlier")
            goto(SHUTDOWN) using DATA_SHUTDOWN(commitments1, localShutdown, d.remoteShutdown.get, closeStatus) storing() sending localShutdown
          } else {
            stay() using d.copy(commitments = commitments1) storing()
          }
        case Left(cause) => handleLocalError(cause, d, Some(revocation))
      }

    case Event(r: RevocationTimeout, d: DATA_NORMAL) => handleRevocationTimeout(r, d)

    case Event(c: CMD_CLOSE, d: DATA_NORMAL) =>
      if (d.localShutdown.isDefined) {
        handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      } else if (d.commitments.changes.localHasUnsignedOutgoingHtlcs) {
        // NB: simplistic behavior, we could also sign-then-close
        handleCommandError(CannotCloseWithUnsignedOutgoingHtlcs(d.channelId), c)
      } else if (d.commitments.changes.localHasUnsignedOutgoingUpdateFee) {
        handleCommandError(CannotCloseWithUnsignedOutgoingUpdateFee(d.channelId), c)
      } else {
        val localScriptPubKey = c.scriptPubKey.getOrElse(getOrGenerateFinalScriptPubKey(d))
        d.commitments.channelParams.validateLocalShutdownScript(localScriptPubKey) match {
          case Left(e) => handleCommandError(e, c)
          case Right(localShutdownScript) =>
            val shutdown = createShutdown(d.commitments, localShutdownScript)
            handleCommandSuccess(c, d.copy(localShutdown = Some(shutdown), closeStatus_opt = Some(CloseStatus.Initiator(c.feerates)))) storing() sending shutdown
        }
      }

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey, _), d: DATA_NORMAL) =>
      d.commitments.channelParams.validateRemoteShutdownScript(remoteScriptPubKey) match {
        case Left(e) =>
          log.warning(s"they sent an invalid closing script: ${e.getMessage}")
          context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
          stay() sending Warning(d.channelId, "invalid closing script")
        case Right(remoteShutdownScript) =>
          // they have pending unsigned htlcs         => they violated the spec, close the channel
          // they don't have pending unsigned htlcs
          //    we have pending unsigned htlcs
          //      we already sent a shutdown message  => spec violation (we can't send htlcs after having sent shutdown)
          //      we did not send a shutdown message
          //        we are ready to sign              => we stop sending further htlcs, we initiate a signature
          //        we are waiting for a rev          => we stop sending further htlcs, we wait for their revocation, will resign immediately after, and then we will send our shutdown message
          //    we have no pending unsigned htlcs
          //      we already sent a shutdown message
          //        there are pending signed changes  => send our shutdown message, go to SHUTDOWN
          //        there are no htlcs                => send our shutdown message, go to NEGOTIATING
          //      we did not send a shutdown message
          //        there are pending signed changes  => go to SHUTDOWN
          //        there are no htlcs                => go to NEGOTIATING
          remoteCloseeNonce_opt = remoteShutdown.closeeNonce_opt
          if (d.commitments.changes.remoteHasUnsignedOutgoingHtlcs) {
            handleLocalError(CannotCloseWithUnsignedOutgoingHtlcs(d.channelId), d, Some(remoteShutdown))
          } else if (d.commitments.changes.remoteHasUnsignedOutgoingUpdateFee) {
            handleLocalError(CannotCloseWithUnsignedOutgoingUpdateFee(d.channelId), d, Some(remoteShutdown))
          } else if (d.commitments.changes.localHasUnsignedOutgoingHtlcs) { // do we have unsigned outgoing htlcs?
            require(d.localShutdown.isEmpty, "can't have pending unsigned outgoing htlcs after having sent Shutdown")
            // are we in the middle of a signature?
            d.commitments.remoteNextCommitInfo match {
              case Left(_) =>
                // we already have a signature in progress, will resign when we receive the revocation
                ()
              case Right(_) =>
                // no, let's sign right away
                self ! CMD_SIGN()
            }
            // in the meantime we won't send new changes
            stay() using d.copy(remoteShutdown = Some(remoteShutdown), closeStatus_opt = Some(CloseStatus.NonInitiator(None)))
          } else if (d.commitments.latest.commitmentFormat.isInstanceOf[TaprootCommitmentFormat] && remoteShutdown.closeeNonce_opt.isEmpty) {
            handleLocalError(MissingClosingNonce(d.channelId), d, Some(remoteShutdown))
          } else {
            // so we don't have any unsigned outgoing changes
            val (localShutdown, sendList) = d.localShutdown match {
              case Some(localShutdown) => (localShutdown, Nil)
              case None =>
                val localShutdown = createShutdown(d.commitments, getOrGenerateFinalScriptPubKey(d))
                // we need to send our shutdown if we didn't previously
                (localShutdown, localShutdown :: Nil)
            }
            val closeStatus = d.localShutdown match {
              case Some(_) =>
                // this should always be defined, we provide a fallback for backward compat with older channels
                d.closeStatus_opt.getOrElse(CloseStatus.Initiator(None))
              case None => CloseStatus.NonInitiator(None)
            }
            // are there pending signed changes on either side? we need to have received their last revocation!
            if (d.commitments.hasNoPendingHtlcsOrFeeUpdate) {
              // there are no pending signed changes, let's directly negotiate a closing transaction
              if (Features.canUseFeature(d.commitments.localChannelParams.initFeatures, d.commitments.remoteChannelParams.initFeatures, Features.SimpleClose)) {
                val (d1, closingComplete_opt) = startSimpleClose(d.commitments, localShutdown, remoteShutdown, closeStatus)
                goto(NEGOTIATING_SIMPLE) using d1 storing() sending sendList ++ closingComplete_opt.toSeq
              } else if (d.commitments.localChannelParams.paysClosingFees) {
                // we pay the closing fees, so we initiate the negotiation by sending the first closing_signed
                val (closingTx, closingSigned) = MutualClose.makeFirstClosingTx(channelKeys, d.commitments.latest, localShutdown.scriptPubKey, remoteShutdownScript, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf, closeStatus.feerates_opt)
                goto(NEGOTIATING) using DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending sendList :+ closingSigned
              } else {
                // we are not the channel initiator, will wait for their closing_signed
                goto(NEGOTIATING) using DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing() sending sendList
              }
            } else {
              // there are some pending signed changes, we need to wait for them to be settled (fail/fulfill htlcs and sign fee updates)
              goto(SHUTDOWN) using DATA_SHUTDOWN(d.commitments, localShutdown, remoteShutdown, closeStatus) storing() sending sendList
            }
          }
      }

    case Event(ProcessCurrentBlockHeight(c), d: DATA_NORMAL) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates.BitcoinCore, d: DATA_NORMAL) => handleCurrentFeerate(c, d)

    case Event(_: ChannelReady, d: DATA_NORMAL) =>
      // After a reconnection, if the channel hasn't been used yet, our peer cannot be sure we received their channel_ready
      // so they will resend it. Their remote funding status must also be set to Locked if it wasn't already.
      // NB: Their remote funding status will be stored when the commitment is next updated, or channel_ready will
      // be sent again if a reconnection occurs first.
      stay() using d.copy(commitments = d.commitments.copy(active = d.commitments.active.map {
        case c if c.fundingTxIndex == 0 => c.copy(remoteFundingStatus = RemoteFundingStatus.Locked)
        case c => c
      }))

    // Channels are publicly announced if both parties want it: we ignore this message if we don't want to announce the channel.
    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_NORMAL) if d.commitments.announceChannel =>
      d.commitments.resolveCommitment(remoteAnnSigs.shortChannelId) match {
        case Some(c) if d.lastAnnouncedCommitment_opt.exists(_.fundingTxIndex > c.fundingTxIndex) =>
          log.info("ignoring remote announcement_signatures for scid={} with fundingTxIndex={}, we have a more recent announcement with scid={} and fundingTxIndex={}", remoteAnnSigs.shortChannelId, c.fundingTxIndex, d.lastAnnouncement_opt.map(_.shortChannelId), d.lastAnnouncedCommitment_opt.map(_.fundingTxIndex))
          stay()
        case Some(c) if d.lastAnnouncement_opt.exists(_.shortChannelId == remoteAnnSigs.shortChannelId) =>
          if (!announcementSigsSent.contains(remoteAnnSigs.shortChannelId)) {
            log.info("re-sending announcement_signatures for scid={}", remoteAnnSigs.shortChannelId)
            val localAnnSigs_opt = c.signAnnouncement(nodeParams, d.commitments.channelParams, channelKeys.fundingKey(c.fundingTxIndex))
            localAnnSigs_opt.foreach(annSigs => announcementSigsSent += annSigs.shortChannelId)
            stay() sending localAnnSigs_opt.toSeq
          } else {
            log.debug("ignoring remote announcement_signatures for scid={} with fundingTxIndex={}, channel is already announced and announcement_signatures was sent since we last reconnected", remoteAnnSigs.shortChannelId, c.fundingTxIndex)
            stay()
          }
        case Some(c) =>
          c.signAnnouncement(nodeParams, d.commitments.channelParams, channelKeys.fundingKey(c.fundingTxIndex)) match {
            case Some(localAnnSigs) =>
              val fundingPubKey = channelKeys.fundingKey(c.fundingTxIndex).publicKey
              val channelAnn = Announcements.makeChannelAnnouncement(nodeParams.chainHash, localAnnSigs.shortChannelId, nodeParams.nodeId, remoteNodeId, fundingPubKey, c.remoteFundingPubKey, localAnnSigs.nodeSignature, remoteAnnSigs.nodeSignature, localAnnSigs.bitcoinSignature, remoteAnnSigs.bitcoinSignature)
              if (!Announcements.checkSigs(channelAnn)) {
                handleLocalError(InvalidAnnouncementSignatures(d.channelId, remoteAnnSigs), d, Some(remoteAnnSigs))
              } else {
                log.info("announcing channelId={} on the network with shortChannelId={} for fundingTxIndex={}", d.channelId, localAnnSigs.shortChannelId, c.fundingTxIndex)
                // We generate a new channel_update because we can now use the scid of the announced funding transaction.
                val scidForChannelUpdate = Helpers.scidForChannelUpdate(Some(channelAnn), d.aliases.localAlias)
                val channelUpdate = Helpers.channelUpdate(nodeParams, scidForChannelUpdate, d.commitments, d.channelUpdate.relayFees, enable = true, d.channelUpdate.blip18InboundFees_opt)
                context.system.eventStream.publish(ShortChannelIdAssigned(self, d.channelId, Some(channelAnn), d.aliases, remoteNodeId))
                // We use goto() instead of stay() because we want to fire transitions.
                goto(NORMAL) using d.copy(lastAnnouncement_opt = Some(channelAnn), channelUpdate = channelUpdate) storing()
              }
            case None =>
              log.warning("cannot generate announcement_signatures for scid={} with fundingTxIndex={}", remoteAnnSigs.shortChannelId, c.fundingTxIndex)
              trimAnnouncementSigsStashIfNeeded()
              announcementSigsStash += (remoteAnnSigs.shortChannelId -> remoteAnnSigs)
              stay()
          }
        case None =>
          // The funding transaction doesn't have enough confirmations yet: we stash the remote message and will replay
          // it when we receive WatchFundingConfirmedTriggered. We don't need to persist this message, it will be
          // retransmitted on reconnection.
          log.debug("received remote announcement signatures for scid={}, delaying", remoteAnnSigs.shortChannelId)
          trimAnnouncementSigsStashIfNeeded()
          announcementSigsStash += (remoteAnnSigs.shortChannelId -> remoteAnnSigs)
          stay()
      }

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) =>
      val channelUpdate1 = Helpers.channelUpdate(nodeParams, scidForChannelUpdate(d), d.commitments, Relayer.RelayFees(c.feeBase, c.feeProportionalMillionths), enable = true, InboundFees.fromOptions(c.inboundFeeBase_opt, c.inboundFeeProportionalMillionths_opt))
      log.debug(s"updating relay fees: prev={} next={}", d.channelUpdate.toStringShort, channelUpdate1.toStringShort)
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_SUCCESS(c, d.channelId)
      // we use goto() instead of stay() because we want to fire transitions
      goto(NORMAL) using d.copy(channelUpdate = channelUpdate1) storing()

    case Event(BroadcastChannelUpdate(reason), d: DATA_NORMAL) =>
      val age = TimestampSecond.now() - d.channelUpdate.timestamp
      val channelUpdate1 = Helpers.channelUpdate(nodeParams, scidForChannelUpdate(d), d.commitments, d.channelUpdate.relayFees, enable = true, d.channelUpdate.blip18InboundFees_opt)
      reason match {
        case Reconnected if d.commitments.announceChannel && Announcements.areSame(channelUpdate1, d.channelUpdate) && age < REFRESH_CHANNEL_UPDATE_INTERVAL =>
          // we already sent an identical channel_update not long ago (flapping protection in case we keep being disconnected/reconnected)
          log.debug("not sending a new identical channel_update, current one was created {} days ago", age.toDays)
          stay()
        case BalanceThresholdCrossed if d.channelUpdate.htlcMaximumMsat == channelUpdate1.htlcMaximumMsat =>
          // The current channel update already uses the desired htlcMaximumMsat.
          stay()
        case BalanceThresholdCrossed if age < nodeParams.channelConf.minTimeBetweenUpdates =>
          startSingleTimer(BalanceThresholdCrossed.toString, BroadcastChannelUpdate(BalanceThresholdCrossed), nodeParams.channelConf.minTimeBetweenUpdates - age)
          stay()
        case _ =>
          log.debug("refreshing channel_update announcement (reason={})", reason)
          if (d.channelUpdate.htlcMaximumMsat != channelUpdate1.htlcMaximumMsat) {
            log.info("updating maximum HTLC amount to {} (old={})", channelUpdate1.htlcMaximumMsat, d.channelUpdate.htlcMaximumMsat)
          }
          // we use goto() instead of stay() because we want to fire transitions
          goto(NORMAL) using d.copy(channelUpdate = channelUpdate1) storing()
      }

    case Event(cmd: CMD_SPLICE, d: DATA_NORMAL) =>
      if (!d.commitments.remoteChannelParams.initFeatures.hasFeature(Features.SplicePrototype)) {
        log.warning("cannot initiate splice, peer doesn't support splicing")
        cmd.replyTo ! RES_FAILURE(cmd, CommandUnavailableInThisState(d.channelId, "splice", NORMAL))
        stay()
      } else if (d.commitments.active.count(_.fundingTxIndex == d.commitments.latest.fundingTxIndex) > 1) {
        log.warning("cannot initiate splice, the previous splice has unconfirmed rbf attempts")
        cmd.replyTo ! RES_FAILURE(cmd, InvalidSpliceWithUnconfirmedTx(d.channelId, d.commitments.latest.fundingTxId))
        stay()
      } else {
        d.spliceStatus match {
          case SpliceStatus.NoSplice =>
            startSingleTimer(QuiescenceTimeout.toString, QuiescenceTimeout(peer), nodeParams.channelConf.quiescenceTimeout)
            if (d.commitments.localIsQuiescent) {
              val stfu = Stfu(d.channelId, initiator = true)
              val spliceStatus1 = SpliceStatus.NegotiatingQuiescence(Some(cmd), QuiescenceNegotiation.Initiator.SentStfu(stfu))
              stay() using d.copy(spliceStatus = spliceStatus1) sending stfu
            } else {
              val spliceStatus1 = SpliceStatus.NegotiatingQuiescence(Some(cmd), QuiescenceNegotiation.Initiator.QuiescenceRequested)
              stay() using d.copy(spliceStatus = spliceStatus1)
            }
          case _ =>
            log.warning("cannot initiate splice, another one is already in progress")
            cmd.replyTo ! RES_FAILURE(cmd, InvalidSpliceAlreadyInProgress(d.channelId))
            stay()
        }
      }

    case Event(cmd: CMD_BUMP_FUNDING_FEE, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.NoSplice =>
          d.commitments.latest.localFundingStatus match {
            case fundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx =>
              if (cmd.targetFeerate < fundingTx.fundingParams.minNextFeerate) {
                log.warning("cannot initiate rbf: feerate too low ({} < {})", cmd.targetFeerate, fundingTx.fundingParams.minNextFeerate)
                cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfFeerate(d.channelId, cmd.targetFeerate, fundingTx.fundingParams.minNextFeerate))
                stay()
              } else if (cmd.requestFunding_opt.isEmpty && fundingTx.liquidityPurchase_opt.nonEmpty) {
                log.warning("cannot initiate rbf: a liquidity purchase was included in the previous transaction but is not included in this one")
                cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfMissingLiquidityPurchase(d.channelId, fundingTx.liquidityPurchase_opt.get.amount))
                stay()
              } else {
                startSingleTimer(QuiescenceTimeout.toString, QuiescenceTimeout(peer), nodeParams.channelConf.quiescenceTimeout)
                if (d.commitments.localIsQuiescent) {
                  val stfu = Stfu(d.channelId, initiator = true)
                  val spliceStatus1 = SpliceStatus.NegotiatingQuiescence(Some(cmd), QuiescenceNegotiation.Initiator.SentStfu(stfu))
                  stay() using d.copy(spliceStatus = spliceStatus1) sending stfu
                } else {
                  val spliceStatus1 = SpliceStatus.NegotiatingQuiescence(Some(cmd), QuiescenceNegotiation.Initiator.QuiescenceRequested)
                  stay() using d.copy(spliceStatus = spliceStatus1)
                }
              }
            case _: LocalFundingStatus.ZeroconfPublishedFundingTx =>
              log.warning("cannot initiate rbf: transaction is using 0-conf (txId={})", d.commitments.latest.fundingTxId)
              cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfZeroConf(d.channelId))
              stay()
            case _ =>
              log.warning("cannot initiate rbf: transaction already confirmed (txId={})", d.commitments.latest.fundingTxId)
              cmd.replyTo ! RES_FAILURE(cmd, InvalidRbfTxConfirmed(d.channelId))
              stay()
          }
        case _ =>
          log.warning("cannot initiate rbf, a concurrent splice attempt is already in progress")
          cmd.replyTo ! RES_FAILURE(cmd, InvalidSpliceAlreadyInProgress(d.channelId))
          stay()
      }

    case Event(_: Stfu, d: DATA_NORMAL) if d.localShutdown.isDefined =>
      log.warning("our peer sent stfu but we sent shutdown first")
      // We don't need to do anything, they should accept our shutdown.
      stay()

    case Event(msg: Stfu, d: DATA_NORMAL) =>
      if (d.commitments.remoteIsQuiescent) {
        d.spliceStatus match {
          case SpliceStatus.NoSplice =>
            startSingleTimer(QuiescenceTimeout.toString, QuiescenceTimeout(peer), nodeParams.channelConf.quiescenceTimeout)
            if (d.commitments.localIsQuiescent) {
              stay() using d.copy(spliceStatus = SpliceStatus.NonInitiatorQuiescent) sending Stfu(d.channelId, initiator = false)
            } else {
              stay() using d.copy(spliceStatus = SpliceStatus.NegotiatingQuiescence(None, QuiescenceNegotiation.NonInitiator.ReceivedStfu(msg)))
            }
          case SpliceStatus.NegotiatingQuiescence(cmd_opt, QuiescenceNegotiation.Initiator.QuiescenceRequested) =>
            // We could keep track of our splice attempt and merge it with the remote splice instead of cancelling it.
            // But this is an edge case that should rarely occur, so it's probably not worth the additional complexity.
            log.warning("our peer initiated quiescence before us, cancelling our splice attempt")
            cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, ConcurrentRemoteSplice(d.channelId)))
            stay() using d.copy(spliceStatus = SpliceStatus.NegotiatingQuiescence(None, QuiescenceNegotiation.NonInitiator.ReceivedStfu(msg)))
          case SpliceStatus.NegotiatingQuiescence(Some(cmd), QuiescenceNegotiation.Initiator.SentStfu(_)) =>
            // If both sides send stfu at the same time, the quiescence initiator is the channel opener.
            if (!msg.initiator || d.commitments.localChannelParams.isChannelOpener) {
              cmd match {
                case cmd: CMD_SPLICE => initiateSplice(cmd, d) match {
                  case Left(f) =>
                    cmd.replyTo ! RES_FAILURE(cmd, f)
                    context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
                    stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending Warning(d.channelId, f.getMessage)
                  case Right(spliceInit) =>
                    stay() using d.copy(spliceStatus = SpliceStatus.SpliceRequested(cmd, spliceInit)) sending spliceInit
                }
                case cmd: CMD_BUMP_FUNDING_FEE => initiateSpliceRbf(cmd, d) match {
                  case Left(f) =>
                    cmd.replyTo ! RES_FAILURE(cmd, f)
                    context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
                    stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending Warning(d.channelId, f.getMessage)
                  case Right(txInitRbf) =>
                    stay() using d.copy(spliceStatus = SpliceStatus.RbfRequested(cmd, txInitRbf)) sending txInitRbf
                }
              }
            } else {
              log.warning("concurrent stfu received and our peer is the channel initiator, cancelling our splice attempt")
              cmd.replyTo ! RES_FAILURE(cmd, ConcurrentRemoteSplice(d.channelId))
              stay() using d.copy(spliceStatus = SpliceStatus.NonInitiatorQuiescent)
            }
          case _ =>
            log.warning("ignoring duplicate stfu")
            stay()
        }
      } else {
        log.warning("our peer sent stfu but is not quiescent")
        // NB: we use a small delay to ensure we've sent our warning before disconnecting.
        context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
        stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending Warning(d.channelId, InvalidSpliceNotQuiescent(d.channelId).getMessage)
      }

    case Event(_: QuiescenceTimeout, d: DATA_NORMAL) => handleQuiescenceTimeout(d)

    case Event(msg: SpliceInit, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.NonInitiatorQuiescent =>
          if (!d.commitments.isQuiescent) {
            log.info("rejecting splice request: channel not quiescent")
            stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidSpliceNotQuiescent(d.channelId).getMessage)
          } else if (msg.feerate < nodeParams.currentBitcoinCoreFeerates.minimum) {
            log.info("rejecting splice request: feerate too low ({} < {})", msg.feerate, nodeParams.currentBitcoinCoreFeerates.minimum)
            stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidSpliceFeerate(d.channelId, msg.feerate, nodeParams.currentBitcoinCoreFeerates.minimum).getMessage)
          } else if (d.commitments.active.count(_.fundingTxIndex == d.commitments.latest.fundingTxIndex) > 1) {
            val previousTxs = d.commitments.active.filter(_.fundingTxIndex == d.commitments.latest.fundingTxIndex).map(_.fundingTxId)
            log.info("rejecting splice request: the previous splice has unconfirmed rbf attempts (txIds={})", previousTxs.mkString(", "))
            stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidSpliceWithUnconfirmedTx(d.channelId, d.commitments.latest.fundingTxId).getMessage)
          } else if (d.commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx]) {
            log.info("rejecting splice request: the previous funding transaction is unconfirmed (txId={})", d.commitments.latest.fundingTxId)
            stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidSpliceWithUnconfirmedTx(d.channelId, d.commitments.latest.fundingTxId).getMessage)
          } else {
            val parentCommitment = d.commitments.latest.commitment
            val localFundingPubKey = channelKeys.fundingKey(parentCommitment.fundingTxIndex + 1).publicKey
            val fundingScript = Transactions.makeFundingScript(localFundingPubKey, msg.fundingPubKey, parentCommitment.commitmentFormat).pubkeyScript
            LiquidityAds.validateRequest(nodeParams.privateKey, d.channelId, fundingScript, msg.feerate, isChannelCreation = false, msg.requestFunding_opt, nodeParams.liquidityAdsConfig.rates_opt, msg.useFeeCredit_opt) match {
              case Left(t) =>
                log.warning("rejecting splice request with invalid liquidity ads: {}", t.getMessage)
                stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, t.getMessage)
              case Right(willFund_opt) =>
                log.info(s"accepting splice with remote.in.amount=${msg.fundingContribution} remote.in.push=${msg.pushAmount}")
                // We only support updating phoenix channels to taproot: we ignore other attempts at upgrading the
                // commitment format and will simply apply the previous commitment format.
                val nextCommitmentFormat = msg.channelType_opt match {
                  case Some(channelType: ChannelTypes.SimpleTaprootChannelsPhoenix) if parentCommitment.commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat =>
                    log.info(s"accepting upgrade to $channelType during splice from commitment format ${parentCommitment.commitmentFormat}")
                    PhoenixSimpleTaprootChannelCommitmentFormat
                  case Some(channelType) =>
                    log.info(s"rejecting upgrade to $channelType during splice from commitment format ${parentCommitment.commitmentFormat}")
                    parentCommitment.commitmentFormat
                  case _ =>
                    parentCommitment.commitmentFormat
                }
                val spliceAck = SpliceAck(d.channelId,
                  fundingContribution = willFund_opt.map(_.purchase.amount).getOrElse(0 sat),
                  fundingPubKey = localFundingPubKey,
                  pushAmount = 0.msat,
                  requireConfirmedInputs = nodeParams.channelConf.requireConfirmedInputsForDualFunding,
                  willFund_opt = willFund_opt.map(_.willFund),
                  feeCreditUsed_opt = msg.useFeeCredit_opt,
                  channelType_opt = msg.channelType_opt
                )
                val fundingParams = InteractiveTxParams(
                  channelId = d.channelId,
                  isInitiator = false,
                  localContribution = spliceAck.fundingContribution,
                  remoteContribution = msg.fundingContribution,
                  sharedInput_opt = Some(SharedFundingInput(channelKeys, parentCommitment)),
                  remoteFundingPubKey = msg.fundingPubKey,
                  localOutputs = Nil,
                  commitmentFormat = nextCommitmentFormat,
                  lockTime = msg.lockTime,
                  dustLimit = parentCommitment.localCommitParams.dustLimit.max(parentCommitment.remoteCommitParams.dustLimit),
                  targetFeerate = msg.feerate,
                  requireConfirmedInputs = RequireConfirmedInputs(forLocal = msg.requireConfirmedInputs, forRemote = spliceAck.requireConfirmedInputs)
                )
                val sessionId = randomBytes32()
                val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
                  sessionId,
                  nodeParams, fundingParams,
                  channelParams = d.commitments.channelParams,
                  localCommitParams = parentCommitment.localCommitParams,
                  remoteCommitParams = parentCommitment.remoteCommitParams,
                  channelKeys = channelKeys,
                  purpose = InteractiveTxBuilder.SpliceTx(parentCommitment, d.commitments.changes),
                  localPushAmount = spliceAck.pushAmount, remotePushAmount = msg.pushAmount,
                  liquidityPurchase_opt = willFund_opt.map(_.purchase),
                  wallet
                ))
                txBuilder ! InteractiveTxBuilder.Start(self)
                stay() using d.copy(spliceStatus = SpliceStatus.SpliceInProgress(cmd_opt = None, sessionId, txBuilder, remoteCommitSig = None)) sending spliceAck
            }
          }
        case SpliceStatus.NoSplice =>
          log.info("rejecting splice attempt: quiescence not negotiated")
          stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidSpliceNotQuiescent(d.channelId).getMessage)
        case SpliceStatus.SpliceAborted =>
          log.info("rejecting splice attempt: our previous tx_abort was not acked")
          stay() sending Warning(d.channelId, InvalidSpliceTxAbortNotAcked(d.channelId).getMessage)
        case _ =>
          log.info("rejecting splice attempt: the current splice attempt must be completed or aborted first")
          stay() sending Warning(d.channelId, InvalidSpliceAlreadyInProgress(d.channelId).getMessage)
      }

    case Event(msg: SpliceAck, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.SpliceRequested(cmd, spliceInit) =>
          log.info("our peer accepted our splice request and will contribute {} to the funding transaction", msg.fundingContribution)
          val parentCommitment = d.commitments.latest.commitment
          // We only support updating phoenix channels to taproot: we ignore other attempts at upgrading the
          // commitment format and will simply apply the previous commitment format.
          val nextCommitmentFormat = msg.channelType_opt match {
            case Some(_: ChannelTypes.SimpleTaprootChannelsPhoenix) if parentCommitment.commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat => PhoenixSimpleTaprootChannelCommitmentFormat
            case _ => parentCommitment.commitmentFormat
          }
          val fundingParams = InteractiveTxParams(
            channelId = d.channelId,
            isInitiator = true,
            localContribution = spliceInit.fundingContribution,
            remoteContribution = msg.fundingContribution,
            sharedInput_opt = Some(SharedFundingInput(channelKeys, parentCommitment)),
            remoteFundingPubKey = msg.fundingPubKey,
            localOutputs = cmd.spliceOutputs,
            commitmentFormat = nextCommitmentFormat,
            lockTime = spliceInit.lockTime,
            dustLimit = parentCommitment.localCommitParams.dustLimit.max(parentCommitment.remoteCommitParams.dustLimit),
            targetFeerate = spliceInit.feerate,
            requireConfirmedInputs = RequireConfirmedInputs(forLocal = msg.requireConfirmedInputs, forRemote = spliceInit.requireConfirmedInputs)
          )
          val fundingScript = Transactions.makeFundingScript(spliceInit.fundingPubKey, msg.fundingPubKey, parentCommitment.commitmentFormat).pubkeyScript
          LiquidityAds.validateRemoteFunding(spliceInit.requestFunding_opt, remoteNodeId, d.channelId, fundingScript, msg.fundingContribution, spliceInit.feerate, isChannelCreation = false, msg.willFund_opt) match {
            case Left(t) =>
              log.info("rejecting splice attempt: invalid liquidity ads response ({})", t.getMessage)
              cmd.replyTo ! RES_FAILURE(cmd, t)
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, t.getMessage)
            case Right(liquidityPurchase_opt) =>
              val sessionId = randomBytes32()
              val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
                sessionId,
                nodeParams, fundingParams,
                channelParams = d.commitments.channelParams,
                localCommitParams = parentCommitment.localCommitParams,
                remoteCommitParams = parentCommitment.remoteCommitParams,
                channelKeys = channelKeys,
                purpose = InteractiveTxBuilder.SpliceTx(parentCommitment, d.commitments.changes),
                localPushAmount = cmd.pushAmount, remotePushAmount = msg.pushAmount,
                liquidityPurchase_opt = liquidityPurchase_opt,
                wallet
              ))
              txBuilder ! InteractiveTxBuilder.Start(self)
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceInProgress(cmd_opt = Some(cmd), sessionId, txBuilder, remoteCommitSig = None))
          }
        case _ =>
          log.info(s"ignoring unexpected splice_ack=$msg")
          stay()
      }

    case Event(msg: InteractiveTxConstructionMessage, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.SpliceInProgress(_, _, txBuilder, _) =>
          txBuilder ! InteractiveTxBuilder.ReceiveMessage(msg)
          stay()
        case _ =>
          log.info("ignoring unexpected interactive-tx message: {}", msg.getClass.getSimpleName)
          stay() sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)
      }

    case Event(msg: TxInitRbf, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.NonInitiatorQuiescent =>
          getSpliceRbfContext(None, d) match {
            case Right(rbf) if msg.feerate < rbf.latestFundingTx.fundingParams.minNextFeerate =>
              log.info("rejecting rbf request: the new feerate must be at least {} (proposed={})", rbf.latestFundingTx.fundingParams.minNextFeerate, msg.feerate)
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidRbfFeerate(d.channelId, msg.feerate, rbf.latestFundingTx.fundingParams.minNextFeerate).getMessage)
            case Right(rbf) if msg.requestFunding_opt.isEmpty && rbf.latestFundingTx.liquidityPurchase_opt.nonEmpty =>
              log.info("rejecting rbf attempt: a liquidity purchase was included in the previous transaction but is not included in this one")
              // Our peer is trying to trick us into contributing the amount they were previously paying for, but
              // without paying for it by leveraging the fact that we'll keep contributing the same amount.
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidRbfMissingLiquidityPurchase(d.channelId, rbf.latestFundingTx.liquidityPurchase_opt.get.amount).getMessage)
            case Right(rbf) if nodeParams.currentBlockHeight < rbf.latestFundingTx.createdAt + nodeParams.channelConf.remoteRbfLimits.attemptDeltaBlocks =>
              log.info("rejecting rbf attempt: last attempt was less than {} blocks ago", nodeParams.channelConf.remoteRbfLimits.attemptDeltaBlocks)
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidRbfAttemptTooSoon(d.channelId, rbf.latestFundingTx.createdAt, rbf.latestFundingTx.createdAt + nodeParams.channelConf.remoteRbfLimits.attemptDeltaBlocks).getMessage)
            case Right(rbf) =>
              val fundingScript = d.commitments.latest.commitInput(channelKeys).txOut.publicKeyScript
              LiquidityAds.validateRequest(nodeParams.privateKey, d.channelId, fundingScript, msg.feerate, isChannelCreation = false, msg.requestFunding_opt, nodeParams.liquidityAdsConfig.rates_opt, feeCreditUsed_opt = None) match {
                case Left(t) =>
                  log.warning("rejecting rbf request with invalid liquidity ads: {}", t.getMessage)
                  stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, t.getMessage)
                case Right(willFund_opt) =>
                  // We contribute the amount of liquidity requested by our peer, if liquidity ads is active.
                  // Otherwise we keep the same contribution we made to the previous funding transaction.
                  val fundingContribution = willFund_opt.map(_.purchase.amount).getOrElse(rbf.latestFundingTx.fundingParams.localContribution)
                  log.info("accepting rbf with remote.in.amount={} local.in.amount={}", msg.fundingContribution, fundingContribution)
                  val txAckRbf = TxAckRbf(d.channelId, fundingContribution, rbf.latestFundingTx.fundingParams.requireConfirmedInputs.forRemote, willFund_opt.map(_.willFund))
                  val fundingParams = InteractiveTxParams(
                    channelId = d.channelId,
                    isInitiator = false,
                    localContribution = fundingContribution,
                    remoteContribution = msg.fundingContribution,
                    sharedInput_opt = Some(SharedFundingInput(channelKeys, rbf.parentCommitment)),
                    remoteFundingPubKey = rbf.latestFundingTx.fundingParams.remoteFundingPubKey,
                    localOutputs = rbf.latestFundingTx.fundingParams.localOutputs,
                    commitmentFormat = rbf.latestFundingTx.fundingParams.commitmentFormat,
                    lockTime = msg.lockTime,
                    dustLimit = rbf.latestFundingTx.fundingParams.dustLimit,
                    targetFeerate = msg.feerate,
                    requireConfirmedInputs = RequireConfirmedInputs(forLocal = msg.requireConfirmedInputs, forRemote = txAckRbf.requireConfirmedInputs)
                  )
                  val sessionId = randomBytes32()
                  val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
                    sessionId,
                    nodeParams, fundingParams,
                    channelParams = d.commitments.channelParams,
                    localCommitParams = rbf.parentCommitment.localCommitParams,
                    remoteCommitParams = rbf.parentCommitment.remoteCommitParams,
                    channelKeys = channelKeys,
                    purpose = rbf,
                    localPushAmount = 0 msat, remotePushAmount = 0 msat,
                    willFund_opt.map(_.purchase),
                    wallet
                  ))
                  txBuilder ! InteractiveTxBuilder.Start(self)
                  stay() using d.copy(spliceStatus = SpliceStatus.SpliceInProgress(cmd_opt = None, sessionId, txBuilder, remoteCommitSig = None)) sending txAckRbf
              }
            case Left(f) =>
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, f.getMessage)
          }
        case SpliceStatus.NoSplice =>
          log.info("rejecting rbf attempt: quiescence not negotiated")
          stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, InvalidSpliceNotQuiescent(d.channelId).getMessage)
        case SpliceStatus.SpliceAborted =>
          log.info("rejecting rbf attempt: our previous tx_abort was not acked")
          stay() sending Warning(d.channelId, InvalidRbfTxAbortNotAcked(d.channelId).getMessage)
        case _ =>
          log.info("rejecting rbf attempt: the current attempt must be completed or aborted first")
          stay() sending Warning(d.channelId, InvalidRbfAlreadyInProgress(d.channelId).getMessage)
      }

    case Event(msg: TxAckRbf, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.RbfRequested(cmd, txInitRbf) =>
          getSpliceRbfContext(Some(cmd), d) match {
            case Right(rbf) =>
              val fundingScript = d.commitments.latest.commitInput(channelKeys).txOut.publicKeyScript
              LiquidityAds.validateRemoteFunding(cmd.requestFunding_opt, remoteNodeId, d.channelId, fundingScript, msg.fundingContribution, txInitRbf.feerate, isChannelCreation = false, msg.willFund_opt) match {
                case Left(t) =>
                  log.info("rejecting rbf attempt: invalid liquidity ads response ({})", t.getMessage)
                  cmd.replyTo ! RES_FAILURE(cmd, t)
                  stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, t.getMessage)
                case Right(liquidityPurchase_opt) =>
                  log.info("our peer accepted our rbf request and will contribute {} to the funding transaction", msg.fundingContribution)
                  val fundingParams = InteractiveTxParams(
                    channelId = d.channelId,
                    isInitiator = true,
                    localContribution = txInitRbf.fundingContribution,
                    remoteContribution = msg.fundingContribution,
                    sharedInput_opt = Some(SharedFundingInput(channelKeys, rbf.parentCommitment)),
                    remoteFundingPubKey = rbf.latestFundingTx.fundingParams.remoteFundingPubKey,
                    localOutputs = rbf.latestFundingTx.fundingParams.localOutputs,
                    commitmentFormat = rbf.latestFundingTx.fundingParams.commitmentFormat,
                    lockTime = txInitRbf.lockTime,
                    dustLimit = rbf.latestFundingTx.fundingParams.dustLimit,
                    targetFeerate = txInitRbf.feerate,
                    requireConfirmedInputs = RequireConfirmedInputs(forLocal = msg.requireConfirmedInputs, forRemote = txInitRbf.requireConfirmedInputs)
                  )
                  val sessionId = randomBytes32()
                  val txBuilder = context.spawnAnonymous(InteractiveTxBuilder(
                    sessionId,
                    nodeParams, fundingParams,
                    channelParams = d.commitments.channelParams,
                    localCommitParams = rbf.parentCommitment.localCommitParams,
                    remoteCommitParams = rbf.parentCommitment.remoteCommitParams,
                    channelKeys = channelKeys,
                    purpose = rbf,
                    localPushAmount = 0 msat, remotePushAmount = 0 msat,
                    liquidityPurchase_opt = liquidityPurchase_opt,
                    wallet
                  ))
                  txBuilder ! InteractiveTxBuilder.Start(self)
                  stay() using d.copy(spliceStatus = SpliceStatus.SpliceInProgress(cmd_opt = Some(cmd), sessionId, txBuilder, remoteCommitSig = None))
              }
            case Left(f) =>
              cmd.replyTo ! RES_FAILURE(cmd, f)
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, f.getMessage)
          }
        case _ =>
          log.info("ignoring unexpected tx_ack_rbf (status={})", d.spliceStatus.getClass.getSimpleName)
          stay()
      }

    case Event(msg: TxAbort, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.SpliceInProgress(cmd_opt, _, txBuilder, _) =>
          log.info("our peer aborted the splice attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, SpliceAttemptAborted(d.channelId)))
          txBuilder ! InteractiveTxBuilder.Abort
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage) calling endQuiescence(d)
        case SpliceStatus.SpliceWaitingForSigs(signingSession) =>
          log.info("our peer aborted the splice attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          rollbackFundingAttempt(signingSession.fundingTx.tx, previousTxs = Seq.empty) // no splice rbf yet
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage) calling endQuiescence(d)
        case SpliceStatus.SpliceRequested(cmd, _) =>
          log.info("our peer rejected our splice attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          cmd.replyTo ! RES_FAILURE(cmd, new RuntimeException(s"splice attempt rejected by our peer: ${msg.toAscii}"))
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage) calling endQuiescence(d)
        case SpliceStatus.RbfRequested(cmd, _) =>
          log.info("our peer rejected our rbf attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          cmd.replyTo ! RES_FAILURE(cmd, new RuntimeException(s"rbf attempt rejected by our peer: ${msg.toAscii}"))
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage) calling endQuiescence(d)
        case SpliceStatus.NonInitiatorQuiescent =>
          log.info("our peer aborted their own splice attempt: ascii='{}' bin={}", msg.toAscii, msg.data)
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage) calling endQuiescence(d)
        case SpliceStatus.SpliceAborted =>
          log.debug("our peer acked our previous tx_abort")
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) calling endQuiescence(d)
        case SpliceStatus.NoSplice =>
          log.info("our peer wants to abort the splice, but we've already negotiated a splice transaction: ascii='{}' bin={}", msg.toAscii, msg.data)
          // We ack their tx_abort but we keep monitoring the funding transaction until it's confirmed or double-spent.
          stay() sending TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage)
        case _: SpliceStatus.NegotiatingQuiescence =>
          log.info("our peer aborted the splice during quiescence negotiation, disconnecting: ascii='{}' bin={}", msg.toAscii, msg.data)
          // NB: we use a small delay to ensure we've sent our warning before disconnecting.
          context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
          stay() using d.copy(spliceStatus = SpliceStatus.NoSplice) sending Warning(d.channelId, UnexpectedInteractiveTxMessage(d.channelId, msg).getMessage)
      }

    case Event(msg: InteractiveTxBuilder.Response, d: DATA_NORMAL) =>
      d.spliceStatus match {
        case SpliceStatus.SpliceInProgress(cmd_opt, currentSessionId, _, remoteCommitSig_opt) =>
          msg match {
            case InteractiveTxBuilder.SendMessage(sessionId, msg) =>
              if (sessionId == currentSessionId) {
                stay() sending msg
              } else {
                log.info("ignoring outgoing interactive-tx message {} from previous session", msg.getClass.getSimpleName)
                stay()
              }
            case InteractiveTxBuilder.Succeeded(signingSession, commitSig, liquidityPurchase_opt, nextRemoteCommitNonce_opt) =>
              log.info(s"splice tx created with fundingTxIndex=${signingSession.fundingTxIndex} fundingTxId=${signingSession.fundingTx.txId}")
              nextRemoteCommitNonce_opt.foreach { case (txId, nonce) => remoteNextCommitNonces = remoteNextCommitNonces + (txId -> nonce) }
              cmd_opt.foreach(cmd => cmd.replyTo ! RES_SPLICE(fundingTxIndex = signingSession.fundingTxIndex, signingSession.fundingTx.txId, signingSession.fundingParams.fundingAmount, signingSession.localCommit.fold(_.spec, _.spec).toLocal))
              remoteCommitSig_opt.foreach(self ! _)
              liquidityPurchase_opt.collect {
                case purchase if !signingSession.fundingParams.isInitiator => peer ! LiquidityPurchaseSigned(d.channelId, signingSession.fundingTx.txId, signingSession.fundingTxIndex, signingSession.remoteCommitParams.htlcMinimum, purchase)
              }
              val d1 = d.copy(spliceStatus = SpliceStatus.SpliceWaitingForSigs(signingSession))
              stay() using d1 storing() sending commitSig
            case f: InteractiveTxBuilder.Failed =>
              log.info("splice attempt failed: {}", f.cause.getMessage)
              cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, f.cause))
              stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, f.cause.getMessage)
          }
        case _ =>
          // This can happen if we received a tx_abort right before receiving the interactive-tx result.
          log.warning("ignoring interactive-tx result with spliceStatus={}", d.spliceStatus.getClass.getSimpleName)
          stay()
      }

    case Event(msg: TxSignatures, d: DATA_NORMAL) =>
      d.commitments.latest.localFundingStatus match {
        case dfu@LocalFundingStatus.DualFundedUnconfirmedFundingTx(fundingTx: PartiallySignedSharedTransaction, _, _, _) if fundingTx.txId == msg.txId =>
          // we already sent our tx_signatures
          InteractiveTxSigningSession.addRemoteSigs(channelKeys, dfu.fundingParams, fundingTx, msg) match {
            case Left(cause) =>
              log.warning("received invalid tx_signatures for fundingTxId={}: {}", msg.txId, cause.getMessage)
              // The funding transaction may still confirm (since our peer should be able to generate valid signatures),
              // so we cannot close the channel yet.
              stay() sending Error(d.channelId, InvalidFundingSignature(d.channelId, Some(fundingTx.txId)).getMessage)
            case Right(fundingTx) =>
              val dfu1 = dfu.copy(sharedTx = fundingTx)
              d.commitments.updateLocalFundingStatus(msg.txId, dfu1, d.lastAnnouncedFundingTxId_opt) match {
                case Right((commitments1, _)) =>
                  log.info("publishing funding tx for channelId={} fundingTxId={}", d.channelId, fundingTx.signedTx.txid)
                  Metrics.recordSplice(dfu.fundingParams, fundingTx.tx)
                  stay() using d.copy(commitments = commitments1) storing() calling publishFundingTx(dfu1)
                case Left(_) =>
                  stay()
              }
          }
        case _ =>
          d.spliceStatus match {
            case SpliceStatus.SpliceWaitingForSigs(signingSession) =>
              // we have not yet sent our tx_signatures
              signingSession.receiveTxSigs(channelKeys, msg, nodeParams.currentBlockHeight) match {
                case Left(f) =>
                  rollbackFundingAttempt(signingSession.fundingTx.tx, previousTxs = Seq.empty) // no splice rbf yet
                  stay() using d.copy(spliceStatus = SpliceStatus.SpliceAborted) sending TxAbort(d.channelId, f.getMessage)
                case Right(signingSession1) =>
                  val minDepth_opt = d.commitments.channelParams.minDepth(nodeParams.channelConf.minDepth)
                  watchFundingConfirmed(signingSession.fundingTx.txId, minDepth_opt, delay_opt = None)
                  val commitments1 = d.commitments.add(signingSession1.commitment)
                  val d1 = d.copy(commitments = commitments1, spliceStatus = SpliceStatus.NoSplice)
                  log.info("publishing funding tx for channelId={} fundingTxId={}", d.channelId, signingSession1.fundingTx.sharedTx.txId)
                  Metrics.recordSplice(signingSession1.fundingTx.fundingParams, signingSession1.fundingTx.sharedTx.tx)
                  stay() using d1 storing() sending signingSession1.localSigs calling publishFundingTx(signingSession1.fundingTx) calling endQuiescence(d1)
              }
            case _ =>
              // We may receive an outdated tx_signatures if the transaction is already confirmed.
              log.warning("ignoring unexpected tx_signatures for txId={}", msg.txId)
              stay()
          }
      }

    case Event(w: WatchPublishedTriggered, d: DATA_NORMAL) =>
      val fundingStatus = LocalFundingStatus.ZeroconfPublishedFundingTx(w.tx, d.commitments.localFundingSigs(w.tx.txid), d.commitments.liquidityPurchase(w.tx.txid))
      d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus, d.lastAnnouncedFundingTxId_opt) match {
        case Right((commitments1, commitment)) =>
          watchFundingConfirmed(w.tx.txid, Some(nodeParams.channelConf.minDepth), delay_opt = None)
          maybeEmitEventsPostSplice(d.aliases, d.commitments, commitments1, d.lastAnnouncement_opt)
          maybeUpdateMaxHtlcAmount(d.channelUpdate.htlcMaximumMsat, commitments1)
          spliceLockedSent += (commitment.fundingTxId -> commitment.fundingTxIndex)
          trimSpliceLockedSentIfNeeded()
          stay() using d.copy(commitments = commitments1) storing() sending SpliceLocked(d.channelId, w.tx.txid)
        case Left(_) => stay()
      }

    case Event(w: WatchFundingConfirmedTriggered, d: DATA_NORMAL) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, commitment)) =>
          // We check if this commitment was already locked before receiving the event (which happens when using 0-conf
          // or for the initial funding transaction). If it was previously not locked, we must send splice_locked now.
          val previouslyNotLocked = d.commitments.all.exists(c => c.fundingTxId == commitment.fundingTxId && c.localFundingStatus.isInstanceOf[LocalFundingStatus.NotLocked])
          val spliceLocked_opt = if (previouslyNotLocked) {
            spliceLockedSent += (commitment.fundingTxId -> commitment.fundingTxIndex)
            trimSpliceLockedSentIfNeeded()
            Some(SpliceLocked(d.channelId, w.tx.txid))
          } else None
          // If the channel is public and we've received the remote splice_locked, we send our announcement_signatures
          // in order to generate the channel_announcement.
          val remoteLocked = commitment.fundingTxIndex == 0 || d.commitments.all.exists(c => c.fundingTxId == commitment.fundingTxId && c.remoteFundingStatus == RemoteFundingStatus.Locked)
          val localAnnSigs_opt = if (d.commitments.announceChannel && remoteLocked) commitment.signAnnouncement(nodeParams, commitments1.channelParams, channelKeys.fundingKey(commitment.fundingTxIndex)) else None
          localAnnSigs_opt match {
            case Some(localAnnSigs) =>
              announcementSigsSent += localAnnSigs.shortChannelId
              // If we've already received the remote announcement_signatures, we're now ready to process them.
              announcementSigsStash.get(localAnnSigs.shortChannelId).foreach(self ! _)
            case None => // The channel is private or the commitment isn't locked on the remote side.
          }
          if (commitment.fundingTxIndex > 0) {
            maybeEmitEventsPostSplice(d.aliases, d.commitments, commitments1, d.lastAnnouncement_opt)
            maybeUpdateMaxHtlcAmount(d.channelUpdate.htlcMaximumMsat, commitments1)
          }
          stay() using d.copy(commitments = commitments1) storing() sending spliceLocked_opt.toSeq ++ localAnnSigs_opt.toSeq
        case Left(_) => stay()
      }

    case Event(msg: SpliceLocked, d: DATA_NORMAL) =>
      d.commitments.updateRemoteFundingStatus(msg.fundingTxId, d.lastAnnouncedFundingTxId_opt) match {
        case Right((commitments1, commitment)) =>
          // If we have both already sent splice_locked for this commitment, then we are receiving splice_locked
          // again after a reconnection and must retransmit our splice_locked and new announcement_signatures. Nodes
          // retransmit splice_locked after a reconnection when they have received splice_locked but NOT matching signatures
          // before the last disconnect. If a matching splice_locked has already been sent since reconnecting, then do not
          // retransmit splice_locked to avoid a loop.
          // NB: It is important both nodes retransmit splice_locked after reconnecting to ensure new Taproot nonces
          // are exchanged for channel announcements.
          val isLatestLocked = d.commitments.lastLocalLocked_opt.exists(_.fundingTxId == msg.fundingTxId) && d.commitments.lastRemoteLocked_opt.exists(_.fundingTxId == msg.fundingTxId)
          val spliceLocked_opt = if (d.commitments.announceChannel && isLatestLocked && !spliceLockedSent.contains(commitment.fundingTxId)) {
            spliceLockedSent += (commitment.fundingTxId -> commitment.fundingTxIndex)
            trimSpliceLockedSentIfNeeded()
            Some(SpliceLocked(d.channelId, commitment.fundingTxId))
          } else {
            None
          }
          // If the commitment is confirmed, we were waiting to receive the remote splice_locked before sending our announcement_signatures.
          val localAnnSigs_opt = commitment.signAnnouncement(nodeParams, commitments1.channelParams, channelKeys.fundingKey(commitment.fundingTxIndex)) match {
            case Some(localAnnSigs) if !announcementSigsSent.contains(localAnnSigs.shortChannelId) =>
              announcementSigsSent += localAnnSigs.shortChannelId
              // If we've already received the remote announcement_signatures, we're now ready to process them.
              announcementSigsStash.get(localAnnSigs.shortChannelId).foreach(self ! _)
              Some(localAnnSigs)
            case Some(_) => None // We've already sent these announcement_signatures since the last reconnect.
            case None => None // The channel is private or the commitment isn't locked on our side.
          }
          maybeEmitEventsPostSplice(d.aliases, d.commitments, commitments1, d.lastAnnouncement_opt)
          maybeUpdateMaxHtlcAmount(d.channelUpdate.htlcMaximumMsat, commitments1)
          stay() using d.copy(commitments = commitments1) storing() sending spliceLocked_opt.toSeq ++ localAnnSigs_opt.toSeq
        case Left(_) => stay()
      }

    case Event(INPUT_DISCONNECTED, d: DATA_NORMAL) =>
      // we cancel the timer that would have made us send the enabled update after reconnection (flappy channel protection)
      cancelTimer(Reconnected.toString)
      // if we are splicing, we need to cancel it
      reportSpliceFailure(d.spliceStatus, new RuntimeException("splice attempt failed: disconnected"))
      val d1 = d.spliceStatus match {
        // We keep track of the RBF status: we should be able to complete the signature steps on reconnection.
        case _: SpliceStatus.SpliceWaitingForSigs => d
        case _ => d.copy(spliceStatus = SpliceStatus.NoSplice)
      }
      // if we have pending unsigned htlcs, then we cancel them and generate an update with the disabled flag set, that will be returned to the sender in a temporary channel failure
      if (d.commitments.changes.localChanges.proposed.collectFirst { case add: UpdateAddHtlc => add }.isDefined) {
        log.debug("updating channel_update announcement (reason=disabled)")
        val channelUpdate1 = Helpers.channelUpdate(nodeParams, scidForChannelUpdate(d), d.commitments, d.channelUpdate.relayFees, enable = false, d.channelUpdate.blip18InboundFees_opt)
        // NB: the htlcs stay in the commitments.localChange, they will be cleaned up after reconnection
        d.commitments.changes.localChanges.proposed.collect {
          case add: UpdateAddHtlc => relayer ! RES_ADD_SETTLED(d.commitments.originChannels(add.id), add, HtlcResult.DisconnectedBeforeSigned(channelUpdate1))
        }
        goto(OFFLINE) using d1.copy(channelUpdate = channelUpdate1) storing()
      } else {
        goto(OFFLINE) using d1
      }

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)

  })

  /*
           .d8888b.  888      .d88888b.   .d8888b. 8888888 888b    888  .d8888b.
          d88P  Y88b 888     d88P" "Y88b d88P  Y88b  888   8888b   888 d88P  Y88b
          888    888 888     888     888 Y88b.       888   88888b  888 888    888
          888        888     888     888  "Y888b.    888   888Y88b 888 888
          888        888     888     888     "Y88b.  888   888 Y88b888 888  88888
          888    888 888     888     888       "888  888   888  Y88888 888    888
          Y88b  d88P 888     Y88b. .d88P Y88b  d88P  888   888   Y8888 Y88b  d88P
           "Y8888P"  88888888 "Y88888P"   "Y8888P" 8888888 888    Y888  "Y8888P88
   */

  when(SHUTDOWN)(handleExceptions {
    case Event(c: CMD_FULFILL_HTLC, d: DATA_SHUTDOWN) =>
      d.commitments.sendFulfill(c, nodeParams.privateKey, nodeParams.features.hasFeature(Features.AttributionData)) match {
        case Right((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fulfill
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_SHUTDOWN) =>
      d.commitments.receiveFulfill(fulfill) match {
        case Right((commitments1, origin, htlc)) =>
          // we forward preimages as soon as possible to the upstream channel because it allows us to pull funds
          relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.RemoteFulfill(fulfill))
          stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_SHUTDOWN) =>
      d.commitments.sendFail(c, nodeParams.privateKey, nodeParams.features.hasFeature(Features.AttributionData)) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_SHUTDOWN) =>
      d.commitments.sendFailMalformed(c) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_SHUTDOWN) =>
      d.commitments.receiveFail(fail) match {
        case Right((commitments1, _, _)) =>
          stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_SHUTDOWN) =>
      d.commitments.receiveFailMalformed(fail) match {
        case Right((commitments1, _, _)) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_SHUTDOWN) =>
      d.commitments.sendFee(c, nodeParams.onChainFeeConf) match {
        case Right((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fee
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_SHUTDOWN) =>
      d.commitments.receiveFee(fee, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c: CMD_SIGN, d: DATA_SHUTDOWN) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !d.commitments.changes.localHasChanges =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay()
        case Right(_) =>
          d.commitments.sendCommit(channelKeys, remoteNextCommitNonces) match {
            case Right((commitments1, commit)) =>
              log.debug("sending a new sig, spec:\n{}", commitments1.latest.specs2String)
              val nextRemoteCommit = commitments1.latest.nextRemoteCommit_opt.get.commit
              val nextCommitNumber = nextRemoteCommit.index
              // We persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
              // counterparty, so only htlcs above remote's dust_limit matter.
              val trimmedOfferedHtlcs = d.commitments.active.flatMap(c => Transactions.trimOfferedHtlcs(c.remoteCommitParams.dustLimit, nextRemoteCommit.spec, c.commitmentFormat)).map(_.add).toSet
              val trimmedReceivedHtlcs = d.commitments.active.flatMap(c => Transactions.trimReceivedHtlcs(c.remoteCommitParams.dustLimit, nextRemoteCommit.spec, c.commitmentFormat)).map(_.add).toSet
              (trimmedOfferedHtlcs ++ trimmedReceivedHtlcs).foreach { htlc =>
                log.debug(s"adding paymentHash=${htlc.paymentHash} cltvExpiry=${htlc.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber")
                nodeParams.db.channels.addHtlcInfo(d.channelId, nextCommitNumber, htlc.paymentHash, htlc.cltvExpiry)
              }
              context.system.eventStream.publish(ChannelSignatureSent(self, commitments1))
              // we expect a quick response from our peer
              startSingleTimer(RevocationTimeout.toString, RevocationTimeout(commitments1.latest.remoteCommit.index, peer), nodeParams.channelConf.revocationTimeout)
              handleCommandSuccess(c, d.copy(commitments = commitments1)).storing().sending(commit).acking(commitments1.changes.localChanges.signed)
            case Left(cause) => handleCommandError(cause, c)
          }
        case Left(_) =>
          log.debug("already in the process of signing, will sign again as soon as possible")
          stay()
      }

    case Event(commit: CommitSigs, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown, closeStatus)) =>
      d.commitments.receiveCommit(commit, channelKeys) match {
        case Right((commitments1, revocation)) =>
          // we always reply with a revocation
          log.debug("received a new sig:\n{}", commitments1.latest.specs2String)
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          if (commitments1.hasNoPendingHtlcsOrFeeUpdate) {
            if (Features.canUseFeature(d.commitments.localChannelParams.initFeatures, d.commitments.remoteChannelParams.initFeatures, Features.SimpleClose)) {
              val (d1, closingComplete_opt) = startSimpleClose(commitments1, localShutdown, remoteShutdown, closeStatus)
              goto(NEGOTIATING_SIMPLE) using d1 storing() sending revocation +: closingComplete_opt.toSeq
            } else if (d.commitments.localChannelParams.paysClosingFees) {
              // we pay the closing fees, so we initiate the negotiation by sending the first closing_signed
              val (closingTx, closingSigned) = MutualClose.makeFirstClosingTx(channelKeys, commitments1.latest, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf, d.closeStatus.feerates_opt)
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending revocation :: closingSigned :: Nil
            } else {
              // we are not the channel initiator, will wait for their closing_signed
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing() sending revocation
            }
          } else {
            if (commitments1.changes.localHasChanges) {
              // if we have newly acknowledged changes let's sign them
              self ! CMD_SIGN()
            }
            stay() using d.copy(commitments = commitments1) storing() sending revocation
          }
        case Left(cause) => handleLocalError(cause, d, Some(commit))
      }

    case Event(revocation: RevokeAndAck, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown, closeStatus)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked including the shutdown message
      d.commitments.receiveRevocation(revocation, nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure) match {
        case Right((commitments1, actions)) =>
          cancelTimer(RevocationTimeout.toString)
          remoteNextCommitNonces = revocation.nextCommitNonces
          log.debug("received a new rev, spec:\n{}", commitments1.latest.specs2String)
          actions.foreach {
            case PostRevocationAction.RelayHtlc(add) =>
              // BOLT 2: A sending node SHOULD fail to route any HTLC added after it sent shutdown.
              log.debug("closing in progress: failing {}", add)
              val attribution = FailureAttributionData(htlcReceivedAt = TimestampMilli.now(), trampolineReceivedAt_opt = None)
              self ! CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(PermanentChannelFailure()), Some(attribution), commit = true)
            case PostRevocationAction.RejectHtlc(add) =>
              // BOLT 2: A sending node SHOULD fail to route any HTLC added after it sent shutdown.
              log.debug("closing in progress: rejecting {}", add)
              val attribution = FailureAttributionData(htlcReceivedAt = TimestampMilli.now(), trampolineReceivedAt_opt = None)
              self ! CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(PermanentChannelFailure()), Some(attribution), commit = true)
            case PostRevocationAction.RelayFailure(result) =>
              log.debug("forwarding {} to relayer", result)
              relayer ! result
          }
          if (commitments1.hasNoPendingHtlcsOrFeeUpdate) {
            log.debug("switching to NEGOTIATING spec:\n{}", commitments1.latest.specs2String)
            if (Features.canUseFeature(d.commitments.localChannelParams.initFeatures, d.commitments.remoteChannelParams.initFeatures, Features.SimpleClose)) {
              val (d1, closingComplete_opt) = startSimpleClose(commitments1, localShutdown, remoteShutdown, closeStatus)
              goto(NEGOTIATING_SIMPLE) using d1 storing() sending closingComplete_opt.toSeq
            } else if (d.commitments.localChannelParams.paysClosingFees) {
              // we pay the closing fees, so we initiate the negotiation by sending the first closing_signed
              val (closingTx, closingSigned) = MutualClose.makeFirstClosingTx(channelKeys, commitments1.latest, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf, d.closeStatus.feerates_opt)
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending closingSigned
            } else {
              // we are not the channel initiator, will wait for their closing_signed
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing()
            }
          } else {
            if (commitments1.changes.localHasChanges) {
              self ! CMD_SIGN()
            }
            stay() using d.copy(commitments = commitments1) storing()
          }
        case Left(cause) => handleLocalError(cause, d, Some(revocation))
      }

    case Event(shutdown: Shutdown, d: DATA_SHUTDOWN) =>
      if (shutdown.scriptPubKey != d.remoteShutdown.scriptPubKey) {
        log.debug("our peer updated their shutdown script (previous={}, current={})", d.remoteShutdown.scriptPubKey, shutdown.scriptPubKey)
      }
      remoteCloseeNonce_opt = shutdown.closeeNonce_opt
      stay() using d.copy(remoteShutdown = shutdown) storing()

    case Event(r: RevocationTimeout, d: DATA_SHUTDOWN) => handleRevocationTimeout(r, d)

    case Event(ProcessCurrentBlockHeight(c), d: DATA_SHUTDOWN) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates.BitcoinCore, d: DATA_SHUTDOWN) => handleCurrentFeerate(c, d)

    case Event(c: CMD_CLOSE, d: DATA_SHUTDOWN) =>
      val useSimpleClose = Features.canUseFeature(d.commitments.localChannelParams.initFeatures, d.commitments.remoteChannelParams.initFeatures, Features.SimpleClose)
      val nextScriptPubKey_opt = c.scriptPubKey match {
        case Some(scriptPubKey) if scriptPubKey != d.localShutdown.scriptPubKey && useSimpleClose => Some(scriptPubKey)
        case _ => None
      }
      if (c.scriptPubKey.exists(_ != d.localShutdown.scriptPubKey) && !useSimpleClose) {
        handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      } else if (nextScriptPubKey_opt.nonEmpty || c.feerates.nonEmpty) {
        val closeStatus1 = d.closeStatus match {
          case initiator: CloseStatus.Initiator => initiator.copy(feerates_opt = c.feerates.orElse(initiator.feerates_opt))
          case nonInitiator: CloseStatus.NonInitiator => nonInitiator.copy(feerates_opt = c.feerates.orElse(nonInitiator.feerates_opt)) // NB: this is the corner case where we can be non-initiator and have custom feerates
        }
        val shutdown = createShutdown(d.commitments, nextScriptPubKey_opt.getOrElse(d.localShutdown.scriptPubKey))
        val d1 = d.copy(localShutdown = shutdown, closeStatus = closeStatus1)
        handleCommandSuccess(c, d1) storing() sending shutdown
      } else {
        handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      }

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)

  })

  when(NEGOTIATING)(handleExceptions {
    case Event(remoteShutdown: Shutdown, d: DATA_NEGOTIATING) =>
      if (remoteShutdown.scriptPubKey != d.remoteShutdown.scriptPubKey) {
        // This may lead to a signature mismatch if our peer changed their script without using option_simple_close.
        log.warning("received shutdown changing remote script, this may lead to a signature mismatch: previous={}, current={}", d.remoteShutdown.scriptPubKey, remoteShutdown.scriptPubKey)
        stay() using d.copy(remoteShutdown = remoteShutdown) storing()
      } else {
        stay()
      }

    case Event(c: ClosingSigned, d: DATA_NEGOTIATING) =>
      val (remoteClosingFee, remoteSig) = (c.feeSatoshis, c.signature)
      MutualClose.checkClosingSignature(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, remoteClosingFee, remoteSig) match {
        case Right((signedClosingTx, closingSignedRemoteFees)) =>
          val lastLocalClosingSigned_opt = d.closingTxProposed.last.lastOption
          if (lastLocalClosingSigned_opt.exists(_.localClosingSigned.feeSatoshis == remoteClosingFee)) {
            // they accepted the last fee we sent them, so we close without sending a closing_signed
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx))))
          } else if (d.closingTxProposed.flatten.size >= MAX_NEGOTIATION_ITERATIONS) {
            // there were too many iterations, we stop negotiating and accept their fee
            log.warning("could not agree on closing fees after {} iterations, accepting their closing fees ({})", MAX_NEGOTIATION_ITERATIONS, remoteClosingFee)
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
          } else if (lastLocalClosingSigned_opt.flatMap(_.localClosingSigned.feeRange_opt).exists(r => r.min <= remoteClosingFee && remoteClosingFee <= r.max)) {
            // they chose a fee inside our proposed fee range, so we close and send a closing_signed for that fee
            val localFeeRange = lastLocalClosingSigned_opt.flatMap(_.localClosingSigned.feeRange_opt).get
            log.info("they chose a closing fee={} within our fee range (min={} max={})", remoteClosingFee, localFeeRange.min, localFeeRange.max)
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
          } else if (d.commitments.latest.localCommit.spec.toLocal == 0.msat) {
            // we have nothing at stake so there is no need to negotiate, we accept their fee right away
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
          } else {
            c.feeRange_opt match {
              case Some(ClosingSignedTlv.FeeRange(minFee, maxFee)) if !d.commitments.localChannelParams.paysClosingFees =>
                // if we are not paying the closing fees and they proposed a fee range, we pick a value in that range and they should accept it without further negotiation
                // we don't care much about the closing fee since they're paying it (not us) and we can use CPFP if we want to speed up confirmation
                val localClosingFees = MutualClose.firstClosingFee(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf)
                if (maxFee < localClosingFees.min) {
                  log.warning("their highest closing fee is below our minimum fee: {} < {}", maxFee, localClosingFees.min)
                  stay() sending Warning(d.channelId, s"closing fee range must not be below ${localClosingFees.min}")
                } else {
                  val closingFee = localClosingFees match {
                    case ClosingFees(preferred, _, _) if preferred > maxFee => maxFee
                    // if we underestimate the fee, then we're happy with whatever they propose (it will confirm more quickly and we're not paying it)
                    case ClosingFees(preferred, _, _) if preferred < remoteClosingFee => remoteClosingFee
                    case ClosingFees(preferred, _, _) => preferred
                  }
                  if (closingFee == remoteClosingFee) {
                    log.info("accepting their closing fee={}", remoteClosingFee)
                    handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
                  } else {
                    val (closingTx, closingSigned) = MutualClose.makeClosingTx(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, ClosingFees(closingFee, minFee, maxFee))
                    log.info("proposing closing fee={} in their fee range (min={} max={})", closingSigned.feeSatoshis, minFee, maxFee)
                    val closingTxProposed1 = (d.closingTxProposed: @unchecked) match {
                      case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx, closingSigned))
                    }
                    stay() using d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx)) storing() sending closingSigned
                  }
                }
              case _ =>
                val lastLocalClosingFee_opt = lastLocalClosingSigned_opt.map(_.localClosingSigned.feeSatoshis)
                val (closingTx, closingSigned) = {
                  // if we are not the channel initiator and we were waiting for them to send their first closing_signed, we don't have a lastLocalClosingFee, so we compute a firstClosingFee
                  val localClosingFees = MutualClose.firstClosingFee(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf)
                  val nextPreferredFee = MutualClose.nextClosingFee(lastLocalClosingFee_opt.getOrElse(localClosingFees.preferred), remoteClosingFee)
                  MutualClose.makeClosingTx(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, localClosingFees.copy(preferred = nextPreferredFee))
                }
                val closingTxProposed1 = (d.closingTxProposed: @unchecked) match {
                  case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx, closingSigned))
                }
                if (lastLocalClosingFee_opt.contains(closingSigned.feeSatoshis)) {
                  // next computed fee is the same than the one we previously sent (probably because of rounding), let's close now
                  handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx))))
                } else if (closingSigned.feeSatoshis == remoteClosingFee) {
                  // we have converged!
                  log.info("accepting their closing fee={}", remoteClosingFee)
                  handleMutualClose(signedClosingTx, Left(d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSigned
                } else {
                  log.info("proposing closing fee={}", closingSigned.feeSatoshis)
                  stay() using d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx)) storing() sending closingSigned
                }
            }
          }
        case Left(cause) => handleLocalError(cause, d, Some(c))
      }

    case Event(c: CMD_CLOSE, d: DATA_NEGOTIATING) =>
      c.feerates match {
        case Some(feerates) =>
          if (c.scriptPubKey.nonEmpty && !c.scriptPubKey.contains(d.localShutdown.scriptPubKey)) {
            log.warning("cannot update closing script when closing is already in progress")
            handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
          } else {
            log.info("updating our closing feerates: {}", feerates)
            val (closingTx, closingSigned) = MutualClose.makeFirstClosingTx(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf, Some(feerates))
            val closingTxProposed1 = d.closingTxProposed match {
              case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx, closingSigned))
              case previousNegotiations => previousNegotiations :+ List(ClosingTxProposed(closingTx, closingSigned))
            }
            handleCommandSuccess(c, d.copy(closingTxProposed = closingTxProposed1)) storing() sending closingSigned
          }
        case _ =>
          handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      }

    case Event(e: Error, d: DATA_NEGOTIATING) => handleRemoteError(e, d)

  })

  when(NEGOTIATING_SIMPLE)(handleExceptions {
    case Event(shutdown: Shutdown, d: DATA_NEGOTIATING_SIMPLE) =>
      remoteCloseeNonce_opt = shutdown.closeeNonce_opt
      if (shutdown.scriptPubKey != d.remoteScriptPubKey) {
        // This may lead to a signature mismatch: peers must use closing_complete to update their closing script.
        log.warning("received shutdown changing remote script, this may lead to a signature mismatch: previous={}, current={}", d.remoteScriptPubKey, shutdown.scriptPubKey)
        stay() using d.copy(remoteScriptPubKey = shutdown.scriptPubKey) storing()
      } else {
        stay()
      }

    case Event(c: CMD_CLOSE, d: DATA_NEGOTIATING_SIMPLE) =>
      val localScript = c.scriptPubKey.getOrElse(d.localScriptPubKey)
      val closingFeerate = c.feerates.map(_.preferred).getOrElse(nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, maxClosingFeerateOverride_opt = None))
      if (closingFeerate < d.lastClosingFeerate) {
        val err = InvalidRbfFeerate(d.channelId, closingFeerate, d.lastClosingFeerate * 1.2)
        handleCommandError(err, c)
      } else {
        MutualClose.makeSimpleClosingTx(nodeParams.currentBlockHeight, channelKeys, d.commitments.latest, localScript, d.remoteScriptPubKey, closingFeerate, remoteCloseeNonce_opt) match {
          case Left(f) => handleCommandError(f, c)
          case Right((closingTxs, closingComplete, closerNonces)) =>
            log.debug("signing local mutual close transactions: {}", closingTxs)
            localCloserNonces_opt = Some(closerNonces)
            handleCommandSuccess(c, d.copy(lastClosingFeerate = closingFeerate, localScriptPubKey = localScript, proposedClosingTxs = d.proposedClosingTxs :+ closingTxs)) storing() sending closingComplete
        }
      }

    case Event(closingComplete: ClosingComplete, d: DATA_NEGOTIATING_SIMPLE) =>
      // Note that if there is a failure here and we don't send our closing_sig, they may eventually disconnect.
      // On reconnection, we will retransmit shutdown with our latest scripts, so future signing attempts should work.
      if (closingComplete.closeeScriptPubKey != d.localScriptPubKey) {
        log.warning("their closing_complete is not using our latest script: this may happen if we changed our script while they were sending closing_complete")
        // No need to persist their latest script, they will re-sent it on reconnection.
        stay() using d.copy(remoteScriptPubKey = closingComplete.closerScriptPubKey) sending Warning(d.channelId, InvalidCloseeScript(d.channelId, closingComplete.closeeScriptPubKey, d.localScriptPubKey).getMessage)
      } else {
        MutualClose.signSimpleClosingTx(channelKeys, d.commitments.latest, closingComplete.closeeScriptPubKey, closingComplete.closerScriptPubKey, closingComplete, localCloseeNonce_opt) match {
          case Left(f) =>
            log.warning("invalid closing_complete: {}", f.getMessage)
            stay() sending Warning(d.channelId, f.getMessage)
          case Right((signedClosingTx, closingSig, nextCloseeNonce_opt)) =>
            log.debug("signing remote mutual close transaction: {}", signedClosingTx.tx)
            localCloseeNonce_opt = nextCloseeNonce_opt
            val d1 = d.copy(remoteScriptPubKey = closingComplete.closerScriptPubKey, publishedClosingTxs = d.publishedClosingTxs :+ signedClosingTx)
            stay() using d1 storing() calling doPublish(signedClosingTx, localPaysClosingFees = false) sending closingSig
        }
      }

    case Event(closingSig: ClosingSig, d: DATA_NEGOTIATING_SIMPLE) =>
      // Note that if we sent two closing_complete in a row, without waiting for their closing_sig for the first one,
      // this will fail because we only care about our latest closing_complete. This is fine, we should receive their
      // closing_sig for the last closing_complete afterwards.
      MutualClose.receiveSimpleClosingSig(channelKeys, d.commitments.latest, d.proposedClosingTxs.last, closingSig, localCloserNonces_opt, remoteCloseeNonce_opt) match {
        case Left(f) =>
          log.warning("invalid closing_sig: {}", f.getMessage)
          remoteCloseeNonce_opt = closingSig.nextCloseeNonce_opt
          stay() sending Warning(d.channelId, f.getMessage)
        case Right(signedClosingTx) =>
          log.debug("received signatures for local mutual close transaction: {}", signedClosingTx.tx)
          val d1 = d.copy(publishedClosingTxs = d.publishedClosingTxs :+ signedClosingTx)
          remoteCloseeNonce_opt = closingSig.nextCloseeNonce_opt
          stay() using d1 storing() calling doPublish(signedClosingTx, localPaysClosingFees = true)
      }

    case Event(_: AnnouncementSignatures, _: DATA_NEGOTIATING_SIMPLE) =>
      log.debug("ignoring announcement_signatures, we're negotiating closing transactions")
      stay()

    case Event(e: Error, d: DATA_NEGOTIATING_SIMPLE) => handleRemoteError(e, d)

  })

  when(CLOSING)(handleExceptions {
    case Event(c: HtlcSettlementCommand, d: DATA_CLOSING) =>
      (c match {
        case c: CMD_FULFILL_HTLC => d.commitments.sendFulfill(c, nodeParams.privateKey, nodeParams.features.hasFeature(Features.AttributionData))
        case c: CMD_FAIL_HTLC => d.commitments.sendFail(c, nodeParams.privateKey, nodeParams.features.hasFeature(Features.AttributionData))
        case c: CMD_FAIL_MALFORMED_HTLC => d.commitments.sendFailMalformed(c)
      }) match {
        case Right((commitments1, _)) =>
          val commitment = commitments1.latest
          val d1 = c match {
            case c: CMD_FULFILL_HTLC =>
              log.info("htlc #{} with payment_hash={} was fulfilled downstream, recalculating htlc-success transactions", c.id, c.r)
              // We may be able to publish HTLC-success transactions for which we didn't have the preimage.
              // We are already watching the corresponding outputs: no need to set additional watches.
              d.localCommitPublished.foreach(lcp => {
                val commitKeys = commitment.localKeys(channelKeys)
                Closing.LocalClose.claimHtlcsWithPreimage(channelKeys, commitKeys, commitment, c.r).foreach(htlcTx => commitment.commitmentFormat match {
                  case Transactions.DefaultCommitmentFormat =>
                    txPublisher ! TxPublisher.PublishFinalTx(htlcTx, Some(lcp.commitTx.txid))
                  case _: Transactions.AnchorOutputsCommitmentFormat | _: Transactions.SimpleTaprootChannelCommitmentFormat =>
                    txPublisher ! TxPublisher.PublishReplaceableTx(htlcTx, lcp.commitTx, commitment, Closing.confirmationTarget(htlcTx))
                })
              })
              d.remoteCommitPublished.foreach(rcp => {
                val remoteCommit = commitment.remoteCommit
                val commitKeys = commitment.remoteKeys(channelKeys, remoteCommit.remotePerCommitmentPoint)
                Closing.RemoteClose.claimHtlcsWithPreimage(channelKeys, commitKeys, rcp, commitment, remoteCommit, c.r, d.finalScriptPubKey).foreach(htlcTx => {
                  txPublisher ! TxPublisher.PublishReplaceableTx(htlcTx, rcp.commitTx, commitment, Closing.confirmationTarget(htlcTx))
                })
              })
              d.nextRemoteCommitPublished.foreach(nrcp => {
                val remoteCommit = commitment.nextRemoteCommit_opt.get.commit
                val commitKeys = commitment.remoteKeys(channelKeys, remoteCommit.remotePerCommitmentPoint)
                Closing.RemoteClose.claimHtlcsWithPreimage(channelKeys, commitKeys, nrcp, commitment, remoteCommit, c.r, d.finalScriptPubKey).foreach(htlcTx => {
                  txPublisher ! TxPublisher.PublishReplaceableTx(htlcTx, nrcp.commitTx, commitment, Closing.confirmationTarget(htlcTx))
                })
              })
              d.copy(commitments = commitments1)
            case _: CMD_FAIL_HTLC | _: CMD_FAIL_MALFORMED_HTLC =>
              log.info("htlc #{} was failed downstream, recalculating watched htlc outputs", c.id)
              val lcp1 = d.localCommitPublished.map(lcp => Closing.LocalClose.ignoreFailedIncomingHtlc(c.id, lcp, commitment))
              val rcp1 = d.remoteCommitPublished.map(rcp => Closing.RemoteClose.ignoreFailedIncomingHtlc(c.id, rcp, commitment, commitment.remoteCommit))
              val nrcp1 = d.nextRemoteCommitPublished.map(nrcp => Closing.RemoteClose.ignoreFailedIncomingHtlc(c.id, nrcp, commitment, commitment.nextRemoteCommit_opt.get.commit))
              d.copy(commitments = commitments1, localCommitPublished = lcp1, remoteCommitPublished = rcp1, nextRemoteCommitPublished = nrcp1)
          }
          handleCommandSuccess(c, d1) storing()
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_CLOSING) if getTxResponse.txid == d.commitments.latest.fundingTxId =>
      // NB: waitingSinceBlock contains the block at which closing was initiated, not the block at which funding was initiated.
      // That means we're lenient with our peer and give its funding tx more time to confirm, to avoid having to store two distinct
      // waitingSinceBlock (e.g. closingWaitingSinceBlock and fundingWaitingSinceBlock).
      handleGetFundingTx(getTxResponse, d.waitingSince, d.commitments.latest.localFundingStatus.signedTx_opt)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_CLOSING) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_CLOSING) => handleFundingTimeout(d)

    case Event(w: WatchFundingConfirmedTriggered, d: DATA_CLOSING) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, commitment)) =>
          if (d.commitments.latest.fundingTxIndex == commitment.fundingTxIndex && d.commitments.latest.fundingTxId != commitment.fundingTxId) {
            // This is a corner case where:
            //  - we are using dual funding
            //  - *and* the funding tx was RBF-ed
            //  - *and* we went to CLOSING before any funding tx got confirmed (probably due to a local or remote error)
            //  - *and* an older version of the funding tx confirmed and reached min depth (it won't be re-orged out)
            //
            // This means that:
            //  - the whole current commitment tree has been double-spent and can safely be forgotten
            //  - from now on, we only need to keep track of the commitment associated to the funding tx that got confirmed
            //
            // Force-closing is our only option here, if we are in this state the channel was closing and it is too late
            // to negotiate a mutual close.
            log.info("channelId={} was confirmed at blockHeight={} txIndex={} with a previous funding txid={}", d.channelId, w.blockHeight, w.txIndex, w.tx.txid)
            val commitments2 = commitments1.copy(
              active = commitment +: Nil,
              inactive = Nil
            )
            val d1 = d.copy(commitments = commitments2)
            spendLocalCurrent(d1, d.maxClosingFeerate_opt)
          } else {
            // We're still on the same splice history, nothing to do
            stay() using d.copy(commitments = commitments1) storing()
          }
        case Left(_) => stay()
      }

    case Event(WatchFundingSpentTriggered(tx), d: DATA_CLOSING) =>
      if (d.commitments.all.map(_.fundingTxId).contains(tx.txid)) {
        // if the spending tx is itself a funding tx, this is a splice and there is nothing to do
        stay()
      } else if (d.mutualClosePublished.exists(_.tx.txid == tx.txid)) {
        // we already know about this tx, probably because we have published it ourselves after successful negotiation
        stay()
      } else if (d.mutualCloseProposed.exists(_.tx.txid == tx.txid)) {
        // at any time they can publish a closing tx with any sig we sent them: we use their version since it has their sig as well
        val closingTx = d.mutualCloseProposed.find(_.tx.txid == tx.txid).get.copy(tx = tx)
        handleMutualClose(closingTx, Right(d))
      } else if (d.localCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay()
      } else if (d.remoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay()
      } else if (d.nextRemoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay()
      } else if (d.futureRemoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay()
      } else if (tx.txid == d.commitments.latest.remoteCommit.txId) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentCurrent(tx, d)
      } else if (d.commitments.latest.nextRemoteCommit_opt.exists(_.commit.txId == tx.txid)) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentNext(tx, d)
      } else if (tx.txIn.map(_.outPoint.txid).contains(d.commitments.latest.fundingTxId)) {
        // counterparty may attempt to spend a revoked commit tx at any time
        handleRemoteSpentOther(tx, d)
      } else {
        d.commitments.resolveCommitment(tx) match {
          case Some(commitment) =>
            log.warning("a commit tx for an older commitment has been published fundingTxId={} fundingTxIndex={}", tx.txid, commitment.fundingTxIndex)
            blockchain ! WatchAlternativeCommitTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepth)
            stay()
          case None =>
            // This must be a former funding tx that has already been pruned, because watches are unordered.
            log.warning(s"ignoring unrecognized tx=${tx.txid}")
            stay()
        }
      }

    case Event(WatchAlternativeCommitTxConfirmedTriggered(_, _, tx), d: DATA_CLOSING) =>
      d.commitments.resolveCommitment(tx) match {
        case Some(commitment) =>
          log.warning("a commit tx for fundingTxIndex={} fundingTxId={} has been confirmed", commitment.fundingTxIndex, commitment.fundingTxId)
          // Funding transactions with a greater index will never confirm: we abandon them to unlock their wallet inputs,
          // which would otherwise stay locked forever in our bitcoind wallet.
          d.commitments.all
            .collect { case c: Commitment if commitment.fundingTxIndex <= c.fundingTxIndex => c.fundingTxId }
            .foreach { txId =>
              log.warning("abandoning splice txId={} (alternative commitment was confirmed)", txId)
              wallet.abandon(txId)
            }
          // Any anchor transaction that we created based on the latest local or remote commit will never confirm either
          // so we need to abandon them to unlock their wallet inputs.
          nodeParams.db.audit.listPublished(d.channelId).collect {
            case tx if tx.desc == "local-anchor" => tx
            case tx if tx.desc == "remote-anchor" => tx
          }.foreach { tx =>
            log.warning("abandoning {} txId={} (alternative commitment was confirmed)", tx.desc, tx.txId)
            wallet.abandon(tx.txId)
          }
          val commitments1 = d.commitments.copy(
            active = commitment +: Nil,
            inactive = Nil
          )
          // We reset the state to match the commitment that confirmed.
          val d1 = d.copy(commitments = commitments1)
          // This commitment may be revoked: we need to verify that its index matches our latest known index before overwriting our previous commitments.
          if (commitment.localCommit.txId == tx.txid) {
            // Our local commit has been published from the outside, it's unexpected but let's deal with it anyway.
            spendLocalCurrent(d1, d.maxClosingFeerate_opt)
          } else if (commitment.remoteCommit.txId == tx.txid && commitment.remoteCommit.index == d.commitments.remoteCommitIndex) {
            handleRemoteSpentCurrent(tx, d1)
          } else if (commitment.nextRemoteCommit_opt.exists(_.commit.txId == tx.txid) && commitment.remoteCommit.index == d.commitments.remoteCommitIndex && d.commitments.remoteNextCommitInfo.isLeft) {
            handleRemoteSpentNext(tx, d1)
          } else {
            // Our counterparty is trying to broadcast a revoked commit tx (cheating attempt).
            // We need to fail pending outgoing HTLCs, otherwise they will timeout upstream.
            // We must do it here because we're overwriting the commitments data, so we will lose all information
            // about HTLCs that are in the current commitments but were not in the revoked one.
            // We fail *all* outgoing HTLCs:
            //  - those that are not in the revoked commitment will never settle on-chain
            //  - those that are in the revoked commitment will be claimed on-chain, so it's as if they were failed
            // Note that if we already received the preimage for some of these HTLCs, we already relayed it upstream
            // so the fail command will be a no-op.
            val outgoingHtlcs = d.commitments.latest.localCommit.spec.htlcs.collect(DirectedHtlc.outgoing) ++
              d.commitments.latest.remoteCommit.spec.htlcs.collect(DirectedHtlc.incoming) ++
              d.commitments.latest.nextRemoteCommit_opt.map(_.commit.spec.htlcs.collect(DirectedHtlc.incoming)).getOrElse(Set.empty)
            outgoingHtlcs.foreach { add =>
              d.commitments.originChannels.get(add.id) match {
                case Some(origin) =>
                  log.info("failing htlc #{} paymentHash={} origin={}: overridden by revoked remote commit", add.id, add.paymentHash, origin)
                  relayer ! RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(d.channelId, add)))
                case None => ()
              }
            }
            handleRemoteSpentOther(tx, d1)
          }
        case None =>
          log.warning("ignoring unrecognized alternative commit tx={}", tx.txid)
          stay()
      }

    case Event(WatchOutputSpentTriggered(_, tx), d: DATA_CLOSING) =>
      // One of the outputs of the local/remote/revoked commit transaction or of an HTLC transaction was spent.
      // We put a watch to be notified when the transaction confirms: it may double-spend one of our transactions.
      blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepth)
      // If this is an HTLC transaction, it may reveal preimages that we haven't received yet.
      // If we successfully extract those preimages, we can forward them upstream.
      log.debug("processing bitcoin output spent by txid={} tx={}", tx.txid, tx)
      val extracted = Closing.extractPreimages(d.commitments.latest, tx)
      extracted.foreach { case (htlc, preimage) =>
        d.commitments.originChannels.get(htlc.id) match {
          case Some(origin) =>
            log.info("fulfilling htlc #{} paymentHash={} origin={}", htlc.id, htlc.paymentHash, origin)
            relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFulfill(preimage))
          case None =>
            // If we don't have the origin, it means that we already have forwarded the fulfill so that's not a big deal.
            // This can happen if they send a signature containing the fulfill, then fail the channel before we have time to sign it.
            log.warning("cannot fulfill htlc #{} paymentHash={} (origin not found)", htlc.id, htlc.paymentHash)
        }
      }
      stay()

    case Event(WatchTxConfirmedTriggered(blockHeight, _, tx), d: DATA_CLOSING) =>
      log.info("txid={} has reached mindepth, updating closing state", tx.txid)
      context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, tx))
      // first we check if this tx belongs to one of the current local/remote commits, update it and update the channel data
      val d1 = d.copy(
        localCommitPublished = d.localCommitPublished.map(localCommitPublished => {
          // If the tx is one of our HTLC txs, we now publish a 3rd-stage transaction that claims its output.
          val closingFeerate = nodeParams.onChainFeeConf.getClosingFeerate(nodeParams.currentBitcoinCoreFeerates, d.maxClosingFeerate_opt)
          val (localCommitPublished1, htlcDelayedTxs) = Closing.LocalClose.claimHtlcDelayedOutput(localCommitPublished, channelKeys, d.commitments.latest, tx, closingFeerate, d.finalScriptPubKey)
          doPublish(localCommitPublished1, htlcDelayedTxs)
          Closing.updateIrrevocablySpent(localCommitPublished1, tx)
        }),
        remoteCommitPublished = d.remoteCommitPublished.map(Closing.updateIrrevocablySpent(_, tx)),
        nextRemoteCommitPublished = d.nextRemoteCommitPublished.map(Closing.updateIrrevocablySpent(_, tx)),
        futureRemoteCommitPublished = d.futureRemoteCommitPublished.map(Closing.updateIrrevocablySpent(_, tx)),
        revokedCommitPublished = d.revokedCommitPublished.map(rvk => {
          // If the tx is one of our peer's HTLC txs, they were able to claim the output before us.
          // In that case, we immediately publish a penalty transaction spending their HTLC tx to steal their funds.
          // TODO: once we allow changing the commitment format or to_self_delay during a splice, those values may be incorrect.
          val toSelfDelay = d.commitments.latest.remoteCommitParams.toSelfDelay
          val commitmentFormat = d.commitments.latest.commitmentFormat
          val dustLimit = d.commitments.latest.localCommitParams.dustLimit
          val (rvk1, penaltyTxs) = Closing.RevokedClose.claimHtlcTxOutputs(d.commitments.channelParams, channelKeys, d.commitments.remotePerCommitmentSecrets, toSelfDelay, commitmentFormat, rvk, tx, dustLimit, nodeParams.currentBitcoinCoreFeerates, d.finalScriptPubKey)
          doPublish(rvk1, penaltyTxs)
          Closing.updateIrrevocablySpent(rvk1, tx)
        })
      )
      // if the local commitment tx just got confirmed, let's send an event telling when we will get the main output refund
      if (d1.localCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        context.system.eventStream.publish(LocalCommitConfirmed(self, remoteNodeId, d.channelId, blockHeight + d.commitments.latest.localCommitParams.toSelfDelay.toInt))
      }
      // if the local or remote commitment tx just got confirmed, we abandon anchor transactions that were created based
      // on the other commitment: they will never confirm so we must free their wallet inputs.
      if (d1.localCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        nodeParams.db.audit.listPublished(d.channelId).collect {
          case tx if tx.desc == "remote-anchor" =>
            log.warning("abandoning remote-anchor txId={} (local commit was confirmed)", tx.txId)
            wallet.abandon(tx.txId)
        }
      }
      if (d1.remoteCommitPublished.exists(_.commitTx.txid == tx.txid) || d1.nextRemoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        nodeParams.db.audit.listPublished(d.channelId).collect {
          case tx if tx.desc == "local-anchor" =>
            log.warning("abandoning local-anchor txId={} (remote commit was confirmed)", tx.txId)
            wallet.abandon(tx.txId)
        }
      }
      if (d1.futureRemoteCommitPublished.exists(_.commitTx.txid == tx.txid) || d1.revokedCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        nodeParams.db.audit.listPublished(d.channelId).collect {
          case tx if tx.desc == "local-anchor" => tx
          case tx if tx.desc == "remote-anchor" => tx
        }.foreach { tx =>
          log.warning("abandoning {} txId={} (future or revoked commitment was confirmed)", tx.desc, tx.txId)
          wallet.abandon(tx.txId)
        }
      }
      // we may need to fail some htlcs in case a commitment tx was published and they have reached the timeout threshold
      val timedOutHtlcs = Closing.isClosingTypeAlreadyKnown(d1) match {
        case Some(c: Closing.LocalClose) => Closing.trimmedOrTimedOutHtlcs(channelKeys, d.commitments.latest, c.localCommit, tx)
        case Some(c: Closing.RemoteClose) => Closing.trimmedOrTimedOutHtlcs(channelKeys, d.commitments.latest, c.remoteCommit, tx)
        case Some(_: Closing.RevokedClose) => Set.empty[UpdateAddHtlc] // revoked commitments are handled using [[overriddenOutgoingHtlcs]] below
        case Some(_: Closing.RecoveryClose) => Set.empty[UpdateAddHtlc] // we lose htlc outputs in dataloss protection scenarios (future remote commit)
        case Some(_: Closing.MutualClose) => Set.empty[UpdateAddHtlc]
        case None => Set.empty[UpdateAddHtlc]
      }
      timedOutHtlcs.foreach { add =>
        d.commitments.originChannels.get(add.id) match {
          case Some(origin) =>
            log.info("failing htlc #{} paymentHash={} origin={}: htlc timed out", add.id, add.paymentHash, origin)
            relayer ! RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(d.channelId, Set(add))))
          case None =>
            // same as for fulfilling the htlc (no big deal)
            log.info("cannot fail timed out htlc #{} paymentHash={} (origin not found)", add.id, add.paymentHash)
        }
      }
      // we also need to fail outgoing htlcs that we know will never reach the blockchain
      // if we previously received the preimage, we have already relayed it upstream and the command below will be ignored
      Closing.overriddenOutgoingHtlcs(d, tx).foreach { add =>
        d.commitments.originChannels.get(add.id) match {
          case Some(origin) =>
            log.info("failing htlc #{} paymentHash={} origin={}: overridden by local commit", add.id, add.paymentHash, origin)
            relayer ! RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(d.channelId, add)))
          case None =>
            // same as for fulfilling the htlc (no big deal)
            log.info("cannot fail overridden htlc #{} paymentHash={} (origin not found)", add.id, add.paymentHash)
        }
      }
      // for our outgoing payments, let's send events if we know that they will settle on chain
      Closing
        .onChainOutgoingHtlcs(d.commitments.latest.localCommit, d.commitments.latest.remoteCommit, d.commitments.latest.nextRemoteCommit_opt.map(_.commit), tx)
        .map(add => (add, d.commitments.originChannels.get(add.id).map(_.upstream).collect { case Upstream.Local(id) => id })) // we resolve the payment id if this was a local payment
        .collect { case (add, Some(id)) => context.system.eventStream.publish(PaymentSettlingOnChain(id, amount = add.amountMsat, add.paymentHash)) }
      // finally, if one of the unilateral closes is done, we move to CLOSED state, otherwise we stay()
      Closing.isClosed(d1, Some(tx)) match {
        case Some(closingType) =>
          log.info("channel closed (type={})", EventType.Closed(closingType).label)
          context.system.eventStream.publish(ChannelClosed(self, d.channelId, closingType, d.commitments))
          goto(CLOSED) using d1 storing()
        case None =>
          stay() using d1 storing()
      }

    case Event(_: ChannelReestablish, d: DATA_CLOSING) =>
      // they haven't detected that we were closing and are trying to reestablish a connection
      // we give them one of the published txes as a hint
      // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
      val exc = FundingTxSpent(d.channelId, d.spendingTxs.head.txid)
      val error = Error(d.channelId, exc.getMessage)
      stay() sending error

    case Event(c: CMD_CLOSE, d: DATA_CLOSING) => handleCommandError(ClosingAlreadyInProgress(d.channelId), c)

    case Event(c: CMD_BUMP_FORCE_CLOSE_FEE, d: DATA_CLOSING) =>
      val commitmentFormat = d.commitments.latest.commitmentFormat
      commitmentFormat match {
        case _: Transactions.AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
          val commitment = d.commitments.latest
          val fundingKey = channelKeys.fundingKey(commitment.fundingTxIndex)
          val localAnchor_opt = for {
            lcp <- d.localCommitPublished
            commitKeys = commitment.localKeys(channelKeys)
            anchorTx <- Closing.LocalClose.claimAnchor(fundingKey, commitKeys, lcp.commitTx, commitmentFormat)
          } yield PublishReplaceableTx(anchorTx, lcp.commitTx, commitment, c.confirmationTarget)
          val remoteAnchor_opt = for {
            rcp <- d.remoteCommitPublished
            commitKeys = commitment.remoteKeys(channelKeys, commitment.remoteCommit.remotePerCommitmentPoint)
            anchorTx <- Closing.RemoteClose.claimAnchor(fundingKey, commitKeys, rcp.commitTx, commitmentFormat)
          } yield PublishReplaceableTx(anchorTx, rcp.commitTx, commitment, c.confirmationTarget)
          val nextRemoteAnchor_opt = for {
            nrcp <- d.nextRemoteCommitPublished
            commitKeys = commitment.remoteKeys(channelKeys, commitment.nextRemoteCommit_opt.get.commit.remotePerCommitmentPoint)
            anchorTx <- Closing.RemoteClose.claimAnchor(fundingKey, commitKeys, nrcp.commitTx, commitmentFormat)
          } yield PublishReplaceableTx(anchorTx, nrcp.commitTx, commitment, c.confirmationTarget)
          // We favor the remote commitment(s) because they're more interesting than the local commitment (no CSV delays).
          if (remoteAnchor_opt.nonEmpty) {
            remoteAnchor_opt.foreach { publishTx => txPublisher ! publishTx }
            c.replyTo ! RES_SUCCESS(c, d.channelId)
          } else if (nextRemoteAnchor_opt.nonEmpty) {
            nextRemoteAnchor_opt.foreach { publishTx => txPublisher ! publishTx }
            c.replyTo ! RES_SUCCESS(c, d.channelId)
          } else if (localAnchor_opt.nonEmpty) {
            localAnchor_opt.foreach { publishTx => txPublisher ! publishTx }
            c.replyTo ! RES_SUCCESS(c, d.channelId)
          } else {
            log.warning("cannot bump force-close fees, local or remote commit not published")
            c.replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "rbf-force-close", stateName))
          }
          stay()
        case _ =>
          log.warning("cannot bump force-close fees, channel is not using anchor outputs")
          c.replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "rbf-force-close", stateName))
          stay()
      }

    case Event(e: Error, d: DATA_CLOSING) => handleRemoteError(e, d)

    case Event(_: Shutdown, _) =>
      log.debug("ignoring shutdown, closing transaction already published")
      stay()

    case Event(_: ClosingSigned, _) =>
      log.debug("ignoring closing_signed, closing transaction already published")
      stay()

    case Event(_: AnnouncementSignatures, _) =>
      log.debug("ignoring announcement_signatures, channel is closing")
      stay()

    case Event(INPUT_DISCONNECTED | _: INPUT_RECONNECTED, _) => stay() // we don't really care at this point
  })

  when(CLOSED)(handleExceptions {
    case Event(Symbol("shutdown"), _) =>
      stateData match {
        case d: PersistentChannelData =>
          log.info(s"deleting database record for channelId=${d.channelId}")
          nodeParams.db.channels.removeChannel(d.channelId)
        case _: TransientChannelData => // nothing was stored in the DB
      }
      log.info("shutting down")
      stop(FSM.Normal)

    case Event(MakeFundingTxResponse(fundingTx, _, _), _) =>
      // this may happen if connection is lost, or remote sends an error while we were waiting for the funding tx to be created by our wallet
      // in that case we rollback the tx
      wallet.rollback(fundingTx)
      stay()

    case Event(w: WatchTriggered, _) =>
      log.debug("ignoring watch event, channel is closed (event={})", w)
      stay()

    case Event(_: Shutdown, _) =>
      log.debug("ignoring shutdown, channel is closed")
      stay()

    case Event(_: ClosingSigned, _) =>
      log.debug("ignoring closing_signed, channel is closed")
      stay()

    case Event(_: AnnouncementSignatures, _) =>
      log.debug("ignoring announcement_signatures, channel is closed")
      stay()

    case Event(INPUT_DISCONNECTED | _: INPUT_RECONNECTED, _) => stay() // we are disconnected, but it doesn't matter anymore
  })

  when(OFFLINE)(handleExceptions {
    case Event(INPUT_RECONNECTED(r, localInit, remoteInit), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) =>
      activeConnection = r
      // they already proved that we have an outdated commitment
      // there isn't much to do except asking them again to publish their current commitment by sending an error
      val exc = PleasePublishYourCommitment(d.channelId)
      val error = Error(d.channelId, exc.getMessage)
      val d1 = Helpers.updateFeatures(d, localInit, remoteInit)
      goto(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) using d1 sending error

    case Event(INPUT_RECONNECTED(r, localInit, remoteInit), d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      activeConnection = r
      val myFirstPerCommitmentPoint = channelKeys.commitmentPoint(0)
      val nextFundingTlv: Set[ChannelReestablishTlv] = Set(ChannelReestablishTlv.NextFundingTlv(d.signingSession.fundingTxId))
      val nonceTlvs = d.signingSession.fundingParams.commitmentFormat match {
        case _: SegwitV0CommitmentFormat => Set.empty
        case _: SimpleTaprootChannelCommitmentFormat =>
          val localFundingKey = channelKeys.fundingKey(0)
          val remoteFundingPubKey = d.signingSession.fundingParams.remoteFundingPubKey
          val currentCommitNonce_opt = d.signingSession.localCommit match {
            case Left(_) => Some(NonceGenerator.verificationNonce(d.signingSession.fundingTxId, localFundingKey, remoteFundingPubKey, 0))
            case Right(_) => None
          }
          val nextCommitNonce = NonceGenerator.verificationNonce(d.signingSession.fundingTxId, localFundingKey, remoteFundingPubKey, 1)
          Set(
            Some(ChannelReestablishTlv.NextLocalNoncesTlv(List(d.signingSession.fundingTxId -> nextCommitNonce.publicNonce))),
            currentCommitNonce_opt.map(n => ChannelReestablishTlv.CurrentCommitNonceTlv(n.publicNonce)),
          ).flatten[ChannelReestablishTlv]
      }
      val channelReestablish = ChannelReestablish(
        channelId = d.channelId,
        nextLocalCommitmentNumber = d.signingSession.nextLocalCommitmentNumber,
        nextRemoteRevocationNumber = 0,
        yourLastPerCommitmentSecret = PrivateKey(ByteVector32.Zeroes),
        myCurrentPerCommitmentPoint = myFirstPerCommitmentPoint,
        TlvStream(nextFundingTlv ++ nonceTlvs),
      )
      val d1 = Helpers.updateFeatures(d, localInit, remoteInit)
      goto(SYNCING) using d1 sending channelReestablish

    case Event(INPUT_RECONNECTED(r, localInit, remoteInit), d: ChannelDataWithCommitments) =>
      activeConnection = r
      val remotePerCommitmentSecrets = d.commitments.remotePerCommitmentSecrets
      val yourLastPerCommitmentSecret = remotePerCommitmentSecrets.lastIndex.flatMap(remotePerCommitmentSecrets.getHash).getOrElse(ByteVector32.Zeroes)
      val myCurrentPerCommitmentPoint = channelKeys.commitmentPoint(d.commitments.localCommitIndex)
      // If we disconnected while signing a funding transaction, we may need our peer to retransmit their commit_sig.
      val nextLocalCommitmentNumber = d match {
        case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => d.status match {
          case DualFundingStatus.RbfWaitingForSigs(status) => status.nextLocalCommitmentNumber
          case _ => d.commitments.localCommitIndex + 1
        }
        case d: DATA_NORMAL => d.spliceStatus match {
          case SpliceStatus.SpliceWaitingForSigs(status) => status.nextLocalCommitmentNumber
          case _ => d.commitments.localCommitIndex + 1
        }
        case _ => d.commitments.localCommitIndex + 1
      }
      // If we disconnected while signing a funding transaction, we may need our peer to (re)transmit their tx_signatures.
      val rbfTlv: Set[ChannelReestablishTlv] = d match {
        case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => d.status match {
          case DualFundingStatus.RbfWaitingForSigs(status) => Set(ChannelReestablishTlv.NextFundingTlv(status.fundingTx.txId))
          case _ => d.latestFundingTx.sharedTx match {
            case _: InteractiveTxBuilder.PartiallySignedSharedTransaction => Set(ChannelReestablishTlv.NextFundingTlv(d.latestFundingTx.sharedTx.txId))
            case _: InteractiveTxBuilder.FullySignedSharedTransaction => Set.empty
          }
        }
        case d: DATA_NORMAL => d.spliceStatus match {
          case SpliceStatus.SpliceWaitingForSigs(status) => Set(ChannelReestablishTlv.NextFundingTlv(status.fundingTx.txId))
          case _ => d.commitments.latest.localFundingStatus match {
            case LocalFundingStatus.DualFundedUnconfirmedFundingTx(fundingTx: PartiallySignedSharedTransaction, _, _, _) => Set(ChannelReestablishTlv.NextFundingTlv(fundingTx.txId))
            case _ => Set.empty
          }
        }
        case _ => Set.empty
      }
      val lastFundingLockedTlvs: Set[ChannelReestablishTlv] = if (d.commitments.remoteChannelParams.initFeatures.hasFeature(Features.SplicePrototype)) {
        d.commitments.lastLocalLocked_opt.map(c => ChannelReestablishTlv.MyCurrentFundingLockedTlv(c.fundingTxId)).toSet ++
          d.commitments.lastRemoteLocked_opt.map(c => ChannelReestablishTlv.YourLastFundingLockedTlv(c.fundingTxId)).toSet
      } else Set.empty

      // We send our verification nonces for all active commitments.
      val nextCommitNonces: Map[TxId, IndividualNonce] = d.commitments.active.flatMap(c => {
        c.commitmentFormat match {
          case _: SegwitV0CommitmentFormat => None
          case _: SimpleTaprootChannelCommitmentFormat =>
            val localFundingKey = channelKeys.fundingKey(c.fundingTxIndex)
            Some(c.fundingTxId -> NonceGenerator.verificationNonce(c.fundingTxId, localFundingKey, c.remoteFundingPubKey, d.commitments.localCommitIndex + 1).publicNonce)
        }
      }).toMap
      // If an interactive-tx session hasn't been fully signed, we also need to include the corresponding nonces.
      val (interactiveTxCurrentCommitNonce_opt, interactiveTxNextCommitNonce): (Option[IndividualNonce], Map[TxId, IndividualNonce]) = d match {
        case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => d.status match {
          case DualFundingStatus.RbfWaitingForSigs(signingSession) if signingSession.fundingParams.commitmentFormat.isInstanceOf[TaprootCommitmentFormat] =>
            val nextCommitNonce = Map(signingSession.fundingTxId -> signingSession.nextCommitNonce(channelKeys).publicNonce)
            (signingSession.currentCommitNonce_opt(channelKeys).map(_.publicNonce), nextCommitNonce)
          case _ => (None, Map.empty)
        }
        case d: DATA_NORMAL => d.spliceStatus match {
          case SpliceStatus.SpliceWaitingForSigs(signingSession) if signingSession.fundingParams.commitmentFormat.isInstanceOf[TaprootCommitmentFormat] =>
            val nextCommitNonce = Map(signingSession.fundingTxId -> signingSession.nextCommitNonce(channelKeys).publicNonce)
            (signingSession.currentCommitNonce_opt(channelKeys).map(_.publicNonce), nextCommitNonce)
          case _ => (None, Map.empty)
        }
        case _ => (None, Map.empty)
      }
      val nonceTlvs = Set(
        interactiveTxCurrentCommitNonce_opt.map(nonce => ChannelReestablishTlv.CurrentCommitNonceTlv(nonce)),
        if (nextCommitNonces.nonEmpty || interactiveTxNextCommitNonce.nonEmpty) Some(ChannelReestablishTlv.NextLocalNoncesTlv(nextCommitNonces.toSeq ++ interactiveTxNextCommitNonce.toSeq)) else None
      ).flatten

      val channelReestablish = ChannelReestablish(
        channelId = d.channelId,
        nextLocalCommitmentNumber = nextLocalCommitmentNumber,
        nextRemoteRevocationNumber = d.commitments.remoteCommitIndex,
        yourLastPerCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
        myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint,
        tlvStream = TlvStream(rbfTlv ++ lastFundingLockedTlvs ++ nonceTlvs)
      )
      // we update local/remote connection-local global/local features, we don't persist it right now
      val d1 = Helpers.updateFeatures(d, localInit, remoteInit)
      goto(SYNCING) using d1 sending channelReestablish

    case Event(ProcessCurrentBlockHeight(c), d: ChannelDataWithCommitments) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates.BitcoinCore, d: ChannelDataWithCommitments) => handleCurrentFeerateDisconnected(c, d)

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) => handleAddDisconnected(c, d)

    case Event(e: HtlcSettlementCommand, _) =>
      log.debug("cannot settle htlc_id={}, channel is offline", e.id)
      stay()

    case Event(_: CMD_SIGN, _) => stay()

    case Event(_: CMD_UPDATE_FEE, _) => stay()

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) => handleUpdateRelayFeeDisconnected(c, d)

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.latest.fundingTxId => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx_opt)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleDualFundingDoubleSpent(e, d)
  })

  when(SYNCING)(handleExceptions {
    case Event(channelReestablish: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      Helpers.Syncing.checkCommitNonces(channelReestablish, d.commitments, None) match {
        case Some(f) => handleLocalError(f, d, Some(channelReestablish))
        case None =>
          remoteNextCommitNonces = channelReestablish.nextCommitNonces
          goto(WAIT_FOR_FUNDING_CONFIRMED)
      }

    case Event(channelReestablish: ChannelReestablish, d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED) =>
      d.signingSession.fundingParams.commitmentFormat match {
        case _: SimpleTaprootChannelCommitmentFormat if !channelReestablish.nextCommitNonces.contains(d.signingSession.fundingTxId) =>
          val f = MissingCommitNonce(d.channelId, d.signingSession.fundingTxId, commitmentNumber = 1)
          handleLocalError(f, d, Some(channelReestablish))
        case _ =>
          remoteNextCommitNonces = channelReestablish.nextCommitNonces
          channelReestablish.nextFundingTxId_opt match {
            case Some(fundingTxId) if fundingTxId == d.signingSession.fundingTx.txId && channelReestablish.nextLocalCommitmentNumber == 0 =>
              // They haven't received our commit_sig: we retransmit it, and will send our tx_signatures once we've received
              // their commit_sig or their tx_signatures (depending on who must send tx_signatures first).
              val fundingParams = d.signingSession.fundingParams
              val remoteNonce_opt = channelReestablish.currentCommitNonce_opt
              d.signingSession.remoteCommit.sign(d.channelParams, d.signingSession.remoteCommitParams, channelKeys, d.signingSession.fundingTxIndex, fundingParams.remoteFundingPubKey, d.signingSession.commitInput(channelKeys), fundingParams.commitmentFormat, remoteNonce_opt) match {
                case Left(e) => handleLocalError(e, d, Some(channelReestablish))
                case Right(commitSig) => goto(WAIT_FOR_DUAL_FUNDING_SIGNED) sending commitSig
              }
            case _ => goto(WAIT_FOR_DUAL_FUNDING_SIGNED)
          }
      }

    case Event(channelReestablish: ChannelReestablish, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      val pendingRbf_opt = d.status match {
        // Note that we only consider RBF attempts that are also pending for our peer: otherwise it means we have
        // disconnected before they sent their commit_sig, in which case they will abort the RBF attempt on reconnection.
        case DualFundingStatus.RbfWaitingForSigs(signingSession) if channelReestablish.nextFundingTxId_opt.contains(signingSession.fundingTxId) => Some(signingSession)
        case _ => None
      }
      Helpers.Syncing.checkCommitNonces(channelReestablish, d.commitments, pendingRbf_opt) match {
        case Some(f) => handleLocalError(f, d, Some(channelReestablish))
        case None =>
          remoteNextCommitNonces = channelReestablish.nextCommitNonces
          channelReestablish.nextFundingTxId_opt match {
            case Some(fundingTxId) =>
              d.status match {
                case DualFundingStatus.RbfWaitingForSigs(signingSession) if signingSession.fundingTx.txId == fundingTxId =>
                  if (channelReestablish.nextLocalCommitmentNumber == 0) {
                    // They haven't received our commit_sig: we retransmit it.
                    // We're also waiting for signatures from them, and will send our tx_signatures once we receive them.
                    val fundingParams = signingSession.fundingParams
                    val remoteNonce_opt = channelReestablish.currentCommitNonce_opt
                    signingSession.remoteCommit.sign(d.commitments.channelParams, signingSession.remoteCommitParams, channelKeys, signingSession.fundingTxIndex, signingSession.fundingParams.remoteFundingPubKey, signingSession.commitInput(channelKeys), fundingParams.commitmentFormat, remoteNonce_opt) match {
                      case Left(e) => handleLocalError(e, d, Some(channelReestablish))
                      case Right(commitSig) => goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) sending commitSig
                    }
                  } else {
                    // They have already received our commit_sig, but we were waiting for them to send either commit_sig or
                    // tx_signatures first. We wait for their message before sending our tx_signatures.
                    goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED)
                  }
                case _ if d.latestFundingTx.sharedTx.txId == fundingTxId =>
                  // We've already received their commit_sig and sent our tx_signatures. We retransmit our tx_signatures
                  // and our commit_sig if they haven't received it already.
                  if (channelReestablish.nextLocalCommitmentNumber == 0) {
                    val remoteNonce_opt = channelReestablish.currentCommitNonce_opt
                    d.commitments.latest.remoteCommit.sign(d.commitments.channelParams, d.commitments.latest.remoteCommitParams, channelKeys, d.commitments.latest.fundingTxIndex, d.commitments.latest.remoteFundingPubKey, d.commitments.latest.commitInput(channelKeys), d.commitments.latest.commitmentFormat, remoteNonce_opt) match {
                      case Left(e) => handleLocalError(e, d, Some(channelReestablish))
                      case Right(commitSig) => goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) sending Seq(commitSig, d.latestFundingTx.sharedTx.localSigs)
                    }
                  } else {
                    goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) sending d.latestFundingTx.sharedTx.localSigs
                  }
                case _ =>
                  // The fundingTxId must be for an RBF attempt that we didn't store (we got disconnected before receiving
                  // their tx_complete): we tell them to abort that RBF attempt.
                  goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) using d.copy(status = DualFundingStatus.RbfAborted) sending TxAbort(d.channelId, RbfAttemptAborted(d.channelId).getMessage)
              }
            case None => goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED)
          }
      }

    case Event(channelReestablish: ChannelReestablish, d: DATA_WAIT_FOR_CHANNEL_READY) =>
      Helpers.Syncing.checkCommitNonces(channelReestablish, d.commitments, None) match {
        case Some(f) => handleLocalError(f, d, Some(channelReestablish))
        case None =>
          remoteNextCommitNonces = channelReestablish.nextCommitNonces
          val channelReady = createChannelReady(d.aliases, d.commitments)
          goto(WAIT_FOR_CHANNEL_READY) sending channelReady
      }

    case Event(channelReestablish: ChannelReestablish, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) =>
      Helpers.Syncing.checkCommitNonces(channelReestablish, d.commitments, None) match {
        case Some(f) => handleLocalError(f, d, Some(channelReestablish))
        case None =>
          remoteNextCommitNonces = channelReestablish.nextCommitNonces
          val channelReady = createChannelReady(d.aliases, d.commitments)
          // We've already received their commit_sig and sent our tx_signatures. We retransmit our tx_signatures
          // and our commit_sig if they haven't received it already.
          channelReestablish.nextFundingTxId_opt match {
            case Some(fundingTxId) if fundingTxId == d.commitments.latest.fundingTxId =>
              d.commitments.latest.localFundingStatus.localSigs_opt match {
                case Some(txSigs) if channelReestablish.nextLocalCommitmentNumber == 0 =>
                  log.info("re-sending commit_sig and tx_signatures for fundingTxIndex={} fundingTxId={}", d.commitments.latest.fundingTxIndex, d.commitments.latest.fundingTxId)
                  val remoteNonce_opt = channelReestablish.currentCommitNonce_opt
                  d.commitments.latest.remoteCommit.sign(d.commitments.channelParams, d.commitments.latest.remoteCommitParams, channelKeys, d.commitments.latest.fundingTxIndex, d.commitments.latest.remoteFundingPubKey, d.commitments.latest.commitInput(channelKeys), d.commitments.latest.commitmentFormat, remoteNonce_opt) match {
                    case Left(e) => handleLocalError(e, d, Some(channelReestablish))
                    case Right(commitSig) => goto(WAIT_FOR_DUAL_FUNDING_READY) sending Seq(commitSig, txSigs, channelReady)
                  }
                case Some(txSigs) =>
                  log.info("re-sending tx_signatures for fundingTxIndex={} fundingTxId={}", d.commitments.latest.fundingTxIndex, d.commitments.latest.fundingTxId)
                  goto(WAIT_FOR_DUAL_FUNDING_READY) sending Seq(txSigs, channelReady)
                case None =>
                  log.warning("cannot retransmit tx_signatures, we don't have them (status={})", d.commitments.latest.localFundingStatus)
                  goto(WAIT_FOR_DUAL_FUNDING_READY) sending channelReady
              }
            case _ => goto(WAIT_FOR_DUAL_FUNDING_READY) sending channelReady
          }
      }

    case Event(channelReestablish: ChannelReestablish, d: DATA_NORMAL) =>
      Syncing.checkSync(channelKeys, d.commitments, channelReestablish) match {
        case syncFailure: SyncResult.Failure =>
          handleSyncFailure(channelReestablish, syncFailure, d)
        case syncSuccess: SyncResult.Success =>
          // normal case, our data is up-to-date
          var sendQueue = Queue.empty[LightningMessage]
          // We re-send channel_ready and announcement_signatures for the initial funding transaction if necessary.
          val (channelReady_opt, announcementSigs_opt) = resendChannelReadyIfNeeded(channelReestablish, d)
          sendQueue = sendQueue ++ channelReady_opt.toSeq ++ announcementSigs_opt.toSeq
          // If we disconnected in the middle of a signing a splice transaction, we re-send our signatures or abort.
          val (spliceStatus1, spliceMessages) = resumeSpliceSigningSessionIfNeeded(channelReestablish, d)
          sendQueue = sendQueue ++ spliceMessages

          // Prune previous funding transactions and RBF attempts if we already sent splice_locked for the last funding
          // transaction that is also locked by our counterparty; we either missed their splice_locked or it confirmed
          // while disconnected.
          val commitments1 = channelReestablish.myCurrentFundingLocked_opt
            .flatMap(remoteFundingTxLocked => d.commitments.updateRemoteFundingStatus(remoteFundingTxLocked, d.lastAnnouncedFundingTxId_opt).toOption.map(_._1))
            .getOrElse(d.commitments)
            // We then clean up unsigned updates that haven't been received before the disconnection.
            .discardUnsignedUpdates()

          // If there is a pending splice, we need to receive nonces for the corresponding transaction if we're using taproot.
          val pendingSplice_opt = spliceStatus1 match {
            // Note that we only consider splices that are also pending for our peer: otherwise it means we have disconnected
            // before they sent their commit_sig, in which case they will abort the splice attempt on reconnection.
            case SpliceStatus.SpliceWaitingForSigs(signingSession) if channelReestablish.nextFundingTxId_opt.contains(signingSession.fundingTxId) => Some(signingSession)
            case _ => None
          }
          Helpers.Syncing.checkCommitNonces(channelReestablish, commitments1, pendingSplice_opt) match {
            case Some(f) => handleLocalError(f, d, Some(channelReestablish))
            case None =>
              remoteNextCommitNonces = channelReestablish.nextCommitNonces
              // We re-send our latest splice_locked if needed.
              val spliceLocked_opt = resendSpliceLockedIfNeeded(channelReestablish, commitments1, d.lastAnnouncement_opt)
              sendQueue = sendQueue ++ spliceLocked_opt.toSeq
              // We may need to retransmit updates and/or commit_sig and/or revocation to resume the channel.
              sendQueue = sendQueue ++ syncSuccess.retransmit

              commitments1.remoteNextCommitInfo match {
                case Left(_) =>
                  // we expect them to (re-)send the revocation immediately
                  startSingleTimer(RevocationTimeout.toString, RevocationTimeout(commitments1.remoteCommitIndex, peer), nodeParams.channelConf.revocationTimeout)
                case _ => ()
              }

              // do I have something to sign?
              if (commitments1.changes.localHasChanges) {
                self ! CMD_SIGN()
              }

              // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
              d.localShutdown.foreach {
                localShutdown =>
                  log.debug("re-sending local_shutdown")
                  sendQueue = sendQueue :+ localShutdown
              }

              if (d.commitments.announceChannel) {
                // we will re-enable the channel after some delay to prevent flappy updates in case the connection is unstable
                startSingleTimer(Reconnected.toString, BroadcastChannelUpdate(Reconnected), 10 seconds)
              } else {
                // except for private channels where our peer is likely a mobile wallet: they will stay online only for a short period of time,
                // so we need to re-enable them immediately to ensure we can route payments to them. It's also less of a problem to frequently
                // refresh the channel update for private channels, since we won't broadcast it to the rest of the network.
                self ! BroadcastChannelUpdate(Reconnected)
              }

              // We usually handle feerate updates once per block (~10 minutes), but when our remote is a mobile wallet that
              // only briefly connects and then disconnects, we may never have the opportunity to send our `update_fee`, so
              // we send it (if needed) when reconnected.
              val shutdownInProgress = d.localShutdown.nonEmpty || d.remoteShutdown.nonEmpty
              if (d.commitments.localChannelParams.paysCommitTxFees && !shutdownInProgress) {
                val currentFeeratePerKw = d.commitments.latest.localCommit.spec.commitTxFeerate
                val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, d.commitments.latest.commitmentFormat)
                if (nodeParams.onChainFeeConf.shouldUpdateFee(currentFeeratePerKw, networkFeeratePerKw)) {
                  self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
                }
              }

              // We tell the peer that the channel is ready to process payments that may be queued.
              if (!shutdownInProgress) {
                val fundingTxIndex = commitments1.active.map(_.fundingTxIndex).min
                peer ! ChannelReadyForPayments(self, remoteNodeId, d.channelId, fundingTxIndex)
              }

              goto(NORMAL) using d.copy(commitments = commitments1, spliceStatus = spliceStatus1) sending sendQueue
          }
      }

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) => handleAddDisconnected(c, d)

    case Event(e: HtlcSettlementCommand, _) =>
      log.debug("cannot settle htlc_id={}, channel is syncing", e.id)
      stay()

    case Event(_: CMD_SIGN, _) => stay()

    case Event(_: CMD_UPDATE_FEE, _) => stay()

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) => handleUpdateRelayFeeDisconnected(c, d)

    case Event(channelReestablish: ChannelReestablish, d: DATA_SHUTDOWN) =>
      Helpers.Syncing.checkCommitNonces(channelReestablish, d.commitments, None) match {
        case Some(f) => handleLocalError(f, d, Some(channelReestablish))
        case None =>
          remoteNextCommitNonces = channelReestablish.nextCommitNonces
          Syncing.checkSync(channelKeys, d.commitments, channelReestablish) match {
            case syncFailure: SyncResult.Failure =>
              handleSyncFailure(channelReestablish, syncFailure, d)
            case syncSuccess: SyncResult.Success =>
              val commitments1 = d.commitments.discardUnsignedUpdates()
              val sendQueue = Queue.empty[LightningMessage] ++ syncSuccess.retransmit :+ d.localShutdown
              // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
              goto(SHUTDOWN) using d.copy(commitments = commitments1) sending sendQueue
          }
      }

    case Event(_: ChannelReestablish, d: DATA_NEGOTIATING) =>
      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      // negotiation restarts from the beginning, and is initialized by the channel initiator
      // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
      if (d.commitments.localChannelParams.paysClosingFees) {
        // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
        val (closingTx, closingSigned) = Closing.MutualClose.makeFirstClosingTx(channelKeys, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.currentFeeratesForFundingClosing, nodeParams.onChainFeeConf, None)
        val closingTxProposed1 = d.closingTxProposed :+ List(ClosingTxProposed(closingTx, closingSigned))
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) storing() sending d.localShutdown :: closingSigned :: Nil
      } else {
        // we start a new round of negotiation
        val closingTxProposed1 = if (d.closingTxProposed.last.isEmpty) d.closingTxProposed else d.closingTxProposed :+ List()
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) sending d.localShutdown
      }

    case Event(_: ChannelReestablish, d: DATA_NEGOTIATING_SIMPLE) =>
      // We retransmit our shutdown: we may have updated our script and they may not have received it.
      val localShutdown = createShutdown(d.commitments, d.localScriptPubKey)
      goto(NEGOTIATING_SIMPLE) using d sending localShutdown

    // This handler is a workaround for an issue in lnd: starting with versions 0.10 / 0.11, they sometimes fail to send
    // a channel_reestablish when reconnecting a channel that recently got confirmed, and instead send a channel_ready
    // first and then go silent. This is due to a race condition on their side, so we trigger a reconnection, hoping that
    // we will eventually receive their channel_reestablish.
    case Event(_: ChannelReady, d) =>
      log.warning("received channel_ready before channel_reestablish (known lnd bug): disconnecting...")
      // NB: we use a small delay to ensure we've sent our warning before disconnecting.
      context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
      stay() sending Warning(d.channelId, "spec violation: you sent channel_ready before channel_reestablish")

    // This handler is a workaround for an issue in lnd similar to the one above: they sometimes send announcement_signatures
    // before channel_reestablish, which is a minor spec violation. It doesn't halt the channel, we can simply postpone
    // that message.
    case Event(remoteAnnSigs: AnnouncementSignatures, d) =>
      log.debug("received announcement_signatures before channel_reestablish (known lnd bug): delaying...")
      context.system.scheduler.scheduleOnce(5 seconds, self, remoteAnnSigs)
      stay() sending Warning(d.channelId, "spec violation: you sent announcement_signatures before channel_reestablish")

    case Event(ProcessCurrentBlockHeight(c), d: ChannelDataWithCommitments) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates.BitcoinCore, d: ChannelDataWithCommitments) => handleCurrentFeerateDisconnected(c, d)

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.latest.fundingTxId => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx_opt)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleDualFundingDoubleSpent(e, d)

    case Event(e: Error, d: PersistentChannelData) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)(PartialFunction.empty[Event, State])

  when(ERR_INFORMATION_LEAK)(PartialFunction.empty[Event, State])

  whenUnhandled {

    case Event(INPUT_DISCONNECTED, _) => goto(OFFLINE)

    case Event(c: CMD_GET_CHANNEL_STATE, _) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_GET_CHANNEL_STATE(stateName)
      stay()

    case Event(c: CMD_GET_CHANNEL_DATA, _) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_GET_CHANNEL_DATA(stateData)
      stay()

    case Event(c: CMD_GET_CHANNEL_INFO, _) =>
      c.replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, stateData.channelId, self, stateName, stateData)
      stay()

    case Event(c: CMD_ADD_HTLC, d: PersistentChannelData) =>
      log.debug(s"rejecting htlc request in state=$stateName")
      val error = ChannelUnavailable(d.channelId)
      handleAddHtlcCommandError(c, error, None) // we don't provide a channel_update: this will be a permanent channel failure

    case Event(c: CMD_CLOSE, d) => handleCommandError(CommandUnavailableInThisState(d.channelId, "close", stateName), c)

    case Event(c: CMD_FORCECLOSE, d) =>
      d match {
        case data: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED =>
          rollbackFundingAttempt(data.signingSession.fundingTx.tx, Nil)
          handleFastClose(c, d.channelId)
        case data: ChannelDataWithCommitments =>
          val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
          val failure = ForcedLocalCommit(d.channelId)
          c.resetFundingTxIndex_opt match {
            case Some(resetFundingTxIndex) =>
              val isActive = data.commitments.active.exists(_.fundingTxIndex == resetFundingTxIndex)
              val nextFundingUnconfirmed = data.commitments.active.filter(_.fundingTxIndex > resetFundingTxIndex).forall(_.localFundingStatus.isInstanceOf[LocalFundingStatus.UnconfirmedFundingTx])
              if (isActive && nextFundingUnconfirmed) {
                // The commitment hasn't been deactivated yet and more recent funding transactions are unconfirmed, so
                // we may try force-closing using this commitment index. Note however that if a more recent funding
                // transaction confirms first, our closing attempt will permanently fail, we will have lost data about
                // the latest confirmed funding transaction and may not be able to get our funds back. Use with extreme
                // caution!
                log.warning("force-closing with fundingTxIndex reset to {} (concurrent funding transactions: {})", resetFundingTxIndex, data.commitments.active.filter(_.fundingTxIndex > resetFundingTxIndex).map(_.fundingTxId).mkString(", "))
                replyTo ! RES_SUCCESS(c, data.channelId)
                val commitments1 = data.commitments.copy(active = data.commitments.active.filter(_.fundingTxIndex <= resetFundingTxIndex))
                val resetData = Helpers.updateCommitments(data, commitments1)
                handleLocalError(failure, resetData, Some(c))
              } else {
                handleCommandError(CommandUnavailableInThisState(d.channelId, "forcecloseresetfundingindex", stateName), c)
              }
            case None =>
              replyTo ! RES_SUCCESS(c, data.channelId)
              handleLocalError(failure, data, Some(c))
          }
        case _ =>
          handleCommandError(CommandUnavailableInThisState(d.channelId, "forceclose", stateName), c)
      }

    // In states where we don't explicitly handle this command, we won't broadcast a new channel update immediately,
    // but we will once we get back to NORMAL, because the updated fees have been saved to our peers DB.
    case Event(c: CMD_UPDATE_RELAY_FEE, d) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_SUCCESS(c, d.channelId)
      stay()

    case Event(c: CMD_BUMP_FUNDING_FEE, d) =>
      c.replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "rbf", stateName))
      stay()

    case Event(c: CMD_BUMP_FORCE_CLOSE_FEE, d) =>
      c.replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "rbf-force-close", stateName))
      stay()

    case Event(c: CMD_SPLICE, d) =>
      c.replyTo ! RES_FAILURE(c, CommandUnavailableInThisState(d.channelId, "splice", stateName))
      stay()

    // at restore, if the configuration has changed, the channel will send a command to itself to update the relay fees
    case Event(RES_SUCCESS(_: CMD_UPDATE_RELAY_FEE, channelId), d: DATA_NORMAL) if channelId == d.channelId => stay()

    // we only care about this event in NORMAL and SHUTDOWN state, and there may be cases where the task is not cancelled
    case Event(_: RevocationTimeout, _) => stay()

    // we reschedule with a random delay to prevent herd effect when there are a lot of channels
    case Event(c: CurrentBlockHeight, _) =>
      context.system.scheduler.scheduleOnce(blockProcessingDelay, self, ProcessCurrentBlockHeight(c))
      stay()

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(ProcessCurrentBlockHeight(_), _) => stay()

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(_: CurrentFeerates.BitcoinCore, _) => stay()

    // we only care about this event in NORMAL state
    case Event(_: BroadcastChannelUpdate, _) => stay()

    // we receive this when we tell the peer to disconnect
    case Event(_: Peer.DisconnectResponse, _) => stay()

    // funding tx was confirmed in time, let's just ignore this
    case Event(BITCOIN_FUNDING_TIMEOUT, _: PersistentChannelData) => stay()

    // peer doesn't cancel the timer
    case Event(TickChannelOpenTimeout, _) => stay()

    case Event(w: WatchPublishedTriggered, d: ChannelDataWithCommitments) =>
      // When our peer sends us channel_ready while we're still waiting for confirmations, we may opportunistically
      // switch to zero-conf, in which case we have both a WatchPublished and a WatchConfirmed pending. But it may not
      // actually be a real switch to zero-conf: maybe the transaction is confirmed, and they simply received the block
      // slightly before us. In that case, the WatchConfirmed may trigger first, and it would be inefficient to let the
      // WatchPublished override our funding status: it will make us set a new WatchConfirmed that will instantly
      // trigger and rewrite the funding status again.
      val alreadyConfirmed = d.commitments.active.exists(c => c.fundingTxId == w.tx.txid && c.localFundingStatus.isInstanceOf[LocalFundingStatus.ConfirmedFundingTx])
      if (alreadyConfirmed) {
        stay()
      } else {
        val fundingStatus = LocalFundingStatus.ZeroconfPublishedFundingTx(w.tx, d.commitments.localFundingSigs(w.tx.txid), d.commitments.liquidityPurchase(w.tx.txid))
        val lastAnnouncedFundingTxId_opt = d match {
          case d: DATA_NORMAL => d.lastAnnouncedFundingTxId_opt
          case _ => None
        }
        d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus, lastAnnouncedFundingTxId_opt) match {
          case Right((commitments1, _)) =>
            log.info("zero-conf funding txid={} has been published", w.tx.txid)
            // This is a zero-conf channel, the min-depth isn't critical: we use the default.
            watchFundingConfirmed(w.tx.txid, Some(nodeParams.channelConf.minDepth), delay_opt = None)
            val d1 = d match {
              // NB: we discard remote's stashed channel_ready, they will send it back at reconnection
              case d: DATA_WAIT_FOR_FUNDING_CONFIRMED => DATA_WAIT_FOR_CHANNEL_READY(commitments1, aliases = createShortIdAliases(d.channelId))
              case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, aliases = createShortIdAliases(d.channelId))
              case d: DATA_WAIT_FOR_CHANNEL_READY => d.copy(commitments = commitments1)
              case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => d.copy(commitments = commitments1)
              case d: DATA_NORMAL => d.copy(commitments = commitments1)
              case d: DATA_SHUTDOWN => d.copy(commitments = commitments1)
              case d: DATA_NEGOTIATING => d.copy(commitments = commitments1)
              case d: DATA_NEGOTIATING_SIMPLE => d.copy(commitments = commitments1)
              case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => d.copy(commitments = commitments1)
              case d: DATA_CLOSING => d.copy(commitments = commitments1)
            }
            stay() using d1 storing()
          case Left(_) => stay()
        }
      }

    case Event(w: WatchFundingConfirmedTriggered, d: ChannelDataWithCommitments) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, _)) =>
          log.info("funding txid={} has been confirmed", w.tx.txid)
          val d1 = d match {
            // NB: we discard remote's stashed channel_ready, they will send it back at reconnection
            case d: DATA_WAIT_FOR_FUNDING_CONFIRMED => DATA_WAIT_FOR_CHANNEL_READY(commitments1, aliases = createShortIdAliases(d.channelId))
            case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, aliases = createShortIdAliases(d.channelId))
            case d: DATA_WAIT_FOR_CHANNEL_READY => d.copy(commitments = commitments1)
            case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => d.copy(commitments = commitments1)
            case d: DATA_NORMAL => d.copy(commitments = commitments1)
            case d: DATA_SHUTDOWN => d.copy(commitments = commitments1)
            case d: DATA_NEGOTIATING => d.copy(commitments = commitments1)
            case d: DATA_NEGOTIATING_SIMPLE => d.copy(commitments = commitments1)
            case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => d.copy(commitments = commitments1)
            case d: DATA_CLOSING => d // there is a dedicated handler in CLOSING state
          }
          stay() using d1 storing()
        case Left(_) => stay()
      }

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.exists(_.unsignedTx.tx.txid == tx.txid) =>
      // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
      handleMutualClose(getMutualClosePublished(tx, d.closingTxProposed), Left(d))

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if d.bestUnpublishedClosingTx_opt.exists(_.tx.txid == tx.txid) =>
      log.warning(s"looks like a mutual close tx has been published from the outside of the channel: closingTxId=${tx.txid}")
      // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
      handleMutualClose(d.bestUnpublishedClosingTx_opt.get, Left(d))

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING_SIMPLE) if d.findClosingTx(tx).nonEmpty =>
      if (!d.publishedClosingTxs.exists(_.tx.txid == tx.txid)) {
        // They published one of our closing transactions without sending us their signature (or we ignored them because
        // of a race with our closing_complete). We need to publish it ourselves to record the fees and watch for confirmation.
        val closingTx = d.findClosingTx(tx).get.copy(tx = tx)
        stay() using d.copy(publishedClosingTxs = d.publishedClosingTxs :+ closingTx) storing() calling doPublish(closingTx, localPaysClosingFees = true)
      } else {
        // This is one of the transactions we published.
        blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepth)
        stay()
      }

    case Event(WatchTxConfirmedTriggered(_, _, tx), d: DATA_NEGOTIATING_SIMPLE) if d.findClosingTx(tx).nonEmpty =>
      val closingType = MutualClose(d.findClosingTx(tx).get)
      log.info("channel closed (type={})", EventType.Closed(closingType).label)
      context.system.eventStream.publish(ChannelClosed(self, d.channelId, closingType, d.commitments))
      goto(CLOSED) using d storing()

    case Event(WatchFundingSpentTriggered(tx), d: ChannelDataWithCommitments) =>
      if (d.commitments.all.map(_.fundingTxId).contains(tx.txid)) {
        // if the spending tx is itself a funding tx, this is a splice and there is nothing to do
        stay()
      } else if (tx.txid == d.commitments.latest.remoteCommit.txId) {
        handleRemoteSpentCurrent(tx, d)
      } else if (d.commitments.latest.nextRemoteCommit_opt.exists(_.commit.txId == tx.txid)) {
        handleRemoteSpentNext(tx, d)
      } else if (tx.txid == d.commitments.latest.localCommit.txId) {
        log.warning(s"processing local commit spent from the outside")
        spendLocalCurrent(d, maxClosingFeerateOverride_opt = None)
      } else if (tx.txIn.map(_.outPoint.txid).contains(d.commitments.latest.fundingTxId)) {
        handleRemoteSpentOther(tx, d)
      } else {
        d.commitments.resolveCommitment(tx) match {
          case Some(commitment) =>
            log.warning("a commit tx for an older commitment has been published fundingTxId={} fundingTxIndex={}", tx.txid, commitment.fundingTxIndex)
            // We watch the commitment tx, in the meantime we force close using the latest commitment.
            blockchain ! WatchAlternativeCommitTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepth)
            spendLocalCurrent(d, maxClosingFeerateOverride_opt = None)
          case None =>
            // This must be a former funding tx that has already been pruned, because watches are unordered.
            log.warning(s"ignoring unrecognized tx=${tx.txid}")
            stay()
        }
      }
  }

  onTransition {
    case WAIT_FOR_INIT_INTERNAL -> WAIT_FOR_INIT_INTERNAL => () // called at channel initialization
    case state -> nextState =>
      if (state != nextState) {
        val commitments_opt = nextStateData match {
          case d: ChannelDataWithCommitments => Some(d.commitments)
          case _: ChannelDataWithoutCommitments => None
          case _: TransientChannelData => None
        }
        context.system.eventStream.publish(ChannelStateChanged(self, nextStateData.channelId, peer, remoteNodeId, state, nextState, commitments_opt))
      }
      if (nextState == CLOSED) {
        // channel is closed, scheduling this actor for self destruction
        context.system.scheduler.scheduleOnce(1 minute, self, Symbol("shutdown"))
      }
      if (nextState == OFFLINE) {
        // we can cancel the timer, we are not expecting anything when disconnected
        cancelTimer(RevocationTimeout.toString)
        cancelTimer(QuiescenceTimeout.toString)
      }

      sealed trait EmitLocalChannelEvent
      /*
       * This event is for:
       *  - the router: so it knows about this channel to find routes
       *  - the relayer: so it learns about the channel real scid/alias and can route to it
       *  - the peer: so they can "learn the other end's forwarding parameters" (BOLT 7)
       */
      case class EmitLocalChannelUpdate(reason: String, d: DATA_NORMAL, sendToPeer: Boolean) extends EmitLocalChannelEvent
      /*
       * When a channel that could previously be used to relay payments starts closing, we advertise the fact that this
       * channel can't be used for payments anymore. If the channel is private we don't really need to tell the
       * counterparty because it is already aware that the channel is being closed
       */
      case class EmitLocalChannelDown(d: DATA_NORMAL) extends EmitLocalChannelEvent

      // We only send the channel_update directly to the peer if we are connected AND the channel hasn't been announced
      val emitEvent_opt: Option[EmitLocalChannelEvent] = (state, nextState, stateData, nextStateData) match {
        case (WAIT_FOR_INIT_INTERNAL, OFFLINE, _, d: DATA_NORMAL) => Some(EmitLocalChannelUpdate("restore", d, sendToPeer = false))
        case (WAIT_FOR_CHANNEL_READY | WAIT_FOR_DUAL_FUNDING_READY, NORMAL, _, d: DATA_NORMAL) => Some(EmitLocalChannelUpdate("initial", d, sendToPeer = true))
        case (NORMAL, NORMAL, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.lastAnnouncement_opt != d2.lastAnnouncement_opt => Some(EmitLocalChannelUpdate("normal->normal", d2, sendToPeer = d2.lastAnnouncement_opt.isEmpty && d1.channelUpdate != d2.channelUpdate))
        case (SYNCING, NORMAL, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.lastAnnouncement_opt != d2.lastAnnouncement_opt => Some(EmitLocalChannelUpdate("syncing->normal", d2, sendToPeer = d2.lastAnnouncement_opt.isEmpty))
        case (NORMAL, OFFLINE, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.lastAnnouncement_opt != d2.lastAnnouncement_opt => Some(EmitLocalChannelUpdate("normal->offline", d2, sendToPeer = false))
        case (OFFLINE, OFFLINE, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.lastAnnouncement_opt != d2.lastAnnouncement_opt => Some(EmitLocalChannelUpdate("offline->offline", d2, sendToPeer = false))
        case (NORMAL | SYNCING | OFFLINE, SHUTDOWN | NEGOTIATING | NEGOTIATING_SIMPLE | CLOSING | CLOSED | ERR_INFORMATION_LEAK | WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT, d: DATA_NORMAL, _) => Some(EmitLocalChannelDown(d))
        case _ => None
      }
      emitEvent_opt.foreach {
        case EmitLocalChannelUpdate(reason, d, sendToPeer) =>
          log.debug("emitting channel update event: reason={} sendToPeer={} channel_update={} channel_announcement={}", reason, sendToPeer, d.channelUpdate, d.lastAnnouncement_opt)
          val lcu = LocalChannelUpdate(self, d.channelId, d.aliases, remoteNodeId, d.lastAnnouncedCommitment_opt, d.channelUpdate, d.commitments)
          context.system.eventStream.publish(lcu)
          if (sendToPeer) {
            // We may need a real scid to fallback to if our peer didn't send us an alias, even if we're using a private
            // channel, otherwise they won't be able to match our update to a channel.
            val realScid_opt = d.lastAnnouncement_opt.map(_.shortChannelId).orElse(d.commitments.all.flatMap(_.shortChannelId_opt).headOption)
            send(Helpers.channelUpdateForDirectPeer(nodeParams, d.channelUpdate, realScid_opt, d.aliases))
          }
        case EmitLocalChannelDown(d) =>
          log.debug("emitting channel down event")
          if (d.lastAnnouncement_opt.nonEmpty) {
            // We tell the rest of the network that this channel shouldn't be used anymore.
            val disabledUpdate = Helpers.channelUpdate(nodeParams, scidForChannelUpdate(d), d.commitments, d.channelUpdate.relayFees, enable = false, d.channelUpdate.blip18InboundFees_opt)
            context.system.eventStream.publish(LocalChannelUpdate(self, d.channelId, d.aliases, remoteNodeId, d.lastAnnouncedCommitment_opt, disabledUpdate, d.commitments))
          }
          val lcd = LocalChannelDown(self, d.channelId, d.commitments.all.flatMap(_.shortChannelId_opt), d.aliases, remoteNodeId)
          context.system.eventStream.publish(lcd)
      }

      // When we change our channel update parameters (e.g. relay fees), we want to advertise it to other actors.
      (stateData, nextStateData) match {
        // NORMAL->NORMAL, NORMAL->OFFLINE, SYNCING->NORMAL
        case (d1: DATA_NORMAL, d2: DATA_NORMAL) => maybeEmitChannelUpdateParametersChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = Some(d1.channelUpdate), d2)
        // WAIT_FOR_FUNDING_CONFIRMED->NORMAL, WAIT_FOR_CHANNEL_READY->NORMAL
        case (_: DATA_WAIT_FOR_FUNDING_CONFIRMED, d2: DATA_NORMAL) => maybeEmitChannelUpdateParametersChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case (_: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED, d2: DATA_NORMAL) => maybeEmitChannelUpdateParametersChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case (_: DATA_WAIT_FOR_CHANNEL_READY, d2: DATA_NORMAL) => maybeEmitChannelUpdateParametersChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case (_: DATA_WAIT_FOR_DUAL_FUNDING_READY, d2: DATA_NORMAL) => maybeEmitChannelUpdateParametersChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case _ => ()
      }

      // Notify when the channel was aborted.
      (stateData, nextState) match {
        case (_: TransientChannelData, CLOSING | CLOSED) => context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, stateData.channelId))
        case (_: DATA_WAIT_FOR_FUNDING_CONFIRMED | _: DATA_WAIT_FOR_CHANNEL_READY, CLOSING | CLOSED) => context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, stateData.channelId))
        case (_: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED | _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED | _: DATA_WAIT_FOR_DUAL_FUNDING_READY, CLOSING | CLOSED) => context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, stateData.channelId))
        case _ => ()
      }
  }

  /** Metrics */
  onTransition {
    case state -> nextState if state != nextState =>
      if (state != WAIT_FOR_INIT_INTERNAL) Metrics.ChannelsCount.withTag(Tags.State, state.toString).decrement()
      if (nextState != WAIT_FOR_INIT_INTERNAL) Metrics.ChannelsCount.withTag(Tags.State, nextState.toString).increment()
  }

  /** Check pending settlement commands */
  onTransition {
    case _ -> CLOSING =>
      PendingCommandsDb.getSettlementCommands(nodeParams.db.pendingCommands, nextStateData.channelId) match {
        case Nil =>
          log.debug("nothing to replay")
        case cmds =>
          log.info("replaying {} unacked fulfills/fails", cmds.size)
          cmds.foreach(self ! _) // they all have commit = false
      }
    case SYNCING -> (NORMAL | SHUTDOWN) =>
      PendingCommandsDb.getSettlementCommands(nodeParams.db.pendingCommands, nextStateData.channelId) match {
        case Nil =>
          log.debug("nothing to replay")
        case cmds =>
          log.info("replaying {} unacked fulfills/fails", cmds.size)
          cmds.foreach(self ! _) // they all have commit = false
          self ! CMD_SIGN() // so we can sign all of them at once
      }
  }

  /** Fail outgoing unsigned htlcs right away when transitioning from NORMAL to CLOSING */
  onTransition {
    case NORMAL -> CLOSING =>
      nextStateData match {
        case d: DATA_CLOSING =>
          d.commitments.changes.localChanges.proposed.collect {
            case add: UpdateAddHtlc => relayer ! RES_ADD_SETTLED(d.commitments.originChannels(add.id), add, HtlcResult.ChannelFailureBeforeSigned)
          }
        case _ => ()
      }
  }

  /** On disconnection we clear up temporary mutable state that applies to the previous connection. */
  onTransition {
    case _ -> OFFLINE =>
      announcementSigsStash = Map.empty
      announcementSigsSent = Set.empty
      spliceLockedSent = Map.empty[TxId, Long]
      remoteNextCommitNonces = Map.empty
      localCloseeNonce_opt = None
      remoteCloseeNonce_opt = None
      localCloserNonces_opt = None
  }

  /*
          888    888        d8888 888b    888 8888888b.  888      8888888888 8888888b.   .d8888b.
          888    888       d88888 8888b   888 888  "Y88b 888      888        888   Y88b d88P  Y88b
          888    888      d88P888 88888b  888 888    888 888      888        888    888 Y88b.
          8888888888     d88P 888 888Y88b 888 888    888 888      8888888    888   d88P  "Y888b.
          888    888    d88P  888 888 Y88b888 888    888 888      888        8888888P"      "Y88b.
          888    888   d88P   888 888  Y88888 888    888 888      888        888 T88b         "888
          888    888  d8888888888 888   Y8888 888  .d88P 888      888        888  T88b  Y88b  d88P
          888    888 d88P     888 888    Y888 8888888P"  88888888 8888888888 888   T88b  "Y8888P"
   */

  private def handleCurrentFeerate(c: CurrentFeerates, d: ChannelDataWithCommitments) = {
    val commitments = d.commitments.latest
    val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, d.commitments.latest.commitmentFormat)
    val currentFeeratePerKw = commitments.localCommit.spec.commitTxFeerate
    val shouldUpdateFee = d.commitments.localChannelParams.paysCommitTxFees && nodeParams.onChainFeeConf.shouldUpdateFee(currentFeeratePerKw, networkFeeratePerKw)
    val shouldClose = !d.commitments.localChannelParams.paysCommitTxFees &&
      nodeParams.onChainFeeConf.feerateToleranceFor(d.commitments.remoteNodeId).isProposedFeerateTooLow(d.commitments.latest.commitmentFormat, networkFeeratePerKw, currentFeeratePerKw) &&
      d.commitments.hasPendingOrProposedHtlcs // we close only if we have HTLCs potentially at risk
    if (shouldUpdateFee) {
      self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
      stay()
    } else if (shouldClose) {
      handleLocalError(FeerateTooDifferent(d.channelId, localFeeratePerKw = networkFeeratePerKw, remoteFeeratePerKw = commitments.localCommit.spec.commitTxFeerate), d, Some(c))
    } else {
      stay()
    }
  }

  /**
   * This is used to check for the commitment fees when the channel is not operational but we have something at stake
   *
   * @param c the new feerates
   * @param d the channel commtiments
   * @return
   */
  private def handleCurrentFeerateDisconnected(c: CurrentFeerates, d: ChannelDataWithCommitments) = {
    val commitments = d.commitments.latest
    val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, d.commitments.latest.commitmentFormat)
    val currentFeeratePerKw = commitments.localCommit.spec.commitTxFeerate
    // if the network fees are too high we risk to not be able to confirm our current commitment
    val shouldClose = networkFeeratePerKw > currentFeeratePerKw &&
      nodeParams.onChainFeeConf.feerateToleranceFor(d.commitments.remoteNodeId).isProposedFeerateTooLow(d.commitments.latest.commitmentFormat, networkFeeratePerKw, currentFeeratePerKw) &&
      d.commitments.hasPendingOrProposedHtlcs // we close only if we have HTLCs potentially at risk
    if (shouldClose) {
      if (nodeParams.onChainFeeConf.closeOnOfflineMismatch) {
        log.warning(s"closing OFFLINE channel due to fee mismatch: currentFeeratePerKw=$currentFeeratePerKw networkFeeratePerKw=$networkFeeratePerKw")
        handleLocalError(FeerateTooDifferent(d.channelId, localFeeratePerKw = currentFeeratePerKw, remoteFeeratePerKw = networkFeeratePerKw), d, Some(c))
      } else {
        log.warning(s"channel is OFFLINE but its fee mismatch is over the threshold: currentFeeratePerKw=$currentFeeratePerKw networkFeeratePerKw=$networkFeeratePerKw")
        stay()
      }
    } else {
      stay()
    }
  }

  private def handleCommandSuccess(c: channel.Command, newData: ChannelData) = {
    val replyTo_opt = c match {
      case hasOptionalReplyTo: HasOptionalReplyToCommand => hasOptionalReplyTo.replyTo_opt
      case hasReplyTo: HasReplyToCommand => if (hasReplyTo.replyTo == ActorRef.noSender) Some(sender()) else Some(hasReplyTo.replyTo)
      case _ => None
    }
    replyTo_opt.foreach(_ ! RES_SUCCESS(c, newData.channelId))
    stay() using newData
  }

  private def handleAddHtlcCommandError(c: CMD_ADD_HTLC, cause: ChannelException, channelUpdate: Option[ChannelUpdate]) = {
    log.warning(s"${cause.getMessage} while processing cmd=${c.getClass.getSimpleName} in state=$stateName")
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    replyTo ! RES_ADD_FAILED(c, cause, channelUpdate)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(cause), isFatal = false))
    stay()
  }

  private def handleCommandError(cause: ChannelException, c: channel.Command) = {
    log.warning(s"${cause.getMessage} while processing cmd=${c.getClass.getSimpleName} in state=$stateName")
    val replyTo_opt = c match {
      case hasOptionalReplyTo: HasOptionalReplyToCommand => hasOptionalReplyTo.replyTo_opt
      case hasReplyTo: HasReplyToCommand => if (hasReplyTo.replyTo == ActorRef.noSender) Some(sender()) else Some(hasReplyTo.replyTo)
      case _ => None
    }
    replyTo_opt.foreach(replyTo => replyTo ! RES_FAILURE(c, cause))
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(cause), isFatal = false))
    stay()
  }

  private def handleRevocationTimeout(revocationTimeout: RevocationTimeout, d: ChannelDataWithCommitments) = {
    d.commitments.remoteNextCommitInfo match {
      case Left(_) if revocationTimeout.remoteCommitNumber + 1 == d.commitments.nextRemoteCommitIndex =>
        log.warning(s"waited for too long for a revocation to remoteCommitNumber=${revocationTimeout.remoteCommitNumber}, disconnecting")
        revocationTimeout.peer ! Peer.Disconnect(remoteNodeId)
      case _ => ()
    }
    stay()
  }

  private def handleAddDisconnected(c: CMD_ADD_HTLC, d: DATA_NORMAL) = {
    log.debug(s"rejecting htlc request in state=$stateName")
    // in order to reduce gossip spam, we don't disable the channel right away when disconnected
    // we will only emit a new channel_update with the disable flag set if someone tries to use that channel
    if (d.channelUpdate.channelFlags.isEnabled) {
      // if the channel isn't disabled we generate a new channel_update
      log.debug("updating channel_update announcement (reason=disabled)")
      val channelUpdate1 = Helpers.channelUpdate(nodeParams, scidForChannelUpdate(d), d.commitments, d.channelUpdate.relayFees, enable = false, d.channelUpdate.blip18InboundFees_opt)
      // then we update the state and replay the request
      self forward c
      // we use goto() to fire transitions
      goto(stateName) using d.copy(channelUpdate = channelUpdate1) storing()
    } else {
      // channel is already disabled, we reply to the request
      val error = ChannelUnavailable(d.channelId)
      handleAddHtlcCommandError(c, error, Some(d.channelUpdate)) // can happen if we are in OFFLINE or SYNCING state (channelUpdate will have enable=false)
    }
  }

  private def handleUpdateRelayFeeDisconnected(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) = {
    val channelUpdate1 = Helpers.channelUpdate(nodeParams, scidForChannelUpdate(d), d.commitments, Relayer.RelayFees(c.feeBase, c.feeProportionalMillionths), enable = false, InboundFees.fromOptions(c.inboundFeeBase_opt, c.inboundFeeProportionalMillionths_opt))
    log.debug(s"updating relay fees: prev={} next={}", d.channelUpdate.toStringShort, channelUpdate1.toStringShort)
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    replyTo ! RES_SUCCESS(c, d.channelId)
    // We're in OFFLINE state, by using stay() instead of goto() we skip the transition handler and won't broadcast the
    // new update right away. The goal is to not emit superfluous updates when the channel is unusable. At reconnection
    // there will be a state transition SYNCING->NORMAL which will cause the update to be broadcast.
    // However, we still need to advertise that the channel_update parameters have changed, so we manually call the method
    maybeEmitChannelUpdateParametersChangedEvent(newUpdate = channelUpdate1, oldUpdate_opt = Some(d.channelUpdate), d)
    stay() using d.copy(channelUpdate = channelUpdate1) storing()
  }

  private def handleSyncFailure(channelReestablish: ChannelReestablish, syncFailure: SyncResult.Failure, d: ChannelDataWithCommitments) = {
    syncFailure match {
      case res: SyncResult.LocalLateProven =>
        log.error(s"counterparty proved that we have an outdated (revoked) local commitment!!! ourLocalCommitmentNumber=${res.ourLocalCommitmentNumber} theirRemoteCommitmentNumber=${res.theirRemoteCommitmentNumber}")
        // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
        // would punish us by taking all the funds in the channel
        handleOutdatedCommitment(channelReestablish, d)
      case res: Syncing.SyncResult.LocalLateUnproven =>
        log.error(s"our local commitment is in sync, but counterparty says that they have a more recent remote commitment than the one we know of (they could be lying)!!! ourRemoteCommitmentNumber=${res.ourRemoteCommitmentNumber} theirCommitmentNumber=${res.theirLocalCommitmentNumber}")
        // there is no way to make sure that they are saying the truth, the best thing to do is "call their bluff" and
        // ask them to publish their commitment right now. If they weren't lying and they do publish their commitment,
        // we need to remember their commitment point in order to be able to claim our outputs
        handleOutdatedCommitment(channelReestablish, d)
      case res: Syncing.SyncResult.RemoteLying =>
        log.error(s"counterparty claims that we have an outdated commitment, but they sent an invalid proof, so our commitment may or may not be revoked: ourLocalCommitmentNumber=${res.ourLocalCommitmentNumber} theirRemoteCommitmentNumber=${res.theirRemoteCommitmentNumber}")
        // they are deliberately trying to fool us into thinking we have a late commitment, but we cannot risk publishing it ourselves, because it may really be revoked!
        handleOutdatedCommitment(channelReestablish, d)
      case SyncResult.RemoteLate =>
        log.error("counterparty appears to be using an outdated commitment, they may request a force-close, standing by...")
        stay()
    }
  }

  private def maybeEmitChannelUpdateParametersChangedEvent(newUpdate: ChannelUpdate, oldUpdate_opt: Option[ChannelUpdate], d: DATA_NORMAL): Unit = {
    if (oldUpdate_opt.isEmpty || !Announcements.areSameRelayParams(newUpdate, oldUpdate_opt.get)) {
      context.system.eventStream.publish(ChannelUpdateParametersChanged(self, d.channelId, d.commitments.remoteNodeId, newUpdate))
    }
  }

  /** Splices change balances and capacity, we send events to notify other actors (router, relayer) */
  private def maybeEmitEventsPostSplice(aliases: ShortIdAliases, oldCommitments: Commitments, newCommitments: Commitments, lastAnnouncement_opt: Option[ChannelAnnouncement]): Unit = {
    // NB: we consider the send and receive balance, because router tracks both
    if (oldCommitments.availableBalanceForSend != newCommitments.availableBalanceForSend || oldCommitments.availableBalanceForReceive != newCommitments.availableBalanceForReceive) {
      context.system.eventStream.publish(AvailableBalanceChanged(self, newCommitments.channelId, aliases, newCommitments, lastAnnouncement_opt))
    }
    if (oldCommitments.active.size != newCommitments.active.size) {
      // Some commitments have been deactivated, which means our available balance changed, which may allow forwarding
      // payments that couldn't be forwarded before.
      val fundingTxIndex = newCommitments.active.map(_.fundingTxIndex).min
      peer ! ChannelReadyForPayments(self, remoteNodeId, newCommitments.channelId, fundingTxIndex)
    }
  }

  private def maybeUpdateMaxHtlcAmount(currentMaxHtlcAmount: MilliSatoshi, newCommitments: Commitments): Unit = {
    val newMaxHtlcAmount = Helpers.maxHtlcAmount(nodeParams, newCommitments)
    if (currentMaxHtlcAmount != newMaxHtlcAmount) {
      self ! BroadcastChannelUpdate(BalanceThresholdCrossed)
    }
  }

  private def handleNewBlock(c: CurrentBlockHeight, d: ChannelDataWithCommitments) = {
    d match {
      case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => handleNewBlockDualFundingUnconfirmed(c, d)
      case _ =>
        // note: this can only happen if state is NORMAL or SHUTDOWN
        // -> in NEGOTIATING there are no more htlcs
        // -> in CLOSING we either have mutual closed (so no more htlcs), or already have unilaterally closed (so no action required), and we can't be in OFFLINE state anyway
        val timedOutOutgoing = d.commitments.timedOutOutgoingHtlcs(c.blockHeight)
        val almostTimedOutIncoming = d.commitments.almostTimedOutIncomingHtlcs(c.blockHeight, nodeParams.channelConf.fulfillSafetyBeforeTimeout)
        if (timedOutOutgoing.nonEmpty) {
          // Downstream timed out.
          handleLocalError(HtlcsTimedoutDownstream(d.channelId, timedOutOutgoing), d, Some(c))
        } else if (almostTimedOutIncoming.nonEmpty) {
          // Upstream is close to timing out, we need to test if we have funds at risk: htlcs for which we know the preimage
          // that are still in our commitment (upstream will try to timeout on-chain).
          val relayedFulfills = d.commitments.changes.localChanges.all.collect { case u: UpdateFulfillHtlc => u.id }.toSet
          val offendingRelayedHtlcs = almostTimedOutIncoming.filter(htlc => relayedFulfills.contains(htlc.id))
          if (offendingRelayedHtlcs.nonEmpty) {
            handleLocalError(HtlcsWillTimeoutUpstream(d.channelId, offendingRelayedHtlcs), d, Some(c))
          } else {
            // There might be pending fulfill commands that we haven't relayed yet.
            // Since this involves a DB call, we only want to check it if all the previous checks failed (this is the slow path).
            val pendingRelayFulfills = nodeParams.db.pendingCommands.listSettlementCommands(d.channelId).collect { case c: CMD_FULFILL_HTLC => c.id }
            val offendingPendingRelayFulfills = almostTimedOutIncoming.filter(htlc => pendingRelayFulfills.contains(htlc.id))
            if (offendingPendingRelayFulfills.nonEmpty) {
              handleLocalError(HtlcsWillTimeoutUpstream(d.channelId, offendingPendingRelayFulfills), d, Some(c))
            } else {
              stay()
            }
          }
        } else {
          stay()
        }
    }
  }

  private def resendChannelReadyIfNeeded(channelReestablish: ChannelReestablish, d: DATA_NORMAL): (Option[ChannelReady], Option[AnnouncementSignatures]) = {
    d.commitments.lastLocalLocked_opt match {
      case None => (None, None)
      // We only send channel_ready for initial funding transactions.
      case Some(c) if c.fundingTxIndex != 0 => (None, None)
      case Some(c) =>
        val remoteSpliceSupport = d.commitments.remoteChannelParams.initFeatures.hasFeature(Features.SplicePrototype)
        // If our peer has not received our channel_ready, we retransmit it.
        val notReceivedByRemote = remoteSpliceSupport && channelReestablish.yourLastFundingLocked_opt.isEmpty
        // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node
        // MUST retransmit channel_ready, otherwise it MUST NOT
        val notReceivedByRemoteLegacy = !remoteSpliceSupport && channelReestablish.nextLocalCommitmentNumber == 1 && c.localCommit.index == 0
        // If this is a public channel and we haven't announced the channel, we retransmit our channel_ready and
        // will also send announcement_signatures.
        val notAnnouncedYet = d.commitments.announceChannel && c.shortChannelId_opt.nonEmpty && d.lastAnnouncement_opt.isEmpty
        val channelReady_opt = if (notAnnouncedYet || notReceivedByRemote || notReceivedByRemoteLegacy) {
          log.debug("re-sending channel_ready")
          Some(createChannelReady(d.aliases, d.commitments))
        } else {
          None
        }
        val announcementSigs_opt = if (notAnnouncedYet) {
          // The funding transaction is confirmed, so we've already sent our announcement_signatures.
          // We haven't announced the channel yet, which means we haven't received our peer's announcement_signatures.
          // We retransmit our announcement_signatures to let our peer know that we're ready to announce the channel.
          val localAnnSigs = c.signAnnouncement(nodeParams, d.commitments.channelParams, channelKeys.fundingKey(c.fundingTxIndex))
          localAnnSigs.foreach(annSigs => announcementSigsSent += annSigs.shortChannelId)
          localAnnSigs
        } else {
          None
        }
        (channelReady_opt, announcementSigs_opt)
    }
  }

  private def resumeSpliceSigningSessionIfNeeded(channelReestablish: ChannelReestablish, d: DATA_NORMAL): (SpliceStatus, Queue[LightningMessage]) = {
    var sendQueue = Queue.empty[LightningMessage]
    val spliceStatus1 = channelReestablish.nextFundingTxId_opt match {
      case Some(fundingTxId) =>
        d.spliceStatus match {
          case SpliceStatus.SpliceWaitingForSigs(signingSession) if signingSession.fundingTx.txId == fundingTxId =>
            if (channelReestablish.nextLocalCommitmentNumber == d.commitments.remoteCommitIndex) {
              // They haven't received our commit_sig: we retransmit it.
              // We're also waiting for signatures from them, and will send our tx_signatures once we receive them.
              log.info("re-sending commit_sig for splice attempt with fundingTxIndex={} fundingTxId={}", signingSession.fundingTxIndex, signingSession.fundingTx.txId)
              val fundingParams = signingSession.fundingParams
              val remoteNonce_opt = channelReestablish.currentCommitNonce_opt
              signingSession.remoteCommit.sign(d.commitments.channelParams, signingSession.remoteCommitParams, channelKeys, signingSession.fundingTxIndex, fundingParams.remoteFundingPubKey, signingSession.commitInput(channelKeys), fundingParams.commitmentFormat, remoteNonce_opt) match {
                case Left(f) => sendQueue = sendQueue :+ Warning(d.channelId, f.getMessage)
                case Right(commitSig) => sendQueue = sendQueue :+ commitSig
              }
            }
            d.spliceStatus
          case _ if d.commitments.latest.fundingTxId == fundingTxId =>
            d.commitments.latest.localFundingStatus match {
              case dfu: LocalFundingStatus.DualFundedUnconfirmedFundingTx =>
                // We've already received their commit_sig and sent our tx_signatures. We retransmit our
                // tx_signatures and our commit_sig if they haven't received it already.
                if (channelReestablish.nextLocalCommitmentNumber == d.commitments.remoteCommitIndex) {
                  log.info("re-sending commit_sig and tx_signatures for fundingTxIndex={} fundingTxId={}", d.commitments.latest.fundingTxIndex, d.commitments.latest.fundingTxId)
                  val remoteNonce_opt = channelReestablish.currentCommitNonce_opt
                  d.commitments.latest.remoteCommit.sign(d.commitments.channelParams, d.commitments.latest.remoteCommitParams, channelKeys, d.commitments.latest.fundingTxIndex, d.commitments.latest.remoteFundingPubKey, d.commitments.latest.commitInput(channelKeys), d.commitments.latest.commitmentFormat, remoteNonce_opt) match {
                    case Left(f) => sendQueue = sendQueue :+ Warning(d.channelId, f.getMessage)
                    case Right(commitSig) => sendQueue = sendQueue :+ commitSig :+ dfu.sharedTx.localSigs
                  }
                } else {
                  log.info("re-sending tx_signatures for fundingTxIndex={} fundingTxId={}", d.commitments.latest.fundingTxIndex, d.commitments.latest.fundingTxId)
                  sendQueue = sendQueue :+ dfu.sharedTx.localSigs
                }
              case fundingStatus =>
                // They have not received our tx_signatures, but they must have received our commit_sig, otherwise we would be in the case above.
                log.info("re-sending tx_signatures for fundingTxIndex={} fundingTxId={} (already published or confirmed)", d.commitments.latest.fundingTxIndex, d.commitments.latest.fundingTxId)
                sendQueue = sendQueue ++ fundingStatus.localSigs_opt.toSeq
            }
            d.spliceStatus
          case _ =>
            // The fundingTxId must be for a splice attempt that we didn't store (we got disconnected before receiving
            // their tx_complete): we tell them to abort that splice attempt.
            log.info(s"aborting obsolete splice attempt for fundingTxId=$fundingTxId")
            sendQueue = sendQueue :+ TxAbort(d.channelId, SpliceAttemptAborted(d.channelId).getMessage)
            SpliceStatus.SpliceAborted
        }
      case None => d.spliceStatus
    }
    (spliceStatus1, sendQueue)
  }

  private def resendSpliceLockedIfNeeded(channelReestablish: ChannelReestablish, commitments: Commitments, lastAnnouncement_opt: Option[ChannelAnnouncement]): Option[SpliceLocked] = {
    commitments.lastLocalLocked_opt match {
      case None => None
      // We only send splice_locked for splice transactions.
      case Some(c) if c.fundingTxIndex == 0 => None
      case Some(c) =>
        // If our peer has not received our splice_locked, we retransmit it.
        val notReceivedByRemote = !channelReestablish.yourLastFundingLocked_opt.contains(c.fundingTxId)
        // If this is a public channel and we haven't announced the splice, we retransmit our splice_locked and
        // will exchange announcement_signatures afterwards.
        val notAnnouncedYet = commitments.announceChannel && lastAnnouncement_opt.forall(ann => !c.shortChannelId_opt.contains(ann.shortChannelId))
        if (notReceivedByRemote || notAnnouncedYet) {
          // Retransmission of local announcement_signatures for splices are done when receiving splice_locked, no need
          // to retransmit here.
          log.debug("re-sending splice_locked for fundingTxId={}", c.fundingTxId)
          spliceLockedSent += (c.fundingTxId -> c.fundingTxIndex)
          trimSpliceLockedSentIfNeeded()
          Some(SpliceLocked(commitments.channelId, c.fundingTxId))
        } else {
          None
        }
    }
  }

  /**
   * Return full information about a known closing tx.
   */
  private def getMutualClosePublished(tx: Transaction, closingTxProposed: List[List[ClosingTxProposed]]): ClosingTx = {
    // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
    val proposedTx_opt = closingTxProposed.flatten.find(_.unsignedTx.tx.txid == tx.txid)
    require(proposedTx_opt.nonEmpty, s"closing tx not found in our proposed transactions: tx=$tx")
    // they added their signature, so we use their version of the transaction
    proposedTx_opt.get.unsignedTx.copy(tx = tx)
  }

  private def initiateSplice(cmd: CMD_SPLICE, d: DATA_NORMAL): Either[ChannelException, SpliceInit] = {
    val parentCommitment = d.commitments.latest.commitment
    val targetFeerate = nodeParams.onChainFeeConf.getFundingFeerate(nodeParams.currentFeeratesForFundingClosing)
    val fundingContribution = InteractiveTxFunder.computeSpliceContribution(
      isInitiator = true,
      sharedInput = SharedFundingInput(channelKeys, parentCommitment),
      spliceInAmount = cmd.additionalLocalFunding,
      spliceOut = cmd.spliceOutputs,
      targetFeerate = targetFeerate)
    val commitTxFees = if (d.commitments.localChannelParams.paysCommitTxFees) {
      Transactions.commitTxTotalCost(parentCommitment.remoteCommitParams.dustLimit, parentCommitment.remoteCommit.spec, parentCommitment.commitmentFormat)
    } else {
      0.sat
    }
    if (fundingContribution < 0.sat && parentCommitment.localCommit.spec.toLocal + fundingContribution < parentCommitment.localChannelReserve(d.commitments.channelParams).max(commitTxFees)) {
      log.warning(s"cannot do splice: insufficient funds (commitTxFees=$commitTxFees reserve=${parentCommitment.localChannelReserve(d.commitments.channelParams)})")
      Left(InvalidSpliceRequest(d.channelId))
    } else if (cmd.spliceOut_opt.map(_.scriptPubKey).exists(!MutualClose.isValidFinalScriptPubkey(_, allowAnySegwit = true, allowOpReturn = false))) {
      log.warning("cannot do splice: invalid splice-out script")
      Left(InvalidSpliceRequest(d.channelId))
    } else {
      log.info(s"initiating splice with local.in.amount=${cmd.additionalLocalFunding} local.in.push=${cmd.pushAmount} local.out.amount=${cmd.spliceOut_opt.map(_.amount).sum}")
      val spliceInit = SpliceInit(d.channelId,
        fundingContribution = fundingContribution,
        lockTime = nodeParams.currentBlockHeight.toLong,
        feerate = targetFeerate,
        fundingPubKey = channelKeys.fundingKey(parentCommitment.fundingTxIndex + 1).publicKey,
        pushAmount = cmd.pushAmount,
        requireConfirmedInputs = nodeParams.channelConf.requireConfirmedInputsForDualFunding,
        requestFunding_opt = cmd.requestFunding_opt,
        channelType_opt = cmd.channelType_opt
      )
      Right(spliceInit)
    }
  }

  private def initiateSpliceRbf(cmd: CMD_BUMP_FUNDING_FEE, d: DATA_NORMAL): Either[ChannelException, TxInitRbf] = {
    getSpliceRbfContext(Some(cmd), d).flatMap(rbf => {
      // We use the same contribution as the previous splice attempt.
      val fundingContribution = rbf.latestFundingTx.fundingParams.localContribution
      val commitTxFees = if (d.commitments.localChannelParams.paysCommitTxFees) {
        Transactions.commitTxTotalCost(rbf.parentCommitment.remoteCommitParams.dustLimit, rbf.parentCommitment.remoteCommit.spec, rbf.latestFundingTx.fundingParams.commitmentFormat)
      } else {
        0.sat
      }
      if (fundingContribution < 0.sat && rbf.parentCommitment.localCommit.spec.toLocal + fundingContribution < rbf.parentCommitment.localChannelReserve(d.commitments.channelParams).max(commitTxFees)) {
        log.warning(s"cannot do rbf: insufficient funds (commitTxFees=$commitTxFees reserve=${rbf.parentCommitment.localChannelReserve(d.commitments.channelParams)})")
        Left(InvalidSpliceRequest(d.channelId))
      } else {
        val txInitRbf = TxInitRbf(d.channelId, cmd.lockTime, cmd.targetFeerate, fundingContribution, rbf.latestFundingTx.fundingParams.requireConfirmedInputs.forRemote, cmd.requestFunding_opt)
        Right(txInitRbf)
      }
    })
  }

  private def getSpliceRbfContext(cmd_opt: Option[CMD_BUMP_FUNDING_FEE], d: DATA_NORMAL): Either[ChannelException, InteractiveTxBuilder.SpliceTxRbf] = {
    d.commitments.latest.localFundingStatus match {
      case fundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx =>
        val fundingTxIndex = d.commitments.latest.fundingTxIndex
        d.commitments.active.find(_.fundingTxIndex == fundingTxIndex - 1) match {
          case Some(parentCommitment) =>
            val previousTxs = d.commitments.active
              .filter(_.fundingTxIndex == fundingTxIndex)
              .map(_.localFundingStatus)
              // All transactions should be unconfirmed, otherwise we wouldn't be in an RBF context.
              .collect { case tx: LocalFundingStatus.DualFundedUnconfirmedFundingTx => tx.sharedTx }
            Right(InteractiveTxBuilder.SpliceTxRbf(parentCommitment, d.commitments.changes, fundingTx, previousTxs, cmd_opt.map(_.fundingFeeBudget)))
          case None =>
            log.warning("cannot initiate rbf: cannot find parent commitment")
            Left(InvalidRbfAttempt(d.channelId))
        }
      case _: LocalFundingStatus.ZeroconfPublishedFundingTx =>
        log.warning("cannot initiate rbf: transaction is using 0-conf (txId={})", d.commitments.latest.fundingTxId)
        Left(InvalidRbfZeroConf(d.channelId))
      case _ =>
        log.warning("cannot initiate rbf: transaction already confirmed (txId={})", d.commitments.latest.fundingTxId)
        Left(InvalidRbfTxConfirmed(d.channelId))
    }
  }

  private def handleQuiescenceTimeout(d: DATA_NORMAL): State = {
    if (d.spliceStatus == SpliceStatus.NoSplice) {
      log.warning("quiescence timed out with no ongoing splice, did we forget to cancel the timer?")
      stay()
    } else {
      log.warning("quiescence timed out in state {}, closing connection", d.spliceStatus.getClass.getSimpleName)
      context.system.scheduler.scheduleOnce(2 second, peer, Peer.Disconnect(remoteNodeId))
      stay() sending Warning(d.channelId, SpliceAttemptTimedOut(d.channelId).getMessage)
    }
  }

  /** Get out of a quiescent state: if there are HTLCs in flight, re-emit pending settlement commands. */
  private def endQuiescence(d: DATA_NORMAL): Unit = {
    // We cancel the quiescence timeout timer, otherwise it may fire during the next quiescence session.
    cancelTimer(QuiescenceTimeout.toString)
    if (d.commitments.hasPendingOrProposedHtlcs) {
      PendingCommandsDb.getSettlementCommands(nodeParams.db.pendingCommands, d.channelId).foreach(self ! _)
    }
  }

  private def reportSpliceFailure(spliceStatus: SpliceStatus, f: Throwable): Unit = {
    val cmd_opt = spliceStatus match {
      case SpliceStatus.NegotiatingQuiescence(cmd_opt, _) => cmd_opt
      case SpliceStatus.SpliceRequested(cmd, _) => Some(cmd)
      case SpliceStatus.RbfRequested(cmd, _) => Some(cmd)
      case SpliceStatus.SpliceInProgress(cmd_opt, _, txBuilder, _) =>
        txBuilder ! InteractiveTxBuilder.Abort
        cmd_opt
      case _ => None
    }
    cmd_opt.foreach(cmd => cmd.replyTo ! RES_FAILURE(cmd, f))
  }

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    val id = currentMessage match {
      case INPUT_RESTORED(data) => data.channelId
      case _ => stateData.channelId
    }
    Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(id), nodeAlias_opt = Some(nodeParams.alias))
  }

  // we let the peer decide what to do
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Escalate }

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    KamonExt.time(ProcessMessage.withTag("MessageType", msg.getClass.getSimpleName)) {
      super.aroundReceive(receive, msg)
    }
  }

  initialize()

}
