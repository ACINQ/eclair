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
import fr.acinq.eclair.{BlockHeight, CltvExpiry, MilliSatoshi}
import fr.acinq.eclair.channel.Upstream.Hot
import fr.acinq.eclair.channel.{OutgoingHtlcAdded, OutgoingHtlcFailed, OutgoingHtlcFulfilled, OutgoingHtlcSettled, Upstream}
import fr.acinq.eclair.reputation.ReputationRecorder._
import fr.acinq.eclair.wire.protocol.{UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

object ReputationRecorder {
  // @formatter:off
  sealed trait Command
  case class GetConfidence(replyTo: ActorRef[Reputation.Score], downstream_opt: Option[PublicKey], fee: MilliSatoshi, currentBlockHeight: BlockHeight, expiry: CltvExpiry, accountable: Boolean) extends Command
  case class WrappedOutgoingHtlcAdded(added: OutgoingHtlcAdded) extends Command
  case class WrappedOutgoingHtlcSettled(settled: OutgoingHtlcSettled) extends Command
  private case object TickAudit extends Command
  // @formatter:on

  def apply(config: Reputation.Config): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcSettled))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter(WrappedOutgoingHtlcAdded))
        timers.startTimerWithFixedDelay(TickAudit, 5 minutes)
        new ReputationRecorder(context, config).run()
      }
    }

  /**
   * A pending outgoing HTLC.
   *
   * @param add          UpdateAddHtlc that contains an id for the HTLC and an accountability value.
   * @param remoteNodeId The outgoing node.
   */
  case class PendingHtlc(add: UpdateAddHtlc, remoteNodeId: PublicKey)
}

class ReputationRecorder(context: ActorContext[ReputationRecorder.Command], config: Reputation.Config) {
  private val outgoingReputations: mutable.Map[PublicKey, Reputation] = mutable.HashMap.empty.withDefaultValue(Reputation.init(config))
  private val pending: mutable.Map[HtlcId, PendingHtlc] = mutable.HashMap.empty

  private val log = context.log

  def run(): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetConfidence(replyTo, downstream_opt, fee, currentBlockHeight, expiry, accountable) =>
        val outgoingConfidence = downstream_opt.flatMap(outgoingReputations.get).map(_.getConfidence(fee, if (accountable) 1 else 0, currentBlockHeight, expiry)).getOrElse(0.0)
        replyTo ! Reputation.Score(outgoingConfidence, accountable)
        Behaviors.same

      case WrappedOutgoingHtlcAdded(OutgoingHtlcAdded(add, remoteNodeId, fee)) =>
        val htlcId = HtlcId(add)
        outgoingReputations(remoteNodeId) = outgoingReputations(remoteNodeId).addPendingHtlc(add, fee, if (add.accountable) 1 else 0)
        pending(htlcId) = PendingHtlc(add, remoteNodeId)
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
          outgoingReputations(p.remoteNodeId) = outgoingReputations(p.remoteNodeId).settlePendingHtlc(htlcId, isSuccess)
        })
        Behaviors.same

      case TickAudit =>
        val totalOutgoingPending = outgoingReputations.values.map(_.pending.size).sum
        log.info("{} pending HTLCs: {} tracked for outgoing reputation", pending.size, totalOutgoingPending)
        Behaviors.same
    }
}
