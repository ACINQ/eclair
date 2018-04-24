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
class ConversionSpec extends FunSuite {
  test("feerate conversions") {
    val kb2sat = Map(1 -> 1200 ,2 -> 1500, 6 -> 2000, 12 -> 2500, 36 -> 3000, 72 -> 5000)
    val feeratesPerKb = FeeratesPerKb(kb2sat(1), kb2sat(2), kb2sat(6), kb2sat(12), kb2sat(36), kb2sat(72))
    val feeratesPerKw = FeeratesPerKw(feeratesPerKb)

    assert(feeratesPerKw == FeeratesPerKw(kb2sat(1) / 4, kb2sat(2) / 4, kb2sat(6) / 4, kb2sat(12) / 4, kb2sat(36) / 4, kb2sat(72) / 4))
    for(target <- kb2sat.keys) {
      val feeratePerKb = FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerKb, target)
      assert(feeratePerKb == kb2sat(target))
      val feeratePerKw = FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerKw, target)
      assert(feeratePerKw == feeratePerKb / 4)
      val feeratePerByte = FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerByte, target)
      assert(feeratePerByte == feeratePerKb / 1024)

      if (target >= 2) {
        assert(FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerKb, target + 1) == FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerKb, target))
        assert(FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerKb, target + 2) == FeeratesPerKw.getFeerate(feeratesPerKw, SatoshiPerKb, target))
      }
    }
  }
}
