/*
 * Copyright 2020 ACINQ SAS
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
import fr.acinq.eclair.blockchain.fee.FeeratePerKw.MinimumFeeratePerKw
import org.scalatest.funsuite.AnyFunSuite

class FeeProviderSpec extends AnyFunSuite {

  test("convert fee rates") {
    assert(FeeratePerByte(FeeratePerKw(2000 sat)) == FeeratePerByte(8 sat))
    assert(FeeratePerKB(FeeratePerByte(10 sat)) == FeeratePerKB(10000 sat))
    assert(FeeratePerKB(FeeratePerKw(25 sat)) == FeeratePerKB(100 sat))
    assert(FeeratePerKw(FeeratePerKB(10000 sat)) == FeeratePerKw(2500 sat))
    assert(FeeratePerKw(FeeratePerByte(10 sat)) == FeeratePerKw(2500 sat))
  }

  test("enforce a minimum feerate-per-kw") {
    assert(FeeratePerKw(FeeratePerKB(1000 sat)) == MinimumFeeratePerKw)
    assert(FeeratePerKw(FeeratePerKB(500 sat)) == MinimumFeeratePerKw)
    assert(FeeratePerKw(FeeratePerByte(1 sat)) == MinimumFeeratePerKw)
  }

  test("compare feerates") {
    assert(FeeratePerKw(500 sat) > FeeratePerKw(499 sat))
    assert(FeeratePerKw(500 sat) >= FeeratePerKw(500 sat))
    assert(FeeratePerKw(499 sat) < FeeratePerKw(500 sat))
    assert(FeeratePerKw(500 sat) <= FeeratePerKw(500 sat))
    assert(FeeratePerKw(500 sat).max(FeeratePerKw(499 sat)) == FeeratePerKw(500 sat))
    assert(FeeratePerKw(499 sat).max(FeeratePerKw(500 sat)) == FeeratePerKw(500 sat))
    assert(FeeratePerKw(500 sat).min(FeeratePerKw(499 sat)) == FeeratePerKw(499 sat))
    assert(FeeratePerKw(499 sat).min(FeeratePerKw(500 sat)) == FeeratePerKw(499 sat))
  }

  test("numeric operations") {
    assert(FeeratePerKw(100 sat) * 0.4 == FeeratePerKw(40 sat))
    assert(FeeratePerKw(501 sat) * 0.5 == FeeratePerKw(250 sat))
    assert(FeeratePerKw(5 sat) * 5 == FeeratePerKw(25 sat))
    assert(FeeratePerKw(100 sat) / 10 == FeeratePerKw(10 sat))
    assert(FeeratePerKw(101 sat) / 10 == FeeratePerKw(10 sat))
    assert(FeeratePerKw(100 sat) + FeeratePerKw(50 sat) == FeeratePerKw(150 sat))
  }

}
