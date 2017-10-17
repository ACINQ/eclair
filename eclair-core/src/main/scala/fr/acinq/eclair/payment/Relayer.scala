package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
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

// @formatter:on


/**
  * Created by PM on 01/02/2017.
  */
class Relayer(nodeSecret: PrivateKey, paymentHandler: ActorRef) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  override def receive: Receive = main(Map(), Map(), Map())

  def shortId2Channel(channels: Map[BinaryData, ActorRef], shortIds: Map[Long, BinaryData], shortId: Long): Option[ActorRef] = shortIds.get(shortId).flatMap(channels.get(_))

  def main(channels: Map[BinaryData, ActorRef], shortIds: Map[Long, BinaryData], channelUpdates: Map[Long, ChannelUpdate]): Receive = {

    case ChannelStateChanged(channel, _, _, _, NORMAL, d: DATA_NORMAL) =>
      import d.commitments.channelId
      log.info(s"adding channel $channelId to available channels")
      context become main(channels + (channelId -> channel), shortIds, channelUpdates)

    case ChannelStateChanged(_, _, _, _, NEGOTIATING, d: DATA_NEGOTIATING) =>
      import d.commitments.channelId
      log.info(s"removing channel $channelId from available channels")
      context become main(channels - channelId, shortIds, channelUpdates)

    case ChannelStateChanged(_, _, _, _, CLOSING, d: DATA_CLOSING) =>
      import d.commitments.channelId
      log.info(s"removing channel $channelId from available channels")
      context become main(channels - channelId, shortIds, channelUpdates)

    case ShortChannelIdAssigned(_, channelId, shortChannelId) =>
      context become main(channels, shortIds + (shortChannelId -> channelId), channelUpdates)

    case channelUpdate: ChannelUpdate =>
      log.info(s"updating relay parameters with channelUpdate=$channelUpdate")
      context become main(channels, shortIds, channelUpdates + (channelUpdate.shortChannelId -> channelUpdate))

    case ForwardAdd(add) =>
      Try(Sphinx.parsePacket(nodeSecret, add.paymentHash, add.onionRoutingPacket))
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
          shortId2Channel(channels, shortIds, perHopPayload.channel_id) match {
            case Some(downstream) =>
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
                  log.info(s"forwarding htlc #${add.id} to downstream=$downstream")
                  downstream forward CMD_ADD_HTLC(perHopPayload.amtToForward, add.paymentHash, perHopPayload.outgoingCltvValue, nextPacket.serialize, upstream_opt = Some(add), commit = true)
              }
            case None =>
              log.warning(s"couldn't resolve downstream channel ${perHopPayload.channel_id}, failing htlc #${add.id}")
              sender ! CMD_FAIL_HTLC(add.id, Right(UnknownNextPeer), commit = true)
          }
        case Success((Attempt.Failure(cause), _, _)) =>
          log.error(s"couldn't parse payload: $cause")
          sender ! CMD_FAIL_HTLC(add.id, Right(PermanentNodeFailure), commit = true)
        case Failure(t) =>
          log.error(t, "couldn't parse onion: ")
          // we cannot even parse the onion packet
          sender ! CMD_FAIL_MALFORMED_HTLC(add.id, Crypto.sha256(add.onionRoutingPacket), failureCode = FailureMessageCodecs.BADONION, commit = true)
      }

    case ForwardFulfill(fulfill, Local(Some(sender))) =>
      sender ! fulfill

    case ForwardFulfill(fulfill, Relayed(originChannelId, originHtlcId)) =>
      val cmd = CMD_FULFILL_HTLC(originHtlcId, fulfill.paymentPreimage, commit = true)
      //context.system.eventStream.publish(PaymentRelayed(MilliSatoshi(htlcIn.amountMsat), MilliSatoshi(htlcIn.amountMsat - htlcOut.amountMsat), htlcIn.paymentHash))
      forward(cmd, originChannelId, channels)

    case ForwardLocalFail(error, Local(Some(sender))) =>
      sender ! Status.Failure(error)

    case ForwardLocalFail(error, Relayed(originChannelId, originHtlcId)) =>
      // TODO: clarify what we're supposed to do in the specs depending on the error
      val failure = error match {
        case HtlcTimedout(_) => PermanentChannelFailure
        case _ =>
          val channelUpdate_opt = for {
            channelId <- channels.map(_.swap).get(sender)
            shortId <- shortIds.map(_.swap).get(channelId)
            update <- channelUpdates.get(shortId)
          } yield update
          // detail errors are caught before relaying the htlc to the downstream channel, here we just return generic error messages
          channelUpdate_opt match {
            case None => TemporaryNodeFailure
            case Some(channelUpdate) => TemporaryChannelFailure(channelUpdate)
          }
      }
      val cmd = CMD_FAIL_HTLC(originHtlcId, Right(failure), commit = true)
      forward(cmd, originChannelId, channels)

    case ForwardFail(fail, Local(Some(sender))) =>
      sender ! fail

    case ForwardFail(fail, Relayed(originChannelId, originHtlcId)) =>
      val cmd = CMD_FAIL_HTLC(originHtlcId, Left(fail.reason), commit = true)
      forward(cmd, originChannelId, channels)

    case ForwardFailMalformed(fail, Local(Some(sender))) =>
      sender ! fail

    case ForwardFailMalformed(fail, Relayed(originChannelId, originHtlcId)) =>
      val cmd = CMD_FAIL_MALFORMED_HTLC(originHtlcId, fail.onionHash, fail.failureCode, commit = true)
      forward(cmd, originChannelId, channels)

    case 'channels => sender ! channels
  }

  def forward(cmd: Command, originChannelId: BinaryData, channels: Map[BinaryData, ActorRef]) = channels.get(originChannelId) match {
    case Some(channel) => channel ! cmd
    case None => log.warning(s"couldn't resolve originChannelId=$originChannelId")
  }

}

object Relayer {
  def props(nodeSecret: PrivateKey, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeSecret: PrivateKey, paymentHandler)
}
