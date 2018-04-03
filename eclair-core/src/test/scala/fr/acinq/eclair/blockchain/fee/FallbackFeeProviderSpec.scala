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

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class FallbackFeeProviderSpec extends FunSuite {

  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    * This provider returns a constant value, but fails after ttl tries
    *
    * @param ttl
    * @param feeratesPerByte
    */
  class FailingFeeProvider(ttl: Int, val feeratesPerByte: FeeratesPerByte) extends FeeProvider {
    var i = 0

    override def getFeerates: Future[FeeratesPerByte] =
      if (i < ttl) {
        i = i + 1
        Future.successful(feeratesPerByte)
      } else Future.failed(new RuntimeException())
  }

  def dummyFeerates = FeeratesPerByte(Random.nextInt(10000), Random.nextInt(10000), Random.nextInt(10000), Random.nextInt(10000), Random.nextInt(10000), Random.nextInt(10000))

  def await[T](f: Future[T]): T = Await.result(f, 3 seconds)

  test("fee provider failover") {
    val provider0 = new FailingFeeProvider(-1, dummyFeerates) // always fails
    val provider1 = new FailingFeeProvider(1, dummyFeerates) // fails after 1 try
    val provider3 = new FailingFeeProvider(3, dummyFeerates) // fails after 3 tries
    val provider5 = new FailingFeeProvider(5, dummyFeerates) // fails after 5 tries
    val provider7 = new FailingFeeProvider(Int.MaxValue, dummyFeerates) // "never" fails

    val fallbackFeeProvider = new FallbackFeeProvider(provider0 :: provider1 :: provider3 :: provider5 :: provider7 :: Nil, 1)

    assert(await(fallbackFeeProvider.getFeerates) === provider1.feeratesPerByte)

    assert(await(fallbackFeeProvider.getFeerates) === provider3.feeratesPerByte)
    assert(await(fallbackFeeProvider.getFeerates) === provider3.feeratesPerByte)
    assert(await(fallbackFeeProvider.getFeerates) === provider3.feeratesPerByte)

    assert(await(fallbackFeeProvider.getFeerates) === provider5.feeratesPerByte)
    assert(await(fallbackFeeProvider.getFeerates) === provider5.feeratesPerByte)
    assert(await(fallbackFeeProvider.getFeerates) === provider5.feeratesPerByte)
    assert(await(fallbackFeeProvider.getFeerates) === provider5.feeratesPerByte)
    assert(await(fallbackFeeProvider.getFeerates) === provider5.feeratesPerByte)

    assert(await(fallbackFeeProvider.getFeerates) === provider7.feeratesPerByte)

  }

  test("ensure minimum feerate") {
    val constantFeeProvider = new ConstantFeeProvider(FeeratesPerByte(1, 1, 1, 1, 1, 1))
    val fallbackFeeProvider = new FallbackFeeProvider(constantFeeProvider :: Nil, 2)
    assert(await(fallbackFeeProvider.getFeerates) === FeeratesPerByte(2, 2, 2, 2, 2, 2))
  }


}
