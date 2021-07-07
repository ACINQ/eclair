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

import fr.acinq.bitcoin.SatoshiLong
import fr.acinq.eclair.TestConstants.TestFeeEstimator
import fr.acinq.eclair.blockchain.CurrentFeerates
import fr.acinq.eclair.channel.{ChannelFeatures, ChannelTypes}
import fr.acinq.eclair.{FeatureSupport, Features, randomKey}
import org.scalatest.funsuite.AnyFunSuite

class FeeEstimatorSpec extends AnyFunSuite {

  test("should update fee when diff ratio exceeded") {
    val feeConf = OnChainFeeConf(FeeTargets(1, 1, 1, 1), new TestFeeEstimator(), closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, FeerateTolerance(0.5, 2.0, FeeratePerKw(2500 sat)), Map.empty)
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1000 sat)))
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(900 sat)))
    assert(!feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1100 sat)))
    assert(feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(899 sat)))
    assert(feeConf.shouldUpdateFee(FeeratePerKw(1000 sat), FeeratePerKw(1101 sat)))
  }

  test("get commitment feerate") {
    val feeEstimator = new TestFeeEstimator()
    val channelFeatures = ChannelFeatures(Features.empty)
    val feeConf = OnChainFeeConf(FeeTargets(1, 2, 1, 1), feeEstimator, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, FeerateTolerance(0.5, 2.0, FeeratePerKw(2500 sat)), Map.empty)

    feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = FeeratePerKw(5000 sat)))
    assert(feeConf.getCommitmentFeerate(randomKey().publicKey, channelFeatures, 100000 sat, None) === FeeratePerKw(5000 sat))

    val currentFeerates = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = FeeratePerKw(4000 sat)))
    assert(feeConf.getCommitmentFeerate(randomKey().publicKey, channelFeatures, 100000 sat, Some(currentFeerates)) === FeeratePerKw(4000 sat))
  }

  test("get commitment feerate (anchor outputs)") {
    val feeEstimator = new TestFeeEstimator()
    val channelFeatures = ChannelFeatures(ChannelTypes.AnchorOutputs.features)
    val defaultNodeId = randomKey().publicKey
    val defaultMaxCommitFeerate = FeeratePerKw(2500 sat)
    val overrideNodeId = randomKey().publicKey
    val overrideMaxCommitFeerate = defaultMaxCommitFeerate * 2
    val feeConf = OnChainFeeConf(FeeTargets(1, 2, 1, 1), feeEstimator, closeOnOfflineMismatch = true, updateFeeMinDiffRatio = 0.1, FeerateTolerance(0.5, 2.0, defaultMaxCommitFeerate), Map(overrideNodeId -> FeerateTolerance(0.5, 2.0, overrideMaxCommitFeerate)))

    feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = defaultMaxCommitFeerate / 2))
    assert(feeConf.getCommitmentFeerate(defaultNodeId, channelFeatures, 100000 sat, None) === defaultMaxCommitFeerate / 2)

    feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = defaultMaxCommitFeerate * 2))
    assert(feeConf.getCommitmentFeerate(defaultNodeId, channelFeatures, 100000 sat, None) === defaultMaxCommitFeerate)
    assert(feeConf.getCommitmentFeerate(overrideNodeId, channelFeatures, 100000 sat, None) === overrideMaxCommitFeerate)

    val currentFeerates1 = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = defaultMaxCommitFeerate / 2))
    assert(feeConf.getCommitmentFeerate(defaultNodeId, channelFeatures, 100000 sat, Some(currentFeerates1)) === defaultMaxCommitFeerate / 2)

    val currentFeerates2 = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = defaultMaxCommitFeerate * 1.5))
    feeEstimator.setFeerate(FeeratesPerKw.single(FeeratePerKw(10000 sat)).copy(blocks_2 = defaultMaxCommitFeerate / 2))
    assert(feeConf.getCommitmentFeerate(defaultNodeId, channelFeatures, 100000 sat, Some(currentFeerates2)) === defaultMaxCommitFeerate)
  }

  test("fee difference too high") {
    val tolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 4.0, anchorOutputMaxCommitFeerate = FeeratePerKw(2500 sat))
    val channelFeatures = ChannelFeatures(Features.empty)
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
      assert(tolerance.isFeeDiffTooHigh(channelFeatures, networkFeerate, proposedFeerate) === expected)
    }
  }

  test("fee difference too high (anchor outputs)") {
    val tolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 4.0, anchorOutputMaxCommitFeerate = FeeratePerKw(2500 sat))
    val channelFeatures = ChannelFeatures(ChannelTypes.AnchorOutputs.features)
    val testCases = Seq(
      (FeeratePerKw(500 sat), FeeratePerKw(500 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(2500 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(10000 sat), false),
      (FeeratePerKw(500 sat), FeeratePerKw(10001 sat), true),
      (FeeratePerKw(2500 sat), FeeratePerKw(10000 sat), false),
      (FeeratePerKw(2500 sat), FeeratePerKw(10001 sat), true),
      (FeeratePerKw(2500 sat), FeeratePerKw(1250 sat), false),
      (FeeratePerKw(2500 sat), FeeratePerKw(1249 sat), true),
      (FeeratePerKw(2500 sat), FeeratePerKw(1000 sat), true),
      (FeeratePerKw(1000 sat), FeeratePerKw(500 sat), false),
      (FeeratePerKw(1000 sat), FeeratePerKw(499 sat), true),
    )
    testCases.foreach { case (networkFeerate, proposedFeerate, expected) =>
      assert(tolerance.isFeeDiffTooHigh(channelFeatures, networkFeerate, proposedFeerate) === expected)
    }
  }

}
