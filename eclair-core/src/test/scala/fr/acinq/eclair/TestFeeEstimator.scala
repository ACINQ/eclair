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

import fr.acinq.eclair.TestConstants.feeratePerKw
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeratePerKB, FeeratePerKw, FeeratesPerKw}

class TestFeeEstimator(initialFeerate: FeeratePerKw = feeratePerKw) extends FeeEstimator {
  private var currentFeerates = FeeratesPerKw.single(initialFeerate)

  // @formatter:off
  override def getFeeratePerKb(target: Int): FeeratePerKB = FeeratePerKB(currentFeerates.feePerBlock(target))
  override def getFeeratePerKw(target: Int): FeeratePerKw = currentFeerates.feePerBlock(target)
  override def getMempoolMinFeeratePerKw(): FeeratePerKw = currentFeerates.mempoolMinFee
  // @formatter:on

  def setFeerate(target: Int, feerate: FeeratePerKw): Unit = {
    target match {
      case 1 => currentFeerates = currentFeerates.copy(block_1 = feerate)
      case 2 => currentFeerates = currentFeerates.copy(blocks_2 = feerate)
      case t if t <= 6 => currentFeerates = currentFeerates.copy(blocks_6 = feerate)
      case t if t <= 12 => currentFeerates = currentFeerates.copy(blocks_12 = feerate)
      case t if t <= 36 => currentFeerates = currentFeerates.copy(blocks_36 = feerate)
      case t if t <= 72 => currentFeerates = currentFeerates.copy(blocks_72 = feerate)
      case t if t <= 144 => currentFeerates = currentFeerates.copy(blocks_144 = feerate)
      case _ => currentFeerates = currentFeerates.copy(blocks_1008 = feerate)
    }
  }

  def setFeerate(feeratesPerKw: FeeratesPerKw): Unit = {
    currentFeerates = feeratesPerKw
  }
}
