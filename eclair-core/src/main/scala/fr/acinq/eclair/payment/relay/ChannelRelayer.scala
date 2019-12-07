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

package fr.acinq.eclair.payment.relay

import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, Props, Status}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.IncomingPacket
import fr.acinq.eclair.payment.relay.Relayer.{ChannelUpdates, NodeChannels, OutgoingChannel}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Logs, NodeParams, ShortChannelId, nodeFee}

/**
 * Created by t-bast on 09/10/2019.
 */

/**
 * The Channel Relayer is used to relay a single upstream HTLC to a downstream channel.
 * It selects the best channel to use to relay and retries using other channels in case a local failure happens.
 */
class ChannelRelayer(nodeParams: NodeParams, relayer: ActorRef, register: ActorRef, commandBuffer: ActorRef) extends Actor with DiagnosticActorLogging {

  import ChannelRelayer._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  override def receive: Receive = {
    case RelayHtlc(r, previousFailures, channelUpdates, node2channels) =>
      handleRelay(r, channelUpdates, node2channels, previousFailures, nodeParams.chainHash) match {
        case RelayFailure(cmdFail) =>
          log.info(s"rejecting htlc #${r.add.id} from channelId=${r.add.channelId} to shortChannelId=${r.payload.outgoingChannelId} reason=${cmdFail.reason}")
          commandBuffer ! CommandBuffer.CommandSend(r.add.channelId, cmdFail)
        case RelaySuccess(selectedShortChannelId, cmdAdd) =>
          log.info(s"forwarding htlc #${r.add.id} from channelId=${r.add.channelId} to shortChannelId=$selectedShortChannelId")
          register ! Register.ForwardShortId(selectedShortChannelId, cmdAdd)
      }

    case Status.Failure(Register.ForwardShortIdFailure(Register.ForwardShortId(shortChannelId, CMD_ADD_HTLC(_, _, _, _, Upstream.Relayed(add), _, _)))) =>
      log.warning(s"couldn't resolve downstream channel $shortChannelId, failing htlc #${add.id}")
      val cmdFail = CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true)
      commandBuffer ! CommandBuffer.CommandSend(add.channelId, cmdFail)

    case Status.Failure(addFailed: AddHtlcFailed) => addFailed.origin match {
      case Origin.Relayed(originChannelId, originHtlcId, _, _) => addFailed.originalCommand match {
        case Some(CMD_ADD_HTLC(_, _, _, _, Upstream.Relayed(add), _, previousFailures)) =>
          log.info(s"retrying htlc #$originHtlcId from channelId=$originChannelId")
          relayer ! Relayer.ForwardAdd(add, previousFailures :+ addFailed)
        case _ =>
          val failure = translateError(addFailed)
          val cmdFail = CMD_FAIL_HTLC(originHtlcId, Right(failure), commit = true)
          log.info(s"rejecting htlc #$originHtlcId from channelId=$originChannelId reason=${cmdFail.reason}")
          commandBuffer ! CommandBuffer.CommandSend(originChannelId, cmdFail)
      }
      case _ => throw new IllegalArgumentException(s"channel relayer received unexpected failure: $addFailed")
    }

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case "ok" => // ignoring responses from channels
  }

  override def mdc(currentMessage: Any): MDC = {
    val paymentHash_opt = currentMessage match {
      case relay: RelayHtlc => Some(relay.r.add.paymentHash)
      case Status.Failure(Register.ForwardShortIdFailure(Register.ForwardShortId(_, c: CMD_ADD_HTLC))) => Some(c.paymentHash)
      case Status.Failure(addFailed: AddHtlcFailed) => Some(addFailed.paymentHash)
      case _ => None
    }
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = paymentHash_opt)
  }
}

object ChannelRelayer {

  def props(nodeParams: NodeParams, relayer: ActorRef, register: ActorRef, commandBuffer: ActorRef) = Props(classOf[ChannelRelayer], nodeParams, relayer, register, commandBuffer)

  case class RelayHtlc(r: IncomingPacket.ChannelRelayPacket, previousFailures: Seq[AddHtlcFailed], channelUpdates: ChannelUpdates, node2channels: NodeChannels)

  // @formatter:off
  sealed trait RelayResult
  case class RelayFailure(cmdFail: CMD_FAIL_HTLC) extends RelayResult
  case class RelaySuccess(shortChannelId: ShortChannelId, cmdAdd: CMD_ADD_HTLC) extends RelayResult
  // @formatter:on

  /**
   * Handle an incoming htlc when we are a relaying node.
   *
   * @return either:
   *         - a CMD_FAIL_HTLC to be sent back upstream
   *         - a CMD_ADD_HTLC to propagate downstream
   */
  def handleRelay(relayPacket: IncomingPacket.ChannelRelayPacket, channelUpdates: ChannelUpdates, node2channels: NodeChannels, previousFailures: Seq[AddHtlcFailed], chainHash: ByteVector32)(implicit log: LoggingAdapter): RelayResult = {
    import relayPacket._
    log.info(s"relaying htlc #${add.id} from channelId={} to requestedShortChannelId={} previousAttempts={}", add.channelId, payload.outgoingChannelId, previousFailures.size)
    val alreadyTried = previousFailures.flatMap(_.channelUpdate).map(_.shortChannelId)
    selectPreferredChannel(relayPacket, channelUpdates, node2channels, alreadyTried)
      .flatMap(selectedShortChannelId => channelUpdates.get(selectedShortChannelId).map(_.channelUpdate)) match {
      case None if previousFailures.nonEmpty =>
        // no more channels to try
        val error = previousFailures
          // we return the error for the initially requested channel if it exists
          .find(_.channelUpdate.map(_.shortChannelId).contains(payload.outgoingChannelId))
          // otherwise we return the error for the first channel tried
          .getOrElse(previousFailures.head)
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(translateError(error)), commit = true))
      case channelUpdate_opt =>
        relayOrFail(relayPacket, channelUpdate_opt, previousFailures)
    }
  }

  /**
   * Select a channel to the same node to relay the payment to, that has the lowest balance and is compatible in
   * terms of fees, expiry_delta, etc.
   *
   * If no suitable channel is found we default to the originally requested channel.
   */
  def selectPreferredChannel(relayPacket: IncomingPacket.ChannelRelayPacket, channelUpdates: ChannelUpdates, node2channels: NodeChannels, alreadyTried: Seq[ShortChannelId])(implicit log: LoggingAdapter): Option[ShortChannelId] = {
    import relayPacket.add
    val requestedShortChannelId = relayPacket.payload.outgoingChannelId
    log.debug(s"selecting next channel for htlc #${add.id} from channelId={} to requestedShortChannelId={} previousAttempts={}", add.channelId, requestedShortChannelId, alreadyTried.size)
    // first we find out what is the next node
    val nextNodeId_opt = channelUpdates.get(requestedShortChannelId) match {
      case Some(OutgoingChannel(nextNodeId, _, _)) =>
        Some(nextNodeId)
      case None => None
    }
    nextNodeId_opt match {
      case Some(nextNodeId) =>
        log.debug(s"next hop for htlc #{} is nodeId={}", add.id, nextNodeId)
        // then we retrieve all known channels to this node
        val allChannels = node2channels.getOrElse(nextNodeId, Set.empty[ShortChannelId])
        // we then filter out channels that we have already tried
        val candidateChannels = allChannels -- alreadyTried
        // and we filter keep the ones that are compatible with this payment (mainly fees, expiry delta)
        candidateChannels
          .map { shortChannelId =>
            val channelInfo_opt = channelUpdates.get(shortChannelId)
            val channelUpdate_opt = channelInfo_opt.map(_.channelUpdate)
            val relayResult = relayOrFail(relayPacket, channelUpdate_opt)
            log.debug(s"candidate channel for htlc #${add.id}: shortChannelId={} balanceMsat={} channelUpdate={} relayResult={}", shortChannelId, channelInfo_opt.map(_.commitments.availableBalanceForSend).getOrElse(""), channelUpdate_opt.getOrElse(""), relayResult)
            (shortChannelId, channelInfo_opt, relayResult)
          }
          .collect { case (shortChannelId, Some(channelInfo), _: RelaySuccess) => (shortChannelId, channelInfo.commitments.availableBalanceForSend) }
          .filter(_._2 > relayPacket.payload.amountToForward) // we only keep channels that have enough balance to handle this payment
          .toList // needed for ordering
          .sortBy(_._2) // we want to use the channel with the lowest available balance that can process the payment
          .headOption match {
          case Some((preferredShortChannelId, availableBalanceMsat)) if preferredShortChannelId != requestedShortChannelId =>
            log.info("replacing requestedShortChannelId={} by preferredShortChannelId={} with availableBalanceMsat={}", requestedShortChannelId, preferredShortChannelId, availableBalanceMsat)
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
  def relayOrFail(relayPacket: IncomingPacket.ChannelRelayPacket, channelUpdate_opt: Option[ChannelUpdate], previousFailures: Seq[AddHtlcFailed] = Seq.empty): RelayResult = {
    import relayPacket._
    channelUpdate_opt match {
      case None =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true))
      case Some(channelUpdate) if !Announcements.isEnabled(channelUpdate.channelFlags) =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)), commit = true))
      case Some(channelUpdate) if payload.amountToForward < channelUpdate.htlcMinimumMsat =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(AmountBelowMinimum(payload.amountToForward, channelUpdate)), commit = true))
      case Some(channelUpdate) if relayPacket.expiryDelta != channelUpdate.cltvExpiryDelta =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(IncorrectCltvExpiry(payload.outgoingCltv, channelUpdate)), commit = true))
      case Some(channelUpdate) if relayPacket.relayFeeMsat < nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, payload.amountToForward) =>
        RelayFailure(CMD_FAIL_HTLC(add.id, Right(FeeInsufficient(add.amountMsat, channelUpdate)), commit = true))
      case Some(channelUpdate) =>
        RelaySuccess(channelUpdate.shortChannelId, CMD_ADD_HTLC(payload.amountToForward, add.paymentHash, payload.outgoingCltv, nextPacket, Upstream.Relayed(add), commit = true, previousFailures = previousFailures))
    }
  }

  /**
   * This helper method translates relaying errors (returned by the downstream outgoing channel) to BOLT 4 standard
   * errors that we should return upstream.
   */
  def translateError(failure: AddHtlcFailed): FailureMessage = {
    val error = failure.t
    val channelUpdate_opt = failure.channelUpdate
    (error, channelUpdate_opt) match {
      case (_: ExpiryTooSmall, Some(channelUpdate)) => ExpiryTooSoon(channelUpdate)
      case (_: ExpiryTooBig, _) => ExpiryTooFar
      case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
      case (_: ChannelUnavailable, Some(channelUpdate)) if !Announcements.isEnabled(channelUpdate.channelFlags) => ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)
      case (_: ChannelUnavailable, None) => PermanentChannelFailure
      case (_: HtlcTimedout, _) => PermanentChannelFailure
      case _ => TemporaryNodeFailure
    }
  }

}