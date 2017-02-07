package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.{PrivateKey, sha256}
import fr.acinq.bitcoin.{BinaryData, ScriptWitness, Transaction}
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire._
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

import scala.util.{Failure, Success, Try}

// @formatter:off

case class OutgoingChannel(channelId: Long, channel: ActorRef, nodeAddress: BinaryData)

sealed trait Origin
case object Local extends Origin
case class Relayed(downstream: UpdateAddHtlc) extends Origin

case class Binding(add: UpdateAddHtlc, origin: Origin)

// @formatter:on


/**
  * Created by PM on 01/02/2017.
  */
class Relayer(nodeSecret: PrivateKey, paymentHandler: ActorRef) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])

  override def receive: Receive = main(Set(), Map())

  def main(upstreams: Set[OutgoingChannel], bindings: Map[UpdateAddHtlc, Origin]): Receive = {

    case ChannelChangedState(channel, _, remoteNodeId, _, NORMAL, d: DATA_NORMAL) =>
      import d.commitments.channelId
      log.info(s"adding channel $channelId to available upstreams")
      context become main(upstreams + OutgoingChannel(channelId, channel, remoteNodeId.hash160), bindings)

    case ChannelChangedState(channel, _, remoteNodeId, _, NEGOTIATING, d: DATA_NEGOTIATING) =>
      import d.commitments.channelId
      log.info(s"removing channel $channelId from upstreams/downstreams (mutual close)")
      // TODO: cleanup bindings
      context become main(upstreams - OutgoingChannel(channelId, channel, remoteNodeId.hash160), bindings)

    case ChannelChangedState(channel, _, remoteNodeId, _, CLOSING, d: DATA_CLOSING) =>
      import d.commitments.channelId
      log.info(s"removing channel $channelId from upstreams/downstreams (unilateral close)")
      // TODO: cleanup bindings
      context become main(upstreams - OutgoingChannel(channelId, channel, remoteNodeId.hash160), bindings)

    case add: UpdateAddHtlc =>
      Try(Sphinx.parsePacket(nodeSecret, add.paymentHash, add.onionRoutingPacket))
        .map {
          case (payload, nextNodeAddress, nextPacket) => (Codecs.perHopPayloadCodec.decode(BitVector(payload.data)), nextNodeAddress, nextPacket)
        } match {
        case Success((_, nextNodeAddress, _)) if nextNodeAddress.forall(_ == 0) =>
          log.info(s"we are the final recipient of htlc #${add.id}")
          context.system.eventStream.publish(PaymentReceived(self, add.paymentHash))
          paymentHandler forward add
        case Success((Attempt.Successful(DecodeResult(payload, _)), nextNodeAddress, nextPacket)) if upstreams.exists(_.nodeAddress == nextNodeAddress) =>
          val upstream = upstreams.find(_.nodeAddress == nextNodeAddress).get.channel
          log.info(s"forwarding htlc #${add.id} to upstream=$upstream")
          upstream ! CMD_ADD_HTLC(payload.amt_to_forward, add.paymentHash, payload.outgoing_cltv_value, nextPacket, origin = Relayed(add), commit = true)
          context become main(upstreams, bindings)
        case Success((Attempt.Successful(DecodeResult(_, _)), nextNodeAddress, _)) =>
          log.warning(s"couldn't resolve upstream node address $nextNodeAddress, failing htlc #${add.id}")
          sender ! CMD_FAIL_HTLC(add.id, "route error", commit = true)
        case Success((Attempt.Failure(cause), _, _)) =>
          log.error(s"couldn't parse payload: $cause")
          sender ! CMD_FAIL_HTLC(add.id, "payload parsing error", commit = true)
        case Failure(t) =>
          log.error(t, "couldn't parse onion: ")
          sender ! CMD_FAIL_HTLC(add.id, "onion parsing error", commit = true)
      }

    case Binding(upstream, origin) =>
      origin match {
        case Local => log.info(s"we are the origin of htlc ${upstream.channelId}/${upstream.id}")
        case Relayed(downstream) => log.info(s"relayed htlc ${downstream.channelId}/${downstream.id} to ${upstream.channelId}/${upstream.id}")
      }
      context become main(upstreams, bindings + (upstream -> origin))

    case (add: UpdateAddHtlc, fulfill: UpdateFulfillHtlc) =>
      bindings.get(add) match {
        case Some(Relayed(origin)) if upstreams.exists(_.channelId == origin.channelId) =>
          val downstream = upstreams.find(_.channelId == origin.channelId).get.channel
          downstream ! CMD_SIGN
          downstream ! CMD_FULFILL_HTLC(origin.id, fulfill.paymentPreimage)
          downstream ! CMD_SIGN
        case Some(Relayed(origin)) =>
          log.warning(s"origin channel ${origin.channelId} has disappeared in the meantime")
        case Some(Local) =>
          log.info(s"we were the origin payer for htlc #${fulfill.id}")
          context.system.eventStream.publish(PaymentSent(self, add.paymentHash))
        case None =>
          log.warning(s"no origin found for htlc $add")
      }

    case (add: UpdateAddHtlc, fail: UpdateFailHtlc) =>
      bindings.get(add) match {
        case Some(Relayed(origin)) if upstreams.exists(_.channelId == origin.channelId) =>
          val downstream = upstreams.find(_.channelId == origin.channelId).get.channel
          downstream ! CMD_SIGN
          // TODO: fix new String(fail.reason)
          downstream ! CMD_FAIL_HTLC(origin.id, new String(fail.reason))
          downstream ! CMD_SIGN
        case Some(Relayed(origin)) =>
          log.warning(s"origin channel ${origin.channelId} has disappeared in the meantime")
        case Some(Local) =>
          log.info(s"we were the origin payer for htlc #${fail.id}")
          // TODO: fix new String(fail.reason)
          context.system.eventStream.publish(PaymentFailed(self, add.paymentHash, new String(fail.reason)))
        case None =>
          log.warning(s"no origin found for htlc $add")
      }

    case WatchEventSpent(BITCOIN_HTLC_SPENT, tx) =>
      // when a remote or local commitment tx containing outgoing htlcs is published on the network,
      // we watch it in order to extract payment preimage if funds are pulled by the counterparty
      // we can then use these preimages to fulfill origin htlcs
      tx.txIn
        .map(_.witness)
        .map {
          case ScriptWitness(localSig :: paymentPreimage :: htlcOfferedScript :: Nil) =>
            log.info(s"extracted preimage=$paymentPreimage from tx=${Transaction.write(tx)}")
            Some(paymentPreimage)
          case ScriptWitness(BinaryData.empty :: remoteSig :: localSig :: paymentPreimage :: htlcOfferedScript :: Nil) =>
            log.info(s"extracted preimage=$paymentPreimage from tx=${Transaction.write(tx)}")
            Some(paymentPreimage)
          case _ =>
            None
        }
        .flatten
        .map { preimage =>
          bindings.collect {
            case b@(upstream, Relayed(downstream)) if downstream.paymentHash == sha256(preimage) =>
              log.info(s"found a match between preimage=$preimage and origin htlc for $b")
              self ! (upstream, UpdateFulfillHtlc(upstream.channelId, upstream.id, preimage))
          }
        }

    case 'upstreams => sender ! upstreams
  }
}

object Relayer {
  def props(nodeSecret: PrivateKey, paymentHandler: ActorRef) = Props(classOf[Relayer], nodeSecret: PrivateKey, paymentHandler)
}
