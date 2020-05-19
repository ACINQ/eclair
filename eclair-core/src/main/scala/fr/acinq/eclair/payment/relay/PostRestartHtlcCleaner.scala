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

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment.Monitoring.Tags
import fr.acinq.eclair.payment.{IncomingPacket, PaymentFailed, PaymentSent}
import fr.acinq.eclair.transactions.DirectedHtlc.outgoing
import fr.acinq.eclair.transactions.OutgoingHtlc
import fr.acinq.eclair.wire.{TemporaryNodeFailure, UpdateAddHtlc}
import fr.acinq.eclair.{Features, LongToBtcAmount, NodeParams}
import scodec.bits.ByteVector

import scala.compat.Platform
import scala.concurrent.Promise
import scala.util.Try

/**
 * Created by t-bast on 21/11/2019.
 */

/**
 * If we have stopped eclair while it was handling HTLCs, it is possible that we are in a state were incoming HTLCs were
 * committed by both sides, but we didn't have time to send and/or sign corresponding HTLCs to the downstream node.
 * It's also possible that we partially forwarded a payment (if MPP was used downstream): we have lost the intermediate
 * state necessary to retry that payment, so we need to wait for the partial HTLC set sent downstream to either fail or
 * fulfill (and forward the result upstream).
 *
 * If we were sending a payment (no downstream HTLCs) when we stopped eclair, we might have sent only a portion of the
 * payment (because of multi-part): we have lost the intermediate state necessary to retry that payment, so we need to
 * wait for the partial HTLC set sent downstream to either fail or fulfill the payment in our DB.
 */
class PostRestartHtlcCleaner(nodeParams: NodeParams, commandBuffer: ActorRef, initialized: Option[Promise[Done]] = None) extends Actor with ActorLogging {

  import PostRestartHtlcCleaner._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  val brokenHtlcs = {
    val channels = listLocalChannels(nodeParams.db.channels)
    cleanupRelayDb(channels, nodeParams.db.pendingRelay)
    checkBrokenHtlcs(channels, nodeParams.db.payments, nodeParams.privateKey, nodeParams.features)
  }

  Metrics.PendingNotRelayed.update(brokenHtlcs.notRelayed.size)
  Metrics.PendingRelayedOut.update(brokenHtlcs.relayedOut.keySet.size)

  override def receive: Receive = main(brokenHtlcs)

  // Once we've loaded the channels and identified broken HTLCs, we let other components know they can proceed.
  Try(initialized.map(_.success(Done)))

  def main(brokenHtlcs: BrokenHtlcs): Receive = {
    // When channels are restarted we immediately fail the incoming HTLCs that weren't relayed.
    case e@ChannelStateChanged(channel, _, _, WAIT_FOR_INIT_INTERNAL | OFFLINE | SYNCING | CLOSING, NORMAL | SHUTDOWN | CLOSING | CLOSED, data: HasCommitments) =>
      log.debug("channel {}: {} -> {}", data.channelId, e.previousState, e.currentState)
      val acked = brokenHtlcs.notRelayed
        .filter(_.add.channelId == data.channelId) // only consider htlcs coming from this channel
        .filter {
          case IncomingHtlc(htlc, preimage_opt) if Commitments.getIncomingHtlcCrossSigned(data.commitments, htlc.id).isDefined =>
            // this htlc is cross signed in the current commitment, we can settle it
            preimage_opt match {
              case Some(preimage) =>
                log.info(s"fulfilling broken htlc=$htlc")
                Metrics.Resolved.withTag(Tags.Success, value = true).withTag(Metrics.Relayed, value = false).increment()
                if (e.currentState != CLOSED) {
                  channel ! CMD_FULFILL_HTLC(htlc.id, preimage, commit = true)
                }
              case None =>
                log.info(s"failing not relayed htlc=$htlc")
                Metrics.Resolved.withTag(Tags.Success, value = false).withTag(Metrics.Relayed, value = false).increment()
                if (e.currentState != CLOSING && e.currentState != CLOSED) {
                  channel ! CMD_FAIL_HTLC(htlc.id, Right(TemporaryNodeFailure), commit = true)
                }
            }
            false // the channel may very well be disconnected before we sign (=ack) the fail/fulfill, so we keep it for now
          case _ =>
            true // the htlc has already been settled, we can forget about it now
        }
      acked.foreach(htlc => log.info(s"forgetting htlc id=${htlc.add.id} channelId=${htlc.add.channelId}"))
      val notRelayed1 = brokenHtlcs.notRelayed diff acked
      Metrics.PendingNotRelayed.update(notRelayed1.size)
      context become main(brokenHtlcs.copy(notRelayed = notRelayed1))

    case _: ChannelStateChanged => // ignore other channel state changes

    case ff: Relayer.ForwardFulfill =>
      log.info("htlc fulfilled downstream: ({},{})", ff.htlc.channelId, ff.htlc.id)
      handleDownstreamFulfill(brokenHtlcs, ff.to, ff.htlc, ff.paymentPreimage)

    case ff: Relayer.ForwardFail =>
      log.info("htlc failed downstream: ({},{},{})", ff.htlc.channelId, ff.htlc.id, ff.getClass.getSimpleName)
      handleDownstreamFailure(brokenHtlcs, ff.to, ff.htlc)

    case GetBrokenHtlcs => sender ! brokenHtlcs

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case ChannelCommandResponse.Ok => // ignoring responses from channels
  }

  private def handleDownstreamFulfill(brokenHtlcs: BrokenHtlcs, origin: Origin, fulfilledHtlc: UpdateAddHtlc, paymentPreimage: ByteVector32): Unit =
    brokenHtlcs.relayedOut.get(origin) match {
      case Some(relayedOut) => origin match {
        case Origin.Local(id, _) =>
          val feesPaid = 0.msat // fees are unknown since we lost the reference to the payment
          nodeParams.db.payments.getOutgoingPayment(id) match {
            case Some(p) =>
              nodeParams.db.payments.updateOutgoingPayment(PaymentSent(p.parentId, fulfilledHtlc.paymentHash, paymentPreimage, p.recipientAmount, p.recipientNodeId, PaymentSent.PartialPayment(id, fulfilledHtlc.amountMsat, feesPaid, fulfilledHtlc.channelId, None) :: Nil))
              // If all downstream HTLCs are now resolved, we can emit the payment event.
              val payments = nodeParams.db.payments.listOutgoingPayments(p.parentId)
              if (!payments.exists(p => p.status == OutgoingPaymentStatus.Pending)) {
                val succeeded = payments.collect {
                  case OutgoingPayment(id, _, _, _, _, amount, _, _, _, _, OutgoingPaymentStatus.Succeeded(_, feesPaid, _, completedAt)) =>
                    PaymentSent.PartialPayment(id, amount, feesPaid, ByteVector32.Zeroes, None, completedAt)
                }
                val sent = PaymentSent(p.parentId, fulfilledHtlc.paymentHash, paymentPreimage, p.recipientAmount, p.recipientNodeId, succeeded)
                log.info(s"payment id=${sent.id} paymentHash=${sent.paymentHash} successfully sent (amount=${sent.recipientAmount})")
                context.system.eventStream.publish(sent)
              }
            case None =>
              log.warning(s"database inconsistency detected: payment $id is fulfilled but doesn't have a corresponding database entry")
              // Since we don't have a matching DB entry, we've lost the payment recipient and total amount, so we put
              // dummy values in the DB (to make sure we store the preimage) but we don't emit an event.
              val dummyFinalAmount = fulfilledHtlc.amountMsat
              val dummyNodeId = nodeParams.nodeId
              nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id, id, None, fulfilledHtlc.paymentHash, PaymentType.Standard, fulfilledHtlc.amountMsat, dummyFinalAmount, dummyNodeId, System.currentTimeMillis, None, OutgoingPaymentStatus.Pending))
              nodeParams.db.payments.updateOutgoingPayment(PaymentSent(id, fulfilledHtlc.paymentHash, paymentPreimage, dummyFinalAmount, dummyNodeId, PaymentSent.PartialPayment(id, fulfilledHtlc.amountMsat, feesPaid, fulfilledHtlc.channelId, None) :: Nil))
          }
          // There can never be more than one pending downstream HTLC for a given local origin (a multi-part payment is
          // instead spread across multiple local origins) so we can now forget this origin.
          Metrics.PendingRelayedOut.decrement()
          context become main(brokenHtlcs.copy(relayedOut = brokenHtlcs.relayedOut - origin))
        case Origin.TrampolineRelayed(origins, _) =>
          // We fulfill upstream as soon as we have the payment preimage available.
          if (!brokenHtlcs.settledUpstream.contains(origin)) {
            log.info(s"received preimage for paymentHash=${fulfilledHtlc.paymentHash}: fulfilling ${origins.length} HTLCs upstream")
            origins.foreach { case (channelId, htlcId) =>
              Metrics.Resolved.withTag(Tags.Success, value = true).withTag(Metrics.Relayed, value = true).increment()
              commandBuffer ! CommandBuffer.CommandSend(channelId, CMD_FULFILL_HTLC(htlcId, paymentPreimage, commit = true))
            }
          }
          val relayedOut1 = relayedOut diff Set((fulfilledHtlc.channelId, fulfilledHtlc.id))
          if (relayedOut1.isEmpty) {
            log.info(s"payment with paymentHash=${fulfilledHtlc.paymentHash} successfully relayed")
            // We could emit a TrampolinePaymentRelayed event but that requires more book-keeping on incoming HTLCs.
            // It seems low priority so isn't done at the moment but can be added when we feel we need it.
            Metrics.PendingRelayedOut.decrement()
            context become main(brokenHtlcs.copy(relayedOut = brokenHtlcs.relayedOut - origin, settledUpstream = brokenHtlcs.settledUpstream - origin))
          } else {
            context become main(brokenHtlcs.copy(relayedOut = brokenHtlcs.relayedOut + (origin -> relayedOut1), settledUpstream = brokenHtlcs.settledUpstream + origin))
          }
        case _: Origin.Relayed =>
          Metrics.Unhandled.withTag(Metrics.Hint, origin.getClass.getSimpleName).increment()
          log.error(s"unsupported origin: ${origin.getClass.getSimpleName}")
      }
      case None =>
        Metrics.Unhandled.withTag(Metrics.Hint, "MissingOrigin").increment()
        log.error(s"received fulfill with unknown origin $origin for htlcId=${fulfilledHtlc.id}, channelId=${fulfilledHtlc.channelId}: cannot forward upstream")
    }

  private def handleDownstreamFailure(brokenHtlcs: BrokenHtlcs, origin: Origin, failedHtlc: UpdateAddHtlc): Unit =
    brokenHtlcs.relayedOut.get(origin) match {
      case Some(relayedOut) =>
        // If this is a local payment, we need to update the DB:
        origin match {
          case Origin.Local(id, _) => nodeParams.db.payments.updateOutgoingPayment(PaymentFailed(id, failedHtlc.paymentHash, Nil))
          case _ =>
        }
        val relayedOut1 = relayedOut diff Set((failedHtlc.channelId, failedHtlc.id))
        // This was the last downstream HTLC we were waiting for.
        if (relayedOut1.isEmpty) {
          // If we haven't already settled upstream, we can fail now.
          if (!brokenHtlcs.settledUpstream.contains(origin)) {
            origin match {
              case Origin.Local(id, _) => nodeParams.db.payments.getOutgoingPayment(id).foreach(p => {
                val payments = nodeParams.db.payments.listOutgoingPayments(p.parentId)
                if (payments.forall(_.status.isInstanceOf[OutgoingPaymentStatus.Failed])) {
                  log.warning(s"payment failed for paymentHash=${failedHtlc.paymentHash}")
                  context.system.eventStream.publish(PaymentFailed(p.parentId, failedHtlc.paymentHash, Nil))
                }
              })
              case Origin.TrampolineRelayed(origins, _) =>
                log.warning(s"payment failed for paymentHash=${failedHtlc.paymentHash}: failing ${origins.length} upstream HTLCs")
                origins.foreach { case (channelId, htlcId) =>
                  Metrics.Resolved.withTag(Tags.Success, value = false).withTag(Metrics.Relayed, value = true).increment()
                  // We don't bother decrypting the downstream failure to forward a more meaningful error upstream, it's
                  // very likely that it won't be actionable anyway because of our node restart.
                  commandBuffer ! CommandBuffer.CommandSend(channelId, CMD_FAIL_HTLC(htlcId, Right(TemporaryNodeFailure), commit = true))
                }
              case _: Origin.Relayed =>
                Metrics.Unhandled.withTag(Metrics.Hint, origin.getClass.getSimpleName).increment()
                log.error(s"unsupported origin: ${origin.getClass.getSimpleName}")
            }
          }
          // We can forget about this payment since it has been fully settled downstream and upstream.
          Metrics.PendingRelayedOut.decrement()
          context become main(brokenHtlcs.copy(relayedOut = brokenHtlcs.relayedOut - origin, settledUpstream = brokenHtlcs.settledUpstream - origin))
        } else {
          context become main(brokenHtlcs.copy(relayedOut = brokenHtlcs.relayedOut + (origin -> relayedOut1)))
        }
      case None =>
        Metrics.Unhandled.withTag(Metrics.Hint, "MissingOrigin").increment()
        log.error(s"received failure with unknown origin $origin for htlcId=${failedHtlc.id}, channelId=${failedHtlc.channelId}")
    }

}

object PostRestartHtlcCleaner {

  def props(nodeParams: NodeParams, commandBuffer: ActorRef, initialized: Option[Promise[Done]] = None) = Props(classOf[PostRestartHtlcCleaner], nodeParams, commandBuffer, initialized)

  case object GetBrokenHtlcs

  object Metrics {

    import kamon.Kamon

    val Relayed = "relayed"
    val Hint = "hint"

    private val pending = Kamon.gauge("payment.broken-htlcs.pending", "Broken HTLCs because of a node restart")
    val PendingNotRelayed = pending.withTag(Relayed, value = false)
    val PendingRelayedOut = pending.withTag(Relayed, value = true)
    val Resolved = Kamon.gauge("payment.broken-htlcs.resolved", "Broken HTLCs resolved after a node restart")
    val Unhandled = Kamon.gauge("payment.broken-htlcs.unhandled", "Broken HTLCs that we don't know how to handle")

  }

  /**
   * @param add          incoming HTLC that was committed upstream.
   * @param preimage_opt payment preimage if the payment succeeded downstream.
   */
  case class IncomingHtlc(add: UpdateAddHtlc, preimage_opt: Option[ByteVector32])

  /**
   * Payments that may be in a broken state after a restart.
   *
   * @param notRelayed      incoming HTLCs that were committed upstream but not relayed downstream.
   * @param relayedOut      outgoing HTLC sets that may have been incompletely sent and need to be watched.
   * @param settledUpstream upstream payments that have already been settled (failed or fulfilled) by this actor.
   */
  case class BrokenHtlcs(notRelayed: Seq[IncomingHtlc], relayedOut: Map[Origin, Set[(ByteVector32, Long)]], settledUpstream: Set[Origin])

  /** Returns true if the given HTLC matches the given origin. */
  private def matchesOrigin(htlcIn: UpdateAddHtlc, origin: Origin): Boolean = origin match {
    case _: Origin.Local => false
    case Origin.Relayed(originChannelId, originHtlcId, _, _) => originChannelId == htlcIn.channelId && originHtlcId == htlcIn.id
    case Origin.TrampolineRelayed(origins, _) => origins.exists {
      case (originChannelId, originHtlcId) => originChannelId == htlcIn.channelId && originHtlcId == htlcIn.id
    }
  }

  /**
   * When we restart while we're receiving a payment, we need to look at the DB to find out whether the payment
   * succeeded or not (which may have triggered external downstream components to treat the payment as received and
   * ship some physical goods to a customer).
   */
  private def shouldFulfill(finalPacket: IncomingPacket.FinalPacket, paymentsDb: IncomingPaymentsDb): Option[ByteVector32] =
    paymentsDb.getIncomingPayment(finalPacket.add.paymentHash) match {
      case Some(IncomingPayment(_, preimage, _, _, IncomingPaymentStatus.Received(_, _))) => Some(preimage)
      case _ => None
    }

  /**
   * If we do nothing after a restart, incoming HTLCs that were committed upstream but not relayed will eventually
   * expire and we won't lose money, but the channel will get closed, which is a major inconvenience. We want to detect
   * this and fast-fail those HTLCs and thus preserve channels.
   *
   * Outgoing HTLC sets that are still pending may either succeed or fail: we need to watch them to properly forward the
   * result upstream to preserve channels.
   */
  private def checkBrokenHtlcs(channels: Seq[HasCommitments], paymentsDb: IncomingPaymentsDb, privateKey: PrivateKey, features: Features)(implicit log: LoggingAdapter): BrokenHtlcs = {
    // We are interested in incoming HTLCs, that have been *cross-signed* (otherwise they wouldn't have been relayed).
    // They signed it first, so the HTLC will first appear in our commitment tx, and later on in their commitment when
    // we subsequently sign it. That's why we need to look in *their* commitment with direction=OUT.
    val htlcsIn = channels
      .flatMap(_.commitments.remoteCommit.spec.htlcs)
      .collect(outgoing)
      .map(IncomingPacket.decrypt(_, privateKey, features))
      .collect {
        // When we're not the final recipient, we'll only consider HTLCs that aren't relayed downstream, so no need to look for a preimage.
        case Right(IncomingPacket.ChannelRelayPacket(add, _, _)) => IncomingHtlc(add, None)
        case Right(IncomingPacket.NodeRelayPacket(add, _, _, _)) => IncomingHtlc(add, None)
        // When we're the final recipient, we want to know if we want to fulfill or fail.
        case Right(p@IncomingPacket.FinalPacket(add, _)) => IncomingHtlc(add, shouldFulfill(p, paymentsDb))
      }

    def isPendingUpstream(channelId: ByteVector32, htlcId: Long): Boolean =
      htlcsIn.exists(htlc => htlc.add.channelId == channelId && htlc.add.id == htlcId)

    // We group relayed outgoing HTLCs by their origin.
    val relayedOut = channels
      .flatMap { c =>
        // Filter out HTLCs that will never reach the blockchain or have already been timed-out on-chain.
        val htlcsToIgnore: Set[Long] = c match {
          case d: DATA_CLOSING =>
            val closingType_opt = Closing.isClosingTypeAlreadyKnown(d)
            val overriddenHtlcs: Set[Long] = (closingType_opt match {
              case Some(c: Closing.LocalClose) => Closing.overriddenOutgoingHtlcs(d, c.localCommitPublished.commitTx)
              case Some(c: Closing.RemoteClose) => Closing.overriddenOutgoingHtlcs(d, c.remoteCommitPublished.commitTx)
              case _ => Set.empty[UpdateAddHtlc]
            }).map(_.id)
            val irrevocablySpent = closingType_opt match {
              case Some(c: Closing.LocalClose) => c.localCommitPublished.irrevocablySpent.values.toSet
              case Some(c: Closing.RemoteClose) => c.remoteCommitPublished.irrevocablySpent.values.toSet
              case _ => Set.empty[ByteVector32]
            }
            val timedoutHtlcs: Set[Long] = (closingType_opt match {
              case Some(c: Closing.LocalClose) =>
                val confirmedTxs = c.localCommitPublished.commitTx :: c.localCommitPublished.htlcTimeoutTxs.filter(tx => irrevocablySpent.contains(tx.txid))
                confirmedTxs.flatMap(tx => Closing.timedoutHtlcs(c.localCommit, c.localCommitPublished, d.commitments.localParams.dustLimit, tx))
              case Some(c: Closing.RemoteClose) =>
                val confirmedTxs = c.remoteCommitPublished.commitTx :: c.remoteCommitPublished.claimHtlcTimeoutTxs.filter(tx => irrevocablySpent.contains(tx.txid))
                confirmedTxs.flatMap(tx => Closing.timedoutHtlcs(c.remoteCommit, c.remoteCommitPublished, d.commitments.remoteParams.dustLimit, tx))
              case _ => Seq.empty[UpdateAddHtlc]
            }).map(_.id).toSet
            overriddenHtlcs ++ timedoutHtlcs
          case _ => Set.empty
        }
        c.commitments.originChannels.collect { case (outgoingHtlcId, origin) if !htlcsToIgnore.contains(outgoingHtlcId) => (origin, c.channelId, outgoingHtlcId) }
      }
      .groupBy { case (origin, _, _) => origin }
      .mapValues(_.map { case (_, channelId, htlcId) => (channelId, htlcId) }.toSet)
      // We are only interested in HTLCs that are pending upstream (not fulfilled nor failed yet).
      // It may be the case that we have unresolved HTLCs downstream that have been resolved upstream when the downstream
      // channel is closing (e.g. due to an HTLC timeout) because cooperatively failing the HTLC downstream will be
      // instant whereas the uncooperative close of the downstream channel will take time.
      .filterKeys {
        case _: Origin.Local => true
        case Origin.Relayed(channelId, htlcId, _, _) => isPendingUpstream(channelId, htlcId)
        case Origin.TrampolineRelayed(htlcs, _) => htlcs.exists { case (channelId, htlcId) => isPendingUpstream(channelId, htlcId) }
      }
      .toMap

    val notRelayed = htlcsIn.filterNot(htlcIn => relayedOut.keys.exists(origin => matchesOrigin(htlcIn.add, origin)))
    log.info(s"htlcsIn=${htlcsIn.length} notRelayed=${notRelayed.length} relayedOut=${relayedOut.values.flatten.size}")
    log.info("notRelayed={}", notRelayed.map(htlc => (htlc.add.channelId, htlc.add.id)))
    log.info("relayedOut={}", relayedOut)
    BrokenHtlcs(notRelayed, relayedOut, Set.empty)
  }

  /**
   * List local channels that may have pending HTLCs, ignoring channels that are still in CLOSING state but have
   * actually been closed. This can happen when the app is stopped just after a channel state has transitioned to CLOSED
   * and before it has effectively been removed. Such closed channels will automatically be removed once the channel is
   * restored.
   */
  private def listLocalChannels(channelsDb: ChannelsDb): Seq[HasCommitments] =
    channelsDb.listLocalChannels().filterNot(c => Closing.isClosed(c, None).isDefined)

  /**
   * We store [[CMD_FULFILL_HTLC]]/[[CMD_FAIL_HTLC]]/[[CMD_FAIL_MALFORMED_HTLC]] in a database
   * (see [[fr.acinq.eclair.payment.relay.CommandBuffer]]) because we don't want to lose preimages, or to forget to fail
   * incoming htlcs, which would lead to unwanted channel closings.
   *
   * Because of the way our watcher works, in a scenario where a downstream channel has gone to the blockchain, it may
   * send several times the same command, and the upstream channel may have disappeared in the meantime.
   *
   * That's why we need to periodically clean up the pending relay db.
   */
  private def cleanupRelayDb(channels: Seq[HasCommitments], relayDb: PendingRelayDb)(implicit log: LoggingAdapter): Unit = {
    // We are interested in incoming HTLCs, that have been *cross-signed* (otherwise they wouldn't have been relayed).
    // If the HTLC is not in their commitment, it means that we have already fulfilled/failed it and that we can remove
    // the command from the pending relay db.
    val channel2Htlc: Set[(ByteVector32, Long)] =
    channels
      .flatMap(_.commitments.remoteCommit.spec.htlcs)
      .collect { case OutgoingHtlc(add) => (add.channelId, add.id) }
      .toSet

    val pendingRelay: Set[(ByteVector32, Long)] = relayDb.listPendingRelay()
    val toClean = pendingRelay -- channel2Htlc
    toClean.foreach {
      case (channelId, htlcId) =>
        log.info(s"cleaning up channelId=$channelId htlcId=$htlcId from relay db")
        relayDb.removePendingRelay(channelId, htlcId)
    }
  }

}