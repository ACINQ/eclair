package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import fr.acinq.bitcoin.{BinaryData, Crypto}
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
case class Relayed(originChannelId: BinaryData, originHtlcId: Long) extends Origin

case class ForwardAdd(add: UpdateAddHtlc)
case class ForwardFulfill(fulfill: UpdateFulfillHtlc, to: Origin)
case class ForwardLocalFail(error: Throwable, to: Origin) // happens when the failure happened in a local channel (and not in some downstream channel)
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
        case Nil => {}
        case preimages =>
          log.info(s"re-sending ${preimages.size} unacked fulfills to channel $channelId")
          preimages.map(p => CMD_FULFILL_HTLC(p._2, p._3, commit = false)).foreach(channel ! _)
          // better to sign once instead of after each fulfill
          channel ! CMD_SIGN
      }

    case channelUpdate: ChannelUpdate =>
      log.info(s"updating relay parameters with channelUpdate=$channelUpdate")
      context become main(channelUpdates + (channelUpdate.shortChannelId -> channelUpdate))

    case ForwardAdd(add) =>
      Try(Sphinx.parsePacket(nodeParams.privateKey, add.paymentHash, add.onionRoutingPacket))
        .map {
          case Sphinx.ParsedPacket(payload, nextPacket, sharedSecret) => (LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(payload.data)), nextPacket, sharedSecret)
        } match {
        case Success((Attempt.Successful(DecodeResult(perHopPayload, _)), nextPacket, _)) if nextPacket.isLastPacket =>
          log.info(s"looks like we are the final recipient of htlc #${add.id}")
          perHopPayload match {
            case PerHopPayload(_, finalAmountToForward, _) if finalAmountToForward > add.amountMsat =>
              sender ! CMD_FAIL_HTLC(add.id, Right(FinalIncorrectHtlcAmount(add.amountMsat)), commit = true)
            case PerHopPayload(_, _, finalOutgoingCltvValue) if finalOutgoingCltvValue != add.expiry =>
              sender ! CMD_FAIL_HTLC(add.id, Right(FinalIncorrectCltvExpiry(add.expiry)), commit = true)
            case _ if add.expiry < Globals.blockCount.get() + 3 => // TODO: check hardcoded value
              sender ! CMD_FAIL_HTLC(add.id, Right(FinalExpiryTooSoon), commit = true)
            case _ =>
              paymentHandler forward add
          }
        case Success((Attempt.Successful(DecodeResult(perHopPayload, _)), nextPacket, _)) =>
          val channelUpdate_opt = channelUpdates.get(perHopPayload.channel_id)
          channelUpdate_opt match {
            case None =>
              // TODO: clarify what we're supposed to do in the specs
              sender ! CMD_FAIL_HTLC(add.id, Right(TemporaryNodeFailure), commit = true)
            case Some(channelUpdate) if !Announcements.isEnabled(channelUpdate.flags) =>
              sender ! CMD_FAIL_HTLC(add.id, Right(ChannelDisabled(channelUpdate.flags, channelUpdate)), commit = true)
            case Some(channelUpdate) if add.amountMsat < channelUpdate.htlcMinimumMsat =>
              sender ! CMD_FAIL_HTLC(add.id, Right(AmountBelowMinimum(add.amountMsat, channelUpdate)), commit = true)
            case Some(channelUpdate) if add.expiry != perHopPayload.outgoingCltvValue + channelUpdate.cltvExpiryDelta =>
              sender ! CMD_FAIL_HTLC(add.id, Right(IncorrectCltvExpiry(add.expiry, channelUpdate)), commit = true)
            case Some(channelUpdate) if add.expiry < Globals.blockCount.get() + 3 => // TODO: hardcoded value
              sender ! CMD_FAIL_HTLC(add.id, Right(ExpiryTooSoon(channelUpdate)), commit = true)
            case _ =>
              log.info(s"forwarding htlc #${add.id} to shortChannelId=${perHopPayload.channel_id}")
              register forward Register.ForwardShortId(perHopPayload.channel_id, CMD_ADD_HTLC(perHopPayload.amtToForward, add.paymentHash, perHopPayload.outgoingCltvValue, nextPacket.serialize, upstream_opt = Some(add), commit = true))
          }
        case Success((Attempt.Failure(cause), _, _)) =>
          log.error(s"couldn't parse payload: $cause")
          sender ! CMD_FAIL_HTLC(add.id, Right(PermanentNodeFailure), commit = true)
        case Failure(t) =>
          log.error(t, "couldn't parse onion: ")
          // we cannot even parse the onion packet
          sender ! CMD_FAIL_MALFORMED_HTLC(add.id, Crypto.sha256(add.onionRoutingPacket), failureCode = FailureMessageCodecs.BADONION, commit = true)
      }

    case Register.ForwardShortIdFailure(Register.ForwardShortId(shortChannelId, CMD_ADD_HTLC(_, _, _, _, Some(add), _))) =>
      log.warning(s"couldn't resolve downstream channel $shortChannelId, failing htlc #${add.id}")
      register ! Register.Forward(add.channelId, CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true))

    case ForwardFulfill(fulfill, Local(Some(sender))) =>
      sender ! fulfill

    case ForwardFulfill(fulfill, Relayed(originChannelId, originHtlcId)) =>
      val cmd = CMD_FULFILL_HTLC(originHtlcId, fulfill.paymentPreimage, commit = true)
      //context.system.eventStream.publish(PaymentRelayed(MilliSatoshi(htlcIn.amountMsat), MilliSatoshi(htlcIn.amountMsat - htlcOut.amountMsat), htlcIn.paymentHash))
      register ! Register.Forward(originChannelId, cmd)
      // we also store the preimage in a db (note that this happens *after* forwarding the fulfill to the channel, so we don't add latency)
      preimagesDb.addPreimage(originChannelId, originHtlcId, fulfill.paymentPreimage)

    case AckFulfillCmd(channelId, htlcId) =>
      log.debug(s"fulfill acked for channelId=$channelId htlcId=$htlcId")
      preimagesDb.removePreimage(channelId, htlcId)

    case ForwardLocalFail(error, Local(Some(sender))) =>
      sender ! Status.Failure(error)

    case ForwardLocalFail(error, Relayed(originChannelId, originHtlcId)) =>
      // TODO: clarify what we're supposed to do in the specs depending on the error
      val failure = error match {
        case HtlcTimedout(_) => PermanentChannelFailure
        case _ => TemporaryNodeFailure
      }
      val cmd = CMD_FAIL_HTLC(originHtlcId, Right(failure), commit = true)
      register ! Register.Forward(originChannelId, cmd)

    case ForwardFail(fail, Local(Some(sender))) =>
      sender ! fail

    case ForwardFail(fail, Relayed(originChannelId, originHtlcId)) =>
      val cmd = CMD_FAIL_HTLC(originHtlcId, Left(fail.reason), commit = true)
      register ! Register.Forward(originChannelId, cmd)

    case ForwardFailMalformed(fail, Local(Some(sender))) =>
      sender ! fail

    case ForwardFailMalformed(fail, Relayed(originChannelId, originHtlcId)) =>
      val cmd = CMD_FAIL_MALFORMED_HTLC(originHtlcId, fail.onionHash, fail.failureCode, commit = true)
      register ! Register.Forward(originChannelId, cmd)
  }

}

object Relayer {
  def props(nodeParams: NodeParams, register: ActorRef, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeParams, register, paymentHandler)
}
