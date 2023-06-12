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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi

import java.util.UUID

/**
 * Created by thomash on 21/07/2023.
 */

object ReputationRecorder {
  // @formatter:off
  sealed trait Command

  sealed trait ChannelRelayCommand extends Command
  case class GetConfidence(replyTo: ActorRef[Confidence], originNode: PublicKey, endorsement: Int, relayId: UUID, fee: MilliSatoshi) extends ChannelRelayCommand
  case class CancelRelay(originNode: PublicKey, endorsement: Int, relayId: UUID) extends ChannelRelayCommand
  case class RecordResult(originNode: PublicKey, endorsement: Int, relayId: UUID, isSuccess: Boolean) extends ChannelRelayCommand

  sealed trait TrampolineRelayCommand extends Command
  case class GetTrampolineConfidence(replyTo: ActorRef[Confidence], fees: Map[PeerEndorsement, MilliSatoshi], relayId: UUID) extends TrampolineRelayCommand
  case class RecordTrampolineFailure(upstream: Set[PeerEndorsement], relayId: UUID) extends TrampolineRelayCommand
  case class RecordTrampolineSuccess(fees: Map[PeerEndorsement, MilliSatoshi], relayId: UUID) extends TrampolineRelayCommand
  // @formatter:on

  /**
   * @param nodeId      nodeId of the upstream peer.
   * @param endorsement endorsement value set by the upstream peer in the HTLC we received.
   */
  case class PeerEndorsement(nodeId: PublicKey, endorsement: Int)

  /** Confidence that the outgoing HTLC will succeed. */
  case class Confidence(value: Double)

  def apply(reputationConfig: Reputation.Config, reputations: Map[PeerEndorsement, Reputation]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case GetConfidence(replyTo, originNode, endorsement, relayId, fee) =>
        val (updatedReputation, confidence) = reputations.getOrElse(PeerEndorsement(originNode, endorsement), Reputation.init(reputationConfig)).attempt(relayId, fee)
        replyTo ! Confidence(confidence)
        ReputationRecorder(reputationConfig, reputations.updated(PeerEndorsement(originNode, endorsement), updatedReputation))
      case CancelRelay(originNode, endorsement, relayId) =>
        val updatedReputation = reputations.getOrElse(PeerEndorsement(originNode, endorsement), Reputation.init(reputationConfig)).cancel(relayId)
        ReputationRecorder(reputationConfig, reputations.updated(PeerEndorsement(originNode, endorsement), updatedReputation))
      case RecordResult(originNode, endorsement, relayId, isSuccess) =>
        val updatedReputation = reputations.getOrElse(PeerEndorsement(originNode, endorsement), Reputation.init(reputationConfig)).record(relayId, isSuccess)
        ReputationRecorder(reputationConfig, reputations.updated(PeerEndorsement(originNode, endorsement), updatedReputation))
      case GetTrampolineConfidence(replyTo, fees, relayId) =>
        val (confidence, updatedReputations) = fees.foldLeft((1.0, reputations)) {
          case ((c, r), (peerEndorsement, fee)) =>
            val (updatedReputation, confidence) = reputations.getOrElse(peerEndorsement, Reputation.init(reputationConfig)).attempt(relayId, fee)
            (c.min(confidence), r.updated(peerEndorsement, updatedReputation))
        }
        replyTo ! Confidence(confidence)
        ReputationRecorder(reputationConfig, updatedReputations)
      case RecordTrampolineFailure(keys, relayId) =>
        val updatedReputations = keys.foldLeft(reputations) {
          case (r, peerEndorsement) =>
            val updatedReputation = reputations.getOrElse(peerEndorsement, Reputation.init(reputationConfig)).record(relayId, isSuccess = false)
            r.updated(peerEndorsement, updatedReputation)
        }
        ReputationRecorder(reputationConfig, updatedReputations)
      case RecordTrampolineSuccess(fees, relayId) =>
        val updatedReputations = fees.foldLeft(reputations) {
          case (r, (peerEndorsement, fee)) =>
            val updatedReputation = reputations.getOrElse(peerEndorsement, Reputation.init(reputationConfig)).record(relayId, isSuccess = true, Some(fee))
            r.updated(peerEndorsement, updatedReputation)
        }
        ReputationRecorder(reputationConfig, updatedReputations)
    }
  }
}
