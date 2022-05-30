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

package fr.acinq.eclair.router.graph

import fr.acinq.bitcoin.scalacompat.{Btc, MilliBtc}
import fr.acinq.eclair.{MilliSatoshi, ToMilliSatoshiConversion}


object RoutingHeuristics {

  // Number of blocks in one year
  val BLOCK_TIME_ONE_YEAR: Int = 365 * 24 * 6

  // Low/High bound for channel capacity
  val CAPACITY_CHANNEL_LOW: MilliSatoshi = MilliBtc(1).toMilliSatoshi
  val CAPACITY_CHANNEL_HIGH: MilliSatoshi = Btc(1).toMilliSatoshi

  // Low/High bound for CLTV channel value
  val CLTV_LOW = 9
  val CLTV_HIGH = 2016

  /**
   * Normalize the given value between (0, 1). If the @param value is outside the min/max window we flatten it to something very close to the
   * extremes but always bigger than zero so it's guaranteed to never return zero
   */
  def normalize(value: Double, min: Double, max: Double): Double = {
    val extent = max - min
    val clampedValue = Math.min(Math.max(min, value), max)
    val normalized = (clampedValue - min) / extent
    Math.min(Math.max(0.00001D, normalized), 0.99999D)
  }

}