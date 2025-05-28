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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 *
 * @param weight           How much fees we would have collected in the past if all payments had succeeded (exponential moving average).
 * @param score            How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt Timestamp of the last recorded payment settlement.
 */
case class PastScore(weight: Double, score: Double, lastSettlementAt: TimestampMilli)

/** We're relaying that payment and are waiting for it to settle. */
case class PendingPayment(fee: MilliSatoshi, endorsement: Int, startedAt: TimestampMilli) {
  def weight(now: TimestampMilli, minDuration: FiniteDuration, multiplier: Double): Double = {
    val duration = now - startedAt
    fee.toLong.toDouble * (duration / minDuration).max(multiplier)
  }
}

case class HtlcId(channelId: ByteVector32, id: Long)

/**
 * Local reputation for a given node.
 *
 * @param pending           Set of pending payments (payments may contain multiple HTLCs when using trampoline).
 * @param halfLife          Half life for the exponential moving average.
 * @param maxRelayDuration  Duration after which payments are penalized for staying pending too long.
 * @param pendingMultiplier How much to penalize pending payments.
 */
case class Reputation(pastScores: Array[PastScore], pending: mutable.Map[HtlcId, PendingPayment], halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double) {
  private def decay(now: TimestampMilli, lastSettlementAt: TimestampMilli): Double = scala.math.pow(0.5, (now - lastSettlementAt) / halfLife)

  /**
   * Estimate the confidence that a payment will succeed.
   */
  def getConfidence(fee: MilliSatoshi, endorsement: Int, now: TimestampMilli = TimestampMilli.now()): Double = {
    val weights = Array.fill(Reputation.endorsementLevels)(0.0)
    val scores = Array.fill(Reputation.endorsementLevels)(0.0)
    for (e <- 0 until Reputation.endorsementLevels) {
      val d = decay(now, pastScores(e).lastSettlementAt)
      weights(e) += d * pastScores(e).weight
      scores(e) += d * pastScores(e).score
    }
    for (p <- pending.values) {
      weights(p.endorsement) += p.weight(now, maxRelayDuration, pendingMultiplier)
    }
    weights(endorsement) += fee.toLong.toDouble * pendingMultiplier
    /*
     Higher endorsement buckets may have fewer payments which makes the weight of pending payments disproportionately
     important. To counter this effect, we try adding payments from the lower buckets to see if it gives us a higher
     confidence score.
     It is acceptable to use payments with lower endorsements to increase the confidence score but not payments with
     higher endorsements.
    */
    var score = scores(endorsement)
    var weight = weights(endorsement)
    var confidence = score / weight
    for (e <- Range.inclusive(endorsement - 1, 0, step = -1)) {
      score += scores(e)
      weight += weights(e)
      confidence = confidence.max(score / weight)
    }
    confidence
  }

  /**
   * Register a pending relay.
   */
  def attempt(htlcId: HtlcId, fee: MilliSatoshi, endorsement: Int, now: TimestampMilli = TimestampMilli.now()): Unit =
    pending(htlcId) = PendingPayment(fee, endorsement, now)

  /**
   * When a payment is settled, we record whether it succeeded and how long it took.
   */
  def record(htlcId: HtlcId, isSuccess: Boolean, now: TimestampMilli = TimestampMilli.now()): Unit =
    pending.remove(htlcId).foreach(p => {
      val d = decay(now, pastScores(p.endorsement).lastSettlementAt)
      val newWeight = d * pastScores(p.endorsement).weight + p.weight(now, maxRelayDuration, if (isSuccess) 1.0 else 0.0)
      val newScore = d * pastScores(p.endorsement).score + (if (isSuccess) p.fee.toLong.toDouble else 0)
      pastScores(p.endorsement) = PastScore(newWeight, newScore, now)
    })
}

object Reputation {
  val endorsementLevels = 8

  case class Config(enabled: Boolean, halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double)

  def init(config: Config): Reputation = Reputation(Array.fill(endorsementLevels)(PastScore(0.0, 0.0, TimestampMilli.min)), mutable.HashMap.empty, config.halfLife, config.maxRelayDuration, config.pendingMultiplier)
}
