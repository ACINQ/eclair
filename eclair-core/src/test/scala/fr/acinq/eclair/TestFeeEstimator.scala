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

package fr.acinq.eclair

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.eclair.TestConstants.feeratePerKw
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeratePerKB, FeeratePerKw, FeeratesPerKw}

class TestFeeEstimator extends FeeEstimator {
  private var currentFeerates = FeeratesPerKw.single(feeratePerKw)

  // @formatter:off
  override def getFeeratePerKb(target: Int): FeeratePerKB = FeeratePerKB(currentFeerates.feePerBlock(target))
  override def getFeeratePerKw(target: Int): FeeratePerKw = currentFeerates.feePerBlock(target)
  override def getMempoolMinFeeratePerKw(): FeeratePerKw = currentFeerates.mempoolMinFee
  // @formatter:on

  def setFeerate(target: Int, feerate: FeeratePerKw): Unit = {
    currentFeerates = currentFeerates
      .modify(_.block_1).setToIf(target == 1)(feerate)
      .modify(_.blocks_2).setToIf(target == 2)(feerate)
      .modify(_.blocks_6).setToIf(target <= 6)(feerate)
      .modify(_.blocks_12).setToIf(target <= 12)(feerate)
      .modify(_.blocks_36).setToIf(target <= 36)(feerate)
      .modify(_.blocks_72).setToIf(target <= 72)(feerate)
      .modify(_.blocks_144).setToIf(target <= 144)(feerate)
      .modify(_.blocks_1008).setToIf(target > 144)(feerate)
  }

  def setFeerate(feeratesPerKw: FeeratesPerKw): Unit = {
    currentFeerates = feeratesPerKw
  }
}
