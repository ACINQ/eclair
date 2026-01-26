/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.payment.relay

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.io.Peer.ProposeOnTheFlyFundingResponse
import fr.acinq.eclair.io.{Peer, PeerReadyNotifier}
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.relay.Relayer.{OutgoingChannel, OutgoingChannelParams}
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPaymentPacket, PaymentEvent, PaymentNotRelayed}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.reputation.ReputationRecorder.GetConfidence
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs.createBadOnionFailure
import fr.acinq.eclair.wire.protocol.PaymentOnion.IntermediatePayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{EncodedNodeId, Features, InitFeature, Logs, NodeParams, TimestampMilli, TimestampSecond, channel, nodeFee}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.Random

object ChannelRelay {

  // @formatter:off
  sealed trait Command
  private case object DoRelay extends Command
  private case class WrappedPeerReadyResult(result: PeerReadyNotifier.Result) extends Command
  private case class WrappedReputationScore(score: Reputation.Score) extends Command
  private case class WrappedForwardFailure(failure: Register.ForwardFailure[CMD_ADD_HTLC]) extends Command
  private case class WrappedAddResponse(res: CommandResponse[CMD_ADD_HTLC]) extends Command
  private case class WrappedOnTheFlyFundingResponse(result: Peer.ProposeOnTheFlyFundingResponse) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait RelayResult
  case class RelayFailure(cmdFail: CMD_FAIL_HTLC) extends RelayResult
  case class RelayNeedsFunding(nextNode: PublicKey, cmdFail: CMD_FAIL_HTLC) extends RelayResult
  case class RelaySuccess(selectedChannelId: ByteVector32, cmdAdd: CMD_ADD_HTLC) extends RelayResult
  // @formatter:on

  def apply(nodeParams: NodeParams,
            register: ActorRef,
            reputationRecorder_opt: Option[typed.ActorRef[GetConfidence]],
            channels: Map[ByteVector32, Relayer.OutgoingChannel],
            originNode: PublicKey,
            relayId: UUID,
            r: IncomingPaymentPacket.ChannelRelayPacket,
            incomingChannelOccupancy: Double): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a channel relay, parent payment id = relay id
        paymentHash_opt = Some(r.add.paymentHash),
        nodeAlias_opt = Some(nodeParams.alias))) {
        val upstream = Upstream.Hot.Channel(r.add.removeUnknownTlvs(), r.receivedAt, originNode, incomingChannelOccupancy)
        val accountable0 = r.add.accountable || nodeParams.relayParams.incomingChannelCongested(upstream.incomingChannelOccupancy)
        val accountable = if (accountable0 && !r.payload.upgradeAccountability) {
          // We don't yet enforce channel jamming protections: we log and update metrics as if we had failed that payment,
          // but we currently relay it anyway. This will let us analyze data before actually activating jamming protection.
          Metrics.recordPaymentRelayFailed(Tags.FailureType.Jamming, Tags.RelayType.Channel)
          context.log.info("payment would have been rejected if jamming protection was activated")
          false
        } else {
          accountable0
        }
        val nextNodeId_opt = channels.values.headOption.map(_.nextNodeId)
        (reputationRecorder_opt, nextNodeId_opt) match {
          case (Some(reputationRecorder), Some(nextNodeId)) =>
            reputationRecorder ! GetConfidence(context.messageAdapter(WrappedReputationScore(_)), nextNodeId, r.relayFeeMsat, nodeParams.currentBlockHeight, r.outgoingCltv, accountable)
          case _ =>
            context.self ! WrappedReputationScore(Reputation.Score.max(accountable))
        }
        Behaviors.receiveMessagePartial {
          case WrappedReputationScore(score) =>
            new ChannelRelay(nodeParams, register, channels, r, upstream, score, context).start()
        }
      }
    }

  /**
   * This helper method translates relaying errors (returned by the downstream outgoing channel) to BOLT 4 standard
   * errors that we should return upstream.
   */
  private def translateLocalError(error: ChannelException, channelUpdate_opt: Option[ChannelUpdate]): FailureMessage = {
    (error, channelUpdate_opt) match {
      case (_: ExpiryTooSmall, Some(channelUpdate)) => ExpiryTooSoon(Some(channelUpdate))
      case (_: ExpiryTooBig, _) => ExpiryTooFar()
      case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(Some(channelUpdate))
      case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(Some(channelUpdate))
      case (_: HtlcValueTooHighInFlight, Some(channelUpdate)) => TemporaryChannelFailure(Some(channelUpdate))
      case (_: LocalDustHtlcExposureTooHigh, Some(channelUpdate)) => TemporaryChannelFailure(Some(channelUpdate))
      case (_: RemoteDustHtlcExposureTooHigh, Some(channelUpdate)) => TemporaryChannelFailure(Some(channelUpdate))
      case (_: FeerateTooDifferent, Some(channelUpdate)) => TemporaryChannelFailure(Some(channelUpdate))
      case (_: ChannelUnavailable, Some(channelUpdate)) if !channelUpdate.channelFlags.isEnabled => ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, Some(channelUpdate))
      case (_: ChannelUnavailable, None) => PermanentChannelFailure()
      case _ => TemporaryNodeFailure()
    }
  }

  def translateRelayFailure(originHtlcId: Long, fail: HtlcResult.Fail, htlcReceivedAt_opt: Option[TimestampMilli]): CMD_FAIL_HTLC = {
    val attribution_opt = htlcReceivedAt_opt.map(receivedAt => FailureAttributionData(htlcReceivedAt = receivedAt, trampolineReceivedAt_opt = None))
    fail match {
      case f: HtlcResult.RemoteFail => CMD_FAIL_HTLC(originHtlcId, FailureReason.EncryptedDownstreamFailure(f.fail.reason, f.fail.attribution_opt), attribution_opt, commit = true)
      case f: HtlcResult.RemoteFailMalformed => CMD_FAIL_HTLC(originHtlcId, FailureReason.LocalFailure(createBadOnionFailure(f.fail.onionHash, f.fail.failureCode)), attribution_opt, commit = true)
      case _: HtlcResult.OnChainFail => CMD_FAIL_HTLC(originHtlcId, FailureReason.LocalFailure(PermanentChannelFailure()), attribution_opt, commit = true)
      case HtlcResult.ChannelFailureBeforeSigned => CMD_FAIL_HTLC(originHtlcId, FailureReason.LocalFailure(PermanentChannelFailure()), attribution_opt, commit = true)
      case f: HtlcResult.DisconnectedBeforeSigned => CMD_FAIL_HTLC(originHtlcId, FailureReason.LocalFailure(TemporaryChannelFailure(Some(f.channelUpdate))), attribution_opt, commit = true)
    }
  }

}

/**
 * see https://doc.akka.io/docs/akka/current/typed/style-guide.html#passing-around-too-many-parameters
 */
class ChannelRelay private(nodeParams: NodeParams,
                           register: ActorRef,
                           channels: Map[ByteVector32, Relayer.OutgoingChannel],
                           r: IncomingPaymentPacket.ChannelRelayPacket,
                           upstream: Upstream.Hot.Channel,
                           reputationScore: Reputation.Score,
                           context: ActorContext[ChannelRelay.Command]) {

  import ChannelRelay._

  private val forwardFailureAdapter = context.messageAdapter[Register.ForwardFailure[CMD_ADD_HTLC]](WrappedForwardFailure)
  private val addResponseAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedAddResponse)
  private val forwardNodeIdFailureAdapter = context.messageAdapter[Register.ForwardNodeIdFailure[Peer.ProposeOnTheFlyFunding]](_ => WrappedOnTheFlyFundingResponse(Peer.ProposeOnTheFlyFundingResponse.NotAvailable("peer not found")))
  private val onTheFlyFundingResponseAdapter = context.messageAdapter[Peer.ProposeOnTheFlyFundingResponse](WrappedOnTheFlyFundingResponse)

  private val nextPathKey_opt = r.payload match {
    case payload: IntermediatePayload.ChannelRelay.Blinded => Some(payload.nextPathKey)
    case _: IntermediatePayload.ChannelRelay.Standard => None
  }

  /** Channel id explicitly requested in the onion payload. */
  private val requestedChannelId_opt = r.payload.outgoing match {
    case Left(_) => None
    case Right(outgoingChannelId) => channels.collectFirst {
      case (channelId, channel) if channel.aliases.localAlias == outgoingChannelId => channelId
      case (channelId, channel) if channel.realScid_opt.contains(outgoingChannelId) => channelId
    }
  }

  private val (requestedShortChannelId_opt, walletNodeId_opt) = r.payload.outgoing match {
    case Left(EncodedNodeId.WithPublicKey.Wallet(walletNodeId)) => (None, Some(walletNodeId))
    case Left(_) => (None, None)
    case Right(shortChannelId) => (Some(shortChannelId), None)
  }

  private case class PreviouslyTried(channelId: ByteVector32, failure: RES_ADD_FAILED[ChannelException])

  def start(): Behavior[Command] = {
    walletNodeId_opt match {
      case Some(walletNodeId) if nodeParams.peerWakeUpConfig.enabled => wakeUp(walletNodeId)
      case _ =>
        context.self ! DoRelay
        relay(None, Seq.empty)
    }
  }

  private def wakeUp(walletNodeId: PublicKey): Behavior[Command] = {
    context.log.info("trying to wake up channel peer (nodeId={})", walletNodeId)
    val notifier = context.spawnAnonymous(PeerReadyNotifier(walletNodeId, timeout_opt = Some(Left(nodeParams.peerWakeUpConfig.timeout))))
    notifier ! PeerReadyNotifier.NotifyWhenPeerReady(context.messageAdapter(WrappedPeerReadyResult))
    Behaviors.receiveMessagePartial {
      case WrappedPeerReadyResult(_: PeerReadyNotifier.PeerUnavailable) =>
        Metrics.recordPaymentRelayFailed(Tags.FailureType.WakeUp, Tags.RelayType.Channel)
        context.log.info("rejecting htlc: failed to wake-up remote peer")
        safeSendAndStop(r.add.channelId, makeCmdFailHtlc(r.add.id, UnknownNextPeer()))
      case WrappedPeerReadyResult(r: PeerReadyNotifier.PeerReady) =>
        context.self ! DoRelay
        relay(Some(r.remoteFeatures), Seq.empty)
    }
  }

  def relay(remoteFeatures_opt: Option[Features[InitFeature]], previousFailures: Seq[PreviouslyTried]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case DoRelay =>
        if (previousFailures.isEmpty) {
          val nextNodeId_opt = channels.headOption.map(_._2.nextNodeId)
          context.log.info("relaying htlc #{} from channelId={} to requestedShortChannelId={} nextNode={}", r.add.id, r.add.channelId, requestedShortChannelId_opt, nextNodeId_opt.getOrElse(""))
        }
        context.log.debug("attempting relay previousAttempts={}", previousFailures.size)
        handleRelay(remoteFeatures_opt, previousFailures) match {
          case RelayFailure(cmdFail) =>
            Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
            context.log.info("rejecting htlc reason={}", cmdFail.reason)
            channels.headOption.map(_._2.nextNodeId).foreach(nextNodeId => context.system.eventStream ! EventStream.Publish(PaymentNotRelayed(r.add.paymentHash, nextNodeId, fees = upstream.amountIn - r.amountToForward)))
            safeSendAndStop(r.add.channelId, cmdFail)
          case RelayNeedsFunding(nextNodeId, cmdFail) =>
            // Note that in the channel relay case, we don't have any outgoing onion shared secrets.
            val cmd = Peer.ProposeOnTheFlyFunding(onTheFlyFundingResponseAdapter, r.amountToForward, r.add.paymentHash, r.outgoingCltv, r.nextPacket, Nil, nextPathKey_opt, upstream)
            register ! Register.ForwardNodeId(forwardNodeIdFailureAdapter, nextNodeId, cmd)
            waitForOnTheFlyFundingResponse(cmdFail)
          case RelaySuccess(selectedChannelId, cmdAdd) =>
            context.log.info("forwarding htlc #{} from channelId={} to channelId={}", r.add.id, r.add.channelId, selectedChannelId)
            register ! Register.Forward(forwardFailureAdapter, selectedChannelId, cmdAdd)
            waitForAddResponse(selectedChannelId, remoteFeatures_opt, previousFailures)
        }
    }
  }

  private def waitForAddResponse(selectedChannelId: ByteVector32, remoteFeatures_opt: Option[Features[InitFeature]], previousFailures: Seq[PreviouslyTried]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedForwardFailure(Register.ForwardFailure(Register.Forward(_, channelId, _))) =>
        context.log.warn(s"couldn't resolve downstream channel $channelId, failing htlc #${upstream.add.id}")
        val cmdFail = makeCmdFailHtlc(upstream.add.id, UnknownNextPeer())
        Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
        channels.headOption.map(_._2.nextNodeId).foreach(nextNodeId => context.system.eventStream ! EventStream.Publish(PaymentNotRelayed(r.add.paymentHash, nextNodeId, fees = upstream.amountIn - r.amountToForward)))
        safeSendAndStop(upstream.add.channelId, cmdFail)

      case WrappedAddResponse(addFailed: RES_ADD_FAILED[_]) =>
        context.log.info("attempt failed with reason={}", addFailed.t.getClass.getSimpleName)
        context.self ! DoRelay
        relay(remoteFeatures_opt, previousFailures :+ PreviouslyTried(selectedChannelId, addFailed))

      case WrappedAddResponse(_: RES_SUCCESS[_]) =>
        context.log.debug("sent htlc to the downstream channel")
        waitForAddSettled()
    }

  private def waitForAddSettled(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedAddResponse(RES_ADD_SETTLED(_, remoteNodeId, htlc, fulfill: HtlcResult.Fulfill)) =>
        val now = TimestampMilli.now()
        context.log.info("relaying fulfill to upstream, receivedAt={}, endedAt={}, confidence={}, originNode={}, outgoingChannel={}", upstream.receivedAt, now, reputationScore.outgoingConfidence, upstream.receivedFrom, htlc.channelId)
        Metrics.relayFulfill(reputationScore.outgoingConfidence)
        val downstreamAttribution_opt = fulfill match {
          case HtlcResult.RemoteFulfill(fulfill) => fulfill.attribution_opt
          case HtlcResult.OnChainFulfill(_) => None
        }
        val attribution = FulfillAttributionData(htlcReceivedAt = upstream.receivedAt, trampolineReceivedAt_opt = None, downstreamAttribution_opt = downstreamAttribution_opt)
        val cmd = CMD_FULFILL_HTLC(upstream.add.id, fulfill.paymentPreimage, Some(attribution), commit = true)
        val incoming = PaymentEvent.IncomingPayment(upstream.add.channelId, upstream.receivedFrom, upstream.amountIn, upstream.receivedAt)
        val outgoing = PaymentEvent.OutgoingPayment(htlc.channelId, remoteNodeId, htlc.amountMsat, now)
        context.system.eventStream ! EventStream.Publish(ChannelPaymentRelayed(htlc.paymentHash, incoming, outgoing))
        recordRelayDuration(isSuccess = true)
        safeSendAndStop(upstream.add.channelId, cmd)
      case WrappedAddResponse(RES_ADD_SETTLED(_, _, htlc, fail: HtlcResult.Fail)) =>
        val now = TimestampMilli.now()
        context.log.info("relaying fail to upstream, receivedAt={}, endedAt={}, confidence={}, originNode={}, outgoingChannel={}", upstream.receivedAt, now, reputationScore.outgoingConfidence, upstream.receivedFrom, htlc.channelId)
        Metrics.relayFail(reputationScore.outgoingConfidence)
        Metrics.recordPaymentRelayFailed(Tags.FailureType.Remote, Tags.RelayType.Channel)
        val cmd = translateRelayFailure(upstream.add.id, fail, Some(upstream.receivedAt))
        recordRelayDuration(isSuccess = false)
        safeSendAndStop(upstream.add.channelId, cmd)
    }

  private def waitForOnTheFlyFundingResponse(cmdFail: CMD_FAIL_HTLC): Behavior[Command] = Behaviors.receiveMessagePartial {
    case WrappedOnTheFlyFundingResponse(response) =>
      response match {
        case ProposeOnTheFlyFundingResponse.Proposed =>
          context.log.info("on-the-fly funding proposed for htlc #{} from channelId={}", r.add.id, r.add.channelId)
          // We're not responsible for the payment relay anymore: another actor will take care of relaying the payment
          // once on-the-fly funding completes.
          Behaviors.stopped
        case ProposeOnTheFlyFundingResponse.NotAvailable(reason) =>
          context.log.warn("could not propose on-the-fly funding for htlc #{} from channelId={}: {}", r.add.id, r.add.channelId, reason)
          Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
          safeSendAndStop(r.add.channelId, cmdFail)
      }
  }

  private def safeSendAndStop(channelId: ByteVector32, cmd: channel.HtlcSettlementCommand): Behavior[Command] = {
    val toSend = cmd match {
      case _: CMD_FULFILL_HTLC => cmd
      case _: CMD_FAIL_HTLC | _: CMD_FAIL_MALFORMED_HTLC => r.payload match {
        case payload: IntermediatePayload.ChannelRelay.Blinded =>
          walletNodeId_opt match {
            case Some(_) =>
              // When the next node is a wallet node directly connected to us, we forward their failure downstream
              // because we don't need to protect the blinded path against probing since it only contains our node.
              // Their node isn't announced so they don't reveal anything by sending back a failure message (which
              // will be encrypted using their blinded node_id).
              cmd match {
                // However, when the failure comes from us, we don't want to leak the unannounced channel by revealing
                // its channel_update: in that case, we always return a temporary node failure instead.
                case cmd@CMD_FAIL_HTLC(_, FailureReason.LocalFailure(_: Update), _, _, _, _) => cmd.copy(reason = FailureReason.LocalFailure(TemporaryNodeFailure()))
                case _ => cmd
              }
            case None =>
              // We are inside a blinded route, so we must carefully choose the error we return to avoid leaking information.
              val failure = InvalidOnionBlinding(Sphinx.hash(r.add.onionRoutingPacket))
              payload.records.get[OnionPaymentPayloadTlv.PathKey] match {
                case Some(_) =>
                  // We are the introduction node: we add a delay to make it look like it could come from further downstream.
                  val delay = Some(Random.nextLong(1000).millis)
                  makeCmdFailHtlc(cmd.id, failure, delay)
                case None =>
                  // We are not the introduction node.
                  CMD_FAIL_MALFORMED_HTLC(cmd.id, failure.onionHash, failure.code, commit = true)
              }
          }
        case _: IntermediatePayload.ChannelRelay.Standard => cmd
      }
    }
    // NB: we are not using an adapter here because we are stopping anyway so we won't be there to get the result
    PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, toSend)
    Behaviors.stopped
  }

  /**
   * Handle an incoming htlc when we are a relaying node.
   *
   * @return either:
   *         - a CMD_FAIL_HTLC to be sent back upstream
   *         - a CMD_ADD_HTLC to propagate downstream
   */
  private def handleRelay(remoteFeatures_opt: Option[Features[InitFeature]], previousFailures: Seq[PreviouslyTried]): RelayResult = {
    val alreadyTried = previousFailures.map(_.channelId)
    selectPreferredChannel(alreadyTried) match {
      case Some(outgoingChannel) => relayOrFail(outgoingChannel)
      case None =>
        // No more channels to try.
        val cmdFail = if (previousFailures.nonEmpty) {
          val error = previousFailures
            // We return the error for the initially requested channel if it exists.
            .find(failure => requestedChannelId_opt.contains(failure.channelId))
            // Otherwise we return the error for the first channel tried.
            .getOrElse(previousFailures.head)
            .failure
          makeCmdFailHtlc(r.add.id, translateLocalError(error.t, error.channelUpdate))
        } else {
          makeCmdFailHtlc(r.add.id, UnknownNextPeer())
        }
        walletNodeId_opt match {
          case Some(walletNodeId) if shouldAttemptOnTheFlyFunding(remoteFeatures_opt, previousFailures) => RelayNeedsFunding(walletNodeId, cmdFail)
          case _ => RelayFailure(cmdFail)
        }
    }
  }

  /**
   * Select a channel to the same node to relay the payment to, that has the lowest capacity and balance and is
   * compatible in terms of fees, expiry_delta, etc.
   *
   * If no suitable channel is found we default to the originally requested channel.
   */
  private def selectPreferredChannel(alreadyTried: Seq[ByteVector32]): Option[OutgoingChannel] = {
    context.log.debug("selecting next channel with requestedShortChannelId={}", requestedShortChannelId_opt)
    // we filter out channels that we have already tried
    val candidateChannels: Map[ByteVector32, OutgoingChannel] = channels -- alreadyTried
    // and we filter again to keep the ones that are compatible with this payment (mainly fees, expiry delta)
    candidateChannels
      .values
      .map { channel =>
        val relayResult = relayOrFail(channel)
        context.log.debug("candidate channel: channelId={} availableForSend={} capacity={} channelUpdate={} result={}",
          channel.channelId,
          channel.commitments.availableBalanceForSend,
          channel.commitments.latest.capacity,
          channel.channelUpdate,
          relayResult match {
            case _: RelaySuccess => "success"
            case RelayFailure(CMD_FAIL_HTLC(_, FailureReason.LocalFailure(failureReason), _, _, _, _)) => failureReason
            case other => other
          })
        (channel, relayResult)
      }
      .collect {
        // we only keep channels that have enough balance to handle this payment
        case (channel, _: RelaySuccess) if channel.commitments.availableBalanceForSend > r.amountToForward => channel
      }
      .toList // needed for ordering
      // we want to use the channel with:
      //  - the lowest available capacity to ensure we keep high-capacity channels for big payments
      //  - the lowest available balance to increase our incoming liquidity
      .sortBy { channel => (channel.commitments.latest.capacity, channel.commitments.availableBalanceForSend) }
      .headOption match {
      case Some(channel) =>
        if (requestedChannelId_opt.contains(channel.channelId)) {
          context.log.debug("requested short channel id is our preferred channel")
          Some(channel)
        } else {
          context.log.debug("replacing requestedShortChannelId={} by preferredShortChannelId={} with availableBalanceMsat={}", requestedShortChannelId_opt, channel.channelUpdate.shortChannelId, channel.commitments.availableBalanceForSend)
          Some(channel)
        }
      case None =>
        val requestedChannel_opt = requestedChannelId_opt.flatMap(channels.get)
        requestedChannel_opt match {
          case Some(requestedChannel) if alreadyTried.contains(requestedChannel.channelId) =>
            context.log.debug("no channel seems to work for this payment and we have already tried the requested channel id: giving up")
            None
          case _ =>
            context.log.debug("no channel seems to work for this payment, we will try to use the one requested")
            requestedChannel_opt
        }
    }
  }

  /**
   * This helper method will tell us if it is not even worth attempting to relay the payment to our local outgoing
   * channel, because some parameters don't match with our settings for that channel. In that case we directly fail the
   * htlc.
   */
  private def relayOrFail(outgoingChannel: OutgoingChannelParams): RelayResult = {
    val update = outgoingChannel.channelUpdate
    validateRelayParams(outgoingChannel) match {
      case Some(fail) =>
        RelayFailure(fail)
      case None if !update.channelFlags.isEnabled =>
        RelayFailure(makeCmdFailHtlc(r.add.id, ChannelDisabled(update.messageFlags, update.channelFlags, Some(update))))
      case None =>
        val origin = Origin.Hot(addResponseAdapter.toClassic, upstream)
        RelaySuccess(outgoingChannel.channelId, CMD_ADD_HTLC(addResponseAdapter.toClassic, r.amountToForward, r.add.paymentHash, r.outgoingCltv, r.nextPacket, nextPathKey_opt, reputationScore, fundingFee_opt = None, origin, commit = true))
    }
  }

  private def validateRelayParams(outgoingChannel: OutgoingChannelParams): Option[CMD_FAIL_HTLC] = {
    val update = outgoingChannel.channelUpdate
    // If our current channel update was recently created, we accept payments that used our previous channel update.
    val allowPreviousUpdate = TimestampSecond.now() - update.timestamp <= nodeParams.relayParams.enforcementDelay
    val prevUpdate_opt = if (allowPreviousUpdate) outgoingChannel.prevChannelUpdate else None
    val htlcMinimumOk = update.htlcMinimumMsat <= r.amountToForward || prevUpdate_opt.exists(_.htlcMinimumMsat <= r.amountToForward)
    val expiryDeltaOk = update.cltvExpiryDelta <= r.expiryDelta || prevUpdate_opt.exists(_.cltvExpiryDelta <= r.expiryDelta)
    val feesOk = nodeFee(update.relayFees, r.amountToForward) <= r.relayFeeMsat || prevUpdate_opt.exists(u => nodeFee(u.relayFees, r.amountToForward) <= r.relayFeeMsat)
    if (!htlcMinimumOk) {
      Some(makeCmdFailHtlc(r.add.id, AmountBelowMinimum(r.amountToForward, Some(update))))
    } else if (!expiryDeltaOk) {
      Some(makeCmdFailHtlc(r.add.id, IncorrectCltvExpiry(r.outgoingCltv, Some(update))))
    } else if (!feesOk) {
      Some(makeCmdFailHtlc(r.add.id, FeeInsufficient(r.add.amountMsat, Some(update))))
    } else {
      None
    }
  }

  /** If we fail to relay a payment, we may want to attempt on-the-fly funding. */
  private def shouldAttemptOnTheFlyFunding(remoteFeatures_opt: Option[Features[InitFeature]], previousFailures: Seq[PreviouslyTried]): Boolean = {
    val featureOk = Features.canUseFeature(nodeParams.features.initFeatures(), remoteFeatures_opt.getOrElse(Features.empty), Features.OnTheFlyFunding)
    // If we have a channel with the next node, we only want to perform on-the-fly funding for liquidity issues.
    val liquidityIssue = previousFailures.forall {
      case PreviouslyTried(_, RES_ADD_FAILED(_, _: InsufficientFunds, _)) => true
      case _ => false
    }
    // If we have a channel with the next peer, but we skipped it because the sender is using invalid relay parameters,
    // we don't want to perform on-the-fly funding: the sender should send a valid payment first.
    val relayParamsOk = channels.values.forall(c => validateRelayParams(c).isEmpty)
    featureOk && liquidityIssue && relayParamsOk
  }

  private def makeCmdFailHtlc(originHtlcId: Long, failure: FailureMessage, delay_opt: Option[FiniteDuration] = None): CMD_FAIL_HTLC = {
    val attribution = FailureAttributionData(htlcReceivedAt = upstream.receivedAt, trampolineReceivedAt_opt = None)
    CMD_FAIL_HTLC(originHtlcId, FailureReason.LocalFailure(failure), Some(attribution), delay_opt, commit = true)
  }

  private def recordRelayDuration(isSuccess: Boolean): Unit =
    Metrics.RelayedPaymentDuration
      .withTag(Tags.Relay, Tags.RelayType.Channel)
      .withTag(Tags.Success, isSuccess)
      .record((TimestampMilli.now() - upstream.receivedAt).toMillis, TimeUnit.MILLISECONDS)
}
