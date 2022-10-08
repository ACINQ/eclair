/*
 * Copyright 2022 ACINQ SAS
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
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await

class ConstantFeeProviderSpec extends AnyFunSuite {

  test("constant fee rates") {
    val feerates =
      FeeratesPerKB(FeeratePerKB(50 sat), FeeratePerKB(100 sat), FeeratePerKB(200 sat), FeeratePerKB(300 sat), FeeratePerKB(400 sat), FeeratePerKB(500 sat), FeeratePerKB(600 sat), FeeratePerKB(650 sat), FeeratePerKB(700 sat))

    val constantProvider = ConstantFeeProvider(feerates)
    val f = for {
      rate1 <- constantProvider.getFeerates
      rate2 <- constantProvider.getFeerates
      rate3 <- constantProvider.getFeerates
    } yield (rate1, rate2, rate3)

    val (rate1, rate2, rate3) = Await.result(f, 1 seconds)
    assert(rate1 == rate2)
    assert(rate2 == rate3)
  }

}
