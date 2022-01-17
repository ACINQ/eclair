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

import scala.concurrent.duration.FiniteDuration

class RateLimiter(n: Int, per: FiniteDuration) {
  val perMs: Long = per.toMillis

  var i = 0
  val accepted: Array[Long] = Array.fill(n)(0)

  def tryAcquire(): Boolean = {
    val now = System.currentTimeMillis()
    if (now - accepted(i) > perMs) {
      accepted(i) = now
      i = (i + 1) % n
      true
    } else {
      false
    }
  }
}
