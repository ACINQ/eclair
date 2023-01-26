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

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.relay.Relayer.{OutgoingChannel, OutgoingChannelParams}
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPaymentPacket}
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs.createBadOnionFailure
import fr.acinq.eclair.wire.protocol.PaymentOnion.IntermediatePayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, NodeParams, TimestampSecond, channel, nodeFee}

import java.util.UUID
import scala.concurrent.duration.DurationLong
import scala.util.Random

object ChannelRelay {

  // @formatter:off
  sealed trait Command
  private case object DoRelay extends Command
  private case class WrappedForwardFailure(failure: Register.ForwardFailure[CMD_ADD_HTLC]) extends Command
  private case class WrappedAddResponse(res: CommandResponse[CMD_ADD_HTLC]) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait RelayResult
  case class RelayFailure(cmdFail: CMD_FAIL_HTLC) extends RelayResult
  case class RelaySuccess(selectedChannelId: ByteVector32, cmdAdd: CMD_ADD_HTLC) extends RelayResult
  // @formatter:on

  def apply(nodeParams: NodeParams, register: ActorRef, channels: Map[ByteVector32, Relayer.OutgoingChannel], relayId: UUID, r: IncomingPaymentPacket.ChannelRelayPacket): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a channel relay, parent payment id = relay id
        paymentHash_opt = Some(r.add.paymentHash),
        nodeAlias_opt = Some(nodeParams.alias))) {
        context.self ! DoRelay
        new ChannelRelay(nodeParams, register, channels, r, context).relay(Seq.empty)
      }
    }

  /**
   * This helper method translates relaying errors (returned by the downstream outgoing channel) to BOLT 4 standard
   * errors that we should return upstream.
   */
  def translateLocalError(error: Throwable, channelUpdate_opt: Option[ChannelUpdate]): FailureMessage = {
    (error, channelUpdate_opt) match {
      case (_: ExpiryTooSmall, Some(channelUpdate)) => ExpiryTooSoon(channelUpdate)
      case (_: ExpiryTooBig, _) => ExpiryTooFar()
      case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: HtlcValueTooHighInFlight, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: LocalDustHtlcExposureTooHigh, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: RemoteDustHtlcExposureTooHigh, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: FeerateTooDifferent, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: ChannelUnavailable, Some(channelUpdate)) if !channelUpdate.channelFlags.isEnabled => ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)
      case (_: ChannelUnavailable, None) => PermanentChannelFailure()
      case _ => TemporaryNodeFailure()
    }
  }

  def translateRelayFailure(originHtlcId: Long, fail: HtlcResult.Fail): CMD_FAIL_HTLC = {
    fail match {
      case f: HtlcResult.RemoteFail => CMD_FAIL_HTLC(originHtlcId, Left(f.fail.reason), commit = true)
      case f: HtlcResult.RemoteFailMalformed => CMD_FAIL_HTLC(originHtlcId, Right(createBadOnionFailure(f.fail.onionHash, f.fail.failureCode)), commit = true)
      case _: HtlcResult.OnChainFail => CMD_FAIL_HTLC(originHtlcId, Right(PermanentChannelFailure()), commit = true)
      case HtlcResult.ChannelFailureBeforeSigned => CMD_FAIL_HTLC(originHtlcId, Right(PermanentChannelFailure()), commit = true)
      case f: HtlcResult.DisconnectedBeforeSigned => CMD_FAIL_HTLC(originHtlcId, Right(TemporaryChannelFailure(f.channelUpdate)), commit = true)
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
                           context: ActorContext[ChannelRelay.Command]) {

  import ChannelRelay._

  private val forwardFailureAdapter = context.messageAdapter[Register.ForwardFailure[CMD_ADD_HTLC]](WrappedForwardFailure)
  private val addResponseAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedAddResponse)

  private case class PreviouslyTried(channelId: ByteVector32, failure: RES_ADD_FAILED[ChannelException])

  def relay(previousFailures: Seq[PreviouslyTried]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case DoRelay =>
        if (previousFailures.isEmpty) {
          context.log.info(s"relaying htlc #${r.add.id} from channelId={} to requestedShortChannelId={} nextNode={}", r.add.channelId, r.payload.outgoingChannelId, nextNodeId_opt.getOrElse(""))
        }
        context.log.debug("attempting relay previousAttempts={}", previousFailures.size)
        handleRelay(previousFailures) match {
          case RelayFailure(cmdFail) =>
            Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
            context.log.info(s"rejecting htlc reason=${cmdFail.reason}")
            safeSendAndStop(r.add.channelId, cmdFail)
          case RelaySuccess(selectedChannelId, cmdAdd) =>
            context.log.info(s"forwarding htlc to channelId=$selectedChannelId")
            register ! Register.Forward(forwardFailureAdapter, selectedChannelId, cmdAdd)
            waitForAddResponse(selectedChannelId, previousFailures)
        }
    }
  }

  def waitForAddResponse(selectedChannelId: ByteVector32, previousFailures: Seq[PreviouslyTried]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedForwardFailure(Register.ForwardFailure(Register.Forward(_, channelId, CMD_ADD_HTLC(_, _, _, _, _, _, o: Origin.ChannelRelayedHot, _)))) =>
        context.log.warn(s"couldn't resolve downstream channel $channelId, failing htlc #${o.add.id}")
        val cmdFail = CMD_FAIL_HTLC(o.add.id, Right(UnknownNextPeer()), commit = true)
        Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
        safeSendAndStop(o.add.channelId, cmdFail)

      case WrappedAddResponse(addFailed@RES_ADD_FAILED(CMD_ADD_HTLC(_, _, _, _, _, _, _: Origin.ChannelRelayedHot, _), _, _)) =>
        context.log.info("attempt failed with reason={}", addFailed.t.getClass.getSimpleName)
        context.self ! DoRelay
        relay(previousFailures :+ PreviouslyTried(selectedChannelId, addFailed))

      case WrappedAddResponse(_: RES_SUCCESS[_]) =>
        context.log.debug("sent htlc to the downstream channel")
        waitForAddSettled()
    }

  def waitForAddSettled(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedAddResponse(RES_ADD_SETTLED(o: Origin.ChannelRelayedHot, htlc, fulfill: HtlcResult.Fulfill)) =>
        context.log.debug("relaying fulfill to upstream")
        val cmd = CMD_FULFILL_HTLC(o.originHtlcId, fulfill.paymentPreimage, commit = true)
        context.system.eventStream ! EventStream.Publish(ChannelPaymentRelayed(o.amountIn, o.amountOut, htlc.paymentHash, o.originChannelId, htlc.channelId))
        safeSendAndStop(o.originChannelId, cmd)

      case WrappedAddResponse(RES_ADD_SETTLED(o: Origin.ChannelRelayedHot, _, fail: HtlcResult.Fail)) =>
        context.log.debug("relaying fail to upstream")
        Metrics.recordPaymentRelayFailed(Tags.FailureType.Remote, Tags.RelayType.Channel)
        val cmd = translateRelayFailure(o.originHtlcId, fail)
        safeSendAndStop(o.originChannelId, cmd)
    }

  def safeSendAndStop(channelId: ByteVector32, cmd: channel.HtlcSettlementCommand): Behavior[Command] = {
    val toSend = cmd match {
      case _: CMD_FULFILL_HTLC => cmd
      case _: CMD_FAIL_HTLC | _: CMD_FAIL_MALFORMED_HTLC => r.payload match {
        case payload: IntermediatePayload.ChannelRelay.Blinded =>
          // We are inside a blinded route, so we must carefully choose the error we return to avoid leaking information.
          val failure = InvalidOnionBlinding(Sphinx.hash(r.add.onionRoutingPacket))
          payload.records.get[OnionPaymentPayloadTlv.BlindingPoint] match {
            case Some(_) =>
              // We are the introduction node: we add a delay to make it look like it could come from further downstream.
              val delay = Some(Random.nextLong(1000).millis)
              CMD_FAIL_HTLC(cmd.id, Right(failure), delay, commit = true)
            case None =>
              // We are not the introduction node.
              CMD_FAIL_MALFORMED_HTLC(cmd.id, failure.onionHash, failure.code, commit = true)
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
  def handleRelay(previousFailures: Seq[PreviouslyTried]): RelayResult = {
    val alreadyTried = previousFailures.map(_.channelId)
    selectPreferredChannel(alreadyTried) match {
      case None if previousFailures.nonEmpty =>
        // no more channels to try
        val error = previousFailures
          // we return the error for the initially requested channel if it exists
          .find(failure => requestedChannelId_opt.contains(failure.channelId))
          // otherwise we return the error for the first channel tried
          .getOrElse(previousFailures.head)
          .failure
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(translateLocalError(error.t, error.channelUpdate)), commit = true))
      case outgoingChannel_opt =>
        relayOrFail(outgoingChannel_opt)
    }
  }

  /** all the channels point to the same next node, we take the first one */
  private val nextNodeId_opt = channels.headOption.map(_._2.nextNodeId)

  /** channel id explicitly requested in the onion payload */
  private val requestedChannelId_opt = channels.collectFirst {
    case (channelId, channel) if channel.shortIds.localAlias == r.payload.outgoingChannelId => channelId
    case (channelId, channel) if channel.shortIds.real.toOption.contains(r.payload.outgoingChannelId) => channelId
  }

  /**
   * Select a channel to the same node to relay the payment to, that has the lowest capacity and balance and is
   * compatible in terms of fees, expiry_delta, etc.
   *
   * If no suitable channel is found we default to the originally requested channel.
   */
  def selectPreferredChannel(alreadyTried: Seq[ByteVector32]): Option[OutgoingChannel] = {
    val requestedShortChannelId = r.payload.outgoingChannelId
    context.log.debug("selecting next channel with requestedShortChannelId={}", requestedShortChannelId)
    // we filter out channels that we have already tried
    val candidateChannels: Map[ByteVector32, OutgoingChannel] = channels -- alreadyTried
    // and we filter again to keep the ones that are compatible with this payment (mainly fees, expiry delta)
    candidateChannels
      .values
      .map { channel =>
        val relayResult = relayOrFail(Some(channel))
        context.log.debug(s"candidate channel: channelId=${channel.channelId} availableForSend={} capacity={} channelUpdate={} result={}",
          channel.commitments.availableBalanceForSend,
          channel.commitments.latest.capacity,
          channel.channelUpdate,
          relayResult match {
            case _: RelaySuccess => "success"
            case RelayFailure(CMD_FAIL_HTLC(_, Right(failureReason), _, _, _)) => failureReason
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
          context.log.debug("replacing requestedShortChannelId={} by preferredShortChannelId={} with availableBalanceMsat={}", requestedShortChannelId, channel.channelUpdate.shortChannelId, channel.commitments.availableBalanceForSend)
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
  def relayOrFail(outgoingChannel_opt: Option[OutgoingChannelParams]): RelayResult = {
    outgoingChannel_opt match {
      case None =>
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(UnknownNextPeer()), commit = true))
      case Some(c) if !c.channelUpdate.channelFlags.isEnabled =>
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(ChannelDisabled(c.channelUpdate.messageFlags, c.channelUpdate.channelFlags, c.channelUpdate)), commit = true))
      case Some(c) if r.amountToForward < c.channelUpdate.htlcMinimumMsat =>
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(AmountBelowMinimum(r.amountToForward, c.channelUpdate)), commit = true))
      case Some(c) if r.expiryDelta < c.channelUpdate.cltvExpiryDelta =>
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(IncorrectCltvExpiry(r.outgoingCltv, c.channelUpdate)), commit = true))
      case Some(c) if r.relayFeeMsat < nodeFee(c.channelUpdate.relayFees, r.amountToForward) &&
        // fees also do not satisfy the previous channel update for `enforcementDelay` seconds after current update
        (TimestampSecond.now() - c.channelUpdate.timestamp > nodeParams.relayParams.enforcementDelay ||
          outgoingChannel_opt.flatMap(_.prevChannelUpdate).forall(c => r.relayFeeMsat < nodeFee(c.relayFees, r.amountToForward))) =>
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(FeeInsufficient(r.add.amountMsat, c.channelUpdate)), commit = true))
      case Some(c: OutgoingChannel) =>
        val origin = Origin.ChannelRelayedHot(addResponseAdapter.toClassic, r.add, r.amountToForward)
        val nextBlindingKey_opt = r.payload match {
          case payload: IntermediatePayload.ChannelRelay.Blinded => Some(payload.nextBlinding)
          case _: IntermediatePayload.ChannelRelay.Standard => None
        }
        RelaySuccess(c.channelId, CMD_ADD_HTLC(addResponseAdapter.toClassic, r.amountToForward, r.add.paymentHash, r.outgoingCltv, r.nextPacket, nextBlindingKey_opt, origin, commit = true))
    }
  }

}
