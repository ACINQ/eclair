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

package fr.acinq.eclair.blockchain.watchdogs

import kamon.Kamon

/**
 * Created by t-bast on 29/09/2020.
 */

object Monitoring {

  object Metrics {
    val BitcoinBlocksSkew = Kamon.gauge("bitcoin.watchdog.blocks.skew", "Number of blocks we're missing compared to other blockchain sources")
    val WatchdogError = Kamon.counter("bitcoin.watchdog.error", "Number of watchdog errors")
  }

  object Tags {
    val Source = "source"
  }

}
