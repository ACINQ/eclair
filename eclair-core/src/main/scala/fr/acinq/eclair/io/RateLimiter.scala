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

/**
 * @param qps maximum number or messages allowed per second
 * This class is not thread-safe, the rate limiter must not be shared between actors.
 */
class RateLimiter(qps: Int) {
  var i = 0
  val accepted: Array[Long] = Array.fill(qps)(0)

  /**
  * [[tryAcquire()]] is guaranteed to return `true` at most `qps` times per second.
  */
  def tryAcquire(): Boolean = {
    val now = System.currentTimeMillis()
    if (now - accepted(i) > 1000) {
      accepted(i) = now
      i = (i + 1) % qps
      true
    } else {
      false
    }
  }
}
