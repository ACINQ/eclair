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
import fr.acinq.eclair.channel.{ChannelJammingException, ChannelParams, IncomingConfidenceTooLow, OutgoingConfidenceTooLow, TooManySmallHtlcs}
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}

import scala.concurrent.duration.FiniteDuration

/**
 * Reputation score per endorsement level.
 *
 * @param weight           How much fees we would have collected in the past if all HTLCs had succeeded (exponential moving average).
 * @param score            How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt Timestamp of the last recorded HTLC settlement.
 */
case class PastScore(weight: Double, score: Double, lastSettlementAt: TimestampMilli)

/** We're relaying that HTLC and are waiting for it to settle. */
case class PendingHtlc(fee: MilliSatoshi, endorsement: Int, startedAt: TimestampMilli) {
  def weight(now: TimestampMilli, minDuration: FiniteDuration, multiplier: Double): Double = {
    val duration = now - startedAt
    fee.toLong.toDouble * (duration / minDuration).max(multiplier)
  }
}

case class HtlcId(channelId: ByteVector32, id: Long)

/**
 * Local reputation for a given node.
 *
 * @param pastScores        Scores from past HTLCs for each endorsement level.
 * @param pending           Set of pending HTLCs.
 * @param halfLife          Half life for the exponential moving average.
 * @param maxRelayDuration  Duration after which HTLCs are penalized for staying pending too long.
 * @param pendingMultiplier How much to penalize pending HTLCs.
 */
case class Reputation(pastScores: Map[Int, PastScore], pending: Map[HtlcId, PendingHtlc], halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double) {
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
  def addPendingHtlc(htlcId: HtlcId, fee: MilliSatoshi, endorsement: Int, now: TimestampMilli = TimestampMilli.now()): Reputation =
    copy(pending = pending + (htlcId -> PendingHtlc(fee, endorsement, now)))

  /**
   * When a HTLC is settled, we record whether it succeeded and how long it took.
   */
  def settlePendingHtlc(htlcId: HtlcId, isSuccess: Boolean, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    val newScores = pending.get(htlcId).map(p => {
      val d = decay(now, pastScores(p.endorsement).lastSettlementAt)
      val newWeight = d * pastScores(p.endorsement).weight + p.weight(now, maxRelayDuration, if (isSuccess) 1.0 else 0.0)
      val newScore = d * pastScores(p.endorsement).score + (if (isSuccess) p.fee.toLong.toDouble else 0)
      pastScores + (p.endorsement -> PastScore(newWeight, newScore, now))
    }).getOrElse(pastScores)
    copy(pending = pending - htlcId, pastScores = newScores)
  }
}

object Reputation {
  val endorsementLevels = 8
  val maxEndorsement = endorsementLevels - 1

  case class Config(enabled: Boolean, halfLife: FiniteDuration, maxRelayDuration: FiniteDuration, pendingMultiplier: Double)

  def init(config: Config): Reputation = Reputation(Map.empty.withDefaultValue(PastScore(0.0, 0.0, TimestampMilli.min)), Map.empty, config.halfLife, config.maxRelayDuration, config.pendingMultiplier)

  /**
   * @param incomingConfidence Confidence that the outgoing HTLC will succeed given the reputation of the incoming peer
   */
  case class Score(incomingConfidence: Double, outgoingConfidence: Double) {
    val endorsement = toEndorsement(incomingConfidence)

    def checkOutgoingChannelOccupancy(outgoingHtlcs: Seq[UpdateAddHtlc], params: ChannelParams): Either[ChannelJammingException, Unit] = {
      val maxAcceptedHtlcs = Seq(params.localCommitParams.maxAcceptedHtlcs, params.remoteCommitParams.maxAcceptedHtlcs).min

      for ((amountMsat, i) <- outgoingHtlcs.map(_.amountMsat).sorted.zipWithIndex) {
        // We want to allow some small HTLCs but still keep slots for larger ones.
        // We never want to reject HTLCs of size above `maxHtlcAmount / maxAcceptedHtlcs` as too small because we want to allow filling all the slots with HTLCs of equal sizes.
        // We use exponentially spaced thresholds in between.
        if (amountMsat.toLong < 1 || amountMsat.toLong.toDouble < math.pow(params.maxHtlcValueInFlight.toLong.toDouble / maxAcceptedHtlcs, i / maxAcceptedHtlcs)) {
          return Left(TooManySmallHtlcs(params.channelId, number = i + 1, below = amountMsat))
        }
      }

      val htlcValueInFlight = outgoingHtlcs.map(_.amountMsat).sum
      val slotsOccupancy = outgoingHtlcs.size.toDouble / maxAcceptedHtlcs
      val valueOccupancy = htlcValueInFlight.toLong.toDouble / params.maxHtlcValueInFlight.toLong.toDouble
      val occupancy = slotsOccupancy max valueOccupancy
      // Because there are only 8 endorsement levels, the highest endorsement corresponds to a confidence between 87.5% and 100%.
      // So even for well-behaved peers setting the highest endorsement we still expect a confidence of less than 93.75%.
      // To compensate for that we add a tolerance of 10% that's also useful for nodes without history.
      if (incomingConfidence + 0.1 < occupancy) {
        return Left(IncomingConfidenceTooLow(params.channelId, incomingConfidence, occupancy))
      }

      Right(())
    }

    def checkIncomingChannelOccupancy(incomingChannelOccupancy: Double, outgoingChannelId: ByteVector32): Either[ChannelJammingException, Unit] = {
      if (outgoingConfidence + 0.1 < incomingChannelOccupancy) {
        return Left(OutgoingConfidenceTooLow(outgoingChannelId, incomingConfidence, incomingChannelOccupancy))
      }
      Right(())
    }
  }

  case object Score {
    val max = Score(1.0, 1.0)

    def fromEndorsement(endorsement: Int): Score = Score((endorsement + 0.5) / 8, 1.0)
  }

  def toEndorsement(confidence: Double): Int = (confidence * endorsementLevels).toInt.min(maxEndorsement)
}
