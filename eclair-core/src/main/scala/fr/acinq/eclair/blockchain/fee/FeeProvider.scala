/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.eclair.feerateKb2Kw

import scala.concurrent.Future

/**
  * Created by PM on 09/07/2017.
  */
trait FeeProvider {

  def getFeerates: Future[FeeratesPerKb]

}

case class FeeratesPerKb(block_1: Long, blocks_2: Long, blocks_6: Long, blocks_12: Long, blocks_36: Long, blocks_72: Long) {
  require(block_1 > 0 && blocks_2 > 0 && blocks_6 > 0 && blocks_12 > 0 && blocks_36 > 0 && blocks_72 > 0, "all feerates must be strictly greater than 0")
}

case class FeeratesPerKw(block_1: Long, blocks_2: Long, blocks_6: Long, blocks_12: Long, blocks_36: Long, blocks_72: Long) {
  require(block_1 > 0 && blocks_2 > 0 && blocks_6 > 0 && blocks_12 > 0 && blocks_36 > 0 && blocks_72 > 0, "all feerates must be strictly greater than 0")

  def getFeerate(unit: FeerateUnit, target: Int) = FeeratesPerKw.getFeerate(this, unit, target)
}

object FeeratesPerKw {
  def apply(feerates: FeeratesPerKb): FeeratesPerKw = FeeratesPerKw(
    block_1 = feerateKb2Kw(feerates.block_1),
    blocks_2 = feerateKb2Kw(feerates.blocks_2),
    blocks_6 = feerateKb2Kw(feerates.blocks_6),
    blocks_12 = feerateKb2Kw(feerates.blocks_12),
    blocks_36 = feerateKb2Kw(feerates.blocks_36),
    blocks_72 = feerateKb2Kw(feerates.blocks_72))

  /**
    * Used in tests
    *
    * @param feeratePerKw
    * @return
    */
  def single(feeratePerKw: Long): FeeratesPerKw = FeeratesPerKw(
    block_1 = feeratePerKw,
    blocks_2 = feeratePerKw,
    blocks_6 = feeratePerKw,
    blocks_12 = feeratePerKw,
    blocks_36 = feeratePerKw,
    blocks_72 = feeratePerKw)

  def getFeerate(feerates: FeeratesPerKw, unit: FeerateUnit, target: Int) : Long = {
    val feeratesPerKw = target match {
      case value if value < 2 => feerates.block_1
      case value if value < 6 => feerates.blocks_2
      case value if value < 12 => feerates.blocks_6
      case value if value < 36 => feerates.blocks_12
      case value if value < 72 => feerates.blocks_36
      case _ => feerates.blocks_72
    }
    val result = unit match {
      case SatoshiPerByte => feeratesPerKw / 256
      case SatoshiPerKb => feeratesPerKw * 4
      case SatoshiPerKw => feeratesPerKw
    }
    result
  }
}

// @formatter:off
sealed trait FeerateUnit
case object SatoshiPerByte extends FeerateUnit
case object SatoshiPerKb extends FeerateUnit
case object SatoshiPerKw extends FeerateUnit
// @formatter:on
