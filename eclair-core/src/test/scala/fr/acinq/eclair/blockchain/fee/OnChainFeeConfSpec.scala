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
import fr.acinq.eclair.channel.ChannelTypes
import fr.acinq.eclair.{TestConstants, randomKey}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

class OnChainFeeConfSpec extends AnyFunSuite {

  private val defaultFeeTargets = FeeTargets(funding = ConfirmationPriority.Medium, closing = ConfirmationPriority.Medium)
  private val defaultFeerateTolerance = FeerateTolerance(0.5, 2.0, FeeratePerKw(2500 sat), DustTolerance(15000 sat, closeOnUpdateFeeOverflow = false))

  test("should update fee when diff ratio exceeded") {
    val feeConf = OnChainFeeConf(defaultFeeTargets, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map.empty)
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1000 sat)))
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(900 sat)))
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1100 sat)))
    assert(feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(899 sat)))
    assert(feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1101 sat)))
  }

  test("get commitment feerate") {
    val channelType = ChannelTypes.Standard()
    val feeConf = OnChainFeeConf(defaultFeeTargets, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map.empty)

    val feerates1 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = FeeratePerKw(5000 sat))
    assert(feeConf.getCommitmentFeerate(feerates1, randomKey().publicKey, channelType, 100000 sat) == FeeratePerKw(5000 sat))

    val feerates2 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = FeeratePerKw(4000 sat))
    assert(feeConf.getCommitmentFeerate(feerates2, randomKey().publicKey, channelType, 100000 sat) == FeeratePerKw(4000 sat))
  }

  test("get commitment feerate (anchor outputs)") {
    val defaultNodeId = randomKey().publicKey
    val defaultMaxCommitFeerate = defaultFeerateTolerance.anchorOutputMaxCommitFeerate
    val overrideNodeId = randomKey().publicKey
    val overrideMaxCommitFeerate = defaultMaxCommitFeerate * 2
    val feeConf = OnChainFeeConf(defaultFeeTargets, safeUtxosThreshold = 0, spendAnchorWithoutHtlcs = true, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, defaultFeerateTolerance, Map(overrideNodeId -> defaultFeerateTolerance.copy(anchorOutputMaxCommitFeerate = overrideMaxCommitFeerate)))

    val feerates1 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate / 2, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates1, defaultNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == defaultMaxCommitFeerate / 2)
    assert(feeConf.getCommitmentFeerate(feerates1, defaultNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == defaultMaxCommitFeerate / 2)

    val feerates2 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate * 2, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates2, defaultNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates2, defaultNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates2, overrideNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == overrideMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates2, overrideNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == overrideMaxCommitFeerate)

    val feerates3 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate / 2, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates3, defaultNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == defaultMaxCommitFeerate / 2)
    assert(feeConf.getCommitmentFeerate(feerates3, defaultNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == defaultMaxCommitFeerate / 2)

    val feerates4 = FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(fast = defaultMaxCommitFeerate * 1.5, minimum = FeeratePerKw(250 sat))
    assert(feeConf.getCommitmentFeerate(feerates4, defaultNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(feerates4, defaultNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == defaultMaxCommitFeerate)

    val feerates5 = FeeratesPerKw.single(FeeratePerKw(25000 sat)).copy(minimum = FeeratePerKw(10000 sat))
    assert(feeConf.getCommitmentFeerate(feerates5, defaultNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates5, defaultNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates5, overrideNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates5, overrideNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)

    val feerates6 = FeeratesPerKw.single(FeeratePerKw(25000 sat)).copy(minimum = FeeratePerKw(10000 sat))
    assert(feeConf.getCommitmentFeerate(feerates6, defaultNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates6, defaultNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates6, overrideNodeId, ChannelTypes.AnchorOutputs(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
    assert(feeConf.getCommitmentFeerate(feerates6, overrideNodeId, ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), 100000 sat) == FeeratePerKw(10000 sat) * 1.25)
  }

  test("fee difference too high") {
    val tolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 4.0, anchorOutputMaxCommitFeerate = FeeratePerKw(2500 sat), DustTolerance(25000 sat, closeOnUpdateFeeOverflow = false))
    val channelType = ChannelTypes.Standard()
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
      assert(tolerance.isFeeDiffTooHigh(channelType, networkFeerate, proposedFeerate) == expected)
    }
  }

  test("fee difference too high (anchor outputs)") {
    val tolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 4.0, anchorOutputMaxCommitFeerate = FeeratePerKw(2500 sat), DustTolerance(25000 sat, closeOnUpdateFeeOverflow = false))
    val testCases = Seq(
      (FeeratePerKw(500 sat), FeeratePerKw(500 sat)),
      (FeeratePerKw(500 sat), FeeratePerKw(2500 sat)),
      (FeeratePerKw(500 sat), FeeratePerKw(10000 sat)),
      (FeeratePerKw(500 sat), FeeratePerKw(10001 sat)),
      (FeeratePerKw(2500 sat), FeeratePerKw(10000 sat)),
      (FeeratePerKw(2500 sat), FeeratePerKw(10001 sat)),
      (FeeratePerKw(2500 sat), FeeratePerKw(1250 sat)),
      (FeeratePerKw(2500 sat), FeeratePerKw(1249 sat)),
      (FeeratePerKw(2500 sat), FeeratePerKw(1000 sat)),
      (FeeratePerKw(1000 sat), FeeratePerKw(500 sat)),
      (FeeratePerKw(1000 sat), FeeratePerKw(499 sat)),
    )
    testCases.foreach { case (networkFeerate, proposedFeerate) =>
      assert(!tolerance.isFeeDiffTooHigh(ChannelTypes.AnchorOutputs(), networkFeerate, proposedFeerate))
      assert(!tolerance.isFeeDiffTooHigh(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), networkFeerate, proposedFeerate))
    }
  }

}
