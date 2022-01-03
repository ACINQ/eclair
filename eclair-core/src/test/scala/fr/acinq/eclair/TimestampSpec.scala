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

import org.scalatest.funsuite.AnyFunSuite


class TimestampSpec extends AnyFunSuite {

  test("timestamp boundaries") {
    assert(TimestampSecond.max.toLong == 253402300799L)
    assert(TimestampSecond.min.toLong == 0)
    assert(TimestampMilli.max.toLong == 253402300799L * 1000)
    assert(TimestampMilli.min.toLong == 0)

    intercept[IllegalArgumentException] {
      TimestampSecond(253402300799L + 1)
    }

    intercept[IllegalArgumentException] {
      TimestampSecond(-1)
    }

    intercept[IllegalArgumentException] {
      TimestampMilli(-1)
    }
  }

}