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

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.wire.protocol.{UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, MilliSatoshiLong, TestConstants, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite

class CommitmentSpecSpec extends AnyFunSuite {

  test("add, fulfill and fail htlcs from the sender side") {
    val spec = CommitmentSpec(htlcs = Set(), commitTxFeerate = FeeratePerKw(1000 sat), toLocal = 5000000 msat, toRemote = 0 msat)
    val R = randomBytes32()
    val H = Crypto.sha256(R)

    val add1 = UpdateAddHtlc(ByteVector32.Zeroes, 1, (2000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket, None)
    val spec1 = CommitmentSpec.reduce(spec, add1 :: Nil, Nil)
    assert(spec1 == spec.copy(htlcs = Set(OutgoingHtlc(add1)), toLocal = 3000000 msat))

    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 2, (1000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket, None)
    val spec2 = CommitmentSpec.reduce(spec1, add2 :: Nil, Nil)
    assert(spec2 == spec1.copy(htlcs = Set(OutgoingHtlc(add1), OutgoingHtlc(add2)), toLocal = 2000000 msat))

    val ful1 = UpdateFulfillHtlc(ByteVector32.Zeroes, add1.id, R)
    val spec3 = CommitmentSpec.reduce(spec2, Nil, ful1 :: Nil)
    assert(spec3 == spec2.copy(htlcs = Set(OutgoingHtlc(add2)), toRemote = 2000000 msat))

    val fail1 = UpdateFailHtlc(ByteVector32.Zeroes, add2.id, R)
    val spec4 = CommitmentSpec.reduce(spec3, Nil, fail1 :: Nil)
    assert(spec4 == spec3.copy(htlcs = Set(), toLocal = 3000000 msat))
  }

  test("add, fulfill and fail htlcs from the receiver side") {
    val spec = CommitmentSpec(htlcs = Set(), commitTxFeerate = FeeratePerKw(1000 sat), toLocal = 0 msat, toRemote = (5000 * 1000) msat)
    val R = randomBytes32()
    val H = Crypto.sha256(R)

    val add1 = UpdateAddHtlc(ByteVector32.Zeroes, 1, (2000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket, None)
    val spec1 = CommitmentSpec.reduce(spec, Nil, add1 :: Nil)
    assert(spec1 == spec.copy(htlcs = Set(IncomingHtlc(add1)), toRemote = 3000 * 1000 msat))

    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 2, (1000 * 1000) msat, H, CltvExpiry(400), TestConstants.emptyOnionPacket, None)
    val spec2 = CommitmentSpec.reduce(spec1, Nil, add2 :: Nil)
    assert(spec2 == spec1.copy(htlcs = Set(IncomingHtlc(add1), IncomingHtlc(add2)), toRemote = (2000 * 1000) msat))

    val ful1 = UpdateFulfillHtlc(ByteVector32.Zeroes, add1.id, R)
    val spec3 = CommitmentSpec.reduce(spec2, ful1 :: Nil, Nil)
    assert(spec3 == spec2.copy(htlcs = Set(IncomingHtlc(add2)), toLocal = (2000 * 1000) msat))

    val fail1 = UpdateFailHtlc(ByteVector32.Zeroes, add2.id, R)
    val spec4 = CommitmentSpec.reduce(spec3, fail1 :: Nil, Nil)
    assert(spec4 == spec3.copy(htlcs = Set(), toRemote = (3000 * 1000) msat))
  }

  test("compute htlc tx feerate based on commitment format") {
    val spec = CommitmentSpec(htlcs = Set(), commitTxFeerate = FeeratePerKw(2500 sat), toLocal = (5000 * 1000) msat, toRemote = (2500 * 1000) msat)
    assert(spec.htlcTxFeerate(Transactions.DefaultCommitmentFormat) == FeeratePerKw(2500 sat))
    assert(spec.htlcTxFeerate(Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat) == FeeratePerKw(2500 sat))
    assert(spec.htlcTxFeerate(Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == FeeratePerKw(0 sat))
  }

  def createHtlc(amount: MilliSatoshi): UpdateAddHtlc = {
    UpdateAddHtlc(ByteVector32.Zeroes, 0, amount, randomBytes32(), CltvExpiry(500), TestConstants.emptyOnionPacket, None)
  }

}
