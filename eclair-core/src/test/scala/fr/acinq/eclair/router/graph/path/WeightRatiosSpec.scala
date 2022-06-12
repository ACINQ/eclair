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

package fr.acinq.eclair.router.graph.path

import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import org.scalatest.funsuite.AnyFunSuite
import fr.acinq.eclair.MilliSatoshiLong

class WeightRatiosSpec extends AnyFunSuite {

  private val RELAY_FEES = RelayFees(2 msat, 1)

  test("Cannot construct when weight ratios do not sum to one") {
    intercept[IllegalArgumentException] {
      WeightRatios(0.1, 0.2, 0.3, 0, RELAY_FEES)
    }
  }

  test("Cannot construct if any of the weights < 0") {
    intercept[IllegalArgumentException] {
      WeightRatios(-0.1, 0.9, 0.0, 0.0, RELAY_FEES)
    }
    intercept[IllegalArgumentException] {
      WeightRatios(0.0, -0.1, 0.9, 0.0, RELAY_FEES)
    }
    intercept[IllegalArgumentException] {
      WeightRatios(0.0, 0.0, -0.1, 0.9, RELAY_FEES)
    }
    intercept[IllegalArgumentException] {
      WeightRatios(0.9, 0.0, 0.0, -0.1, RELAY_FEES)
    }
  }

  test("Typical construction") {
    val weightRatios = WeightRatios(0.1, 0.2, 0.3, 0.4, RELAY_FEES)
    assert(weightRatios.toString == "WeightRatios(0.1,0.2,0.3,0.4,RelayFees(2 msat,1))")
  }

  test("calculateWeightedFactor when baseWeight is 1") {
    val weightRatios = WeightRatios(baseWeight = 1.0, 0.0, 0.0, 0.0, RELAY_FEES)

    assert(weightRatios.calculateWeightedFactor(cltvFactor = 10.0, ageFactor = 20.0, capFactor = 30.0) == 1.0)
  }

  test("calculateWeightedFactor when cltvDeltaWeight is 1") {
    val weightRatios = WeightRatios(0.0, cltvDeltaWeight = 1.0, 0.0, 0.0, RELAY_FEES)

    assert(weightRatios.calculateWeightedFactor(cltvFactor = 10.0, ageFactor = 20.0, capFactor = 30.0) == 10.0)
  }

  test("calculateWeightedFactor when ageWeight is 1") {
    val weightRatios = WeightRatios(0.0, 0.0, 1.0, 0.0, RELAY_FEES)

    assert(weightRatios.calculateWeightedFactor(cltvFactor = 10.0, ageFactor = 20.0, capFactor = 30.0) == 20.0)
  }

  test("calculateWeightedFactor when capacityWeight is 1") {
    val weightRatios = WeightRatios(0.0, 0.0, 0.0, 1.0, RELAY_FEES)

    assert(weightRatios.calculateWeightedFactor(cltvFactor = 10.0, ageFactor = 20.0, capFactor = 30.0) == 30.0)
  }

  test("calculateWeightedFactor for typical WeightRatios") {
    val weightRatios = WeightRatios(0.1, 0.2, 0.3, 0.4, RELAY_FEES)

    assert(weightRatios.calculateWeightedFactor(cltvFactor = 100.0, ageFactor = 10.0, capFactor = 10.0) == 27.1)
    assert(weightRatios.calculateWeightedFactor(cltvFactor = 10.0, ageFactor = 100.0, capFactor = 10.0) == 36.1)
    assert(weightRatios.calculateWeightedFactor(cltvFactor = 10.0, ageFactor = 10.0, capFactor = 100.0) == 45.1)
    assert(weightRatios.calculateWeightedFactor(cltvFactor = 100.0, ageFactor = 100.0, capFactor = 100.0) == 90.1)
  }
}
