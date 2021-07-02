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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, TypedActorRefOps, actorRefAdapter}
import akka.actor.{Actor, ActorContext, ActorRef, FSM, OneForOneStrategy, PossiblyHarmful, Props, Status, SupervisorStrategy, typed}
import akka.event.Logging.MDC
import akka.pattern.pipe
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, SatoshiLong, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.Helpers.{Closing, Funding}
import fr.acinq.eclair.channel.Monitoring.Metrics.ProcessMessage
import fr.acinq.eclair.channel.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishRawTx, PublishReplaceableTx, PublishTx, SetChannelId}
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent.EventType
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.PaymentSettlingOnChain
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, TxOwner}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import java.sql.SQLException
import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

/**
 * Created by PM on 20/08/2015.
 */

object Channel {

  trait TxPublisherFactory {
    def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): typed.ActorRef[TxPublisher.Command]
  }

  case class SimpleTxPublisherFactory(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], bitcoinClient: ExtendedBitcoinClient) extends TxPublisherFactory {
    override def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): typed.ActorRef[TxPublisher.Command] = {
      context.spawn(Behaviors.supervise(TxPublisher(nodeParams, remoteNodeId, TxPublisher.SimpleChildFactory(nodeParams, bitcoinClient, watcher))).onFailure(typed.SupervisorStrategy.restart), "tx-publisher")
    }
  }

  def props(nodeParams: NodeParams, wallet: EclairWallet, remoteNodeId: PublicKey, blockchain: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, txPublisherFactory: TxPublisherFactory, origin_opt: Option[ActorRef]): Props =
    Props(new Channel(nodeParams, wallet, remoteNodeId, blockchain, relayer, txPublisherFactory, origin_opt))

  // see https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
  val ANNOUNCEMENTS_MINCONF = 6

  // https://github.com/lightningnetwork/lightning-rfc/blob/master/02-peer-protocol.md#requirements
  val MAX_FUNDING: Satoshi = 16777216 sat // = 2^24
  val MAX_ACCEPTED_HTLCS = 483

  // we don't want the counterparty to use a dust limit lower than that, because they wouldn't only hurt themselves we may need them to publish their commit tx in certain cases (backup/restore)
  val MIN_DUSTLIMIT: Satoshi = 546 sat

  // we won't exchange more than this many signatures when negotiating the closing fee
  val MAX_NEGOTIATION_ITERATIONS = 20

  // this is defined in BOLT 11
  val MIN_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(18)
  val MAX_CLTV_EXPIRY_DELTA: CltvExpiryDelta = CltvExpiryDelta(7 * 144) // one week

  // since BOLT 1.1, there is a max value for the refund delay of the main commitment tx
  val MAX_TO_SELF_DELAY: CltvExpiryDelta = CltvExpiryDelta(2016)

  // as a fundee, we will wait that many blocks for the funding tx to confirm (funder will rely on the funding tx being double-spent)
  val FUNDING_TIMEOUT_FUNDEE = 2016

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
  // @formatter:on

  case object TickChannelOpenTimeout

  // we will receive this message when we waited too long for a revocation for that commit number (NB: we explicitly specify the peer to allow for testing)
  case class RevocationTimeout(remoteCommitNumber: Long, peer: ActorRef)

  /**
   * Outgoing messages go through the [[Peer]] for logging purposes.
   *
   * [[Channel]] is notified asynchronously of disconnections and reconnections. To preserve sequentiality of messages,
   * we need to also provide the connection that the message is valid for. If the actual connection was reset in the
   * meantime, the [[Peer]] will simply drop the message.
   */
  case class OutgoingMessage(msg: LightningMessage, peerConnection: ActorRef)

  /** We don't immediately process [[CurrentBlockCount]] to avoid herd effects */
  case class ProcessCurrentBlockCount(c: CurrentBlockCount)

}

class Channel(val nodeParams: NodeParams, val wallet: EclairWallet, remoteNodeId: PublicKey, blockchain: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, txPublisherFactory: Channel.TxPublisherFactory, origin_opt: Option[ActorRef] = None)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends FSM[State, Data] with FSMDiagnosticActorLogging[State, Data] {

  import Channel._

  private val keyManager: ChannelKeyManager = nodeParams.channelKeyManager

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: akka.event.DiagnosticLoggingAdapter = diagLog

  // we assume that the peer is the channel's parent
  private val peer = context.parent
  // noinspection ActorMutableStateInspection
  // the last active connection we are aware of; note that the peer manages connections and asynchronously notifies
  // the channel, which means that if we get disconnected, the previous active connection will die and some messages will
  // be sent to dead letters, before the channel gets notified of the disconnection; knowing that this will happen, we
  // choose to not make this an Option (that would be None before the first connection), and instead embrace the fact
  // that the active connection may point to dead letters at all time
  private var activeConnection = context.system.deadLetters

  private val txPublisher = txPublisherFactory.spawnTxPublisher(context, remoteNodeId)

  // this will be used to detect htlc timeouts
  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])
  // the constant delay by which we delay processing of blocks (it will be smoothened among all channels)
  private val blockProcessingDelay = Random.nextLong(nodeParams.maxBlockProcessingDelay.toMillis + 1).millis
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

  /*
                                                NEW
                              FUNDER                            FUNDEE
                                 |                                |
                                 |          open_channel          |WAIT_FOR_OPEN_CHANNEL
                                 |------------------------------->|
          WAIT_FOR_ACCEPT_CHANNEL|                                |
                                 |         accept_channel         |
                                 |<-------------------------------|
                                 |                                |WAIT_FOR_FUNDING_CREATED
                                 |        funding_created         |
                                 |------------------------------->|
          WAIT_FOR_FUNDING_SIGNED|                                |
                                 |         funding_signed         |
                                 |<-------------------------------|
          WAIT_FOR_FUNDING_LOCKED|                                |WAIT_FOR_FUNDING_LOCKED
                                 | funding_locked  funding_locked |
                                 |---------------  ---------------|
                                 |               \/               |
                                 |               /\               |
                                 |<--------------  -------------->|
                           NORMAL|                                |NORMAL
   */

  startWith(WAIT_FOR_INIT_INTERNAL, Nothing)

  when(WAIT_FOR_INIT_INTERNAL)(handleExceptions {
    case Event(initFunder@INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, _, localParams, remote, _, channelFlags, channelVersion), Nothing) =>
      context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isFunder = true, temporaryChannelId, initialFeeratePerKw, Some(fundingTxFeeratePerKw)))
      activeConnection = remote
      txPublisher ! SetChannelId(remoteNodeId, temporaryChannelId)
      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
      val channelKeyPath = keyManager.keyPath(localParams, channelVersion)
      val open = OpenChannel(nodeParams.chainHash,
        temporaryChannelId = temporaryChannelId,
        fundingSatoshis = fundingSatoshis,
        pushMsat = pushMsat,
        dustLimitSatoshis = localParams.dustLimit,
        maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
        channelReserveSatoshis = localParams.channelReserve,
        htlcMinimumMsat = localParams.htlcMinimum,
        feeratePerKw = initialFeeratePerKw,
        toSelfDelay = localParams.toSelfDelay,
        maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
        fundingPubkey = fundingPubKey,
        revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
        paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
        delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
        htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
        firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
        channelFlags = channelFlags,
        // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script.
        // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
        tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)))
      goto(WAIT_FOR_ACCEPT_CHANNEL) using DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder, open) sending open

    case Event(inputFundee@INPUT_INIT_FUNDEE(_, localParams, remote, _, _), Nothing) if !localParams.isFunder =>
      activeConnection = remote
      txPublisher ! SetChannelId(remoteNodeId, inputFundee.temporaryChannelId)
      goto(WAIT_FOR_OPEN_CHANNEL) using DATA_WAIT_FOR_OPEN_CHANNEL(inputFundee)

    case Event(INPUT_RESTORED(data), _) =>
      log.info("restoring channel")
      context.system.eventStream.publish(ChannelRestored(self, data.channelId, peer, remoteNodeId, data.commitments.localParams.isFunder, data.commitments))
      txPublisher ! SetChannelId(remoteNodeId, data.channelId)
      data match {
        // NB: order matters!
        case closing: DATA_CLOSING if Closing.nothingAtStake(closing) =>
          log.info("we have nothing at stake, going straight to CLOSED")
          goto(CLOSED) using closing
        case closing: DATA_CLOSING =>
          // we don't put back the WatchSpent if the commitment tx has already been published and the spending tx already reached mindepth
          val closingType_opt = Closing.isClosingTypeAlreadyKnown(closing)
          log.info(s"channel is closing (closingType=${closingType_opt.map(c => EventType.Closed(c).label).getOrElse("UnknownYet")})")
          // if the closing type is known:
          // - there is no need to watch the funding tx because it has already been spent and the spending tx has already reached mindepth
          // - there is no need to attempt to publish transactions for other type of closes
          closingType_opt match {
            case Some(c: Closing.MutualClose) =>
              doPublish(c.tx)
            case Some(c: Closing.LocalClose) =>
              doPublish(c.localCommitPublished, closing.commitments)
            case Some(c: Closing.RemoteClose) =>
              doPublish(c.remoteCommitPublished)
            case Some(c: Closing.RecoveryClose) =>
              doPublish(c.remoteCommitPublished)
            case Some(c: Closing.RevokedClose) =>
              doPublish(c.revokedCommitPublished)
            case None =>
              // in all other cases we need to be ready for any type of closing
              watchFundingTx(data.commitments, closing.spendingTxs.map(_.txid).toSet)
              closing.mutualClosePublished.foreach(doPublish)
              closing.localCommitPublished.foreach(lcp => doPublish(lcp, closing.commitments))
              closing.remoteCommitPublished.foreach(doPublish)
              closing.nextRemoteCommitPublished.foreach(doPublish)
              closing.revokedCommitPublished.foreach(doPublish)
              closing.futureRemoteCommitPublished.foreach(doPublish)

              // if commitment number is zero, we also need to make sure that the funding tx has been published
              if (closing.commitments.localCommit.index == 0 && closing.commitments.remoteCommit.index == 0) {
                blockchain ! GetTxWithMeta(self, closing.commitments.commitInput.outPoint.txid)
              }
          }
          // no need to go OFFLINE, we can directly switch to CLOSING
          if (closing.waitingSinceBlock > 1500000) {
            // we were using timestamps instead of block heights when the channel was created: we reset it *and* we use block heights
            goto(CLOSING) using closing.copy(waitingSinceBlock = nodeParams.currentBlockHeight) storing()
          } else {
            goto(CLOSING) using closing
          }

        case normal: DATA_NORMAL =>
          watchFundingTx(data.commitments)
          context.system.eventStream.publish(ShortChannelIdAssigned(self, normal.channelId, normal.channelUpdate.shortChannelId, None))

          // we rebuild a new channel_update with values from the configuration because they may have changed while eclair was down
          // NB: we don't update the routing fees, because we don't want to overwrite manual changes made with CMD_UPDATE_RELAY_FEE
          // Since CMD_UPDATE_RELAY_FEE is handled even when being offline, that's the preferred solution to update routing fees
          val candidateChannelUpdate = Announcements.makeChannelUpdate(
            nodeParams.chainHash,
            nodeParams.privateKey,
            remoteNodeId,
            normal.channelUpdate.shortChannelId,
            nodeParams.expiryDelta,
            normal.commitments.remoteParams.htlcMinimum,
            normal.channelUpdate.feeBaseMsat,
            normal.channelUpdate.feeProportionalMillionths,
            normal.commitments.capacity.toMilliSatoshi,
            enable = Announcements.isEnabled(normal.channelUpdate.channelFlags))
          val channelUpdate1 = if (Announcements.areSame(candidateChannelUpdate, normal.channelUpdate)) {
            // if there was no configuration change we keep the existing channel update
            normal.channelUpdate
          } else {
            log.info("refreshing channel_update due to configuration changes old={} new={}", normal.channelUpdate, candidateChannelUpdate)
            candidateChannelUpdate
          }
          // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
          // we take into account the date of the last update so that we don't send superfluous updates when we restart the app
          val periodicRefreshInitialDelay = Helpers.nextChannelUpdateRefresh(channelUpdate1.timestamp)
          context.system.scheduler.schedule(initialDelay = periodicRefreshInitialDelay, interval = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))

          goto(OFFLINE) using normal.copy(channelUpdate = channelUpdate1)

        case funding: DATA_WAIT_FOR_FUNDING_CONFIRMED =>
          watchFundingTx(funding.commitments)
          // we make sure that the funding tx has been published
          blockchain ! GetTxWithMeta(self, funding.commitments.commitInput.outPoint.txid)
          if (funding.waitingSinceBlock > 1500000) {
            // we were using timestamps instead of block heights when the channel was created: we reset it *and* we use block heights
            goto(OFFLINE) using funding.copy(waitingSinceBlock = nodeParams.currentBlockHeight) storing()
          } else {
            goto(OFFLINE) using funding
          }

        case _ =>
          watchFundingTx(data.commitments)
          goto(OFFLINE) using data
      }

    case Event(c: CloseCommand, d) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_OPEN_CHANNEL)(handleExceptions {
    case Event(open: OpenChannel, d@DATA_WAIT_FOR_OPEN_CHANNEL(INPUT_INIT_FUNDEE(_, localParams, _, remoteInit, channelVersion))) =>
      log.info("received OpenChannel={}", open)
      Helpers.validateParamsFundee(nodeParams, localParams.features, channelVersion, open, remoteNodeId) match {
        case Left(t) => handleLocalError(t, d, Some(open))
        case _ =>
          context.system.eventStream.publish(ChannelCreated(self, peer, remoteNodeId, isFunder = false, open.temporaryChannelId, open.feeratePerKw, None))
          val fundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey
          val channelKeyPath = keyManager.keyPath(localParams, channelVersion)
          val minimumDepth = Helpers.minDepthForFunding(nodeParams, open.fundingSatoshis)
          val accept = AcceptChannel(temporaryChannelId = open.temporaryChannelId,
            dustLimitSatoshis = localParams.dustLimit,
            maxHtlcValueInFlightMsat = localParams.maxHtlcValueInFlightMsat,
            channelReserveSatoshis = localParams.channelReserve,
            minimumDepth = minimumDepth,
            htlcMinimumMsat = localParams.htlcMinimum,
            toSelfDelay = localParams.toSelfDelay,
            maxAcceptedHtlcs = localParams.maxAcceptedHtlcs,
            fundingPubkey = fundingPubkey,
            revocationBasepoint = keyManager.revocationPoint(channelKeyPath).publicKey,
            paymentBasepoint = localParams.walletStaticPaymentBasepoint.getOrElse(keyManager.paymentPoint(channelKeyPath).publicKey),
            delayedPaymentBasepoint = keyManager.delayedPaymentPoint(channelKeyPath).publicKey,
            htlcBasepoint = keyManager.htlcPoint(channelKeyPath).publicKey,
            firstPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 0),
            // In order to allow TLV extensions and keep backwards-compatibility, we include an empty upfront_shutdown_script.
            // See https://github.com/lightningnetwork/lightning-rfc/pull/714.
            tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)))
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = open.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = open.maxHtlcValueInFlightMsat,
            channelReserve = open.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = open.htlcMinimumMsat,
            toSelfDelay = open.toSelfDelay,
            maxAcceptedHtlcs = open.maxAcceptedHtlcs,
            fundingPubKey = open.fundingPubkey,
            revocationBasepoint = open.revocationBasepoint,
            paymentBasepoint = open.paymentBasepoint,
            delayedPaymentBasepoint = open.delayedPaymentBasepoint,
            htlcBasepoint = open.htlcBasepoint,
            features = remoteInit.features)
          log.debug("remote params: {}", remoteParams)
          goto(WAIT_FOR_FUNDING_CREATED) using DATA_WAIT_FOR_FUNDING_CREATED(open.temporaryChannelId, localParams, remoteParams, open.fundingSatoshis, open.pushMsat, open.feeratePerKw, None, open.firstPerCommitmentPoint, open.channelFlags, channelVersion, accept) sending accept
      }

    case Event(c: CloseCommand, d) => handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_OPEN_CHANNEL) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_ACCEPT_CHANNEL)(handleExceptions {
    case Event(accept: AcceptChannel, d@DATA_WAIT_FOR_ACCEPT_CHANNEL(INPUT_INIT_FUNDER(temporaryChannelId, fundingSatoshis, pushMsat, initialFeeratePerKw, fundingTxFeeratePerKw, initialRelayFees_opt, localParams, _, remoteInit, _, channelVersion), open)) =>
      log.info(s"received AcceptChannel=$accept")
      Helpers.validateParamsFunder(nodeParams, open, accept) match {
        case Left(t) => handleLocalError(t, d, Some(accept))
        case _ =>
          val remoteParams = RemoteParams(
            nodeId = remoteNodeId,
            dustLimit = accept.dustLimitSatoshis,
            maxHtlcValueInFlightMsat = accept.maxHtlcValueInFlightMsat,
            channelReserve = accept.channelReserveSatoshis, // remote requires local to keep this much satoshis as direct payment
            htlcMinimum = accept.htlcMinimumMsat,
            toSelfDelay = accept.toSelfDelay,
            maxAcceptedHtlcs = accept.maxAcceptedHtlcs,
            fundingPubKey = accept.fundingPubkey,
            revocationBasepoint = accept.revocationBasepoint,
            paymentBasepoint = accept.paymentBasepoint,
            delayedPaymentBasepoint = accept.delayedPaymentBasepoint,
            htlcBasepoint = accept.htlcBasepoint,
            features = remoteInit.features)
          log.debug("remote params: {}", remoteParams)
          val localFundingPubkey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingPubkey.publicKey, remoteParams.fundingPubKey)))
          wallet.makeFundingTx(fundingPubkeyScript, fundingSatoshis, fundingTxFeeratePerKw).pipeTo(self)
          goto(WAIT_FOR_FUNDING_INTERNAL) using DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingSatoshis, pushMsat, initialFeeratePerKw, initialRelayFees_opt, accept.firstPerCommitmentPoint, channelVersion, open)
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.lastSent.temporaryChannelId)))
      handleFastClose(c, d.lastSent.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_ACCEPT_CHANNEL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_INTERNAL)(handleExceptions {
    case Event(MakeFundingTxResponse(fundingTx, fundingTxOutputIndex, fundingTxFee), d@DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, initialRelayFees_opt, remoteFirstPerCommitmentPoint, channelVersion, open)) =>
      // let's create the first commitment tx that spends the yet uncommitted funding tx
      Funding.makeFirstCommitTxs(keyManager, channelVersion, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, fundingTx.hash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, d, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          require(fundingTx.txOut(fundingTxOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, s"pubkey script mismatch!")
          val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath), TxOwner.Remote, channelVersion.commitmentFormat)
          // signature of their initial commitment tx that pays remote pushMsat
          val fundingCreated = FundingCreated(
            temporaryChannelId = temporaryChannelId,
            fundingTxid = fundingTx.hash,
            fundingOutputIndex = fundingTxOutputIndex,
            signature = localSigOfRemoteTx
          )
          val channelId = toLongId(fundingTx.hash, fundingTxOutputIndex)
          peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
          txPublisher ! SetChannelId(remoteNodeId, channelId)
          context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
          // NB: we don't send a ChannelSignatureSent for the first commit
          goto(WAIT_FOR_FUNDING_SIGNED) using DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, fundingTxFee, initialRelayFees_opt, localSpec, localCommitTx, RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint), open.channelFlags, channelVersion, fundingCreated) sending fundingCreated
      }

    case Event(Status.Failure(t), d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      log.error(t, s"wallet returned error: ")
      channelOpenReplyToUser(Left(LocalError(t)))
      handleLocalError(ChannelFundingError(d.temporaryChannelId), d, None) // we use a generic exception and don't send the internal error to the peer

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_INTERNAL) =>
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, _) =>
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CREATED)(handleExceptions {
    case Event(FundingCreated(_, fundingTxHash, fundingTxOutputIndex, remoteSig), d@DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, initialRelayFees_opt, remoteFirstPerCommitmentPoint, channelFlags, channelVersion, _)) =>
      // they fund the channel with their funding tx, so the money is theirs (but we are paid pushMsat)
      Funding.makeFirstCommitTxs(keyManager, channelVersion, temporaryChannelId, localParams, remoteParams, fundingAmount, pushMsat, initialFeeratePerKw, fundingTxHash, fundingTxOutputIndex, remoteFirstPerCommitmentPoint) match {
        case Left(ex) => handleLocalError(ex, d, None)
        case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
          // check remote signature validity
          val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
          val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelVersion.commitmentFormat)
          val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
          Transactions.checkSpendable(signedLocalCommitTx) match {
            case Failure(_) => handleLocalError(InvalidCommitmentSignature(temporaryChannelId, signedLocalCommitTx.tx), d, None)
            case Success(_) =>
              val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, fundingPubKey, TxOwner.Remote, channelVersion.commitmentFormat)
              val channelId = toLongId(fundingTxHash, fundingTxOutputIndex)
              // watch the funding tx transaction
              val commitInput = localCommitTx.input
              val fundingSigned = FundingSigned(
                channelId = channelId,
                signature = localSigOfRemoteTx
              )
              val commitments = Commitments(channelVersion, localParams, remoteParams, channelFlags,
                LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
                localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                originChannels = Map.empty,
                remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
                commitInput, ShaChain.init, channelId = channelId)
              peer ! ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId) // we notify the peer asap so it knows how to route messages
              txPublisher ! SetChannelId(remoteNodeId, channelId)
              context.system.eventStream.publish(ChannelIdAssigned(self, remoteNodeId, temporaryChannelId, channelId))
              context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
              // NB: we don't send a ChannelSignatureSent for the first commit
              log.info(s"waiting for them to publish the funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
              val fundingMinDepth = Helpers.minDepthForFunding(nodeParams, fundingAmount)
              watchFundingTx(commitments)
              blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, fundingMinDepth)
              goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, None, initialRelayFees_opt, nodeParams.currentBlockHeight, None, Right(fundingSigned)) storing() sending fundingSigned
          }
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_CREATED) =>
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.temporaryChannelId)))
      handleFastClose(c, d.temporaryChannelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CREATED) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, _) => goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_SIGNED)(handleExceptions {
    case Event(msg@FundingSigned(_, remoteSig), d@DATA_WAIT_FOR_FUNDING_SIGNED(channelId, localParams, remoteParams, fundingTx, fundingTxFee, initialRelayFees_opt, localSpec, localCommitTx, remoteCommit, channelFlags, channelVersion, fundingCreated)) =>
      // we make sure that their sig checks out and that our first commit tx is spendable
      val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
      val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelVersion.commitmentFormat)
      val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteSig)
      Transactions.checkSpendable(signedLocalCommitTx) match {
        case Failure(cause) =>
          // we rollback the funding tx, it will never be published
          wallet.rollback(fundingTx)
          channelOpenReplyToUser(Left(LocalError(cause)))
          handleLocalError(InvalidCommitmentSignature(channelId, signedLocalCommitTx.tx), d, Some(msg))
        case Success(_) =>
          val commitInput = localCommitTx.input
          val commitments = Commitments(channelVersion, localParams, remoteParams, channelFlags,
            LocalCommit(0, localSpec, PublishableTxs(signedLocalCommitTx, Nil)), remoteCommit,
            LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
            localNextHtlcId = 0L, remoteNextHtlcId = 0L,
            originChannels = Map.empty,
            remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array
            commitInput, ShaChain.init, channelId = channelId)
          val now = System.currentTimeMillis.milliseconds.toSeconds
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments))
          log.info(s"publishing funding tx for channelId=$channelId fundingTxid=${commitInput.outPoint.txid}")
          watchFundingTx(commitments)
          blockchain ! WatchFundingConfirmed(self, commitInput.outPoint.txid, nodeParams.minDepthBlocks)
          log.info(s"committing txid=${fundingTx.txid}")

          // we will publish the funding tx only after the channel state has been written to disk because we want to
          // make sure we first persist the commitment that returns back the funds to us in case of problem
          def publishFundingTx(): Unit = {
            wallet.commit(fundingTx).onComplete {
              case Success(true) =>
                // NB: funding tx isn't confirmed at this point, so technically we didn't really pay the network fee yet, so this is a (fair) approximation
                feePaid(fundingTxFee, fundingTx, "funding", commitments.channelId)
                channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelOpened(channelId)))
              case Success(false) =>
                channelOpenReplyToUser(Left(LocalError(new RuntimeException("couldn't publish funding tx"))))
                self ! BITCOIN_FUNDING_PUBLISH_FAILED // fail-fast: this should be returned only when we are really sure the tx has *not* been published
              case Failure(t) =>
                channelOpenReplyToUser(Left(LocalError(t)))
                log.error(t, s"error while committing funding tx: ") // tx may still have been published, can't fail-fast
            }
          }

          goto(WAIT_FOR_FUNDING_CONFIRMED) using DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, Some(fundingTx), initialRelayFees_opt, now, None, Left(fundingCreated)) storing() calling publishFundingTx()
      }

    case Event(c: CloseCommand, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Right(ChannelOpenResponse.ChannelClosed(d.channelId)))
      handleFastClose(c, d.channelId)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(RemoteError(e)))
      handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("disconnected"))))
      goto(CLOSED)

    case Event(TickChannelOpenTimeout, d: DATA_WAIT_FOR_FUNDING_SIGNED) =>
      // we rollback the funding tx, it will never be published
      wallet.rollback(d.fundingTx)
      channelOpenReplyToUser(Left(LocalError(new RuntimeException("open channel cancelled, took too long"))))
      goto(CLOSED)
  })

  when(WAIT_FOR_FUNDING_CONFIRMED)(handleExceptions {
    case Event(msg: FundingLocked, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      log.info(s"received their FundingLocked, deferring message")
      stay using d.copy(deferred = Some(msg)) // no need to store, they will re-send if we get disconnected

    case Event(WatchFundingConfirmedTriggered(blockHeight, txIndex, fundingTx), d@DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments, _, initialRelayFees_opt, _, deferred, _)) =>
      Try(Transaction.correctlySpends(commitments.localCommit.publishableTxs.commitTx.tx, Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
        case Success(_) =>
          log.info(s"channelId=${commitments.channelId} was confirmed at blockHeight=$blockHeight txIndex=$txIndex")
          blockchain ! WatchFundingLost(self, commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks)
          val channelKeyPath = keyManager.keyPath(d.commitments.localParams, commitments.channelVersion)
          val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
          val fundingLocked = FundingLocked(commitments.channelId, nextPerCommitmentPoint)
          deferred.foreach(self ! _)
          // this is the temporary channel id that we will use in our channel_update message, the goal is to be able to use our channel
          // as soon as it reaches NORMAL state, and before it is announced on the network
          // (this id might be updated when the funding tx gets deeply buried, if there was a reorg in the meantime)
          val shortChannelId = ShortChannelId(blockHeight, txIndex, commitments.commitInput.outPoint.index.toInt)
          goto(WAIT_FOR_FUNDING_LOCKED) using DATA_WAIT_FOR_FUNDING_LOCKED(commitments, shortChannelId, fundingLocked, initialRelayFees_opt) storing() sending fundingLocked
        case Failure(t) =>
          log.error(t, s"rejecting channel with invalid funding tx: ${fundingTx.bin}")
          goto(CLOSED)
      }

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.commitInput.outPoint.txid => handleGetFundingTx(getTxResponse, d.waitingSinceBlock, d.fundingTx)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(ProcessCurrentBlockCount(c), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => d.fundingTx match {
      case Some(_) => stay // we are funder, we're still waiting for the funding tx to be confirmed
      case None if c.blockCount - d.waitingSinceBlock > FUNDING_TIMEOUT_FUNDEE =>
        log.warning(s"funding tx hasn't been published in ${c.blockCount - d.waitingSinceBlock} blocks")
        self ! BITCOIN_FUNDING_TIMEOUT
        stay
      case None => stay // let's wait longer
    }

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_FUNDING_LOCKED)(handleExceptions {
    case Event(FundingLocked(_, nextPerCommitmentPoint), d@DATA_WAIT_FOR_FUNDING_LOCKED(commitments, shortChannelId, _, initialRelayFees_opt)) =>
      // used to get the final shortChannelId, used in announcements (if minDepth >= ANNOUNCEMENTS_MINCONF this event will fire instantly)
      blockchain ! WatchFundingDeeplyBuried(self, commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF)
      context.system.eventStream.publish(ShortChannelIdAssigned(self, commitments.channelId, shortChannelId, None))
      // we create a channel_update early so that we can use it to send payments through this channel, but it won't be propagated to other nodes since the channel is not yet announced
      val (feeBase, feeProportionalMillionths) = initialRelayFees_opt.getOrElse((nodeParams.feeBase, nodeParams.feeProportionalMillionth))
      val initialChannelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, nodeParams.expiryDelta, d.commitments.remoteParams.htlcMinimum, feeBase, feeProportionalMillionths, commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      // we need to periodically re-send channel updates, otherwise channel will be considered stale and get pruned by network
      context.system.scheduler.schedule(initialDelay = REFRESH_CHANNEL_UPDATE_INTERVAL, interval = REFRESH_CHANNEL_UPDATE_INTERVAL, receiver = self, message = BroadcastChannelUpdate(PeriodicRefresh))
      goto(NORMAL) using DATA_NORMAL(commitments.copy(remoteNextCommitInfo = Right(nextPerCommitmentPoint)), shortChannelId, buried = false, None, initialChannelUpdate, None, None, None) storing()

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_WAIT_FOR_FUNDING_LOCKED) if d.commitments.announceChannel =>
      log.debug("received remote announcement signatures, delaying")
      // we may receive their announcement sigs before our watcher notifies us that the channel has reached min_conf (especially during testing when blocks are generated in bulk)
      // note: no need to persist their message, in case of disconnection they will resend it
      context.system.scheduler.scheduleOnce(2 seconds, self, remoteAnnSigs)
      stay

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleInformationLeak(tx, d)

    case Event(e: Error, d: DATA_WAIT_FOR_FUNDING_LOCKED) => handleRemoteError(e, d)
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
      Commitments.sendAdd(d.commitments, c, nodeParams.currentBlockHeight, nodeParams.onChainFeeConf) match {
        case Right((commitments1, add)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortChannelId, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending add
        case Left(cause) => handleAddHtlcCommandError(c, cause, Some(d.channelUpdate))
      }

    case Event(add: UpdateAddHtlc, d: DATA_NORMAL) =>
      Commitments.receiveAdd(d.commitments, add, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(add))
      }

    case Event(c: CMD_FULFILL_HTLC, d: DATA_NORMAL) =>
      Commitments.sendFulfill(d.commitments, c) match {
        case Right((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortChannelId, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fulfill
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_NORMAL) =>
      Commitments.receiveFulfill(d.commitments, fulfill) match {
        case Right((commitments1, origin, htlc)) =>
          // we forward preimages as soon as possible to the upstream channel because it allows us to pull funds
          relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.RemoteFulfill(fulfill))
          stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_NORMAL) =>
      Commitments.sendFail(d.commitments, c, nodeParams.privateKey) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortChannelId, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_NORMAL) =>
      Commitments.sendFailMalformed(d.commitments, c) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortChannelId, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_NORMAL) =>
      Commitments.receiveFail(d.commitments, fail) match {
        case Right((commitments1, _, _)) => stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_NORMAL) =>
      Commitments.receiveFailMalformed(d.commitments, fail) match {
        case Right((commitments1, _, _)) => stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_NORMAL) =>
      Commitments.sendFee(d.commitments, c) match {
        case Right((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN()
          context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortChannelId, commitments1))
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fee
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_NORMAL) =>
      Commitments.receiveFee(d.commitments, fee, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c: CMD_SIGN, d: DATA_NORMAL) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay
        case Right(_) =>
          Commitments.sendCommit(d.commitments, keyManager) match {
            case Right((commitments1, commit)) =>
              log.debug("sending a new sig, spec:\n{}", Commitments.specs2String(commitments1))
              val nextRemoteCommit = commitments1.remoteNextCommitInfo.swap.toOption.get.nextRemoteCommit
              val nextCommitNumber = nextRemoteCommit.index
              // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
              // counterparty, so only htlcs above remote's dust_limit matter
              val trimmedHtlcs = Transactions.trimOfferedHtlcs(d.commitments.remoteParams.dustLimit, nextRemoteCommit.spec, d.commitments.commitmentFormat) ++
                Transactions.trimReceivedHtlcs(d.commitments.remoteParams.dustLimit, nextRemoteCommit.spec, d.commitments.commitmentFormat)
              trimmedHtlcs.map(_.add).foreach { htlc =>
                log.info(s"adding paymentHash=${htlc.paymentHash} cltvExpiry=${htlc.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber")
                nodeParams.db.channels.addHtlcInfo(d.channelId, nextCommitNumber, htlc.paymentHash, htlc.cltvExpiry)
              }
              if (!Helpers.aboveReserve(d.commitments) && Helpers.aboveReserve(commitments1)) {
                // we just went above reserve (can't go below), let's refresh our channel_update to enable/disable it accordingly
                log.info("updating channel_update aboveReserve={}", Helpers.aboveReserve(commitments1))
                self ! BroadcastChannelUpdate(AboveReserve)
              }
              context.system.eventStream.publish(ChannelSignatureSent(self, commitments1))
              // we expect a quick response from our peer
              setTimer(RevocationTimeout.toString, RevocationTimeout(commitments1.remoteCommit.index, peer), timeout = nodeParams.revocationTimeout, repeat = false)
              handleCommandSuccess(c, d.copy(commitments = commitments1)).storing().sending(commit).acking(commitments1.localChanges.signed)
            case Left(cause) => handleCommandError(cause, c)
          }
        case Left(waitForRevocation) =>
          log.debug("already in the process of signing, will sign again as soon as possible")
          val commitments1 = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true)))
          stay using d.copy(commitments = commitments1)
      }

    case Event(commit: CommitSig, d: DATA_NORMAL) =>
      Commitments.receiveCommit(d.commitments, commit, keyManager) match {
        case Right((commitments1, revocation)) =>
          log.debug("received a new sig, spec:\n{}", Commitments.specs2String(commitments1))
          if (Commitments.localHasChanges(commitments1)) {
            // if we have newly acknowledged changes let's sign them
            self ! CMD_SIGN()
          }
          if (d.commitments.availableBalanceForSend != commitments1.availableBalanceForSend) {
            // we send this event only when our balance changes
            context.system.eventStream.publish(AvailableBalanceChanged(self, d.channelId, d.shortChannelId, commitments1))
          }
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          stay using d.copy(commitments = commitments1) storing() sending revocation
        case Left(cause) => handleLocalError(cause, d, Some(commit))
      }

    case Event(revocation: RevokeAndAck, d: DATA_NORMAL) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked
      Commitments.receiveRevocation(d.commitments, revocation) match {
        case Right((commitments1, forwards)) =>
          cancelTimer(RevocationTimeout.toString)
          log.debug("received a new rev, spec:\n{}", Commitments.specs2String(commitments1))
          forwards.foreach {
            case Right(forwardAdd) =>
              log.debug("forwarding {} to relayer", forwardAdd)
              relayer ! forwardAdd
            case Left(result) =>
              log.debug("forwarding {} to relayer", result)
              relayer ! result
          }
          if (Commitments.localHasChanges(commitments1) && d.commitments.remoteNextCommitInfo.left.map(_.reSignAsap) == Left(true)) {
            self ! CMD_SIGN()
          }
          if (d.remoteShutdown.isDefined && !Commitments.localHasUnsignedOutgoingHtlcs(commitments1)) {
            // we were waiting for our pending htlcs to be signed before replying with our local shutdown
            val localShutdown = Shutdown(d.channelId, commitments1.localParams.defaultFinalScriptPubKey)
            // note: it means that we had pending htlcs to sign, therefore we go to SHUTDOWN, not to NEGOTIATING
            require(commitments1.remoteCommit.spec.htlcs.nonEmpty, "we must have just signed new htlcs, otherwise we would have sent our Shutdown earlier")
            goto(SHUTDOWN) using DATA_SHUTDOWN(commitments1, localShutdown, d.remoteShutdown.get, d.closingFeerates) storing() sending localShutdown
          } else {
            stay using d.copy(commitments = commitments1) storing()
          }
        case Left(cause) => handleLocalError(cause, d, Some(revocation))
      }

    case Event(r: RevocationTimeout, d: DATA_NORMAL) => handleRevocationTimeout(r, d)

    case Event(c: CMD_CLOSE, d: DATA_NORMAL) =>
      val localScriptPubKey = c.scriptPubKey.getOrElse(d.commitments.localParams.defaultFinalScriptPubKey)
      val allowAnySegwit = Features.canUseFeature(d.commitments.localParams.features, d.commitments.remoteParams.features, Features.ShutdownAnySegwit)
      if (d.localShutdown.isDefined) {
        handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      } else if (Commitments.localHasUnsignedOutgoingHtlcs(d.commitments)) {
        // NB: simplistic behavior, we could also sign-then-close
        handleCommandError(CannotCloseWithUnsignedOutgoingHtlcs(d.channelId), c)
      } else if (Commitments.localHasUnsignedOutgoingUpdateFee(d.commitments)) {
        handleCommandError(CannotCloseWithUnsignedOutgoingUpdateFee(d.channelId), c)
      } else if (!Closing.isValidFinalScriptPubkey(localScriptPubKey, allowAnySegwit)) {
        handleCommandError(InvalidFinalScript(d.channelId), c)
      } else {
        val shutdown = Shutdown(d.channelId, localScriptPubKey)
        handleCommandSuccess(c, d.copy(localShutdown = Some(shutdown), closingFeerates = c.feerates)) storing() sending shutdown
      }

    case Event(remoteShutdown@Shutdown(_, remoteScriptPubKey), d: DATA_NORMAL) =>
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
      val allowAnySegwit = Features.canUseFeature(d.commitments.localParams.features, d.commitments.remoteParams.features, Features.ShutdownAnySegwit)
      if (!Closing.isValidFinalScriptPubkey(remoteScriptPubKey, allowAnySegwit)) {
        handleLocalError(InvalidFinalScript(d.channelId), d, Some(remoteShutdown))
      } else if (Commitments.remoteHasUnsignedOutgoingHtlcs(d.commitments)) {
        handleLocalError(CannotCloseWithUnsignedOutgoingHtlcs(d.channelId), d, Some(remoteShutdown))
      } else if (Commitments.remoteHasUnsignedOutgoingUpdateFee(d.commitments)) {
        handleLocalError(CannotCloseWithUnsignedOutgoingUpdateFee(d.channelId), d, Some(remoteShutdown))
      } else if (Commitments.localHasUnsignedOutgoingHtlcs(d.commitments)) { // do we have unsigned outgoing htlcs?
        require(d.localShutdown.isEmpty, "can't have pending unsigned outgoing htlcs after having sent Shutdown")
        // are we in the middle of a signature?
        d.commitments.remoteNextCommitInfo match {
          case Left(waitForRevocation) =>
            // yes, let's just schedule a new signature ASAP, which will include all pending unsigned changes
            val commitments1 = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true)))
            // in the meantime we won't send new changes
            stay using d.copy(commitments = commitments1, remoteShutdown = Some(remoteShutdown))
          case Right(_) =>
            // no, let's sign right away
            self ! CMD_SIGN()
            // in the meantime we won't send new changes
            stay using d.copy(remoteShutdown = Some(remoteShutdown))
        }
      } else {
        // so we don't have any unsigned outgoing changes
        val (localShutdown, sendList) = d.localShutdown match {
          case Some(localShutdown) =>
            (localShutdown, Nil)
          case None =>
            val localShutdown = Shutdown(d.channelId, d.commitments.localParams.defaultFinalScriptPubKey)
            // we need to send our shutdown if we didn't previously
            (localShutdown, localShutdown :: Nil)
        }
        // are there pending signed changes on either side? we need to have received their last revocation!
        if (d.commitments.hasNoPendingHtlcsOrFeeUpdate) {
          // there are no pending signed changes, let's go directly to NEGOTIATING
          if (d.commitments.localParams.isFunder) {
            // we are funder, need to initiate the negotiation by sending the first closing_signed
            val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, d.commitments, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, d.closingFeerates)
            goto(NEGOTIATING) using DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending sendList :+ closingSigned
          } else {
            // we are fundee, will wait for their closing_signed
            goto(NEGOTIATING) using DATA_NEGOTIATING(d.commitments, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing() sending sendList
          }
        } else {
          // there are some pending signed changes, we need to wait for them to be settled (fail/fulfill htlcs and sign fee updates)
          goto(SHUTDOWN) using DATA_SHUTDOWN(d.commitments, localShutdown, remoteShutdown, d.closingFeerates) storing() sending sendList
        }
      }

    case Event(ProcessCurrentBlockCount(c), d: DATA_NORMAL) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: DATA_NORMAL) => handleCurrentFeerate(c, d)

    case Event(WatchFundingDeeplyBuriedTriggered(blockHeight, txIndex, _), d: DATA_NORMAL) if d.channelAnnouncement.isEmpty =>
      val shortChannelId = ShortChannelId(blockHeight, txIndex, d.commitments.commitInput.outPoint.index.toInt)
      log.info(s"funding tx is deeply buried at blockHeight=$blockHeight txIndex=$txIndex shortChannelId=$shortChannelId")
      // if final shortChannelId is different from the one we had before, we need to re-announce it
      val channelUpdate = if (shortChannelId != d.shortChannelId) {
        log.info(s"short channel id changed, probably due to a chain reorg: old=${d.shortChannelId} new=$shortChannelId")
        // we need to re-announce this shortChannelId
        context.system.eventStream.publish(ShortChannelIdAssigned(self, d.channelId, shortChannelId, Some(d.shortChannelId)))
        // we re-announce the channelUpdate for the same reason
        Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      } else d.channelUpdate
      val localAnnSigs_opt = if (d.commitments.announceChannel) {
        // if channel is public we need to send our announcement_signatures in order to generate the channel_announcement
        Some(Helpers.makeAnnouncementSignatures(nodeParams, d.commitments, shortChannelId))
      } else None
      // we use GOTO instead of stay because we want to fire transitions
      goto(NORMAL) using d.copy(shortChannelId = shortChannelId, buried = true, channelUpdate = channelUpdate) storing() sending localAnnSigs_opt.toSeq

    case Event(remoteAnnSigs: AnnouncementSignatures, d: DATA_NORMAL) if d.commitments.announceChannel =>
      // channels are publicly announced if both parties want it (defined as feature bit)
      if (d.buried) {
        // we are aware that the channel has reached enough confirmations
        // we already had sent our announcement_signatures but we don't store them so we need to recompute it
        val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments, d.shortChannelId)
        d.channelAnnouncement match {
          case None =>
            require(d.shortChannelId == remoteAnnSigs.shortChannelId, s"shortChannelId mismatch: local=${d.shortChannelId} remote=${remoteAnnSigs.shortChannelId}")
            log.info(s"announcing channelId=${d.channelId} on the network with shortId=${d.shortChannelId}")
            import d.commitments.{localParams, remoteParams}
            val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
            val channelAnn = Announcements.makeChannelAnnouncement(nodeParams.chainHash, localAnnSigs.shortChannelId, nodeParams.nodeId, remoteParams.nodeId, fundingPubKey.publicKey, remoteParams.fundingPubKey, localAnnSigs.nodeSignature, remoteAnnSigs.nodeSignature, localAnnSigs.bitcoinSignature, remoteAnnSigs.bitcoinSignature)
            if (!Announcements.checkSigs(channelAnn)) {
              handleLocalError(InvalidAnnouncementSignatures(d.channelId, remoteAnnSigs), d, Some(remoteAnnSigs))
            } else {
              // we use GOTO instead of stay because we want to fire transitions
              goto(NORMAL) using d.copy(channelAnnouncement = Some(channelAnn)) storing()
            }
          case Some(_) =>
            // they have sent their announcement sigs, but we already have a valid channel announcement
            // this can happen if our announcement_signatures was lost during a disconnection
            // specs says that we "MUST respond to the first announcement_signatures message after reconnection with its own announcement_signatures message"
            // current implementation always replies to announcement_signatures, not only the first time
            // TODO: we should only be nice once, current behaviour opens way to DOS, but this should be handled higher in the stack anyway
            log.info("re-sending our announcement sigs")
            stay sending localAnnSigs
        }
      } else {
        // our watcher didn't notify yet that the tx has reached ANNOUNCEMENTS_MINCONF confirmations, let's delay remote's message
        // note: no need to persist their message, in case of disconnection they will resend it
        log.debug("received remote announcement signatures, delaying")
        context.system.scheduler.scheduleOnce(5 seconds, self, remoteAnnSigs)
        stay
      }

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) =>
      log.info("updating relay fees: prevFeeBaseMsat={} nextFeeBaseMsat={} prevFeeProportionalMillionths={} nextFeeProportionalMillionths={}", d.channelUpdate.feeBaseMsat, c.feeBase, d.channelUpdate.feeProportionalMillionths, c.feeProportionalMillionths)
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, c.feeBase, c.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
      replyTo ! RES_SUCCESS(c, d.channelId)
      // we use GOTO instead of stay because we want to fire transitions
      goto(NORMAL) using d.copy(channelUpdate = channelUpdate) storing()

    case Event(BroadcastChannelUpdate(reason), d: DATA_NORMAL) =>
      val age = System.currentTimeMillis.milliseconds - d.channelUpdate.timestamp.seconds
      val channelUpdate1 = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = Helpers.aboveReserve(d.commitments))
      reason match {
        case Reconnected if d.commitments.announceChannel && Announcements.areSame(channelUpdate1, d.channelUpdate) && age < REFRESH_CHANNEL_UPDATE_INTERVAL =>
          // we already sent an identical channel_update not long ago (flapping protection in case we keep being disconnected/reconnected)
          log.debug("not sending a new identical channel_update, current one was created {} days ago", age.toDays)
          stay
        case _ =>
          log.info("refreshing channel_update announcement (reason={})", reason)
          // we use GOTO instead of stay because we want to fire transitions
          goto(NORMAL) using d.copy(channelUpdate = channelUpdate1) storing()
      }

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NORMAL) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NORMAL) if d.commitments.remoteNextCommitInfo.left.toOption.exists(_.nextRemoteCommit.txid == tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NORMAL) => handleRemoteSpentOther(tx, d)

    case Event(INPUT_DISCONNECTED, d: DATA_NORMAL) =>
      // we cancel the timer that would have made us send the enabled update after reconnection (flappy channel protection)
      cancelTimer(Reconnected.toString)
      // if we have pending unsigned htlcs, then we cancel them and advertise the fact that the channel is now disabled
      val d1 = if (d.commitments.localChanges.proposed.collectFirst { case add: UpdateAddHtlc => add }.isDefined) {
        log.info("updating channel_update announcement (reason=disabled)")
        val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = false)
        d.commitments.localChanges.proposed.collect {
          case add: UpdateAddHtlc => relayer ! RES_ADD_SETTLED(d.commitments.originChannels(add.id), add, HtlcResult.Disconnected(channelUpdate))
        }
        d.copy(channelUpdate = channelUpdate)
      } else {
        d
      }
      goto(OFFLINE) using d1

    case Event(e: Error, d: DATA_NORMAL) => handleRemoteError(e, d)

    case Event(_: FundingLocked, _: DATA_NORMAL) => stay // will happen after a reconnection if no updates were ever committed to the channel

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
      Commitments.sendFulfill(d.commitments, c) match {
        case Right((commitments1, fulfill)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fulfill
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fulfill: UpdateFulfillHtlc, d: DATA_SHUTDOWN) =>
      Commitments.receiveFulfill(d.commitments, fulfill) match {
        case Right((commitments1, origin, htlc)) =>
          // we forward preimages as soon as possible to the upstream channel because it allows us to pull funds
          relayer ! RES_ADD_SETTLED(origin, htlc, HtlcResult.RemoteFulfill(fulfill))
          stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fulfill))
      }

    case Event(c: CMD_FAIL_HTLC, d: DATA_SHUTDOWN) =>
      Commitments.sendFail(d.commitments, c, nodeParams.privateKey) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(c: CMD_FAIL_MALFORMED_HTLC, d: DATA_SHUTDOWN) =>
      Commitments.sendFailMalformed(d.commitments, c) match {
        case Right((commitments1, fail)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fail
        case Left(cause) =>
          // we acknowledge the command right away in case of failure
          handleCommandError(cause, c).acking(d.channelId, c)
      }

    case Event(fail: UpdateFailHtlc, d: DATA_SHUTDOWN) =>
      Commitments.receiveFail(d.commitments, fail) match {
        case Right((commitments1, _, _)) =>
          stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(fail: UpdateFailMalformedHtlc, d: DATA_SHUTDOWN) =>
      Commitments.receiveFailMalformed(d.commitments, fail) match {
        case Right((commitments1, _, _)) => stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fail))
      }

    case Event(c: CMD_UPDATE_FEE, d: DATA_SHUTDOWN) =>
      Commitments.sendFee(d.commitments, c) match {
        case Right((commitments1, fee)) =>
          if (c.commit) self ! CMD_SIGN()
          handleCommandSuccess(c, d.copy(commitments = commitments1)) sending fee
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(fee: UpdateFee, d: DATA_SHUTDOWN) =>
      Commitments.receiveFee(d.commitments, fee, nodeParams.onChainFeeConf) match {
        case Right(commitments1) => stay using d.copy(commitments = commitments1)
        case Left(cause) => handleLocalError(cause, d, Some(fee))
      }

    case Event(c: CMD_SIGN, d: DATA_SHUTDOWN) =>
      d.commitments.remoteNextCommitInfo match {
        case _ if !Commitments.localHasChanges(d.commitments) =>
          log.debug("ignoring CMD_SIGN (nothing to sign)")
          stay
        case Right(_) =>
          Commitments.sendCommit(d.commitments, keyManager) match {
            case Right((commitments1, commit)) =>
              log.debug("sending a new sig, spec:\n{}", Commitments.specs2String(commitments1))
              val nextRemoteCommit = commitments1.remoteNextCommitInfo.left.get.nextRemoteCommit
              val nextCommitNumber = nextRemoteCommit.index
              // we persist htlc data in order to be able to claim htlc outputs in case a revoked tx is published by our
              // counterparty, so only htlcs above remote's dust_limit matter
              val trimmedHtlcs = Transactions.trimOfferedHtlcs(d.commitments.remoteParams.dustLimit, nextRemoteCommit.spec, d.commitments.commitmentFormat) ++
                Transactions.trimReceivedHtlcs(d.commitments.remoteParams.dustLimit, nextRemoteCommit.spec, d.commitments.commitmentFormat)
              trimmedHtlcs.map(_.add).foreach { htlc =>
                log.info(s"adding paymentHash=${htlc.paymentHash} cltvExpiry=${htlc.cltvExpiry} to htlcs db for commitNumber=$nextCommitNumber")
                nodeParams.db.channels.addHtlcInfo(d.channelId, nextCommitNumber, htlc.paymentHash, htlc.cltvExpiry)
              }
              context.system.eventStream.publish(ChannelSignatureSent(self, commitments1))
              // we expect a quick response from our peer
              setTimer(RevocationTimeout.toString, RevocationTimeout(commitments1.remoteCommit.index, peer), timeout = nodeParams.revocationTimeout, repeat = false)
              handleCommandSuccess(c, d.copy(commitments = commitments1)).storing().sending(commit).acking(commitments1.localChanges.signed)
            case Left(cause) => handleCommandError(cause, c)
          }
        case Left(waitForRevocation) =>
          log.debug("already in the process of signing, will sign again as soon as possible")
          stay using d.copy(commitments = d.commitments.copy(remoteNextCommitInfo = Left(waitForRevocation.copy(reSignAsap = true))))
      }

    case Event(commit: CommitSig, d@DATA_SHUTDOWN(_, localShutdown, remoteShutdown, closingFeerates)) =>
      Commitments.receiveCommit(d.commitments, commit, keyManager) match {
        case Right((commitments1, revocation)) =>
          // we always reply with a revocation
          log.debug("received a new sig:\n{}", Commitments.specs2String(commitments1))
          context.system.eventStream.publish(ChannelSignatureReceived(self, commitments1))
          if (commitments1.hasNoPendingHtlcsOrFeeUpdate) {
            if (d.commitments.localParams.isFunder) {
              // we are funder, need to initiate the negotiation by sending the first closing_signed
              val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, closingFeerates)
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending revocation :: closingSigned :: Nil
            } else {
              // we are fundee, will wait for their closing_signed
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing() sending revocation
            }
          } else {
            if (Commitments.localHasChanges(commitments1)) {
              // if we have newly acknowledged changes let's sign them
              self ! CMD_SIGN()
            }
            stay using d.copy(commitments = commitments1) storing() sending revocation
          }
        case Left(cause) => handleLocalError(cause, d, Some(commit))
      }

    case Event(revocation: RevokeAndAck, d@DATA_SHUTDOWN(commitments, localShutdown, remoteShutdown, closingFeerates)) =>
      // we received a revocation because we sent a signature
      // => all our changes have been acked including the shutdown message
      Commitments.receiveRevocation(commitments, revocation) match {
        case Right((commitments1, forwards)) =>
          cancelTimer(RevocationTimeout.toString)
          log.debug("received a new rev, spec:\n{}", Commitments.specs2String(commitments1))
          forwards.foreach {
            case Right(forwardAdd) =>
              // BOLT 2: A sending node SHOULD fail to route any HTLC added after it sent shutdown.
              log.debug("closing in progress: failing {}", forwardAdd.add)
              self ! CMD_FAIL_HTLC(forwardAdd.add.id, Right(PermanentChannelFailure), commit = true)
            case Left(forward) =>
              log.debug("forwarding {} to relayer", forward)
              relayer ! forward
          }
          if (commitments1.hasNoPendingHtlcsOrFeeUpdate) {
            log.debug("switching to NEGOTIATING spec:\n{}", Commitments.specs2String(commitments1))
            if (d.commitments.localParams.isFunder) {
              // we are funder, need to initiate the negotiation by sending the first closing_signed
              val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, commitments1, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, closingFeerates)
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, List(List(ClosingTxProposed(closingTx, closingSigned))), bestUnpublishedClosingTx_opt = None) storing() sending closingSigned
            } else {
              // we are fundee, will wait for their closing_signed
              goto(NEGOTIATING) using DATA_NEGOTIATING(commitments1, localShutdown, remoteShutdown, closingTxProposed = List(List()), bestUnpublishedClosingTx_opt = None) storing()
            }
          } else {
            if (Commitments.localHasChanges(commitments1) && d.commitments.remoteNextCommitInfo.left.map(_.reSignAsap) == Left(true)) {
              self ! CMD_SIGN()
            }
            stay using d.copy(commitments = commitments1) storing()
          }
        case Left(cause) => handleLocalError(cause, d, Some(revocation))
      }

    case Event(r: RevocationTimeout, d: DATA_SHUTDOWN) => handleRevocationTimeout(r, d)

    case Event(ProcessCurrentBlockCount(c), d: DATA_SHUTDOWN) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: DATA_SHUTDOWN) => handleCurrentFeerate(c, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_SHUTDOWN) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_SHUTDOWN) if d.commitments.remoteNextCommitInfo.left.toOption.exists(_.nextRemoteCommit.txid == tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_SHUTDOWN) => handleRemoteSpentOther(tx, d)

    case Event(c: CMD_CLOSE, d: DATA_SHUTDOWN) =>
      c.feerates match {
        case Some(feerates) if c.feerates != d.closingFeerates =>
          log.info("updating our closing feerates: {}", feerates)
          handleCommandSuccess(c, d.copy(closingFeerates = c.feerates)) storing()
        case _ =>
          handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      }

    case Event(e: Error, d: DATA_SHUTDOWN) => handleRemoteError(e, d)

  })

  when(NEGOTIATING)(handleExceptions {
    case Event(c@ClosingSigned(_, remoteClosingFee, remoteSig, _), d: DATA_NEGOTIATING) =>
      log.info("received closing fees={}", remoteClosingFee)
      Closing.checkClosingSignature(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, remoteClosingFee, remoteSig) match {
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
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
          } else if (d.commitments.localCommit.spec.toLocal == 0.msat) {
            // we have nothing at stake so there is no need to negotiate, we accept their fee right away
            handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
          } else {
            c.feeRange_opt match {
              case Some(ClosingSignedTlv.FeeRange(minFee, maxFee)) if !d.commitments.localParams.isFunder =>
                // if we are fundee and they proposed a fee range, we pick a value in that range and they should accept it without further negotiation
                // we don't care much about the closing fee since they're paying it (not us) and we can use CPFP if we want to speed up confirmation
                // but we need to ensure it's high enough to at least propagate across the network
                val txPropagationFeerate = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(1008) * 2
                val txPropagationMinFee = Transactions.weight2fee(txPropagationFeerate, signedClosingTx.tx.weight())
                if (maxFee < txPropagationMinFee) {
                  log.warning("their highest closing fee is below our tx propagation threshold (feerate={}): {} < {}", txPropagationFeerate, maxFee, txPropagationMinFee)
                  stay sending Warning(d.channelId, s"closing fee range must not be below $txPropagationMinFee")
                } else {
                  val closingFee = Closing.firstClosingFee(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets) match {
                    case ClosingFees(preferred, _, _) if preferred > maxFee => maxFee
                    // if we underestimate the fee, then we're happy with whatever they propose (it will confirm more quickly and we're not paying it)
                    case ClosingFees(preferred, _, _) if preferred < remoteClosingFee => remoteClosingFee
                    case ClosingFees(preferred, _, _) => preferred
                  }
                  if (closingFee == remoteClosingFee) {
                    log.info("accepting their closing fees={}", remoteClosingFee)
                    handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSignedRemoteFees
                  } else {
                    val (closingTx, closingSigned) = Closing.makeClosingTx(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, ClosingFees(closingFee, minFee, maxFee))
                    log.info("proposing closing fees={} in their fee range (min={} max={})", closingSigned.feeSatoshis, minFee, maxFee)
                    val closingTxProposed1 = d.closingTxProposed match {
                      case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx, closingSigned))
                    }
                    stay using d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx)) storing() sending closingSigned
                  }
                }
              case _ =>
                val lastLocalClosingFee_opt = lastLocalClosingSigned_opt.map(_.localClosingSigned.feeSatoshis)
                val (closingTx, closingSigned) = {
                  // if we are fundee and we were waiting for them to send their first closing_signed, we don't have a lastLocalClosingFee, so we compute a firstClosingFee
                  val localClosingFees = Closing.firstClosingFee(d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
                  val nextPreferredFee = Closing.nextClosingFee(lastLocalClosingFee_opt.getOrElse(localClosingFees.preferred), remoteClosingFee)
                  Closing.makeClosingTx(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, localClosingFees.copy(preferred = nextPreferredFee))
                }
                val closingTxProposed1 = d.closingTxProposed match {
                  case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx, closingSigned))
                }
                if (lastLocalClosingFee_opt.contains(closingSigned.feeSatoshis)) {
                  // next computed fee is the same than the one we previously sent (probably because of rounding), let's close now
                  handleMutualClose(signedClosingTx, Left(d.copy(bestUnpublishedClosingTx_opt = Some(signedClosingTx))))
                } else if (closingSigned.feeSatoshis == remoteClosingFee) {
                  // we have converged!
                  log.info("accepting their closing fees={}", remoteClosingFee)
                  handleMutualClose(signedClosingTx, Left(d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx)))) sending closingSigned
                } else {
                  log.info("proposing closing fees={}", closingSigned.feeSatoshis)
                  stay using d.copy(closingTxProposed = closingTxProposed1, bestUnpublishedClosingTx_opt = Some(signedClosingTx)) storing() sending closingSigned
                }
            }
          }
        case Left(cause) => handleLocalError(cause, d, Some(c))
      }

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.exists(_.unsignedTx.tx.txid == tx.txid) =>
      // they can publish a closing tx with any sig we sent them, even if we are not done negotiating
      handleMutualClose(getMutualClosePublished(tx, d.closingTxProposed), Left(d))

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if d.commitments.remoteNextCommitInfo.left.toOption.exists(_.nextRemoteCommit.txid == tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) => handleRemoteSpentOther(tx, d)

    case Event(c: CMD_CLOSE, d: DATA_NEGOTIATING) =>
      c.feerates match {
        case Some(feerates) =>
          log.info("updating our closing feerates: {}", feerates)
          val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, Some(feerates))
          val closingTxProposed1 = d.closingTxProposed match {
            case previousNegotiations :+ currentNegotiation => previousNegotiations :+ (currentNegotiation :+ ClosingTxProposed(closingTx, closingSigned))
            case previousNegotiations => previousNegotiations :+ List(ClosingTxProposed(closingTx, closingSigned))
          }
          handleCommandSuccess(c, d.copy(closingTxProposed = closingTxProposed1)) storing() sending closingSigned
        case _ =>
          handleCommandError(ClosingAlreadyInProgress(d.channelId), c)
      }

    case Event(e: Error, d: DATA_NEGOTIATING) => handleRemoteError(e, d)

  })

  when(CLOSING)(handleExceptions {
    case Event(c: CMD_FULFILL_HTLC, d: DATA_CLOSING) =>
      Commitments.sendFulfill(d.commitments, c) match {
        case Right((commitments1, _)) =>
          log.info("got valid payment preimage, recalculating transactions to redeem the corresponding htlc on-chain")
          val localCommitPublished1 = d.localCommitPublished.map(localCommitPublished => Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, commitments1, localCommitPublished.commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets))
          val remoteCommitPublished1 = d.remoteCommitPublished.map(remoteCommitPublished => Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, commitments1.remoteCommit, remoteCommitPublished.commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets))
          val nextRemoteCommitPublished1 = d.nextRemoteCommitPublished.map(remoteCommitPublished => {
            require(commitments1.remoteNextCommitInfo.isLeft, "next remote commit must be defined")
            val remoteCommit = commitments1.remoteNextCommitInfo.swap.toOption.get.nextRemoteCommit
            Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, commitments1, remoteCommit, remoteCommitPublished.commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
          })

          def republish(): Unit = {
            localCommitPublished1.foreach(lcp => doPublish(lcp, commitments1))
            remoteCommitPublished1.foreach(doPublish)
            nextRemoteCommitPublished1.foreach(doPublish)
          }

          handleCommandSuccess(c, d.copy(commitments = commitments1, localCommitPublished = localCommitPublished1, remoteCommitPublished = remoteCommitPublished1, nextRemoteCommitPublished = nextRemoteCommitPublished1)) storing() calling republish()
        case Left(cause) => handleCommandError(cause, c)
      }

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_CLOSING) if getTxResponse.txid == d.commitments.commitInput.outPoint.txid =>
      // NB: waitingSinceBlock contains the block at which closing was initiated, not the block at which funding was initiated.
      // That means we're lenient with our peer and give its funding tx more time to confirm, to avoid having to store two distinct
      // waitingSinceBlock (e.g. closingWaitingSinceBlock and fundingWaitingSinceBlock).
      handleGetFundingTx(getTxResponse, d.waitingSinceBlock, d.fundingTx)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_CLOSING) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_CLOSING) => handleFundingTimeout(d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_CLOSING) =>
      if (d.mutualClosePublished.exists(_.tx.txid == tx.txid)) {
        // we already know about this tx, probably because we have published it ourselves after successful negotiation
        stay
      } else if (d.mutualCloseProposed.exists(_.tx.txid == tx.txid)) {
        // at any time they can publish a closing tx with any sig we sent them: we use their version since it has their sig as well
        val closingTx = d.mutualCloseProposed.find(_.tx.txid == tx.txid).get.copy(tx = tx)
        handleMutualClose(closingTx, Right(d))
      } else if (d.localCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (d.remoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (d.nextRemoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (d.futureRemoteCommitPublished.exists(_.commitTx.txid == tx.txid)) {
        // this is because WatchSpent watches never expire and we are notified multiple times
        stay
      } else if (tx.txid == d.commitments.remoteCommit.txid) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentCurrent(tx, d)
      } else if (d.commitments.remoteNextCommitInfo.left.toOption.exists(_.nextRemoteCommit.txid == tx.txid)) {
        // counterparty may attempt to spend its last commit tx at any time
        handleRemoteSpentNext(tx, d)
      } else {
        // counterparty may attempt to spend a revoked commit tx at any time
        handleRemoteSpentOther(tx, d)
      }

    case Event(WatchOutputSpentTriggered(tx), d: DATA_CLOSING) =>
      // one of the outputs of the local/remote/revoked commit was spent
      // we just put a watch to be notified when it is confirmed
      blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.minDepthBlocks)
      // when a remote or local commitment tx containing outgoing htlcs is published on the network,
      // we watch it in order to extract payment preimage if funds are pulled by the counterparty
      // we can then use these preimages to fulfill origin htlcs
      log.info(s"processing bitcoin output spent by txid=${tx.txid} tx=$tx")
      val extracted = Closing.extractPreimages(d.commitments.localCommit, tx)
      extracted foreach { case (htlc, preimage) =>
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
        val (rev1, penaltyTxs) = Closing.claimRevokedHtlcTxOutputs(keyManager, d.commitments, rev, tx, nodeParams.onChainFeeConf.feeEstimator)
        penaltyTxs.foreach(claimTx => txPublisher ! PublishRawTx(claimTx, None))
        penaltyTxs.foreach(claimTx => blockchain ! WatchOutputSpent(self, tx.txid, claimTx.input.outPoint.index.toInt, hints = Set(claimTx.tx.txid)))
        rev1
      }
      stay using d.copy(revokedCommitPublished = revokedCommitPublished1) storing()

    case Event(WatchTxConfirmedTriggered(blockHeight, _, tx), d: DATA_CLOSING) =>
      log.info(s"txid=${tx.txid} has reached mindepth, updating closing state")
      // first we check if this tx belongs to one of the current local/remote commits, update it and update the channel data
      val d1 = d.copy(
        localCommitPublished = d.localCommitPublished.map(localCommitPublished => {
          // If the tx is one of our HTLC txs, we now publish a 3rd-stage claim-htlc-tx that claims its output.
          val (localCommitPublished1, claimHtlcTx_opt) = Closing.claimLocalCommitHtlcTxOutput(localCommitPublished, keyManager, d.commitments, tx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
          claimHtlcTx_opt.foreach(claimHtlcTx => {
            txPublisher ! PublishRawTx(claimHtlcTx, None)
            blockchain ! WatchTxConfirmed(self, claimHtlcTx.tx.txid, nodeParams.minDepthBlocks)
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
        context.system.eventStream.publish(LocalCommitConfirmed(self, remoteNodeId, d.channelId, blockHeight + d.commitments.remoteParams.toSelfDelay.toInt))
      }
      // we may need to fail some htlcs in case a commitment tx was published and they have reached the timeout threshold
      val timedOutHtlcs = Closing.isClosingTypeAlreadyKnown(d1) match {
        case Some(c: Closing.LocalClose) => Closing.timedOutHtlcs(d.commitments.commitmentFormat, c.localCommit, c.localCommitPublished, d.commitments.localParams.dustLimit, tx)
        case Some(c: Closing.RemoteClose) => Closing.timedOutHtlcs(d.commitments.commitmentFormat, c.remoteCommit, c.remoteCommitPublished, d.commitments.remoteParams.dustLimit, tx)
        case _ => Set.empty[UpdateAddHtlc] // we lose htlc outputs in dataloss protection scenarii (future remote commit)
      }
      timedOutHtlcs.foreach { add =>
        d.commitments.originChannels.get(add.id) match {
          case Some(origin) =>
            log.info(s"failing htlc #${add.id} paymentHash=${add.paymentHash} origin=$origin: htlc timed out")
            relayer ! RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(d.channelId, Set(add))))
          case None =>
            // same as for fulfilling the htlc (no big deal)
            log.info(s"cannot fail timedout htlc #${add.id} paymentHash=${add.paymentHash} (origin not found)")
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
        .onChainOutgoingHtlcs(d.commitments.localCommit, d.commitments.remoteCommit, d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit), tx)
        .map(add => (add, d.commitments.originChannels.get(add.id).collect { case o: Origin.Local => o.id })) // we resolve the payment id if this was a local payment
        .collect { case (add, Some(id)) => context.system.eventStream.publish(PaymentSettlingOnChain(id, amount = add.amountMsat, add.paymentHash)) }
      // and we also send events related to fee
      Closing.networkFeePaid(tx, d1) foreach { case (fee, desc) => feePaid(fee, tx, desc, d.channelId) }
      // then let's see if any of the possible close scenarii can be considered done
      val closingType_opt = Closing.isClosed(d1, Some(tx))
      // finally, if one of the unilateral closes is done, we move to CLOSED state, otherwise we stay (note that we don't store the state)
      closingType_opt match {
        case Some(closingType) =>
          log.info(s"channel closed (type=${closingType_opt.map(c => EventType.Closed(c).label).getOrElse("UnknownYet")})")
          context.system.eventStream.publish(ChannelClosed(self, d.channelId, closingType, d.commitments))
          goto(CLOSED) using d1 storing()
        case None =>
          stay using d1 storing()
      }

    case Event(_: ChannelReestablish, d: DATA_CLOSING) =>
      // they haven't detected that we were closing and are trying to reestablish a connection
      // we give them one of the published txes as a hint
      // note spendingTx != Nil (that's a requirement of DATA_CLOSING)
      val exc = FundingTxSpent(d.channelId, d.spendingTxs.head)
      val error = Error(d.channelId, exc.getMessage)
      stay sending error

    case Event(c: CMD_CLOSE, d: DATA_CLOSING) => handleCommandError(ClosingAlreadyInProgress(d.channelId), c)

    case Event(e: Error, d: DATA_CLOSING) => handleRemoteError(e, d)

    case Event(INPUT_DISCONNECTED | INPUT_RECONNECTED(_, _, _), _) => stay // we don't really care at this point
  })

  when(CLOSED)(handleExceptions {
    case Event(Symbol("shutdown"), _) =>
      stateData match {
        case d: HasCommitments =>
          log.info(s"deleting database record for channelId=${d.channelId}")
          nodeParams.db.channels.removeChannel(d.channelId)
        case _ =>
      }
      log.info("shutting down")
      stop(FSM.Normal)

    case Event(MakeFundingTxResponse(fundingTx, _, _), _) =>
      // this may happen if connection is lost, or remote sends an error while we were waiting for the funding tx to be created by our wallet
      // in that case we rollback the tx
      wallet.rollback(fundingTx)
      stay

    case Event(INPUT_DISCONNECTED, _) => stay // we are disconnected, but it doesn't matter anymore
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

    case Event(INPUT_RECONNECTED(r, localInit, remoteInit), d: HasCommitments) =>
      activeConnection = r

      val yourLastPerCommitmentSecret = d.commitments.remotePerCommitmentSecrets.lastIndex.flatMap(d.commitments.remotePerCommitmentSecrets.getHash).getOrElse(ByteVector32.Zeroes)
      val channelKeyPath = keyManager.keyPath(d.commitments.localParams, d.commitments.channelVersion)
      val myCurrentPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, d.commitments.localCommit.index)

      val channelReestablish = ChannelReestablish(
        channelId = d.channelId,
        nextLocalCommitmentNumber = d.commitments.localCommit.index + 1,
        nextRemoteRevocationNumber = d.commitments.remoteCommit.index,
        yourLastPerCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
        myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
      )

      // we update local/remote connection-local global/local features, we don't persist it right now
      val d1 = Helpers.updateFeatures(d, localInit, remoteInit)

      goto(SYNCING) using d1 sending channelReestablish

    // note: this can only happen if state is NORMAL or SHUTDOWN
    // -> in NEGOTIATING there are no more htlcs
    // -> in CLOSING we either have mutual closed (so no more htlcs), or already have unilaterally closed (so no action required), and we can't be in OFFLINE state anyway
    case Event(ProcessCurrentBlockCount(c), d: HasCommitments) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: HasCommitments) =>
      handleOfflineFeerate(c, d)

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) => handleAddDisconnected(c, d)

    case Event(c: CMD_UPDATE_RELAY_FEE, d: DATA_NORMAL) =>
      log.info(s"updating relay fees: prevFeeBaseMsat={} nextFeeBaseMsat={} prevFeeProportionalMillionths={} nextFeeProportionalMillionths={}", d.channelUpdate.feeBaseMsat, c.feeBase, d.channelUpdate.feeProportionalMillionths, c.feeProportionalMillionths)
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, c.feeBase, c.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = false)
      val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
      replyTo ! RES_SUCCESS(c, d.channelId)
      // we're in OFFLINE state, we don't broadcast the new update right away, we will do that when next time we go to NORMAL state
      stay using d.copy(channelUpdate = channelUpdate) storing()

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.commitInput.outPoint.txid => handleGetFundingTx(getTxResponse, d.waitingSinceBlock, d.fundingTx)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    // just ignore this, we will put a new watch when we reconnect, and we'll be notified again
    case Event(WatchFundingConfirmedTriggered(_, _, _), _) => stay

    case Event(WatchFundingDeeplyBuriedTriggered(_, _, _), _) => stay

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.exists(_.unsignedTx.tx.txid == tx.txid) =>
      handleMutualClose(getMutualClosePublished(tx, d.closingTxProposed), Left(d))

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) if d.commitments.remoteNextCommitInfo.left.toOption.exists(_.nextRemoteCommit.txid == tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => handleRemoteSpentFuture(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) => handleRemoteSpentOther(tx, d)

  })

  when(SYNCING)(handleExceptions {
    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) =>
      val minDepth = if (d.commitments.localParams.isFunder) {
        nodeParams.minDepthBlocks
      } else {
        // when we're fundee we scale the min_depth confirmations depending on the funding amount
        Helpers.minDepthForFunding(nodeParams, d.commitments.commitInput.txOut.amount)
      }
      // we put back the watch (operation is idempotent) because the event may have been fired while we were in OFFLINE
      blockchain ! WatchFundingConfirmed(self, d.commitments.commitInput.outPoint.txid, minDepth)
      goto(WAIT_FOR_FUNDING_CONFIRMED)

    case Event(_: ChannelReestablish, d: DATA_WAIT_FOR_FUNDING_LOCKED) =>
      log.debug("re-sending fundingLocked")
      val channelKeyPath = keyManager.keyPath(d.commitments.localParams, d.commitments.channelVersion)
      val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
      val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
      goto(WAIT_FOR_FUNDING_LOCKED) sending fundingLocked

    case Event(channelReestablish: ChannelReestablish, d: DATA_NORMAL) =>
      var sendQueue = Queue.empty[LightningMessage]
      val channelKeyPath = keyManager.keyPath(d.commitments.localParams, d.commitments.channelVersion)
      channelReestablish match {
        case ChannelReestablish(_, _, nextRemoteRevocationNumber, yourLastPerCommitmentSecret, _) if !Helpers.checkLocalCommit(d, nextRemoteRevocationNumber) =>
          // if next_remote_revocation_number is greater than our local commitment index, it means that either we are using an outdated commitment, or they are lying
          // but first we need to make sure that the last per_commitment_secret that they claim to have received from us is correct for that next_remote_revocation_number minus 1
          if (keyManager.commitmentSecret(channelKeyPath, nextRemoteRevocationNumber - 1) == yourLastPerCommitmentSecret) {
            log.warning(s"counterparty proved that we have an outdated (revoked) local commitment!!! ourCommitmentNumber=${d.commitments.localCommit.index} theirCommitmentNumber=$nextRemoteRevocationNumber")
            // their data checks out, we indeed seem to be using an old revoked commitment, and must absolutely *NOT* publish it, because that would be a cheating attempt and they
            // would punish us by taking all the funds in the channel
            val exc = PleasePublishYourCommitment(d.channelId)
            val error = Error(d.channelId, exc.getMessage)
            goto(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) using DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(d.commitments, channelReestablish) storing() sending error
          } else {
            // they lied! the last per_commitment_secret they claimed to have received from us is invalid
            throw InvalidRevokedCommitProof(d.channelId, d.commitments.localCommit.index, nextRemoteRevocationNumber, yourLastPerCommitmentSecret)
          }
        case ChannelReestablish(_, nextLocalCommitmentNumber, _, _, _) if !Helpers.checkRemoteCommit(d, nextLocalCommitmentNumber) =>
          // if next_local_commit_number is more than one more our remote commitment index, it means that either we are using an outdated commitment, or they are lying
          log.warning(s"counterparty says that they have a more recent commitment than the one we know of!!! ourCommitmentNumber=${d.commitments.remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.index).getOrElse(d.commitments.remoteCommit.index)} theirCommitmentNumber=$nextLocalCommitmentNumber")
          // there is no way to make sure that they are saying the truth, the best thing to do is ask them to publish their commitment right now
          // maybe they will publish their commitment, in that case we need to remember their commitment point in order to be able to claim our outputs
          // not that if they don't comply, we could publish our own commitment (it is not stale, otherwise we would be in the case above)
          val exc = PleasePublishYourCommitment(d.channelId)
          val error = Error(d.channelId, exc.getMessage)
          goto(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) using DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(d.commitments, channelReestablish) storing() sending error
        case _ =>
          // normal case, our data is up-to-date
          if (channelReestablish.nextLocalCommitmentNumber == 1 && d.commitments.localCommit.index == 0) {
            // If next_local_commitment_number is 1 in both the channel_reestablish it sent and received, then the node MUST retransmit funding_locked, otherwise it MUST NOT
            log.debug("re-sending fundingLocked")
            val nextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, 1)
            val fundingLocked = FundingLocked(d.commitments.channelId, nextPerCommitmentPoint)
            sendQueue = sendQueue :+ fundingLocked
          }

          val (commitments1, sendQueue1) = handleSync(channelReestablish, d)
          sendQueue = sendQueue ++ sendQueue1

          // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
          d.localShutdown.foreach {
            localShutdown =>
              log.debug("re-sending localShutdown")
              sendQueue = sendQueue :+ localShutdown
          }

          if (!d.buried) {
            // even if we were just disconnected/reconnected, we need to put back the watch because the event may have been
            // fired while we were in OFFLINE (if not, the operation is idempotent anyway)
            blockchain ! WatchFundingDeeplyBuried(self, d.commitments.commitInput.outPoint.txid, ANNOUNCEMENTS_MINCONF)
          } else {
            // channel has been buried enough, should we (re)send our announcement sigs?
            d.channelAnnouncement match {
              case None if !d.commitments.announceChannel =>
                // that's a private channel, nothing to do
                ()
              case None =>
                // BOLT 7: a node SHOULD retransmit the announcement_signatures message if it has not received an announcement_signatures message
                val localAnnSigs = Helpers.makeAnnouncementSignatures(nodeParams, d.commitments, d.shortChannelId)
                sendQueue = sendQueue :+ localAnnSigs
              case Some(_) =>
                // channel was already announced, nothing to do
                ()
            }
          }

          if (d.commitments.announceChannel) {
            // we will re-enable the channel after some delay to prevent flappy updates in case the connection is unstable
            setTimer(Reconnected.toString, BroadcastChannelUpdate(Reconnected), 10 seconds, repeat = false)
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
          if (d.commitments.localParams.isFunder && !shutdownInProgress) {
            val currentFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw
            val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, d.commitments.channelVersion, d.commitments.capacity, None)
            if (nodeParams.onChainFeeConf.shouldUpdateFee(currentFeeratePerKw, networkFeeratePerKw)) {
              self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
            }
          }

          goto(NORMAL) using d.copy(commitments = commitments1) sending sendQueue
      }

    case Event(c: CMD_ADD_HTLC, d: DATA_NORMAL) => handleAddDisconnected(c, d)

    case Event(channelReestablish: ChannelReestablish, d: DATA_SHUTDOWN) =>
      var sendQueue = Queue.empty[LightningMessage]
      val (commitments1, sendQueue1) = handleSync(channelReestablish, d)
      sendQueue = sendQueue ++ sendQueue1 :+ d.localShutdown
      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      goto(SHUTDOWN) using d.copy(commitments = commitments1) sending sendQueue

    case Event(_: ChannelReestablish, d: DATA_NEGOTIATING) =>
      // BOLT 2: A node if it has sent a previous shutdown MUST retransmit shutdown.
      // negotiation restarts from the beginning, and is initialized by the funder
      // note: in any case we still need to keep all previously sent closing_signed, because they may publish one of them
      if (d.commitments.localParams.isFunder) {
        // we could use the last closing_signed we sent, but network fees may have changed while we were offline so it is better to restart from scratch
        val (closingTx, closingSigned) = Closing.makeFirstClosingTx(keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets, None)
        val closingTxProposed1 = d.closingTxProposed :+ List(ClosingTxProposed(closingTx, closingSigned))
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) storing() sending d.localShutdown :: closingSigned :: Nil
      } else {
        // we start a new round of negotiation
        val closingTxProposed1 = if (d.closingTxProposed.last.isEmpty) d.closingTxProposed else d.closingTxProposed :+ List()
        goto(NEGOTIATING) using d.copy(closingTxProposed = closingTxProposed1) sending d.localShutdown
      }

    // This handler is a workaround for an issue in lnd: starting with versions 0.10 / 0.11, they sometimes fail to send
    // a channel_reestablish when reconnecting a channel that recently got confirmed, and instead send a funding_locked
    // first and then go silent. This is due to a race condition on their side, so we trigger a reconnection, hoping that
    // we will eventually receive their channel_reestablish.
    case Event(_: FundingLocked, _) =>
      log.warning("received funding_locked before channel_reestablish (known lnd bug): disconnecting...")
      peer ! Peer.Disconnect(remoteNodeId)
      stay

    // This handler is a workaround for an issue in lnd similar to the one above: they sometimes send announcement_signatures
    // before channel_reestablish, which is a minor spec violation. It doesn't halt the channel, we can simply postpone
    // that message.
    case Event(remoteAnnSigs: AnnouncementSignatures, _) =>
      log.warning("received announcement_signatures before channel_reestablish (known lnd bug): delaying...")
      context.system.scheduler.scheduleOnce(5 seconds, self, remoteAnnSigs)
      stay

    case Event(ProcessCurrentBlockCount(c), d: HasCommitments) => handleNewBlock(c, d)

    case Event(c: CurrentFeerates, d: HasCommitments) =>
      handleOfflineFeerate(c, d)

    case Event(getTxResponse: GetTxWithMetaResponse, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) if getTxResponse.txid == d.commitments.commitInput.outPoint.txid => handleGetFundingTx(getTxResponse, d.waitingSinceBlock, d.fundingTx)

    case Event(BITCOIN_FUNDING_PUBLISH_FAILED, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingPublishFailed(d)

    case Event(BITCOIN_FUNDING_TIMEOUT, d: DATA_WAIT_FOR_FUNDING_CONFIRMED) => handleFundingTimeout(d)

    // just ignore this, we will put a new watch when we reconnect, and we'll be notified again
    case Event(WatchFundingConfirmedTriggered(_, _, _), _) => stay

    case Event(WatchFundingDeeplyBuriedTriggered(_, _, _), _) => stay

    case Event(WatchFundingSpentTriggered(tx), d: DATA_NEGOTIATING) if d.closingTxProposed.flatten.exists(_.unsignedTx.tx.txid == tx.txid) =>
      handleMutualClose(getMutualClosePublished(tx, d.closingTxProposed), Left(d))

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) if tx.txid == d.commitments.remoteCommit.txid => handleRemoteSpentCurrent(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) if d.commitments.remoteNextCommitInfo.left.toOption.exists(_.nextRemoteCommit.txid == tx.txid) => handleRemoteSpentNext(tx, d)

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) => handleRemoteSpentOther(tx, d)

    case Event(e: Error, d: HasCommitments) => handleRemoteError(e, d)
  })

  when(WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)(handleExceptions {
    case Event(WatchFundingSpentTriggered(tx), d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) => handleRemoteSpentFuture(tx, d)
  })

  private def errorStateHandler: StateFunction = {
    case Event(Symbol("nevermatches"), _) => stay // we can't define a state with no event handler, so we put a dummy one here
  }

  when(ERR_INFORMATION_LEAK)(errorStateHandler)

  when(ERR_FUNDING_LOST)(errorStateHandler)

  whenUnhandled {

    case Event(INPUT_DISCONNECTED, _) => goto(OFFLINE)

    case Event(WatchFundingLostTriggered(_), _) => goto(ERR_FUNDING_LOST)

    case Event(c: CMD_GETSTATE, _) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
      replyTo ! RES_GETSTATE(stateName)
      stay

    case Event(c: CMD_GETSTATEDATA, _) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
      replyTo ! RES_GETSTATEDATA(stateData)
      stay

    case Event(c: CMD_GETINFO, _) =>
      val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
      replyTo ! RES_GETINFO(remoteNodeId, stateData.channelId, stateName, stateData)
      stay

    case Event(c: CMD_ADD_HTLC, d: HasCommitments) =>
      log.info(s"rejecting htlc request in state=$stateName")
      val error = ChannelUnavailable(d.channelId)
      handleAddHtlcCommandError(c, error, None) // we don't provide a channel_update: this will be a permanent channel failure

    case Event(c: CMD_CLOSE, d) => handleCommandError(CommandUnavailableInThisState(d.channelId, "close", stateName), c)

    case Event(c: CMD_FORCECLOSE, d) =>
      d match {
        case data: HasCommitments =>
          val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
          replyTo ! RES_SUCCESS(c, data.channelId)
          handleLocalError(ForcedLocalCommit(data.channelId), data, Some(c))
        case _ => handleCommandError(CommandUnavailableInThisState(d.channelId, "forceclose", stateName), c)
      }

    case Event(c: CMD_UPDATE_RELAY_FEE, d) => handleCommandError(CommandUnavailableInThisState(d.channelId, "updaterelayfee", stateName), c)

    // we only care about this event in NORMAL and SHUTDOWN state, and there may be cases where the task is not cancelled
    case Event(_: RevocationTimeout, _) => stay

    // we reschedule with a random delay to prevent herd effect when there are a lot of channels
    case Event(c: CurrentBlockCount, _) =>
      context.system.scheduler.scheduleOnce(blockProcessingDelay, self, ProcessCurrentBlockCount(c))
      stay

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(ProcessCurrentBlockCount(_), _) => stay

    // we only care about this event in NORMAL and SHUTDOWN state, and we never unregister to the event stream
    case Event(CurrentFeerates(_), _) => stay

    // we only care about this event in NORMAL state
    case Event(_: BroadcastChannelUpdate, _) => stay

    // we receive this when we tell the peer to disconnect
    case Event("disconnecting", _) => stay

    // funding tx was confirmed in time, let's just ignore this
    case Event(BITCOIN_FUNDING_TIMEOUT, _: HasCommitments) => stay

    // peer doesn't cancel the timer
    case Event(TickChannelOpenTimeout, _) => stay

    case Event(WatchFundingSpentTriggered(tx), d: HasCommitments) if tx.txid == d.commitments.localCommit.publishableTxs.commitTx.tx.txid =>
      log.warning(s"processing local commit spent in catch-all handler")
      spendLocalCurrent(d)
  }

  onTransition {
    case WAIT_FOR_INIT_INTERNAL -> WAIT_FOR_INIT_INTERNAL => () // called at channel initialization
    case state -> nextState =>
      if (state != nextState) {
        val commitments_opt = nextStateData match {
          case hasCommitments: HasCommitments => Some(hasCommitments.commitments)
          case _ => None
        }
        context.system.eventStream.publish(ChannelStateChanged(self, nextStateData.channelId, peer, remoteNodeId, state, nextState, commitments_opt))
      }

      if (nextState == CLOSED) {
        // channel is closed, scheduling this actor for self destruction
        context.system.scheduler.scheduleOnce(10 seconds, self, Symbol("shutdown"))
      }
      if (nextState == OFFLINE) {
        // we can cancel the timer, we are not expecting anything when disconnected
        cancelTimer(RevocationTimeout.toString)
      }

      // if channel is private, we send the channel_update directly to remote
      // they need it "to learn the other end's forwarding parameters" (BOLT 7)
      (state, nextState, stateData, nextStateData) match {
        case (_, _, d1: DATA_NORMAL, d2: DATA_NORMAL) if !d1.commitments.announceChannel && !d1.buried && d2.buried =>
          // for a private channel, when the tx was just buried we need to send the channel_update to our peer (even if it didn't change)
          send(d2.channelUpdate)
        case (SYNCING, NORMAL, d1: DATA_NORMAL, d2: DATA_NORMAL) if !d1.commitments.announceChannel && d2.buried =>
          // otherwise if we're coming back online, we rebroadcast the latest channel_update
          // this makes sure that if the channel_update was missed, we have a chance to re-send it
          send(d2.channelUpdate)
        case (_, _, d1: DATA_NORMAL, d2: DATA_NORMAL) if !d1.commitments.announceChannel && d1.channelUpdate != d2.channelUpdate && d2.buried =>
          // otherwise, we only send it when it is different, and tx is already buried
          send(d2.channelUpdate)
        case _ => ()
      }

      (state, nextState, stateData, nextStateData) match {
        // ORDER MATTERS!
        case (WAIT_FOR_INIT_INTERNAL, OFFLINE, _, normal: DATA_NORMAL) =>
          Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
            log.debug("re-emitting channel_update={} enabled={} ", normal.channelUpdate, Announcements.isEnabled(normal.channelUpdate.channelFlags))
          }
          context.system.eventStream.publish(LocalChannelUpdate(self, normal.commitments.channelId, normal.shortChannelId, normal.commitments.remoteParams.nodeId, normal.channelAnnouncement, normal.channelUpdate, normal.commitments))
        case (_, _, d1: DATA_NORMAL, d2: DATA_NORMAL) if d1.channelUpdate == d2.channelUpdate && d1.channelAnnouncement == d2.channelAnnouncement =>
          // don't do anything if neither the channel_update nor the channel_announcement didn't change
          ()
        case (WAIT_FOR_FUNDING_LOCKED | NORMAL | OFFLINE | SYNCING, NORMAL | OFFLINE, _, normal: DATA_NORMAL) =>
          // when we do WAIT_FOR_FUNDING_LOCKED->NORMAL or NORMAL->NORMAL or SYNCING->NORMAL or NORMAL->OFFLINE, we send out the new channel_update (most of the time it will just be to enable/disable the channel)
          log.info("emitting channel_update={} enabled={} ", normal.channelUpdate, Announcements.isEnabled(normal.channelUpdate.channelFlags))
          context.system.eventStream.publish(LocalChannelUpdate(self, normal.commitments.channelId, normal.shortChannelId, normal.commitments.remoteParams.nodeId, normal.channelAnnouncement, normal.channelUpdate, normal.commitments))
        case (_, _, _: DATA_NORMAL, _: DATA_NORMAL) =>
          // in any other case (e.g. OFFLINE->SYNCING) we do nothing
          ()
        case (_, _, normal: DATA_NORMAL, _) =>
          // when we finally leave the NORMAL state (or OFFLINE with NORMAL data) to go to SHUTDOWN/NEGOTIATING/CLOSING/ERR*, we advertise the fact that channel can't be used for payments anymore
          // if the channel is private we don't really need to tell the counterparty because it is already aware that the channel is being closed
          context.system.eventStream.publish(LocalChannelDown(self, normal.commitments.channelId, normal.shortChannelId, normal.commitments.remoteParams.nodeId))
        case _ => ()
      }
  }

  onTransition {
    case state -> nextState if state != nextState =>
      if (state != WAIT_FOR_INIT_INTERNAL) Metrics.ChannelsCount.withTag(Tags.State, state.toString).decrement()
      if (nextState != WAIT_FOR_INIT_INTERNAL) Metrics.ChannelsCount.withTag(Tags.State, nextState.toString).increment()
  }

  onTransition {
    case _ -> CLOSING =>
      PendingCommandsDb.getSettlementCommands(nodeParams.db.pendingCommands, nextStateData.asInstanceOf[HasCommitments].channelId) match {
        case Nil =>
          log.debug("nothing to replay")
        case cmds =>
          log.info("replaying {} unacked fulfills/fails", cmds.size)
          cmds.foreach(self ! _) // they all have commit = false
      }
    case SYNCING -> (NORMAL | SHUTDOWN) =>
      PendingCommandsDb.getSettlementCommands(nodeParams.db.pendingCommands, nextStateData.asInstanceOf[HasCommitments].channelId) match {
        case Nil =>
          log.debug("nothing to replay")
        case cmds =>
          log.info("replaying {} unacked fulfills/fails", cmds.size)
          cmds.foreach(self ! _) // they all have commit = false
          self ! CMD_SIGN() // so we can sign all of them at once
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

  /**
   * This function is used to return feedback to user at channel opening
   */
  private def channelOpenReplyToUser(message: Either[ChannelOpenError, ChannelOpenResponse]): Unit = {
    val m = message match {
      case Left(LocalError(t)) => Status.Failure(t)
      case Left(RemoteError(e)) => Status.Failure(new RuntimeException(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}"))
      case Right(s) => s
    }
    origin_opt.foreach(_ ! m)
  }

  private def handleCurrentFeerate(c: CurrentFeerates, d: HasCommitments) = {
    val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, d.commitments.channelVersion, d.commitments.capacity, Some(c))
    val currentFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw
    val shouldUpdateFee = d.commitments.localParams.isFunder && nodeParams.onChainFeeConf.shouldUpdateFee(currentFeeratePerKw, networkFeeratePerKw)
    val shouldClose = !d.commitments.localParams.isFunder &&
      nodeParams.onChainFeeConf.feerateToleranceFor(d.commitments.remoteNodeId).isFeeDiffTooHigh(d.commitments.channelVersion, networkFeeratePerKw, currentFeeratePerKw) &&
      d.commitments.hasPendingOrProposedHtlcs // we close only if we have HTLCs potentially at risk
    if (shouldUpdateFee) {
      self ! CMD_UPDATE_FEE(networkFeeratePerKw, commit = true)
      stay
    } else if (shouldClose) {
      handleLocalError(FeerateTooDifferent(d.channelId, localFeeratePerKw = networkFeeratePerKw, remoteFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw), d, Some(c))
    } else {
      stay
    }
  }

  /**
   * This is used to check for the commitment fees when the channel is not operational but we have something at stake
   *
   * @param c the new feerates
   * @param d the channel commtiments
   * @return
   */
  private def handleOfflineFeerate(c: CurrentFeerates, d: HasCommitments) = {
    val networkFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, d.commitments.channelVersion, d.commitments.capacity, Some(c))
    val currentFeeratePerKw = d.commitments.localCommit.spec.feeratePerKw
    // if the network fees are too high we risk to not be able to confirm our current commitment
    val shouldClose = networkFeeratePerKw > currentFeeratePerKw &&
      nodeParams.onChainFeeConf.feerateToleranceFor(d.commitments.remoteNodeId).isFeeDiffTooHigh(d.commitments.channelVersion, networkFeeratePerKw, currentFeeratePerKw) &&
      d.commitments.hasPendingOrProposedHtlcs // we close only if we have HTLCs potentially at risk
    if (shouldClose) {
      if (nodeParams.onChainFeeConf.closeOnOfflineMismatch) {
        log.warning(s"closing OFFLINE channel due to fee mismatch: currentFeeratePerKw=$currentFeeratePerKw networkFeeratePerKw=$networkFeeratePerKw")
        handleLocalError(FeerateTooDifferent(d.channelId, localFeeratePerKw = currentFeeratePerKw, remoteFeeratePerKw = networkFeeratePerKw), d, Some(c))
      } else {
        log.warning(s"channel is OFFLINE but its fee mismatch is over the threshold: currentFeeratePerKw=$currentFeeratePerKw networkFeeratePerKw=$networkFeeratePerKw")
        stay
      }
    } else {
      stay
    }
  }

  private def handleFastClose(c: CloseCommand, channelId: ByteVector32) = {
    val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
    replyTo ! RES_SUCCESS(c, channelId)
    goto(CLOSED)
  }

  private def handleCommandSuccess(c: channel.Command, newData: Data) = {
    val replyTo_opt = c match {
      case hasOptionalReplyTo: HasOptionalReplyToCommand => hasOptionalReplyTo.replyTo_opt
      case hasReplyTo: HasReplyToCommand => if (hasReplyTo.replyTo == ActorRef.noSender) Some(sender) else Some(hasReplyTo.replyTo)
    }
    replyTo_opt.foreach { replyTo =>
      replyTo ! RES_SUCCESS(c, newData.channelId)
    }
    stay using newData
  }

  private def handleAddHtlcCommandError(c: CMD_ADD_HTLC, cause: ChannelException, channelUpdate: Option[ChannelUpdate]) = {
    log.warning(s"${cause.getMessage} while processing cmd=${c.getClass.getSimpleName} in state=$stateName")
    val replyTo = if (c.replyTo == ActorRef.noSender) sender else c.replyTo
    replyTo ! RES_ADD_FAILED(c, cause, channelUpdate)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, stateData, LocalError(cause), isFatal = false))
    stay
  }

  private def handleCommandError(cause: ChannelException, c: channel.Command) = {
    log.warning(s"${cause.getMessage} while processing cmd=${c.getClass.getSimpleName} in state=$stateName")
    val replyTo_opt = c match {
      case hasOptionalReplyTo: HasOptionalReplyToCommand => hasOptionalReplyTo.replyTo_opt
      case hasReplyTo: HasReplyToCommand => if (hasReplyTo.replyTo == ActorRef.noSender) Some(sender) else Some(hasReplyTo.replyTo)
    }
    replyTo_opt.foreach(replyTo => replyTo ! RES_FAILURE(c, cause))
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, stateData, LocalError(cause), isFatal = false))
    stay
  }

  private def watchFundingTx(commitments: Commitments, additionalKnownSpendingTxs: Set[ByteVector32] = Set.empty): Unit = {
    // TODO: should we wait for an acknowledgment from the watcher?
    val knownSpendingTxs = Set(commitments.localCommit.publishableTxs.commitTx.tx.txid, commitments.remoteCommit.txid) ++ commitments.remoteNextCommitInfo.left.toSeq.map(_.nextRemoteCommit.txid).toSet ++ additionalKnownSpendingTxs
    blockchain ! WatchFundingSpent(self, commitments.commitInput.outPoint.txid, commitments.commitInput.outPoint.index.toInt, knownSpendingTxs)
    // TODO: implement this? (not needed if we use a reasonable min_depth)
    //blockchain ! WatchLost(self, commitments.commitInput.outPoint.txid, nodeParams.minDepthBlocks, BITCOIN_FUNDING_LOST)
  }

  /**
   * When we are funder, we use this function to detect when our funding tx has been double-spent (by another transaction
   * that we made for some reason). If the funding tx has been double spent we can forget about the channel.
   */
  private def checkDoubleSpent(fundingTx: Transaction): Unit = {
    log.debug(s"checking status of funding tx txid=${fundingTx.txid}")
    wallet.doubleSpent(fundingTx).onComplete {
      case Success(true) =>
        log.warning(s"funding tx has been double spent! fundingTxid=${fundingTx.txid} fundingTx=$fundingTx")
        self ! BITCOIN_FUNDING_PUBLISH_FAILED
      case Success(false) => ()
      case Failure(t) => log.error(t, s"error while testing status of funding tx fundingTxid=${fundingTx.txid}: ")
    }
  }

  private def handleGetFundingTx(getTxResponse: GetTxWithMetaResponse, waitingSinceBlock: Long, fundingTx_opt: Option[Transaction]) = {
    import getTxResponse._
    tx_opt match {
      case Some(_) => () // the funding tx exists, nothing to do
      case None =>
        fundingTx_opt match {
          // ORDER MATTERS!!
          case Some(fundingTx) =>
            // if we are funder, we never give up
            log.info(s"republishing the funding tx...")
            txPublisher ! PublishRawTx(fundingTx, fundingTx.txIn.head.outPoint, "funding-tx", None)
            // we also check if the funding tx has been double-spent
            checkDoubleSpent(fundingTx)
            context.system.scheduler.scheduleOnce(1 day, blockchain.toClassic, GetTxWithMeta(self, txid))
          case None if (nodeParams.currentBlockHeight - waitingSinceBlock) > FUNDING_TIMEOUT_FUNDEE =>
            // if we are fundee, we give up after some time
            log.warning(s"funding tx hasn't been published in ${nodeParams.currentBlockHeight - waitingSinceBlock} blocks")
            self ! BITCOIN_FUNDING_TIMEOUT
          case None =>
            // let's wait a little longer
            log.info(s"funding tx still hasn't been published in ${nodeParams.currentBlockHeight - waitingSinceBlock} blocks, will wait ${FUNDING_TIMEOUT_FUNDEE - nodeParams.currentBlockHeight + waitingSinceBlock} more blocks...")
            context.system.scheduler.scheduleOnce(1 day, blockchain.toClassic, GetTxWithMeta(self, txid))
        }
    }
    stay
  }

  private def handleFundingPublishFailed(d: HasCommitments) = {
    log.error(s"failed to publish funding tx")
    val exc = ChannelFundingError(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    // NB: we don't use the handleLocalError handler because it would result in the commit tx being published, which we don't want:
    // implementation *guarantees* that in case of BITCOIN_FUNDING_PUBLISH_FAILED, the funding tx hasn't and will never be published, so we can close the channel right away
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, stateData, LocalError(exc), isFatal = true))
    goto(CLOSED) sending error
  }

  private def handleFundingTimeout(d: HasCommitments) = {
    log.warning(s"funding tx hasn't been confirmed in time, cancelling channel delay=$FUNDING_TIMEOUT_FUNDEE")
    val exc = FundingTxTimedout(d.channelId)
    val error = Error(d.channelId, exc.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, stateData, LocalError(exc), isFatal = true))
    goto(CLOSED) sending error
  }

  private def handleRevocationTimeout(revocationTimeout: RevocationTimeout, d: HasCommitments) = {
    d.commitments.remoteNextCommitInfo match {
      case Left(waitingForRevocation) if revocationTimeout.remoteCommitNumber + 1 == waitingForRevocation.nextRemoteCommit.index =>
        log.warning(s"waited for too long for a revocation to remoteCommitNumber=${revocationTimeout.remoteCommitNumber}, disconnecting")
        revocationTimeout.peer ! Peer.Disconnect(remoteNodeId)
      case _ => ()
    }
    stay
  }

  private def handleAddDisconnected(c: CMD_ADD_HTLC, d: DATA_NORMAL) = {
    log.info(s"rejecting htlc request in state=$stateName")
    // in order to reduce gossip spam, we don't disable the channel right away when disconnected
    // we will only emit a new channel_update with the disable flag set if someone tries to use that channel
    if (Announcements.isEnabled(d.channelUpdate.channelFlags)) {
      // if the channel isn't disabled we generate a new channel_update
      log.info("updating channel_update announcement (reason=disabled)")
      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, remoteNodeId, d.shortChannelId, d.channelUpdate.cltvExpiryDelta, d.channelUpdate.htlcMinimumMsat, d.channelUpdate.feeBaseMsat, d.channelUpdate.feeProportionalMillionths, d.commitments.capacity.toMilliSatoshi, enable = false)
      // then we update the state and replay the request
      self forward c
      // we use goto to fire transitions
      goto(stateName) using d.copy(channelUpdate = channelUpdate)
    } else {
      // channel is already disabled, we reply to the request
      val error = ChannelUnavailable(d.channelId)
      handleAddHtlcCommandError(c, error, Some(d.channelUpdate)) // can happen if we are in OFFLINE or SYNCING state (channelUpdate will have enable=false)
    }
  }

  private def handleNewBlock(c: CurrentBlockCount, d: HasCommitments) = {
    val timedOutOutgoing = d.commitments.timedOutOutgoingHtlcs(c.blockCount)
    val almostTimedOutIncoming = d.commitments.almostTimedOutIncomingHtlcs(c.blockCount, nodeParams.fulfillSafetyBeforeTimeout)
    if (timedOutOutgoing.nonEmpty) {
      // Downstream timed out.
      handleLocalError(HtlcsTimedoutDownstream(d.channelId, timedOutOutgoing), d, Some(c))
    } else if (almostTimedOutIncoming.nonEmpty) {
      // Upstream is close to timing out, we need to test if we have funds at risk: htlcs for which we know the preimage
      // that are still in our commitment (upstream will try to timeout on-chain).
      val relayedFulfills = d.commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.id }.toSet
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
          stay
        }
      }
    } else {
      stay
    }
  }

  private def handleLocalError(cause: Throwable, d: Data, msg: Option[Any]) = {
    cause match {
      case _: ForcedLocalCommit => log.warning(s"force-closing channel at user request")
      case _ if stateName == WAIT_FOR_OPEN_CHANNEL => log.warning(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
      case _ => log.error(s"${cause.getMessage} while processing msg=${msg.getOrElse("n/a").getClass.getSimpleName} in state=$stateName")
    }
    cause match {
      case _: ChannelException => ()
      case _ => log.error(cause, s"msg=${msg.getOrElse("n/a")} stateData=$stateData")
    }
    val error = Error(d.channelId, cause.getMessage)
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, stateData, LocalError(cause), isFatal = true))

    d match {
      case dd: HasCommitments if Closing.nothingAtStake(dd) => goto(CLOSED)
      case negotiating@DATA_NEGOTIATING(_, _, _, _, Some(bestUnpublishedClosingTx)) =>
        log.info(s"we have a valid closing tx, publishing it instead of our commitment: closingTxId=${bestUnpublishedClosingTx.tx.txid}")
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case dd: HasCommitments => spendLocalCurrent(dd) sending error // otherwise we use our current commitment
      case _ => goto(CLOSED) sending error // when there is no commitment yet, we just send an error to our peer and go to CLOSED state
    }
  }

  private def handleRemoteError(e: Error, d: Data) = {
    // see BOLT 1: only print out data verbatim if is composed of printable ASCII characters
    log.error(s"peer sent error: ascii='${e.toAscii}' bin=${e.data.toHex}")
    context.system.eventStream.publish(ChannelErrorOccurred(self, stateData.channelId, remoteNodeId, stateData, RemoteError(e), isFatal = true))

    d match {
      case _: DATA_CLOSING => stay // nothing to do, there is already a spending tx published
      case negotiating@DATA_NEGOTIATING(_, _, _, _, Some(bestUnpublishedClosingTx)) =>
        // if we were in the process of closing and already received a closing sig from the counterparty, it's always better to use that
        handleMutualClose(bestUnpublishedClosingTx, Left(negotiating))
      case hasCommitments: HasCommitments => spendLocalCurrent(hasCommitments) // NB: we publish the commitment even if we have nothing at stake (in a dataloss situation our peer will send us an error just for that)
      case _ => goto(CLOSED) // when there is no commitment yet, we just go to CLOSED state in case an error occurs
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

  private def handleMutualClose(closingTx: ClosingTx, d: Either[DATA_NEGOTIATING, DATA_CLOSING]) = {
    log.info(s"closing tx published: closingTxId=${closingTx.tx.txid}")
    val nextData = d match {
      case Left(negotiating) => DATA_CLOSING(negotiating.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), mutualClosePublished = closingTx :: Nil)
      case Right(closing) => closing.copy(mutualClosePublished = closing.mutualClosePublished :+ closingTx)
    }
    goto(CLOSING) using nextData storing() calling doPublish(closingTx)
  }

  private def doPublish(closingTx: ClosingTx): Unit = {
    txPublisher ! PublishRawTx(closingTx, None)
    blockchain ! WatchTxConfirmed(self, closingTx.tx.txid, nodeParams.minDepthBlocks)
  }

  private def spendLocalCurrent(d: HasCommitments) = {
    val outdatedCommitment = d match {
      case _: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => true
      case closing: DATA_CLOSING if closing.futureRemoteCommitPublished.isDefined => true
      case _ => false
    }
    if (outdatedCommitment) {
      log.warning("we have an outdated commitment: will not publish our local tx")
      stay
    } else {
      val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx
      val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, d.commitments, commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
      val nextData = d match {
        case closing: DATA_CLOSING => closing.copy(localCommitPublished = Some(localCommitPublished))
        case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), localCommitPublished = Some(localCommitPublished))
        case waitForFundingConfirmed: DATA_WAIT_FOR_FUNDING_CONFIRMED => DATA_CLOSING(d.commitments, fundingTx = waitForFundingConfirmed.fundingTx, waitingSinceBlock = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished))
        case _ => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, localCommitPublished = Some(localCommitPublished))
      }
      goto(CLOSING) using nextData storing() calling doPublish(localCommitPublished, d.commitments)
    }
  }

  /**
   * This helper method will publish txs only if they haven't yet reached minDepth
   */
  private def publishIfNeeded(txs: Iterable[PublishTx], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(publishTx => Closing.inputAlreadySpent(publishTx.input, irrevocablySpent))
    process.foreach { publishTx => txPublisher ! publishTx }
    skip.foreach(publishTx => log.info("no need to republish tx spending {}:{}, it has already been confirmed", publishTx.input.txid, publishTx.input.index))
  }

  /**
   * This helper method will watch txs only if they haven't yet reached minDepth
   */
  private def watchConfirmedIfNeeded(txs: Iterable[Transaction], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    val (skip, process) = txs.partition(Closing.inputsAlreadySpent(_, irrevocablySpent))
    process.foreach(tx => blockchain ! WatchTxConfirmed(self, tx.txid, nodeParams.minDepthBlocks))
    skip.foreach(tx => log.info(s"no need to watch txid=${tx.txid}, it has already been confirmed"))
  }

  /**
   * This helper method will watch txs only if the utxo they spend hasn't already been irrevocably spent
   *
   * @param parentTx transaction which outputs will be watched
   * @param outputs  outputs that will be watched. They must be a subset of the outputs of the `parentTx`
   */
  private def watchSpentIfNeeded(parentTx: Transaction, outputs: Iterable[OutPoint], irrevocablySpent: Map[OutPoint, Transaction]): Unit = {
    outputs.foreach { output =>
      require(output.txid == parentTx.txid && output.index < parentTx.txOut.size, s"output doesn't belong to the given parentTx: output=${output.txid}:${output.index} (expected txid=${parentTx.txid} index < ${parentTx.txOut.size})")
    }
    val (skip, process) = outputs.partition(irrevocablySpent.contains)
    process.foreach(output => blockchain ! WatchOutputSpent(self, parentTx.txid, output.index.toInt, Set.empty))
    skip.foreach(output => log.info(s"no need to watch output=${output.txid}:${output.index}, it has already been spent by txid=${irrevocablySpent.get(output).map(_.txid)}"))
  }

  private def doPublish(localCommitPublished: LocalCommitPublished, commitments: Commitments): Unit = {
    import localCommitPublished._

    val commitInput = commitments.commitInput.outPoint
    val publishQueue = commitments.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat =>
        val redeemableHtlcTxs = htlcTxs.values.flatten.map(tx => PublishRawTx(tx, Some(commitTx.txid)))
        List(PublishRawTx(commitTx, commitInput, "commit-tx", None)) ++ (claimMainDelayedOutputTx.map(tx => PublishRawTx(tx, None)) ++ redeemableHtlcTxs ++ claimHtlcDelayedTxs.map(tx => PublishRawTx(tx, None)))
      case Transactions.AnchorOutputsCommitmentFormat =>
        val claimLocalAnchor = claimAnchorTxs.collect { case tx: Transactions.ClaimLocalAnchorOutputTx => PublishReplaceableTx(tx, commitments) }
        val redeemableHtlcTxs = htlcTxs.values.collect { case Some(tx) => PublishReplaceableTx(tx, commitments) }
        List(PublishRawTx(commitTx, commitInput, "commit-tx", None)) ++ claimLocalAnchor ++ claimMainDelayedOutputTx.map(tx => PublishRawTx(tx, None)) ++ redeemableHtlcTxs ++ claimHtlcDelayedTxs.map(tx => PublishRawTx(tx, None))
    }
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainDelayedOutputTx.map(_.tx) ++ claimHtlcDelayedTxs.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = htlcTxs.keys
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  private def handleRemoteSpentCurrent(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their current commit in txid=${commitTx.txid}")
    require(commitTx.txid == d.commitments.remoteCommit.txid, "txid mismatch")

    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, d.commitments, d.commitments.remoteCommit, commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(remoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), remoteCommitPublished = Some(remoteCommitPublished))
      case waitForFundingConfirmed: DATA_WAIT_FOR_FUNDING_CONFIRMED => DATA_CLOSING(d.commitments, fundingTx = waitForFundingConfirmed.fundingTx, waitingSinceBlock = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, remoteCommitPublished = Some(remoteCommitPublished))
      case _ => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, remoteCommitPublished = Some(remoteCommitPublished))
    }
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished)
  }

  private def handleRemoteSpentFuture(commitTx: Transaction, d: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT) = {
    log.warning(s"they published their future commit (because we asked them to) in txid=${commitTx.txid}")
    d.commitments.channelVersion match {
      case v if v.paysDirectlyToWallet =>
        val remoteCommitPublished = RemoteCommitPublished(commitTx, None, Map.empty, List.empty, Map.empty)
        val nextData = DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, Nil, futureRemoteCommitPublished = Some(remoteCommitPublished))
        goto(CLOSING) using nextData storing() // we don't need to claim our main output in the remote commit because it already spends to our wallet address
      case _ =>
        val remotePerCommitmentPoint = d.remoteChannelReestablish.myCurrentPerCommitmentPoint
        val remoteCommitPublished = Helpers.Closing.claimRemoteCommitMainOutput(keyManager, d.commitments, remotePerCommitmentPoint, commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
        val nextData = DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, Nil, futureRemoteCommitPublished = Some(remoteCommitPublished))
        goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished)
    }
  }

  private def handleRemoteSpentNext(commitTx: Transaction, d: HasCommitments) = {
    log.warning(s"they published their next commit in txid=${commitTx.txid}")
    require(d.commitments.remoteNextCommitInfo.isLeft, "next remote commit must be defined")
    val remoteCommit = d.commitments.remoteNextCommitInfo.left.get.nextRemoteCommit
    require(commitTx.txid == remoteCommit.txid, "txid mismatch")

    val remoteCommitPublished = Helpers.Closing.claimRemoteCommitTxOutputs(keyManager, d.commitments, remoteCommit, commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)
    val nextData = d match {
      case closing: DATA_CLOSING => closing.copy(nextRemoteCommitPublished = Some(remoteCommitPublished))
      case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), nextRemoteCommitPublished = Some(remoteCommitPublished))
      // NB: if there is a next commitment, we can't be in DATA_WAIT_FOR_FUNDING_CONFIRMED so we don't have the case where fundingTx is defined
      case _ => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, nextRemoteCommitPublished = Some(remoteCommitPublished))
    }
    goto(CLOSING) using nextData storing() calling doPublish(remoteCommitPublished)
  }

  private def doPublish(remoteCommitPublished: RemoteCommitPublished): Unit = {
    import remoteCommitPublished._

    val publishQueue = (claimMainOutputTx ++ claimHtlcTxs.values.flatten).map(tx => PublishRawTx(tx, None))
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = claimHtlcTxs.keys
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  private def handleRemoteSpentOther(tx: Transaction, d: HasCommitments) = {
    log.warning(s"funding tx spent in txid=${tx.txid}")
    Helpers.Closing.claimRevokedRemoteCommitTxOutputs(keyManager, d.commitments, tx, nodeParams.db.channels, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets) match {
      case Some(revokedCommitPublished) =>
        log.warning(s"txid=${tx.txid} was a revoked commitment, publishing the penalty tx")
        val exc = FundingTxSpent(d.channelId, tx)
        val error = Error(d.channelId, exc.getMessage)

        val nextData = d match {
          case closing: DATA_CLOSING => closing.copy(revokedCommitPublished = closing.revokedCommitPublished :+ revokedCommitPublished)
          case negotiating: DATA_NEGOTIATING => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, negotiating.closingTxProposed.flatten.map(_.unsignedTx), revokedCommitPublished = revokedCommitPublished :: Nil)
          // NB: if there is a revoked commitment, we can't be in DATA_WAIT_FOR_FUNDING_CONFIRMED so we don't have the case where fundingTx is defined
          case _ => DATA_CLOSING(d.commitments, fundingTx = None, waitingSinceBlock = nodeParams.currentBlockHeight, mutualCloseProposed = Nil, revokedCommitPublished = revokedCommitPublished :: Nil)
        }
        goto(CLOSING) using nextData storing() calling doPublish(revokedCommitPublished) sending error
      case None =>
        // the published tx was neither their current commitment nor a revoked one
        log.error(s"couldn't identify txid=${tx.txid}, something very bad is going on!!!")
        goto(ERR_INFORMATION_LEAK)
    }
  }

  private def doPublish(revokedCommitPublished: RevokedCommitPublished): Unit = {
    import revokedCommitPublished._

    val publishQueue = (claimMainOutputTx ++ mainPenaltyTx ++ htlcPenaltyTxs ++ claimHtlcDelayedPenaltyTxs).map(tx => PublishRawTx(tx, None))
    publishIfNeeded(publishQueue, irrevocablySpent)

    // we watch:
    // - the commitment tx itself, so that we can handle the case where we don't have any outputs
    // - 'final txs' that send funds to our wallet and that spend outputs that only us control
    val watchConfirmedQueue = List(commitTx) ++ claimMainOutputTx.map(_.tx)
    watchConfirmedIfNeeded(watchConfirmedQueue, irrevocablySpent)

    // we watch outputs of the commitment tx that both parties may spend
    val watchSpentQueue = (mainPenaltyTx ++ htlcPenaltyTxs).map(_.input.outPoint)
    watchSpentIfNeeded(commitTx, watchSpentQueue, irrevocablySpent)
  }

  private def handleInformationLeak(tx: Transaction, d: HasCommitments) = {
    // this is never supposed to happen !!
    log.error(s"our funding tx ${d.commitments.commitInput.outPoint.txid} was spent by txid=${tx.txid} !!")
    val exc = FundingTxSpent(d.channelId, tx)
    val error = Error(d.channelId, exc.getMessage)

    // let's try to spend our current local tx
    val commitTx = d.commitments.localCommit.publishableTxs.commitTx.tx
    val localCommitPublished = Helpers.Closing.claimCurrentLocalCommitTxOutputs(keyManager, d.commitments, commitTx, nodeParams.onChainFeeConf.feeEstimator, nodeParams.onChainFeeConf.feeTargets)

    goto(ERR_INFORMATION_LEAK) calling doPublish(localCommitPublished, d.commitments) sending error
  }

  private def handleSync(channelReestablish: ChannelReestablish, d: HasCommitments): (Commitments, Queue[LightningMessage]) = {
    var sendQueue = Queue.empty[LightningMessage]
    // first we clean up unacknowledged updates
    log.debug("discarding proposed OUT: {}", d.commitments.localChanges.proposed.map(Commitments.msg2String(_)).mkString(","))
    log.debug("discarding proposed IN: {}", d.commitments.remoteChanges.proposed.map(Commitments.msg2String(_)).mkString(","))
    val commitments1 = d.commitments.copy(
      localChanges = d.commitments.localChanges.copy(proposed = Nil),
      remoteChanges = d.commitments.remoteChanges.copy(proposed = Nil),
      localNextHtlcId = d.commitments.localNextHtlcId - d.commitments.localChanges.proposed.collect { case u: UpdateAddHtlc => u }.size,
      remoteNextHtlcId = d.commitments.remoteNextHtlcId - d.commitments.remoteChanges.proposed.collect { case u: UpdateAddHtlc => u }.size)
    log.debug(s"localNextHtlcId=${d.commitments.localNextHtlcId}->${commitments1.localNextHtlcId}")
    log.debug(s"remoteNextHtlcId=${d.commitments.remoteNextHtlcId}->${commitments1.remoteNextHtlcId}")

    def resendRevocation(): Unit = {
      // let's see the state of remote sigs
      if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber) {
        // nothing to do
      } else if (commitments1.localCommit.index == channelReestablish.nextRemoteRevocationNumber + 1) {
        // our last revocation got lost, let's resend it
        log.debug("re-sending last revocation")
        val channelKeyPath = keyManager.keyPath(d.commitments.localParams, d.commitments.channelVersion)
        val localPerCommitmentSecret = keyManager.commitmentSecret(channelKeyPath, d.commitments.localCommit.index - 1)
        val localNextPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, d.commitments.localCommit.index + 1)
        val revocation = RevokeAndAck(
          channelId = commitments1.channelId,
          perCommitmentSecret = localPerCommitmentSecret,
          nextPerCommitmentPoint = localNextPerCommitmentPoint
        )
        sendQueue = sendQueue :+ revocation
      } else throw RevocationSyncError(d.channelId)
    }

    // re-sending sig/rev (in the right order)
    commitments1.remoteNextCommitInfo match {
      case Left(waitingForRevocation) if waitingForRevocation.nextRemoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber =>
        // we had sent a new sig and were waiting for their revocation
        // they had received the new sig but their revocation was lost during the disconnection
        // they will send us the revocation, nothing to do here
        log.debug("waiting for them to re-send their last revocation")
        resendRevocation()
      case Left(waitingForRevocation) if waitingForRevocation.nextRemoteCommit.index == channelReestablish.nextLocalCommitmentNumber =>
        // we had sent a new sig and were waiting for their revocation
        // they didn't receive the new sig because of the disconnection
        // we just resend the same updates and the same sig

        val revWasSentLast = commitments1.localCommit.index > waitingForRevocation.sentAfterLocalCommitIndex
        if (!revWasSentLast) resendRevocation()

        log.debug("re-sending previously local signed changes: {}", commitments1.localChanges.signed.map(Commitments.msg2String(_)).mkString(","))
        commitments1.localChanges.signed.foreach(revocation => sendQueue = sendQueue :+ revocation)
        log.debug("re-sending the exact same previous sig")
        sendQueue = sendQueue :+ waitingForRevocation.sent

        if (revWasSentLast) resendRevocation()
      case Right(_) if commitments1.remoteCommit.index + 1 == channelReestablish.nextLocalCommitmentNumber =>
        // there wasn't any sig in-flight when the disconnection occurred
        resendRevocation()
      case _ => throw CommitmentSyncError(d.channelId)
    }

    commitments1.remoteNextCommitInfo match {
      case Left(_) =>
        // we expect them to (re-)send the revocation immediately
        setTimer(RevocationTimeout.toString, RevocationTimeout(commitments1.remoteCommit.index, peer), timeout = nodeParams.revocationTimeout, repeat = false)
      case _ => ()
    }

    // have I something to sign?
    if (Commitments.localHasChanges(commitments1)) {
      self ! CMD_SIGN()
    }

    (commitments1, sendQueue)
  }

  /**
   * This helper function runs the state's default event handlers, and react to exceptions by unilaterally closing the channel
   */
  private def handleExceptions(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      try {
        s(event)
      } catch {
        case t: SQLException =>
          log.error(t, "fatal database error\n")
          sys.exit(-2)
        case t: Throwable => handleLocalError(t, event.stateData, None)
      }
  }

  private def feePaid(fee: Satoshi, tx: Transaction, desc: String, channelId: ByteVector32): Unit = Try { // this may fail with an NPE in tests because context has been cleaned up, but it's not a big deal
    log.info(s"paid feeSatoshi=${fee.toLong} for txid=${tx.txid} desc=$desc")
    context.system.eventStream.publish(NetworkFeePaid(self, remoteNodeId, channelId, tx, fee, desc))
  }

  implicit private def state2mystate(state: FSM.State[fr.acinq.eclair.channel.State, Data]): MyState = MyState(state)

  case class MyState(state: FSM.State[fr.acinq.eclair.channel.State, Data]) {

    def storing(unused: Unit = ()): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      state.stateData match {
        case d: HasCommitments =>
          log.debug("updating database record for channelId={}", d.channelId)
          nodeParams.db.channels.addOrUpdateChannel(d)
          context.system.eventStream.publish(ChannelPersisted(self, remoteNodeId, d.channelId, d))
          state
        case _ =>
          log.error(s"can't store data=${state.stateData} in state=${state.stateName}")
          state
      }
    }

    def sending(msgs: Seq[LightningMessage]): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      msgs.foreach(sending)
      state
    }

    def sending(msg: LightningMessage): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      send(msg)
      state
    }

    /**
     * This method allows performing actions during the transition, e.g. after a call to [[MyState.storing]]. This is
     * particularly useful to publish transactions only after we are sure that the state has been persisted.
     */
    def calling(f: => Unit): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      f
      state
    }

    /**
     * We don't acknowledge htlc commands immediately, because we send them to the channel as soon as possible, and they
     * may not yet have been written to the database.
     *
     * @param cmd fail/fulfill command that has been processed
     */
    def acking(channelId: ByteVector32, cmd: HtlcSettlementCommand): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      log.debug("scheduling acknowledgement of cmd id={}", cmd.id)
      context.system.scheduler.scheduleOnce(10 seconds)(PendingCommandsDb.ackSettlementCommand(nodeParams.db.pendingCommands, channelId, cmd))(context.system.dispatcher)
      state
    }

    def acking(updates: List[UpdateMessage]): FSM.State[fr.acinq.eclair.channel.State, Data] = {
      log.debug("scheduling acknowledgement of cmds ids={}", updates.collect { case s: HtlcSettlementMessage => s.id }.mkString(","))
      context.system.scheduler.scheduleOnce(10 seconds)(PendingCommandsDb.ackSettlementCommands(nodeParams.db.pendingCommands, updates))(context.system.dispatcher)
      state
    }

  }

  private def send(msg: LightningMessage): Unit = {
    peer ! OutgoingMessage(msg, activeConnection)
  }

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    val id = currentMessage match {
      case INPUT_RESTORED(data) => data.channelId
      case _ => stateData.channelId
    }
    Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(id))
  }

  // we let the peer decide what to do
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Escalate }

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    KamonExt.time(ProcessMessage.withTag("MessageType", msg.getClass.getSimpleName)) {
      super.aroundReceive(receive, msg)
    }
  }

  initialize()

}
