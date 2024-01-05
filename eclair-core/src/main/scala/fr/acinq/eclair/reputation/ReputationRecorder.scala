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
import fr.acinq.eclair.reputation.Reputation.ReputationConfig
import fr.acinq.eclair.MilliSatoshi

import java.util.UUID

object ReputationRecorder {
  sealed trait Command

  sealed trait StandardCommand extends Command
  case class GetConfidence(replyTo: ActorRef[Confidence], originNode: PublicKey, isEndorsed: Boolean, relayId: UUID, fee: MilliSatoshi) extends StandardCommand
  case class CancelRelay(originNode: PublicKey, isEndorsed: Boolean, relayId: UUID) extends StandardCommand
  case class RecordResult(originNode: PublicKey, isEndorsed: Boolean, relayId: UUID, isSuccess: Boolean) extends StandardCommand

  sealed trait TrampolineCommand extends Command
  case class GetTrampolineConfidence(replyTo: ActorRef[Confidence], fees: Map[(PublicKey, Boolean), MilliSatoshi], relayId: UUID) extends TrampolineCommand
  case class RecordTrampolineFailure(keys: Set[(PublicKey, Boolean)], relayId: UUID) extends TrampolineCommand
  case class RecordTrampolineSuccess(fees: Map[(PublicKey, Boolean), MilliSatoshi], relayId: UUID) extends TrampolineCommand

  case class Confidence(value: Double)

  def apply(reputationConfig: ReputationConfig, reputations: Map[(PublicKey, Boolean), Reputation]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case GetConfidence(replyTo, originNode, isEndorsed, relayId, fee) =>
        val updatedReputation = reputations.getOrElse((originNode, isEndorsed), Reputation.init(reputationConfig)).attempt(relayId, fee)
        replyTo ! Confidence(updatedReputation.confidence())
        ReputationRecorder(reputationConfig, reputations.updated((originNode, isEndorsed), updatedReputation))
      case CancelRelay(originNode, isEndorsed, relayId) =>
        val updatedReputation = reputations.getOrElse((originNode, isEndorsed), Reputation.init(reputationConfig)).cancel(relayId)
        ReputationRecorder(reputationConfig, reputations.updated((originNode, isEndorsed), updatedReputation))
      case RecordResult(originNode, isEndorsed, relayId, isSuccess) =>
        val updatedReputation = reputations.getOrElse((originNode, isEndorsed), Reputation.init(reputationConfig)).record(relayId, isSuccess)
        ReputationRecorder(reputationConfig, reputations.updated((originNode, isEndorsed), updatedReputation))
      case GetTrampolineConfidence(replyTo, fees, relayId) =>
        val (confidence, updatedReputations) = fees.foldLeft((1.0, reputations)){case ((c, r), ((originNode, isEndorsed), fee)) =>
          val updatedReputation = reputations.getOrElse((originNode, isEndorsed), Reputation.init(reputationConfig)).attempt(relayId, fee)
          val updatedConfidence = c.min(updatedReputation.confidence())
          (updatedConfidence, r.updated((originNode, isEndorsed), updatedReputation))
        }
        replyTo ! Confidence(confidence)
        ReputationRecorder(reputationConfig, updatedReputations)
      case RecordTrampolineFailure(keys, relayId) =>
        val updatedReputations = keys.foldLeft(reputations) { case (r, (originNode, isEndorsed)) =>
          val updatedReputation = reputations.getOrElse((originNode, isEndorsed), Reputation.init(reputationConfig)).record(relayId, isSuccess = false)
          r.updated((originNode, isEndorsed), updatedReputation)
        }
        ReputationRecorder(reputationConfig, updatedReputations)
      case RecordTrampolineSuccess(fees, relayId) =>
        val updatedReputations = fees.foldLeft(reputations) { case (r, ((originNode, isEndorsed), fee)) =>
          val updatedReputation = reputations.getOrElse((originNode, isEndorsed), Reputation.init(reputationConfig)).record(relayId, isSuccess = true, Some(fee))
          r.updated((originNode, isEndorsed), updatedReputation)
        }
        ReputationRecorder(reputationConfig, updatedReputations)
    }
  }
}
