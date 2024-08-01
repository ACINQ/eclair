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

/** Local reputation per incoming node and endorsement level
 *
 * @param pastWeight        How much fees we would have collected in the past if all HTLCs had succeeded (exponential moving average).
 * @param pastScore         How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt  Timestamp of the last recorded HTLC settlement.
 * @param pending           Set of pending HTLCs.
 * @param halfLife          Half life for the exponential moving average.
 * @param goodDuration      Duration after which HTLCs are penalized for staying pending too long.
 * @param pendingMultiplier How much to penalize pending HTLCs.
 */
case class Reputation(pastWeight: Double, pastScore: Double, lastSettlementAt: TimestampMilli, pending: Map[UUID, Pending], halfLife: FiniteDuration, goodDuration: FiniteDuration, pendingMultiplier: Double) {
  private def decay(now: TimestampMilli): Double = scala.math.pow(0.5, (now - lastSettlementAt) / halfLife)

  private def pendingWeight(now: TimestampMilli): Double = pending.values.map(_.weight(now, goodDuration, pendingMultiplier)).sum

  /** Register a HTLC to relay and estimate the confidence that it will succeed.
   * @return (updated reputation, confidence)
   */
  def attempt(relayId: UUID, fee: MilliSatoshi, now: TimestampMilli = TimestampMilli.now()): (Reputation, Double) = {
    val d = decay(now)
    val newReputation = copy(pending = pending + (relayId -> Pending(fee, now)))
    val confidence = d * pastScore / (d * pastWeight + newReputation.pendingWeight(now))
    (newReputation, confidence)
  }

  /** Mark a previously registered HTLC as failed without trying to relay it (usually because its confidence was too low).
   * @return updated reputation
   */
  def cancel(relayId: UUID): Reputation = copy(pending = pending - relayId)

  /** When a HTLC is settled, we record whether it succeeded and how long it took.
   *
   * @param feeOverride When relaying trampoline payments, the actual fee is only known when the payment succeeds. This
   *                    is used instead of the fee upper bound that was known when first attempting the relay.
   * @return updated reputation
   */
  def record(relayId: UUID, isSuccess: Boolean, feeOverride: Option[MilliSatoshi] = None, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    val d = decay(now)
    var p = pending.getOrElse(relayId, Pending(MilliSatoshi(0), now))
    feeOverride.foreach(fee => p = p.copy(fee = fee))
    val newWeight = d * pastWeight + p.weight(now, goodDuration, 1.0)
    val newScore = d * pastScore + (if (isSuccess) p.fee.toLong.toDouble else 0)
    Reputation(newWeight, newScore, now, pending - relayId, halfLife, goodDuration, pendingMultiplier)
  }
}

object Reputation {
  case class Pending(fee: MilliSatoshi, startedAt: TimestampMilli) {
    def weight(now: TimestampMilli, minDuration: FiniteDuration, multiplier: Double): Double = {
      val duration = now - startedAt
      fee.toLong.toDouble * (duration / minDuration).max(multiplier)
    }
  }

  case class ReputationConfig(halfLife: FiniteDuration, maxHtlcRelayDuration: FiniteDuration, pendingMultiplier: Double)

  def init(config: ReputationConfig): Reputation = Reputation(0.0, 0.0, TimestampMilli.min, Map.empty, config.halfLife, config.maxHtlcRelayDuration, config.pendingMultiplier)
}
