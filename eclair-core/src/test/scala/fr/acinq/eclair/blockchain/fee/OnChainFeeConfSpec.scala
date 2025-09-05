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

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.transactions.Transactions.{DefaultCommitmentFormat, UnsafeLegacyAnchorOutputsCommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat}
import org.scalatest.funsuite.AnyFunSuite

class OnChainFeeConfSpec extends AnyFunSuite {

  private val defaultFeeTargets = FeeTargets(funding = ConfirmationPriority.Medium, closing = ConfirmationPriority.Medium)
  private val defaultMaxClosingFeerate = FeeratePerKw(10_000 sat)
  private val defaultFeerateTolerance = FeerateTolerance(0.5, 2.0, FeeratePerKw(2500 sat), DustTolerance(15000 sat, closeOnUpdateFeeOverflow = false))

  test("should update fee when diff ratio exceeded") {
    val feeConf = OnChainFeeConf(defaultFeeTargets, defaultMaxClosingFeerate, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, anchorWithoutHtlcsMaxFee = 10_000.sat, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map.empty)
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1000 sat)))
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(900 sat)))
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1100 sat)))
    assert(feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(899 sat)))
    assert(feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1101 sat)))
  }

  test("get commitment feerate") {
    val commitmentFormat = DefaultCommitmentFormat
    val feeConf = OnChainFeeConf(defaultFeeTargets, defaultMaxClosingFeerate, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, anchorWithoutHtlcsMaxFee = 10_000.sat, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map.empty)

    val feerates1 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = FeeratePerKw(5000 sat))
    assert(feeConf.getCommitmentFeerate(feerates1, randomKey().publicKey, commitmentFormat) == FeeratePerKw(5000 sat))

    val feerates2 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = FeeratePerKw(4000 sat))
    assert(feeConf.getCommitmentFeerate(feerates2, randomKey().publicKey, commitmentFormat) == FeeratePerKw(4000 sat))
  }

  test("get commitment feerate (anchor outputs)") {
    val defaultNodeId = randomKey().publicKey
    val defaultMaxCommitFeerate = defaultFeerateTolerance.anchorOutputMaxCommitFeerate
    val overrideNodeId = randomKey().publicKey
    val overrideMaxCommitFeerate = defaultMaxCommitFeerate * 2
    val feeConf = OnChainFeeConf(defaultFeeTargets, defaultMaxClosingFeerate, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, anchorWithoutHtlcsMaxFee = 10_000.sat, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map(overrideNodeId -> defaultFeerateTolerance.copy(anchorOutputMaxCommitFeerate = overrideMaxCommitFeerate)))

    val feerates1 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate / 2, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates1, defaultNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate / 2)
    assert(feeConf.getCommitmentFeerate(feerates1, defaultNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate / 2)

    val feerates2 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate * 2, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates2, defaultNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates2, defaultNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates2, overrideNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == overrideMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates2, overrideNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == overrideMaxCommitFeerate)

    val feerates3 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate / 2, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates3, defaultNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate / 2)
    assert(feeConf.getCommitmentFeerate(feerates3, defaultNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate / 2)

    val feerates4 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate * 1.5, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates4, defaultNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates4, defaultNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == defaultMaxCommitFeerate)

    val feerates5 = FeeratesPerKw.single(FeeratePerKw(25000 sat)).copy(minimum = FeeratePerKw(10000 sat))
    assert(feeConf.getCommitmentFeerate(feerates5, defaultNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates5, defaultNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates5, overrideNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates5, overrideNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)

    val feerates6 = FeeratesPerKw.single(FeeratePerKw(25000 sat)).copy(minimum = FeeratePerKw(10000 sat))
    assert(feeConf.getCommitmentFeerate(feerates6, defaultNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates6, defaultNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates6, overrideNodeId, UnsafeLegacyAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates6, overrideNodeId, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat) == FeeratePerKw(10000 sat) * 1.25)
  }

  test("get closing feerate") {
    val maxClosingFeerate = FeeratePerKw(2500 sat)
    val feeTargets = FeeTargets(funding = ConfirmationPriority.Medium, closing = ConfirmationPriority.Fast)
    val feeConf = OnChainFeeConf(feeTargets, maxClosingFeerate, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, anchorWithoutHtlcsMaxFee = 10_000.sat, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map.empty)
    val feerates1 = FeeratesPerKw.single(FeeratePerKw(1000 sat)).copy(fast = FeeratePerKw(1500 sat))
    assert(feeConf.getClosingFeerate(feerates1, None) == FeeratePerKw(1500 sat))
    val feerates2 = FeeratesPerKw.single(FeeratePerKw(1000 sat)).copy(fast = FeeratePerKw(500 sat))
    assert(feeConf.getClosingFeerate(feerates2, None) == FeeratePerKw(500 sat))
    val feerates3 = FeeratesPerKw.single(FeeratePerKw(1000 sat)).copy(fast = FeeratePerKw(3000 sat))
    assert(feeConf.getClosingFeerate(feerates3, None) == maxClosingFeerate)
    assert(feeConf.getClosingFeerate(feerates3, maxClosingFeerateOverride_opt = Some(FeeratePerKw(2600 sat))) == FeeratePerKw(2600 sat))
    assert(feeConf.getClosingFeerate(feerates3, maxClosingFeerateOverride_opt = Some(FeeratePerKw(2400 sat))) == FeeratePerKw(2400 sat))
  }

  test("fee difference too high") {
    val tolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 4.0, anchorOutputMaxCommitFeerate = FeeratePerKw(2500 sat), DustTolerance(25000 sat, closeOnUpdateFeeOverflow = false))
    val testCases = Seq(
      (FeeratePerKw(500 sat), FeeratePerKw(500 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(250 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(249 sat), true),
      (FeeratePerKw(500 sat), FeeratePerKw(200 sat), true),
      (FeeratePerKw(249 sat), FeeratePerKw(500 sat), false),
      (FeeratePerKw(250 sat), FeeratePerKw(500 sat), false),
      (FeeratePerKw(250 sat), FeeratePerKw(1000 sat), false),
      (FeeratePerKw(250 sat), FeeratePerKw(1001 sat), true),
      (FeeratePerKw(250 sat), FeeratePerKw(1500 sat), true),
    )
    testCases.foreach { case (networkFeerate, proposedFeerate, expected) =>
      assert(tolerance.isFeeDiffTooHigh(DefaultCommitmentFormat, networkFeerate, proposedFeerate) == expected)
    }
  }

  test("fee difference too high (anchor outputs)") {
    val tolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 4.0, anchorOutputMaxCommitFeerate = FeeratePerKw(2500 sat), DustTolerance(25000 sat, closeOnUpdateFeeOverflow = false))
    val testCases = Seq(
      (FeeratePerKw(500 sat), FeeratePerKw(500 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(1000 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(2000 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(2001 sat), true),
      (FeeratePerKw(2500 sat), FeeratePerKw(10000 sat), false),
      (FeeratePerKw(2500 sat), FeeratePerKw(10001 sat), true),
      (FeeratePerKw(2500 sat), FeeratePerKw(1250 sat), false),
      (FeeratePerKw(2500 sat), FeeratePerKw(1000 sat), false),
      (FeeratePerKw(1000 sat), FeeratePerKw(500 sat), false),
    )
    testCases.foreach { case (networkFeerate, proposedFeerate, expected) =>
      assert(tolerance.isFeeDiffTooHigh(UnsafeLegacyAnchorOutputsCommitmentFormat, networkFeerate, proposedFeerate) == expected)
      assert(tolerance.isFeeDiffTooHigh(ZeroFeeHtlcTxAnchorOutputsCommitmentFormat, networkFeerate, proposedFeerate) == expected)
    }
  }

}
