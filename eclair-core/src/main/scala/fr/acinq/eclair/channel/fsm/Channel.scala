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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.OnChainWallet.MakeFundingTxResponse
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.Commitments.PostRevocationAction
import fr.acinq.eclair.channel.Helpers.Syncing.SyncResult
import fr.acinq.eclair.channel.Helpers.{Closing, Syncing, getRelayFees, scidForChannelUpdate}
import fr.acinq.eclair.channel.Monitoring.Metrics.ProcessMessage
import fr.acinq.eclair.channel.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, SetChannelId}
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent.EventType
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentSettlingOnChain}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.ClosingTx
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
                         maxFundingSatoshis: Satoshi,
                         toRemoteDelay: CltvExpiryDelta,
                         maxToLocalDelay: CltvExpiryDelta,
                         minDepthBlocks: Int,
                         expiryDelta: CltvExpiryDelta,
                         fulfillSafetyBeforeTimeout: CltvExpiryDelta,
                         minFinalExpiryDelta: CltvExpiryDelta,
                         maxBlockProcessingDelay: FiniteDuration,
                         maxTxPublishRetryDelay: FiniteDuration,
                         unhandledExceptionStrategy: UnhandledExceptionStrategy,
                         revocationTimeout: FiniteDuration,
                         requireConfirmedInputsForDualFunding: Boolean,
                         channelOpenerWhitelist: Set[PublicKey],
                         maxPendingChannelsPerPeer: Int,
                         maxTotalPendingChannelsPrivateNodes: Int,
                         remoteRbfLimits: RemoteRbfLimits) {
    require(0 <= maxHtlcValueInFlightPercent && maxHtlcValueInFlightPercent <= 100, "max-htlc-value-in-flight-percent must be between 0 and 100")

    def minFundingSatoshis(announceChannel: Boolean): Satoshi = if (announceChannel) minFundingPublicSatoshis else minFundingPrivateSatoshis
  }

  trait TxPublisherFactory {
    def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): typed.ActorRef[TxPublisher.Command]
  }

  case class SimpleTxPublisherFactory(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], bitcoinClient: BitcoinCoreClient) extends TxPublisherFactory {
    override def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): typed.ActorRef[TxPublisher.Command] = {
      context.spawn(Behaviors.supervise(TxPublisher(nodeParams, remoteNodeId, TxPublisher.SimpleChildFactory(nodeParams, bitcoinClient, watcher))).onFailure(typed.SupervisorStrategy.restart), "tx-publisher")
    }
  }

  def props(nodeParams: NodeParams, wallet: OnChainChannelFunder with OnchainPubkeyCache, remoteNodeId: PublicKey, blockchain: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, txPublisherFactory: TxPublisherFactory, origin_opt: Option[ActorRef]): Props =
    Props(new Channel(nodeParams, wallet, remoteNodeId, blockchain, relayer, txPublisherFactory, origin_opt))

  // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  val ANNOUNCEMENTS_MINCONF = 6

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
  val MAX_FUNDING: Satoshi = 16777216 sat // = 2^24
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
  val MAX_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(7 * 144) // one week

  // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
  val MAX_TO_SELF_DELAY: CltvExpiryDelta = CltvExpiryDelta(2016)

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
  case object AboveReserve extends BroadcastReason

  private[channel] sealed trait BitcoinEvent extends PossiblyHarmful
  private[channel] case object BITCOIN_FUNDING_PUBLISH_FAILED extends BitcoinEvent
  private[channel] case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
  private[channel] case class BITCOIN_FUNDING_DOUBLE_SPENT(fundingTxIds: Set[ByteVector32]) extends BitcoinEvent
  // @formatter:on

  case object TickChannelOpenTimeout

  // we will receive this message when we waited too long for a revocation for that commit number (NB: we explicitly specify the peer to allow for testing)
  case class RevocationTimeout(remoteCommitNumber: Long, peer: ActorRef)

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

class Channel(val nodeParams: NodeParams, val wallet: OnChainChannelFunder with OnchainPubkeyCache, val remoteNodeId: PublicKey, val blockchain: typed.ActorRef[ZmqWatcher.Command], val relayer: ActorRef, val txPublisherFactory: Channel.TxPublisherFactory, val origin_opt: Option[ActorRef] = None)(implicit val ec: ExecutionContext = ExecutionContext.Implicits.global)
  extends FSM[ChannelState, ChannelData]
    with FSMDiagnosticActorLogging[ChannelState, ChannelData]
    with ChannelOpenSingleFunded
    with ChannelOpenDualFunded
    with CommonHandlers
    with ErrorHandlers {

  import Channel._

  val keyManager: ChannelKeyManager = nodeParams.channelKeyManager

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

  val txPublisher = txPublisherFactory.spawnTxPublisher(context, remoteNodeId)

  // this will be used to detect htlc timeouts
  context.system.eventStream.subscribe(self, classOf[CurrentBlockHeight])
  // the constant delay by which we delay processing of blocks (it will be smoothened among all channels)
  private val blockProcessingDelay = Random.nextLong(nodeParams.channelConf.maxBlockProcessingDelay.toMillis + 1).millis
  // this will be used to make sure the current commitment fee is up-to-date
  context.system.eventStream.subscribe(self, classOf[CurrentFeerates])

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
      context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isInitiator = true, input.temporaryChannelId, input.commitTxFeerate, Some(input.fundingTxFeerate)))
      activeConnection = input.remote
      txPublisher ! SetChannelId(remoteNodeId, input.temporaryChannelId)
      // We will process the input in the next state differently depending on whether we use dual-funding or not.
      self ! input
      if (input.dualFunded) {
        goto(WAIT_FOR_INIT_DUAL_FUNDED_CHANNEL)
      } else {
        goto(WAIT_FOR_INIT_SINGLE_FUNDED_CHANNEL)
      }

    case Event(input: INPUT_INIT_CHANNEL_NON_INITIATOR, Nothing) if !input.localParams.isInitiator =>
      activeConnection = input.remote
      txPublisher ! SetChannelId(remoteNodeId, input.temporaryChannelId)
      if (input.dualFunded) {
        goto(WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL) using DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL(input)
      } else {
        goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(input)
      }

    case Event(INPUT_RESTORED(data), _) =>
      log.debug("restoring channel")
      context.system.eventStream.publish(ChannelRestored(self, data.channelId, peer, remoteNodeId, data))
      txPublisher ! SetChannelId(remoteNodeId, data.channelId)

      // we watch all unconfirmed funding txs, whatever our state is
      // (there can be multiple funding txs due to rbf, and they can be unconfirmed in any state due to zero-conf)
      data.commitments.active.foreach { commitment =>
        commitment.localFundingStatus match {
          case _: LocalFundingStatus.SingleFundedUnconfirmedFundingTx =>
            // NB: in the case of legacy single-funded channels, the funding tx may actually be confirmed already (and
            // the channel fully operational). We could have set a specific Unknown status, but it would have forced
            // us to keep it forever. Instead, we just put a watch which, if the funding tx was indeed confirmed, will
            // trigger instantly, and the state will be updated and a watch-spent will be set. This will only happen
            // once, because at the next restore, the status of the funding tx will be "confirmed".
            blockchain ! GetTxWithMeta(self, commitment.fundingTxId)
            watchFundingConfirmed(commitment.fundingTxId, Some(singleFundingMinDepth(data)))
          case fundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx =>
            publishFundingTx(fundingTx)
            watchFundingConfirmed(fundingTx.sharedTx.txId, fundingTx.fundingParams.minDepth_opt)
          case fundingTx: LocalFundingStatus.ZeroconfPublishedFundingTx =>
            // those are zero-conf channels, the min-depth isn't critical, we use the default
            watchFundingConfirmed(fundingTx.tx.txid, Some(nodeParams.channelConf.minDepthBlocks.toLong))
          case _: LocalFundingStatus.ConfirmedFundingTx =>
            data match {
              case closing: DATA_CLOSING if Closing.nothingAtStake(closing) || Closing.isClosingTypeAlreadyKnown(closing).isDefined =>
                // no need to do anything
                ()
              case closing: DATA_CLOSING =>
                // in all other cases we need to be ready for any type of closing
                watchFundingSpent(commitment, closing.spendingTxs.map(_.txid).toSet)
              case _ =>
                watchFundingSpent(commitment)
            }
        }
      }

      data match {
        // NB: order matters!
        case closing: DATA_CLOSING if Closing.nothingAtStake(closing) =>
          log.info("we have nothing at stake, going straight to CLOSED")
          context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, closing.channelId))
          goto(CLOSED) using closing
        case closing: DATA_CLOSING =>
          val isInitiator = closing.commitments.params.localParams.isInitiator
          // we don't put back the WatchSpent if the commitment tx has already been published and the spending tx already reached mindepth
          val closingType_opt = Closing.isClosingTypeAlreadyKnown(closing)
          log.info(s"channel is closing (closingType=${closingType_opt.map(c => EventType.Closed(c).label).getOrElse("UnknownYet")})")
          // if the closing type is known:
          // - there is no need to watch the funding tx because it has already been spent and the spending tx has already reached mindepth
          // - there is no need to attempt to publish transactions for other type of closes
          // - there is a single commitment, the others have all been invalidated
          closingType_opt match {
            case Some(c: Closing.MutualClose) =>
              doPublish(c.tx, isInitiator)
            case Some(c: Closing.LocalClose) =>
              doPublish(c.localCommitPublished, closing.commitments.latest)
            case Some(c: Closing.RemoteClose) =>
              doPublish(c.remoteCommitPublished, closing.commitments.latest)
            case Some(c: Closing.RecoveryClose) =>
              doPublish(c.remoteCommitPublished, closing.commitments.latest)
            case Some(c: Closing.RevokedClose) =>
              doPublish(c.revokedCommitPublished)
            case None =>
              closing.mutualClosePublished.foreach(mcp => doPublish(mcp, isInitiator))
              closing.localCommitPublished.foreach(lcp => doPublish(lcp, closing.commitments.latest))
              closing.remoteCommitPublished.foreach(rcp => doPublish(rcp, closing.commitments.latest))
              closing.nextRemoteCommitPublished.foreach(rcp => doPublish(rcp, closing.commitments.latest))
              closing.revokedCommitPublished.foreach(doPublish)
              closing.futureRemoteCommitPublished.foreach(rcp => doPublish(rcp, closing.commitments.latest))
          }
          // no need to go OFFLINE, we can directly switch to CLOSING
          goto(CLOSING) using closing

        case normal: DATA_NORMAL =>
          context.system.eventStream.publish(ShortChannelIdAssigned(self, normal.channelId, normal.shortIds, remoteNodeId))

          // we check the configuration because the values for channel_update may have changed while eclair was down
          val fees = getRelayFees(nodeParams, remoteNodeId, data.commitments.announceChannel)
          if (fees.feeBase != normal.channelUpdate.feeBaseMsat ||
            fees.feeProportionalMillionths != normal.channelUpdate.feeProportionalMillionths ||
            nodeParams.channelConf.expiryDelta != normal.channelUpdate.cltvExpiryDelta) {
            log.info("refreshing channel_update due to configuration changes")
            self ! CMD_UPDATE_RELAY_FEE(ActorRef.noSender, fees.feeBase, fees.feeProportionalMillionths, Some(nodeParams.channelConf.expiryDelta))
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
    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) if d.localShutdown.isDefined || d.remoteShutdown.isDefined =>
      // note: spec would allow us to keep sending new htlcs after having received their shutdown (and not sent ours)
      // but we want to converge as fast as possible and they would probably not route them anyway
      val error = NoMoreHtlcsClosingInProgress(d.channelId)
      handleAddHtlcCommandError(c, error, Some(d.channelUpdate))

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) =>
      d.commitments.sendAdd(c, nodeParams.currentBlockHeight, nodeParams.onChainFeeConf) match {
        case Right((commitments1, add)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortIds, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending add
        case Left(cause) => handleAddHtlcCommandError(c, cause, Some(d.channelUpdate))
      }

    case Event(add: UpdateAddHtlc, d: DATA_NORMAL) =>
      d.commitments.receiveAdd(add, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(add))
      }

    case Event(c: CMD_FULFILL_HTLC, d: DATA_NORMAL) =>
      d.commitments.sendFulfill(c) match {
        case Right((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortIds, commitments1))
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
          stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_NORMAL) =>
      c.delay_opt match {
        case Some(delay) =>
          log.debug("delaying CMD_FAIL_HTLC with id={} for {}", c.id, delay)
          context.system.scheduler.scheduleOnce(delay, self, c.copy(delay_opt = None))
          stay()
        case None => d.commitments.sendFail(c, nodeParams.privateKey) match {
          case Right((commitments1, fail)) =>
            if (c.commit) self ! CMD_SIGN()
            context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortIds, commitments1))
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
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortIds, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_NORMAL) =>
      d.commitments.receiveFail(fail) match {
        case Right((commitments1, _, _)) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_NORMAL) =>
      d.commitments.receiveFailMalformed(fail) match {
        case Right((commitments1, _, _)) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_NORMAL) =>
      d.commitments.sendFee(c, nodeParams.onChainFeeConf) match {
        case Right((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortIds, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fee
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_NORMAL) =>
      d.commitments.receiveFee(fee, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c: CMD_SIGN, d: DATA_NORMAL) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !d.commitments.changes.localHasChanges =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay()
        case Right(_) =>
          d.commitments.sendCommit(keyManager) match {
            case Right((commitments1, commit)) =>
              log.debug("sending a new sig, spec:\n{}", commitments1.latest.specs2String)
              val nextRemoteCommit = commitments1.latest.nextRemoteCommit_opt.get.commit
              val nextCommitNumber = nextRemoteCommit.index
              // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
              // counterparty, so only htlcs above remote's dust_limit matter
              val trimmedHtlcs = Transactions.trimOfferedHtlcs(d.commitments.params.remoteParams.dustLimit, nextRemoteCommit.spec, commitments1.params.commitmentFormat) ++
                Transactions.trimReceivedHtlcs(commitments1.params.remoteParams.dustLimit, nextRemoteCommit.spec, commitments1.params.commitmentFormat)
              trimmedHtlcs.map(_.add).foreach { htlc =>
                log.debug(s"adding paymentHash=${htlc.paymentHash} cltvExpiry=${htlc.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber")
                nodeParams.db.channels.addHtlcInfo(d.channelId, nextCommitNumber, htlc.paymentHash, htlc.cltvExpiry)
              }
              if (!Helpers.aboveReserve(d.commitments) && Helpers.aboveReserve(commitments1)) {
                // we just went above reserve (can't go below), let's refresh our channel_update to enable/disable it accordingly
                log.debug("updating channel_update aboveReserve={}", Helpers.aboveReserve(commitments1))
                self ! BroadcastChannelUpdate(AboveReserve)
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

    case Event(commit: CommitSig, d: DATA_NORMAL) =>
      d.commitments.receiveCommit(Seq(commit), keyManager) match {
        case Right((commitments1, revocation)) =>
          log.debug("received a new sig, spec:\n{}", commitments1.latest.specs2String)
          if (commitments1.changes.localHasChanges) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN()
          }
          if (d.commitments.availableBalanceForSend != commitments1.availableBalanceForSend) {
            // we send this event only when our balance changes
            context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortIds, commitments1))
          }
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          stay() using d.copy(commitments = commitments1) storing() sending revocation
        case Left(cause) => handleLocalError(cause, d, Some(commit))
      }

    case Event(revocation: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      d.commitments.receiveRevocation(revocation, nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure) match {
        case Right((commitments1, actions)) =>
          cancelTimer(RevocationTimeout.toString)
          log.debug("received a new rev, spec:\n{}", commitments1.latest.specs2String)
          actions.foreach {
            case PostRevocationAction.RelayHtlc(add) =>
              log.debug("forwarding incoming htlc {} to relayer", add)
              relayer ! Relayer.RelayForward(add)
            case PostRevocationAction.RejectHtlc(add) =>
              log.debug("rejecting incoming htlc {}", add)
              // NB: we don't set commit = true, we will sign all updates at once afterwards.
              self ! CMD_FAIL_HTLC(add.id, Right(TemporaryChannelFailure(d.channelUpdate)), commit = true)
            case PostRevocationAction.RelayFailure(result) =>
              log.debug("forwarding {} to relayer", result)
              relayer ! result
          }
          if (commitments1.changes.localHasChanges) {
            self ! CMD_SIGN()
          }
          if (d.remoteShutdown.isDefined && !commitments1.changes.localHasUnsignedOutgoingHtlcs) {
            // we were waiting for our pending htlcs to be signed before replying with our local shutdown
            val finalScriptPubKey = commitments1.params.localParams.upfrontShutdownScript_opt.getOrElse(getOrGenerateFinalScriptPubKey(d))
            val localShutdown = Shutdown(d.channelId, finalScriptPubKey)
            // note: it means that we had pending htlcs to sign, therefore we go to SHUTDOWN, not to NEGOTIATING
            require(commitments1.latest.remoteCommit.spec.htlcs.nonEmpty, "we must have just signed new htlcs, otherwise we would have sent our Shutdown earlier")
            goto(SHUTDOWN) using DATA_SHUTDOWN(commitments1, localShutdown, d.remoteShutdown.get, d.closingFeerates) storing() sending localShutdown
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
        val localScriptPubKey = c.scriptPubKey.getOrElse(d.commitments.params.localParams.upfrontShutdownScript_opt.getOrElse(getOrGenerateFinalScriptPubKey(d)))
        d.commitments.params.validateLocalShutdownScript(localScriptPubKey) match {
          case Left(e) => handleCommandError(e, c)
          case Right(localShutdownScript) =>
            val shutdown = Shutdown(d.channelId, localShutdownScript)
            handleCommandSuccess(c, d.copy(localShutdown = Some(shutdown), closingFeerates = c.feerates)) storing() sending shutdown
        }
      }

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey, _), d: DATA_NORMAL) =>
      d.commitments.params.validateRemoteShutdownScript(remoteScriptPubKey) match {
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
            stay() using d.copy(remoteShutdown = Some(remoteShutdown))
          } else {
            // so we don't have any unsigned outgoing changes
            val (localShutdown, sendList) = d.localShutdown match {
              case Some(localShutdown) =>
                (localShutdown, Nil)
              case None =>
                val localShutdown = Shutdown(d.channelId, getOrGenerateFinalScriptPubKey(d))
                // we need to send our shutdown if we didn't previously
                (localShutdown, localShutdown :: Nil)
            }
            // are there pending signed changes on either side? we need to have received their last revocation!
            if (d.commitments.hasNoPendingHtlcsOrFeeUpdate) {
              // there are no pending signed changes, let's go directly to NEGOTIATING
              if (d.commitments.params.localParams.isInitiator) {
                // we are the channel initiator, need to initiate the negotiation by sending the first closing_signed
                val (closingTx, closingSigned) = Closing.MutualClose.makeFirstClosingTx(keyManager, d.commitments.latest, localShutdown.scriptPubKey, remoteShutdownScript, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, d.closingFeerates)
                goto(NEGOTIATING) using DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending sendList :+ closingSigned
              } else {
                // we are not the channel initiator, will wait for their closing_signed
                goto(NEGOTIATING) using DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing() sending sendList
              }
            } else {
              // there are some pending signed changes, we need to wait for them to be settled (fail/fulfill htlcs and sign fee updates)
              goto(SHUTDOWN) using DATA_SHUTDOWN(d.commitments, localShutdown, remoteShutdown, d.closingFeerates) storing() sending sendList
            }
          }
      }

    case Event(ProcessCurrentBlockHeight(c), d: DATA_NORMAL) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: DATA_NORMAL) => handleCurrentFeerate(c, d)

    case Event(WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, fundingTx), d: DATA_NORMAL) if d.channelAnnouncement.isEmpty =>
      val finalRealShortId = RealScidStatus.Final(RealShortChannelId(blockHeight, txIndex, d.commitments.latest.commitInput.outPoint.index.toInt))
      log.info(s"funding tx is deeply buried at blockHeight=$blockHeight txIndex=$txIndex shortChannelId=${finalRealShortId.realScid}")
      val shortIds1 = d.shortIds.copy(real = finalRealShortId)
      context.system.eventStream.publish(ShortChannelIdAssigned(self, d.channelId, shortIds1, remoteNodeId))
      if (d.shortIds.real == RealScidStatus.Unknown) {
        // this is a zero-conf channel and it is the first time we know for sure that the funding tx has been confirmed
        context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, fundingTx))
      }
      val scidForChannelUpdate = Helpers.scidForChannelUpdate(d.channelAnnouncement, shortIds1.localAlias)
      // if the shortChannelId is different from the one we had before, we need to re-announce it
      val channelUpdate1 = if (d.channelUpdate.shortChannelId != scidForChannelUpdate) {
        log.info(s"using new scid in channel_update: old=${d.channelUpdate.shortChannelId} new=$scidForChannelUpdate")
        // we re-announce the channelUpdate for the same reason
        Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = !d.commitments.announceChannel, enable = Helpers.aboveReserve(d.commitments))
      } else {
        d.channelUpdate
      }
      if (d.commitments.announceChannel) {
        // if channel is public we need to send our announcement_signatures in order to generate the channel_announcement
        val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments.params, finalRealShortId.realScid)
        // we use goto() instead of stay() because we want to fire transitions
        goto(NORMAL) using d.copy(shortIds = shortIds1, channelUpdate = channelUpdate1) storing() sending localAnnSigs
      } else {
        // we use goto() instead of stay() because we want to fire transitions
        goto(NORMAL) using d.copy(shortIds = shortIds1, channelUpdate = channelUpdate1) storing()
      }

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_NORMAL) if d.commitments.announceChannel =>
      // channels are publicly announced if both parties want it (defined as feature bit)
      d.shortIds.real match {
        case RealScidStatus.Final(realScid) =>
          // we are aware that the channel has reached enough confirmations
          // we already had sent our announcement_signatures but we don't store them so we need to recompute it
          val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments.params, realScid)
          d.channelAnnouncement match {
            case None =>
              require(localAnnSigs.shortChannelId == remoteAnnSigs.shortChannelId, s"shortChannelId mismatch: local=${localAnnSigs.shortChannelId} remote=${remoteAnnSigs.shortChannelId}")
              log.info(s"announcing channelId=${d.channelId} on the network with shortId=${localAnnSigs.shortChannelId}")
              val fundingPubKey = keyManager.fundingPublicKey(d.commitments.params.localParams.fundingKeyPath)
              val channelAnn = Announcements.makeChannelAnnouncement(nodeParams.chainHash, localAnnSigs.shortChannelId, nodeParams.nodeId, d.commitments.params.remoteParams.nodeId, fundingPubKey.publicKey, d.commitments.params.remoteParams.fundingPubKey, localAnnSigs.nodeSignature, remoteAnnSigs.nodeSignature, localAnnSigs.bitcoinSignature, remoteAnnSigs.bitcoinSignature)
              if (!Announcements.checkSigs(channelAnn)) {
                handleLocalError(InvalidAnnouncementSignatures(d.channelId, remoteAnnSigs), d, Some(remoteAnnSigs))
              } else {
                // we generate a new channel_update because the scid used may change if we were previously using an alias
                val scidForChannelUpdate = Helpers.scidForChannelUpdate(Some(channelAnn), d.shortIds.localAlias)
                val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = false, enable = Helpers.aboveReserve(d.commitments))
                // we use goto() instead of stay() because we want to fire transitions
                goto(NORMAL) using d.copy(channelAnnouncement = Some(channelAnn), channelUpdate = channelUpdate) storing()
              }
            case Some(_) =>
              // they have sent their announcement sigs, but we already have a valid channel announcement
              // this can happen if our announcement_signatures was lost during a disconnection
              // specs says that we "MUST respond to the first announcement_signatures message after reconnection with its own announcement_signatures message"
              // current implementation always replies to announcement_signatures, not only the first time
              // TODO: we should only be nice once, current behaviour opens way to DOS, but this should be handled higher in the stack anyway
              log.debug("re-sending our announcement sigs")
              stay() sending localAnnSigs
          }
        case _ =>
          // our watcher didn't notify yet that the tx has reached ANNOUNCEMENTS_MINCONF confirmations, let's delay remote's message
          // note: no need to persist their message, in case of disconnection they will resend it
          log.debug("received remote announcement signatures, delaying")
          context.system.scheduler.scheduleOnce(5 seconds, self, remoteAnnSigs)
          stay()
      }

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) =>
      val channelUpdate1 = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate(d), c.cltvExpiryDelta_opt.getOrElse(d.channelUpdate.cltvExpiryDelta), d.channelUpdate.htlcMinimumMsat, c.feeBase, c.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = !d.commitments.announceChannel, enable = Helpers.aboveReserve(d.commitments))
      log.info(s"updating relay fees: prev={} next={}", d.channelUpdate.toStringShort, channelUpdate1.toStringShort)
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_SUCCESS(c, d.channelId)
      // we use goto() instead of stay() because we want to fire transitions
      goto(NORMAL) using d.copy(channelUpdate = channelUpdate1) storing()

    case Event(BroadcastChannelUpdate(reason), d: DATA_NORMAL) =>
      val age = TimestampSecond.now() - d.channelUpdate.timestamp
      val channelUpdate1 = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate(d), d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = !d.commitments.announceChannel, enable = Helpers.aboveReserve(d.commitments))
      reason match {
        case Reconnected if d.commitments.announceChannel && Announcements.areSame(channelUpdate1, d.channelUpdate) && age < REFRESH_CHANNEL_UPDATE_INTERVAL =>
          // we already sent an identical channel_update not long ago (flapping protection in case we keep being disconnected/reconnected)
          log.debug("not sending a new identical channel_update, current one was created {} days ago", age.toDays)
          stay()
        case _ =>
          log.debug("refreshing channel_update announcement (reason={})", reason)
          // we use goto() instead of stay() because we want to fire transitions
          goto(NORMAL) using d.copy(channelUpdate = channelUpdate1) storing()
      }

    case Event(INPUT_DISCONNECTED, d: DATA_NORMAL) =>
      // we cancel the timer that would have made us send the enabled update after reconnection (flappy channel protection)
      cancelTimer(Reconnected.toString)
      // if we have pending unsigned htlcs, then we cancel them and generate an update with the disabled flag set, that will be returned to the sender in a temporary channel failure
      if (d.commitments.changes.localChanges.proposed.collectFirst { case add: UpdateAddHtlc => add }.isDefined) {
        log.debug("updating channel_update announcement (reason=disabled)")
        val channelUpdate1 = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate(d), d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = !d.commitments.announceChannel, enable = false)
        // NB: the htlcs stay() in the commitments.localChange, they will be cleaned up after reconnection
        d.commitments.changes.localChanges.proposed.collect {
          case add: UpdateAddHtlc => relayer ! RES_ADD_SETTLED(d.commitments.originChannels(add.id), add, HtlcResult.DisconnectedBeforeSigned(channelUpdate1))
        }
        goto(OFFLINE) using d.copy(channelUpdate = channelUpdate1) storing()
      } else {
        goto(OFFLINE) using d
      }

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)

    case Event(_: ChannelReady, _: DATA_NORMAL) => stay() // will happen after a reconnection if no updates were ever committed to the channel

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
      d.commitments.sendFulfill(c) match {
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
      d.commitments.sendFail(c, nodeParams.privateKey) match {
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
      d.commitments.receiveFee(fee, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay() using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c: CMD_SIGN, d: DATA_SHUTDOWN) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !d.commitments.changes.localHasChanges =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay()
        case Right(_) =>
          d.commitments.sendCommit(keyManager) match {
            case Right((commitments1, commit)) =>
              log.debug("sending a new sig, spec:\n{}", commitments1.latest.specs2String)
              val nextRemoteCommit = commitments1.latest.nextRemoteCommit_opt.get.commit
              val nextCommitNumber = nextRemoteCommit.index
              // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
              // counterparty, so only htlcs above remote's dust_limit matter
              val trimmedHtlcs = Transactions.trimOfferedHtlcs(d.commitments.params.remoteParams.dustLimit, nextRemoteCommit.spec, d.commitments.params.commitmentFormat) ++
                Transactions.trimReceivedHtlcs(d.commitments.params.remoteParams.dustLimit, nextRemoteCommit.spec, d.commitments.params.commitmentFormat)
              trimmedHtlcs.map(_.add).foreach { htlc =>
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

    case Event(commit: CommitSig, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown, closingFeerates)) =>
      d.commitments.receiveCommit(Seq(commit), keyManager) match {
        case Right((commitments1, revocation)) =>
          // we always reply with a revocation
          log.debug("received a new sig:\n{}", commitments1.latest.specs2String)
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          if (commitments1.hasNoPendingHtlcsOrFeeUpdate) {
            if (d.commitments.params.localParams.isInitiator) {
              // we are the channel initiator, need to initiate the negotiation by sending the first closing_signed
              val (closingTx, closingSigned) = Closing.MutualClose.makeFirstClosingTx(keyManager, commitments1.latest, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, closingFeerates)
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

    case Event(revocation: RevokeAndAck, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown, closingFeerates)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked including the shutdown message
      d.commitments.receiveRevocation(revocation, nodeParams.onChainFeeConf.feerateToleranceFor(remoteNodeId).dustTolerance.maxExposure) match {
        case Right((commitments1, actions)) =>
          cancelTimer(RevocationTimeout.toString)
          log.debug("received a new rev, spec:\n{}", commitments1.latest.specs2String)
          actions.foreach {
            case PostRevocationAction.RelayHtlc(add) =>
              // BOLT 2: A sending node SHOULD fail to route any HTLC added after it sent shutdown.
              log.debug("closing in progress: failing {}", add)
              self ! CMD_FAIL_HTLC(add.id, Right(PermanentChannelFailure()), commit = true)
            case PostRevocationAction.RejectHtlc(add) =>
              // BOLT 2: A sending node SHOULD fail to route any HTLC added after it sent shutdown.
              log.debug("closing in progress: rejecting {}", add)
              self ! CMD_FAIL_HTLC(add.id, Right(PermanentChannelFailure()), commit = true)
            case PostRevocationAction.RelayFailure(result) =>
              log.debug("forwarding {} to relayer", result)
              relayer ! result
          }
          if (commitments1.hasNoPendingHtlcsOrFeeUpdate) {
            log.debug("switching to NEGOTIATING spec:\n{}", commitments1.latest.specs2String)
            if (d.commitments.params.localParams.isInitiator) {
              // we are the channel initiator, need to initiate the negotiation by sending the first closing_signed
              val (closingTx, closingSigned) = Closing.MutualClose.makeFirstClosingTx(keyManager, commitments1.latest, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, closingFeerates)
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

    case Event(r: RevocationTimeout, d: DATA_SHUTDOWN) => handleRevocationTimeout(r, d)

    case Event(ProcessCurrentBlockHeight(c), d: DATA_SHUTDOWN) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: DATA_SHUTDOWN) => handleCurrentFeerate(c, d)

    case Event(c: CMD_CLOSE, d: DATA_SHUTDOWN) =>
      c.feerates match {
        case Some(feerates) if c.feerates != d.closingFeerates =>
          if (c.scriptPubKey.nonEmpty && !c.scriptPubKey.contains(d.localShutdown.scriptPubKey)) {
            log.warning("cannot update closing script when closing is already in progress")
            handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
          } else {
            log.info("updating our closing feerates: {}", feerates)
            handleCommandSuccess(c, d.copy(closingFeerates = c.feerates)) storing()
          }
        case _ =>
          handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      }

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)

  })

  when(NEGOTIATING)(handleExceptions {
    // Upon reconnection, nodes must re-transmit their shutdown message, so we may receive it now.
    case Event(remoteShutdown: Shutdown, d: DATA_NEGOTIATING) =>
      if (remoteShutdown != d.remoteShutdown) {
        // This is a spec violation: it will likely lead to a disagreement when exchanging closing_signed and a force-close.
        log.warning("received unexpected shutdown={} (previous={})", remoteShutdown, d.remoteShutdown)
      }
      stay()

    case Event(c: ClosingSigned, d: DATA_NEGOTIATING) =>
      val (remoteClosingFee, remoteSig) = (c.feeSatoshis, c.signature)
      Closing.MutualClose.checkClosingSignature(keyManager, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, remoteClosingFee, remoteSig) match {
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
              case Some(ClosingSignedTlv.FeeRange(minFee, maxFee)) if !d.commitments.params.localParams.isInitiator =>
                // if we are not the channel initiator and they proposed a fee range, we pick a value in that range and they should accept it without further negotiation
                // we don't care much about the closing fee since they're paying it (not us) and we can use CPFP if we want to speed up confirmation
                val localClosingFees = Closing.MutualClose.firstClosingFee(d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
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
                    val (closingTx, closingSigned) = Closing.MutualClose.makeClosingTx(keyManager, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, ClosingFees(closingFee, minFee, maxFee))
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
                  val localClosingFees = Closing.MutualClose.firstClosingFee(d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
                  val nextPreferredFee = Closing.MutualClose.nextClosingFee(lastLocalClosingFee_opt.getOrElse(localClosingFees.preferred), remoteClosingFee)
                  Closing.MutualClose.makeClosingTx(keyManager, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, localClosingFees.copy(preferred = nextPreferredFee))
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
            val (closingTx, closingSigned) = Closing.MutualClose.makeFirstClosingTx(keyManager, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, Some(feerates))
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

  when(CLOSING)(handleExceptions {
    case Event(c: HtlcSettlementCommand, d: DATA_CLOSING) =>
      (c match {
        case c: CMD_FULFILL_HTLC => d.commitments.sendFulfill(c)
        case c: CMD_FAIL_HTLC => d.commitments.sendFail(c, nodeParams.privateKey)
        case c: CMD_FAIL_MALFORMED_HTLC => d.commitments.sendFailMalformed(c)
      }) match {
        case Right((commitments1, _)) =>
          log.info("got valid settlement for htlc={}, recalculating htlc transactions", c.id)
          val commitment = commitments1.latest
          val localCommitPublished1 = d.localCommitPublished.map(localCommitPublished => localCommitPublished.copy(htlcTxs = Closing.LocalClose.claimHtlcOutputs(keyManager, commitment)))
          val remoteCommitPublished1 = d.remoteCommitPublished.map(remoteCommitPublished => remoteCommitPublished.copy(claimHtlcTxs = Closing.RemoteClose.claimHtlcOutputs(keyManager, commitment, commitment.remoteCommit, nodeParams.onChainFeeConf.feeEstimator, d.finalScriptPubKey)))
          val nextRemoteCommitPublished1 = d.nextRemoteCommitPublished.map(remoteCommitPublished => remoteCommitPublished.copy(claimHtlcTxs = Closing.RemoteClose.claimHtlcOutputs(keyManager, commitment, commitment.nextRemoteCommit_opt.get.commit, nodeParams.onChainFeeConf.feeEstimator, d.finalScriptPubKey)))

          def republish(): Unit = {
            localCommitPublished1.foreach(lcp => doPublish(lcp, commitment))
            remoteCommitPublished1.foreach(rcp => doPublish(rcp, commitment))
            nextRemoteCommitPublished1.foreach(rcp => doPublish(rcp, commitment))
          }
          // TODO: when using splices we should be updating all competing commitments
          handleCommandSuccess(c, d.copy(commitments = commitments1, localCommitPublished = localCommitPublished1, remoteCommitPublished = remoteCommitPublished1, nextRemoteCommitPublished = nextRemoteCommitPublished1)) storing() calling republish()
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
        case Right((commitments1, _)) =>
          if (d.commitments.latest.fundingTxId == w.tx.txid) {
            // The best funding tx candidate has been confirmed, alternative commitments have been pruned
            stay() using d.copy(commitments = commitments1) storing()
          } else {
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
            val commitment = commitments1.latest
            val d1 = d.copy(commitments = commitments1)
            spendLocalCurrent(d1)
          }
        case Left(_) => stay()
      }

    case Event(WatchFundingSpentTriggered(tx), d: DATA_CLOSING) =>
      if (d.mutualClosePublished.exists(_.tx.txid == tx.txid)) {
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
      } else if (tx.txid == d.commitments.latest.remoteCommit.txid) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentCurrent(tx, d)
      } else if (d.commitments.latest.nextRemoteCommit_opt.exists(_.commit.txid == tx.txid)) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentNext(tx, d)
      } else if (tx.txIn.map(_.outPoint.txid).contains(d.commitments.latest.fundingTxId)) {
        // counterparty may attempt to spend a revoked commit tx at any time
        handleRemoteSpentOther(tx, d)
      } else {
        log.warning(s"unrecognized tx=${tx.txid}")
        // this was for another commitments
        stay()
      }

    case Event(WatchOutputSpentTriggered(tx), d: DATA_CLOSING) =>
      // one of the outputs of the local/remote/revoked commit was spent
      // we just put a watch to be notified when it is confirmed
      blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.channelConf.minDepthBlocks)
      // when a remote or local commitment tx containing outgoing htlcs is published on the network,
      // we watch it in order to extract payment preimage if funds are pulled by the counterparty
      // we can then use these preimages to fulfill origin htlcs
      log.debug(s"processing bitcoin output spent by txid=${tx.txid} tx=$tx")
      val extracted = Closing.extractPreimages(d.commitments.latest.localCommit, tx)
      extracted.foreach { case (htlc, preimage) =>
        d.commitments.originChannels.get(htlc.id) match {
          case Some(origin) =>
            log.info(s"fulfilling htlc #${htlc.id} paymentHash=${htlc.paymentHash} origin=$origin")
            relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.OnChainFulfill(preimage))
          case None =>
            // if we don't have the origin, it means that we already have forwarded the fulfill so that's not a big deal.
            // this can happen if they send a signature containing the fulfill, then fail the channel before we have time to sign it
            log.info(s"cannot fulfill htlc #${htlc.id} paymentHash=${htlc.paymentHash} (origin not found)")
        }
      }
      val revokedCommitPublished1 = d.revokedCommitPublished.map { rev =>
        val (rev1, penaltyTxs) = Closing.RevokedClose.claimHtlcTxOutputs(keyManager, d.commitments.params, d.commitments.remotePerCommitmentSecrets, rev, tx, nodeParams.onChainFeeConf.feeEstimator, d.finalScriptPubKey)
        penaltyTxs.foreach(claimTx => txPublisher ! PublishFinalTx(claimTx, claimTx.fee, None))
        penaltyTxs.foreach(claimTx => blockchain ! WatchOutputSpent(self, tx.txid, claimTx.input.outPoint.index.toInt, hints = Set(claimTx.tx.txid)))
        rev1
      }
      stay() using d.copy(revokedCommitPublished = revokedCommitPublished1) storing()

    case Event(WatchTxConfirmedTriggered(blockHeight, _, tx), d: DATA_CLOSING) =>
      log.info(s"txid=${tx.txid} has reached mindepth, updating closing state")
      context.system.eventStream.publish(TransactionConfirmed(d.channelId, remoteNodeId, tx))
      // first we check if this tx belongs to one of the current local/remote commits, update it and update the channel data
      val d1 = d.copy(
        localCommitPublished = d.localCommitPublished.map(localCommitPublished => {
          // If the tx is one of our HTLC txs, we now publish a 3rd-stage claim-htlc-tx that claims its output.
          val (localCommitPublished1, claimHtlcTx_opt) = Closing.LocalClose.claimHtlcDelayedOutput(localCommitPublished, keyManager, d.commitments.latest, tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, d.finalScriptPubKey)
          claimHtlcTx_opt.foreach(claimHtlcTx => {
            txPublisher ! PublishFinalTx(claimHtlcTx, claimHtlcTx.fee, None)
            blockchain ! WatchTxConfirmed(self, claimHtlcTx.tx.txid, nodeParams.channelConf.minDepthBlocks)
          })
          Closing.updateLocalCommitPublished(localCommitPublished1, tx)
        }),
        remoteCommitPublished = d.remoteCommitPublished.map(Closing.updateRemoteCommitPublished(_, tx)),
        nextRemoteCommitPublished = d.nextRemoteCommitPublished.map(Closing.updateRemoteCommitPublished(_, tx)),
        futureRemoteCommitPublished = d.futureRemoteCommitPublished.map(Closing.updateRemoteCommitPublished(_, tx)),
        revokedCommitPublished = d.revokedCommitPublished.map(Closing.updateRevokedCommitPublished(_, tx))
      )
      // if the local commitment tx just got confirmed, let's send an event telling when we will get the main output refund
      if (d1.localCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        context.system.eventStream.publish(LocalCommitConfirmed(self, remoteNodeId, d.channelId, blockHeight + d.commitments.params.remoteParams.toSelfDelay.toInt))
      }
      // we may need to fail some htlcs in case a commitment tx was published and they have reached the timeout threshold
      val timedOutHtlcs = Closing.isClosingTypeAlreadyKnown(d1) match {
        case Some(c: Closing.LocalClose) => Closing.trimmedOrTimedOutHtlcs(d.commitments.params.commitmentFormat, c.localCommit, c.localCommitPublished, d.commitments.params.localParams.dustLimit, tx)
        case Some(c: Closing.RemoteClose) => Closing.trimmedOrTimedOutHtlcs(d.commitments.params.commitmentFormat, c.remoteCommit, c.remoteCommitPublished, d.commitments.params.remoteParams.dustLimit, tx)
        case _ => Set.empty[UpdateAddHtlc] // we lose htlc outputs in dataloss protection scenarios (future remote commit)
      }
      timedOutHtlcs.foreach { add =>
        d.commitments.originChannels.get(add.id) match {
          case Some(origin) =>
            log.info(s"failing htlc #${add.id} paymentHash=${add.paymentHash} origin=$origin: htlc timed out")
            relayer ! RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(d.channelId, Set(add))))
          case None =>
            // same as for fulfilling the htlc (no big deal)
            log.info(s"cannot fail timed out htlc #${add.id} paymentHash=${add.paymentHash} (origin not found)")
        }
      }
      // we also need to fail outgoing htlcs that we know will never reach the blockchain
      Closing.overriddenOutgoingHtlcs(d, tx).foreach { add =>
        d.commitments.originChannels.get(add.id) match {
          case Some(origin) =>
            log.info(s"failing htlc #${add.id} paymentHash=${add.paymentHash} origin=$origin: overridden by local commit")
            relayer ! RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(d.channelId, add)))
          case None =>
            // same as for fulfilling the htlc (no big deal)
            log.info(s"cannot fail overridden htlc #${add.id} paymentHash=${add.paymentHash} (origin not found)")
        }
      }
      // for our outgoing payments, let's send events if we know that they will settle on chain
      Closing
        .onChainOutgoingHtlcs(d.commitments.latest.localCommit, d.commitments.latest.remoteCommit, d.commitments.latest.nextRemoteCommit_opt.map(_.commit), tx)
        .map(add => (add, d.commitments.originChannels.get(add.id).collect { case o: Origin.Local => o.id })) // we resolve the payment id if this was a local payment
        .collect { case (add, Some(id)) => context.system.eventStream.publish(PaymentSettlingOnChain(id, amount = add.amountMsat, add.paymentHash)) }
      // then let's see if any of the possible close scenarios can be considered done
      val closingType_opt = Closing.isClosed(d1, Some(tx))
      // finally, if one of the unilateral closes is done, we move to CLOSED state, otherwise we stay()
      closingType_opt match {
        case Some(closingType) =>
          log.info(s"channel closed (type=${closingType_opt.map(c => EventType.Closed(c).label).getOrElse("UnknownYet")})")
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

    case Event(e: Error, d: DATA_CLOSING) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED | INPUT_RECONNECTED(_, _, _), _) => stay() // we don't really care at this point
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

    case Event(INPUT_DISCONNECTED, _) => stay() // we are disconnected, but it doesn't matter anymore
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

    case Event(INPUT_RECONNECTED(r, localInit, remoteInit), d: PersistentChannelData) =>
      activeConnection = r

      val remotePerCommitmentSecrets = d.commitments.remotePerCommitmentSecrets
      val yourLastPerCommitmentSecret = remotePerCommitmentSecrets.lastIndex.flatMap(remotePerCommitmentSecrets.getHash).getOrElse(ByteVector32.Zeroes)
      val channelKeyPath = keyManager.keyPath(d.commitments.params.localParams, d.commitments.params.channelConfig)
      val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, d.commitments.localCommitIndex)

      val channelReestablish = ChannelReestablish(
        channelId = d.channelId,
        nextLocalCommitmentNumber = d.commitments.localCommitIndex + 1,
        nextRemoteRevocationNumber = d.commitments.remoteCommitIndex,
        yourLastPerCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
        myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
      )

      // we update local/remote connection-local global/local features, we don't persist it right now
      val d1 = Helpers.updateFeatures(d, localInit, remoteInit)

      goto(SYNCING) using d1 sending channelReestablish

    case Event(ProcessCurrentBlockHeight(c), d: PersistentChannelData) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: PersistentChannelData) => handleCurrentFeerateDisconnected(c, d)

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) => handleAddDisconnected(c, d)

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) => handleUpdateRelayFeeDisconnected(c, d)

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.latest.fundingTxId => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx_opt)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleDualFundingDoubleSpent(e, d)

    // just ignore this, we will put a new watch when we reconnect, and we'll be notified again
    case Event(_: WatchFundingDeeplyBuriedTriggered, _) => stay()
  })

  when(SYNCING)(handleExceptions {
    case Event(_: ChannelReestablish, _: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      goto(WAIT_FOR_FUNDING_CONFIRMED)

    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) =>
      goto(WAIT_FOR_DUAL_FUNDING_CONFIRMED) sending d.latestFundingTx.sharedTx.localSigs

    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_CHANNEL_READY) =>
      log.debug("re-sending channelReady")
      val channelReady = createChannelReady(d.shortIds, d.commitments.params)
      goto(WAIT_FOR_CHANNEL_READY) sending channelReady

    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_DUAL_FUNDING_READY) =>
      log.debug("re-sending channelReady")
      val channelReady = createChannelReady(d.shortIds, d.commitments.params)
      goto(WAIT_FOR_DUAL_FUNDING_READY) sending channelReady

    case Event(channelReestablish: ChannelReestablish, d: DATA_NORMAL) =>
      Syncing.checkSync(keyManager, d.commitments, channelReestablish) match {
        case syncFailure: SyncResult.Failure =>
          handleSyncFailure(channelReestablish, syncFailure, d)
        case syncSuccess: SyncResult.Success =>
          var sendQueue = Queue.empty[LightningMessage]
          // normal case, our data is up-to-date

          if (channelReestablish.nextLocalCommitmentNumber == 1 && d.commitments.localCommitIndex == 0) {
            // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit channel_ready, otherwise it MUST NOT
            log.debug("re-sending channelReady")
            val channelKeyPath = keyManager.keyPath(d.commitments.params.localParams, d.commitments.params.channelConfig)
            val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
            val channelReady = ChannelReady(d.commitments.channelId, nextPerCommitmentPoint)
            sendQueue = sendQueue :+ channelReady
          }

          // we may need to retransmit updates and/or commit_sig and/or revocation
          sendQueue = sendQueue ++ syncSuccess.retransmit

          // then we clean up unsigned updates
          val commitments1 = d.commitments.discardUnsignedUpdates()

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
              log.debug("re-sending localShutdown")
              sendQueue = sendQueue :+ localShutdown
          }

          d.shortIds.real match {
            case RealScidStatus.Final(realShortChannelId) =>
              // should we (re)send our announcement sigs?
              if (d.commitments.announceChannel && d.channelAnnouncement.isEmpty) {
                // BOLT 7: a node SHOULD retransmit the announcement_signatures message if it has not received an announcement_signatures message
                val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments.params, realShortChannelId)
                sendQueue = sendQueue :+ localAnnSigs
              }
            case _ =>
              // even if we were just disconnected/reconnected, we need to put back the watch because the event may have been
              // fired while we were in OFFLINE (if not, the operation is idempotent anyway)
              blockchain ! WatchFundingDeeplyBuried(self, d.commitments.latest.fundingTxId, ANNOUNCEMENTS_MINCONF)
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
          if (d.commitments.params.localParams.isInitiator && !shutdownInProgress) {
            // TODO: what should we do here if we have multiple commitments using different feerates?
            val currentFeeratePerKw = d.commitments.latest.localCommit.spec.commitTxFeerate
            val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, d.commitments.params.channelType, d.commitments.latest.capacity, None)
            if (nodeParams.onChainFeeConf.shouldUpdateFee(currentFeeratePerKw, networkFeeratePerKw)) {
              self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
            }
          }

          goto(NORMAL) using d.copy(commitments = commitments1) sending sendQueue
      }

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) => handleAddDisconnected(c, d)

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) => handleUpdateRelayFeeDisconnected(c, d)

    case Event(channelReestablish: ChannelReestablish, d: DATA_SHUTDOWN) =>
      Syncing.checkSync(keyManager, d.commitments, channelReestablish) match {
        case syncFailure: SyncResult.Failure =>
          handleSyncFailure(channelReestablish, syncFailure, d)
        case syncSuccess: SyncResult.Success =>
          val commitments1 = d.commitments.discardUnsignedUpdates()
          val sendQueue = Queue.empty[LightningMessage] ++ syncSuccess.retransmit :+ d.localShutdown
          // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
          goto(SHUTDOWN) using d.copy(commitments = commitments1) sending sendQueue
      }

    case Event(_: ChannelReestablish, d: DATA_NEGOTIATING) =>
      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      // negotiation restarts from the beginning, and is initialized by the channel initiator
      // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
      if (d.commitments.params.localParams.isInitiator) {
        // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
        val (closingTx, closingSigned) = Closing.MutualClose.makeFirstClosingTx(keyManager, d.commitments.latest, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, None)
        val closingTxProposed1 = d.closingTxProposed :+ List(ClosingTxProposed(closingTx, closingSigned))
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) storing() sending d.localShutdown :: closingSigned :: Nil
      } else {
        // we start a new round of negotiation
        val closingTxProposed1 = if (d.closingTxProposed.last.isEmpty) d.closingTxProposed else d.closingTxProposed :+ List()
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) sending d.localShutdown
      }

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

    case Event(ProcessCurrentBlockHeight(c), d: PersistentChannelData) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: PersistentChannelData) => handleCurrentFeerateDisconnected(c, d)

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.latest.fundingTxId => handleGetFundingTx(getTxResponse, d.waitingSince, d.fundingTx_opt)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(e: BITCOIN_FUNDING_DOUBLE_SPENT, d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED) => handleDualFundingDoubleSpent(e, d)

    // just ignore this, we will put a new watch when we reconnect, and we'll be notified again
    case Event(_: WatchFundingDeeplyBuriedTriggered, _) => stay()

    case Event(e: Error, d: PersistentChannelData) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)(handleExceptions {
    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => handleRemoteSpentFuture(tx, d)
  })

  private def errorStateHandler: StateFunction = {
    case Event(Symbol("nevermatches"), _) => stay() // we can't define a state with no event handler, so we put a dummy one here
  }

  when(ERR_INFORMATION_LEAK)(errorStateHandler)

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
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_GET_CHANNEL_INFO(remoteNodeId, stateData.channelId, stateName, stateData)
      stay()

    case Event(c: CMD_ADD_HTLC, d: PersistentChannelData) =>
      log.debug(s"rejecting htlc request in state=$stateName")
      val error = ChannelUnavailable(d.channelId)
      handleAddHtlcCommandError(c, error, None) // we don't provide a channel_update: this will be a permanent channel failure

    case Event(c: CMD_CLOSE, d) => handleCommandError(CommandUnavailableInThisState(d.channelId, "close", stateName), c)

    case Event(c: CMD_FORCECLOSE, d) =>
      d match {
        case data: PersistentChannelData =>
          val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
          replyTo ! RES_SUCCESS(c, data.channelId)
          val failure = ForcedLocalCommit(data.channelId)
          handleLocalError(failure, data, Some(c))
        case _: TransientChannelData =>
          handleCommandError(CommandUnavailableInThisState(d.channelId, "forceclose", stateName), c)
      }

    // In states where we don't explicitly handle this command, we won't broadcast a new channel update immediately,
    // but we will once we get back to NORMAL, because the updated fees have been saved to our peers DB.
    case Event(c: CMD_UPDATE_RELAY_FEE, d) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
      replyTo ! RES_SUCCESS(c, d.channelId)
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
    case Event(CurrentFeerates(_), _) => stay()

    // we only care about this event in NORMAL state
    case Event(_: BroadcastChannelUpdate, _) => stay()

    // we receive this when we tell the peer to disconnect
    case Event("disconnecting", _) => stay()

    // funding tx was confirmed in time, let's just ignore this
    case Event(BITCOIN_FUNDING_TIMEOUT, _: PersistentChannelData) => stay()

    // peer doesn't cancel the timer
    case Event(TickChannelOpenTimeout, _) => stay()

    case Event(w: WatchPublishedTriggered, d: PersistentChannelData) =>
      val fundingStatus = LocalFundingStatus.ZeroconfPublishedFundingTx(w.tx)
      d.commitments.updateLocalFundingStatus(w.tx.txid, fundingStatus) match {
        case Right((commitments1, _)) =>
          log.info(s"zero-conf funding txid=${w.tx.txid} has been published")
          watchFundingConfirmed(w.tx.txid, Some(nodeParams.channelConf.minDepthBlocks))
          val d1 = d match {
            // NB: we discard remote's stashed channel_ready, they will send it back at reconnection
            case d: DATA_WAIT_FOR_FUNDING_CONFIRMED =>
              val realScidStatus = RealScidStatus.Unknown
              val shortIds = createShortIds(d.channelId, realScidStatus)
              DATA_WAIT_FOR_CHANNEL_READY(commitments1, shortIds)
            case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED =>
              val realScidStatus = RealScidStatus.Unknown
              val shortIds = createShortIds(d.channelId, realScidStatus)
              DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, shortIds)
            case d: DATA_WAIT_FOR_CHANNEL_READY => d.copy(commitments = commitments1)
            case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => d.copy(commitments = commitments1)
            case d: DATA_NORMAL => d.copy(commitments = commitments1)
            case d: DATA_SHUTDOWN => d.copy(commitments = commitments1)
            case d: DATA_NEGOTIATING => d.copy(commitments = commitments1)
            case d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => d.copy(commitments = commitments1)
            case d: DATA_CLOSING => d.copy(commitments = commitments1)
          }
          stay() using d1 storing()
        case Left(_) => stay()
      }

    case Event(w: WatchFundingConfirmedTriggered, d: PersistentChannelData) =>
      acceptFundingTxConfirmed(w, d) match {
        case Right((commitments1, commitment)) =>
          log.info(s"funding txid=${w.tx.txid} has been confirmed")
          val d1 = d match {
            // NB: we discard remote's stashed channel_ready, they will send it back at reconnection
            case d: DATA_WAIT_FOR_FUNDING_CONFIRMED =>
              val realScidStatus = RealScidStatus.Temporary(RealShortChannelId(w.blockHeight, w.txIndex, commitment.commitInput.outPoint.index.toInt))
              val shortIds = createShortIds(d.channelId, realScidStatus)
              DATA_WAIT_FOR_CHANNEL_READY(commitments1, shortIds)
            case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED =>
              val realScidStatus = RealScidStatus.Temporary(RealShortChannelId(w.blockHeight, w.txIndex, commitment.commitInput.outPoint.index.toInt))
              val shortIds = createShortIds(d.channelId, realScidStatus)
              DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, shortIds)
            case d: DATA_WAIT_FOR_CHANNEL_READY => d.copy(commitments = commitments1)
            case d: DATA_WAIT_FOR_DUAL_FUNDING_READY => d.copy(commitments = commitments1)
            case d: DATA_NORMAL => d.copy(commitments = commitments1)
            case d: DATA_SHUTDOWN => d.copy(commitments = commitments1)
            case d: DATA_NEGOTIATING => d.copy(commitments = commitments1)
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

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => handleRemoteSpentFuture(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: PersistentChannelData) =>
      if (tx.txid == d.commitments.latest.remoteCommit.txid) {
        handleRemoteSpentCurrent(tx, d)
      } else if (d.commitments.latest.nextRemoteCommit_opt.exists(_.commit.txid == tx.txid)) {
        handleRemoteSpentNext(tx, d)
      } else if (tx.txid == d.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txid) {
        log.warning(s"processing local commit spent from the outside")
        spendLocalCurrent(d)
      } else {
        handleRemoteSpentOther(tx, d)
      }
  }

  onTransition {
    case WAIT_FOR_INIT_INTERNAL -> WAIT_FOR_INIT_INTERNAL => () // called at channel initialization
    case state -> nextState =>
      if (state != nextState) {
        val commitments_opt = nextStateData match {
          case d: PersistentChannelData => Some(d.commitments)
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
        case (NORMAL, NORMAL, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.shortIds.real.toOption != d2.shortIds.real.toOption || d1.channelUpdate != d2.channelUpdate || d1.channelAnnouncement != d2.channelAnnouncement => Some(EmitLocalChannelUpdate("normal->normal", d2, sendToPeer = d2.channelAnnouncement.isEmpty && d1.channelUpdate != d2.channelUpdate))
        case (SYNCING, NORMAL, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.channelAnnouncement != d2.channelAnnouncement => Some(EmitLocalChannelUpdate("syncing->normal", d2, sendToPeer = d2.channelAnnouncement.isEmpty))
        case (NORMAL, OFFLINE, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.channelAnnouncement != d2.channelAnnouncement => Some(EmitLocalChannelUpdate("normal->offline", d2, sendToPeer = false))
        case (OFFLINE, OFFLINE, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate != d2.channelUpdate || d1.channelAnnouncement != d2.channelAnnouncement => Some(EmitLocalChannelUpdate("offline->offline", d2, sendToPeer = false))
        case (NORMAL | SYNCING | OFFLINE, SHUTDOWN | NEGOTIATING | CLOSING | CLOSED | ERR_INFORMATION_LEAK | WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT, d: DATA_NORMAL, _) => Some(EmitLocalChannelDown(d))
        case _ => None
      }
      emitEvent_opt.foreach {
        case EmitLocalChannelUpdate(reason, d, sendToPeer) =>
          log.debug(s"emitting channel update event: reason=$reason enabled=${d.channelUpdate.channelFlags.isEnabled} sendToPeer=$sendToPeer realScid=${d.shortIds.real} channel_update={} channel_announcement={}", d.channelUpdate, d.channelAnnouncement.map(_ => "yes").getOrElse("no"))
          val lcu = LocalChannelUpdate(self, d.channelId, d.shortIds, d.commitments.params.remoteParams.nodeId, d.channelAnnouncement, d.channelUpdate, d.commitments)
          context.system.eventStream.publish(lcu)
          if (sendToPeer) {
            send(Helpers.channelUpdateForDirectPeer(nodeParams, d.channelUpdate, d.shortIds))
          }
        case EmitLocalChannelDown(d) =>
          log.debug(s"emitting channel down event")
          val lcd = LocalChannelDown(self, d.channelId, d.shortIds, d.commitments.params.remoteParams.nodeId)
          context.system.eventStream.publish(lcd)
      }

      // When we change our channel update parameters (e.g. relay fees), we want to advertise it to other actors.
      (stateData, nextStateData) match {
        // NORMAL->NORMAL, NORMAL->OFFLINE, SYNCING->NORMAL
        case (d1: DATA_NORMAL, d2: DATA_NORMAL) => maybeEmitChannelUpdateChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = Some(d1.channelUpdate), d2)
        // WAIT_FOR_FUNDING_CONFIRMED->NORMAL, WAIT_FOR_CHANNEL_READY->NORMAL
        case (_: DATA_WAIT_FOR_FUNDING_CONFIRMED, d2: DATA_NORMAL) => maybeEmitChannelUpdateChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case (_: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED, d2: DATA_NORMAL) => maybeEmitChannelUpdateChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case (_: DATA_WAIT_FOR_CHANNEL_READY, d2: DATA_NORMAL) => maybeEmitChannelUpdateChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case (_: DATA_WAIT_FOR_DUAL_FUNDING_READY, d2: DATA_NORMAL) => maybeEmitChannelUpdateChangedEvent(newUpdate = d2.channelUpdate, oldUpdate_opt = None, d2)
        case _ => ()
      }

      // Notify when the channel was aborted.
      (stateData, nextState) match {
        case (_: TransientChannelData, CLOSING | CLOSED) => context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, stateData.channelId))
        case (_: DATA_WAIT_FOR_FUNDING_CONFIRMED | _: DATA_WAIT_FOR_CHANNEL_READY, CLOSING | CLOSED) => context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, stateData.channelId))
        case (_: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED | _: DATA_WAIT_FOR_DUAL_FUNDING_READY, CLOSING | CLOSED) => context.system.eventStream.publish(ChannelAborted(self, remoteNodeId, stateData.channelId))
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
      (nextStateData: @unchecked) match {
        case d: DATA_CLOSING =>
          d.commitments.changes.localChanges.proposed.collect {
            case add: UpdateAddHtlc => relayer ! RES_ADD_SETTLED(d.commitments.originChannels(add.id), add, HtlcResult.ChannelFailureBeforeSigned)
          }
      }
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

  private def handleCurrentFeerate(c: CurrentFeerates, d: PersistentChannelData) = {
    // TODO: we should consider *all* commitments
    val commitments = d.commitments.latest
    val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, d.commitments.params.channelType, commitments.capacity, Some(c))
    val currentFeeratePerKw = commitments.localCommit.spec.commitTxFeerate
    val shouldUpdateFee = d.commitments.params.localParams.isInitiator && nodeParams.onChainFeeConf.shouldUpdateFee(currentFeeratePerKw, networkFeeratePerKw)
    val shouldClose = !d.commitments.params.localParams.isInitiator &&
      nodeParams.onChainFeeConf.feerateToleranceFor(d.commitments.remoteNodeId).isFeeDiffTooHigh(d.commitments.params.channelType, networkFeeratePerKw, currentFeeratePerKw) &&
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
  private def handleCurrentFeerateDisconnected(c: CurrentFeerates, d: PersistentChannelData) = {
    // TODO: we should consider *all* commitments
    val commitments = d.commitments.latest
    val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, d.commitments.params.channelType, commitments.capacity, Some(c))
    val currentFeeratePerKw = commitments.localCommit.spec.commitTxFeerate
    // if the network fees are too high we risk to not be able to confirm our current commitment
    val shouldClose = networkFeeratePerKw > currentFeeratePerKw &&
      nodeParams.onChainFeeConf.feerateToleranceFor(d.commitments.remoteNodeId).isFeeDiffTooHigh(d.commitments.params.channelType, networkFeeratePerKw, currentFeeratePerKw) &&
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
    }
    replyTo_opt.foreach { replyTo =>
      replyTo ! RES_SUCCESS(c, newData.channelId)
    }
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
    }
    replyTo_opt.foreach(replyTo => replyTo ! RES_FAILURE(c, cause))
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, LocalError(cause), isFatal = false))
    stay()
  }

  private def handleRevocationTimeout(revocationTimeout: RevocationTimeout, d: PersistentChannelData) = {
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
      log.info("updating channel_update announcement (reason=disabled)")
      val channelUpdate1 = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate(d), d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = !d.commitments.announceChannel, enable = false)
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
    val channelUpdate1 = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, scidForChannelUpdate(d), c.cltvExpiryDelta_opt.getOrElse(d.channelUpdate.cltvExpiryDelta), d.channelUpdate.htlcMinimumMsat, c.feeBase, c.feeProportionalMillionths, d.commitments.params.maxHtlcAmount, isPrivate = !d.commitments.announceChannel, enable = false)
    log.info(s"updating relay fees: prev={} next={}", d.channelUpdate.toStringShort, channelUpdate1.toStringShort)
    val replyTo = if (c.replyTo == ActorRef.noSender) sender() else c.replyTo
    replyTo ! RES_SUCCESS(c, d.channelId)
    // We're in OFFLINE state, by using stay() instead of goto() we skip the transition handler and won't broadcast the
    // new update right away. The goal is to not emit superfluous updates when the channel is unusable. At reconnection
    // there will be a state transition SYNCING->NORMAL which will cause the update to be broadcast.
    // However, we still need to advertise that the channel_update parameters have changed, so we manually call the method
    maybeEmitChannelUpdateChangedEvent(newUpdate = channelUpdate1, oldUpdate_opt = Some(d.channelUpdate), d)
    stay() using d.copy(channelUpdate = channelUpdate1) storing()
  }

  private def handleSyncFailure(channelReestablish: ChannelReestablish, syncFailure: SyncResult.Failure, d: PersistentChannelData) = {
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
        log.error(s"counterparty is lying about us having an outdated commitment!!! ourLocalCommitmentNumber=${res.ourLocalCommitmentNumber} theirRemoteCommitmentNumber=${res.theirRemoteCommitmentNumber}")
        // they are deliberately trying to fool us into thinking we have a late commitment
        handleLocalError(InvalidRevokedCommitProof(d.channelId, res.ourLocalCommitmentNumber, res.theirRemoteCommitmentNumber, res.invalidPerCommitmentSecret), d, Some(channelReestablish))
      case SyncResult.RemoteLate =>
        log.error("counterparty appears to be using an outdated commitment, they may request a force-close, standing by...")
        stay()
    }
  }

  private def maybeEmitChannelUpdateChangedEvent(newUpdate: ChannelUpdate, oldUpdate_opt: Option[ChannelUpdate], d: DATA_NORMAL): Unit = {
    if (oldUpdate_opt.isEmpty || !Announcements.areSameIgnoreFlags(newUpdate, oldUpdate_opt.get)) {
      context.system.eventStream.publish(ChannelUpdateParametersChanged(self, d.channelId, d.commitments.remoteNodeId, newUpdate))
    }
  }

  private def handleNewBlock(c: CurrentBlockHeight, d: PersistentChannelData) = {
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
