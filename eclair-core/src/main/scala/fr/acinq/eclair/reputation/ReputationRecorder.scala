/*
 * Copyright 2023 ACINQ SAS
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
import fr.acinq.eclair.reputation.Reputation.HtlcId
import fr.acinq.eclair.wire.protocol.{UpdateFailHtlc, UpdateFailMalformedHtlc}
import ReputationRecorder._

import scala.collection.mutable


/**
 * Created by thomash on 21/07/2023.
 */

object ReputationRecorder {
  // @formatter:off
  sealed trait Command
  case class GetConfidence(replyTo: ActorRef[Confidence], upstream: Upstream.Hot.Channel, fee: MilliSatoshi) extends Command
  case class GetTrampolineConfidence(replyTo: ActorRef[Confidence], upstream: Upstream.Hot.Trampoline, fee: MilliSatoshi) extends Command
  private case class WrappedOutgoingHtlcAdded(added: OutgoingHtlcAdded) extends Command
  private case class WrappedOutgoingHtlcFailed(failed: OutgoingHtlcFailed) extends Command
  private case class WrappedOutgoingHtlcFulfilled(fulfilled: OutgoingHtlcFulfilled) extends Command
  // @formatter:on

  /**
   * @param nodeId      nodeId of the upstream peer.
   * @param endorsement endorsement value set by the upstream peer in the HTLC we received.
   */
  case class PeerEndorsement(nodeId: PublicKey, endorsement: Int)

  object PeerEndorsement {
    def apply(channel: Upstream.Hot.Channel): PeerEndorsement = PeerEndorsement(channel.receivedFrom, channel.add.endorsement)
  }

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
  private val reputations: mutable.Map[PeerEndorsement, Reputation] = mutable.HashMap.empty.withDefaultValue(Reputation.init(config))
  private val pending: mutable.Map[HtlcId, Upstream.Hot] = mutable.HashMap.empty

  def run(): Behavior[Command] =
    Behaviors.receiveMessage {
      case GetConfidence(replyTo, upstream, fee) =>
        val confidence = reputations(PeerEndorsement(upstream)).getConfidence(fee)
        replyTo ! Confidence(confidence)
        Behaviors.same

      case GetTrampolineConfidence(replyTo, upstream, totalFee) =>
        val confidence =
          upstream.received
            .groupMapReduce(r => PeerEndorsement(r.receivedFrom, r.add.endorsement))(_.add.amountMsat)(_ + _)
            .map {
              case (peerEndorsement, amount) =>
                val fee = amount * totalFee.toLong / upstream.amountIn.toLong
                reputations(peerEndorsement).getConfidence(fee)
            }
            .min
        replyTo ! Confidence(confidence)
        Behaviors.same

      case WrappedOutgoingHtlcAdded(OutgoingHtlcAdded(add, upstream, fee)) =>
        val htlcId = HtlcId(add.channelId, add.id)
        upstream match {
          case channel: Hot.Channel =>
            reputations(PeerEndorsement(channel)) = reputations(PeerEndorsement(channel)).attempt(htlcId, fee)
            pending += (htlcId -> upstream)
          case trampoline: Hot.Trampoline =>
            trampoline.received.foreach(channel =>
              reputations(PeerEndorsement(channel)) = reputations(PeerEndorsement(channel)).attempt(htlcId, fee * channel.amountIn.toLong / trampoline.amountIn.toLong)
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
            reputations(PeerEndorsement(channel)) = reputations(PeerEndorsement(channel)).record(htlcId, isSuccess = false)
          case Some(trampoline: Hot.Trampoline) =>
            trampoline.received.foreach(channel =>
              reputations(PeerEndorsement(channel)) = reputations(PeerEndorsement(channel)).record(htlcId, isSuccess = false)
            )
          case _ => ()
        }
        pending -= htlcId
        Behaviors.same

      case WrappedOutgoingHtlcFulfilled(OutgoingHtlcFulfilled(fulfill)) =>
        val htlcId = HtlcId(fulfill.channelId, fulfill.id)
        pending.get(htlcId) match {
          case Some(channel: Hot.Channel) =>
            reputations(PeerEndorsement(channel)) = reputations(PeerEndorsement(channel)).record(htlcId, isSuccess = true)
          case Some(trampoline: Hot.Trampoline) =>
            trampoline.received.foreach(channel =>
              reputations(PeerEndorsement(channel)) = reputations(PeerEndorsement(channel)).record(htlcId, isSuccess = true)
            )
          case _ => ()
        }
        pending -= htlcId
        Behaviors.same
    }
}
