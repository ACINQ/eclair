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
import akka.actor.{Actor, ActorContext, ActorRef, ExtendedActorSystem, FSM, OneForOneStrategy, PossiblyHarmful, Props, Status, SupervisorStrategy, Terminated, typed}
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
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, CurrentFeerates, OnChainChannelFunder, OnchainPubkeyCache}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
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
import fr.acinq.eclair.wire.protocol.LiquidityAds.PaymentDetails
import fr.acinq.eclair.wire.protocol.{Error, HasChannelId, HasTemporaryChannelId, LightningMessage, LiquidityAds, NodeAddress, OnTheFlyFundingFailureMessage, OnionMessage, OnionRoutingPacket, RoutingMessage, SpliceInit, UnknownMessage, Warning, WillAddHtlc, WillFailHtlc, WillFailMalformedHtlc}

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
           wallet: OnchainPubkeyCache,
           channelFactory: Peer.ChannelFactory,
           switchboard: ActorRef,
           register: ActorRef,
           router: typed.ActorRef[Router.GetNodeId],
           pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command]) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  private var pendingOnTheFlyFunding = Map.empty[ByteVector32, OnTheFlyFunding.Pending]

  context.system.eventStream.subscribe(self, classOf[CurrentFeerates])
  context.system.eventStream.subscribe(self, classOf[CurrentBlockHeight])

  startWith(INSTANTIATING, Nothing)

  when(INSTANTIATING) {
    case Event(init: Init, _) =>
      pendingOnTheFlyFunding = init.pendingOnTheFlyFunding
      val channels = init.storedChannels.map { state =>
        val channel = spawnChannel()
        channel ! INPUT_RESTORED(state)
        FinalChannelId(state.channelId) -> channel
      }.toMap
      context.system.eventStream.publish(PeerCreated(self, remoteNodeId))
      goto(DISCONNECTED) using DisconnectedData(channels) // when we restart, we will attempt to reconnect right away, but then we'll wait
  }

  when(DISCONNECTED) {
    case Event(p: Peer.Connect, _) =>
      reconnectionTask forward p
      stay()

    case Event(connectionReady: PeerConnection.ConnectionReady, d: DisconnectedData) =>
      gotoConnected(connectionReady, d.channels.map { case (k: ChannelId, v) => (k, v) })

    case Event(Terminated(actor), d: DisconnectedData) if d.channels.values.toSet.contains(actor) =>
      // we have at most 2 ids: a TemporaryChannelId and a FinalChannelId
      val channelIds = d.channels.filter(_._2 == actor).keys
      log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
      val channels1 = d.channels -- channelIds
      if (channels1.isEmpty && !pendingSignedOnTheFlyFunding()) {
        log.info("that was the last open channel")
        context.system.eventStream.publish(LastChannelClosed(self, remoteNodeId))
        // We have no existing channels or pending signed transaction, we can forget about this peer.
        stopPeer()
      } else {
        stay() using d.copy(channels = channels1)
      }

    case Event(ConnectionDown(_), d: DisconnectedData) =>
      Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
        log.debug("connection lost while negotiating connection")
      }
      if (d.channels.isEmpty && !pendingSignedOnTheFlyFunding()) {
        // We have no existing channels or pending signed transaction, we can forget about this peer.
        stopPeer()
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
        val channel = spawnChannel()
        c.timeout_opt.map(openTimeout => context.system.scheduler.scheduleOnce(openTimeout.duration, channel, Channel.TickChannelOpenTimeout)(context.dispatcher))
        val dualFunded = Features.canUseFeature(d.localFeatures, d.remoteFeatures, Features.DualFunding)
        val requireConfirmedInputs = c.requireConfirmedInputsOverride_opt.getOrElse(nodeParams.channelConf.requireConfirmedInputsForDualFunding)
        val temporaryChannelId = if (dualFunded) {
          Helpers.dualFundedTemporaryChannelId(nodeParams, localParams, channelConfig)
        } else {
          randomBytes32()
        }
        val fundingTxFeerate = c.fundingTxFeerate_opt.getOrElse(nodeParams.onChainFeeConf.getFundingFeerate(nodeParams.currentBitcoinCoreFeerates))
        val commitTxFeerate = nodeParams.onChainFeeConf.getCommitmentFeerate(nodeParams.currentBitcoinCoreFeerates, remoteNodeId, channelType.commitmentFormat, c.fundingAmount)
        log.info(s"requesting a new channel with type=$channelType fundingAmount=${c.fundingAmount} dualFunded=$dualFunded pushAmount=${c.pushAmount_opt} fundingFeerate=$fundingTxFeerate temporaryChannelId=$temporaryChannelId localParams=$localParams")
        channel ! INPUT_INIT_CHANNEL_INITIATOR(temporaryChannelId, c.fundingAmount, dualFunded, commitTxFeerate, fundingTxFeerate, c.fundingTxFeeBudget_opt, c.pushAmount_opt, requireConfirmedInputs, c.requestFunding_opt, localParams, d.peerConnection, d.remoteInit, c.channelFlags_opt.getOrElse(nodeParams.channelConf.channelFlags), channelConfig, channelType, c.channelOrigin, replyTo)
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
          case None if Features.canUseFeature(d.localFeatures, d.remoteFeatures, Features.DualFunding) =>
            openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Right(open), d.localFeatures, d.remoteFeatures, d.peerConnection.toTyped, d.address)
            stay()
          case None =>
            log.info("rejecting open_channel2: dual funding is not supported")
            self ! Peer.OutgoingMessage(Error(open.temporaryChannelId, "dual funding is not supported"), d.peerConnection)
            stay()
          case Some(_) =>
            log.warning("ignoring open_channel2 with duplicate temporaryChannelId={}", open.temporaryChannelId)
            stay()
        }

      case Event(SpawnChannelNonInitiator(open, channelConfig, channelType, addFunding_opt, localParams, peerConnection), d: ConnectedData) =>
        val temporaryChannelId = open.fold(_.temporaryChannelId, _.temporaryChannelId)
        if (peerConnection == d.peerConnection) {
          OnTheFlyFunding.validateOpen(open, pendingOnTheFlyFunding) match {
            case reject: OnTheFlyFunding.ValidationResult.Reject =>
              log.warning("rejecting on-the-fly channel: {}", reject.cancel.toAscii)
              self ! Peer.OutgoingMessage(reject.cancel, d.peerConnection)
              cancelUnsignedOnTheFlyFunding(reject.paymentHashes)
              context.system.eventStream.publish(ChannelAborted(ActorRef.noSender, remoteNodeId, temporaryChannelId))
              stay()
            case accept: OnTheFlyFunding.ValidationResult.Accept =>
              val channel = spawnChannel()
              log.info(s"accepting a new channel with type=$channelType temporaryChannelId=$temporaryChannelId localParams=$localParams")
              open match {
                case Left(open) =>
                  channel ! INPUT_INIT_CHANNEL_NON_INITIATOR(open.temporaryChannelId, None, dualFunded = false, None, requireConfirmedInputs = false, localParams, d.peerConnection, d.remoteInit, channelConfig, channelType)
                  channel ! open
                case Right(open) =>
                  val requireConfirmedInputs = nodeParams.channelConf.requireConfirmedInputsForDualFunding
                  channel ! INPUT_INIT_CHANNEL_NON_INITIATOR(open.temporaryChannelId, addFunding_opt, dualFunded = true, None, requireConfirmedInputs, localParams, d.peerConnection, d.remoteInit, channelConfig, channelType)
                  channel ! open
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
        val htlc = WillAddHtlc(nodeParams.chainHash, randomBytes32(), cmd.amount, cmd.paymentHash, cmd.expiry, cmd.onion, cmd.nextBlindingKey_opt)
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
                  proposed = pending.proposed :+ OnTheFlyFunding.Proposal(htlc, cmd.upstream),
                  status = OnTheFlyFunding.Status.Proposed(timer)
                )
              case status: OnTheFlyFunding.Status.Funded =>
                log.info("received extra payment for on-the-fly funding that has already been funded with txId={} (payment_hash={}, amount={})", status.txId, cmd.paymentHash, cmd.amount)
                pending.copy(proposed = pending.proposed :+ OnTheFlyFunding.Proposal(htlc, cmd.upstream))
            }
          case None =>
            self ! Peer.OutgoingMessage(htlc, d.peerConnection)
            Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Proposed).increment()
            val timer = context.system.scheduler.scheduleOnce(nodeParams.onTheFlyFundingConfig.proposalTimeout, self, OnTheFlyFundingTimeout(cmd.paymentHash))(context.dispatcher)
            OnTheFlyFunding.Pending(Seq(OnTheFlyFunding.Proposal(htlc, cmd.upstream)), OnTheFlyFunding.Status.Proposed(timer))
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
                      case msg: WillFailHtlc => Left(msg.reason)
                      case msg: WillFailMalformedHtlc => Right(createBadOnionFailure(msg.onionHash, msg.failureCode))
                    }
                    htlc.createFailureCommands(Some(failure)).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
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
                pending.createFailureCommands().foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
                Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Expired).increment()
                pendingOnTheFlyFunding -= timeout.paymentHash
                self ! Peer.OutgoingMessage(Warning(s"on-the-fly funding proposal timed out for payment_hash=${timeout.paymentHash}"), d.peerConnection)
              case status: OnTheFlyFunding.Status.Funded =>
                log.warning("ignoring on-the-fly funding proposal timeout, already funded with txId={}", status.txId)
            }
          case None =>
            log.debug("ignoring on-the-fly funding timeout for payment_hash={} (already completed)", timeout.paymentHash)
        }
        stay()

      case Event(msg: SpliceInit, d: ConnectedData) =>
        d.channels.get(FinalChannelId(msg.channelId)) match {
          case Some(channel) =>
            OnTheFlyFunding.validateSplice(msg, nodeParams.channelConf.htlcMinimum, pendingOnTheFlyFunding) match {
              case reject: OnTheFlyFunding.ValidationResult.Reject =>
                log.warning("rejecting on-the-fly splice: {}", reject.cancel.toAscii)
                self ! Peer.OutgoingMessage(reject.cancel, d.peerConnection)
                cancelUnsignedOnTheFlyFunding(reject.paymentHashes)
              case accept: OnTheFlyFunding.ValidationResult.Accept =>
                fulfillOnTheFlyFundingHtlcs(accept.preimages)
                channel forward msg
            }
          case None => replyUnknownChannel(d.peerConnection, msg.channelId)
        }
        stay()

      case Event(e: ChannelReadyForPayments, _: ConnectedData) =>
        pendingOnTheFlyFunding.foreach {
          case (paymentHash, pending) =>
            pending.status match {
              case _: OnTheFlyFunding.Status.Proposed => ()
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
        stay()

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
        if (d.channels.isEmpty && !pendingSignedOnTheFlyFunding()) {
          // We have no existing channels or pending signed transaction, we can forget about this peer.
          stopPeer()
        } else {
          d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
          goto(DISCONNECTED) using DisconnectedData(d.channels.collect { case (k: FinalChannelId, v) => (k, v) })
        }

      case Event(Terminated(actor), d: ConnectedData) if d.channels.values.toSet.contains(actor) =>
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

      case Event(connectionReady: PeerConnection.ConnectionReady, d: ConnectedData) =>
        log.debug(s"got new connection, killing current one and switching")
        d.peerConnection ! PeerConnection.Kill(KillReason.ConnectionReplaced)
        d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        gotoConnected(connectionReady, d.channels)

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

      case Event(unknownMsg: UnknownMessage, d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        context.system.eventStream.publish(UnknownMessageReceived(self, remoteNodeId, unknownMsg, d.connectionInfo))
        stay()

      case Event(RelayUnknownMessage(unknownMsg: UnknownMessage), d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        logMessage(unknownMsg, "OUT")
        d.peerConnection forward unknownMsg
        stay()

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
      replyTo ! PeerInfo(self, remoteNodeId, stateName, d match {
        case c: ConnectedData => Some(c.address)
        case _ => None
      }, d.channels.values.toSet)
      stay()

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
        case d: ConnectedData => d.peerConnection ! nodeParams.recommendedFeerates(remoteNodeId, d.localFeatures, d.remoteFeatures)
        case _ => ()
      }
      stay()

    case Event(current: CurrentBlockHeight, d) =>
      // If we have pending will_add_htlc that are timing out, it doesn't make any sense to keep them, even if we have
      // already funded the corresponding channel: our peer will force-close if we relay them.
      // Our only option is to fail the upstream HTLCs to ensure that the upstream channels don't force-close.
      // Note that we won't be paid for the liquidity we've provided, but we don't have a choice.
      val expired = pendingOnTheFlyFunding.filter {
        case (_, pending) => pending.proposed.exists(_.htlc.expiry.blockHeight <= current.blockHeight)
      }
      expired.foreach {
        case (paymentHash, pending) =>
          log.warning("will_add_htlc expired for payment_hash={}, our peer may be malicious", paymentHash)
          Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.Timeout).increment()
          pending.createFailureCommands().foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
      }
      expired.foreach {
        case (paymentHash, pending) => pending.status match {
          case _: OnTheFlyFunding.Status.Proposed => ()
          case _: OnTheFlyFunding.Status.Funded => nodeParams.db.liquidity.removePendingOnTheFlyFunding(remoteNodeId, paymentHash)
        }
      }
      pendingOnTheFlyFunding = pendingOnTheFlyFunding.removedAll(expired.keys)
      d match {
        case d: DisconnectedData if d.channels.isEmpty && pendingOnTheFlyFunding.isEmpty => stopPeer()
        case _ => stay()
      }

    case Event(e: LiquidityPurchaseSigned, _: ConnectedData) =>
      // We signed a liquidity purchase from our peer. At that point we're not 100% sure yet it will succeed: if
      // we disconnect before our peer sends their signature, the funding attempt may be cancelled when reconnecting.
      // If that happens, the on-the-fly proposal will stay in our state until we reach the CLTV expiry, at which
      // point we will forget it and fail the upstream HTLCs. This is also what would happen if we successfully
      // funded the channel, but it closed before we could relay the HTLCs.
      val (paymentHashes, fees) = e.purchase.paymentDetails match {
        case PaymentDetails.FromChannelBalance => (Nil, 0 sat)
        case p: PaymentDetails.FromChannelBalanceForFutureHtlc => (p.paymentHashes, 0 sat)
        case p: PaymentDetails.FromFutureHtlc => (p.paymentHashes, e.purchase.fees.total)
        case p: PaymentDetails.FromFutureHtlcWithPreimage => (p.preimages.map(preimage => Crypto.sha256(preimage)), e.purchase.fees.total)
      }
      // We split the fees across payments. We could dynamically re-split depending on whether some payments are failed
      // instead of fulfilled, but that's overkill: if our peer fails one of those payment, they're likely malicious
      // and will fail anyway, even if we try to be clever with fees splitting.
      var remainingFees = fees.toMilliSatoshi
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
                case OnTheFlyFunding.Proposal(htlc, upstream) => upstream match {
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
          stay()
        case OnTheFlyFunding.PaymentRelayer.RelayFailed(paymentHash, failure) =>
          log.warning("on-the-fly HTLC failure for payment_hash={}: {}", paymentHash, failure.toString)
          Metrics.OnTheFlyFunding.withTag(Tags.OnTheFlyFundingState, Tags.OnTheFlyFundingStates.relayFailed(failure)).increment()
          // We don't give up yet by relaying the failure upstream: we may have simply been disconnected, or the added
          // liquidity may have been consumed by concurrent HTLCs. We'll retry at the next reconnection with that peer
          // or after the next splice, and will only give up when the outgoing will_add_htlc timeout.
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

  private def gotoConnected(connectionReady: PeerConnection.ConnectionReady, channels: Map[ChannelId, ActorRef]): State = {
    require(remoteNodeId == connectionReady.remoteNodeId, s"invalid nodeid: $remoteNodeId != ${connectionReady.remoteNodeId}")
    log.debug("got authenticated connection to address {}", connectionReady.address)

    if (connectionReady.outgoing) {
      // we store the node address upon successful outgoing connection, so we can reconnect later
      // any previous address is overwritten
      nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, connectionReady.address)
    }

    // let's bring existing/requested channels online
    channels.values.toSet[ActorRef].foreach(_ ! INPUT_RECONNECTED(connectionReady.peerConnection, connectionReady.localInit, connectionReady.remoteInit)) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)

    // We tell our peer what our current feerates are.
    connectionReady.peerConnection ! nodeParams.recommendedFeerates(remoteNodeId, connectionReady.localInit.features, connectionReady.remoteInit.features)

    goto(CONNECTED) using ConnectedData(connectionReady.address, connectionReady.peerConnection, connectionReady.localInit, connectionReady.remoteInit, channels)
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

  private def spawnChannel(): ActorRef = {
    val channel = channelFactory.spawn(context, remoteNodeId)
    context watch channel
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
            status.timer.cancel()
            true
          case _: OnTheFlyFunding.Status.Funded => false
        }
      case _ => false
    }
    unsigned.foreach {
      case (paymentHash, pending) =>
        log.info("cancelling on-the-fly funding for payment_hash={}", paymentHash)
        pending.createFailureCommands().foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
    }
    pendingOnTheFlyFunding = pendingOnTheFlyFunding.removedAll(unsigned.keys)
  }

  private def fulfillOnTheFlyFundingHtlcs(preimages: Set[ByteVector32]): Unit = {
    preimages.foreach(preimage => pendingOnTheFlyFunding.get(Crypto.sha256(preimage)) match {
      case Some(pending) => pending.createFulfillCommands(preimage).foreach { case (channelId, cmd) => PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd) }
      case None => ()
    })
  }

  /** Return true if we have signed on-the-fly funding transactions and haven't settled the corresponding HTLCs yet. */
  private def pendingSignedOnTheFlyFunding(): Boolean = {
    pendingOnTheFlyFunding.exists {
      case (_, pending) => pending.status match {
        case _: OnTheFlyFunding.Status.Proposed => false
        case _: OnTheFlyFunding.Status.Funded => true
      }
    }
  }

  // resume the openChannelInterceptor in case of failure, we always want the open channel request to succeed or fail
  private val openChannelInterceptor = context.spawnAnonymous(Behaviors.supervise(OpenChannelInterceptor(context.self.toTyped, nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter)).onFailure(typed.SupervisorStrategy.resume))

  private def stopPeer(): State = {
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

}

object Peer {

  val CHANNELID_ZERO: ByteVector32 = ByteVector32.Zeroes

  trait ChannelFactory {
    def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef
  }

  case class SimpleChannelFactory(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, wallet: OnChainChannelFunder with OnchainPubkeyCache, txPublisherFactory: Channel.TxPublisherFactory) extends ChannelFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef =
      context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, relayer, txPublisherFactory))
  }

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, wallet: OnchainPubkeyCache, channelFactory: ChannelFactory, switchboard: ActorRef, register: ActorRef, router: typed.ActorRef[Router.GetNodeId], pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command]): Props =
    Props(new Peer(nodeParams, remoteNodeId, wallet, channelFactory, switchboard, register, router, pendingChannelsRateLimiter))

  // @formatter:off

  // used to identify the logger for raw messages
  case class MessageLogs()

  sealed trait ChannelId { def id: ByteVector32 }
  case class TemporaryChannelId(id: ByteVector32) extends ChannelId
  case class FinalChannelId(id: ByteVector32) extends ChannelId

  sealed trait Data {
    def channels: Map[_ <: ChannelId, ActorRef] // will be overridden by Map[FinalChannelId, ActorRef] or Map[ChannelId, ActorRef]
  }
  case object Nothing extends Data { override def channels = Map.empty }
  case class DisconnectedData(channels: Map[FinalChannelId, ActorRef]) extends Data
  case class ConnectedData(address: NodeAddress, peerConnection: ActorRef, localInit: protocol.Init, remoteInit: protocol.Init, channels: Map[ChannelId, ActorRef]) extends Data {
    val connectionInfo: ConnectionInfo = ConnectionInfo(address, peerConnection, localInit, remoteInit)
    def localFeatures: Features[InitFeature] = localInit.features
    def remoteFeatures: Features[InitFeature] = remoteInit.features
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
                         disableMaxHtlcValueInFlight: Boolean = false,
                         channelOrigin: ChannelOrigin = ChannelOrigin.Default) extends PossiblyHarmful {
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

  case class SpawnChannelInitiator(replyTo: akka.actor.typed.ActorRef[OpenChannelResponse], cmd: Peer.OpenChannel, channelConfig: ChannelConfig, channelType: SupportedChannelType, localParams: LocalParams)
  case class SpawnChannelNonInitiator(open: Either[protocol.OpenChannel, protocol.OpenDualFundedChannel], channelConfig: ChannelConfig, channelType: SupportedChannelType, addFunding_opt: Option[LiquidityAds.AddFunding], localParams: LocalParams, peerConnection: ActorRef)

  /** If [[Features.OnTheFlyFunding]] is supported and we're connected, relay a funding proposal to our peer. */
  case class ProposeOnTheFlyFunding(replyTo: typed.ActorRef[ProposeOnTheFlyFundingResponse], amount: MilliSatoshi, paymentHash: ByteVector32, expiry: CltvExpiry, onion: OnionRoutingPacket, nextBlindingKey_opt: Option[PublicKey], upstream: Upstream.Hot)

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
  case class PeerInfo(peer: ActorRef, nodeId: PublicKey, state: State, address: Option[NodeAddress], channels: Set[ActorRef]) extends PeerInfoResponse
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

  case class RelayOnionMessage(messageId: ByteVector32, msg: OnionMessage, replyTo_opt: Option[typed.ActorRef[Status]])

  case class RelayUnknownMessage(unknownMessage: UnknownMessage)
  // @formatter:on
}
