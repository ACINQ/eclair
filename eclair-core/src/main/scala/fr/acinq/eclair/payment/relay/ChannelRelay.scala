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

import java.util.UUID

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.PendingCommandsDb
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.relay.Relayer.OutgoingChannel
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPacket}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, NodeParams, ShortChannelId, channel, nodeFee}

object ChannelRelay {

  // @formatter:off
  sealed trait Command
  private case object DoRelay extends Command
  private case class WrappedForwardShortIdFailure(failure: Register.ForwardShortIdFailure[CMD_ADD_HTLC]) extends Command
  private case class WrappedAddResponse(res: CommandResponse[CMD_ADD_HTLC]) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait RelayResult
  case class RelayFailure(cmdFail: CMD_FAIL_HTLC) extends RelayResult
  case class RelaySuccess(shortChannelId: ShortChannelId, cmdAdd: CMD_ADD_HTLC) extends RelayResult
  // @formatter:on

  def apply(nodeParams: NodeParams, register: ActorRef, channels: Map[ShortChannelId, Relayer.OutgoingChannel], relayId: UUID, r: IncomingPacket.ChannelRelayPacket): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a channel relay, parent payment id = relay id
        paymentHash_opt = Some(r.add.paymentHash))) {
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
      case (_: ExpiryTooBig, _) => ExpiryTooFar
      case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: FeerateTooDifferent, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: ChannelUnavailable, Some(channelUpdate)) if !Announcements.isEnabled(channelUpdate.channelFlags) => ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)
      case (_: ChannelUnavailable, None) => PermanentChannelFailure
      case _ => TemporaryNodeFailure
    }
  }

  def translateRelayFailure(originHtlcId: Long, fail: HtlcResult.Fail): channel.Command with channel.HtlcSettlementCommand = {
    fail match {
      case f: HtlcResult.RemoteFail => CMD_FAIL_HTLC(originHtlcId, Left(f.fail.reason), commit = true)
      case f: HtlcResult.RemoteFailMalformed => CMD_FAIL_MALFORMED_HTLC(originHtlcId, f.fail.onionHash, f.fail.failureCode, commit = true)
      case _: HtlcResult.OnChainFail => CMD_FAIL_HTLC(originHtlcId, Right(PermanentChannelFailure), commit = true)
      case HtlcResult.ChannelFailureBeforeSigned => CMD_FAIL_HTLC(originHtlcId, Right(PermanentChannelFailure), commit = true)
      case f: HtlcResult.DisconnectedBeforeSigned => CMD_FAIL_HTLC(originHtlcId, Right(TemporaryChannelFailure(f.channelUpdate)), commit = true)
    }
  }

}

/**
 * see https://doc.akka.io/docs/akka/current/typed/style-guide.html#passing-around-too-many-parameters
 */
class ChannelRelay private(nodeParams: NodeParams,
                           register: ActorRef,
                           channels: Map[ShortChannelId, Relayer.OutgoingChannel],
                           r: IncomingPacket.ChannelRelayPacket,
                           context: ActorContext[ChannelRelay.Command]) {

  import ChannelRelay._

  private val forwardShortIdAdapter = context.messageAdapter[Register.ForwardShortIdFailure[CMD_ADD_HTLC]](WrappedForwardShortIdFailure)
  private val addResponseAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedAddResponse)

  private case class PreviouslyTried(shortChannelId: ShortChannelId, failure: RES_ADD_FAILED[ChannelException])

  def relay(previousFailures: Seq[PreviouslyTried]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case DoRelay =>
        if (previousFailures.isEmpty) {
          context.log.info(s"relaying htlc #${r.add.id} from channelId={} to requestedShortChannelId={} nextNode={}", r.add.channelId, r.payload.outgoingChannelId, nextNodeId_opt.getOrElse(""))
        }
        context.log.info("attempting relay previousAttempts={}", previousFailures.size)
        handleRelay(previousFailures) match {
          case RelayFailure(cmdFail) =>
            Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
            context.log.info(s"rejecting htlc reason=${cmdFail.reason}")
            safeSendAndStop(r.add.channelId, cmdFail)
          case RelaySuccess(selectedShortChannelId, cmdAdd) =>
            context.log.info(s"forwarding htlc to shortChannelId=$selectedShortChannelId")
            register ! Register.ForwardShortId(forwardShortIdAdapter.toClassic, selectedShortChannelId, cmdAdd)
            waitForAddResponse(selectedShortChannelId, previousFailures)
        }
    }
  }

  def waitForAddResponse(selectedShortChannelId: ShortChannelId, previousFailures: Seq[PreviouslyTried]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedForwardShortIdFailure(Register.ForwardShortIdFailure(Register.ForwardShortId(_, shortChannelId, CMD_ADD_HTLC(_, _, _, _, _, o: Origin.ChannelRelayedHot, _)))) =>
        context.log.warn(s"couldn't resolve downstream channel $shortChannelId, failing htlc #${o.add.id}")
        val cmdFail = CMD_FAIL_HTLC(o.add.id, Right(UnknownNextPeer), commit = true)
        Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
        safeSendAndStop(o.add.channelId, cmdFail)

      case WrappedAddResponse(addFailed@RES_ADD_FAILED(CMD_ADD_HTLC(_, _, _, _, _, _: Origin.ChannelRelayedHot, _), _, _)) =>
        context.log.info("attempt failed with reason={}", addFailed.t.getClass.getSimpleName)
        context.self ! DoRelay
        relay(previousFailures :+ PreviouslyTried(selectedShortChannelId, addFailed))

      case WrappedAddResponse(_: RES_SUCCESS[_]) =>
        context.log.debug("sent htlc to the downstream channel")
        waitForAddSettled()
    }

  def waitForAddSettled(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedAddResponse(RES_ADD_SETTLED(o: Origin.ChannelRelayedHot, htlc, fulfill: HtlcResult.Fulfill)) =>
        context.log.info("relaying fulfill to upstream")
        val cmd = CMD_FULFILL_HTLC(o.originHtlcId, fulfill.paymentPreimage, commit = true)
        context.system.eventStream ! EventStream.Publish(ChannelPaymentRelayed(o.amountIn, o.amountOut, htlc.paymentHash, o.originChannelId, htlc.channelId))
        safeSendAndStop(o.originChannelId, cmd)

      case WrappedAddResponse(RES_ADD_SETTLED(o: Origin.ChannelRelayedHot, _, fail: HtlcResult.Fail)) =>
        context.log.info("relaying fail to upstream")
        Metrics.recordPaymentRelayFailed(Tags.FailureType.Remote, Tags.RelayType.Channel)
        val cmd = translateRelayFailure(o.originHtlcId, fail)
        safeSendAndStop(o.originChannelId, cmd)
    }

  def safeSendAndStop(channelId: ByteVector32, cmd: channel.Command with channel.HtlcSettlementCommand): Behavior[Command] = {
    // NB: we are not using an adapter here because we are stopping anyway so we won't be there to get the result
    PendingCommandsDb.safeSend(register, nodeParams.db.pendingCommands, channelId, cmd)
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
    val alreadyTried = previousFailures.map(_.shortChannelId)
    selectPreferredChannel(alreadyTried)
      .flatMap(selectedShortChannelId => channels.get(selectedShortChannelId).map(_.channelUpdate)) match {
      case None if previousFailures.nonEmpty =>
        // no more channels to try
        val error = previousFailures
          // we return the error for the initially requested channel if it exists
          .find(_.shortChannelId == r.payload.outgoingChannelId)
          // otherwise we return the error for the first channel tried
          .getOrElse(previousFailures.head)
          .failure
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(translateLocalError(error.t, error.channelUpdate)), commit = true))
      case channelUpdate_opt =>
        relayOrFail(channelUpdate_opt)
    }
  }

  /** all the channels point to the same next node, we take the first one */
  private val nextNodeId_opt = channels.headOption.map(_._2.nextNodeId)

  /**
   * Select a channel to the same node to relay the payment to, that has the lowest capacity and balance and is
   * compatible in terms of fees, expiry_delta, etc.
   *
   * If no suitable channel is found we default to the originally requested channel.
   */
  def selectPreferredChannel(alreadyTried: Seq[ShortChannelId]): Option[ShortChannelId] = {
    val requestedShortChannelId = r.payload.outgoingChannelId
    context.log.debug("selecting next channel with requestedShortChannelId={}", requestedShortChannelId)
    nextNodeId_opt match {
      case Some(_) =>
        // we then filter out channels that we have already tried
        val candidateChannels: Map[ShortChannelId, OutgoingChannel] = channels -- alreadyTried
        // and we filter again to keep the ones that are compatible with this payment (mainly fees, expiry delta)
        candidateChannels
          .map { case (shortChannelId, channelInfo) =>
            val relayResult = relayOrFail(Some(channelInfo.channelUpdate))
            context.log.debug(s"candidate channel: shortChannelId=$shortChannelId availableForSend={} capacity={} channelUpdate={} result={}",
              channelInfo.commitments.availableBalanceForSend,
              channelInfo.commitments.capacity,
              channelInfo.channelUpdate,
              relayResult match {
                case _: RelaySuccess => "success"
                case RelayFailure(CMD_FAIL_HTLC(_, Right(failureReason), _, _)) => failureReason
                case other => other
              })
            (shortChannelId, channelInfo, relayResult)
          }
          .collect {
            // we only keep channels that have enough balance to handle this payment
            case (shortChannelId, channelInfo, _: RelaySuccess) if channelInfo.commitments.availableBalanceForSend > r.payload.amountToForward => (shortChannelId, channelInfo.commitments)
          }
          .toList // needed for ordering
          // we want to use the channel with:
          //  - the lowest available capacity to ensure we keep high-capacity channels for big payments
          //  - the lowest available balance to increase our incoming liquidity
          .sortBy { case (_, commitments) => (commitments.capacity, commitments.availableBalanceForSend) }
          .headOption match {
          case Some((preferredShortChannelId, commitments)) if preferredShortChannelId != requestedShortChannelId =>
            context.log.info("replacing requestedShortChannelId={} by preferredShortChannelId={} with availableBalanceMsat={}", requestedShortChannelId, preferredShortChannelId, commitments.availableBalanceForSend)
            Some(preferredShortChannelId)
          case Some(_) =>
            context.log.debug("requested short channel id is our preferred channel")
            Some(requestedShortChannelId)
          case None if !alreadyTried.contains(requestedShortChannelId) =>
            context.log.debug("no channel seems to work for this payment, we will try to use the requested short channel id")
            Some(requestedShortChannelId)
          case None =>
            context.log.debug("no channel seems to work for this payment and we have already tried the requested channel id: giving up")
            None
        }
      case _ => Some(requestedShortChannelId) // we don't have a channel_update for this short_channel_id
    }
  }

  /**
   * This helper method will tell us if it is not even worth attempting to relay the payment to our local outgoing
   * channel, because some parameters don't match with our settings for that channel. In that case we directly fail the
   * htlc.
   */
  def relayOrFail(channelUpdate_opt: Option[ChannelUpdate]): RelayResult = {
    import r._
    channelUpdate_opt match {
      case None =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true))
      case Some(channelUpdate) if !Announcements.isEnabled(channelUpdate.channelFlags) =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)), commit = true))
      case Some(channelUpdate) if payload.amountToForward < channelUpdate.htlcMinimumMsat =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(AmountBelowMinimum(payload.amountToForward, channelUpdate)), commit = true))
      case Some(channelUpdate) if r.expiryDelta < channelUpdate.cltvExpiryDelta =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(IncorrectCltvExpiry(payload.outgoingCltv, channelUpdate)), commit = true))
      case Some(channelUpdate) if r.relayFeeMsat < nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, payload.amountToForward) =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(FeeInsufficient(add.amountMsat, channelUpdate)), commit = true))
      case Some(channelUpdate) =>
        val origin = Origin.ChannelRelayedHot(addResponseAdapter.toClassic, add, payload.amountToForward)
        RelaySuccess(channelUpdate.shortChannelId, CMD_ADD_HTLC(addResponseAdapter.toClassic, payload.amountToForward, add.paymentHash, payload.outgoingCltv, nextPacket, origin, commit = true))
    }
  }

}
