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

import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
 * Created by thomash on 21/07/2023.
 */

/**
 * Local reputation for a given incoming node, that should be track for each incoming endorsement level.
 *
 * @param pastWeight        How much fees we would have collected in the past if all payments had succeeded (exponential moving average).
 * @param pastScore         How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt  Timestamp of the last recorded payment settlement.
 * @param pending           Set of pending payments (payments may contain multiple HTLCs when using trampoline).
 * @param halfLife          Half life for the exponential moving average.
 * @param maxRelayDuration  Duration after which payments are penalized for staying pending too long.
 * @param pendingMultiplier How much to penalize pending payments.
 */
case class Reputation(pastWeight: Double, pastScore: Double, lastSettlementAt: TimestampMilli, pending: Map[UUID, Reputation.PendingPayment], halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double) {
  private def decay(now: TimestampMilli): Double = scala.math.pow(0.5, (now - lastSettlementAt) / halfLife)

  private def pendingWeight(now: TimestampMilli): Double = pending.values.map(_.weight(now, maxRelayDuration, pendingMultiplier)).sum

  /**
   * Register a payment to relay and estimate the confidence that it will succeed.
   *
   * @return (updated reputation, confidence)
   */
  def attempt(relayId: UUID, fee: MilliSatoshi, now: TimestampMilli = TimestampMilli.now()): (Reputation, Double) = {
    val d = decay(now)
    val newReputation = copy(pending = pending + (relayId -> Reputation.PendingPayment(fee, now)))
    val confidence = d * pastScore / (d * pastWeight + newReputation.pendingWeight(now))
    (newReputation, confidence)
  }

  /**
   * Mark a previously registered payment as failed without trying to relay it (usually because its confidence was too low).
   *
   * @return updated reputation
   */
  def cancel(relayId: UUID): Reputation = copy(pending = pending - relayId)

  /**
   * When a payment is settled, we record whether it succeeded and how long it took.
   *
   * @param feeOverride When relaying trampoline payments, the actual fee is only known when the payment succeeds. This
   *                    is used instead of the fee upper bound that was known when first attempting the relay.
   * @return updated reputation
   */
  def record(relayId: UUID, isSuccess: Boolean, feeOverride: Option[MilliSatoshi] = None, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    pending.get(relayId) match {
      case Some(p) =>
        val d = decay(now)
        val p1 = p.copy(fee = feeOverride.getOrElse(p.fee))
        val newWeight = d * pastWeight + p1.weight(now, maxRelayDuration, 1.0)
        val newScore = d * pastScore + (if (isSuccess) p1.fee.toLong.toDouble else 0)
        Reputation(newWeight, newScore, now, pending - relayId, halfLife, maxRelayDuration, pendingMultiplier)
      case None => this
    }
  }
}

object Reputation {
  /** We're relaying that payment and are waiting for it to settle. */
  case class PendingPayment(fee: MilliSatoshi, startedAt: TimestampMilli) {
    def weight(now: TimestampMilli, minDuration: FiniteDuration, multiplier: Double): Double = {
      val duration = now - startedAt
      fee.toLong.toDouble * (duration / minDuration).max(multiplier)
    }
  }

  case class Config(enabled: Boolean, halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double)

  def init(config: Config): Reputation = Reputation(0.0, 0.0, TimestampMilli.min, Map.empty, config.halfLife, config.maxRelayDuration, config.pendingMultiplier)
}
