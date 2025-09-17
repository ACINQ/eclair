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

package fr.acinq.eclair.io

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, ClassicActorRefOps}
import akka.actor.{Actor, ActorContext, ActorRef, ExtendedActorSystem, FSM, OneForOneStrategy, PossiblyHarmful, Props, Status, SupervisorStrategy, typed}
import akka.event.Logging.MDC
import akka.event.{BusLogging, DiagnosticLoggingAdapter}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong, TxId}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, CurrentFeerates, OnChainChannelFunder, OnChainPubkeyCache}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.keymanager.ChannelKeys
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.MessageRelay.Status
import fr.acinq.eclair.io.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.io.OpenChannelInterceptor.{OpenChannelInitiator, OpenChannelNonInitiator}
import fr.acinq.eclair.io.PeerConnection.KillReason
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.payment.{OnTheFlyFundingPaymentRelayed, PaymentRelayed}
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs.createBadOnionFailure
import fr.acinq.eclair.wire.protocol.{AddFeeCredit, ChannelTlv, CurrentFeeCredit, Error, FailureReason, HasChannelId, HasTemporaryChannelId, LightningMessage, LiquidityAds, NodeAddress, OnTheFlyFundingFailureMessage, OnionMessage, OnionRoutingPacket, PeerStorageRetrieval, PeerStorageStore, RecommendedFeerates, RoutingMessage, SpliceInit, TlvStream, TxAbort, UnknownMessage, Warning, WillAddHtlc, WillFailHtlc, WillFailMalformedHtlc}
import scodec.bits.ByteVector

/**
 * This actor represents a logical peer. There is one [[Peer]] per unique remote node id at all time.
 *
 * The [[Peer]] actor is mostly in charge of managing channels, stays alive when the peer is disconnected, and only dies
 * when there are no more channels.
 *
 * Everytime a new connection is established, it is sent to the [[Peer]] and replaces the previous one.
 *
 * Created by PM on 26/08/2016.
 */
class Peer(val nodeParams: NodeParams,
           remoteNodeId: PublicKey,
           wallet: OnChainPubkeyCache,
           channelFactory: Peer.ChannelFactory,
           switchboard: ActorRef,
           register: ActorRef,
           router: typed.ActorRef[Router.GetNodeId],
           pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command]) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  private var pendingOnTheFlyFunding = Map.empty[ByteVector32, OnTheFlyFunding.Pending]
  private var feeCredit = Option.empty[MilliSatoshi]

  context.system.eventStream.subscribe(self, classOf[CurrentFeerates])
  context.system.eventStream.subscribe(self, classOf[CurrentBlockHeight])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  startWith(INSTANTIATING, Nothing)

  when(INSTANTIATING) {
    case Event(init: Init, _) =>
      pendingOnTheFlyFunding = init.pendingOnTheFlyFunding
      val channels = init.storedChannels.map { state =>
        val channelKeys = nodeParams.channelKeyManager.channelKeys(state.channelParams.channelConfig, state.channelParams.localParams.fundingKeyPath)
        val channel = spawnChannel(channelKeys)
        channel ! INPUT_RESTORED(state)
        FinalChannelId(state.channelId) -> channel
      }.toMap
      context.system.eventStream.publish(PeerCreated(self, remoteNodeId))
      val peerStorageData = if (nodeParams.features.hasFeature(Features.ProvideStorage)) {
        nodeParams.db.peers.getStorage(remoteNodeId)
      } else {
        None
      }
      // When we restart, we will attempt to reconnect right away, but then we'll wait.
      // We don't fetch our peer's features from the DB: if the connection succeeds, we will get them from their init message, which saves a DB call.
      goto(DISCONNECTED) using DisconnectedData(channels, activeChannels = Set.empty, PeerStorage(peerStorageData, written = true), remoteFeatures_opt = None)
  }

  when(DISCONNECTED) {
    case Event(p: Peer.Connect, _) =>
      reconnectionTask forward p
      stay()

    case Event(connectionReady: PeerConnection.ConnectionReady, d: DisconnectedData) =>
      gotoConnected(connectionReady, d.channels.map { case (k: ChannelId, v) => (k, v) }, d.activeChannels, d.peerStorage)

    case Event(ChannelTerminated(actor), d: DisconnectedData) =>
      val channels1 = if (d.channels.values.toSet.contains(actor)) {
        // we have at most 2 ids: a TemporaryChannelId and a FinalChannelId
        val channelIds = d.channels.filter(_._2 == actor).keys
        log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
        d.channels -- channelIds
      } else {
        d.channels
      }
      if (channels1.isEmpty && canForgetPendingOnTheFlyFunding()) {
        log.info("that was the last open channel")
        context.system.eventStream.publish(LastChannelClosed(self, remoteNodeId))
        // We have no existing channels or pending signed transaction, we can forget about this peer.
        stopPeer(d.peerStorage)
      } else {
        stay() using d.copy(channels = channels1)
      }

    case Event(ConnectionDown(_), d: DisconnectedData) =>
      Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
        log.debug("connection lost while negotiating connection")
      }
      if (d.channels.isEmpty && canForgetPendingOnTheFlyFunding()) {
        // We have no existing channels or pending signed transaction, we can forget about this peer.
        stopPeer(d.peerStorage)
      } else {
        stay()
      }

    // This event is usually handled while we're connected, but if our peer disconnects right when we're emitting this,
    // we still want to record the channelId mapping.
    case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: DisconnectedData) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      stay() using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

    case Event(e: SpawnChannelInitiator, _) =>
      e.replyTo ! OpenChannelResponse.Disconnected
      stay()

    case Event(_: SpawnChannelNonInitiator, _) => stay() // we got disconnected before creating the channel actor

    case Event(_: LightningMessage, _) => stay() // we probably just got disconnected and that's the last messages we received

    case Event(WritePeerStorage, d: DisconnectedData) =>
      d.peerStorage.data.foreach(nodeParams.db.peers.updateStorage(remoteNodeId, _))
      stay() using d.copy(peerStorage = d.peerStorage.copy(written = true))

    case Event(e: ChannelReadyForPayments, d: DisconnectedData) =>
      if (!d.peerStorage.written && !isTimerActive(WritePeerStorageTimerKey)) {
        startSingleTimer(WritePeerStorageTimerKey, WritePeerStorage, nodeParams.peerStorageConfig.getWriteDelay(remoteNodeId, d.remoteFeatures_opt.map(_.features)))
      }
      val remoteFeatures_opt = d.remoteFeatures_opt match {
        case Some(remoteFeatures) if !remoteFeatures.written =>
          // We have a channel, so we can write to the DB without any DoS risk.
          nodeParams.db.peers.addOrUpdatePeerFeatures(remoteNodeId, remoteFeatures.features)
          Some(remoteFeatures.copy(written = true))
        case _ => d.remoteFeatures_opt
      }
      stay() using d.copy(activeChannels = d.activeChannels + e.channelId, remoteFeatures_opt = remoteFeatures_opt)

    case Event(e: LocalChannelDown, d: DisconnectedData) =>
      stay() using d.copy(activeChannels = d.activeChannels - e.channelId)
  }

  when(CONNECTED) {
    dropStaleMessages {
      case Event(c: Peer.Connect, d: ConnectedData) =>
        c.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(d.peerConnection, self)
        stay()

      case Event(Peer.OutgoingMessage(msg, peerConnection), d: ConnectedData) if peerConnection == d.peerConnection => // this is an outgoing message, but we need to make sure that this is for the current active connection
        logMessage(msg, "OUT")
        d.peerConnection forward msg
        stay()

      case Event(warning: Warning, _: ConnectedData) =>
        log.warning("peer sent warning: {}", warning.toAscii)
        // NB: we don't forward warnings to the channel actors, they shouldn't take any automatic action.
        // It's up to the node operator to decide what to do to address the warning.
        stay()

      case Event(err@Error(channelId, reason, _), d: ConnectedData) if channelId == CHANNELID_ZERO =>
        log.error(s"connection-level error, failing all channels! reason=${new String(reason.toArray)}")
        context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Info, s"$remoteNodeId sent us a connection-level error, closing all channels (reason=${new String(reason.toArray)})"))
        d.channels.values.toSet[ActorRef].foreach(_ forward err) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        d.peerConnection ! PeerConnection.Kill(KillReason.AllChannelsFail)
        stay()

      case Event(err: Error, d: ConnectedData) =>
        // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
        d.channels.get(FinalChannelId(err.channelId)).orElse(d.channels.get(TemporaryChannelId(err.channelId))) match {
          case Some(channel) => channel forward err
          case None => () // let's not create a ping-pong of error messages here
        }
        stay()

      case Event(c: Peer.OpenChannel, d: ConnectedData) =>
        openChannelInterceptor ! OpenChannelInitiator(sender().toTyped, remoteNodeId, c, d.localFeatures, d.remoteFeatures)
        stay()

      case Event(SpawnChannelInitiator(replyTo, c, channelConfig, channelType, localParams), d: ConnectedData) =>
        val channelKeys = nodeParams.channelKeyManager.channelKeys(channelConfig, localParams.fundingKeyPath)
        val channel = spawnChannel(channelKeys)
        context.system.scheduler.scheduleOnce(c.timeout_opt.map(_.duration).getOrElse(nodeParams.channelConf.channelFundingTimeout), channel, Channel.TickChannelOpenTimeout)(context.dispatcher)
        val dualFunded = Features.canUseFeature(d.localFeatures, d.remoteFeatures, Features.DualFunding)
        val requireConfirmedInputs = c.requireConfirmedInputsOverride_opt.getOrElse(nodeParams.channelConf.requireConfirmedInputsForDualFunding)
        val temporaryChannelId = if (dualFunded) {
          Helpers.dualFundedTemporaryChannelId(channelKeys)
        } else {
          randomBytes32()
        }
        val init = INPUT_INIT_CHANNEL_INITIATOR(
          temporaryChannelId = temporaryChannelId,
          fundingAmount = c.fundingAmount,
          dualFunded = dualFunded,
          commitTxFeerate = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, channelType.commitmentFormat),
          fundingTxFeerate = c.fundingTxFeerate_opt.getOrElse(nodeParams.onChainFeeConf.getFundingFeerate(nodeParams.currentFeeratesForFundingClosing)),
          fundingTxFeeBudget_opt = c.fundingTxFeeBudget_opt,
          pushAmount_opt = c.pushAmount_opt,
          requireConfirmedInputs = requireConfirmedInputs,
          requestFunding_opt = c.requestFunding_opt,
          localChannelParams = localParams,
          proposedCommitParams = nodeParams.channelConf.commitParams(c.fundingAmount, unlimitedMaxHtlcValueInFlight = false),
          remote = d.peerConnection,
          remoteInit = d.remoteInit,
          channelFlags = c.channelFlags_opt.getOrElse(nodeParams.channelConf.channelFlags),
          channelConfig = channelConfig,
          channelType = channelType,
          replyTo = replyTo
        )
        log.info(s"requesting a new channel with type=$channelType fundingAmount=${c.fundingAmount} dualFunded=$dualFunded pushAmount=${c.pushAmount_opt} fundingFeerate=${init.fundingTxFeerate} temporaryChannelId=$temporaryChannelId")
        channel ! init
        stay() using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))

      case Event(open: protocol.OpenChannel, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(open.temporaryChannelId)) match {
          case None =>
            openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), d.localFeatures, d.remoteFeatures, d.peerConnection.toTyped, d.address)
            stay()
          case Some(_) =>
            log.warning("ignoring open_channel with duplicate temporaryChannelId={}", open.temporaryChannelId)
            stay()
        }

      case Event(open: protocol.OpenDualFundedChannel, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(open.temporaryChannelId)) match {
          case None if !Features.canUseFeature(d.localFeatures, d.remoteFeatures, Features.DualFunding) =>
            log.info("rejecting open_channel2: dual funding is not supported")
            self ! Peer.OutgoingMessage(Error(open.temporaryChannelId, "dual funding is not supported"), d.peerConnection)
            stay()
          case None if open.usesOnTheFlyFunding && !d.fundingFeerateOk(open.fundingFeerate) =>
            log.info("rejecting open_channel2: feerate too low ({} < {})", open.fundingFeerate, d.currentFeerates.fundingFeerate)
            self ! Peer.OutgoingMessage(Error(open.temporaryChannelId, FundingFeerateTooLow(open.temporaryChannelId, open.fundingFeerate, d.currentFeerates.fundingFeerate).getMessage), d.peerConnection)
            stay()
          case None =>
            openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Right(open), d.localFeatures, d.remoteFeatures, d.peerConnection.toTyped, d.address)
            stay()
          case Some(_) =>
            log.warning("ignoring open_channel2 with duplicate temporaryChannelId={}", open.temporaryChannelId)
            stay()
        }

      case Event(SpawnChannelNonInitiator(open, channelConfig, channelType, addFunding_opt, localParams, peerConnection), d: ConnectedData) =>
        val temporaryChannelId = open.fold(_.temporaryChannelId, _.temporaryChannelId)
        if (peerConnection == d.peerConnection) {
          OnTheFlyFunding.validateOpen(nodeParams.onTheFlyFundingConfig, open, pendingOnTheFlyFunding, feeCredit.getOrElse(0 msat)) match {
            case reject: OnTheFlyFunding.ValidationResult.Reject =>
              log.warning("rejecting on-the-fly channel: {}", reject.cancel.toAscii)
              self ! Peer.OutgoingMessage(reject.cancel, d.peerConnection)
              cancelUnsignedOnTheFlyFunding(reject.paymentHashes)
              context.system.eventStream.publish(ChannelAborted(ActorRef.noSender, remoteNodeId, temporaryChannelId))
              stay()
            case accept: OnTheFlyFunding.ValidationResult.Accept =>
              val channelKeys = nodeParams.channelKeyManager.channelKeys(channelConfig, localParams.fundingKeyPath)
              val channel = spawnChannel(channelKeys)
              context.system.scheduler.scheduleOnce(nodeParams.channelConf.channelFundingTimeout, channel, Channel.TickChannelOpenTimeout)(context.dispatcher)
              log.info(s"accepting a new channel with type=$channelType temporaryChannelId=$temporaryChannelId localParams=$localParams")
              open match {
                case Left(open) =>
                  val init = INPUT_INIT_CHANNEL_NON_INITIATOR(
                    temporaryChannelId = open.temporaryChannelId,
                    fundingContribution_opt = None,
                    dualFunded = false,
                    pushAmount_opt = None,
                    requireConfirmedInputs = false,
                    localChannelParams = localParams,
                    proposedCommitParams = nodeParams.channelConf.commitParams(open.fundingSatoshis, unlimitedMaxHtlcValueInFlight = false),
                    remote = d.peerConnection,
                    remoteInit = d.remoteInit,
                    channelConfig = channelConfig,
                    channelType = channelType)
                  channel ! init
                  channel ! open
                case Right(open) =>
                  val init = INPUT_INIT_CHANNEL_NON_INITIATOR(
                    temporaryChannelId = open.temporaryChannelId,
                    fundingContribution_opt = addFunding_opt,
                    dualFunded = true,
                    pushAmount_opt = None,
                    requireConfirmedInputs = nodeParams.channelConf.requireConfirmedInputsForDualFunding,
                    localChannelParams = localParams,
                    proposedCommitParams = nodeParams.channelConf.commitParams(open.fundingAmount + addFunding_opt.map(_.fundingAmount).getOrElse(0 sat), unlimitedMaxHtlcValueInFlight = false),
                    remote = d.peerConnection,
                    remoteInit = d.remoteInit,
                    channelConfig = channelConfig,
                    channelType = channelType)
                  channel ! init
                  accept.useFeeCredit_opt match {
                    case Some(useFeeCredit) => channel ! open.copy(tlvStream = TlvStream(open.tlvStream.records + ChannelTlv.UseFeeCredit(useFeeCredit)))
                    case None => channel ! open
                  }
              }
              fulfillOnTheFlyFundingHtlcs(accept.preimages)
              stay() using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
          }
        } else {
          log.warning("ignoring open_channel request that reconnected during channel intercept, temporaryChannelId={}", temporaryChannelId)
          context.system.eventStream.publish(ChannelAborted(ActorRef.noSender, remoteNodeId, temporaryChannelId))
          stay()
        }

      case Event(cmd: ProposeOnTheFlyFunding, d: ConnectedData) if !d.remoteFeatures.hasFeature(Features.OnTheFlyFunding) =>
        cmd.replyTo ! ProposeOnTheFlyFundingResponse.NotAvailable("peer does not support on-the-fly funding")
        stay()

      case Event(cmd: ProposeOnTheFlyFunding, d: ConnectedData) =>
        // We send the funding proposal to our peer, and report it to the sender.
        val htlc = WillAddHtlc(nodeParams.chainHash, randomBytes32(), cmd.amount, cmd.paymentHash, cmd.expiry, cmd.onion, cmd.nextPathKey_opt)
        cmd.replyTo ! ProposeOnTheFlyFundingResponse.Proposed
        // We update our list of pending proposals for that payment.
        val pending = pendingOnTheFlyFunding.get(htlc.paymentHash) match {
          case Some(pending) =>
            pending.status match {
              case status: OnTheFlyFunding.Status.Proposed =>
                self ! Peer.OutgoingMessage(htlc, d.peerConnection)
                // We extend the previous timer.
                status.timer.cancel()
                val timer = context.system.scheduler.scheduleOnce(nodeParams.onTheFlyFundingConfig.proposalTimeout, self, OnTheFlyFundingTimeout(cmd.paymentHash))(context.dispatcher)
                pending.copy(
                  proposed = pending.proposed :+ OnTheFlyFunding.Proposal(htlc, cmd.upstream, cmd.onionSharedSecrets),
                  status = OnTheFlyFunding.Status.Proposed(timer)
                )
              case status: OnTheFlyFunding.Status.AddedToFeeCredit =>
                log.info("received extra payment for on-the-fly funding that was added to fee credit (payment_hash={}, amount={})", cmd.paymentHash, cmd.amount)
                val proposal = OnTheFlyFunding.Proposal(htlc, cmd.upstream, cmd.onionSharedSecrets)
                proposal.createFulfillCommands(status.preimage).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                pending.copy(proposed = pending.proposed :+ proposal)
              case status: OnTheFlyFunding.Status.Funded =>
                log.info("rejecting extra payment for on-the-fly funding that has already been funded with txId={} (payment_hash={}, amount={})", status.txId, cmd.paymentHash, cmd.amount)
                // The payer is buggy and is paying the same payment_hash multiple times. We could simply claim that
                // extra payment for ourselves, but we're nice and instead immediately fail it.
                val proposal = OnTheFlyFunding.Proposal(htlc, cmd.upstream, cmd.onionSharedSecrets)
                proposal.createFailureCommands(None)(log).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                pending
            }
          case None =>
            self ! Peer.OutgoingMessage(htlc, d.peerConnection)
            Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Proposed).increment()
            val timer = context.system.scheduler.scheduleOnce(nodeParams.onTheFlyFundingConfig.proposalTimeout, self, OnTheFlyFundingTimeout(cmd.paymentHash))(context.dispatcher)
            OnTheFlyFunding.Pending(Seq(OnTheFlyFunding.Proposal(htlc, cmd.upstream, cmd.onionSharedSecrets)), OnTheFlyFunding.Status.Proposed(timer))
        }
        pendingOnTheFlyFunding += (htlc.paymentHash -> pending)
        stay()

      case Event(msg: OnTheFlyFundingFailureMessage, d: ConnectedData) =>
        pendingOnTheFlyFunding.get(msg.paymentHash) match {
          case Some(pending) =>
            pending.status match {
              case status: OnTheFlyFunding.Status.Proposed =>
                pending.proposed.find(_.htlc.id == msg.id) match {
                  case Some(htlc) =>
                    val failure = msg match {
                      case msg: WillFailHtlc => FailureReason.EncryptedDownstreamFailure(msg.reason, msg.attribution_opt)
                      case msg: WillFailMalformedHtlc => FailureReason.LocalFailure(createBadOnionFailure(msg.onionHash, msg.failureCode))
                    }
                    htlc.createFailureCommands(Some(failure))(log).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                    val proposed1 = pending.proposed.filterNot(_.htlc.id == msg.id)
                    if (proposed1.isEmpty) {
                      Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Rejected).increment()
                      status.timer.cancel()
                      pendingOnTheFlyFunding -= msg.paymentHash
                    } else {
                      pendingOnTheFlyFunding += (msg.paymentHash -> pending.copy(proposed = proposed1))
                    }
                  case None =>
                    log.warning("ignoring will_fail_htlc: no matching proposal for id={}", msg.id)
                    self ! Peer.OutgoingMessage(Warning(s"ignoring will_fail_htlc: no matching proposal for id=${msg.id}"), d.peerConnection)
                }
              case _: OnTheFlyFunding.Status.AddedToFeeCredit =>
                log.warning("ignoring will_fail_htlc: on-the-fly funding already added to fee credit")
                self ! Peer.OutgoingMessage(Warning("ignoring will_fail_htlc: on-the-fly funding already added to fee credit"), d.peerConnection)
              case status: OnTheFlyFunding.Status.Funded =>
                log.warning("ignoring will_fail_htlc: on-the-fly funding already signed with txId={}", status.txId)
                self ! Peer.OutgoingMessage(Warning(s"ignoring will_fail_htlc: on-the-fly funding already signed with txId=${status.txId}"), d.peerConnection)
            }
          case None =>
            log.warning("ignoring will_fail_htlc: no matching proposal for payment_hash={}", msg.paymentHash)
            self ! Peer.OutgoingMessage(Warning(s"ignoring will_fail_htlc: no matching proposal for payment_hash=${msg.paymentHash}"), d.peerConnection)
        }
        stay()

      case Event(timeout: OnTheFlyFundingTimeout, d: ConnectedData) =>
        pendingOnTheFlyFunding.get(timeout.paymentHash) match {
          case Some(pending) =>
            pending.status match {
              case _: OnTheFlyFunding.Status.Proposed =>
                log.warning("on-the-fly funding proposal timed out for payment_hash={}", timeout.paymentHash)
                pending.createFailureCommands(log).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Expired).increment()
                pendingOnTheFlyFunding -= timeout.paymentHash
                self ! Peer.OutgoingMessage(Warning(s"on-the-fly funding proposal timed out for payment_hash=${timeout.paymentHash}"), d.peerConnection)
              case _: OnTheFlyFunding.Status.AddedToFeeCredit =>
                log.warning("ignoring on-the-fly funding proposal timeout, already added to fee credit")
              case status: OnTheFlyFunding.Status.Funded =>
                log.warning("ignoring on-the-fly funding proposal timeout, already funded with txId={}", status.txId)
            }
          case None =>
            log.debug("ignoring on-the-fly funding timeout for payment_hash={} (already completed)", timeout.paymentHash)
        }
        stay()

      case Event(msg: AddFeeCredit, d: ConnectedData) if !nodeParams.features.hasFeature(Features.FundingFeeCredit) =>
        self ! Peer.OutgoingMessage(Warning(s"ignoring add_fee_credit for payment_hash=${Crypto.sha256(msg.preimage)}, ${Features.FundingFeeCredit.rfcName} is not supported"), d.peerConnection)
        stay()

      case Event(msg: AddFeeCredit, d: ConnectedData) =>
        val paymentHash = Crypto.sha256(msg.preimage)
        pendingOnTheFlyFunding.get(paymentHash) match {
          case Some(pending) =>
            pending.status match {
              case status: OnTheFlyFunding.Status.Proposed =>
                feeCredit = Some(nodeParams.db.liquidity.addFeeCredit(remoteNodeId, pending.amountOut))
                log.info("received add_fee_credit for payment_hash={}, adding {} to fee credit (total = {})", paymentHash, pending.amountOut, feeCredit)
                status.timer.cancel()
                Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.AddedToFeeCredit).increment()
                pending.createFulfillCommands(msg.preimage).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                self ! Peer.OutgoingMessage(CurrentFeeCredit(nodeParams.chainHash, feeCredit.getOrElse(0 msat)), d.peerConnection)
                pendingOnTheFlyFunding += (paymentHash -> pending.copy(status = OnTheFlyFunding.Status.AddedToFeeCredit(msg.preimage)))
              case _: OnTheFlyFunding.Status.AddedToFeeCredit =>
                log.warning("ignoring duplicate add_fee_credit for payment_hash={}", paymentHash)
                // We already fulfilled upstream HTLCs, there is nothing else to do.
                self ! Peer.OutgoingMessage(Warning(s"ignoring add_fee_credit: on-the-fly proposal already funded for payment_hash=$paymentHash"), d.peerConnection)
              case _: OnTheFlyFunding.Status.Funded =>
                log.warning("ignoring add_fee_credit for funded on-the-fly proposal (payment_hash={})", paymentHash)
                // They seem to be malicious, so let's fulfill upstream HTLCs for safety.
                pending.createFulfillCommands(msg.preimage).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                self ! Peer.OutgoingMessage(Warning(s"ignoring add_fee_credit: on-the-fly proposal already funded for payment_hash=$paymentHash"), d.peerConnection)
            }
          case None =>
            log.warning("ignoring add_fee_credit for unknown payment_hash={}", paymentHash)
            self ! Peer.OutgoingMessage(Warning(s"ignoring add_fee_credit: unknown payment_hash=$paymentHash"), d.peerConnection)
            // This may happen if the remote node is very slow and the timeout was reached before receiving their message.
            // We sent the current fee credit to let them detect it and reconcile their state.
            self ! Peer.OutgoingMessage(CurrentFeeCredit(nodeParams.chainHash, feeCredit.getOrElse(0 msat)), d.peerConnection)
        }
        stay()

      case Event(msg: SpliceInit, d: ConnectedData) =>
        d.channels.get(FinalChannelId(msg.channelId)) match {
          case Some(_) if msg.usesOnTheFlyFunding && !d.fundingFeerateOk(msg.feerate) =>
            log.info("rejecting open_channel2: feerate too low ({} < {})", msg.feerate, d.currentFeerates.fundingFeerate)
            self ! Peer.OutgoingMessage(TxAbort(msg.channelId, FundingFeerateTooLow(msg.channelId, msg.feerate, d.currentFeerates.fundingFeerate).getMessage), d.peerConnection)
          case Some(channel) =>
            OnTheFlyFunding.validateSplice(nodeParams.onTheFlyFundingConfig, msg, nodeParams.channelConf.htlcMinimum, pendingOnTheFlyFunding, feeCredit.getOrElse(0 msat)) match {
              case reject: OnTheFlyFunding.ValidationResult.Reject =>
                log.warning("rejecting on-the-fly splice: {}", reject.cancel.toAscii)
                self ! Peer.OutgoingMessage(reject.cancel, d.peerConnection)
                cancelUnsignedOnTheFlyFunding(reject.paymentHashes)
              case accept: OnTheFlyFunding.ValidationResult.Accept =>
                fulfillOnTheFlyFundingHtlcs(accept.preimages)
                accept.useFeeCredit_opt match {
                  case Some(useFeeCredit) => channel forward msg.copy(tlvStream = TlvStream(msg.tlvStream.records + ChannelTlv.UseFeeCredit(useFeeCredit)))
                  case None => channel forward msg
                }
            }
          case None => replyUnknownChannel(d.peerConnection, msg.channelId)
        }
        stay()

      case Event(e: ChannelReadyForPayments, d: ConnectedData) =>
        pendingOnTheFlyFunding.foreach {
          case (paymentHash, pending) =>
            pending.status match {
              case _: OnTheFlyFunding.Status.Proposed => ()
              case _: OnTheFlyFunding.Status.AddedToFeeCredit => ()
              case status: OnTheFlyFunding.Status.Funded =>
                context.child(paymentHash.toHex) match {
                  case Some(_) => log.debug("already relaying payment_hash={}", paymentHash)
                  case None if e.fundingTxIndex < status.fundingTxIndex => log.debug("too early to relay payment_hash={}, funding not locked ({} < {})", paymentHash, e.fundingTxIndex, status.fundingTxIndex)
                  case None =>
                    val relayer = context.spawn(Behaviors.supervise(OnTheFlyFunding.PaymentRelayer(nodeParams, remoteNodeId, e.channelId, paymentHash)).onFailure(typed.SupervisorStrategy.stop), paymentHash.toHex)
                    relayer ! OnTheFlyFunding.PaymentRelayer.TryRelay(self.toTyped, e.channel.toTyped, pending.proposed, status)
                }
            }
        }
        if (!d.peerStorage.written && !isTimerActive(WritePeerStorageTimerKey)) {
          startSingleTimer(WritePeerStorageTimerKey, WritePeerStorage, nodeParams.peerStorageConfig.getWriteDelay(remoteNodeId, Some(d.remoteFeatures)))
        }
        if (!d.remoteFeaturesWritten) {
          // We have a channel, so we can write to the DB without any DoS risk.
          nodeParams.db.peers.addOrUpdatePeerFeatures(remoteNodeId, d.remoteFeatures)
        }
        stay() using d.copy(activeChannels = d.activeChannels + e.channelId, remoteFeaturesWritten = true)

      case Event(e: LocalChannelDown, d: ConnectedData) =>
        stay() using d.copy(activeChannels = d.activeChannels - e.channelId)

      case Event(msg: HasChannelId, d: ConnectedData) =>
        d.channels.get(FinalChannelId(msg.channelId)) match {
          case Some(channel) => channel forward msg
          case None => replyUnknownChannel(d.peerConnection, msg.channelId)
        }
        stay()

      case Event(msg: HasTemporaryChannelId, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
          case Some(channel) => channel forward msg
          case None => replyUnknownChannel(d.peerConnection, msg.temporaryChannelId)
        }
        stay()

      case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: ConnectedData) if d.channels.contains(TemporaryChannelId(temporaryChannelId)) =>
        log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
        // we have our first channel with that peer: let's sync our routing table
        if (!d.channels.keys.exists(_.isInstanceOf[FinalChannelId])) {
          d.peerConnection ! PeerConnection.DoSync(replacePrevious = false)
        }
        // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
        // we won't clean it up, but we won't remember the temporary id on channel termination
        stay() using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

      case Event(Disconnect(nodeId, replyTo_opt), d: ConnectedData) if nodeId == remoteNodeId =>
        log.debug("disconnecting")
        val replyTo = replyTo_opt.getOrElse(sender().toTyped)
        replyTo ! Disconnecting(nodeId)
        d.peerConnection ! PeerConnection.Kill(KillReason.UserRequest)
        stay()

      case Event(ConnectionDown(peerConnection), d: ConnectedData) if peerConnection == d.peerConnection =>
        Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
          log.debug("connection lost")
        }
        if (d.channels.isEmpty && canForgetPendingOnTheFlyFunding()) {
          // We have no existing channels or pending signed transaction, we can forget about this peer.
          stopPeer(d.peerStorage)
        } else {
          d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
          val lastRemoteFeatures = LastRemoteFeatures(d.remoteFeatures, d.remoteFeaturesWritten)
          goto(DISCONNECTED) using DisconnectedData(d.channels.collect { case (k: FinalChannelId, v) => (k, v) }, d.activeChannels, d.peerStorage, Some(lastRemoteFeatures))
        }

      case Event(ChannelTerminated(actor), d: ConnectedData) =>
        if (d.channels.values.toSet.contains(actor)) {
          // we have at most 2 ids: a TemporaryChannelId and a FinalChannelId
          val channelIds = d.channels.filter(_._2 == actor).keys
          log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
          val channels1 = d.channels -- channelIds
          if (channels1.isEmpty) {
            log.info("that was the last open channel, closing the connection")
            context.system.eventStream.publish(LastChannelClosed(self, remoteNodeId))
            d.peerConnection ! PeerConnection.Kill(KillReason.NoRemainingChannel)
          }
          stay() using d.copy(channels = channels1)
        } else {
          stay()
        }

      case Event(connectionReady: PeerConnection.ConnectionReady, d: ConnectedData) =>
        log.debug(s"got new connection, killing current one and switching")
        d.peerConnection ! PeerConnection.Kill(KillReason.ConnectionReplaced)
        d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        gotoConnected(connectionReady, d.channels, d.activeChannels, d.peerStorage)

      case Event(msg: OnionMessage, _: ConnectedData) =>
        OnionMessages.process(nodeParams.privateKey, msg) match {
          case OnionMessages.DropMessage(reason) =>
            log.info("dropping message from {}: {}", remoteNodeId.value.toHex, reason.toString)
          case OnionMessages.SendMessage(nextNode, message) if nodeParams.features.hasFeature(Features.OnionMessages) =>
            val messageId = randomBytes32()
            log.info("relaying onion message with messageId={}", messageId)
            val relay = context.spawn(Behaviors.supervise(MessageRelay(nodeParams, switchboard, register, router)).onFailure(typed.SupervisorStrategy.stop), s"relay-message-$messageId")
            relay ! MessageRelay.RelayMessage(messageId, remoteNodeId, nextNode, message, nodeParams.onionMessageConfig.relayPolicy, None)
          case OnionMessages.SendMessage(_, _) =>
            log.debug("dropping message from {}: relaying onion messages is disabled", remoteNodeId.value.toHex)
          case received: OnionMessages.ReceiveMessage =>
            log.info("received message from {}: {}", remoteNodeId.value.toHex, received)
            context.system.eventStream.publish(received)
        }
        stay()

      case Event(RelayOnionMessage(messageId, msg, replyTo_opt), d: ConnectedData) =>
        d.peerConnection ! msg
        replyTo_opt.foreach(_ ! MessageRelay.Sent(messageId))
        stay()

      case Event(msg: RecommendedFeerates, _) =>
        log.info("our peer recommends the following feerates: funding={}, commitment={}", msg.fundingFeerate, msg.commitmentFeerate)
        stay()

      case Event(unknownMsg: UnknownMessage, d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        context.system.eventStream.publish(UnknownMessageReceived(self, remoteNodeId, unknownMsg, d.connectionInfo))
        stay()

      case Event(RelayUnknownMessage(unknownMsg: UnknownMessage), d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        logMessage(unknownMsg, "OUT")
        d.peerConnection forward unknownMsg
        stay()

      case Event(store: PeerStorageStore, d: ConnectedData) =>
        if (nodeParams.features.hasFeature(Features.ProvideStorage)) {
          // If we don't have any pending write operations, we write the updated peer storage to disk after a delay.
          // This ensures that when we receive a burst of peer storage updates, we will rate-limit our IO disk operations.
          // If we already have a pending write operation, we must not reset the timer, otherwise we may indefinitely delay
          // writing to the DB and may never store our peer's backup.
          if (d.activeChannels.isEmpty) {
            log.debug("received peer storage from peer with no active channel")
          } else if (!isTimerActive(WritePeerStorageTimerKey)) {
            startSingleTimer(WritePeerStorageTimerKey, WritePeerStorage, nodeParams.peerStorageConfig.getWriteDelay(remoteNodeId, Some(d.remoteFeatures)))
          }
          stay() using d.copy(peerStorage = PeerStorage(Some(store.blob), written = false))
        } else {
          log.debug("ignoring peer storage, feature disabled")
          stay()
        }

      case Event(WritePeerStorage, d: ConnectedData) =>
        d.peerStorage.data.foreach(nodeParams.db.peers.updateStorage(remoteNodeId, _))
        stay() using d.copy(peerStorage = d.peerStorage.copy(written = true))

      case Event(unhandledMsg: LightningMessage, _) =>
        log.warning("ignoring message {}", unhandledMsg)
        stay()
    }
  }

  whenUnhandled {
    case Event(_: Peer.OpenChannel, _) =>
      sender() ! Status.Failure(new RuntimeException("not connected"))
      stay()

    case Event(r: Peer.ProposeOnTheFlyFunding, _) =>
      r.replyTo ! ProposeOnTheFlyFundingResponse.NotAvailable("peer not connected")
      stay()

    case Event(Disconnect(nodeId, replyTo_opt), _) =>
      val replyTo = replyTo_opt.getOrElse(sender().toTyped)
      replyTo ! NotConnected(nodeId)
      stay()

    case Event(r: GetPeerInfo, d) =>
      val replyTo = r.replyTo.getOrElse(sender().toTyped)
      d match {
        case c: ConnectedData =>
          replyTo ! PeerInfo(self, remoteNodeId, stateName, Some(c.remoteFeatures), Some(c.address), c.channels.values.toSet)
          stay()
        case d: DisconnectedData =>
          // If we haven't reconnected since our last restart, we fetch the latest remote features from our DB.
          val remoteFeatures_opt = d.remoteFeatures_opt
            .orElse(nodeParams.db.peers.getPeer(remoteNodeId).map(nodeInfo => LastRemoteFeatures(nodeInfo.features, written = true)))
          replyTo ! PeerInfo(self, remoteNodeId, stateName, remoteFeatures_opt.map(_.features), None, d.channels.values.toSet)
          stay() using d.copy(remoteFeatures_opt = remoteFeatures_opt)
        case _ =>
          replyTo ! PeerInfo(self, remoteNodeId, stateName, None, None, d.channels.values.toSet)
          stay()
      }

    case Event(r: GetPeerChannels, d) =>
      if (d.channels.isEmpty) {
        r.replyTo ! PeerChannels(remoteNodeId, Nil)
      } else {
        val actor = context.spawnAnonymous(PeerChannelsCollector(remoteNodeId))
        actor ! PeerChannelsCollector.GetChannels(r.replyTo, d.channels.values.map(_.toTyped).toSet)
      }
      stay()

    case Event(_: CurrentFeerates, d) =>
      d match {
        case d: ConnectedData =>
          val feerates = nodeParams.recommendedFeerates(remoteNodeId, d.localFeatures, d.remoteFeatures)
          d.peerConnection ! feerates
          // We keep our previous recommended feerate: if our peer is concurrently sending a message based on the
          // previous feerates, we should accept it.
          stay() using d.copy(currentFeerates = feerates, previousFeerates_opt = Some(d.currentFeerates))
        case _ =>
          stay()
      }

    case Event(current: CurrentBlockHeight, d) =>
      // If we have pending will_add_htlc that are timing out, it doesn't make any sense to keep them, even if we have
      // already funded the corresponding channel: our peer will force-close if we relay them.
      // Our only option is to fail the upstream HTLCs to ensure that the upstream channels don't force-close.
      // Note that we won't be paid for the liquidity we've provided, but we don't have a choice.
      val expired = pendingOnTheFlyFunding.filter {
        case (_, pending) => pending.proposed.exists(_.htlc.expiry.blockHeight <= current.blockHeight)
      }
      expired.foreach {
        case (paymentHash, pending) => pending.status match {
          case _: OnTheFlyFunding.Status.Proposed =>
            log.warning("proposed will_add_htlc expired for payment_hash={}", paymentHash)
            Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Timeout).increment()
            pending.createFailureCommands(log).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
          case _: OnTheFlyFunding.Status.AddedToFeeCredit =>
            // Nothing to do, we already fulfilled the upstream HTLCs.
            log.debug("forgetting will_add_htlc added to fee credit for payment_hash={}", paymentHash)
          case _: OnTheFlyFunding.Status.Funded =>
            log.warning("funded will_add_htlc expired for payment_hash={}, our peer may be malicious", paymentHash)
            Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Timeout).increment()
            pending.createFailureCommands(log).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
            nodeParams.db.liquidity.removePendingOnTheFlyFunding(remoteNodeId, paymentHash)
        }
      }
      pendingOnTheFlyFunding = pendingOnTheFlyFunding.removedAll(expired.keys)
      d match {
        case d: DisconnectedData if d.channels.isEmpty && pendingOnTheFlyFunding.isEmpty => stopPeer(d.peerStorage)
        case _ => stay()
      }

    case Event(e: LiquidityPurchaseSigned, d: ConnectedData) =>
      // If that liquidity purchase was partially paid with fee credit, we will deduce it from what our peer owes us
      // and remove the corresponding amount from our peer's credit.
      // Note that since we only allow a single channel per user when on-the-fly funding is used, and it's not possible
      // to request a splice while one is already in progress, it's safe to only remove fee credit once the funding
      // transaction has been signed.
      val feeCreditUsed = e.purchase match {
        case _: LiquidityAds.Purchase.Standard => 0 msat
        case p: LiquidityAds.Purchase.WithFeeCredit =>
          feeCredit = Some(nodeParams.db.liquidity.removeFeeCredit(remoteNodeId, p.feeCreditUsed))
          self ! OutgoingMessage(CurrentFeeCredit(nodeParams.chainHash, feeCredit.getOrElse(0 msat)), d.peerConnection)
          p.feeCreditUsed
      }
      // We signed a liquidity purchase from our peer. At that point we're not 100% sure yet it will succeed: if
      // we disconnect before our peer sends their signature, the funding attempt may be cancelled when reconnecting.
      // If that happens, the on-the-fly proposal will stay in our state until we reach the CLTV expiry, at which
      // point we will forget it and fail the upstream HTLCs. This is also what would happen if we successfully
      // funded the channel, but it closed before we could relay the HTLCs.
      val (paymentHashes, feesOwed) = e.purchase.paymentDetails match {
        case LiquidityAds.PaymentDetails.FromChannelBalance => (Nil, 0 msat)
        case p: LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc => (p.paymentHashes, 0 msat)
        case p: LiquidityAds.PaymentDetails.FromFutureHtlc => (p.paymentHashes, e.purchase.fees.total - feeCreditUsed)
        case p: LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage => (p.preimages.map(preimage => Crypto.sha256(preimage)), e.purchase.fees.total - feeCreditUsed)
      }
      // We split the fees across payments. We could dynamically re-split depending on whether some payments are failed
      // instead of fulfilled, but that's overkill: if our peer fails one of those payment, they're likely malicious
      // and will fail anyway, even if we try to be clever with fees splitting.
      var remainingFees = feesOwed.max(0 msat)
      pendingOnTheFlyFunding
        .filter { case (paymentHash, _) => paymentHashes.contains(paymentHash) }
        .values.toSeq
        // In case our peer goes offline, we start with payments that are as far as possible from timing out.
        .sortBy(_.expiry).reverse
        .foreach(payment => {
          payment.status match {
            case status: OnTheFlyFunding.Status.Proposed =>
              status.timer.cancel()
              val paymentFees = remainingFees.min(payment.maxFees(e.htlcMinimum))
              remainingFees -= paymentFees
              log.info("liquidity purchase signed for payment_hash={}, waiting to relay HTLCs (txId={}, fundingTxIndex={}, fees={})", payment.paymentHash, e.txId, e.fundingTxIndex, paymentFees)
              val payment1 = payment.copy(status = OnTheFlyFunding.Status.Funded(e.channelId, e.txId, e.fundingTxIndex, paymentFees))
              Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Funded).increment()
              nodeParams.db.liquidity.addPendingOnTheFlyFunding(remoteNodeId, payment1)
              pendingOnTheFlyFunding += payment.paymentHash -> payment1
            case _: OnTheFlyFunding.Status.AddedToFeeCredit =>
              log.warning("liquidity purchase was signed for payment_hash={} that was also added to fee credit: our peer may be malicious", payment.paymentHash)
              // Our peer tried to concurrently get a channel funded *and* add the same payment to its fee credit.
              // We've already signed the funding transaction so we can't abort, but we have also received the preimage
              // and fulfilled the upstream HTLCs: we simply won't forward the matching HTLCs on the funded channel.
              // Instead of being paid the funding fees, we've claimed the entire incoming HTLC set, which is bigger
              // than the fees (otherwise we wouldn't have accepted the on-the-fly funding attempt), so it's fine.
              // They cannot have used that additional fee credit yet because we only allow a single channel per user
              // when on-the-fly funding is used, and it's not possible to request a splice while one is already in
              // progress.
              feeCredit = Some(nodeParams.db.liquidity.removeFeeCredit(remoteNodeId, payment.amountOut))
            case status: OnTheFlyFunding.Status.Funded =>
              log.warning("liquidity purchase was already signed for payment_hash={} (previousTxId={}, currentTxId={})", payment.paymentHash, status.txId, e.txId)
          }
        })
      stay()

    case Event(e: OnTheFlyFunding.PaymentRelayer.RelayResult, _) =>
      e match {
        case success: OnTheFlyFunding.PaymentRelayer.RelaySuccess =>
          pendingOnTheFlyFunding.get(success.paymentHash) match {
            case Some(pending) =>
              log.info("successfully relayed on-the-fly HTLC for payment_hash={}", success.paymentHash)
              // We've been paid for our liquidity fees: we can now fulfill upstream.
              pending.createFulfillCommands(success.preimage).foreach {
                case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd)
              }
              // We emit a relay event: since we waited for on-chain funding before relaying the payment, the timestamps
              // won't be accurate, but everything else is.
              pending.proposed.foreach {
                case OnTheFlyFunding.Proposal(htlc, upstream, _) => upstream match {
                  case _: Upstream.Local => ()
                  case u: Upstream.Hot.Channel =>
                    val incoming = PaymentRelayed.IncomingPart(u.add.amountMsat, u.add.channelId, u.receivedAt)
                    val outgoing = PaymentRelayed.OutgoingPart(htlc.amount, success.channelId, TimestampMilli.now())
                    context.system.eventStream.publish(OnTheFlyFundingPaymentRelayed(htlc.paymentHash, Seq(incoming), Seq(outgoing)))
                  case u: Upstream.Hot.Trampoline =>
                    val incoming = u.received.map(r => PaymentRelayed.IncomingPart(r.add.amountMsat, r.add.channelId, r.receivedAt))
                    val outgoing = PaymentRelayed.OutgoingPart(htlc.amount, success.channelId, TimestampMilli.now())
                    context.system.eventStream.publish(OnTheFlyFundingPaymentRelayed(htlc.paymentHash, incoming, Seq(outgoing)))
                }
              }
              Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.RelaySucceeded).increment()
              Metrics.OnTheFlyFundingFees.withoutTags().record(success.fees.toLong)
              nodeParams.db.liquidity.removePendingOnTheFlyFunding(remoteNodeId, success.paymentHash)
              pendingOnTheFlyFunding -= success.paymentHash
            case None => ()
          }
          // If this is a payment that was initially rejected, it wasn't a malicious node, but rather a temporary issue.
          nodeParams.onTheFlyFundingConfig.fromFutureHtlcFulfilled(success.paymentHash)
          stay()
        case OnTheFlyFunding.PaymentRelayer.RelayFailed(paymentHash, failure) =>
          log.warning("on-the-fly HTLC failure for payment_hash={}: {}", paymentHash, failure.toString)
          Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.relayFailed(failure)).increment()
          // We don't give up yet by relaying the failure upstream: we may have simply been disconnected, or the added
          // liquidity may have been consumed by concurrent HTLCs. We'll retry at the next reconnection with that peer
          // or after the next splice, and will only give up when the outgoing will_add_htlc timeout.
          val fundingStatus = pendingOnTheFlyFunding.get(paymentHash).map(_.status)
          failure match {
            case OnTheFlyFunding.PaymentRelayer.RemoteFailure(_) if fundingStatus.collect { case s: OnTheFlyFunding.Status.Funded => s.remainingFees }.sum > 0.msat =>
              // We are still owed some fees for the funding transaction we published: we need these HTLCs to succeed.
              // They received the HTLCs but failed them, which means that they're likely malicious (but not always,
              // they may have other pending HTLCs that temporarily prevent relaying the whole HTLC set because of
              // channel limits). We disable funding from future HTLCs to limit our exposure to fee siphoning.
              nodeParams.onTheFlyFundingConfig.fromFutureHtlcFailed(paymentHash, remoteNodeId)
            case _ => ()
          }
          stay()
      }

    case Event(_: Peer.OutgoingMessage, _) => stay() // we got disconnected or reconnected and this message was for the previous connection

    case Event(RelayOnionMessage(messageId, _, replyTo_opt), _) =>
      replyTo_opt.foreach(_ ! MessageRelay.Disconnected(messageId))
      stay()
  }

  private val reconnectionTask = context.actorOf(ReconnectionTask.props(nodeParams, remoteNodeId), "reconnection-task")

  onTransition {
    case _ -> (DISCONNECTED | CONNECTED) => reconnectionTask ! Peer.Transition(stateData, nextStateData)
  }

  onTransition {
    case DISCONNECTED -> CONNECTED =>
      Metrics.PeersConnected.withoutTags().increment()
      context.system.eventStream.publish(PeerConnected(self, remoteNodeId, nextStateData.asInstanceOf[Peer.ConnectedData].connectionInfo))
    case CONNECTED -> CONNECTED => // connection switch
      context.system.eventStream.publish(PeerConnected(self, remoteNodeId, nextStateData.asInstanceOf[Peer.ConnectedData].connectionInfo))
      cancelUnsignedOnTheFlyFunding()
    case CONNECTED -> DISCONNECTED =>
      Metrics.PeersConnected.withoutTags().decrement()
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
      cancelUnsignedOnTheFlyFunding()
  }

  onTermination {
    case StopEvent(_, CONNECTED, _: ConnectedData) =>
      // the transition handler won't be fired if we go directly from CONNECTED to closed
      Metrics.PeersConnected.withoutTags().decrement()
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  private def gotoConnected(connectionReady: PeerConnection.ConnectionReady, channels: Map[ChannelId, ActorRef], activeChannels: Set[ByteVector32], peerStorage: PeerStorage): State = {
    require(remoteNodeId == connectionReady.remoteNodeId, s"invalid nodeId: $remoteNodeId != ${connectionReady.remoteNodeId}")
    log.debug("got authenticated connection to address {}", connectionReady.address)

    if (connectionReady.outgoing) {
      // We store the node address and features upon successful outgoing connection, so we can reconnect later.
      // The previous address is overwritten: we don't need it since the current one works.
      nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, connectionReady.address, connectionReady.remoteInit.features)
    }

    // If we have some data stored from our peer, we send it to them before doing anything else.
    peerStorage.data.foreach(connectionReady.peerConnection ! PeerStorageRetrieval(_))

    // let's bring existing/requested channels online
    channels.values.toSet[ActorRef].foreach(_ ! INPUT_RECONNECTED(connectionReady.peerConnection, connectionReady.localInit, connectionReady.remoteInit)) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)

    // We tell our peer what our current feerates are.
    val feerates = nodeParams.recommendedFeerates(remoteNodeId, connectionReady.localInit.features, connectionReady.remoteInit.features)
    connectionReady.peerConnection ! feerates

    if (Features.canUseFeature(connectionReady.localInit.features, connectionReady.remoteInit.features, Features.FundingFeeCredit)) {
      if (feeCredit.isEmpty) {
        // We read the fee credit from the database on the first connection attempt.
        // We keep track of the latest credit afterwards and don't need to read it from the DB at every reconnection.
        feeCredit = Some(nodeParams.db.liquidity.getFeeCredit(remoteNodeId))
      }
      log.info("reconnecting with fee credit = {}", feeCredit)
      connectionReady.peerConnection ! CurrentFeeCredit(nodeParams.chainHash, feeCredit.getOrElse(0 msat))
    }

    goto(CONNECTED) using ConnectedData(connectionReady.address, connectionReady.peerConnection, connectionReady.localInit, connectionReady.remoteInit, channels, activeChannels, feerates, None, peerStorage, remoteFeaturesWritten = connectionReady.outgoing)
  }

  /**
   * We need to ignore [[LightningMessage]] not sent by the current [[PeerConnection]]. This may happen if we switch
   * between connections.
   */
  private def dropStaleMessages(s: StateFunction): StateFunction = {
    case Event(msg: LightningMessage, d: ConnectedData) if sender() != d.peerConnection =>
      log.warning("dropping message from stale connection: {}", msg)
      stay()
    case e if s.isDefinedAt(e) =>
      s(e)
  }

  private def spawnChannel(channelKeys: ChannelKeys): ActorRef = {
    val channel = channelFactory.spawn(context, remoteNodeId, channelKeys)
    context.watchWith(channel, ChannelTerminated(channel.ref))
    channel
  }

  private def replyUnknownChannel(peerConnection: ActorRef, unknownChannelId: ByteVector32): Unit = {
    val msg = Error(unknownChannelId, "unknown channel")
    self ! Peer.OutgoingMessage(msg, peerConnection)
  }

  private def cancelUnsignedOnTheFlyFunding(): Unit = cancelUnsignedOnTheFlyFunding(pendingOnTheFlyFunding.keySet)

  private def cancelUnsignedOnTheFlyFunding(paymentHashes: Set[ByteVector32]): Unit = {
    val unsigned = pendingOnTheFlyFunding.filter {
      case (paymentHash, pending) if paymentHashes.contains(paymentHash) =>
        pending.status match {
          case status: OnTheFlyFunding.Status.Proposed =>
            log.info("cancelling on-the-fly funding for payment_hash={}", paymentHash)
            status.timer.cancel()
            pending.createFailureCommands(log).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
            true
          // We keep proposals that have been added to fee credit until we reach the HTLC expiry or we restart. This
          // guarantees that our peer cannot concurrently add to their fee credit a payment for which we've signed a
          // funding transaction.
          case _: OnTheFlyFunding.Status.AddedToFeeCredit => false
          case _: OnTheFlyFunding.Status.Funded => false
        }
      case _ => false
    }
    pendingOnTheFlyFunding = pendingOnTheFlyFunding.removedAll(unsigned.keys)
  }

  private def fulfillOnTheFlyFundingHtlcs(preimages: Set[ByteVector32]): Unit = {
    preimages.foreach(preimage => pendingOnTheFlyFunding.get(Crypto.sha256(preimage)) match {
      case Some(pending) => pending.createFulfillCommands(preimage).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
      case None => ()
    })
  }

  /** Return true if we can forget pending on-the-fly funding transactions and stop ourselves. */
  private def canForgetPendingOnTheFlyFunding(): Boolean = {
    pendingOnTheFlyFunding.forall {
      case (_, pending) => pending.status match {
        case _: OnTheFlyFunding.Status.Proposed => true
        // We don't stop ourselves if our peer has some fee credit.
        // They will likely come back online to use that fee credit.
        case _: OnTheFlyFunding.Status.AddedToFeeCredit => false
        // We don't stop ourselves if we've signed an on-the-fly funding proposal but haven't settled HTLCs yet.
        // We must watch the expiry of those HTLCs and obtain the preimage before they expire to get paid.
        case _: OnTheFlyFunding.Status.Funded => false
      }
    }
  }

  // resume the openChannelInterceptor in case of failure, we always want the open channel request to succeed or fail
  private val openChannelInterceptor = context.spawnAnonymous(Behaviors.supervise(OpenChannelInterceptor(context.self.toTyped, nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter)).onFailure(typed.SupervisorStrategy.resume))

  private def stopPeer(peerStorage: PeerStorage): State = {
    if (!peerStorage.written) {
      peerStorage.data.foreach(nodeParams.db.peers.updateStorage(remoteNodeId, _))
    }
    log.info("removing peer from db")
    cancelUnsignedOnTheFlyFunding()
    nodeParams.db.peers.removePeer(remoteNodeId)
    stop(FSM.Normal)
  }

  // a failing channel won't be restarted, it should handle its states
  // connection are stateless
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

  // we use this to log raw messages coming in and out of the peer
  private val msgLogger = new BusLogging(context.system.eventStream, "", classOf[Peer.MessageLogs], context.system.asInstanceOf[ExtendedActorSystem].logFilter) with DiagnosticLoggingAdapter

  private def logMessage(msg: LightningMessage, direction: String): Unit = {
    require(direction == "IN" || direction == "OUT")
    msgLogger.mdc(mdc(msg))
    msgLogger.info(s"$direction msg={}", msg)
    msgLogger.clearMDC()
  }

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    msg match {
      case lm: LightningMessage => logMessage(lm, "IN")
      case _ => ()
    }
    super.aroundReceive(receive, msg)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(LogCategory(currentMessage), Some(remoteNodeId), Logs.channelId(currentMessage), nodeAlias_opt = Some(nodeParams.alias))
  }

  private val WritePeerStorageTimerKey = "peer-storage-write"
}

object Peer {

  val CHANNELID_ZERO: ByteVector32 = ByteVector32.Zeroes

  trait ChannelFactory {
    def spawn(context: ActorContext, remoteNodeId: PublicKey, channelKeys: ChannelKeys): ActorRef
  }

  case class SimpleChannelFactory(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, wallet: OnChainChannelFunder with OnChainPubkeyCache, txPublisherFactory: Channel.TxPublisherFactory) extends ChannelFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey, channelKeys: ChannelKeys): ActorRef =
      context.actorOf(Channel.props(nodeParams, channelKeys, wallet, remoteNodeId, watcher, relayer, txPublisherFactory))
  }

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, wallet: OnChainPubkeyCache, channelFactory: ChannelFactory, switchboard: ActorRef, register: ActorRef, router: typed.ActorRef[Router.GetNodeId], pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command]): Props =
    Props(new Peer(nodeParams, remoteNodeId, wallet, channelFactory, switchboard, register, router, pendingChannelsRateLimiter))

  // @formatter:off

  // used to identify the logger for raw messages
  case class MessageLogs()

  sealed trait ChannelId { def id: ByteVector32 }
  case class TemporaryChannelId(id: ByteVector32) extends ChannelId
  case class FinalChannelId(id: ByteVector32) extends ChannelId

  case class PeerStorage(data: Option[ByteVector], written: Boolean)

  case class LastRemoteFeatures(features: Features[InitFeature], written: Boolean)

  sealed trait Data {
    def channels: Map[_ <: ChannelId, ActorRef] // will be overridden by Map[FinalChannelId, ActorRef] or Map[ChannelId, ActorRef]
    def activeChannels: Set[ByteVector32] // channels that are available to process payments
    def peerStorage: PeerStorage
  }
  case object Nothing extends Data {
    override def channels = Map.empty
    override def activeChannels: Set[ByteVector32] = Set.empty
    override def peerStorage: PeerStorage = PeerStorage(None, written = true)
  }
  case class DisconnectedData(channels: Map[FinalChannelId, ActorRef], activeChannels: Set[ByteVector32], peerStorage: PeerStorage, remoteFeatures_opt: Option[LastRemoteFeatures]) extends Data
  case class ConnectedData(address: NodeAddress, peerConnection: ActorRef, localInit: protocol.Init, remoteInit: protocol.Init, channels: Map[ChannelId, ActorRef], activeChannels: Set[ByteVector32], currentFeerates: RecommendedFeerates, previousFeerates_opt: Option[RecommendedFeerates], peerStorage: PeerStorage, remoteFeaturesWritten: Boolean) extends Data {
    val connectionInfo: ConnectionInfo = ConnectionInfo(address, peerConnection, localInit, remoteInit)
    def localFeatures: Features[InitFeature] = localInit.features
    def remoteFeatures: Features[InitFeature] = remoteInit.features
    /** Returns true if the proposed feerate matches one of our recent feerate recommendations. */
    def fundingFeerateOk(proposedFeerate: FeeratePerKw): Boolean = currentFeerates.fundingFeerate <= proposedFeerate || previousFeerates_opt.exists(_.fundingFeerate <= proposedFeerate)
  }

  sealed trait State
  case object INSTANTIATING extends State
  case object DISCONNECTED extends State
  case object CONNECTED extends State

  case class Init(storedChannels: Set[PersistentChannelData], pendingOnTheFlyFunding: Map[ByteVector32, OnTheFlyFunding.Pending])
  case class Connect(nodeId: PublicKey, address_opt: Option[NodeAddress], replyTo: ActorRef, isPersistent: Boolean) {
    def uri: Option[NodeURI] = address_opt.map(NodeURI(nodeId, _))
  }
  object Connect {
    def apply(uri: NodeURI, replyTo: ActorRef, isPersistent: Boolean): Connect = new Connect(uri.nodeId, Some(uri.address), replyTo, isPersistent)
  }

  case class Disconnect(nodeId: PublicKey, replyTo_opt: Option[typed.ActorRef[DisconnectResponse]] = None) extends PossiblyHarmful
  sealed trait DisconnectResponse {
    def nodeId: PublicKey
  }
  case class Disconnecting(nodeId: PublicKey) extends DisconnectResponse { override def toString: String = s"peer $nodeId disconnecting" }
  case class NotConnected(nodeId: PublicKey) extends DisconnectResponse { override def toString: String = s"peer $nodeId not connected" }

  case class OpenChannel(remoteNodeId: PublicKey,
                         fundingAmount: Satoshi,
                         channelType_opt: Option[SupportedChannelType],
                         pushAmount_opt: Option[MilliSatoshi],
                         fundingTxFeerate_opt: Option[FeeratePerKw],
                         fundingTxFeeBudget_opt: Option[Satoshi],
                         requestFunding_opt: Option[LiquidityAds.RequestFunding],
                         channelFlags_opt: Option[ChannelFlags],
                         timeout_opt: Option[Timeout],
                         requireConfirmedInputsOverride_opt: Option[Boolean] = None,
                         disableMaxHtlcValueInFlight: Boolean = false) extends PossiblyHarmful {
    require(!(channelType_opt.exists(_.features.contains(Features.ScidAlias)) && channelFlags_opt.exists(_.announceChannel)), "option_scid_alias is not compatible with public channels")
    require(fundingAmount > 0.sat, s"funding amount must be positive")
    pushAmount_opt.foreach(pushAmount => {
      require(pushAmount >= 0.msat, s"pushAmount must be positive")
      require(pushAmount <= fundingAmount, s"pushAmount must be less than or equal to funding amount")
    })
    fundingTxFeerate_opt.foreach(feerate => require(feerate >= FeeratePerKw.MinimumFeeratePerKw, s"fee rate $feerate is below minimum ${FeeratePerKw.MinimumFeeratePerKw}"))
  }

  sealed trait OpenChannelResponse
  object OpenChannelResponse {
    /**
     * This response doesn't fully guarantee that the channel will actually be opened, because our peer may potentially
     * double-spend the funding transaction. Callers must wait for on-chain confirmations if they want guarantees that
     * the channel has been opened.
     */
    case class Created(channelId: ByteVector32, fundingTxId: TxId, fee: Satoshi) extends OpenChannelResponse { override def toString  = s"created channel $channelId with fundingTxId=$fundingTxId and fees=$fee" }
    case class Rejected(reason: String) extends OpenChannelResponse { override def toString = reason }
    case object Cancelled extends OpenChannelResponse { override def toString  = "channel creation cancelled" }
    case object Disconnected extends OpenChannelResponse { override def toString = "disconnected" }
    case object TimedOut extends OpenChannelResponse { override def toString = "open channel cancelled, took too long" }
    case class RemoteError(ascii: String) extends OpenChannelResponse { override def toString = s"peer aborted the channel funding flow: '$ascii'" }
  }

  case class SpawnChannelInitiator(replyTo: akka.actor.typed.ActorRef[OpenChannelResponse], cmd: Peer.OpenChannel, channelConfig: ChannelConfig, channelType: SupportedChannelType, localParams: LocalChannelParams)
  case class SpawnChannelNonInitiator(open: Either[protocol.OpenChannel, protocol.OpenDualFundedChannel], channelConfig: ChannelConfig, channelType: SupportedChannelType, addFunding_opt: Option[LiquidityAds.AddFunding], localParams: LocalChannelParams, peerConnection: ActorRef)

  /** If [[Features.OnTheFlyFunding]] is supported and we're connected, relay a funding proposal to our peer. */
  case class ProposeOnTheFlyFunding(replyTo: typed.ActorRef[ProposeOnTheFlyFundingResponse], amount: MilliSatoshi, paymentHash: ByteVector32, expiry: CltvExpiry, onion: OnionRoutingPacket, onionSharedSecrets: Seq[Sphinx.SharedSecret], nextPathKey_opt: Option[PublicKey], upstream: Upstream.Hot)

  sealed trait ProposeOnTheFlyFundingResponse
  object ProposeOnTheFlyFundingResponse {
    case object Proposed extends ProposeOnTheFlyFundingResponse
    case class NotAvailable(reason: String) extends ProposeOnTheFlyFundingResponse
  }

  /** We signed a funding transaction where our peer purchased some liquidity. */
  case class LiquidityPurchaseSigned(channelId: ByteVector32, txId: TxId, fundingTxIndex: Long, htlcMinimum: MilliSatoshi, purchase: LiquidityAds.Purchase)

  case class OnTheFlyFundingTimeout(paymentHash: ByteVector32)

  case class GetPeerInfo(replyTo: Option[typed.ActorRef[PeerInfoResponse]])
  sealed trait PeerInfoResponse { def nodeId: PublicKey }
  case class PeerInfo(peer: ActorRef, nodeId: PublicKey, state: State, features: Option[Features[InitFeature]], address: Option[NodeAddress], channels: Set[ActorRef]) extends PeerInfoResponse
  case class PeerNotFound(nodeId: PublicKey) extends PeerInfoResponse with DisconnectResponse { override def toString: String = s"peer $nodeId not found" }

  /** Return the peer's current channels: note that the data may change concurrently, never assume it is fully up-to-date. */
  case class GetPeerChannels(replyTo: typed.ActorRef[PeerChannels])
  case class ChannelInfo(channel: typed.ActorRef[Command], state: ChannelState, data: ChannelData)
  case class PeerChannels(nodeId: PublicKey, channels: Seq[ChannelInfo])

  case class PeerRoutingMessage(peerConnection: ActorRef, remoteNodeId: PublicKey, message: RoutingMessage) extends RemoteTypes

  /**
   * Dedicated command for outgoing messages for logging purposes.
   *
   * To preserve sequentiality of messages in the event of disconnections and reconnections, we provide a reference to
   * the connection that the message is valid for. If the actual connection was reset in the meantime, the [[Peer]]
   * will simply drop the message.
   */
  case class OutgoingMessage(msg: LightningMessage, peerConnection: ActorRef)

  case class Transition(previousData: Peer.Data, nextData: Peer.Data)

  /**
   * Sent by the peer-connection to notify the peer that the connection is down.
   * We could use watchWith on the peer-connection but it doesn't work with akka cluster when untrusted mode is enabled
   */
  case class ConnectionDown(peerConnection: ActorRef) extends RemoteTypes

  /** A child channel actor has been terminated. */
  case class ChannelTerminated(channel: ActorRef) extends RemoteTypes

  case class RelayOnionMessage(messageId: ByteVector32, msg: OnionMessage, replyTo_opt: Option[typed.ActorRef[Status]])

  case class RelayUnknownMessage(unknownMessage: UnknownMessage)

  case object WritePeerStorage
  // @formatter:on
}
