package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams, ShortChannelId}
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

import scala.util.{Failure, Success}

// @formatter:off

sealed trait Origin
case class Local(sender: Option[ActorRef]) extends Origin // we don't persist reference to local actors
case class Relayed(originChannelId: BinaryData, originHtlcId: Long, amountMsatIn: Long, amountMsatOut: Long) extends Origin

case class ForwardAdd(add: UpdateAddHtlc)
case class ForwardFulfill(fulfill: UpdateFulfillHtlc, to: Origin, htlc: UpdateAddHtlc)
case class ForwardFail(fail: UpdateFailHtlc, to: Origin, htlc: UpdateAddHtlc)
case class ForwardFailMalformed(fail: UpdateFailMalformedHtlc, to: Origin, htlc: UpdateAddHtlc)

// @formatter:on


/**
  * Created by PM on 01/02/2017.
  */
class Relayer(nodeParams: NodeParams, register: ActorRef, paymentHandler: ActorRef) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  val commandBuffer = context.actorOf(Props(new CommandBuffer(nodeParams, register)))

  override def receive: Receive = main(Map())

  def main(channelUpdates: Map[ShortChannelId, ChannelUpdate]): Receive = {

    case LocalChannelUpdate(_, channelId, shortChannelId, remoteNodeId, _, channelUpdate) =>
      log.debug(s"updating channel_update for channelId=$channelId shortChannelId=$shortChannelId remoteNodeId=$remoteNodeId channelUpdate=$channelUpdate ")
      context become main(channelUpdates + (channelUpdate.shortChannelId -> channelUpdate))

    case LocalChannelDown(_, channelId, shortChannelId, _) =>
      log.debug(s"removed local channel_update for channelId=$channelId shortChannelId=$shortChannelId")
      context become main(channelUpdates - shortChannelId)

    case ForwardAdd(add) =>
      log.debug(s"received forwarding request for htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} ")
      Sphinx.parsePacket(nodeParams.privateKey, add.paymentHash, add.onionRoutingPacket)
        .flatMap {
          case Sphinx.ParsedPacket(payload, nextPacket, sharedSecret) =>
            LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(payload.data)) match {
              case Attempt.Successful(DecodeResult(perHopPayload, _)) => Success((perHopPayload, nextPacket, sharedSecret))
              case Attempt.Failure(cause) => Failure(new RuntimeException(cause.messageWithContext))
            }
        } match {
        case Success((perHopPayload, nextPacket, _)) if nextPacket.isLastPacket =>
          val cmd = perHopPayload match {
            case PerHopPayload(_, finalAmountToForward, _) if finalAmountToForward > add.amountMsat =>
              Left(CMD_FAIL_HTLC(add.id, Right(FinalIncorrectHtlcAmount(add.amountMsat)), commit = true))
            case PerHopPayload(_, _, finalOutgoingCltvValue) if finalOutgoingCltvValue != add.expiry =>
              Left(CMD_FAIL_HTLC(add.id, Right(FinalIncorrectCltvExpiry(add.expiry)), commit = true))
            case _ =>
              Right(add)
          }
          cmd match {
            case Left(cmdFail) =>
              log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} reason=${cmdFail.reason}")
              commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
            case Right(addHtlc) =>
              log.debug(s"forwarding htlc #${add.id} paymentHash=${add.paymentHash} to payment-handler")
              paymentHandler forward addHtlc
          }
        case Success((perHopPayload, nextPacket, _)) =>
          val cmd = channelUpdates.get(perHopPayload.channel_id) match {
            case None =>
              // if we don't (yet?) have a channel_update for the next channel, we consider the channel doesn't exist
              // TODO: use a different channel to the same peer instead?
              Left(CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true))
            case Some(channelUpdate) if !Announcements.isEnabled(channelUpdate.flags) =>
              Left(CMD_FAIL_HTLC(add.id, Right(ChannelDisabled(channelUpdate.flags, channelUpdate)), commit = true))
            case Some(channelUpdate) if add.amountMsat < channelUpdate.htlcMinimumMsat =>
              Left(CMD_FAIL_HTLC(add.id, Right(AmountBelowMinimum(add.amountMsat, channelUpdate)), commit = true))
            case Some(channelUpdate) if add.expiry != perHopPayload.outgoingCltvValue + channelUpdate.cltvExpiryDelta =>
              Left(CMD_FAIL_HTLC(add.id, Right(IncorrectCltvExpiry(add.expiry, channelUpdate)), commit = true))
            case _ =>
              Right(CMD_ADD_HTLC(perHopPayload.amtToForward, add.paymentHash, perHopPayload.outgoingCltvValue, nextPacket.serialize, upstream_opt = Some(add), commit = true))
          }
          cmd match {
            case Left(cmdFail) =>
              log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} to shortChannelId=${perHopPayload.channel_id} reason=${cmdFail.reason}")
              commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
            case Right(cmdAdd) =>
              log.info(s"forwarding htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} to shortChannelId=${perHopPayload.channel_id}")
              register ! Register.ForwardShortId(perHopPayload.channel_id, cmdAdd)
          }
        case Failure(t) =>
          log.warning(s"couldn't parse onion: reason=${t.getMessage}")
          val cmdFail = CMD_FAIL_MALFORMED_HTLC(add.id, Crypto.sha256(add.onionRoutingPacket), failureCode = FailureMessageCodecs.BADONION, commit = true)
          log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} reason=malformed onionHash=${cmdFail.onionHash} failureCode=${cmdFail.failureCode}")
          commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)
      }

    case Status.Failure(Register.ForwardShortIdFailure(Register.ForwardShortId(shortChannelId, CMD_ADD_HTLC(_, _, _, _, Some(add), _)))) =>
      log.warning(s"couldn't resolve downstream channel $shortChannelId, failing htlc #${add.id}")
      val cmdFail = CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true)
      commandBuffer ! CommandBuffer.CommandSend(add.channelId, add.id, cmdFail)

    case Status.Failure(AddHtlcFailed(_, paymentHash, _, Local(None), _)) =>
      // we sent the payment, but we probably restarted and the reference to the original sender was lost, we just publish the failure on the event stream
      context.system.eventStream.publish(PaymentFailed(paymentHash, Nil))

    case Status.Failure(AddHtlcFailed(_, _, error, Local(Some(sender)), _)) =>
      sender ! Status.Failure(error)

    case Status.Failure(AddHtlcFailed(_, paymentHash, error, Relayed(originChannelId, originHtlcId, _, _), channelUpdate_opt)) =>
      val failure = (error, channelUpdate_opt) match {
        case (_: ExpiryTooSmall, Some(channelUpdate)) => ExpiryTooSoon(channelUpdate)
        case (_: ExpiryTooBig, _) => ExpiryTooFar
        case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
        case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
        case (_: ChannelUnavailable, Some(channelUpdate)) if !Announcements.isEnabled(channelUpdate.flags) => ChannelDisabled(channelUpdate.flags, channelUpdate)
        case (_: ChannelUnavailable, None) => PermanentChannelFailure
        case (_: HtlcTimedout, _) => PermanentChannelFailure
        case _ => TemporaryNodeFailure
      }
      val cmdFail = CMD_FAIL_HTLC(originHtlcId, Right(failure), commit = true)
      log.info(s"rejecting htlc #$originHtlcId paymentHash=$paymentHash from channelId=$originChannelId reason=${cmdFail.reason}")
      commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmdFail)

    case ForwardFulfill(fulfill, Local(None), add) =>
      // we sent the payment, but we probably restarted and the reference to the original sender was lost, we just publish the failure on the event stream
      context.system.eventStream.publish(PaymentSucceeded(add.amountMsat, add.paymentHash, fulfill.paymentPreimage, Nil))

    case ForwardFulfill(fulfill, Local(Some(sender)), _) =>
      sender ! fulfill

    case ForwardFulfill(fulfill, Relayed(originChannelId, originHtlcId, amountMsatIn, amountMsatOut), _) =>
      val cmd = CMD_FULFILL_HTLC(originHtlcId, fulfill.paymentPreimage, commit = true)
      commandBuffer ! CommandBuffer.CommandSend(originChannelId, originHtlcId, cmd)
      context.system.eventStream.publish(PaymentRelayed(MilliSatoshi(amountMsatIn), MilliSatoshi(amountMsatOut), Crypto.sha256(fulfill.paymentPreimage)))

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
}
