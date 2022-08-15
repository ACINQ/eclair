/*
 * Copyright 2021 ACINQ SAS
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
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, MilliSatoshiLong, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

class DustExposureSpec extends AnyFunSuiteLike {

  def createHtlc(id: Long, amount: MilliSatoshi): UpdateAddHtlc = {
    UpdateAddHtlc(ByteVector32.Zeroes, id, amount, randomBytes32(), CltvExpiry(500), TestConstants.emptyOnionPacket, None)
  }

  test("compute dust exposure") {
    {
      val htlcs = Set[DirectedHtlc](
        IncomingHtlc(createHtlc(0, 449.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(0, 449.sat.toMilliSatoshi)),
        IncomingHtlc(createHtlc(1, 450.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(1, 450.sat.toMilliSatoshi)),
        IncomingHtlc(createHtlc(2, 499.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(2, 499.sat.toMilliSatoshi)),
        IncomingHtlc(createHtlc(3, 500.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(3, 500.sat.toMilliSatoshi)),
      )
      val spec = CommitmentSpec(htlcs, FeeratePerKw(FeeratePerByte(50 sat)), 50000 msat, 75000 msat)
      assert(DustExposure.computeExposure(spec, 450 sat, Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == 898.sat.toMilliSatoshi)
      assert(DustExposure.computeExposure(spec, 500 sat, Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == 2796.sat.toMilliSatoshi)
      assert(DustExposure.computeExposure(spec, 500 sat, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == 3796.sat.toMilliSatoshi)
    }
    {
      // Low feerate: buffer adds 10 sat/byte
      val dustLimit = 500.sat
      val feerate = FeeratePerKw(FeeratePerByte(10 sat))
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate, Transactions.DefaultCommitmentFormat) == 2257.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate, Transactions.DefaultCommitmentFormat) == 2157.sat)
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate * 2, Transactions.DefaultCommitmentFormat) == 4015.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate * 2, Transactions.DefaultCommitmentFormat) == 3815.sat)
      val htlcs = Set[DirectedHtlc](
        // Below the dust limit.
        IncomingHtlc(createHtlc(0, 450.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(0, 450.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 10 sat/byte
        IncomingHtlc(createHtlc(1, 2250.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(1, 2150.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 20 sat/byte
        IncomingHtlc(createHtlc(2, 4010.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(2, 3810.sat.toMilliSatoshi)),
        // Above the dust limit, untrimmed at 20 sat/byte
        IncomingHtlc(createHtlc(3, 4020.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(3, 3820.sat.toMilliSatoshi)),
      )
      val spec = CommitmentSpec(htlcs, feerate, 50000 msat, 75000 msat)
      val expected = 450.sat + 450.sat + 2250.sat + 2150.sat + 4010.sat + 3810.sat
      assert(DustExposure.computeExposure(spec, dustLimit, Transactions.DefaultCommitmentFormat) == expected.toMilliSatoshi)
      assert(DustExposure.computeExposure(spec, feerate * 2, dustLimit, Transactions.DefaultCommitmentFormat) == DustExposure.computeExposure(spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(4, 4010.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(4, 3810.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(!DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(5, 4020.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(!DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(5, 3820.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
    }
    {
      // High feerate: buffer adds 25%
      val dustLimit = 1000.sat
      val feerate = FeeratePerKw(FeeratePerByte(80 sat))
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == 15120.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == 14320.sat)
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate * 1.25, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == 18650.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate * 1.25, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == 17650.sat)
      val htlcs = Set[DirectedHtlc](
        // Below the dust limit.
        IncomingHtlc(createHtlc(0, 900.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(0, 900.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 80 sat/byte
        IncomingHtlc(createHtlc(1, 15000.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(1, 14000.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 100 sat/byte
        IncomingHtlc(createHtlc(2, 18000.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(2, 17000.sat.toMilliSatoshi)),
        // Above the dust limit, untrimmed at 100 sat/byte
        IncomingHtlc(createHtlc(3, 19000.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(3, 18000.sat.toMilliSatoshi)),
      )
      val spec = CommitmentSpec(htlcs, feerate, 50000 msat, 75000 msat)
      val expected = 900.sat + 900.sat + 15000.sat + 14000.sat + 18000.sat + 17000.sat
      assert(DustExposure.computeExposure(spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == expected.toMilliSatoshi)
      assert(DustExposure.computeExposure(spec, feerate * 1.25, dustLimit, Transactions.DefaultCommitmentFormat) == DustExposure.computeExposure(spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(4, 18000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      assert(DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(4, 17000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      assert(!DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(5, 19000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      assert(!DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(5, 18000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
    }
  }

  test("filter incoming htlcs before forwarding") {
    val dustLimit = 1000.sat
    val initialSpec = CommitmentSpec(Set.empty, FeeratePerKw(10000 sat), 0 msat, 0 msat)
    assert(DustExposure.computeExposure(initialSpec, dustLimit, Transactions.DefaultCommitmentFormat) == 0.msat)
    assert(DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(0, 9000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(0, 9000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    // NB: HTLC-success transactions are bigger than HTLC-timeout transactions: that means incoming htlcs have a higher
    // dust threshold than outgoing htlcs in our commitment.
    assert(DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(0, 9500.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(!DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(0, 9500.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(!DustExposure.contributesToDustExposure(IncomingHtlc(createHtlc(0, 10000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(!DustExposure.contributesToDustExposure(OutgoingHtlc(createHtlc(0, 10000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))

    val updatedSpec = initialSpec.copy(htlcs = Set(
      OutgoingHtlc(createHtlc(2, 9000.sat.toMilliSatoshi)),
      OutgoingHtlc(createHtlc(3, 9500.sat.toMilliSatoshi)),
      IncomingHtlc(createHtlc(4, 9500.sat.toMilliSatoshi)),
    ))
    assert(DustExposure.computeExposure(updatedSpec, dustLimit, Transactions.DefaultCommitmentFormat) == 18500.sat.toMilliSatoshi)

    val receivedHtlcs = Seq(
      createHtlc(5, 9500.sat.toMilliSatoshi),
      createHtlc(6, 5000.sat.toMilliSatoshi),
      createHtlc(7, 1000.sat.toMilliSatoshi),
      createHtlc(8, 400.sat.toMilliSatoshi),
      createHtlc(9, 400.sat.toMilliSatoshi),
      createHtlc(10, 50000.sat.toMilliSatoshi),
    )
    val (accepted, rejected) = DustExposure.filterBeforeForward(25000 sat, updatedSpec, dustLimit, 10000.sat.toMilliSatoshi, initialSpec, dustLimit, 15000.sat.toMilliSatoshi, receivedHtlcs, Transactions.DefaultCommitmentFormat)
    assert(accepted.map(_.id).toSet == Set(5, 6, 8, 10))
    assert(rejected.map(_.id).toSet == Set(7, 9))
  }

}
