/*
 * Copyright 2026 ACINQ SAS
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

package fr.acinq.eclair.profit

import kamon.Kamon
import kamon.metric.Metric

object Monitoring {

  object Metrics {
    val DailyProfit: Metric.Gauge = Kamon.gauge("routing.profit.daily", "Rolling daily routing profit (mBTC)")
    val WeeklyProfit: Metric.Gauge = Kamon.gauge("routing.profit.weekly", "Rolling weekly routing profit (mBTC)")
    val DailyVolume: Metric.Gauge = Kamon.gauge("routing.volume.daily", "Rolling daily payment volume (mBTC)")
    val WeeklyVolume: Metric.Gauge = Kamon.gauge("routing.volume.weekly", "Rolling weekly payment volume (mBTC)")
  }

}
