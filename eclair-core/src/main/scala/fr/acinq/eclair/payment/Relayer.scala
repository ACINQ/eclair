package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams}
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

import scala.util.{Failure, Success, Try}

// @formatter:off

sealed trait Origin
case class Local(sender: Option[ActorRef]) extends Origin // we don't persist reference to local actors
case class Relayed(originChannelId: BinaryData, originHtlcId: Long, amountMsatIn: Long, amountMsatOut: Long) extends Origin

case class ForwardAdd(add: UpdateAddHtlc)
case class ForwardFulfill(fulfill: UpdateFulfillHtlc, to: Origin)
case class ForwardFail(fail: UpdateFailHtlc, to: Origin)
case class ForwardFailMalformed(fail: UpdateFailMalformedHtlc, to: Origin)

case class AckFulfillCmd(channelId: BinaryData, htlcId: Long)

// @formatter:on


/**
  * Created by PM on 01/02/2017.
  */
class Relayer(nodeParams: NodeParams, register: ActorRef, paymentHandler: ActorRef) extends Actor with ActorLogging {

  import nodeParams.preimagesDb

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  override def receive: Receive = main(Map())

  def main(channelUpdates: Map[Long, ChannelUpdate]): Receive = {

    case ChannelStateChanged(channel, _, _, _, NORMAL | SHUTDOWN | CLOSING, d: HasCommitments) =>
      import d.channelId
      preimagesDb.listPreimages(channelId) match {
        case Nil => ()
        case preimages =>
          log.info(s"re-sending ${preimages.size} unacked fulfills to channel $channelId")
          preimages.map(p => CMD_FULFILL_HTLC(p._2, p._3, commit = false)).foreach(channel ! _)
          // better to sign once instead of after each fulfill
          channel ! CMD_SIGN
      }

    case channelUpdate: ChannelUpdate =>
      log.debug(s"updating relay parameters with channelUpdate=$channelUpdate")
      context become main(channelUpdates + (channelUpdate.shortChannelId -> channelUpdate))

    case ForwardAdd(add) =>
      log.debug(s"received forwarding request for htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} ")
      Try(Sphinx.parsePacket(nodeParams.privateKey, add.paymentHash, add.onionRoutingPacket))
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
            case _ if add.expiry < Globals.blockCount.get() + 3 => // TODO: check hardcoded value
              Left(CMD_FAIL_HTLC(add.id, Right(FinalExpiryTooSoon), commit = true))
            case _ =>
              Right(add)
          }
          cmd match {
            case Left(cmdFail) =>
              log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} reason=${cmdFail.reason}")
              sender ! cmdFail
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
            case Some(channelUpdate) if add.expiry < Globals.blockCount.get() + 3 => // TODO: hardcoded value
              Left(CMD_FAIL_HTLC(add.id, Right(ExpiryTooSoon(channelUpdate)), commit = true))
            case _ =>
              Right(CMD_ADD_HTLC(perHopPayload.amtToForward, add.paymentHash, perHopPayload.outgoingCltvValue, nextPacket.serialize, upstream_opt = Some(add), commit = true))
          }
          cmd match {
            case Left(cmdFail) =>
              log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} to shortChannelId=${perHopPayload.channel_id.toHexString} reason=${cmdFail.reason}")
              sender ! cmdFail
            case Right(cmdAdd) =>
              log.info(s"forwarding htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} to shortChannelId=${perHopPayload.channel_id.toHexString}")
              register ! Register.ForwardShortId(perHopPayload.channel_id, cmdAdd)
          }
        case Failure(t) =>
          log.warning(s"couldn't parse onion: reason=${t.getMessage}")
          val cmdFail = CMD_FAIL_MALFORMED_HTLC(add.id, Crypto.sha256(add.onionRoutingPacket), failureCode = FailureMessageCodecs.BADONION, commit = true)
          log.info(s"rejecting htlc #${add.id} paymentHash=${add.paymentHash} from channelId=${add.channelId} reason=malformed onionHash=${cmdFail.onionHash} failureCode=${cmdFail.failureCode}")
          sender ! cmdFail
      }

    case Status.Failure(Register.ForwardShortIdFailure(Register.ForwardShortId(shortChannelId, CMD_ADD_HTLC(_, _, _, _, Some(add), _)))) =>
      log.warning(s"couldn't resolve downstream channel ${shortChannelId.toHexString}, failing htlc #${add.id}")
      register ! Register.Forward(add.channelId, CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true))

    case Status.Failure(AddHtlcFailed(_, error, Local(Some(sender)), _)) =>
      sender ! Status.Failure(error)

    case Status.Failure(AddHtlcFailed(_, error, Relayed(originChannelId, originHtlcId, _, _), channelUpdate_opt)) =>
      val failure = (error, channelUpdate_opt) match {
        case (_: ChannelUnavailable, Some(channelUpdate)) if !Announcements.isEnabled(channelUpdate.flags) => ChannelDisabled(channelUpdate.flags, channelUpdate)
        case (_: InsufficientFunds, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
        case (_: TooManyAcceptedHtlcs, Some(channelUpdate)) => TemporaryChannelFailure(channelUpdate)
        case (_: HtlcTimedout, _) => PermanentChannelFailure
        case _ => TemporaryNodeFailure
      }
      val cmd = CMD_FAIL_HTLC(originHtlcId, Right(failure), commit = true)
      register ! Register.Forward(originChannelId, cmd)

    case ForwardFulfill(fulfill, Local(Some(sender))) =>
      sender ! fulfill

    case ForwardFulfill(fulfill, Relayed(originChannelId, originHtlcId, amountMsatIn, amountMsatOut)) =>
      val cmd = CMD_FULFILL_HTLC(originHtlcId, fulfill.paymentPreimage, commit = true)
      register ! Register.Forward(originChannelId, cmd)
      context.system.eventStream.publish(PaymentRelayed(MilliSatoshi(amountMsatIn), MilliSatoshi(amountMsatOut), Crypto.sha256(fulfill.paymentPreimage)))
      // we also store the preimage in a db (note that this happens *after* forwarding the fulfill to the channel, so we don't add latency)
      preimagesDb.addPreimage(originChannelId, originHtlcId, fulfill.paymentPreimage)

    case AckFulfillCmd(channelId, htlcId) =>
      log.debug(s"fulfill acked for channelId=$channelId htlcId=$htlcId")
      preimagesDb.removePreimage(channelId, htlcId)

    case ForwardFail(fail, Local(Some(sender))) =>
      sender ! fail

    case ForwardFail(fail, Relayed(originChannelId, originHtlcId, _, _)) =>
      val cmd = CMD_FAIL_HTLC(originHtlcId, Left(fail.reason), commit = true)
      register ! Register.Forward(originChannelId, cmd)

    case ForwardFailMalformed(fail, Local(Some(sender))) =>
      sender ! fail

    case ForwardFailMalformed(fail, Relayed(originChannelId, originHtlcId, _, _)) =>
      val cmd = CMD_FAIL_MALFORMED_HTLC(originHtlcId, fail.onionHash, fail.failureCode, commit = true)
      register ! Register.Forward(originChannelId, cmd)

    case "ok" => () // ignoring responses from channels
  }

}

object Relayer {
  def props(nodeParams: NodeParams, register: ActorRef, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeParams, register, paymentHandler)
}
