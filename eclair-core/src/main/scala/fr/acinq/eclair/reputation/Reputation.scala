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
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{BlockHeight, CltvExpiry, MilliSatoshi, TimestampMilli}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Reputation score per accountability level.
 *
 * @param weight           How much fees we would have collected in the past if all HTLCs had succeeded (exponential moving average).
 * @param score            How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt Timestamp of the last recorded HTLC settlement.
 */
case class PastScore(weight: Double, score: Double, lastSettlementAt: TimestampMilli) {
  def decay(now: TimestampMilli, halfLife: FiniteDuration): PastScore = {
    val d = scala.math.pow(0.5, (now - lastSettlementAt) / halfLife)
    PastScore(d * weight, d * score, now)
  }
}

/** We're relaying that HTLC and are waiting for it to settle. */
case class PendingHtlc(fee: MilliSatoshi, accountability: Int, startedAt: TimestampMilli, expiry: CltvExpiry) {
  def weight(now: TimestampMilli, minDuration: FiniteDuration, currentBlockHeight: BlockHeight): Double = {
    val alreadyPending = now - startedAt
    val untilExpiry = (expiry.toLong - currentBlockHeight.toLong) * 10.minutes
    val duration = alreadyPending + untilExpiry
    fee.toLong.toDouble * (duration / minDuration)
  }
}

case class HtlcId(channelId: ByteVector32, id: Long)

case object HtlcId {
  def apply(add: UpdateAddHtlc): HtlcId = HtlcId(add.channelId, add.id)
}

/**
 * Local reputation for a given node.
 *
 * @param pastScores        Scores from past HTLCs for each accountability level.
 * @param pending           Set of pending HTLCs.
 * @param halfLife          Half life for the exponential moving average.
 * @param maxRelayDuration  Duration after which HTLCs are penalized for staying pending too long.
 */
case class Reputation(pastScores: Map[Int, PastScore], pending: Map[HtlcId, PendingHtlc], halfLife: FiniteDuration, maxRelayDuration: FiniteDuration) {
  /**
   * Estimate the confidence that a payment will succeed.
   */
  def getConfidence(fee: MilliSatoshi, accountability: Int, currentBlockHeight: BlockHeight, expiry: CltvExpiry, now: TimestampMilli = TimestampMilli.now()): Double = {
    val weights = Array.fill(Reputation.accountabilityLevels)(0.0)
    val scores = Array.fill(Reputation.accountabilityLevels)(0.0)
    for (e <- 0 until Reputation.accountabilityLevels) {
      val ps = pastScores(e).decay(now, halfLife)
      weights(e) += ps.weight
      scores(e) += ps.score
    }
    for (p <- pending.values) {
      weights(p.accountability) += p.weight(now, maxRelayDuration, currentBlockHeight)
    }
    weights(accountability) += PendingHtlc(fee, accountability, now, expiry).weight(now, maxRelayDuration, currentBlockHeight)
    /*
     Higher accountability buckets may have fewer payments which makes the weight of pending payments disproportionately
     important. To counter this effect, we try adding payments from the lower buckets to see if it gives us a higher
     confidence score.
     It is acceptable to use payments with lower accountability to increase the confidence score but not payments with
     higher accountability.
    */
    var score = scores(accountability)
    var weight = weights(accountability)
    var confidence = score / weight
    for (e <- Range.inclusive(accountability - 1, 0, step = -1)) {
      score += scores(e)
      weight += weights(e)
      confidence = confidence.max(score / weight)
    }
    confidence.min(1.0)
  }

  /**
   * Register a pending relay.
   */
  def addPendingHtlc(add: UpdateAddHtlc, fee: MilliSatoshi, accountability: Int, now: TimestampMilli = TimestampMilli.now()): Reputation =
    copy(pending = pending + (HtlcId(add) -> PendingHtlc(fee, accountability, now, add.cltvExpiry)))

  /**
   * When a HTLC is settled, we record whether it succeeded and how long it took.
   */
  def settlePendingHtlc(htlcId: HtlcId, isSuccess: Boolean, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    val newScores = pending.get(htlcId).map(p => {
      val ps = pastScores(p.accountability).decay(now, halfLife)
      val duration = now - p.startedAt
      val (weight, score) = if (isSuccess) {
        (p.fee.toLong.toDouble * (duration / maxRelayDuration).max(1.0), p.fee.toLong.toDouble)
      } else {
        (p.fee.toLong.toDouble * (duration / maxRelayDuration), 0.0)
      }
      val newWeight = ps.weight + weight
      val newScore = ps.score + score
      pastScores + (p.accountability -> PastScore(newWeight, newScore, now))
    }).getOrElse(pastScores)
    copy(pending = pending - htlcId, pastScores = newScores)
  }

  def addExtraFee(fee: MilliSatoshi, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    val ps = pastScores(0).decay(now, halfLife)
    copy(pastScores = pastScores + (0 -> ps.copy(score = ps.score + fee.toLong.toDouble)))
  }
}

object Reputation {
  private val accountabilityLevels = 2

  case class Config(enabled: Boolean, halfLife: FiniteDuration, maxRelayDuration: FiniteDuration)

  def init(config: Config): Reputation = Reputation(Map.empty.withDefaultValue(PastScore(0.0, 0.0, TimestampMilli.min)), Map.empty, config.halfLife, config.maxRelayDuration)

  case class Score(outgoingConfidence: Double, accountable: Boolean) {
    def checkIncomingChannelOccupancy(incomingChannelOccupancy: Double, outgoingChannelId: ByteVector32): Either[ChannelJammingException, Unit] = {
      if (outgoingConfidence + 0.1 < incomingChannelOccupancy) {
        return Left(OutgoingConfidenceTooLow(outgoingChannelId, outgoingConfidence, incomingChannelOccupancy))
      }
      Right(())
    }
  }

  case object Score {
    def min: Score = Score(0.0, accountable = false)
    def max(accountable: Boolean): Score = Score(1.0, accountable)
  }

  def incomingOccupancy(commitments: Commitments): Double = {
    commitments.active.map(commitment => {
      val incomingHtlcs = commitment.localCommit.spec.htlcs.collect(DirectedHtlc.incoming)
      val slotsOccupancy = commitments.active.map(c => incomingHtlcs.size.toDouble / (c.localCommitParams.maxAcceptedHtlcs min c.remoteCommitParams.maxAcceptedHtlcs)).max
      val htlcValueInFlight = incomingHtlcs.toSeq.map(_.amountMsat).sum
      val valueOccupancy = commitments.active.map(c => htlcValueInFlight.toLong.toDouble / c.maxHtlcValueInFlight.toLong.toDouble).max
      slotsOccupancy max valueOccupancy
    }).max
  }
}
