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

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FeeratesPerByteSpec extends FunSuite {
  test("ensure minimum feerate") {
    val feeratesPerByte = FeeratesPerByte.enforceMinimumFeerate(FeeratesPerByte(0, 0, 0, 0, 0, 0), 1)
    assert(feeratesPerByte.block_1 == 1)
    assert(feeratesPerByte.blocks_2 == 1)
    assert(feeratesPerByte.blocks_6 == 1)
    assert(feeratesPerByte.blocks_12 == 1)
    assert(feeratesPerByte.blocks_36 == 1)
    assert(feeratesPerByte.blocks_72 == 1)
  }

  test("check when converting to fee rate per kiloweight") {
    intercept[RuntimeException] {
      val feeratesPerKw = FeeratesPerKw(FeeratesPerByte(0, 0, 0, 0, 0, 0))
    }
  }
}
