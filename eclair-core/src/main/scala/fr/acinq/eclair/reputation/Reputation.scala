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

import fr.acinq.eclair.reputation.Reputation.Pending
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

case class Reputation(pastWeight: Double, pending: Map[UUID, Pending], pastScore: Double, maxWeight: Double, minDuration: FiniteDuration, pendingMultiplier: Double) {
  private def pendingWeight(now: TimestampMilli): Double = pending.values.map(_.weight(now, minDuration, pendingMultiplier)).sum

  def confidence(now: TimestampMilli = TimestampMilli.now()): Double = pastScore / (pastWeight + pendingWeight(now))

  def attempt(relayId: UUID, fee: MilliSatoshi, startedAt: TimestampMilli = TimestampMilli.now()): Reputation =
    copy(pending = pending + (relayId -> Pending(fee, startedAt)))

  def cancel(relayId: UUID): Reputation = copy(pending = pending - relayId)

  def record(relayId: UUID, isSuccess: Boolean, feeOverride: Option[MilliSatoshi] = None, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    var p = pending.getOrElse(relayId, Pending(MilliSatoshi(0), now))
    feeOverride.foreach(fee => p = p.copy(fee = fee))
    val newWeight = pastWeight + p.weight(now, minDuration, 1.0)
    val newScore = if (isSuccess) pastScore + p.fee.toLong.toDouble else pastScore
    if (newWeight > maxWeight) {
      Reputation(maxWeight, pending - relayId, newScore * maxWeight / newWeight, maxWeight, minDuration, pendingMultiplier)
    } else {
      Reputation(newWeight, pending - relayId, newScore, maxWeight, minDuration, pendingMultiplier)
    }
  }
}

object Reputation {
  case class Pending(fee: MilliSatoshi, startedAt: TimestampMilli) {
    def weight(now: TimestampMilli, minDuration: FiniteDuration, pendingMultiplier: Double): Double = {
      val duration = now - startedAt
      fee.toLong.toDouble * (duration / minDuration).max(pendingMultiplier)
    }
  }

  case class ReputationConfig(maxWeight: MilliSatoshi, minDuration: FiniteDuration, pendingMultiplier: Double)

  def init(config: ReputationConfig): Reputation = Reputation(0.0, Map.empty, 0.0, config.maxWeight.toLong.toDouble, config.minDuration, config.pendingMultiplier)
}
