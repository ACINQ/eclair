/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.router

import fr.acinq.eclair.{LongToBtcAmount, MilliSatoshi}
import kamon.Kamon

object Monitoring {

  object Metrics {
    val FindRouteDuration = Kamon.timer("router.find-route.duration", "Path-finding duration")
    val RouteLength = Kamon.histogram("router.find-route.length", "Path-finding result length")
  }

  object Tags {
    val NumberOfRoutes = "numRoutes"
    val Amount = "amount"

    /**
     * We split amounts in buckets that can be used to tag metrics.
     * The goal is to detect if some amount buckets end up killing the performance of the path-finding.
     */
    def amountBucket(amount: MilliSatoshi): String = {
      val tiny = "0 sat < 1000 sat"
      val small = "1000 sat < 1 mBTC"
      val medium = "1 mBTC < 100 mBTC"
      val big = "100 mBTC < 1 BTC"
      val reckless = "1 BTC < ???"
      amount match {
        case amount if amount < 1000.sat => tiny
        case amount if amount < 1.mbtc => small
        case amount if amount < 100.mbtc => medium
        case amount if amount < 1.btc => big
        case _ => reckless
      }
    }
  }

}
