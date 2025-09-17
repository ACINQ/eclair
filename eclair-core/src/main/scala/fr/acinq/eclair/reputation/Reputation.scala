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
 * Reputation score per endorsement level.
 *
 * @param weight           How much fees we would have collected in the past if all HTLCs had succeeded (exponential moving average).
 * @param score            How much fees we have collected in the past (exponential moving average).
 * @param lastSettlementAt Timestamp of the last recorded HTLC settlement.
 */
case class PastScore(weight: Double, score: Double, lastSettlementAt: TimestampMilli)

/** We're relaying that HTLC and are waiting for it to settle. */
case class PendingHtlc(fee: MilliSatoshi, endorsement: Int, startedAt: TimestampMilli, expiry: CltvExpiry) {
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
 * @param pastScores        Scores from past HTLCs for each endorsement level.
 * @param pending           Set of pending HTLCs.
 * @param halfLife          Half life for the exponential moving average.
 * @param maxRelayDuration  Duration after which HTLCs are penalized for staying pending too long.
 */
case class Reputation(pastScores: Map[Int, PastScore], pending: Map[HtlcId, PendingHtlc], halfLife: FiniteDuration, maxRelayDuration: FiniteDuration) {
  private def decay(now: TimestampMilli, lastSettlementAt: TimestampMilli): Double = scala.math.pow(0.5, (now - lastSettlementAt) / halfLife)

  /**
   * Estimate the confidence that a payment will succeed.
   */
  def getConfidence(fee: MilliSatoshi, endorsement: Int, currentBlockHeight: BlockHeight, expiry: CltvExpiry, now: TimestampMilli = TimestampMilli.now()): Double = {
    val weights = Array.fill(Reputation.endorsementLevels)(0.0)
    val scores = Array.fill(Reputation.endorsementLevels)(0.0)
    for (e <- 0 until Reputation.endorsementLevels) {
      val d = decay(now, pastScores(e).lastSettlementAt)
      weights(e) += d * pastScores(e).weight
      scores(e) += d * pastScores(e).score
    }
    for (p <- pending.values) {
      weights(p.endorsement) += p.weight(now, maxRelayDuration, currentBlockHeight)
    }
    weights(endorsement) += PendingHtlc(fee, endorsement, now, expiry).weight(now, maxRelayDuration, currentBlockHeight)
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
  def addPendingHtlc(add: UpdateAddHtlc, fee: MilliSatoshi, endorsement: Int, now: TimestampMilli = TimestampMilli.now()): Reputation =
    copy(pending = pending + (HtlcId(add) -> PendingHtlc(fee, endorsement, now, add.cltvExpiry)))

  /**
   * When a HTLC is settled, we record whether it succeeded and how long it took.
   */
  def settlePendingHtlc(htlcId: HtlcId, isSuccess: Boolean, now: TimestampMilli = TimestampMilli.now()): Reputation = {
    val newScores = pending.get(htlcId).map(p => {
      val d = decay(now, pastScores(p.endorsement).lastSettlementAt)
      val duration = now - p.startedAt
      val (weight, score) = if (isSuccess) {
        (p.fee.toLong.toDouble * (duration / maxRelayDuration).max(1.0), p.fee.toLong.toDouble)
      } else {
        (p.fee.toLong.toDouble * (duration / maxRelayDuration), 0.0)
      }
      val newWeight = d * pastScores(p.endorsement).weight + weight
      val newScore = d * pastScores(p.endorsement).score + score
      pastScores + (p.endorsement -> PastScore(newWeight, newScore, now))
    }).getOrElse(pastScores)
    copy(pending = pending - htlcId, pastScores = newScores)
  }
}

object Reputation {
  private val endorsementLevels = 8
  val maxEndorsement: Int = endorsementLevels - 1

  case class Config(enabled: Boolean, halfLife: FiniteDuration, maxRelayDuration: FiniteDuration)

  def init(config: Config): Reputation = Reputation(Map.empty.withDefaultValue(PastScore(0.0, 0.0, TimestampMilli.min)), Map.empty, config.halfLife, config.maxRelayDuration)

  /**
   * @param incomingConfidence Confidence that the outgoing HTLC will succeed given the reputation of the incoming peer
   */
  case class Score(incomingConfidence: Double, outgoingConfidence: Double) {
    val endorsement: Int = toEndorsement(incomingConfidence)

    def checkOutgoingChannelOccupancy(channelId: ByteVector32, commitment: Commitment, outgoingHtlcs: Seq[UpdateAddHtlc]): Either[ChannelJammingException, Unit] = {
      val maxAcceptedHtlcs = Seq(commitment.localCommitParams.maxAcceptedHtlcs, commitment.remoteCommitParams.maxAcceptedHtlcs).min

      for ((amountMsat, i) <- outgoingHtlcs.map(_.amountMsat).sorted.zipWithIndex) {
        // We want to allow some small HTLCs but still keep slots for larger ones.
        // We never want to reject HTLCs of size above `maxHtlcAmount / maxAcceptedHtlcs` as too small because we want to allow filling all the slots with HTLCs of equal sizes.
        // We use exponentially spaced thresholds in between.
        if (amountMsat.toLong < 1 || amountMsat.toLong.toDouble < math.pow(commitment.maxHtlcValueInFlight.toLong.toDouble / maxAcceptedHtlcs, i / maxAcceptedHtlcs)) {
          return Left(TooManySmallHtlcs(channelId, number = i + 1, below = amountMsat))
        }
      }

      val htlcValueInFlight = outgoingHtlcs.map(_.amountMsat).sum
      val slotsOccupancy = outgoingHtlcs.size.toDouble / maxAcceptedHtlcs
      val valueOccupancy = htlcValueInFlight.toLong.toDouble / commitment.maxHtlcValueInFlight.toLong.toDouble
      val occupancy = slotsOccupancy max valueOccupancy
      // Because there are only 8 endorsement levels, the highest endorsement corresponds to a confidence between 87.5% and 100%.
      // So even for well-behaved peers setting the highest endorsement we still expect a confidence of less than 93.75%.
      // To compensate for that we add a tolerance of 10% that's also useful for nodes without history.
      if (incomingConfidence + 0.1 < occupancy) {
        return Left(IncomingConfidenceTooLow(channelId, incomingConfidence, occupancy))
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
    val max: Score = Score(1.0, 1.0)

    def fromEndorsement(endorsement: Int): Score = Score((endorsement + 0.5) / 8, 1.0)
  }

  def toEndorsement(confidence: Double): Int = (confidence * endorsementLevels).toInt.min(maxEndorsement)

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
