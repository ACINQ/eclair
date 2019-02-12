/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.nodeFee
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentLifecycle.{PaymentFailed, PaymentSucceeded}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{NodeParams, ShortChannelId}
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

// @formatter:off

sealed trait Origin
case class Local(sender: Option[ActorRef]) extends Origin // we don't persist reference to local actors
case class Relayed(originChannelId: BinaryData, originHtlcId: Long, amountMsatIn: Long, amountMsatOut: Long) extends Origin

sealed trait ForwardMessage
case class ForwardAdd(add: UpdateAddHtlc, canRedirect: Boolean = true) extends ForwardMessage
case class ForwardFulfill(fulfill: UpdateFulfillHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardMessage
case class ForwardFail(fail: UpdateFailHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardMessage
case class ForwardFailMalformed(fail: UpdateFailMalformedHtlc, to: Origin, htlc: UpdateAddHtlc) extends ForwardMessage

// @formatter:on


/**
  * Created by PM on 01/02/2017.
  */
class Relayer(nodeParams: NodeParams, register: ActorRef, paymentHandler: ActorRef) extends Actor with ActorLogging {

  import Relayer._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog = log

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])

  val commandBuffer = context.actorOf(Props(new CommandBuffer(nodeParams, register)))

  override def receive: Receive = main(Map.empty, new mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId])

  def main(channelUpdates: Map[ShortChannelId, OutgoingChannel], node2channels: mutable.HashMap[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId]): Receive = {

    case LocalChannelUpdate(_, channelId, shortChannelId, remoteNodeId, _, channelUpdate, commitments) =>
      log.debug(s"updating local channel info for channelId=$channelId shortChannelId=$shortChannelId remoteNodeId=$remoteNodeId channelUpdate={} commitments={}", channelUpdate, commitments)
      context become main(channelUpdates + (channelUpdate.shortChannelId -> OutgoingChannel(remoteNodeId, channelUpdate, commitments.availableBalanceForSendMsat)), node2channels.addBinding(remoteNodeId, channelUpdate.shortChannelId))

    case LocalChannelDown(_, channelId, shortChannelId, remoteNodeId) =>
      log.debug(s"removed local channel info for channelId=$channelId shortChannelId=$shortChannelId")
      context become main(channelUpdates - shortChannelId, node2channels.removeBinding(remoteNodeId, shortChannelId))

    case AvailableBalanceChanged(_, _, shortChannelId, _, commitments) =>
      val channelUpdates1 = channelUpdates.get(shortChannelId) match {
        case Some(c: OutgoingChannel) => channelUpdates + (shortChannelId -> c.copy(availableBalanceMsat = commitments.availableBalanceForSendMsat))
        case None => channelUpdates // we only consider the balance if we have the channel_update
      }
      context become main(channelUpdates1, node2channels)

    case ForwardAdd(add, canRedirect) =>
      log.debug(s"received forwarding request for htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId}")
      tryParsePacket(add, nodeParams.privateKey) match {
        case Success(p: FinalPayload) =>
          handleFinal(p) match {
            case Left(cmdFail) =>
              log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} reason=${cmdFail.reason}")
              commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
            case Right(addHtlc) =>
              log.debug(s"forwarding htlc #${add.id} paymentHash=${add.paymentHash} to payment-handler")
              paymentHandler forward addHtlc
          }
        case Success(r: RelayPayload) =>
          val selectedShortChannelId = if (canRedirect) selectPreferredChannel(r, channelUpdates, node2channels) else r.payload.shortChannelId
          handleRelay(r, channelUpdates.get(selectedShortChannelId).map(_.channelUpdate)) match {
            case Left(cmdFail) =>
              log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} to shortChannelId=${r.payload.shortChannelId} reason=${cmdFail.reason}")
              commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
            case Right(cmdAdd) =>
              log.info(s"forwarding htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} to shortChannelId=$selectedShortChannelId")
              register ! Register.ForwardShortId(selectedShortChannelId, cmdAdd)
          }
        case Failure(t) =>
          log.warning(s"couldn't parse onion: reason=${t.getMessage}")
          val cmdFail = CMD_FAIL_MALFORMED_HTLC(add.id, Crypto.sha256(add.onionRoutingPacket), failureCode = FailureMessageCodecs.BADONION, commit = true)
          log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} reason=malformed onionHash=${cmdFail.onionHash} failureCode=${cmdFail.failureCode}")
          commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
      }

    case Status.Failure(Register.ForwardShortIdFailure(Register.ForwardShortId(shortChannelId, CMD_ADD_HTLC(_, _, _, _, Some(add), _, _)))) =>
      log.warning(s"couldn't resolve downstream channel $shortChannelId, failing htlc #${add.id}")
      val cmdFail = CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true)
      commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)

    case Status.Failure(AddHtlcFailed(_, paymentHash, _, Local(None), _, _)) =>
      // we sent the payment, but we probably restarted and the reference to the original sender was lost, we just publish the failure on the event stream
      context.system.eventStream.publish(PaymentFailed(paymentHash, Nil))

    case Status.Failure(AddHtlcFailed(_, _, error, Local(Some(sender)), _, _)) =>
      sender ! Status.Failure(error)

    case Status.Failure(AddHtlcFailed(_, paymentHash, error, Relayed(originChannelId, originHtlcId, _, _), channelUpdate_opt, originalCommand_opt)) =>
      originalCommand_opt match {
        case Some(cmd) if cmd.redirected && cmd.upstream_opt.isDefined => // cmd.upstream_opt.isDefined always true since origin = relayed
          // if it was redirected, we give it one more try with the original requested channel (meaning that the error returned will always be for the requested channel)
          log.info(s"retrying htlc #$originHtlcId paymentHash=$paymentHash from channelId=$originChannelId")
          self ! ForwardAdd(cmd.upstream_opt.get, canRedirect = false)
        case _ =>
          // otherwise we just return a failure
          val failure = (error, channelUpdate_opt) match {
            case (_: ExpiryTooSmall, Some(channelUpdate)) => ExpiryTooSoon(channelUpdate)
            case (_: ExpiryTooBig, _) => ExpiryTooFar
            case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
            case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
            case (_: ChannelUnavailable, Some(channelUpdate)) if !Announcements.isEnabled(channelUpdate.channelFlags) => ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)
            case (_: ChannelUnavailable, None) => PermanentChannelFailure
            case (_: HtlcTimedout, _) => PermanentChannelFailure
            case _ => TemporaryNodeFailure
          }
          val cmdFail = CMD_FAIL_HTLC(originHtlcId, Right(failure), commit = true)
          log.info(s"rejecting htlc #$originHtlcId paymentHash=$paymentHash from channelId=$originChannelId reason=${cmdFail.reason}")
          commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmdFail)
      }

    case ForwardFulfill(fulfill, Local(None), add) =>
      val feesPaid = MilliSatoshi(0)
      context.system.eventStream.publish(PaymentSent(MilliSatoshi(add.amountMsat), feesPaid, add.paymentHash, fulfill.paymentPreimage, fulfill.channelId))
      // we sent the payment, but we probably restarted and the reference to the original sender was lost, we just publish the success on the event stream
      context.system.eventStream.publish(PaymentSucceeded(add.amountMsat, add.paymentHash, fulfill.paymentPreimage, Nil)) //

    case ForwardFulfill(fulfill, Local(Some(sender)), _) =>
      sender ! fulfill

    case ForwardFulfill(fulfill, Relayed(originChannelId, originHtlcId, amountMsatIn, amountMsatOut), add) =>
      val cmd = CMD_FULFILL_HTLC(originHtlcId, fulfill.paymentPreimage, commit = true)
      commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)
      context.system.eventStream.publish(PaymentRelayed(MilliSatoshi(amountMsatIn), MilliSatoshi(amountMsatOut), add.paymentHash, fromChannelId = originChannelId, toChannelId = fulfill.channelId))

    case ForwardFail(_, Local(None), add) =>
      // we sent the payment, but we probably restarted and the reference to the original sender was lost, we just publish the failure on the event stream
      context.system.eventStream.publish(PaymentFailed(add.paymentHash, Nil))

    case ForwardFail(fail, Local(Some(sender)), _) =>
      sender ! fail

    case ForwardFail(fail, Relayed(originChannelId, originHtlcId, _, _), _) =>
      val cmd = CMD_FAIL_HTLC(originHtlcId, Left(fail.reason), commit = true)
      commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)

    case ForwardFailMalformed(_, Local(None), add) =>
      // we sent the payment, but we probably restarted and the reference to the original sender was lost, we just publish the failure on the event stream
      context.system.eventStream.publish(PaymentFailed(add.paymentHash, Nil))

    case ForwardFailMalformed(fail, Local(Some(sender)), _) =>
      sender ! fail

    case ForwardFailMalformed(fail, Relayed(originChannelId, originHtlcId, _, _), _) =>
      val cmd = CMD_FAIL_MALFORMED_HTLC(originHtlcId, fail.onionHash, fail.failureCode, commit = true)
      commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case "ok" => () // ignoring responses from channels
  }

}

object Relayer {
  def props(nodeParams: NodeParams, register: ActorRef, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeParams, register, paymentHandler)

  case class OutgoingChannel(nextNodeId: PublicKey, channelUpdate: ChannelUpdate, availableBalanceMsat: Long)

  // @formatter:off
  sealed trait NextPayload
  case class FinalPayload(add: UpdateAddHtlc, payload: PerHopPayload) extends NextPayload
  case class RelayPayload(add: UpdateAddHtlc, payload: PerHopPayload, nextPacket: Sphinx.Packet) extends NextPayload {
    val relayFeeMsat = add.amountMsat - payload.amtToForward
    val expiryDelta = add.cltvExpiry - payload.outgoingCltvValue
  }
  // @formatter:on

  /**
    * Parse and decode the onion of a received htlc, and find out if the payment is to be relayed,
    * or if our node is the last one in the route
    *
    * @param add        incoming htlc
    * @param privateKey this node's private key
    * @return the payload for the next hop
    */
  def tryParsePacket(add: UpdateAddHtlc, privateKey: PrivateKey): Try[NextPayload] =
    Sphinx
      .parsePacket(privateKey, add.paymentHash, add.onionRoutingPacket)
      .flatMap {
        case Sphinx.ParsedPacket(payload, nextPacket, _) =>
          LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(payload.data)) match {
            case Attempt.Successful(DecodeResult(perHopPayload, _)) if nextPacket.isLastPacket =>
              Success(FinalPayload(add, perHopPayload))
            case Attempt.Successful(DecodeResult(perHopPayload, _)) =>
              Success(RelayPayload(add, perHopPayload, nextPacket))
            case Attempt.Failure(cause) =>
              Failure(new RuntimeException(cause.messageWithContext))
          }
      }

  /**
    * Handle an incoming htlc when we are the last node
    *
    * @param finalPayload payload
    * @return either:
    *         - a CMD_FAIL_HTLC to be sent back upstream
    *         - an UpdateAddHtlc to forward
    */
  def handleFinal(finalPayload: FinalPayload): Either[CMD_FAIL_HTLC, UpdateAddHtlc] = {
    import finalPayload.add
    finalPayload.payload match {
      case PerHopPayload(_, finalAmountToForward, _) if finalAmountToForward > add.amountMsat =>
        Left(CMD_FAIL_HTLC(add.id, Right(FinalIncorrectHtlcAmount(add.amountMsat)), commit = true))
      case PerHopPayload(_, _, finalOutgoingCltvValue) if finalOutgoingCltvValue != add.cltvExpiry =>
        Left(CMD_FAIL_HTLC(add.id, Right(FinalIncorrectCltvExpiry(add.cltvExpiry)), commit = true))
      case _ =>
        Right(add)
    }
  }

  /**
    * Handle an incoming htlc when we are a relaying node
    *
    * @param relayPayload payload
    * @return either:
    *         - a CMD_FAIL_HTLC to be sent back upstream
    *         - a CMD_ADD_HTLC to propagate downstream
    */
  def handleRelay(relayPayload: RelayPayload, channelUpdate_opt: Option[ChannelUpdate])(implicit log: LoggingAdapter): Either[CMD_FAIL_HTLC, CMD_ADD_HTLC] = {
    import relayPayload._
    channelUpdate_opt match {
      case None =>
        Left(CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true))
      case Some(channelUpdate) if !Announcements.isEnabled(channelUpdate.channelFlags) =>
        Left(CMD_FAIL_HTLC(add.id, Right(ChannelDisabled(channelUpdate.messageFlags, channelUpdate.channelFlags, channelUpdate)), commit = true))
      case Some(channelUpdate) if payload.amtToForward < channelUpdate.htlcMinimumMsat =>
        Left(CMD_FAIL_HTLC(add.id, Right(AmountBelowMinimum(payload.amtToForward, channelUpdate)), commit = true))
      case Some(channelUpdate) if relayPayload.expiryDelta != channelUpdate.cltvExpiryDelta =>
        Left(CMD_FAIL_HTLC(add.id, Right(IncorrectCltvExpiry(payload.outgoingCltvValue, channelUpdate)), commit = true))
      case Some(channelUpdate) if relayPayload.relayFeeMsat < nodeFee(channelUpdate.feeBaseMsat, channelUpdate.feeProportionalMillionths, payload.amtToForward) =>
        Left(CMD_FAIL_HTLC(add.id, Right(FeeInsufficient(add.amountMsat, channelUpdate)), commit = true))
      case Some(channelUpdate) =>
        val isRedirected = (channelUpdate.shortChannelId != payload.shortChannelId) // we may decide to use another channel (to the same node) from the one requested
        Right(CMD_ADD_HTLC(payload.amtToForward, add.paymentHash, payload.outgoingCltvValue, nextPacket.serialize, upstream_opt = Some(add), commit = true, redirected = isRedirected))
    }
  }

  /**
    * Select a channel to the same node to the relay the payment to, that has the lowest balance and is compatible in
    * terms of fees, expiry_delta, etc.
    *
    * If no suitable channel is found we default to the originally requested channel.
    *
    * @param relayPayload
    * @param channelUpdates
    * @param node2channels
    * @param log
    * @return
    */
  def selectPreferredChannel(relayPayload: RelayPayload, channelUpdates: Map[ShortChannelId, OutgoingChannel], node2channels: mutable.Map[PublicKey, mutable.Set[ShortChannelId]] with mutable.MultiMap[PublicKey, ShortChannelId])(implicit log: LoggingAdapter): ShortChannelId = {
    import relayPayload.add
    val requestedShortChannelId = relayPayload.payload.shortChannelId
    log.debug(s"selecting next channel for htlc #{} paymentHash={} from channelId={} to requestedShortChannelId={}", add.id, add.paymentHash, add.channelId, requestedShortChannelId)
    // first we find out what is the next node
    channelUpdates.get(requestedShortChannelId) match {
      case Some(OutgoingChannel(nextNodeId, _, _)) =>
        log.debug(s"next hop for htlc #{} paymentHash={} is nodeId={}", add.id, add.paymentHash, nextNodeId)
        // then we retrieve all known channels to this node
        val candidateChannels = node2channels.get(nextNodeId).getOrElse(Set.empty[ShortChannelId])
        // and we filter keep the ones that are compatible with this payment (mainly fees, expiry delta)
        candidateChannels
          .map { shortChannelId =>
              val channelInfo_opt = channelUpdates.get(shortChannelId)
              val channelUpdate_opt = channelInfo_opt.map(_.channelUpdate)
              val relayResult = handleRelay(relayPayload, channelUpdate_opt)
              log.debug(s"candidate channel for htlc #${add.id} paymentHash=${add.paymentHash}: shortChannelId={} balanceMsat={} channelUpdate={} relayResult={}", shortChannelId, channelInfo_opt.map(_.availableBalanceMsat).getOrElse(""), channelUpdate_opt.getOrElse(""), relayResult)
              (shortChannelId, channelInfo_opt, relayResult)
          }
          .collect { case (shortChannelId, Some(channelInfo), Right(_)) => (shortChannelId, channelInfo.availableBalanceMsat) }
          .filter(_._2 > relayPayload.payload.amtToForward) // we only keep channels that have enough balance to handle this payment
          .toList // needed for ordering
          .sortBy(_._2) // we want to use the channel with the lowest available balance that can process the payment
          .headOption match {
          case Some((preferredShortChannelId, availableBalanceMsat)) if preferredShortChannelId != requestedShortChannelId =>
            log.info("replacing requestedShortChannelId={} by preferredShortChannelId={} with availableBalanceMsat={}", requestedShortChannelId, preferredShortChannelId, availableBalanceMsat)
            preferredShortChannelId
          case Some(_) =>
            // the requested short_channel_id is already our preferred channel
            requestedShortChannelId
          case None =>
            // no channel seem to work for this payment, we keep the requested channel id
            requestedShortChannelId
        }
      case _ => requestedShortChannelId // we don't have a channel_update for this short_channel_id
    }
  }
}
