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

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.MilliSatoshi.{maxOf, minOf}
import org.scalatest.FunSuite

/**
 * Created by t-bast on 22/08/2019.
 */

class MilliSatoshiSpec extends FunSuite {

  test("millisatoshi numeric operations") {
    // add
    assert(MilliSatoshi(561) + 0.msat === MilliSatoshi(561))
    assert(MilliSatoshi(561) + 0.sat === MilliSatoshi(561))
    assert(MilliSatoshi(561) + 1105.msat === MilliSatoshi(1666))
    assert(MilliSatoshi(2000) + 3.sat === MilliSatoshi(5000))

    // subtract
    assert(MilliSatoshi(561) - 0.msat === MilliSatoshi(561))
    assert(MilliSatoshi(1105) - 561.msat === MilliSatoshi(544))
    assert(561.msat - 1105.msat === -MilliSatoshi(544))
    assert(MilliSatoshi(561) - 1105.msat === -MilliSatoshi(544))
    assert(MilliSatoshi(1105) - 1.sat === MilliSatoshi(105))

    // multiply
    assert(MilliSatoshi(561) * 1 === 561.msat)
    assert(MilliSatoshi(561) * 2 === 1122.msat)
    assert(MilliSatoshi(561) * 2.5 === 1402.msat)

    // divide
    assert(MilliSatoshi(561) / 1 === MilliSatoshi(561))
    assert(MilliSatoshi(561) / 2 === MilliSatoshi(280))

    // compare
    assert(MilliSatoshi(561) <= MilliSatoshi(561))
    assert(MilliSatoshi(561) <= 1105.msat)
    assert(MilliSatoshi(561) < MilliSatoshi(1105))
    assert(MilliSatoshi(561) >= MilliSatoshi(561))
    assert(MilliSatoshi(1105) >= MilliSatoshi(561))
    assert(MilliSatoshi(1105) > MilliSatoshi(561))
    assert(MilliSatoshi(1000) <= Satoshi(1))
    assert(MilliSatoshi(1000) <= 2.sat)
    assert(MilliSatoshi(1000) < Satoshi(2))
    assert(MilliSatoshi(1000) >= Satoshi(1))
    assert(MilliSatoshi(2000) >= Satoshi(1))
    assert(MilliSatoshi(2000) > Satoshi(1))

    // maxOf
    assert(maxOf(561 msat, 1105 msat) === MilliSatoshi(1105))
    assert(maxOf(1 sat, 1105 msat) === MilliSatoshi(1105))
    assert(maxOf(1105 msat, 2 sat) === MilliSatoshi(2000))
    assert(maxOf(1 sat, 2 sat) === Satoshi(2))

    // minOf
    assert(minOf(561 msat, 1105 msat) === MilliSatoshi(561))
    assert(minOf(1 sat, 1105 msat) === MilliSatoshi(1000))
    assert(minOf(1105 msat, 2 sat) === MilliSatoshi(1105))
    assert(minOf(1 sat, 2 sat) === Satoshi(1))
  }

}
