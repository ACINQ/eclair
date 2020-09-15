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
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.relay.ChannelRelay.{WrappedAddResponse, WrappedForwardShortIdFailure}
import fr.acinq.eclair.payment.relay.ChannelRelayer.{ChannelUpdates, NodeChannels}
import fr.acinq.eclair.payment.relay.Relayer.OutgoingChannel
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, IncomingPacket}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
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

  def apply(nodeParams: NodeParams, register: ActorRef, channelUpdates: ChannelUpdates, node2channels: NodeChannels, relayId: UUID, r: IncomingPacket.ChannelRelayPacket): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(
        category_opt = Some(Logs.LogCategory.PAYMENT),
        parentPaymentId_opt = Some(relayId), // for a channel relay, parent payment id = relay id
        paymentHash_opt = Some(r.add.paymentHash))) {
        context.self ! DoRelay
        new ChannelRelay(nodeParams, register, channelUpdates, node2channels, r, context)(Seq.empty)
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

  def translateRelayFailure(originHtlcId: Long, fail: HtlcResult.Fail): channel.Command with channel.HasHtlcId = {
    fail match {
      case f: HtlcResult.RemoteFail => CMD_FAIL_HTLC(originHtlcId, Left(f.fail.reason), commit = true)
      case f: HtlcResult.RemoteFailMalformed => CMD_FAIL_MALFORMED_HTLC(originHtlcId, f.fail.onionHash, f.fail.failureCode, commit = true)
      case _: HtlcResult.OnChainFail => CMD_FAIL_HTLC(originHtlcId, Right(PermanentChannelFailure), commit = true)
      case f: HtlcResult.Disconnected => CMD_FAIL_HTLC(originHtlcId, Right(TemporaryChannelFailure(f.channelUpdate)), commit = true)
    }
  }

}

/**
 * see https://doc.akka.io/docs/akka/current/typed/style-guide.html#passing-around-too-many-parameters
 */
class ChannelRelay private(
                            nodeParams: NodeParams,
                            register: ActorRef,
                            channelUpdates: ChannelUpdates,
                            node2channels: NodeChannels,
                            r: IncomingPacket.ChannelRelayPacket,
                            context: ActorContext[ChannelRelay.Command]) {

  private val forwardShortIdAdapter = context.messageAdapter[Register.ForwardShortIdFailure[CMD_ADD_HTLC]](WrappedForwardShortIdFailure)
  private val addResponseAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedAddResponse)

  import ChannelRelay._

  def apply(previousFailures: Seq[RES_ADD_FAILED[ChannelException]]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case DoRelay =>
        context.log.info(s"relaying htlc #${r.add.id} from channelId={} to requestedShortChannelId={} previousAttempts={}", r.add.channelId, r.payload.outgoingChannelId, previousFailures.size)
        handleRelay(previousFailures) match {
          case RelayFailure(cmdFail) =>
            Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
            context.log.info(s"rejecting htlc #${r.add.id} from channelId=${r.add.channelId} to shortChannelId=${r.payload.outgoingChannelId} reason=${cmdFail.reason}")
            safeSendAndStop(r.add.channelId, cmdFail)
          case RelaySuccess(selectedShortChannelId, cmdAdd) =>
            context.log.info(s"forwarding htlc #${r.add.id} from channelId=${r.add.channelId} to shortChannelId=$selectedShortChannelId")
            register ! Register.ForwardShortId(forwardShortIdAdapter.toClassic, selectedShortChannelId, cmdAdd)
            waitForAddResponse(previousFailures)
        }
    }
  }

  def waitForAddResponse(previousFailures: Seq[RES_ADD_FAILED[ChannelException]]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedForwardShortIdFailure(Register.ForwardShortIdFailure(Register.ForwardShortId(_, shortChannelId, CMD_ADD_HTLC(_, _, _, _, _, Origin.ChannelRelayedHot(_, add, _), _)))) =>
        context.log.warn(s"couldn't resolve downstream channel $shortChannelId, failing htlc #${add.id}")
        val cmdFail = CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true)
        Metrics.recordPaymentRelayFailed(Tags.FailureType(cmdFail), Tags.RelayType.Channel)
        safeSendAndStop(add.channelId, cmdFail)

      case WrappedAddResponse(addFailed@RES_ADD_FAILED(CMD_ADD_HTLC(_, _, _, _, _, Origin.ChannelRelayedHot(_, add, _), _), _, _)) =>
        context.log.info(s"retrying htlc #${add.id} from channelId=${add.channelId}")
        context.self ! DoRelay
        apply(previousFailures :+ addFailed)

      case WrappedAddResponse(_: RES_SUCCESS[_]) =>
        context.log.debug("sent htlc to the downstream channel")
        waitForAddSettled()
    }

  def waitForAddSettled(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedAddResponse(RES_ADD_SETTLED(o: Origin.ChannelRelayedHot, htlc, fulfill: HtlcResult.Fulfill)) =>
        context.log.info("relaying fulfill upstream")
        val cmd = CMD_FULFILL_HTLC(o.originHtlcId, fulfill.paymentPreimage, commit = true)
        context.system.eventStream ! EventStream.Publish(ChannelPaymentRelayed(o.amountIn, o.amountOut, htlc.paymentHash, o.originChannelId, htlc.channelId))
        safeSendAndStop(o.originChannelId, cmd)

      case WrappedAddResponse(RES_ADD_SETTLED(o: Origin.ChannelRelayedHot, _, fail: HtlcResult.Fail)) =>
        context.log.info("relaying fail upstream")
        Metrics.recordPaymentRelayFailed(Tags.FailureType.Remote, Tags.RelayType.Channel)
        val cmd = translateRelayFailure(o.originHtlcId, fail)
        safeSendAndStop(o.originChannelId, cmd)
    }

  def safeSendAndStop(channelId: ByteVector32, cmd: channel.Command with channel.HasHtlcId): Behavior[Command] = {
    // NB: we are not using an adapter here because we are stopping anyway so we won't be there to get the result
    PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, context.system.deadLetters.toClassic, channelId, cmd)
    Behaviors.stopped
  }

  /**
   * Handle an incoming htlc when we are a relaying node.
   *
   * @return either:
   *         - a CMD_FAIL_HTLC to be sent back upstream
   *         - a CMD_ADD_HTLC to propagate downstream
   */
  def handleRelay(previousFailures: Seq[RES_ADD_FAILED[ChannelException]]): RelayResult = {
    val alreadyTried = previousFailures.flatMap(_.channelUpdate).map(_.shortChannelId)
    selectPreferredChannel(alreadyTried)
      .flatMap(selectedShortChannelId => channelUpdates.get(selectedShortChannelId).map(_.channelUpdate)) match {
      case None if previousFailures.nonEmpty =>
        // no more channels to try
        val error = previousFailures
          // we return the error for the initially requested channel if it exists
          .find(_.channelUpdate.map(_.shortChannelId).contains(r.payload.outgoingChannelId))
          // otherwise we return the error for the first channel tried
          .getOrElse(previousFailures.head)
        RelayFailure(CMD_FAIL_HTLC(r.add.id, Right(translateLocalError(error.t, error.channelUpdate)), commit = true))
      case channelUpdate_opt =>
        relayOrFail(channelUpdate_opt)
    }
  }

  /**
   * Select a channel to the same node to relay the payment to, that has the lowest balance and is compatible in
   * terms of fees, expiry_delta, etc.
   *
   * If no suitable channel is found we default to the originally requested channel.
   */
  def selectPreferredChannel(alreadyTried: Seq[ShortChannelId]): Option[ShortChannelId] = {
    import r.add
    val requestedShortChannelId = r.payload.outgoingChannelId
    context.log.debug(s"selecting next channel for htlc #${add.id} from channelId={} to requestedShortChannelId={} previousAttempts={}", add.channelId, requestedShortChannelId, alreadyTried.size)
    // first we find out what is the next node
    val nextNodeId_opt = channelUpdates.get(requestedShortChannelId) match {
      case Some(OutgoingChannel(nextNodeId, _, _)) =>
        Some(nextNodeId)
      case None => None
    }
    nextNodeId_opt match {
      case Some(nextNodeId) =>
        context.log.debug(s"next hop for htlc #{} is nodeId={}", add.id, nextNodeId)
        // then we retrieve all known channels to this node
        val allChannels = node2channels.get(nextNodeId)
        // we then filter out channels that we have already tried
        val candidateChannels = allChannels -- alreadyTried
        // and we filter keep the ones that are compatible with this payment (mainly fees, expiry delta)
        candidateChannels
          .map { shortChannelId =>
            val channelInfo_opt = channelUpdates.get(shortChannelId)
            val channelUpdate_opt = channelInfo_opt.map(_.channelUpdate)
            val relayResult = relayOrFail(channelUpdate_opt)
            context.log.debug(s"candidate channel for htlc #${add.id}: shortChannelId={} balanceMsat={} channelUpdate={} relayResult={}", shortChannelId, channelInfo_opt.map(_.commitments.availableBalanceForSend).getOrElse(""), channelUpdate_opt.getOrElse(""), relayResult)
            (shortChannelId, channelInfo_opt, relayResult)
          }
          .collect { case (shortChannelId, Some(channelInfo), _: RelaySuccess) => (shortChannelId, channelInfo.commitments.availableBalanceForSend) }
          .filter(_._2 > r.payload.amountToForward) // we only keep channels that have enough balance to handle this payment
          .toList // needed for ordering
          .sortBy(_._2) // we want to use the channel with the lowest available balance that can process the payment
          .headOption match {
          case Some((preferredShortChannelId, availableBalanceMsat)) if preferredShortChannelId != requestedShortChannelId =>
            context.log.info("replacing requestedShortChannelId={} by preferredShortChannelId={} with availableBalanceMsat={}", requestedShortChannelId, preferredShortChannelId, availableBalanceMsat)
            Some(preferredShortChannelId)
          case Some(_) =>
            // the requested short_channel_id is already our preferred channel
            Some(requestedShortChannelId)
          case None if !alreadyTried.contains(requestedShortChannelId) =>
            // no channel seem to work for this payment, we keep the requested channel id
            Some(requestedShortChannelId)
          case None =>
            // no channel seem to work for this payment and we have already tried the requested channel id: we give up
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
      case Some(channelUpdate) if r.expiryDelta != channelUpdate.cltvExpiryDelta =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(IncorrectCltvExpiry(payload.outgoingCltv, channelUpdate)), commit = true))
      case Some(channelUpdate) if r.relayFeeMsat < nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, payload.amountToForward) =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(FeeInsufficient(add.amountMsat, channelUpdate)), commit = true))
      case Some(channelUpdate) =>
        val origin = Origin.ChannelRelayedHot(addResponseAdapter.toClassic, add, payload.amountToForward)
        RelaySuccess(channelUpdate.shortChannelId, CMD_ADD_HTLC(addResponseAdapter.toClassic, payload.amountToForward, add.paymentHash, payload.outgoingCltv, nextPacket, origin, commit = true))
    }
  }

}
