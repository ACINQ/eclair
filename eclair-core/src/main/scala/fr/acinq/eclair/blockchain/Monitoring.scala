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

package fr.acinq.eclair.blockchain

import kamon.Kamon
import kamon.metric.Metric

object Monitoring {

  object Metrics {
    val NewBlockCheckConfirmedDuration: Metric.Timer = Kamon.timer("bitcoin.watcher.newblock.checkconfirmed")
    val RpcBasicInvokeCount: Metric.Counter = Kamon.counter("bitcoin.rpc.basic.invoke.count")
    val RpcBasicInvokeDuration: Metric.Timer = Kamon.timer("bitcoin.rpc.basic.invoke.duration")
    val RpcBatchInvokeDuration: Metric.Timer = Kamon.timer("bitcoin.rpc.batch.invoke.duration")
    val FeeratesPerByte: Metric.Gauge = Kamon.gauge("bitcoin.feerates-per-byte", "Current feerates in sat/byte")
    val CannotRetrieveFeeratesCount: Metric.Counter = Kamon.counter("bitcoin.rpc.feerates.error", "Number of failures to retrieve on-chain feerates")
  }

  object Tags {
    val Method = "method"
    val Wallet = "wallet"
    val Priority = "priority"

    object Priorities {
      val Minimum = "0-minimum"
      val Slow = "1-slow"
      val Medium = "2-medium"
      val Fast = "3-fast"
      val Fastest = "4-fastest"
    }
  }

}
