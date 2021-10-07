/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.wire.protocol.{UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, MilliSatoshiLong, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite

class CommitmentSpecSpec extends AnyFunSuite {

  test("add, fulfill and fail htlcs from the sender side") {
    val spec = CommitmentSpec(htlcs = Set(), commitTxFeerate = FeeratePerKw(1000 sat), toLocal = 5000000 msat, toRemote = 0 msat)
    val R = randomBytes32()
    val H = Crypto.sha256(R)

    val add1 = UpdateAddHtlc(ByteVector32.Zeroes, 1, (2000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket)
    val spec1 = CommitmentSpec.reduce(spec, add1 :: Nil, Nil)
    assert(spec1 === spec.copy(htlcs = Set(OutgoingHtlc(add1)), toLocal = 3000000 msat))

    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 2, (1000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket)
    val spec2 = CommitmentSpec.reduce(spec1, add2 :: Nil, Nil)
    assert(spec2 === spec1.copy(htlcs = Set(OutgoingHtlc(add1), OutgoingHtlc(add2)), toLocal = 2000000 msat))

    val ful1 = UpdateFulfillHtlc(ByteVector32.Zeroes, add1.id, R)
    val spec3 = CommitmentSpec.reduce(spec2, Nil, ful1 :: Nil)
    assert(spec3 === spec2.copy(htlcs = Set(OutgoingHtlc(add2)), toRemote = 2000000 msat))

    val fail1 = UpdateFailHtlc(ByteVector32.Zeroes, add2.id, R)
    val spec4 = CommitmentSpec.reduce(spec3, Nil, fail1 :: Nil)
    assert(spec4 === spec3.copy(htlcs = Set(), toLocal = 3000000 msat))
  }

  test("add, fulfill and fail htlcs from the receiver side") {
    val spec = CommitmentSpec(htlcs = Set(), commitTxFeerate = FeeratePerKw(1000 sat), toLocal = 0 msat, toRemote = (5000 * 1000) msat)
    val R = randomBytes32()
    val H = Crypto.sha256(R)

    val add1 = UpdateAddHtlc(ByteVector32.Zeroes, 1, (2000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket)
    val spec1 = CommitmentSpec.reduce(spec, Nil, add1 :: Nil)
    assert(spec1 === spec.copy(htlcs = Set(IncomingHtlc(add1)), toRemote = 3000 * 1000 msat))

    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 2, (1000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket)
    val spec2 = CommitmentSpec.reduce(spec1, Nil, add2 :: Nil)
    assert(spec2 === spec1.copy(htlcs = Set(IncomingHtlc(add1), IncomingHtlc(add2)), toRemote = (2000 * 1000) msat))

    val ful1 = UpdateFulfillHtlc(ByteVector32.Zeroes, add1.id, R)
    val spec3 = CommitmentSpec.reduce(spec2, ful1 :: Nil, Nil)
    assert(spec3 === spec2.copy(htlcs = Set(IncomingHtlc(add2)), toLocal = (2000 * 1000) msat))

    val fail1 = UpdateFailHtlc(ByteVector32.Zeroes, add2.id, R)
    val spec4 = CommitmentSpec.reduce(spec3, fail1 :: Nil, Nil)
    assert(spec4 === spec3.copy(htlcs = Set(), toRemote = (3000 * 1000) msat))
  }

  test("compute htlc tx feerate based on commitment format") {
    val spec = CommitmentSpec(htlcs = Set(), commitTxFeerate = FeeratePerKw(2500 sat), toLocal = (5000 * 1000) msat, toRemote = (2500 * 1000) msat)
    assert(spec.htlcTxFeerate(Transactions.DefaultCommitmentFormat) === FeeratePerKw(2500 sat))
    assert(spec.htlcTxFeerate(Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === FeeratePerKw(2500 sat))
    assert(spec.htlcTxFeerate(Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) === FeeratePerKw(0 sat))
  }

  def createHtlc(amount: MilliSatoshi, id: Long = 0): UpdateAddHtlc = {
    UpdateAddHtlc(ByteVector32.Zeroes, id, amount, randomBytes32(), CltvExpiry(500), TestConstants.emptyOnionPacket)
  }

  test("compute dust exposure") {
    {
      val htlcs = Set[DirectedHtlc](
        IncomingHtlc(createHtlc(449.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(449.sat.toMilliSatoshi)),
        IncomingHtlc(createHtlc(450.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(450.sat.toMilliSatoshi)),
        IncomingHtlc(createHtlc(499.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(499.sat.toMilliSatoshi)),
        IncomingHtlc(createHtlc(500.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(500.sat.toMilliSatoshi)),
      )
      val spec = CommitmentSpec(htlcs, FeeratePerKw(FeeratePerByte(50 sat)), 50000 msat, 75000 msat)
      assert(CommitmentSpec.dustExposure(spec, 450 sat, Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) === 898.sat.toMilliSatoshi)
      assert(CommitmentSpec.dustExposure(spec, 500 sat, Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) === 2796.sat.toMilliSatoshi)
      assert(CommitmentSpec.dustExposure(spec, 500 sat, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === 3796.sat.toMilliSatoshi)
    }
    {
      // Low feerate: buffer adds 10 sat/byte
      val dustLimit = 500.sat
      val feerate = FeeratePerKw(FeeratePerByte(10 sat))
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate, Transactions.DefaultCommitmentFormat) === 2257.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate, Transactions.DefaultCommitmentFormat) === 2157.sat)
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate * 2, Transactions.DefaultCommitmentFormat) === 4015.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate * 2, Transactions.DefaultCommitmentFormat) === 3815.sat)
      val htlcs = Set[DirectedHtlc](
        // Below the dust limit.
        IncomingHtlc(createHtlc(450.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(450.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 10 sat/byte
        IncomingHtlc(createHtlc(2250.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(2150.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 20 sat/byte
        IncomingHtlc(createHtlc(4010.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(3810.sat.toMilliSatoshi)),
        // Above the dust limit, untrimmed at 20 sat/byte
        IncomingHtlc(createHtlc(4020.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(3820.sat.toMilliSatoshi)),
      )
      val spec = CommitmentSpec(htlcs, feerate, 50000 msat, 75000 msat)
      val expected = 450.sat + 450.sat + 2250.sat + 2150.sat + 4010.sat + 3810.sat
      assert(CommitmentSpec.dustExposure(spec, dustLimit, Transactions.DefaultCommitmentFormat) === expected.toMilliSatoshi)
      assert(CommitmentSpec.dustExposure(spec, feerate * 2, dustLimit, Transactions.DefaultCommitmentFormat) === CommitmentSpec.dustExposure(spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(4010.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(3810.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(!CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(4020.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(!CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(3820.sat.toMilliSatoshi)), spec, dustLimit, Transactions.DefaultCommitmentFormat))
    }
    {
      // High feerate: buffer adds 25%
      val dustLimit = 1000.sat
      val feerate = FeeratePerKw(FeeratePerByte(80 sat))
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === 15120.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === 14320.sat)
      assert(Transactions.receivedHtlcTrimThreshold(dustLimit, feerate * 1.25, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === 18650.sat)
      assert(Transactions.offeredHtlcTrimThreshold(dustLimit, feerate * 1.25, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === 17650.sat)
      val htlcs = Set[DirectedHtlc](
        // Below the dust limit.
        IncomingHtlc(createHtlc(900.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(900.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 80 sat/byte
        IncomingHtlc(createHtlc(15000.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(14000.sat.toMilliSatoshi)),
        // Above the dust limit, trimmed at 100 sat/byte
        IncomingHtlc(createHtlc(18000.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(17000.sat.toMilliSatoshi)),
        // Above the dust limit, untrimmed at 100 sat/byte
        IncomingHtlc(createHtlc(19000.sat.toMilliSatoshi)),
        OutgoingHtlc(createHtlc(18000.sat.toMilliSatoshi)),
      )
      val spec = CommitmentSpec(htlcs, feerate, 50000 msat, 75000 msat)
      val expected = 900.sat + 900.sat + 15000.sat + 14000.sat + 18000.sat + 17000.sat
      assert(CommitmentSpec.dustExposure(spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) === expected.toMilliSatoshi)
      assert(CommitmentSpec.dustExposure(spec, feerate * 1.25, dustLimit, Transactions.DefaultCommitmentFormat) === CommitmentSpec.dustExposure(spec, dustLimit, Transactions.DefaultCommitmentFormat))
      assert(CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(18000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      assert(CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(17000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      assert(!CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(19000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
      assert(!CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(18000.sat.toMilliSatoshi)), spec, dustLimit, Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat))
    }
  }

  test("add incoming htlcs until we reach our maximum dust exposure") {
    val dustLimit = 1000.sat
    val initialSpec = CommitmentSpec(Set.empty, FeeratePerKw(10000 sat), 0 msat, 0 msat)
    assert(CommitmentSpec.dustExposure(initialSpec, dustLimit, Transactions.DefaultCommitmentFormat) === 0.msat)
    assert(CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(9000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(9000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    // NB: HTLC-success transactions are bigger than HTLC-timeout transactions: that means incoming htlcs have a higher
    // dust threshold than outgoing htlcs in our commitment.
    assert(CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(9500.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(!CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(9500.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(!CommitmentSpec.contributesToDustExposure(IncomingHtlc(createHtlc(10000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))
    assert(!CommitmentSpec.contributesToDustExposure(OutgoingHtlc(createHtlc(10000.sat.toMilliSatoshi)), initialSpec, dustLimit, Transactions.DefaultCommitmentFormat))

    val updatedSpec = initialSpec.copy(htlcs = Set(
      OutgoingHtlc(createHtlc(9000.sat.toMilliSatoshi)),
      OutgoingHtlc(createHtlc(9500.sat.toMilliSatoshi)),
      IncomingHtlc(createHtlc(9500.sat.toMilliSatoshi)),
    ))
    assert(CommitmentSpec.dustExposure(updatedSpec, dustLimit, Transactions.DefaultCommitmentFormat) === 18500.sat.toMilliSatoshi)

    val receivedHtlcs = Seq(
      createHtlc(id = 5, amount = 9500.sat.toMilliSatoshi),
      createHtlc(id = 6, amount = 5000.sat.toMilliSatoshi),
      createHtlc(id = 7, amount = 1000.sat.toMilliSatoshi),
      createHtlc(id = 8, amount = 400.sat.toMilliSatoshi),
      createHtlc(id = 9, amount = 400.sat.toMilliSatoshi),
      createHtlc(id = 10, amount = 50000.sat.toMilliSatoshi),
    )
    val (accepted, rejected) = CommitmentSpec.addIncomingHtlcsUntilDustExposureReached(25000 sat, updatedSpec, dustLimit, 10000.sat.toMilliSatoshi, initialSpec, dustLimit, 15000.sat.toMilliSatoshi, receivedHtlcs, Transactions.DefaultCommitmentFormat)
    assert(accepted.map(_.id).toSet === Set(5, 6, 8, 10))
    assert(rejected.map(_.id).toSet === Set(7, 9))
  }

}
