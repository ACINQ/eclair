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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.reputation.Reputation.HtlcId
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}

import scala.concurrent.duration.FiniteDuration

/**
 * Created by thomash on 21/07/2023.
 */

/**
 * Local reputation for a given incoming node, that should be tracked for each incoming endorsement level.
 *
 * @param pastWeight        How much fees we would have collected in the past if all payments had succeeded (exponential moving average).
 * @param pastScore         How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt  Timestamp of the last recorded payment settlement.
 * @param pending           Set of pending payments (payments may contain multiple HTLCs when using trampoline).
 * @param halfLife          Half life for the exponential moving average.
 * @param maxRelayDuration  Duration after which payments are penalized for staying pending too long.
 * @param pendingMultiplier How much to penalize pending payments.
 */
case class Reputation(pastWeight: Double, pastScore: Double, lastSettlementAt: TimestampMilli, pending: Map[HtlcId, Reputation.PendingPayment], halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double) {
  private def decay(now: TimestampMilli): Double = scala.math.pow(0.5, (now - lastSettlementAt) / halfLife)

  private def pendingWeight(now: TimestampMilli): Double = pending.values.map(_.weight(now, maxRelayDuration, pendingMultiplier)).sum

  /**
   * Estimate the confidence that a payment will succeed.
   */
  def getConfidence(fee: MilliSatoshi, now: TimestampMilli = TimestampMilli.now()): Double = {
    val d = decay(now)
    d * pastScore / (d * pastWeight + pendingWeight(now) + fee.toLong.toDouble * pendingMultiplier)
  }

  /**
   * Register a pending relay.
   *
   * @return updated reputation
   */
  def attempt(htlcId: HtlcId, fee: MilliSatoshi, now: TimestampMilli = TimestampMilli.now()): Reputation =
    copy(pending = pending + (htlcId -> Reputation.PendingPayment(fee, now)))

  /**
   * When a payment is settled, we record whether it succeeded and how long it took.
   *
   * @return updated reputation
   */
  def record(htlcId: HtlcId, isSuccess: Boolean, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    pending.get(htlcId) match {
      case Some(p) =>
        val d = decay(now)
        val newWeight = d * pastWeight + p.weight(now, maxRelayDuration, if (isSuccess) 1.0 else 0.0)
        val newScore = d * pastScore + (if (isSuccess) p.fee.toLong.toDouble else 0)
        Reputation(newWeight, newScore, now, pending - htlcId, halfLife, maxRelayDuration, pendingMultiplier)
      case None => this
    }
  }
}

object Reputation {
  case class HtlcId(channelId: ByteVector32, id: Long)

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
