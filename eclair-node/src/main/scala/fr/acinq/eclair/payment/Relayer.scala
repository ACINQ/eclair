package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.{PrivateKey, sha256}
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, ScriptWitness, Transaction}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ParsedPacket
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

import scala.compat.Platform
import scala.util.{Failure, Success, Try}

// @formatter:off

case class OutgoingChannel(channelId: BinaryData, channel: ActorRef, nodeAddress: BinaryData)

sealed trait Origin
case class Local(sender: ActorRef) extends Origin
case class Relayed(upstream: UpdateAddHtlc) extends Origin

case class AddHtlcSucceeded(add: UpdateAddHtlc, origin: Origin)
case class AddHtlcFailed(add: CMD_ADD_HTLC, failure: FailureMessage)
case class ForwardAdd(add: UpdateAddHtlc)
case class ForwardFulfill(fulfill: UpdateFulfillHtlc)
case class ForwardFail(fail: UpdateFailHtlc)
case class ForwardFailMalformed(fail: UpdateFailMalformedHtlc)

// @formatter:on


/**
  * Created by PM on 01/02/2017.
  */
class Relayer(nodeSecret: PrivateKey, paymentHandler: ActorRef) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])

  override def receive: Receive = main(Set(), Map(), Map(),Map())

  case class DownstreamHtlcId(channelId: BinaryData, htlcId: Long)

  def main(channels: Set[OutgoingChannel], bindings: Map[DownstreamHtlcId, Origin], shortIds: Map[BinaryData, Long], channelUpdates: Map[Long, ChannelUpdate]): Receive = {

    case ChannelStateChanged(channel, _, remoteNodeId, _, NORMAL, d: DATA_NORMAL) =>
      import d.commitments.channelId
      log.info(s"adding channel $channelId to available channels")
      context become main(channels + OutgoingChannel(channelId, channel, remoteNodeId.hash160), bindings, shortIds, channelUpdates)

    case ChannelStateChanged(channel, _, remoteNodeId, _, NEGOTIATING, d: DATA_NEGOTIATING) =>
      import d.commitments.channelId
      log.info(s"removing channel $channelId from available channels")
      // TODO: cleanup bindings
      context become main(channels - OutgoingChannel(channelId, channel, remoteNodeId.hash160), bindings, shortIds, channelUpdates)

    case ChannelStateChanged(channel, _, remoteNodeId, _, CLOSING, d: DATA_CLOSING) =>
      import d.commitments.channelId
      log.info(s"removing channel $channelId from available channels")
      // TODO: cleanup bindings
      context become main(channels - OutgoingChannel(channelId, channel, remoteNodeId.hash160), bindings, shortIds, channelUpdates)

    case ShortChannelIdAssigned(_, channelId, shortChannelId) =>
      context become main(channels, bindings, shortIds + (channelId -> shortChannelId), channelUpdates)

    case channelUpdate: ChannelUpdate =>
      log.info(s"updating relay parameters with channelUpdate=$channelUpdate")
      context become main(channels, bindings, shortIds, channelUpdates + (channelUpdate.shortChannelId -> channelUpdate))

    case ForwardAdd(add) =>
      Try(Sphinx.parsePacket(nodeSecret, add.paymentHash, add.onionRoutingPacket))
        .map {
          case ParsedPacket(payload, nextNodeAddress, nextPacket, sharedSecret) => (LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(payload.data)), nextNodeAddress, nextPacket, sharedSecret)
        } match {
        case Success((_, nextNodeAddress, _, sharedSecret)) if nextNodeAddress.forall(_ == 0) =>
          log.info(s"looks like we are the final recipient of htlc #${add.id}")
          paymentHandler forward (add, sharedSecret)
        case Success((Attempt.Successful(DecodeResult(perHopPayload, _)), nextNodeAddress, nextPacket, sharedSecret)) if channels.exists(_.nodeAddress == nextNodeAddress) =>
          val outgoingChannel = channels.find(_.nodeAddress == nextNodeAddress).get
          val channelUpdate = shortIds.get(outgoingChannel.channelId).flatMap(shortId => channelUpdates.get(shortId))
          channelUpdate match {
            case None =>
              // TODO: clarify what we're supposed to to in the specs
              val reason = Sphinx.createErrorPacket(sharedSecret, TemporaryChannelFailure)
              sender ! CMD_FAIL_HTLC(add.id, reason, commit = true)
            case Some(channelUpdate) if add.amountMsat < channelUpdate.htlcMinimumMsat =>
              val reason = Sphinx.createErrorPacket(sharedSecret, AmountBelowMinimum(add.amountMsat, channelUpdate))
              sender ! CMD_FAIL_HTLC(add.id, reason, commit = true)
            case Some(channelUpdate) if add.expiry != perHopPayload.outgoing_cltv_value + channelUpdate.cltvExpiryDelta =>
              val reason = Sphinx.createErrorPacket(sharedSecret, IncorrectCltvExpiry(add.expiry, channelUpdate))
              sender ! CMD_FAIL_HTLC(add.id, reason, commit = true)
            case Some(channelUpdate) if add.expiry < Globals.blockCount.get() + 3 =>
              // if we are the final payee, we need a reasonable amount of time to pull the funds before the sender can get refunded
              val reason = Sphinx.createErrorPacket(sharedSecret, FinalExpiryTooSoon)
              sender ! CMD_FAIL_HTLC(add.id, reason, commit = true)
            case _ =>
              val downstream = outgoingChannel.channel
              log.info(s"forwarding htlc #${add.id} to downstream=$downstream")
              downstream ! CMD_ADD_HTLC(perHopPayload.amt_to_forward, add.paymentHash, perHopPayload.outgoing_cltv_value, nextPacket, upstream_opt = Some(add), commit = true)
              context.system.eventStream.publish(PaymentRelayed(MilliSatoshi(perHopPayload.amt_to_forward), MilliSatoshi(add.amountMsat - perHopPayload.amt_to_forward), add.paymentHash))
          }
        case Success((Attempt.Successful(DecodeResult(_, _)), nextNodeAddress, _, sharedSecret)) =>
          log.warning(s"couldn't resolve downstream node address $nextNodeAddress, failing htlc #${add.id}")
          val reason = Sphinx.createErrorPacket(sharedSecret, UnknownNextPeer)
          sender ! CMD_FAIL_HTLC(add.id, reason, commit = true)
        case Success((Attempt.Failure(cause), _, _, sharedSecret)) =>
          log.error(s"couldn't parse payload: $cause")
          val reason = Sphinx.createErrorPacket(sharedSecret, PermanentNodeFailure)
          sender ! CMD_FAIL_HTLC(add.id, reason, commit = true)
        case Failure(t) =>
          log.error(t, "couldn't parse onion: ")
          // we cannot even parse the onion packet
          sender ! CMD_FAIL_MALFORMED_HTLC(add.id, Crypto.sha256(add.onionRoutingPacket), failureCode = FailureMessageCodecs.BADONION, commit = true)
      }

    case AddHtlcSucceeded(downstream, origin) =>
      origin match {
        case Local(_) => log.info(s"we are the origin of htlc ${downstream.channelId}/${downstream.id}")
        case Relayed(upstream) => log.info(s"relayed htlc ${upstream.channelId}/${upstream.id} to ${downstream}/${downstream.id}")
      }
      context become main(channels, bindings + (DownstreamHtlcId(downstream.channelId, downstream.id) -> origin), shortIds, channelUpdates)

    case AddHtlcFailed(CMD_ADD_HTLC(_, _, _, onion, Some(updateAddHtlc), _), failure) if channels.exists(_.channelId == updateAddHtlc.channelId) =>
      val upstream = channels.find(_.channelId == updateAddHtlc.channelId).get.channel
      // create an error packet using the upstream node's shared secret
      val sharedSecret = Sphinx.parsePacket(nodeSecret, updateAddHtlc.paymentHash, updateAddHtlc.onionRoutingPacket).sharedSecret
      val errorPacket = Sphinx.createErrorPacket(sharedSecret, failure)
      upstream ! CMD_FAIL_HTLC(updateAddHtlc.id, errorPacket, commit = true)

    case ForwardFulfill(fulfill) =>
      bindings.get(DownstreamHtlcId(fulfill.channelId, fulfill.id)) match {
        case Some(Relayed(origin)) if channels.exists(_.channelId == origin.channelId) =>
          val upstream = channels.find(_.channelId == origin.channelId).get.channel
          upstream ! CMD_FULFILL_HTLC(origin.id, fulfill.paymentPreimage, commit = true)
        case Some(Relayed(origin)) =>
          log.warning(s"origin channel ${origin.channelId} has disappeared in the meantime")
        case Some(Local(origin)) =>
          log.info(s"we were the origin payer for htlc #${fulfill.id}")
          origin ! fulfill
        case None =>
          log.warning(s"no origin found for htlc ${fulfill.channelId}/${fulfill.id}")
      }

    case ForwardFail(fail) =>
      bindings.get(DownstreamHtlcId(fail.channelId, fail.id)) match {
        case Some(Relayed(origin)) if channels.exists(_.channelId == origin.channelId) =>
          val upstream = channels.find(_.channelId == origin.channelId).get.channel
          // obfuscate the error packet with the upstream node's shared secret
          val sharedSecret = Sphinx.parsePacket(nodeSecret, origin.paymentHash, origin.onionRoutingPacket).sharedSecret
          val reason1 = Sphinx.forwardErrorPacket(fail.reason, sharedSecret)
          upstream ! CMD_FAIL_HTLC(origin.id, reason1, commit = true)
        case Some(Relayed(origin)) =>
          log.warning(s"origin channel ${origin.channelId} has disappeared in the meantime")
        case Some(Local(origin)) =>
          log.info(s"we were the origin payer for htlc #${fail.id}")
          origin ! fail
        case None =>
          log.warning(s"no origin found for htlc ${fail.channelId}/${fail.id}")
      }

    case ForwardFailMalformed(fail) =>
      bindings.get(DownstreamHtlcId(fail.channelId, fail.id)) match {
        case Some(Relayed(origin)) if channels.exists(_.channelId == origin.channelId) =>
          val upstream = channels.find(_.channelId == origin.channelId).get.channel
          upstream ! CMD_FAIL_MALFORMED_HTLC(origin.id, fail.onionHash, fail.failureCode, commit = true)
        case Some(Relayed(origin)) =>
          log.warning(s"origin channel ${origin.channelId} has disappeared in the meantime")
        case Some(Local(origin)) =>
          log.info(s"we were the origin payer for htlc #${fail.id}")
          origin ! fail
        case None =>
          log.warning(s"no origin found for htlc ${fail.channelId}/${fail.id}")
      }

    case w@WatchEventSpent(BITCOIN_HTLC_SPENT, tx) =>
      // when a remote or local commitment tx containing outgoing htlcs is published on the network,
      // we watch it in order to extract payment preimage if funds are pulled by the counterparty
      // we can then use these preimages to fulfill origin htlcs
      log.warning(s"processing $w")
      tx.txIn
        .map(_.witness)
        .map {
          case ScriptWitness(Seq(localSig, paymentPreimage, htlcOfferedScript)) =>
            log.info(s"extracted preimage=$paymentPreimage from tx=${Transaction.write(tx)} (claim-htlc-success)")
            Some(paymentPreimage)
          case ScriptWitness(Seq(BinaryData.empty, remoteSig, localSig, paymentPreimage, htlcOfferedScript)) =>
            log.info(s"extracted preimage=$paymentPreimage from tx=${Transaction.write(tx)} (htlc-success)")
            Some(paymentPreimage)
          case _ =>
            None
        }
        .flatten
        .map { preimage =>
          bindings.collect {
            case b@(downstreamHtlcId, Relayed(upstream)) if upstream.paymentHash == sha256(preimage) =>
              log.info(s"found a match between preimage=$preimage and origin htlc for $b")
              self ! ForwardFulfill(UpdateFulfillHtlc(downstreamHtlcId.channelId, downstreamHtlcId.htlcId, preimage))
          }
        }

    case 'channels => sender ! channels
  }
}

object Relayer {
  def props(nodeSecret: PrivateKey, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeSecret: PrivateKey, paymentHandler)
}
