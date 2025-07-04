/*
 * Copyright 2025 ACINQ SAS
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

package fr.acinq.eclair.reputation

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.Upstream.Hot
import fr.acinq.eclair.channel.{OutgoingHtlcAdded, OutgoingHtlcFailed, OutgoingHtlcFulfilled, OutgoingHtlcSettled, Upstream}
import fr.acinq.eclair.reputation.ReputationRecorder._
import fr.acinq.eclair.wire.protocol.{UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc}

import scala.collection.mutable

object ReputationRecorder {
  // @formatter:off
  sealed trait Command
  case class GetConfidence(replyTo: ActorRef[Reputation.Score], upstream: Upstream.Hot, downstream: PublicKey, fee: MilliSatoshi) extends Command
  private case class WrappedOutgoingHtlcAdded(added: OutgoingHtlcAdded) extends Command
  private case class WrappedOutgoingHtlcSettled(settled: OutgoingHtlcSettled) extends Command
  // @formatter:on

  def apply(config: Reputation.Config): Behavior[Command] =
    Behaviors.setup(context => {
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcAdded))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcSettled))
      new ReputationRecorder(config).run()
    })

  /**
   * A pending outgoing HTLC.
   *
   * @param add        UpdateAddHtlc that contains an id for the HTLC and an endorsement value.
   * @param upstream   The incoming node or nodes.
   * @param downstream The outgoing node.
   */
  case class PendingHtlc(add: UpdateAddHtlc, upstream: Upstream.Hot, downstream: PublicKey)
}

class ReputationRecorder(config: Reputation.Config) {
  private val incomingReputations: mutable.Map[PublicKey, Reputation] = mutable.HashMap.empty.withDefaultValue(Reputation.init(config))
  private val outgoingReputations: mutable.Map[PublicKey, Reputation] = mutable.HashMap.empty.withDefaultValue(Reputation.init(config))
  private val pending: mutable.Map[HtlcId, PendingHtlc] = mutable.HashMap.empty

  def run(): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetConfidence(replyTo, _: Upstream.Local, _, _) =>
        replyTo ! Reputation.Score(1.0, Reputation.maxEndorsement)
        Behaviors.same

      case GetConfidence(replyTo, upstream: Upstream.Hot.Channel, downstream, fee) =>
        val incomingConfidence = incomingReputations.get(upstream.receivedFrom).map(_.getConfidence(fee, upstream.add.endorsement)).getOrElse(0.0)
        val endorsement = Reputation.toEndorsement(incomingConfidence)
        val outgoingConfidence = outgoingReputations.get(downstream).map(_.getConfidence(fee, endorsement)).getOrElse(0.0)
        replyTo ! Reputation.Score(incomingConfidence min outgoingConfidence, endorsement)
        Behaviors.same

      case GetConfidence(replyTo, upstream: Upstream.Hot.Trampoline, downstream, totalFee) =>
        val incomingConfidence =
          upstream.received
            .groupMapReduce(_.receivedFrom)(r => (r.add.amountMsat, r.add.endorsement)) {
              case ((amount1, endorsement1), (amount2, endorsement2)) => (amount1 + amount2, endorsement1 min endorsement2)
            }
            .map {
              case (nodeId, (amount, endorsement)) =>
                val fee = amount * totalFee.toLong / upstream.amountIn.toLong
                incomingReputations.get(nodeId).map(_.getConfidence(fee, endorsement)).getOrElse(0.0)
            }
            .min
        val endorsement = Reputation.toEndorsement(incomingConfidence)
        val outgoingConfidence = outgoingReputations.get(downstream).map(_.getConfidence(totalFee, endorsement)).getOrElse(0.0)
        replyTo ! Reputation.Score(incomingConfidence min outgoingConfidence, endorsement)
        Behaviors.same

      case WrappedOutgoingHtlcAdded(OutgoingHtlcAdded(add, remoteNodeId, upstream, fee)) =>
        val htlcId = HtlcId(add.channelId, add.id)
        upstream match {
          case channel: Hot.Channel =>
            incomingReputations(channel.receivedFrom) = incomingReputations(channel.receivedFrom).addPendingHtlc(htlcId, fee, channel.add.endorsement)
          case trampoline: Hot.Trampoline =>
            trampoline.received
              .groupMapReduce(_.receivedFrom)(r => (r.add.amountMsat, r.add.endorsement)) {
                case ((amount1, endorsement1), (amount2, endorsement2)) => (amount1 + amount2, endorsement1 min endorsement2)
              }
              .foreach { case (nodeId, (amount, endorsement)) =>
                incomingReputations(nodeId) = incomingReputations(nodeId).addPendingHtlc(htlcId, fee * amount.toLong / trampoline.amountIn.toLong, endorsement)
              }
          case _: Upstream.Local => ()
        }
        outgoingReputations(remoteNodeId) = outgoingReputations(remoteNodeId).addPendingHtlc(htlcId, fee, add.endorsement)
        pending(htlcId) = PendingHtlc(add, upstream, remoteNodeId)
        Behaviors.same

      case WrappedOutgoingHtlcSettled(settled) =>
        val htlcId = settled match {
          case OutgoingHtlcFailed(UpdateFailHtlc(channelId, id, _, _)) => HtlcId(channelId, id)
          case OutgoingHtlcFailed(UpdateFailMalformedHtlc(channelId, id, _, _, _)) => HtlcId(channelId, id)
          case OutgoingHtlcFulfilled(fulfill) => HtlcId(fulfill.channelId, fulfill.id)
        }
        val isSuccess = settled match {
          case _: OutgoingHtlcFailed => false
          case _: OutgoingHtlcFulfilled => true
        }
        pending.remove(htlcId).foreach(p => {
          p.upstream match {
            case Hot.Channel(_, _, receivedFrom) =>
              incomingReputations(receivedFrom) = incomingReputations(receivedFrom).settlePendingHtlc(htlcId, isSuccess)
            case Hot.Trampoline(received) =>
              received.foreach(channel =>
                incomingReputations(channel.receivedFrom) = incomingReputations(channel.receivedFrom).settlePendingHtlc(htlcId, isSuccess)
              )
            case _: Upstream.Local => ()
          }
          outgoingReputations(p.downstream) = outgoingReputations(p.downstream).settlePendingHtlc(htlcId, isSuccess)
        })
        Behaviors.same
    }
}
