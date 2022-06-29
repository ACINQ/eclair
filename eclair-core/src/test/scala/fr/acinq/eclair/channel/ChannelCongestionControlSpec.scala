/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelCongestionControl.CongestionConfig
import fr.acinq.eclair.channel.HtlcFiltering.FilteredHtlcs
import fr.acinq.eclair.transactions.Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat
import fr.acinq.eclair.transactions.{CommitmentSpec, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, MilliSatoshiLong, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

class ChannelCongestionControlSpec extends AnyFunSuiteLike {

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  def createHtlc(id: Long, amount: MilliSatoshi): UpdateAddHtlc = {
    UpdateAddHtlc(ByteVector32.Zeroes, id, amount, randomBytes32(), CltvExpiry(500), TestConstants.emptyOnionPacket)
  }

  test("reject htlcs when buckets are full") {
    val maxAcceptedTests = Seq(30, 50, 100, 200, 300, 483)
    val maxInFlightTests = Seq(
      100_000_000L msat,
      250_000_000L msat,
      500_000_000L msat,
      750_000_000L msat,
      1_000_000_000L msat,
      5_000_000_000L msat,
      10_000_000_000L msat,
      50_000_000_000L msat,
      100_000_000_000L msat,
      200_000_000_000L msat,
      300_000_000_000L msat,
      400_000_000_000L msat,
      500_000_000_000L msat,
    )
    maxAcceptedTests.foreach { maxAcceptedHtlcs =>
      maxInFlightTests.foreach { maxHtlcValueInFlight =>
        val cfg = CongestionConfig(maxAcceptedHtlcs, maxHtlcValueInFlight)
        assert(cfg.buckets.length == 3)
        // We start with an empty channel.
        val dustLimit = 330 sat
        var htlcCount = 0
        var amountLocked = 0 msat
        var localSpec = CommitmentSpec(Set.empty, FeeratePerKw(0 sat), maxHtlcValueInFlight * 2, maxHtlcValueInFlight * 2)
        var remoteSpec = CommitmentSpec(Set.empty, FeeratePerKw(0 sat), maxHtlcValueInFlight * 2, maxHtlcValueInFlight * 2)

        def addHtlc(htlcAmount: MilliSatoshi): Unit = {
          val add = createHtlc(htlcCount, htlcAmount)
          localSpec = CommitmentSpec.addHtlc(localSpec, OutgoingHtlc(add))
          remoteSpec = CommitmentSpec.addHtlc(remoteSpec, IncomingHtlc(add))
        }

        def fillBucket(htlcAmount: MilliSatoshi): Unit = {
          while (ChannelCongestionControl.shouldSendHtlc(createHtlc(htlcCount, htlcAmount), localSpec, dustLimit, maxAcceptedHtlcs, remoteSpec, dustLimit, maxAcceptedHtlcs, maxHtlcValueInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)) {
            addHtlc(htlcAmount)
            htlcCount += 1
            amountLocked += htlcAmount
          }
        }

        // We fill the first bucket with htlcs at the dust limit.
        fillBucket(dustLimit.toMilliSatoshi)
        assert(htlcCount == cfg.buckets.head.size)
        assert(htlcCount < maxAcceptedHtlcs)
        assert(!ChannelCongestionControl.shouldSendHtlc(createHtlc(htlcCount, cfg.buckets.head.threshold), localSpec, dustLimit, maxAcceptedHtlcs, remoteSpec, dustLimit, maxAcceptedHtlcs, maxHtlcValueInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
        // We fill the second bucket with the lowest htlc amount allowed in that bucket.
        fillBucket(cfg.buckets.head.threshold + 1.msat)
        assert(htlcCount == cfg.buckets(1).size)
        assert(htlcCount < maxAcceptedHtlcs)
        assert(!ChannelCongestionControl.shouldSendHtlc(createHtlc(htlcCount, cfg.buckets(1).threshold), localSpec, dustLimit, maxAcceptedHtlcs, remoteSpec, dustLimit, maxAcceptedHtlcs, maxHtlcValueInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
        // We fill the third bucket with the lowest htlc amount allowed in that bucket.
        fillBucket(cfg.buckets(1).threshold + 1.msat)
        assert(htlcCount == cfg.buckets(2).size)
        assert(htlcCount < maxAcceptedHtlcs)
        assert(!ChannelCongestionControl.shouldSendHtlc(createHtlc(htlcCount, cfg.buckets(2).threshold), localSpec, dustLimit, maxAcceptedHtlcs, remoteSpec, dustLimit, maxAcceptedHtlcs, maxHtlcValueInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
        // We fill the remaining htlc slots with the lowest htlc amount allowed for the last bucket.
        while (htlcCount < maxAcceptedHtlcs) {
          val amount = cfg.buckets(2).threshold + 1.msat
          assert(ChannelCongestionControl.shouldSendHtlc(createHtlc(htlcCount, amount), localSpec, dustLimit, maxAcceptedHtlcs, remoteSpec, dustLimit, maxAcceptedHtlcs, maxHtlcValueInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
          addHtlc(amount)
          htlcCount += 1
          amountLocked += amount
        }
        assert(htlcCount == maxAcceptedHtlcs)
        assert(amountLocked > maxHtlcValueInFlight * 0.5, s"max-accepted = $maxAcceptedHtlcs, max-in-flight = $maxHtlcValueInFlight, amount-locked = $amountLocked, ratio = ${amountLocked.toLong.toDouble / maxHtlcValueInFlight.toLong}")
      }
    }
  }

  test("different local and remote limits") {
    val localMaxAccepted = 10
    val localDustLimit = 330 sat
    val remoteMaxAccepted = 20
    val remoteDustLimit = 660 sat
    val maxInFlight = 250_000_000 msat
    var localSpec = CommitmentSpec(Set.empty, FeeratePerKw(0 sat), 500_000_000 msat, 500_000_000 msat)
    var remoteSpec = CommitmentSpec(Set.empty, FeeratePerKw(0 sat), 500_000_000 msat, 500_000_000 msat)
    // We can send as many HTLCs below dust as we want.
    (1 to 25).foreach(i => {
      val add = createHtlc(i, 300_000 msat)
      assert(ChannelCongestionControl.shouldSendHtlc(add, localSpec, localDustLimit, localMaxAccepted, remoteSpec, remoteDustLimit, remoteMaxAccepted, maxInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
      localSpec = CommitmentSpec.addHtlc(localSpec, OutgoingHtlc(add))
      remoteSpec = CommitmentSpec.addHtlc(remoteSpec, IncomingHtlc(add))
    })
    // We enforce the most restricting limits for HTLCs above dust: the first local bucket allows only 5 tiny htlcs.
    (26 to 30).foreach(i => {
      val add = createHtlc(i, 330_000 msat)
      assert(ChannelCongestionControl.shouldSendHtlc(add, localSpec, localDustLimit, localMaxAccepted, remoteSpec, remoteDustLimit, remoteMaxAccepted, maxInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
      localSpec = CommitmentSpec.addHtlc(localSpec, OutgoingHtlc(add))
      remoteSpec = CommitmentSpec.addHtlc(remoteSpec, IncomingHtlc(add))
    })
    val rejectedHtlc = createHtlc(50, 330_000 msat)
    assert(!ChannelCongestionControl.shouldSendHtlc(rejectedHtlc, localSpec, localDustLimit, localMaxAccepted, remoteSpec, remoteDustLimit, remoteMaxAccepted, maxInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat))
  }

  test("filter htlcs before forwarding") {
    val maxAccepted = 10
    val maxInFlight = 250_000_000 msat
    val dustLimit = 500 sat
    val localSpec = CommitmentSpec(Set.empty, FeeratePerKw(0 sat), 500_000_000 msat, 500_000_000 msat)
    val remoteSpec = CommitmentSpec(Set.empty, FeeratePerKw(0 sat), 500_000_000 msat, 500_000_000 msat)

    {
      val received = FilteredHtlcs(
        accepted = Seq(
          // HTLCs for the first bucket:
          createHtlc(0, 500_000 msat),
          createHtlc(1, 500_000 msat),
          createHtlc(2, 500_000 msat),
          createHtlc(3, 500_000 msat),
          createHtlc(4, 500_000 msat),
          createHtlc(5, 500_000 msat),
          createHtlc(6, 500_000 msat),
          createHtlc(7, 500_000 msat),
          // HTLCs for the second bucket:
          createHtlc(8, 5_000_000 msat),
          createHtlc(9, 5_000_000 msat),
          createHtlc(10, 5_000_000 msat),
          createHtlc(11, 5_000_000 msat),
          createHtlc(12, 5_000_000 msat),
          // HTLCs for the third bucket:
          createHtlc(13, 20_000_000 msat),
          createHtlc(14, 20_000_000 msat),
          createHtlc(15, 20_000_000 msat),
          // Unrestricted HTLCs:
          createHtlc(16, 50_000_000 msat),
        ),
        rejected = Seq(
          createHtlc(100, 2_000 msat),
          createHtlc(101, 3_000 msat),
        )
      )
      val filtered = ChannelCongestionControl.filterBeforeForward(localSpec, dustLimit, maxAccepted, remoteSpec, dustLimit, received, maxInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
      assert(filtered.accepted.map(_.id).toSet == Set(0, 1, 2, 3, 4, 8, 9, 10, 13, 16))
      assert(filtered.rejected.map(_.id).toSet == Set(5, 6, 7, 11, 12, 14, 15, 100, 101))
    }
    {
      val received = FilteredHtlcs(
        accepted = Seq(
          // HTLCs for the third bucket:
          createHtlc(0, 20_000_000 msat),
          createHtlc(1, 20_000_000 msat),
          createHtlc(2, 20_000_000 msat),
          createHtlc(3, 20_000_000 msat),
          createHtlc(4, 20_000_000 msat),
          createHtlc(5, 20_000_000 msat),
          createHtlc(6, 20_000_000 msat),
          createHtlc(7, 20_000_000 msat),
          createHtlc(8, 20_000_000 msat),
          createHtlc(9, 20_000_000 msat),
          // HTLCs for the second bucket:
          createHtlc(10, 5_000_000 msat),
          createHtlc(11, 5_000_000 msat),
          // HTLCs for the first bucket:
          createHtlc(12, 500_000 msat),
          createHtlc(13, 500_000 msat),
        ),
        rejected = Seq(
          createHtlc(100, 2_000 msat),
          createHtlc(101, 3_000 msat),
        )
      )
      val filtered = ChannelCongestionControl.filterBeforeForward(localSpec, dustLimit, maxAccepted, remoteSpec, dustLimit, received, maxInFlight, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
      assert(filtered.accepted.map(_.id).toSet == Set(0, 1, 2, 3, 4, 5, 6, 7, 8))
      assert(filtered.rejected.map(_.id).toSet == Set(9, 10, 11, 12, 13, 100, 101))
    }
  }

}
