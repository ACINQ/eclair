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
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.Upstream.Hot
import fr.acinq.eclair.channel.{OutgoingHtlcAdded, OutgoingHtlcFailed, OutgoingHtlcFulfilled, Upstream}
import fr.acinq.eclair.wire.protocol.{UpdateFailHtlc, UpdateFailMalformedHtlc}
import ReputationRecorder._

import scala.collection.mutable

object ReputationRecorder {
  // @formatter:off
  sealed trait Command
  case class GetConfidence(replyTo: ActorRef[Confidence], upstream: Upstream.Hot.Channel, fee: MilliSatoshi) extends Command
  case class GetTrampolineConfidence(replyTo: ActorRef[Confidence], upstream: Upstream.Hot.Trampoline, fee: MilliSatoshi) extends Command
  private case class WrappedOutgoingHtlcAdded(added: OutgoingHtlcAdded) extends Command
  private case class WrappedOutgoingHtlcFailed(failed: OutgoingHtlcFailed) extends Command
  private case class WrappedOutgoingHtlcFulfilled(fulfilled: OutgoingHtlcFulfilled) extends Command
  // @formatter:on

  /** Confidence that the outgoing HTLC will succeed. */
  case class Confidence(value: Double)

  def apply(config: Reputation.Config): Behavior[Command] =
    Behaviors.setup(context => {
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcAdded))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcFailed))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcFulfilled))
      new ReputationRecorder(config, context).run()
    })
}

class ReputationRecorder(config: Reputation.Config, context: ActorContext[ReputationRecorder.Command]) {
  private val reputations: mutable.Map[PublicKey, Reputation] = mutable.HashMap.empty.withDefault(_ => Reputation.init(config))
  private val pending: mutable.Map[HtlcId, Upstream.Hot] = mutable.HashMap.empty

  private def getReputation(nodeId: PublicKey): Reputation = {
    reputations.get(nodeId) match {
      case Some(reputation) => reputation
      case None => {
        val reputation = Reputation.init(config)
        reputations(nodeId) = reputation
        reputation
      }
    }
  }

  def run(): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetConfidence(replyTo, upstream, fee) =>
        val confidence = reputations(upstream.receivedFrom).getConfidence(fee, upstream.add.endorsement)
        replyTo ! Confidence(confidence)
        Behaviors.same

      case GetTrampolineConfidence(replyTo, upstream, totalFee) =>
        val confidence =
          upstream.received
            .groupMapReduce(_.receivedFrom)(r => (r.add.amountMsat, r.add.endorsement)){
              case ((amount1, endorsement1), (amount2, endorsement2)) => (amount1 + amount2, endorsement1 min endorsement2)
            }
            .map {
              case (nodeId, (amount, endorsement)) =>
                val fee = amount * totalFee.toLong / upstream.amountIn.toLong
                reputations(nodeId).getConfidence(fee, endorsement)
            }
            .min
        replyTo ! Confidence(confidence)
        Behaviors.same

      case WrappedOutgoingHtlcAdded(OutgoingHtlcAdded(add, upstream, fee)) =>
        val htlcId = HtlcId(add.channelId, add.id)
        upstream match {
          case channel: Hot.Channel =>
            getReputation(channel.receivedFrom).attempt(htlcId, fee, channel.add.endorsement)
            pending += (htlcId -> upstream)
          case trampoline: Hot.Trampoline =>
            trampoline.received.foreach(channel =>
              getReputation(channel.receivedFrom).attempt(htlcId, fee * channel.amountIn.toLong / trampoline.amountIn.toLong, channel.add.endorsement)
            )
            pending += (htlcId -> upstream)
          case _: Upstream.Local => ()
        }
        Behaviors.same

      case WrappedOutgoingHtlcFailed(OutgoingHtlcFailed(fail)) =>
        val htlcId = fail match {
          case UpdateFailHtlc(channelId, id, _, _) => HtlcId(channelId, id)
          case UpdateFailMalformedHtlc(channelId, id, _, _, _) => HtlcId(channelId, id)
        }
        pending.get(htlcId) match {
          case Some(channel: Hot.Channel) =>
            getReputation(channel.receivedFrom).record(htlcId, isSuccess = false)
          case Some(trampoline: Hot.Trampoline) =>
            trampoline.received.foreach(channel =>
              getReputation(channel.receivedFrom).record(htlcId, isSuccess = false)
            )
          case _ => ()
        }
        pending -= htlcId
        Behaviors.same

      case WrappedOutgoingHtlcFulfilled(OutgoingHtlcFulfilled(fulfill)) =>
        val htlcId = HtlcId(fulfill.channelId, fulfill.id)
        pending.get(htlcId) match {
          case Some(channel: Hot.Channel) =>
            getReputation(channel.receivedFrom).record(htlcId, isSuccess = true)
          case Some(trampoline: Hot.Trampoline) =>
            trampoline.received.foreach(channel =>
              getReputation(channel.receivedFrom).record(htlcId, isSuccess = true)
            )
          case _ => ()
        }
        pending -= htlcId
        Behaviors.same
    }
}
