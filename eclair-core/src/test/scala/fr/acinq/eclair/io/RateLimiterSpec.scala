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

package fr.acinq.eclair.io

import akka.testkit.TestProbe
import fr.acinq.eclair._
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration._

class RateLimiterSpec extends TestKitBaseClass with AnyFunSuiteLike {
  def sleep(duration: FiniteDuration): Unit = {
    val probe = TestProbe()
    system.scheduler.scheduleOnce(duration, probe.ref, ())(system.dispatcher)
    probe.expectMsg(())
  }

  test("can burst immediately") {
    val rateLimiter = new RateLimiter(10)
    for(_ <- 1 to 10) {
      assert(rateLimiter.tryAcquire())
    }
  }

  test("blocks when limit rate is reached") {
    val rateLimiter = new RateLimiter(10)
    for(_ <- 1 to 11) {
      rateLimiter.tryAcquire()
    }
    for(_ <- 1 to 20) {
      assert(!rateLimiter.tryAcquire())
    }
  }

  test("recovers after time has passed") {
    val rateLimiter = new RateLimiter(10)
    for(_ <- 1 to 11) {
      rateLimiter.tryAcquire()
    }
    assert(!rateLimiter.tryAcquire())
    sleep(1 second)
    assert(rateLimiter.tryAcquire())
  }
}

