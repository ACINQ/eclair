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

import org.scalatest.{FunSuite, ParallelTestExecution}

/**
 * Created by t-bast on 21/08/2019.
 */

class CltvExpirySpec extends FunSuite with ParallelTestExecution {

  test("cltv expiry delta") {
    val d = CltvExpiryDelta(561)
    assert(d.toInt === 561)

    // add
    assert(d + 5 === CltvExpiryDelta(566))
    assert(d + CltvExpiryDelta(5) === CltvExpiryDelta(566))

    // compare
    assert(d <= CltvExpiryDelta(561))
    assert(d < CltvExpiryDelta(562))
    assert(d >= CltvExpiryDelta(561))
    assert(d > CltvExpiryDelta(560))

    // convert to cltv expiry
    assert(d.toCltvExpiry(blockHeight = 1105) === CltvExpiry(1666))
    assert(d.toCltvExpiry(blockHeight = 1106) === CltvExpiry(1667))
  }

  test("cltv expiry") {
    val e = CltvExpiry(1105)
    assert(e.toLong === 1105)

    // add
    assert(e + CltvExpiryDelta(561) === CltvExpiry(1666))
    assert(e - CltvExpiryDelta(561) === CltvExpiry(544))
    assert(e - CltvExpiry(561) === CltvExpiryDelta(544))

    // compare
    assert(e <= CltvExpiry(1105))
    assert(e < CltvExpiry(1106))
    assert(e >= CltvExpiry(1105))
    assert(e > CltvExpiry(1104))
  }

}
