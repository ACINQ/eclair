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

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class SmoothFeeProviderSpec extends AnyFunSuite {

  test("smooth fee rates") {
    val rates = Array(
      FeeratesPerKB(minimum = FeeratePerKB(50 sat), fastest = FeeratePerKB(700 sat), fast = FeeratePerKB(650 sat), medium = FeeratePerKB(500 sat), slow = FeeratePerKB(400 sat)),
      FeeratesPerKB(minimum = FeeratePerKB(60 sat), fastest = FeeratePerKB(800 sat), fast = FeeratePerKB(750 sat), medium = FeeratePerKB(600 sat), slow = FeeratePerKB(500 sat)),
      FeeratesPerKB(minimum = FeeratePerKB(70 sat), fastest = FeeratePerKB(900 sat), fast = FeeratePerKB(850 sat), medium = FeeratePerKB(700 sat), slow = FeeratePerKB(600 sat)),
      FeeratesPerKB(minimum = FeeratePerKB(70 sat), fastest = FeeratePerKB(900 sat), fast = FeeratePerKB(850 sat), medium = FeeratePerKB(700 sat), slow = FeeratePerKB(600 sat)),
      FeeratesPerKB(minimum = FeeratePerKB(70 sat), fastest = FeeratePerKB(900 sat), fast = FeeratePerKB(850 sat), medium = FeeratePerKB(700 sat), slow = FeeratePerKB(600 sat))
    )
    val provider: FeeProvider = new FeeProvider {
      var index = 0

      override def getFeerates: Future[FeeratesPerKB] = {
        val rate = rates(index)
        index = (index + 1) % rates.length
        Future.successful(rate)
      }
    }

    val smoothProvider = new SmoothFeeProvider(provider, windowSize = 3)
    val f = for {
      rate1 <- smoothProvider.getFeerates
      rate2 <- smoothProvider.getFeerates
      rate3 <- smoothProvider.getFeerates
      rate4 <- smoothProvider.getFeerates
      rate5 <- smoothProvider.getFeerates
    } yield (rate1, rate2, rate3, rate4, rate5)

    val (rate1, rate2, rate3, rate4, rate5) = Await.result(f, 5 seconds)
    assert(rate1 == rates(0))
    assert(rate2 == SmoothFeeProvider.smooth(Seq(rates(0), rates(1))))
    assert(rate3 == SmoothFeeProvider.smooth(Seq(rates(0), rates(1), rates(2))))
    assert(rate3 == FeeratesPerKB(minimum = FeeratePerKB(60 sat), fastest = FeeratePerKB(800 sat), fast = FeeratePerKB(750 sat), medium = FeeratePerKB(600 sat), slow = FeeratePerKB(500 sat)))
    assert(rate4 == SmoothFeeProvider.smooth(Seq(rates(1), rates(2), rates(3))))
    assert(rate5 == rates(4)) // since the last 3 values are the same
  }

}
