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

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class FallbackFeeProviderSpec extends AnyFunSuite {

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
   * This provider returns a constant value, but fails after ttl tries
   */
  class FailingFeeProvider(ttl: Int, val feeratesPerKB: FeeratesPerKB) extends FeeProvider {
    var i = 0

    override def getFeerates: Future[FeeratesPerKB] =
      if (i < ttl) {
        i = i + 1
        Future.successful(feeratesPerKB)
      } else Future.failed(new RuntimeException())
  }

  def dummyFeerate = FeeratePerKB(1000.sat + Random.nextInt(10000).sat)

  def dummyFeerates = FeeratesPerKB(dummyFeerate, dummyFeerate, dummyFeerate, dummyFeerate, dummyFeerate, dummyFeerate, dummyFeerate, dummyFeerate, dummyFeerate)

  def await[T](f: Future[T]): T = Await.result(f, 3 seconds)

  test("fee provider failover") {
    val provider0 = new FailingFeeProvider(-1, dummyFeerates) // always fails
    val provider1 = new FailingFeeProvider(1, dummyFeerates) // fails after 1 try
    val provider3 = new FailingFeeProvider(3, dummyFeerates) // fails after 3 tries
    val provider5 = new FailingFeeProvider(5, dummyFeerates) // fails after 5 tries
    val provider7 = new FailingFeeProvider(Int.MaxValue, dummyFeerates) // "never" fails

    val fallbackFeeProvider = new FallbackFeeProvider(provider0 :: provider1 :: provider3 :: provider5 :: provider7 :: Nil, FeeratePerByte(1 sat))

    assert(await(fallbackFeeProvider.getFeerates) == provider1.feeratesPerKB)

    assert(await(fallbackFeeProvider.getFeerates) == provider3.feeratesPerKB)
    assert(await(fallbackFeeProvider.getFeerates) == provider3.feeratesPerKB)
    assert(await(fallbackFeeProvider.getFeerates) == provider3.feeratesPerKB)

    assert(await(fallbackFeeProvider.getFeerates) == provider5.feeratesPerKB)
    assert(await(fallbackFeeProvider.getFeerates) == provider5.feeratesPerKB)
    assert(await(fallbackFeeProvider.getFeerates) == provider5.feeratesPerKB)
    assert(await(fallbackFeeProvider.getFeerates) == provider5.feeratesPerKB)
    assert(await(fallbackFeeProvider.getFeerates) == provider5.feeratesPerKB)

    assert(await(fallbackFeeProvider.getFeerates) == provider7.feeratesPerKB)
  }

  test("ensure minimum feerate") {
    val constantFeeProvider = ConstantFeeProvider(FeeratesPerKB(FeeratePerKB(64000 sat), FeeratePerKB(32000 sat), FeeratePerKB(16000 sat), FeeratePerKB(8000 sat), FeeratePerKB(4000 sat), FeeratePerKB(2000 sat), FeeratePerKB(1500 sat), FeeratePerKB(1000 sat), FeeratePerKB(1000 sat)))
    val minFeeratePerByte = FeeratePerByte(2 sat)
    val minFeeratePerKB = FeeratePerKB(minFeeratePerByte)
    val fallbackFeeProvider = new FallbackFeeProvider(constantFeeProvider :: Nil, minFeeratePerByte)
    assert(await(fallbackFeeProvider.getFeerates) == FeeratesPerKB(FeeratePerKB(64000 sat), FeeratePerKB(32000 sat), FeeratePerKB(16000 sat), FeeratePerKB(8000 sat), FeeratePerKB(4000 sat), minFeeratePerKB, minFeeratePerKB, minFeeratePerKB, minFeeratePerKB))
  }

}
